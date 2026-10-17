// Licensed to the .NET Foundation under one or more agreements.
// The .NET Foundation licenses this file to you under the MIT license.

// Copied from the Razor compiler's tests (src/Razor/src/Shared/Microsoft.AspNetCore.Razor.Test.Common/Language/IntegrationTests/
// RazorDiagnosticSerializer.cs, dotnet/roslyn): the `.diagnostics.txt` format. Adapted: the namespace.

#nullable disable

using System.Diagnostics;
using Microsoft.AspNetCore.Razor.Language;

namespace RazorDump.Baselines;

public static class RazorDiagnosticSerializer
{
    public static string Serialize(RazorDiagnostic diagnostic)
    {
        return diagnostic.ToString();
    }

    /// <summary>
    /// Serializes a diagnostic that originates from a source document with a known file path,
    /// asserting that any diagnostic with a real source location carries that path. A located
    /// span with a null path can crash consumers that build a concrete location from it (e.g. the
    /// source generator's <c>Location.Create</c>); the unspecified ("zero") location may omit it.
    /// </summary>
    public static string SerializeAssertingFilePath(RazorDiagnostic diagnostic)
    {
        var span = diagnostic.Span;

        Debug.Assert(
            span.FilePath is not null || (span.LineIndex < 0 && span.CharacterIndex < 0),
            $"Diagnostic '{diagnostic.Id}' has source location ({span.LineIndex + 1},{span.CharacterIndex + 1}) but no file path.");

        return diagnostic.ToString();
    }
}
