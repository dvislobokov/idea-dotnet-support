using System;

namespace Playground.Editor;

/// <summary>
/// Live check of the Alt+Enter context actions on <c>if</c> (0.1.163, <c>NativeCSharpIfActions.kt</c>): Invert 'if', Merge nested 'if'
/// statements, Split into two 'if's, Convert to guard clause, Add braces / Remove braces. «Context actions» = Built-in (the default). Put
/// the caret where a marker says, press Alt+Enter, check EXPECT, then undo (Ctrl+Z) so the file stays as it is. Nothing here is called,
/// the file only has to compile.
/// </summary>
public class IfActions
{
    // TYPE:if-invert — Alt+Enter on `if` of Invert: "Invert 'if'". EXPECT: `if (count != 0)` with the two blocks swapped (`B()` first,
    // `A()` in the else). On `if` of Chain NOT offered (an `else if` chain).
    public static void Invert(int count)
    {
        if (count == 0)
        {
            A();
        }
        else
        {
            B();
        }
    }

    public static void Chain(int count)
    {
        if (count == 0) A();
        else if (count == 1) B();
        else C();
    }

    // TYPE:if-invert-guard — Alt+Enter on `if` of Guard: "Invert 'if'". EXPECT: `if (!ok || name is null) return;` and `A();` / `B();`
    // after it at the method's indent, no block. In the loop of Loop: `if (item == 0) continue;` then `A();`.
    public static void Guard(bool ok, string? name)
    {
        if (ok && name is not null)
        {
            A();
            B();
        }
    }

    public static void Loop(int[] items)
    {
        foreach (var item in items)
        {
            if (item != 0)
            {
                A();
            }
        }
    }

    // TYPE:if-guard-clause — Alt+Enter on `if` of Guard: "Convert to guard clause". EXPECT: the same as Invert 'if' there. NOT offered on
    // `if` of Loop (one statement only) nor on `if` of Invert (it has an `else`).

    // TYPE:if-merge — Alt+Enter on the outer `if` of Merge: "Merge nested 'if' statements". EXPECT: `if (count > 0 && (ok || count > 10))`
    // with the inner block one level left. NOT offered on `if` of NoMerge (a second statement in the outer block).
    public static void Merge(int count, bool ok)
    {
        if (count > 0)
        {
            if (ok || count > 10)
            {
                A();
                B();
            }
        }
    }

    public static void NoMerge(int count, bool ok)
    {
        if (count > 0)
        {
            if (ok) A();
            B();
        }
    }

    // TYPE:if-split — Alt+Enter on `if` of Split: "Split into two 'if's". EXPECT: `if (count > 0)` with a block holding `if (ok)` and the old
    // block, one level right. NOT offered on `if` of Or (`||`).
    public static void Split(int count, bool ok)
    {
        if (count > 0 && ok)
        {
            A();
            B();
        }
    }

    public static void Or(int count, bool ok)
    {
        if (count > 0 || ok) A();
    }

    // TYPE:if-add-braces — Alt+Enter on `if`, on `else`, on `foreach` and on `while` of Braces: "Add braces". EXPECT: the one statement in
    // `{ }` on its own lines each time. "Remove braces" is NOT offered there.
    public static void Braces(bool ok, int[] items, int n)
    {
        if (ok) A();
        else B();
        foreach (var item in items)
            A();
        while (n > 0) n--;
    }

    // TYPE:if-remove-braces — Alt+Enter on `if` of Blocks: "Remove braces". EXPECT: `A();` under `if (ok)` without braces, the `else` block
    // untouched. NOT offered on `if` of Two (two statements) nor on `if` of Dangling (the `else` would move to the inner `if`).
    public static void Blocks(bool ok)
    {
        if (ok)
        {
            A();
        }
        else
        {
            B();
        }
    }

    public static void Two(bool ok)
    {
        if (ok)
        {
            A();
            B();
        }
    }

    public static void Dangling(bool ok, bool other)
    {
        if (ok)
        {
            if (other) A();
        }
        else B();
    }

    private static void A() { }
    private static void B() { }
    private static void C() { }
}
