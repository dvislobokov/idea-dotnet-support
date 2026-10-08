using System.Collections.Immutable;
using System.Diagnostics;
using System.Globalization;
using System.IO.Compression;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Text;
using System.Text.Json;
using System.Xml;

namespace DotNetSupport.Indexer;

// The index of an assembly: what other code may name in it — its public and protected types with their generic parameters, bases,
// interfaces and attributes, and every public and protected member of them with its signature — read from the metadata alone:
// nothing is loaded, nothing of the assembly runs. Since format 4 also what the compiler imports but other code cannot reach, apart from
// the rest (internal and private types, internal and private protected members, the accessibility of accessors, InternalsVisibleTo):
// the errors of access (CS0122, CS0272) and the friend assemblies. One file per assembly, named by the MVID of the module: a package of NuGet and a
// reference pack of the SDK never change, so neither does their index, and a project is a list of such files. The XML documentation
// next to the assembly (`System.Console.xml`) goes into a second file, `<mvid>.dnxd`, read only when a documentation is asked for.
// The format is fixed-size records behind one header, little-endian, read by the plugin through a mapped buffer
// (src/main/kotlin/.../index/AssemblyIndex.kt). Change the format: change FormatVersion, the plugin names its cache folder by it.
//
//   dotnet AssemblyIndexer.dll --out <dir> [--list <file with a path per line>] [--force] [--wait <seconds>] [<assembly>...]
//
// A line of JSON per assembly goes to the standard output, the last line is the summary.
//
// Several IDEs, or several projects of one, may ask for the same assemblies at the same moment. The folder of the indexes is locked
// for the time of a run (`.lock` in it, held open by the process: the system lets it go when the process ends, however it ends), so
// the second process waits for the first and then finds the indexes made.
public static class Program
{
    public const int FormatVersion = 5;

    public static int Main(string[] args)
    {
        string? output = null;
        var force = false;
        var wait = 120;
        var paths = new List<string>();
        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--out": output = args[++i]; break;
                case "--list": paths.AddRange(File.ReadAllLines(args[++i]).Select(line => line.Trim()).Where(line => line.Length > 0)); break;
                case "--force": force = true; break;
                case "--wait": wait = int.Parse(args[++i]); break;
                case "--version": Console.WriteLine(FormatVersion); return 0;
                default: paths.Add(args[i]); break;
            }
        }
        if (output == null || paths.Count == 0)
        {
            Console.Error.WriteLine("usage: dotnet AssemblyIndexer.dll --out <dir> [--list <file>] [--force] [--wait <seconds>] [<assembly>...]");
            return 2;
        }
        Directory.CreateDirectory(output);
        Console.OutputEncoding = new UTF8Encoding(false);

        var waiting = Stopwatch.StartNew();
        using var guard = DirectoryLock.Acquire(output, TimeSpan.FromSeconds(wait));
        if (guard == null)
        {
            Console.Error.WriteLine($"The folder of the indexes is in use by another indexer for more than {wait} s: {output}");
            return 3;
        }
        var waited = waiting.Elapsed.TotalMilliseconds;

        var total = Stopwatch.StartNew();
        int indexed = 0, skipped = 0, failed = 0, types = 0, members = 0, docs = 0;
        long bytes = 0, docBytes = 0;
        // the assemblies are independent of each other: on as many cores as there are, but half, the IDE is working too
        var reports = new Dictionary<string, object?>[paths.Count];
        var parallel = new ParallelOptions { MaxDegreeOfParallelism = Math.Max(1, Environment.ProcessorCount / 2) };
        Parallel.For(0, paths.Count, parallel, i =>
        {
            var path = paths[i];
            var watch = Stopwatch.StartNew();
            var report = new Dictionary<string, object?> { ["path"] = path };
            try
            {
                var result = Index(path, output, force);
                report["mvid"] = result.Mvid;
                report["index"] = result.File;
                if (result.Written == null)
                {
                    Interlocked.Increment(ref skipped);
                    report["skipped"] = true;
                }
                else
                {
                    Interlocked.Increment(ref indexed);
                    var memberCount = result.Written.Types.Sum(type => type.Members.Count);
                    Interlocked.Add(ref types, result.Written.Types.Count);
                    Interlocked.Add(ref members, memberCount);
                    Interlocked.Add(ref bytes, result.Bytes);
                    Interlocked.Add(ref docs, result.Docs);
                    Interlocked.Add(ref docBytes, result.DocBytes);
                    report["types"] = result.Written.Types.Count;
                    report["members"] = memberCount;
                    report["bytes"] = result.Bytes;
                    if (result.Docs > 0)
                    {
                        report["docs"] = result.Docs;
                        report["docBytes"] = result.DocBytes;
                    }
                }
            }
            catch (Exception e) when (e is BadImageFormatException or IOException or UnauthorizedAccessException or InvalidOperationException)
            {
                // a native dll, a resource-only assembly, a file that is being written: not what is indexed
                Interlocked.Increment(ref failed);
                report["error"] = e.GetType().Name + ": " + e.Message;
            }
            report["ms"] = Math.Round(watch.Elapsed.TotalMilliseconds, 1);
            reports[i] = report;
        });
        foreach (var report in reports) Console.WriteLine(JsonSerializer.Serialize(report));
        Console.WriteLine(JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["summary"] = true, ["format"] = FormatVersion, ["indexed"] = indexed, ["skipped"] = skipped, ["failed"] = failed,
            ["types"] = types, ["members"] = members, ["bytes"] = bytes, ["docs"] = docs, ["docBytes"] = docBytes,
            ["ms"] = Math.Round(total.Elapsed.TotalMilliseconds, 1), ["waited"] = Math.Round(waited, 1),
        }));
        return 0;
    }

    private sealed record Indexed(string Mvid, string File, AssemblyData? Written, long Bytes, int Docs, long DocBytes);

    private static Indexed Index(string path, string output, bool force)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        using var pe = new PEReader(stream);
        if (!pe.HasMetadata) throw new BadImageFormatException("no metadata");
        var reader = pe.GetMetadataReader();
        var mvid = reader.GetGuid(reader.GetModuleDefinition().Mvid);
        var file = Path.Combine(output, mvid.ToString("N") + ".dnix");
        if (!force && File.Exists(file)) return new Indexed(mvid.ToString("N"), file, null, 0, 0, 0);

        var data = new MetadataScanner(reader).Scan();
        // the documentation first: whoever sees the index may look for it
        int docs = 0;
        long docBytes = 0;
        var xml = Path.ChangeExtension(path, ".xml");
        if (File.Exists(xml))
        {
            var entries = DocWriter.Read(xml);
            if (entries.Count > 0)
            {
                var docFile = Path.Combine(output, mvid.ToString("N") + ".dnxd");
                var docTemporary = docFile + "." + Guid.NewGuid().ToString("N") + ".tmp";
                DocWriter.Write(docTemporary, mvid, entries);
                docs = entries.Count;
                docBytes = new FileInfo(docTemporary).Length;
                File.Move(docTemporary, docFile, overwrite: true);
            }
        }
        // the same assembly may come twice in a list (two copies of one package), and both be indexed at once
        var temporary = file + "." + Guid.NewGuid().ToString("N") + ".tmp";
        IndexWriter.Write(temporary, mvid, data);
        var length = new FileInfo(temporary).Length;
        // another process may have written the same index meanwhile: it is the same one
        File.Move(temporary, file, overwrite: true);
        return new Indexed(mvid.ToString("N"), file, data, length, docs, docBytes);
    }
}

/// <summary>The folder of the indexes for one process at a time: a file held open with no sharing, which no crash leaves behind locked.</summary>
public sealed class DirectoryLock : IDisposable
{
    private readonly FileStream _stream;

    private DirectoryLock(FileStream stream) => _stream = stream;

    public static DirectoryLock? Acquire(string directory, TimeSpan timeout)
    {
        var file = Path.Combine(directory, ".lock");
        var watch = Stopwatch.StartNew();
        while (true)
        {
            try
            {
                return new DirectoryLock(new FileStream(file, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None, 1, FileOptions.None));
            }
            catch (IOException) when (watch.Elapsed < timeout)
            {
                Thread.Sleep(50);
            }
            catch (UnauthorizedAccessException) when (watch.Elapsed < timeout)
            {
                Thread.Sleep(50);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                return null;
            }
        }
    }

    public void Dispose() => _stream.Dispose();
}

public enum TypeKind : byte { Class = 1, Struct = 2, Interface = 3, Enum = 4, Delegate = 5, StaticClass = 6 }

public enum MemberKind : byte
{
    Method = 1, ExtensionMethod = 2, Property = 3, Field = 4, Constant = 5, EnumMember = 6, Constructor = 7, Indexer = 8, Event = 9, Operator = 10,
}

/// <summary>
/// Of a type. Protected: a nested type that only the derived types see (protected, protected internal). Internal: it or a type around it is
/// internal or private protected (seen by the friend assemblies only); Private: it or a type around it is private (seen by no other
/// assembly). The last two are apart in the index (format 4): the compiler imports them to say CS0122, nobody else names them.
/// </summary>
[Flags]
public enum TypeFlags
{
    None = 0, Obsolete = 1, Hidden = 2, Abstract = 4, Sealed = 8, Static = 16, Record = 32, ReadOnly = 64, RefLike = 128, Protected = 256,
    Internal = 512, Private = 1024,
}

/// <summary>
/// Of a member. Getter and Setter: the accessors other code may call; ReadOnly: a readonly field, a readonly member of a struct. The access:
/// Protected for protected, protected internal and private protected; Internal for internal, protected internal and private protected;
/// PrivateProtected for private protected. The accessibility of each accessor of a property (format 4) is an <see cref="Access"/> at
/// <see cref="GetterShift"/> and <see cref="SetterShift"/>: an accessor the compiler does not import (a private one) is <see cref="Access.None"/>.
/// </summary>
[Flags]
public enum MemberFlags
{
    None = 0, Obsolete = 1, Hidden = 2, Static = 4, Protected = 8, Abstract = 16, Virtual = 32, Override = 64, Sealed = 128, ReadOnly = 256,
    Getter = 512, Setter = 1024, InitOnly = 2048, Required = 4096, Internal = 8192, PrivateProtected = 16384,
    /// <summary>A method, property or indexer that returns `ref readonly` (format 5; bits 15–20 are the accesses of the accessors).</summary>
    RefReadOnly = 1 << 21,
}

/// <summary>The accessibility of an accessor, 3 bits of <see cref="MemberFlags"/>.</summary>
public enum Access { None = 0, Public = 1, Protected = 2, Internal = 3, ProtectedInternal = 4, PrivateProtected = 5 }

public static class AccessBits
{
    public const int GetterShift = 15, SetterShift = 18;

    public static Access Of(MethodAttributes attributes) => (attributes & MethodAttributes.MemberAccessMask) switch
    {
        MethodAttributes.Public => Access.Public,
        MethodAttributes.Family => Access.Protected,
        MethodAttributes.Assembly => Access.Internal,
        MethodAttributes.FamORAssem => Access.ProtectedInternal,
        MethodAttributes.FamANDAssem => Access.PrivateProtected,
        _ => Access.None,
    };

    /// <summary>The flags of a member of that access: Protected, Internal, PrivateProtected.</summary>
    public static MemberFlags Flags(Access access) => access switch
    {
        Access.Protected => MemberFlags.Protected,
        Access.Internal => MemberFlags.Internal,
        Access.ProtectedInternal => MemberFlags.Protected | MemberFlags.Internal,
        Access.PrivateProtected => MemberFlags.Protected | MemberFlags.Internal | MemberFlags.PrivateProtected,
        _ => MemberFlags.None,
    };

    /// <summary>Internal or private protected: only a friend assembly may reach it, it goes apart from what everyone sees.</summary>
    public static bool IsFriendOnly(MemberFlags flags) => (flags & MemberFlags.Internal) != 0 && ((flags & MemberFlags.Protected) == 0 || (flags & MemberFlags.PrivateProtected) != 0);
}

[Flags]
public enum ParameterFlags { None = 0, Optional = 1, Out = 2, Ref = 4, Params = 8, This = 16, In = 32, HasDefault = 64 }

/// <summary>Variance and constraints of a generic parameter; the first five are the bits of the metadata.</summary>
[Flags]
public enum GenericFlags { None = 0, Covariant = 1, Contravariant = 2, Class = 4, Struct = 8, New = 16, AllowsRefStruct = 32, Unmanaged = 64 }

public sealed record GenericEntry(string Name, GenericFlags Flags, List<string> Constraints);

public sealed record ParameterEntry(string Type, string Name, ParameterFlags Flags, string? Default);

public sealed class MemberEntry
{
    public string Name = "";
    public MemberKind Kind;
    public MemberFlags Flags;
    /// <summary>What a method returns, the type of a property, a field, an event (a reference, see <see cref="TypeRefs"/>).</summary>
    public string Type = "";
    public List<ParameterEntry> Parameters = new();
    public List<GenericEntry> Generics = new();
    public List<string> Attributes = new();
    /// <summary>The value of a constant or an enum member, as C# writes it.</summary>
    public string? Value;
    /// <summary>What the `this` parameter of an extension method is: a type by its metadata name, `[]` an array, `!` a type parameter.</summary>
    public string? ExtensionKey;
    /// <summary>
    /// What the nullable flow analysis of the plugin needs beyond the annotations of the types (format 3): items `target:code` separated by `;`
    /// — target `r` the return value or the type of the member, `0`, `1`… a parameter, `m` the member; codes: `o` an oblivious reference
    /// type (no nullable context), `M` / `N` / `A` / `D` `[MaybeNull]` / `[NotNull]` / `[AllowNull]` / `[DisallowNull]`, `W1` / `W0`
    /// `[NotNullWhen(true / false)]`, `w1` / `w0` `[MaybeNullWhen(…)]`, `X1` / `X0` `[DoesNotReturnIf(…)]`, `I=name` `[NotNullIfNotNull("name")]`;
    /// of the member: `R` `[DoesNotReturn]`, `MN=a,b` `[MemberNotNull("a", "b")]`, `MW1=a,b` / `MW0=…` `[MemberNotNullWhen(…)]`. Null when none.
    /// </summary>
    public string? Nullability;
}

public sealed class TypeEntry
{
    public string Namespace = "";
    /// <summary>`Dictionary`2+Enumerator`: the name within the namespace as the metadata writes it.</summary>
    public string Path = "";
    public TypeKind Kind;
    public TypeFlags Flags;
    public TypeEntry? Declaring;
    public readonly List<TypeEntry> Nested = new();
    public string? Base;
    public List<string> Interfaces = new();
    /// <summary>All of them, the ones of the types around a nested type first, as the metadata has them.</summary>
    public List<GenericEntry> Generics = new();
    public List<string> Attributes = new();
    /// <summary>The underlying type of an enum.</summary>
    public string? Underlying;
    public readonly List<MemberEntry> Members = new();
}

public sealed class AssemblyData
{
    public string Name = "";
    public string Version = "";
    public readonly List<TypeEntry> Types = new();
    /// <summary>The assemblies `[assembly: InternalsVisibleTo("Name, PublicKey=…")]` names: `Name`, or `Name,key` when a key is required.</summary>
    public readonly List<string> InternalsVisibleTo = new();
}

/// <summary>The types other assemblies see and their members, from the metadata tables.</summary>
public sealed class MetadataScanner(MetadataReader reader)
{
    private const string CompilerServices = "System.Runtime.CompilerServices";

    /// <summary>What the compiler says through attributes and the index says by its flags and references: not in the lists of attributes.</summary>
    private static readonly HashSet<string> Decoded =
    [
        CompilerServices + ".NullableAttribute", CompilerServices + ".NullableContextAttribute", CompilerServices + ".NullablePublicOnlyAttribute",
        CompilerServices + ".IsReadOnlyAttribute", CompilerServices + ".IsByRefLikeAttribute", CompilerServices + ".ExtensionAttribute",
        CompilerServices + ".TupleElementNamesAttribute", CompilerServices + ".RequiredMemberAttribute", CompilerServices + ".IsUnmanagedAttribute",
        CompilerServices + ".RefSafetyRulesAttribute", CompilerServices + ".CompilerFeatureRequiredAttribute", CompilerServices + ".ScopedRefAttribute",
        CompilerServices + ".CompilerGeneratedAttribute", "System.ParamArrayAttribute", "System.Reflection.DefaultMemberAttribute",
    ];

    private readonly SignatureProvider _provider = new();

    public AssemblyData Scan()
    {
        var data = new AssemblyData();
        if (reader.IsAssembly)
        {
            var assembly = reader.GetAssemblyDefinition();
            data.Name = reader.GetString(assembly.Name);
            data.Version = assembly.Version.ToString();
            foreach (var handle in assembly.GetCustomAttributes())
            {
                var attribute = reader.GetCustomAttribute(handle);
                if (AttributeType(attribute) != CompilerServices + ".InternalsVisibleToAttribute") continue;
                try
                {
                    // `Name, PublicKey=…` makes a friend of the assembly signed with that key only: the key itself is not needed, that there is one is
                    var parts = StringArgument(attribute).Split(',');
                    var friend = parts[0].Trim() + (parts.Skip(1).Any(p => p.Trim().StartsWith("PublicKey", StringComparison.OrdinalIgnoreCase)) ? ",key" : "");
                    if (parts[0].Trim().Length > 0 && !data.InternalsVisibleTo.Contains(friend)) data.InternalsVisibleTo.Add(friend);
                }
                catch (BadImageFormatException)
                {
                    // an attribute the metadata cannot decode names no friend
                }
            }
        }
        var entries = new Dictionary<TypeDefinitionHandle, TypeEntry>();
        foreach (var handle in reader.TypeDefinitions)
        {
            var type = reader.GetTypeDefinition(handle);
            var visibility = VisibilityOf(type);
            var name = reader.GetString(type.Name);
            if (name.Contains('<')) continue;
            var custom = type.GetCustomAttributes();
            var entry = new TypeEntry { Kind = KindOf(type) };
            var outermost = type;
            while (outermost.IsNested) outermost = reader.GetTypeDefinition(outermost.GetDeclaringType());
            entry.Namespace = reader.GetString(outermost.Namespace);
            entry.Path = PathOf(type);
            entry.Flags = TypeFlagsOf(type, custom, (visibility & TypeFlags.Protected) != 0) | (visibility & (TypeFlags.Internal | TypeFlags.Private));
            entries[handle] = entry;
            data.Types.Add(entry);

            var context = Context(custom, type.IsNested ? DefaultContext(reader.GetTypeDefinition(type.GetDeclaringType())) : (byte)0);
            entry.Generics = Generics(type.GetGenericParameters(), context);
            entry.Attributes = AttributeNames(custom);
            if (!type.BaseType.IsNil && entry.Kind is TypeKind.Class or TypeKind.StaticClass)
                entry.Base = TypeRefs.Encode(Decode(type.BaseType), Annotations.Of(reader, AttributeType, custom, context));
            foreach (var implementation in type.GetInterfaceImplementations())
            {
                var interfaceImplementation = reader.GetInterfaceImplementation(implementation);
                entry.Interfaces.Add(TypeRefs.Encode(Decode(interfaceImplementation.Interface), Annotations.Of(reader, AttributeType, interfaceImplementation.GetCustomAttributes(), context)));
            }

            // a private type is there for its name only: no other assembly reaches anything of it
            if ((visibility & TypeFlags.Private) != 0) continue;
            AddMethods(entry, type, context);
            AddProperties(entry, type, context);
            AddEvents(entry, type, context);
            AddFields(entry, type, context);
        }
        foreach (var (handle, entry) in entries)
        {
            var type = reader.GetTypeDefinition(handle);
            if (!type.IsNested) continue;
            if (entries.TryGetValue(type.GetDeclaringType(), out var declaring))
            {
                entry.Declaring = declaring;
                declaring.Nested.Add(entry);
            }
        }
        return data;
    }

    private void AddMethods(TypeEntry entry, TypeDefinition type, byte typeContext)
    {
        foreach (var handle in type.GetMethods())
        {
            var method = reader.GetMethodDefinition(handle);
            var attributes = method.Attributes;
            if (AccessBits.Of(attributes) == Access.None) continue;
            var name = reader.GetString(method.Name);
            if (name.Contains('<')) continue;
            MemberKind kind;
            if ((attributes & MethodAttributes.RTSpecialName) != 0)
            {
                // the static constructor is called by no one
                if (name != ".ctor") continue;
                kind = MemberKind.Constructor;
            }
            else if ((attributes & MethodAttributes.SpecialName) != 0)
            {
                // the accessors are the properties and events
                if (!name.StartsWith("op_", StringComparison.Ordinal)) continue;
                kind = MemberKind.Operator;
            }
            else kind = MemberKind.Method;
            var custom = method.GetCustomAttributes();
            var extension = kind == MemberKind.Method && Has(custom, CompilerServices, "ExtensionAttribute");
            if (extension) kind = MemberKind.ExtensionMethod;
            var context = Context(custom, typeContext);
            var signature = method.DecodeSignature(_provider, null);
            var member = new MemberEntry
            {
                Name = name, Kind = kind, Flags = MethodFlags(attributes, custom, entry.Kind == TypeKind.Interface),
                Generics = Generics(method.GetGenericParameters(), context), Attributes = AttributeNames(custom),
            };
            var (returnAttributes, parameters, nullability) = Parameters(method.GetParameters(), signature.ParameterTypes, context, extension);
            member.Type = TypeRefs.Encode(signature.ReturnType, Annotations.Of(reader, AttributeType, returnAttributes, context));
            if (returnAttributes != null && Has(returnAttributes.Value, CompilerServices, "IsReadOnlyAttribute")) member.Flags |= MemberFlags.RefReadOnly;
            member.Parameters = parameters;
            NullableCodes(custom, "m", nullability);
            NullableCodes(returnAttributes, "r", nullability);
            if (IsOblivious(signature.ReturnType, returnAttributes, context)) nullability.Add("r:o");
            member.Nullability = Join(nullability);
            if (extension && signature.ParameterTypes.Length > 0) member.ExtensionKey = TypeRefs.ExtensionKey(signature.ParameterTypes[0]);
            entry.Members.Add(member);
        }
    }

    /// <summary>
    /// The parameters with their names, flags and defaults, the attributes of the return value (the parameter number 0) and what the nullable
    /// attributes and oblivious types of the parameters say (<see cref="MemberEntry.Nullability"/>).
    /// </summary>
    private (CustomAttributeHandleCollection?, List<ParameterEntry>, List<string>) Parameters(ParameterHandleCollection handles, ImmutableArray<Sig> types, byte context, bool extension)
    {
        var count = types.Length;
        var names = new string?[count];
        var flags = new ParameterFlags[count];
        var defaults = new string?[count];
        var custom = new CustomAttributeHandleCollection?[count];
        CustomAttributeHandleCollection? returnAttributes = null;
        foreach (var handle in handles)
        {
            var parameter = reader.GetParameter(handle);
            var at = parameter.SequenceNumber - 1;
            if (at == -1) returnAttributes = parameter.GetCustomAttributes();
            if (at < 0 || at >= count) continue;
            names[at] = reader.GetString(parameter.Name);
            custom[at] = parameter.GetCustomAttributes();
            if ((parameter.Attributes & ParameterAttributes.Optional) != 0) flags[at] |= ParameterFlags.Optional;
            if ((parameter.Attributes & ParameterAttributes.Out) != 0) flags[at] |= ParameterFlags.Out;
            if ((parameter.Attributes & ParameterAttributes.In) != 0) flags[at] |= ParameterFlags.In;
            if (Has(parameter.GetCustomAttributes(), "System", "ParamArrayAttribute")) flags[at] |= ParameterFlags.Params;
            if ((parameter.Attributes & ParameterAttributes.HasDefault) != 0)
            {
                flags[at] |= ParameterFlags.HasDefault;
                var constant = parameter.GetDefaultValue();
                defaults[at] = constant.IsNil ? "default" : Literals.Of(reader, constant);
            }
        }
        var result = new List<ParameterEntry>(count);
        var nullability = new List<string>();
        for (var i = 0; i < count; i++)
        {
            var type = types[i];
            NullableCodes(custom[i], i.ToString(CultureInfo.InvariantCulture), nullability);
            if (IsOblivious(type, custom[i], context)) nullability.Add(i.ToString(CultureInfo.InvariantCulture) + ":o");
            if (TypeRefs.Unwrap(type) is ByRefSig byRef)
            {
                type = byRef.Element;
                // `out` and `in` are by reference as well, and say so by their own flags
                if ((flags[i] & (ParameterFlags.Out | ParameterFlags.In)) == 0) flags[i] |= ParameterFlags.Ref;
            }
            if (extension && i == 0) flags[i] |= ParameterFlags.This;
            result.Add(new ParameterEntry(TypeRefs.Encode(type, Annotations.Of(reader, AttributeType, custom[i], context)), names[i] ?? "arg" + i, flags[i], defaults[i]));
        }
        return (returnAttributes, result, nullability);
    }

    private CustomAttributeHandleCollection? ReturnAttributes(MethodDefinition method)
    {
        foreach (var handle in method.GetParameters())
        {
            var parameter = reader.GetParameter(handle);
            if (parameter.SequenceNumber == 0) return parameter.GetCustomAttributes();
        }
        return null;
    }

    private CustomAttributeHandleCollection? ValueAttributes(MethodDefinition setter)
    {
        foreach (var handle in setter.GetParameters())
        {
            var parameter = reader.GetParameter(handle);
            if (parameter.SequenceNumber == 1) return parameter.GetCustomAttributes();
        }
        return null;
    }

    private static string? Join(List<string> items) => items.Count == 0 ? null : string.Join(";", items);

    /// <summary>A reference type at the top of [sig] with no nullable context: what code without nullable annotations says (`string` that may be null).</summary>
    private bool IsOblivious(Sig sig, CustomAttributeHandleCollection? custom, byte context)
    {
        sig = TypeRefs.Unwrap(sig);
        if (sig is ByRefSig byRef) sig = TypeRefs.Unwrap(byRef.Element);
        var reference = sig switch
        {
            NamedSig named => !named.ValueType,
            GenericSig generic => !generic.Definition.ValueType,
            ParameterSig or ArraySig => true,
            _ => false,
        };
        return reference && Annotations.Of(reader, AttributeType, custom, context).Next() == 0;
    }

    private const string CodeAnalysis = "System.Diagnostics.CodeAnalysis.";

    /// <summary>The nullable attributes of `System.Diagnostics.CodeAnalysis` in [custom] as items of <see cref="MemberEntry.Nullability"/> for [target].</summary>
    private void NullableCodes(CustomAttributeHandleCollection? custom, string target, List<string> into)
    {
        if (custom == null) return;
        foreach (var handle in custom.Value)
        {
            var attribute = reader.GetCustomAttribute(handle);
            var type = AttributeType(attribute);
            if (!type.StartsWith(CodeAnalysis, StringComparison.Ordinal)) continue;
            string? code;
            try
            {
                code = type[CodeAnalysis.Length..] switch
                {
                    "AllowNullAttribute" => "A",
                    "DisallowNullAttribute" => "D",
                    "MaybeNullAttribute" => "M",
                    "NotNullAttribute" => "N",
                    "NotNullWhenAttribute" => "W" + BoolArgument(attribute),
                    "MaybeNullWhenAttribute" => "w" + BoolArgument(attribute),
                    "DoesNotReturnIfAttribute" => "X" + BoolArgument(attribute),
                    "NotNullIfNotNullAttribute" => "I=" + StringArgument(attribute),
                    "DoesNotReturnAttribute" => "R",
                    "MemberNotNullAttribute" => "MN=" + string.Join(",", MemberNames(attribute, false)),
                    "MemberNotNullWhenAttribute" => "MW" + BoolArgument(attribute) + "=" + string.Join(",", MemberNames(attribute, true)),
                    _ => null,
                };
            }
            catch (BadImageFormatException)
            {
                code = null;
            }
            if (code == null) continue;
            // `[DoesNotReturn]`, `[MemberNotNull]`, `[MemberNotNullWhen]` are of the member, wherever they are found (a property and its getter both have them)
            var member = code == "R" || code.StartsWith("MN=", StringComparison.Ordinal) || code.StartsWith("MW", StringComparison.Ordinal);
            var item = (member ? "m" : target) + ":" + code;
            if (!into.Contains(item)) into.Add(item);
        }
    }

    /// <summary>`1` / `0`: the first argument, a bool, of an attribute's constructor.</summary>
    private string BoolArgument(CustomAttribute attribute)
    {
        var blob = reader.GetBlobReader(attribute.Value);
        if (blob.Length < 3 || blob.ReadUInt16() != 1) throw new BadImageFormatException();
        return blob.ReadBoolean() ? "1" : "0";
    }

    private string StringArgument(CustomAttribute attribute)
    {
        var blob = reader.GetBlobReader(attribute.Value);
        if (blob.Length < 3 || blob.ReadUInt16() != 1) throw new BadImageFormatException();
        return blob.ReadSerializedString() ?? "";
    }

    /// <summary>The member names of `[MemberNotNull]` (`string` or `params string[]`) and of `[MemberNotNullWhen]` after its bool ([afterBool]).</summary>
    private List<string> MemberNames(CustomAttribute attribute, bool afterBool)
    {
        var signature = attribute.Constructor.Kind == HandleKind.MemberReference
            ? reader.GetMemberReference((MemberReferenceHandle)attribute.Constructor).Signature
            : reader.GetMethodDefinition((MethodDefinitionHandle)attribute.Constructor).Signature;
        var sig = reader.GetBlobReader(signature);
        sig.ReadSignatureHeader();
        sig.ReadCompressedInteger();
        sig.ReadSignatureTypeCode();
        if (afterBool) sig.ReadSignatureTypeCode();
        var array = sig.ReadSignatureTypeCode() == SignatureTypeCode.SZArray;
        var blob = reader.GetBlobReader(attribute.Value);
        if (blob.Length < 3 || blob.ReadUInt16() != 1) throw new BadImageFormatException();
        if (afterBool) blob.ReadBoolean();
        var names = new List<string>();
        if (!array) names.Add(blob.ReadSerializedString() ?? "");
        else
        {
            var count = blob.ReadInt32();
            for (var i = 0; i < count && count <= blob.RemainingBytes; i++) names.Add(blob.ReadSerializedString() ?? "");
        }
        return names;
    }

    private void AddProperties(TypeEntry entry, TypeDefinition type, byte typeContext)
    {
        foreach (var handle in type.GetProperties())
        {
            var property = reader.GetPropertyDefinition(handle);
            var accessors = property.GetAccessors();
            var getter = accessors.Getter.IsNil ? (MethodDefinition?)null : reader.GetMethodDefinition(accessors.Getter);
            var setter = accessors.Setter.IsNil ? (MethodDefinition?)null : reader.GetMethodDefinition(accessors.Setter);
            var getterAccess = getter == null ? Access.None : AccessBits.Of(getter.Value.Attributes);
            var setterAccess = setter == null ? Access.None : AccessBits.Of(setter.Value.Attributes);
            // a private accessor is not imported by the compiler: as if there were none
            if (getterAccess == Access.None && setterAccess == Access.None) continue;
            var getterVisible = IsVisible(getterAccess);
            var setterVisible = IsVisible(setterAccess);
            var name = reader.GetString(property.Name);
            if (name.Contains('<')) continue;
            var accessor = getterVisible || !setterVisible && getterAccess != Access.None ? getter!.Value : setter!.Value;
            var custom = property.GetCustomAttributes();
            // the wider of the two accessors says who sees the property
            var flags = MethodFlags(accessor.Attributes, custom, entry.Kind == TypeKind.Interface) & ~(MemberFlags.Protected | MemberFlags.Internal | MemberFlags.PrivateProtected);
            flags |= AccessBits.Flags(Wider(getterAccess, setterAccess));
            flags |= (MemberFlags)((int)getterAccess << AccessBits.GetterShift | (int)setterAccess << AccessBits.SetterShift);
            if (getterVisible) flags |= MemberFlags.Getter;
            if (setterVisible) flags |= MemberFlags.Setter;
            if (getter != null && Has(getter.Value.GetCustomAttributes(), CompilerServices, "IsReadOnlyAttribute")) flags |= MemberFlags.ReadOnly;
            if (Has(custom, CompilerServices, "RequiredMemberAttribute")) flags |= MemberFlags.Required;
            if (setter != null && setter.Value.DecodeSignature(_provider, null).ReturnType is ModifiedSig { Modifier: CompilerServices + ".IsExternalInit" }) flags |= MemberFlags.InitOnly;
            if (getter != null && ReturnsReadOnlyRef(reader, getter.Value)) flags |= MemberFlags.RefReadOnly;
            var context = Context(accessor.GetCustomAttributes(), typeContext);
            var signature = property.DecodeSignature(_provider, null);
            var member = new MemberEntry
            {
                Name = name, Kind = signature.ParameterTypes.Length > 0 ? MemberKind.Indexer : MemberKind.Property, Flags = flags,
                // a property has no nullable context of its own: the one of its type (an accessor may have one, for its parameters)
                Attributes = AttributeNames(custom), Type = TypeRefs.Encode(signature.ReturnType, Annotations.Of(reader, AttributeType, custom, typeContext)),
            };
            if (signature.ParameterTypes.Length > 0)
            {
                // the names of the parameters of an indexer are the ones of its getter, or of its setter but its last, the value
                member.Parameters = Parameters(accessor.GetParameters(), signature.ParameterTypes, context, false).Item2;
            }
            var nullability = new List<string>();
            NullableCodes(custom, "r", nullability);
            if (getter != null)
            {
                NullableCodes(getter.Value.GetCustomAttributes(), "m", nullability);
                // `[MaybeNull]` / `[NotNull]` of a property are emitted on the return value of its getter
                NullableCodes(ReturnAttributes(getter.Value), "r", nullability);
            }
            // and `[AllowNull]` / `[DisallowNull]` on the value parameter of its setter
            if (setter != null && signature.ParameterTypes.Length == 0) NullableCodes(ValueAttributes(setter.Value), "r", nullability);
            if (IsOblivious(signature.ReturnType, custom, typeContext)) nullability.Add("r:o");
            member.Nullability = Join(nullability);
            entry.Members.Add(member);
        }
    }

    private void AddEvents(TypeEntry entry, TypeDefinition type, byte typeContext)
    {
        foreach (var handle in type.GetEvents())
        {
            var @event = reader.GetEventDefinition(handle);
            var adder = @event.GetAccessors().Adder;
            if (adder.IsNil) continue;
            var accessor = reader.GetMethodDefinition(adder);
            if (AccessBits.Of(accessor.Attributes) == Access.None) continue;
            var name = reader.GetString(@event.Name);
            if (name.Contains('<')) continue;
            var custom = @event.GetCustomAttributes();
            entry.Members.Add(new MemberEntry
            {
                Name = name, Kind = MemberKind.Event, Flags = MethodFlags(accessor.Attributes, custom, entry.Kind == TypeKind.Interface),
                Attributes = AttributeNames(custom), Type = TypeRefs.Encode(Decode(@event.Type), Annotations.Of(reader, AttributeType, custom, typeContext)),
            });
        }
    }

    private void AddFields(TypeEntry entry, TypeDefinition type, byte context)
    {
        foreach (var handle in type.GetFields())
        {
            var field = reader.GetFieldDefinition(handle);
            var attributes = field.Attributes;
            if ((attributes & FieldAttributes.RTSpecialName) != 0)
            {
                // `value__` of an enum: its underlying type
                if (entry.Kind == TypeKind.Enum) entry.Underlying = TypeRefs.Encode(field.DecodeSignature(_provider, null), Annotations.None);
                continue;
            }
            if (AccessBits.Of((MethodAttributes)(int)(attributes & FieldAttributes.FieldAccessMask)) == Access.None) continue;
            var name = reader.GetString(field.Name);
            if (name.Contains('<')) continue;
            var custom = field.GetCustomAttributes();
            var kind = entry.Kind == TypeKind.Enum ? MemberKind.EnumMember : (attributes & FieldAttributes.Literal) != 0 ? MemberKind.Constant : MemberKind.Field;
            var flags = CommonFlags(custom);
            if ((attributes & FieldAttributes.Static) != 0) flags |= MemberFlags.Static;
            flags |= AccessBits.Flags(AccessBits.Of((MethodAttributes)(int)(attributes & FieldAttributes.FieldAccessMask)));
            if ((attributes & FieldAttributes.InitOnly) != 0) flags |= MemberFlags.ReadOnly;
            if (Has(custom, CompilerServices, "RequiredMemberAttribute")) flags |= MemberFlags.Required;
            var constant = field.GetDefaultValue();
            var fieldType = field.DecodeSignature(_provider, null);
            var nullability = new List<string>();
            NullableCodes(custom, "r", nullability);
            if (kind == MemberKind.Field && IsOblivious(fieldType, custom, context)) nullability.Add("r:o");
            entry.Members.Add(new MemberEntry
            {
                Name = name, Kind = kind, Flags = flags, Attributes = AttributeNames(custom),
                Type = TypeRefs.Encode(fieldType, Annotations.Of(reader, AttributeType, custom, context)),
                Value = (attributes & FieldAttributes.Literal) != 0 && !constant.IsNil ? Literals.Of(reader, constant) : null,
                Nullability = Join(nullability),
            });
        }
    }

    private List<GenericEntry> Generics(GenericParameterHandleCollection handles, byte context)
    {
        var result = new List<GenericEntry>(handles.Count);
        foreach (var handle in handles)
        {
            var parameter = reader.GetGenericParameter(handle);
            var flags = (GenericFlags)((int)parameter.Attributes & 0x3F);
            if (Has(parameter.GetCustomAttributes(), CompilerServices, "IsUnmanagedAttribute")) flags |= GenericFlags.Unmanaged;
            var constraints = new List<string>();
            foreach (var constraintHandle in parameter.GetConstraints())
            {
                var constraint = reader.GetGenericParameterConstraint(constraintHandle);
                var sig = Decode(constraint.Type);
                // `struct` is written as a constraint to ValueType as well
                if ((flags & GenericFlags.Struct) != 0 && sig is NamedSig { Name: "System.ValueType" }) continue;
                constraints.Add(TypeRefs.Encode(sig, Annotations.Of(reader, AttributeType, constraint.GetCustomAttributes(), context)));
            }
            result.Add(new GenericEntry(reader.GetString(parameter.Name), flags, constraints));
        }
        return result;
    }

    private Sig Decode(EntityHandle handle) => handle.Kind switch
    {
        HandleKind.TypeDefinition => _provider.GetTypeFromDefinition(reader, (TypeDefinitionHandle)handle, 0),
        HandleKind.TypeReference => _provider.GetTypeFromReference(reader, (TypeReferenceHandle)handle, 0),
        HandleKind.TypeSpecification => reader.GetTypeSpecification((TypeSpecificationHandle)handle).DecodeSignature(_provider, null),
        _ => new NamedSig("System.Object", false),
    };

    private MemberFlags MethodFlags(MethodAttributes attributes, CustomAttributeHandleCollection custom, bool ofInterface)
    {
        var flags = CommonFlags(custom);
        if ((attributes & MethodAttributes.Static) != 0) flags |= MemberFlags.Static;
        flags |= AccessBits.Flags(AccessBits.Of(attributes));
        if ((attributes & MethodAttributes.Abstract) != 0) flags |= MemberFlags.Abstract;
        else if ((attributes & MethodAttributes.Virtual) != 0 && (attributes & MethodAttributes.Final) == 0) flags |= MemberFlags.Virtual;
        if ((attributes & MethodAttributes.Virtual) != 0 && (attributes & MethodAttributes.NewSlot) == 0 && !ofInterface) flags |= MemberFlags.Override;
        if ((attributes & MethodAttributes.Final) != 0 && (attributes & MethodAttributes.NewSlot) == 0 && !ofInterface) flags |= MemberFlags.Sealed;
        if (Has(custom, CompilerServices, "IsReadOnlyAttribute")) flags |= MemberFlags.ReadOnly;
        return flags;
    }

    /// <summary>A getter of `ref readonly`: the IsReadOnlyAttribute is on its return parameter (sequence 0).</summary>
    private bool ReturnsReadOnlyRef(MetadataReader reader, MethodDefinition getter)
    {
        foreach (var handle in getter.GetParameters())
        {
            var parameter = reader.GetParameter(handle);
            if (parameter.SequenceNumber == 0) return Has(parameter.GetCustomAttributes(), CompilerServices, "IsReadOnlyAttribute");
        }
        return false;
    }

    private MemberFlags CommonFlags(CustomAttributeHandleCollection custom)
    {
        var flags = MemberFlags.None;
        if (Has(custom, "System", "ObsoleteAttribute")) flags |= MemberFlags.Obsolete;
        if (IsHidden(custom)) flags |= MemberFlags.Hidden;
        return flags;
    }

    /// <summary>Public, protected and protected internal: what code of another assembly may see; not private protected.</summary>
    private static bool IsVisible(Access access) => access is Access.Public or Access.Protected or Access.ProtectedInternal;

    /// <summary>The accessibility of a property of two accessors: the union of what each lets in.</summary>
    private static Access Wider(Access a, Access b)
    {
        if (a == Access.None) return b;
        if (b == Access.None || a == b) return a;
        if (a == Access.Public || b == Access.Public) return Access.Public;
        if (a == Access.PrivateProtected) return b;
        if (b == Access.PrivateProtected) return a;
        // protected and internal, or one of them and protected internal
        return Access.ProtectedInternal;
    }

    private TypeFlags TypeFlagsOf(TypeDefinition type, CustomAttributeHandleCollection custom, bool protectedOnly)
    {
        var flags = TypeFlags.None;
        if (Has(custom, "System", "ObsoleteAttribute")) flags |= TypeFlags.Obsolete;
        if (IsHidden(custom)) flags |= TypeFlags.Hidden;
        if (protectedOnly) flags |= TypeFlags.Protected;
        var attributes = type.Attributes;
        if ((attributes & TypeAttributes.Interface) == 0)
        {
            if ((attributes & TypeAttributes.Abstract) != 0) flags |= TypeFlags.Abstract;
            if ((attributes & TypeAttributes.Sealed) != 0) flags |= TypeFlags.Sealed;
            if ((attributes & (TypeAttributes.Abstract | TypeAttributes.Sealed)) == (TypeAttributes.Abstract | TypeAttributes.Sealed)) flags |= TypeFlags.Static;
        }
        if (Has(custom, CompilerServices, "IsReadOnlyAttribute")) flags |= TypeFlags.ReadOnly;
        if (Has(custom, CompilerServices, "IsByRefLikeAttribute")) flags |= TypeFlags.RefLike;
        // a record class has the clone method the compiler names so (a record struct says nothing a reference assembly keeps)
        foreach (var handle in type.GetMethods())
        {
            if (reader.StringComparer.Equals(reader.GetMethodDefinition(handle).Name, "<Clone>$"))
            {
                flags |= TypeFlags.Record;
                break;
            }
        }
        return flags;
    }

    /// <summary>
    /// Who in another assembly sees the type, all the way out: everyone (none), the derived types (Protected: nested protected), a friend
    /// assembly (Internal: internal or private protected), nobody (Private).
    /// </summary>
    private TypeFlags VisibilityOf(TypeDefinition type)
    {
        var flags = TypeFlags.None;
        while (true)
        {
            var visibility = type.Attributes & TypeAttributes.VisibilityMask;
            if (!type.IsNested) return visibility == TypeAttributes.Public ? flags : flags | TypeFlags.Internal;
            flags |= visibility switch
            {
                TypeAttributes.NestedPublic => TypeFlags.None,
                TypeAttributes.NestedFamily or TypeAttributes.NestedFamORAssem => TypeFlags.Protected,
                TypeAttributes.NestedAssembly => TypeFlags.Internal,
                TypeAttributes.NestedFamANDAssem => TypeFlags.Internal | TypeFlags.Protected,
                _ => TypeFlags.Private,
            };
            type = reader.GetTypeDefinition(type.GetDeclaringType());
        }
    }

    /// <summary>`Dictionary`2+Enumerator`: the name of a type within its namespace, as the metadata writes it.</summary>
    private string PathOf(TypeDefinition type)
    {
        var name = reader.GetString(type.Name);
        return type.IsNested ? PathOf(reader.GetTypeDefinition(type.GetDeclaringType())) + "+" + name : name;
    }

    private TypeKind KindOf(TypeDefinition type)
    {
        if ((type.Attributes & TypeAttributes.Interface) != 0) return TypeKind.Interface;
        var baseName = FullName(type.BaseType);
        if (baseName == "System.Enum") return TypeKind.Enum;
        if (baseName == "System.ValueType") return TypeKind.Struct;
        if (baseName is "System.MulticastDelegate" or "System.Delegate") return TypeKind.Delegate;
        const TypeAttributes both = TypeAttributes.Abstract | TypeAttributes.Sealed;
        return (type.Attributes & both) == both ? TypeKind.StaticClass : TypeKind.Class;
    }

    /// <summary>`System.Collections.Generic.Dictionary`2+Enumerator` of a reference or a definition; empty for anything else.</summary>
    private string FullName(EntityHandle handle)
    {
        if (handle.IsNil) return "";
        switch (handle.Kind)
        {
            case HandleKind.TypeReference:
                var reference = reader.GetTypeReference((TypeReferenceHandle)handle);
                if (reference.ResolutionScope.Kind == HandleKind.TypeReference) return FullName(reference.ResolutionScope) + "+" + reader.GetString(reference.Name);
                return Qualified(reader.GetString(reference.Namespace), reader.GetString(reference.Name));
            case HandleKind.TypeDefinition:
                var definition = reader.GetTypeDefinition((TypeDefinitionHandle)handle);
                if (definition.IsNested) return FullName(definition.GetDeclaringType()) + "+" + reader.GetString(definition.Name);
                return Qualified(reader.GetString(definition.Namespace), reader.GetString(definition.Name));
            default:
                return "";
        }
    }

    private static string Qualified(string @namespace, string name) => @namespace.Length == 0 ? name : @namespace + "." + name;

    /// <summary>The nullable context a member falls back to: `[NullableContext]` of it, else of the type, else of the types around.</summary>
    private byte Context(CustomAttributeHandleCollection custom, byte outer)
    {
        foreach (var handle in custom)
        {
            var attribute = reader.GetCustomAttribute(handle);
            if (AttributeType(attribute) != CompilerServices + ".NullableContextAttribute") continue;
            var blob = reader.GetBlobReader(attribute.Value);
            if (blob.Length >= 3 && blob.ReadUInt16() == 1) return blob.ReadByte();
        }
        return outer;
    }

    private byte DefaultContext(TypeDefinition type) =>
        Context(type.GetCustomAttributes(), type.IsNested ? DefaultContext(reader.GetTypeDefinition(type.GetDeclaringType())) : (byte)0);

    private List<string> AttributeNames(CustomAttributeHandleCollection custom)
    {
        var names = new List<string>();
        foreach (var handle in custom)
        {
            var name = AttributeType(reader.GetCustomAttribute(handle));
            if (name.Length > 0 && !Decoded.Contains(name) && !names.Contains(name)) names.Add(name);
        }
        return names;
    }

    /// <summary>`[EditorBrowsable(EditorBrowsableState.Never)]`: what its author does not want to be offered.</summary>
    private bool IsHidden(CustomAttributeHandleCollection attributes)
    {
        foreach (var handle in attributes)
        {
            var attribute = reader.GetCustomAttribute(handle);
            if (AttributeType(attribute) != "System.ComponentModel.EditorBrowsableAttribute") continue;
            var blob = reader.GetBlobReader(attribute.Value);
            // the prolog, then the one argument of the constructor: the state as an int, Never = 1
            if (blob.Length >= 6 && blob.ReadUInt16() == 1 && blob.ReadInt32() == 1) return true;
        }
        return false;
    }

    private bool Has(CustomAttributeHandleCollection attributes, string @namespace, string name)
    {
        var full = @namespace + "." + name;
        foreach (var handle in attributes)
        {
            if (AttributeType(reader.GetCustomAttribute(handle)) == full) return true;
        }
        return false;
    }

    private readonly Dictionary<EntityHandle, string> _attributeTypes = new();

    /// <summary>The full name of the type of an attribute, by its constructor: a few constructors serve all the attributes of an assembly.</summary>
    private string AttributeType(CustomAttribute attribute)
    {
        if (_attributeTypes.TryGetValue(attribute.Constructor, out var known)) return known;
        var name = attribute.Constructor.Kind switch
        {
            HandleKind.MemberReference => FullName(reader.GetMemberReference((MemberReferenceHandle)attribute.Constructor).Parent),
            HandleKind.MethodDefinition => FullName(reader.GetMethodDefinition((MethodDefinitionHandle)attribute.Constructor).GetDeclaringType()),
            _ => "",
        };
        _attributeTypes[attribute.Constructor] = name;
        return name;
    }
}

/// <summary>A type as a signature has it, before it is written down (<see cref="TypeRefs"/>).</summary>
public abstract record Sig;

/// <summary>`System.Collections.Generic.Dictionary`2+Enumerator`, and whether it is a value type (the signature says so).</summary>
public sealed record NamedSig(string Name, bool ValueType) : Sig;

public sealed record GenericSig(NamedSig Definition, ImmutableArray<Sig> Arguments) : Sig;

public sealed record ParameterSig(int Index, bool OfMethod) : Sig;

/// <summary>Rank 0 is the array of one dimension, `T[]`.</summary>
public sealed record ArraySig(Sig Element, int Rank) : Sig;

public sealed record PointerSig(Sig Element) : Sig;

public sealed record ByRefSig(Sig Element) : Sig;

public sealed record FunctionPointerSig(Sig Return, ImmutableArray<Sig> Parameters) : Sig;

/// <summary>`modreq` / `modopt`: `IsExternalInit` of an init accessor, `InAttribute` of `in` and `ref readonly`.</summary>
public sealed record ModifiedSig(string Modifier, Sig Inner) : Sig;

public sealed class SignatureProvider : ISignatureTypeProvider<Sig, object?>
{
    public Sig GetPrimitiveType(PrimitiveTypeCode typeCode) => typeCode switch
    {
        PrimitiveTypeCode.String => new NamedSig("System.String", false),
        PrimitiveTypeCode.Object => new NamedSig("System.Object", false),
        PrimitiveTypeCode.IntPtr => new NamedSig("System.IntPtr", true),
        PrimitiveTypeCode.UIntPtr => new NamedSig("System.UIntPtr", true),
        _ => new NamedSig("System." + typeCode, true),
    };

    public Sig GetTypeFromDefinition(MetadataReader metadata, TypeDefinitionHandle handle, byte rawTypeKind)
    {
        var type = metadata.GetTypeDefinition(handle);
        var name = metadata.GetString(type.Name);
        var full = type.IsNested
            ? ((NamedSig)GetTypeFromDefinition(metadata, type.GetDeclaringType(), 0)).Name + "+" + name
            : Qualified(metadata.GetString(type.Namespace), name);
        return new NamedSig(full, rawTypeKind == (byte)SignatureTypeKind.ValueType);
    }

    public Sig GetTypeFromReference(MetadataReader metadata, TypeReferenceHandle handle, byte rawTypeKind)
    {
        var type = metadata.GetTypeReference(handle);
        var name = metadata.GetString(type.Name);
        var full = type.ResolutionScope.Kind == HandleKind.TypeReference
            ? ((NamedSig)GetTypeFromReference(metadata, (TypeReferenceHandle)type.ResolutionScope, 0)).Name + "+" + name
            : Qualified(metadata.GetString(type.Namespace), name);
        return new NamedSig(full, rawTypeKind == (byte)SignatureTypeKind.ValueType);
    }

    public Sig GetTypeFromSpecification(MetadataReader metadata, object? context, TypeSpecificationHandle handle, byte rawTypeKind) =>
        metadata.GetTypeSpecification(handle).DecodeSignature(this, context);

    public Sig GetSZArrayType(Sig elementType) => new ArraySig(elementType, 0);

    public Sig GetArrayType(Sig elementType, ArrayShape shape) => new ArraySig(elementType, Math.Max(1, shape.Rank));

    public Sig GetByReferenceType(Sig elementType) => new ByRefSig(elementType);

    public Sig GetPointerType(Sig elementType) => new PointerSig(elementType);

    public Sig GetPinnedType(Sig elementType) => elementType;

    public Sig GetModifiedType(Sig modifier, Sig unmodifiedType, bool isRequired) => new ModifiedSig((modifier as NamedSig)?.Name ?? "", unmodifiedType);

    public Sig GetFunctionPointerType(MethodSignature<Sig> signature) => new FunctionPointerSig(signature.ReturnType, signature.ParameterTypes);

    public Sig GetGenericInstantiation(Sig genericType, ImmutableArray<Sig> typeArguments) =>
        genericType is NamedSig named ? new GenericSig(named, typeArguments) : genericType;

    public Sig GetGenericTypeParameter(object? context, int index) => new ParameterSig(index, false);

    public Sig GetGenericMethodParameter(object? context, int index) => new ParameterSig(index, true);

    private static string Qualified(string @namespace, string name) => @namespace.Length == 0 ? name : @namespace + "." + name;
}

/// <summary>
/// The nullable annotations and the names of tuple elements of one type in a signature: `[Nullable]` gives a byte per type in
/// pre-order (a value type but a generic one takes none: its type arguments do), `[NullableContext]` the byte of the rest;
/// `[TupleElementNames]` the names of the elements of the tuples, in pre-order as well.
/// </summary>
public sealed class Annotations
{
    public static readonly Annotations None = new(null, 0, null);

    private readonly byte[]? _bytes;
    private readonly byte _single;
    private readonly string?[]? _names;
    private int _byte;
    private int _name;

    private Annotations(byte[]? bytes, byte single, string?[]? names)
    {
        _bytes = bytes;
        _single = single;
        _names = names;
    }

    public static Annotations Of(MetadataReader reader, Func<CustomAttribute, string> typeOf, CustomAttributeHandleCollection? custom, byte context)
    {
        byte[]? bytes = null;
        var single = context;
        string?[]? names = null;
        if (custom != null)
        {
            foreach (var handle in custom.Value)
            {
                var attribute = reader.GetCustomAttribute(handle);
                var type = typeOf(attribute);
                if (type == "System.Runtime.CompilerServices.NullableAttribute")
                {
                    var blob = reader.GetBlobReader(attribute.Value);
                    if (blob.Length < 3 || blob.ReadUInt16() != 1) continue;
                    // `[Nullable(1)]`: the prolog, a byte and no named arguments; `[Nullable(new byte[] { 1, 2 })]`: a count before
                    if (blob.Length == 5) single = blob.ReadByte();
                    else
                    {
                        var count = blob.ReadInt32();
                        if (count < 0 || count > blob.RemainingBytes) continue;
                        bytes = blob.ReadBytes(count);
                    }
                }
                else if (type == "System.Runtime.CompilerServices.TupleElementNamesAttribute")
                {
                    var blob = reader.GetBlobReader(attribute.Value);
                    if (blob.Length < 6 || blob.ReadUInt16() != 1) continue;
                    var count = blob.ReadInt32();
                    if (count < 0 || count > blob.RemainingBytes) continue;
                    names = new string?[count];
                    for (var i = 0; i < count; i++) names[i] = blob.ReadSerializedString();
                }
            }
        }
        return bytes == null && single == 0 && names == null ? None : new Annotations(bytes, single, names);
    }

    /// <summary>0 oblivious, 1 not annotated, 2 annotated (`string?`).</summary>
    public byte Next()
    {
        if (this == None) return 0;
        if (_bytes == null) return _single;
        return _byte < _bytes.Length ? _bytes[_byte++] : (byte)0;
    }

    public string?[]? Names(int count)
    {
        if (_names == null) return null;
        var taken = new string?[count];
        for (var i = 0; i < count; i++) taken[i] = _name < _names.Length ? _names[_name++] : null;
        return taken.Any(name => name != null) ? taken : null;
    }
}

/// <summary>
/// A reference to a type, written as a string (so that the pool of strings shares the same ones) by a small prefix grammar the
/// plugin parses when it needs it (`IndexedTypeRef.parse`):
/// <code>
/// ref  := '?' ref                                annotated: `string?`, `T?` of a type parameter that is not a struct
///       | 'N' name ';'                           a reference type: `NSystem.String;`, nested: `NSystem.Environment+SpecialFolder;`
///       | 'V' name ';'                           a value type
///       | 'I' count ['{' names '}'] ':' ref ref*  a generic instantiation: the definition (N or V), then its arguments — the ones
///                                                of the types around a nested type first; the names of the elements of a tuple
///                                                (all of them for a long one, empty for the unnamed ones), comma-separated
///       | '!' index ';'                          a type parameter of the type
///       | 'M' index ';'                          a type parameter of the method
///       | '[' ref                                `T[]`
///       | 'A' rank ';' ref                       `T[,]`
///       | '*' ref                                `T*`
///       | '&amp;' ref                                `ref T` (a return; parameters say it by their flags)
///       | 'F' count ':' ref ref*                 `delegate*`: the return, then the parameters
/// </code>
/// </summary>
public static class TypeRefs
{
    public static string Encode(Sig sig, Annotations annotations)
    {
        var builder = new StringBuilder();
        Write(sig, annotations, builder);
        return builder.ToString();
    }

    public static Sig Unwrap(Sig sig)
    {
        while (sig is ModifiedSig modified) sig = modified.Inner;
        return sig;
    }

    /// <summary>What an extension method extends, for the table the plugin finds them in by the type of the receiver.</summary>
    public static string ExtensionKey(Sig sig)
    {
        sig = Unwrap(sig);
        if (sig is ByRefSig byRef) sig = Unwrap(byRef.Element);
        return sig switch
        {
            NamedSig named => named.Name,
            GenericSig generic => generic.Definition.Name,
            ArraySig => "[]",
            ParameterSig => "!",
            _ => "",
        };
    }

    private static void Write(Sig sig, Annotations annotations, StringBuilder builder)
    {
        switch (sig)
        {
            case ModifiedSig modified:
                Write(modified.Inner, annotations, builder);
                break;
            case ByRefSig byRef:
                builder.Append('&');
                Write(byRef.Element, annotations, builder);
                break;
            case PointerSig pointer:
                builder.Append('*');
                Write(pointer.Element, annotations, builder);
                break;
            case FunctionPointerSig function:
                builder.Append('F').Append(function.Parameters.Length).Append(':');
                Write(function.Return, annotations, builder);
                foreach (var parameter in function.Parameters) Write(parameter, annotations, builder);
                break;
            case NamedSig named:
                if (!named.ValueType && annotations.Next() == 2) builder.Append('?');
                Named(named, builder);
                break;
            case GenericSig generic:
                if (!generic.Definition.ValueType && annotations.Next() == 2) builder.Append('?');
                builder.Append('I').Append(generic.Arguments.Length);
                if (IsTuple(generic))
                {
                    // the rest of a long tuple is a tuple of its own here and takes its names, nulls, after the ones of the whole
                    var names = annotations.Names(TupleSize(generic));
                    if (names != null) builder.Append('{').Append(string.Join(",", names.Select(name => name ?? ""))).Append('}');
                }
                builder.Append(':');
                Named(generic.Definition, builder);
                foreach (var argument in generic.Arguments) Write(argument, annotations, builder);
                break;
            case ParameterSig parameter:
                if (annotations.Next() == 2) builder.Append('?');
                builder.Append(parameter.OfMethod ? 'M' : '!').Append(parameter.Index).Append(';');
                break;
            case ArraySig array:
                if (annotations.Next() == 2) builder.Append('?');
                if (array.Rank == 0) builder.Append('[');
                else builder.Append('A').Append(array.Rank).Append(';');
                Write(array.Element, annotations, builder);
                break;
        }
    }

    private static void Named(NamedSig named, StringBuilder builder) => builder.Append(named.ValueType ? 'V' : 'N').Append(named.Name).Append(';');

    private static bool IsTuple(GenericSig generic) => generic.Definition.Name.StartsWith("System.ValueTuple`", StringComparison.Ordinal);

    /// <summary>The elements of a tuple; one of more than seven has the rest in its eighth argument.</summary>
    private static int TupleSize(GenericSig generic) =>
        generic.Arguments.Length == 8 && Unwrap(generic.Arguments[7]) is GenericSig rest && IsTuple(rest) ? 7 + TupleSize(rest) : generic.Arguments.Length;
}

/// <summary>Constants and default values as C# writes them: `42`, `"text"`, `'c'`, `true`, `null`, `1.5F`.</summary>
public static class Literals
{
    public static string? Of(MetadataReader reader, ConstantHandle handle)
    {
        var constant = reader.GetConstant(handle);
        var blob = reader.GetBlobReader(constant.Value);
        var invariant = CultureInfo.InvariantCulture;
        return constant.TypeCode switch
        {
            ConstantTypeCode.Boolean => blob.ReadBoolean() ? "true" : "false",
            ConstantTypeCode.Char => Quote(blob.ReadChar().ToString(), '\''),
            ConstantTypeCode.SByte => blob.ReadSByte().ToString(invariant),
            ConstantTypeCode.Byte => blob.ReadByte().ToString(invariant),
            ConstantTypeCode.Int16 => blob.ReadInt16().ToString(invariant),
            ConstantTypeCode.UInt16 => blob.ReadUInt16().ToString(invariant),
            ConstantTypeCode.Int32 => blob.ReadInt32().ToString(invariant),
            ConstantTypeCode.UInt32 => blob.ReadUInt32().ToString(invariant),
            ConstantTypeCode.Int64 => blob.ReadInt64().ToString(invariant),
            ConstantTypeCode.UInt64 => blob.ReadUInt64().ToString(invariant),
            ConstantTypeCode.Single => Floating(blob.ReadSingle(), "float", "F"),
            ConstantTypeCode.Double => Floating(blob.ReadDouble(), "double", ""),
            ConstantTypeCode.String => Quote(blob.Length == 0 ? "" : blob.ReadUTF16(blob.Length), '"'),
            ConstantTypeCode.NullReference => "null",
            _ => null,
        };
    }

    private static string Floating(double value, string keyword, string suffix)
    {
        if (double.IsNaN(value)) return keyword + ".NaN";
        if (double.IsPositiveInfinity(value)) return keyword + ".PositiveInfinity";
        if (double.IsNegativeInfinity(value)) return keyword + ".NegativeInfinity";
        var text = suffix == "F" ? ((float)value).ToString("R", CultureInfo.InvariantCulture) : value.ToString("R", CultureInfo.InvariantCulture);
        return text + suffix;
    }

    private static string Quote(string value, char quote)
    {
        var builder = new StringBuilder().Append(quote);
        foreach (var c in value)
        {
            switch (c)
            {
                case '\\': builder.Append("\\\\"); break;
                case '\n': builder.Append("\\n"); break;
                case '\r': builder.Append("\\r"); break;
                case '\t': builder.Append("\\t"); break;
                case '\0': builder.Append("\\0"); break;
                default:
                    if (c == quote) builder.Append('\\').Append(c);
                    else if (char.IsControl(c) || char.IsSurrogate(c)) builder.Append("\\u").Append(((int)c).ToString("X4", CultureInfo.InvariantCulture));
                    else builder.Append(c);
                    break;
            }
        }
        return builder.Append(quote).ToString();
    }
}

/// <summary>
/// The file of an index. Everything is an int of 4 bytes, little-endian; a list is an index into the table of ints, where its count
/// is followed by its items (0 is the empty list); -1 is no string, no reference, no type.
/// <code>
/// header    "DNIX", version, mvid (16 bytes), assembly name, assembly version,
///           strings, types, members, parameters, generics, ints, names, extensions: for each its count and the offset of its table,
///           all the types, all the members (format 4: the counts above are of what other assemblies see), the friend assemblies
///           (list of InternalsVisibleTo: `Name`, `Name,key` when it needs a key)                              108 bytes
/// strings   count + 1 offsets into the data that follows them, UTF-8
/// types     namespace, path (`Dictionary`2+Enumerator`), kind | flags &lt;&lt; 8, declaring type, base (reference),
///           interfaces (list of references), first generic, generic count, first member, member count, nested (list of types),
///           attributes (list of names), underlying type of an enum, first and count of the members only a friend sees,
///           the nested types only a friend or no one sees (list)                                                 64 bytes
///           the types other assemblies see sorted by the namespace, then by the path: a type by its name and the types of a
///           namespace by a binary search; after them the internal and private ones (no members for a private one), sorted so too
/// members   name, type, kind | flags &lt;&lt; 8, type (reference), first parameter, parameter count, first generic,
///           generic count, attributes (list of names), value, nullability                                        44 bytes
///           the ones of a type together, in the order of the types: first the members everyone sees of the types everyone sees,
///           then the internal and private protected members, and all the members of the internal types
/// params    type (reference), name, flags, default value                                                        16 bytes
/// generics  name, flags, constraints (list of references)                                                       12 bytes
/// names     name, target: a type, or a member with the highest bit set                                           8 bytes
///           the types and the static members other code sees (not the protected ones), sorted by the name in lower case,
///           then by the name: the table a prefix is searched in
/// extensions  what the `this` parameter is (TypeRefs.ExtensionKey), member; sorted by the first                  8 bytes
/// </code>
/// The references are strings of the grammar of <see cref="TypeRefs"/>.
/// </summary>
public static class IndexWriter
{
    public const int MemberBit = unchecked((int)0x80000000);
    public const int HeaderSize = 4 + 4 + 16 + 4 + 4 + 8 * 8 + 3 * 4;
    public const int TypeSize = 16 * 4;

    /// <summary>A type other assemblies see (public, or nested protected): not only a friend, not nobody.</summary>
    private static bool IsSeen(TypeEntry type) => (type.Flags & (TypeFlags.Internal | TypeFlags.Private)) == 0;

    public static void Write(string file, Guid mvid, AssemblyData data)
    {
        var strings = new StringPool();
        var ints = new IntLists();
        int Ref(string? value) => value == null ? -1 : strings.Id(value);
        int Refs(IEnumerable<string> values) => ints.Id(values.Select(strings.Id).ToList());

        // what other assemblies see first, sorted; then what only a friend sees or no one, sorted on its own (format 4)
        var types = data.Types.Where(IsSeen)
            .OrderBy(type => type.Namespace, StringComparer.Ordinal).ThenBy(type => type.Path, StringComparer.Ordinal)
            .ToList();
        var seenTypes = types.Count;
        types.AddRange(data.Types.Where(type => !IsSeen(type))
            .OrderBy(type => type.Namespace, StringComparer.Ordinal).ThenBy(type => type.Path, StringComparer.Ordinal));
        var typeIndex = new Dictionary<TypeEntry, int>(ReferenceEqualityComparer.Instance);
        for (var i = 0; i < types.Count; i++) typeIndex[types[i]] = i;

        var typeRows = new List<int[]>(types.Count);
        var memberRows = new List<int[]>();
        var parameterRows = new List<int[]>();
        var genericRows = new List<int[]>();
        var names = new List<(string Name, int Target)>();
        var extensions = new List<(string Key, int Member)>();

        int Generics(List<GenericEntry> generics)
        {
            var first = genericRows.Count;
            foreach (var generic in generics) genericRows.Add([strings.Id(generic.Name), (int)generic.Flags, Refs(generic.Constraints)]);
            return first;
        }

        // the members everyone sees of the types everyone sees first: the rows below the count of the header; the rest after them
        int AddMembers(int t, IEnumerable<MemberEntry> members, bool offered)
        {
            var first = memberRows.Count;
            foreach (var member in members)
            {
                var row = memberRows.Count;
                var firstParameter = parameterRows.Count;
                foreach (var parameter in member.Parameters) parameterRows.Add([strings.Id(parameter.Type), strings.Id(parameter.Name), (int)parameter.Flags, Ref(parameter.Default)]);
                var memberGeneric = Generics(member.Generics);
                memberRows.Add([
                    strings.Id(member.Name), t, (byte)member.Kind | ((int)member.Flags << 8), strings.Id(member.Type), firstParameter, member.Parameters.Count,
                    memberGeneric, member.Generics.Count, Refs(member.Attributes), Ref(member.Value), Ref(member.Nullability),
                ]);
                // what import completion offers: a static member other code sees, called by the name of its type
                if (offered && (member.Flags & (MemberFlags.Static | MemberFlags.Protected)) == MemberFlags.Static && member.Kind is not (MemberKind.Constructor or MemberKind.Operator))
                    names.Add((member.Name, row | MemberBit));
                if (member.ExtensionKey != null) extensions.Add((member.ExtensionKey, row));
            }
            return first;
        }

        var memberRanges = new (int First, int Count, int FirstFriend, int FriendCount)[types.Count];
        for (var t = 0; t < seenTypes; t++)
        {
            var seen = types[t].Members.Where(member => !AccessBits.IsFriendOnly(member.Flags)).ToList();
            memberRanges[t] = (AddMembers(t, seen, (types[t].Flags & TypeFlags.Protected) == 0), seen.Count, 0, 0);
        }
        var seenMembers = memberRows.Count;
        for (var t = 0; t < types.Count; t++)
        {
            if (t >= seenTypes)
            {
                var own = types[t].Members.Where(member => !AccessBits.IsFriendOnly(member.Flags)).ToList();
                memberRanges[t] = (AddMembers(t, own, false), own.Count, 0, 0);
            }
            var friend = types[t].Members.Where(member => AccessBits.IsFriendOnly(member.Flags)).ToList();
            memberRanges[t] = memberRanges[t] with { FirstFriend = AddMembers(t, friend, false), FriendCount = friend.Count };
        }

        for (var t = 0; t < types.Count; t++)
        {
            var type = types[t];
            var firstGeneric = Generics(type.Generics);
            if (t < seenTypes && (type.Flags & TypeFlags.Protected) == 0) names.Add((SimpleName(type.Path), t));
            var range = memberRanges[t];
            typeRows.Add([
                strings.Id(type.Namespace), strings.Id(type.Path), (byte)type.Kind | ((int)type.Flags << 8), type.Declaring == null ? -1 : typeIndex[type.Declaring],
                Ref(type.Base), Refs(type.Interfaces), firstGeneric, type.Generics.Count, range.First, range.Count,
                ints.Id(type.Nested.Where(IsSeen).Select(nested => typeIndex[nested]).OrderBy(index => index).ToList()), Refs(type.Attributes), Ref(type.Underlying),
                range.FirstFriend, range.FriendCount, ints.Id(type.Nested.Where(nested => !IsSeen(nested)).Select(nested => typeIndex[nested]).OrderBy(index => index).ToList()),
            ]);
        }
        // a nested type is found by its own name: `Enumerator` of `Dictionary.Enumerator`
        var sortedNames = names
            .Select(name => (Key: name.Name.ToLowerInvariant(), name.Name, Id: strings.Id(name.Name), name.Target))
            .OrderBy(name => name.Key, StringComparer.Ordinal).ThenBy(name => name.Name, StringComparer.Ordinal).ThenBy(name => name.Target & ~MemberBit)
            .ToList();
        var sortedExtensions = extensions
            .Select(extension => (extension.Key, Id: strings.Id(extension.Key), extension.Member))
            .OrderBy(extension => extension.Key, StringComparer.Ordinal).ThenBy(extension => extension.Member)
            .ToList();
        var assembly = strings.Id(data.Name);
        var version = strings.Id(data.Version);
        var friends = Refs(data.InternalsVisibleTo);

        var stringTable = HeaderSize;
        var stringData = stringTable + (strings.Count + 1) * 4;
        var typeTable = Align(stringData + strings.Bytes);
        var memberTable = typeTable + typeRows.Count * TypeSize;
        var parameterTable = memberTable + memberRows.Count * 44;
        var genericTable = parameterTable + parameterRows.Count * 16;
        var intTable = genericTable + genericRows.Count * 12;
        var nameTable = intTable + ints.Count * 4;
        var extensionTable = nameTable + sortedNames.Count * 8;

        using var stream = new FileStream(file, FileMode.Create, FileAccess.Write, FileShare.None);
        using var writer = new BinaryWriter(new BufferedStream(stream, 1 << 16), Encoding.UTF8);
        writer.Write("DNIX"u8);
        writer.Write(Program.FormatVersion);
        writer.Write(mvid.ToByteArray());
        writer.Write(assembly);
        writer.Write(version);
        foreach (var (count, offset) in new[]
                 {
                     (strings.Count, stringTable), (seenTypes, typeTable), (seenMembers, memberTable), (parameterRows.Count, parameterTable),
                     (genericRows.Count, genericTable), (ints.Count, intTable), (sortedNames.Count, nameTable), (sortedExtensions.Count, extensionTable),
                 })
        {
            writer.Write(count);
            writer.Write(offset);
        }
        writer.Write(typeRows.Count);
        writer.Write(memberRows.Count);
        writer.Write(friends);
        var position = 0;
        foreach (var bytes in strings.Encoded)
        {
            writer.Write(position);
            position += bytes.Length;
        }
        writer.Write(position);
        foreach (var bytes in strings.Encoded) writer.Write(bytes);
        for (var at = stringData + strings.Bytes; at < typeTable; at++) writer.Write((byte)0);
        foreach (var rows in new[] { typeRows, memberRows, parameterRows, genericRows })
        {
            foreach (var row in rows)
            {
                foreach (var value in row) writer.Write(value);
            }
        }
        foreach (var value in ints.Values) writer.Write(value);
        foreach (var name in sortedNames)
        {
            writer.Write(name.Id);
            writer.Write(name.Target);
        }
        foreach (var extension in sortedExtensions)
        {
            writer.Write(extension.Id);
            writer.Write(extension.Member);
        }
    }

    private static int Align(int offset) => (offset + 3) & ~3;

    /// <summary>`Dictionary`2+Enumerator` -> `Enumerator`, `List`1` -> `List`.</summary>
    private static string SimpleName(string path)
    {
        var name = path[(path.LastIndexOf('+') + 1)..];
        var mark = name.IndexOf('`');
        return mark >= 0 ? name[..mark] : name;
    }

    private sealed class StringPool
    {
        private readonly Dictionary<string, int> _ids = new(StringComparer.Ordinal);
        public readonly List<byte[]> Encoded = new();
        public int Bytes;
        public int Count => Encoded.Count;

        public int Id(string value)
        {
            if (_ids.TryGetValue(value, out var id)) return id;
            id = Encoded.Count;
            var bytes = Encoding.UTF8.GetBytes(value);
            Encoded.Add(bytes);
            Bytes += bytes.Length;
            _ids[value] = id;
            return id;
        }
    }

    /// <summary>Lists of ints, the same list stored once: a count, then the items. The first is the empty one.</summary>
    private sealed class IntLists
    {
        private readonly Dictionary<string, int> _ids = new(StringComparer.Ordinal) { [""] = 0 };
        public readonly List<int> Values = [0];
        public int Count => Values.Count;

        public int Id(List<int> items)
        {
            var key = string.Join(",", items);
            if (_ids.TryGetValue(key, out var id)) return id;
            id = Values.Count;
            Values.Add(items.Count);
            Values.AddRange(items);
            _ids[key] = id;
            return id;
        }
    }
}

/// <summary>
/// The XML documentation of an assembly (`System.Console.xml` next to `System.Console.dll`), by the documentation ID:
/// <code>
/// header   "DNXD", version, mvid (16 bytes), number of entries, number of blocks, offset of the blocks          36 bytes
/// blocks   offset of the first ID, its length, offset of the compressed block, its length, its length inflated   20 bytes
/// data     the first ID of every block (UTF-8), then the blocks (raw deflate)
/// </code>
/// A block inflated is entries sorted by the ID (ordinal, as UTF-8 bytes), each an ID, byte 1, the text, byte 0; the blocks follow
/// the same order, so an ID is found by a binary search over the first IDs and a scan of one block of about <see cref="BlockSize"/>.
/// A text is the inner XML of the `member` element, its runs of white space made one space: `summary`, `param`, `returns` as they are.
/// </summary>
public static class DocWriter
{
    /// <summary>The runs of white space one space, the ends trimmed: the indentation of the file is most of its size.</summary>
    private static string Collapse(string text)
    {
        var builder = new StringBuilder(text.Length);
        var space = false;
        foreach (var c in text)
        {
            if (char.IsWhiteSpace(c))
            {
                space = builder.Length > 0;
                continue;
            }
            if (space) builder.Append(' ');
            space = false;
            builder.Append(c);
        }
        return builder.ToString();
    }

    public static List<(string Id, string Text)> Read(string file)
    {
        var entries = new List<(string, string)>();
        try
        {
            using var reader = XmlReader.Create(file, new XmlReaderSettings { DtdProcessing = DtdProcessing.Ignore, IgnoreComments = true, XmlResolver = null });
            while (reader.Read())
            {
                while (reader.NodeType == XmlNodeType.Element && reader.Name == "member")
                {
                    var id = reader.GetAttribute("name");
                    var text = Collapse(reader.ReadInnerXml());
                    if (!string.IsNullOrEmpty(id) && text.Length > 0) entries.Add((id, text));
                }
            }
        }
        catch (XmlException)
        {
            // a broken documentation is no documentation; the index is made all the same
        }
        return entries;
    }

    /// <summary>The raw size a block of entries is compressed at: big enough for deflate to find the repeats, small enough to inflate for one entry.</summary>
    public const int BlockSize = 16 * 1024;

    public static void Write(string file, Guid mvid, List<(string Id, string Text)> entries)
    {
        var sorted = entries
            .Select(entry => (Id: Encoding.UTF8.GetBytes(entry.Id), Text: Encoding.UTF8.GetBytes(entry.Text)))
            .GroupBy(entry => Convert.ToHexString(entry.Id)).Select(group => group.First())
            .OrderBy(entry => entry.Id, ByteComparer.Instance)
            .ToList();
        var firstIds = new MemoryStream();
        var compressed = new MemoryStream();
        var blocks = new List<(int FirstId, int FirstIdLength, int At, int Length, int Raw)>();
        var raw = new MemoryStream();
        byte[]? first = null;
        void Flush()
        {
            if (first == null) return;
            var idAt = (int)firstIds.Position;
            firstIds.Write(first);
            var at = (int)compressed.Position;
            using (var deflate = new DeflateStream(compressed, CompressionLevel.Optimal, leaveOpen: true)) deflate.Write(raw.GetBuffer(), 0, (int)raw.Length);
            blocks.Add((idAt, first.Length, at, (int)compressed.Position - at, (int)raw.Length));
            raw.SetLength(0);
            first = null;
        }
        foreach (var (id, text) in sorted)
        {
            first ??= id;
            raw.Write(id);
            raw.WriteByte(1);
            raw.Write(text);
            raw.WriteByte(0);
            if (raw.Length >= BlockSize) Flush();
        }
        Flush();

        const int header = 36;
        var idsAt = header + blocks.Count * 20;
        var dataAt = idsAt + (int)firstIds.Length;
        using var stream = new FileStream(file, FileMode.Create, FileAccess.Write, FileShare.None);
        using var writer = new BinaryWriter(stream);
        writer.Write("DNXD"u8);
        writer.Write(Program.FormatVersion);
        writer.Write(mvid.ToByteArray());
        writer.Write(sorted.Count);
        writer.Write(blocks.Count);
        writer.Write(header);
        foreach (var block in blocks)
        {
            writer.Write(idsAt + block.FirstId);
            writer.Write(block.FirstIdLength);
            writer.Write(dataAt + block.At);
            writer.Write(block.Length);
            writer.Write(block.Raw);
        }
        writer.Write(firstIds.GetBuffer(), 0, (int)firstIds.Length);
        writer.Write(compressed.GetBuffer(), 0, (int)compressed.Length);
    }

    private sealed class ByteComparer : IComparer<byte[]>
    {
        public static readonly ByteComparer Instance = new();
        public int Compare(byte[]? x, byte[]? y) => x.AsSpan().SequenceCompareTo(y.AsSpan());
    }
}
