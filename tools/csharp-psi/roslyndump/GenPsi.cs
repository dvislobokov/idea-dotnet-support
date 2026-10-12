// gen-psi: the typed PSI of csharp-psi-core (interfaces, implementations, shapes, factory table, visitor) and the
// accessor table of its corpus gate, from Roslyn's Syntax.xml. Rules: docs/csharp-psi/GRAMMAR.md, section "PSI".
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using Microsoft.CodeAnalysis.CSharp;

namespace RoslynDump;

static class GenPsi
{
    const string Package = "io.github.dotnetsupport.csharp.lang.psi";
    const string SyntaxXmlPath = "src/Compilers/CSharp/Portable/Syntax/Syntax.xml";

    enum Cat { Token, Node, TokenList, NodeList, SeparatedList, AnyList, Bool }

    sealed class Field
    {
        public string Name = "";
        public Cat Cat;
        /// <summary>Roslyn class of a node, node list or separated list field.</summary>
        public string? NodeType;
        public bool Optional;
        public List<string> Kinds = [];
        public string? Comment;
        public string Owner = "";

        // Computed.
        /// <summary>Kinds a child must have to belong to the field (elements for lists); empty with <see cref="AnyToken"/>.</summary>
        public SortedSet<string> Accepts = new(StringComparer.Ordinal);
        /// <summary>A token field or token list with no kinds in Syntax.xml: any token not claimed by what follows.</summary>
        public bool AnyToken;
        public SortedSet<string> Stop = new(StringComparer.Ordinal);
        public bool IsList => Cat is Cat.TokenList or Cat.NodeList or Cat.SeparatedList or Cat.AnyList;
        public bool Nullable => Optional || IsList;
    }

    sealed class NodeDef
    {
        public string Name = "";
        public string? Base;
        public bool Abstract;
        public bool Predefined;
        public List<string> Kinds = [];
        public List<Field> Fields = [];
        public string? Comment;
    }

    /// <summary>
    /// Fields whose Syntax.xml shape is ambiguous for a positional match (adjacent fields that accept the same kind), and
    /// how the matcher of csharp-psi resolves each (docs/csharp-psi/GRAMMAR.md, "PSI"). Key: Roslyn class.Field.
    /// </summary>
    static readonly Dictionary<string, string> Resolved = new(StringComparer.Ordinal)
    {
        // `await using const int x = 0;`: the optional keywords come first and are taken first; the modifiers of a
        // local (ParseLocalDeclarationStatementModifiers) are never `await` or `using`.
        ["LocalDeclarationStatementSyntax.AwaitKeyword"] = "taken first (never a modifier)",
        ["LocalDeclarationStatementSyntax.UsingKeyword"] = "taken first (never a modifier)",
    };

    /// <summary>
    /// Abstract fields a concrete class does not repeat: Roslyn implements them by hand (partial classes), not as slots.
    /// Value: the Kotlin expression of the implementation.
    /// </summary>
    static readonly Dictionary<string, string> HandWrittenOverrides = new(StringComparer.Ordinal)
    {
        // NameColonSyntax.cs: `public override ExpressionSyntax Expression => Name;`
        ["NameColonSyntax.Expression"] = "nameElement",
        // ExtensionBlockDeclarationSyntax.cs: an extension block has no identifier and no base list.
        ["ExtensionBlockDeclarationSyntax.Identifier"] = "null",
        ["ExtensionBlockDeclarationSyntax.BaseList"] = "null",
    };

    /// <summary>
    /// Classes whose implementations are stub-based (step 8, docs/csharp-psi/GRAMMAR.md, "Stubs"): they extend
    /// `CSharpStubElementImpl` (a `StubBasedPsiElementBase`) and get a constructor from a stub; the factory table lists
    /// them in `stubConstructors`. Which of their nodes get a stub is decided by `CSharpStubRules`: the declarations
    /// outside bodies, and the nodes between them (the compilation unit, an extension block, the variable declaration of
    /// a field), so that the parent of a stub is the parent of its node.
    /// </summary>
    static readonly HashSet<string> Stubbed = new(StringComparer.Ordinal)
    {
        "CompilationUnitSyntax", "NamespaceDeclarationSyntax", "FileScopedNamespaceDeclarationSyntax",
        "ClassDeclarationSyntax", "StructDeclarationSyntax", "InterfaceDeclarationSyntax", "RecordDeclarationSyntax",
        "EnumDeclarationSyntax", "DelegateDeclarationSyntax", "ExtensionBlockDeclarationSyntax",
        "MethodDeclarationSyntax", "ConstructorDeclarationSyntax", "DestructorDeclarationSyntax", "OperatorDeclarationSyntax",
        "ConversionOperatorDeclarationSyntax", "PropertyDeclarationSyntax", "IndexerDeclarationSyntax", "EventDeclarationSyntax",
        "FieldDeclarationSyntax", "EventFieldDeclarationSyntax", "VariableDeclarationSyntax", "VariableDeclaratorSyntax",
        "EnumMemberDeclarationSyntax",
    };

    /// <summary>`gen-psi &lt;roslyn dir&gt; --out &lt;csharp-psi-core dir&gt;`: main and test sources of the module.</summary>
    public static int Run(string roslynDir, string moduleDir)
    {
        var mainOut = Path.Combine(moduleDir, "src/main/kotlin/io/github/dotnetsupport/csharp/lang/psi");
        var testOut = Path.Combine(moduleDir, "src/test/kotlin/io/github/dotnetsupport/csharp/lang/psi");
        var xmlPath = Path.Combine(roslynDir, SyntaxXmlPath);
        if (!File.Exists(xmlPath))
        {
            Console.Error.WriteLine($"gen-psi: {xmlPath} not found (tools/csharp-psi/fetch-roslyn.sh)");
            return 2;
        }
        // The header names the commit the sources were generated from: the checkout's HEAD, which must be the pin.
        var roslynCommit = FindRoslynCommit();
        var head = GitHead(roslynDir);
        if (head == null || !head.Equals(roslynCommit, StringComparison.OrdinalIgnoreCase))
        {
            Console.Error.WriteLine($"gen-psi: {roslynDir} is at {head ?? "(not a git checkout)"}, gradle.properties pins roslynCommit " +
                                    $"{roslynCommit}: rerun tools/csharp-psi/fetch-roslyn.sh (or update the pin with the package, CLAUDE.md)");
            return 2;
        }
        var doc = XDocument.Load(xmlPath);
        var nodes = new List<NodeDef>();
        foreach (var e in doc.Root!.Elements())
        {
            var kind = e.Name.LocalName;
            if (kind is not ("PredefinedNode" or "AbstractNode" or "Node")) continue;
            var n = new NodeDef
            {
                Name = (string)e.Attribute("Name")!,
                Base = (string?)e.Attribute("Base"),
                Abstract = kind == "AbstractNode",
                Predefined = kind == "PredefinedNode",
                Kinds = e.Elements("Kind").Select(k => (string)k.Attribute("Name")!).ToList(),
                Comment = Summary(e.Element("TypeComment")),
            };
            CollectFields(e, n, false);
            nodes.Add(n);
        }
        var byName = nodes.ToDictionary(n => n.Name);
        var model = new Model(nodes, byName);
        model.Compute();

        var header = $"""
            // Generated by `roslyndump gen-psi` (tools/csharp-psi/roslyndump/GenPsi.cs) from Roslyn's {SyntaxXmlPath},
            // roslynCommit {roslynCommit}. Do not edit: rerun gen-psi (tools/csharp-psi/roslyndump/README.md).
            // Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
            """;
        // Everything is generated first and written only when the model has no errors: a failed run leaves the
        // checked-in sources as they were.
        var files = new List<(string Path, string Text)>
        {
            (Path.Combine(mainOut, "CSharpPsi.kt"), model.Interfaces(header)),
            (Path.Combine(mainOut, "CSharpVisitor.kt"), model.Visitor(header)),
            (Path.Combine(mainOut, "impl", "CSharpPsiImpl.kt"), model.Impls(header)),
            (Path.Combine(mainOut, "impl", "CSharpSyntaxKindSets.kt"), model.KindSets(header)),
            (Path.Combine(testOut, "CSharpPsiFieldTable.kt"), model.FieldTable(header)),
        };
        model.Report();
        if (model.Errors != 0)
        {
            Console.Error.WriteLine($"gen-psi: {model.Errors} errors, nothing written");
            return 1;
        }
        Directory.CreateDirectory(Path.Combine(mainOut, "impl"));
        Directory.CreateDirectory(testOut);
        foreach (var (path, text) in files) File.WriteAllText(path, text);
        return 0;
    }

    /// <summary>`git rev-parse HEAD` of <paramref name="dir"/>; null when it is not a git checkout or git fails.</summary>
    static string? GitHead(string dir)
    {
        try
        {
            var psi = new System.Diagnostics.ProcessStartInfo("git", ["-C", dir, "rev-parse", "HEAD"])
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
            };
            using var p = System.Diagnostics.Process.Start(psi)!;
            var output = p.StandardOutput.ReadToEnd().Trim();
            p.StandardError.ReadToEnd();
            p.WaitForExit();
            return p.ExitCode == 0 && output.Length > 0 ? output : null;
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return null;
        }
    }

    static void CollectFields(XElement parent, NodeDef n, bool optional)
    {
        foreach (var c in parent.Elements())
        {
            switch (c.Name.LocalName)
            {
                case "Field":
                    var type = (string)c.Attribute("Type")!;
                    var f = new Field
                    {
                        Name = (string)c.Attribute("Name")!,
                        Optional = optional || (string?)c.Attribute("Optional") == "true",
                        // <ContextualKind Name="RecordKeyword"/>: the token is a contextual keyword, whose kind in the tree is the keyword's.
                        Kinds = c.Elements().Where(k => k.Name.LocalName is "Kind" or "ContextualKind").Select(k => (string)k.Attribute("Name")!).ToList(),
                        Comment = Summary(c.Element("PropertyComment")),
                        Owner = n.Name,
                    };
                    (f.Cat, f.NodeType) = type switch
                    {
                        "SyntaxToken" => (Cat.Token, null),
                        "SyntaxList<SyntaxToken>" => (Cat.TokenList, null),
                        "SyntaxNodeOrTokenList" => (Cat.AnyList, null),
                        "bool" => (Cat.Bool, null),
                        _ when type.StartsWith("SyntaxList<") => (Cat.NodeList, type["SyntaxList<".Length..^1]),
                        _ when type.StartsWith("SeparatedSyntaxList<") => (Cat.SeparatedList, type["SeparatedSyntaxList<".Length..^1]),
                        _ => (Cat.Node, type),
                    };
                    n.Fields.Add(f);
                    break;
                // Exactly one alternative of a Choice is present (or none, Optional="true"): every field in it is optional.
                case "Choice":
                    CollectFields(c, n, true);
                    break;
                case "Sequence":
                    CollectFields(c, n, optional);
                    break;
            }
        }
    }

    static string? Summary(XElement? comment)
    {
        var summary = comment?.Element("summary");
        if (summary == null) return null;
        summary = new XElement(summary);
        // <see cref="X"/> and the like carry the name in an attribute.
        foreach (var see in summary.Descendants().ToList().Where(d => d.Attribute("cref") != null || d.Attribute("langword") != null))
            see.ReplaceWith(new XText(((string?)see.Attribute("cref") ?? (string?)see.Attribute("langword") ?? "").Split(':').Last()));
        var text = string.Concat(summary.DescendantNodes().OfType<XText>().Select(t => t.Value));
        text = Regex.Replace(text, @"\s+", " ").Trim().Replace("*/", "* /");
        return text.Length == 0 ? null : text;
    }

    sealed class Model(List<NodeDef> nodes, Dictionary<string, NodeDef> byName)
    {
        public int Errors;
        readonly List<string> notes = [];
        readonly List<string> ambiguities = [];
        // Named kind sets of node types used by fields (Kotlin: CSharpSyntaxKindSets).
        readonly SortedDictionary<string, SortedSet<string>> nodeSets = new(StringComparer.Ordinal);
        HashSet<string> tokenKinds = [];

        IEnumerable<NodeDef> Ancestors(NodeDef n)
        {
            for (var b = n.Base; b != null && byName.TryGetValue(b, out var bn); b = bn.Base) yield return bn;
        }

        bool IsA(NodeDef n, string type) => n.Name == type || Ancestors(n).Any(a => a.Name == type);

        IEnumerable<NodeDef> Concrete => nodes.Where(n => !n.Abstract && !n.Predefined);

        SortedSet<string> KindsOf(string type) =>
            new(Concrete.Where(n => IsA(n, type)).SelectMany(n => n.Kinds), StringComparer.Ordinal);

        Field? Inherited(NodeDef n, string field) =>
            Ancestors(n).Select(a => a.Fields.FirstOrDefault(f => f.Name == field)).FirstOrDefault(f => f != null);

        public void Compute()
        {
            tokenKinds = Enum.GetNames<SyntaxKind>().Where(k => SyntaxFacts.IsAnyToken(Enum.Parse<SyntaxKind>(k))).ToHashSet();
            var allKinds = Enum.GetNames<SyntaxKind>().ToHashSet();
            foreach (var n in nodes)
            {
                foreach (var k in n.Kinds)
                    if (!allKinds.Contains(k)) Error($"{n.Name}: unknown kind {k}");
                foreach (var f in n.Fields)
                {
                    // An override without kinds keeps the kinds of the field it overrides.
                    var kinds = f.Kinds.Count > 0 ? f.Kinds : Inherited(n, f.Name)?.Kinds ?? [];
                    foreach (var k in kinds)
                        if (!allKinds.Contains(k)) Error($"{n.Name}.{f.Name}: unknown kind {k}");
                    switch (f.Cat)
                    {
                        case Cat.Token:
                        case Cat.TokenList:
                            if (kinds.Count == 0) f.AnyToken = true;
                            else f.Accepts.UnionWith(kinds);
                            break;
                        case Cat.Node:
                        case Cat.NodeList:
                        case Cat.SeparatedList:
                            if (!byName.ContainsKey(f.NodeType!)) Error($"{n.Name}.{f.Name}: unknown type {f.NodeType}");
                            var set = KindsOf(f.NodeType!);
                            if (kinds.Count > 0) set.IntersectWith(kinds);
                            if (set.Count == 0) Error($"{n.Name}.{f.Name}: no node kind");
                            f.Accepts = set;
                            if (kinds.Count == 0) nodeSets[f.NodeType!] = set;
                            break;
                    }
                }
            }
            foreach (var n in Concrete)
            {
                // A concrete class repeats every field of its abstract bases (Override="true"), in its own order.
                foreach (var a in Ancestors(n))
                    foreach (var f in a.Fields.Where(f => f.Cat != Cat.Bool))
                        if (n.Fields.All(g => g.Name != f.Name) && !HandWrittenOverrides.ContainsKey($"{n.Name}.{f.Name}"))
                            Error($"{n.Name} does not repeat {a.Name}.{f.Name}");
                Analyse(n);
            }
            foreach (var n in nodes)
                foreach (var f in n.Fields.Where(f => f.Cat == Cat.Bool))
                    notes.Add($"bool field {n.Name}.{f.Name} not generated (not a slot)");
        }

        // FIRST of the fields from index i on: the kinds the next child may have, up to and including the first field
        // that is always present; `any` when a token field with no kinds is on the way.
        (SortedSet<string> Kinds, bool AnyToken) First(List<Field> fields, int i)
        {
            var set = new SortedSet<string>(StringComparer.Ordinal);
            var any = false;
            for (; i < fields.Count; i++)
            {
                var f = fields[i];
                if (f.Cat == Cat.Bool) continue;
                if (f.Cat == Cat.AnyList) any = true;
                set.UnionWith(f.Accepts);
                any |= f.AnyToken;
                if (!f.Nullable) break;
            }
            return (set, any);
        }

        void Analyse(NodeDef n)
        {
            var fields = n.Fields.Where(f => f.Cat != Cat.Bool).ToList();
            for (var i = 0; i < fields.Count; i++)
            {
                var f = fields[i];
                var (rest, restAny) = First(fields, i + 1);
                if (f.AnyToken)
                {
                    // Stops before a token the following fields can take.
                    f.Stop = new SortedSet<string>(rest.Where(tokenKinds.Contains), StringComparer.Ordinal);
                    if (restAny) Ambiguity(n, f, fields[i + 1], "both take any token");
                    continue;
                }
                if (!f.Nullable) continue;
                var cont = new SortedSet<string>(f.Accepts, StringComparer.Ordinal);
                if (f.Cat == Cat.SeparatedList) cont.Add("CommaToken");
                var overlap = cont.Where(rest.Contains).ToList();
                if (restAny) overlap.AddRange(cont.Where(tokenKinds.Contains));
                if (overlap.Count > 0)
                {
                    var next = fields.Skip(i + 1).First(g => g.Accepts.Overlaps(overlap) || g.AnyToken);
                    Ambiguity(n, f, next, string.Join(" ", overlap.Distinct().Take(8)) + (overlap.Count > 8 ? " ..." : ""));
                }
            }
        }

        void Ambiguity(NodeDef n, Field f, Field next, string what)
        {
            var key = $"{n.Name}.{f.Name}";
            var resolved = Resolved.TryGetValue(key, out var how) ? how : null;
            ambiguities.Add($"{key} / {next.Name}: {what}{(resolved != null ? $" -> {resolved}" : "")}");
            // A new overlap (a new Roslyn) needs a decision: a rule of CSharpSyntaxShape or a hand-written accessor.
            if (resolved == null) Error($"{key} / {next.Name}: adjacent fields take the same kinds ({what}), add a resolution to GenPsi.Resolved");
        }

        void Error(string message)
        {
            Errors++;
            Console.Error.WriteLine("gen-psi: error: " + message);
        }

        public void Report()
        {
            foreach (var a in ambiguities) Console.Error.WriteLine("gen-psi: adjacent fields: " + a);
            foreach (var n in notes) Console.Error.WriteLine("gen-psi: " + n);
            Console.Error.WriteLine($"gen-psi: {nodes.Count(n => n.Abstract)} abstract and {Concrete.Count()} concrete classes, " +
                                    $"{Concrete.Sum(n => n.Kinds.Count)} node kinds, {Concrete.Sum(n => n.Fields.Count(f => f.Cat != Cat.Bool))} fields of concrete classes, " +
                                    $"{ambiguities.Count} adjacent-field overlaps");
        }

        // ---- Kotlin names -------------------------------------------------------------------------------------------

        static readonly HashSet<string> KotlinHardKeywords =
        [
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
            "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
            "when", "while",
        ];

        // Accessors whose getter would clash with a member of PsiElement / NavigatablePsiElement (getName(): String?).
        static readonly Dictionary<string, string> Renamed = new(StringComparer.Ordinal) { ["name"] = "nameElement" };

        static readonly HashSet<string> PsiElementMembers =
        [
            "name", "text", "parent", "children", "context", "node", "language", "project", "manager", "reference",
            "references", "navigationElement", "originalElement", "containingFile", "textRange", "textOffset",
            "textLength", "firstChild", "lastChild", "nextSibling", "prevSibling", "presentation", "resolveScope",
            "useScope", "valid", "writable", "physical", "startOffsetInParent", "shape", "icon", "copy",
        ];

        public static string Iface(string roslynClass) => roslynClass switch
        {
            "CSharpSyntaxNode" => "CSharpElement",
            _ => "CSharp" + (roslynClass.EndsWith("Syntax") ? roslynClass[..^"Syntax".Length] : roslynClass),
        };

        static string Short(string roslynClass) => Iface(roslynClass)["CSharp".Length..];

        static string PropName(string field)
        {
            var p = char.ToLowerInvariant(field[0]) + field[1..];
            if (Renamed.TryGetValue(p, out var r)) return r;
            if (PsiElementMembers.Contains(p)) throw new InvalidOperationException($"field {field} clashes with a PsiElement member");
            return p;
        }

        static string Kt(string ident) => KotlinHardKeywords.Contains(ident) ? $"`{ident}`" : ident;

        static string KtType(Field f) => f.Cat switch
        {
            Cat.Token => "PsiElement?",
            Cat.Node => Iface(f.NodeType!) + "?",
            Cat.TokenList or Cat.AnyList => "List<PsiElement>",
            Cat.NodeList or Cat.SeparatedList => $"List<{Iface(f.NodeType!)}>",
            _ => throw new InvalidOperationException(),
        };

        static string RoslynType(Field f) => f.Cat switch
        {
            Cat.Token => "SyntaxToken",
            Cat.Node => f.NodeType!,
            Cat.TokenList => "SyntaxList<SyntaxToken>",
            Cat.AnyList => "SyntaxNodeOrTokenList",
            Cat.NodeList => $"SyntaxList<{f.NodeType}>",
            Cat.SeparatedList => $"SeparatedSyntaxList<{f.NodeType}>",
            _ => "bool",
        };

        string Doc(string indent, params string?[] lines)
        {
            var list = lines.Select(l => l?.Trim()).Where(l => !string.IsNullOrEmpty(l)).ToList();
            if (list.Count == 0) return "";
            if (list.Count == 1 && list[0]!.Length < 100) return $"{indent}/** {list[0]} */\n";
            var sb = new StringBuilder($"{indent}/**\n");
            foreach (var l in list)
                foreach (var w in Wrap(l!, 110 - indent.Length)) sb.Append($"{indent} * {w}\n");
            return sb.Append($"{indent} */\n").ToString();
        }

        static IEnumerable<string> Wrap(string text, int width)
        {
            var line = new StringBuilder();
            foreach (var word in text.Split(' '))
            {
                if (line.Length > 0 && line.Length + 1 + word.Length > width)
                {
                    yield return line.ToString();
                    line.Clear();
                }
                if (line.Length > 0) line.Append(' ');
                line.Append(word);
            }
            if (line.Length > 0) yield return line.ToString();
        }

        string BaseIface(NodeDef n) => n.Base == null || n.Base == "SyntaxNode" ? "CSharpElement" : Iface(n.Base);

        // ---- CSharpPsi.kt -------------------------------------------------------------------------------------------

        public string Interfaces(string header)
        {
            var sb = new StringBuilder(header).Append('\n');
            sb.Append($$"""
                package {{Package}}

                import com.intellij.psi.PsiElement

                // One interface per class of Roslyn's Syntax.xml, named `CSharp` + the class name without `Syntax`, in Roslyn's
                // hierarchy. Accessors follow the fields in slot order: nodes typed, tokens as PsiElement (the leaf), lists as
                // List, separated lists as the elements plus `<field>Separators`; an absent or missing (recovery) child is null.
                // Field `Name` is `nameElement` (PsiElement's getName() is taken). Rules: docs/csharp-psi/GRAMMAR.md, "PSI".


                """);
            foreach (var n in nodes.Where(n => n.Name is not ("CSharpSyntaxNode" or "SyntaxToken")))
            {
                var kinds = n.Abstract || n.Predefined ? "" : "Kinds: " + string.Join(", ", n.Kinds.Select(k => $"`{k}`")) + ".";
                sb.Append(Doc("", $"Roslyn `{n.Name}`{(n.Abstract ? " (abstract)" : "")}. {n.Comment}", kinds));
                sb.Append($"interface {Iface(n.Name)} : {BaseIface(n)}");
                var own = new List<string>();
                foreach (var f in n.Fields.Where(f => f.Cat != Cat.Bool))
                {
                    var inherited = Inherited(n, f.Name);
                    var over = "";
                    if (inherited != null)
                    {
                        if (KtType(inherited) == KtType(f)) continue;
                        if (!(inherited.Cat == f.Cat && byName[f.NodeType!] is var t && IsA(t, inherited.NodeType!)))
                            throw new InvalidOperationException($"{n.Name}.{f.Name} narrows {inherited.Owner}.{f.Name} incompatibly");
                        over = "override ";
                    }
                    var doc = Doc("    ", $"`{f.Name}`: `{RoslynType(f)}`{(f.Optional ? ", optional" : "")}{KindsDoc(f)}. {f.Comment}");
                    var decl = new StringBuilder(doc).Append($"    {over}val {Kt(PropName(f.Name))}: {KtType(f)}\n");
                    if (f.Cat == Cat.SeparatedList && over == "")
                        decl.Append($"    /** The separators of [{PropName(f.Name)}], in order (missing ones left out). */\n    val {PropName(f.Name)}Separators: List<PsiElement>\n");
                    own.Add(decl.ToString());
                }
                if (own.Count == 0) sb.Append("\n\n");
                else sb.Append(" {\n").Append(string.Join("\n", own)).Append("}\n\n");
            }
            sb.Length -= 1;
            return sb.ToString();
        }

        static string KindsDoc(Field f) => f.Cat == Cat.Token && f.Accepts.Count > 0
            ? " (" + string.Join(", ", f.Accepts.Select(k => $"`{k}`")) + ")"
            : "";

        // ---- CSharpVisitor.kt ---------------------------------------------------------------------------------------

        public string Visitor(string header)
        {
            var sb = new StringBuilder(header).Append('\n');
            sb.Append($$"""
                package {{Package}}

                import com.intellij.psi.PsiElementVisitor

                /**
                 * Visitor over the generated PSI: one method per class of Syntax.xml; each delegates to the method of its Roslyn
                 * base class, up to [visitCSharpElement] and [visitElement]. `accept` of every implementation calls its own method.
                 */
                @Suppress("unused")
                open class CSharpVisitor : PsiElementVisitor() {
                    open fun visitCSharpElement(o: CSharpElement) {
                        visitElement(o)
                    }


                """);
            foreach (var n in nodes.Where(n => n.Name is not ("CSharpSyntaxNode" or "SyntaxToken")))
            {
                var baseMethod = n.Base is null or "SyntaxNode" or "CSharpSyntaxNode" ? "visitCSharpElement" : "visit" + Short(n.Base);
                sb.Append($"    open fun visit{Short(n.Name)}(o: {Iface(n.Name)}) {{\n        {baseMethod}(o)\n    }}\n\n");
            }
            sb.Length -= 1;
            sb.Append("}\n");
            return sb.ToString();
        }

        // ---- impl/CSharpSyntaxKindSets.kt ---------------------------------------------------------------------------

        static string SetName(string roslynClass) => Short(roslynClass).ToUpperSnake();

        public string KindSets(string header)
        {
            var sb = new StringBuilder(header).Append('\n');
            sb.Append($$"""
                package {{Package}}.impl

                import com.intellij.psi.tree.TokenSet
                import io.github.dotnetsupport.csharp.lang.SyntaxKind as K

                /**
                 * The node kinds of every Roslyn class used as a field type: the kinds of all concrete classes derived from it.
                 * A body block ([io.github.dotnetsupport.csharp.lang.parser.CSharpBodyBlockType]) is matched as `Block`.
                 */
                @Suppress("unused")
                object CSharpSyntaxKindSets {

                """);
            foreach (var (type, set) in nodeSets)
            {
                sb.Append($"    /** `{type}` */\n    @JvmField val {SetName(type)}: TokenSet = {Lower(SetName(type))}()\n");
                sb.Append($"    private fun {Lower(SetName(type))}(): TokenSet = TokenSet.create(\n");
                foreach (var k in set) sb.Append($"        K.{k},\n");
                sb.Append("    )\n\n");
            }
            sb.Length -= 1;
            sb.Append("}\n");
            return sb.ToString();
        }

        static string Lower(string snake) => "set" + string.Concat(snake.Split('_').Select(p => p[..1] + p[1..].ToLowerInvariant()));

        string SetExpr(Field f, bool stop = false)
        {
            var set = stop ? f.Stop : f.Accepts;
            if (!stop && f.Kinds.Count == 0 && f.NodeType != null && nodeSets.ContainsKey(f.NodeType) && f.Cat != Cat.Token)
                return "S." + SetName(f.NodeType);
            if (!stop && f.Cat is Cat.Node or Cat.NodeList or Cat.SeparatedList && f.NodeType != null && nodeSets.TryGetValue(f.NodeType, out var full) && full.SetEquals(set))
                return "S." + SetName(f.NodeType);
            if (set.Count == 0) return "TokenSet.EMPTY";
            return "TokenSet.create(" + string.Join(", ", set.Select(k => "K." + k)) + ")";
        }

        // ---- impl/CSharpPsiImpl.kt ----------------------------------------------------------------------------------

        public string Impls(string header)
        {
            var sb = new StringBuilder(header).Append('\n');
            sb.Append($$"""
                package {{Package}}.impl

                import com.intellij.lang.ASTNode
                import com.intellij.psi.PsiElement
                import com.intellij.psi.tree.IElementType
                import com.intellij.psi.tree.TokenSet
                import {{Package}}.*
                import {{Package}}.stubs.CSharpStub
                import io.github.dotnetsupport.csharp.lang.SyntaxKind as K
                import {{Package}}.impl.CSharpSyntaxKindSets as S
                import {{Package}}.impl.CSharpSyntaxShape.Companion.anyList
                import {{Package}}.impl.CSharpSyntaxShape.Companion.node
                import {{Package}}.impl.CSharpSyntaxShape.Companion.nodeList
                import {{Package}}.impl.CSharpSyntaxShape.Companion.separatedList
                import {{Package}}.impl.CSharpSyntaxShape.Companion.token
                import {{Package}}.impl.CSharpSyntaxShape.Companion.tokenList

                // One class per concrete class of Syntax.xml. [shape] lists the fields in slot order with the kinds each accepts;
                // CSharpSyntaxShape assigns the children of the node to them (docs/csharp-psi/GRAMMAR.md, "PSI").


                """);
            foreach (var n in Concrete)
            {
                var fields = n.Fields.Where(f => f.Cat != Cat.Bool).ToList();
                sb.Append($"/** Roslyn `{n.Name}`: {string.Join(", ", n.Kinds)}. */\n");
                if (Stubbed.Contains(n.Name))
                {
                    sb.Append($"class {Iface(n.Name)}Impl : CSharpStubElementImpl, {Iface(n.Name)} {{\n");
                    sb.Append("    constructor(node: ASTNode) : super(node)\n");
                    sb.Append("    constructor(stub: CSharpStub, type: IElementType) : super(stub, type)\n\n");
                }
                else sb.Append($"class {Iface(n.Name)}Impl(node: ASTNode) : CSharpElementImpl(node), {Iface(n.Name)} {{\n");
                sb.Append("    override val shape: CSharpSyntaxShape get() = SHAPE\n");
                for (var i = 0; i < fields.Count; i++)
                {
                    var f = fields[i];
                    var p = Kt(PropName(f.Name));
                    var getter = f.Cat switch
                    {
                        Cat.Token => $"tokenSlot({i})",
                        Cat.Node => $"nodeSlot({i})",
                        Cat.NodeList => $"nodeListSlot({i})",
                        Cat.SeparatedList => $"separatedSlot({i})",
                        Cat.TokenList or Cat.AnyList => $"tokenListSlot({i})",
                        _ => throw new InvalidOperationException(),
                    };
                    sb.Append($"    override val {p}: {KtType(f)} get() = {getter}\n");
                    if (f.Cat == Cat.SeparatedList) sb.Append($"    override val {PropName(f.Name)}Separators: List<PsiElement> get() = separatorsSlot({i})\n");
                }
                foreach (var a in Ancestors(n))
                    foreach (var f in a.Fields.Where(f => f.Cat != Cat.Bool && n.Fields.All(g => g.Name != f.Name)))
                        sb.Append($"    /** Not a slot: Roslyn's hand-written `{n.Name}.{f.Name}`. */\n    override val {Kt(PropName(f.Name))}: {KtType(f)} get() = {HandWrittenOverrides[$"{n.Name}.{f.Name}"]}\n");
                sb.Append($"    override fun accept(visitor: CSharpVisitor) = visitor.visit{Short(n.Name)}(this)\n\n");
                sb.Append("    companion object {\n");
                sb.Append($"        @JvmField val SHAPE = CSharpSyntaxShape(\"{n.Name}\", arrayOf(\n");
                foreach (var f in fields)
                {
                    var name = $"\"{f.Name}\"";
                    var opt = f.Optional ? ", optional = true" : "";
                    sb.Append("            " + f.Cat switch
                    {
                        Cat.Token when f.AnyToken => $"token({name}, null{opt}, stop = {SetExpr(f, true)})",
                        Cat.Token => $"token({name}, {SetExpr(f)}{opt})",
                        Cat.Node => $"node({name}, {SetExpr(f)}{opt})",
                        Cat.NodeList => $"nodeList({name}, {SetExpr(f)})",
                        Cat.SeparatedList => $"separatedList({name}, {SetExpr(f)})",
                        Cat.TokenList when f.AnyToken => $"tokenList({name}, null, stop = {SetExpr(f, true)})",
                        Cat.TokenList => $"tokenList({name}, {SetExpr(f)})",
                        Cat.AnyList => $"anyList({name})",
                        _ => throw new InvalidOperationException(),
                    } + ",\n");
                }
                sb.Append("        ))\n    }\n}\n\n");
            }
            // Factory table.
            sb.Append("""
                /** Constructors of the implementations by element type (every kind of every concrete class). */
                internal object CSharpPsiImplTable {
                    @JvmField val constructors: Map<IElementType, (ASTNode) -> PsiElement> = create()

                    private fun create(): Map<IElementType, (ASTNode) -> PsiElement> {
                        val map = HashMap<IElementType, (ASTNode) -> PsiElement>(2048)

                """);
            foreach (var n in Concrete)
            {
                // a stub-based class has two constructors: the one from a node by a lambda, not an ambiguous reference
                var create = Stubbed.Contains(n.Name) ? $"{{ {Iface(n.Name)}Impl(it) }}" : $"::{Iface(n.Name)}Impl";
                sb.Append($"        for (k in arrayOf({string.Join(", ", n.Kinds.Select(k => "K." + k))})) map[k] = {create}\n");
            }
            sb.Append("        return map\n    }\n\n");
            sb.Append("""
                    /** Constructors from a stub of the stub-based implementations, by element type (every kind of their classes). */
                    @JvmField val stubConstructors: Map<IElementType, (CSharpStub, IElementType) -> PsiElement> = createStubbed()

                    private fun createStubbed(): Map<IElementType, (CSharpStub, IElementType) -> PsiElement> {
                        val map = HashMap<IElementType, (CSharpStub, IElementType) -> PsiElement>(64)

                """);
            foreach (var n in Concrete.Where(n => Stubbed.Contains(n.Name)))
                sb.Append($"        for (k in arrayOf({string.Join(", ", n.Kinds.Select(k => "K." + k))})) map[k] = {{ stub, type -> {Iface(n.Name)}Impl(stub, type) }}\n");
            sb.Append("        return map\n    }\n}\n");
            var missing = Stubbed.Where(s => Concrete.All(n => n.Name != s)).ToList();
            if (missing.Count > 0) throw new InvalidOperationException("GenPsi.Stubbed names no concrete class: " + string.Join(", ", missing));
            return sb.ToString();
        }

        // ---- test: CSharpPsiFieldTable.kt ---------------------------------------------------------------------------

        public string FieldTable(string header)
        {
            var sb = new StringBuilder(header).Append('\n');
            sb.Append($$"""
                package {{Package}}

                import com.intellij.psi.PsiElement
                import {{Package}}.impl.*

                /**
                 * The generated PSI as the accessor gate sees it (`PsiAccessorCorpusTest`): per Roslyn kind the interface and the
                 * implementation class, and the fields of its class in slot order, each read through the public accessor; a
                 * separated list yields its elements and separators in tree order ([PsiFieldSupport.inTreeOrder]).
                 */
                object CSharpPsiFieldTable {
                    class Field(val name: String, val get: (PsiElement) -> List<PsiElement>)

                    /** Roslyn class name to its fields. */
                    @JvmField val fieldsByClass: Map<String, List<Field>> = fields()

                    /** Roslyn kind name to (Roslyn class name, interface, implementation). */
                    @JvmField val classByKind: Map<String, Triple<String, Class<*>, Class<*>>> = classes()

                    private fun one(e: PsiElement?): List<PsiElement> = if (e == null) emptyList() else listOf(e)


                """);
            foreach (var n in Concrete)
            {
                var fields = n.Fields.Where(f => f.Cat != Cat.Bool).ToList();
                var i = Iface(n.Name);
                sb.Append($"    private fun {Lower1(Short(n.Name))}(): List<Field> = listOf(\n");
                foreach (var f in fields)
                {
                    var p = Kt(PropName(f.Name));
                    var expr = f.Cat switch
                    {
                        Cat.Token or Cat.Node => $"one((it as {i}).{p})",
                        Cat.SeparatedList => $"PsiFieldSupport.inTreeOrder(it, (it as {i}).{p}, it.{PropName(f.Name)}Separators)",
                        _ => $"(it as {i}).{p}",
                    };
                    sb.Append($"        Field(\"{f.Name}\") {{ {expr} }},\n");
                }
                sb.Append("    )\n\n");
            }
            sb.Append("    private fun fields(): Map<String, List<Field>> = hashMapOf(\n");
            foreach (var n in Concrete) sb.Append($"        \"{n.Name}\" to {Lower1(Short(n.Name))}(),\n");
            sb.Append("    )\n\n");
            sb.Append("    private fun classes(): Map<String, Triple<String, Class<*>, Class<*>>> {\n");
            sb.Append("        val map = HashMap<String, Triple<String, Class<*>, Class<*>>>(2048)\n");
            foreach (var n in Concrete)
                sb.Append($"        for (k in arrayOf({string.Join(", ", n.Kinds.Select(k => $"\"{k}\""))})) map[k] = Triple(\"{n.Name}\", {Iface(n.Name)}::class.java, {Iface(n.Name)}Impl::class.java)\n");
            sb.Append("        return map\n    }\n}\n");
            return sb.ToString();
        }

        static string Lower1(string s) => char.ToLowerInvariant(s[0]) + s[1..];
    }

    static string FindRoslynCommit()
    {
        foreach (var start in new[] { Directory.GetCurrentDirectory(), AppContext.BaseDirectory })
        {
            for (var dir = new DirectoryInfo(start); dir != null; dir = dir.Parent)
            {
                var props = Path.Combine(dir.FullName, "gradle.properties");
                if (!File.Exists(props)) continue;
                var line = File.ReadLines(props).FirstOrDefault(l => l.StartsWith("roslynCommit="));
                if (line != null) return line["roslynCommit=".Length..].Trim();
            }
        }
        throw new InvalidOperationException("roslynCommit not found in a gradle.properties above the current directory");
    }

    static string ToUpperSnake(this string s) => Regex.Replace(s, "(?<=[a-z0-9])([A-Z])", "_$1").ToUpperInvariant();
}
