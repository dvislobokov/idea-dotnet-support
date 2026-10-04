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
