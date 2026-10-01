// Code metrics of C# projects, the ones of Visual Studio (Analyze | Calculate Code Metrics):
//   maintainability index  MAX(0, (171 - 5.2 ln(Halstead volume) - 0.23 cyclomatic complexity - 16.2 ln(executable lines)) * 100 / 171)
//   cyclomatic complexity  1 + the decisions of a member: if, ?:, loops, case labels and switch arms, catch, &&, ||, ??, ?., and / or patterns
//   depth of inheritance   the base classes up to System.Object
//   class coupling         the distinct types a member or a type uses, without itself, primitive types and type parameters
//   lines                  non-blank lines of the declaration; executable lines - the lines statements start on
//
// Usage: CodeMetrics --input <projects.json>. The input, written by the plugin from what MSBuild says of every project:
//   { "projects": [ { "name", "file", "sources": [...], "references": [...], "defines": [...], "langVersion", "nullable", "usings": [...],
//                     "allowUnsafe": bool } ] }
// The output (stdout, JSON): { "projects": [ { "name", "file", "error"?, "types": [ { "namespace", "name", "kind", "file", "line", "lines",
//   "inheritance", "coupled": [...], "members": [ { "name", "kind", "file", "line", "complexity", "maintainability", "coupling", "lines",
//   "executable" } ] } ] } ] }
using System.Text.Json;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;

namespace DotNetSupport.CodeMetrics;

public static class Program
{
    public static int Main(string[] args)
    {
        var input = args.SkipWhile(a => a != "--input").Skip(1).FirstOrDefault();
        if (input == null || !File.Exists(input))
        {
            Console.Error.WriteLine("Usage: CodeMetrics --input <projects.json>");
            return 2;
        }
        using var document = JsonDocument.Parse(File.ReadAllText(input));
        using var output = new Utf8JsonWriter(Console.OpenStandardOutput(), new JsonWriterOptions { Indented = false });
        output.WriteStartObject();
        output.WriteStartArray("projects");
        foreach (var project in document.RootElement.GetProperty("projects").EnumerateArray())
        {
            output.WriteStartObject();
            output.WriteString("name", Text(project, "name"));
            output.WriteString("file", Text(project, "file"));
            try
            {
                var types = Analyze(project);
                output.WriteStartArray("types");
                foreach (var type in types) Write(output, type);
                output.WriteEndArray();
            }
            catch (Exception e)
            {
                output.WriteString("error", e.Message);
            }
            output.WriteEndObject();
            output.Flush();
        }
        output.WriteEndArray();
        output.WriteEndObject();
        output.Flush();
        return 0;
    }

    private static string Text(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() ?? "" : "";

    private static IEnumerable<string> Texts(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Array
            ? value.EnumerateArray().Where(v => v.ValueKind == JsonValueKind.String).Select(v => v.GetString() ?? "").Where(s => s.Length > 0)
            : [];

    // --- the compilation ---

    private static List<TypeMetrics> Analyze(JsonElement project)
    {
        var language = LanguageVersion.Latest;
        if (LanguageVersionFacts.TryParse(Text(project, "langVersion"), out var parsed)) language = parsed;
        var parse = new CSharpParseOptions(language, DocumentationMode.None, SourceCodeKind.Regular, Texts(project, "defines"));
        var trees = new List<SyntaxTree>();
        foreach (var path in Texts(project, "sources").Distinct(StringComparer.OrdinalIgnoreCase))
        {
            if (!File.Exists(path)) continue;
            trees.Add(CSharpSyntaxTree.ParseText(File.ReadAllText(path), parse, path));
        }
        // GlobalUsings.g.cs is written by the first build: before it, the usings come from the items
        if (!trees.Any(t => t.FilePath.EndsWith(".GlobalUsings.g.cs", StringComparison.OrdinalIgnoreCase)))
        {
            var usings = string.Concat(Texts(project, "usings").Select(u => $"global using global::{u};\n"));
            if (usings.Length > 0) trees.Add(CSharpSyntaxTree.ParseText(usings, parse, "GlobalUsings.g.cs"));
        }
        var references = Texts(project, "references").Where(File.Exists).Distinct(StringComparer.OrdinalIgnoreCase)
            .Select(path => (MetadataReference)MetadataReference.CreateFromFile(path));
        var nullable = Text(project, "nullable").ToLowerInvariant() switch
        {
            "enable" => NullableContextOptions.Enable, "warnings" => NullableContextOptions.Warnings, "annotations" => NullableContextOptions.Annotations,
            _ => NullableContextOptions.Disable,
        };
        var topLevel = trees.Any(t => t.GetRoot().ChildNodes().OfType<GlobalStatementSyntax>().Any());
        var options = new CSharpCompilationOptions(topLevel ? OutputKind.ConsoleApplication : OutputKind.DynamicallyLinkedLibrary,
            nullableContextOptions: nullable, allowUnsafe: project.TryGetProperty("allowUnsafe", out var u) && u.ValueKind == JsonValueKind.True);
        var compilation = CSharpCompilation.Create(Text(project, "name"), trees, references, options);

        var types = new Dictionary<INamedTypeSymbol, TypeMetrics>(SymbolEqualityComparer.Default);
        foreach (var tree in trees)
        {
            if (IsGenerated(tree.FilePath)) continue;
            var model = compilation.GetSemanticModel(tree);
            var root = tree.GetRoot();
            foreach (var declaration in root.DescendantNodes().OfType<BaseTypeDeclarationSyntax>())
            {
                if (model.GetDeclaredSymbol(declaration) is not INamedTypeSymbol symbol) continue;
                var type = TypeOf(types, symbol, tree, declaration);
                type.Lines += Lines(declaration);
                Couple(model, declaration is TypeDeclarationSyntax t ? NodesOfTypeOnly(t) : [declaration], symbol, type.Coupled);
                if (declaration is TypeDeclarationSyntax typeDeclaration)
                    foreach (var member in typeDeclaration.Members) Member(model, member, symbol, tree, type);
            }
            foreach (var declaration in root.DescendantNodes().OfType<DelegateDeclarationSyntax>())
            {
                if (model.GetDeclaredSymbol(declaration) is not INamedTypeSymbol symbol) continue;
                var type = TypeOf(types, symbol, tree, declaration);
                type.Lines += Lines(declaration);
                Couple(model, [declaration], symbol, type.Coupled);
            }
            var statements = root.ChildNodes().OfType<GlobalStatementSyntax>().ToList();
            if (statements.Count > 0 && compilation.GetEntryPoint(CancellationToken.None)?.ContainingType is { } program)
            {
                var type = TypeOf(types, program, tree, statements[0]);
                var member = Measure(model, statements, statements.Select(s => s.Statement), program, tree, "<top-level statements>", "method", statements[0]);
                type.Members.Add(member);
                type.Lines += member.Lines;
                type.Coupled.UnionWith(member.Coupled);
            }
        }
        return types.Values.OrderBy(t => t.Namespace).ThenBy(t => t.Name).ToList();
    }

    private static bool IsGenerated(string path)
    {
        var normalized = path.Replace('\\', '/');
        return normalized.Contains("/obj/") || normalized.EndsWith("GlobalUsings.g.cs", StringComparison.OrdinalIgnoreCase);
    }

    private static TypeMetrics TypeOf(Dictionary<INamedTypeSymbol, TypeMetrics> types, INamedTypeSymbol symbol, SyntaxTree tree, SyntaxNode at)
    {
        if (types.TryGetValue(symbol, out var known)) return known;
        var type = new TypeMetrics
        {
            Namespace = symbol.ContainingNamespace is { IsGlobalNamespace: false } ns ? ns.ToDisplayString() : "",
            Name = symbol.ToDisplayString(TypeName),
            Kind = symbol.TypeKind switch
            {
                TypeKind.Interface => "interface", TypeKind.Struct => symbol.IsRecord ? "record struct" : "struct", TypeKind.Enum => "enum",
                TypeKind.Delegate => "delegate", _ => symbol.IsRecord ? "record" : "class",
            },
            File = tree.FilePath,
            Line = LineOf(at),
            Inheritance = Depth(symbol),
        };
        types[symbol] = type;
        return type;
    }

    /** The base classes up to System.Object; an interface has none. A base the references do not resolve still counts, and ends the chain. */
    private static int Depth(INamedTypeSymbol type)
    {
        if (type.TypeKind == TypeKind.Interface) return 0;
        var depth = 0;
        for (var current = type.BaseType; current != null; current = current.BaseType)
        {
            depth++;
            if (current.TypeKind == TypeKind.Error) break;
        }
        return depth;
    }

    // --- members ---

    private static void Member(SemanticModel model, MemberDeclarationSyntax member, INamedTypeSymbol type, SyntaxTree tree, TypeMetrics metrics)
    {
        switch (member)
        {
            case BaseMethodDeclarationSyntax method:
                Add(model.GetDeclaredSymbol(method), method, Bodies(method.Body, method.ExpressionBody), "method");
                break;
            case BasePropertyDeclarationSyntax property:
                IEnumerable<SyntaxNode> bodies = property.AccessorList?.Accessors.SelectMany(a => Bodies(a.Body, a.ExpressionBody)) ?? [];
                if (property is PropertyDeclarationSyntax { ExpressionBody: { } arrow }) bodies = bodies.Append(arrow.Expression);
                if (property is IndexerDeclarationSyntax { ExpressionBody: { } indexerArrow }) bodies = bodies.Append(indexerArrow.Expression);
                if (property is PropertyDeclarationSyntax { Initializer: { } initializer }) bodies = bodies.Append(initializer.Value);
                Add(model.GetDeclaredSymbol(property), property, bodies.ToList(), property is EventDeclarationSyntax ? "event" : "property");
                break;
            case FieldDeclarationSyntax or EventFieldDeclarationSyntax:
                // no row of their own (as the fields of VS, they would only add noise); what they use couples the type
                Couple(model, [member], type, metrics.Coupled);
                break;
        }

        void Add(ISymbol? symbol, MemberDeclarationSyntax declaration, IReadOnlyCollection<SyntaxNode> bodies, string kind)
        {
            if (symbol == null) return;
            var measured = Measure(model, [declaration], bodies, type, tree, symbol.ToDisplayString(MemberName), kind, declaration);
            metrics.Members.Add(measured);
            metrics.Coupled.UnionWith(measured.Coupled);
        }
    }

    private static List<SyntaxNode> Bodies(BlockSyntax? body, ArrowExpressionClauseSyntax? arrow) =>
        body != null ? [body] : arrow != null ? [arrow.Expression] : [];

    /** [declarations]: what the lines and the coupling are counted on (the signature included); [bodies]: the code that runs. */
    private static MemberMetrics Measure(SemanticModel model, IReadOnlyCollection<SyntaxNode> declarations, IEnumerable<SyntaxNode> bodies, INamedTypeSymbol type,
        SyntaxTree tree, string name, string kind, SyntaxNode at)
    {
        var code = bodies.ToList();
        var member = new MemberMetrics { Name = name, Kind = kind, File = tree.FilePath, Line = LineOf(at) };
        member.Lines = declarations.Sum(Lines);
        member.Complexity = 1 + code.Sum(Decisions);
        member.Executable = ExecutableLines(code);
        Couple(model, declarations, type, member.Coupled);
        member.Maintainability = Maintainability(code, member.Complexity, member.Executable);
        return member;
    }

    // --- cyclomatic complexity ---

    private static int Decisions(SyntaxNode code) => code.DescendantNodesAndSelf().Sum(node => node switch
    {
        IfStatementSyntax or ConditionalExpressionSyntax or WhileStatementSyntax or DoStatementSyntax or ForStatementSyntax
            or CommonForEachStatementSyntax or CatchClauseSyntax or ConditionalAccessExpressionSyntax => 1,
        CaseSwitchLabelSyntax or CasePatternSwitchLabelSyntax => 1,
        SwitchExpressionArmSyntax arm => arm.Pattern is DiscardPatternSyntax ? 0 : 1,
        BinaryExpressionSyntax binary => binary.Kind() is SyntaxKind.LogicalAndExpression or SyntaxKind.LogicalOrExpression or SyntaxKind.CoalesceExpression ? 1 : 0,
        AssignmentExpressionSyntax assignment => assignment.IsKind(SyntaxKind.CoalesceAssignmentExpression) ? 1 : 0,
        BinaryPatternSyntax => 1,
        _ => 0,
    });

    // --- lines ---

    private static int LineOf(SyntaxNode node) => node.GetLocation().GetLineSpan().StartLinePosition.Line + 1;

    /** Non-blank lines of the declaration, from its first token (its leading comments are not counted) to its last. */
    private static int Lines(SyntaxNode node)
    {
        var text = node.SyntaxTree.GetText();
        var span = node.Span;
        var first = text.Lines.GetLineFromPosition(span.Start).LineNumber;
        var last = text.Lines.GetLineFromPosition(Math.Max(span.Start, span.End - 1)).LineNumber;
        var count = 0;
        for (var line = first; line <= last; line++)
            if (!string.IsNullOrWhiteSpace(text.Lines[line].ToString())) count++;
        return count;
    }

    /** The lines statements start on (blocks are not statements of their own); an expression body is one statement. */
    private static int ExecutableLines(IEnumerable<SyntaxNode> code)
    {
        var lines = new HashSet<(string, int)>();
        foreach (var root in code)
        {
            var nodes = root is ExpressionSyntax ? [root] : root.DescendantNodesAndSelf().Where(n => n is StatementSyntax and not BlockSyntax and not LocalFunctionStatementSyntax);
            foreach (var node in nodes) lines.Add((node.SyntaxTree.FilePath, node.GetLocation().GetLineSpan().StartLinePosition.Line));
            // the expression bodies of lambdas and local functions are statements too
            foreach (var arrow in root.DescendantNodes().OfType<ArrowExpressionClauseSyntax>()) lines.Add((arrow.SyntaxTree.FilePath, arrow.Expression.GetLocation().GetLineSpan().StartLinePosition.Line));
            foreach (var lambda in root.DescendantNodes().OfType<LambdaExpressionSyntax>())
                if (lambda.ExpressionBody is { } body) lines.Add((body.SyntaxTree.FilePath, body.GetLocation().GetLineSpan().StartLinePosition.Line));
        }
        return lines.Count;
    }

    // --- maintainability index ---

    /** Halstead's volume over the tokens of the code: identifiers and literals are operands, keywords and punctuation operators. */
    private static int Maintainability(IReadOnlyCollection<SyntaxNode> code, int complexity, int executable)
    {
        if (code.Count == 0 || executable == 0) return 100;
        var operators = new HashSet<string>();
        var operands = new HashSet<string>();
        var total = 0;
        foreach (var token in code.SelectMany(c => c.DescendantTokens()))
        {
            if (token.IsKind(SyntaxKind.OpenBraceToken) || token.IsKind(SyntaxKind.CloseBraceToken) || token.IsKind(SyntaxKind.SemicolonToken)) continue;
            total++;
            if (token.IsKind(SyntaxKind.IdentifierToken) || token.IsKind(SyntaxKind.NumericLiteralToken) || token.IsKind(SyntaxKind.StringLiteralToken)
                || token.IsKind(SyntaxKind.CharacterLiteralToken) || token.IsKind(SyntaxKind.InterpolatedStringTextToken))
                operands.Add(token.ValueText);
            else operators.Add(token.Text);
        }
        var vocabulary = Math.Max(2, operators.Count + operands.Count);
        var volume = Math.Max(1.0, total * Math.Log2(vocabulary));
        var index = (171 - 5.2 * Math.Log(volume) - 0.23 * complexity - 16.2 * Math.Log(Math.Max(1, executable))) * 100 / 171;
        return (int)Math.Round(Math.Clamp(index, 0, 100));
    }

    // --- class coupling ---

    /** The nodes of a type declaration without its members: the base list, the attributes, the parameters of a primary constructor. */
    private static IEnumerable<SyntaxNode> NodesOfTypeOnly(TypeDeclarationSyntax type) =>
        new SyntaxNode?[] { type.BaseList, type.ParameterList, type.TypeParameterList }.OfType<SyntaxNode>()
            .Concat(type.ConstraintClauses).Concat(type.AttributeLists);

    private static void Couple(SemanticModel model, IEnumerable<SyntaxNode> nodes, INamedTypeSymbol self, HashSet<string> coupled)
    {
        foreach (var root in nodes)
        foreach (var node in root.DescendantNodesAndSelf())
        {
            switch (node)
            {
                case IdentifierNameSyntax or GenericNameSyntax:
                    var symbol = model.GetSymbolInfo(node).Symbol ?? model.GetSymbolInfo(node).CandidateSymbols.FirstOrDefault();
                    switch (symbol)
                    {
                        case ITypeSymbol t: AddType(t, self, coupled); break;
                        case IMethodSymbol m:
                            if (m.MethodKind is MethodKind.ReducedExtension) AddType(m.ReducedFrom?.ContainingType, self, coupled);
                            else AddType(m.ContainingType, self, coupled);
                            AddType(m.ReturnType, self, coupled);
                            break;
                        case IPropertySymbol p: AddType(p.ContainingType, self, coupled); AddType(p.Type, self, coupled); break;
                        case IFieldSymbol f: AddType(f.ContainingType, self, coupled); AddType(f.Type, self, coupled); break;
                        case IEventSymbol e: AddType(e.ContainingType, self, coupled); AddType(e.Type, self, coupled); break;
                        case ILocalSymbol l: AddType(l.Type, self, coupled); break;
                        case IParameterSymbol a: AddType(a.Type, self, coupled); break;
                    }
                    break;
                case ImplicitObjectCreationExpressionSyntax or ObjectCreationExpressionSyntax:
                    AddType(model.GetTypeInfo(node).Type, self, coupled);
                    break;
            }
        }
    }

    private static void AddType(ITypeSymbol? type, INamedTypeSymbol self, HashSet<string> coupled)
    {
        switch (type)
        {
            case null or ITypeParameterSymbol or IDynamicTypeSymbol: return;
            case IArrayTypeSymbol array: AddType(array.ElementType, self, coupled); return;
            case IPointerTypeSymbol pointer: AddType(pointer.PointedAtType, self, coupled); return;
            case INamedTypeSymbol named:
                if (named.IsTupleType) { foreach (var element in named.TupleElements) AddType(element.Type, self, coupled); return; }
                foreach (var argument in named.TypeArguments) AddType(argument, self, coupled);
                var definition = named.OriginalDefinition;
                if (definition.SpecialType != SpecialType.None || definition.IsAnonymousType || definition.TypeKind == TypeKind.Error) return;
                if (SymbolEqualityComparer.Default.Equals(definition, self.OriginalDefinition)) return;
                coupled.Add(definition.ToDisplayString(SymbolDisplayFormat.CSharpErrorMessageFormat));
                return;
        }
    }

    // --- output ---

    private static readonly SymbolDisplayFormat TypeName = new(
        typeQualificationStyle: SymbolDisplayTypeQualificationStyle.NameAndContainingTypes,
        genericsOptions: SymbolDisplayGenericsOptions.IncludeTypeParameters);

    private static readonly SymbolDisplayFormat MemberName = new(
        genericsOptions: SymbolDisplayGenericsOptions.IncludeTypeParameters,
        memberOptions: SymbolDisplayMemberOptions.IncludeParameters | SymbolDisplayMemberOptions.IncludeExplicitInterface,
        parameterOptions: SymbolDisplayParameterOptions.IncludeType | SymbolDisplayParameterOptions.IncludeParamsRefOut,
        miscellaneousOptions: SymbolDisplayMiscellaneousOptions.UseSpecialTypes | SymbolDisplayMiscellaneousOptions.EscapeKeywordIdentifiers);

    private static void Write(Utf8JsonWriter output, TypeMetrics type)
    {
        output.WriteStartObject();
        output.WriteString("namespace", type.Namespace);
        output.WriteString("name", type.Name);
        output.WriteString("kind", type.Kind);
        output.WriteString("file", type.File);
        output.WriteNumber("line", type.Line);
        output.WriteNumber("lines", type.Lines);
        output.WriteNumber("inheritance", type.Inheritance);
        output.WriteStartArray("coupled");
        foreach (var name in type.Coupled.Order(StringComparer.Ordinal)) output.WriteStringValue(name);
        output.WriteEndArray();
        output.WriteStartArray("members");
        foreach (var member in type.Members.OrderBy(m => m.File).ThenBy(m => m.Line))
        {
            output.WriteStartObject();
            output.WriteString("name", member.Name);
            output.WriteString("kind", member.Kind);
            output.WriteString("file", member.File);
            output.WriteNumber("line", member.Line);
            output.WriteNumber("complexity", member.Complexity);
            output.WriteNumber("maintainability", member.Maintainability);
            output.WriteNumber("coupling", member.Coupled.Count);
            output.WriteNumber("lines", member.Lines);
            output.WriteNumber("executable", member.Executable);
            output.WriteEndObject();
        }
        output.WriteEndArray();
        output.WriteEndObject();
    }

    private sealed class TypeMetrics
    {
        public string Namespace = "", Name = "", Kind = "", File = "";
        public int Line, Lines, Inheritance;
        public readonly HashSet<string> Coupled = new(StringComparer.Ordinal);
        public readonly List<MemberMetrics> Members = [];
    }

    private sealed class MemberMetrics
    {
        public string Name = "", Kind = "", File = "";
        public int Line, Lines, Complexity, Maintainability, Executable;
        public readonly HashSet<string> Coupled = new(StringComparer.Ordinal);
    }
}
