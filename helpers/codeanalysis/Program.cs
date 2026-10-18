// CodeAnalysisHelper: the source generators and the Roslyn analyzers of the projects of one solution, for the plugin without the language
// server (CSHARP_PSI_MIGRATION.md, D3 / D4). A helper that stays running: `CodeAnalysisHelper --serve`, the protocol is in Protocol.cs.
//
//   generate    {projectPath, configuration, targetFramework?, outputRoot}
//            -> {project, framework, files: [{path, generatorAssembly, generatorType, hintName}], buildFiles: [paths], additionalFiles: [paths],
//                generators: [type names], errors: [..], milliseconds}
//               runs the generators of the project (Razor's among them) and writes what they make under outputRoot, as
//               <generator assembly>/<generator type>/<hint name>; files of an earlier run that are not made again are deleted.
//               buildFiles: the C# the targets of the build made in obj/ for the compiler (XAML, Grpc.Tools, resources) — the design-time
//               `Compile` runs those targets (MarkupCompilePass1, Protobuf_Compile) before it gives the command line
//   analyze     {projectPath, configuration, targetFramework?, outputRoot, paths?: [..], excludedIds?: [..], fixes?: bool}
//            -> {project, framework, diagnostics: [{id, severity, message, path, startLine, startColumn, endLine, endColumn, category,
//                helpLink, source, fixes: [titles]}], analyzers, milliseconds}
//               the analyzers of the project (its packages, the CA rules of the SDK, the IDE rules of code style) with the severities of
//               its .editorconfig files; diagnostics of `paths` only when given; lines and columns are 0-based, columns in UTF-16 units
//   fix         {projectPath, configuration, targetFramework?, outputRoot, path, id, startLine, startColumn, title}
//            -> {title, edits: [{path, startLine, startColumn, endLine, endColumn, text}], created: [{path, text}]}
//   invalidate  {paths: [..]} -> {dropped}   a project file, a props file or a new source file: the projects it touches are loaded again
//   info        {} -> {roslyn, sdkDirectory, framework, workingSet, managed, projects}
//
// The files are read from disk: the plugin saves the documents first. A project is the command line of its compiler (a design-time
// `Compile` of `dotnet msbuild`, nothing built); its project references are loaded the same way and referenced as projects, so the types
// their generators make are seen. One request at a time. The design-time builds are cached on disk while nothing they read changes
// (DesignTimeCache.cs, under <outputRoot>/.design-time): a new helper loads the projects without MSBuild.
using System.Collections.Concurrent;
using System.Collections.Immutable;
using System.Composition;
using System.Diagnostics;
using System.Reflection;
using System.Runtime.Loader;
using System.Text;
using System.Text.Json;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CodeActions;
using Microsoft.CodeAnalysis.CodeFixes;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.Diagnostics;
using Microsoft.CodeAnalysis.Host.Mef;
using Microsoft.CodeAnalysis.Text;

namespace DotNetSupport.Helpers.CodeAnalysis;

public static class Program
{
    public static async Task<int> Main(string[] args)
    {
        // whatever a generator or an analyzer prints must not get into the protocol
        Console.SetOut(Console.Error);
        if (!args.Contains("--serve"))
        {
            Console.Error.WriteLine("Usage: CodeAnalysisHelper --serve   (requests on stdin, answers on stdout, see Protocol.cs)");
            return 2;
        }
        var host = new Host();
        await HelperProtocol.ServeAsync((method, parameters, cancellation) => host.HandleAsync(method, parameters, cancellation));
        return 0;
    }
}

/** What a request names: the project, the configuration and framework it is compiled with, where its generated files go. */
internal sealed record Target(string ProjectPath, string Configuration, string? Framework, string OutputDirectory)
{
    public string Key => $"{ProjectPath.ToLowerInvariant()}|{Configuration}|{Framework}";

    /** The disk cache of the design-time builds of the solution (DesignTimeCache): next to the folders of the generated files. */
    public string CacheDirectory => Path.Combine(Path.GetDirectoryName(OutputDirectory)!, ".design-time");

    public static Target Of(JsonElement parameters)
    {
        var path = Json.Text(parameters, "projectPath") ?? throw new HelperException("projectPath is missing");
        path = Path.GetFullPath(path);
        if (!File.Exists(path)) throw new HelperException($"No project file {path}");
        var root = Json.Text(parameters, "outputRoot") ?? throw new HelperException("outputRoot is missing");
        return new Target(path, Json.Text(parameters, "configuration") ?? "Debug", Json.Text(parameters, "targetFramework"), OutputOf(Path.GetFullPath(root), path));
    }

    /** The folder of the generated files of [projectPath] under [root]: its name and a hash of its path (the plugin names it the same way). */
    public static string OutputOf(string root, string projectPath) =>
        Path.Combine(root, $"{Path.GetFileNameWithoutExtension(projectPath)}-{Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(projectPath.ToLowerInvariant().Replace('\\', '/'))))[..8].ToLowerInvariant()}");
}

internal sealed class Host
{
    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly Projects projects = new();

    public async Task<object?> HandleAsync(string method, JsonElement? parameters, CancellationToken cancellation)
    {
        if (method == "info") return Info();
        await gate.WaitAsync(cancellation);
        try
        {
            return method switch
            {
                "generate" => await projects.GenerateAsync(Target.Of(Required(parameters, method)), cancellation),
                "analyze" => await projects.AnalyzeAsync(Target.Of(Required(parameters, method)), Required(parameters, method), cancellation),
                "fix" => await projects.FixAsync(Target.Of(Required(parameters, method)), Required(parameters, method), cancellation),
                "invalidate" => projects.Invalidate(Json.Texts(Required(parameters, method), "paths").ToList()),
                _ => throw new HelperException($"CodeAnalysisHelper knows no method `{method}`"),
            };
        }
        finally
        {
            projects.CollectDropped();
            gate.Release();
        }
    }

    private static JsonElement Required(JsonElement? parameters, string method) => parameters ?? throw new HelperException($"{method} needs params");

    private object Info() => new
    {
        roslyn = typeof(Compilation).Assembly.GetName().Version?.ToString(),
        sdkDirectory = Sdk.Directory,
        framework = Environment.Version.ToString(),
        workingSet = Environment.WorkingSet,
        managed = GC.GetTotalMemory(false),
        projects = projects.Count,
        assemblies = MetadataCache.Count,
    };
}

/** The SDK the helper was built with (its folder, from the metadata the project file writes): the IDE and CA analyzers come from there. */
internal static class Sdk
{
    public static readonly string? Directory = typeof(Sdk).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>().FirstOrDefault(a => a.Key == "SdkDirectory")?.Value;

    /** The analyzers of code style (IDE*) of the SDK: the build gives them only with EnforceCodeStyleInBuild, an IDE always has them. */
    public static IEnumerable<string> CodeStyleAnalyzers()
    {
        if (Directory == null) return [];
        var folder = Path.Combine(Directory, "Sdks", "Microsoft.NET.Sdk", "codestyle", "cs");
        return System.IO.Directory.Exists(folder) ? System.IO.Directory.GetFiles(folder, "*.dll") : [];
    }
}

/** A project as the compiler sees it, in the workspace, with its generators, analyzers and fixes. */
internal sealed class LoadedProject(Target target, ProjectId id, CSharpCommandLineArguments arguments, string framework)
{
    public readonly Target Target = target;
    public readonly ProjectId Id = id;
    public CSharpCommandLineArguments Arguments = arguments;
    /** The command line of the design-time build the project was loaded with: a new one that differs in its files only is applied in place. */
    public List<string> CommandLine = [];
    /** A file of the project appeared, went or was renamed ([Projects.Invalidate]): its sources are listed again before the next request. */
    public bool FileSetChanged;
    public readonly string Framework = framework;
    public readonly Dictionary<string, DateTime> Inputs = new(StringComparer.OrdinalIgnoreCase);
    public readonly Dictionary<string, (DocumentId Id, DateTime Stamp)> Sources = new(StringComparer.OrdinalIgnoreCase);
    public readonly Dictionary<string, DocumentId> Generated = new(StringComparer.OrdinalIgnoreCase);
    public readonly List<string> GeneratorErrors = [];
    /** Sources of the compiler that targets of the build made in `obj/` (XAML, gRPC, resources): the design-time build ran those targets. */
    public List<string> BuildFiles = [];
    public List<LoadedProject> References = [];
    public ImmutableArray<ISourceGenerator> Generators = [];
    public ImmutableArray<DiagnosticAnalyzer> Analyzers = [];
    public List<CodeFixProvider> Fixers = [];
    public List<AdditionalText> AdditionalTexts = [];
    /** The additional files (`.razor`, `.cshtml`, files of analyzers) as last read: a changed one is given to the generators anew. */
    public readonly Dictionary<string, (DocumentId Id, DateTime Stamp)> Additional = new(StringComparer.OrdinalIgnoreCase);
    public GeneratorDriver? Driver;
    public ImmutableArray<Diagnostic> GeneratorDiagnostics = [];

    /** The framework a build without one picks: the project is the same under the key without a framework. */
    public bool DefaultFramework;

    public string Name => Path.GetFileNameWithoutExtension(Target.ProjectPath);
}

internal sealed class Projects
{
    private readonly AdhocWorkspace workspace = new(MefHostServices.DefaultHost);
    private readonly Dictionary<string, LoadedProject> loaded = new();

    public int Count => loaded.Values.Distinct().Count();

    // ---- requests

    public async Task<object> GenerateAsync(Target target, CancellationToken cancellation)
    {
        var watch = Stopwatch.StartNew();
        var project = await PrepareAsync(target, cancellation);
        return new
        {
            project = target.ProjectPath,
            framework = project.Framework,
            files = project.Generated.Keys.Select(path => GeneratedFile(project, path)).ToList(),
            buildFiles = project.BuildFiles,
            // what a change of which asks the generators to run again, and which generators there are (a framework module of the plugin
            // trusts what it knows only when its generator ran)
            additionalFiles = project.AdditionalTexts.Select(t => t.Path).ToList(),
            generators = project.Generators.Select(g => g.GetGeneratorType().FullName).ToList(),
            errors = project.GeneratorErrors,
            milliseconds = watch.ElapsedMilliseconds,
            workingSet = Environment.WorkingSet,
        };
    }

    public async Task<object> AnalyzeAsync(Target target, JsonElement parameters, CancellationToken cancellation)
    {
        var watch = Stopwatch.StartNew();
        var project = await PrepareAsync(target, cancellation);
        var excluded = Json.Texts(parameters, "excludedIds").ToHashSet(StringComparer.OrdinalIgnoreCase);
        var paths = Json.Texts(parameters, "paths").Select(Path.GetFullPath).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var withFixes = parameters.TryGetProperty("fixes", out var f) && f.ValueKind == JsonValueKind.True;
        var diagnostics = await DiagnosticsAsync(project, paths, cancellation);
        diagnostics = diagnostics.Where(d => !excluded.Contains(d.Id)).ToList();
        var fixable = project.Fixers.SelectMany(f => SafeIds(f)).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var solution = workspace.CurrentSolution;
        var answers = new List<object>();
        foreach (var diagnostic in diagnostics.Take(MaxDiagnostics))
        {
            var span = diagnostic.Location.GetLineSpan();
            List<string> fixes = [];
            if (withFixes && fixable.Contains(diagnostic.Id) && answers.Count < MaxDiagnosticsWithFixes)
            {
                var document = DocumentOf(solution, project, span.Path);
                if (document != null) fixes = (await ActionsAsync(project, document, diagnostic, cancellation)).Select(a => a.Title).Distinct().ToList();
            }
            answers.Add(new
            {
                id = diagnostic.Id,
                severity = diagnostic.Severity.ToString().ToLowerInvariant(),
                message = diagnostic.GetMessage(),
                path = span.Path,
                startLine = span.StartLinePosition.Line,
                startColumn = span.StartLinePosition.Character,
                endLine = span.EndLinePosition.Line,
                endColumn = span.EndLinePosition.Character,
                category = diagnostic.Descriptor.Category,
                helpLink = string.IsNullOrEmpty(diagnostic.Descriptor.HelpLinkUri) ? null : diagnostic.Descriptor.HelpLinkUri,
                source = project.GeneratorDiagnostics.Contains(diagnostic) ? "generator" : "analyzer",
                fixable = fixable.Contains(diagnostic.Id),
                fixes,
            });
        }
        return new
        {
            project = target.ProjectPath,
            framework = project.Framework,
            diagnostics = answers,
            analyzers = project.Analyzers.Length,
            milliseconds = watch.ElapsedMilliseconds,
            workingSet = Environment.WorkingSet,
        };
    }

    public async Task<object> FixAsync(Target target, JsonElement parameters, CancellationToken cancellation)
    {
        var project = await PrepareAsync(target, cancellation);
        var path = Path.GetFullPath(Json.Text(parameters, "path") ?? throw new HelperException("fix needs path"));
        var id = Json.Text(parameters, "id") ?? throw new HelperException("fix needs id");
        var title = Json.Text(parameters, "title") ?? throw new HelperException("fix needs title");
        var line = parameters.GetProperty("startLine").GetInt32();
        var column = parameters.GetProperty("startColumn").GetInt32();
        var diagnostics = await DiagnosticsAsync(project, new HashSet<string>(StringComparer.OrdinalIgnoreCase) { path }, cancellation);
        var diagnostic = diagnostics.FirstOrDefault(d => d.Id == id && d.Location.GetLineSpan().StartLinePosition == new LinePosition(line, column))
            ?? throw new HelperException($"{id} is not at {line + 1}:{column + 1} of {Path.GetFileName(path)} any more");
        var solution = workspace.CurrentSolution;
        var document = DocumentOf(solution, project, path) ?? throw new HelperException($"{path} is not a document of {project.Name}");
        var action = (await ActionsAsync(project, document, diagnostic, cancellation)).FirstOrDefault(a => a.Title == title)
            ?? throw new HelperException($"No fix `{title}` for {id} any more");
        var operations = await action.GetOperationsAsync(cancellation);
        var changed = operations.OfType<ApplyChangesOperation>().FirstOrDefault()?.ChangedSolution
            ?? throw new HelperException($"`{title}` changes nothing the plugin can apply");
        var edits = new List<object>();
        var created = new List<object>();
        foreach (var projectChanges in changed.GetChanges(solution).GetProjectChanges())
        {
            foreach (var documentId in projectChanges.GetChangedDocuments(onlyGetDocumentsWithTextChanges: true))
            {
                var before = solution.GetDocument(documentId)!;
                var after = changed.GetDocument(documentId)!;
                if (before.FilePath == null || IsGenerated(project, before.FilePath)) continue;
                var text = await before.GetTextAsync(cancellation);
                foreach (var change in await after.GetTextChangesAsync(before, cancellation))
                {
                    var span = text.Lines.GetLinePositionSpan(change.Span);
                    edits.Add(new { path = before.FilePath, startLine = span.Start.Line, startColumn = span.Start.Character, endLine = span.End.Line, endColumn = span.End.Character, text = change.NewText ?? "" });
                }
            }
            foreach (var documentId in projectChanges.GetAddedDocuments())
            {
                var added = changed.GetDocument(documentId)!;
                var addedPath = added.FilePath ?? Path.Combine(Path.GetDirectoryName(project.Target.ProjectPath)!, added.Name);
                created.Add(new { path = addedPath, text = (await added.GetTextAsync(cancellation)).ToString() });
            }
        }
        return new { title, edits, created };
    }

    /**
     * A project file, a props file, an `.editorconfig` drops the projects reading it (and those referencing them): they load again. A source
     * or another file of a project that appeared, went or was renamed (a `.cs`, a `.razor`) only marks the project: its files are listed
     * again before the next request and applied in place ([UpdateFileSetAsync]) — the projects referencing it stay, and the generators keep
     * their state (a renamed `.cs` of MudBlazor reloaded MudBlazor.Docs with it and ran its Razor generator from scratch: 14–15 s).
     */
    public object Invalidate(List<string> paths)
    {
        var dropped = 0;
        var marked = 0;
        var full = paths.Select(p => Path.GetFullPath(p)).ToList();
        foreach (var project in loaded.Values.Distinct().ToList())
        {
            if (!loaded.ContainsValue(project)) continue;   // already dropped with a project it references
            var directory = Path.GetDirectoryName(project.Target.ProjectPath)! + Path.DirectorySeparatorChar;
            var under = full.Where(p => p.StartsWith(directory, StringComparison.OrdinalIgnoreCase)).ToList();
            if (full.Any(p => project.Inputs.ContainsKey(p) || IsDirectoryFile(p)) || under.Any(IsProjectInput))
            {
                Drop(project);
                dropped++;
            }
            else if (under.Count > 0)
            {
                project.FileSetChanged = true;
                marked++;
            }
        }
        return new { dropped, marked };
    }

    /** What MSBuild imports or reads as the project: a change of it is a change of the command line, not of a file it lists. */
    private static bool IsProjectInput(string path) =>
        IsDirectoryFile(path) || Path.GetExtension(path).ToLowerInvariant() is ".csproj" or ".props" or ".targets" or ".editorconfig" or ".globalconfig" or ".json" or ".sln" or ".slnx"
            || Path.GetFileName(path).Equals(".editorconfig", StringComparison.OrdinalIgnoreCase);

    private static bool IsDirectoryFile(string path) => Path.GetFileName(path).StartsWith("Directory.", StringComparison.OrdinalIgnoreCase);

    // ---- the compilation of a project

    /** [target] loaded (again when its inputs have changed), its sources as they are on disk, its generated files made again. */
    private async Task<LoadedProject> PrepareAsync(Target target, CancellationToken cancellation)
    {
        try
        {
            var project = await LoadAsync(target, [], cancellation);
            await GenerateAsync(project, [], cancellation);
            return project;
        }
        finally
        {
            // what was fetched ahead and not taken (a reference that failed before its turn) is not kept for the next request: its
            // inputs may change meanwhile
            lock (prefetched) { prefetchGeneration++; prefetched.Clear(); }
        }
    }

    // ---- the design-time builds of the references, ahead of their turn

    /**
     * The design-time builds of the projects a request will load, started as soon as the project referencing them has its own: the
     * projects are still loaded one after another, references first (the workspace and [loaded] are touched by one flow only), but
     * their MSBuild processes run side by side (DesignTime.Slots) while the referencing one waits for the first of them or its
     * generators run. Guarded by its own lock: the tasks that fill it run on the thread pool.
     */
    private readonly Dictionary<string, Task<DesignTime.Result>> prefetched = new();
    private int prefetchGeneration;

    /** The design-time build of [target]: the one fetched ahead when there is one, else now. */
    private Task<DesignTime.Result> DesignOf(Target target, CancellationToken cancellation)
    {
        lock (prefetched)
        {
            if (prefetched.Remove(target.Key, out var ahead)) return ahead;
        }
        return DesignTime.CompileAsync(target, cancellation);
    }

    /** The project behind a reference of [target] to [source] (a project of ReferencePath): its target, keyed as LoadAsync keys it. */
    private static Target ReferenceTarget(Target target, (string Project, string? Framework) source) =>
        new(source.Project, target.Configuration, source.Framework, Target.OutputOf(Path.GetDirectoryName(target.OutputDirectory)!, Path.GetFullPath(source.Project)));

    /**
     * Starts the design-time builds of the projects [design] of [target] references, and through them of theirs, but not of those in
     * [known] (loaded at the start of the request: LoadAsync checks them itself, mostly without MSBuild).
     */
    private void Prefetch(Target target, DesignTime.Result design, IReadOnlySet<string> known, CancellationToken cancellation)
    {
        int generation;
        lock (prefetched) generation = prefetchGeneration;
        Prefetch(target, design, known, generation, cancellation);
    }

    /** [generation]: of the request that started it — a build that ends after its request starts nothing for the next one. */
    private void Prefetch(Target target, DesignTime.Result design, IReadOnlySet<string> known, int generation, CancellationToken cancellation)
    {
        foreach (var source in design.ProjectOutputs.Values.Distinct())
        {
            if (!File.Exists(source.Project)) continue;
            var reference = ReferenceTarget(target, source);
            if (known.Contains(reference.Key)) continue;
            lock (prefetched)
            {
                if (generation != prefetchGeneration || prefetched.ContainsKey(reference.Key)) continue;
                prefetched[reference.Key] = Task.Run(async () =>
                {
                    var result = await DesignTime.CompileAsync(reference, cancellation);
                    Prefetch(reference, result, known, generation, cancellation);
                    return result;
                }, cancellation);
            }
        }
    }

    private async Task<LoadedProject> LoadAsync(Target target, HashSet<string> visiting, CancellationToken cancellation)
    {
        if (loaded.TryGetValue(target.Key, out var known))
        {
            if (known.Inputs.All(i => Stamp(i.Key) == i.Value) && known.References.All(r => loaded.ContainsKey(r.Target.Key))
                && (!known.FileSetChanged || await UpdateFileSetAsync(known, cancellation)))
            {
                SyncSources(known);
                return known;
            }
            Drop(known);
        }
        if (!visiting.Add(target.Key)) throw new HelperException($"{Path.GetFileName(target.ProjectPath)} references itself");
        var project = await LoadNewAsync(target, visiting, cancellation);
        loaded[target.Key] = project;
        // a project referenced without a framework (one target: SetTargetFramework is empty) and asked for by the plugin with the one it
        // has is the same project: known by both keys it is not loaded twice (MudBlazor.UnitTests.Shared was, seen by the UI robot)
        if (project.Framework.Length > 0) loaded.TryAdd((target with { Framework = project.Framework }).Key, project);
        // and the other way: asked for with the framework first, then referenced without one (MudBlazor.Analyzers by its CodeFixes)
        if (project.DefaultFramework) loaded.TryAdd((target with { Framework = null }).Key, project);
        return project;
    }

    /** Projects were dropped since the last request ended: their compilations and drivers are garbage now. */
    private bool dropped;

    /**
     * A full collection once a request dropped projects (a renamed source file, a changed project file — rare): the compilations of the
     * old ones are the whole heap of a project, and the workstation GC let them pile up (the playground, eight renames in Lib: +170 MB).
     */
    public void CollectDropped()
    {
        if (!dropped) return;
        dropped = false;
        GC.Collect(2, GCCollectionMode.Forced, blocking: true, compacting: true);
        GC.WaitForPendingFinalizers();
        GC.Collect(2, GCCollectionMode.Forced, blocking: true, compacting: true);
    }

    private void Drop(LoadedProject project)
    {
        dropped = true;

        foreach (var key in loaded.Where(e => e.Value == project).Select(e => e.Key).ToList()) loaded.Remove(key);
        var solution = workspace.CurrentSolution;
        if (solution.GetProject(project.Id) != null) workspace.TryApplyChanges(solution.RemoveProject(project.Id));
        // who referenced it is loaded again with it
        foreach (var other in loaded.Values.Distinct().Where(o => o.References.Contains(project)).ToList()) Drop(other);
    }

    private async Task<LoadedProject> LoadNewAsync(Target target, HashSet<string> visiting, CancellationToken cancellation)
    {
        var watch = Stopwatch.StartNew();
        var design = await DesignOf(target, cancellation);
        // the references' builds start now, side by side, instead of each one when its turn comes below
        Prefetch(target, design, new HashSet<string>(loaded.Keys), cancellation);
        var directory = Path.GetDirectoryName(target.ProjectPath)!;
        var arguments = CSharpCommandLineParser.Default.Parse(design.CommandLine, directory, sdkDirectory: null);
        var id = ProjectId.CreateNewId(target.ProjectPath);
        var project = new LoadedProject(target, id, arguments, design.Framework ?? target.Framework ?? "") { DefaultFramework = design.DefaultFramework, CommandLine = design.CommandLine };
        foreach (var input in design.Inputs.Append(target.ProjectPath)) project.Inputs[Path.GetFullPath(input)] = Stamp(input);
        foreach (var config in arguments.AnalyzerConfigPaths) project.Inputs[config] = Stamp(config);

        // project references: those projects loaded the same way and referenced as projects, else their assemblies if they are built
        var projectReferences = new List<ProjectReference>();
        var metadata = new List<MetadataReference>();
        foreach (var reference in arguments.MetadataReferences)
        {
            var path = Path.GetFullPath(reference.Reference, directory);
            if (design.ProjectOutputs.TryGetValue(path, out var source))
            {
                try
                {
                    var other = await LoadAsync(ReferenceTarget(target, source), visiting, cancellation);
                    await GenerateAsync(other, [], cancellation);
                    project.References.Add(other);
                    projectReferences.Add(new ProjectReference(other.Id, reference.Properties.Aliases));
                    continue;
                }
                catch (HelperException e)
                {
                    HelperProtocol.Log("warn", $"{project.Name}: the referenced {Path.GetFileName(source.Project)} is taken from its assembly: {e.Message}");
                }
            }
            if (File.Exists(path)) metadata.Add(MetadataCache.Reference(path, reference.Properties));
        }

        var documents = new List<DocumentInfo>();
        project.BuildFiles = BuildFiles(arguments.SourceFiles.Select(f => f.Path), design.IntermediateDirectory ?? Path.Combine(directory, "obj"));
        foreach (var file in arguments.SourceFiles)
        {
            var documentId = DocumentId.CreateNewId(id, file.Path);
            documents.Add(Document(documentId, file.Path));
            project.Sources[file.Path] = (documentId, Stamp(file.Path));
        }
        var configs = arguments.AnalyzerConfigPaths.Where(File.Exists).Select(p => Document(DocumentId.CreateNewId(id, p), p)).ToList();
        var additional = new List<DocumentInfo>();
        foreach (var file in arguments.AdditionalFiles.Where(f => File.Exists(f.Path)))
        {
            var documentId = DocumentId.CreateNewId(id, file.Path);
            additional.Add(Document(documentId, file.Path));
            project.Additional[file.Path] = (documentId, Stamp(file.Path));
        }
        var info = ProjectInfo.Create(id, VersionStamp.Create(), project.Name, Path.GetFileNameWithoutExtension(arguments.OutputFileName ?? project.Name), LanguageNames.CSharp,
                filePath: target.ProjectPath, outputFilePath: arguments.OutputFileName == null ? null : Path.Combine(arguments.OutputDirectory, arguments.OutputFileName),
                compilationOptions: arguments.CompilationOptions, parseOptions: arguments.ParseOptions, documents: documents, projectReferences: projectReferences,
                metadataReferences: metadata, additionalDocuments: additional)
            .WithAnalyzerConfigDocuments(configs);
        if (!workspace.TryApplyChanges(workspace.CurrentSolution.AddProject(info))) throw new HelperException($"{project.Name} could not be added to the workspace");
        project.AdditionalTexts = arguments.AdditionalFiles.Select(f => (AdditionalText)new FileText(f.Path)).ToList();

        // analyzers and generators: those of the command line (packages, the SDK, Razor's), the code style of the SDK
        var analyzerPaths = arguments.AnalyzerReferences.Select(a => Path.GetFullPath(a.FilePath, directory)).ToList();
        foreach (var codeStyle in Sdk.CodeStyleAnalyzers())
            if (!analyzerPaths.Any(p => Path.GetFileName(p).Equals(Path.GetFileName(codeStyle), StringComparison.OrdinalIgnoreCase))) analyzerPaths.Add(codeStyle);
        var generators = new List<ISourceGenerator>();
        var analyzers = new List<DiagnosticAnalyzer>();
        // Razor's generator of the SDK of the project is built against the Roslyn of that SDK, no newer than the helper's (the newest SDK)
        foreach (var path in analyzerPaths.Where(File.Exists))
        {
            var reference = AnalyzerLoader.Instance.Reference(path);
            generators.AddRange(reference.Generators);
            if (!design.LiveAnalyzers) continue;
            analyzers.AddRange(reference.Analyzers);
            project.Fixers.AddRange(AnalyzerLoader.Instance.Fixers(path));
        }
        project.Generators = [.. generators];
        project.Analyzers = [.. analyzers];
        var parse = (CSharpParseOptions)arguments.ParseOptions;
        var options = workspace.CurrentSolution.GetProject(id)!.AnalyzerOptions.AnalyzerConfigOptionsProvider;
        if (generators.Count > 0)
            project.Driver = CSharpGeneratorDriver.Create(project.Generators, project.AdditionalTexts, parse, options,
                new GeneratorDriverOptions(IncrementalGeneratorOutputKind.None, trackIncrementalGeneratorSteps: false, baseDirectory: target.OutputDirectory));
        HelperProtocol.Log("info", $"{project.Name} ({project.Framework}) loaded in {watch.ElapsedMilliseconds} ms: {documents.Count} files, {metadata.Count} assemblies, " +
            $"{projectReferences.Count} projects, {generators.Count} generators, {analyzers.Count} analyzers, {project.Fixers.Count} fixes");
        return project;
    }

    /**
     * The files of [project] listed again after one appeared, went or was renamed: the design-time build again (the tree of the project
     * changed, so its cache misses), and when its command line differs from the one the project was loaded with in the sources and the
     * additional files only, they are added and removed in place — the documents of the workspace, the additional texts of the driver,
     * whose state stays (an unchanged `.razor` is not generated again), and the projects referencing this one stay loaded. False when
     * anything else changed: the project loads again.
     */
    private async Task<bool> UpdateFileSetAsync(LoadedProject project, CancellationToken cancellation)
    {
        var watch = Stopwatch.StartNew();
        project.FileSetChanged = false;
        var design = await DesignOf(project.Target, cancellation);
        var directory = Path.GetDirectoryName(project.Target.ProjectPath)!;
        var arguments = CSharpCommandLineParser.Default.Parse(design.CommandLine, directory, sdkDirectory: null);
        if (!SameButFiles(project.CommandLine, project.Arguments, design.CommandLine, arguments, directory) || design.Framework != null && design.Framework != project.Framework)
        {
            HelperProtocol.Log("info", $"{project.Name}: its command line changed with its files, it loads again");
            return false;
        }
        var solution = workspace.CurrentSolution;
        var sources = arguments.SourceFiles.Select(f => f.Path).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var removed = project.Sources.Keys.Where(p => !sources.Contains(p)).ToList();
        foreach (var path in removed)
        {
            solution = solution.RemoveDocument(project.Sources[path].Id);
            project.Sources.Remove(path);
        }
        var added = sources.Where(p => !project.Sources.ContainsKey(p)).ToList();
        foreach (var path in added)
        {
            var documentId = DocumentId.CreateNewId(project.Id, path);
            solution = solution.AddDocument(Document(documentId, path));
            project.Sources[path] = (documentId, Stamp(path));
        }
        // the additional files: the driver keeps its texts by identity, so an unchanged one stays the same object
        var additional = arguments.AdditionalFiles.Select(f => f.Path).ToList();
        var additionalSet = additional.ToHashSet(StringComparer.OrdinalIgnoreCase);
        var goneTexts = project.AdditionalTexts.Where(t => !additionalSet.Contains(t.Path)).ToList();
        foreach (var text in goneTexts)
            if (project.Additional.Remove(text.Path, out var known) && solution.GetAdditionalDocument(known.Id) != null) solution = solution.RemoveAdditionalDocument(known.Id);
        var kept = project.AdditionalTexts.Where(t => additionalSet.Contains(t.Path)).ToDictionary(t => t.Path, StringComparer.OrdinalIgnoreCase);
        var newTexts = new List<AdditionalText>();
        foreach (var path in additional.Where(p => !kept.ContainsKey(p)).Distinct(StringComparer.OrdinalIgnoreCase))
        {
            newTexts.Add(new FileText(path));
            if (!File.Exists(path)) continue;
            var documentId = DocumentId.CreateNewId(project.Id, path);
            solution = solution.AddAdditionalDocument(Document(documentId, path));
            project.Additional[path] = (documentId, Stamp(path));
        }
        project.AdditionalTexts = additional.Select(p => kept.TryGetValue(p, out var text) ? text : newTexts.First(t => t.Path.Equals(p, StringComparison.OrdinalIgnoreCase))).ToList();
        // the build wrote the generated `.editorconfig` again: the metadata of the new additional files (Razor's TargetPath) is there
        foreach (var config in solution.GetProject(project.Id)!.AnalyzerConfigDocuments.Where(d => d.FilePath != null && Stamp(d.FilePath) != project.Inputs.GetValueOrDefault(d.FilePath)))
            solution = solution.WithAnalyzerConfigDocumentText(config.Id, Read(config.FilePath!));
        if (!workspace.TryApplyChanges(solution)) throw new HelperException($"{project.Name}: its new files could not be applied to the workspace");
        project.Arguments = arguments;
        project.CommandLine = design.CommandLine;
        project.BuildFiles = BuildFiles(sources, design.IntermediateDirectory ?? Path.Combine(directory, "obj"));
        project.Inputs.Clear();
        foreach (var input in design.Inputs.Append(project.Target.ProjectPath)) project.Inputs[Path.GetFullPath(input)] = Stamp(input);
        foreach (var config in arguments.AnalyzerConfigPaths) project.Inputs[config] = Stamp(config);
        if (project.Driver != null)
        {
            if (goneTexts.Count > 0) project.Driver = project.Driver.RemoveAdditionalTexts([.. goneTexts]);
            if (newTexts.Count > 0) project.Driver = project.Driver.AddAdditionalTexts([.. newTexts]);
            project.Driver = project.Driver.WithUpdatedAnalyzerConfigOptions(workspace.CurrentSolution.GetProject(project.Id)!.AnalyzerOptions.AnalyzerConfigOptionsProvider);
        }
        HelperProtocol.Log("info", $"{project.Name}: files listed again in {watch.ElapsedMilliseconds} ms: sources +{added.Count} -{removed.Count}, additional files +{newTexts.Count} -{goneTexts.Count}");
        return true;
    }

    /**
     * Whether two command lines of the compiler differ in their sources and additional files only: everything else — options, references,
     * analyzers, configs — in the same order.
     */
    internal static bool SameButFiles(List<string> before, CommandLineArguments parsedBefore, List<string> after, CommandLineArguments parsedAfter, string directory)
    {
        static List<string> Rest(List<string> line, CommandLineArguments parsed, string directory)
        {
            var sources = parsed.SourceFiles.Select(f => f.Path).ToHashSet(StringComparer.OrdinalIgnoreCase);
            return line.Where(token =>
            {
                var option = token.TrimStart('"');
                if (option.StartsWith("/additionalfile:", StringComparison.OrdinalIgnoreCase) || option.StartsWith("-additionalfile:", StringComparison.OrdinalIgnoreCase)) return false;
                try { return !sources.Contains(Path.GetFullPath(token.Trim('"'), directory)); }
                catch (Exception e) when (e is ArgumentException or NotSupportedException or PathTooLongException) { return true; }
            }).ToList();
        }
        return Rest(before, parsedBefore, directory).SequenceEqual(Rest(after, parsedAfter, directory), StringComparer.Ordinal);
    }

    /** Sources changed on disk since they were read are read again; a source that is gone lists the files of the project again at the next request. */
    private void SyncSources(LoadedProject project)
    {
        var solution = workspace.CurrentSolution;
        var changed = false;
        foreach (var (path, (documentId, stamp)) in project.Sources.ToList())
        {
            var now = Stamp(path);
            if (now == stamp) continue;
            if (now == DateTime.MinValue) { project.FileSetChanged = true; continue; }
            solution = solution.WithDocumentText(documentId, Read(path), PreservationMode.PreserveIdentity);
            project.Sources[path] = (documentId, now);
            changed = true;
        }
        // an additional file is the same object to the driver until it is replaced: an incremental generator would keep what it made of it
        for (var i = 0; i < project.AdditionalTexts.Count; i++)
        {
            var text = project.AdditionalTexts[i];
            if (!project.Additional.TryGetValue(text.Path, out var known)) continue;
            var now = Stamp(text.Path);
            if (now == known.Stamp || now == DateTime.MinValue) continue;
            var fresh = new FileText(text.Path);
            project.AdditionalTexts[i] = fresh;
            project.Driver = project.Driver?.ReplaceAdditionalText(text, fresh);
            if (solution.GetAdditionalDocument(known.Id) != null) solution = solution.WithAdditionalDocumentText(known.Id, Read(text.Path), PreservationMode.PreserveIdentity);
            project.Additional[text.Path] = (known.Id, now);
            changed = true;
        }
        if (changed) workspace.TryApplyChanges(solution);
        foreach (var reference in project.References) SyncSources(reference);
    }

    /**
     * The generators of [project] run on its compilation without what they made the last time (incrementally: the driver keeps its state);
     * what they make now replaces the generated documents of the workspace and the files on disk.
     */
    private async Task GenerateAsync(LoadedProject project, HashSet<LoadedProject> done, CancellationToken cancellation)
    {
        if (!done.Add(project)) return;
        foreach (var reference in project.References) await GenerateAsync(reference, done, cancellation);
        if (project.Driver == null) return;
        var compilation = await workspace.CurrentSolution.GetProject(project.Id)!.GetCompilationAsync(cancellation) ?? throw new HelperException($"{project.Name} has no compilation");
        var input = compilation.RemoveSyntaxTrees(compilation.SyntaxTrees.Where(t => project.Generated.ContainsKey(t.FilePath)));
        project.Driver = project.Driver.RunGenerators(input, cancellation);
        var run = project.Driver.GetRunResult();
        project.GeneratorDiagnostics = run.Diagnostics;
        project.GeneratorErrors.Clear();
        var solution = workspace.CurrentSolution;
        var made = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var result in run.Results)
        {
            if (result.Exception != null)
            {
                var name = result.Generator.GetGeneratorType().FullName;
                project.GeneratorErrors.Add($"{name}: {result.Exception.GetType().Name}: {result.Exception.Message}");
                HelperProtocol.Log("warn", $"{project.Name}: the generator {name} failed: {result.Exception}");
            }
            foreach (var source in result.GeneratedSources)
            {
                // Razor's hint names have folders with `/` (`Components/Alert/MudAlert_razor.g.cs`): one spelling, the one CleanUp sees on disk
                var path = Path.GetFullPath(source.SyntaxTree.FilePath);
                made.Add(path);
                Write(path, source.SourceText);
                if (project.Generated.TryGetValue(path, out var documentId))
                {
                    if (!(await solution.GetDocument(documentId)!.GetTextAsync(cancellation)).ContentEquals(source.SourceText))
                        solution = solution.WithDocumentText(documentId, source.SourceText, PreservationMode.PreserveIdentity);
                }
                else
                {
                    documentId = DocumentId.CreateNewId(project.Id, path);
                    solution = solution.AddDocument(DocumentInfo.Create(documentId, Path.GetFileName(path), loader: TextLoader.From(TextAndVersion.Create(source.SourceText, VersionStamp.Create(), path)), filePath: path));
                    project.Generated[path] = documentId;
                }
            }
        }
        foreach (var (path, documentId) in project.Generated.Where(g => !made.Contains(g.Key)).ToList())
        {
            solution = solution.RemoveDocument(documentId);
            project.Generated.Remove(path);
        }
        workspace.TryApplyChanges(solution);
        CleanUp(project.Target.OutputDirectory, made);
    }

    private async Task<List<Diagnostic>> DiagnosticsAsync(LoadedProject project, HashSet<string> paths, CancellationToken cancellation)
    {
        var compilation = await workspace.CurrentSolution.GetProject(project.Id)!.GetCompilationAsync(cancellation) ?? throw new HelperException($"{project.Name} has no compilation");
        var result = new List<Diagnostic>();
        result.AddRange(project.GeneratorDiagnostics.Where(d => d.Location.IsInSource && (paths.Count == 0 || paths.Contains(d.Location.GetLineSpan().Path))));
        if (project.Analyzers.Length > 0)
        {
            var options = new CompilationWithAnalyzersOptions(workspace.CurrentSolution.GetProject(project.Id)!.AnalyzerOptions,
                (exception, analyzer, _) => HelperProtocol.Log("warn", $"{project.Name}: the analyzer {analyzer.GetType().FullName} failed: {exception.Message}"),
                concurrentAnalysis: true, logAnalyzerExecutionTime: false, reportSuppressedDiagnostics: false);
            var analysis = compilation.WithAnalyzers(project.Analyzers, options);
            if (paths.Count == 0)
            {
                result.AddRange(await analysis.GetAnalyzerDiagnosticsAsync(cancellation));
            }
            else
            {
                foreach (var tree in compilation.SyntaxTrees.Where(t => paths.Contains(t.FilePath)))
                {
                    result.AddRange(await analysis.GetAnalyzerSyntaxDiagnosticsAsync(tree, cancellation));
                    result.AddRange(await analysis.GetAnalyzerSemanticDiagnosticsAsync(compilation.GetSemanticModel(tree), null, cancellation));
                }
            }
        }
        return result
            .Where(d => d.Location.IsInSource && d.Severity != DiagnosticSeverity.Hidden && !d.IsSuppressed && d.Id != "AD0001" && !IsGenerated(project, d.Location.GetLineSpan().Path))
            .Distinct()
            .OrderBy(d => d.Location.GetLineSpan().Path, StringComparer.OrdinalIgnoreCase).ThenBy(d => d.Location.SourceSpan.Start)
            .ToList();
    }

    /** The code fixes of [diagnostic], nested ones flattened ("Fix all" groups are not offered). */
    private static async Task<List<CodeAction>> ActionsAsync(LoadedProject project, Document document, Diagnostic diagnostic, CancellationToken cancellation)
    {
        var actions = new List<CodeAction>();
        foreach (var fixer in project.Fixers.Where(f => SafeIds(f).Contains(diagnostic.Id)))
        {
            try
            {
                await fixer.RegisterCodeFixesAsync(new CodeFixContext(document, diagnostic, (action, _) => { lock (actions) actions.Add(action); }, cancellation));
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                HelperProtocol.Log("warn", $"{project.Name}: the fix {fixer.GetType().FullName} failed on {diagnostic.Id}: {e.Message}");
            }
        }
        return actions.SelectMany(Flatten).ToList();
    }

    private static IEnumerable<CodeAction> Flatten(CodeAction action)
    {
        var nested = action.NestedActions;
        return nested.IsDefaultOrEmpty ? [action] : nested.SelectMany(Flatten);
    }

    private static ImmutableArray<string> SafeIds(CodeFixProvider fixer)
    {
        try { return fixer.FixableDiagnosticIds; }
        catch (Exception) { return []; }
    }

    // ---- files

    private static Document? DocumentOf(Solution solution, LoadedProject project, string path) =>
        project.Sources.TryGetValue(path, out var source) ? solution.GetDocument(source.Id) : null;

    private static bool IsGenerated(LoadedProject project, string path) =>
        path.StartsWith(project.Target.OutputDirectory + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase);

    private static object GeneratedFile(LoadedProject project, string path)
    {
        // <output>/<generator assembly>/<generator type>/<hint name>, as the driver names them
        var relative = Path.GetRelativePath(project.Target.OutputDirectory, path).Split(Path.DirectorySeparatorChar, 3);
        return new { path, generatorAssembly = relative.ElementAtOrDefault(0), generatorType = relative.ElementAtOrDefault(1), hintName = relative.ElementAtOrDefault(2) };
    }

    /**
     * The sources of the command line that the targets of the build made under [intermediate] (`obj/`): `MainWindow.g.cs` of XAML,
     * `Greet.cs` / `GreetGrpc.cs` of Grpc.Tools, a strongly typed resource class... The files every SDK project has there (global usings,
     * assembly info and attributes) are left out: the plugin knows what they say from the project.
     */
    internal static List<string> BuildFiles(IEnumerable<string> sources, string intermediate)
    {
        var root = intermediate.TrimEnd('\\', '/') + Path.DirectorySeparatorChar;
        return sources.Select(Path.GetFullPath)
            .Where(p => p.StartsWith(root, StringComparison.OrdinalIgnoreCase))
            .Where(p => !p.EndsWith(".GlobalUsings.g.cs", StringComparison.OrdinalIgnoreCase) && !p.EndsWith(".AssemblyInfo.cs", StringComparison.OrdinalIgnoreCase)
                && !p.EndsWith(".AssemblyAttributes.cs", StringComparison.OrdinalIgnoreCase) && !p.EndsWith(".RazorAssemblyInfo.cs", StringComparison.OrdinalIgnoreCase))
            .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
    }

    private static DocumentInfo Document(DocumentId id, string path) =>
        DocumentInfo.Create(id, Path.GetFileName(path), loader: TextLoader.From(TextAndVersion.Create(Read(path), VersionStamp.Create(), path)), filePath: path);

    private static SourceText Read(string path)
    {
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            return SourceText.From(stream, Encoding.UTF8, SourceHashAlgorithm.Sha256);
        }
        catch (IOException)
        {
            return SourceText.From("");
        }
    }

    private static void Write(string path, SourceText text)
    {
        var content = text.ToString();
        if (File.Exists(path) && File.ReadAllText(path) == content) return;
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        if (File.Exists(path)) File.SetAttributes(path, FileAttributes.Normal);
        File.WriteAllText(path, content, new UTF8Encoding(false));
        // read-only, as in Rider: an edit would be lost at the next run
        File.SetAttributes(path, FileAttributes.ReadOnly);
    }

    private static void CleanUp(string directory, HashSet<string> keep)
    {
        if (!Directory.Exists(directory)) return;
        foreach (var file in Directory.GetFiles(directory, "*", SearchOption.AllDirectories).Where(f => !keep.Contains(f)))
        {
            try { File.SetAttributes(file, FileAttributes.Normal); File.Delete(file); }
            catch (IOException) { }
        }
        foreach (var folder in Directory.GetDirectories(directory, "*", SearchOption.AllDirectories).OrderByDescending(d => d.Length))
            if (!Directory.EnumerateFileSystemEntries(folder).Any()) Directory.Delete(folder);
    }

    private static DateTime Stamp(string path) => File.Exists(path) ? File.GetLastWriteTimeUtc(path) : DateTime.MinValue;

    private const int MaxDiagnostics = 2000;
    private const int MaxDiagnosticsWithFixes = 300;
}

/**
 * The metadata of the referenced assemblies, one copy for every project and every load of a project: a reference read by
 * `MetadataReference.CreateFromFile` is the whole image in native memory, freed by a finalizer only — ten projects of one solution held ten
 * copies of the same reference packs, and each reload of a project (a renamed source file, a changed project file) one more, which the GC
 * did not see (the WSL sandbox, 2026-10-18: the helper of the playground from 590 to 870 MB in a few renames, its managed heap 35 MB).
 * Keyed by the path, its time stamp and length: a rebuilt assembly is read again, the compilations of the old one keep theirs.
 */
internal static class MetadataCache
{
    private static readonly ConcurrentDictionary<string, (DateTime Stamp, long Length, AssemblyMetadata Metadata)> Cache = new(StringComparer.OrdinalIgnoreCase);

    public static int Count => Cache.Count;

    public static MetadataReference Reference(string path, MetadataReferenceProperties properties)
    {
        var info = new FileInfo(path);
        var (stamp, length) = (info.LastWriteTimeUtc, info.Length);
        var entry = Cache.AddOrUpdate(path, p => (stamp, length, Read(p)), (p, old) => old.Stamp == stamp && old.Length == length ? old : (stamp, length, Read(p)));
        return entry.Metadata.GetReference(aliases: properties.Aliases, embedInteropTypes: properties.EmbedInteropTypes, filePath: path);
    }

    /** The image read into memory and the file closed, as CreateFromFile reads it: an assembly of the solution can still be built. */
    private static AssemblyMetadata Read(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        return AssemblyMetadata.CreateFromStream(stream, System.Reflection.PortableExecutable.PEStreamOptions.PrefetchEntireImage);
    }
}

/** An additional file of the project (`<AdditionalFiles>`), read when a generator or an analyzer asks. */
internal sealed class FileText(string path) : AdditionalText
{
    public override string Path { get; } = path;
    private readonly Lazy<SourceText?> text = new(() =>
    {
        if (!File.Exists(path)) return null;
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        // with the checksum of the build (SHA-256): Razor's generator writes it into `#pragma checksum` and RazorSourceChecksumAttribute
        return SourceText.From(stream, Encoding.UTF8, SourceHashAlgorithm.Sha256);
    });

    // a snapshot, read once: a generator compares the old text with the new one (Razor does), and a file read again would be the new
    // one both times — a changed file is a new FileText (Projects.SyncSources)
    public override SourceText? GetText(CancellationToken cancellationToken = default) => text.Value;
}

/**
 * Loads analyzer assemblies from their bytes, one load context per folder (a package with its own dependencies does not meet another
 * version of them), so the files are not locked: an analyzer project of the solution can still be built. Roslyn itself and the framework
 * come from the helper.
 */
internal sealed class AnalyzerLoader : IAnalyzerAssemblyLoader
{
    public static readonly AnalyzerLoader Instance = new();
    private readonly ConcurrentDictionary<string, FolderContext> contexts = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, Assembly> assemblies = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, List<CodeFixProvider>> fixers = new(StringComparer.OrdinalIgnoreCase);

    private readonly ConcurrentDictionary<string, Loaded> references = new(StringComparer.OrdinalIgnoreCase);

    /** The generators and analyzers of C# of an analyzer assembly. */
    public sealed record Loaded(DateTime Stamp, long Length, ImmutableArray<ISourceGenerator> Generators, ImmutableArray<DiagnosticAnalyzer> Analyzers);

    /**
     * The generators and analyzers of [path], read once per version of the file for every project and every load of one: a new
     * AnalyzerFileReference reads the metadata of its assembly again, and a reload of the projects of a solution (400 analyzer assemblies
     * each) piled those copies up (MetadataCache says the same of the references). The instances are stateless, as Roslyn shares them.
     */
    public Loaded Reference(string path)
    {
        var info = new FileInfo(path);
        var (stamp, length) = (info.LastWriteTimeUtc, info.Length);
        if (references.TryGetValue(path, out var known) && known.Stamp == stamp && known.Length == length) return known;
        var reference = new AnalyzerFileReference(path, this);
        reference.AnalyzerLoadFailed += (_, e) => HelperProtocol.Log("warn", $"{Path.GetFileName(path)}: {e.Message}");
        var loaded = new Loaded(stamp, length, reference.GetGenerators(LanguageNames.CSharp), reference.GetAnalyzers(LanguageNames.CSharp));
        references[path] = loaded;
        return loaded;
    }

    public void AddDependencyLocation(string fullPath) { }


    public Assembly LoadFromPath(string fullPath) => assemblies.GetOrAdd(fullPath, path =>
    {
        var folder = Path.GetDirectoryName(path)!;
        return contexts.GetOrAdd(folder, f => new FolderContext(f)).LoadFile(path);
    });

    /** The code fixes of C# an analyzer assembly exports, made once per assembly. */
    public List<CodeFixProvider> Fixers(string path) => fixers.GetOrAdd(path, p =>
    {
        var found = new List<CodeFixProvider>();
        Type?[] types;
        try { types = LoadFromPath(p).GetTypes(); }
        catch (ReflectionTypeLoadException e) { types = e.Types; }
        catch (Exception) { return found; }
        foreach (var type in types)
        {
            if (type == null || type.IsAbstract || !typeof(CodeFixProvider).IsAssignableFrom(type)) continue;
            var export = type.GetCustomAttribute<ExportCodeFixProviderAttribute>();
            if (export == null || !export.Languages.Contains(LanguageNames.CSharp)) continue;
            try
            {
                if (Activator.CreateInstance(type) is CodeFixProvider fixer) found.Add(fixer);
            }
            catch (Exception) { }
        }
        return found;
    });

    private sealed class FolderContext(string folder) : AssemblyLoadContext($"analyzers: {folder}")
    {
        public Assembly LoadFile(string path)
        {
            var name = AssemblyName.GetAssemblyName(path);
            if (Shared(name)) return Default.LoadFromAssemblyName(name);
            using var stream = new MemoryStream(File.ReadAllBytes(path));
            return LoadFromStream(stream);
        }

        protected override Assembly? Load(AssemblyName name)
        {
            if (Shared(name)) return null;
            var candidate = Path.Combine(folder, name.Name + ".dll");
            if (!File.Exists(candidate)) return null;
            using var stream = new MemoryStream(File.ReadAllBytes(candidate));
            return LoadFromStream(stream);
        }

        /**
         * Roslyn, its Workspaces and what the framework has: one copy for all, the helper's. Roslyn is what the helper carries next to itself,
         * not every `Microsoft.CodeAnalysis.*`: Razor's generator (`Microsoft.CodeAnalysis.Razor.Compiler`) and the analyzers of packages
         * come from their own folders.
         */
        private static bool Shared(AssemblyName name) =>
            name.Name != null && (name.Name.StartsWith("Microsoft.CodeAnalysis", StringComparison.Ordinal) && File.Exists(Path.Combine(AppContext.BaseDirectory, name.Name + ".dll"))
                || name.Name.StartsWith("System.Composition", StringComparison.Ordinal) || Default.Assemblies.Any(a => a.GetName().Name == name.Name));
    }
}

/** The design-time `Compile` of a project: the command line of its compiler, without running it, and the projects behind its references. */
internal static class DesignTime
{
    public sealed record Result(List<string> CommandLine, string? Framework, Dictionary<string, (string Project, string? Framework)> ProjectOutputs, List<string> Inputs, bool LiveAnalyzers, string? IntermediateDirectory, bool DefaultFramework);

    /**
     * The design-time build of [target], from the disk cache (DesignTimeCache) while nothing it read has changed; otherwise MSBuild runs
     * and its result is cached with what it read.
     */
    public static async Task<Result> CompileAsync(Target target, CancellationToken cancellation)
    {
        var watch = Stopwatch.StartNew();
        var name = Path.GetFileName(target.ProjectPath);
        var key = DesignTimeCache.Key(target);
        var file = DesignTimeCache.FileOf(target);
        var environment = DesignTimeCache.EnvironmentHash(Environment.GetEnvironmentVariables());
        var entry = DesignTimeCache.Read(file);
        var stale = DesignTimeCache.Stale(entry, key, environment, RealDisk.Instance);
        if (stale == null)
        {
            HelperProtocol.Log("info", $"design-time build of {name} ({entry!.Framework}) from the cache in {watch.ElapsedMilliseconds} ms");
            return DesignTimeCache.ResultOf(entry);
        }
        HelperProtocol.Log("info", $"design-time cache of {name} ({target.Framework ?? "default framework"}) not used: {stale} ({watch.ElapsedMilliseconds} ms)");
        var started = DateTime.UtcNow;
        var directory = Path.GetDirectoryName(target.ProjectPath)!;
        var before = DesignTimeCache.Take(target.ProjectPath, [], [], [], RealDisk.Instance);
        var binlog = Path.Combine(Path.GetTempPath(), $"codeanalysis-{Guid.NewGuid():N}.binlog");
        var zip = Path.ChangeExtension(binlog, ".ProjectImports.zip");
        try
        {
            var (result, exitCode) = await RunAsync(target, binlog, cancellation);
            if (exitCode != 0) { HelperProtocol.Log("info", $"design-time build of {name} not cached: MSBuild exited with {exitCode}"); return result; }
            if (!File.Exists(zip)) { HelperProtocol.Log("warn", $"design-time build of {name} not cached: MSBuild wrote no list of imports"); return result; }
            try
            {
                var imports = DesignTimeCache.ImportsOf(zip);
                var skipped = OutputFolders(result, directory);
                var immutable = PackageFolders(result.Inputs).Append(Path.GetDirectoryName(Environment.ProcessPath ?? "") ?? "").Where(p => p.Length > 0).ToList();
                var sources = DesignTimeCache.CommandLineFiles(result.CommandLine, directory, globbedOnly: true).ToList();
                var after = DesignTimeCache.Take(target.ProjectPath, sources, skipped, immutable, RealDisk.Instance);
                var (made, why) = DesignTimeCache.Make(key, environment, result, target.ProjectPath, imports, before, after, started, skipped, RealDisk.Instance);
                if (made == null) HelperProtocol.Log("info", $"design-time build of {name} not cached: {why}");
                else
                {
                    DesignTimeCache.Write(file, made);
                    HelperProtocol.Log("info", $"design-time build of {name} cached: {made.Files.Count} files, {made.Trees.Count} folders, {made.Existing.Count} named files watched");
                }
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or InvalidDataException)
            {
                // the build is good, only its cache is not: the next load runs MSBuild again
                HelperProtocol.Log("warn", $"design-time build of {name} not cached: {e.Message}");
            }
            return result;
        }
        finally
        {
            foreach (var temporary in (string[])[binlog, zip])
                try { File.Delete(temporary); } catch (Exception) { }
        }
    }

    /** The folders a build of the project writes, when they are not named bin / obj (a custom BaseIntermediateOutputPath, artifacts/). */
    private static List<string> OutputFolders(Result result, string directory)
    {
        var folders = new List<string>();
        if (result.IntermediateDirectory != null) folders.Add(result.IntermediateDirectory.TrimEnd('\\', '/'));
        var output = result.CommandLine.FirstOrDefault(a => a.StartsWith("/out:", StringComparison.OrdinalIgnoreCase));
        if (output != null)
        {
            try { folders.Add(Path.GetDirectoryName(Path.GetFullPath(output[5..].Trim('"'), directory))!); }
            catch (Exception) { }
        }
        return folders;
    }

    /** The folders of NuGet packages the restore named (project.assets.json): a package is a folder per version, never changed in place. */
    private static List<string> PackageFolders(List<string> inputs)
    {
        var folders = new List<string>();
        foreach (var assets in inputs.Where(p => p.EndsWith("project.assets.json", StringComparison.OrdinalIgnoreCase) && File.Exists(p)))
        {
            try
            {
                using var stream = File.OpenRead(assets);
                using var json = JsonDocument.Parse(stream);
                if (json.RootElement.TryGetProperty("packageFolders", out var packageFolders))
                    foreach (var folder in packageFolders.EnumerateObject()) folders.Add(Path.GetFullPath(folder.Name).TrimEnd('\\', '/'));
            }
            catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException) { }
        }
        return folders;
    }

    private static async Task<(Result Result, int ExitCode)> RunAsync(Target target, string binlog, CancellationToken cancellation)
    {
        var framework = target.Framework;
        var exitCode = 0;
        if (string.IsNullOrEmpty(framework))
        {
            var (properties, propertiesExit) = await MsBuildAsync(target.ProjectPath, ["-getProperty:TargetFramework", "-getProperty:TargetFrameworks", $"-p:Configuration={target.Configuration}"], cancellation);
            using var _ = properties;
            exitCode = propertiesExit;
            var all = properties.RootElement.GetProperty("Properties");
            var single = all.GetProperty("TargetFramework").GetString();
            var many = all.GetProperty("TargetFrameworks").GetString();
            framework = !string.IsNullOrEmpty(single) ? single : many?.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).FirstOrDefault();
        }
        List<string> args = ["-t:Compile", "-p:SkipCompilerExecution=true", "-p:ProvideCommandLineArgs=true", "-p:DesignTimeBuild=true", "-p:BuildProjectReferences=false",
            "-p:NonExistentFile=__NonExistentSubDir__/__NonExistentFile__", $"-p:Configuration={target.Configuration}", "-getItem:CscCommandLineArgs",
            "-getItem:ReferencePathWithRefAssemblies", "-getProperty:ProjectAssetsFile", "-getProperty:TargetFramework", "-getProperty:TargetFrameworks", "-getProperty:RunAnalyzers", "-getProperty:RunAnalyzersDuringLiveAnalysis", "-getProperty:BaseIntermediateOutputPath"];
        if (!string.IsNullOrEmpty(framework)) args.Add($"-p:TargetFramework={framework}");
        // the binary log only for the zip of the imports it writes next to itself: every file the evaluations read, for the cache
        // (measured on MudBlazor.Docs: 1.50 s with it, 1.44 s without)
        args.Add($"-bl:{binlog};ProjectImports=ZipFile");
        var (json, compileExit) = await MsBuildAsync(target.ProjectPath, args, cancellation);
        using var __ = json;
        if (compileExit != 0) exitCode = compileExit;
        var root = json.RootElement;
        var items = root.GetProperty("Items");
        var commandLine = items.TryGetProperty("CscCommandLineArgs", out var csc) ? csc.EnumerateArray().Select(i => i.GetProperty("Identity").GetString()!).ToList() : [];
        if (commandLine.Count == 0) throw new HelperException($"{Path.GetFileName(target.ProjectPath)}: MSBuild gave no command line of the compiler (not a C# project, or it is not restored)");
        var directory = Path.GetDirectoryName(target.ProjectPath)!;
        var outputs = new Dictionary<string, (string, string?)>(StringComparer.OrdinalIgnoreCase);
        if (items.TryGetProperty("ReferencePathWithRefAssemblies", out var references))
            foreach (var reference in references.EnumerateArray())
            {
                if (!reference.TryGetProperty("ReferenceSourceTarget", out var kind) || kind.GetString() != "ProjectReference" || !reference.TryGetProperty("MSBuildSourceProjectFile", out var source)) continue;
                string? referencedFramework = null;
                if (reference.TryGetProperty("SetTargetFramework", out var set) && set.GetString() is { Length: > 0 } text && text.StartsWith("TargetFramework=")) referencedFramework = text["TargetFramework=".Length..];
                outputs[Path.GetFullPath(reference.GetProperty("Identity").GetString()!, directory)] = (source.GetString()!, referencedFramework);
                if (reference.TryGetProperty("OriginalItemSpec", out var original)) outputs[Path.GetFullPath(original.GetString()!, directory)] = (source.GetString()!, referencedFramework);
            }
        var inputs = new List<string>();
        var props = root.GetProperty("Properties");
        if (props.TryGetProperty("ProjectAssetsFile", out var assets) && assets.GetString() is { Length: > 0 } assetsPath) inputs.Add(Path.GetFullPath(assetsPath, directory));
        var actual = props.TryGetProperty("TargetFramework", out var tf) ? tf.GetString() : null;
        // as Visual Studio: `RunAnalyzers` false, or `RunAnalyzersDuringLiveAnalysis` false without it, turns the analyzers off in the editor
        var run = props.TryGetProperty("RunAnalyzers", out var ra) ? ra.GetString() : null;
        var live = props.TryGetProperty("RunAnalyzersDuringLiveAnalysis", out var la) ? la.GetString() : null;
        var liveAnalyzers = !(string.Equals(run, "false", StringComparison.OrdinalIgnoreCase) || string.IsNullOrEmpty(run) && string.Equals(live, "false", StringComparison.OrdinalIgnoreCase));
        var intermediate = props.TryGetProperty("BaseIntermediateOutputPath", out var bi) && bi.GetString() is { Length: > 0 } obj ? Path.GetFullPath(obj, directory) : null;
        // the framework a build without one picks (the only one, or the first of TargetFrameworks): a reference without a framework means it
        var frameworks = props.TryGetProperty("TargetFrameworks", out var tfs) ? tfs.GetString()?.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries) : null;
        var resolved = string.IsNullOrEmpty(actual) ? framework : actual;
        var byDefault = frameworks is not { Length: > 0 } || string.Equals(frameworks[0], resolved, StringComparison.OrdinalIgnoreCase);
        return (new Result(commandLine, resolved, outputs, inputs, liveAnalyzers, intermediate, byDefault), exitCode);
    }

    /**
     * How many `dotnet msbuild` run at once (the design-time builds of references fetched ahead, Projects.Prefetch): half the processors,
     * at least two — each is a process of its own with an evaluation that takes a core, and the IDE and the generators need the rest.
     */
    private static readonly SemaphoreSlim Slots = new(Math.Max(2, Environment.ProcessorCount / 2));

    private static async Task<(JsonDocument Json, int ExitCode)> MsBuildAsync(string project, IEnumerable<string> args, CancellationToken cancellation)
    {
        await Slots.WaitAsync(cancellation);
        try
        {
            return await RunMsBuildAsync(project, args, cancellation);
        }
        finally
        {
            Slots.Release();
        }
    }

    private static async Task<(JsonDocument Json, int ExitCode)> RunMsBuildAsync(string project, IEnumerable<string> args, CancellationToken cancellation)
    {
        // the dotnet that runs the helper: the plugin started it in the folder of the solution, so a global.json there counts
        var info = new ProcessStartInfo(Environment.ProcessPath ?? "dotnet")
        {
            RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, WorkingDirectory = Path.GetDirectoryName(project)!,
            StandardOutputEncoding = Encoding.UTF8,
        };
        info.Environment["DOTNET_CLI_UI_LANGUAGE"] = "en";
        info.Environment["DOTNET_NOLOGO"] = "1";
        info.Environment["DOTNET_SKIP_FIRST_TIME_EXPERIENCE"] = "1";
        info.Environment["MSBUILDDISABLENODEREUSE"] = "1";
        foreach (var a in (string[])["msbuild", project, "-nologo", "-v:q", "-nr:false"]) info.ArgumentList.Add(a);
        foreach (var a in args) info.ArgumentList.Add(a);
        var watch = Stopwatch.StartNew();
        using var process = Process.Start(info) ?? throw new HelperException("dotnet msbuild could not be started");
        await using var registration = cancellation.Register(() => { try { process.Kill(true); } catch (Exception) { } });
        var errors = process.StandardError.ReadToEndAsync(cancellation);
        var output = await process.StandardOutput.ReadToEndAsync(cancellation);
        await process.WaitForExitAsync(cancellation);
        HelperProtocol.Log("info", $"dotnet msbuild {Path.GetFileName(project)} {string.Join(' ', args.Where(a => a.StartsWith("-t:") || a.StartsWith("-p:TargetFramework")))}: {watch.ElapsedMilliseconds} ms");
        // errors of the project (it does not compile) come before the JSON; the items are there anyway
        var start = output.IndexOf('{');
        if (start < 0) throw new HelperException($"dotnet msbuild {Path.GetFileName(project)} failed (exit code {process.ExitCode}): {(output + await errors).Trim()[..Math.Min(1500, (output + await errors).Trim().Length)]}");
        return (JsonDocument.Parse(output[start..]), process.ExitCode);
    }
}

internal static class Json
{
    public static string? Text(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    public static IEnumerable<string> Texts(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Array
            ? value.EnumerateArray().Where(v => v.ValueKind == JsonValueKind.String).Select(v => v.GetString()!)
            : [];
}
