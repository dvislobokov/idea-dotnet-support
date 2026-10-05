namespace Playground.Editor;

/// <summary>
/// Live check of Complete Statement (Ctrl+Shift+Enter) and of the gray <c>;</c> on the plugin's own tree (0.1.48). Settings | Tools | .NET |
/// Language Server → Source of Features → «Typing assistance» = Built-in, then the markers below: type what a <c>// TYPE:name</c> comment says
/// on the empty line under it, press what it says, compare with EXPECT, undo with Ctrl+Z. With «Language server» (the default for now) the
/// tokens answer: the differences are named in EXPECT ("tokens: …"). The file only has to compile.
/// </summary>
public class CompleteStatement
{
    private int _count;

    // Each typing marker has a method of its own: what follows the typed line is the `}` of the method, as when a statement is new

    public void Call(int a, int b)
    {
        // TYPE:complete-call — type `Make(a, b` (no closing parenthesis), Ctrl+Shift+Enter. EXPECT: `Make(a, b);` and the caret on a new
        // line under it. Tokens: nothing is added, a new line opens under `Make(a, b`
        
    }

    public void Nested(int b)
    {
        // TYPE:complete-nested — type `_count = Make(Make(1, 2), b`, Ctrl+Shift+Enter. EXPECT: `_count = Make(Make(1, 2), b);`, a new line.
        // Tokens: nothing added
        
    }

    public void TwoLines(int a, int b)
    {
        // TYPE:complete-two-lines — caret at the end of the `Make(a,` line below, Ctrl+Shift+Enter. EXPECT: the statement is complete, nothing
        // is split: the new line opens under `b);`. Then erase that `;` and repeat: EXPECT `;` back after `b)`. Tokens: a new line opens
        // between `Make(a,` and `b);`, the call is split
        Make(a,
             b);
    }

    public void Unfinished(int a)
    {
        // TYPE:complete-unfinished — type `Make(a, `, Ctrl+Shift+Enter. EXPECT with either choice: nothing added (an argument is still to
        // come), a new line under it
        
    }

    public void If(bool ready)
    {
        // TYPE:complete-if — type `if (ready` (no `)`), Ctrl+Shift+Enter. EXPECT: `if (ready)`, then `{` and `}` on lines of their own (as the
        // braces of this method) with the caret indented on the empty line between them. Tokens: nothing added, a new line under it
        
    }

    public void ForEach(int[] items)
    {
        // TYPE:complete-foreach — type `foreach (var item in items)`, Ctrl+Shift+Enter. EXPECT: a block under it with the caret inside.
        // Tokens: a new line only
        
    }

    public void While()
    {
        // TYPE:complete-while — type `while (Ready(`, Ctrl+Shift+Enter. EXPECT: `while (Ready())` and a block with the caret inside. Tokens:
        // a new line only
        
    }

    public void IfSameLine(bool ready)
    {
        // TYPE:complete-if-same-line — type `if (ready) Make(1, 2`, Ctrl+Shift+Enter. EXPECT: `if (ready) Make(1, 2);`, a new line under it.
        // Tokens: nothing added
        
    }

    // TYPE:complete-method — on the empty line below type `public void Run()`, Ctrl+Shift+Enter. EXPECT: `{` and `}` under it, the caret
    // inside, indented. Tokens: a new line only. Type `public int Total` instead: EXPECT `public int Total;` (a field; tokens: nothing added)


    // TYPE:gray-semicolon — in Grays below erase `);` at the end of `.Where(x => x > 0);` and type `)` again. EXPECT: a gray `;` after
    // `.Where(x => x > 0)` — the statement starts on the line above; Tab takes it. Tokens: no gray `;` there (the
    // line alone is no statement). On the empty line under the comment type `Make(1, 2`, then `)`: EXPECT a gray `;` after `)` with either
    // choice; type `Make(1,` + Enter + `Make(2, 3)`: tokens offer a gray `;` after `Make(2, 3)`, the tree does not (the outer call lacks `)`)
    public int Grays(int[] values)
    {
        var positive = values
            .Where(x => x > 0);

        return positive.Count();
    }

    private static int Make(int a, int b) => a + b;

    private static bool Ready() => true;
}
