// Where the source of a type or a member of a library is (sourcelink/LibrarySources), as Rider's "navigate to sources from Source Link
// and embedded sources": the portable PDB of the assembly (next to it, as NuGet packages ship it, or embedded in the dll) names the
// document of each method's sequence points, keeps the source itself when it was embedded, and carries the Source Link JSON that maps the
// paths of the build machine to URLs. The plugin downloads, checks the hash and opens the file; this only reads the PDB.
//
//   sourceLocation  {assembly, typeName, memberId?}
//                   → {assembly, assemblyName, pdb, document, line, column, hashAlgorithm, hash, sourceLink?, embedded?, memberFound}
//
// `line` and `column` are 1-based, the first sequence point of the member (of the type: the lowest line of its methods in the document most
// of them are in): the plugin looks for the declaration above it. `hashAlgorithm`: `SHA256` | `SHA1` | `MD5` | a GUID; `hash`: hex.
// `sourceLink`: the JSON of the PDB as it is; `embedded`: the text of the document when the PDB holds it. An error without a PDB, a document
// or any sequence point: the plugin falls back to the decompiler.

using System.Collections.Concurrent;
using System.IO.Compression;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Text;
using ICSharpCode.Decompiler.Documentation;
using ICSharpCode.Decompiler.Metadata;
using ICSharpCode.Decompiler.TypeSystem;

namespace DotNetSupport.Helpers.DotNetHelper;

public sealed record SourceLocation(string Assembly, string AssemblyName, string Pdb, string Document, int Line, int Column, string HashAlgorithm, string Hash,
    string? SourceLink, string? Embedded, bool MemberFound);

internal static class SourceLocator
{
    private static readonly Guid SourceLinkKind = new("CC110556-A091-4D38-9FEC-25AB9A351A6A");
    private static readonly Guid EmbeddedSourceKind = new("0E8A571B-6926-466E-B4AD-8AB04611F5FE");
    private static readonly Guid Sha1 = new("ff1816ec-aa5e-4d10-87f7-6f4963833460");
    private static readonly Guid Sha256 = new("8829d00f-11b8-4213-878b-770e8597ac16");
    private static readonly Guid Md5 = new("406ea660-64cf-4c82-b6f0-42d48172a799");

    private static readonly ConcurrentDictionary<string, LoadedPdb> Cache = new(OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal);
    private const int CacheSize = 8;
    /** Larger than any real source file: the cap on an embedded one, decompressed. */
    private const int MaxEmbeddedSource = 16 * 1024 * 1024;

    public static SourceLocation Locate(string assembly, string typeName, string? memberId, CancellationToken token) =>
        TypeDecompiler.WithDefinition(assembly, typeName, (definition, module, path) =>
        {
            token.ThrowIfCancellationRequested();
            var pdb = Load(module, path);
            if (pdb.Reader == null) throw new HelperException(pdb.Problem ?? $"no PDB for {Path.GetFileName(path)}");
            var metadata = module.Metadata;
            var typeHandle = (TypeDefinitionHandle)definition.MetadataToken;

            var found = false;
            Point? point = null;
            if (memberId != null && !memberId.StartsWith("T:"))
            {
                var handle = MemberHandle(definition, memberId, out found);
                if (handle != null) point = pdb.FirstPoint(handle.Value);
            }
            point ??= TypePoint(pdb, metadata, typeHandle) ?? throw new HelperException($"the PDB of {Path.GetFileName(path)} has no sequence points of {definition.ReflectionName}");

            var document = pdb.Reader.GetDocument(point.Value.Document);
            var name = pdb.Reader.GetString(document.Name);
            var algorithm = pdb.Reader.GetGuid(document.HashAlgorithm);
            var hash = Convert.ToHexString(pdb.Reader.GetBlobBytes(document.Hash)).ToLowerInvariant();
            var embedded = pdb.Embedded(point.Value.Document);
            var assemblyName = metadata.GetString(metadata.GetAssemblyDefinition().Name);
            return new SourceLocation(path, assemblyName, pdb.Name, name, point.Value.Line, point.Value.Column, AlgorithmName(algorithm), hash, pdb.SourceLink, embedded, found);
        });

    private static string AlgorithmName(Guid guid) => guid == Sha256 ? "SHA256" : guid == Sha1 ? "SHA1" : guid == Md5 ? "MD5" : guid.ToString();

    /** The method whose sequence points stand for [memberId]: a method or constructor itself, the getter (else setter) of a property, the adder of an event. */
    private static MethodDefinitionHandle? MemberHandle(ITypeDefinition definition, string memberId, out bool found)
    {
        found = false;
        IMember? member = null;
        var plainId = memberId.Contains('(') ? memberId[..memberId.IndexOf('(')] : memberId;
        foreach (var candidate in definition.Members)
        {
            string id;
            try { id = IdStringProvider.GetIdString(candidate); }
            catch (Exception) { continue; }
            if (id == memberId) { member = candidate; found = true; break; }
            // another overload (the index of the plugin and the decompiler do not always spell a signature alike): better than the type
            if (member == null && (id.Contains('(') ? id[..id.IndexOf('(')] : id) == plainId) member = candidate;
        }
        return member switch
        {
            IMethod method => Handle(method),
            IProperty property => Handle(property.Getter) ?? Handle(property.Setter),
            IEvent @event => Handle(@event.AddAccessor) ?? Handle(@event.RemoveAccessor),
            _ => null,
        };
    }

    private static MethodDefinitionHandle? Handle(IMethod? method) =>
        method?.MetadataToken is { IsNil: false, Kind: HandleKind.MethodDefinition } token ? (MethodDefinitionHandle)token : null;

    /** The lowest line of the methods of the type (its nested types' when it has no bodies of its own) in the document most of them are in. */
    private static Point? TypePoint(LoadedPdb pdb, MetadataReader metadata, TypeDefinitionHandle typeHandle)
    {
        var points = new List<Point>();
        void Collect(TypeDefinitionHandle handle)
        {
            foreach (var method in metadata.GetTypeDefinition(handle).GetMethods())
            {
                if (pdb.FirstPoint(method) is { } point) points.Add(point);
            }
        }
        Collect(typeHandle);
        if (points.Count == 0) foreach (var nested in metadata.GetTypeDefinition(typeHandle).GetNestedTypes()) Collect(nested);
        if (points.Count == 0) return null;
        var document = points.GroupBy(p => p.Document).OrderByDescending(g => g.Count()).First().Key;
        var first = points.Where(p => p.Document == document).MinBy(p => p.Line);
        return first with { Column = 1 };
    }

    private static LoadedPdb Load(PEFile module, string path)
    {
        var stamp = Stamp(path);
        if (Cache.TryGetValue(path, out var cached) && cached.Stamp == stamp)
        {
            cached.LastUse = DateTime.UtcNow;
            return cached;
        }
        var loaded = new LoadedPdb(module, path, stamp);
        Cache[path] = loaded;
        foreach (var old in Cache.OrderByDescending(e => e.Value.LastUse).Skip(CacheSize).ToList()) Cache.TryRemove(old.Key, out _);
        return loaded;
    }

    private static (long, long) Stamp(string path)
    {
        var info = new FileInfo(path);
        return info.Exists ? (info.LastWriteTimeUtc.Ticks, info.Length) : (0, 0);
    }

    internal readonly record struct Point(DocumentHandle Document, int Line, int Column);

    /** The portable PDB of an assembly: its reader, the Source Link JSON, and `MoveNext` of each state machine for the async methods and iterators. */
    private sealed class LoadedPdb
    {
        public readonly (long, long) Stamp;
        public readonly MetadataReader? Reader;
        public readonly string Name;
        public readonly string? Problem;
        public readonly string? SourceLink;
        public DateTime LastUse = DateTime.UtcNow;
        private readonly Dictionary<MethodDefinitionHandle, MethodDefinitionHandle> moveNext = new();

        public LoadedPdb(PEFile module, string path, (long, long) stamp)
        {
            Stamp = stamp;
            string? name;
            (Reader, _, name, Problem) = IlViewer.ReadPdb(module.Reader, path);
            Name = name ?? "";
            if (Reader == null) return;
            foreach (var handle in Reader.GetCustomDebugInformation(EntityHandle.ModuleDefinition))
            {
                var info = Reader.GetCustomDebugInformation(handle);
                if (Reader.GetGuid(info.Kind) == SourceLinkKind) SourceLink = Encoding.UTF8.GetString(Reader.GetBlobBytes(info.Value));
            }
            foreach (var handle in Reader.MethodDebugInformation)
            {
                var kickoff = Reader.GetMethodDebugInformation(handle).GetStateMachineKickoffMethod();
                if (!kickoff.IsNil) moveNext[kickoff] = handle.ToDefinitionHandle();
            }
        }

        /** The first visible sequence point of [method]; of `MoveNext` of its state machine for an async method or an iterator. */
        public Point? FirstPoint(MethodDefinitionHandle method)
        {
            if (Reader == null) return null;
            var info = Reader.GetMethodDebugInformation(method.ToDebugInformationHandle());
            if (info.SequencePointsBlob.IsNil)
            {
                return moveNext.TryGetValue(method, out var mover) ? FirstPoint(mover) : null;
            }
            foreach (var point in info.GetSequencePoints())
            {
                if (!point.IsHidden && !point.Document.IsNil) return new Point(point.Document, point.StartLine, point.StartColumn);
            }
            return null;
        }

        /** The text of [document] when the PDB embeds it (`<EmbedAllSources>`, `<EmbedUntrackedSources>`): raw or deflated, per the format word. */
        public string? Embedded(DocumentHandle document)
        {
            if (Reader == null) return null;
            foreach (var handle in Reader.GetCustomDebugInformation(document))
            {
                var info = Reader.GetCustomDebugInformation(handle);
                if (Reader.GetGuid(info.Kind) != EmbeddedSourceKind) continue;
                var blob = Reader.GetBlobReader(info.Value);
                var format = blob.ReadInt32();
                var bytes = blob.ReadBytes(blob.RemainingBytes);
                // the size is the PDB's word: a crafted one must not make the helper allocate gigabytes
                if (format > MaxEmbeddedSource || (format == 0 && bytes.Length > MaxEmbeddedSource)) return null;
                if (format > 0)
                {
                    using var deflate = new DeflateStream(new MemoryStream(bytes), CompressionMode.Decompress);
                    var raw = new byte[format];
                    deflate.ReadExactly(raw);
                    bytes = raw;
                }
                using var text = new StreamReader(new MemoryStream(bytes), Encoding.UTF8, true);
                return text.ReadToEnd();
            }
            return null;
        }
    }
}
