namespace Playground.Editor;

/// <summary>
/// Live check of 0.1.171: the modifiers changed from a header with Alt+Enter, as Rider offers them on Jellyfin. Put the caret where the
/// <c>// TYPE:name</c> comment says, press Alt+Enter, compare with EXPECT, undo (Ctrl+Z). Nothing here is called, the file only has to
/// compile.
/// </summary>
public class ModifierActions
{
    // TYPE:mod-to-private — caret on the name `Rename`, Alt+Enter. EXPECT: "To private" first; → `private void Rename(...)`. Its submenu
    // (→ on the row) lists "To protected", "To internal", "To protected internal", "To private protected" — NOT "To public"
    public void Rename(string name) => System.Console.WriteLine(name);

    // TYPE:mod-to-public — caret on `_count`, Alt+Enter. EXPECT: "To public" (the field is private); the order of `static readonly` is kept
    private static readonly int _count = 1;

    // TYPE:mod-to-virtual — caret on `Describe`, Alt+Enter. EXPECT: "To virtual" → `public virtual string Describe()`; NOT on the
    // `static` method `Create` below, NOT on the private `Hidden`
    public string Describe() => _count.ToString();

    public static ModifierActions Create() => new();

    private void Hidden() { }

    public void Use() => Hidden();
}

// TYPE:mod-to-internal — caret on the name `IModifierShop`, Alt+Enter. EXPECT: "To internal" → `internal interface IModifierShop`;
// "Make partial" → `public partial interface IModifierShop`
public interface IModifierShop
{
    // TYPE:mod-interface-member — caret on `Open`, Alt+Enter. EXPECT: no "To private" / "To internal" (members of an interface)
    void Open();
}

public abstract class ModifierShape
{
    // TYPE:mod-to-abstract — caret on `Area`, Alt+Enter. EXPECT: "To abstract" → `public abstract double Area();` (the body goes);
    // on `Name`: `public abstract string Name { get; set; }` (the initializer goes)
    public virtual double Area()
    {
        return 0;
    }

    public string Name { get; set; } = "";

    // TYPE:mod-override — caret on `ToString`, Alt+Enter. EXPECT: no "To private" / "To virtual" (an override keeps the base's accessibility)
    public override string ToString() => Name;
}

// TYPE:mod-struct — caret on `Length`, Alt+Enter. EXPECT: "To private", its submenu only "To internal" (no `protected` in a struct)
public struct ModifierPoint
{
    public int Length() => 0;
}
