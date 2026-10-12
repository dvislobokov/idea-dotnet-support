using System.Text.RegularExpressions;

namespace Playground.Editor;

/// <summary>
/// Live check of the context actions on expressions (0.1.171, Rider's rows on Jellyfin № 30–34). Put the caret where a marker says,
/// Alt+Enter, choose the row, compare with EXPECT, undo (Ctrl+Z). Nothing here is called, the file only has to compile.
/// </summary>
public static partial class ExpressionActions
{
    public class ExprFolder { public List<int> Children { get; } = new(); }

    public class ExprUser { public string Name { get; set; } = ""; public int Age { get; set; } }

    public static int Twice(this int x) => x * 2;

    public static object Cast(object item)
    {
        // TYPE:expr-safe-cast — caret on `ExprFolder` of the cast below, Alt+Enter, "To safe cast". EXPECT: `(item as ExprFolder).Children`;
        // NOT offered on `(int)item` two lines below (a value type)
        var children = ((ExprFolder)item).Children;
        var number = (int)item;
        return children.Count + number;
    }

    // TYPE:expr-strings — caret in the pattern below, Alt+Enter. EXPECT: "To regular string" → "^(?!\\s)[\\w\\ ]+$", "To raw string" →
    // """^(?!\s)[\w\ ]+$"""; on "C:\\temp" "To verbatim string" → @"C:\temp"; NOT "To verbatim string" on "a\nb" (not exact)
    [GeneratedRegex(@"^(?!\s)[\w\ ]+$")]
    private static partial Regex Pattern();

    public static string Paths() => "C:\\temp" + "a\nb";

    public static List<int> Calls(List<ExprUser> users)
    {
        // TYPE:expr-static-call — caret on `Select`, Alt+Enter, "To static method invocation". EXPECT: `Enumerable.Select(users, user => user.Age)`;
        // then on `Select` again "To extension method invocation" → back to `users.Select(user => user.Age)`; on `Twice` → `ExpressionActions.Twice(1)`
        var ages = users.Select(user => user.Age).ToList();
        ages.Add(1.Twice());
        return ages;
    }

    public static void Statements(List<ExprUser> users)
    {
        // TYPE:expr-try-catch — caret on `Clear`, Alt+Enter, "Generate try-catch block". EXPECT: the statement in `try { }` and
        // `catch (Exception e) { Console.WriteLine(e); throw; }`, the caret on `Exception`; NOT offered on `var first = …` (used below)
        users.Clear();
        var first = users.FirstOrDefault();
        Console.WriteLine(first);

        // TYPE:expr-initializer — caret on `var` of the line below, Alt+Enter, "Convert initializer to assignment". EXPECT:
        // `var user = new ExprUser();`, `user.Name = "a";`, `user.Age = 1;` on lines of their own; NOT offered on `new List<int> { 1 }`
        var user = new ExprUser { Name = "a", Age = 1 };
        var list = new List<int> { 1 };
        Console.WriteLine(user.Name + list.Count);
    }
}
