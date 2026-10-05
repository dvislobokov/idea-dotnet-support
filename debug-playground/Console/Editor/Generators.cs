using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Logging;

namespace Playground.Editor;

/// <summary>
/// Live check of source generators without the language server (0.1.77, CSHARP_PSI_MIGRATION.md task D4): the helper of the plugin
/// (CodeAnalysisHelper, built from source with the SDK of the machine on first use) runs the generators of the project on save, after a
/// build and on .NET → Code Analysis → Refresh Generated Files, and writes what they make to the caches of the IDE. Settings | .NET |
/// Analyzers and Generators → «Run source generators» (on by default). The server off (Settings | .NET | Language Server), so nothing
/// below comes from it. Three generators of the framework and of Microsoft.Extensions.Logging: regex, JSON, logging.
/// Type on the empty line under a marker, check EXPECT, undo with Ctrl+Z.
/// </summary>
public static partial class GeneratorScenarios
{
    // TYPE:sg-tree — no typing: Solution view → Console → Dependencies → .NET 9.0 → Analyzers.
    // EXPECT: nodes System.Text.RegularExpressions.Generator, System.Text.Json.SourceGeneration and
    // Microsoft.Extensions.Logging.Generators (lightning icon) next to the analyzer packages; under each the generator type and its files
    // (`RegexGenerator.g.cs`, `PlaygroundJsonContext.GeneratedOrder.g.cs`, `LoggerMessage.g.cs`...). Opening one shows the generated code
    // read-only (lock in the tab, typing asks to make it writable and is refused). NOT: the files in Console/obj or in the project tree.

    [GeneratedRegex(@"^\d{4}-\d{2}-\d{2}$")]
    private static partial Regex IsoDate();

    [LoggerMessage(Level = LogLevel.Information, Message = "Order {OrderId} shipped to {Customer}")]
    public static partial void OrderShipped(ILogger logger, int orderId, string customer);

    public static bool IsDate(string text) => IsoDate().IsMatch(text);

    public static string Serialize(GeneratedOrder order) => System.Text.Json.JsonSerializer.Serialize(order, PlaygroundJsonContext.Default.GeneratedOrder);

    public static void Members()
    {
        // TYPE:sg-member — type `var info = PlaygroundJsonContext.Default.` and Ctrl+Space.
        // EXPECT: `GeneratedOrder` and `String` (the JsonTypeInfo properties the JSON generator made) in the list; Ctrl+click on
        // `Default` in Serialize above opens `PlaygroundJsonContext.g.cs` from the caches, read-only.

        // TYPE:sg-error — type `var missing = PlaygroundJsonContext.Default.Customer;` and save (Ctrl+S).
        // EXPECT: after the save (a second at most) «CS1061: 'PlaygroundJsonContext' does not contain a definition for 'Customer'…» —
        // red although the context is partial: the generated part is known, so the plugin is sure. Before the generators have run (first
        // open of the solution) or while the file is edited and not saved, it is silent rather than wrong.

        // TYPE:sg-new — add `[JsonSerializable(typeof(int[]))]` over PlaygroundJsonContext, save, then type
        // `var ints = PlaygroundJsonContext.Default.Int32Array;`.
        // EXPECT: no red, completion offers `Int32Array` after the save (the generator ran again). Undo both edits and save.
    }
}

[JsonSerializable(typeof(GeneratedOrder))]
internal partial class PlaygroundJsonContext : JsonSerializerContext;

public sealed record GeneratedOrder(int Id, string Customer);
