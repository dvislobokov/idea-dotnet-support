// The code generation modes of razordump (codegen, ir, cases-codegen): the engines as the SDK's source generator and as the compiler's
// integration tests configure them, the in-memory file system they read, and the three sections of a dump. Format: README.md.
using System.Collections.Immutable;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Razor.Language;
using Microsoft.AspNetCore.Razor.Language.Intermediate;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using RazorDump.Baselines;

namespace RazorDump;

/// <summary>A file of the in-memory file system: the text (UTF-8, a BOM ahead when <c>bom</c>) and the paths as a host gives them.</summary>
sealed class DumpProjectItem(string basePath, string filePath, string? physicalPath, string? relativePhysicalPath, RazorFileKind? fileKind,
    string? content, bool bom = true) : RazorProjectItem
{
    public override string BasePath => basePath;
    public override string FilePath => filePath;
    public override string PhysicalPath => physicalPath!;
    public override string RelativePhysicalPath => relativePhysicalPath!;
    public override RazorFileKind FileKind => fileKind ?? base.FileKind;
    public override bool Exists => content != null;
    public string Content => content ?? throw new InvalidOperationException($"{filePath} does not exist");

    public override Stream Read()
    {
        // As TestRazorProjectItem / InMemoryFileContent: the checksum of the source document is of these bytes.
        var preamble = bom ? Encoding.UTF8.GetPreamble() : [];
        return new MemoryStream([.. preamble, .. Encoding.UTF8.GetBytes(Content)], writable: false);
    }
}

/// <summary>
/// The files the engine sees, by their Razor path (<c>/a/b.razor</c>): as VirtualRazorProjectFileSystem of the source generator and the
/// tests; the imports (<c>_Imports.razor</c>, <c>_ViewImports.cshtml</c> of the folder and the folders above) are found through it.
/// </summary>
sealed class DumpFileSystem : RazorProjectFileSystem
{
    readonly Dictionary<string, RazorProjectItem> items = new(StringComparer.Ordinal);

    public void Add(RazorProjectItem item) => items[item.FilePath] = item;

    public override IEnumerable<RazorProjectItem> EnumerateItems(string basePath) =>
        items.Values.Where(i => i.FilePath.StartsWith(basePath.TrimEnd('/') + "/", StringComparison.Ordinal));

    public override RazorProjectItem GetItem(string path, RazorFileKind? fileKind) =>
        items.TryGetValue(path, out var item) ? item : new DumpProjectItem("/", path, null, null, fileKind, null);
}

/// <summary>A fixed set of tag helpers, as the tests' CompilationTagHelperFeature gives the ones it discovered.</summary>
sealed class FixedTagHelperFeature(TagHelperCollection tagHelpers) : RazorEngineFeatureBase, ITagHelperFeature
{
    public TagHelperCollection TagHelpers { get; set; } = tagHelpers;
    public TagHelperCollection GetTagHelpers(CancellationToken cancellationToken = default) => TagHelpers;
}

static class Codegen
{
    const BindingFlags Any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;

    /// <summary>
    /// The engine of the SDK's source generator (RazorSourceGenerator.Helpers.cs, GetGenerationProjectEngine; the options of
    /// RazorSourceGenerator.RazorProviders.cs): consolidated MVC views, AddComponentParameter available (a net8+ Components reference),
    /// metadata source checksum attributes only with <paramref name="metadataChecksums"/>, the Roslyn tokenizer, C# preview, the tag
    /// helpers of <paramref name="tagHelpers"/> (filled after the declaration pass); <paramref name="newLine"/> is the generated document's.
    /// </summary>
    public static RazorProjectEngine CreateGeneratorEngine(RazorProjectFileSystem fileSystem, RazorLanguageVersion version, string rootNamespace, string newLine,
        FixedTagHelperFeature tagHelpers, bool metadataChecksums)
    {
        var configuration = new RazorConfiguration(version, "Default", [], UseConsolidatedMvcViews: true, SuppressAddComponentParameter: false, UseRoslynTokenizer: true);
        return RazorProjectEngine.Create(configuration, fileSystem, b =>
        {
            b.SetRootNamespace(rootNamespace);
            b.ConfigureCodeGenerationOptions(o =>
            {
                // GenerateRazorMetadataSourceChecksumAttributes: off unless the project sets it (MVC's runtime compilation package does).
                o.SuppressMetadataSourceChecksumAttributes = !metadataChecksums;
                o.SupportLocalizedComponentNames = false;
                o.SuppressAddComponentParameter = configuration.SuppressAddComponentParameter;
                o.NewLine = newLine;
            });
            b.ConfigureParserOptions(o =>
            {
                o.UseRoslynTokenizer = true;
                o.CSharpParseOptions = new CSharpParseOptions(LanguageVersion.Preview);
            });
            Microsoft.CodeAnalysis.Razor.CompilerFeatures.Register(b);
            Microsoft.AspNetCore.Mvc.Razor.Extensions.RazorExtensions.Register(b);
            b.Features.Add(tagHelpers);
            b.SetCSharpLanguageVersion(LanguageVersion.Preview);
        });
    }

    /// <summary>
    /// The tag helpers the source generator would see (its declaration pass and StaticCompilationTagHelperFeature): the declarations of the
    /// directory's components (ProcessDeclarationOnly) and its `.cs` files compiled as <paramref name="assemblyName"/> against the reference
    /// assemblies of the installed .NET and ASP.NET Core (packs/*.Ref, the runtime's major version) and <paramref name="references"/>,
    /// discovered with the compiler's producers (components, bind, event handlers, ref, key, splat, render mode, form name, MVC tag helpers,
    /// view components). Package references are not resolved: pass their dlls; the C# a build adds (obj/…/&lt;Project&gt;.GlobalUsings.g.cs
    /// of ImplicitUsings, other generators' output) comes through <paramref name="extraCSharp"/> (files or directories).
    /// </summary>
    public static TagHelperCollection DiscoverProjectTagHelpers(RazorProjectEngine engine, DumpFileSystem fileSystem, string? root,
        IEnumerable<string> razorPaths, string assemblyName, IEnumerable<string> references, IEnumerable<string> extraCSharp)
    {
        var parseOptions = new CSharpParseOptions(LanguageVersion.Preview);
        var trees = new List<SyntaxTree>();
        foreach (var relative in razorPaths)
        {
            var item = fileSystem.GetItem("/" + relative, fileKind: null);
            if (item.FileKind != RazorFileKind.Component) continue;
            var declaration = engine.ProcessDeclarationOnly(item, CancellationToken.None);
            trees.Add(CSharpSyntaxTree.ParseText(GetCSharpDocument(declaration).Text, parseOptions, path: relative + ".decl.cs"));
        }
        foreach (var file in root == null ? [] : Directory.EnumerateFiles(root, "*.cs", new EnumerationOptions { RecurseSubdirectories = true }))
        {
            var relative = Path.GetRelativePath(root, file).Replace('\\', '/');
            if (relative.Split('/').Any(part => part is "bin" or "obj" or ".git" or "node_modules")) continue;
            trees.Add(CSharpSyntaxTree.ParseText(File.ReadAllText(file), parseOptions, path: relative));
        }
        foreach (var extra in extraCSharp)
        {
            IEnumerable<string> files = Directory.Exists(extra) ? Directory.EnumerateFiles(extra, "*.cs", SearchOption.AllDirectories).Order(StringComparer.Ordinal) : [extra];
            trees.AddRange(files.Select(f => CSharpSyntaxTree.ParseText(File.ReadAllText(f), parseOptions, path: f)));
        }
        var compilation = CSharpCompilation.Create(assemblyName, trees,
            [.. TagHelperDiscovery.References(), .. references.Select(r => MetadataReference.CreateFromFile(r))],
            new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary));
        var discoveryEngine = RazorProjectEngine.Create(engine.Configuration, new DumpFileSystem(), b =>
        {
            Microsoft.CodeAnalysis.Razor.CompilerFeatures.Register(b);
            Microsoft.AspNetCore.Mvc.Razor.Extensions.RazorExtensions.Register(b);
            b.RegisterDefaultTagHelperProducer();
        });
        return TagHelperDiscovery.Discover(discoveryEngine, compilation);
    }

    /// <summary>
    /// The engine of the compiler's integration tests (IntegrationTestBase.CreateProjectEngine): CRLF, unique ids suppressed, the MVC
    /// extension of the configuration's name (RegisterExtensions), the test class's own configuration, the tag helpers it discovered, the
    /// Roslyn tokenizer with the test's C# version. (The tests' EnableMarkupSplit is newer than the SDK's compiler: it has no such option.)
    /// </summary>
    public static RazorProjectEngine CreateTestEngine(RazorConfiguration configuration, RazorProjectFileSystem fileSystem, bool registerMvc,
        LanguageVersion csharpVersion, TagHelperCollection tagHelpers)
    {
        return RazorProjectEngine.Create(configuration, fileSystem, b =>
        {
            b.ConfigureCodeGenerationOptions(o =>
            {
                o.NewLine = "\r\n";
                o.SuppressUniqueIds = "__UniqueIdSuppressedForTesting__";
            });
            b.RegisterExtensions();
            if (registerMvc) Microsoft.AspNetCore.Mvc.Razor.Extensions.RazorExtensions.Register(b);
            b.Features.Add(new FixedTagHelperFeature(tagHelpers));
            b.SetCSharpLanguageVersion(csharpVersion);
            b.ConfigureParserOptions(o =>
            {
                o.UseRoslynTokenizer = true;
                o.CSharpParseOptions = new CSharpParseOptions(csharpVersion);
            });
        });
    }

    public static RazorCSharpDocument GetCSharpDocument(RazorCodeDocument document) =>
        (RazorCSharpDocument)typeof(RazorCodeDocument).GetMethod("GetRequiredCSharpDocument", Any, [])!.Invoke(document, null)!;

    public static DocumentIntermediateNode GetDocumentNode(RazorCodeDocument document) =>
        (DocumentIntermediateNode)typeof(RazorCodeDocument).GetMethod("GetRequiredDocumentNode", Any, [])!.Invoke(document, null)!;

    /// <summary>The generated C# as the `.codegen.cs` baselines hold it.</summary>
    public static string GeneratedCode(RazorCodeDocument document) => GetCSharpDocument(document).Text.ToString();

    /// <summary>The `.mappings.txt` of the document; a mapping whose two texts differ (the tests' assertion) goes to stderr.</summary>
    public static string Mappings(RazorCodeDocument document, string name) =>
        SourceMappingsSerializer.Serialize(GetCSharpDocument(document), document.Source,
            mismatch => Console.Error.WriteLine($"# {name}: the mapping {mismatch} maps different texts"));

    /// <summary>The `.diagnostics.txt` of the document: one diagnostic per line, its line breaks normalized as the tests do.</summary>
    public static string Diagnostics(RazorCodeDocument document) =>
        string.Concat(GetCSharpDocument(document).Diagnostics.Select(d => RazorDiagnosticSerializer.Serialize(d) + "\n"));

    /// <summary>The `.ir.txt` of the document.</summary>
    public static string Ir(RazorCodeDocument document) => IntermediateNodeSerializer.Serialize(GetDocumentNode(document));

    /// <summary>A section of a dump: line breaks LF, a final one added when missing.</summary>
    public static void AppendSection(StringBuilder output, string? header, string text)
    {
        if (header != null) output.Append(header).Append('\n');
        var lf = Lf(text);
        output.Append(lf);
        if (lf.Length > 0 && !lf.EndsWith('\n')) output.Append('\n');
    }

    public static string Lf(string text) => text.Replace("\r\n", "\n");

    /// <summary>The three sections of a codegen dump (the header of the file written by the caller).</summary>
    public static void AppendCodegen(StringBuilder output, RazorCodeDocument document, string name)
    {
        AppendSection(output, null, GeneratedCode(document));
        AppendSection(output, "### mappings", Mappings(document, name));
        AppendSection(output, "### diagnostics", Diagnostics(document));
    }

    /// <summary>
    /// The in-memory file system of a directory: every `.razor` / `.cshtml` under it (bin, obj, node_modules, .git skipped) as the source
    /// generator's project item — Razor path <c>/rel</c>, physical and relative path <c>rel</c> (relative, so the dump does not depend
    /// on the machine; with <paramref name="absolutePaths"/> the absolute path and the OS separators, as a build has them), the text read
    /// with its line breaks normalized, a BOM if the file had one.
    /// </summary>
    public static DumpFileSystem ReadDirectory(string root, IEnumerable<string> relativePaths, string? newLine, bool absolutePaths)
    {
        var fileSystem = new DumpFileSystem();
        foreach (var relative in relativePaths)
        {
            var bytes = File.ReadAllBytes(Path.Combine(root, relative));
            var bom = bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF;
            var text = Encoding.UTF8.GetString(bytes, bom ? 3 : 0, bytes.Length - (bom ? 3 : 0));
            // newLine null: the text as on disk (as a build reads it); otherwise every line break becomes newLine.
            if (newLine != null) text = text.Replace("\r\n", "\n").Replace('\r', '\n').Replace("\n", newLine);
            var physical = absolutePaths ? Path.GetFullPath(Path.Combine(root, relative)) : relative;
            var relativePhysical = absolutePaths ? relative.Replace('/', Path.DirectorySeparatorChar) : relative;
            fileSystem.Add(new DumpProjectItem("/", "/" + relative, physical, relativePhysical, FileKinds.GetFileKindFromPath(relative), text, bom));
        }
        return fileSystem;
    }
}

/// <summary>
/// One case of the compiler's integration tests (<c>TestFiles/IntegrationTests/&lt;Class&gt;/&lt;Name&gt;.cshtml</c> with its
/// <c>.codegen.cs</c>) and what its test method does, read from the test class (<c>test/IntegrationTests/&lt;Class&gt;.cs</c>).
/// </summary>
sealed class TestCase
{
    public required string InputPath { get; init; }
    /// <summary>The test's file name, as GetTestFileName + the extension: <c>TestFiles/IntegrationTests/X/Name.cshtml</c>.</summary>
    public required string TestFileName { get; init; }
    public required string Name { get; init; }
    public string? SkipReason { get; set; }
    public RazorFileKind? FileKind { get; set; }
    /// <summary>The imports the test adds to its file system (AddProjectItemFromText): file name (relative to the test's folder), text.</summary>
    public List<(string FileName, string Text)> Imports { get; } = [];
    /// <summary>The C# the test adds to its compilation (AddCSharpSyntaxTree): where its tag helpers are discovered.</summary>
    public List<string> CSharpTrees { get; } = [];
}

/// <summary>What a test class configures for all its cases.</summary>
sealed class TestClass
{
    public RazorConfiguration Configuration { get; set; } = RazorConfiguration.Default;
    public bool RegisterMvc { get; set; }
    public LanguageVersion CSharpVersion { get; set; } = LanguageVersion.Preview;
    public Dictionary<string, string> Methods { get; } = new(StringComparer.Ordinal);
    public string AssemblyName { get; set; } = "TestAssembly";

    static readonly Dictionary<string, TestClass> Cache = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>The class of the tests of a TestFiles folder, `null` when there is no such source next to it.</summary>
    public static TestClass? For(string testFilesFolder, string className)
    {
        var testDir = Path.GetDirectoryName(testFilesFolder)!;
        var source = Path.Combine(testDir, "IntegrationTests", className + ".cs");
        if (Cache.TryGetValue(source, out var cached)) return cached;
        TestClass? result = null;
        if (File.Exists(source)) result = Parse(File.ReadAllText(source));
        Cache[source] = result!;
        return result;
    }

    static TestClass Parse(string text)
    {
        var result = new TestClass();
        // `new(RazorLanguageVersion.Version_2_0, "MVC-2.1", Extensions: [])` of the MVC test classes; otherwise RazorConfiguration.Default.
        var configuration = Regex.Match(text, @"new\(RazorLanguageVersion\.(\w+),\s*""([^""]+)""");
        if (configuration.Success)
        {
            var version = (RazorLanguageVersion)typeof(RazorLanguageVersion).GetField(configuration.Groups[1].Value)!.GetValue(null)!;
            result.Configuration = new RazorConfiguration(version, configuration.Groups[2].Value, []);
        }
        // RunTest of the Language tests: CreateProjectEngine(RazorExtensions.Register).
        result.RegisterMvc = text.Contains("CreateProjectEngine(RazorExtensions.Register)", StringComparison.Ordinal);
        var csharp = Regex.Match(text, @"CSharpParseOptions\s*=>\s*base\.CSharpParseOptions\.WithLanguageVersion\(LanguageVersion\.(\w+)\)");
        if (csharp.Success) result.CSharpVersion = Enum.Parse<LanguageVersion>(csharp.Groups[1].Value);
        if (Regex.Match(text, @"BaseCompilation\s*=\s*[\w.()]+\.WithAssemblyName\(""(\w+)""\)") is { Success: true } assembly) result.AssemblyName = assembly.Groups[1].Value;
        // The bodies of the test methods: from one `public void Name(` to the next.
        var methods = Regex.Matches(text, @"public void (\w+)\(");
        for (var i = 0; i < methods.Count; i++)
        {
            var end = i + 1 < methods.Count ? methods[i + 1].Index : text.Length;
            result.Methods[methods[i].Groups[1].Value] = text[methods[i].Index..end];
        }
        return result;
    }

    /// <summary>What the test method of a case does: skipped with tag helper descriptors of its own; imports, file kind, C# otherwise.</summary>
    public void Describe(TestCase testCase)
    {
        if (!Methods.TryGetValue(testCase.Name, out var body)) return;
        if (body.Contains("RunTagHelpersTest", StringComparison.Ordinal)) testCase.SkipReason = "tag helpers";
        else if (body.Contains("RunDiscoveredTagHelpersTest", StringComparison.Ordinal)) testCase.SkipReason = "tag helpers";
        if (body.Contains("fileKind: RazorFileKind.Component", StringComparison.Ordinal)) testCase.FileKind = RazorFileKind.Component;
        foreach (Match m in Regex.Matches(body, @"AddProjectItemFromText\("))
        {
            var (literal, next) = ReadStringLiteral(body, m.Index + m.Length);
            if (literal == null) continue;
            var fileName = "_ViewImports.cshtml";
            var rest = body[next..].TrimStart();
            if (rest.StartsWith(',') && ReadStringLiteral(rest, 1) is { Value: { } explicitName }) fileName = explicitName;
            testCase.Imports.Add((fileName, literal));
        }
        foreach (Match m in Regex.Matches(body, @"AddCSharpSyntaxTree\("))
        {
            var (literal, _) = ReadStringLiteral(body, m.Index + m.Length);
            if (literal != null) testCase.CSharpTrees.Add(literal);
        }
    }

    /// <summary>A C# string literal at <paramref name="start"/> (after whitespace): `"…"`, `@"…"`, `$$"""…"""` or `"""…"""` (raw, dedented).</summary>
    static (string? Value, int End) ReadStringLiteral(string text, int start)
    {
        var i = start;
        while (i < text.Length && char.IsWhiteSpace(text[i])) i++;
        while (i < text.Length && text[i] == '$') i++;
        if (i + 3 <= text.Length && text.AsSpan(i, 3).SequenceEqual("\"\"\""))
        {
            var quotes = 0;
            while (i + quotes < text.Length && text[i + quotes] == '"') quotes++;
            var delimiter = new string('"', quotes);
            var contentStart = i + quotes;
            var close = text.IndexOf(delimiter, contentStart, StringComparison.Ordinal);
            if (close < 0) return (null, start);
            var raw = text[contentStart..close].Replace("\r\n", "\n");
            // Multi-line raw string: drop the first line break and the closing line; the closing line's whitespace is the indentation.
            var lastBreak = raw.LastIndexOf('\n');
            var indentation = raw[(lastBreak + 1)..];
            var lines = raw[(raw.IndexOf('\n') + 1)..lastBreak].Split('\n');
            var value = string.Join("\n", lines.Select(l => l.StartsWith(indentation, StringComparison.Ordinal) ? l[indentation.Length..] : l.TrimStart()));
            return (value, close + quotes);
        }
        var verbatim = i < text.Length && text[i] == '@';
        if (verbatim) i++;
        if (i >= text.Length || text[i] != '"') return (null, start);
        var builder = new StringBuilder();
        for (i++; i < text.Length; i++)
        {
            var c = text[i];
            if (c == '"')
            {
                if (verbatim && i + 1 < text.Length && text[i + 1] == '"') { builder.Append('"'); i++; continue; }
                return (builder.ToString(), i + 1);
            }
            if (!verbatim && c == '\\' && i + 1 < text.Length)
            {
                i++;
                builder.Append(text[i] switch { 'n' => '\n', 'r' => '\r', 't' => '\t', '0' => '\0', var other => other });
                continue;
            }
            builder.Append(c);
        }
        return (null, start);
    }
}

/// <summary>
/// The tag helpers of a test's compilation, as its CompilationTagHelperFeature finds them: the test's C# (AddCSharpSyntaxTree) compiled
/// with the reference assemblies of the installed ASP.NET Core and .NET (packs/*.Ref), discovered by the compiler's
/// ITagHelperDiscoveryService (internal: reflection) of an engine with the class's extensions.
/// </summary>
static class TagHelperDiscovery
{
    static ImmutableArray<MetadataReference>? references;

    public static ImmutableArray<MetadataReference> References()
    {
        if (references is { } cached) return cached;
        // <dotnet>/shared/Microsoft.NETCore.App/<version>/System.Private.CoreLib.dll
        var dotnetRoot = Path.GetFullPath(Path.Combine(Path.GetDirectoryName(typeof(object).Assembly.Location)!, "..", "..", ".."));
        var result = new List<MetadataReference>();
        foreach (var pack in new[] { "Microsoft.NETCore.App.Ref", "Microsoft.AspNetCore.App.Ref" })
        {
            var packDir = Path.Combine(dotnetRoot, "packs", pack);
            var version = Directory.Exists(packDir)
                ? Directory.GetDirectories(packDir).Select(Path.GetFileName).Where(v => v!.StartsWith($"{Environment.Version.Major}.", StringComparison.Ordinal))
                    .OrderBy(v => Version.TryParse(v!.Split('-')[0], out var parsed) ? parsed : new Version()).LastOrDefault()
                : null;
            if (version == null) continue;
            var refDir = Directory.GetDirectories(Path.Combine(packDir, version, "ref")).OrderBy(d => d, StringComparer.Ordinal).Last();
            result.AddRange(Directory.GetFiles(refDir, "*.dll").Select(f => MetadataReference.CreateFromFile(f)));
        }
        references = [.. result];
        return references.Value;
    }

    static readonly Dictionary<string, TagHelperCollection> Cache = new(StringComparer.Ordinal);

    public static TagHelperCollection Discover(TestClass testClass, IReadOnlyList<string> csharpTrees)
    {
        var key = $"{RuntimeHelpers.GetHashCode(testClass)}\u0001{string.Join("\u0001", csharpTrees)}";
        if (!Cache.TryGetValue(key, out var result)) Cache[key] = result = DiscoverUncached(testClass, csharpTrees);
        return result;
    }

    static TagHelperCollection DiscoverUncached(TestClass testClass, IReadOnlyList<string> csharpTrees)
    {
        var parseOptions = new CSharpParseOptions(testClass.CSharpVersion);
        var compilation = CSharpCompilation.Create(testClass.AssemblyName, csharpTrees.Select(t => CSharpSyntaxTree.ParseText(t, parseOptions)),
            References(), new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary));
        var engine = RazorProjectEngine.Create(testClass.Configuration, new DumpFileSystem(), b =>
        {
            b.RegisterExtensions();
            if (testClass.RegisterMvc) Microsoft.AspNetCore.Mvc.Razor.Extensions.RazorExtensions.Register(b);
            b.RegisterDefaultTagHelperProducer();
        });
        return Discover(engine, compilation);
    }

    /// <summary>The tag helpers of a compilation (its assembly and its references) by the producers of the engine.</summary>
    public static TagHelperCollection Discover(RazorProjectEngine engine, Compilation compilation)
    {
        var serviceType = typeof(RazorProjectEngine).Assembly.GetType("Microsoft.AspNetCore.Razor.Language.ITagHelperDiscoveryService", throwOnError: true)!;
        var service = engine.Engine.Features.Single(serviceType.IsInstanceOfType);
        var getTagHelpers = serviceType.GetMethod("GetTagHelpers", [typeof(Compilation), typeof(CancellationToken)])!;
        return (TagHelperCollection)getTagHelpers.Invoke(service, [compilation, CancellationToken.None])!;
    }
}

/// <summary>
/// cases-codegen: the cases of the compiler's integration tests under a TestFiles directory (an input with a <c>.codegen.cs</c> beside
/// it), each processed as its test does (IntegrationTestBase.CreateProjectItemFromFile + CreateProjectEngine + Process).
/// </summary>
static class CasesCodegen
{
    /// <summary><paramref name="tagHelpersOut"/>: where the descriptors of every processed case go, <c>&lt;Project&gt;/&lt;Class&gt;/&lt;Name&gt;.taghelpers.json</c>.</summary>
    public static string Run(string target, RazorLanguageVersion? languageVersion, bool check, StringBuilder output, string? tagHelpersOut = null)
    {
        var inputs = Directory.EnumerateFiles(target, "*.*", SearchOption.AllDirectories)
            .Where(f => f.EndsWith(".cshtml", StringComparison.OrdinalIgnoreCase) || f.EndsWith(".razor", StringComparison.OrdinalIgnoreCase))
            .Where(f => File.Exists(Path.ChangeExtension(f, ".codegen.cs")))
            .Select(f => Path.GetRelativePath(target, f).Replace('\\', '/'))
            .OrderBy(r => r, StringComparer.Ordinal)
            .ToList();
        int cases = 0, same = 0, irSame = 0, irBaselines = 0, mappingsSame = 0, mappingsBaselines = 0, diagnosticsSame = 0, skipped = 0, failed = 0;
        var differing = new StringBuilder();
        foreach (var relative in inputs)
        {
            cases++;
            var inputPath = Path.Combine(target, relative);
            var full = Path.GetFullPath(inputPath).Replace('\\', '/');
            var at = full.LastIndexOf("/TestFiles/", StringComparison.Ordinal);
            var testFileName = at >= 0 ? full[(at + 1)..] : relative;
            var className = Path.GetFileName(Path.GetDirectoryName(full))!;
            var testCase = new TestCase { InputPath = inputPath, TestFileName = testFileName, Name = Path.GetFileNameWithoutExtension(full) };
            var testClass = at >= 0 ? TestClass.For(full[..(at + "/TestFiles".Length)], className) : null;
            testClass ??= new TestClass { RegisterMvc = true };
            testClass.Describe(testCase);
            output.Append("### ").Append(relative).Append('\n');
            if (testCase.SkipReason != null)
            {
                output.Append("# skipped: ").Append(testCase.SkipReason).Append('\n');
                skipped++;
                continue;
            }
            RazorCodeDocument codeDocument;
            try
            {
                if (tagHelpersOut != null) WriteTagHelpers(tagHelpersOut, full, testFileName, relative, TagHelperDiscovery.Discover(testClass, testCase.CSharpTrees));
                codeDocument = Process(testCase, testClass, languageVersion);
            }
            catch (Exception e)
            {
                var error = (e as TargetInvocationException)?.InnerException ?? e;
                output.Append("# failed: ").Append(error.GetType().Name).Append(": ").Append(error.Message.ReplaceLineEndings(" ")).Append('\n');
                differing.Append("# differs ").Append(relative).Append(": failed (").Append(error.Message.ReplaceLineEndings(" ")).Append(")\n");
                failed++;
                continue;
            }
            var code = Codegen.GeneratedCode(codeDocument);
            var mappings = Codegen.Mappings(codeDocument, relative);
            var diagnostics = Codegen.Diagnostics(codeDocument);
            Codegen.AppendSection(output, null, code);
            Codegen.AppendSection(output, "### mappings", mappings);
            Codegen.AppendSection(output, "### diagnostics", diagnostics);
            if (!check) continue;

            var parts = new List<string>();
            if (Same(code, Baseline(inputPath, ".codegen.cs"))) same++; else parts.Add("codegen");
            if (Baseline(inputPath, ".ir.txt") is { } ir)
            {
                irBaselines++;
                if (Same(Codegen.Ir(codeDocument), ir)) irSame++; else parts.Add("ir");
            }
            if (Baseline(inputPath, ".mappings.txt") is { } mappingsBaseline)
            {
                mappingsBaselines++;
                if (Same(mappings, mappingsBaseline)) mappingsSame++; else parts.Add("mappings");
            }
            if (Same(diagnostics, Baseline(inputPath, ".diagnostics.txt") ?? "")) diagnosticsSame++; else parts.Add("diagnostics");
            if (parts.Count > 0) differing.Append("# differs ").Append(relative).Append(": ").Append(string.Join(' ', parts)).Append('\n');
        }
        if (!check) return $"# cases={cases} skipped={skipped} failed={failed}\n";
        return $"# cases={cases} same={same} ir-same={irSame} mappings-same={mappingsSame} skipped={skipped}" +
            $" diagnostics-same={diagnosticsSame} failed={failed} ir-baselines={irBaselines} mappings-baselines={mappingsBaselines}\n" + differing;
    }

    /// <summary>
    /// The descriptors of a case as JSON (TagHelperJson) at <c>&lt;dir&gt;/&lt;Project&gt;/&lt;Class&gt;/&lt;Name&gt;.taghelpers.json</c>, the project the
    /// test project's folder without <c>Microsoft.AspNetCore.</c> (<c>Mvc.Razor.Extensions</c>), as razor-psi's RazorCodegenCases names a case.
    /// </summary>
    static void WriteTagHelpers(string dir, string fullPath, string testFileName, string relative, TagHelperCollection tagHelpers)
    {
        if (tagHelpers.Count == 0) return;
        var at = fullPath.LastIndexOf("/test/TestFiles/", StringComparison.Ordinal);
        var project = at >= 0 ? Path.GetFileName(fullPath[..at]).Replace("Microsoft.AspNetCore.", "") : "";
        const string prefix = "TestFiles/IntegrationTests/";
        var inProject = testFileName.StartsWith(prefix, StringComparison.Ordinal) ? testFileName[prefix.Length..] : relative;
        var file = Path.Combine(dir, project, inProject[..^Path.GetExtension(inProject).Length] + ".taghelpers.json");
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        File.WriteAllText(file, TagHelperJson.Serialize(tagHelpers), new UTF8Encoding(false));
    }

    static RazorCodeDocument Process(TestCase testCase, TestClass testClass, RazorLanguageVersion? languageVersion)
    {
        // CreateProjectItemFromFile: the paths are the test's file name, the text the file's (the tests ran on Windows: CRLF), a BOM.
        var text = File.ReadAllText(testCase.InputPath).Replace("\r\n", "\n").Replace("\n", "\r\n");
        if (text.Length > 0 && text[0] == '﻿') text = text[1..];
        var withoutExtension = testCase.TestFileName[..^Path.GetExtension(testCase.TestFileName).Length];
        var workingDirectory = Path.GetDirectoryName(withoutExtension)!;
        var item = new DumpProjectItem(workingDirectory, "/" + testCase.TestFileName, testCase.TestFileName, testCase.TestFileName, testCase.FileKind, text);
        var fileSystem = new DumpFileSystem();
        foreach (var (fileName, importText) in testCase.Imports)
        {
            // CreateProjectItemFromText as the test's NormalizedDefaultImportFeature reads it back: CRLF, trimmed, base path "".
            var physicalPath = Path.Combine(workingDirectory, fileName).Replace('/', '\\');
            var filePath = "/" + physicalPath.Replace('\\', '/').TrimStart('/');
            var normalized = Regex.Replace(importText, "(?<!\r)\n", "\r\n").Trim();
            fileSystem.Add(new DumpProjectItem("", filePath, physicalPath, physicalPath, null, normalized));
        }
        var configuration = languageVersion == null ? testClass.Configuration : testClass.Configuration with { LanguageVersion = languageVersion };
        // Always: the references hold MVC's own tag helpers (UrlResolutionTagHelper, BodyTagHelper, HeadTagHelper) the default imports add.
        var tagHelpers = TagHelperDiscovery.Discover(testClass, testCase.CSharpTrees);
        var engine = Codegen.CreateTestEngine(configuration, fileSystem, testClass.RegisterMvc, testClass.CSharpVersion, tagHelpers);
        // Process(projectItem) with the tests' NormalizedDefaultImportFeature, which wraps every import feature (internal here): each import
        // that exists is read back trimmed and with CRLF line breaks — the default imports of the MVC extension start with a line break.
        var getImports = typeof(RazorProjectEngine).GetMethod("GetImports", BindingFlags.NonPublic | BindingFlags.Public | BindingFlags.Instance, [typeof(RazorProjectItem)])!;
        var imports = ((ImmutableArray<RazorProjectItem>)getImports.Invoke(engine, [item])!)
            .Where(i => i.Exists)
            .Select(i =>
            {
                using var reader = new StreamReader(i.Read());
                var normalized = Regex.Replace(reader.ReadToEnd().Trim(), "(?<!\r)\n", "\r\n");
                return RazorSourceDocument.ReadFrom(new DumpProjectItem(i.BasePath, i.FilePath, i.PhysicalPath, i.RelativePhysicalPath, null, normalized));
            })
            .ToImmutableArray();
        return engine.Process(RazorSourceDocument.ReadFrom(item), item.FileKind, imports, tagHelpers, CancellationToken.None);
    }

    static string? Baseline(string inputPath, string extension)
    {
        var path = Path.ChangeExtension(inputPath, extension);
        if (!File.Exists(path)) return null;
        var text = File.ReadAllText(path);
        return text.Length > 0 && text[0] == '﻿' ? text[1..] : text;
    }

    static bool Same(string actual, string? baseline) => baseline != null && Codegen.Lf(actual) == Codegen.Lf(baseline);
}
