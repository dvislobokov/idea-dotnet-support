// MsBuildHost: evaluation of MSBuild projects for the plugin (msbuild/MsBuildEvaluation), with the MSBuild of the SDK that `dotnet`
// resolves in the working directory (the plugin starts the helper in the directory of the solution, so a global.json there counts).
// A helper that stays running: `MsBuildHost --serve`, the protocol is in Protocol.cs.
//
//   evaluate    {projectPath, globalProperties: {name: value}, properties: [names], itemTypes: [names], targets: [names]}
//            -> {properties: {name: value}, items: {type: [{include, metadata: {name: value}}]}, imports: [paths], targetFrameworks: [..],
//                warning, milliseconds, reused}
//               include is the evaluated one, made a full path when it is a file on disk; metadata without the well-known ones;
//               warning: the imports that are not there, without which the project was evaluated (null when there are none), or
//               why the targets failed;
//               targets: run on a copy of the evaluation before properties and items are read, the ones the project has (others are
//               skipped). For what the SDK computes in targets and not in the evaluation: `AddImplicitDefineConstants` adds NET10_0,
//               NETFRAMEWORK, NET48_OR_GREATER... to DefineConstants. Only targets without side effects are meant.
//   invalidate  {paths: [..]} -> {dropped: n}   a project, an import, a file under a project or a new Directory.Build.props above it
//   info        {} -> {msBuildVersion, msBuildPath, sdk}
//
// One ProjectCollection per set of global properties; an evaluation is reused until the project or any of its imports changes on disk
// (their timestamps) or `invalidate` names something it depends on. Files that appear under a project (globs) are only seen through
// `invalidate`: the plugin sends it from its listener of the file system.
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Text.Json;
using Microsoft.Build.Construction;
using Microsoft.Build.Evaluation;
using Microsoft.Build.Exceptions;
using Microsoft.Build.Execution;
using Microsoft.Build.Locator;

namespace DotNetSupport.Helpers.MsBuildHost;

public static class Program
{
    private static string? registrationError;
    private static VisualStudioInstance? instance;

    public static async Task<int> Main(string[] args)
    {
        // whatever MSBuild or a task prints must not get into the protocol, which writes to the standard output stream itself
        Console.SetOut(Console.Error);
        Register();
        if (!args.Contains("--serve"))
        {
            Console.Error.WriteLine("Usage: MsBuildHost --serve   (requests on stdin, answers on stdout, see Protocol.cs)");
            return 2;
        }
        await HelperProtocol.ServeAsync((method, parameters, cancellation) => Task.FromResult(Handle(method, parameters, cancellation)));
        return 0;
    }

    private static void Register()
    {
        try
        {
            var options = new VisualStudioInstanceQueryOptions { DiscoveryTypes = DiscoveryType.DotNetSdk, WorkingDirectory = Directory.GetCurrentDirectory() };
            instance = MSBuildLocator.QueryVisualStudioInstances(options).FirstOrDefault();
            if (instance == null)
            {
                registrationError = $"No .NET SDK MSBuild for {Directory.GetCurrentDirectory()} that runs on .NET {Environment.Version} (`dotnet --info` there names none)";
                HelperProtocol.Log("error", registrationError);
                return;
            }
            MSBuildLocator.RegisterInstance(instance);
            HelperProtocol.Log("info", $"MSBuild {instance.Version} of the SDK in {instance.MSBuildPath}, runtime .NET {Environment.Version}, working directory {Directory.GetCurrentDirectory()}");
        }
        catch (Exception e)
        {
            registrationError = $"MSBuild of the .NET SDK could not be loaded: {e.Message}";
            HelperProtocol.Log("error", registrationError);
        }
    }

    private static object? Handle(string method, JsonElement? parameters, CancellationToken cancellation)
    {
        if (registrationError != null) throw new HelperException(registrationError);
        return method switch
        {
            "info" => new { msBuildVersion = instance?.Version.ToString(), msBuildPath = instance?.MSBuildPath, sdk = instance?.VisualStudioRootPath },
            "evaluate" => Evaluator.Evaluate(parameters ?? throw new HelperException("evaluate needs params"), cancellation),
            "invalidate" => Evaluator.Invalidate(parameters),
            _ => throw new HelperException($"MsBuildHost knows no method `{method}`"),
        };
    }
}

/** Everything that touches the types of MSBuild: not loaded before MSBuildLocator has registered the SDK. */
internal static class Evaluator
{
    private sealed class Collection(ProjectCollection projects)
    {
        public readonly ProjectCollection Projects = projects;
        public readonly Dictionary<string, Entry> Entries = new(StringComparer.OrdinalIgnoreCase);
    }

    private sealed class Entry(Project project, string? warning)
    {
        public Project Project = project;
        /** Why the project was evaluated without some of its imports, null when it has all of them. */
        public readonly string? Warning = warning;
        public Dictionary<string, DateTime> Stamps = new(StringComparer.OrdinalIgnoreCase);
        public bool Dirty;
    }

    private static readonly ConcurrentDictionary<string, Collection> Collections = new();

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static object Evaluate(JsonElement parameters, CancellationToken cancellation)
    {
        var path = Text(parameters, "projectPath") ?? throw new HelperException("evaluate needs projectPath");
        path = Path.GetFullPath(path);
        if (!File.Exists(path)) throw new HelperException($"No project file {path}");
        var globals = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (parameters.TryGetProperty("globalProperties", out var given) && given.ValueKind == JsonValueKind.Object)
            foreach (var property in given.EnumerateObject())
                if (property.Value.ValueKind == JsonValueKind.String && !string.IsNullOrEmpty(property.Value.GetString())) globals[property.Name] = property.Value.GetString()!;
        var key = string.Join(";", globals.Select(p => $"{p.Key}={p.Value}"));
        var collection = Collections.GetOrAdd(key, _ => new Collection(new ProjectCollection(new Dictionary<string, string>(globals, StringComparer.OrdinalIgnoreCase))));

        cancellation.ThrowIfCancellationRequested();
        var watch = Stopwatch.StartNew();
        bool reused;
        Project project;
        lock (collection)
        {
            try
            {
                (project, reused) = Load(collection, path);
            }
            catch (InvalidProjectFileException e)
            {
                throw new HelperException(e.Message);
            }
        }
        var elapsed = watch.ElapsedMilliseconds;
        if (!reused) HelperProtocol.Log("info", $"evaluated {path} [{(key.Length == 0 ? "no global properties" : key)}] in {elapsed} ms");

        lock (collection)
        {
            var warning = collection.Entries.TryGetValue(path, out var entry) ? entry.Warning : null;
            var targets = Texts(parameters, "targets").ToList();
            ProjectInstance? instance = null;
            if (targets.Count > 0)
            {
                cancellation.ThrowIfCancellationRequested();
                (instance, var failure) = RunTargets(project, targets);
                if (failure != null) warning = warning == null ? failure : $"{warning}; {failure}";
            }
            var properties = new Dictionary<string, string>();
            foreach (var name in Texts(parameters, "properties")) properties[name] = instance?.GetPropertyValue(name) ?? project.GetPropertyValue(name);
            var items = new Dictionary<string, List<object>>();
            foreach (var type in Texts(parameters, "itemTypes"))
            {
                var evaluated = instance != null
                    ? instance.GetItems(type).Select(i => (i.EvaluatedInclude, i.Metadata.Select(m => (m.Name, m.EvaluatedValue))))
                    : project.GetItems(type).Select(i => (i.EvaluatedInclude, i.Metadata.Select(m => (m.Name, m.EvaluatedValue))));
                var list = new List<object>();
                foreach (var (include, all) in evaluated)
                {
                    var metadata = new Dictionary<string, string>();
                    foreach (var (name, value) in all) if (value.Length > 0) metadata[name] = value;
                    list.Add(new { include = FullPathOfFile(project.DirectoryPath, include), metadata });
                }
                items[type] = list;
            }
            return new
            {
                properties,
                items,
                imports = project.Imports.Select(i => i.ImportedProject.FullPath).Distinct(StringComparer.OrdinalIgnoreCase).ToList(),
                targetFrameworks = TargetFrameworks(project),
                warning,
                milliseconds = elapsed,
                reused,
            };
        }
    }

    /** One build at a time: `ProjectInstance.Build` goes through the default BuildManager of the process, which runs one build only. */
    private static readonly object BuildLock = new();

    /**
     * [targets] that the project has, run on a copy of its evaluation (the evaluation itself stays as it is, to be reused); null with the
     * reason when none of them is there or the build failed: the caller reads the evaluation then.
     */
    private static (ProjectInstance?, string?) RunTargets(Project project, List<string> targets)
    {
        var instance = project.CreateProjectInstance();
        var present = targets.Where(instance.Targets.ContainsKey).ToArray();
        if (present.Length == 0) return (null, null);
        var errors = new ErrorLogger();
        bool built;
        lock (BuildLock)
        {
            try
            {
                built = instance.Build(present, [errors]);
            }
            catch (Exception e) when (e is InvalidOperationException or InvalidProjectFileException)
            {
                return (null, $"targets {string.Join(";", present)}: {e.Message}");
            }
        }
        return built ? (instance, null) : (null, $"targets {string.Join(";", present)} failed: {string.Join("; ", errors.Errors.Take(3))}");
    }

    /** The errors of a build of targets, for the warning of the answer. */
    private sealed class ErrorLogger : Microsoft.Build.Framework.ILogger
    {
        public readonly List<string> Errors = [];
        public Microsoft.Build.Framework.LoggerVerbosity Verbosity { get; set; } = Microsoft.Build.Framework.LoggerVerbosity.Quiet;
        public string? Parameters { get; set; }
        public void Initialize(Microsoft.Build.Framework.IEventSource eventSource) => eventSource.ErrorRaised += (_, e) => { lock (Errors) Errors.Add(e.Message ?? e.Code ?? "error"); };
        public void Shutdown() { }
    }

    /** The evaluation of [path] in [collection], made again when the project or one of its imports has changed since. Under the lock of the collection. */
    private static (Project, bool) Load(Collection collection, string path)
    {
        if (collection.Entries.TryGetValue(path, out var entry))
        {
            var changed = entry.Stamps.Where(s => Stamp(s.Key) != s.Value).Select(s => s.Key).ToList();
            if (changed.Count == 0 && !entry.Dirty) return (entry.Project, true);
            foreach (var file in changed) Reload(collection, file);
            if (entry.Dirty) Reload(collection, path);
            entry.Project.MarkDirty();
            entry.Project.ReevaluateIfNecessary();
            entry.Dirty = false;
        }
        else
        {
            // a file another collection has read is read again here: the cache of the XML is per collection
            entry = LoadNew(collection, path);
            collection.Entries[path] = entry;
        }
        entry.Stamps = new Dictionary<string, DateTime>(StringComparer.OrdinalIgnoreCase) { [path] = Stamp(path) };
        foreach (var import in entry.Project.Imports) entry.Stamps[import.ImportedProject.FullPath] = Stamp(import.ImportedProject.FullPath);
        return (entry.Project, false);
    }

    /**
     * A project that imports what is not there (the targets of Visual Studio a project of .NET Framework names: `$(VSToolsPath)\WebApplications`)
     * is evaluated again without them, as Visual Studio opens such a project: its properties and items are still the ones of the project
     * file, only what the missing file would have added is not there. The message of the first attempt goes with the answer.
     */
    private static Entry LoadNew(Collection collection, string path)
    {
        try
        {
            return new Entry(collection.Projects.LoadProject(path), null);
        }
        catch (InvalidProjectFileException e) when (e.ErrorCode is "MSB4019" or "MSB4020")
        {
            var project = new Project(path, null, null, collection.Projects, ProjectLoadSettings.IgnoreMissingImports | ProjectLoadSettings.IgnoreInvalidImports | ProjectLoadSettings.IgnoreEmptyImports);
            HelperProtocol.Log("warn", $"{path} is evaluated without the imports that are not there: {e.Message}");
            return new Entry(project, e.Message);
        }
    }

    /** The XML of [file] read again from disk, if the collection has it. */
    private static void Reload(Collection collection, string file)
    {
        try
        {
            var xml = ProjectRootElement.TryOpen(file, collection.Projects);
            if (xml == null) return;
            if (File.Exists(file)) xml.Reload(throwIfUnsavedChanges: false);
        }
        catch (Exception e) when (e is InvalidProjectFileException or IOException or UnauthorizedAccessException)
        {
            // the evaluation that follows reports what is wrong with the file
        }
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static object Invalidate(JsonElement? parameters)
    {
        var paths = parameters is { } p ? Texts(p, "paths").Select(Path.GetFullPath).ToList() : [];
        var dropped = 0;
        foreach (var collection in Collections.Values)
        {
            lock (collection)
            {
                foreach (var (projectPath, entry) in collection.Entries)
                {
                    if (entry.Dirty || !(paths.Count == 0 || paths.Any(path => DependsOn(projectPath, entry, path)))) continue;
                    entry.Dirty = true;
                    dropped++;
                }
            }
        }
        return new { dropped };
    }

    /**
     * Whether a change of [path] can change the evaluation of [projectPath]: the project itself, one of its imports, a file under its
     * directory (globs), or a file in a directory above it (a Directory.Build.props that has just appeared).
     */
    private static bool DependsOn(string projectPath, Entry entry, string path)
    {
        if (entry.Stamps.ContainsKey(path)) return true;
        var projectDirectory = Path.GetDirectoryName(projectPath) ?? "";
        var directory = Path.GetDirectoryName(path) ?? "";
        return IsUnder(path, projectDirectory) || IsUnder(projectDirectory, directory) || string.Equals(projectDirectory, directory, StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsUnder(string path, string directory) =>
        directory.Length > 0 && path.StartsWith(directory.TrimEnd('\\', '/') + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase);

    private static DateTime Stamp(string file)
    {
        try { return File.Exists(file) ? File.GetLastWriteTimeUtc(file) : DateTime.MinValue; }
        catch (Exception) { return DateTime.MinValue; }
    }

    /** `TargetFrameworks`, else `TargetFramework`, else what a project of .NET Framework says: `v4.7.2` -> `net472`. */
    private static List<string> TargetFrameworks(Project project)
    {
        var many = Split(project.GetPropertyValue("TargetFrameworks"));
        if (many.Count > 0) return many;
        var one = project.GetPropertyValue("TargetFramework").Trim();
        if (one.Length > 0) return [one];
        var version = project.GetPropertyValue("TargetFrameworkVersion").Trim();
        var identifier = project.GetPropertyValue("TargetFrameworkIdentifier").Trim();
        if (version.Length > 0 && (identifier.Length == 0 || identifier == ".NETFramework")) return ["net" + version.TrimStart('v', 'V').Replace(".", "")];
        return [];
    }

    private static string FullPathOfFile(string directory, string include)
    {
        if (include.Length == 0 || include.IndexOfAny(Path.GetInvalidPathChars()) >= 0) return include;
        try
        {
            var full = Path.GetFullPath(Path.Combine(directory, include));
            return File.Exists(full) ? full : include;
        }
        catch (Exception)
        {
            return include;
        }
    }

    private static List<string> Split(string value) => value.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();

    private static string? Text(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static IEnumerable<string> Texts(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Array
            ? value.EnumerateArray().Where(v => v.ValueKind == JsonValueKind.String).Select(v => v.GetString() ?? "").Where(s => s.Length > 0).ToList()
            : [];
}
