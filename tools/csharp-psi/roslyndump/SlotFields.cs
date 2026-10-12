// --fields: the Syntax.xml field of every child of a node, for the PSI accessor gate of csharp-psi.
using System.Collections;
using System.Collections.Concurrent;
using System.Reflection;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;

namespace RoslynDump;

/// <summary>
/// The field names of a node's slots come from Roslyn itself, independently of how gen-psi reads Syntax.xml: the
/// generated internal (green) class of every node type has a constructor <c>(SyntaxKind kind, &lt;one parameter per
/// field in slot order&gt;)</c>, whose parameter names are the field names in camel case (<c>else</c> for <c>Else</c>);
/// <c>bool</c> parameters (<c>isActive</c> of directives) are not slots. The value of each field is read through the
/// public property of the red node; flattened in slot order (lists element by element, separated lists with their
/// separators) the values are exactly <c>ChildNodesAndTokens()</c>, which is checked.
/// </summary>
static class SlotFields
{
    static readonly Assembly CSharp = typeof(CSharpSyntaxNode).Assembly;
    static readonly ConcurrentDictionary<Type, (string Name, PropertyInfo Property)[]> Cache = new();

    public static string[] Of(SyntaxNode node)
    {
        var slots = Cache.GetOrAdd(node.GetType(), Slots);
        var names = new List<string>();
        var values = new List<SyntaxNodeOrToken>();
        foreach (var (name, property) in slots)
        {
            var before = values.Count;
            switch (property.GetValue(node))
            {
                case null: break;
                case SyntaxToken t:
                    if (t.RawKind != 0) values.Add(t);
                    break;
                case SyntaxNode n:
                    values.Add(n);
                    break;
                case SyntaxTokenList tokens:
                    foreach (var t in tokens) values.Add(t);
                    break;
                case SyntaxNodeOrTokenList list:
                    foreach (var x in list) values.Add(x);
                    break;
                case var v when v.GetType().Name.StartsWith("SeparatedSyntaxList"):
                    var withSeparators = (SyntaxNodeOrTokenList)v.GetType().GetMethod("GetWithSeparators")!.Invoke(v, null)!;
                    foreach (var x in withSeparators) values.Add(x);
                    break;
                case IEnumerable list:
                    foreach (var x in list) values.Add((SyntaxNode)x);
                    break;
                default:
                    throw new InvalidOperationException($"{node.GetType().Name}.{name}: unexpected value {property.PropertyType}");
            }
            for (var i = before; i < values.Count; i++) names.Add(name);
        }
        var children = node.ChildNodesAndTokens();
        if (children.Count != values.Count)
            throw new InvalidOperationException($"{node.GetType().Name} at {node.SpanStart}: {children.Count} children, {values.Count} field values");
        var k = 0;
        foreach (var child in children)
        {
            var v = values[k++];
            if (child.RawKind != v.RawKind || child.FullSpan != v.FullSpan || child.IsToken != v.IsToken)
                throw new InvalidOperationException($"{node.GetType().Name} at {node.SpanStart}: child {k - 1} is {child.Kind()} {child.FullSpan}, field {names[k - 1]} gives {v.Kind()} {v.FullSpan}");
        }
        return names.ToArray();
    }

    static (string, PropertyInfo)[] Slots(Type red)
    {
        var green = CSharp.GetType("Microsoft.CodeAnalysis.CSharp.Syntax.InternalSyntax." + red.Name)
                    ?? throw new InvalidOperationException($"no internal class for {red.Name}");
        var ctor = green.GetConstructors(BindingFlags.NonPublic | BindingFlags.Public | BindingFlags.Instance)
            .Where(c => c.GetParameters() is [{ ParameterType: var k }, ..] ps && k == typeof(SyntaxKind) &&
                        ps.Skip(1).All(p => p.ParameterType.Namespace != typeof(SyntaxKind).Namespace + ".Syntax.InternalSyntax" || p.ParameterType.Name != "SyntaxFactoryContext") &&
                        ps.Skip(1).All(p => !p.ParameterType.IsArray))
            .OrderBy(c => c.GetParameters().Length)
            .First();
        return ctor.GetParameters().Skip(1)
            .Where(p => p.ParameterType != typeof(bool))
            .Select(p =>
            {
                var name = char.ToUpperInvariant(p.Name![0]) + p.Name[1..];
                var property = red.GetProperty(name, BindingFlags.Public | BindingFlags.Instance)
                               ?? throw new InvalidOperationException($"{red.Name}: no property {name}");
                return (name, property);
            })
            .ToArray();
    }
}
