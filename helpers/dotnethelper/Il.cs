// The IL viewer of the plugin (il/IlHelperSource), as the IL Viewer of Rider: the IL of the code at a line of a source file, taken from the
// last build. ICSharpCode.Decompiler (the engine of ILSpy) disassembles in the ildasm format; the portable PDB says which bodies hold the line.
//
//   il  {assembly, file, line, typeName?, memberName?}
//       → {assembly, assemblyModified, pdb?, bodies: [{name, kind, text, atCaret, mapping: [{textLine, offset, startLine, startColumn, endLine, endColumn}]}], warning?}
//
// `kind`: method | stateMachine | lambda | localFunction | type | field. `pdb`: the path of the PDB file, `embedded`, or null without one.
// The assembly and its PDB are read into memory, so the build that overwrites them is never blocked; what is read is kept by path and time.

using System.Collections.Concurrent;
using System.Collections.Immutable;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Text;
using System.Text.RegularExpressions;
using ICSharpCode.Decompiler;
using ICSharpCode.Decompiler.DebugInfo;
using ICSharpCode.Decompiler.Disassembler;
using ICSharpCode.Decompiler.Metadata;
using DecompilerSequencePoint = ICSharpCode.Decompiler.DebugInfo.SequencePoint;

namespace DotNetSupport.Helpers.DotNetHelper;

public sealed record IlLineMapping(int TextLine, int Offset, int StartLine, int StartColumn, int EndLine, int EndColumn);

public sealed record IlBody(string Name, string Kind, string Text, bool AtCaret, List<IlLineMapping> Mapping);

public sealed record IlAnswer(string Assembly, long AssemblyModified, string? Pdb, List<IlBody> Bodies, string? Warning);

internal static partial class IlViewer
{
    private const int CacheSize = 16;
    private static readonly ConcurrentDictionary<string, LoadedAssembly> Cache = new(OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal);

    public static IlAnswer Il(string assembly, string file, int line, string? typeName, string? memberName, CancellationToken token)
    {
        var loaded = Load(Path.GetFullPath(assembly));
        lock (loaded)
        {
            token.ThrowIfCancellationRequested();
            return new Request(loaded, file, line, typeName, memberName, token).Answer();
        }
    }

    // ------------------------------------------------------------------------------------------------ loading and the cache

    private static LoadedAssembly Load(string path)
    {
        if (!File.Exists(path)) throw new HelperException($"{path} is not there: build the project first");
        var stamp = Stamp(path);
        if (Cache.TryGetValue(path, out var cached) && cached.Stamp == stamp && cached.PdbStamp == Stamp(cached.PdbFile))
        {
            cached.LastUse = DateTime.UtcNow;
            return cached;
        }
        LoadedAssembly loaded;
        try { loaded = new LoadedAssembly(path, stamp); }
        catch (Exception e) when (e is BadImageFormatException or InvalidOperationException or EndOfStreamException)
        {
            throw new HelperException($"{Path.GetFileName(path)} cannot be read ({e.Message}): is it being built right now?");
        }
        Cache[path] = loaded;
        foreach (var old in Cache.OrderByDescending(e => e.Value.LastUse).Skip(CacheSize).ToList()) Cache.TryRemove(old.Key, out _);
        return loaded;
    }

    /** The time and size of [path]; a build that rewrites the file changes it. 0 for a file that is not there. */
    private static (long, long) Stamp(string? path)
    {
        if (path == null) return (0, 0);
        var info = new FileInfo(path);
        return info.Exists ? (info.LastWriteTimeUtc.Ticks, info.Length) : (0, 0);
    }

    /** The whole file, opened so that a writer or a delete at the same moment is not refused: the build of the project must never fail on us. */
    internal static byte[] ReadShared(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        var bytes = new byte[stream.Length];
        stream.ReadExactly(bytes);
        return bytes;
    }

    // ------------------------------------------------------------------------------------------------ one request

    private sealed class Request(LoadedAssembly loaded, string file, int line, string? typeName, string? memberName, CancellationToken token)
    {
        private readonly MetadataReader metadata = loaded.Module.Metadata;

        public IlAnswer Answer()
        {
            var (bodies, why) = FromPdb();
            if (bodies.Count > 0) return Result(bodies, null);
            return ByName(why);
        }

        private IlAnswer Result(List<IlBody> bodies, string? warning) =>
            new(loaded.Path, new DateTimeOffset(loaded.Stamp.Item1, TimeSpan.Zero).ToUnixTimeMilliseconds(), loaded.PdbName, bodies, warning);

        /** The bodies whose visible sequence points in the file span the line; else why the PDB gave nothing. */
        private (List<IlBody>, string) FromPdb()
        {
            var assemblyName = Path.GetFileName(loaded.Path);
            if (loaded.Pdb == null) return ([], loaded.PdbProblem ?? $"no PDB for {assemblyName}");
            var documents = loaded.Documents(file);
            if (documents.Count == 0) return ([], $"the PDB of {assemblyName} has no {Path.GetFileName(file)} (is it compiled into this assembly?)");

            var candidates = new List<Candidate>();
            foreach (var (method, points) in loaded.Points)
            {
                var inFile = points.Where(p => documents.Contains(p.Document)).ToList();
                if (inFile.Count == 0) continue;
                var first = inFile.Min(p => p.StartLine);
                var last = inFile.Max(p => p.EndLine);
                if (line < first || line > last) continue;
                candidates.Add(new Candidate(method, first, last, inFile));
            }
            // a constructor whose field initializers lie above and below a method spans that method: it is not the code of the line
            var spanning = candidates.Where(c => !c.Touches(line) && c.GapAround(line) is (int above, int below)
                && candidates.Any(other => other != c && Kind(other.Method) is "method" or "stateMachine" && other.First > above && other.Last < below)).ToList();
            candidates.RemoveAll(spanning.Contains);
            if (candidates.Count == 0) return ([], $"the PDB has no code at line {line} (a field, a type header, a member without a body)");
            if (memberName != null && candidates.All(c => !c.Touches(line) && IsConstructor(c.Method)) && NamesDataMember())
                return ([], $"`{memberName}` has no code of its own at line {line}");

            var innermost = candidates.OrderBy(c => c.Last - c.First).ThenByDescending(c => c.Touches(line)).ThenByDescending(c => Kind(c.Method) == "stateMachine").First();
            var methods = new List<(MethodDefinitionHandle Method, int Span)>();
            foreach (var candidate in candidates.OrderByDescending(c => c.Last - c.First))
            {
                if (loaded.Kickoff.TryGetValue(candidate.Method, out var kickoff)) methods.Add((kickoff, candidate.Last - candidate.First));
                methods.Add((candidate.Method, candidate.Last - candidate.First));
                if (loaded.MoveNext.TryGetValue(candidate.Method, out var moveNext)) methods.Add((moveNext, candidate.Last - candidate.First));
            }
            var bodies = methods.Select(m => m.Method).Distinct().Select(m => Body(m, Kind(m), m == innermost.Method, documents)).ToList();
            return (bodies, "");
        }

        /** By [typeName] and [memberName] of the declaration scanner: every overload of the member, else the whole type. */
        private IlAnswer ByName(string why)
        {
            if (typeName == null) return Result([], $"No IL at line {line}: {why}, and the declaration around it is not known.");
            var type = loaded.FindType(typeName);
            if (type == null) return Result([], $"No IL at line {line}: {why}, and {Path.GetFileName(loaded.Path)} has no type {typeName}: is the build up to date?");
            var documents = loaded.Pdb == null ? new HashSet<DocumentHandle>() : loaded.Documents(file);
            if (memberName != null)
            {
                var methods = Members(type.Value, memberName);
                if (methods.Count > 0)
                {
                    var bodies = new List<IlBody>();
                    foreach (var method in methods)
                    {
                        bodies.Add(Body(method, "method", bodies.Count == 0, documents));
                        if (loaded.MoveNext.TryGetValue(method, out var moveNext)) bodies.Add(Body(moveNext, "stateMachine", false, documents));
                    }
                    var overloads = methods.Count > 1 ? $"all {methods.Count} methods named `{memberName}`" : $"`{memberName}`";
                    return Result(bodies, $"{Capitalize(why)}, so the IL is found by name: {overloads}.");
                }
                // a field has no code: its declaration is all there is (an initializer is in the constructor, which the PDB finds on its line)
                if (Field(type.Value, memberName) is { } field)
                    return Result([FieldBody(type.Value, field)], $"`{memberName}` is a field: it has no IL of its own, only this declaration. An initializer would be in the constructor.");
                why += $"; {TypeDisplayName(type.Value)} has no method `{memberName}`";
            }
            return Result([TypeBody(type.Value)], $"{Capitalize(why)}, so the IL is found by name: the whole type {TypeDisplayName(type.Value)}.");
        }

        /** Methods named [name] in [type]: the accessors of a property or an event of that name, the constructors for the name of the type. */
        private List<MethodDefinitionHandle> Members(TypeDefinitionHandle type, string name)
        {
            var definition = metadata.GetTypeDefinition(type);
            var isTypeName = StripArity(metadata.GetString(definition.Name)) == name;
            return definition.GetMethods().Where(h =>
            {
                var method = metadata.GetString(metadata.GetMethodDefinition(h).Name);
                if (method == name || method.EndsWith("." + name)) return true;
                if (isTypeName && method is ".ctor" or ".cctor") return true;
                foreach (var prefix in AccessorPrefixes)
                {
                    if (method.StartsWith(prefix) && (method[prefix.Length..] == name || method[prefix.Length..].EndsWith("." + name))) return true;
                }
                return false;
            }).ToList();
        }

        /** [memberName] is a field, a property without code or an event of [typeName], and no method: a data member. */
        private bool NamesDataMember()
        {
            if (typeName == null || memberName == null || loaded.FindType(typeName) is not { } type) return false;
            var definition = metadata.GetTypeDefinition(type);
            if (definition.GetMethods().Any(h => metadata.GetString(metadata.GetMethodDefinition(h).Name) == memberName)) return false;
            return definition.GetFields().Any(h => metadata.GetString(metadata.GetFieldDefinition(h).Name) == memberName)
                || definition.GetProperties().Any(h => metadata.GetString(metadata.GetPropertyDefinition(h).Name) == memberName)
                || definition.GetEvents().Any(h => metadata.GetString(metadata.GetEventDefinition(h).Name) == memberName);
        }

        private bool IsConstructor(MethodDefinitionHandle method) => metadata.GetString(metadata.GetMethodDefinition(method).Name) is ".ctor" or ".cctor";

        // -------------------------------------------------------------------------------------------- disassembly

        private IlBody Body(MethodDefinitionHandle method, string kind, bool atCaret, IReadOnlySet<DocumentHandle> documents)
        {
            var text = Disassemble(d => d.DisassembleMethod(loaded.Module, method));
            var labels = new Dictionary<int, int>();
            var lines = text.Split('\n');
            for (var i = 0; i < lines.Length; i++)
            {
                var match = Label().Match(lines[i]);
                if (match.Success) labels.TryAdd(Convert.ToInt32(match.Groups[1].Value, 16), i);
            }
            var mapping = new List<IlLineMapping>();
            if (loaded.Points.TryGetValue(method, out var points))
            {
                foreach (var point in points.Where(p => documents.Contains(p.Document)).OrderBy(p => p.Offset))
                {
                    if (labels.TryGetValue(point.Offset, out var textLine) && mapping.All(m => m.Offset != point.Offset))
                        mapping.Add(new IlLineMapping(textLine, point.Offset, point.StartLine, point.StartColumn, point.EndLine, point.EndColumn));
                }
            }
            return new IlBody(MethodDisplayName(method), kind, text, atCaret, mapping);
        }

        private IlBody TypeBody(TypeDefinitionHandle type) => new(TypeDisplayName(type), "type", Disassemble(d => d.DisassembleType(loaded.Module, type)), true, []);

        private IlBody FieldBody(TypeDefinitionHandle type, FieldDefinitionHandle field) =>
            new($"{TypeDisplayName(type)}.{metadata.GetString(metadata.GetFieldDefinition(field).Name)}", "field", Disassemble(d => d.DisassembleField(loaded.Module, field)), true, []);

        private FieldDefinitionHandle? Field(TypeDefinitionHandle type, string name) =>
            metadata.GetTypeDefinition(type).GetFields().Where(h => metadata.GetString(metadata.GetFieldDefinition(h).Name) == name).Select(h => (FieldDefinitionHandle?)h).FirstOrDefault();

        private string Disassemble(Action<ReflectionDisassembler> write)
        {
            var writer = new StringWriter { NewLine = "\n" };
            var output = new PlainTextOutput(writer) { IndentationString = "  " };
            var disassembler = new ReflectionDisassembler(output, token)
            {
                DetectControlStructure = true, ShowSequencePoints = false, ShowMetadataTokens = false, ExpandMemberDefinitions = true, DebugInfo = loaded.DebugInfo,
            };
            write(disassembler);
            // ILSpy leaves a space at the end of a header line (`.method public hidebysig `)
            return string.Join('\n', writer.ToString().Split('\n').Select(l => l.TrimEnd())).TrimEnd('\n');
        }

        // -------------------------------------------------------------------------------------------- names

        private string Kind(MethodDefinitionHandle method)
        {
            if (loaded.Kickoff.ContainsKey(method)) return "stateMachine";
            var name = metadata.GetString(metadata.GetMethodDefinition(method).Name);
            if (name.StartsWith('<'))
            {
                if (name.Contains(">g__")) return "localFunction";
                if (name.Contains(">b__")) return "lambda";
            }
            return "method";
        }

        private string MethodDisplayName(MethodDefinitionHandle handle)
        {
            var method = metadata.GetMethodDefinition(handle);
            var context = new SignatureNames.Context(metadata, method.GetDeclaringType(), handle);
            string[] parameters;
            try { parameters = method.DecodeSignature(new SignatureNames(metadata), context).ParameterTypes.ToArray(); }
            catch (BadImageFormatException) { parameters = ["?"]; }
            var name = metadata.GetString(method.Name);
            var generics = method.GetGenericParameters().Count > 0
                ? "<" + string.Join(", ", method.GetGenericParameters().Select(p => metadata.GetString(metadata.GetGenericParameter(p).Name))) + ">"
                : "";
            return $"{TypeDisplayName(method.GetDeclaringType())}.{name}{generics}({string.Join(", ", parameters)})";
        }

        private string TypeDisplayName(TypeDefinitionHandle handle)
        {
            var type = metadata.GetTypeDefinition(handle);
            var name = StripArity(metadata.GetString(type.Name));
            var declaring = type.GetDeclaringType();
            return declaring.IsNil ? name : TypeDisplayName(declaring) + "." + name;
        }
    }

    private static readonly string[] AccessorPrefixes = ["get_", "set_", "add_", "remove_", "raise_"];

    private static string Capitalize(string text) => text.Length == 0 ? text : char.ToUpperInvariant(text[0]) + text[1..];

    internal static string StripArity(string name) => name.IndexOf('`') is var i and >= 0 ? name[..i] : name;

    [GeneratedRegex(@"^\s*IL_([0-9a-fA-F]{4,})\s*:")]
    private static partial Regex Label();

    private sealed record Candidate(MethodDefinitionHandle Method, int First, int Last, List<Point> Points)
    {
        public bool Touches(int line) => Points.Any(p => p.StartLine <= line && line <= p.EndLine);

        /** The last line of code above [line] and the first below it, when the line is between two sequence points of the method. */
        public (int, int)? GapAround(int line)
        {
            var above = Points.Where(p => p.EndLine < line).Select(p => p.EndLine).DefaultIfEmpty(int.MinValue).Max();
            var below = Points.Where(p => p.StartLine > line).Select(p => p.StartLine).DefaultIfEmpty(int.MaxValue).Min();
            return above == int.MinValue || below == int.MaxValue ? null : (above, below);
        }
    }

    internal sealed record Point(int Offset, DocumentHandle Document, int StartLine, int StartColumn, int EndLine, int EndColumn);

    // ------------------------------------------------------------------------------------------------ an assembly read into memory

    private sealed class LoadedAssembly
    {
        public readonly string Path;
        public readonly (long, long) Stamp;
        public readonly PEFile Module;
        public readonly MetadataReader? Pdb;
        public readonly string? PdbFile;
        public readonly (long, long) PdbStamp;
        public readonly string? PdbName;
        public readonly string? PdbProblem;
        public readonly IDebugInfoProvider? DebugInfo;
        public DateTime LastUse = DateTime.UtcNow;

        /** Visible sequence points of each method with a body (the PDB); hidden ones (0xfeefee) are left out. */
        public readonly Dictionary<MethodDefinitionHandle, List<Point>> Points = new();
        /** `MoveNext` of a state machine → the async method or iterator that starts it, and back. */
        public readonly Dictionary<MethodDefinitionHandle, MethodDefinitionHandle> Kickoff = new();
        public readonly Dictionary<MethodDefinitionHandle, MethodDefinitionHandle> MoveNext = new();

        private readonly List<(DocumentHandle Handle, string[] Segments, string Joined)> documents = [];
        private readonly Dictionary<string, TypeDefinitionHandle> types = new();

        public LoadedAssembly(string path, (long, long) stamp)
        {
            Path = path;
            Stamp = stamp;
            Module = new PEFile(path, new MemoryStream(ReadShared(path)), PEStreamOptions.PrefetchEntireImage);
            var metadata = Module.Metadata;
            foreach (var handle in metadata.TypeDefinitions) types[FullName(metadata, handle)] = handle;

            (Pdb, PdbFile, PdbName, PdbProblem) = ReadPdb();
            PdbStamp = IlViewer.Stamp(PdbFile);
            if (Pdb != null)
            {
                DebugInfo = new PdbDebugInfo(Pdb, PdbName ?? "");
                foreach (var handle in Pdb.Documents)
                {
                    var name = Pdb.GetString(Pdb.GetDocument(handle).Name);
                    var segments = Segments(name);
                    documents.Add((handle, segments, string.Join('/', segments)));
                }
                foreach (var handle in Pdb.MethodDebugInformation)
                {
                    var info = Pdb.GetMethodDebugInformation(handle);
                    var method = handle.ToDefinitionHandle();
                    var kickoff = info.GetStateMachineKickoffMethod();
                    if (!kickoff.IsNil) { Kickoff[method] = kickoff; MoveNext[kickoff] = method; }
                    if (info.SequencePointsBlob.IsNil) continue;
                    var points = new List<Point>();
                    foreach (var point in info.GetSequencePoints())
                    {
                        if (!point.IsHidden) points.Add(new Point(point.Offset, point.Document, point.StartLine, point.StartColumn, point.EndLine, point.EndColumn));
                    }
                    if (points.Count > 0) Points[method] = points;
                }
            }
            StateMachinesByAttributes(metadata);
        }

        /** The portable PDB next to the assembly, at the path the assembly names, or embedded; else why there is none. */
        private (MetadataReader?, string?, string?, string?) ReadPdb()
        {
            var reader = Module.Reader;
            var name = System.IO.Path.GetFileName(Path);
            string? named = null;
            BlobContentId? expected = null;
            foreach (var entry in reader.ReadDebugDirectory())
            {
                if (entry.Type == DebugDirectoryEntryType.EmbeddedPortablePdb)
                    return (reader.ReadEmbeddedPortablePdbDebugDirectoryData(entry).GetMetadataReader(), null, "embedded", null);
                if (entry.Type == DebugDirectoryEntryType.CodeView && named == null)
                {
                    var codeView = reader.ReadCodeViewDebugDirectoryData(entry);
                    named = codeView.Path;
                    if (entry.IsPortableCodeView) expected = new BlobContentId(codeView.Guid, entry.Stamp);
                }
            }
            var candidates = new List<string> { System.IO.Path.ChangeExtension(Path, ".pdb") };
            if (!string.IsNullOrEmpty(named))
            {
                candidates.Add(System.IO.Path.Combine(System.IO.Path.GetDirectoryName(Path)!, System.IO.Path.GetFileName(named.Replace('\\', '/').Split('/').Last())));
                // the path comes from the assembly: a UNC or device path there (`\\host`, `//host`, `/\host`, `\\?\UNC`) would make File.Exists
                // reach a server and give it the NTLM hash, so only a local path is followed: a drive letter on Windows, `/x` elsewhere
                if (IsLocalRooted(named)) candidates.Add(named);
            }
            foreach (var candidate in candidates.Distinct(StringComparer.OrdinalIgnoreCase))
            {
                if (!File.Exists(candidate)) continue;
                var bytes = ReadShared(candidate);
                if (bytes.Length >= 4 && bytes[0] == 'B' && bytes[1] == 'S' && bytes[2] == 'J' && bytes[3] == 'B')
                {
                    var pdb = MetadataReaderProvider.FromPortablePdbImage(ImmutableArray.Create(bytes)).GetMetadataReader();
                    if (expected != null && pdb.DebugMetadataHeader != null && new BlobContentId(pdb.DebugMetadataHeader.Id) != expected)
                        return (null, candidate, null, $"{System.IO.Path.GetFileName(candidate)} does not match {name} (left from another build)");
                    return (pdb, candidate, candidate, null);
                }
                if (Encoding.ASCII.GetString(bytes, 0, Math.Min(bytes.Length, 19)) == "Microsoft C/C++ MSF")
                    return (null, candidate, null, $"{System.IO.Path.GetFileName(candidate)} is a Windows PDB, only portable PDBs are read (<DebugType>portable</DebugType>)");
                return (null, candidate, null, $"{System.IO.Path.GetFileName(candidate)} is not a PDB");
            }
            return (null, candidates[0], null, $"no PDB for {name} (<DebugType>none</DebugType>?)");
        }

        /** `C:\...` on Windows, `/...` elsewhere; never a network or device path, whatever its slashes. */
        private static bool IsLocalRooted(string path)
        {
            if (OperatingSystem.IsWindows())
                return path.Length >= 3 && char.IsAsciiLetter(path[0]) && path[1] == ':' && (path[2] == '\\' || path[2] == '/');
            return path.StartsWith('/') && !path.StartsWith("//");
        }

        /** The state machines of async methods and iterators by their attributes too: without a PDB, and for the method found by name. */
        private void StateMachinesByAttributes(MetadataReader metadata)
        {
            foreach (var handle in metadata.MethodDefinitions)
            {
                if (MoveNext.ContainsKey(handle)) continue;
                foreach (var attributeHandle in metadata.GetMethodDefinition(handle).GetCustomAttributes())
                {
                    var attribute = metadata.GetCustomAttribute(attributeHandle);
                    if (AttributeName(metadata, attribute) is not ("AsyncStateMachineAttribute" or "IteratorStateMachineAttribute" or "AsyncIteratorStateMachineAttribute")) continue;
                    try
                    {
                        var value = attribute.DecodeValue(new AttributeTypes());
                        if (value.FixedArguments.FirstOrDefault().Value is not string typeName) continue;
                        if (!types.TryGetValue(typeName.Split(',')[0].Trim(), out var stateMachine)) continue;
                        var moveNext = metadata.GetTypeDefinition(stateMachine).GetMethods()
                            .FirstOrDefault(m => metadata.GetString(metadata.GetMethodDefinition(m).Name) is "MoveNext" or "System.Collections.IEnumerator.MoveNext");
                        if (moveNext.IsNil) continue;
                        MoveNext[handle] = moveNext;
                        Kickoff.TryAdd(moveNext, handle);
                    }
                    catch (BadImageFormatException) { }
                }
            }
        }

        private static string? AttributeName(MetadataReader metadata, CustomAttribute attribute)
        {
            EntityHandle type = attribute.Constructor.Kind switch
            {
                HandleKind.MemberReference => metadata.GetMemberReference((MemberReferenceHandle)attribute.Constructor).Parent,
                HandleKind.MethodDefinition => metadata.GetMethodDefinition((MethodDefinitionHandle)attribute.Constructor).GetDeclaringType(),
                _ => default,
            };
            return type.Kind switch
            {
                HandleKind.TypeReference => metadata.GetString(metadata.GetTypeReference((TypeReferenceHandle)type).Name),
                HandleKind.TypeDefinition => metadata.GetString(metadata.GetTypeDefinition((TypeDefinitionHandle)type).Name),
                _ => null,
            };
        }

        /**
         * The documents of the PDB that are [file]: the same path (case aside on Windows); else, as for a deterministic build whose paths start
         * with `/_/`, those that share the longest tail of the path with it, the file name at least.
         */
        public IReadOnlySet<DocumentHandle> Documents(string file)
        {
            var wanted = Segments(file);
            var joined = string.Join('/', wanted);
            var comparison = OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
            var exact = documents.Where(d => string.Equals(d.Joined, joined, comparison)).Select(d => d.Handle).ToHashSet();
            if (exact.Count > 0) return exact;
            var best = 0;
            var found = new HashSet<DocumentHandle>();
            foreach (var document in documents)
            {
                var common = 0;
                while (common < wanted.Length && common < document.Segments.Length
                       && string.Equals(wanted[^(common + 1)], document.Segments[^(common + 1)], StringComparison.OrdinalIgnoreCase)) common++;
                if (common == 0 || common < best) continue;
                if (common > best) { best = common; found.Clear(); }
                found.Add(document.Handle);
            }
            return found;
        }

        /** The segments of a path, `\` or `/`, with `.` and `..` resolved. */
        private static string[] Segments(string path)
        {
            var result = new List<string>();
            foreach (var segment in path.Replace('\\', '/').Split('/', StringSplitOptions.RemoveEmptyEntries))
            {
                if (segment == ".") continue;
                if (segment == ".." && result.Count > 0) result.RemoveAt(result.Count - 1);
                else result.Add(segment);
            }
            return result.ToArray();
        }

        /** The type by its metadata name (`Ns.Outer+Inner`, the arity of generics optional; `.` for `+` too). */
        public TypeDefinitionHandle? FindType(string name)
        {
            if (types.TryGetValue(name, out var exact)) return exact;
            var plain = Plain(name);
            foreach (var (full, handle) in types) if (Plain(full) == plain) return handle;
            var dotted = plain.Replace('+', '.');
            foreach (var (full, handle) in types) if (Plain(full).Replace('+', '.') == dotted) return handle;
            return null;
        }

        private static string Plain(string name) => Regex.Replace(name, @"`\d+", "");

        private static string FullName(MetadataReader metadata, TypeDefinitionHandle handle)
        {
            var type = metadata.GetTypeDefinition(handle);
            var name = metadata.GetString(type.Name);
            var declaring = type.GetDeclaringType();
            if (!declaring.IsNil) return FullName(metadata, declaring) + "+" + name;
            var ns = metadata.GetString(type.Namespace);
            return ns.Length == 0 ? name : ns + "." + name;
        }
    }

    /** What the disassembler asks the PDB: the names of the locals (`.locals init ([0] int32 total)`). */
    private sealed class PdbDebugInfo(MetadataReader pdb, string description) : IDebugInfoProvider
    {
        public string Description => description;
        public string SourceFileName => description;

        public IList<DecompilerSequencePoint> GetSequencePoints(MethodDefinitionHandle method)
        {
            var info = pdb.GetMethodDebugInformation(method.ToDebugInformationHandle());
            if (info.SequencePointsBlob.IsNil) return [];
            return info.GetSequencePoints().Select(p => new DecompilerSequencePoint
            {
                Offset = p.Offset, StartLine = p.StartLine, StartColumn = p.StartColumn, EndLine = p.EndLine, EndColumn = p.EndColumn,
                DocumentUrl = p.Document.IsNil ? "" : pdb.GetString(pdb.GetDocument(p.Document).Name),
            }).ToList();
        }

        public IList<Variable> GetVariables(MethodDefinitionHandle method) =>
            Locals(method).Select(v => new Variable(v.Index, pdb.GetString(v.Name))).ToList();

        public bool TryGetName(MethodDefinitionHandle method, int index, out string name)
        {
            foreach (var variable in Locals(method))
            {
                if (variable.Index != index) continue;
                name = pdb.GetString(variable.Name);
                return true;
            }
            name = null!;
            return false;
        }

        public bool TryGetExtraTypeInfo(MethodDefinitionHandle method, int index, out PdbExtraTypeInfo extraTypeInfo)
        {
            extraTypeInfo = default;
            return false;
        }

        private IEnumerable<LocalVariable> Locals(MethodDefinitionHandle method)
        {
            foreach (var scope in pdb.GetLocalScopes(method))
            foreach (var variable in pdb.GetLocalScope(scope).GetLocalVariables())
                yield return pdb.GetLocalVariable(variable);
        }
    }

    /** The values of `[AsyncStateMachine(typeof(...))]`: a type argument comes as its serialized name, which is all that is needed. */
    private sealed class AttributeTypes : ICustomAttributeTypeProvider<object?>
    {
        public object? GetPrimitiveType(PrimitiveTypeCode typeCode) => typeCode;
        public object? GetSystemType() => "System.Type";
        public object? GetSZArrayType(object? elementType) => null;
        public object? GetTypeFromDefinition(MetadataReader reader, TypeDefinitionHandle handle, byte rawTypeKind) => null;

        public object? GetTypeFromReference(MetadataReader reader, TypeReferenceHandle handle, byte rawTypeKind)
        {
            var type = reader.GetTypeReference(handle);
            return reader.GetString(type.Namespace) + "." + reader.GetString(type.Name);
        }

        public object? GetTypeFromSerializedName(string name) => name;
        public PrimitiveTypeCode GetUnderlyingEnumType(object? type) => PrimitiveTypeCode.Int32;
        public bool IsSystemType(object? type) => type as string == "System.Type";
    }
}

/** C#-like names of the parameter types of a method, for the title of a body: `Add(int, int)`, `Run(List<string>, ref int)`. */
internal sealed class SignatureNames(MetadataReader metadata) : ISignatureTypeProvider<string, SignatureNames.Context>
{
    public sealed record Context(MetadataReader Metadata, TypeDefinitionHandle Type, MethodDefinitionHandle Method);

    public string GetPrimitiveType(PrimitiveTypeCode typeCode) => typeCode switch
    {
        PrimitiveTypeCode.Boolean => "bool", PrimitiveTypeCode.Byte => "byte", PrimitiveTypeCode.SByte => "sbyte", PrimitiveTypeCode.Char => "char",
        PrimitiveTypeCode.Int16 => "short", PrimitiveTypeCode.UInt16 => "ushort", PrimitiveTypeCode.Int32 => "int", PrimitiveTypeCode.UInt32 => "uint",
        PrimitiveTypeCode.Int64 => "long", PrimitiveTypeCode.UInt64 => "ulong", PrimitiveTypeCode.Single => "float", PrimitiveTypeCode.Double => "double",
        PrimitiveTypeCode.String => "string", PrimitiveTypeCode.Object => "object", PrimitiveTypeCode.Void => "void", PrimitiveTypeCode.IntPtr => "nint",
        PrimitiveTypeCode.UIntPtr => "nuint", PrimitiveTypeCode.TypedReference => "TypedReference", _ => typeCode.ToString(),
    };

    public string GetTypeFromDefinition(MetadataReader reader, TypeDefinitionHandle handle, byte rawTypeKind) =>
        IlViewer.StripArity(reader.GetString(reader.GetTypeDefinition(handle).Name));

    public string GetTypeFromReference(MetadataReader reader, TypeReferenceHandle handle, byte rawTypeKind) =>
        IlViewer.StripArity(reader.GetString(reader.GetTypeReference(handle).Name));

    public string GetTypeFromSpecification(MetadataReader reader, Context genericContext, TypeSpecificationHandle handle, byte rawTypeKind) =>
        reader.GetTypeSpecification(handle).DecodeSignature(this, genericContext);

    public string GetSZArrayType(string elementType) => elementType + "[]";
    public string GetArrayType(string elementType, ArrayShape shape) => elementType + "[" + new string(',', shape.Rank - 1) + "]";
    public string GetByReferenceType(string elementType) => "ref " + elementType;
    public string GetPointerType(string elementType) => elementType + "*";
    public string GetPinnedType(string elementType) => elementType;
    public string GetModifiedType(string modifier, string unmodifiedType, bool isRequired) => unmodifiedType;
    public string GetFunctionPointerType(MethodSignature<string> signature) => $"delegate*<{string.Join(", ", signature.ParameterTypes.Append(signature.ReturnType))}>";

    public string GetGenericInstantiation(string genericType, ImmutableArray<string> typeArguments) =>
        genericType == "Nullable" && typeArguments.Length == 1 ? typeArguments[0] + "?" : $"{genericType}<{string.Join(", ", typeArguments)}>";

    public string GetGenericMethodParameter(Context genericContext, int index)
    {
        var parameters = metadata.GetMethodDefinition(genericContext.Method).GetGenericParameters();
        return index < parameters.Count ? metadata.GetString(metadata.GetGenericParameter(parameters[index]).Name) : "!!" + index;
    }

    public string GetGenericTypeParameter(Context genericContext, int index)
    {
        var parameters = metadata.GetTypeDefinition(genericContext.Type).GetGenericParameters();
        return index < parameters.Count ? metadata.GetString(metadata.GetGenericParameter(parameters[index]).Name) : "!" + index;
    }
}
