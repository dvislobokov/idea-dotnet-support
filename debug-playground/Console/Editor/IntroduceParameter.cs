using System;

namespace Playground.Editor;

/// <summary>
/// Live check of Introduce Parameter without the language server (0.1.81). Select what a marker says, press Ctrl+Alt+P (Refactor |
/// Introduce Parameter) or Refactor This (Ctrl+Alt+Shift+T) → "Introduce Parameter...", check EXPECT, then undo (Ctrl+Z; the calls in
/// IntroduceParameterCaller are undone with it). After the refactoring the name of the parameter is in a box at the declaration and at its
/// use: type another name, both change, Enter.
/// </summary>
public class IntroduceParameterScenarios
{
    private int _seed = 7;

    // TYPE:introduce-parameter — select `10` in Area. EXPECT: a popup "Pass It at Every Call" / "Make the Parameter Optional (= 10)".
    // The first: `Area(int width, int <name>)`, `return width * <name>;`, the calls `Area(3, 10)` in Use and `s.Area(5, 10)` in
    // IntroduceParameterCaller. The second: `Area(int width, int <name> = 10)`, the calls stay as they are.
    public int Area(int width)
    {
        return width * 10;
    }

    public void Use() => Console.WriteLine(Area(3));

    // TYPE:introduce-parameter-args — select `width * 2` in Twice (no popup: it is not a constant). EXPECT: `Twice(int width, int <name>)`,
    // `return <name> + 1;`; the calls pass the expression with their own arguments: `Twice(4, 4 * 2)`, `Twice(a + b, (a + b) * 2)`.
    public int Twice(int width)
    {
        return width * 2 + 1;
    }

    public int UseTwice(int a, int b) => Twice(4) + Twice(a + b);

    // TYPE:introduce-parameter-optional — select `"> "` in Prompt. EXPECT: the new parameter goes before `int level = 1` (an optional one
    // is last), the calls `Prompt("a", "> ")` and `Prompt("b", "> ", 2)`.
    public string Prompt(string text, int level = 1)
    {
        return "> " + text + level;
    }

    public string UsePrompt() => Prompt("a") + Prompt("b", 2);

    // TYPE:introduce-parameter-refused — select `local + 1` in Refused. EXPECT: a red hint "The expression uses the local 'local'", nothing
    // changes. Select `_seed * 2` in Seeded: "… a call goes to another one ('other', …)" — IntroduceParameterCaller calls it on another
    // object, which has its own `_seed`.
    public int Refused()
    {
        var local = 1;
        return local + 1;
    }

    public int Seeded()
    {
        return _seed * 2;
    }
}

public class IntroduceParameterCaller
{
    public int Get(IntroduceParameterScenarios s) => s.Area(5);

    public int Other(IntroduceParameterScenarios other) => other.Seeded();
}
