using Playground.Lib;

namespace Playground.Editor;

/// <summary>
/// Live check of Find Usages with grouping, as in Rider (ROADMAP: «До уровня Rider и .NET Framework», stage 2). Nothing is typed here: put the
/// caret where a <c>// TYPE:name</c> comment says, press Alt+F7 and compare the Find tool window with EXPECT. Wait for the widget
/// «Roslyn: DebugPlayground.sln» first: the usages come from the server. The groups are switched by the toolbar of the window (the gear /
/// «View Options»: Group by Usage Type, Module, Directory, File Structure; Merge Usages on the Same Line) — the toggles of the IDE, the same as in
/// other languages. The file only has to compile.
/// </summary>
[UsageNote(nameof(UsageSample.Counter))]
public class UsageSample
{
    // TYPE:find-usages-field — caret on `Counter` below, Alt+F7. EXPECT, 13 usages, with Group by Usage Type on: «Read access» 3 (two in Read,
    // one of them the qualifier of `Counter.ToString`, and Peek's `in`), «Write access» 7 (the constructor, `= 5`, `+= 2`, `++`, `--`, `ref`,
    // `out`), «Usage in nameof» 2 (in Name and in the attribute above the class), «Declaration» 1 (this line); no «Unclassified». With Group by
    // File Structure on: UsageSample → UsageSample() / Read() / Write() / ByRef() / Name() / Counter; the nameof of the attribute right under
    // UsageSample. Merge Usages on the Same Line on: the two reads in Read() become one row
    public int Counter;

    public string Title { get; set; } = "";

    public UsageSample()
    {
        Counter = 0;
        UsageLog.Record("created");
    }

    public int Read() => Counter + 1 + Counter.ToString().Length;

    public void Write()
    {
        Counter = 5;
        Counter += 2;
        Counter++;
        --Counter;
        Title = Title + "!";
    }

    public void ByRef()
    {
        Bump(ref Counter);
        Reset(out Counter);
        Peek(in Counter);
        UsageLog.Record(nameof(ByRef));
    }

    public string Name() => nameof(Counter);

    private static void Bump(ref int value) => value++;
    private static void Reset(out int value) => value = 0;
    private static void Peek(in int value) => Console.WriteLine(value);
}

// TYPE:find-usages-type — caret on `UsageSample` in the header of the class above, Alt+F7. EXPECT, 9 usages, with Group by Usage Type:
// «Declaration» 2 (the class and its constructor), «Usage in base type list» (DerivedSample), «New instance creation», «Usage in typeof»,
// «Type check (is / as)» (`as UsageSample`), «Usage in declaration type» (the parameter of Make), «Usage in type argument» (List<UsageSample>),
// «Usage in nameof» (the attribute of UsageSample)
public class DerivedSample : UsageSample
{
    private readonly List<UsageSample> _samples = [];

    public object Make(UsageSample other)
    {
        var made = new UsageSample();
        _samples.Add(made);
        Console.WriteLine(typeof(UsageSample).Name);
        return other is DerivedSample ? made : other as UsageSample ?? made;
    }

    // TYPE:find-usages-method — caret on `Record` below, Alt+F7. EXPECT, 7 usages: «Invocation» 5, «Declaration» 1 (in Lib), «Usage in
    // documentation» 1 (the `<see cref>` of UsageLog); with Group by Module on (Rider: by project) the usages split into the .NET projects
    // Console (3) and Lib (4); with Group by File Structure on: UsageSample → UsageSample() and ByRef(), DerivedSample → Log(); UsageLog →
    // Record(string what) and RecordTwice(string what) with two rows (two lines, so Merge Usages on the Same Line keeps them apart)
    public void Log() => UsageLog.Record("derived");
}

[AttributeUsage(AttributeTargets.Class)]
public sealed class UsageNoteAttribute(string member) : Attribute
{
    public string Member { get; } = member;
}

// TYPE:find-usages-attribute — caret on `UsageNoteAttribute` above, Alt+F7. EXPECT: «Usage in attribute» 1 (the `[UsageNote(...)]` of
// UsageSample) and «Declaration» (the server gives the name twice: the class and its primary constructor)

// Kinds of usages on the plugin's own tree (0.1.46): Settings | Tools | .NET | Language Server → Source of Features → «Kinds of usages» =
// Built-in (the default), then the markers below. With «Language server» the same usages are grouped by the tokens: the differences are
// named in EXPECT ("tokens: …"); everything else is the same with either choice.
public class UsageTally
{
    // TYPE:find-usages-native-field — caret on `Total` below, Alt+F7. EXPECT, 5 usages, with Group by Usage Type on: «Declaration» 1 (this
    // line), «Write access» 2 (`(Total, var count) = other` in Tuples — tokens: Read access —, and `Total = 3` inside `Inner = { … }`),
    // «Read access» 2 (Deconstruct, the property pattern `{ Total: > 0 }`); no «Unclassified». `t.Total` in the query of Totals is not in
    // the answer of roslyn-language-server 5.12 (robot, 2026-10-04), with either choice
    public int Total;

    public List<int> Lines { get; } = [];

    public UsageTally? Inner { get; set; }

    public void Deconstruct(out int total, out int count) => (total, count) = (Total, Lines.Count);

    public void Tuples(UsageTally other)
    {
        (Total, var count) = other;
        Console.WriteLine(count);
    }

    // TYPE:find-usages-native-members — caret on `Lines` above, Alt+F7. EXPECT: «Read access» 2 (Deconstruct and `Lines = { 1, 2 }` below:
    // a nested initializer reads the list and adds to it — tokens: Write access), «Declaration» 1. Then caret on `Inner` above: «Read access»
    // 1 (`Inner = { Total = 3 }` — tokens: Write access), «Declaration» 1
    public UsageTally Fill() => new() { Lines = { 1, 2 }, Inner = { Total = 3 } };

    // TYPE:find-usages-native-type — caret on `UsageTally` in the header of the class, Alt+F7. EXPECT, with Group by Usage Type on:
    // «Declaration» 1, «Usage in declaration type» 4 (`UsageTally? Inner`, the parameter of Tuples, the return type of Fill, the `out`
    // parameter of Load — tokens: Write access for the last), «Usage in type argument» 1 (`IEnumerable<UsageTally>`), «Type check (is / as)» 1
    // (`UsageTally { Total: > 0 }` in the switch — tokens: Read access)
    public static int Kind(object item) => item switch { UsageTally { Total: > 0 } => 1, _ => 0 };

    public static bool Load(out UsageTally made)
    {
        made = new();
        return true;
    }

    public static IEnumerable<int> Totals(IEnumerable<UsageTally> tallies) => from t in tallies let total = t.Total select total;
}
