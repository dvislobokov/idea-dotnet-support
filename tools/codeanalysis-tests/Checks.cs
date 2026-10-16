// Checks of DesignTimeCache over a fake disk: when an entry of the disk cache of design-time builds counts and when it does not.
namespace DotNetSupport.Helpers.CodeAnalysis.Tests;

internal sealed class FakeDisk : ICacheDisk
{
    public readonly Dictionary<string, (long Ticks, long Length)> Files = new(StringComparer.OrdinalIgnoreCase);
    /** Folder -> the relative paths under it (what a walk gives). */
    public readonly Dictionary<string, List<string>> Trees = new(StringComparer.OrdinalIgnoreCase);

    public (long Ticks, long Length) Stamp(string path) => Files.TryGetValue(path, out var s) ? s : (-1, -1);

    public bool Exists(string path) => Files.ContainsKey(path);

    public List<string>? Entries(string root, int depth, IReadOnlyCollection<string> skipped, int limit)
    {
        var entries = (Trees.TryGetValue(root, out var e) ? e : []).Where(x => !skipped.Any(s => DesignTimeCache.Under(Path.Combine(root, x), s))).ToList();
        entries.Sort(StringComparer.OrdinalIgnoreCase);
        return entries.Count > limit ? null : entries;
    }
}

public static class Checks
{
    private static int failures;
    private static readonly string Root = Path.GetPathRoot(Path.GetFullPath("."))!;
    private static string P(params string[] parts) => Path.Combine([Root, .. parts]);

    private static readonly DateTime Started = new(2026, 10, 16, 12, 0, 0, DateTimeKind.Utc);
    private static readonly long Old = Started.AddHours(-1).Ticks;

    public static int Main()
    {
        Run(nameof(NoEntryIsAMiss), NoEntryIsAMiss);
        Run(nameof(AnEntryJustMadeIsAHit), AnEntryJustMadeIsAHit);
        Run(nameof(AChangedImportIsAMiss), AChangedImportIsAMiss);
        Run(nameof(ANearerDirectoryBuildPropsIsAMiss), ANearerDirectoryBuildPropsIsAMiss);
        Run(nameof(ANewSourceIsAMiss), ANewSourceIsAMiss);
        Run(nameof(ANewLinkedSourceIsAMiss), ANewLinkedSourceIsAMiss);
        Run(nameof(AChangedXamlIsAMiss), AChangedXamlIsAMiss);
        Run(nameof(AGoneSourceOfObjIsAMiss), AGoneSourceOfObjIsAMiss);
        Run(nameof(AnotherEnvironmentOrFormatOrKeyIsAMiss), AnotherEnvironmentOrFormatOrKeyIsAMiss);
        Run(nameof(AnInputWrittenWhileMsBuildRanIsNotCached), AnInputWrittenWhileMsBuildRanIsNotCached);
        Run(nameof(AFileAddedWhileMsBuildRanIsNotCached), AFileAddedWhileMsBuildRanIsNotCached);
        Run(nameof(AnOutputOfTheBuildInTheLogIsOnlyRequiredToExist), AnOutputOfTheBuildInTheLogIsOnlyRequiredToExist);
        Run(nameof(ImportsOfTheZip), ImportsOfTheZip);
        Run(nameof(TheOuterRootOfALinkedFile), TheOuterRootOfALinkedFile);
        Run(nameof(TheFilesOfACommandLine), TheFilesOfACommandLine);
        Console.WriteLine(failures == 0 ? "all checks passed" : $"{failures} check(s) failed");
        return failures == 0 ? 0 : 1;
    }

    private static void Run(string name, Action check)
    {
        try { check(); Console.WriteLine($"ok   {name}"); }
        catch (Exception e) { failures++; Console.WriteLine($"FAIL {name}: {e.Message}"); }
    }

    private static void Expect(bool condition, string what) { if (!condition) throw new Exception(what); }

    private static void Miss(string? why, string containing) => Expect(why != null && why.Contains(containing, StringComparison.OrdinalIgnoreCase), $"expected a miss with `{containing}`, got `{why ?? "a hit"}`");

    // ---- a project App with a linked ../Shared/S.cs, an import Directory.Build.props, a XAML file and an obj/ source

    private static readonly string Project = P("repo", "App", "App.csproj");
    private static readonly string Props = P("repo", "Directory.Build.props");
    private static readonly string Assets = P("repo", "App", "obj", "project.assets.json");
    private static readonly string Xaml = P("repo", "App", "MainWindow.xaml");
    private static readonly string ObjSource = P("repo", "App", "obj", "Debug", "App.GlobalUsings.g.cs");
    private static readonly string Linked = P("repo", "Shared", "S.cs");
    private static readonly List<string> Skipped = [P("repo", "App", "obj"), P("repo", "App", "bin", "Debug")];

    private static FakeDisk Disk()
    {
        var disk = new FakeDisk();
        foreach (var file in (string[])[Project, Props, Assets, Xaml, ObjSource, Linked, P("repo", "App", "A.cs")]) disk.Files[file] = (Old, 100);
        disk.Trees[P("repo", "App")] = ["A.cs", "App.csproj", "MainWindow.xaml"];
        disk.Trees[P("repo", "Shared")] = ["S.cs"];
        return disk;
    }

    private static DesignTime.Result Result() => new(
        [$"/out:{P("repo", "App", "obj", "Debug", "App.dll")}", "/noconfig", P("repo", "App", "A.cs"), Linked, ObjSource],
        "net10.0", new Dictionary<string, (string, string?)>(), [Assets], true, P("repo", "App", "obj"), true);

    private static (DesignTimeCache.Entry? Entry, string? Why) Make(FakeDisk disk, FakeDisk? before = null, List<string>? imports = null)
    {
        var sources = DesignTimeCache.CommandLineFiles(Result().CommandLine, P("repo", "App"), globbedOnly: true).ToList();
        var earlier = DesignTimeCache.Take(Project, [], [], [], before ?? disk);
        var after = DesignTimeCache.Take(Project, sources, Skipped, [], disk);
        return DesignTimeCache.Make("key", "env", Result(), Project, imports ?? [Props, Project], earlier, after, Started, Skipped, disk);
    }

    private static DesignTimeCache.Entry Made(FakeDisk disk)
    {
        var (entry, why) = Make(disk);
        Expect(entry != null, $"the build should be cached, not: {why}");
        return entry!;
    }

    private static void NoEntryIsAMiss() => Miss(DesignTimeCache.Stale(null, "key", "env", Disk()), "no entry");

    private static void AnEntryJustMadeIsAHit()
    {
        var disk = Disk();
        var entry = Made(disk);
        Expect(DesignTimeCache.Stale(entry, "key", "env", disk) == null, "a hit expected");
        Expect(entry.Trees.Any(t => t.Root == P("repo", "Shared")), "the folder of the linked file is watched");
        Expect(entry.Files.Any(f => f.Path == P("repo", "App", "Directory.Build.props") && f.Ticks == -1), "a missing nearer Directory.Build.props is recorded as missing");
        Expect(DesignTimeCache.ResultOf(entry).CommandLine.SequenceEqual(Result().CommandLine), "the command line comes back as it was");
    }

    private static void AChangedImportIsAMiss()
    {
        var disk = Disk();
        var entry = Made(disk);
        disk.Files[Props] = (Old + 1, 100);
        Miss(DesignTimeCache.Stale(entry, "key", "env", disk), "Directory.Build.props changed");
        disk.Files[Props] = (Old, 101);
        Miss(DesignTimeCache.Stale(entry, "key", "env", disk), "Directory.Build.props changed");
        disk.Files.Remove(Props);
        Miss(DesignTimeCache.Stale(entry, "key", "env", disk), "is gone");
    }

    private static void ANearerDirectoryBuildPropsIsAMiss()
    {
        var disk = Disk();
        var entry = Made(disk);
        disk.Files[P("repo", "App", "Directory.Build.props")] = (Old, 10);
        Miss(DesignTimeCache.Stale(entry, "key", "env", disk), "appeared");
    }

    private static void ANewSourceIsAMiss()
    {
        var disk = Disk();
        var entry = Made(disk);
        disk.Trees[P("repo", "App")].Add("B.cs");
        Miss(DesignTimeCache.Stale(entry, "key", "env", disk), "the files under");
    }

    private static void ANewLinkedSourceIsAMiss()
    {
        var disk = Disk();
        var entry = Made(disk);
        disk.Trees[P("repo", "Shared")].Add("T.cs");
        Miss(DesignTimeCache.Stale(entry, "key", "env", disk), "Shared");
    }

    private static void AChangedXamlIsAMiss()
    {
        var disk = Disk();
        var entry = Made(disk);
        disk.Files[Xaml] = (Old + 5, 100);
        Miss(DesignTimeCache.Stale(entry, "key", "env", disk), "MainWindow.xaml changed");
    }

    private static void AGoneSourceOfObjIsAMiss()
    {
        var disk = Disk();
        var entry = Made(disk);
        disk.Files.Remove(ObjSource);
        Miss(DesignTimeCache.Stale(entry, "key", "env", disk), "GlobalUsings.g.cs is gone");
    }

    private static void AnotherEnvironmentOrFormatOrKeyIsAMiss()
    {
        var disk = Disk();
        var entry = Made(disk);
        Miss(DesignTimeCache.Stale(entry, "key", "env2", disk), "environment");
        Miss(DesignTimeCache.Stale(entry, "key2", "env", disk), "another key");
        entry.Format = DesignTimeCache.Format - 1;
        Miss(DesignTimeCache.Stale(entry, "key", "env", disk), "format");
    }

    private static void AnInputWrittenWhileMsBuildRanIsNotCached()
    {
        var disk = Disk();
        disk.Files[Props] = (Started.AddSeconds(1).Ticks, 100);
        var (entry, why) = Make(disk);
        Expect(entry == null, "not cached");
        Miss(why, "written while MSBuild ran");
    }

    private static void AFileAddedWhileMsBuildRanIsNotCached()
    {
        var before = Disk();
        var disk = Disk();
        disk.Trees[P("repo", "App")].Add("B.cs");
        var (entry, why) = Make(disk, before);
        Expect(entry == null, "not cached");
        Miss(why, "changed while MSBuild ran");
    }

    private static void AnOutputOfTheBuildInTheLogIsOnlyRequiredToExist()
    {
        var disk = Disk();
        var config = P("repo", "App", "obj", "Debug", "App.GeneratedMSBuildEditorConfig.editorconfig");
        disk.Files[config] = (Started.AddSeconds(1).Ticks, 10);
        var (entry, why) = Make(disk, imports: [Props, Project, config]);
        Expect(entry != null, $"cached: {why}");
        Expect(entry!.Files.All(f => f.Path != config) && entry.Existing.Contains(config), "the output is required to exist, not stamped");
        disk.Files[config] = (Started.AddSeconds(30).Ticks, 12);
        Expect(DesignTimeCache.Stale(entry, "key", "env", disk) == null, "a later build writing it again changes nothing");
    }

    private static void ImportsOfTheZip()
    {
        if (!OperatingSystem.IsWindows()) return;
        var imports = DesignTimeCache.ImportsOf([@"C\Users\me\repo\Directory.Build.props", @"C\Program Files\dotnet\sdk\10.0.401\Sdks\Microsoft.NET.Sdk\Sdk\Sdk.props"]);
        Expect(imports[0] == @"C:\Users\me\repo\Directory.Build.props", imports[0]);
        Expect(imports[1] == @"C:\Program Files\dotnet\sdk\10.0.401\Sdks\Microsoft.NET.Sdk\Sdk\Sdk.props", imports[1]);
    }

    private static void TheOuterRootOfALinkedFile()
    {
        Expect(DesignTimeCache.OuterRoot(P("repo", "Shared", "Deep", "S.cs"), P("repo", "App")) == P("repo", "Shared"), "the top folder below the common one");
        Expect(DesignTimeCache.OuterRoot(P("repo", "S.cs"), P("repo", "App")) == P("repo"), "a file in a folder above: that folder");
    }

    private static void TheFilesOfACommandLine()
    {
        var directory = P("repo", "App");
        List<string> commandLine = ["/noconfig", "/reference:Alias=" + P("refs", "A.dll"), "/reference:" + P("refs", "B.dll"), "/analyzer:" + P("a", "G.dll"),
            "/additionalfile:Pages/Index.razor", "/analyzerconfig:" + P("repo", ".editorconfig"), "Program.cs", "/out:obj/App.dll"];
        var all = DesignTimeCache.CommandLineFiles(commandLine, directory).ToList();
        Expect(all.Contains(P("refs", "A.dll")) && all.Contains(P("refs", "B.dll")) && all.Contains(P("a", "G.dll")), "references and analyzers, the alias aside");
        Expect(all.Contains(P("repo", "App", "Pages", "Index.razor")) && all.Contains(P("repo", "App", "Program.cs")), "sources and additional files, relative to the project");
        Expect(!all.Any(p => p.EndsWith("App.dll")), "not the output");
        var globbed = DesignTimeCache.CommandLineFiles(commandLine, directory, globbedOnly: true).ToList();
        Expect(globbed.Count == 2, "only the source and the additional file: " + string.Join(", ", globbed));
    }
}
