using System;

namespace Playground.Editor;

/// <summary>
/// Live check of the Alt+Enter context actions on parameters and arguments (0.1.163, <c>NativeCSharpParameterActions.kt</c>): the null
/// check of a parameter, the names of the arguments of a call added and removed. «Context actions» = Built-in (the default). Put the caret
/// where a marker says, press Alt+Enter, check EXPECT, then undo (Ctrl+Z) so the file stays as it is. Nothing here is called, the file
/// only has to compile.
/// </summary>
public class ParameterActions
{
    public class Order
    {
        public Order(string name, int count, bool force) { }
    }

    // TYPE:param-check-null — Alt+Enter on `name` in the parameters of Save: "Check parameter for null". EXPECT:
    // `ArgumentNullException.ThrowIfNull(name);` as the first statement of the body (the `if … throw new ArgumentNullException(nameof(name));`
    // form on a target framework without ThrowIfNull). NOT offered on `count` (an int), on `label` (`string?`), on `name` of Short (an
    // expression body) nor on `name` of Checked (the check is there).
    public static void Save(string name, int count, string? label)
    {
        Console.WriteLine(name + count + label);
    }

    public static int Short(string name) => name.Length;

    public static void Checked(string name)
    {
        ArgumentNullException.ThrowIfNull(name);
        Console.WriteLine(name);
    }

    // TYPE:param-add-names — Alt+Enter inside the parentheses of `Save("a", 1, null)`: "Add argument names". EXPECT:
    // `Save(name: "a", count: 1, label: null)`. The same on `new Order("a", count: 1, false)` → `new Order(name: "a", count: 1, force: false)`.
    // Also there: "Add argument name 'count'" on the single argument `1` (names it and the ones after it). NOT offered on `Log("a", "b", "c")`
    // (several values of a `params` parameter).
    public static void Calls()
    {
        Save("a", 1, null);
        var order = new Order("a", count: 1, false);
        Log("a", "b", "c");
        Console.WriteLine(order);
    }

    public static void Log(string head, params string[] parts) { }

    // TYPE:param-remove-names — Alt+Enter inside the parentheses of `Save(name: "b", count: 2, label: "l")`: "Remove argument names".
    // EXPECT: `Save("b", 2, "l")`. NOT offered on `Save(count: 3, name: "c", label: null)` (the order differs from the parameters').
    public static void NamedCalls()
    {
        Save(name: "b", count: 2, label: "l");
        Save(count: 3, name: "c", label: null);
    }

    // TYPE:param-check-variable — Alt+Enter on `names` in Locals: "Check variable for null". EXPECT: right after the declaration
    // `ArgumentNullException.ThrowIfNull(names);`. NOT offered on `count`. Ctrl+Z.
    // TYPE:param-check-expression — Alt+Enter on `Message` in Locals: "Check expression for null" → `Console.WriteLine(error.Message)`
    // under `if (error.Message != null) { … }`; "Assert expression is not null" → `System.Diagnostics.Debug.Assert(error.Message != null,
    // "error.Message != null");` before it. Ctrl+Z.
    public void Locals(Exception error)
    {
        var names = new List<string>();
        var count = names.Count;
        Console.WriteLine(error.Message);
        Console.WriteLine(count);
    }

    // TYPE:param-overload — Alt+Enter on `force` in Store: "Create overload without parameter". EXPECT: right after Store
    // `public void Store(string name, int retries) => Store(name, default, retries);`. Ctrl+Z.
    // TYPE:param-chop — Alt+Enter on `name` in Store: "Chop parameters list". EXPECT: each parameter on its line, aligned under `string name`.
    // Then Alt+Enter again: "Put parameters on one line" puts them back. Ctrl+Z.
    public void Store(string name, bool force, int retries)
    {
        Console.WriteLine($"{name} {force} {retries}");
    }
}
