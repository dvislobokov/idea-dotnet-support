// `roslyndump semantics`: Roslyn's semantic view of the files of a project, the oracle of csharp-psi's semantic layers
// (CSHARP_PSI_MIGRATION.md, step 11, task C0). Format: README.md next to this file, section "semantics".
using System.Collections.Immutable;
using System.Diagnostics;
using System.Reflection;
using System.Text;
using System.Text.Json;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.Diagnostics;

namespace RoslynDump;

static class Semantics
{
    public const int FormatVersion = 1;

    /// <summary>What the command line gave besides the input.</summary>
    public sealed class Options
    {
        public string? Root;
        public string? OutPath;
        public string Configuration = "Debug";
        public string? Framework;
        public bool Generators = true;
        // Files mode only: the compilation is made here, not by MSBuild.
        public List<string> Defines = [];
        public LanguageVersion LanguageVersion = LanguageVersion.Preview;
        public List<string> References = [];
        public List<string> Usings = [];
        public List<string> Includes = [];
        // Files mode: the name of the compilation; a reference assembly of that name is left out (the sources are that library).
        public string? Assembly;
    }

    /// <summary>A compilation, the files of it to dump, its trees made by source generators and those of every compilation involved.</summary>
    sealed record Input(string Name, CSharpCompilation Compilation, List<SyntaxTree> Dumped, List<SyntaxTree> Generated, HashSet<SyntaxTree> AllGenerated);

    public static int Run(string input, Options options)
    {
        var watch = Stopwatch.StartNew();
        input = Path.GetFullPath(input);
        Input built;
        string root;
        if (input.EndsWith("proj", StringComparison.OrdinalIgnoreCase) && File.Exists(input))
        {
            root = Path.GetFullPath(options.Root ?? Path.GetDirectoryName(input)!);
            var projects = new ProjectCompilations(options);
            var compilation = projects.Get(input);
            if (compilation == null) return 1;
            var projectDir = Path.GetDirectoryName(input)! + Path.DirectorySeparatorChar;
            var dumped = compilation.SyntaxTrees
                .Where(t => t.FilePath.StartsWith(projectDir, StringComparison.OrdinalIgnoreCase) && !UnderBuildOutput(Path.GetRelativePath(projectDir, t.FilePath)))
                .ToList();
            built = Finish(Path.GetRelativePath(root, input).Replace('\\', '/'), compilation, dumped, projects.Generated);
        }
        else if (Directory.Exists(input) || File.Exists(input))
        {
            root = Path.GetFullPath(options.Root ?? (Directory.Exists(input) ? input : Path.GetDirectoryName(input)!));
            built = Finish(Path.GetRelativePath(root, input).Replace('\\', '/'), FilesCompilation(input, options, out var dumped), dumped, []);
        }
        else
        {
            Console.Error.WriteLine($"no such project, file or directory: {input}");
            return 2;
        }
        if (options.Includes.Count > 0)
            built.Dumped.RemoveAll(t => !options.Includes.Any(p => Relative(root, t.FilePath) is var r && (r == p || r.StartsWith(p + "/", StringComparison.Ordinal))));
        built.Dumped.Sort((a, b) => string.CompareOrdinal(Relative(root, a.FilePath), Relative(root, b.FilePath)));

        using var output = options.OutPath == null
            ? new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = false }
            : new StreamWriter(options.OutPath, false, new UTF8Encoding(false));
        var writer = new Writer(output, root, built);
        writer.Header();
        foreach (var tree in built.Dumped) writer.File(tree);
        output.Flush();
        Console.Error.WriteLine($"# semantics files={built.Dumped.Count} names={writer.Names} bound={writer.Bound} candidates={writer.Candidates} " +
                                $"expressions={writer.Expressions} typed={writer.Typed} diagnostics={writer.Diagnostics} millis={watch.ElapsedMilliseconds}");
        return 0;
    }

    static bool UnderBuildOutput(string relative) => relative.Replace('\\', '/').Split('/').Any(p => p is "bin" or "obj");

    static string Relative(string root, string path) => Path.GetRelativePath(root, path).Replace('\\', '/');

    // The IntelliJ document has LF line separators and no BOM; offsets are compared in that text (as in `tree`).
    internal static SyntaxTree Parse(string path, CSharpParseOptions options) =>
        CSharpSyntaxTree.ParseText(File.ReadAllText(path).Replace("\r\n", "\n").Replace('\r', '\n'), options, path, Encoding.UTF8);

    static Input Finish(string name, CSharpCompilation compilation, List<SyntaxTree> dumped, HashSet<SyntaxTree> generated) =>
        new(name, compilation, dumped, compilation.SyntaxTrees.Where(generated.Contains).ToList(), generated);

    // ---- files mode

    static CSharpCompilation FilesCompilation(string input, Options options, out List<SyntaxTree> dumped)
    {
        var parse = new CSharpParseOptions(options.LanguageVersion, DocumentationMode.Parse, SourceCodeKind.Regular, options.Defines);
        var files = File.Exists(input)
            ? [input]
            : Directory.EnumerateFiles(input, "*.cs", SearchOption.AllDirectories)
                .Where(f => !Path.GetRelativePath(input, f).Split(Path.DirectorySeparatorChar).Any(p => p is "bin" or "obj" or ".git"))
                .Order(StringComparer.Ordinal).ToList();
        dumped = files.Select(f => Parse(f, parse)).ToList();
        var trees = new List<SyntaxTree>(dumped);
        if (options.Usings.Count > 0)
            trees.Add(CSharpSyntaxTree.ParseText(string.Concat(options.Usings.Select(u => $"global using global::{u};\n")), parse, "<usings>.g.cs", Encoding.UTF8));
        var references = (options.References.Count > 0 ? options.References : [DefaultReferencePack()])
            .SelectMany(r => Directory.Exists(r) ? Directory.EnumerateFiles(r, "*.dll").Order(StringComparer.Ordinal).ToList() : new List<string> { r })
            .Where(r => IsAssembly(r) && !string.Equals(Path.GetFileNameWithoutExtension(r), options.Assembly, StringComparison.OrdinalIgnoreCase))
            .Select(r => (MetadataReference)MetadataReference.CreateFromFile(r));
        var compilationOptions = new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary, allowUnsafe: true, nullableContextOptions: NullableContextOptions.Enable);
        return CSharpCompilation.Create(options.Assembly ?? "Files", trees, references, compilationOptions);
    }

    static bool IsAssembly(string path)
    {
        try { using var s = File.OpenRead(path); using var pe = new System.Reflection.PortableExecutable.PEReader(s); return pe.HasMetadata && System.Reflection.Metadata.PEReaderExtensions.GetMetadataReader(pe).IsAssembly; }
        catch { return false; }
    }

    /// <summary>The newest `Microsoft.NETCore.App.Ref` pack next to the running runtime: what a project of the SDK would reference.</summary>
    static string DefaultReferencePack()
    {
        var dotnet = Path.GetFullPath(Path.Combine(Path.GetDirectoryName(typeof(object).Assembly.Location)!, "..", "..", ".."));
        var pack = new DirectoryInfo(Path.Combine(dotnet, "packs", "Microsoft.NETCore.App.Ref")).GetDirectories()
            .OrderByDescending(d => Version.TryParse(d.Name.Split('-')[0], out var v) ? v : new Version()).First();
        return pack.GetDirectories("ref")[0].GetDirectories().OrderByDescending(d => d.Name, StringComparer.Ordinal).First().FullName;
    }

    // ---- project mode

    /// <summary>
    /// Compilations of projects as csc would make them: the `CscCommandLineArgs` of a design-time `Compile` (no compiler run, project
    /// references not built), parsed by Roslyn's command line parser. A `ProjectReference` becomes the compilation of that project from its
    /// sources, so its symbols are declared in source, as the plugin sees the solution.
    /// </summary>
    sealed class ProjectCompilations(Options options)
    {
        readonly Dictionary<string, CSharpCompilation?> cache = new(StringComparer.OrdinalIgnoreCase);
        public readonly HashSet<SyntaxTree> Generated = [];

        public CSharpCompilation? Get(string project, string? framework = null)
        {
            if (cache.TryGetValue(project, out var known)) return known;
            cache[project] = null; // a cycle ends here
            var result = Build(project, framework ?? options.Framework);
            cache[project] = result;
            return result;
        }

        CSharpCompilation? Build(string project, string? framework)
        {
            var dir = Path.GetDirectoryName(project)!;
            if (framework == null)
            {
                var props = MsBuild(project, ["-getProperty:TargetFramework", "-getProperty:TargetFrameworks"], restore: true);
                if (props == null) return null;
                var tf = props.RootElement.GetProperty("Properties").GetProperty("TargetFramework").GetString();
                var tfs = props.RootElement.GetProperty("Properties").GetProperty("TargetFrameworks").GetString();
                if (string.IsNullOrEmpty(tf) && !string.IsNullOrEmpty(tfs)) framework = tfs.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)[0];
            }
            List<string> args = ["-t:Compile", "-p:SkipCompilerExecution=true", "-p:ProvideCommandLineArgs=true", "-p:DesignTimeBuild=true",
                "-p:BuildProjectReferences=false", "-p:NonExistentFile=__NonExistentSubDir__/__NonExistentFile__", $"-p:Configuration={options.Configuration}", "-getItem:CscCommandLineArgs", "-getItem:ReferencePathWithRefAssemblies"];
            if (framework != null) args.Add($"-p:TargetFramework={framework}");
            using var json = MsBuild(project, args, restore: framework == null);
            if (json == null) return null;
            var items = json.RootElement.GetProperty("Items");
            var commandLine = items.TryGetProperty("CscCommandLineArgs", out var csc) ? csc.EnumerateArray().Select(i => i.GetProperty("Identity").GetString()!).ToList() : [];
            if (commandLine.Count == 0)
            {
                Console.Error.WriteLine($"{project}: no CscCommandLineArgs (not a C# project, or several target frameworks: --framework)");
                return null;
            }
            // Outputs of project references -> those projects, compiled from source instead.
            var projectOutputs = new Dictionary<string, (string Project, string? Framework)>(StringComparer.OrdinalIgnoreCase);
            if (items.TryGetProperty("ReferencePathWithRefAssemblies", out var refs))
                foreach (var r in refs.EnumerateArray())
                    if (r.TryGetProperty("ReferenceSourceTarget", out var source) && source.GetString() == "ProjectReference" && r.TryGetProperty("MSBuildSourceProjectFile", out var p))
                    {
                        string? tf = null;
                        if (r.TryGetProperty("SetTargetFramework", out var set) && set.GetString() is { Length: > 0 } s && s.StartsWith("TargetFramework=")) tf = s["TargetFramework=".Length..];
                        projectOutputs[r.GetProperty("Identity").GetString()!] = (p.GetString()!, tf);
                        if (r.TryGetProperty("OriginalItemSpec", out var original)) projectOutputs[Path.GetFullPath(original.GetString()!, dir)] = (p.GetString()!, tf);
                    }

            var parsed = CSharpCommandLineParser.Default.Parse(commandLine, dir, sdkDirectory: null);
            var parse = parsed.ParseOptions.WithDocumentationMode(DocumentationMode.Parse);
            var trees = parsed.SourceFiles.Select(f => Parse(f.Path, parse)).ToList();
            var references = new List<MetadataReference>();
            foreach (var r in parsed.MetadataReferences)
            {
                var path = Path.GetFullPath(r.Reference, dir);
                if (projectOutputs.TryGetValue(path, out var source))
                {
                    var compilation = Get(source.Project, source.Framework);
                    if (compilation != null) { references.Add(compilation.ToMetadataReference(r.Properties.Aliases)); continue; }
                }
                if (File.Exists(path)) references.Add(MetadataReference.CreateFromFile(path, r.Properties));
            }
            var name = Path.GetFileNameWithoutExtension(parsed.OutputFileName ?? Path.GetFileName(project));
            var result = CSharpCompilation.Create(name, trees, references, parsed.CompilationOptions);
            if (!options.Generators) return result;
            // Source generators of the project (LoggerMessage, options validation, regexes...): what they declare binds, as in the build.
            var generators = parsed.AnalyzerReferences
                .Select(a => new AnalyzerFileReference(Path.GetFullPath(a.FilePath, dir), Loader.Instance))
                .SelectMany(a => { try { return a.GetGenerators(LanguageNames.CSharp); } catch (Exception) { return []; } })
                .ToArray();
            if (generators.Length == 0) return result;
            CSharpGeneratorDriver.Create(generators, parseOptions: parse).RunGeneratorsAndUpdateCompilation(result, out var updated, out _);
            Generated.UnionWith(updated.SyntaxTrees.Except(result.SyntaxTrees));
            return (CSharpCompilation)updated;
        }

        static JsonDocument? MsBuild(string project, IEnumerable<string> args, bool restore)
        {
            var info = new ProcessStartInfo("dotnet") { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, WorkingDirectory = Path.GetDirectoryName(project)! };
            info.ArgumentList.Add("msbuild");
            info.ArgumentList.Add(project);
            if (restore) info.ArgumentList.Add("-restore");
            info.ArgumentList.Add("-nologo");
            info.ArgumentList.Add("-v:q");
            foreach (var a in args) info.ArgumentList.Add(a);
            using var process = Process.Start(info)!;
            var stderr = process.StandardError.ReadToEndAsync();
            var stdout = process.StandardOutput.ReadToEnd();
            process.WaitForExit();
            var start = stdout.IndexOf('{');
            if (start < 0)
            {
                Console.Error.WriteLine($"dotnet msbuild {project} failed ({process.ExitCode}):\n{stdout}{stderr.Result}");
                return null;
            }
            // Errors of the project (it does not compile) come before the JSON on stdout; the items are there anyway.
            return JsonDocument.Parse(stdout[start..]);
        }
    }

    sealed class Loader : IAnalyzerAssemblyLoader
    {
        public static readonly Loader Instance = new();
        public void AddDependencyLocation(string fullPath) { }
        public Assembly LoadFromPath(string fullPath) => Assembly.LoadFrom(fullPath);
    }

    // ---- output

    sealed class Writer(TextWriter output, string root, Input input)
    {
        public long Names, Bound, Candidates, Expressions, Typed, Diagnostics;

        static readonly SymbolDisplayFormat TypeFormat = new(
            globalNamespaceStyle: SymbolDisplayGlobalNamespaceStyle.Omitted,
            typeQualificationStyle: SymbolDisplayTypeQualificationStyle.NameAndContainingTypesAndNamespaces,
            genericsOptions: SymbolDisplayGenericsOptions.IncludeTypeParameters,
            miscellaneousOptions: SymbolDisplayMiscellaneousOptions.EscapeKeywordIdentifiers | SymbolDisplayMiscellaneousOptions.UseSpecialTypes);

        readonly HashSet<SyntaxTree> generated = input.AllGenerated;

        public void Header()
        {
            var c = input.Compilation;
            output.WriteLine($"# roslyndump semantics {FormatVersion}");
            output.WriteLine($"S\tinput\t{input.Name}");
            output.WriteLine($"S\tassembly\t{c.AssemblyName}");
            var parse = (CSharpParseOptions?)input.Dumped.FirstOrDefault()?.Options ?? CSharpParseOptions.Default;
            output.WriteLine($"S\tlangversion\t{parse.LanguageVersion.ToDisplayString()}");
            output.WriteLine($"S\tdefine\t{string.Join(';', parse.PreprocessorSymbolNames)}");
            output.WriteLine($"S\tnullable\t{c.Options.NullableContextOptions}");
            foreach (var r in c.References.Select(r => r switch
                     {
                         CompilationReference cr => "project:" + cr.Compilation.AssemblyName,
                         // the full path after the name: what a resolver indexes to see the same assemblies
                         PortableExecutableReference pe => Path.GetFileName(pe.FilePath ?? pe.Display ?? "?") + (pe.FilePath is { } path ? "\t" + Path.GetFullPath(path) : ""),
                         _ => r.Display ?? "?",
                     }).Order(StringComparer.Ordinal))
                output.WriteLine($"S\treference\t{r}");
            // Every source file the symbols can be declared in: this compilation's and the referenced projects', with their parse options.
            var sources = new List<SyntaxTree>();
            void Collect(Compilation compilation, HashSet<Compilation> seen)
            {
                if (!seen.Add(compilation)) return;
                sources.AddRange(compilation.SyntaxTrees.Where(t => !generated.Contains(t) && System.IO.File.Exists(t.FilePath)));
                foreach (var r in compilation.References.OfType<CompilationReference>()) Collect(r.Compilation, seen);
            }
            Collect(c, []);
            // Parse options are numbered (`S options <n> <langversion> <defines>`): a solution has few distinct ones.
            var numbered = new Dictionary<string, int>();
            foreach (var t in sources.DistinctBy(t => t.FilePath).OrderBy(t => Relative(root, t.FilePath), StringComparer.Ordinal))
            {
                var o = (CSharpParseOptions)t.Options;
                var key = $"{o.LanguageVersion.ToDisplayString()}\t{string.Join(';', o.PreprocessorSymbolNames)}";
                if (!numbered.TryGetValue(key, out var n))
                {
                    numbered[key] = n = numbered.Count;
                    output.WriteLine($"S\toptions\t{n}\t{key}");
                }
                output.WriteLine($"S\tsrc\t{Relative(root, t.FilePath)}\t{n}");
            }
            foreach (var t in input.Generated) output.WriteLine($"S\tgenerated\t{GeneratedName(t)}");
        }

        static string GeneratedName(SyntaxTree tree) => tree.FilePath.Replace('\\', '/');

        public void File(SyntaxTree tree)
        {
            output.WriteLine($"F\t{Relative(root, tree.FilePath)}");
            var model = input.Compilation.GetSemanticModel(tree);
            var unit = tree.GetRoot();
            foreach (var token in unit.DescendantTokens(descendIntoTrivia: false))
                if (token.IsKind(SyntaxKind.IdentifierToken) && !token.IsMissing) Name(model, token);
            foreach (var node in unit.DescendantNodes(descendIntoTrivia: false))
                if (node is ExpressionSyntax expression) Expression(model, expression);
            foreach (var d in model.GetDiagnostics()
                         .Where(d => d.Severity >= DiagnosticSeverity.Warning && !d.IsSuppressed && d.Location.SourceTree == tree)
                         .OrderBy(d => d.Location.SourceSpan.Start).ThenBy(d => d.Location.SourceSpan.End).ThenBy(d => d.Id, StringComparer.Ordinal))
            {
                Diagnostics++;
                output.WriteLine($"D\t{d.Location.SourceSpan.Start}\t{d.Location.SourceSpan.End}\t{d.Id}\t{(d.Severity == DiagnosticSeverity.Error ? "error" : "warning")}");
            }
        }

        // ---- names

        void Name(SemanticModel model, SyntaxToken token)
        {
            Names++;
            var flags = new List<string>();
            var role = "ref";
            ISymbol? symbol = null;
            var candidates = ImmutableArray<ISymbol>.Empty;
            var parent = token.Parent!;
            if (parent is SimpleNameSyntax name && name.Identifier == token)
            {
                if (name.Parent is NameEqualsSyntax { Parent: UsingDirectiveSyntax alias } && alias.Alias!.Name == name) { role = "decl"; symbol = model.GetDeclaredSymbol(alias); }
                else if (name.Parent is NameEqualsSyntax { Parent: AnonymousObjectMemberDeclaratorSyntax member }) { role = "decl"; symbol = model.GetDeclaredSymbol(member); }
                else
                {
                    var info = model.GetSymbolInfo(name);
                    symbol = info.Symbol;
                    if (symbol == null && info.CandidateSymbols.Length > 0) { candidates = info.CandidateSymbols; flags.Add("cand=" + info.CandidateReason); }
                    if (model.GetAliasInfo(name) != null) flags.Add("alias");
                    // An attribute name binds to the constructor: the name stands for the type.
                    if (symbol is IMethodSymbol { MethodKind: MethodKind.Constructor } ctor) { symbol = ctor.ContainingType; flags.Add("ctor"); }
                    if (symbol is IErrorTypeSymbol error) { candidates = error.CandidateSymbols; symbol = null; if (candidates.Length > 0) flags.Add("cand=" + error.CandidateReason); }
                    if (IsQualifiedRight(name)) flags.Add("acc");
                }
            }
            else
            {
                role = "decl";
                symbol = DeclaredSymbol(model, parent);
            }
            if (SyntaxFacts.GetContextualKeywordKind(token.ValueText) is SyntaxKind.VarKeyword or SyntaxKind.NameOfKeyword or SyntaxKind.UnmanagedKeyword
                || token.ValueText is "notnull" or "dynamic" or "nint" or "nuint" or "_") flags.Add("kw");
            if (symbol != null) Bound++;
            else if (candidates.Length > 0) Candidates++;
            var symbols = symbol != null ? [Normalize(symbol)] : candidates.Select(Normalize).Distinct(SymbolEqualityComparer.Default).ToList();
            if (role == "ref" && symbol != null && !flags.Contains("acc")) Membership(model, token, symbols[0], flags);
            var kind = symbols.Count == 0 ? "-" : string.Join('|', symbols.Select(Kind));
            var id = symbols.Count == 0 ? "-" : string.Join('|', symbols.Select(Id));
            var decl = symbols.Count == 0 ? "-" : string.Join('|', symbols.Select(Declarations));
            output.WriteLine($"N\t{token.SpanStart}\t{token.Text}\t{role}\t{kind}\t{id}\t{decl}\t{(flags.Count == 0 ? "-" : string.Join(',', flags))}");
        }

        static ISymbol? DeclaredSymbol(SemanticModel model, SyntaxNode node)
        {
            try { return model.GetDeclaredSymbol(node); }
            catch (Exception) { return null; }
        }

        /// <summary>The name after `.`, `?.`, `::` or of a qualified name: looked up in what is on the left.</summary>
        static bool IsQualifiedRight(SimpleNameSyntax name) => name.Parent switch
        {
            MemberAccessExpressionSyntax access => access.Name == name,
            MemberBindingExpressionSyntax => true,
            QualifiedNameSyntax qualified => qualified.Right == name,
            AliasQualifiedNameSyntax aliased => aliased.Name == name,
            _ => false,
        };

        static ISymbol Normalize(ISymbol symbol)
        {
            if (symbol is IMethodSymbol { ReducedFrom: { } reduced }) symbol = reduced;
            if (symbol is IAliasSymbol) return symbol;
            return symbol.OriginalDefinition;
        }

        /// <summary>`own`: a member of a type around the name (the outer types included); `inh`: of one of their base types.</summary>
        static void Membership(SemanticModel model, SyntaxToken token, ISymbol symbol, List<string> flags)
        {
            if (symbol.Kind is not (SymbolKind.Method or SymbolKind.Property or SymbolKind.Field or SymbolKind.Event or SymbolKind.NamedType)) return;
            if (symbol is IMethodSymbol { MethodKind: MethodKind.LocalFunction }) return;
            var container = symbol.ContainingType?.OriginalDefinition;
            if (container == null) return;
            var enclosing = model.GetEnclosingSymbol(token.SpanStart);
            for (var type = enclosing as INamedTypeSymbol ?? enclosing?.ContainingType; type != null; type = type.ContainingType)
            {
                if (SymbolEqualityComparer.Default.Equals(type.OriginalDefinition, container)) { flags.Add("own"); return; }
                for (var b = type.BaseType; b != null; b = b.BaseType)
                    if (SymbolEqualityComparer.Default.Equals(b.OriginalDefinition, container)) { flags.Add("inh"); return; }
            }
        }

        static string Kind(ISymbol s) => s switch
        {
            INamedTypeSymbol t => "NamedType." + t.TypeKind,
            IMethodSymbol m => "Method." + m.MethodKind,
            ILocalSymbol { IsConst: true } => "Local.Const",
            _ => s.Kind.ToString(),
        };

        static string Id(ISymbol s)
        {
            if (s.Kind is SymbolKind.Namespace or SymbolKind.NamedType or SymbolKind.Method or SymbolKind.Property or SymbolKind.Field or SymbolKind.Event
                && s is not IMethodSymbol { MethodKind: MethodKind.LocalFunction or MethodKind.AnonymousFunction })
            {
                try
                {
                    if (DocumentationCommentId.CreateDeclarationId(s) is { Length: > 0 } id && !id.Contains(' ') && !id.Contains('\t')) return id;
                }
                catch (Exception) { }
            }
            return s.Kind + ":" + (s.Name.Length == 0 ? "?" : s.Name);
        }

        string Declarations(ISymbol s)
        {
            if (s is INamespaceSymbol) return "ns";
            var places = new List<string>();
            foreach (var l in s.Locations)
            {
                if (l.IsInSource && l.SourceTree is { } tree)
                    places.Add(generated.Contains(tree) || !System.IO.File.Exists(tree.FilePath) ? $"gen:{GeneratedName(tree)}:{l.SourceSpan.Start}" : $"{Relative(root, tree.FilePath)}:{l.SourceSpan.Start}");
                else if (l.IsInMetadata) places.Add("asm:" + (l.MetadataModule?.ContainingAssembly?.Name ?? s.ContainingAssembly?.Name ?? "?"));
            }
            return places.Count == 0 ? "-" : string.Join(',', places.Distinct().Select(Escape));
        }

        // `,` separates places and `|` candidates: escaped in paths (`.NETCoreApp,Version=v9.0.AssemblyAttributes.cs`), as are `%` and a tab.
        static string Escape(string place) => place.Replace("%", "%25").Replace(",", "%2C").Replace("|", "%7C").Replace("\t", "%09");

        // ---- expressions

        void Expression(SemanticModel model, ExpressionSyntax expression)
        {
            Expressions++;
            Microsoft.CodeAnalysis.TypeInfo info;
            try { info = model.GetTypeInfo(expression); }
            catch (Exception) { info = default; }
            var type = TypeText(info.Type);
            var converted = TypeText(info.ConvertedType);
            if (type != "-") Typed++;
            output.WriteLine($"X\t{expression.Span.Start}\t{expression.Span.End}\t{expression.Kind()}\t{type}\t{(converted == type ? "=" : converted)}");
        }

        static string TypeText(ITypeSymbol? type) => type == null ? "-" : type.TypeKind == TypeKind.Error ? "?" + type.Name : type.ToDisplayString(TypeFormat);
    }
}
