// appsettingsSchema: the sections of appsettings*.json a project reads, found in its C# by syntax alone (Roslyn parser, no compilation,
// no references), as a JSON schema (draft-07) for the editor of the plugin (appsettings/AppSettingsSchemaService).
//
//   appsettingsSchema  {files: [path], overlays?: [{path, text}]}
//                      → {schema, sources: [{path, file, line, kind}], sections: [{path, type, how, file, line}], unresolved: [name],
//                         files, parsed, elapsedMs}
//
// `files` are the .cs files of the project and of the projects it references; `overlays` the texts of the documents the IDE has not
// saved yet. What is bound to a section:
//   services.Configure<T>(config.GetSection("X"))          services.AddOptions<T>().Bind(config.GetSection("X"))
//   services.AddOptions<T>().BindConfiguration("X")         config.GetSection("X").Get<T>() / .Bind(obj)
//   config.GetValue<T>("A:B")   config["A:B"]   config.GetConnectionString("Db")   // appsettings: X:Y   (a comment above a class)
// A section name is a literal, a constant, nameof(...), a concatenation of those, or a path "A:B"; GetSection calls chain.
// A type is looked up among the files by name, the way C# would (namespaces, usings, nested types); one it cannot find (a library
// type) is an open object. `sources` say where each key comes from: the property it is bound to (kind "property") or the call that
// binds a section (kind "section"), lines from 1; the path of an item of a list or a dictionary has `*` for the index or the key.

using System.Collections.Concurrent;
using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;

namespace DotNetSupport.Helpers.DotNetHelper;

internal static class AppSettingsSchema
{
    private static readonly CSharpParseOptions ParseOptions = CSharpParseOptions.Default.WithLanguageVersion(LanguageVersion.Preview)
        .WithDocumentationMode(DocumentationMode.Parse);

    /** Parsed files by path, kept while the file on disk is the same: the second request parses only what has changed. */
    private static readonly ConcurrentDictionary<string, (DateTime Written, long Length, SyntaxTree Tree)> Trees = new(StringComparer.OrdinalIgnoreCase);

    public static JsonObject Build(JsonElement? p, CancellationToken token)
    {
        var clock = Stopwatch.StartNew();
        var files = Params.Strings(p, "files") ?? new List<string>();
        var overlays = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (p is { ValueKind: JsonValueKind.Object } o && o.TryGetProperty("overlays", out var list) && list.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in list.EnumerateArray())
            {
                var path = Params.String(item, "path");
                var text = Params.String(item, "text");
                if (path != null && text != null) overlays[Path.GetFullPath(path)] = text;
            }
        }
        var paths = files.Select(Path.GetFullPath).Concat(overlays.Keys).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        var parsed = 0;
        var trees = new SyntaxTree?[paths.Count];
        Parallel.For(0, paths.Count, new ParallelOptions { CancellationToken = token }, i =>
        {
            var path = paths[i];
            if (overlays.TryGetValue(path, out var text))
            {
                trees[i] = CSharpSyntaxTree.ParseText(text, ParseOptions, path, cancellationToken: token);
                Interlocked.Increment(ref parsed);
                return;
            }
            try
            {
                var info = new FileInfo(path);
                if (!info.Exists) return;
                if (Trees.TryGetValue(path, out var cached) && cached.Written == info.LastWriteTimeUtc && cached.Length == info.Length)
                {
                    trees[i] = cached.Tree;
                    return;
                }
                var tree = CSharpSyntaxTree.ParseText(File.ReadAllText(path), ParseOptions, path, cancellationToken: token);
                Trees[path] = (info.LastWriteTimeUtc, info.Length, tree);
                trees[i] = tree;
                Interlocked.Increment(ref parsed);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        });
        var analysis = new Analysis(trees.Where(t => t != null).Select(t => t!).ToList(), token);
        analysis.Run();
        return new JsonObject
        {
            ["schema"] = analysis.Schema,
            ["sources"] = new JsonArray(analysis.Sources.Distinct().Select(s => (JsonNode)new JsonObject
                { ["path"] = s.Path, ["file"] = s.File, ["line"] = s.Line, ["kind"] = s.Kind }).ToArray()),
            ["sections"] = new JsonArray(analysis.Sections.Select(s => (JsonNode)new JsonObject
                { ["path"] = s.Path, ["type"] = s.Type, ["how"] = s.How, ["file"] = s.File, ["line"] = s.Line }).ToArray()),
            ["unresolved"] = new JsonArray(analysis.Unresolved.Order(StringComparer.Ordinal).Select(u => (JsonNode)JsonValue.Create(u)!).ToArray()),
            ["files"] = paths.Count,
            ["parsed"] = parsed,
            ["elapsedMs"] = clock.ElapsedMilliseconds,
        };
    }

    internal sealed record Source(string Path, string File, int Line, string Kind);

    internal sealed record Section(string Path, string? Type, string How, string File, int Line);

    /** The kind of a type as the configuration binder sees it. */
    private enum Shape { Unknown, Object, Collection, Dictionary }

    private sealed class Analysis(List<SyntaxTree> trees, CancellationToken token)
    {
        public readonly JsonObject Schema = new() { ["$schema"] = "http://json-schema.org/draft-07/schema#", ["type"] = "object", ["properties"] = new JsonObject() };
        public readonly List<Source> Sources = new();
        public readonly List<Section> Sections = new();
        public readonly HashSet<string> Unresolved = new(StringComparer.Ordinal);

        private const int MaxDepth = 12;
        private readonly Dictionary<string, List<BaseTypeDeclarationSyntax>> _types = new(StringComparer.Ordinal);
        private readonly Dictionary<string, List<string>> _bySimpleName = new(StringComparer.Ordinal);
        private readonly Dictionary<string, ExpressionSyntax> _constants = new(StringComparer.Ordinal);
        private readonly List<UsingDirectiveSyntax> _globalUsings = new();

        public void Run()
        {
            foreach (var tree in trees) Index(tree.GetRoot(token));
            foreach (var tree in trees)
            {
                token.ThrowIfCancellationRequested();
                var root = tree.GetRoot(token);
                foreach (var node in root.DescendantNodes())
                {
                    switch (node)
                    {
                        case InvocationExpressionSyntax invocation: Invocation(invocation); break;
                        case ElementAccessExpressionSyntax access: Indexer(access); break;
                        case TypeDeclarationSyntax type: Marker(type); break;
                    }
                }
            }
        }

        // ---- the index of the declarations

        private void Index(SyntaxNode root)
        {
            foreach (var u in root.DescendantNodes(n => n is CompilationUnitSyntax or BaseNamespaceDeclarationSyntax).OfType<UsingDirectiveSyntax>())
                if (u.GlobalKeyword.IsKind(SyntaxKind.GlobalKeyword)) _globalUsings.Add(u);
            foreach (var declaration in root.DescendantNodes(n => n is CompilationUnitSyntax or BaseNamespaceDeclarationSyntax or TypeDeclarationSyntax)
                         .OfType<BaseTypeDeclarationSyntax>())
            {
                var name = FullName(declaration);
                if (!_types.TryGetValue(name, out var list)) _types[name] = list = new List<BaseTypeDeclarationSyntax>();
                list.Add(declaration);
                var simple = declaration.Identifier.Text;
                if (!_bySimpleName.TryGetValue(simple, out var names)) _bySimpleName[simple] = names = new List<string>();
                if (!names.Contains(name)) names.Add(name);
                if (declaration is not TypeDeclarationSyntax type) continue;
                foreach (var field in type.Members.OfType<FieldDeclarationSyntax>())
                {
                    var isConstant = field.Modifiers.Any(SyntaxKind.ConstKeyword) ||
                                     field.Modifiers.Any(SyntaxKind.StaticKeyword) && field.Modifiers.Any(SyntaxKind.ReadOnlyKeyword);
                    if (!isConstant) continue;
                    foreach (var variable in field.Declaration.Variables)
                        if (variable.Initializer != null) _constants[name + "." + variable.Identifier.Text] = variable.Initializer.Value;
                }
                foreach (var property in type.Members.OfType<PropertyDeclarationSyntax>())
                {
                    // public static string Section => "X";  /  { get; } = "X";
                    if (!property.Modifiers.Any(SyntaxKind.StaticKeyword)) continue;
                    var value = property.ExpressionBody?.Expression ?? property.Initializer?.Value;
                    if (value != null) _constants[name + "." + property.Identifier.Text] = value;
                }
            }
        }

        private static string FullName(BaseTypeDeclarationSyntax declaration)
        {
            var parts = new List<string> { declaration.Identifier.Text };
            for (var parent = declaration.Parent; parent != null; parent = parent.Parent)
            {
                switch (parent)
                {
                    case BaseTypeDeclarationSyntax type: parts.Add(type.Identifier.Text); break;
                    case BaseNamespaceDeclarationSyntax ns: parts.Add(ns.Name.ToString()); break;
                }
            }
            parts.Reverse();
            return string.Join(".", parts);
        }

        // ---- what binds a section

        private void Invocation(InvocationExpressionSyntax invocation)
        {
            if (invocation.Expression is not MemberAccessExpressionSyntax access) return;
            var name = access.Name.Identifier.Text;
            var typeArguments = (access.Name as GenericNameSyntax)?.TypeArgumentList.Arguments;
            var arguments = invocation.ArgumentList.Arguments;
            switch (name)
            {
                case "Configure" when typeArguments is { Count: 1 }:
                {
                    // services.Configure<T>(section) and services.Configure<T>("name", section); a lambda is not a section
                    foreach (var argument in arguments.Reverse())
                    {
                        var path = SectionPath(argument.Expression);
                        if (path == null) continue;
                        Bind(path, typeArguments.Value[0], invocation, "Configure");
                        break;
                    }
                    break;
                }
                case "Bind" or "BindConfiguration":
                {
                    var options = OptionsType(access.Expression);
                    if (options != null)
                    {
                        var path = name == "BindConfiguration"
                            ? arguments.Count > 0 ? Text(arguments[0].Expression) : null
                            : arguments.Count > 0 ? SectionPath(arguments[0].Expression) : null;
                        if (path != null) Bind(path, options, invocation, name);
                        break;
                    }
                    if (name != "Bind" || arguments.Count == 0) break;
                    // section.Bind(instance) / config.Bind("Key", instance)
                    var receiver = SectionPath(access.Expression);
                    if (receiver == null) break;
                    var target = arguments[^1].Expression;
                    if (arguments.Count == 2)
                    {
                        var key = Text(arguments[0].Expression);
                        if (key == null) break;
                        receiver = Join(receiver, key);
                    }
                    var type = InstanceType(target);
                    if (type != null) Bind(receiver, type, invocation, "Bind");
                    else Unresolved.Add(target.ToString());
                    break;
                }
                case "Get" when typeArguments is { Count: 1 }:
                {
                    var path = SectionPath(access.Expression);
                    if (path != null) Bind(path, typeArguments.Value[0], invocation, "Get");
                    break;
                }
                case "GetValue" when typeArguments is { Count: 1 } && arguments.Count >= 1:
                {
                    var receiver = SectionPath(access.Expression);
                    var key = Text(arguments[0].Expression);
                    if (receiver == null || key == null) break;
                    var schema = TypeSchema(typeArguments.Value[0], invocation, Join(receiver, key), new Stack<string>(), 0);
                    if (arguments.Count >= 2 && Default(arguments[1].Expression, schema) is { } fallback) schema["default"] = fallback;
                    Place(Join(receiver, key), schema, invocation, "GetValue", typeArguments.Value[0].ToString());
                    break;
                }
                case "GetConnectionString" when arguments.Count == 1:
                {
                    var key = Text(arguments[0].Expression);
                    if (key == null || SectionPath(access.Expression) == null) break;
                    Place(Join("ConnectionStrings", key), new JsonObject { ["type"] = "string" }, invocation, "GetConnectionString", "string");
                    break;
                }
            }
        }

        private void Indexer(ElementAccessExpressionSyntax access)
        {
            if (access.ArgumentList.Arguments.Count != 1) return;
            var receiver = SectionPath(access.Expression);
            if (receiver == null) return;
            var key = Text(access.ArgumentList.Arguments[0].Expression);
            if (key == null) return;
            Place(Join(receiver, key), new JsonObject { ["type"] = new JsonArray("string", "number", "boolean", "null") }, access, "indexer", "string");
        }

        private static readonly Regex MarkerPattern = new(@"^//\s*appsettings\s*:\s*(\S+)", RegexOptions.IgnoreCase);

        /** `// appsettings: Section:Sub` right above a class: what the code binds in a way the search above does not see. */
        private void Marker(TypeDeclarationSyntax type)
        {
            foreach (var trivia in type.GetLeadingTrivia())
            {
                if (!trivia.IsKind(SyntaxKind.SingleLineCommentTrivia)) continue;
                var match = MarkerPattern.Match(trivia.ToString().Trim());
                if (!match.Success) continue;
                var name = FullName(type);
                var path = match.Groups[1].Value.Trim(':');
                Place(path, ObjectSchema(name, path, new Stack<string>(), 0), type, "comment", name);
            }
        }

        /** T of `AddOptions<T>()` somewhere down the chain of calls the receiver is: `.AddOptions<T>().ValidateOnStart().Bind(...)`. */
        private static TypeSyntax? OptionsType(ExpressionSyntax receiver)
        {
            for (var e = receiver; e is InvocationExpressionSyntax { Expression: MemberAccessExpressionSyntax access };)
            {
                if (access.Name is GenericNameSyntax { TypeArgumentList.Arguments.Count: 1 } generic &&
                    generic.Identifier.Text is "AddOptions" or "AddOptionsWithValidateOnStart")
                    return generic.TypeArgumentList.Arguments[0];
                e = access.Expression;
            }
            return null;
        }

        /** The type of what `Bind(target)` fills: `new T()`, or the declared type of a local, a field, a property or a parameter. */
        private TypeSyntax? InstanceType(ExpressionSyntax target)
        {
            switch (target)
            {
                case ObjectCreationExpressionSyntax creation: return creation.Type;
                case ParenthesizedExpressionSyntax parenthesized: return InstanceType(parenthesized.Expression);
                case IdentifierNameSyntax identifier:
                {
                    var declared = Declaration(identifier);
                    switch (declared)
                    {
                        case VariableDeclaratorSyntax { Parent: VariableDeclarationSyntax declaration } variable:
                            if (!declaration.Type.IsVar) return declaration.Type;
                            return variable.Initializer?.Value is ObjectCreationExpressionSyntax creation ? creation.Type : null;
                        case ParameterSyntax parameter: return parameter.Type;
                        case PropertyDeclarationSyntax property: return property.Type;
                    }
                    return null;
                }
                case MemberAccessExpressionSyntax { Expression: ThisExpressionSyntax } member: return InstanceType(member.Name);
            }
            return null;
        }

        /** What a name stands for, looked for in the method, then in the type, the way a reader finds it: the innermost first. */
        private static SyntaxNode? Declaration(IdentifierNameSyntax identifier)
        {
            var name = identifier.Identifier.Text;
            for (SyntaxNode? scope = identifier.Parent; scope != null; scope = scope.Parent)
            {
                switch (scope)
                {
                    case BlockSyntax or CompilationUnitSyntax or SwitchSectionSyntax:
                    {
                        var statements = scope switch
                        {
                            BlockSyntax block => block.Statements.AsEnumerable(),
                            SwitchSectionSyntax section => section.Statements,
                            CompilationUnitSyntax unit => unit.Members.OfType<GlobalStatementSyntax>().Select(g => g.Statement),
                            _ => Enumerable.Empty<StatementSyntax>(),
                        };
                        foreach (var statement in statements)
                        {
                            if (statement.SpanStart > identifier.SpanStart) break;
                            if (statement is LocalDeclarationStatementSyntax local)
                                foreach (var variable in local.Declaration.Variables)
                                    if (variable.Identifier.Text == name) return variable;
                        }
                        break;
                    }
                    case BaseMethodDeclarationSyntax method:
                        if (method.ParameterList.Parameters.FirstOrDefault(p => p.Identifier.Text == name) is { } parameter) return parameter;
                        break;
                    case LocalFunctionStatementSyntax function:
                        if (function.ParameterList.Parameters.FirstOrDefault(p => p.Identifier.Text == name) is { } localParameter) return localParameter;
                        break;
                    case ParenthesizedLambdaExpressionSyntax lambda:
                        if (lambda.ParameterList.Parameters.FirstOrDefault(p => p.Identifier.Text == name) is { } lambdaParameter) return lambdaParameter;
                        break;
                    case SimpleLambdaExpressionSyntax simple:
                        if (simple.Parameter.Identifier.Text == name) return simple.Parameter;
                        break;
                    case TypeDeclarationSyntax type:
                        foreach (var member in type.Members)
                        {
                            switch (member)
                            {
                                case FieldDeclarationSyntax field:
                                    foreach (var variable in field.Declaration.Variables)
                                        if (variable.Identifier.Text == name) return variable;
                                    break;
                                case PropertyDeclarationSyntax property when property.Identifier.Text == name: return property;
                            }
                        }
                        if (type.ParameterList?.Parameters.FirstOrDefault(p => p.Identifier.Text == name) is { } primary) return primary;
                        return null;
                }
            }
            return null;
        }

        // ---- section paths

        /**
         * The configuration path an expression stands for: "" for the root of the configuration, "A:B" for `GetSection("A:B")`; null for
         * what does not look like configuration at all. Without types, a receiver counts as configuration by its name (`config`,
         * `_configuration`, `builder.Configuration`, `section`) or by being a GetSection call, or a local made of one.
         */
        private string? SectionPath(ExpressionSyntax expression, int depth = 0)
        {
            if (depth > 8) return null;
            switch (expression)
            {
                case ParenthesizedExpressionSyntax parenthesized: return SectionPath(parenthesized.Expression, depth + 1);
                case PostfixUnaryExpressionSyntax { RawKind: (int)SyntaxKind.SuppressNullableWarningExpression } bang: return SectionPath(bang.Operand, depth + 1);
                case InvocationExpressionSyntax { Expression: MemberAccessExpressionSyntax access } invocation
                    when access.Name.Identifier.Text is "GetSection" or "GetRequiredSection" && invocation.ArgumentList.Arguments.Count == 1:
                {
                    var key = Text(invocation.ArgumentList.Arguments[0].Expression);
                    if (key == null) return null;
                    return Join(SectionPath(access.Expression, depth + 1) ?? "", key);
                }
                case IdentifierNameSyntax identifier:
                {
                    var declared = Declaration(identifier);
                    switch (declared)
                    {
                        case VariableDeclaratorSyntax { Initializer.Value: var value } when IsSection(value):
                            return SectionPath(value, depth + 1);
                        case ParameterSyntax { Type: { } type } when IsSectionType(type):
                            return null; // a section handed in from elsewhere: which one is not known here
                    }
                    return LooksLikeConfiguration(identifier.Identifier.Text) ? "" : null;
                }
                case MemberAccessExpressionSyntax member:
                    return LooksLikeConfiguration(member.Name.Identifier.Text) ? "" : null;
            }
            return null;
        }

        private bool IsSection(ExpressionSyntax expression) => expression switch
        {
            InvocationExpressionSyntax { Expression: MemberAccessExpressionSyntax access } => access.Name.Identifier.Text is "GetSection" or "GetRequiredSection",
            ParenthesizedExpressionSyntax parenthesized => IsSection(parenthesized.Expression),
            PostfixUnaryExpressionSyntax bang => IsSection(bang.Operand),
            IdentifierNameSyntax or MemberAccessExpressionSyntax => SectionPath(expression) != null,
            _ => false,
        };

        private static bool IsSectionType(TypeSyntax type) => type.ToString().TrimEnd('?').EndsWith("IConfigurationSection", StringComparison.Ordinal);

        private static bool LooksLikeConfiguration(string name) =>
            name.Contains("config", StringComparison.OrdinalIgnoreCase) || name.Contains("section", StringComparison.OrdinalIgnoreCase) ||
            name.Equals("cfg", StringComparison.OrdinalIgnoreCase) || name.Equals("settings", StringComparison.OrdinalIgnoreCase);

        private static string Join(string prefix, string key) =>
            prefix.Length == 0 ? key.Trim(':') : key.Length == 0 ? prefix : prefix.TrimEnd(':') + ":" + key.Trim(':');

        /** The string an expression always is: a literal, a constant, nameof(...), `ConfigurationPath.Combine(...)` or a concatenation. */
        private string? Text(ExpressionSyntax expression, int depth = 0)
        {
            if (depth > 8) return null;
            switch (expression)
            {
                case LiteralExpressionSyntax literal when literal.IsKind(SyntaxKind.StringLiteralExpression): return literal.Token.ValueText;
                case InterpolatedStringExpressionSyntax interpolated when interpolated.Contents.All(c => c is InterpolatedStringTextSyntax):
                    return string.Concat(interpolated.Contents.Cast<InterpolatedStringTextSyntax>().Select(c => c.TextToken.ValueText));
                case InterpolatedStringExpressionSyntax interpolated:
                {
                    var builder = new StringBuilder();
                    foreach (var content in interpolated.Contents)
                    {
                        var part = content switch
                        {
                            InterpolatedStringTextSyntax text => text.TextToken.ValueText,
                            InterpolationSyntax hole => Text(hole.Expression, depth + 1),
                            _ => null,
                        };
                        if (part == null) return null;
                        builder.Append(part);
                    }
                    return builder.ToString();
                }
                case BinaryExpressionSyntax binary when binary.IsKind(SyntaxKind.AddExpression):
                    return Text(binary.Left, depth + 1) is { } left && Text(binary.Right, depth + 1) is { } right ? left + right : null;
                case ParenthesizedExpressionSyntax parenthesized: return Text(parenthesized.Expression, depth + 1);
                case InvocationExpressionSyntax { Expression: IdentifierNameSyntax { Identifier.Text: "nameof" } } nameOf when nameOf.ArgumentList.Arguments.Count == 1:
                    return nameOf.ArgumentList.Arguments[0].Expression switch
                    {
                        MemberAccessExpressionSyntax member => member.Name.Identifier.Text,
                        SimpleNameSyntax simple => simple.Identifier.Text,
                        var other => other.ToString(),
                    };
                case InvocationExpressionSyntax { Expression: MemberAccessExpressionSyntax { Name.Identifier.Text: "Combine" } combine } invocation
                    when combine.Expression.ToString().EndsWith("ConfigurationPath", StringComparison.Ordinal):
                {
                    var parts = invocation.ArgumentList.Arguments.Select(a => Text(a.Expression, depth + 1)).ToList();
                    return parts.Any(p => p == null) ? null : string.Join(":", parts);
                }
                case IdentifierNameSyntax identifier:
                {
                    switch (Declaration(identifier))
                    {
                        case VariableDeclaratorSyntax { Initializer.Value: var value }: return Text(value, depth + 1);
                        case PropertyDeclarationSyntax property when (property.ExpressionBody?.Expression ?? property.Initializer?.Value) is { } value:
                            return Text(value, depth + 1);
                    }
                    // a constant of a type around, or of its base
                    for (var type = identifier.FirstAncestorOrSelf<BaseTypeDeclarationSyntax>(); type != null; type = type.Parent?.FirstAncestorOrSelf<BaseTypeDeclarationSyntax>())
                        if (_constants.TryGetValue(FullName(type) + "." + identifier.Identifier.Text, out var constant)) return Text(constant, depth + 1);
                    return null;
                }
                case MemberAccessExpressionSyntax member:
                {
                    var owner = member.Expression.ToString();
                    foreach (var typeName in Candidates(owner, member))
                        if (_constants.TryGetValue(typeName + "." + member.Name.Identifier.Text, out var constant)) return Text(constant, depth + 1);
                    return null;
                }
            }
            return null;
        }

        // ---- types

        /** Full names a type name written at [context] can mean, the most likely first: nested, the namespaces around, the usings, anywhere. */
        private IEnumerable<string> Candidates(string written, SyntaxNode context)
        {
            var name = written.StartsWith("global::", StringComparison.Ordinal) ? written[8..] : written;
            var seen = new HashSet<string>(StringComparer.Ordinal);
            IEnumerable<string> All()
            {
                for (var type = context.FirstAncestorOrSelf<BaseTypeDeclarationSyntax>(); type != null; type = type.Parent?.FirstAncestorOrSelf<BaseTypeDeclarationSyntax>())
                    yield return FullName(type) + "." + name;
                var namespaces = context.Ancestors().OfType<BaseNamespaceDeclarationSyntax>().Select(n => n.Name.ToString()).Reverse().ToList();
                for (var i = namespaces.Count; i > 0; i--) yield return string.Join(".", namespaces.Take(i)) + "." + name;
                yield return name;
                var usings = context.Ancestors().SelectMany(a => a switch
                {
                    CompilationUnitSyntax unit => unit.Usings.AsEnumerable(),
                    BaseNamespaceDeclarationSyntax ns => ns.Usings,
                    _ => Enumerable.Empty<UsingDirectiveSyntax>(),
                }).Concat(_globalUsings);
                var first = name.Split('.')[0];
                foreach (var u in usings)
                {
                    if (u.StaticKeyword.IsKind(SyntaxKind.StaticKeyword) || u.Name == null) continue;
                    if (u.Alias != null)
                    {
                        if (u.Alias.Name.Identifier.Text == first) yield return u.Name.ToString() + name[first.Length..];
                        continue;
                    }
                    yield return u.Name + "." + name;
                }
                if (_bySimpleName.TryGetValue(name.Split('.')[^1], out var anywhere))
                    foreach (var full in anywhere) if (full.EndsWith("." + name, StringComparison.Ordinal) || full == name) yield return full;
            }
            foreach (var candidate in All()) if (seen.Add(candidate)) yield return candidate;
        }

        private string? ResolveType(string written, SyntaxNode context) => Candidates(written, context).FirstOrDefault(_types.ContainsKey);

        private void Bind(string path, TypeSyntax type, SyntaxNode at, string how)
        {
            var schema = TypeSchema(type, at, path, new Stack<string>(), 0);
            Place(path, schema, at, how, type.ToString());
        }

        /** Puts [schema] at [path] of the root, making the objects on the way; what is there already is merged with it. */
        private void Place(string path, JsonObject schema, SyntaxNode at, string how, string? type)
        {
            var line = Line(at);
            var file = at.SyntaxTree.FilePath;
            Sections.Add(new Section(path, type, how, file, line));
            if (path.Length > 0) Sources.Add(new Source(path, file, line, "section"));
            var target = Schema;
            var segments = path.Split(':', StringSplitOptions.RemoveEmptyEntries);
            if (segments.Length == 0)
            {
                // the root takes keys from everywhere: a class bound to it does not close it
                schema.Remove("x-dotnet-type");
                Merge(Schema, schema);
                return;
            }
            for (var i = 0; i < segments.Length - 1; i++)
            {
                var properties = Properties(target);
                if (properties[segments[i]] is not JsonObject next) properties[segments[i]] = next = new JsonObject { ["type"] = "object" };
                target = next;
            }
            var last = Properties(target);
            if (last[segments[^1]] is JsonObject existing) Merge(existing, schema);
            else last[segments[^1]] = schema;
        }

        private static JsonObject Properties(JsonObject schema)
        {
            if (schema["properties"] is JsonObject properties) return properties;
            schema["type"] ??= "object";
            return (JsonObject)(schema["properties"] = new JsonObject());
        }

        /** [from] into [into]: properties are joined, a key the target lacks is taken over; an object stays closed when either side was. */
        private static void Merge(JsonObject into, JsonObject from)
        {
            foreach (var (key, value) in from.ToList())
            {
                if (value == null) continue;
                if (key == "properties" && value is JsonObject properties)
                {
                    var target = Properties(into);
                    foreach (var (name, property) in properties.ToList())
                    {
                        if (property is not JsonObject propertySchema) continue;
                        if (target[name] is JsonObject existing) Merge(existing, propertySchema);
                        else target[name] = propertySchema.DeepClone();
                    }
                }
                else if (into[key] == null) into[key] = value.DeepClone();
            }
        }

        private static int Line(SyntaxNode node) => node.GetLocation().GetLineSpan().StartLinePosition.Line + 1;

        private static readonly string[] Collections =
        [
            "List", "IList", "ICollection", "IEnumerable", "IReadOnlyList", "IReadOnlyCollection", "HashSet", "ISet", "IReadOnlySet",
            "Collection", "ReadOnlyCollection", "ObservableCollection", "SortedSet", "LinkedList", "Queue", "Stack", "ImmutableArray",
            "ImmutableList", "ImmutableHashSet", "IImmutableList", "ConcurrentBag",
        ];

        private static readonly string[] Dictionaries =
        [
            "Dictionary", "IDictionary", "IReadOnlyDictionary", "SortedDictionary", "ConcurrentDictionary", "ImmutableDictionary",
            "IImmutableDictionary", "SortedList", "FrozenDictionary",
        ];

        private const string IntegerPattern = @"^\s*[-+]?\d+\s*$";
        private const string NumberPattern = @"^\s*[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?\s*$";
        private const string BooleanPattern = "^\\s*([Tt][Rr][Uu][Ee]|[Ff][Aa][Ll][Ss][Ee])\\s*$";
        private const string GuidPattern = @"^[{(]?[0-9A-Fa-f]{8}-?[0-9A-Fa-f]{4}-?[0-9A-Fa-f]{4}-?[0-9A-Fa-f]{4}-?[0-9A-Fa-f]{12}[)}]?$";
        private const string TimeSpanPattern = @"^\s*-?(\d+|(\d+\.)?\d{1,2}:\d{1,2}(:\d{1,2}(\.\d{1,7})?)?)\s*$";

        /** A value the binder takes from a JSON value or from a string ("5", "true"): both are fine, a string that is not a number is not. */
        private static JsonObject Scalar(string jsonType, string? pattern, string? description = null)
        {
            var schema = new JsonObject { ["type"] = pattern == null ? jsonType : new JsonArray(jsonType, "string") };
            if (pattern != null) schema["pattern"] = pattern;
            if (description != null) schema["description"] = description;
            return schema;
        }

        private static JsonObject? Builtin(string name) => name switch
        {
            "string" or "String" or "char" or "Char" => new JsonObject { ["type"] = "string" },
            "bool" or "Boolean" => Scalar("boolean", BooleanPattern),
            "int" or "long" or "short" or "byte" or "sbyte" or "uint" or "ulong" or "ushort" or "nint" or "nuint" or "Int32" or "Int64" or "Int16" or "Byte"
                or "SByte" or "UInt32" or "UInt64" or "UInt16" or "Int128" or "UInt128" or "BigInteger" => Scalar("integer", IntegerPattern),
            "float" or "double" or "decimal" or "Single" or "Double" or "Decimal" or "Half" => Scalar("number", NumberPattern),
            "Guid" => new JsonObject { ["type"] = "string", ["pattern"] = GuidPattern },
            "Uri" => new JsonObject { ["type"] = "string", ["description"] = "URI" },
            "DateTime" or "DateTimeOffset" or "DateOnly" or "TimeOnly" => new JsonObject { ["type"] = "string", ["description"] = name },
            "TimeSpan" => new JsonObject { ["type"] = "string", ["pattern"] = TimeSpanPattern, ["description"] = "TimeSpan: [d.]hh:mm[:ss[.fffffff]]" },
            "CultureInfo" or "Version" or "Type" or "Encoding" or "IPAddress" => new JsonObject { ["type"] = "string" },
            "object" or "Object" or "dynamic" or "IConfiguration" or "IConfigurationSection" or "JsonElement" or "JsonNode" or "JsonObject" => new JsonObject(),
            _ => null,
        };

        private static (string Name, IReadOnlyList<TypeSyntax> Arguments) Parts(TypeSyntax type) => type switch
        {
            GenericNameSyntax generic => (generic.Identifier.Text, generic.TypeArgumentList.Arguments),
            QualifiedNameSyntax { Right: GenericNameSyntax generic } q => (q.Left + "." + generic.Identifier.Text, generic.TypeArgumentList.Arguments),
            AliasQualifiedNameSyntax alias => Parts(alias.Name),
            _ => (type.ToString(), Array.Empty<TypeSyntax>()),
        };

        private JsonObject TypeSchema(TypeSyntax type, SyntaxNode context, string path, Stack<string> stack, int depth)
        {
            switch (type)
            {
                case NullableTypeSyntax nullable: return Nullable(TypeSchema(nullable.ElementType, context, path, stack, depth));
                case ArrayTypeSyntax array: return new JsonObject { ["type"] = "array", ["items"] = TypeSchema(array.ElementType, context, Join(path, "*"), stack, depth + 1) };
                case PredefinedTypeSyntax predefined: return Builtin(predefined.Keyword.Text) ?? new JsonObject();
                case TupleTypeSyntax: return new JsonObject();
            }
            var (written, arguments) = Parts(type);
            var simple = written.Split('.')[^1];
            if (arguments.Count == 1 && simple == "Nullable") return Nullable(TypeSchema(arguments[0], context, path, stack, depth));
            if (arguments.Count == 1 && Collections.Contains(simple))
                return new JsonObject { ["type"] = "array", ["items"] = TypeSchema(arguments[0], context, Join(path, "*"), stack, depth + 1) };
            if (arguments.Count == 2 && Dictionaries.Contains(simple))
                return new JsonObject { ["type"] = "object", ["additionalProperties"] = TypeSchema(arguments[1], context, Join(path, "*"), stack, depth + 1) };
            var resolved = ResolveType(written, context);
            if (resolved == null)
            {
                if (Builtin(simple) is { } builtin) return builtin;
                if (IsTypeParameter(simple, context)) return new JsonObject();
                Unresolved.Add(written);
                return new JsonObject { ["description"] = $"{written}: a type that is not in the sources" };
            }
            return ObjectSchema(resolved, path, stack, depth);
        }

        private static bool IsTypeParameter(string name, SyntaxNode context) =>
            context.AncestorsAndSelf().Any(a => a switch
            {
                TypeDeclarationSyntax t => t.TypeParameterList?.Parameters.Any(p => p.Identifier.Text == name) == true,
                MethodDeclarationSyntax m => m.TypeParameterList?.Parameters.Any(p => p.Identifier.Text == name) == true,
                _ => false,
            });

        private static JsonObject Nullable(JsonObject schema)
        {
            switch (schema["type"])
            {
                case JsonValue value when value.TryGetValue<string>(out var single): schema["type"] = new JsonArray(single, "null"); break;
                case JsonArray types when !types.Any(t => t?.GetValue<string>() == "null"): types.Add("null"); break;
            }
            if (schema["enum"] is JsonArray values && !values.Any(v => v == null)) values.Add(null);
            return schema;
        }

        /** The schema of a type of the sources: an enum, or an object of its properties (and of its base classes). */
        private JsonObject ObjectSchema(string fullName, string path, Stack<string> stack, int depth)
        {
            var declarations = _types[fullName];
            if (declarations[0] is EnumDeclarationSyntax enumeration) return EnumSchema(enumeration);
            if (stack.Contains(fullName) || depth > MaxDepth)
                return new JsonObject { ["type"] = "object", ["description"] = $"{fullName.Split('.')[^1]} (again, as above)" };
            stack.Push(fullName);
            try
            {
                var schema = new JsonObject { ["type"] = "object" };
                var properties = new JsonObject();
                var description = declarations.Select(Summary).FirstOrDefault(s => s != null);
                if (description != null) schema["description"] = description;
                AddProperties(fullName, properties, path, stack, depth, new HashSet<string>(StringComparer.Ordinal));
                schema["properties"] = properties;
                // the type is known to the last property: a key it does not have is told apart by the plugin (a weak warning, not a schema error)
                schema["x-dotnet-type"] = fullName;
                return schema;
            }
            finally
            {
                stack.Pop();
            }
        }

        private void AddProperties(string fullName, JsonObject properties, string path, Stack<string> stack, int depth, HashSet<string> visited)
        {
            if (!visited.Add(fullName) || !_types.TryGetValue(fullName, out var declarations)) return;
            foreach (var declaration in declarations.OfType<TypeDeclarationSyntax>())
            {
                // a record's positional parameters are bound as properties (the binder calls the constructor)
                if (declaration is RecordDeclarationSyntax { ParameterList: { } parameters })
                {
                    foreach (var parameter in parameters.Parameters)
                    {
                        if (parameter.Type == null) continue;
                        var key = parameter.Identifier.Text;
                        if (properties.ContainsKey(key)) continue;
                        var schema = TypeSchema(parameter.Type, parameter, Join(path, key), stack, depth + 1);
                        if (parameter.Default != null && Default(parameter.Default.Value, schema) is { } value) schema["default"] = value;
                        properties[key] = schema;
                        Sources.Add(new Source(Join(path, key), parameter.SyntaxTree.FilePath, Line(parameter), "property"));
                    }
                }
                var isInterface = declaration is InterfaceDeclarationSyntax;
                foreach (var property in declaration.Members.OfType<PropertyDeclarationSyntax>())
                {
                    if (!Bindable(property, isInterface)) continue;
                    var key = KeyName(property) ?? property.Identifier.Text;
                    if (properties.ContainsKey(key)) continue;
                    var schema = TypeSchema(property.Type, property, Join(path, key), stack, depth + 1);
                    if (Summary(property) is { } summary) schema["description"] = schema["description"] is JsonValue own ? summary + "\n" + own : summary;
                    if (property.Initializer != null && Default(property.Initializer.Value, schema) is { } value) schema["default"] = value;
                    properties[key] = schema;
                    Sources.Add(new Source(Join(path, key), property.SyntaxTree.FilePath, property.Identifier.GetLocation().GetLineSpan().StartLinePosition.Line + 1, "property"));
                }
            }
            // then the base class: its properties are bound too, the ones of the derived class win
            foreach (var declaration in declarations.OfType<TypeDeclarationSyntax>())
            {
                var baseType = declaration.BaseList?.Types.FirstOrDefault()?.Type;
                if (baseType == null) continue;
                var (written, _) = Parts(baseType);
                var resolved = ResolveType(written, declaration);
                if (resolved != null && _types[resolved][0] is ClassDeclarationSyntax or RecordDeclarationSyntax)
                    AddProperties(resolved, properties, path, stack, depth, visited);
            }
        }

        /**
         * What the binder writes: a public instance property with a setter or `init` that is not private; a get-only one it fills when it
         * starts with a value (`= new()`, `= []`) and is a collection or an object.
         */
        private static bool Bindable(PropertyDeclarationSyntax property, bool isInterface)
        {
            if (property.Modifiers.Any(SyntaxKind.StaticKeyword)) return false;
            if (!isInterface && !property.Modifiers.Any(SyntaxKind.PublicKeyword)) return false;
            if (property.ExplicitInterfaceSpecifier != null) return false;
            var accessors = property.AccessorList?.Accessors;
            var setter = accessors?.FirstOrDefault(a => a.IsKind(SyntaxKind.SetAccessorDeclaration) || a.IsKind(SyntaxKind.InitAccessorDeclaration));
            if (setter != null) return !setter.Modifiers.Any(m => m.IsKind(SyntaxKind.PrivateKeyword) || m.IsKind(SyntaxKind.ProtectedKeyword));
            return property.Initializer?.Value is BaseObjectCreationExpressionSyntax or CollectionExpressionSyntax or ArrayCreationExpressionSyntax;
        }

        /** `[ConfigurationKeyName("other")]` renames the key. */
        private string? KeyName(PropertyDeclarationSyntax property) =>
            property.AttributeLists.SelectMany(l => l.Attributes)
                .Where(a => a.Name.ToString().Split('.')[^1] is "ConfigurationKeyName" or "ConfigurationKeyNameAttribute")
                .Select(a => a.ArgumentList?.Arguments.FirstOrDefault()?.Expression).Where(e => e != null)
                .Select(e => Text(e!)).FirstOrDefault(t => t != null);

        private JsonObject EnumSchema(EnumDeclarationSyntax enumeration)
        {
            var names = enumeration.Members.Select(m => m.Identifier.Text).ToList();
            var flags = enumeration.AttributeLists.SelectMany(l => l.Attributes).Any(a => a.Name.ToString().Split('.')[^1] is "Flags" or "FlagsAttribute");
            var schema = new JsonObject { ["type"] = "string" };
            var lines = new List<string>();
            if (Summary(enumeration) is { } summary) lines.Add(summary);
            if (flags)
                lines.Add("Flags, several joined with a comma: " + string.Join(", ", names));
            else
                schema["enum"] = new JsonArray(names.Select(n => (JsonNode)JsonValue.Create(n)!).ToArray());
            foreach (var member in enumeration.Members)
                if (Summary(member) is { } memberSummary) lines.Add($"{member.Identifier.Text}: {memberSummary}");
            if (lines.Count > 0) schema["description"] = string.Join("\n", lines);
            return schema;
        }

        /** The value an initializer gives, when it is one the file shows: a literal, an enum member, `TimeSpan.FromSeconds(30)`, a list of literals. */
        private JsonNode? Default(ExpressionSyntax value, JsonObject schema)
        {
            switch (value)
            {
                case LiteralExpressionSyntax literal:
                    return literal.Kind() switch
                    {
                        SyntaxKind.StringLiteralExpression => JsonValue.Create(literal.Token.ValueText),
                        SyntaxKind.TrueLiteralExpression => JsonValue.Create(true),
                        SyntaxKind.FalseLiteralExpression => JsonValue.Create(false),
                        SyntaxKind.NumericLiteralExpression => Number(literal.Token.Value),
                        _ => null,
                    };
                case PrefixUnaryExpressionSyntax { RawKind: (int)SyntaxKind.UnaryMinusExpression, Operand: LiteralExpressionSyntax number }
                    when number.IsKind(SyntaxKind.NumericLiteralExpression):
                    return Number(number.Token.Value, negative: true);
                case MemberAccessExpressionSyntax member when schema["enum"] is JsonArray values:
                    return values.Any(v => v?.GetValue<string>() == member.Name.Identifier.Text) ? JsonValue.Create(member.Name.Identifier.Text) : null;
                case InvocationExpressionSyntax { Expression: MemberAccessExpressionSyntax { Expression: IdentifierNameSyntax { Identifier.Text: "TimeSpan" } } factory } call
                    when call.ArgumentList.Arguments.Count == 1 && call.ArgumentList.Arguments[0].Expression is LiteralExpressionSyntax amount &&
                         amount.Token.Value is IConvertible convertible:
                {
                    var n = convertible.ToDouble(CultureInfo.InvariantCulture);
                    TimeSpan? span = factory.Name.Identifier.Text switch
                    {
                        "FromMilliseconds" => TimeSpan.FromMilliseconds(n),
                        "FromSeconds" => TimeSpan.FromSeconds(n),
                        "FromMinutes" => TimeSpan.FromMinutes(n),
                        "FromHours" => TimeSpan.FromHours(n),
                        "FromDays" => TimeSpan.FromDays(n),
                        _ => null,
                    };
                    return span == null ? null : JsonValue.Create(span.Value.ToString("c", CultureInfo.InvariantCulture));
                }
                case CollectionExpressionSyntax collection:
                    return Items(collection.Elements.Select(e => (e as ExpressionElementSyntax)?.Expression), schema);
                case ArrayCreationExpressionSyntax { Initializer: { } initializer }:
                    return Items(initializer.Expressions, schema);
                case ImplicitArrayCreationExpressionSyntax array:
                    return Items(array.Initializer.Expressions, schema);
                case ObjectCreationExpressionSyntax { Initializer: { } initializer } when initializer.IsKind(SyntaxKind.CollectionInitializerExpression):
                    return Items(initializer.Expressions, schema);
            }
            return null;
        }

        private JsonNode? Items(IEnumerable<ExpressionSyntax?> expressions, JsonObject schema)
        {
            if (schema["items"] is not JsonObject items) return null;
            var values = new JsonArray();
            foreach (var expression in expressions)
            {
                var item = expression == null ? null : Default(expression, items);
                if (item == null) return null;
                values.Add(item);
            }
            return values;
        }

        private static JsonNode? Number(object? value, bool negative = false) => value switch
        {
            int i => JsonValue.Create(negative ? -i : i),
            long l => JsonValue.Create(negative ? -l : l),
            uint u => JsonValue.Create(negative ? -(long)u : u),
            ulong ul when !negative => JsonValue.Create(ul),
            double d => JsonValue.Create(negative ? -d : d),
            float f => JsonValue.Create(negative ? -f : f),
            decimal m => JsonValue.Create(negative ? -m : m),
            _ => null,
        };

        /** The text of the `<summary>` of the XML doc of a declaration, on one line: `<see cref="X"/>` gives X, `<c>x</c>` gives x. */
        private static string? Summary(SyntaxNode declaration)
        {
            foreach (var trivia in declaration.GetLeadingTrivia())
            {
                if (trivia.GetStructure() is not DocumentationCommentTriviaSyntax doc) continue;
                var summary = doc.Content.OfType<XmlElementSyntax>().FirstOrDefault(e => e.StartTag.Name.LocalName.Text == "summary");
                if (summary == null) continue;
                var text = Regex.Replace(XmlText(summary.Content), @"\s+", " ").Trim();
                return text.Length > 0 ? text : null;
            }
            return null;
        }

        private static string XmlText(SyntaxList<XmlNodeSyntax> nodes)
        {
            var builder = new StringBuilder();
            foreach (var node in nodes)
            {
                switch (node)
                {
                    case XmlTextSyntax text:
                        foreach (var token in text.TextTokens)
                            builder.Append(token.IsKind(SyntaxKind.XmlTextLiteralNewLineToken) ? " " : token.ValueText);
                        break;
                    case XmlElementSyntax element:
                        builder.Append(XmlText(element.Content));
                        break;
                    case XmlEmptyElementSyntax empty:
                        foreach (var attribute in empty.Attributes)
                        {
                            switch (attribute)
                            {
                                case XmlCrefAttributeSyntax cref: builder.Append(cref.Cref.ToString().Split('.')[^1]); break;
                                case XmlNameAttributeSyntax name: builder.Append(name.Identifier.Identifier.Text); break;
                                case XmlTextAttributeSyntax textAttribute: builder.Append(string.Concat(textAttribute.TextTokens.Select(t => t.ValueText))); break;
                            }
                        }
                        break;
                }
            }
            return builder.ToString();
        }
    }
}
