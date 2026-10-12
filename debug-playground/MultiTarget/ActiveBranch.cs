// The native tree (CSHARP_PSI_MIGRATION.md, step 7) parses a file with the #if symbols of the framework chosen in the toolbar.
namespace MultiTarget;

// TYPE:active-branch — nothing to type. Settings | .NET | Language Server, Source of Features: "Structure, folding and breadcrumbs"
// = Built-in, OK; open this file and the Structure view (Alt+7); switch the framework in the toolbar: Debug | .NET 10.0, then .NET 9.0.
// EXPECT: .NET 10.0 -> Structure shows Net10Only with Describe and no Net9Only; .NET 9.0 or Default (the first framework) -> Net9Only
// and no Net10Only. The view follows the toolbar without reopening the file. Back to "Language server": the file is parsed again
// with the heuristic tree at once, also without reopening.
#if NET10_0
public static class Net10Only
{
    public static string Describe() => "the .NET 10 branch";
}
#else
public static class Net9Only
{
    public static string Describe() => "the .NET 9 branch";
}
#endif
