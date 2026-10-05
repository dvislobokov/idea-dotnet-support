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
            // TYPE:extract-refused — select the `if` line below, Extract Method. EXPECT: a red hint "The selection contains 'return' with a
            // value …", nothing changes. On the `continue` line (0.1.81): `if (NewMethod(item)) continue;` and a `bool` method with
            // `if (item < 0) return true;` and `return false;`. Both lines together: a hint, the method could not do both.
            if (item > 10) return item;
            if (item < 0) continue;
        }
        return items.Sum();
    }

    public void LoopCarried(int n)
    {
        var last = -1;
        for (var i = 0; i < n; i++)
        {
            if (last >= 0) Console.WriteLine(last);
            // TYPE:extract-loop-carried — select the line under this comment, Extract Method (0.1.81). EXPECT: `last = NewMethod(i);` and
            // `private static int NewMethod(int i)` that declares `int last;`, assigns and returns it: the next iteration reads `last` in
            // the `if` above. NOT expected: `NewMethod(last, i)` that loses the new value.
            last = i * 2;
        }
    }

    public int LoopSum(List<int> items)
    {
        var sum = 0;
        foreach (var item in items)
        {
            // TYPE:extract-loop-break — select the two lines under this comment, Extract Method (0.1.81). EXPECT: `if (NewMethod(item, ref
            // sum)) break;` and `private static bool NewMethod(int item, ref int sum)` with `return true;` in place of `break;` and
            // `return false;` at the end. Select only `Console.WriteLine(item); sum += item;` instead: `sum = NewMethod(item, sum);`.
            if (item < 0) break;
            Console.WriteLine(item); sum += item;
        }
        return sum;
    }

    public void LoopFound(int[] values, int wanted)
    {
        var found = -1;
        for (var i = 0; i < values.Length; i++)
        {
            // TYPE:extract-loop-sometimes — select the line under this comment, Extract Method (0.1.81). EXPECT: `found = NewMethod(values,
            // i, wanted, found);`: written only when equal, so the old value comes in and goes back.
            if (values[i] == wanted) found = i;
        }
        Console.WriteLine(found);
    }

    public void ReturnEarly(string? text)
    {
        // TYPE:extract-return-void — select the two lines under this comment, Extract Method (0.1.81). EXPECT: `if (NewMethod(text))
        // return;` and a `bool` method whose `return;` became `return true;`.
        if (text == null) return;
        Console.WriteLine(text.Length);
        Console.WriteLine("done");
    }

    public void IntroduceField(int a)
    {
        // TYPE:introduce-field — select `string.Concat("a", "b")` below, Refactor | Introduce Field (Ctrl+Alt+F) or Refactor This →
        // "Introduce Field...". EXPECT: `private readonly string _concat = string.Concat("a", "b");` after `_factor`, `_concat` in place
        // of the expression. On `a * 2` instead: a field `private int _value;` and `_value = a * 2;` before the line. Since 0.1.81 the
        // name is in a box at the field and at its uses: type another name, all change, Enter.
        Console.WriteLine(string.Concat("a", "b"));
        Console.WriteLine(a * 2);
    }
}
