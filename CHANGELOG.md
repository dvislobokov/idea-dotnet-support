# Changelog

Every feature is a new version `0.1.x`. The build puts these sections into the change notes of the plugin
(Settings | Plugins → What's New) and fails when there is no section for the current `pluginVersion`.

### Unreleased — quick wins of the grey text (fold into the next version)

- No grey text inside string and character literals or comments: the provider looks at the PSI leaf at the caret (either tree:
  string, verbatim, raw and interpolated-string text tokens, `//`, `///`, `/* */`) before it asks the network, or at the host lexer's
  token while the document is not yet committed; right after the closing quote or `*/` and in a hole of an interpolated string it is
  code again, `// ⟨caret⟩` on an empty comment stays suppressed. Setting "Suggest inside strings and comments" (off) turns the gate
  off; the code-only confidence gate (`codeConfidence`) is unchanged — this one is in addition to it
- The thread of the network runs at a low priority (`MIN_PRIORITY + 1`) while it loads, warms up or prefills an opened file and at the
  normal priority while a grey-text request is queued or running (`NnThread`: raised by the request, lowered when none waits; no
  extra threads)
- A suggestion that is exactly what already follows the caret on the line, or that would make the line a copy of the previous one
  (the model repeating the line above: `a.Name = b.Name;` twice) is dropped (`CSharpNnInline.repeatsPreviousLine`); the engine's own
  guard sees only repetitions inside the generated text
- Tab with the completion list and the grey text both shown is arbitrated by the platform (checked on 2026.1.4: `InlineCompletionActionsPromoter`
  puts `InsertInlineCompletionAction` first while the grey text is shown and `InlineCompletionHandler.insert()` hides the lookup), so
  one Tab inserts the grey text only, never the list's item on top of it; no change needed

## 0.1.133

- The " ML" mark on the ranked completion items is off by default (`CSharpMlSettings.showMarker`); Settings | .NET | ML completion turns it on.

## 0.1.132

- ML completion in a build with `-PmlEnabled=true` (`bash gradlew buildPlugin -PmlEnabled=true [-Pml.big=true]` → `idea-dotnet-support-<v>-ml.zip`;
  the plain build is unchanged): grey text to the end of the line in C# files from our own transformer `cs31m-e2-lr2e3` (31 M parameters,
  the engine of idea-ml-completion in `lib/ml-core.jar` with its native SIMD kernels, scalar fallback) while typing, while the completion
  popup is open or on an explicit call; Tab accepts. The confidence gate is 0.7 (14 % of positions, 94 % exact lines), 0.5 right after
  `.` / `?.` / `::` / `->`, 0.25 on a line just opened by Enter; a suggestion of closing brackets only is hidden (a setting shows it).
  Adopted from the Go plugin's live use (0.2.199–0.2.206): the provider is first and asks a host's own provider before the network, one
  KV-cache session per editor, the model copied once to the IDE system directory by SHA-256, the tail of a suggestion the rest of the
  line already has is dropped, the gate counts the code tokens only (the text inside a string literal is the guess, a setting), an
  uncertain line shows its longest certain start, every answer of the network can go to the plugin log (category `ml`)
- The completion list ordered by the ML ranker `e18-rank` trained on the plugin's own exported lists (MRR 0.71 against 0.53 of the
  rules): the native list is held back until every contributor has answered, each candidate gets the same features as the export
  (the n-gram model `e15-a` with the per-file cache, the 19 language features) through the same code — a parity test replays the
  export on its fixture and compares the vectors — and the weigher puts the score before the rule priority; the rows carry a grey
  `ML` mark (a setting); the plugin's own order stays while the models load and in a build without them
- Preload: the models load and warm up in the background when a project with C# files opens (after indexing; a project without `.cs`
  files pays nothing, the application start loads nothing), and every C# editor opened prefills the KV cache of its session with the
  file at the caret on the model's thread, so the first grey-text request there is incremental (~25 ms) instead of a cold prefill
- Settings | .NET | ML completion: the ranker and its mark, the grey text with its three gates, closers, string guessing, the big
  model `cs50m-e3-lr2e3` (when packed with `-Pml.big=true`), a directory with other models, the log of every answer, and what is
  loaded (models, kernels, shown / accepted counts)

## 0.1.131

- The headless export of completion lists for the ML ranker (`mlDataset`) is 7× faster per position (45 ms, was 334; 2 s on a
  solution of 500 types) and sees the libraries: the
  projects of a repository are restored (`dotnet restore`, with their MSBuild files fetched apart from the source corpus) and their
  assemblies indexed before the export, so the members of `string`, `List<T>` and NuGet types are in the lists after a dot (98 % of
  such positions get a list, was 61 %; recall 0.81, was 0.71); the type names of the solution are read from the stub index once per
  repository instead of once per position, and the debug log of the test framework, which formatted a time stamp on every index
  lookup, is off. A persistent system directory and one folder of assembly indexes for all workers (`-Pml.sandbox`, `-Pml.helpers`). Nothing
  changes in the editor: the per-file snapshot exists only while the export sets it

## 0.1.130

- Offline export of real completion lists for the ML ranker (Gradle task `mlDataset`, the plugin half of experiment e18 of the shared
  completion engine): the plugin's own completion runs headlessly over C# repositories, every sampled position records the list the
  IDE would show with the identifier of the source as the answer, and each candidate carries the features the ranker learns from — its
  kind, static or not, how far its declaration is (local, member, base, receiver, imported, not imported), whether a `using` is needed,
  whether it fits the expected type, the distance to its declaration and the plugin's own rule order. The same feature code will rank
  the list in the IDE once a model is trained on these exports. Nothing changes in the editor in this version

## 0.1.129

- The pass that gives every C# file of the project its `#if` symbols and language version (at startup, on another framework in the
  toolbar) no longer freezes the IDE: it checks for cancellation before every file, so a write action (a save, a VFS refresh) interrupts
  it instead of waiting for it with the UI, and it resumes where it was instead of starting over. Each file is normalized once and asks
  the project model once (was twice, and once per project of the solution). Seen live 2026-10-07: freezes of 7–19 s on a repository of
  31 projects and 530 files, every thread dump in this pass

## 0.1.128

- A `.cs`, `.csx`, `.sln`, `.slnx` or `.csproj` that opens as another file type (a user association made before the plugin was installed,
  typically Text, shadows the plugin's own and nothing of C# works in the file) gets a banner "This file opens as "Text", not as C#" with
  Associate with C# and File Types Settings…; a .NET project with such an association gets a modal dialog at startup and when such a
  file is opened, with the same fix ("Not Now" holds for the session, "Don't ask again" keeps the banner only)

## 0.1.127

- The gray text and the typing assistance of the native C# tree no longer log "Read access is allowed from inside read-action only"
  when the inline completion handler asks on the EDT (seen in the sandbox log): the tree is read under a read action
- A test that every test of a live `dotnet test` run starts under a suite that is still open, in every order the events come (the
  platform forgets a finished node and logs "Parent node is undefined" for a child that comes after it)

## 0.1.126

- `override` completion as in Rider: typing the start of the return type (`public override str`) keeps the members of that type
  (`string Describe()`, `string ToString()`), `public override string D` completes Describe over the typed type, and `struct`, `class`
  and other keywords no longer show after `override`
- From `public ov` the list offers `override string Describe` rows that write the whole member, and gray text shows the best member to
  override: Tab writes it with its `using` directives and puts the caret in the body

## 0.1.125

- The Project tool window reliably opens in the Solution view the first time a folder with a solution is opened: the switch used to
  race the creation of the tool window and was never retried when it lost. After the first switch the pane you choose is kept on reopen

## 0.1.124

- Code Vision: no "N usages" / "N implementations" / Run / Debug lenses over library sources opened via Source Link, decompiled types
  or the metadata view — only over the sources of the solution, as in Rider
- Shared frameworks a package requires (`Grpc.AspNetCore` → `Microsoft.AspNetCore.App`) are now part of what the project compiles
  against: their types resolve in completion, Go to Declaration and the compiler-error checks without a `FrameworkReference` or the Web
  SDK in the project file

## 0.1.123

- Source Link works: every download failed in 0.1.121 and 0.1.122, and the decompiled code opened instead
- Code Vision: every declaration of a line keeps its own lens entry — `int a, b;` reads "1 usage | no usages", a one-line enum shows
  the type and each member
- Code Vision: the counts of members below an edit in the file no longer drop to "no usages" until the file is reopened; the lenses of
  every open editor are recomputed when another file changes or indexing ends
- Find Usages: a constructor initializer `base(...)` / `this(...)` is a usage of the constructor only, not of the type, as Roslyn and
  Rider count it; "N usages" of a class agrees with Show Usages
- No "Read access is allowed from inside read-action only" error when clicking "N implementations" or the gutter of overrides with
  several targets

## 0.1.122

- The "Analysis" scopes of Settings | .NET | Language Server work without the language server: with "Compiler diagnostics for"
  fullSolution the Problems tool window (Project Errors) lists the errors and warnings of every file of the solution, checked in the
  background and updated as files change; openFiles lists the open files; none shows no compiler diagnostics at all, as Roslyn does
- "Analyzer diagnostics for" fullSolution analyzes whole projects on save and after a build and puts the analyzer warnings into the same
  tab; none hides them in the editor (Run Code Analysis still lists them)
- With the server on, only one provider fills the Project Errors tab: the server when "Errors and warnings" is Language server, the
  plugin when Built-in
- Every option of Settings | .NET | Language Server now works with the server off

## 0.1.121

- Go to Declaration on a type or member of a library opens its original source when the PDB of the assembly has Source Link or embedded
  sources (the option "Navigate to Source Link and embedded sources" of Settings | .NET | Language Server, now honoured without the
  server): the file is downloaded by the IDE's HTTP client, checked against the hash in the PDB, cached on disk and shown read-only with
  the banner "Navigated to source from Source Link: <url>"; without a PDB, a mapping or the network the decompiled code or the metadata
  view opens as before
- Source Link URLs are followed only over HTTPS to public hosts, without redirects and up to 16 MB: a PDB cannot make the IDE reach this
  machine or the local network

## 0.1.120

- Applying Settings | .NET | Language Server redraws the inlay hints and Code Vision of the open C# files at once: a toggled Inlay Hints
  or Code Lens option, or a switched source of "Inlay hints" / "Code Vision", no longer waits for the next edit
- Switching "Inlay hints" or "Code Vision" to the language server while it runs shows the server's hints and lenses immediately,
  without a restart of the server
- No false CS7036 on a named argument in its own position followed by positional ones (`Draw(width: 1, "t", shape: s)`, C# 7.2):
  the arguments are bound as Roslyn binds them

## 0.1.119

- Code Vision without the language server: "N usages" above every type and member, counted by the plugin's own Find Usages (a click
  opens Show Usages), "N implementations / overrides / inheritors" where there are any, and "Run | Debug" above test methods and test
  classes, running the same configurations as the gutter
- The Code Lens options "References" and "Run and debug tests" of Settings | .NET | Language Server govern the built-in lenses too; new
  switch "Code Vision" in Source of Features (Built-in by default); with Language server the server's lenses show instead, never both
- Usages outside the edited file are cached until another file changes, so typing in a large file recounts that file alone

## 0.1.118

- Every option of Settings | .NET | Language Server now applies to the built-in C# features as it does to the server: completion from
  unimported namespaces, name suggestions, regex completion, completion in argument lists, decompiled navigation, remarks in quick
  documentation, symbol search in reference assemblies, documentation comment auto-insert, regex / JSON highlighting in strings, the
  insertion location of generated members and throwing vs auto generated properties
- "Organize 'using' directives when formatting": Reformat Code with the built-in formatter removes unused directives and sorts the rest
  (`System` first unless `dotnet_sort_system_directives_first = false` in .editorconfig)
- Implemented interface and abstract properties throw `NotImplementedException` by default, as in Roslyn ("Generated properties: prefer
  throwing properties"); choose "prefer auto properties" for `{ get; set; }`

## 0.1.117

- Inlay hints without the language server: parameter names before arguments and the types of `var`, lambda parameters, `new()` and
  collection expressions, computed by the plugin's own semantics with Roslyn's rules; the Inlay Hints options of Settings | .NET |
  Language Server apply to them exactly as to the server's
- New switch "Inlay hints" in Source of Features (Built-in by default); with Language server the server's hints answer and the built-in
  ones stand back, never both
- A click on a type hint opens the type: a declaration of the solution or the metadata view of an assembly type

## 0.1.116

- Colored matching brackets in C# files: pairs of `()`, `[]`, `{}` and the `<>` of generic type lists are colored by nesting depth
  (three levels, VS Code's colors for light and dark schemes; Editor | Color Scheme | C# | Braces and operators | Matching brackets).
  Strings, characters, comments and inactive `#if` branches are left alone; interpolation holes count as code; an unmatched bracket stays
  plain and does not shift the rest of the file. Painted at once as a file opens
- Settings | .NET: "Colorize matching brackets" (on by default); switching it re-highlights the open editors

## 0.1.115

- Errors of statements without the language server: a value returned from a void method, an `async Task` or a lambda, or a missing one
  (CS0127, CS1997, CS0126, CS8030, CS8031), `return` in an iterator (CS1622), `await` outside an `async` method or lambda (CS4032, CS4033,
  CS4034, with Alt+Enter «Make method async» on it), `await` inside `lock` (CS1996)
- Switch and jump errors: duplicate `case` labels found by their constant values — enum members, constants, folded expressions (CS0152),
  falling through or out of a case (CS0163, CS8070), `break` / `continue` outside a loop (CS0139), `goto` to a missing label or case (CS0159),
  leaving a `finally` (CS0157)
- Error messages name types with their nullable annotations, as the compiler does

## 0.1.114

- Override errors: no member to override (CS0115), a base member that is not virtual or is sealed (CS0506, CS0239), a changed access
  modifier (CS0507, `protected internal` of libraries too) or return type (CS0508, CS1715) — against bases of the solution and of libraries
- Abstract and body errors (CS0513, CS0500, CS0501), deriving from a sealed or static type, a record or a class among interfaces (CS0509,
  CS0709, CS8864, CS8865, CS0527), `new` of an abstract type, interface or static class, target-typed `new()` too (CS0144, CS0712), a static
  member through an instance (CS0176), instance members in field initializers (CS0236)
- No more false «name does not exist» on a positional parameter passed to a record's base: `record Employee(string Name) : Person(Name)`

## 0.1.113

- Ambiguous calls (CS0121) and type arguments that cannot be inferred (CS0411), with generics, `params`, optional and `ref` parameters,
  extension methods and library types, named in the order the compiler uses
- Lambda errors: a wrong number of parameters (CS1593), a lambda where no delegate is expected (CS1660), mismatched parameter types or
  `ref` / `out` / `in` (CS1661, CS1678, CS1676, CS1677), a block body that does not return on every path (CS1643) — in overloaded and generic
  calls, `return`, property initializers and event `+=`
- Bad arguments (CS1503) are reported against the same overload `dotnet build` picks: derived types first, library methods in metadata order
- Errors on calls are no longer hidden by extension methods the file does not import (EF Core's `Like`, `Vector.Store`) or that cannot
  take the receiver (`Queryable.Take` next to `Enumerable.Take` on a `List<int>`)

## 0.1.112

- Operators and casts as `dotnet build` reports them: an operator that cannot take its operands (CS0019, CS0023 — `decimal * double`,
  `bool + int`, `!count`), an impossible cast (CS0030); silent wherever a user-defined operator or conversion may apply
- Statement forms and implicit typing: `count + 1;` (CS0201), a type or method used as a value (CS0119), a method group assigned to a value
  (CS0428), `var` with `null`, a void call, no initializer or an array initializer (CS0815, CS0818, CS0820), indexing what has no indexer
  (CS0021), `this` without an instance (CS0026, CS0027)
- Range indexing of spans (`span[1..]`) is typed as a span, not as its element

## 0.1.111

- Access errors across projects and libraries as `dotnet build` reports them: `InternalsVisibleTo` (MSBuild items and attributes, the
  friend's public key, `AssemblyName`), internal and private types and protected members (CS0122, overloads and constructors too)
- Read-only targets: properties and indexers without a reachable setter (CS0200, CS0272), getters (CS0154, CS0271), readonly and static
  readonly fields (CS0191, CS0198), init-only properties (CS8852), something that is no variable (CS0131), readonly fields, properties and
  indexers passed by `ref` / `out` (CS0192, CS0199, CS0206)
- The index of assemblies (format 4) keeps internal and private types, internal members and the accessibility of accessors; the indexes are
  rebuilt once

## 0.1.110

- `Use of unassigned local variable` (CS0165) and `Use of unassigned out parameter` (CS0269) with the compiler's flow rules: branches, loops,
  `try` / `finally`, `&&` / `||`, `out` arguments, `is` patterns and lambdas; an `out` parameter left unassigned on a `return` or at the end
  of the method (CS0177)

## 0.1.109

- Duplicate declarations: a local, local function or parameter declared twice (CS0128, CS0100), a local reusing a name of an enclosing scope
  (CS0136), LINQ range variables (CS1930, CS1931), a local used before its declaration (CS0841, CS0844)
- Duplicate members and types: the same name twice (CS0102), the same parameter types (CS0111), overloads differing only by `ref` / `out` /
  `in` (CS0663), conversions (CS0557), partial methods (CS0756, CS0757), interface members implemented twice (CS8646), partial parts with
  other type parameter names (CS0264), a type declared twice in a namespace — across files and folders, in the compile order of MSBuild (CS0101)

## 0.1.108

- Type arguments that break the constraints of a generic type or method (CS0311, CS0315, CS0452, CS0453, CS0310), written or inferred,
  for types and methods of the solution and of libraries; the wrong number of type arguments (CS0305, CS0308)
- Every compiler error of the plugin has a file in `debug-playground/Broken/Errors` with its lines marked `// ERROR CSxxxx`;
  `tools/diag/check_errors.py` compares the marks with `dotnet build`, the plugin in the IDE and roslyn-language-server

## 0.1.107

- No need to type `=`: after `Console.BackgroundColor ` (a settable member and a space) the list opens by itself with
  `= ConsoleColor.Black`… for an enum, or `= name`, `= dto.Name` for other values, and Enter writes the assignment with its `;`
- Generic extension methods respect the constraints of their `this` type parameter: `AddEndpointFilter` (`where TBuilder : IEndpointConventionBuilder`)
  is no longer offered after `day.`, and calling it on such a value is an error (CS1061)

## 0.1.106

- The completion list opens by itself after `= ` and `return ` where an enum is expected (`Console.BackgroundColor = `), as after `==` and `case`
- An enum member chosen at the end of a line closes the statement: `Console.BackgroundColor = ConsoleColor.Black;`, `Take(Status.Paid);`,
  `if (status == Status.Paid)` — also after `ConsoleColor.`; nothing is added when the line goes on or inside an initializer

## 0.1.105

- Ctrl+Click goes on inside decompiled code: the names of a decompiled type resolve against a project compiled against its assembly
  (else the project with the most references), so a type or call in it opens the next decompiled type

## 0.1.104

- Gray text no longer offers a variable name after a member of a type: `JsonSerializer.Serialize ` showed `serialize`, `Console.Out `
  showed `out`, as if they were nested types; a nested type (`Json.Options `) still gets its name

## 0.1.103

- Gray text no longer offers a variable name after a member of a value: `member.Ad` with `Admin` selected in the list showed `min admin`,
  as after a type; now it shows `min = isAdmin;` — the member and the value at hand for it
- `member.Email = ` (a statement) gets the value at hand as gray text with `;`, the properties of the values around included (`dto.Email;`);
  never the member itself
- The completion list at `Name = ` of an initializer and at `member.Name = ` has rows with the paths into the values at hand
  (`dto.Name`, `dto.Email`), best first
- No IDE error ("Read access is allowed from inside read-action only") from the gray text when the completion list closes
- The list puts the path rows (`dto.Name`) under the value the gray text gives (`name`), so both say the same; `Name =` without a space gets ` name;` with one

## 0.1.102

- Gray text while the completion list is open: it follows the selected row and shows what choosing it gives — `new ` with `Member`
  selected → `Member();`, `Draft(Mem` with `MemberDto` selected → `berDto memberDto`, the value of `Name = ` when the selected row begins
  it. Tab takes the row with the gray text and closes the list; Enter takes the row alone, as before
- `new Membe` lists `Member { … }` right under `Member` for a type created without arguments that has members to set: choosing it writes
  the object initializer, a member a line, the required ones first, each with its value when one is at hand (`Name = name`); Tab goes
  from value to value. Shown for the types whose name is typed (two letters at least), for the type the variable names
  (`var member = new `) and for the expected type
- `new OrderLine` of a type with required members fills their values from the variables at hand too, with the same Tab stops

## 0.1.101

- More gray text while typing, by rules (no machine learning), Tab takes it:
  - `var user = new ` → `User();`, the type named as the variable (`var users = new ` → `List<User>();`), when it is created with no
    arguments and has no required members
  - `new User(|)` → a gray `;` after the `)`; Tab writes `new User();`. Also after the `}` of an initializer written over several lines
  - on the empty line of `new User() { }`: the members a line each, the required ones first, with their values when they are at hand
    (`Name = userDto.Name,`)
  - `Name = ` in an object initializer → the variable, parameter or member of that name and a fitting type (`name`), or a property of
    one (`userDto.Name`); no `;` there any more
  - a name after a type: `UserDto ` → `userDto`, `List<User> ` → `users`, `IUserService ` → `userService`, `_userDto` for a private
    field, also in `foreach`
- After `class ` (`record`, `struct`, `interface`, `enum`) completion offers the name of the file; choosing it writes the body too
- `public clas` lists `class` once: the live template of that name is no longer next to the keyword

## 0.1.100

- Alt+Enter on a name that does not resolve offers Rider's "Create …" quick fixes: "Create class / record / struct / interface / enum 'Foo'"
  (a new file next to the current one, in its namespace; `new Foo(1, name)` gets a constructor with those parameters, `Foo.Bar` an enum
  with `Bar`, a name in the base list an interface), "Create field / property / local variable / parameter 'x'" typed from the usage, and
  "Create method 'M'" for a call (its parameters from the arguments, `void` / `Task` from the place); `x.Missing` creates the member in
  that type of the solution
- Implement missing members keeps the default values of parameters (`CancellationToken ct = default`, `= null`, `= 0`) and their
  attributes (`[CallerMemberName]`): calls without those arguments no longer fail with CS7036 at the build
- CS7036 ("There is no argument given that corresponds to the required parameter …") is shown before the build for a call of a class
  whose method lacks a default value its interface has, and for `new T(…)` of a type of the solution; CS1729 when no constructor takes
  that many arguments
- Reformat Code lays out code written on one line as Rider's default style does: `class A { void M() { x(); } }` gets its braces and
  statements on lines of their own; accessors, lambdas, enums and initializers stay on their line, and `csharp_preserve_single_line_blocks`
  in `.editorconfig` still keeps blocks as they are
- `;` typed inside the parentheses that end a statement goes after them (`new Repository(|)` + `;` gives `new Repository();`), and `)`
  after a string steps over the one the editor put
- Enter after an enum written on one line (`enum Status { New, Paid }`) keeps the indent of the declaration
- Postfix templates are not offered after a type name (`OrderStatus.` lists the members only); `.new` and `.typeof` stay for classes
- New → Class/Interface → Record puts the caret inside the `()` of the record
- In `appsettings*.json` a `,` typed right before the comma a completion has put steps over it instead of writing `,,`
- The welcome page no longer logs an IDE error ("JBCefApp$Holder <clinit> requests ProxyMigrationService") when it is the first embedded browser of the session.

## 0.1.99

- New Solution on SDK 10: the templates are no longer all under "Not supported for the selected Target Framework" and Create works again.
  The frameworks of a template are read from the choices of `--framework` as well as from its option line, and a help that cannot be read
  means "any framework", never "none"
- "Manage NuGet Packages..." of a project no longer fails with a NullPointerException when the NuGet window opens for the first time
- The NuGet window asks the password storage only about feeds that need credentials (stored by the plugin, or after a 401 / 403 of the
  feed), once per session, and never about nuget.org: on Linux without a keychain every search used to show "IDE error occurred"
- The notification of a failed build before a launch says the launch "was not started"
- "Move to Solution Folder..." on projects of the Solution view, and dragging projects onto a solution folder or the solution moves them
  there, as in Rider; dragging no longer logs "Access is allowed from EDT only"
- Add | New Project... is the window of New Solution: the same kinds, template names and options; options of a template show only when
  they apply (the Azure AD fields of Web API with an authentication that uses them)
- After Add | New Project... the new project is selected and expanded in the Solution view

## 0.1.98

- Object initializers and C# 11 `required` members, as in Rider: choosing `new OrderLine` from completion for a type with required members
  writes the initializer with them (`{ Sku = |, Title = }`, one member a line, the caret at the first value) instead of `()`; a type
  whose constructors all take arguments keeps `new T(|)`. The row shows the members: `OrderLine { Sku, Title }`
- CS9035 ("Required member 'OrderLine.Sku' must be set in the object initializer or attribute constructor") is shown without the language
  server, for the types of the solution and of the referenced assemblies; a constructor marked `[SetsRequiredMembers]` sets them all.
  Its quick fix "Add initializer for required members" writes the missing ones
- Inside `new T { | }` completion offers "Fill required members" and "Fill all members"; choosing a member writes `Name = `
- Alt+Enter in an object initializer: "Initialize members" and "Initialize required members"

## 0.1.97

- C# color palettes: Rider, Visual Studio, VS Code, Nord, Dracula, One Dark / One Light, Solarized and GitHub colors for C# on top of the
  color scheme you use — the background and every other language stay as they are, and the dark or light variant follows the background
  of the scheme (also when the theme is switched)
- Choose it in Settings | .NET ("C# color palette") or in .NET → C# Color Palette…, which previews each palette in the open editors while
  you move through the list (Esc puts the previous one back); "IDE default" returns the scheme's own C# colors
- The first C# file opened with a scheme that leaves C# uncolored offers the palettes once ("Don't Show Again" silences it)

## 0.1.96

- Double completion, as in Rider and IntelliJ: the second Ctrl+Space shows what the first one leaves out, and the first one says so in the
  line at the bottom of the list ("Press Ctrl+Space again to show…")
- After a dot, the second press adds the members the place does not see (private, protected and internal of other types, protected members
  of library types), grayed with "(not accessible)" at the bottom; choosing one writes it as it is, the compiler's error is then yours
- Where a type may stand, the second press adds the types of the packages (and the outputs of projects) that other projects of the solution
  reference and this one does not, as `Name (in Namespace, Package 1.2.3)`, from the first letter typed; choosing one adds the `using` and
  offers to add the package or the project reference in a balloon (`dotnet add package` / `dotnet add reference` run by its button only)
- The second Ctrl+Shift+Space adds the chains: `order.Customer`, `ledger.Count` — a member of a local, a parameter or a member of the
  enclosing type whose value is of the expected type, one access deep, with `()` for a parameterless method

## 0.1.95

- MSBuild files: `$(` completes property names (those of the file, of the files it imports and of `Directory.Build.props` / `.targets` / `Directory.Packages.props`
  above it, the ones MSBuild knows, the ones of the schema), `@(` item types, `%(` metadata (`%(Item.` of that item); the parenthesis is closed
- MSBuild files: file paths in `<Import Project="…">` (MSBuild files) and `<ProjectReference Include="…">` (project files), `..\` and `$(MSBuildThisFileDirectory)` included
- `[assembly: InternalsVisibleTo("` lists the projects of the solution; `extern alias ` the aliases of the references of the project; `delegate* unmanaged[` the calling conventions
- `#:package ` in a file-based app lists the package ids of the feeds (after `@` their versions); `#:` lists the directives
- New setting Settings | .NET → "Exclude from completion": types and namespaces (`System.Data.*`) the C# completion never offers, also not imported ones and their extension methods
- Live templates `hal`, `ua`, `rta`, `ctx` for ASP.NET Core controllers
- Format specifiers are listed for a format that starts with a digit (`$"{x:0`)

## 0.1.94

- Explicit interface implementation: after `void IFoo.`, `int IFoo.` or `IFoo.` at the start of a member the list offers the members of `IFoo`
  that are not implemented explicitly yet and writes the whole signature with a body; `void ` itself offers the names of the implemented
  interfaces, which write `IFoo.` and open the list
- `[]` after the dot of an array, a string, a list, a dictionary or a type with an indexer (shown as `this[int index]`): it turns `x.` into `x[|]`
- Names of tuple elements after the dot (`pair.Title`), and names for the variables of a deconstruction (`var (title, count) = pair;`,
  `foreach (var (a, b) in pairs)`) taken from the tuple, from `Deconstruct` or from the positional record
- `partial class |` (struct, record, interface) offers the partial types of the same namespace that have a part in another file

## 0.1.93

- Message templates of logging: in `logger.LogInformation("Order {OrderId}", …)` (every `Log…` method, `BeginScope`), Serilog's
  `Log.Information(…)` and `[LoggerMessage(Message = "…")]` the placeholders have the color of format items, as in Rider; after `{` the
  list offers names made of the arguments (`order.Id` → `OrderId`, `Id`; the argument of that placeholder first) or the parameters of the
  `[LoggerMessage]` method, and Ctrl+Space in the text offers `{Name}` for an argument no placeholder takes yet
- A warning when the number of arguments does not match the placeholders of a message template (as CA2017), on the placeholder without an
  argument or on the argument without a placeholder; in `[LoggerMessage]`, on a placeholder without a parameter of the method
- Route templates of `[Route]`, `[HttpGet]`…`[HttpOptions]` and of `MapGet` / `MapPost` / `MapGroup`…: braces, parameters and constraints
  colored; after `{` the parameters of the action or the handler (not services, `CancellationToken`, `[FromBody]`), after `:` the route
  constraints (`int`, `long`, `guid`, `alpha`, `minlength()`, `range()`, `regex()`…), after `[` `controller` / `action` / `area`
- JSON in strings after `// lang=json`, in arguments of `[StringSyntax(StringSyntaxAttribute.Json)]` parameters and of `JsonDocument.Parse`,
  `JsonSerializer.Deserialize`, `JObject.Parse`…: JSON colors, errors and Edit JSON Fragment (where the IDE has the JSON plugin)
- Configuration keys: `configuration["…"]`, `GetSection("…")`, `GetValue<T>("…")` list the keys of the project's `appsettings*.json`
  (nested ones as `Section:Key`, relative to a `GetSection` they are asked of), `GetConnectionString("…")` the connection strings
- `services.AddScoped<IService, ` (`AddTransient`, `AddSingleton`, `TryAdd…`, `AddKeyed…`) lists the implementations of the service from the
  solution first, with the `using` of their namespace
- The list opens by itself after `{` of a template, `:` of a route parameter, the quote of a configuration key and `AddScoped<IService, `

## 0.1.92

- A method chosen from the completion list with `.` or `;` gets its call: `Total().` (the members of the result open), `Save();`,
  `Register(|);` with the caret in the parentheses when it takes arguments; the only item inserted by itself gets `()` / `<>` as with Enter
- Extension methods from assemblies show their types with what the receiver gives: `ToImmutableArray()` of a `List<Order>` returns
  `ImmutableArray<Order>`, not `ImmutableArray<TSource>`; parameter info writes `Func<Order, bool> predicate`
- A lambda place is found by the parameter's type, so a delegate with any name (`delegate bool Rule(Order o)`) opens the list with the
  lambda and keeps the typed parameter name, not only `Func`, `Action` and `...Handler`
- In `catch (` only types deriving from `Exception` come first: an enum or a class with a short name (`Ex`) no longer sits among them

## 0.1.91

- The completion list remembers what you choose: an item chosen before goes up among the items of its kind, while the order of the kinds
  stays as in Rider (locals, members, types, keywords)
- Commit characters: `.`, `,`, `;`, space, `=`, `[`, `)` and `(` take the selected item of a list that opened by itself and are typed
  after it (`cou` + `;` → `counter;`); not with nothing typed, not for an item that matches only in the middle
- Suggestion mode where a new name is written (`foreach (var `, `out var `, a name after its type, a lambda's parameter): the list shows
  names without selecting one, so Enter and space keep what you typed
- The list opens by itself after `#` at the start of a line, `<` of type arguments, `(` / `,` where a lambda may go, `== `, `case `,
  `[` of an attribute, and after `new `, `using `, `override ` with the language server off
- Quick Documentation (Ctrl+Q) of an item of the completion list, and the documentation popup that follows the selection
- Keywords as Roslyn recommends them: `and` / `or` after a pattern, `when` in a switch, `with` / `switch` / `is` / `as` after an
  expression, `get` / `set` / `init` and `add` / `remove` in accessor lists, `field` in a property accessor, `allows`, `extension` in a
  static class, `assembly:` / `module:` in a file's attribute list, `managed` / `unmanaged` after `delegate*`
- `nameof(` lists names only, `typeof(` types only (no `dynamic`); from three letters on, items that contain what you typed in the middle
  are listed under those that start with it (`ReceiptLi` → `WriteReceiptLine`)

## 0.1.90

- Completion inside interpolated strings works in holes of every kind (`$"{order.}"`, `$@"…"`, raw `$$"""{{…}}"""`): names, and
  members after a dot; the text of a string stays without a list
- Format specifiers: after `{value:` in an interpolation, `{0:` in `string.Format` / `Console.WriteLine` / `AppendFormat`, inside
  `ToString("…")` and the format of `DateTime.ParseExact` the list offers the formats of the value's type with examples, as in Rider:
  numbers (`N2`, `C`, `P`, `X`, `D`…), dates and times (`d`, `D`, `t`, `T`, `yyyy-MM-dd`, `HH:mm:ss`, `o`, `s`, `u`…), `TimeSpan`,
  `Guid`, enums
- Regular expressions in strings: the patterns of `new Regex(…)`, of the static `Regex.IsMatch` / `Match` / `Replace` / `Split`…,
  `[GeneratedRegex]`, `[RegularExpression]`, parameters marked `[StringSyntax(StringSyntaxAttribute.Regex)]` and strings after a
  `// lang=regex` comment are highlighted as regular expressions, with completion (`\d`, `(?<name>`), brace matching, inspections and
  Check RegExp of the IDE; .NET named groups `(?<name>…)` / `(?'name'…)` are understood
- Preprocessor directives: `#` at the start of a line lists the directives; `#if` / `#elif` / `#define` list the symbols of the
  project (its `DefineConstants`, the symbols of every target framework, `#define`s of the file); `#nullable` and `#pragma warning`
  complete their arguments, `#pragma warning disable` the warning codes with titles
- XML documentation comments: `<` lists the tags and writes their closing part, `</` closes an open tag, `<param name="` offers the
  parameters not documented yet (`typeparam`, `paramref`, `typeparamref` likewise), `cref="` the members and types the place sees

## 0.1.89

- Postfix templates look at the type of the expression, as in Rider: `.await` is offered on tasks and awaitables only, `.foreach` on
  collections, `.for` / `.forr` on collections and numbers (`for (var i = 0; i < orders.Count; i++)`, `.Length` of an array or a string),
  `.if` / `.else` / `.while` / `.not` on `bool`, `.null` / `.notnull` not on value types that cannot be null, `.using` on disposables,
  `.lock` on reference types, `.throw` on exceptions. When the type is not known, every template is offered as before
- `.var`, `.foreach` and `.using` name the variable after the expression and its type (`order.Total.var` → `orderTotal`, `GetOrders().var` →
  `orders`, `orders.foreach` → `order`) in a template box with the other names to choose from; a name already used nearby gets a number
- New postfix templates of Rider: `.field` and `.prop` (introduce a field or a property for the expression), `.to` (assign to …), `.arg`
  (wrap into a call), `.sel` (select the expression), `.parse` / `.tryparse` on strings with a list of target types, and `.inject` typed
  between the members of a type (`IOrderService.inject`: a primary constructor parameter, or a constructor parameter and a field). The
  templates have Rider's descriptions; statement templates are no longer offered between members
- Live templates grow to Rider's set (69 instead of 33): `ctorf` / `ctorp` (constructor initializing all fields / properties), `itli`,
  `itar`, `ritar`, `sfc`, `outv`, `out`, `asrt`, `asrtn`, `psvm`, `sim`, `~`, `indexer`, `iterator`, `iterindex`, `equals`, `Attribute`,
  `Exception`, `namespace`, `#if`, `#region`, `checked`, `unchecked`, `unsafe`, `pci`, `pcs`, `psr`, `ear`, `nguid`, `from`, `join`, `mbox`,
  `propdp` / `dependencyProperty`, `attachedProperty`; descriptions as in Rider. `foreach`, `itli`, `from` name the element after the
  collection (`orders` → `order`)

## 0.1.88

- C# completion knows the expected type, as in Rider (built-in completion, no language server needed):
  - object, collection and `with` initializers list only the members that can still be assigned (`new Order { Id = 1, |` → `Customer`,
    `Status`…, not `Id`, read-only or `private set` ones); property patterns (`order is { |`, nested `{ Buyer: { |`) list the members of
    the matched type
  - the members of an expected enum come first as `OrderStatus.Paid` rows with their values: after `status == `, `case `, in a
    `switch` arm, after `is `, in arguments, assignments and `{ Status: `; the list opens by itself after `== ` and `case `
  - `await Highlights` rows for a method or local of `Task<T>` whose result is wanted; choosing one writes `await` and makes the method `async`
  - `new` with a target type: `Order o = new ` offers `Order()` first, then the derived types; `throw new ` only exceptions (of the
    solution and of the imported namespaces), `catch (` exceptions first; a base list offers classes and interfaces, `event ` delegates,
    a constraint classes and interfaces
  - smart completion (Ctrl+Shift+Space) lists only what fits the expected type: locals, members, methods returning it, static members of
    the type itself (`String.Empty`), enum members, `await` rows, `new T()`, `null` / `default` / `true` / `false`; where the type is
    unknown it is the usual list

## 0.1.87

- Completion without the language server offers the types of the referenced assemblies in the namespaces the file sees, with nothing
  typed too: `Li` or Ctrl+Space on an empty line gives `List<>` where `System.Collections.Generic` is imported, by a `using`, the implicit
  usings of the SDK or a `global using`. Types of the solution imported only by a `global using` are offered as well
- Types that are not imported, from the first letter, as in Rider: `Receipt (in Shop.Billing)` for a class of the solution in a
  neighbour namespace, `StringBuilder (in System.Text)` for one of the framework or a package. Choosing one adds the `using`; when the file
  already sees another type of that name (`Timer` of `System.Threading`), the namespace is written in front of it instead
- Extension methods of namespaces that are not imported after a dot (`numbers.ToImm` → `ToImmutableArray() (in
  System.Collections.Immutable)`), of the assemblies and of the solution, only those that take the value left of the dot; choosing one
  writes the call and adds the `using`
- Attributes of the assemblies at `[`: `[Obs` gives `Obsolete` and `ObsoletedOSPlatform (in System.Runtime.Versioning)`

## 0.1.86

- Completion in an argument list works without the language server again (it is off by default since 0.1.76, and these features came
  only from it):
  - choosing a method in the completion list opens its parameter info by itself, with the gray text of the arguments at hand
  - a lambda where a delegate is expected, first in the list and as gray text after `(` / `,`: `items.Where(` offers `x => ` and
    `(x, i) => ` of the second overload, named after the parameter types as in Rider; delegates of the solution are named by their
    parameters
  - `Changed += ` offers `(sender, e) => {}` and "Create method OnChanged(object?, EventArgs)", which adds the handler to the class;
    the same at a variable of a delegate type (`Func<int, bool> filter = `)
  - named arguments: `Place(qu` offers `quantity:` first; after positional arguments the remaining parameters of the overloads that fit;
    in an attribute the constructor parameters and the settable properties (`[Obsolete(DiagnosticId = `)

## 0.1.85

- Writing an override without the language server works as in Rider: after `override ` (or `public override `) the list opens by
  itself with the members of the base classes that can still be overridden — of the solution, of assemblies (`BackgroundService`,
  `Exception`, `object`) and of the C# the build generates (`Greeter.GreeterBase` of Grpc.Tools). The chosen member is written whole:
  the base's accessibility, the types as the file names them (no `global::`), `return base.X(...)` or `throw new NotImplementedException()`,
  `await` when `async` is typed, the `using` directives it needs
- Missing members are red without the server: CS0534 (an abstract member of the base class) on the class name and CS0535 (an interface
  member) on the interface in the base list. Alt+Enter there — and on an empty line of the class body — offers "Implement missing
  members"; "Override members..." is on Alt+Enter on whitespace of the body and on the header of a class with a base class. Ctrl+O and
  Ctrl+I open the same dialogs
- `base.` lists the members of base classes from assemblies too (`base.StartAsync` in a `BackgroundService`)
- Parameter Info (Ctrl+P) works in `: base(...)`, `: this(...)` and in the base of a primary constructor
- Generate → Overriding members no longer adds `using System;` to a member that does not throw
- The Override Members dialog shows one node per base type, and `override ` lists the members of the real base before those of `object`

## 0.1.84

- Colors of a C# file are there as it opens: identifiers, inactive `#if` text and format items no longer appear half a second after
  the keywords and strings. A file opened again shows the colors it had when it was closed at once (before the first paint, while its
  text is the same); a file opened for the first time gets them in about 0.15–0.25 s instead of 0.45–0.9 s, computed right away and
  reused by the editor's analysis instead of being computed twice; the analysis that follows changes nothing on screen. While the IDE
  indexes a project that has just opened, the file gets the plain colors at once and the full ones as soon as the indexes are ready
- The colors of identifiers are computed once per change: the editor's repeated passes over an unchanged file reuse them

## 0.1.83

- No false "CS1061: does not contain a definition" on members inherited from a base class of the solution when the file that uses them
  imports another type of the same name as the base (an EF Core entity `DirectStressTest : StressTest` queried in a handler that imports a
  DTO `StressTest`): the base is the one the entity's own declaration sees, as in the compiler. Quick Documentation and Go to
  Declaration of such members find them in that base

- C# colors follow the color scheme of the IDE: the plugin no longer puts its own colors into the IDE's schemes, so C# kinds take the
  scheme's Language Defaults (class, interface, function declaration and call, static method and field, constant, metadata) and edits of
  them reach C#, in Dark, Islands Dark, Light and any other scheme alike. Rider's palette is a scheme to pick: Settings | Editor |
  Color Scheme | "Rider Dark" or "Rider Light"

## 0.1.82

- Analyzer results without the language server are as quiet as in Rider and Visual Studio: the editor shows warnings and errors at the
  severity the compiler gives them (`.editorconfig`, rulesets, `AnalysisLevel`, `AnalysisMode`), while suggestions (IDE0290, CA1859…) are
  no longer underlined — their fixes stay on Alt+Enter. "Show suggestions" (Settings | .NET | Analyzers and Generators, now off by default)
  brings them back as weak warnings. On the playground: 3 marks instead of 174. `RunAnalyzers=false` is respected
- Run Code Analysis groups its results in the Build tool window by severity: Errors, Warnings, and Suggestions collapsed
- Find Usages and Rename without the server find what the compiler calls implicitly: `Deconstruct` (deconstruction, `foreach (var (a, b) …)`),
  `Add` (collection initializers), `GetEnumerator` (`foreach`), `Dispose` (`using`) and `GetAwaiter` (`await`), as Roslyn does; Rename
  leaves those places alone
- Find Usages of a type finds target-typed `new(…)` in dictionary and object initializers (`["a"] = new(…)`), collection initializers and
  collection expressions
- C# that MSBuild targets write into `obj/` — WPF XAML (`*.g.cs`, `*.g.i.cs`), gRPC (Grpc.Tools), resources — is known without the server:
  taken from the design-time build of the helper, or from `obj/` after the last build for projects of the old format. Such files appear
  under Dependencies → Analyzers → "Generated by build" and open with a banner. WPF and gRPC projects now show their errors instead of
  staying silent; after a XAML, proto or resx file is saved, the generated files are marked out of date until they are made again
- Names qualified by a namespace alias (`pb::IMessage<T>`, as protoc writes them) resolve; members of WPF types (TextBlock, Window…) get
  their errors — a non-public interface of a library type no longer makes the whole type unknown

## 0.1.81

- Extract Method inside loops: a variable the selection writes and the next iteration reads (in the loop's condition, before the
  selection or in the selection itself) is returned; a variable written on some paths only is passed in as well; `break`, `continue` or
  `return;` leaving the selection becomes `if (NewMethod(…)) break;` (or `NewMethod(…); break;` when every path leaves), as in Rider. What
  C# cannot express is refused with a hint: two kinds of jumps, a jump together with a value to return, `return` with a value
- Introduce Parameter (Ctrl+Alt+P, Refactor This → Introduce Parameter...) without the language server: the selected expression becomes
  a new parameter of the method or constructor, and every call in the solution passes it, with the parameters it reads replaced by the
  call's own arguments (`Twice(a + b, (a + b) * 2)`); a constant can stay as the default value of an optional parameter instead. The
  name is edited in place. Refused with a hint: locals in the expression, a method that overrides or is overridden, members of the
  instance when a call goes to another object
- Introduce Field: the name of the new field is edited in place at the field and at its uses, as Introduce Variable does
- Alt+Enter "Convert '?:' to 'if' statement" on a `?:` deeper in a statement (an argument, an operand): the statement is repeated in
  both branches; for the value of a local, the local is declared before the `if`
- Alt+Enter "Introduce variable" with several equal expressions in the block: Rider's chooser "Replace this occurrence only" / "Replace
  all N occurrences"
- Generate (Alt+Insert): Delegating members (first the field or property, then the members of its type), Equality comparer (a nested
  `IEqualityComparer<T>` class and a static property), Relational members (`IComparable<T>`, optionally `IComparable` and the operators
  `<`, `>`, `<=`, `>=`) and Relational comparer (a nested `IComparer<T>`), in Rider's order of the list. The server's "Introduce
  parameter for …" and "Generate comparison operators" rows no longer stand beside the plugin's own ones

## 0.1.80

- Nullable flow analysis on the plugin's own semantics (no language server): the null state of every local, parameter and member path
  (`x`, `this.F`, `x.A.B`) follows assignments, `== null` / `is null` / `is not null` / `is { }` / `is T t`, `??`, `??=`, `?.`, `!`,
  `&&` / `||`, early `return` / `throw`, loops, `try` and `switch`. New warnings as in Roslyn: CS8602 (dereference of a possibly null
  reference), CS8601 (possible null assignment), CS8604 (possible null argument, with the method in the message); CS8600, CS8603 and
  CS8625 now follow the flow instead of only literal `null`, and CS8618 is reported on constructors that leave a non-nullable field or
  auto-property unset on some path (`required`, `[SetsRequiredMembers]`, `: this(...)` and `[MemberNotNull]` helpers understood; partial
  types once their source generators are known)
- The attributes of `System.Diagnostics.CodeAnalysis` are understood in your code and in the referenced assemblies (`[NotNullWhen]`,
  `[MaybeNullWhen]`, `[NotNull]`, `[MaybeNull]`, `[AllowNull]`, `[DisallowNull]`, `[NotNullIfNotNull]`, `[DoesNotReturn]`,
  `[DoesNotReturnIf]`, `[MemberNotNull]`, `[MemberNotNullWhen]`): `string.IsNullOrEmpty`, `TryGetValue`, `ArgumentNullException.ThrowIfNull`
  and the like no longer leave a value "maybe null". Code compiled without nullable annotations is never warned about. The assembly index
  format changed (version 3): assemblies are indexed again once
- Precision first: where the analysis is not sure (a call it does not know, a lambda's captured variables, a type parameter) it stays
  silent. On the semantic gate (the playground and 17 libraries of dotnet/runtime, 887 files) it reports no warning Roslyn does not
- LINQ query expressions are translated into the method calls they stand for (C# spec 12.20.3) and resolved against the real type of the
  source: `Queryable` with `Expression<Func<...>>` for an `IQueryable<T>` (EF Core `DbSet<T>` included), your own `Select` / `Where` /
  `SelectMany` / `Join` / `GroupBy`, instance or extension. Range variables, `let`, `join ... into`, `group ... into` and continuations get
  the types the real methods give: completion, Quick Documentation (`(range variable) Order o`) and errors inside queries follow them
- Method-syntax calls on an `IQueryable<T>` pick `Queryable` over `Enumerable` (the better receiver), so lambdas against
  `Expression<Func<...>>` are typed and the result stays `IQueryable<T>`

## 0.1.79

- Analyzers and source generators of a project (and the design-time build behind them) no longer run for a project opened in Safe Mode
  (not trusted): they are code of the project

- Compound runs as in Rider: .NET Project configurations started together (a compound configuration, Run / Debug N Projects, or several
  started one right after another) are built by one build before any of them starts — the projects of a solution as one solution-filter
  build in the Build tool window, shared libraries once — and then run with `dotnet run --no-build`, so their builds no longer fight over
  `obj/`. If the build fails, none of them is started. On the playground the build of two services sharing a library took 1.9 s instead of
  3.6 s one after another
- Save as Compound Configuration: offered after Run N Projects, in the Solution view popup for several projects and in the Run menu (the
  .NET configurations running now). The compound is named after the projects (`Web + Worker`, or "Multiple Projects"), missing .NET Project
  configurations are saved, and they go into a folder of the same name, so the Services tool window groups them and stops or reruns them together
- "Wait for" in the .NET Project configuration: start after another configuration has started, listens on its address (from
  `launchSettings.json` or its output) or answers on a health URL, like `WaitFor` of .NET Aspire; with a timeout, a progress status while
  waiting, a notification when it runs out, and an error for a configuration that waits for itself or in a circle
- Debug of a compound configuration of .NET Project ones: it could not be started at all in IntelliJ IDEA Community (no runner of the
  platform took it); now the projects are built once and a debug session starts for each

## 0.1.78

- Overload resolution of the plugin's own C# support as in the compiler: implicit conversions (numeric, nullable, reference, boxing,
  user-defined), the better function member, `params` in normal and expanded form, optional parameters and named arguments, generic
  type inference, extension methods, lambdas and method groups against `Func` / `Action` / own delegates, target-typed `null`,
  `default`, `new()`, `?:` and collection expressions. Go to Declaration, quick documentation, colors and the types of `var` pick the
  right overload where they used to give up or pick the first one
- More compiler errors and warnings without the language server, with Roslyn's codes and places: unreachable code (CS0162, gray as in
  Rider), unused locals (CS0168 / CS0219, gray, "Remove unused variable"), a task not awaited in an `async` method (CS4014, "Add
  'await'"), instance member from a static context (CS0120), wrong argument type (CS1503) and missing arguments of generic, `params`
  and optional overloads and with named arguments (CS1501 / CS7036), conversions of `?:` and switch expressions (CS0029 / CS0266), and
  the simple nullable warnings where the nullable context is on: `null` to a non-nullable local, field, property, argument or return
  (CS8600, CS8625, CS8603) and fields and auto-properties left null by a class without constructors (CS8618). `#pragma warning
  disable`, `<NoWarn>` and `.editorconfig` severities are respected
- "Add argument name" context action: the parameter name before a positional argument and the ones after it

## 0.1.77

- Source generators without the language server: the generators of a project (`[GeneratedRegex]`, System.Text.Json, `[LoggerMessage]`,
  options validation, generator packages such as CommunityToolkit.Mvvm; not Razor) run in a helper of the plugin on save, after a build
  and on .NET | Code Analysis | Refresh Generated Files. Their files are under Dependencies → .NET x → Analyzers → generator, read-only
  with a banner, and what they declare resolves: completion, Go to Declaration and the built-in errors see it, and the errors no longer
  stay silent in projects with generators (a member missing from a partial type is reported once its generated part is known)
- Roslyn analyzers without the language server: the analyzers of the packages of a project, the CA rules and the IDE code style rules of
  the SDK, with the severities of `.editorconfig`, run in the background a second after a save (on the saved file) and on .NET | Code
  Analysis | Run Code Analysis (the whole project or solution, listed in the Build tool window). Their warnings are in the editor with
  the id first, suggestions (Info) as weak warnings, and Alt+Enter offers the code fixes of the analyzers ("Make static", "Rename to
  LoadAsync"...), applied as one undoable command. A warning of the last build that the analyzers report live is not shown twice
- Settings | .NET | Analyzers and Generators: run source generators, run analyzers on save, show suggestions, and when the helper stops
  without requests (10 minutes). The analyzers stand back while the language server is enabled, which runs them itself
- The helper (CodeAnalysisHelper) is built from source on first use with the newest .NET SDK of the machine, 8 or newer, against the Roslyn
  that SDK carries (no download); one per solution. On the playground (2 projects, 414 analyzers) it loads in ~4 s, analyzes a file in
  ~1 s the first time and in ~0.1–0.5 s after, applies a fix in ~0.3 s, and takes ~300 MB

## 0.1.76

- The C# language server (roslyn-language-server) is off by default: completion, navigation, Find Usages, rename, hierarchies, errors,
  quick documentation, context actions, Generate and Extract Method come from the plugin's own C# support. The server can still be
  turned on in Settings | .NET | Language Server; without it the plugin no longer asks for the .NET 10 SDK

## 0.1.75

- Generate (Alt+Insert) without the language server, with Rider's generators: Constructor (with base constructors), Read-only properties,
  Properties, Missing members (abstract members and interfaces, from the solution and from referenced assemblies), Overriding members,
  Partial members, Deconstructor, Equality members (`IEquatable<T>`, `HashCode.Combine`, equality operators), Formatting members
  (`ToString`) and the Dispose pattern. Members are chosen in a dialog grouped as in Rider; the code is formatted, gets the `using`
  directives it needs and goes where the caret is. Rows with nothing to offer are gray; the language server's rows of the same
  generators are no longer listed twice. Code | Implement Methods (Ctrl+I) and Override Methods (Ctrl+O) open the same dialogs in C#
- Extract Method (Ctrl+Alt+M, Refactor This) without the language server: statements or an expression; parameters from the locals it
  reads, the value it returns or `out` parameters for more, `async` when it awaits, `static` when it uses no instance member; the new
  name is edited in place. Refused with a hint when the selection returns, breaks out of a loop or jumps
- Introduce Field (Ctrl+Alt+F, Refactor This) without the language server: a `readonly` field initialized where it is declared, or a
  field assigned in the member when the expression reads locals

## 0.1.74

- C# errors without the language server ("Errors and warnings" = Built-in, the default): the plugin's own resolver reports the errors it is
  sure of with Roslyn's codes, texts and places, as you type and before any build — CS0103 / CS0246 / CS0234 (unknown name, type or
  namespace), CS1061 / CS0117 (no such member), CS1501 / CS7036 (wrong number of arguments), CS0029 / CS0266 (no implicit conversion) and
  CS0161 (not all code paths return a value). It stays silent when anything is unknown (an assembly not indexed yet, a generated file, a
  syntax error in the member), so a red mark is a real error
- Unused `using` directives are gray (CS8019, CS8933 for a repeat of a global using), with "Remove unused directives in file"
- "Import type" on an unknown type or name, and on an extension method that is not imported: the blue `System.Diagnostics.Stopwatch?
  Alt+Enter` hint as in Rider, a list of namespaces when there are several
- An error the plugin shows is no longer shown a second time by the language server or by the last build
- The assemblies of a project outside the solution are indexed once a file of it is open (completion and errors there too)

## 0.1.73

- References across the solution without the C# language server (Settings | .NET | Language Server → Source of Features →
  "Navigation and usages" and "Rename", Built-in by default): the plugin's own resolver finds them, in every project of the solution
- Find Usages (Alt+F7) and Show Usages (Ctrl+Alt+F7) of types and members: usages through the hierarchy of a member (a call through the
  interface counts for the implementation, as with the server), `nameof`, doc comment `cref`, `base(…)` and target-typed `new()` for
  constructors, members in property patterns; grouped by Read / Write / nameof / documentation as before. Usages of a type or member of a
  referenced assembly from its use in the code or from its metadata view
- The usages of a member or type under the caret are highlighted in the file (writes in the write color)
- Go to Implementation (Ctrl+Alt+B): the classes below a type or interface (every part of a partial one), the overrides and
  implementations of a member; Go to Super (Ctrl+U): the base types, the member a member overrides or implements (in an assembly — its
  metadata view)
- Type Hierarchy (Ctrl+H) with its Supertypes / Subtypes views and Call Hierarchy (Ctrl+Alt+H) with callers and callees
- Gutter icons as in Rider: "Overrides member", "Implements member", "Is overridden", "Has implementations", "Has subclasses"; a click
  lists them
- Rename (Shift+F6) of types and members across the solution: every declaration and usage (`nameof` and `cref` too), constructors and
  the finalizer with their type, the file named after the type; for a member of a hierarchy a dialog asks whether to rename the members it
  overrides or implements and the ones that override it ("Rename All" / "Only This"); a member or type of the same name is shown as a conflict
- With "Language server" chosen for these features and the server ready, the server answers all of the above, as before

## 0.1.72

- Quick documentation and parameter info, and the Alt+Enter context actions are now **Built-in** by default (Settings | .NET |
  Language Server → Source of Features): checked against the language server on the playground; switch back to "Language server" there
- Quick documentation: `<inheritdoc/>` shows the documentation of the base member, the interface member or the `cref` it names (of the
  solution and of the assemblies), with the member's own parts kept; `cref`s are links — a click shows the documentation of that symbol,
  F4 opens it (its declaration, or the metadata view of an assembly); Ctrl+Q on `var` shows the type it stands for (`T is int`);
  parameters of library methods show their nullability as Roslyn does (`string? value`); one popup instead of two pages when the server runs
- Parameter info: the constructor the arguments pick is marked (`new StringBuilder(16)`); on a named argument (`count: 2`) the parameter
  of that name is highlighted and the overloads without it are greyed
- Completion after a dot: no `System.Void`, no `Finalize` after `this.`, only the members of an enum after its name (as the server)
- Context actions: "Inline variable" also on `var` and on the type of the declaration; the server's "Replace conditional expression with
  statements" no longer stands beside the built-in "Convert '?:' to 'if' statement"

## 0.1.71

- Colors inside C# strings, as in Rider: the code in the holes of interpolated strings (`$"Total {x + 1:N2}"`) is colored as code
  (keywords, numbers, operators, nested strings), the braces of a hole as braces, alignment and format (`,5`, `:N2`) as a format item;
  raw strings with `$$"""` holes too
- Escape sequences are highlighted: `\t`, `\n`, `A`, `""` of verbatim strings, `{{` / `}}` of interpolated ones, in two alternating
  colors when they stand side by side; an invalid one (`\q`) is marked
- Format items of `string.Format`, `Console.WriteLine`, `StringBuilder.AppendFormat` (`{0}`, `{1,5:N2}`) get the format item color
- Settings | Editor | Color Scheme | C#: new group "String" with "String text", "Escape sequence" (Valid, Valid 2, Invalid) and "Format item"

## 0.1.70

- The settings of the plugin have a node of their own at the root of Settings: **.NET**, with Toolset and Build, NuGet, Coverage,
  Debugger and Language Server under it (they were under Tools). Editor | Code Style | C# and Color Scheme | C# stay where they were
- The main toolbar has Rider's **Build Solution** button right before the Run widget instead of the Debug / Release and target
  framework combo box: the hammer builds the solution, its arrow opens Build, Rebuild, Clean, NuGet Restore, Cancel Build and the
  choice of the configuration and the target framework
- **Refactor This** (Ctrl+Alt+Shift+T) in a C# file lists what can be done at the caret or on the selection, as in Rider: Rename,
  Introduce / Inline Variable, Move type to its file, the refactorings of the language server (Introduce local, Extract method,
  Extract base class...) — only what is available
- **Navigate To** (Ctrl+Shift+G, also in the Navigate menu): Declaration, Implementation, Base Symbols, Find Usages, Related Files,
  Type of Symbol, Related Tests, Show Usages, Type / Call Hierarchy, IL Code, Reveal in the file manager
- **Generate** (Alt+Insert) in a C# file shows the generators at once, in Rider's order: those of the language server (constructor,
  Equals and GetHashCode, overrides, interface members), Unit Test, Partial Part, Insert New GUID — no second popup
- The menu of the editor of a C# file has Rider's Find Usages Advanced..., Inspect (Call Hierarchy, Type Hierarchy, IL Code),
  Quick Definition and "Generate Code..."
- Fixed: Call / Type Hierarchy asked the language server while a menu was being shown and reported an IDE error; the server is now
  asked when the action is chosen. A build stopped with Cancel Build is shown as cancelled, not as failed
- The completion list no longer opens by itself after `{`, `(`, `,` and the other characters the language server triggers on with
  nothing typed (`GetStringAsync(...){` listed the parameters and fields of the class): as in Rider, it opens by itself after `.`,
  after `[` of an attribute and after a space where a type, a name or a keyword is expected; Ctrl+Space lists everything as before

## 0.1.69

- Debugging of .NET Framework 4.x programs on Windows with `dotnet-debugger` 0.2.0: Debug of a project of the old format starts the
  program its build has made (`TargetPath`), in its output folder as in Visual Studio; breakpoints, stepping, variables and evaluation
  work as for .NET
- A 32-bit program (AnyCPU with "Prefer 32-bit", the default of old project templates, or x86) is refused before the debugger starts,
  with what to change in the project: the debugger debugs 64-bit processes only. Attach to a 32-bit process is refused the same way
- Run | Attach to Process offers .NET Framework processes on Windows without a registry key (the key
  `dotnet.debugger.attach.netFramework` is gone): managed executables, from the first opening of the list, and hosts with the desktop
  CLR loaded
- Debug builds the project once: the program found by "Build .NET Project" reaches the debugger (every Debug used to build the project
  a second time)
- The editor no longer says that the packages of a project of the old format are not restored: such a project has no
  `project.assets.json`
- Typing `"` after `$` or `@` (`Console.WriteLine($"`) puts the closing quote, as for a plain string
- Debug no longer reports an IDE error "[Split debugger] RunContentDescriptor should not be used in split mode" on every start
  (IntelliJ 2026.1)

## 0.1.68

- The built-in formatter (Reformat Code with the formatter "Built-in") lays out initializers and argument lists as Rider does, where
  `dotnet format` leaves them as they are. A multi-line object, collection, array or anonymous-object initializer and a multi-line
  collection expression get the `{` / `[` and the `}` / `]` on lines of their own, the elements one indent in; the elements keep their
  lines (`1, 2,` stays together) unless one of them spans lines, then each goes to a line of its own. One-line ones get Rider's spaces:
  `new List<int> { 1, 2, 3 }`, `[1, 2, 3]`. `csharp_new_line_before_open_brace` without `object_collection_array_initializers` keeps
  the brace at the end of the line
- The lines of a multi-line argument list (calls, `new`, indexers, attributes, `: base(...)`) and of a parameter list go one indent
  right of the line of the call, as in Rider (not under the first argument); a `)` on a line of its own goes under that line, nested
  lists one indent further when the outer arguments are on lines of their own. Line breaks are never added or removed there

## 0.1.67

- A folder with a .NET solution or project opened in IntelliJ IDEA gets a module with the folder as its content root, as GoLand,
  PyCharm and WebStorm make one. IDEA 2026.1 opened such a folder without a module, and then its C# files were not indexed: the
  Built-in identifier colors, the plugin's Alt+Enter actions ("Make method async", the `using` conversions, "Convert to 'global using'",
  the context actions), the types of other files and Go to Class were missing. The Built-in colors and the Alt+Enter actions also
  work in a C# file the IDE does not index (a project outside the opened folder)
- The text of an inactive `#if` branch is gray (Settings | Editor | Color Scheme | C# → Preprocessor → Inactive branch), from the
  symbols of the project and the configuration chosen in the toolbar
- No completion list in or right after a number: `int x = 1` and Enter no longer writes `1_resized`
- The errors of the language server carry their code like the plugin's own (`CS0230: Type and identifier are both required…`)
- Ctrl + hover over a name the plugin's navigation resolves (a local, a parameter, a member) shows its line (`(local variable) int total`)
- The panel of gray text names the plugin, not an internal id ("Tab to complete io.github.dotnetsupport.declarations")
- Alt+Enter: the server's "Fix All: Make method async" (and the other "Fix All" rows of actions the plugin does itself) no longer
  stands above the plugin's own row

## 0.1.66

- Completion after a dot without the language server (Settings | Tools | .NET | Language Server → "Completion" = Built-in): `orders.`
  lists the members of the type of `orders` — of the solution and of the referenced assemblies, the inherited ones included, with the
  type arguments filled in (`Add(int item)` of a `List<int>`) — and the extension methods of the imported namespaces, without their
  `this` parameter; `Console.` lists the static members and nested types, `System.` the namespaces and types, `Color.` the enum members.
  Only what C# lets the place see: private members inside their type, protected ones inside a derived type (of a library base only
  through `this.`). The language server's items of the same names are not shown twice
- Quick documentation (Ctrl+Q, hover) and Parameter Info (Ctrl+P) on the plugin's own semantics, with "Documentation and parameter info"
  = Built-in (the language server stays the default until checked live): the first line as Rider writes it
  (`void Console.WriteLine(string value) (+ 17 overloads)`, `(parameter) int limit`, `decimal Order.Total { get; set; }`), the XML
  documentation of `///` comments and of the documentation files of assemblies (summary, parameters, returns, exceptions, remarks);
  every overload of a call as a row, the one that fits marked
- Extension methods of the solution declared on a keyword type (`this string text`) are found for colors and Go to Declaration too

## 0.1.65

- Errors of `using` without the language server ("Errors and warnings" = Built-in): CS1674 on a resource of `using` that is not
  `IDisposable` (`using (var n = 5)`), CS8410 on `await using` of something not `IAsyncDisposable`, and Roslyn's "Did you mean 'using'
  rather than 'await using'?" / the reverse when the other interface is there — with the texts and codes of the compiler, shown once.
  Nothing is reported where the type is not known through and through (an unknown type or base, a type parameter, a `DisposeAsync`
  method of the pattern)
- The completion list at `using (` and `using var x = ` leaves out the locals, parameters, fields and properties known not to be
  disposable (`IAsyncDisposable` for `await using`)

## 0.1.64

- Alt+Enter actions of the C# code without the language server (Settings | Tools | .NET | Language Server → "Context actions" =
  Built-in; without the server they work anyway): "Convert to '?:' expression" for an `if` that returns or assigns in both branches,
  "Convert '?:' to 'if' statement", "To expression body" / "To block body" for methods, local functions, constructors, operators,
  properties, indexers and accessors, "Use explicit type" (the type as the place writes it) / "Use 'var'", "Introduce variable" (the
  selection or the call at the caret, the name edited in both places at once) and "Inline variable". With Built-in the server's rows
  of the same actions are not shown twice

## 0.1.63

- Decompiled sources, as in Rider: Solution view → Dependencies → a package, an assembly of a framework (Frameworks →
  Microsoft.NETCore.App → System.Text.Json) or a referenced assembly → Decompile... lists its types; the chosen one opens as read-only C#
  in a tab `JsonSerializer.cs [System.Text.Json]` with the banner "Decompiled from System.Text.Json 10.0.0.0. Read-only", colors, folding,
  Structure and the XML documentation of the members. No `ilspycmd` or other global tool: the plugin's .NET helper decompiles with
  ICSharpCode.Decompiler (the engine of ILSpy). A reference assembly is decompiled from its implementation (the shared framework, `lib/` of
  the package), so the methods have bodies; a type forwarded elsewhere (`System.String` of `System.Runtime`) comes from where it lives.
  Decompiled types are kept on disk: a tab reopened with the project and Back in the navigation history find them again; a rebuilt or
  updated assembly is decompiled anew
- Built-in Go to Declaration (Ctrl+B) of a type or a member of a library opens its decompiled code once the type has been decompiled;
  the first time it opens the metadata view at once and decompiles the type in the background for the next time

## 0.1.62

- Go to Class (Ctrl+N) and Go to Symbol (Ctrl+Alt+Shift+N) find the types and members of the referenced assemblies — the framework,
  NuGet packages, assemblies by path — when "Include non-project items" is on (Search Everywhere: "All Places"), without the language
  server: `List<T> (System.Collections.Generic, System.Collections 10.0)`, one row for an assembly several projects refer to
- They open the metadata view of the type, as Rider does without a decompiler: a read-only C# file made of the index of the assembly —
  where the assembly is and its version, the type with its generic parameters, constraints, bases and attributes, every public and
  protected member as a signature without a body, the XML documentation as `///`, the nested types — with the caret on the member;
  the tab says which assembly it is and survives a restart of the IDE
- Built-in Go to Declaration (Ctrl+B, Ctrl+click; Settings | Tools | .NET | Language Server → "Navigation and usages" = Built-in) of a
  type or a member of an assembly opens its metadata view while the language server is not ready; with the server ready its decompiled
  source opens as before

## 0.1.61

- `using` on the plugin's own C# tree. Completion (Built-in): `using var` and `await using var` at the start of a statement (the latter
  makes the method `async`, as `await` does), locals, `var` and `new` in `using (`, namespaces of the solution and of the referenced
  assemblies after `using ` (level by level after a dot), types too after `using static ` and `using X = `, `global using` at the top of a
  file
- Postfix `.awaitusing` next to `.using`; both name the variable after the type made or the method called
  (`new StreamReader(path).using` → `using var reader = …`), `.awaitusing` makes the method `async`
- Alt+Enter: Convert to 'using' declaration / Convert to 'using' statement, Wrap in 'using' statement (a local made by `new`), Sort
  'using' directives (global first, `System` first), Convert to 'global using' (moves the directive to the project's `GlobalUsings.cs`,
  made when there is none). "Make method async" works on `await using` and `await foreach` too; with the Built-in source the server's
  row of the same action is no longer listed twice

## 0.1.60

- Navigation and usages, Completion and Colors of identifiers are built in by default (Settings | Tools | .NET | Language Server |
  Source of Features): a robot compared them with the language server on the playground — Go to Declaration went to the same places,
  completion lost nothing of the server's list, and every name the server colors got the same or a finer color
- Built-in Go to Declaration on `new Order()` goes to the constructor, as the language server does, not to the class
- Built-in completion offers the keywords it missed (`global`, `ref`, `stackalloc`, `static` in expressions; `dynamic`, `extern`,
  `scoped`, `void` in statements; `sealed` and `ref` at the start of a member) and no longer shows the language server's `override`
  members a second time next to its own; a named argument `amount:` stays next to the local `amount`
- Choosing the language server's `await`, `override` or `partial` item no longer fails (a stack overflow in the IDE log) and no longer
  leaves `awaitait` or `();` behind: the whole edit of the server is applied
- Built-in colors: switching «Colors of identifiers» no longer leaves the language server's colors under the built-in ones; calls of
  overloads with an argument of unknown type (`Console.WriteLine(x)`), `g.Key` of `group … into g` and methods on a LINQ query get
  their colors

## 0.1.59

- The assemblies the solution is compiled against are libraries of the IDE: Project view → External Libraries lists the framework
  packs (`Microsoft.NETCore.App.Ref 10.0.12`, `.NETFramework 4.8.1`), the NuGet packages with their versions and the assemblies
  referenced by path, each with its dlls; they are in the "All Places" scope and not in the project's. Updated after restore, a change of
  a project file or of the framework in the toolbar; only the dlls are added (binary, nothing reads their content), not the XML docs next
  to them

## 0.1.58

- Built-in Go to Declaration (Ctrl+B) and colors of identifiers now know the type of any expression, not only of a name: a call with
  inferred type arguments (`orders.First(o => ...).Total`, `items.Select(x => x).Last()`), `await`, an indexer, `?.` and `??`, `?:`,
  operators, tuples with element names, deconstruction, `foreach` over a dictionary, LINQ query expressions and the parameters of
  lambdas — the member after a dot on them goes to its declaration in the solution without the language server
- `var` takes the type of what it is initialized with, so members of such variables resolve as well

## 0.1.57

- Built-in colors of identifiers (Settings | Tools | .NET | Language Server → «Colors of identifiers» = Built-in) now know the
  referenced assemblies: `Console`, `List`, `Math.PI`, `WriteLine`, `numbers.Count`, extension methods like `Where` get the colors
  of their kinds without the language server, and the namespaces of `using static` / alias directives are colored
- Built-in Go to Declaration (Ctrl+B) goes to a member after a dot when the type of the value before it is known (`order.Total`,
  `Make().Total`, `this.Items`) and to the parameter of a named argument; members of assemblies are still left to the language server
- The plugin resolves C# names itself: namespaces, `using` / alias / `global using` (from other files and the project's implicit
  usings), types of the solution and of the referenced assemblies, inherited members, overloads by arguments, extension methods

## 0.1.56

- Errors and warnings and Rename are built in by default (Settings | Tools | .NET | Language Server | Source of Features): the syntax errors
  show at once while typing, with the compiler's codes, and the semantic ones still come from the language server; Shift+F6 renames
  locals, parameters, local functions, labels and type parameters without waiting for the server (members and types still go to it)
- Switching «Errors and warnings» no longer leaves the language server's syntax errors doubled or missing until the next edit
- Rename: the check of conflicts and the edit itself run after the inline rename ends, not inside it (an error of the IDE about a
  write-unsafe context)

## 0.1.55

- Completion of C# files can come from the plugin itself: Settings | Tools | .NET | Language Server | Source of Features |
  «Completion» = Built-in lists, at once and before the language server has loaded the solution, the keywords the place allows
  (`break` / `continue` only in a loop, `else` after an `if`, `catch` / `finally` after a `try`, `yield` in an iterator, the
  modifiers not typed yet at the start of a member), the locals, parameters, members and types in scope — locals first, then
  parameters, members, types and keywords, what fits the expected type higher — `override` of the base members (the whole
  member is written: `throw new NotImplementedException();` for an abstract one, a `base` call for a virtual one), `partial`
  methods and names for a new variable after its type (`StringBuilder ` → `builder`, `stringBuilder`). The server's items
  join the list once it is ready, without doubles. Language server stays the default until the scenarios are checked
- In a method that returns `Task<T>` / `Task` / `ValueTask` and is not `async`, `return ` gets the gray text
  `Task.FromResult();` / `Task.CompletedTask;` (Tab) and the same first item of completion
- Choosing `await` in a method that is not `async` makes it `async` (`void` becomes `Task`, `int` becomes `Task<int>`;
  event handlers stay `async void`); Alt+Enter on an `await` offers «Make method async» without the language server

## 0.1.54

- Syntax errors of C# files can come from the plugin itself: Settings | Tools | .NET | Language Server | Source of Features |
  «Errors and warnings» = Built-in shows the errors of the parser, of literals and of preprocessor directives with the compiler's
  codes, messages and places (`CS1002: ; expected` after the end of the line, not on the next one), at once while typing and before
  the language server has loaded the solution. The server still reports the semantic errors, and gives way only on the syntax errors
  the plugin shows itself, so nothing is shown twice. Language server stays the default until the scenarios are checked
- Without the language server (turned off) the syntax errors are shown by the plugin

## 0.1.53

- Rename (Shift+F6) on the built-in C# syntax tree (Settings | Tools | .NET | Language Server → «Rename» — Built-in; the default stays
  Language server for now): locals, parameters of lambdas, anonymous methods and local functions, parameters of methods, constructors,
  indexers and primary constructors, local functions, labels, query range variables and type parameters are renamed in place, without
  the server: every use in the file follows while typing, named arguments of the calls and `<param>` / `<typeparam>` tags of the doc
  comment too, and one Ctrl+Z brings everything back. A reserved keyword gets `@` (`@class`); a name that would change what another name
  means (a field it would hide, a lambda parameter that would capture a use, a second declaration in the same scope) shows the
  «Problems Detected» dialog. Members and types, a parameter used as a named argument in another file and the type parameters of a
  `partial` declaration still go to the language server; before it is ready a hint says why
- Navigation, the highlighting of usages, colors and rename on the built-in tree now share one resolver of names. Go to Declaration
  gains what only the colors knew: members of base classes of the solution, `base.X`, `Type.X`, members set by an object initializer,
  members of `using static` types. Scopes follow C# more closely everywhere: a local is visible in its whole block, variables of
  `while` / `do` / `lock` conditions and embedded statements stay there, a query continuation (`into g`) hides the variables before it.
  The positional parameters of a record are properties: their usages are no longer highlighted as if they were local

## 0.1.52

- The index of assemblies (format 2) keeps what a library shows to its users, not only what completion of unimported members needed:
  every public and protected type with its generic parameters and constraints, base type, interfaces, nested types and attributes,
  and every public and protected member — instance and static methods, constructors, operators, properties and indexers, events,
  fields, constants with their values — with signatures (nullable annotations, tuple element names, `ref` / `out` / `in` /
  `params`, default values). Extension methods are found by what they extend, and the XML documentation of a package or of the SDK
  is indexed as well (a separate compressed file, read only when asked for). This is the groundwork for the plugin's own C#
  semantics; nothing changes in the editor yet
- Indexing runs on half of the cores: the reference pack of .NET 10 with its documentation takes 0.8 s once per machine
- What a project is compiled against now follows the framework chosen in the toolbar, and includes projects of the old format
  (`Reference` with `HintPath`, the .NET Framework reference assemblies of `TargetFrameworkVersion`), the reference assemblies of
  .NET Framework for SDK projects (the `Microsoft.NETFramework.ReferenceAssemblies` package or the ones installed on the machine),
  and reference packs restore has downloaded for a framework the SDK has no pack of. Completion of unimported members sees them
- Members marked `[EditorBrowsable(Never)]` are still not offered by completion of unimported members

## 0.1.51

- Colors of identifiers in the palette of Rider: Settings | Editor | Color Scheme | C# now has Rider's groups and names — class,
  static class, record, struct, record struct, interface, enum, delegate, type parameter, attribute, namespace; method declaration and
  call, static and extension methods, local function; field, static field, constant (bold, also enum members), property, static
  property, event; local variable, mutable local variable (underlined: written again after its declaration), parameter, primary
  constructor parameter, label. Every new color falls back to the three colors of before, so custom schemes keep working
- The language server's colors use the new palette too (`static`, reassigned locals, locals and parameters, namespaces, labels)
- Colors of identifiers on the built-in C# syntax tree (Settings | Tools | .NET | Language Server → «Colors of identifiers» —
  Built-in; the default stays Language server for now): declarations by their kind and modifiers, locals, parameters, local functions,
  type parameters and labels with their uses, members of the type and its partial parts and base classes in the solution, `this.X`,
  `Type.X`, `using static`, object initializers, types of the solution by kind — read from the index, without parsing other files and
  without the server. What only the server can resolve (library types and their members) stays uncolored in this mode; switching the
  source recolors open editors at once

## 0.1.50

- Go to Declaration (Ctrl+B, Ctrl+click), Ctrl+hover and the highlighting of usages under the caret on the built-in C# syntax tree
  (Settings | Tools | .NET | Language Server → «Navigation and usages» — Built-in; the default stays Language server for now): locals,
  `out var` and pattern variables, deconstruction, `foreach` / `for` / `using` / `catch` variables, parameters of methods, constructors,
  primary constructors, lambdas and local functions, local functions, `goto` labels, query range variables and type parameters, by the
  scopes of C# (a lambda's parameter hides a local, a local hides a field) — without waiting for the language server
- Members of the enclosing type and of its other `partial` parts by name (overloads are offered as a list) and the types of the
  solution by name, among the namespaces the file is in and imports with `using`
- What the tree cannot tell (a member after a dot, base members, types of packages and the framework) still goes to the language
  server, and Go to Super (Ctrl+U) works whichever source is chosen: switching to Built-in loses no navigation
- Highlighting of a local tells reads from writes (a declaration, `=`, `++`, `out` and `ref` are writes) and stays within its method

## 0.1.49

- Reformat Code on the built-in C# syntax tree (Settings | Tools | .NET | Language Server → «Formatting», Built-in by default): what `dotnet format whitespace` does, inside the IDE and without a process — indents, braces on lines of
  their own, spaces around operators and after keywords, `case` labels, the blank lines kept, the `indent_*` and `csharp_*` options of
  `.editorconfig`; literals, comments and inactive `#if` branches are never touched
- Reformat Selection, Code | Auto-Indent Lines and the indent of a paste work with it; with CSharpier chosen or found by «Auto»
  CSharpier still formats, «None» still turns formatting off
- Settings | Tools | .NET | Toolset and Build → «Formatter»: a new «Built-in» choice next to «dotnet format (on save)». The built-in
  formatter works as you go (Reformat Code, a selection, Auto-Indent Lines, a paste); `dotnet format` formats whole files only, so it
  is meant for Reformat Code and «Reformat code» on save. «Auto» picks between the two by the «Formatting» switch of the Language
  Server page; a chosen formatter no longer depends on that switch
- «None» no longer lets Reformat Code or Reformat Selection touch C# files

## 0.1.48

- Typing assistance on the built-in C# syntax tree (Settings | Tools | .NET | Language Server → «Typing assistance» — Built-in, the
  default, or Language server for the previous token rules):
  - Extend Selection (Ctrl+W) goes through the syntax: `name` → `name.Trim()` → the argument list → the call → the expression → the
    statement → the block → the member with its attributes and doc comment → the type; the text of a string without its quotes, the hole
    of an interpolated string
  - Complete Statement (Ctrl+Shift+Enter) adds the `)`, `]` and `;` a statement lacks (`Foo(a, b` → `Foo(a, b);`), gives `if`, `foreach`,
    `while`, a method or a class without a body a block with the caret in it, and no longer splits a call written over two lines
  - The gray `;` is offered at the end of a statement written over several lines, and not after a line that only looks finished
- The gray text of lambdas and arguments of the language server follows the parameter info, not the typing assistance switch
- Completion of members that are not imported no longer offers static methods where only a type may stand: in `Task<…>` and other
  type arguments, `typeof(…)`, after `as`, in a base list and a `where` constraint (`Task<str` offered `Conversion.Str`)

## 0.1.47

- Go to Class and Go to Symbol on the built-in C# syntax tree use stub indexes: the rows come from the index, files are not parsed to
  show them
- C# files are parsed once while the IDE indexes them (the old declaration index serves only the "Language server" tree)
- Indexing follows each file's `#if` symbols and language version; such files are reindexed and parsed again when the target
  framework or the project configuration changes, so Go to Class leads to the class of the active `#if` branch
- New indexes of extension methods and of test attributes (`[Fact]`, `[Test]`) for the coming features

## 0.1.46

- Find Usages groups usages by kind on the plugin's own C# tree: Settings | Tools | .NET | Language Server → Source of Features →
  "Kinds of usages" — Built-in (the default), or Language server (the previous token heuristics). The tree tells more exactly: a
  deconstruction `(a, b) = …` writes `a` and `b`; a member given a nested initializer (`Lines = { 1, 2 }`) is read, not written; the
  type of `out Order o` is a declaration type, not a write; a type in a `switch` pattern is a type check; query variables, lambda
  parameters and members of anonymous types are declarations; the text of an interpolated string is a string. It works while the
  IDE indexes

## 0.1.45

- C# files are now parsed by the plugin's own parser, a port of Roslyn's (checked against the compiler: the same trees on the
  sources of Roslyn, dotnet/runtime and aspnetcore). Structure view, folding, breadcrumbs, Go to Class / Symbol, the IL Viewer, Go to
  Base, run markers of tests and the other features that read declarations take them from its tree, without waiting for the language
  server. Fixed on the way: a method with two headers under `#if` / `#else` no longer swallows the rest of its class, a `record` after
  a top-level `using (…) { … }` is found, `int a, b;` shows both fields, `[assembly: …]` is not part of the namespace below it, the
  primary constructor of a generic record and the attributes of an enum member are shown
- `#if` regions follow the project: the symbols and the C# version of a file come from its project and the configuration and target
  framework chosen in the toolbar; switching the target framework updates the Structure view
- Settings | Tools | .NET | Language Server → Source of Features: "Structure, folding and breadcrumbs" — Built-in (the default) or
  Language server (the previous heuristics)

## 0.1.44

- Identifier colours while the solution loads: a C# file restored from the last session is coloured by the plugin's heuristics as soon
  as it is shown, also when another tab (README.md of the folder) was opened over it; the heuristics step aside only once the server's
  colours of that file are on screen, not when the server becomes ready (a file had no colours for 100–300 ms then)
- The semantic tokens kept from the last session colour an opened file before the server is ready (about 2 s after opening the
  playground instead of 4.5–5 s): they were stored but never shown
- Identifier colours no longer drop to none and come back when the server refreshes its tokens (seen 1–4 times while a solution
  loaded): the old colours stay until the new ones replace them
- No `AlreadyDisposedException` in idea.log after a project with a running server is closed
- Go to Symbol / Go to Class show a C# member as Java does: `Area()` with its parameters, the type it is in in grey (`BaseShape`,
  `Outer.Inner`; for a type its namespace and outer types) and the file on the right — the same rows before the server is ready and
  after (an interface method and its three overrides were four identical rows "Area  GoToBase.cs"). The Structure view still shows the
  type after the name
- Completion: what matches the typed prefix in its case comes first: `pub` gives `public` above `PublicKey`, `public s` gives
  `sbyte`, `sealed`, `short`, `static`, `string`, `struct` above `String` and `SByte`, `str` (also in TYPE of the `prop` template)
  gives `string` first. Types of namespaces that are not imported (the server's and the plugin's) rank below keywords
- Typing the name of a property (`public RankedOrder Order`) no longer pops up the server's name suggestions, which hid the grey
  ` { get; set; }` and took Tab; Ctrl+Space still lists them, and a lower-case name (a field) still gets them
- Developer tools: `baseline.py` puts the measured file back in front and chooses the solution of a folder with several;
  `tools/roslyn-lsp/capture_keywords.py` records what the server answers where keywords and types compete

## 0.1.43

- Groundwork for the plugin's own C# code model (no change in behaviour yet): for every C# file the plugin now knows what the compiler
  of its project gets in the configuration and target framework chosen in the toolbar — the conditional compilation symbols (`DEBUG`,
  `TRACE`, `NET10_0`, `NETFRAMEWORK`, `NET48_OR_GREATER`... as MSBuild computes them), the C# language version (explicit, or the
  default of the SDK for the target framework), nullable context, global usings, the root namespace and the source files. Projects of
  the old format (.NET Framework without the SDK) are read too
- Structure view, breadcrumbs, folding, Go to Class / Symbol, Go to Base / Implementation, the IL Viewer, inline values, test markers and
  the other features that read the declarations of a C# file now get them through one model (`CSharpSyntaxModel`), so that the plugin's
  own parser can replace the heuristics later; the behaviour is pinned by snapshot tests. No visible change intended
- Developer tools: `tools/ui-robot/baseline.py` measures the editor (first highlighting, server ready, memory of the IDE and of the
  server, completion latency) on `debug-playground` and on a sample of ASP.NET Core; the numbers are in `CSHARP_PSI_MIGRATION.md`

## 0.1.42

- Run of a .NET Framework project of the old format starts the program its build has made (`bin\Debug\App.exe`), in its output
  folder, as Visual Studio does; `dotnet run` cannot run such a project. Program arguments, the working directory and the environment
  of the configuration apply. Stop closes a WPF or Windows Forms program at once. The output of a console program is read in the OEM
  code page of Windows, the one its console writes in
- Build of a single old-format project with `packages.config` restores its packages: the build is given the folder of its solution,
  as Visual Studio does

## 0.1.41

- .NET Framework projects of the old format (without `Sdk`), and the solutions with them, are built by MSBuild of Visual Studio or
  Build Tools, found by `vswhere`: WPF markup is compiled, `packages.config` is restored by the build, web targets are there. The .NET
  SDK used to skip the XAML of such a WPF project and fail with "no Main". Settings | Tools | .NET | Toolset and Build has "MSBuild
  version": Auto (old-format projects by Visual Studio, the rest by the .NET SDK), the .NET SDK always, or a chosen Visual Studio for
  everything. Without Visual Studio the plugin says so once, with a link to Build Tools
- The Solution view of an old-format project reads the targets of Visual Studio too (`$(VSToolsPath)`), instead of skipping them

## 0.1.40

- Groundwork for the plugin's own C# code model (no change in behaviour yet): the plugin is built with three new, still empty modules,
  and every feature of the language server can now be switched between the server and a built-in implementation. The switch shows
  up on Settings | Tools | .NET | Language Server for a feature once its built-in implementation exists; today there is none, and
  everything works as before

## 0.1.39

- Unit Tests: the output of `dotnet test` itself (the summary, the results file) stays at the root of the run instead of being shown as
  the output of the test that happened to be running; a test sees only its own output
- Unit Tests: Stop no longer marks as interrupted a test that has already finished — its outcome is kept even when its details had
  not come yet
- New Solution is a button on the Welcome screen right after New Project (it used to be hidden behind "⋮"), in IntelliJ IDEA and in
  the IDEs whose New Project button is a group, like GoLand
- IL Viewer: a field without an initializer shows its declaration with a note that a field has no IL of its own; a line with no IL
  says so once; the note wraps instead of being cut
- New Solution: the description of a template wraps instead of being cut at the right edge

## 0.1.38

- Aspire: the AppHost project is recognized (Aspire 8, 9 and 13) — its own icon, its run configuration first. The dashboard login link
  is clickable in the console and kept for **Open Dashboard** in the Services tool window, and the browser opens straight on it. Debug of
  the AppHost attaches the debugger to every project service it starts, each in its own tab, again after a restart of the resource; Stop
  ends them all. A breakpoint at the very start of a service may still be missed: the debugger attaches once the process runs
- Services: the links of a row (the address of a web project, Restart of `dotnet watch`) now show up — the platform only made existing
  text clickable

## 0.1.37

- Find Usages for C# is grouped as in Rider, behind the standard Group by Usage Type / Module / File Structure toggles of the Usages
  view: by usage kind (read, write, invocation, `nameof`, attribute, `new`, base type, `using`, `typeof`, type check, cast, type
  argument, doc comment), by .NET project and by the type and member the usage is in

## 0.1.36

- Unit Tests: results appear while `dotnet test` runs (VSTest: xUnit, NUnit, MSTest), as in Rider — each test shows as running when it
  starts, then as passed, failed or skipped with its duration, message, stack trace and output; Stop marks the test that was running;
  the TRX report at the end fills in anything missed. The plugin's test logger is built once per machine on the first run. Projects on
  Microsoft Testing Platform still get their results at the end

## 0.1.35

- **Publish...** for applications (the Solution view and the .NET menu), as in Rider: configuration, target framework, target runtime,
  deployment mode (framework-dependent / self-contained), single file, ReadyToRun, trimming, target location, with the command shown
  before it runs. `.pubxml` publish profiles are read — those of Visual Studio included — and written by Save as Profile; a container
  image is published with `dotnet publish -t:PublishContainer`. The output goes to the Build tool window, a notification opens the
  folder; Save as run configuration makes a ".NET Publish" run configuration

## 0.1.34

- Security: the packages folders named in `obj/project.assets.json` are read only when they are local (or the folder of `NUGET_PACKAGES`):
  a cloned repository with such a file pointing at `\\host\share` no longer makes the IDE log on to that host, which would give it the
  NTLM hash of the user. Applies to the package schemas of appsettings and to the assembly index

## 0.1.33

- `appsettings.json` and `appsettings.<Environment>.json` get completion and checks for the sections your code reads — in any project,
  not only ASP.NET Core: `Configure<T>(GetSection(...))`, `AddOptions<T>().Bind / BindConfiguration`, `GetSection(...).Get<T>()`,
  `GetValue<T>`, `config["A:B"]`, or a `// appsettings: Section` comment above a class. Keys come with their XML-doc descriptions and
  defaults, enums with their values; a value of the wrong type is a warning, a key the options class does not have is a weak warning.
  Works together with the ASP.NET Core sections of SchemaStore and the `ConfigurationSchema.json` of NuGet packages, and follows a C#
  file a second after it changes, even before it is saved
- Security: the Solution view and the IL viewer no longer evaluate the MSBuild logic of a project the IDE has not been told to trust;
  the IL viewer follows only a local path to a PDB named inside an assembly, never a network one

## 0.1.32

- **IL Viewer** (.NET | IL Viewer), as in Rider: the IL of the method at the caret, from the last build, follows the editor. The PDB tells
  which code is on the line — the method, the state machine of an async method or an iterator, a lambda, a local function; the lines
  of C# and their IL are highlighted both ways. A banner says when the source has changed since the build, with a Build button

## 0.1.31

- The C# language server in the status bar shows its CPU and memory next to the C# icon, updated every two seconds, instead of the
  name of the solution (that one is in the tooltip and the popup)

## 0.1.30

- **New Solution** window as in Rider — File | New, the .NET menu and the Welcome screen: project kinds and templates, target framework
  and the SDK to pin in `global.json`, language, the template description and advanced settings, a Git repository

## 0.1.29

- Memory Dump (.NET Monitor) shows the retained size of every type and a tree of dominators — who keeps how much of the heap alive —
  computed in the background by a new diagnostics helper on ClrMD
- With the registry key `dotnet.debugger.attach.netFramework`, Run | Attach to Process also offers native hosts that load the .NET
  Framework runtime themselves (IIS `w3wp.exe`, Office, Windows PowerShell), 32-bit ones included

## 0.1.28

- Projects are evaluated by MSBuild itself, as a build sees them (conditions, properties, imports), through a helper that stays running:
  Debug finds the built assembly without a `dotnet msbuild` call per launch, and an old-style (non-SDK) project shows in the Solution
  view exactly the files it lists; the others appear only with Show All Files

## 0.1.27

- NuGet: a feed the IDE cannot reach (proxy, certificates, credentials) is searched through a .NET helper that goes to the network like
  the `dotnet` CLI — nuget.config credentials and credential providers included; local folder feeds and V2 feeds are searched too.
  The journal says which way every request went
- NuGet Restore (the NuGet window, .NET | NuGet | Restore and the automatic restore) restores `packages.config` projects into the
  packages folder of their solution (`repositoryPath` of nuget.config respected); a package that is not there is marked "not restored"
  in Dependencies

## 0.1.26

- `dotnet watch`: the Hot Reload state (watching, changes applied, restart needed after a rude edit, build failed) in the row of the
  Services tool window and colored in the run console, with **Restart** there and in the toolbar of the console. Under an IDE
  `dotnet watch` does not read its keys (Ctrl+R, the y/n answer), so Restart runs the configuration again in the same tab

## 0.1.25

- Go to Base (Ctrl+U) on a method, property, event or indexer jumps to the member it overrides or implements in the base classes and
  interfaces: the nearest base class first, then the interfaces; several — a list. A base type that is not in the sources is gone to
  as a type. Needs the C# language server

## 0.1.24

- Legacy projects with `packages.config`: their packages are shown under Dependencies → Packages and as installed in the NuGet window,
  where install, update and remove are disabled with an explanation instead of writing a `PackageReference` into the project

## 0.1.23

- Run | Attach to Process can offer .NET Framework processes (a managed `.exe` without `runtimeconfig.json`), for a debug adapter that
  debugs the desktop CLR. Off by default: registry key `dotnet.debugger.attach.netFramework`

## 0.1.22

- Several projects selected in the Solution view: **Run N Projects** / **Debug N Projects** in the context menu, as Rider's "Run
  Multiple Projects". The projects are built one after another first, then launched together, each in its own tab of Services
- The builds a debug launch waits for go one after another, so a Compound configuration with several .NET projects no longer builds
  their shared dependencies twice at the same time

## 0.1.21

- The journal of NuGet (.NET | Plugin Logs, category `nuget`, and the Log tab of the NuGet window) now has every request to a feed:
  the URL, the route the IDE takes to it (direct or through which proxy, with or without stored credentials), the HTTP status, the size
  and the time of the answer, the services the feed announces, and the result of every search and version lookup by feed. A failure
  is described by its class and causes (`UnknownHostException: host`, `SSLHandshakeException: PKIX path building failed`, `HTTP 401`)
  with the setting of the IDE that usually fixes it — the feeds are read by the HTTP client of the IDE, which has its own proxy,
  certificates and credentials, so the `dotnet` CLI can reach a feed the IDE cannot. Nothing is said "once per session" any more: a
  feed that is down says so on every request. The first request also writes the proxy settings of the IDE and the proxy variables of
  its environment
- The list of the NuGet window says "none, N of M feeds did not answer" instead of an empty "Available Packages: 0" when a feed failed
- The sources the window searches are in the journal as the CLI lists them, with the local feeds that are skipped

## 0.1.20

- Package completion in project files looks as in Rider: the id and, in gray, the version — nothing else, so the popup
  is narrow. The next characters narrow the list in place; the feed is asked again only when nothing is left, so the
  list no longer rebuilds (and flickers) on every keystroke. A `.` or `-` goes on with the id instead of inserting the
  selected package
- NuGet window: the rows of the list look as in Rider: the id, the installed version and the name of the feed, the latest
  version in the color of a link at the right edge, a vulnerable or deprecated package marked by an icon; no description
  in the row. The list keeps the width of the window, so the versions no longer vanish behind its right edge after a
  refresh. The rows carry no action icons: install, update and remove are the buttons of the card

## 0.1.19

- **.NET | Plugin Logs**: the journal of the plugin in a tool window — what it started, what came back, what failed and why,
  one line per event, nothing of the IDE in it. The same journal is in `~/idea-dotnet-logs/plugin/plugin-<day>.log`
  (.NET | Open Logs Folder, formerly Show Plugin Logs). Failures of external programs are described by their exit code
  and last lines, not by stack traces
- The C# language server explains why it did not start: the runtime its host asked for, where the host looked and what
  it found there (`You must install or update .NET`), with the `dotnet` the plugin uses and its runtimes in the journal.
  The server is started with `DOTNET_ROOT` set to the installation of that `dotnet`, so a .NET 10 in a folder of its own
  is found even when the machine registers an older one
- Notifications for what used to vanish silently: the server process that could not be created, the debug adapter that
  exited in the middle of a session, the indexer of assemblies that could not be built. Every error notification has a
  "Plugin Logs" button
- The check at the opening of a solution lists the SDKs and the runtimes in the journal, and the ".NET 10 is missing"
  dialog looks at the runtimes too, not only the SDKs

## 0.1.18

- Settings | Tools | .NET: **Additional search folders** for the dotnet CLI. Each folder and its immediate `dotnet*`
  subfolders are scanned for a `dotnet` host (so `/usr/share` finds `/usr/share/dotnet-sdk-8.8.403`), ahead of PATH. The
  same list can be set on every machine through the `DOTNET_SUPPORT_SEARCH_PATHS` environment variable — for corporate
  installs in non-standard directories

## 0.1.17

- Editor, gray text: `break;` on the first (empty) line of a `case` / `default` section

## 0.1.16

- Editor, gray text (Tab to accept): a `;` after a statement that is missing it (the same safe cases as Complete Statement), and
  ` => throw new NotImplementedException();` after a method header in a class / struct / record

## 0.1.15

- Editor: **Complete Statement** (Ctrl+Shift+Enter) adds the missing `;` at the end of a statement and opens a new line. Conservative
  without a parser — only clear statements (an assignment, a call, `return` / `throw` / `break` …); a header like `if (x)` or a declaration
  like `void M()` is never turned into `...;`

## 0.1.14

- Editor: three more postfix templates — `.for` (`for (var i = 0; i < expr; i++)`), `.forr` (reverse) and `.cast` (`((T)expr)`, with the
  type placeholder selected)

## 0.1.13

- When a .NET solution is opened and no .NET 10 SDK is installed, a modal dialog now says the C# language server (Roslyn) needs the .NET 10
  SDK and will not start on .NET 8 / 9 — with buttons to download it or open the settings. Replaces relying on the easy-to-miss balloon

## 0.1.12

- Editor: the other occurrences of the identifier under the caret are now highlighted while the C# server is still loading (by matching
  the token text); once the server covers the file, its accurate `documentHighlight` takes over

## 0.1.11

- Editor: pressing Enter inside a `//` line comment or a `/* */` block comment now continues the comment on the next line (`// ` for a line
  comment, ` * ` aligned for a block). The `///` doc comment already did this

## 0.1.10

- Editor: **Extend / Shrink Selection** (Ctrl+W / Ctrl+Shift+W) now grows structurally in C# — word → contents of the enclosing `()` / `[]`
  / `{}` → the brackets too → the next pair out, and the string or comment the caret is in

## 0.1.9

- Editor, `.http` files: **Insert Development JWT** in the context menu runs `dotnet user-jwts create` for the ASP.NET Core project and
  inserts an `Authorization: Bearer <token>` header at the caret — to call an `[Authorize]` endpoint without hand-making a token

## 0.1.8

- NuGet: **Consolidate Package Versions** (.NET → NuGet menu and the NuGet window toolbar) finds packages referenced at different versions
  across the solution's projects and brings them to the highest version used, with a preview of the changes

## 0.1.7

- When a solution with an ASP.NET Core web project is opened, the plugin checks the HTTPS development certificate and, if it is not trusted,
  offers to trust it (`dotnet dev-certs https`) — so the first `https://` run does not warn in the browser

## 0.1.6

- Editor: **Run C# File with dotnet** in the context menu of a standalone `.cs` file (one that no project owns) runs `dotnet run <file>` —
  a file-based app, .NET 10+ — in a console with a Stop button

## 0.1.5

- NuGet window: right-click a package → **Why Is This Installed?** runs `dotnet nuget why` and shows the dependency paths that pulled it
  in, per target framework (needs .NET SDK 8.0.400+)

## 0.1.4

- NuGet window: installed packages that are **vulnerable** or **deprecated** are now flagged — a tag in the list (severity for a
  vulnerability, "Deprecated" otherwise) and a line in the package card with a link to the advisory. Uses
  `dotnet list package --vulnerable` and `--deprecated`; outdated packages already showed the "→ latest" arrow

## 0.1.3

- NuGet window, Sources tab: the **Enabled** checkbox is now clickable — a click enables or disables the feed
  (`dotnet nuget enable | disable source`), instead of only looking like a checkbox

## 0.1.2

- Code Metrics: Calculate Code Metrics on a project or solution (.NET menu and the Solution view context menu) opens the **Code Metrics**
  tool window with the maintainability index, cyclomatic complexity, depth of inheritance, class coupling and lines of code of every
  namespace, type and member, as `Analyze | Calculate Code Metrics` of Visual Studio. The maintainability index carries its green / yellow
  / red band; double-click opens the member; the toolbar recalculates and copies the rows as CSV. Measured by a Roslyn helper built on the
  machine (no build of the solution is run — references are its last build)

## 0.1.1

- Debugger: long, multi-line and structured strings get a **View** link; the popup shows JSON, XML, HTML and JWT formatted and highlighted,
  the hover over such a string in the editor shows the same popup
- Debugger: temporary breakpoints (Remove once hit) and dependent ones (Disable until hitting the following breakpoint) work

## 0.1.0

The first public baseline.

- Solution view as in Rider: `.sln` / `.slnx`, solution folders, projects, dependencies, file nesting, Show All Files, VCS colors
- C# through `roslyn-language-server`: completion, diagnostics, quick fixes, rename, navigation, code lens, inlay hints, semantic highlighting
- Build, Rebuild, Clean, Restore in the Build tool window; Debug / Release and target framework switcher
- Run configurations from `launchSettings.json`, Endpoints window, `.http` requests
- Debugger on its own DAP client: breakpoints with conditions, hit counts and logpoints, exception breakpoints, Evaluate, Set Value,
  Attach to Process, debugging of tests, Set Next Statement
- Unit Tests explorer, code coverage (coverlet)
- NuGet window: search, install, sources, folders
- EF Core: migrations, database update, scripts, scaffolding, migrations window
- MSBuild files: schema-driven completion and documentation for properties, items and metadata
- Monitor, memory dump, allocations by line; Upgrade Assistant analysis; `dotnet new` templates with options
- Settings pages in English and Russian
