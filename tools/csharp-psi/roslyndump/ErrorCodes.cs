// `errors`: Roslyn's error codes with their English message formats, for the diagnostics of csharp-psi
// (CSharpErrorCode.kt). By reflection over the internal `ErrorCode` enum and `CSharpResources`.
using System.Globalization;
using System.Reflection;
using System.Resources;
using System.Text;
using Microsoft.CodeAnalysis.CSharp;

namespace RoslynDump;

static class ErrorCodes
{
    /// <summary>One line per code: `CS<number> <EnumName> <message format>` (line breaks of the format become spaces).</summary>
    public static int Run(string? outPath)
    {
        var assembly = typeof(CSharpSyntaxTree).Assembly;
        var codes = assembly.GetType("Microsoft.CodeAnalysis.CSharp.ErrorCode", throwOnError: true)!;
        var resources = (ResourceManager)assembly.GetType("Microsoft.CodeAnalysis.CSharp.CSharpResources", throwOnError: true)!
            .GetProperty("ResourceManager", BindingFlags.Static | BindingFlags.NonPublic | BindingFlags.Public)!.GetValue(null)!;
        using var output = outPath == null
            ? new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false))
            : new StreamWriter(outPath, false, new UTF8Encoding(false));
        foreach (var value in Enum.GetValues(codes))
        {
            var name = value.ToString()!;
            var number = Convert.ToInt32(value);
            if (number <= 0) continue;
            var message = resources.GetString(name, CultureInfo.InvariantCulture);
            if (message == null) continue;
            output.WriteLine($"CS{number:D4} {name} {message.ReplaceLineEndings(" ")}");
        }
        return 0;
    }
}
