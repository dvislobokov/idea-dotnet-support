namespace Playground.Lib;

/// <summary>
/// The other project of the Find Usages scenario (<c>Console/Editor/FindUsages.cs</c>): <see cref="Record"/> is called here and from Console,
/// so its usages fall into two projects of the solution.
/// </summary>
public static class UsageLog
{
    public static int Count { get; private set; }

    public static void Record(string what) => Count++;

    public static void RecordTwice(string what)
    {
        Record(what);
        Record(what + " again");
    }
}
