using Grpc.Core;
using Grpc.Net.Compression;
using Microsoft.Extensions.Hosting;

namespace Playground.Grpc;

/// <summary>
/// Live check of Go to Declaration into the original sources of a library (0.1.116): the portable PDBs that Grpc.Net.* ship next to their dlls
/// in the NuGet cache carry Source Link to raw.githubusercontent.com. The server off, Settings | .NET | Language Server → «Navigate to
/// Source Link and embedded sources» on (the default), the network reachable. Ctrl+Click on a name under a marker, check EXPECT.
/// Journal: .NET | Plugin Logs, category «sourcelink».
/// </summary>
public static class SourceLinkScenarios
{
    // TYPE:sourcelink-type — Ctrl+Click on `GzipCompressionProvider` below (the first time: a progress «Looking for the source of
    // GzipCompressionProvider» in the status bar for a second or two).
    // EXPECT: a tab «GzipCompressionProvider.cs [Grpc.Net.Common]» with the real source of grpc-dotnet (comments, `///` docs, the bodies), the
    // caret on `class GzipCompressionProvider`, above it the banner «Navigated to source from Source Link:
    // https://raw.githubusercontent.com/grpc/grpc-dotnet/<sha>/src/Grpc.Net.Common/Compression/GzipCompressionProvider.cs. Read-only» with
    // «Open in Browser»; typing into it changes nothing. Back (Ctrl+Alt+Left) returns here, Forward opens the same tab again.
    // NOT: the «Decompiled from Grpc.Net.Common» view (that is the fallback), nor the metadata view without bodies.
    public static ICompressionProvider Gzip() => new GzipCompressionProvider(System.IO.Compression.CompressionLevel.Fastest);

    // TYPE:sourcelink-member — Ctrl+Click on `CreateCompressionStream` below.
    // EXPECT: the same tab (no download the second time), the caret on the method `CreateCompressionStream(Stream stream, CompressionLevel?
    // compressionLevel)`. Then Ctrl+Click on `Status` and on `StatusCode`: «Status.cs [Grpc.Core.Api]» from the same repository, the caret on
    // the struct and on the property.
    public static Stream Compress(Stream stream) => new GzipCompressionProvider(System.IO.Compression.CompressionLevel.Optimal).CreateCompressionStream(stream, null);

    public static StatusCode CodeOf(Status status) => status.StatusCode;

    // TYPE:sourcelink-fallback — Ctrl+Click on `Host` below: Microsoft.Extensions.Hosting of the shared framework has no PDB at all.
    // EXPECT: the decompiled view «Decompiled from Microsoft.Extensions.Hosting …» (or the metadata view without a decompiler) as before
    // 0.1.116, at once; in the journal one line «No source location of T:Microsoft.Extensions.Hosting.Host in Microsoft.Extensions.Hosting.dll:
    // no PDB …», and no more lines for this assembly on the next clicks.
    public static IHostBuilder Builder() => Host.CreateDefaultBuilder();

    // TYPE:sourcelink-off — Settings | .NET | Language Server, uncheck «Navigate to Source Link and embedded sources», OK; Ctrl+Click on
    // `GzipCompressionProvider` in Gzip() above.
    // EXPECT: the decompiled view (or the metadata view), not the Source Link tab; check the option again — the Source Link tab is back at once
    // (from the cache on disk, no download).
    // TYPE:sourcelink-offline — disconnect the network (or set a wrong proxy in Settings | Appearance & Behavior | System Settings | HTTP Proxy),
    // then Ctrl+Click on `Status` in CodeOf() above after `Status.cs` has never been opened.
    // EXPECT: after the progress, the decompiled view of Status; in the journal «… could not be downloaded from https://raw.githubusercontent.com/…».
    // The IDE never freezes.
}
