// razordump: the Razor compiler's view of .razor / .cshtml files, the oracle of razor-psi (RAZOR_PLAN.md, R2). Format: README.md next to this file.
using System.Collections.Immutable;
using System.Diagnostics;
using System.Text;
using Microsoft.AspNetCore.Razor.Language;
using System.Reflection;
using Microsoft.CodeAnalysis.CSharp;
using RazorDump;

var positional = new List<string>();
string? outPath = null;
var roslynTokenizer = true;
var editHandlers = true;
string? languageVersionOption = null;
var crlf = false;
string? rootNamespace = null;
string? assemblyName = null;
var check = false;
var discoverTagHelpers = true;
var metadataChecksums = false;
var absolutePaths = false;
string? newLineOption = null;
var keepNewlines = false;
var references = new List<string>();
var extraCSharp = new List<string>();
var includes = new List<string>();
string? tagHelpersOut = null;
for (var i = 0; i < args.Length; i++)
{
    switch (args[i])
    {
        case "--out": outPath = args[++i]; break;
        // The SDK's default since .NET 10 (use-roslyn-tokenizer=true in Sdk.Razor.CurrentVersion.targets); the native tokenizer is the older one.
        case "--native-tokenizer": roslynTokenizer = false; break;
        // Span edit handlers (`SpanEditHandler;Accepts:…` after a span): the tooling's view, on in the compiler's own test baselines.
        case "--no-edit-handlers": editHandlers = false; break;
        // A Razor language version as RazorLangVersion takes it: 3.0, 8.0, 10.0 (the SDK's for net10.0), latest, experimental.
        case "--langversion": languageVersionOption = args[++i]; break;
        // tree, codegen, ir: the files are read with their line breaks turned into CRLF instead of LF (as the compiler's test inputs are
        // written); codegen, ir also generate CRLF then (--newline overrides). cases-codegen always reads CRLF, as the tests ran on Windows.
        case "--crlf": crlf = true; break;
        // codegen, ir: the project's root namespace (MSBuild RootNamespace); the source generator's default is ASP.
        case "--root-namespace": rootNamespace = args[++i]; break;
        // codegen, ir: the assembly the directory's components and tag helpers belong to (`@addTagHelper *, Name`); default the root namespace.
        case "--assembly-name": assemblyName = args[++i]; break;
        // codegen, ir: more assemblies to discover tag helpers and components in (package references), `;`-separated or `@file`.
        case "--references":
            var referenceSpec = args[++i];
            references.AddRange((referenceSpec.StartsWith('@') ? File.ReadAllLines(referenceSpec[1..]) : referenceSpec.Split(';')).Select(x => x.Trim()).Where(x => x.Length > 0));
            break;
        // codegen, ir: more C# for the discovery compilation (files or directories; obj/<cfg>/<tfm>/<Project>.GlobalUsings.g.cs of ImplicitUsings).
        case "--csharp":
            var csharpSpec = args[++i];
            extraCSharp.AddRange((csharpSpec.StartsWith('@') ? File.ReadAllLines(csharpSpec[1..]) : csharpSpec.Split(';')).Select(x => x.Trim()).Where(x => x.Length > 0));
            break;
        // codegen, ir: the RazorSourceChecksumAttribute of the file and its imports (GenerateRazorMetadataSourceChecksumAttributes=true).
        case "--metadata-checksums": metadataChecksums = true; break;
        // codegen, ir: the files' absolute paths with the OS separators as the build has them (#pragma checksum, #line, the tag helper ids).
        case "--absolute-paths": absolutePaths = true; break;
        // codegen, ir: the generated document's line breaks, lf or crlf; default those of the input (--crlf). A build on Windows writes crlf
        // (the generator's NewLine is Environment.NewLine) whatever the input's are.
        case "--newline": newLineOption = args[++i]; break;
        // codegen, ir: the files' line breaks as on disk (a build reads them so) instead of LF (or CRLF with --crlf).
        case "--keep-newlines": keepNewlines = true; break;
        // codegen, ir: no tag helper discovery (components and directive attributes stay markup).
        case "--no-tag-helpers": discoverTagHelpers = false; break;
        // cases-codegen: compare every case with its baselines, the summary to stderr.
        case "--check": check = true; break;
        // cases-codegen: the descriptors of every processed case as JSON, <dir>/<Project>/<Class>/<Name>.taghelpers.json.
        case "--tag-helpers-out": tagHelpersOut = args[++i]; break;
        // Relative paths under the directory to dump (files or directories), `;`-separated; `@file` reads one per line.
        case "--include":
            var spec = args[++i];
            var items = spec.StartsWith('@') ? File.ReadAllLines(spec[1..]) : spec.Split(';');
            includes.AddRange(items.Select(x => x.Trim().Replace('\\', '/').TrimEnd('/')).Where(x => x.Length > 0));
            break;
        default: positional.Add(args[i]); break;
    }
}
const string usage = """
    usage: razordump tree <file or directory> [--native-tokenizer] [--no-edit-handlers] [--langversion V] [--crlf] [--include P;Q|@file] [--out file]
           razordump cases <directory of .stree.txt baselines> [--native-tokenizer] [--no-edit-handlers] [--langversion V] [--out file]
           razordump codegen <file or directory> [--langversion V] [--root-namespace NS] [--assembly-name A] [--references P;Q|@file] [--csharp P;Q|@file] [--no-tag-helpers] [--metadata-checksums] [--absolute-paths] [--newline lf|crlf] [--crlf | --keep-newlines] [--include P;Q|@file] [--out file]
           razordump ir <file or directory> [same options as codegen]
           razordump cases-codegen <TestFiles directory> [--langversion V] [--check] [--tag-helpers-out dir] [--out file]
           razordump tag-helpers [<directory>] [--langversion V] [--root-namespace NS] [--assembly-name A] [--references P;Q|@file] [--csharp P;Q|@file] [--out file]
    """;
if (positional.Count != 2 && !(positional.Count == 1 && positional[0] == "tag-helpers"))
{
    Console.Error.WriteLine(usage);
    return 2;
}
if (!RazorLanguageVersion.TryParse(languageVersionOption ?? "latest", out var razorVersion))
{
    Console.Error.WriteLine($"unknown Razor language version: {languageVersionOption}");
    return 2;
}
var command = positional[0];
var target = positional.Count > 1 ? Path.GetFullPath(positional[1]) : null!;
var output = new StringBuilder();
var stopwatch = Stopwatch.StartNew();
var files = 0;
var filesWithDiagnostics = 0;
var nodes = 0L;
switch (command)
{
    case "tree":
    {
        var root = Directory.Exists(target) ? target : Path.GetDirectoryName(target)!;
        var engine = CreateEngine(root, razorVersion, roslynTokenizer, editHandlers);
        foreach (var file in EnumerateFiles(target, includes))
        {
            var relative = Path.GetRelativePath(root, file).Replace('\\', '/');
            // CRLF and CR become LF before parsing, so offsets are those of the IntelliJ document (as roslyndump does); --crlf makes them CRLF.
            var text = NormalizeNewLines(File.ReadAllText(file), crlf);
            var kind = FileKinds.GetFileKindFromPath(file);
            var source = RazorSourceDocument.Create(text, relative);
            var tree = Parse(engine, source, kind);
            Dump(output, relative, tree, ref nodes, ref filesWithDiagnostics);
            files++;
        }
        break;
    }
    case "cases":
    {
        // The parser tests of the compiler (legacyTest/TestFiles/ParserTests): the input of a case is the first line of its `.stree.txt`
        // (`RazorDocument - [0..N)::N - [text]`, a line break written `LF` — the serializer writes Environment.NewLine so, and the tests
        // run on Windows: the inputs have CRLF line breaks); the kind is the test's, guessed as RazorLexerGateTest does.
        var engine = CreateEngine(target, razorVersion, roslynTokenizer, editHandlers);
        var cases = Directory.EnumerateFiles(target, "*.stree.txt", SearchOption.AllDirectories)
            .Select(f => Path.GetRelativePath(target, f).Replace('\\', '/')).OrderBy(x => x, StringComparer.Ordinal).ToList();
        foreach (var relative in cases)
        {
            var firstLine = File.ReadLines(Path.Combine(target, relative)).FirstOrDefault() ?? "";
            var start = firstLine.IndexOf(" - [", StringComparison.Ordinal);
            start = start < 0 ? -1 : firstLine.IndexOf(" - [", start + 4, StringComparison.Ordinal);
            if (start < 0 || !firstLine.EndsWith(']')) continue;
            var text = firstLine[(start + 4)..^1].Replace("LF", "\r\n");
            var name = relative[..^".stree.txt".Length];
            var kind = name.Contains("Component", StringComparison.Ordinal) || text.Contains("@code", StringComparison.Ordinal) ? RazorFileKind.Component : RazorFileKind.Legacy;
            var source = RazorSourceDocument.Create(text, fileName: ""); // no path: the baselines print spans without one
            var tree = Parse(engine, source, kind);
            Dump(output, name, tree, ref nodes, ref filesWithDiagnostics);
            files++;
        }
        break;
    }
    case "codegen":
    case "ir":
    {
        // The source generator's view: every file with the imports of its folder and the folders above (within the directory).
        var root = Directory.Exists(target) ? target : Path.GetDirectoryName(target)!;
        var all = EnumerateFiles(root, []).Select(f => Path.GetRelativePath(root, f).Replace('\\', '/')).ToList();
        var fileSystem = Codegen.ReadDirectory(root, all, keepNewlines ? null : crlf ? "\r\n" : "\n", absolutePaths);
        var tagHelpers = new FixedTagHelperFeature(TagHelperCollection.Empty);
        var newLine = (newLineOption ?? (crlf ? "crlf" : "lf")) == "crlf" ? "\r\n" : "\n";
        var engine = Codegen.CreateGeneratorEngine(fileSystem, razorVersion, rootNamespace ?? "ASP", newLine, tagHelpers, metadataChecksums);
        if (discoverTagHelpers)
            tagHelpers.TagHelpers = Codegen.DiscoverProjectTagHelpers(engine, fileSystem, root, all, assemblyName ?? rootNamespace ?? "ASP", references, extraCSharp);
        foreach (var file in EnumerateFiles(target, includes))
        {
            var relative = Path.GetRelativePath(root, file).Replace('\\', '/');
            var codeDocument = engine.Process(fileSystem.GetItem("/" + relative, fileKind: null), CancellationToken.None);
            output.Append("### ").Append(relative).Append('\n');
            if (command == "ir") Codegen.AppendSection(output, null, Codegen.Ir(codeDocument));
            else Codegen.AppendCodegen(output, codeDocument, relative);
            if (Codegen.GetCSharpDocument(codeDocument).Diagnostics.Length > 0) filesWithDiagnostics++;
            files++;
        }
        break;
    }
    case "cases-codegen":
    {
        var summary = CasesCodegen.Run(target, languageVersionOption == null ? null : razorVersion, check, output, tagHelpersOut);
        if (outPath != null) File.WriteAllText(outPath, output.ToString(), new UTF8Encoding(false));
        else Console.Out.Write(output.ToString());
        Console.Error.Write(summary);
        Console.Error.WriteLine($"# millis={stopwatch.ElapsedMilliseconds}");
        return 0;
    }
    case "tag-helpers":
    {
        // The descriptors codegen discovers (all the producers), as JSON: of a project directory's components and `.cs` files, or of
        // --csharp / --references alone without one.
        List<string> all = target == null ? [] : [.. EnumerateFiles(target, []).Select(f => Path.GetRelativePath(target, f).Replace('\\', '/'))];
        var fileSystem = target == null ? new DumpFileSystem() : Codegen.ReadDirectory(target, all, "\n", absolutePaths: false);
        var engine = Codegen.CreateGeneratorEngine(fileSystem, razorVersion, rootNamespace ?? "ASP", "\n", new FixedTagHelperFeature(TagHelperCollection.Empty), false);
        var tagHelpers = Codegen.DiscoverProjectTagHelpers(engine, fileSystem, target, all, assemblyName ?? rootNamespace ?? "ASP", references, extraCSharp);
        var json = TagHelperJson.Serialize(tagHelpers);
        if (outPath != null) File.WriteAllText(outPath, json, new UTF8Encoding(false));
        else Console.Out.Write(json);
        Console.Error.WriteLine($"# tagHelpers={tagHelpers.Count} millis={stopwatch.ElapsedMilliseconds}");
        return 0;
    }
    default:
        Console.Error.WriteLine(usage);
        return 2;
}
if (outPath != null) File.WriteAllText(outPath, output.ToString(), new UTF8Encoding(false));
else Console.Out.Write(output.ToString());
Console.Error.WriteLine(command is "codegen" or "ir"
    ? $"# files={files} filesWithDiagnostics={filesWithDiagnostics} millis={stopwatch.ElapsedMilliseconds}"
    : $"# files={files} filesWithDiagnostics={filesWithDiagnostics} nodes={nodes} millis={stopwatch.ElapsedMilliseconds}");
return 0;

static RazorProjectEngine CreateEngine(string root, RazorLanguageVersion version, bool roslynTokenizer, bool editHandlers)
{
    // As the source generator configures its engine (RazorSourceGenerator.Helpers.cs): the compiler's features, the MVC extension
    // (which registers inject, model, page, section for .cshtml), the C# language version last (the MVC extension sets C# 8).
    var configuration = new RazorConfiguration(version, "default", [], UseRoslynTokenizer: roslynTokenizer);
    var fileSystem = RazorProjectFileSystem.Create(root);
    return RazorProjectEngine.Create(configuration, fileSystem, b =>
    {
        b.ConfigureParserOptions(o =>
        {
            o.UseRoslynTokenizer = roslynTokenizer;
            Internals.SetEnableSpanEditHandlers(o, editHandlers);
        });
        Microsoft.CodeAnalysis.Razor.CompilerFeatures.Register(b);
        Microsoft.AspNetCore.Mvc.Razor.Extensions.RazorExtensions.Register(b);
        b.SetCSharpLanguageVersion(LanguageVersion.Preview);
    });
}

static RazorSyntaxTree Parse(RazorProjectEngine engine, RazorSourceDocument source, RazorFileKind kind)
{
    // The whole pipeline, as a build runs it; the tree taken is the parse before the tag helper rewrite (R2; the rewritten one is R5).
    var codeDocument = engine.Process(source, kind, ImmutableArray<RazorSourceDocument>.Empty, tagHelpers: null);
    return Internals.GetSyntaxTree(codeDocument);
}

static void Dump(StringBuilder output, string name, RazorSyntaxTree tree, ref long nodes, ref int filesWithDiagnostics)
{
    output.Append("### ").Append(name).Append('\n');
    // The serializer writes "LF" for Environment.NewLine (CRLF here) inside content and ends its lines with it too; a lone '\n' of
    // LF content stays raw, so it is written "LF" after the line ends are told apart. (The tool runs where Environment.NewLine is CRLF.)
    var serialized = Internals.Serialize(tree).Replace("\r\n", "\u0001").Replace("\n", "LF").Replace("\u0001", "\n");
    output.Append(serialized);
    if (!serialized.EndsWith('\n')) output.Append('\n');
    nodes += serialized.Count(c => c == '\n');
    var diagnostics = tree.Diagnostics;
    if (diagnostics.Length > 0) filesWithDiagnostics++;
    foreach (var diagnostic in diagnostics)
    {
        output.Append("# diagnostic ").Append(diagnostic.Id).Append(diagnostic.Span.ToString()).Append(' ').Append(diagnostic.GetMessage().Replace("\r\n", "\n").Replace('\n', ' ')).Append('\n');
    }
}

static IEnumerable<string> EnumerateFiles(string target, List<string> includes)
{
    if (File.Exists(target)) return [target];
    var all = Directory.EnumerateFiles(target, "*.*", new EnumerationOptions { RecurseSubdirectories = true, AttributesToSkip = 0 })
        .Where(f => f.EndsWith(".razor", StringComparison.OrdinalIgnoreCase) || f.EndsWith(".cshtml", StringComparison.OrdinalIgnoreCase))
        .Select(f => Path.GetRelativePath(target, f).Replace('\\', '/'))
        .Where(r => !r.Split('/').Any(part => part is "bin" or "obj" or ".git" or "node_modules"))
        .Where(r => includes.Count == 0 || includes.Any(i => r == i || r.StartsWith(i + "/", StringComparison.Ordinal)))
        .OrderBy(r => r, StringComparer.Ordinal)
        .ToList();
    return all.Select(r => Path.Combine(target, r));
}

static string NormalizeNewLines(string text, bool crlf)
{
    if (text.Length > 0 && text[0] == '﻿') text = text[1..];
    var lf = text.Replace("\r\n", "\n").Replace('\r', '\n');
    return crlf ? lf.Replace("\n", "\r\n") : lf;
}

/// <summary>The internal members of the compiler the tool needs: the dll is ReadyToRun, so no IgnoresAccessChecksTo rewrite; reflection instead.</summary>
static class Internals
{
    const BindingFlags Any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;
    static readonly Assembly Compiler = typeof(RazorProjectEngine).Assembly;

    public static void SetEnableSpanEditHandlers(RazorParserOptions.Builder builder, bool value) =>
        typeof(RazorParserOptions.Builder).GetProperty("EnableSpanEditHandlers", Any)!.SetValue(builder, value);

    public static RazorSyntaxTree GetSyntaxTree(RazorCodeDocument document) =>
        (RazorSyntaxTree)typeof(RazorCodeDocument).GetMethod("GetRequiredSyntaxTree", Any, [])!.Invoke(document, null)!;

    public static string Serialize(RazorSyntaxTree tree)
    {
        var root = typeof(RazorSyntaxTree).GetProperty("Root", Any)!.GetValue(tree)!;
        var serializer = Compiler.GetType("Microsoft.AspNetCore.Razor.Language.Syntax.SyntaxSerializer+Default", throwOnError: true)!;
        var razorSyntaxNode = Compiler.GetType("Microsoft.AspNetCore.Razor.Language.Syntax.RazorSyntaxNode", throwOnError: true)!;
        return (string)serializer.GetMethod("Serialize", Any, [razorSyntaxNode])!.Invoke(null, [root])!;
    }
}
