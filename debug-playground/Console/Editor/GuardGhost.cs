namespace Playground.Editor;

/// <summary>
/// Live check of the guard rules of the gray text (0.1.163, `NativeCSharpGuardGhost.kt`): what an empty guard `if` does, what an empty
/// `catch` does, the type of a parameter from its first letters. Rider shows the same lines from its local network; here they are rules.
/// Lines to type on are marked <c>// TYPE:name</c>: put the caret on the empty line under the marker (or where the marker says), type
/// what the comment says, compare with EXPECT, undo (Ctrl+Z). Nothing here is called, the file only has to compile.
/// </summary>
public class GuardGhost
{
    public interface ILogger
    {
        void LogError(Exception exception, string message);
    }

    public enum OperationType { Insert, Update, Delete }

    public class Member
    {
        public string Name { get; set; } = "";
    }

    private readonly ILogger _logger = null!;

    public void Save(Member member, string name)
    {
        // TYPE:guard-throw — on the empty line inside the block below. EXPECT: gray `throw new ArgumentNullException(nameof(member));`
        // (a parameter checked by the first statement of the body)
        if (member == null)
        {

        }

        // TYPE:guard-throw-empty — on the empty line below. EXPECT: gray `throw new ArgumentNullException(nameof(name));` (a guard
        // behind another guard is still first)
        if (string.IsNullOrEmpty(name))
        {

        }

        var count = 0;
        // TYPE:guard-return — on the empty line below (the guard is no longer first, the method is void). EXPECT: gray `return;`
        if (member.Name == null)
        {

        }
        Console.WriteLine(count);
    }

    public bool Check(Member member)
    {
        var ok = member.Name.Length > 0;
        // TYPE:guard-return-false — on the empty line below. EXPECT: gray `return false;` (a bool method; `!ok` names nothing to throw for)
        if (!ok)
        {

        }
        return ok;
    }

    public Member? Find(List<Member> members)
    {
        foreach (var member in members)
        {
            // TYPE:guard-continue — on the empty line below. EXPECT: gray `continue;` (inside a loop)
            if (member == null)
            {

            }
        }
        var first = members.FirstOrDefault();
        // TYPE:guard-return-null — on the empty line below. EXPECT: gray `return null;` (the result is nullable)
        if (first == null)
        {

        }
        return first;
    }

    public int Count(Member member)
    {
        var count = 0;
        // TYPE:guard-silent — on the empty line below. EXPECT: no gray text (an int result: `return 0;` or a throw are as likely)
        if (member == null)
        {

        }
        return count;
    }

    public void Load(Member member)
    {
        // TYPE:guard-catch-log — on the empty line in the `catch` below. EXPECT: gray `_logger.LogError(e, "Load failed");` (the type has
        // an ILogger field and the clause names its exception)
        try
        {
            Save(member, member.Name);
        }
        catch (Exception e)
        {

        }

        // TYPE:guard-catch-throw — on the empty line in the `catch` below. EXPECT: gray `throw;` (no exception variable to log)
        try
        {
            Save(member, member.Name);
        }
        catch
        {

        }
    }

    public void Rename(Member member, string name)
    {
        ArgumentException.ThrowIfNullOrEmpty(name);
        // TYPE:guard-after-check — on the empty line below type `member.null` + Tab. EXPECT: `if (member == null) { | }` and, without another
        // key, gray `throw new ArgumentNullException(nameof(member));` (the check above is an argument check too; 0.1.169, Jellyfin № 4, 5)

    }

    // TYPE:guard-parameter-type — put the caret between the parentheses of `Draft()` below and type `OperationT`. EXPECT: gray
    // `ype operationType`; with `Oper` typed instead — nothing (`OperatingSystem` of System begins so too); with `TimeSp` — `an timeSpan`
    private static void Draft() { }
}
