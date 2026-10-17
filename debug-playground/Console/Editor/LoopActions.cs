using System;
using System.Collections.Generic;

namespace Playground.Editor;

/// <summary>
/// Live check of the Alt+Enter context actions on loops (0.1.163, <c>NativeCSharpLoopActions.kt</c>): <c>foreach</c> ↔ <c>for</c> over an
/// indexable collection, a <c>for</c> reversed, <c>foreach</c> over an expression statement.
/// Put the caret where a marker says, press Alt+Enter, check EXPECT, then undo (Ctrl+Z) so the file stays as it is. Nothing here is
/// called, the file only has to compile.
/// </summary>
public class LoopActions
{
    // TYPE:loop-foreach-to-for — Alt+Enter on `foreach` of ForEachList: "Convert 'foreach' to 'for'". EXPECT: `for (var i = 0; i < items.Count; i++)`
    // with `var item = items[i];` as the first statement of the body, the rest of the body kept. On ForEachArray: `numbers.Length` and the index
    // `j` (`i` is a parameter there). NOT offered on ForEachEnumerable (an IEnumerable has no indexer) nor with the caret inside the body.
    public static void ForEachList(List<string> items)
    {
        foreach (var item in items)
        {
            Console.WriteLine(item);
        }
    }

    public static void ForEachArray(int[] numbers, int i)
    {
        foreach (var n in numbers) Console.WriteLine(n + i);
    }

    public static void ForEachEnumerable(IEnumerable<string> items)
    {
        foreach (var item in items)
        {
            Console.WriteLine(item);
        }
    }

    // TYPE:loop-for-to-foreach — Alt+Enter on `for` of ForList: "Convert 'for' to 'foreach'". EXPECT: `foreach (var item in items)` and the
    // statement `var item = items[i];` gone. On ForArray: `foreach (var name in names)` with every `names[i]` replaced by `name`.
    // NOT offered on ForIndex (`i` is used by itself) nor on ForWrite (`names[i]` is assigned).
    public static void ForList(List<string> items)
    {
        for (var i = 0; i < items.Count; i++)
        {
            var item = items[i];
            Console.WriteLine(item);
        }
    }

    public static void ForArray(string[] names)
    {
        for (int i = 0; i < names.Length; i++)
        {
            Console.WriteLine(names[i] + names[i].Length);
        }
    }

    public static void ForIndex(string[] names)
    {
        for (var i = 0; i < names.Length; i++)
        {
            Console.WriteLine(i);
        }
    }

    public static void ForWrite(string[] names)
    {
        for (var i = 0; i < names.Length; i++)
        {
            names[i] = "";
        }
    }

    // TYPE:loop-reverse — Alt+Enter on `for` of ForList: "Reverse 'for'". EXPECT: `for (var i = items.Count - 1; i >= 0; i--)`; on Reversed
    // the other way round: `for (var i = 0; i < count; i++)`. NOT offered on Stepped (`i += 2`).
    public static void Reversed(int count)
    {
        for (var i = count - 1; i >= 0; i--)
        {
            Console.WriteLine(i);
        }
    }

    public static void Stepped(int count)
    {
        for (var i = 1; i <= count; i += 2)
        {
            Console.WriteLine(i);
        }
    }

    // TYPE:loop-iterate — on the empty line below type `items;` and press Alt+Enter on it: "Iterate via 'foreach'". EXPECT:
    // `foreach (var item in items)` with an empty block and the caret inside it. Type `count;` instead: NOT offered (an int).
    public static void Iterate(List<string> items, int count)
    {

        Console.WriteLine(items.Count + count);
    }
}
