using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

namespace Playground.Editor;

/// <summary>
/// Live check of Extract Method and Introduce Field without the language server (0.1.75, CSHARP_PSI_MIGRATION.md task C4d). Select what a
/// marker says, press Ctrl+Alt+M (Refactor | Extract | Method) or Refactor This (Ctrl+Alt+Shift+T) → "Extract Method...", check EXPECT,
/// then undo (Ctrl+Z). After the extraction the name `NewMethod` is in a frame at the call and the declaration: typing a new name changes
/// both, Enter finishes (a scenario not undone leaves its `NewMethod`: the next one is `NewMethod1`). With the server ready Refactor This
/// has no second "Extract method" row of the server. Introduce Field puts the field after the last field of the type.
/// </summary>
public class ExtractMethodScenarios
{
    private readonly int _factor = 3;

    public int Statements(int a, int b)
    {
        // TYPE:extract-statements — select the two lines below (from `var sum` to `Console.WriteLine(sum);`), Extract Method. EXPECT:
        // `NewMethod(a, b);` in their place and `private static void NewMethod(int a, int b)` after this method with the two lines.
        var sum = a + b;
        Console.WriteLine(sum);
        return a;
    }

    public int Returned(int a, int b)
    {
        // TYPE:extract-returned — select the two lines below, Extract Method. EXPECT: `var total = NewMethod(a, b);` and
        // `private static int NewMethod(int a, int b)` ending with `return total;`.
        var total = a + b;
        total *= 2;
        return total;
    }

    public string OutParameter(int a)
    {
        int count;
        string label;
        // TYPE:extract-out — select the two lines below, Extract Method. EXPECT: `count = NewMethod(a, out label);`; the method declares
        // `int count;` itself, takes `out string label` and returns `count`.
        count = a * 2;
        label = count.ToString();
        return label + count;
    }

    public int Expression(int a)
    {
        // TYPE:extract-expression — select `a * _factor + 1` below, Extract Method. EXPECT: `return NewMethod(a);` and a method that is
        // not static (it reads `_factor`): `private int NewMethod(int a) { return a * _factor + 1; }`.
        return a * _factor + 1;
    }

    public async Task<int> Async(Task<int> work)
    {
        // TYPE:extract-async — select the line below, Extract Method. EXPECT: `var value = await NewMethod(work);` and
        // `private static async Task<int> NewMethod(Task<int> work)`.
        var value = await work;
        return value + 1;
    }

    public int Refused(List<int> items)
    {
        foreach (var item in items)
        {
            // TYPE:extract-refused — select the `if` line below, Extract Method. EXPECT: a red hint "The selection contains 'return' …",
            // nothing changes. The same for the `continue;` line alone: "… out of a loop it does not hold".
            if (item > 10) return item;
            if (item < 0) continue;
        }
        return items.Sum();
    }

    public void IntroduceField(int a)
    {
        // TYPE:introduce-field — select `string.Concat("a", "b")` below, Refactor | Introduce Field (Ctrl+Alt+F) or Refactor This →
        // "Introduce Field...". EXPECT: `private readonly string _concat = string.Concat("a", "b");` after `_factor`, `_concat` in place
        // of the expression. On `a * 2` instead: a field `private int _value;` and `_value = a * 2;` before the line.
        Console.WriteLine(string.Concat("a", "b"));
        Console.WriteLine(a * 2);
    }
}
