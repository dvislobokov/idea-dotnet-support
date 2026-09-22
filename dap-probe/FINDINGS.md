# dotnet-debugger 0.1.0 — findings from a black-box DAP probe

This file is written to be handed to a coding agent working in the `dotnet-debugger` repository
(https://github.com/dvislobokov/dotnet-debugger). Every finding has a reproduction, the observed behaviour, the expected
behaviour and, where I could tell, a hint about the cause. Nothing here comes from reading the debugger's source: it is
all observed over the wire.

## Task for the agent

1. Work through the findings in priority order (P1 → P3). For each one: reproduce it with the scripts in this folder,
   find the cause in the source, fix it, and add a regression test to the repository's own test suite.
2. Do not "fix" a finding by special-casing the probe. The probes are examples of what an IDE client does.
3. Several findings share a cause (raw `HRESULT` texts, enum display, error messages that name the wrong thing): fix the
   cause once.
4. When a finding turns out to be intended behaviour, say so and document it in the README instead of changing the code.
5. Re-run `batch1.py`, `batch2.py`, `batch3.py` and the `verify_*.py` scripts at the end and compare with the "What works"
   section: none of it should regress.

## Environment of the probe

- Windows 11 x64, English OEM code page (437). .NET SDK 10.0.401 and 9.0.301; runtimes 10.0.12 and 9.0.6.
- `dotnet-debugger-dap` 0.1.0 from nuget.org, installed with `dotnet tool install --tool-path`, run as
  `dotnet-debugger --log=FILE`, DAP over stdio.
- Debuggee: `t/App` (console, net10.0, Debug unless said otherwise) referencing `t/Lib`. `Program.cs` dispatches on the
  first argument to one scenario; breakpoint lines are marked in the sources with `// BP:<name>` comments and the harness
  resolves them by name, so line numbers below are informative only.

### How to run

```
dotnet build t/App/App.csproj
python batch1.py [variables stepping async_code threads exceptions closures output]
python batch2.py [spans debugattrs debugger_break deep huge evil unicode_names patterns asynciter parallel deadlock
                  staticctor filters crashes dynamic_code multiline generics finalizer childproc refparams async_stepping]
python batch3.py [bp_features process_control attach launch_variants protocol_abuse paths_and_builds server_mode]
python verify_attach.py ; python verify_garbage.py ; python verify_misc.py
```

`dap.py` expects the adapter at `tools/dotnet-debugger[.exe]` next to it (`dotnet tool install dotnet-debugger-dap --tool-path tools`);
set the environment variable `DAP_ADAPTER` to test a local build. `out-windows/` and `out-linux/` hold the raw output of the
runs this report is based on (in `out-windows/b3b.txt` the `attach*` sessions show timeouts: that was a bug of the harness,
fixed since; `verify_attach.py` has the correct results). Every
session writes `logs/<name>.transcript.jsonl` (all DAP messages both ways) and `logs/<name>.adapter.log`.

A second run of everything was done on Linux in Docker; see "Linux run" at the end — two of the worst problems exist only there.

Not covered at all: macOS, arm64, ASP.NET Core applications, attaching to a `dotnet test` host, .NET 8 / 9 debuggees,
single-file and ReadyToRun applications, symbol servers, `sourceFileMap`, the VS Code extension.

---

## P1 — breaks ordinary debugging

### 1. An unhandled exception never stops the debugger, and the exit code is reported as 0
- Repro: `batch1.py exceptions` (sessions `unhandled-*`, `asyncunhandled-*`), `batch2.py crashes` (`crash-threadcrash`).
  Scenarios `unhandled` (throw on the main thread), `asyncunhandled` (throw after an `await`), `threadcrash` (throw on a
  worker thread); exception filters `[]` and `["user-unhandled"]`.
- Actual: no `stopped` event at all; the process dies, stderr shows "Unhandled exception. ..."; then
  `exited {"exitCode": 0}` and `terminated`.
- Expected: a `stopped` event with `reason: "exception"` (break mode `unhandled`) at the throw site before the process is
  torn down, regardless of the filters — every .NET debugger does this, it is the main reason to run under a debugger.
  And `exitCode` must be the real one: without a debugger the same program exits with `0xE0434352` (3762504530);
  `verify_misc.py` prints it.
- Note: other abnormal exits report the right code (`stackoverflow` → -2147023895, `failfast` → -2146232797,
  `Environment.Exit(7)` from a thread → 7, killed from outside → 1), so only the unhandled-exception path is wrong.
- Hint: the ICorDebug `Exception` callback with `DEBUG_EXCEPTION_UNHANDLED` is probably ignored unless a filter matches.

### 2. Program output that is not ASCII is corrupted
- Repro: `batch1.py output` (scenario `output` prints `Кириллица и emoji 🙂 €`), `launch-env` (argument `ünï` echoed back).
- Actual: `output` events contain `????????? ? emoji ?? ?`; the echoed argument arrives as `�n�`.
- Expected: the text as the program wrote it.
- Cause, as far as it can be seen from outside: the debuggee's redirected stdout uses the OEM code page of its (hidden)
  console — cp437 here, cp866 on a Russian Windows — while the adapter decodes the bytes as UTF-8. On cp437 Cyrillic is
  already lost inside the debuggee (`?`), on cp866 it would arrive as mojibake.
- Fix direction: give the debuggee a console whose output code page is 65001 before it starts (what vsdbg does), or at
  least decode with the console's code page. `DOTNET_` environment switches do not change `Console.OutputEncoding`.

### 3. `stepIn` on a call of an `async` method does nothing
- Repro: `verify_misc.py`, session `v-async-stepin`: breakpoint `async-second` (`var second = await Compute(first);`),
  then `stepIn`.
- Actual: `stopped(step)` at the very same location, `AsyncCode.Run() Rest.cs:8:9`; the stack is unchanged. A second
  `stepIn` behaves the same; only `next` leaves the line.
- Expected: stop at the first statement of `Compute`.
- Related, P2: stepping with `next` past the end of an async method (`v-async-step-past-end`: `next` on the closing
  brace of `Compute`) lets the program run to completion instead of stopping in the awaiting caller after its `await`.
  `stepOut` from the same method does work (it lands in `Run` at the `await`), so the machinery exists.

### 4. Strings longer than 4096 characters cannot be displayed
- Repro: `batch1.py variables` (local `longText = new string('a', 5000)`), `batch2.py huge` (`hugeString`, 10 M chars).
- Actual: the variable's value is
  `<error: startIndex ('0') must be less than or equal to '-904'. (Parameter 'startIndex') Actual value was 0.>` with
  `type` missing; `-904` is `4096 - 5000`, for the 10 M string it is `-9995904`. Same for `evaluate` of the variable in
  every context, including `clipboard`.
- Expected: a truncated value (with an ellipsis) in `variables` / `watch` / `hover`, the full value in `clipboard`
  context, and the type `string`.
- Oddity that shows the two code paths: `evaluate "new string('q', 5000)"` returns all 5002 characters, untruncated.
  That path needs a limit too (a 10 M result would be shipped whole).

### 5. A second debugger attaching to an already debugged process kills that process
- Repro: `verify_attach.py`, sessions `v-attach-first` / `v-attach-second`: two adapters, both `attach` to the same pid.
- Actual: the second `attach` answers `success: true`; the target dies immediately (`exited {"exitCode": -1}` in both
  sessions, OS exit code 4294967295).
- Expected: the second attach fails with a message like "process 1234 is already being debugged", and the target keeps
  running under the first debugger.

### 6. One slow request blocks the adapter completely, `disconnect` included (on Linux it is fatal: see L1)
- Repro: `batch2.py huge`: `variables` for `bytes` (`byte[5_000_000]`) or `million` (`List<int>` with 1 M items) without
  `start` / `count`.
- Actual: the 1 M list takes 72 s and returns 1 000 001 items; the 5 M array did not answer in 120 s. While such a
  request runs, nothing else is answered — the next `variables` timed out after 60 s, and the adapter was still alive
  5 s after `disconnect` and had to be killed. `cancel` is advertised (`supportsCancelRequest`) and always answers
  `success: true`, but has no visible effect.
- Expected: a cap on unpaged results (the client was told `indexedVariables`, so paging is its job — refuse or truncate
  beyond, say, 10 000 children), real cancellation, and `disconnect` / `terminate` / `pause` handled out of band.
- Related: paging into a `Dictionary` is O(start): `start: 0, count: 100` takes 0.2 s, but `start: 999900` (beyond the
  end of a 100 000-entry dictionary) took 38 s before returning an empty list. Validate `start` against the count first,
  and avoid one func-eval per skipped element.

### 7. Malformed input terminates the adapter
- Repro: `verify_garbage.py`. After `initialize`, send one bad frame, then a normal `threads` request.
- Actual: the adapter stops answering (its stdout closes; exit code 1 shortly after) for: a body that is not JSON, invalid
  JSON, a JSON array instead of an object, `"seq": "7"` (string instead of number). A `Content-Length` far larger than
  the data that follows hangs it forever, which is fair, but there is no timeout or size limit.
- Survives correctly: a header block without `Content-Length`, a request without `command`, a stray `response`, an
  unknown `type`, `arguments` of the wrong JSON type, negative `Content-Length`, lower-case header name, LF-only line ends.
- Expected: an error response where a `seq` can be recovered, otherwise skip the frame; never lose the session (the
  debuggee dies with it).

---

## P2 — wrong or misleading, with workarounds

### 8. After an evaluation that cannot be aborted the thread is left hijacked, and everything afterwards fails
- Repro: `batch2.py evil`: `evaluate "evil.Deadlocks"` (a getter that takes a lock held forever by another thread).
- Actual: after 8 s: "Evaluation timed out. The evaluated code could not be interrupted and is still running on its
  thread." — a good message. But then every evaluation, even `evil.Fine`, fails with
  `Evaluation not possible: Error HRESULT CORDBG_E_FUNC_EVAL_BAD_START_POINT has been returned from a call to a COM component.`,
  and after `continue` the program never reaches the next breakpoint and never exits: the main thread is still inside
  the abandoned evaluation.
- Expected: try `ICorDebugEval2::RudeAbort` after `Abort` fails; if the thread cannot be recovered, say so once in plain
  words (an `output` event with category `important`) and answer later evaluations with that same sentence, not with an
  HRESULT. An interruptible hang (`evil.Hangs`, a `while(true)`) is handled well: 5.5 s timeout, later evaluations work.

### 9. Listing variables calls `ToString()` on user types with a 5-second timeout, every time
- Repro: `verify_misc.py`, session `v-tostring`: the frame has a local whose `ToString()` never returns.
- Actual: `variables` for the locals takes 5.6 s, then shows `{DebugTarget.HangingToString}`. It costs 5.6 s again on
  every stop and every refresh. Expanding an object with a hanging property costs another 5.5 s (the remaining properties
  are then shown as `<not evaluated>`, which is a good design).
- Expected: a much shorter timeout for implicit evaluations (1 s is usual), remember for the rest of the stop that
  implicit evaluation timed out and skip it, and a launch option to turn implicit property / `ToString()` evaluation off
  (other .NET debuggers call it `allowImplicitFuncEval`). Related observation: getters with side effects run on every
  expansion (`derived.SideEffect` increments a static counter each time the object is expanded).

### 10. Enum values are shown as the type name
- Repro: `batch1.py variables`: `DayOfWeek day = DayOfWeek.Friday`.
- Actual: value `{System.DayOfWeek}` with children, in locals, in `evaluate`, inside other objects (`date.DayOfWeek`,
  `date.Kind`) and inside `DebuggerDisplay` strings (`Task`: `Status = {System.Threading.Tasks.TaskStatus}`).
- Expected: `Friday`. A `[Flags]` enum declared in the debuggee *is* shown correctly (`Read | Write`), and
  `setVariable perm := Perm.Exec` answers `Exec`, so only some enums take the wrong path — apparently the ones from
  assemblies without symbols.

### 11. `DebuggerDisplay` with escaped braces breaks (anonymous types)
- Repro: `batch1.py variables`: `var anon = new { A = 1, B = "two" }`.
- Actual: `\<error: Invalid expression: Unexpected token '{'>, B = "two" }`.
- Expected: `{ A = 1, B = "two" }`. The compiler-generated attribute is `\{ A = {A}, B = {B} }`: `\{` and `\}` are
  literal braces.

### 12. `stopAtEntry` stops in external code
- Repro: `batch3.py launch_variants`, session `launch-stop-at-entry`.
- Actual: `stopped {"reason": "entry"}` with the top frame `[External Code]`, line 0, no source.
- Expected: the first sequence point of the entry method (`Program.<Main>$` / `Main`), so that an IDE has a line to show.

### 13. With `justMyCode: false`, stepping into code without symbols stops there
- Repro: `batch1.py stepping`, session `stepping-nojmc` (`justMyCode: false`, `enableStepFiltering: false`), `stepIn`
  on a line that calls LINQ.
- Actual: `stopped(step)` in `System.RuntimeFieldHandle.GetLoaderAllocator()` with no source, line 0; the next `stepIn`
  stays there.
- Expected: a method without symbols cannot be stepped through; step over it (or step out at once) and stop at the next
  location that has source.

### 14. Attaching to a process that is not .NET reports success
- Repro: `verify_attach.py`, `v-attach-native` (attach to the python process).
- Actual: `success: true`, and then silence.
- Expected: a failure such as "process 1234 does not host the .NET runtime".

### 15. A first-chance exception thrown in external code: the top frame is external and `$exception` cannot be evaluated
- Repro: `batch1.py exceptions`, session `exceptions-all`: `int.Parse("not a number")` with filter `all`, `justMyCode: true`.
- Actual: the stop location is `[External Code]` (line 0); `evaluate "$exception"` with that frame id fails with
  "No information is available for external code." `exceptionInfo` works.
- Expected: `$exception` belongs to the thread, not to the frame, and should evaluate in any frame. Consider reporting
  the first user frame as the stop location under Just My Code, as other debuggers do.

### 16. `--server`: after the client disconnects the process stays alive but does not listen any more
- Repro: `batch3.py server_mode` (`--server=4711`): connect, `initialize`, close the socket.
- Actual: a second client is refused while the first is connected (fine); after the first one leaves the adapter keeps
  running, port 4711 is closed, new connections are refused.
- Expected: either exit when the client leaves or go back to accepting.

### 17. Conditions and hit conditions that make no sense are accepted silently
- Repro: `batch3.py bp_features`.
- `hitCondition: "abc"` → `verified: true`, no message, and the breakpoint then stops on every hit.
- `condition: "i + 1"` (not a `bool`) → `verified: true`, never stops, no message anywhere.
- Lines `0` and `-5` → bound to line 6 of the file and `verified: true`.
- Expected: `verified: false` with a `message` for the first two (or an `output` event on the first hit, the way failing
  conditions are already reported — that part is good: `condition: "missing > 1"` stops and prints
  "Breakpoint condition 'missing > 1' failed: The name 'missing' does not exist in the current context."); reject
  non-positive lines.

---

## P3 — gaps in the expression evaluator and cosmetics

### 18. Expression evaluator: unsupported constructs (each with a repro in `batch1.py variables` unless noted)
| Expression | Actual | Expected |
|---|---|---|
| `derived.Greet("bob")` where the method has `int times = 1` | "No method or extension method 'Greet' takes 1 argument(s)" | optional parameters are filled in |
| `perm.HasFlag(Perm.Write)` | same message | an enum argument is boxed to `System.Enum` |
| `twice(4)` where `twice` is a `Func<int,int>` local | "The name 'twice' does not exist in the current context." | delegate invocation, or at least a truthful message |
| `tuple.Name` for `(Id: 1, Name: "t")` | "'Name' is not a member of the value." | tuple element names from the PDB (`Item2` works) |
| `maybe = null` / `setVariable maybe := null` for `int?` | "Cannot assign null to a value type." | assigns `null` to a `Nullable<T>` |
| `setVariable point := new Point(9, 9)` (a struct) | "The value cannot be converted to the type of the variable." | assigns the struct |
| `list.Select(x => x + "!").First()` | "Expressions of kind 'SimpleLambdaExpression' are not supported." | known limitation; worth a README line |
| `input is int n && n > 3`, `x with { ... }`, `checked(...)` | "Expressions of kind '...' are not supported." | low priority |
| `*pointer` for `int*` (`batch2.py spans`) | "The operation is only supported for primitive values and strings." | dereference |
| `typeof(T)`, `T.Title` inside a generic method (`batch2.py generics`) | "Type 'T' not found." | the type argument of the current instantiation |
| `expando.Name` on an `ExpandoObject` (`batch2.py dynamic_code`) | "'Name' is not a member of the value." | dynamic members (a "Dynamic View") |
| `numbers,5` | "Unexpected token ','" | the element-count format specifier; `,nq` and `,h` work |

### 19. Error texts leak `HRESULT`s or blame the wrong name
- `attach` to a pid that does not exist: "Error HRESULT ERROR_INVALID_PARAMETER has been returned from a call to a COM component."
- `stackTrace` with an unknown or missing `threadId`: the same text.
- `$exception` when no exception is in flight: "DebugException: Error HRESULT S_FALSE has been returned ...".
- `evaluate "e.InnerException.Message"` when `e` is not in scope: "The name 'e.InnerException.Message' does not exist"
  — it should name `e`. Likewise `order.Hidden`.
- `evaluate "input is int"` when `input` is not in scope: "Type tests are only supported for objects of the debuggee."
- `setFunctionBreakpoints` with `DebugTarget.Output.Sleeper(int)`: the message quotes `'DebugTarget.Output.Sleeper(int'`
  (closing parenthesis lost), and the signature form is not understood at all.

### 20. Smaller things
- Generic type names lose the backtick instead of being rendered: `System.Linq.Expressions.Expression1<System.Func<int, int>>`
  (should be `Expression<Func<int, int>>`).
- `Nullable<int>` is typed `System.Nullable<int>`; `int?` is what the user wrote.
- Function breakpoints resolve only in modules with symbols: `System.Console.WriteLine`, `System.Threading.Thread.Sleep`
  stay unverified even with `justMyCode: false`.
- `launch` is accepted before `initialize`.
- `launch` of a native executable (`whoami.exe`) "succeeds": it runs, prints, exits; nothing says it is not a .NET program.
- `source` request with `sourceReference: 0` and a `path`: "Unknown source reference 0." (per the protocol the path applies).
- `terminate`: the debuggee is killed and `exited {"exitCode": 0}` is reported; a killed process should not look successful.
- Async iterators have no `[Async Call Stack]` section (plain `async` methods awaited directly do have one).
- `stepOut` while the frame being left throws and catches internally (`v-stepout-exception`): the step ends in that
  frame's `catch` block instead of in the caller. Defensible, but worth a conscious decision.
- `Console.ReadLine()` under `console: internalConsole` blocks forever: there is no way to type. Returning end-of-file,
  or a README note that input needs `integratedTerminal`, would save users a puzzle.
- A breakpoint on a line without code slides to the next sequence point anywhere in the file, even into another
  method (line 1, `namespace ...;`, binds to line 6).

---

## What works (keep it working)

- Capabilities are honest for everything tested: conditional / hit-count breakpoints, logpoints (with `{{` escapes and
  inline error text), function breakpoints in user code, exception filters with type conditions
  (`DebugTarget.ShopException, System.Format*`), `setVariable`, `setExpression`, `gotoTargets` + `goto`, `restart`,
  `terminate`, `modules`, `loadedSources`, value format specifiers `,nq` and `,h`.
- Breakpoints: pending → verified through `breakpoint` events when the module loads; added and removed while running;
  column breakpoints on a line with two lambdas stop in the right lambda; in a class library, in lambdas, local
  functions, generic methods (each instantiation), nested generic types, static constructors, finalizers, exception
  filters, methods invoked through reflection, iterators and async iterators; 8 threads hitting one breakpoint in
  `Parallel.For` are reported one by one; path forms (forward slashes, lower-case drive letter, all upper case) all bind;
  a path with spaces, Cyrillic, `#`, `&` and parentheses works; a source file edited after the build gets the message
  "The source file differs from the one the module was built with".
- Stepping in synchronous code, into another assembly, over properties (step filtering), through
  `[DebuggerHidden]` / `[DebuggerStepThrough]` / `[DebuggerNonUserCode]`; `next` across `await` follows the continuation
  to another thread; `stepOut` of an async method.
- Stacks: `[External Code]` folding, `[Async Call Stack]`, named threads, 3003 frames with `startFrame` / `levels`
  paging in about a millisecond.
- Variables: all primitive types, escapes and Cyrillic inside strings, nullable, `[Flags]` enums, structs, tuples,
  records, arrays (multi-dimensional and jagged, indexed paging on a 5 M array in ~10 ms), `List` / `Dictionary` /
  `HashSet` with `Raw View`, `Span<T>` / `ReadOnlySpan<char>` / `Memory<T>`, pointers, `nint`, ref / out / in
  parameters, captured variables flattened into lambda and local-function frames, identifiers in Cyrillic, Japanese and
  `@class`, `DebuggerDisplay`, `DebuggerBrowsable(Never | RootHidden)`, `DebuggerTypeProxy`, properties that throw
  (shown as `<System.InvalidOperationException: getter failed>`). Evaluating through a self-referencing object
  (`cycle.Next.Next.Next.Name`) works; *expanding* it was not checked, the adapter was busy with finding 6 at that moment.
- Evaluation: arithmetic, comparisons, `?.`, `??`, `?:`, casts, `is` / `as`, indexers on arrays, lists and
  dictionaries, instance and static method calls, extension methods (`numbers.Sum()`, `lazy.Count()`), `new`, string
  interpolation, `typeof`, `nameof`, `default`, `sizeof`, assignment, `$exception`, exceptions thrown by the expression
  reported with type and message.
- Exceptions: `all` filter with accurate `exceptionInfo` (type, message, stack trace, inner exceptions), rethrow and
  wrapping, `TypeInitializationException`, `AggregateException`, `Debugger.Break()`.
- Process control: `pause` / `continue`, `restart`, `disconnect` with `terminateDebuggee: false` leaves a launched
  process running, attach + detach leaves the target running, a debuggee killed from outside produces `exited` +
  `terminated`, no orphan is left when the adapter itself is killed, `runInTerminal` is requested when the client
  supports it and silently skipped when it does not, `project` + `build` launch, the apphost `.exe` as `program`,
  `env` with `null` removing a variable, arguments with spaces and quotes.
- Wrong-state requests are answered cleanly ("The debuggee is running.", "Unknown variables reference 4.",
  "Unknown frame 987654."), stale references die with `continue`, ten `next` requests fired at once yield one step and
  nine clean errors.

---

## Linux run (Docker) — what differs from Windows

Same scripts, same debuggee, `mcr.microsoft.com/dotnet/sdk:10.0` (x64) with `--cap-add=SYS_PTRACE --security-opt seccomp=unconfined`:

```
docker build -t dap-probe .            # --build-arg DEBUGGER_VERSION=x.y.z for another release
docker run --rm --cap-add=SYS_PTRACE --security-opt seccomp=unconfined -v "$PWD/out-linux:/probe/out" dap-probe
python compare.py out-windows -- out-linux     # session-by-session diff, ids / paths / timings normalized
```

44 of 84 sessions are identical after normalization; most of the rest differ only in exit-code conventions, thread
timing and path syntax. **Findings 3, 4, 7, 9–13, 15–20 reproduce on Linux unchanged.** The real differences:

### L1 (P1, Linux only). An unpaged `variables` request makes the adapter eat 15 GB and get OOM-killed
- Repro: `batch2.py huge`, `variables` for `million` (`List<int>`, 1 M items) without `start` / `count`.
- Actual: no answer; `dmesg`: `Out of memory: Killed process (dotnet-debugger) total-vm:54480000kB, anon-rss:15214200kB`.
  The adapter stays as `<defunct>`, the debuggee is orphaned, every later request of the client times out.
- On Windows the same request is answered in 72 s (memory was not measured there). 15 GB for a million `int`s is about
  15 KB per element: something is retained per child — a handle, a func-eval, a variable reference that is never
  released. This is the strongest argument for finding 6: cap unpaged results, and find the per-element leak.

### L2 (P1, Linux only). Expanding an object that has a property with an endless loop loses the whole session
- Repro: `batch2.py evil`: `variables` on `evil` (class `EvilProperties`; the getter `Hangs` is `while (true) { }`).
- Actual, after 15 s: `Hangs = <error: The debuggee cannot be stopped: a thread is running a loop the runtime cannot
  interrupt (a limitation of .NET 8 on Linux and macOS). The session can only be terminated.>`. From then on
  `evaluate` and `stackTrace` answer "The debuggee is running.", `threads` and `pause` fail with the same long message,
  no `stopped` event ever comes again.
- But an explicit `evaluate "evil.Hangs"` in the same place **works on Linux**: "Evaluation timed out." after 5.5 s and
  the next evaluation succeeds. So the runtime *can* be recovered here; the implicit-evaluation path used by
  `variables` does something different from the explicit one (different timeout? no abort?). Make both use the path
  that recovers.
- The message says ".NET 8"; the debuggee ran on .NET 10.0.
- On Windows the same expansion costs 5.5 s and everything keeps working (finding 9).

### L3 (P2, Linux only). `attach` to a process id that does not exist reports success
- Repro: `verify_attach.py`, `v-attach-nonexistent` (pid 999999) → `success: true`. On Windows this fails (with a raw
  HRESULT, finding 19). Attaching to a non-.NET process "succeeds" on both platforms (finding 14).

### L4 (P2, Linux only). When the adapter dies, the debuggee is left running
- Repro: `batch3.py process_control`, session `adapter-killed` (the adapter gets SIGKILL): the debuggee is still alive
  afterwards; on Windows it is gone. L1 produces the same orphan. A launched debuggee should not outlive its debugger:
  `PR_SET_PDEATHSIG` in the launcher script, or a watchdog.

### L5 (P3, Linux only). A working directory that does not exist is ignored
- Repro: `batch3.py launch_variants`, `launch-bad-cwd`: `launch` succeeds and the program runs (in some other
  directory). Windows fails the launch with the message of the OS.

### Better on Linux than on Windows
- Finding 1, second half: the exit code after an unhandled exception is right on Linux (134). The missing `stopped`
  event is the same on both.
- Finding 2 does not exist on Linux: Cyrillic, emoji and non-ASCII arguments come through intact.
- Finding 5: the second debugger gets `success: false` ("Error HRESULT 0x800700B7 ..." — still a raw HRESULT) and the
  target keeps running under the first one.
- `Console.ReadLine()` under `internalConsole` returns `null` at once on Linux instead of blocking forever (finding 20).
- `terminate` and an external kill report 137; Windows reports 0 for `terminate` (finding 20).

### Expected platform differences (not findings)
- An upper-cased path does not bind a breakpoint on Linux: the file system is case sensitive.
- Abnormal exits are 134 (SIGABRT) where Windows has `0xE0434352`, `0xC00000FD` and the FailFast code.
- Which thread reaches a shared breakpoint first, and how many threads exist at that moment, varies from run to run.
