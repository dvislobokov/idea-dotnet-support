namespace Playground.Editor;

/// <summary>
/// Live check of the member actions of 0.1.173 (`NativeCSharpMemberActions.kt`), as Rider offers them on Alt+Enter. Put the caret where
/// the <c>// TYPE:name</c> marker says, press Alt+Enter, choose the action, compare with EXPECT, undo (Ctrl+Z). Context actions = Built-in.
/// </summary>
public class MemberActions
{
    // TYPE:member-backing-field — caret on `Title`, Alt+Enter, "To property with backing field". EXPECT: `private string _title = "";`
    // above, then `public string Title { get => _title; private set => _title = value; }` on lines. "To property with 'field' keyword":
    // `get => field; private set => field = value;` and `= "";` after the block. "To computed property": `public string Title => "";`
    public string Title { get; private set; } = "";

    // TYPE:member-auto — caret on `Count`, Alt+Enter, "To auto-property". EXPECT: `public int Count { get; set; } = 1;`, the field `_count`
    // gone. NOT offered on `Total` (its setter does more than write the field)
    private int _count = 1;

    public int Count
    {
        get => _count;
        set => _count = value;
    }

    private int _total;

    public int Total
    {
        get => _total;
        set => _total = value < 0 ? 0 : value;
    }

    // TYPE:member-encapsulate — caret on `_name`, Alt+Enter, "Encapsulate field". EXPECT: `public string Name => _name;` under the field.
    // On `Limit`: the field becomes `private int _limit;` and a property `public int Limit { get => _limit; set => _limit = value; }`
    private readonly string _name = "member";

    public int Limit;

    public string Describe() => $"{_name} {Limit} {Title} {Count} {Total}";
}

public interface IMemberRunner
{
    void Run();
}

public class MemberRunner : IMemberRunner
{
    // TYPE:member-explicit — caret on `Run`, Alt+Enter, "To explicit implementation". EXPECT: `void IMemberRunner.Run() { }`, no `public`;
    // then on it "To implicit implementation" brings `public void Run() { }` back
    public void Run() { }
}

public class FactoryOrder
{
    public int Id { get; }

    // TYPE:member-factory — caret on the constructor's name, Alt+Enter, "To factory method". EXPECT: the constructor `private`, under it
    // `public static FactoryOrder Create(int id) => new FactoryOrder(id);`, and `new FactoryOrder(7)` below becomes `FactoryOrder.Create(7)`
    public FactoryOrder(int id)
    {
        Id = id;
    }

    public static FactoryOrder Seven() => new FactoryOrder(7);
}
