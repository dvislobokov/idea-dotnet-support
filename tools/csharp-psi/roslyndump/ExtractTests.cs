// extract-tests: the source snippets Roslyn's parsing tests (Test/Syntax/Parsing) feed to the parser, one file each.
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;

namespace RoslynDump;

static partial class ExtractTests
{
    // Entry points: helpers of ParsingTests and its subclasses, and SyntaxFactory directly. The value is the kind of the
    // snippet: expr, stmt, member (ParseMemberDeclaration) or cs (a compilation unit). "node" is UsingNode/ParseNode,
    // whose kind depends on the test class (ParseNode overrides); doc is a doc comment built by a ParseNode override
    // (CrefParsingTests: `/// <see cref="{0}"/>`), written as the whole comment.
    static readonly Dictionary<string, string> EntryPoints = new()
    {
        ["UsingExpression"] = "expr",
        ["UsingStatement"] = "stmt",
        ["UsingDeclaration"] = "member",
        ["UsingTree"] = "cs",
        ["UsingNode"] = "node",
        ["ParseNode"] = "node",
        ["ParseTree"] = "cs",
        ["ParseFile"] = "cs",
        ["ParseAndValidate"] = "cs",
        ["ParseAndValidateFirst"] = "cs",
        ["ParseWithRoundTripCheck"] = "cs",
        ["ParseAndRoundTripping"] = "cs",
        ["ParseAndCheckTerminalSpans"] = "cs",
        ["UsingLineDirective"] = "cs",
        ["ParseExpression"] = "expr",          // ExpressionParsingTests' helper, or SyntaxFactory.ParseExpression
        ["ParseStatement"] = "stmt",           // StatementParsingTests' helper, or SyntaxFactory.ParseStatement
        ["ParseDeclaration"] = "member",       // MemberDeclarationParsingTests' helper
        ["ParseMemberDeclaration"] = "member",
        ["ParseSyntaxTree"] = "cs",
        ["ParseCompilationUnit"] = "cs",
        ["ParseText"] = "cs",                  // CSharpSyntaxTree.ParseText
    };

    // Parser entry points with no snippet kind of ours: counted, not extracted.
    static readonly HashSet<string> Unsupported =
        ["ParseName", "ParseTypeName", "ParseLeadingTrivia", "ParseTrailingTrivia", "ParseToken", "ParseTokens", "ParseAttributeArgumentList",
         "ParseArgumentList", "ParseBracketedArgumentList", "ParseParameterList", "ParseBracketedParameterList", "ParseIncompleteSyntax",
         "ParseAllPrefixes", "ParseKnownTypeName"];

    sealed record ClassInfo(string Name, string? Base, string? NodeKind, string? NodeOptions, string? TreeOptions,
        Dictionary<string, ExpressionSyntax> Fields, string? DocFormat = null);

    sealed record Snippet(string File, string Method, string Kind, string Entry, string Options, string ClassDefault, string Text);

    public static int Run(string testsDir, string outDir)
    {
        var parseOptions = new CSharpParseOptions(LanguageVersion.Preview);
        var files = Directory.EnumerateFiles(testsDir, "*.cs", SearchOption.TopDirectoryOnly).Order(StringComparer.Ordinal).ToList();
        var trees = files.Select(f => CSharpSyntaxTree.ParseText(File.ReadAllText(f), parseOptions, f)).ToList();

        // Pass 1: test classes, their ParseNode/ParseTree overrides and their constant string fields.
        var classes = new Dictionary<string, ClassInfo>();
        foreach (var tree in trees)
        foreach (var cls in tree.GetRoot().DescendantNodes().OfType<ClassDeclarationSyntax>())
        {
            if (classes.ContainsKey(cls.Identifier.ValueText)) continue;
            var baseName = cls.BaseList?.Types.FirstOrDefault()?.Type switch
            {
                IdentifierNameSyntax id => id.Identifier.ValueText,
                QualifiedNameSyntax q => q.Right.Identifier.ValueText,
                _ => null,
            };
            string? nodeKind = null, nodeOptions = null, treeOptions = null, docFormat = null;
            foreach (var m in cls.Members.OfType<MethodDeclarationSyntax>().Where(m => m.Modifiers.Any(SyntaxKind.OverrideKeyword)))
            {
                var body = (SyntaxNode?)m.Body ?? m.ExpressionBody;
                if (body == null) continue;
                var calls = body.DescendantNodes().OfType<InvocationExpressionSyntax>().ToList();
                var parse = calls.FirstOrDefault(c => CalleeName(c) is "ParseExpression" or "ParseStatement" or "ParseSyntaxTree" or "ParseCompilationUnit" or "ParseLeadingTrivia");
                // An override that only forwards its options parameter has no default of its own.
                var options = parse == null ? null : OptionsText(parse, 1);
                if (options == "options") options = null;
                if (m.Identifier.ValueText == "ParseNode")
                {
                    nodeKind = CalleeName(parse) switch
                    {
                        "ParseExpression" => "expr",
                        "ParseStatement" => "stmt",
                        "ParseSyntaxTree" or "ParseCompilationUnit" => "cs",
                        "ParseLeadingTrivia" => DocFormat(body) is { } f ? "doc" : "skip:doc-comment ParseNode override without a constant format",
                        _ => calls.Any(c => CalleeName(c) == "ParseTree") ? "cs" : "skip:unknown ParseNode override",
                    };
                    nodeOptions = options;
                    if (nodeKind == "doc") docFormat = DocFormat(body);
                }
                else if (m.Identifier.ValueText == "ParseTree") treeOptions = parse == null ? "<throws>" : options;
            }
            var fields = new Dictionary<string, ExpressionSyntax>();
            foreach (var f in cls.Members.OfType<FieldDeclarationSyntax>()
                         .Where(f => f.Modifiers.Any(SyntaxKind.ConstKeyword) || (f.Modifiers.Any(SyntaxKind.StaticKeyword) && f.Modifiers.Any(SyntaxKind.ReadOnlyKeyword))))
            foreach (var v in f.Declaration.Variables)
                if (v.Initializer != null) fields[v.Identifier.ValueText] = v.Initializer.Value;
            classes[cls.Identifier.ValueText] = new ClassInfo(cls.Identifier.ValueText, baseName, nodeKind, nodeOptions, treeOptions, fields, docFormat);
        }

        // The ParseNode/ParseTree in effect for a class: its own override or the nearest base's.
        (string NodeKind, string? NodeOptions, string? TreeOptions, string? DocFormat) Effective(string? cls)
        {
            string? kind = null, nodeOptions = null, treeOptions = null, docFormat = null;
            for (var guard = 0; cls != null && classes.TryGetValue(cls, out var info) && guard < 20; cls = info.Base, guard++)
            {
                if (kind == null && info.NodeKind != null) { kind = info.NodeKind; nodeOptions = info.NodeOptions; docFormat = info.DocFormat; }
                treeOptions ??= info.TreeOptions;
            }
            // ParsingTests.ParseNode is ParseTree(text, options).GetCompilationUnitRoot().
            return (kind ?? "cs", kind == null ? treeOptions : nodeOptions, treeOptions, docFormat);
        }

        // Pass 2: calls of the entry points.
        var snippets = new List<Snippet>();
        var skipped = new SortedDictionary<string, int>(StringComparer.Ordinal);
        void Skip(string reason) => skipped[reason] = skipped.GetValueOrDefault(reason) + 1;
        var resolvedLocals = 0;
        foreach (var tree in trees)
        {
            var testFile = Path.GetFileNameWithoutExtension(tree.FilePath);
            foreach (var call in tree.GetRoot().DescendantNodes().OfType<InvocationExpressionSyntax>())
            {
                var callee = CalleeName(call);
                if (callee == null) continue;
                // Calls through another receiver (e.g. tree.ParseText) are not parser entry points; SyntaxFactory and
                // CSharpSyntaxTree are, and so are this.X and X of the test class.
                var receiver = (call.Expression as MemberAccessExpressionSyntax)?.Expression.ToString();
                if (receiver is not (null or "SyntaxFactory" or "CSharpSyntaxTree" or "this" or "RoundTrippingTests" or "ParsingTests")) continue;
                if (receiver == "CSharpSyntaxTree" && callee != "ParseText" || callee == "ParseText" && receiver != "CSharpSyntaxTree") continue;
                if (Unsupported.Contains(callee)) { Skip($"entry point without a snippet kind: {callee}"); continue; }
                if (!EntryPoints.TryGetValue(callee, out var kind)) continue;
                var method = call.Ancestors().OfType<MethodDeclarationSyntax>().FirstOrDefault();
                var cls = call.Ancestors().OfType<ClassDeclarationSyntax>().FirstOrDefault()?.Identifier.ValueText;
                // The helpers themselves (ParseNode overrides, UsingStatement in ParsingTests, ...) forward a parameter.
                if (method != null && (EntryPoints.ContainsKey(method.Identifier.ValueText) || method.Modifiers.Any(SyntaxKind.OverrideKeyword)))
                    continue;
                var (nodeKind, nodeOptions, treeOptions, docFormat) = Effective(cls);
                string classDefault = "";
                if (kind == "node")
                {
                    kind = nodeKind;
                    classDefault = nodeOptions ?? "";
                    if (kind.StartsWith("skip:")) { Skip(kind[5..]); continue; }
                }
                else if (callee is "UsingTree" or "ParseTree" or "UsingLineDirective") classDefault = treeOptions ?? "";
                if (call.ArgumentList.Arguments.Count == 0) { Skip("no arguments"); continue; }
                var first = call.ArgumentList.Arguments[0].Expression;
                var resolver = new Resolver(call, classes, cls);
                var text = resolver.Fold(first);
                if (text == null) { Skip($"first argument not constant: {resolver.Failure ?? first.Kind().ToString()}"); continue; }
                if (resolver.UsedLocal) resolvedLocals++;
                var methodName = method?.Identifier.ValueText ?? "_member";
                if (kind == "doc") text = docFormat!.Replace("{0}", text);
                snippets.Add(new Snippet(testFile, methodName, kind, callee, OptionsText(call, 1) ?? "", Flat(classDefault), text.Replace("\r\n", "\n").Replace('\r', '\n')));
            }
        }

        // Output. A previous extraction (recognised by its index.tsv) is replaced; any other non-empty directory is refused.
        if (Directory.Exists(outDir) && Directory.EnumerateFileSystemEntries(outDir).Any())
        {
            if (!File.Exists(Path.Combine(outDir, "index.tsv")))
            {
                Console.Error.WriteLine($"extract-tests: {outDir} is not empty and has no index.tsv; refusing to overwrite");
                return 1;
            }
            Directory.Delete(outDir, recursive: true);
        }
        Directory.CreateDirectory(outDir);
        var index = new StringBuilder("file\tmethod\tkind\tentry\toptions\tclassDefault\tpath\n");
        foreach (var group in snippets.GroupBy(s => (s.File, s.Method)))
        {
            var list = group.ToList();
            for (var i = 0; i < list.Count; i++)
            {
                var s = list[i];
                var rel = $"{s.File}/{s.Method}{(list.Count > 1 ? "_" + (i + 1) : "")}.{s.Kind}";
                var path = Path.Combine(outDir, rel);
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                File.WriteAllText(path, s.Text, new UTF8Encoding(false));
                index.Append($"{s.File}\t{s.Method}\t{s.Kind}\t{s.Entry}\t{s.Options}\t{s.ClassDefault}\t{rel}\n");
            }
        }
        File.WriteAllText(Path.Combine(outDir, "index.tsv"), index.ToString(), new UTF8Encoding(false));

        Console.Error.WriteLine($"extract-tests: {files.Count} files, {snippets.Count} snippets ({resolvedLocals} through a local or field)");
        foreach (var g in snippets.GroupBy(s => s.Kind).OrderBy(g => g.Key, StringComparer.Ordinal))
            Console.Error.WriteLine($"  kind {g.Key}: {g.Count()}");
        foreach (var g in snippets.GroupBy(s => s.Entry).OrderBy(g => g.Key, StringComparer.Ordinal))
            Console.Error.WriteLine($"  entry {g.Key}: {g.Count()}");
        Console.Error.WriteLine($"  skipped: {skipped.Values.Sum()}");
        foreach (var (reason, count) in skipped) Console.Error.WriteLine($"    {count} {reason}");
        return 0;
    }

    // The constant format of `string.Format(<format>, text)` in a doc-comment ParseNode override, e.g.
    // `/// <see cref="{0}"/>` (CrefParsingTests), `/// <param name="{0}"/>` (NameAttributeValueParsingTests).
    static string? DocFormat(SyntaxNode body)
    {
        var format = body.DescendantNodes().OfType<InvocationExpressionSyntax>()
            .FirstOrDefault(c => CalleeName(c) == "Format" && c.ArgumentList.Arguments.Count == 2);
        if (format?.ArgumentList.Arguments[0].Expression is not LiteralExpressionSyntax lit || !lit.IsKind(SyntaxKind.StringLiteralExpression)) return null;
        var text = lit.Token.ValueText;
        return text.Contains("{0}") && !text.Contains("{{") && !text.Contains("}}") ? text : null;
    }

    static string? CalleeName(InvocationExpressionSyntax? call) => call?.Expression switch
    {
        IdentifierNameSyntax id => id.Identifier.ValueText,
        MemberAccessExpressionSyntax { Name: IdentifierNameSyntax id } => id.Identifier.ValueText,
        _ => null,
    };

    // The parse options argument as written: named options/parseOptions, or the first positional argument after the
    // text that looks like options (TestOptions.X, options, ...WithLanguageVersion(...)). Diagnostics are not options.
    static string? OptionsText(InvocationExpressionSyntax call, int from)
    {
        var args = call.ArgumentList.Arguments;
        foreach (var a in args.Skip(from))
        {
            if (a.NameColon?.Name.Identifier.ValueText is { } n)
            {
                if (n is "options" or "parseOptions") return Flat(a.Expression.ToString());
                continue;
            }
            if (a.Expression is InvocationExpressionSyntax inv && CalleeName(inv) == "Diagnostic") continue;
            if (a.Expression is InvocationExpressionSyntax { Expression: MemberAccessExpressionSyntax ma } && ma.ToString().StartsWith("Diagnostic(")) continue;
            var text = a.Expression.ToString();
            if (OptionsLike().IsMatch(text)) return Flat(text);
        }
        return null;
    }

    [GeneratedRegex(@"(?i)options|LanguageVersion|^null$")]
    private static partial Regex OptionsLike();

    static string Flat(string s) => Regex.Replace(s, @"\s+", " ").Trim();

    /// <summary>Folds a compile-time constant string: literals of every form, constant interpolations, +, string.Empty,
    /// locals assigned once in the enclosing method and const / static readonly fields of the test classes.</summary>
    sealed class Resolver(InvocationExpressionSyntax call, Dictionary<string, ClassInfo> classes, string? cls)
    {
        public string? Failure;
        public bool UsedLocal;
        int depth;

        public string? Fold(ExpressionSyntax e)
        {
            if (++depth > 50) return Fail("too deep");
            try { return FoldCore(e); }
            finally { depth--; }
        }

        string? Fail(string reason)
        {
            Failure ??= reason;
            return null;
        }

        string? FoldCore(ExpressionSyntax e)
        {
            switch (e)
            {
                case LiteralExpressionSyntax lit when lit.IsKind(SyntaxKind.StringLiteralExpression):
                    return lit.Token.ValueText;
                case LiteralExpressionSyntax lit:
                    return Fail($"literal {lit.Kind()}");
                case ParenthesizedExpressionSyntax p:
                    return Fold(p.Expression);
                case BinaryExpressionSyntax b when b.IsKind(SyntaxKind.AddExpression):
                    return Fold(b.Left) is { } l && Fold(b.Right) is { } r ? l + r : null;
                case InterpolatedStringExpressionSyntax s:
                {
                    var sb = new StringBuilder();
                    foreach (var c in s.Contents)
                    {
                        if (c is InterpolatedStringTextSyntax t) sb.Append(t.TextToken.ValueText);
                        else if (c is InterpolationSyntax { AlignmentClause: null, FormatClause: null } i && Fold(i.Expression) is { } v) sb.Append(v);
                        else return Fail("interpolation with a non-constant hole");
                    }
                    return sb.ToString();
                }
                case MemberAccessExpressionSyntax { Expression: PredefinedTypeSyntax, Name.Identifier.ValueText: "Empty" }:
                    return "";
                case MemberAccessExpressionSyntax { Expression: IdentifierNameSyntax owner, Name: IdentifierNameSyntax member }:
                    return classes.TryGetValue(owner.Identifier.ValueText, out var info) && info.Fields.TryGetValue(member.Identifier.ValueText, out var fe)
                        ? Fold(fe)
                        : Fail("member access");
                case IdentifierNameSyntax id:
                    return Identifier(id.Identifier.ValueText);
                default:
                    return Fail(e.Kind().ToString());
            }
        }

        string? Identifier(string name)
        {
            // A local of the enclosing method (or local function), declared once with an initializer and never assigned.
            var body = call.Ancestors().FirstOrDefault(a => a is BaseMethodDeclarationSyntax or LocalFunctionStatementSyntax or AccessorDeclarationSyntax);
            if (body != null)
            {
                if (body is BaseMethodDeclarationSyntax { ParameterList: var pl } && pl.Parameters.Any(p => p.Identifier.ValueText == name)
                    || body is LocalFunctionStatementSyntax { ParameterList: var lpl } && lpl.Parameters.Any(p => p.Identifier.ValueText == name))
                    return Fail("parameter (theory data)");
                var declarators = body.DescendantNodes().OfType<VariableDeclaratorSyntax>().Where(v => v.Identifier.ValueText == name).ToList();
                if (declarators.Count > 1) return Fail("local declared more than once");
                if (declarators.Count == 1)
                {
                    var assigned = body.DescendantNodes().OfType<AssignmentExpressionSyntax>()
                        .Any(a => a.Left is IdentifierNameSyntax l && l.Identifier.ValueText == name);
                    if (assigned) return Fail("local assigned more than once");
                    if (declarators[0].Initializer?.Value is not { } init) return Fail("local without initializer");
                    if (declarators[0].SpanStart > call.SpanStart) return Fail("local declared after the call");
                    UsedLocal = true;
                    return Fold(init);
                }
                if (body.DescendantNodes().OfType<ForEachStatementSyntax>().Any(f => f.Identifier.ValueText == name))
                    return Fail("foreach variable");
            }
            // A const or static readonly field of the test class or one of its bases.
            for (var (c, guard) = (cls, 0); c != null && classes.TryGetValue(c, out var info) && guard < 20; c = info.Base, guard++)
            {
                if (info.Fields.TryGetValue(name, out var fe))
                {
                    UsedLocal = true;
                    return Fold(fe);
                }
            }
            return Fail("unresolved identifier");
        }
    }
}
