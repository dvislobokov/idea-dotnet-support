// The disk cache of the design-time builds of CodeAnalysisHelper (Program.cs, DesignTime): what `dotnet msbuild -t:Compile` said of a
// project, kept with everything that build read, so a project whose inputs have not changed is loaded again (a new helper after the idle
// stop, a new session of the IDE) without MSBuild — 1–3 s a project, one after another, was most of the time to the first generated file.
//
// Correctness first: an entry counts only while every file the evaluation imported (the zip of the binary log lists them, the SDK's and
// the packages' included), the files that would be imported if they appeared (Directory.Build.*, global.json, .editorconfig up the tree),
// the names of the files under the project (its globs), the installed SDKs, packs and workload manifests and the environment are as they
// were. Anything else — a different key, a new format, a file the build wrote while it ran — is a miss, and MSBuild runs.
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace DotNetSupport.Helpers.CodeAnalysis;

/** What the cache reads of the disk: the real one in the helper, a fake in the tests (tools/codeanalysis-tests). */
internal interface ICacheDisk
{
    /** The last write (UTC ticks) and the length of a file; ticks -1 when there is no such file. */
    (long Ticks, long Length) Stamp(string path);

    bool Exists(string path);

    /**
     * The entries (files and folders) under [root] as paths relative to it, sorted; [depth] 0: the entries of [root] only, -1: all.
     * Folders named bin, obj, node_modules or starting with a dot are skipped, as the default globs of MSBuild do, and so is any of
     * [skipped]. Null when there are more than [limit]: too big to watch.
     */
    List<string>? Entries(string root, int depth, IReadOnlyCollection<string> skipped, int limit);
}

internal sealed class RealDisk : ICacheDisk
{
    public static readonly RealDisk Instance = new();

    public (long Ticks, long Length) Stamp(string path)
    {
        var info = new FileInfo(path);
        return info.Exists ? (info.LastWriteTimeUtc.Ticks, info.Length) : (-1, -1);
    }

    public bool Exists(string path) => File.Exists(path) || Directory.Exists(path);

    public List<string>? Entries(string root, int depth, IReadOnlyCollection<string> skipped, int limit)
    {
        var result = new List<string>();
        if (!Directory.Exists(root)) return result;
        var queue = new Queue<(string Path, int Level)>();
        queue.Enqueue((root, 0));
        while (queue.Count > 0)
        {
            var (folder, level) = queue.Dequeue();
            IEnumerable<FileSystemInfo> entries;
            try { entries = new DirectoryInfo(folder).EnumerateFileSystemInfos(); }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException) { continue; }
            foreach (var entry in entries)
            {
                var isFolder = (entry.Attributes & FileAttributes.Directory) != 0;
                if (isFolder && DesignTimeCache.SkippedFolder(entry.Name, entry.FullName, skipped)) continue;
                result.Add(Path.GetRelativePath(root, entry.FullName) + (isFolder ? Path.DirectorySeparatorChar : ""));
                if (result.Count > limit) return null;
                // a link to a folder elsewhere is an entry, not a cone to walk (a loop of links would never end)
                if (isFolder && (depth < 0 || level < depth) && (entry.Attributes & FileAttributes.ReparsePoint) == 0) queue.Enqueue((entry.FullName, level + 1));
            }
        }
        result.Sort(StringComparer.OrdinalIgnoreCase);
        return result;
    }
}

internal static class DesignTimeCache
{
    /** Raise it with any change of [Entry] or of what [Fingerprint] records: entries of another format are misses. */
    public const int Format = 1;

    /** The most entries a tree may have: a project in the root of a huge folder is not cached rather than walked at every load. */
    public const int TreeLimit = 100_000;

    /** What would be imported (or read by `dotnet`) if it appeared in the folder of the project or above it. */
    public static readonly string[] ProbedNames =
        ["Directory.Build.props", "Directory.Build.targets", "Directory.Packages.props", "Directory.Build.rsp", "global.json", ".editorconfig", ".globalconfig"];

    /** Files the targets of the build turn into C# of `obj/` (XAML, protobuf, resources): skipping MSBuild skips those targets. */
    public static readonly string[] BuildInputExtensions = [".xaml", ".proto", ".resx"];

    public sealed class Entry
    {
        public int Format { get; set; }
        public string Key { get; set; } = "";
        public string Environment { get; set; } = "";
        public DateTime Written { get; set; }
        // the result of the design-time build (DesignTime.Result)
        public List<string> CommandLine { get; set; } = [];
        public string? Framework { get; set; }
        public List<Output> ProjectOutputs { get; set; } = [];
        public List<string> Inputs { get; set; } = [];
        public bool LiveAnalyzers { get; set; }
        public string? IntermediateDirectory { get; set; }
        public bool DefaultFramework { get; set; }
        // what it read
        public List<FileStamp> Files { get; set; } = [];
        public List<TreeStamp> Trees { get; set; } = [];
        public List<string> Existing { get; set; } = [];
    }

    public sealed class Output
    {
        public string Path { get; set; } = "";
        public string Project { get; set; } = "";
        public string? Framework { get; set; }
    }

    /** A file as it was: its last write and length, -1 / -1 when it was not there (and must not appear). */
    public sealed class FileStamp
    {
        public string Path { get; set; } = "";
        public long Ticks { get; set; }
        public long Length { get; set; }
    }

    /** The names under a folder, as a hash: a file added, removed or renamed there changes what the globs of the project give. */
    public sealed class TreeStamp
    {
        public string Root { get; set; } = "";
        public int Depth { get; set; }
        public List<string> Skipped { get; set; } = [];
        public string Hash { get; set; } = "";
    }

    // ---- the key and the file

    public static string Key(Target target) =>
        $"{Format}|{target.ProjectPath.ToLowerInvariant()}|{target.Configuration}|{target.Framework}|{System.Environment.ProcessPath}";

    public static string FileOf(Target target) =>
        Path.Combine(target.CacheDirectory, $"{Path.GetFileNameWithoutExtension(target.ProjectPath)}-{Hash([Key(target)])[..16]}.json");

    /**
     * The environment `dotnet msbuild` gets: every variable is a property to MSBuild, so another value may be another build. Hashed,
     * sorted by name.
     */
    public static string EnvironmentHash(System.Collections.IDictionary variables) =>
        Hash(variables.Keys.Cast<object>().Select(k => k.ToString()!).OrderBy(k => k, StringComparer.Ordinal).Select(k => $"{k}={variables[k]}"));

    public static string Hash(IEnumerable<string> lines)
    {
        using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (var line in lines)
        {
            sha.AppendData(Encoding.UTF8.GetBytes(line));
            sha.AppendData("\n"u8);
        }
        return Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant();
    }

    public static bool SkippedFolder(string name, string fullPath, IReadOnlyCollection<string> skipped) =>
        name.StartsWith('.') || name.Equals("bin", StringComparison.OrdinalIgnoreCase) || name.Equals("obj", StringComparison.OrdinalIgnoreCase)
        || name.Equals("node_modules", StringComparison.OrdinalIgnoreCase) || skipped.Any(s => Same(s, fullPath));

    private static bool Same(string a, string b) =>
        string.Equals(a.TrimEnd('\\', '/'), b.TrimEnd('\\', '/'), OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);

    // ---- the rule

    /**
     * Null when [entry] still says what a design-time build of [key] would say now; otherwise why not, for the journal. Pure over [disk]:
     * the order of the checks is the order of their cost.
     */
    public static string? Stale(Entry? entry, string key, string environment, ICacheDisk disk)
    {
        if (entry == null) return "no entry";
        if (entry.Format != Format) return $"format {entry.Format}, not {Format}";
        if (entry.Key != key) return "another key";
        if (entry.Environment != environment) return "the environment changed";
        foreach (var file in entry.Files)
        {
            var now = disk.Stamp(file.Path);
            if (now.Ticks != file.Ticks || now.Length != file.Length)
                return file.Ticks < 0 ? $"{file.Path} appeared" : now.Ticks < 0 ? $"{file.Path} is gone" : $"{file.Path} changed";
        }
        foreach (var path in entry.Existing)
            if (!disk.Exists(path)) return $"{path} is gone";
        foreach (var tree in entry.Trees)
        {
            var entries = disk.Entries(tree.Root, tree.Depth, tree.Skipped, TreeLimit);
            if (entries == null || Hash(entries) != tree.Hash) return $"the files under {tree.Root} changed";
        }
        return null;
    }

    // ---- what a build read

    /** Every folder from [directory] up to the root of its volume. */
    public static IEnumerable<string> Ancestors(string directory)
    {
        for (var folder = directory; !string.IsNullOrEmpty(folder); folder = Path.GetDirectoryName(folder)) yield return folder;
    }

    public static bool Under(string path, string folder) =>
        path.StartsWith(folder.TrimEnd('\\', '/') + Path.DirectorySeparatorChar, OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);

    /**
     * The folder whose tree holds [file] of a project in [projectDirectory] that is not under it (a linked `..\Shared\*.cs`): the top
     * folder below what the two have in common, so a file added next to it or in a new folder beside it is seen.
     */
    public static string OuterRoot(string file, string projectDirectory)
    {
        var folder = Path.GetDirectoryName(file)!;
        var common = Ancestors(projectDirectory).FirstOrDefault(a => Under(folder, a) || Same(a, folder));
        if (common == null) return Ancestors(folder).Last();
        if (Same(common, folder)) return folder;
        var top = folder;
        while (!Same(Path.GetDirectoryName(top)!, common)) top = Path.GetDirectoryName(top)!;
        return top;
    }

    /**
     * Paths of the imports in the zip `-bl:…;ProjectImports=ZipFile` writes: entry names are the full paths without the colon of the
     * drive (`C\Users\…`) on Windows, without the leading `/` elsewhere.
     */
    public static List<string> ImportsOf(IEnumerable<string> entryNames) => entryNames.Select(name =>
    {
        var path = name.Replace('/', Path.DirectorySeparatorChar);
        if (OperatingSystem.IsWindows()) return path.Length > 1 && char.IsLetter(path[0]) && path[1] == '\\' ? $"{path[0]}:{path[1..]}" : path;
        return "/" + path.TrimStart('/');
    }).ToList();

    public static List<string> ImportsOf(string zip)
    {
        using var archive = ZipFile.OpenRead(zip);
        return ImportsOf(archive.Entries.Select(e => e.FullName));
    }

    /** The trees and the stamps of the build-generated inputs a design-time build of [projectPath] depends on, before or after it ran. */
    public sealed record Snapshot(List<(TreeStamp Tree, List<string>? Entries)> Trees, List<FileStamp> BuildInputs);

    /**
     * What a design-time build depends on besides its imports: the names under the folder of the project and under the outer folders of
     * its linked [sources]; the stamps of its XAML / protobuf / resources; the installed SDKs, packs and workload manifests.
     * [skipped]: the folders the build writes (its obj/ and bin/ when they are not named so).
     */
    public static Snapshot Take(string projectPath, IEnumerable<string> sources, IReadOnlyCollection<string> skipped, IReadOnlyCollection<string> immutable, ICacheDisk disk)
    {
        var directory = Path.GetDirectoryName(projectPath)!;
        var trees = new List<(TreeStamp, List<string>?)>();
        void Tree(string root, int depth, IReadOnlyCollection<string> skip)
        {
            if (trees.Any(t => Same(t.Item1.Root, root))) return;
            var entries = disk.Entries(root, depth, skip, TreeLimit);
            trees.Add((new TreeStamp { Root = root, Depth = depth, Skipped = skip.ToList(), Hash = entries == null ? "" : Hash(entries) }, entries));
        }
        Tree(directory, -1, skipped);
        foreach (var source in sources)
        {
            if (Under(source, directory) || skipped.Any(s => Under(source, s)) || immutable.Any(i => Under(source, i))) continue;
            Tree(OuterRoot(source, directory), -1, skipped);
        }
        // `dotnet` picks the SDK by global.json among the installed ones; the reference packs and the workloads are folders per version
        var dotnet = System.Environment.ProcessPath is { } host && Path.GetFileNameWithoutExtension(host).Equals("dotnet", StringComparison.OrdinalIgnoreCase) ? Path.GetDirectoryName(host) : null;
        if (dotnet != null)
        {
            Tree(Path.Combine(dotnet, "sdk"), 0, []);
            Tree(Path.Combine(dotnet, "packs"), 1, []);
            Tree(Path.Combine(dotnet, "sdk-manifests"), 2, []);
        }
        var inputs = new List<FileStamp>();
        foreach (var (tree, entries) in trees)
        {
            if (tree.Depth >= 0 || entries == null) continue;
            foreach (var relative in entries.Where(e => BuildInputExtensions.Any(x => e.EndsWith(x, StringComparison.OrdinalIgnoreCase))))
                inputs.Add(StampOf(Path.Combine(tree.Root, relative), disk));
        }
        return new Snapshot(trees, inputs);
    }

    public static FileStamp StampOf(string path, ICacheDisk disk)
    {
        var (ticks, length) = disk.Stamp(path);
        return new FileStamp { Path = path, Ticks = ticks, Length = length };
    }

    /**
     * The entry of a design-time build, or why there is none: [before] was taken before MSBuild started (at [started]), [after] once it
     * ended; a tree that changed meanwhile, a tree too big or an input written while MSBuild ran (an editor, a checkout) leaves the
     * build uncached — it may have seen either state.
     */
    public static (Entry? Entry, string? Why) Make(string key, string environment, DesignTime.Result result, string projectPath, List<string> imports,
        Snapshot before, Snapshot after, DateTime started, IReadOnlyCollection<string> skipped, ICacheDisk disk)
    {
        if (after.Trees.Any(t => t.Entries == null)) return (null, $"more than {TreeLimit} files under {after.Trees.First(t => t.Entries == null).Tree.Root}");
        foreach (var (tree, entries) in after.Trees)
        {
            var earlier = before.Trees.FirstOrDefault(t => Same(t.Tree.Root, tree.Root));
            if (earlier.Tree == null) continue;   // known only after the build (the outer folder of a linked file)
            var filtered = earlier.Entries?.Where(e => !skipped.Any(s => Under(Path.Combine(tree.Root, e), s) || Same(Path.Combine(tree.Root, e), s))).ToList();
            if (filtered == null || Hash(filtered) != tree.Hash) return (null, $"the files under {tree.Root} changed while MSBuild ran");
        }
        var directory = Path.GetDirectoryName(projectPath)!;
        var files = new Dictionary<string, FileStamp>(OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal);
        foreach (var path in imports.Concat(result.Inputs).Append(projectPath).Append(projectPath + ".user")) files.TryAdd(path, StampOf(path, disk));
        foreach (var folder in Ancestors(directory))
            foreach (var name in ProbedNames)
                files.TryAdd(Path.Combine(folder, name), StampOf(Path.Combine(folder, name), disk));
        foreach (var input in after.BuildInputs) files.TryAdd(input.Path, input);
        // the zip also holds what the targets embed in the log, outputs of this very build among them (the GeneratedMSBuildEditorConfig
        // of obj/): written under the output folders while it ran, they are its results, not its inputs — they only have to be there
        var outputs = files.Values.Where(f => f.Ticks >= started.Ticks && skipped.Any(s => Under(f.Path, s))).Select(f => f.Path).ToList();
        foreach (var output in outputs) files.Remove(output);
        var margin = started.AddSeconds(-2).Ticks;
        var written = files.Values.FirstOrDefault(f => f.Ticks >= margin);
        if (written != null) return (null, $"{written.Path} was written while MSBuild ran");
        // the inputs of the trees both snapshots walked (the outer folder of a linked file is known only after the build)
        var earlierInputs = before.BuildInputs.ToDictionary(f => f.Path, f => f, files.Comparer);
        var walkedBefore = before.Trees.Where(t => t.Tree.Depth < 0).Select(t => t.Tree.Root).ToList();
        var changed = after.BuildInputs.Where(f => walkedBefore.Any(r => Under(f.Path, r)))
            .FirstOrDefault(f => !earlierInputs.TryGetValue(f.Path, out var e) || e.Ticks != f.Ticks || e.Length != f.Length);
        if (changed != null) return (null, $"{changed.Path} changed while MSBuild ran");
        var entry = new Entry
        {
            Format = Format, Key = key, Environment = environment, Written = DateTime.UtcNow,
            CommandLine = result.CommandLine, Framework = result.Framework, Inputs = result.Inputs, LiveAnalyzers = result.LiveAnalyzers,
            IntermediateDirectory = result.IntermediateDirectory, DefaultFramework = result.DefaultFramework,
            ProjectOutputs = result.ProjectOutputs.Select(o => new Output { Path = o.Key, Project = o.Value.Project, Framework = o.Value.Framework }).ToList(),
            Files = files.Values.ToList(),
            Trees = after.Trees.Select(t => t.Tree).ToList(),
            // what the command line names and the build left there: obj/ sources of its targets above all, which a skipped build does not write
            Existing = CommandLineFiles(result.CommandLine, directory).Concat(outputs).Where(disk.Exists).Distinct(files.Comparer).ToList(),
        };
        return (entry, null);
    }

    public static DesignTime.Result ResultOf(Entry entry) => new(entry.CommandLine, entry.Framework,
        entry.ProjectOutputs.ToDictionary(o => o.Path, o => (o.Project, o.Framework), StringComparer.OrdinalIgnoreCase),
        entry.Inputs, entry.LiveAnalyzers, entry.IntermediateDirectory, entry.DefaultFramework);

    /**
     * The files a command line of the compiler names: sources, references, analyzers, additional files, configs, key files; with
     * [globbedOnly] the sources and the additional files, what the globs of a project give (the configs are probed up the tree instead).
     */
    public static IEnumerable<string> CommandLineFiles(List<string> commandLine, string directory, bool globbedOnly = false)
    {
        foreach (var argument in commandLine)
        {
            string? value;
            if (!argument.StartsWith('/') && !argument.StartsWith('-')) value = argument;
            else
            {
                var colon = argument.IndexOf(':');
                if (colon < 0) continue;
                var name = argument[1..colon].ToLowerInvariant();
                if (globbedOnly ? name != "additionalfile"
                    : name is not ("reference" or "r" or "analyzer" or "a" or "additionalfile" or "analyzerconfig" or "keyfile" or "ruleset" or "win32manifest" or "win32icon")) continue;
                value = argument[(colon + 1)..];
                // an alias: /reference:Alias=path
                if (name is "reference" or "r" && value.IndexOf('=') is > 0 and var equals && !Path.IsPathRooted(value[..equals])) value = value[(equals + 1)..];
            }
            value = value.Trim('"');
            if (value.Length == 0 || value.Contains('*') || value.Contains('?')) continue;
            string full;
            try { full = Path.GetFullPath(value, directory); }
            catch (Exception) { continue; }
            yield return full;
        }
    }

    // ---- reading and writing

    public static Entry? Read(string file)
    {
        try
        {
            if (!File.Exists(file)) return null;
            using var stream = File.OpenRead(file);
            return JsonSerializer.Deserialize<Entry>(stream);
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException or NotSupportedException)
        {
            return null;
        }
    }

    /** Written next to its place and moved there: a reader (the helper of another IDE) never sees half a file. */
    public static void Write(string file, Entry entry)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        var temporary = file + "." + Guid.NewGuid().ToString("N")[..8] + ".tmp";
        try
        {
            using (var stream = File.Create(temporary)) JsonSerializer.Serialize(stream, entry);
            File.Move(temporary, file, overwrite: true);
        }
        finally
        {
            try { File.Delete(temporary); } catch (Exception) { }
        }
    }
}
