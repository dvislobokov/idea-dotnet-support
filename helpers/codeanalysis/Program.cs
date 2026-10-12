// CodeAnalysisHelper: the source generators and the Roslyn analyzers of the projects of one solution, for the plugin without the language
// server (CSHARP_PSI_MIGRATION.md, D3 / D4). A helper that stays running: `CodeAnalysisHelper --serve`, the protocol is in Protocol.cs.
//
//   generate    {projectPath, configuration, targetFramework?, outputRoot}
//            -> {project, framework, files: [{path, generatorAssembly, generatorType, hintName}], buildFiles: [paths], errors: [..], milliseconds}
//               runs the generators of the project (Razor's left out) and writes what they make under outputRoot, as
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
// their generators make are seen. One request at a time.
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
    public readonly CSharpCommandLineArguments Arguments = arguments;
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
    public GeneratorDriver? Driver;
    public ImmutableArray<Diagnostic> GeneratorDiagnostics = [];

    public string Name => Path.GetFileNameWithoutExtension(Target.ProjectPath);
}

internal sealed class Projects
{
    private readonly AdhocWorkspace workspace = new(MefHostServices.DefaultHost);
    private readonly Dictionary<string, LoadedProject> loaded = new();

    public int Count => loaded.Count;

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

    public object Invalidate(List<string> paths)
    {
        var dropped = 0;
        foreach (var project in loaded.Values.ToList())
        {
            var directory = Path.GetDirectoryName(project.Target.ProjectPath)! + Path.DirectorySeparatorChar;
            var touched = paths.Any(p =>
            {
                var full = Path.GetFullPath(p);
                return project.Inputs.ContainsKey(full) || full.StartsWith(directory, StringComparison.OrdinalIgnoreCase) && !full.EndsWith(".cs", StringComparison.OrdinalIgnoreCase)
                    || full.EndsWith(".cs", StringComparison.OrdinalIgnoreCase) && full.StartsWith(directory, StringComparison.OrdinalIgnoreCase) && !project.Sources.ContainsKey(full)
                    || Path.GetFileName(full).StartsWith("Directory.", StringComparison.OrdinalIgnoreCase);
            });
            if (!touched) continue;
            Drop(project);
            dropped++;
        }
        return new { dropped };
    }

    // ---- the compilation of a project

    /** [target] loaded (again when its inputs have changed), its sources as they are on disk, its generated files made again. */
    private async Task<LoadedProject> PrepareAsync(Target target, CancellationToken cancellation)
    {
        var project = await LoadAsync(target, [], cancellation);
        await GenerateAsync(project, [], cancellation);
        return project;
    }

    private async Task<LoadedProject> LoadAsync(Target target, HashSet<string> visiting, CancellationToken cancellation)
    {
        if (loaded.TryGetValue(target.Key, out var known))
        {
            if (known.Inputs.All(i => Stamp(i.Key) == i.Value) && known.References.All(r => loaded.ContainsKey(r.Target.Key)))
            {
                SyncSources(known);
                return known;
            }
            Drop(known);
        }
        if (!visiting.Add(target.Key)) throw new HelperException($"{Path.GetFileName(target.ProjectPath)} references itself");
        var project = await LoadNewAsync(target, visiting, cancellation);
        loaded[target.Key] = project;
        return project;
    }

    private void Drop(LoadedProject project)
    {
        loaded.Remove(project.Target.Key);
        var solution = workspace.CurrentSolution;
        if (solution.GetProject(project.Id) != null) workspace.TryApplyChanges(solution.RemoveProject(project.Id));
        // who referenced it is loaded again with it
        foreach (var other in loaded.Values.Where(o => o.References.Contains(project)).ToList()) Drop(other);
    }

    private async Task<LoadedProject> LoadNewAsync(Target target, HashSet<string> visiting, CancellationToken cancellation)
    {
        var watch = Stopwatch.StartNew();
        var design = await DesignTime.CompileAsync(target, cancellation);
        var directory = Path.GetDirectoryName(target.ProjectPath)!;
        var arguments = CSharpCommandLineParser.Default.Parse(design.CommandLine, directory, sdkDirectory: null);
        var id = ProjectId.CreateNewId(target.ProjectPath);
        var project = new LoadedProject(target, id, arguments, design.Framework ?? target.Framework ?? "");
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
                    var other = await LoadAsync(new Target(source.Project, target.Configuration, source.Framework, Target.OutputOf(Path.GetDirectoryName(target.OutputDirectory)!, Path.GetFullPath(source.Project))), visiting, cancellation);
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
            if (File.Exists(path)) metadata.Add(MetadataReference.CreateFromFile(path, reference.Properties));
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
        var additional = arguments.AdditionalFiles.Where(f => File.Exists(f.Path)).Select(f => Document(DocumentId.CreateNewId(id, f.Path), f.Path)).ToList();
        var info = ProjectInfo.Create(id, VersionStamp.Create(), project.Name, Path.GetFileNameWithoutExtension(arguments.OutputFileName ?? project.Name), LanguageNames.CSharp,
                filePath: target.ProjectPath, outputFilePath: arguments.OutputFileName == null ? null : Path.Combine(arguments.OutputDirectory, arguments.OutputFileName),
                compilationOptions: arguments.CompilationOptions, parseOptions: arguments.ParseOptions, documents: documents, projectReferences: projectReferences,
                metadataReferences: metadata, additionalDocuments: additional)
            .WithAnalyzerConfigDocuments(configs);
        if (!workspace.TryApplyChanges(workspace.CurrentSolution.AddProject(info))) throw new HelperException($"{project.Name} could not be added to the workspace");
        project.AdditionalTexts = arguments.AdditionalFiles.Select(f => (AdditionalText)new FileText(f.Path)).ToList();

        // analyzers and generators: those of the command line (packages, the SDK), the code style of the SDK, Razor's generators left out
        var analyzerPaths = arguments.AnalyzerReferences.Select(a => Path.GetFullPath(a.FilePath, directory)).ToList();
        foreach (var codeStyle in Sdk.CodeStyleAnalyzers())
            if (!analyzerPaths.Any(p => Path.GetFileName(p).Equals(Path.GetFileName(codeStyle), StringComparison.OrdinalIgnoreCase))) analyzerPaths.Add(codeStyle);
        var generators = new List<ISourceGenerator>();
        var analyzers = new List<DiagnosticAnalyzer>();
        // Razor left out entirely: its compiler is built against a newer Roslyn than the SDK's and is about .razor files
        foreach (var path in analyzerPaths.Where(p => File.Exists(p) && !IsRazor(p)))
        {
            var reference = new AnalyzerFileReference(path, AnalyzerLoader.Instance);
            reference.AnalyzerLoadFailed += (_, e) => HelperProtocol.Log("warn", $"{project.Name}: {Path.GetFileName(path)}: {e.Message}");
            generators.AddRange(reference.GetGenerators(LanguageNames.CSharp));
            if (!design.LiveAnalyzers) continue;
            analyzers.AddRange(reference.GetAnalyzers(LanguageNames.CSharp));
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

    /** Sources changed on disk since they were read are read again; a source that is gone loads the project again at the next request. */
    private void SyncSources(LoadedProject project)
    {
        var solution = workspace.CurrentSolution;
        var changed = false;
        foreach (var (path, (documentId, stamp)) in project.Sources.ToList())
        {
            var now = Stamp(path);
            if (now == stamp) continue;
            if (now == DateTime.MinValue) { project.Inputs[path] = DateTime.MaxValue; continue; }
            solution = solution.WithDocumentText(documentId, Read(path), PreservationMode.PreserveIdentity);
            project.Sources[path] = (documentId, now);
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
                var path = source.SyntaxTree.FilePath;
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

    private static bool IsRazor(string path) => Path.GetFileName(path).Contains("Razor", StringComparison.OrdinalIgnoreCase);

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

/** An additional file of the project (`<AdditionalFiles>`), read when a generator or an analyzer asks. */
internal sealed class FileText(string path) : AdditionalText
{
    public override string Path { get; } = path;
    public override SourceText? GetText(CancellationToken cancellationToken = default) =>
        File.Exists(Path) ? SourceText.From(File.ReadAllText(Path), Encoding.UTF8) : null;
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

        /** Roslyn, its Workspaces and what the framework has: one copy for all, the helper's. */
        private static bool Shared(AssemblyName name) =>
            name.Name != null && (name.Name.StartsWith("Microsoft.CodeAnalysis", StringComparison.Ordinal) && !name.Name.Contains("Analyzers") && !name.Name.Contains("CodeStyle") && !name.Name.Contains("NetAnalyzers")
                || name.Name.StartsWith("System.Composition", StringComparison.Ordinal) || Default.Assemblies.Any(a => a.GetName().Name == name.Name));
    }
}

/** The design-time `Compile` of a project: the command line of its compiler, without running it, and the projects behind its references. */
internal static class DesignTime
{
    public sealed record Result(List<string> CommandLine, string? Framework, Dictionary<string, (string Project, string? Framework)> ProjectOutputs, List<string> Inputs, bool LiveAnalyzers, string? IntermediateDirectory);

    public static async Task<Result> CompileAsync(Target target, CancellationToken cancellation)
    {
        var framework = target.Framework;
        if (string.IsNullOrEmpty(framework))
        {
            using var properties = await MsBuildAsync(target.ProjectPath, ["-getProperty:TargetFramework", "-getProperty:TargetFrameworks", $"-p:Configuration={target.Configuration}"], cancellation);
            var all = properties.RootElement.GetProperty("Properties");
            var single = all.GetProperty("TargetFramework").GetString();
            var many = all.GetProperty("TargetFrameworks").GetString();
            framework = !string.IsNullOrEmpty(single) ? single : many?.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).FirstOrDefault();
        }
        List<string> args = ["-t:Compile", "-p:SkipCompilerExecution=true", "-p:ProvideCommandLineArgs=true", "-p:DesignTimeBuild=true", "-p:BuildProjectReferences=false",
            "-p:NonExistentFile=__NonExistentSubDir__/__NonExistentFile__", $"-p:Configuration={target.Configuration}", "-getItem:CscCommandLineArgs",
            "-getItem:ReferencePathWithRefAssemblies", "-getProperty:ProjectAssetsFile", "-getProperty:TargetFramework", "-getProperty:RunAnalyzers", "-getProperty:RunAnalyzersDuringLiveAnalysis", "-getProperty:BaseIntermediateOutputPath"];
        if (!string.IsNullOrEmpty(framework)) args.Add($"-p:TargetFramework={framework}");
        using var json = await MsBuildAsync(target.ProjectPath, args, cancellation);
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
        return new Result(commandLine, string.IsNullOrEmpty(actual) ? framework : actual, outputs, inputs, liveAnalyzers, intermediate);
    }

    private static async Task<JsonDocument> MsBuildAsync(string project, IEnumerable<string> args, CancellationToken cancellation)
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
        return JsonDocument.Parse(output[start..]);
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
