// Licensed to the .NET Foundation under one or more agreements.
// The .NET Foundation licenses this file to you under the MIT license.

// Copied from the Razor compiler's tests (src/Razor/src/Shared/Microsoft.AspNetCore.Razor.Test.Common/Language/IntegrationTests/
// SourceMappingsSerializer.cs, dotnet/roslyn): the `.mappings.txt` format. Adapted: the namespace, a plain StringBuilder instead of the
// pool, a TextSpan built here instead of the internal AsTextSpan, and the xunit assertion (the mapped texts are equal ignoring case)
// reported through `mismatch` instead of thrown. The output is the same byte for byte.

#nullable disable

using System.Text;
using Microsoft.AspNetCore.Razor.Language;
using Microsoft.CodeAnalysis.Text;

namespace RazorDump.Baselines;

public static class SourceMappingsSerializer
{
    internal static string Serialize(RazorCSharpDocument csharpDocument, RazorSourceDocument sourceDocument, Action<string> mismatch = null)
    {
        var builder = new StringBuilder();

        foreach (var sourceMapping in csharpDocument.SourceMappingsSortedByGenerated)
        {
            builder.Append("Source Location: ");
            var sourceCode = GetCodeForSpan(sourceMapping.OriginalSpan, sourceDocument.Text);
            AppendMappingLocation(builder, sourceMapping.OriginalSpan, sourceCode);

            builder.Append("Generated Location: ");
            var generatedCode = GetCodeForSpan(sourceMapping.GeneratedSpan, csharpDocument.Text);
            AppendMappingLocation(builder, sourceMapping.GeneratedSpan, generatedCode);

            if (!string.Equals(sourceCode, generatedCode, StringComparison.OrdinalIgnoreCase))
            {
                mismatch?.Invoke($"{sourceMapping.OriginalSpan} -> {sourceMapping.GeneratedSpan}");
            }
            builder.AppendLine();
        }

        return builder.ToString();
    }

    private static void AppendMappingLocation(StringBuilder builder, SourceSpan location, string content)
    {
        builder
            .AppendLine(location.ToString())
            .Append('|')
            .Append(content)
            .AppendLine("|");
    }

    private static string GetCodeForSpan(SourceSpan location, SourceText content)
    {
        return content.ToString(new TextSpan(location.AbsoluteIndex, location.Length));
    }
}
