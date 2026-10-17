// The tag helper descriptors as JSON (tag-helpers, cases-codegen --tag-helpers-out): everything a TagHelperDescriptor holds, so razor-psi's
// model is rebuilt exactly from it (razor-psi-core: syntax/TagHelperDescriptorJson.kt reads and writes the same text). Format: README.md.
using System.Collections;
using System.Globalization;
using System.Reflection;
using System.Text;
using Microsoft.AspNetCore.Razor.Language;

namespace RazorDump;

static class TagHelperJson
{
    const BindingFlags Any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance;

    /// <summary>One array of descriptors in the collection's order, indented by two spaces, LF line breaks, a final one.</summary>
    public static string Serialize(IEnumerable<TagHelperDescriptor> descriptors)
    {
        var output = new StringBuilder();
        Write(output, descriptors.Select(Descriptor).ToList<object?>(), 0);
        return output.Append('\n').ToString();
    }

    static Obj Descriptor(TagHelperDescriptor d) => new()
    {
        ["kind"] = d.Kind.ToString(),
        ["runtimeKind"] = d.RuntimeKind.ToString(),
        ["name"] = d.Name,
        ["assemblyName"] = d.AssemblyName,
        ["displayName"] = d.DisplayName,
        ["typeName"] = d.TypeName,
        ["typeNamespace"] = d.TypeNamespace,
        ["typeNameIdentifier"] = d.TypeNameIdentifier,
        ["documentation"] = d.Documentation,
        ["tagOutputHint"] = d.TagOutputHint,
        ["caseSensitive"] = d.CaseSensitive,
        ["isFullyQualifiedNameMatch"] = (bool)Get(d, "IsFullyQualifiedNameMatch")!,
        ["classifyAttributesOnly"] = d.ClassifyAttributesOnly,
        ["tagMatchingRules"] = d.TagMatchingRules.Select(Rule).ToList<object?>(),
        ["boundAttributes"] = d.BoundAttributes.Select(Attribute).ToList<object?>(),
        ["allowedChildTags"] = d.AllowedChildTags.Select(ChildTag).ToList<object?>(),
        ["metadata"] = Metadata(d.Metadata),
        ["diagnostics"] = Diagnostics(d.Diagnostics),
    };

    static Obj Rule(TagMatchingRuleDescriptor r) => new()
    {
        ["tagName"] = r.TagName,
        ["parentTag"] = r.ParentTag,
        ["tagStructure"] = r.TagStructure.ToString(),
        ["caseSensitive"] = r.CaseSensitive,
        ["attributes"] = r.Attributes.Select(RequiredAttribute).ToList<object?>(),
        ["diagnostics"] = Diagnostics(r.Diagnostics),
    };

    static Obj RequiredAttribute(RequiredAttributeDescriptor a) => new()
    {
        ["name"] = a.Name,
        ["nameComparison"] = a.NameComparison.ToString(),
        ["value"] = a.Value,
        ["valueComparison"] = a.ValueComparison.ToString(),
        ["caseSensitive"] = a.CaseSensitive,
        ["isDirectiveAttribute"] = a.IsDirectiveAttribute,
        ["diagnostics"] = Diagnostics(a.Diagnostics),
    };

    static Obj Attribute(BoundAttributeDescriptor a) => new()
    {
        ["name"] = a.Name,
        ["propertyName"] = a.PropertyName,
        ["typeName"] = a.TypeName,
        ["indexerNamePrefix"] = a.IndexerNamePrefix,
        ["indexerTypeName"] = a.IndexerTypeName,
        ["documentation"] = a.Documentation,
        ["displayName"] = a.DisplayName,
        ["containingType"] = a.ContainingType,
        ["caseSensitive"] = a.CaseSensitive,
        ["hasIndexer"] = a.HasIndexer,
        ["isEnum"] = a.IsEnum,
        ["isEditorRequired"] = (bool)Get(a, "IsEditorRequired")!,
        ["isDirectiveAttribute"] = a.IsDirectiveAttribute,
        ["isWeaklyTyped"] = a.IsWeaklyTyped,
        ["parameters"] = a.Parameters.Select(Parameter).ToList<object?>(),
        ["metadata"] = Metadata(a.Metadata),
        ["diagnostics"] = Diagnostics(a.Diagnostics),
    };

    static Obj Parameter(BoundAttributeParameterDescriptor p) => new()
    {
        ["name"] = p.Name,
        ["propertyName"] = p.PropertyName,
        ["typeName"] = p.TypeName,
        ["documentation"] = p.Documentation,
        ["caseSensitive"] = p.CaseSensitive,
        ["isEnum"] = p.IsEnum,
        ["bindAttributeGetSet"] = p.BindAttributeGetSet,
        ["diagnostics"] = Diagnostics(p.Diagnostics),
    };

    static Obj ChildTag(AllowedChildTagDescriptor t) => new()
    {
        ["name"] = t.Name,
        ["displayName"] = t.DisplayName,
        ["diagnostics"] = Diagnostics(t.Diagnostics),
    };

    /// <summary>
    /// The properties of each metadata record, as the compiler of dotnet/roslyn main declares them (Language/Components/*Metadata.cs,
    /// Mvc/ViewComponentMetadata.cs): the JSON has these keys whatever the installed compiler has, a property it lacks (10.0.401: PropertyMetadata's
    /// AcceptsStringLiteral and AcceptsAssetPath, ComponentMetadata's AcceptsUnmatchedAttributes) written as the record's default; one it has
    /// beyond them stops the dump. ViewComponentMetadata's internal OriginalTypeNameObject is flat, as a descriptor's type name.
    /// </summary>
    static readonly Dictionary<string, string[]> MetadataProperties = new()
    {
        ["None"] = [],
        ["TypeParameter"] = ["IsCascading", "Constraints", "NameWithAttributes"],
        ["Property"] = ["GloballyQualifiedTypeName", "IsChildContent", "IsEventCallback", "IsDelegateSignature", "IsDelegateWithAwaitableResult", "IsGenericTyped",
            "IsInitOnlyProperty", "AcceptsStringLiteral", "AcceptsAssetPath"],
        ["ChildContentParameter"] = [],
        ["Bind"] = ["IsFallback", "ValueAttribute", "ChangeAttribute", "ExpressionAttribute", "TypeAttribute", "IsInvariantCulture", "Format"],
        ["Component"] = ["IsGeneric", "HasRenderModeDirective", "AcceptsUnmatchedAttributes"],
        ["EventHandler"] = ["EventArgsType"],
        ["ViewComponent"] = ["Name", "OriginalTypeNameObject"],
        ["AssetPath"] = ["Element", "Attribute"],
    };

    /// <summary>The metadata properties the installed compiler lacks that a dump wrote as defaults (<c>Component.AcceptsUnmatchedAttributes</c>).</summary>
    public static readonly SortedSet<string> Absent = new(StringComparer.Ordinal);

    /// <summary>The kind and the properties of <see cref="MetadataProperties"/>, camel-cased, in that order.</summary>
    static Obj Metadata(MetadataObject metadata)
    {
        var kind = metadata.Kind.ToString();
        var result = new Obj { ["kind"] = kind };
        var names = MetadataProperties.TryGetValue(kind, out var known) ? known : throw new InvalidOperationException($"metadata kind {kind}");
        var declared = metadata.GetType().GetProperties(Any | BindingFlags.DeclaredOnly)
            .Where(p => p.GetIndexParameters().Length == 0 && p.Name is not ("EqualityContract" or "HasDefaultValue" or "Kind" or "OriginalTypeName"))
            .ToDictionary(p => p.Name);
        if (declared.Keys.FirstOrDefault(n => !names.Contains(n)) is { } unknown)
            throw new InvalidOperationException($"{metadata.GetType().Name}.{unknown}: a metadata property the dump does not know");
        foreach (var name in names)
        {
            var value = declared.TryGetValue(name, out var property) ? property.GetValue(metadata) : null;
            if (name == "OriginalTypeNameObject")
            {
                result["originalTypeName"] = value == null ? null : Get(value, "FullName");
                result["originalTypeNamespace"] = value == null ? null : Get(value, "Namespace");
                result["originalTypeNameIdentifier"] = value == null ? null : Get(value, "Name");
                continue;
            }
            // A property the installed compiler lacks: the record's default (false for the flags, null for the strings).
            if (property == null) Absent.Add(kind + "." + name);
            result[CamelCase(name)] = property == null ? (name.StartsWith("Is") || name.StartsWith("Has") || name.StartsWith("Accepts") ? false : null)
                : value switch { null or string or bool => value, _ => throw new InvalidOperationException($"metadata value of type {value.GetType()}") };
        }
        return result;
    }

    static List<object?> Diagnostics(IEnumerable<RazorDiagnostic> diagnostics) => diagnostics.Select(d => (object?)new Obj
    {
        ["id"] = d.Id,
        ["severity"] = d.Severity.ToString(),
        ["warningLevel"] = d.WarningLevel,
        ["message"] = d.GetMessage(CultureInfo.InvariantCulture),
        ["span"] = new Obj
        {
            ["filePath"] = d.Span.FilePath,
            ["absoluteIndex"] = d.Span.AbsoluteIndex,
            ["lineIndex"] = d.Span.LineIndex,
            ["characterIndex"] = d.Span.CharacterIndex,
            ["length"] = d.Span.Length,
            ["lineCount"] = d.Span.LineCount,
            ["endCharacterIndex"] = d.Span.EndCharacterIndex,
        },
    }).ToList();

    static object? Get(object target, string name)
    {
        for (var type = target.GetType(); type != null; type = type.BaseType)
        {
            if (type.GetProperty(name, Any | BindingFlags.DeclaredOnly) is { } property) return property.GetValue(target);
        }
        throw new InvalidOperationException($"{target.GetType()} has no property {name}");
    }

    static string CamelCase(string name) => char.ToLowerInvariant(name[0]) + name[1..];

    /// <summary>A JSON object: its keys in insertion order.</summary>
    sealed class Obj : IEnumerable<KeyValuePair<string, object?>>
    {
        readonly List<KeyValuePair<string, object?>> entries = [];
        public object? this[string key] { set => entries.Add(new(key, value)); }
        public int Count => entries.Count;
        public IEnumerator<KeyValuePair<string, object?>> GetEnumerator() => entries.GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    }

    /// <summary>
    /// The printer razor-psi's TagHelperDescriptorJson repeats: two spaces of indentation per level, <c>"key": value</c>, an empty array or
    /// object as <c>[]</c> / <c>{}</c>; in strings only <c>"</c>, <c>\</c> and the control characters are escaped.
    /// </summary>
    static void Write(StringBuilder output, object? value, int indent)
    {
        switch (value)
        {
            case null: output.Append("null"); break;
            case bool b: output.Append(b ? "true" : "false"); break;
            case int i: output.Append(i.ToString(CultureInfo.InvariantCulture)); break;
            case string s: WriteString(output, s); break;
            case Obj o:
                if (o.Count == 0) { output.Append("{}"); break; }
                output.Append("{\n");
                var first = true;
                foreach (var (key, item) in o)
                {
                    if (!first) output.Append(",\n");
                    first = false;
                    output.Append(' ', indent + 2);
                    WriteString(output, key);
                    output.Append(": ");
                    Write(output, item, indent + 2);
                }
                output.Append('\n').Append(' ', indent).Append('}');
                break;
            case List<object?> list:
                if (list.Count == 0) { output.Append("[]"); break; }
                output.Append("[\n");
                for (var k = 0; k < list.Count; k++)
                {
                    if (k > 0) output.Append(",\n");
                    output.Append(' ', indent + 2);
                    Write(output, list[k], indent + 2);
                }
                output.Append('\n').Append(' ', indent).Append(']');
                break;
            default: throw new InvalidOperationException($"JSON value of type {value.GetType()}");
        }
    }

    static void WriteString(StringBuilder output, string s)
    {
        output.Append('"');
        foreach (var c in s)
        {
            switch (c)
            {
                case '"': output.Append("\\\""); break;
                case '\\': output.Append("\\\\"); break;
                case '\n': output.Append("\\n"); break;
                case '\r': output.Append("\\r"); break;
                case '\t': output.Append("\\t"); break;
                case '\b': output.Append("\\b"); break;
                case '\f': output.Append("\\f"); break;
                default:
                    if (c < ' ') output.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
                    else output.Append(c);
                    break;
            }
        }
        output.Append('"');
    }
}
