namespace Playground.Editor;

/// <summary>
/// Live check of 0.1.168: Alt+Enter on a field no constructor assigns, as Rider offers it — "Initialize field from constructor" and, with
/// several such fields, "Initialize members from constructor". Put the caret where the marker says, press Alt+Enter, choose the item,
/// compare with EXPECT, undo (Ctrl+Z). Nothing here is called, the file only has to compile. Context actions = Built-in (the default).
/// </summary>
public class FieldActionsOne
{
    // TYPE:field-one — caret on `_count`, Alt+Enter, "Initialize field from constructor". EXPECT: `FieldActionsOne(string name, int count)`
    // and `_count = count;` right after `_name = name;`, before `Start();`
    private readonly string _name;
    private readonly int _count;

    public FieldActionsOne(string name)
    {
        _name = name;
        Start();
    }

    private void Start() => System.Console.WriteLine($"{_name} {_count}");
}

public class FieldActionsChained
{
    // TYPE:field-chained — caret on `_label`, Alt+Enter, "Initialize field from constructor". EXPECT: only `FieldActionsChained(int size)`
    // gets `string label` and `_label = label;`; the constructor chaining with `: this(0)` stays as it is
    private readonly string? _label;

    public FieldActionsChained() : this(0) { }

    public FieldActionsChained(int size)
    {
        System.Console.WriteLine(size);
    }

    public override string ToString() => _label ?? "";
}

public class FieldActionsNone
{
    // TYPE:field-none — caret on `_offset`, Alt+Enter, "Initialize field from constructor". EXPECT: a new constructor after the field:
    // `public FieldActionsNone(System.TimeSpan offset) { _offset = offset; }` on separate lines
    private readonly System.TimeSpan _offset;

    public System.TimeSpan Offset => _offset;
}

public class FieldActionsPrimary(string title)
{
    // TYPE:field-primary — caret on `_pages`, Alt+Enter, "Initialize field from constructor". EXPECT: `FieldActionsPrimary(string title,
    // int pages)` and `private readonly int _pages = pages;`
    private readonly int _pages;

    public string Describe() => $"{title}: {_pages}";
}

public class FieldActionsMany
{
    // TYPE:field-many — caret on `_first`, Alt+Enter: both "Initialize field from constructor" and "Initialize members from constructor".
    // EXPECT for the second: `public FieldActionsMany(string first, int second)` assigning both; `_third` (initialized) is left out.
    // NOT offered on `_third` (it has an initializer) or on `Shared` (static)
    private readonly string? _first;
    private readonly int _second;
    private readonly bool _third = true;
    private static readonly int Shared = 1;

    public string Describe() => $"{_first} {_second} {_third} {Shared}";
}
