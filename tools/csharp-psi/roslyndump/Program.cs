// roslyndump: Roslyn's view of C# files for the corpus tests of csharp-psi. Line format: README.md next to this file.
using System.Diagnostics;
using System.Text;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using RoslynDump;

var positional = new List<string>();
var defines = new List<string>();
string? outPath = null;
var lines = false;
var fields = false;
var languageVersion = LanguageVersion.Preview;
var includes = new List<string>();
var semantics = new Semantics.Options();
for (var i = 0; i < args.Length; i++)
{
    switch (args[i])
    {
        case "--define": defines.AddRange(args[++i].Split(';', StringSplitOptions.RemoveEmptyEntries)); break;
        case "--out": outPath = args[++i]; break;
        case "--lines": lines = true; break;
        // tree, expr, stmt, member: each child of a node carries the Syntax.xml field it belongs to (` f=<Field>`).
        case "--fields": fields = true; break;
        // LangVersion strings as csc takes them (LanguageVersionFacts.TryParse): 7.3, 14, latest, default, preview.
        case "--langversion":
            if (!LanguageVersionFacts.TryParse(args[++i], out languageVersion))
            {
                Console.Error.WriteLine($"unknown language version: {args[i]}");
                return 2;
            }
            break;
        // Relative paths under the directory to dump (files or directories), `;`-separated; `@file` reads one per line.
        case "--include":
            var spec = args[++i];
            var items = spec.StartsWith('@') ? File.ReadAllLines(spec[1..]) : spec.Split(';');
            includes.AddRange(items.Select(x => x.Trim().Replace('\\', '/').TrimEnd('/')).Where(x => x.Length > 0));
            break;
        // semantics (Semantics.cs): project mode, files mode.
        case "--root": semantics.Root = args[++i]; break;
        case "--configuration": semantics.Configuration = args[++i]; break;
        case "--framework": semantics.Framework = args[++i]; break;
        case "--no-generators": semantics.Generators = false; break;
        case "--refs":
            var refs = args[++i];
            semantics.References.AddRange((refs.StartsWith('@') ? File.ReadAllLines(refs[1..]) : refs.Split(';')).Select(x => x.Trim()).Where(x => x.Length > 0));
            break;
        case "--assembly": semantics.Assembly = args[++i]; break;
        case "--usings": semantics.Usings.AddRange(args[++i].Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)); break;
        default: positional.Add(args[i]); break;
    }
}
const string usage = """
    usage: roslyndump tokens|tree|doc <file or directory> [--fields] [--define A;B] [--langversion V] [--include P;Q|@file] [--out file]
           roslyndump expr|stmt|member <file or directory> [--lines] [--fields] [--define A;B] [--langversion V] [--include P;Q|@file] [--out file]
           roslyndump semantics <project file> [--root dir] [--configuration C] [--framework TFM] [--no-generators] [--include P;Q|@file] [--out file]
           roslyndump semantics <file or directory> [--root dir] [--refs dir;a.dll|@file] [--assembly Name] [--usings A;B] [--define A;B] [--langversion V] [--include P;Q|@file] [--out file]
           roslyndump gen-kinds --out <directory>
           roslyndump gen-psi <Roslyn source directory> --out <csharp-psi-core directory>
           roslyndump extract-tests <ParsingTests directory> --out <directory>
           roslyndump errors [--out file]
    """;
var mode = positional.Count > 0 ? positional[0] : "";
switch (mode)
{
    case "gen-kinds" when positional.Count == 1 && outPath != null:
        return GenKinds.Run(outPath);
    case "gen-psi" when positional.Count == 2 && outPath != null:
        return GenPsi.Run(Path.GetFullPath(positional[1]), Path.GetFullPath(outPath));
    case "extract-tests" when positional.Count == 2 && outPath != null:
        return ExtractTests.Run(Path.GetFullPath(positional[1]), Path.GetFullPath(outPath));
    case "semantics" when positional.Count == 2:
        semantics.OutPath = outPath;
        semantics.Defines = defines;
        semantics.LanguageVersion = languageVersion;
        semantics.Includes = includes;
        return Semantics.Run(positional[1], semantics);
    case "errors" when positional.Count == 1:
        return ErrorCodes.Run(outPath);
    case "tokens" or "tree" or "doc" or "expr" or "stmt" or "member" when positional.Count == 2:
        break;
    default:
        Console.Error.WriteLine(usage);
        return 2;
}

var root = Path.GetFullPath(positional[1]);
var options = new CSharpParseOptions(languageVersion, DocumentationMode.Parse, SourceCodeKind.Regular, defines);
// Snippet modes: a directory holds one snippet per *.expr / *.stmt / *.member file; a single file is one snippet,
// or one snippet per non-empty line with --lines. `doc` is `tree` over *.doc files (doc comment snippets of
// extract-tests).
var extension = mode is "tokens" or "tree" ? ".cs" : "." + mode;

// Directories are walked in a stable order so that two dumps of one corpus diff cleanly.
var files = File.Exists(root)
    ? [root]
    : Directory.EnumerateFiles(root, "*" + extension, SearchOption.AllDirectories)
        .Where(f => !Path.GetRelativePath(root, f).Split(Path.DirectorySeparatorChar).Any(p => p is "bin" or "obj" or ".git"))
        .Where(f => includes.Count == 0 || IsIncluded(Path.GetRelativePath(root, f).Replace('\\', '/')))
        .Order(StringComparer.Ordinal)
        .ToList();

using var output = outPath == null
    ? new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = false }
    : new StreamWriter(outPath, false, new UTF8Encoding(false));
var dumper = new Dumper(output) { Fields = fields };
var watch = Stopwatch.StartNew();
long units = 0, unitsWithErrors = 0;
foreach (var file in files)
{
    // The IntelliJ document has LF line separators and no BOM; offsets are compared in that text.
    var text = File.ReadAllText(file).Replace("\r\n", "\n").Replace('\r', '\n');
    var name = File.Exists(root) ? Path.GetFileName(file) : Path.GetRelativePath(root, file).Replace('\\', '/');
    if (lines && mode is "expr" or "stmt" or "member")
    {
        var split = text.Split('\n');
        for (var n = 0; n < split.Length; n++)
            if (!string.IsNullOrWhiteSpace(split[n])) Unit($"{name}:{n + 1}", split[n]);
    }
    else Unit(name, text);
}
output.Flush();
Console.Error.WriteLine($"# files={files.Count} units={units} unitsWithErrors={unitsWithErrors} nodes={dumper.Nodes} errors={dumper.Errors} millis={watch.ElapsedMilliseconds}");
return 0;

// A relative path is included when it is an --include entry or lies under one.
bool IsIncluded(string relative) =>
    includes.Any(p => relative == p || relative.StartsWith(p + "/", StringComparison.Ordinal));

void Unit(string name, string text)
{
    units++;
    output.Write("file ");
    output.WriteLine(name);
    var errorsBefore = dumper.Errors;
    switch (mode)
    {
        case "tokens":
            dumper.DumpTokens(CSharpSyntaxTree.ParseText(text, options).GetRoot());
            return;
        case "tree" or "doc":
            dumper.DumpTree(CSharpSyntaxTree.ParseText(text, options).GetRoot());
            break;
        // consumeFullText: what follows the snippet becomes skipped-tokens trivia with an error, as in Roslyn's tests.
        case "expr":
            dumper.DumpTree(SyntaxFactory.ParseExpression(text, 0, options, consumeFullText: true));
            break;
        case "stmt":
            dumper.DumpTree(SyntaxFactory.ParseStatement(text, 0, options, consumeFullText: true));
            break;
        case "member":
            // ParseMemberDeclaration returns null when the text does not start a member: dumped as an empty unit.
            if (SyntaxFactory.ParseMemberDeclaration(text, 0, options, consumeFullText: true) is { } member) dumper.DumpTree(member);
            break;
    }
    if (dumper.Errors > errorsBefore) unitsWithErrors++;
}

namespace RoslynDump
{
    /// <summary>The N/T/V/D records of README.md.</summary>
    sealed class Dumper(TextWriter output)
    {
        public long Nodes;
        public long Errors;
        /// <summary>`--fields`: children are annotated with the field of their parent (<see cref="SlotFields"/>).</summary>
        public bool Fields;

        /// <summary>Nodes and tokens, then the trivia section, then the parser's errors (`tree`, `expr`, `stmt`).</summary>
        public void DumpTree(SyntaxNode root)
        {
            DumpNode(root, 0);
            DumpTreeTrivia(root);
            // A node parsed by SyntaxFactory.ParseExpression etc. has positions relative to the start of its text, as
            // a compilation unit does.
            foreach (var d in root.GetDiagnostics())
            {
                if (d.Severity != DiagnosticSeverity.Error) continue;
                Errors++;
                output.WriteLine($"D {d.Id} {d.Location.SourceSpan.Start} {d.Location.SourceSpan.End} {d.GetMessage().ReplaceLineEndings(" ")}");
            }
        }

        void DumpNode(SyntaxNode node, int depth, string? field = null)
        {
            Nodes++;
            output.Write($"N {depth} {node.Kind()} {node.Span.Start} {node.Span.End}");
            output.WriteLine(field == null ? "" : " f=" + field);
            var names = Fields ? SlotFields.Of(node) : null;
            var i = 0;
            foreach (var child in node.ChildNodesAndTokens())
            {
                var name = names?[i++];
                if (child.AsNode() is { } n) DumpNode(n, depth + 1, name);
                else output.WriteLine(TokenLine(child.AsToken(), depth + 1) + (name == null ? "" : " f=" + name));
            }
        }

        // Trivia hangs on tokens in Roslyn, so a directive would land inside a node whose span does not cover it. The
        // tree is written without trivia; trivia that carries structure (directives, skipped tokens, doc comments) or
        // is disabled text follows as a flat section, structure nodes at depth 1. The skipped tokens inside a doc
        // comment (trivia of its XML tokens) follow its structure as V records of their own.
        void DumpTreeTrivia(SyntaxNode root)
        {
            foreach (var trivia in root.DescendantTrivia(descendIntoTrivia: false))
            {
                if (trivia.GetStructure() is { } structure)
                {
                    output.WriteLine($"V {trivia.Kind()} {trivia.Span.Start} {trivia.Span.End}");
                    DumpNode(structure, 1);
                    if (structure is Microsoft.CodeAnalysis.CSharp.Syntax.DocumentationCommentTriviaSyntax)
                    {
                        foreach (var inner in structure.DescendantTrivia(descendIntoTrivia: true))
                            if (inner.IsKind(SyntaxKind.SkippedTokensTrivia))
                                output.WriteLine($"V {inner.Kind()} {inner.Span.Start} {inner.Span.End}");
                    }
                }
                else if (trivia.IsKind(SyntaxKind.DisabledTextTrivia))
                    output.WriteLine($"V {trivia.Kind()} {trivia.Span.Start} {trivia.Span.End}");
            }
        }

        // Every token and every trivia in text order, structured trivia flattened into its tokens.
        // Recursion by hand: DescendantTokens(descendIntoTrivia) yields a directive's tokens before the plain trivia
        // around them.
        public void DumpTokens(SyntaxNode root)
        {
            foreach (var token in root.DescendantTokens(descendIntoTrivia: false))
            {
                DumpTokenTrivia(token.LeadingTrivia);
                if (token.Span.Length > 0 || token.IsKind(SyntaxKind.EndOfFileToken)) output.WriteLine(TokenLine(token, null));
                DumpTokenTrivia(token.TrailingTrivia);
            }
        }

        void DumpTokenTrivia(SyntaxTriviaList list)
        {
            foreach (var t in list)
            {
                if (t.GetStructure() is { } structure) DumpTokens(structure);
                else output.WriteLine($"V {t.Kind()} {t.Span.Start} {t.Span.End}");
            }
        }

        static string TokenLine(SyntaxToken token, int? depth)
        {
            var line = new StringBuilder("T ");
            if (depth != null) line.Append(depth).Append(' ');
            line.Append(token.Kind()).Append(' ').Append(token.Span.Start).Append(' ').Append(token.Span.End);
            if (token.IsMissing) line.Append(" missing");
            // ContextualKind() is internal; for identifiers it is the contextual keyword the text spells.
            if (token.IsKind(SyntaxKind.IdentifierToken) && SyntaxFacts.GetContextualKeywordKind(token.ValueText) is var contextual and not SyntaxKind.None)
                line.Append(" ck=").Append(contextual);
            return line.ToString();
        }
    }
}
