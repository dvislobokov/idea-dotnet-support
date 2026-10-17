// symbols: the Roslyn symbols the compiler's tag helper producers read, as JSON — the input of razor-psi's port of the discovery
// (RAZOR_PLAN.md, R5a), whose output `tag-helpers` dumps. Format: README.md ("Symbols for the discovery: symbols").
using System.Globalization;
using System.Reflection;
using System.Text;
using System.Text.Json;
using Microsoft.CodeAnalysis;

namespace RazorDump;

static class SymbolsJson
{
    // The full names the producers' factories look up (Compilation.GetTypeByMetadataName / GetTypesByMetadataName).
    static readonly string[] WellKnown =
    [
        "Microsoft.AspNetCore.Components.BindConverter", "Microsoft.AspNetCore.Components.BindElementAttribute",
        "Microsoft.AspNetCore.Components.BindInputElementAttribute", "Microsoft.AspNetCore.Components.EventHandlerAttribute",
        "Microsoft.AspNetCore.Components.Rendering.RenderTreeBuilder", "Microsoft.AspNetCore.Components.IComponentRenderMode",
        "Microsoft.AspNetCore.Components.ElementReference", "Microsoft.AspNetCore.Components.AcceptsAssetPathAttribute",
        "Microsoft.AspNetCore.Components.IComponent", "Microsoft.AspNetCore.Razor.TagHelpers.ITagHelper",
        "Microsoft.AspNetCore.Mvc.ViewComponentAttribute", "Microsoft.AspNetCore.Mvc.NonViewComponentAttribute",
        "System.Threading.Tasks.Task", "System.Threading.Tasks.Task`1", "System.Collections.Generic.IDictionary`2",
        "System.Collections.Generic.Dictionary`2",
    ];

    // The only methods the producers ask about: view components' Invoke / InvokeAsync, FormName's RenderTreeBuilder.AddNamedEvent.
    static readonly HashSet<string> Methods = new(StringComparer.Ordinal) { "Invoke", "InvokeAsync", "AddNamedEvent", "AddComponentParameter" };

    static readonly SymbolDisplayFormat FullName = SymbolDisplayFormat.FullyQualifiedFormat
        .WithGlobalNamespaceStyle(SymbolDisplayGlobalNamespaceStyle.Omitted).RemoveMiscellaneousOptions(SymbolDisplayMiscellaneousOptions.UseSpecialTypes);
    static readonly SymbolDisplayFormat GloballyQualified = SymbolDisplayFormat.FullyQualifiedFormat
        .WithGlobalNamespaceStyle(SymbolDisplayGlobalNamespaceStyle.Included).RemoveMiscellaneousOptions(SymbolDisplayMiscellaneousOptions.UseSpecialTypes);

    sealed class State(Compilation compilation, Utf8JsonWriter writer)
    {
        public readonly Compilation Compilation = compilation;
        public Utf8JsonWriter Writer = writer;
        /// <summary>Definitions to write: key → symbol; full ones with their members, the rest as stubs (names and kind).</summary>
        public readonly Dictionary<string, INamedTypeSymbol> Definitions = new(StringComparer.Ordinal);
        public readonly HashSet<string> Full = new(StringComparer.Ordinal);
        public readonly Queue<INamedTypeSymbol> Pending = new();
        public readonly INamedTypeSymbol? StringObjectDictionary = compilation.GetTypeByMetadataName("System.Collections.Generic.Dictionary`2")
            ?.Construct(compilation.GetSpecialType(SpecialType.System_String), compilation.GetSpecialType(SpecialType.System_Object));
        public readonly INamedTypeSymbol? IDictionary = compilation.GetTypeByMetadataName("System.Collections.Generic.IDictionary`2");
    }

    public static string Serialize(Compilation compilation)
    {
        var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream, new JsonWriterOptions { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }))
        {
            var state = new State(compilation, writer);
            writer.WriteStartObject();
            writer.WriteString("assembly", compilation.AssemblyName);

            // The assemblies in the order the discovery service visits them: the compilation's, then the references.
            var assemblies = new List<IAssemblySymbol> { compilation.Assembly };
            foreach (var reference in compilation.References)
                if (compilation.GetAssemblyOrModuleSymbol(reference) is IAssemblySymbol assembly) assemblies.Add(assembly);
            writer.WriteStartArray("assemblies");
            foreach (var assembly in assemblies)
            {
                writer.WriteStartObject();
                writer.WriteString("name", assembly.Name);
                writer.WriteStartArray("references");
                foreach (var name in assembly.Modules.First().ReferencedAssemblies.Select(a => a.Name)) writer.WriteStringValue(name);
                writer.WriteEndArray();
                var walk = assembly.Name.StartsWith("Microsoft.AspNetCore.", StringComparison.Ordinal) ||
                           assembly.Modules.First().ReferencedAssemblies.Any(a => a.Name.StartsWith("Microsoft.AspNetCore.", StringComparison.Ordinal));
                // The types of an assembly that might hold tag helpers, as TagHelperDiscoverer walks them: namespaces depth first, the nested
                // types of public types.
                writer.WriteStartArray("types");
                if (walk)
                {
                    var stack = new Stack<INamespaceOrTypeSymbol>();
                    stack.Push(assembly.GlobalNamespace);
                    while (stack.Count > 0)
                    {
                        var item = stack.Pop();
                        if (item is INamespaceSymbol ns)
                        {
                            var members = ns.GetMembers().ToArray();
                            for (var i = members.Length - 1; i >= 0; i--) stack.Push((INamespaceOrTypeSymbol)members[i]);
                        }
                        else if (item is INamedTypeSymbol type)
                        {
                            writer.WriteStringValue(Require(state, type, full: true));
                            if (type.DeclaredAccessibility == Accessibility.Public)
                            {
                                var nested = type.GetTypeMembers();
                                for (var i = nested.Length - 1; i >= 0; i--) stack.Push(nested[i]);
                            }
                        }
                    }
                }
                writer.WriteEndArray();
                writer.WriteEndObject();
            }
            writer.WriteEndArray();

            writer.WriteStartObject("wellKnown");
            foreach (var name in WellKnown)
            {
                writer.WritePropertyName(name);
                writer.WriteStartArray();
                foreach (var type in compilation.GetTypesByMetadataName(name)) writer.WriteStringValue(Require(state, type, full: true));
                writer.WriteEndArray();
            }
            writer.WriteEndObject();
            writer.WriteStartObject("byMetadataName");
            foreach (var name in WellKnown)
            {
                var type = compilation.GetTypeByMetadataName(name);
                if (type == null) writer.WriteNull(name);
                else writer.WriteString(name, Require(state, type, full: true));
            }
            writer.WriteEndObject();

            // The definitions: every walked type in full and what the full ones reach (base classes and attribute classes in full — the
            // producers walk their members and bases; the rest as stubs: name, namespace, kind). First a dry run until nothing new is
            // reached, so a definition is known full or stub before it is written.
            var real = state.Writer;
            using (var dry = new Utf8JsonWriter(Stream.Null))
            {
                state.Writer = dry;
                dry.WriteStartObject();
                var visited = new HashSet<string>(StringComparer.Ordinal);
                while (true)
                {
                    var next = state.Definitions.Where(d => !visited.Contains((state.Full.Contains(d.Key) ? "F" : "S") + d.Key)).ToList();
                    if (next.Count == 0) break;
                    foreach (var (key, type) in next)
                    {
                        var full = state.Full.Contains(key);
                        visited.Add((full ? "F" : "S") + key);
                        WriteDefinition(state, key, type, full);
                    }
                }
            }
            state.Writer = real;
            var count = state.Definitions.Count;
            writer.WriteStartObject("types");
            foreach (var (key, type) in state.Definitions.ToList()) WriteDefinition(state, key, type, state.Full.Contains(key));
            if (state.Definitions.Count != count) throw new InvalidOperationException("a definition reached only while writing");
            writer.WriteEndObject();
            writer.WriteEndObject();
        }
        return Encoding.UTF8.GetString(stream.ToArray()) + "\n";
    }

    /// <summary>The key of a definition: <c>assembly|metadata name</c> (nested types with <c>+</c>), registered to be written.</summary>
    static string Require(State state, INamedTypeSymbol type, bool full)
    {
        var definition = type.OriginalDefinition;
        var key = Key(definition);
        state.Definitions.TryAdd(key, definition);
        if (full && state.Full.Add(key))
        {
            // the producers walk the base classes and the attribute classes' bases: those in full as well
            if (definition.BaseType is { } baseType) Require(state, baseType, full: true);
            if (definition.ContainingType is { } containing) Require(state, containing, full: false);
        }
        return key;
    }

    static string Key(INamedTypeSymbol definition)
    {
        var name = new StringBuilder(definition.MetadataName);
        for (var outer = definition.ContainingType; outer != null; outer = outer.ContainingType) name.Insert(0, outer.MetadataName + "+");
        var ns = definition.ContainingNamespace is { IsGlobalNamespace: false } n ? n.ToDisplayString() + "." : "";
        return (definition.ContainingAssembly?.Name ?? "?") + "|" + ns + name;
    }

    static void WriteDefinition(State state, string key, INamedTypeSymbol type, bool full)
    {
        var w = state.Writer;
        w.WritePropertyName(key);
        w.WriteStartObject();
        w.WriteString("name", type.Name);
        w.WriteString("metadataName", type.MetadataName);
        w.WriteString("namespace", type.ContainingNamespace is { IsGlobalNamespace: false } ns ? ns.ToDisplayString() : "");
        if (type.ContainingType != null) w.WriteString("containing", Require(state, type.ContainingType, full: false));
        w.WriteString("kind", type.TypeKind.ToString());
        w.WriteString("accessibility", type.DeclaredAccessibility.ToString());
        if (type.IsAbstract) w.WriteBoolean("abstract", true);
        if (type.IsStatic) w.WriteBoolean("static", true);
        if (type.SpecialType != SpecialType.None) w.WriteString("special", type.SpecialType.ToString());
        w.WriteNumber("arity", type.Arity);
        if (!full)
        {
            w.WriteEndObject();
            return;
        }
        w.WriteBoolean("full", true);
        if (type.TypeParameters.Length > 0)
        {
            w.WriteStartArray("typeParameters");
            foreach (var parameter in type.TypeParameters)
            {
                w.WriteStartObject();
                w.WriteString("name", parameter.Name);
                if (parameter.HasReferenceTypeConstraint) w.WriteBoolean("class", true);
                if (parameter.HasValueTypeConstraint) w.WriteBoolean("struct", true);
                if (parameter.HasUnmanagedTypeConstraint) w.WriteBoolean("unmanaged", true);
                if (parameter.HasNotNullConstraint) w.WriteBoolean("notnull", true);
                if (parameter.HasConstructorConstraint) w.WriteBoolean("new", true);
                if (parameter.ConstraintTypes.Length > 0)
                {
                    w.WriteStartArray("constraintTypes");
                    foreach (var constraint in parameter.ConstraintTypes) TypeRef(state, constraint, displays: true);
                    w.WriteEndArray();
                }
                Attributes(state, parameter.GetAttributes());
                w.WriteEndObject();
            }
            w.WriteEndArray();
        }
        if (type.BaseType != null)
        {
            w.WritePropertyName("base");
            TypeRef(state, type.BaseType, displays: false);
        }
        if (type.AllInterfaces.Length > 0)
        {
            w.WriteStartArray("interfaces");
            foreach (var i in type.AllInterfaces) TypeRef(state, i, displays: false);
            w.WriteEndArray();
        }
        Attributes(state, type.GetAttributes());
        if (type.GetDocumentationCommentXml() is { Length: > 0 } doc) w.WriteString("doc", doc);

        var properties = type.GetMembers().OfType<IPropertySymbol>().ToList();
        if (properties.Count > 0)
        {
            w.WriteStartArray("properties");
            foreach (var property in properties) Property(state, property);
            w.WriteEndArray();
        }
        var methods = type.GetMembers().OfType<IMethodSymbol>().Where(m => Methods.Contains(m.Name)).ToList();
        if (methods.Count > 0)
        {
            w.WriteStartArray("methods");
            foreach (var method in methods)
            {
                w.WriteStartObject();
                w.WriteString("name", method.Name);
                w.WriteString("accessibility", method.DeclaredAccessibility.ToString());
                if (method.IsStatic) w.WriteBoolean("static", true);
                if (method.ReturnsVoid) w.WriteBoolean("void", true);
                w.WritePropertyName("returnType");
                TypeRef(state, method.ReturnType, displays: false);
                w.WriteStartArray("parameters");
                foreach (var parameter in method.Parameters)
                {
                    w.WriteStartObject();
                    w.WriteString("name", parameter.Name);
                    w.WritePropertyName("type");
                    TypeRef(state, parameter.Type, displays: true);
                    if (parameter.HasExplicitDefaultValue) w.WriteBoolean("default", true);
                    DictionaryArguments(state, parameter.Type);
                    w.WriteEndObject();
                }
                w.WriteEndArray();
                w.WriteEndObject();
            }
            w.WriteEndArray();
        }
        w.WriteEndObject();
    }

    static void Property(State state, IPropertySymbol property)
    {
        var w = state.Writer;
        w.WriteStartObject();
        w.WriteString("name", property.Name);
        w.WritePropertyName("type");
        TypeRef(state, property.Type, displays: true);
        w.WriteString("accessibility", property.DeclaredAccessibility.ToString());
        if (property.IsStatic) w.WriteBoolean("static", true);
        if (property.IsOverride) w.WriteBoolean("override", true);
        if (property.OverriddenProperty is { } overridden)
        {
            w.WriteStartObject("overridden");
            w.WriteString("type", Require(state, overridden.ContainingType, full: true));
            w.WriteString("name", overridden.Name);
            w.WriteEndObject();
        }
        if (property.Parameters.Length > 0) w.WriteNumber("parameters", property.Parameters.Length);
        if (property.GetMethod != null) w.WriteString("getter", property.GetMethod.DeclaredAccessibility.ToString());
        if (property.SetMethod != null)
        {
            w.WriteString("setter", property.SetMethod.DeclaredAccessibility.ToString());
            if (property.SetMethod.IsInitOnly) w.WriteBoolean("initOnly", true);
        }
        Attributes(state, property.GetAttributes());
        if (property.GetDocumentationCommentXml() is { Length: > 0 } doc) w.WriteString("doc", doc);
        // the compilation's answers the producers ask: CanConvertStringLiteral, the assignment of Dictionary<string, object> (CaptureUnmatchedValues)
        if (CanConvertStringLiteral(state.Compilation, property.Type)) w.WriteBoolean("stringLiteral", true);
        if (state.StringObjectDictionary != null)
        {
            var conversion = state.Compilation.ClassifyCommonConversion(state.StringObjectDictionary, property.Type);
            if (conversion.IsIdentity || (conversion.IsImplicit && conversion.IsReference)) w.WriteBoolean("dictionaryAssignable", true);
        }
        // of the constructed delegate: Func<Task> is awaitable, Func<TResult> is not
        if (property.Type is INamedTypeSymbol { TypeKind: TypeKind.Delegate, DelegateInvokeMethod: { } invoke } && IsAwaitable(invoke)) w.WriteBoolean("awaitable", true);
        DictionaryArguments(state, property.Type);
        w.WriteEndObject();
    }

    /// <summary>The type arguments of <c>IDictionary&lt;,&gt;</c> when the type is one or implements one (the first such interface).</summary>
    static void DictionaryArguments(State state, ITypeSymbol type)
    {
        if (state.IDictionary == null) return;
        var dictionary = type is INamedTypeSymbol named && SymbolEqualityComparer.Default.Equals(named.ConstructedFrom, state.IDictionary)
            ? named
            : type.AllInterfaces.FirstOrDefault(i => SymbolEqualityComparer.Default.Equals(i.ConstructedFrom, state.IDictionary));
        if (dictionary == null) return;
        state.Writer.WriteStartArray("dictionary");
        foreach (var argument in dictionary.TypeArguments) TypeRef(state, argument, displays: true);
        state.Writer.WriteEndArray();
    }

    // CompilationExtensions.CanConvertStringLiteral: an implicit union conversion from string (C# unions; false where Roslyn has none).
    static readonly PropertyInfo? IsUnion = typeof(Microsoft.CodeAnalysis.CSharp.Conversion).GetProperty("IsUnion");

    static bool CanConvertStringLiteral(Compilation compilation, ITypeSymbol type)
    {
        var stringType = compilation.GetSpecialType(SpecialType.System_String);
        if (stringType.TypeKind == TypeKind.Error || SymbolEqualityComparer.Default.Equals(type, stringType) || IsUnion == null) return false;
        var conversion = Microsoft.CodeAnalysis.CSharp.CSharpExtensions.ClassifyConversion(compilation, stringType, type);
        return conversion.IsImplicit && (bool)IsUnion.GetValue(conversion)!;
    }

    // ComponentTagHelperProducer.IsAwaitable on the delegate's Invoke: a GetAwaiter whose result has IsCompleted, OnCompleted and GetResult.
    static bool IsAwaitable(IMethodSymbol invoke)
    {
        if (invoke.ReturnsVoid) return false;
        foreach (var candidate in invoke.ReturnType.GetMembers("GetAwaiter"))
        {
            if (candidate is not IMethodSymbol { ReturnType: { } awaiter }) continue;
            var members = awaiter.GetMembers();
            if (members.Any(m => m is IPropertySymbol { Name: "IsCompleted", Type.SpecialType: SpecialType.System_Boolean, GetMethod: not null }) &&
                members.Any(m => m is IMethodSymbol { Name: "OnCompleted", ReturnsVoid: true, Parameters: [{ Type.TypeKind: TypeKind.Delegate }] }) &&
                members.Any(m => m is IMethodSymbol { Name: "GetResult", Parameters: [] }))
                return true;
        }
        return invoke.IsAsync;
    }

    static void Attributes(State state, IEnumerable<AttributeData> attributes)
    {
        var list = attributes.Where(a => a.AttributeClass != null).ToList();
        if (list.Count == 0) return;
        var w = state.Writer;
        w.WriteStartArray("attributes");
        foreach (var attribute in list)
        {
            w.WriteStartObject();
            w.WriteString("class", Require(state, attribute.AttributeClass!, full: true));
            if (attribute.ConstructorArguments.Length > 0)
            {
                w.WriteStartArray("arguments");
                foreach (var argument in attribute.ConstructorArguments) Constant(state, argument);
                w.WriteEndArray();
            }
            if (attribute.NamedArguments.Length > 0)
            {
                w.WriteStartArray("named");
                foreach (var (name, argument) in attribute.NamedArguments)
                {
                    w.WriteStartObject();
                    w.WriteString("name", name);
                    w.WritePropertyName("value");
                    Constant(state, argument);
                    w.WriteEndObject();
                }
                w.WriteEndArray();
            }
            w.WriteEndObject();
        }
        w.WriteEndArray();
    }

    /// <summary>A typed constant: <c>{"kind", "type", "value"}</c>; a type as a type reference, an array as a list of constants, an enum as its number.</summary>
    static void Constant(State state, TypedConstant constant)
    {
        var w = state.Writer;
        w.WriteStartObject();
        w.WriteString("kind", constant.Kind.ToString());
        if (constant.Type != null)
        {
            w.WritePropertyName("type");
            TypeRef(state, constant.Type, displays: constant.Kind == TypedConstantKind.Enum);
        }
        w.WritePropertyName("value");
        if (constant.Kind == TypedConstantKind.Array)
        {
            if (constant.IsNull) w.WriteNullValue();
            else
            {
                w.WriteStartArray();
                foreach (var item in constant.Values) Constant(state, item);
                w.WriteEndArray();
            }
        }
        else
        {
            switch (constant.Value)
            {
                case null: w.WriteNullValue(); break;
                case ITypeSymbol type: TypeRef(state, type, displays: true); break;
                case string s: w.WriteStringValue(s); break;
                case bool b: w.WriteBooleanValue(b); break;
                case char c: w.WriteStringValue(c.ToString()); break;
                case float or double: w.WriteRawValue(Convert.ToDouble(constant.Value, CultureInfo.InvariantCulture).ToString("R", CultureInfo.InvariantCulture)); break;
                case ulong u: w.WriteRawValue(u.ToString(CultureInfo.InvariantCulture)); break;
                default: w.WriteRawValue(Convert.ToInt64(constant.Value, CultureInfo.InvariantCulture).ToString(CultureInfo.InvariantCulture)); break;
            }
        }
        w.WriteEndObject();
    }

    /// <summary>
    /// A type reference: a named type <c>{"t":"n","d":key,"a":[arguments],"o":containing type}</c> (with <c>"?"</c> when annotated nullable,
    /// <c>"tn"</c> the element names of a tuple), a type parameter <c>{"t":"p","n"}</c>, an array <c>{"t":"a","e","r"}</c>, a pointer, dynamic,
    /// an error type; with <paramref name="displays"/> the three spellings the producers use (<c>"s": [default, full name, globally qualified]</c>).
    /// </summary>
    static void TypeRef(State state, ITypeSymbol type, bool displays)
    {
        var w = state.Writer;
        w.WriteStartObject();
        switch (type)
        {
            case IErrorTypeSymbol error:
                w.WriteString("t", "err");
                w.WriteString("n", error.Name);
                // a name written qualified (System.Collections.IComparer<T> of the wrong arity) keeps its namespace
                if (error.ContainingSymbol is INamespaceSymbol { IsGlobalNamespace: false } errorNamespace) w.WriteString("ns", errorNamespace.ToDisplayString());
                if (error.TypeArguments.Length > 0)
                {
                    w.WriteStartArray("a");
                    foreach (var argument in error.TypeArguments) TypeRef(state, argument, displays: false);
                    w.WriteEndArray();
                }
                break;
            case INamedTypeSymbol named:
                w.WriteString("t", "n");
                w.WriteString("d", Require(state, named, full: false));
                if (named.TypeArguments.Length > 0)
                {
                    w.WriteStartArray("a");
                    for (var i = 0; i < named.TypeArguments.Length; i++)
                    {
                        var argument = named.TypeArguments[i];
                        // the annotation of a type argument lives beside it (TypeArgumentNullableAnnotations)
                        if (named.TypeArgumentNullableAnnotations[i] == NullableAnnotation.Annotated && argument.NullableAnnotation != NullableAnnotation.Annotated)
                            argument = argument.WithNullableAnnotation(NullableAnnotation.Annotated);
                        TypeRef(state, argument, displays: false);
                    }
                    w.WriteEndArray();
                }
                if (named.ContainingType != null)
                {
                    w.WritePropertyName("o");
                    TypeRef(state, named.ContainingType, displays: false);
                }
                if (named.IsTupleType)
                {
                    // a tuple (shown in tuple syntax): its element names, ItemN where it has none
                    w.WriteStartArray("tn");
                    foreach (var element in named.TupleElements) w.WriteStringValue(element.Name);
                    w.WriteEndArray();
                }
                break;
            case ITypeParameterSymbol parameter:
                w.WriteString("t", "p");
                w.WriteString("n", parameter.Name);
                if (parameter.TypeParameterKind == TypeParameterKind.Method) w.WriteBoolean("m", true);
                break;
            case IArrayTypeSymbol array:
                w.WriteString("t", "a");
                w.WritePropertyName("e");
                TypeRef(state, array.ElementType, displays: false);
                if (array.Rank > 1) w.WriteNumber("r", array.Rank);
                break;
            case IPointerTypeSymbol pointer:
                w.WriteString("t", "ptr");
                w.WritePropertyName("e");
                TypeRef(state, pointer.PointedAtType, displays: false);
                break;
            case IDynamicTypeSymbol:
                w.WriteString("t", "dyn");
                break;
            default:
                w.WriteString("t", "other");
                w.WriteString("n", type.ToDisplayString());
                break;
        }
        if (type.NullableAnnotation == NullableAnnotation.Annotated) w.WriteBoolean("q", true);
        if (displays)
        {
            w.WriteStartArray("s");
            w.WriteStringValue(type.ToDisplayString());
            w.WriteStringValue(type.ToDisplayString(FullName));
            w.WriteStringValue(type.ToDisplayString(GloballyQualified));
            w.WriteEndArray();
        }
        w.WriteEndObject();
    }
}
