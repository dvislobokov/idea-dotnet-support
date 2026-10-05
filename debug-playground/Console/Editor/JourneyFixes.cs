using System.Runtime.CompilerServices;

namespace Playground.Editor;

/// <summary>
/// Live check of the editor findings of the developer journey (docs/DEV_JOURNEY.md, stage 4; 0.1.100). Settings | .NET | Language Server →
/// Source of Features: «Errors and warnings», «Completion», «Formatting» = Built-in. Type on the empty line under a marker comment (or do what
/// it says), compare with EXPECT, then undo (Ctrl+Z; delete a file a fix created) so the file keeps compiling.
/// </summary>
public interface IJourneyOrders
{
    Task AddAsync(string order, CancellationToken ct = default);
    void Log(string text, int level = 0, string? source = null, params object[] args);
    void Trace(ref int count, [CallerMemberName] string caller = "");
}

public class JourneyStore
{
    public int Add(int a, int b) => a + b;
}

public enum JourneyStatus { New, Paid, Shipped }

// TYPE:journey-reformat — caret on the line below, Ctrl+Alt+L. EXPECT: the class laid out in full, as Rider: `{` and `}` of the class, of `M`
// and of the `if` on lines of their own, one statement a line, `public int Count { get; set; }` and the lambda stay on one line each.
// NOT: «Formatted 1 line» with the line as it was. With `csharp_preserve_single_line_blocks = true` in .editorconfig it stays as it is.
public class JourneyOneLine { public int Count { get; set; } public void M(bool ok) { if (ok) { Count++; Console.WriteLine(Count); } Action a = () => { Count--; }; a(); } }

public class JourneyFixes
{
    // TYPE:journey-implement-defaults — on the empty line below type `class JourneyOrders : IJourneyOrders { }`, Alt+Enter on `JourneyOrders`
    // → «Implement missing members», all three. EXPECT: `AddAsync(string order, CancellationToken ct = default)`,
    // `Log(string text, int level = 0, string? source = null, params object[] args)`, `Trace(ref int count, [CallerMemberName] string caller = "")`.
    // NOT: a parameter without its `= …` (calls `AddAsync(order)` failed with CS7036 at the build).

    public async Task Body(JourneyStore store, List<int> items, bool ready)
    {
        // TYPE:journey-create-class — type `var repo = new InMemoryOrderStore();`, Alt+Enter on `InMemoryOrderStore`. EXPECT: «Create class
        // 'InMemoryOrderStore'» first, then «Create record …», «Create struct …»; the class opens in a new file `Editor/InMemoryOrderStore.cs`
        // with `namespace Playground.Editor;`, the caret inside its body. `new Shipment(1, "a")` → a constructor `Shipment(int i, string a)`.
        // Delete the new file, Ctrl+Z.

        // TYPE:journey-create-enum — type `var state = ShipmentState.Sent;`, Alt+Enter on `ShipmentState`. EXPECT: «Create class …» and
        // «Create enum 'ShipmentState'»; the enum — a new file with the member `Sent`. Delete the file, Ctrl+Z.

        // TYPE:journey-create-member — type `int n = store.Count;`, Alt+Enter on `Count`. EXPECT: «Create property 'Count'», «Create field
        // 'Count'»; the property `public int Count { get; set; }` is written at the end of `JourneyStore` (above in this file). Ctrl+Z twice.

        // TYPE:journey-create-value — type `_total = 5m;`, Alt+Enter on `_total`. EXPECT: «Create field '_total'» first (`private decimal _total;`
        // at the top of the class), «Create local variable '_total'» (`var _total = 5m;`), «Create parameter '_total'», «Create property …».
        // `Recalculate(1, "a");` → «Create method 'Recalculate'»: `private void Recalculate(int i, string a)` with `throw new NotImplementedException();`.
        // `await LoadAsync();` → `private Task LoadAsync()`. Ctrl+Z.

        // TYPE:journey-semicolon — type `var other = new JourneyStore(` (the editor puts `)`) and then `;`. EXPECT: `var other = new JourneyStore();|`
        // with the caret after `;`. NOT: `new JourneyStore(;`. In `for (int i = 0` the `;` is typed inside the parentheses as always. Ctrl+Z.

        // TYPE:journey-paren-string — type `Console.WriteLine($"{store,3} {2.5:C}");` as it is, key by key. EXPECT: one `)` at the end, the caret
        // after `;`. NOT: `");)`. Ctrl+Z.

        // TYPE:journey-indent-foreach — type `foreach (var item in items.OrderByDescending(i => i))` and Enter. EXPECT: the caret one level in
        // (under `item` + 4). The same after `if (ready)` + Enter. Ctrl+Z.

        // TYPE:journey-cs7036 — type `store.Add(1);` EXPECT: red CS7036 «There is no argument given that corresponds to the required parameter
        // 'b' of 'JourneyStore.Add(int, int)'» on `Add` before any build; `new JourneyStore(1);` → CS1729 «'JourneyStore' does not contain a
        // constructor that takes 1 arguments». Ctrl+Z.

        // TYPE:journey-postfix-enum — type `var day = JourneyStatus.` and Ctrl+Space. EXPECT: `New`, `Paid`, `Shipped` (and the members of every
        // enum) — no postfix templates `.arg`, `.await`, `.cast`, `.nameof`, `.par`, `.parse`. After `ready.` they are there. Ctrl+Z.

        await Task.CompletedTask;
    }

    // TYPE:journey-indent-enum — on the empty line below type `enum Kind { A, B }` and Enter. EXPECT: the caret at the indent of `enum`
    // (4 columns), not 8. Ctrl+Z.

    // TYPE:journey-record — not typed: New → Class/Interface (on the Editor folder) → Record, name `JourneyRecord`. EXPECT: the file
    // `public record JourneyRecord();` with the caret inside `()`, not at 1:1. Delete the file.

    // TYPE:journey-appsettings-comma — not in this file: in `Console/appsettings.json` on a new line before the last property type `"Jour`,
    // choose a key of the schema that completion writes as `"Key": {},` and type ` "A": 1 },` inside. EXPECT: one comma after `}`. Ctrl+Z.
}
