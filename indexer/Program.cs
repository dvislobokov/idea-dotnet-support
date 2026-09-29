using System.Collections.Immutable;
using System.Diagnostics;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Text;
using System.Text.Json;

namespace DotNetSupport.Indexer;

// The index of an assembly: its public types and their public static members (extension methods, constants and enum members among
// them), read from the metadata alone — nothing is loaded, nothing of the assembly runs. One file per assembly, named by the MVID of
// the module: a package of NuGet and a reference pack of the SDK never change, so neither does their index, and a project is a list
// of such files. The format is fixed-size records behind one header, little-endian, read by the plugin through a mapped buffer
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
    public const int FormatVersion = 1;

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
        int indexed = 0, skipped = 0, failed = 0, types = 0, members = 0;
        long bytes = 0;
        foreach (var path in paths)
        {
            var watch = Stopwatch.StartNew();
            var report = new Dictionary<string, object?> { ["path"] = path };
            try
            {
                var result = Index(path, output, force);
                report["mvid"] = result.Mvid;
                report["index"] = result.File;
                if (result.Written == null)
                {
                    skipped++;
                    report["skipped"] = true;
                }
                else
                {
                    indexed++;
                    types += result.Written.Types.Count;
                    members += result.Written.Members.Count;
                    bytes += result.Bytes;
                    report["types"] = result.Written.Types.Count;
                    report["members"] = result.Written.Members.Count;
                    report["bytes"] = result.Bytes;
                }
            }
            catch (Exception e) when (e is BadImageFormatException or IOException or UnauthorizedAccessException or InvalidOperationException)
            {
                // a native dll, a resource-only assembly, a file that is being written: not what is indexed
                failed++;
                report["error"] = e.GetType().Name + ": " + e.Message;
            }
            report["ms"] = Math.Round(watch.Elapsed.TotalMilliseconds, 1);
            Console.WriteLine(JsonSerializer.Serialize(report));
        }
        Console.WriteLine(JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["summary"] = true, ["format"] = FormatVersion, ["indexed"] = indexed, ["skipped"] = skipped, ["failed"] = failed,
            ["types"] = types, ["members"] = members, ["bytes"] = bytes, ["ms"] = Math.Round(total.Elapsed.TotalMilliseconds, 1),
            ["waited"] = Math.Round(waited, 1),
        }));
        return 0;
    }

    private sealed record Indexed(string Mvid, string File, AssemblyData? Written, long Bytes);

    private static Indexed Index(string path, string output, bool force)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        using var pe = new PEReader(stream);
        if (!pe.HasMetadata) throw new BadImageFormatException("no metadata");
        var reader = pe.GetMetadataReader();
        var mvid = reader.GetGuid(reader.GetModuleDefinition().Mvid);
        var file = Path.Combine(output, mvid.ToString("N") + ".dnix");
        if (!force && File.Exists(file)) return new Indexed(mvid.ToString("N"), file, null, 0);

        var data = new MetadataScanner(reader).Scan();
        var temporary = file + "." + Environment.ProcessId + ".tmp";
        IndexWriter.Write(temporary, mvid, data);
        var length = new FileInfo(temporary).Length;
        // another process may have written the same index meanwhile: it is the same one
        File.Move(temporary, file, overwrite: true);
        return new Indexed(mvid.ToString("N"), file, data, length);
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

public enum MemberKind : byte { Method = 1, ExtensionMethod = 2, Property = 3, Field = 4, Constant = 5, EnumMember = 6 }

[Flags]
public enum EntryFlags : byte { None = 0, Obsolete = 1 }

[Flags]
public enum ParameterFlags { None = 0, Optional = 1, Out = 2, Ref = 4, Params = 8, This = 16, In = 32 }

public sealed record TypeEntry(string Namespace, string Name, TypeKind Kind, int Arity, EntryFlags Flags);

public sealed record ParameterEntry(string Type, string Name, ParameterFlags Flags);

public sealed record MemberEntry(string Name, int Type, MemberKind Kind, int Arity, EntryFlags Flags, string ReturnType, List<ParameterEntry> Parameters);

public sealed class AssemblyData
{
    public string Name = "";
    public readonly List<TypeEntry> Types = new();
    public readonly List<MemberEntry> Members = new();
}

/// <summary>Public types and public static members of an assembly, from its metadata tables.</summary>
public sealed class MetadataScanner(MetadataReader reader)
{
    private readonly TypeNames _names = new();

    public AssemblyData Scan()
    {
        var data = new AssemblyData();
        if (reader.IsAssembly) data.Name = reader.GetString(reader.GetAssemblyDefinition().Name);
        foreach (var handle in reader.TypeDefinitions)
        {
            var type = reader.GetTypeDefinition(handle);
            if (!IsPublic(type) || IsHidden(type.GetCustomAttributes())) continue;
            var name = NestedName(type);
            if (name.Contains('<')) continue;
            var (bare, arity) = SplitArity(name);
            var kind = KindOf(type);
            var outermost = type;
            while (outermost.IsNested) outermost = reader.GetTypeDefinition(outermost.GetDeclaringType());
            var index = data.Types.Count;
            data.Types.Add(new TypeEntry(reader.GetString(outermost.Namespace), bare, kind, arity, Flags(type.GetCustomAttributes())));
            if (kind == TypeKind.Delegate) continue;
            var context = new GenericContext(TypeParameters(type.GetGenericParameters()), ImmutableArray<string>.Empty);
            AddMethods(data, index, type, context);
            AddProperties(data, index, type, context);
            AddFields(data, index, type, kind, context);
        }
        return data;
    }

    private void AddMethods(AssemblyData data, int typeIndex, TypeDefinition type, GenericContext typeContext)
    {
        foreach (var handle in type.GetMethods())
        {
            var method = reader.GetMethodDefinition(handle);
            var attributes = method.Attributes;
            if ((attributes & MethodAttributes.MemberAccessMask) != MethodAttributes.Public || (attributes & MethodAttributes.Static) == 0) continue;
            // accessors, operators, constructors
            if ((attributes & (MethodAttributes.SpecialName | MethodAttributes.RTSpecialName)) != 0) continue;
            var custom = method.GetCustomAttributes();
            if (IsHidden(custom)) continue;
            var name = reader.GetString(method.Name);
            if (name.Contains('<')) continue;
            var generic = method.GetGenericParameters();
            var context = typeContext with { MethodParameters = TypeParameters(generic) };
            var signature = method.DecodeSignature(_names.Provider(reader), context);
            var extension = Has(custom, "System.Runtime.CompilerServices", "ExtensionAttribute");
            var parameters = Parameters(method, signature, extension);
            data.Members.Add(new MemberEntry(name, typeIndex, extension ? MemberKind.ExtensionMethod : MemberKind.Method, generic.Count, Flags(custom), signature.ReturnType, parameters));
        }
    }

    private List<ParameterEntry> Parameters(MethodDefinition method, MethodSignature<string> signature, bool extension)
    {
        var names = new string[signature.ParameterTypes.Length];
        var flags = new ParameterFlags[signature.ParameterTypes.Length];
        foreach (var handle in method.GetParameters())
        {
            var parameter = reader.GetParameter(handle);
            // 0 is the return value
            var at = parameter.SequenceNumber - 1;
            if (at < 0 || at >= names.Length) continue;
            names[at] = reader.GetString(parameter.Name);
            if ((parameter.Attributes & ParameterAttributes.Optional) != 0) flags[at] |= ParameterFlags.Optional;
            if ((parameter.Attributes & ParameterAttributes.Out) != 0) flags[at] |= ParameterFlags.Out;
            if ((parameter.Attributes & ParameterAttributes.In) != 0) flags[at] |= ParameterFlags.In;
            if (Has(parameter.GetCustomAttributes(), "System", "ParamArrayAttribute")) flags[at] |= ParameterFlags.Params;
        }
        var result = new List<ParameterEntry>(names.Length);
        for (var i = 0; i < names.Length; i++)
        {
            var type = signature.ParameterTypes[i];
            if (type.StartsWith("ref ", StringComparison.Ordinal))
            {
                type = type[4..];
                // `out` and `in` are by reference as well, and say so by their own flags
                if ((flags[i] & (ParameterFlags.Out | ParameterFlags.In)) == 0) flags[i] |= ParameterFlags.Ref;
            }
            if (extension && i == 0) flags[i] |= ParameterFlags.This;
            result.Add(new ParameterEntry(type, names[i] ?? "arg" + i, flags[i]));
        }
        return result;
    }

    private void AddProperties(AssemblyData data, int typeIndex, TypeDefinition type, GenericContext context)
    {
        foreach (var handle in type.GetProperties())
        {
            var property = reader.GetPropertyDefinition(handle);
            var getter = property.GetAccessors().Getter;
            if (getter.IsNil) continue;
            var accessor = reader.GetMethodDefinition(getter);
            if ((accessor.Attributes & MethodAttributes.MemberAccessMask) != MethodAttributes.Public || (accessor.Attributes & MethodAttributes.Static) == 0) continue;
            var custom = property.GetCustomAttributes();
            if (IsHidden(custom)) continue;
            var signature = property.DecodeSignature(_names.Provider(reader), context);
            // an indexer is not called by a name
            if (signature.ParameterTypes.Length > 0) continue;
            data.Members.Add(new MemberEntry(reader.GetString(property.Name), typeIndex, MemberKind.Property, 0, Flags(custom), signature.ReturnType, new List<ParameterEntry>()));
        }
    }

    private void AddFields(AssemblyData data, int typeIndex, TypeDefinition type, TypeKind kind, GenericContext context)
    {
        foreach (var handle in type.GetFields())
        {
            var field = reader.GetFieldDefinition(handle);
            var attributes = field.Attributes;
            if ((attributes & FieldAttributes.FieldAccessMask) != FieldAttributes.Public || (attributes & FieldAttributes.Static) == 0) continue;
            var custom = field.GetCustomAttributes();
            if (IsHidden(custom)) continue;
            var name = reader.GetString(field.Name);
            if (name.Contains('<')) continue;
            var memberKind = kind == TypeKind.Enum ? MemberKind.EnumMember : (attributes & FieldAttributes.Literal) != 0 ? MemberKind.Constant : MemberKind.Field;
            data.Members.Add(new MemberEntry(name, typeIndex, memberKind, 0, Flags(custom), field.DecodeSignature(_names.Provider(reader), context), new List<ParameterEntry>()));
        }
    }

    private bool IsPublic(TypeDefinition type)
    {
        while (true)
        {
            var visibility = type.Attributes & TypeAttributes.VisibilityMask;
            if (!type.IsNested) return visibility == TypeAttributes.Public;
            if (visibility != TypeAttributes.NestedPublic) return false;
            type = reader.GetTypeDefinition(type.GetDeclaringType());
        }
    }

    /// <summary>`Outer.Inner` for a nested type, with the arity marks of the metadata kept: `Dictionary`2.Enumerator`.</summary>
    private string NestedName(TypeDefinition type)
    {
        var name = reader.GetString(type.Name);
        return type.IsNested ? NestedName(reader.GetTypeDefinition(type.GetDeclaringType())) + "." + name : name;
    }

    /// <summary>`Dictionary`2.Enumerator` -> (`Dictionary.Enumerator`, 2): the arity is the sum of the type parameters around.</summary>
    private static (string, int) SplitArity(string name)
    {
        var arity = 0;
        var bare = new StringBuilder();
        foreach (var part in name.Split('.'))
        {
            var mark = part.IndexOf('`');
            if (mark >= 0 && int.TryParse(part.AsSpan(mark + 1), out var count)) arity += count;
            if (bare.Length > 0) bare.Append('.');
            bare.Append(mark >= 0 ? part[..mark] : part);
        }
        return (bare.ToString(), arity);
    }

    private TypeKind KindOf(TypeDefinition type)
    {
        if ((type.Attributes & TypeAttributes.Interface) != 0) return TypeKind.Interface;
        var (baseNamespace, baseName) = NameOf(type.BaseType);
        if (baseNamespace == "System")
        {
            if (baseName == "Enum") return TypeKind.Enum;
            if (baseName == "ValueType") return TypeKind.Struct;
            if (baseName is "MulticastDelegate" or "Delegate") return TypeKind.Delegate;
        }
        const TypeAttributes both = TypeAttributes.Abstract | TypeAttributes.Sealed;
        return (type.Attributes & both) == both ? TypeKind.StaticClass : TypeKind.Class;
    }

    private (string, string) NameOf(EntityHandle handle)
    {
        if (handle.IsNil) return ("", "");
        switch (handle.Kind)
        {
            case HandleKind.TypeReference:
                var reference = reader.GetTypeReference((TypeReferenceHandle)handle);
                return (reader.GetString(reference.Namespace), reader.GetString(reference.Name));
            case HandleKind.TypeDefinition:
                var definition = reader.GetTypeDefinition((TypeDefinitionHandle)handle);
                return (reader.GetString(definition.Namespace), reader.GetString(definition.Name));
            default:
                return ("", "");
        }
    }

    private ImmutableArray<string> TypeParameters(GenericParameterHandleCollection handles) =>
        handles.Select(handle => reader.GetString(reader.GetGenericParameter(handle).Name)).ToImmutableArray();

    private EntryFlags Flags(CustomAttributeHandleCollection attributes) =>
        Has(attributes, "System", "ObsoleteAttribute") ? EntryFlags.Obsolete : EntryFlags.None;

    /// <summary>`[EditorBrowsable(EditorBrowsableState.Never)]`: what its author does not want to be offered.</summary>
    private bool IsHidden(CustomAttributeHandleCollection attributes)
    {
        foreach (var handle in attributes)
        {
            var attribute = reader.GetCustomAttribute(handle);
            if (AttributeType(attribute) != ("System.ComponentModel", "EditorBrowsableAttribute")) continue;
            var blob = reader.GetBlobReader(attribute.Value);
            // the prolog, then the one argument of the constructor: the state as an int, Never = 1
            if (blob.Length >= 6 && blob.ReadUInt16() == 1 && blob.ReadInt32() == 1) return true;
        }
        return false;
    }

    private bool Has(CustomAttributeHandleCollection attributes, string @namespace, string name)
    {
        foreach (var handle in attributes)
        {
            if (AttributeType(reader.GetCustomAttribute(handle)) == (@namespace, name)) return true;
        }
        return false;
    }

    private (string, string) AttributeType(CustomAttribute attribute)
    {
        switch (attribute.Constructor.Kind)
        {
            case HandleKind.MemberReference:
                return NameOf(reader.GetMemberReference((MemberReferenceHandle)attribute.Constructor).Parent);
            case HandleKind.MethodDefinition:
                return NameOf(reader.GetMethodDefinition((MethodDefinitionHandle)attribute.Constructor).GetDeclaringType());
            default:
                return ("", "");
        }
    }
}

public sealed record GenericContext(ImmutableArray<string> TypeParameters, ImmutableArray<string> MethodParameters);

/// <summary>The types of a signature as C# writes them: `int`, `string?` is not known here, `List&lt;T&gt;`, `T[]`, `ref T`.</summary>
public sealed class TypeNames
{
    private readonly SignatureProvider _provider = new();

    public ISignatureTypeProvider<string, GenericContext> Provider(MetadataReader reader) => _provider;

    private sealed class SignatureProvider : ISignatureTypeProvider<string, GenericContext>
    {
        public string GetPrimitiveType(PrimitiveTypeCode typeCode) => typeCode switch
        {
            PrimitiveTypeCode.Boolean => "bool",
            PrimitiveTypeCode.Byte => "byte",
            PrimitiveTypeCode.SByte => "sbyte",
            PrimitiveTypeCode.Char => "char",
            PrimitiveTypeCode.Int16 => "short",
            PrimitiveTypeCode.UInt16 => "ushort",
            PrimitiveTypeCode.Int32 => "int",
            PrimitiveTypeCode.UInt32 => "uint",
            PrimitiveTypeCode.Int64 => "long",
            PrimitiveTypeCode.UInt64 => "ulong",
            PrimitiveTypeCode.Single => "float",
            PrimitiveTypeCode.Double => "double",
            PrimitiveTypeCode.String => "string",
            PrimitiveTypeCode.Object => "object",
            PrimitiveTypeCode.Void => "void",
            PrimitiveTypeCode.IntPtr => "nint",
            PrimitiveTypeCode.UIntPtr => "nuint",
            PrimitiveTypeCode.TypedReference => "TypedReference",
            _ => typeCode.ToString(),
        };

        public string GetTypeFromDefinition(MetadataReader metadata, TypeDefinitionHandle handle, byte rawTypeKind)
        {
            var type = metadata.GetTypeDefinition(handle);
            var name = Bare(metadata.GetString(type.Name));
            return type.IsNested ? GetTypeFromDefinition(metadata, type.GetDeclaringType(), 0) + "." + name : name;
        }

        public string GetTypeFromReference(MetadataReader metadata, TypeReferenceHandle handle, byte rawTypeKind)
        {
            var type = metadata.GetTypeReference(handle);
            var name = Bare(metadata.GetString(type.Name));
            if (type.ResolutionScope.Kind == HandleKind.TypeReference) return GetTypeFromReference(metadata, (TypeReferenceHandle)type.ResolutionScope, 0) + "." + name;
            // the types C# has a word for, when they come by reference and not as a primitive of the signature
            if (metadata.GetString(type.Namespace) == "System")
            {
                switch (name)
                {
                    case "Object": return "object";
                    case "String": return "string";
                    case "Decimal": return "decimal";
                }
            }
            return name;
        }

        public string GetTypeFromSpecification(MetadataReader metadata, GenericContext context, TypeSpecificationHandle handle, byte rawTypeKind) =>
            metadata.GetTypeSpecification(handle).DecodeSignature(this, context);

        public string GetSZArrayType(string elementType) => elementType + "[]";

        public string GetArrayType(string elementType, ArrayShape shape) => elementType + "[" + new string(',', Math.Max(0, shape.Rank - 1)) + "]";

        public string GetByReferenceType(string elementType) => "ref " + elementType;

        public string GetPointerType(string elementType) => elementType + "*";

        public string GetPinnedType(string elementType) => elementType;

        public string GetModifiedType(string modifier, string unmodifiedType, bool isRequired) => unmodifiedType;

        public string GetFunctionPointerType(MethodSignature<string> signature) =>
            "delegate*<" + string.Join(", ", signature.ParameterTypes.Append(signature.ReturnType)) + ">";

        public string GetGenericInstantiation(string genericType, ImmutableArray<string> typeArguments) =>
            genericType == "Nullable" && typeArguments.Length == 1 ? typeArguments[0] + "?" : genericType + "<" + string.Join(", ", typeArguments) + ">";

        public string GetGenericTypeParameter(GenericContext context, int index) =>
            index < context.TypeParameters.Length ? context.TypeParameters[index] : "T" + index;

        public string GetGenericMethodParameter(GenericContext context, int index) =>
            index < context.MethodParameters.Length ? context.MethodParameters[index] : "TM" + index;

        private static string Bare(string name)
        {
            var mark = name.IndexOf('`');
            return mark >= 0 ? name[..mark] : name;
        }
    }
}

/// <summary>
/// The file of an index. Everything is an int of 4 bytes, little-endian, but for the flags that share one:
/// <code>
/// header   "DNIX", version, mvid (16 bytes), assembly name (string id),
///          strings, types, members, parameters, names: for each its count and the offset of its table
/// strings  count + 1 offsets into the data that follows them, UTF-8
/// types    namespace, name, kind | arity &lt;&lt; 8 | flags &lt;&lt; 16                                     12 bytes
/// members  name, type, kind | arity &lt;&lt; 8 | flags &lt;&lt; 16, return type, first parameter, parameters   24 bytes
/// params   type, name, flags                                                                     12 bytes
/// names    name, target: a type, or a member with the highest bit set                             8 bytes
///          sorted by the name in lower case, then by the name: the table a prefix is searched in
/// </code>
/// </summary>
public static class IndexWriter
{
    public const int MemberBit = unchecked((int)0x80000000);

    public static void Write(string file, Guid mvid, AssemblyData data)
    {
        var strings = new StringPool();
        var assembly = strings.Id(data.Name);
        var types = data.Types.Select(type => (strings.Id(type.Namespace), strings.Id(type.Name), type.Kind, type.Arity, type.Flags)).ToList();
        var parameters = new List<(int, int, int)>();
        var members = new List<(int, int, int, int, int, int)>();
        foreach (var member in data.Members)
        {
            var first = parameters.Count;
            foreach (var parameter in member.Parameters) parameters.Add((strings.Id(parameter.Type), strings.Id(parameter.Name), (int)parameter.Flags));
            members.Add((strings.Id(member.Name), member.Type, Pack((byte)member.Kind, member.Arity, member.Flags), strings.Id(member.ReturnType), first, member.Parameters.Count));
        }
        var names = new List<(string, int, int)>(data.Types.Count + data.Members.Count);
        for (var i = 0; i < data.Types.Count; i++) names.Add((SimpleName(data.Types[i].Name), types[i].Item2, i));
        for (var i = 0; i < data.Members.Count; i++) names.Add((data.Members[i].Name, members[i].Item1, i | MemberBit));
        // a nested type is found by its own name: `Enumerator` of `Dictionary.Enumerator`
        var sorted = names
            .Select(name => (Key: name.Item1.ToLowerInvariant(), Name: name.Item1, Id: strings.Id(name.Item1), Target: name.Item3))
            .OrderBy(name => name.Key, StringComparer.Ordinal).ThenBy(name => name.Name, StringComparer.Ordinal).ThenBy(name => name.Target & ~MemberBit)
            .ToList();

        const int header = 4 + 4 + 16 + 4 + 5 * 8;
        var stringTable = header;
        var stringData = stringTable + (strings.Count + 1) * 4;
        var typeTable = Align(stringData + strings.Bytes);
        var memberTable = typeTable + types.Count * 12;
        var parameterTable = memberTable + members.Count * 24;
        var nameTable = parameterTable + parameters.Count * 12;

        using var stream = new FileStream(file, FileMode.Create, FileAccess.Write, FileShare.None);
        using var writer = new BinaryWriter(stream, Encoding.UTF8);
        writer.Write("DNIX"u8);
        writer.Write(Program.FormatVersion);
        writer.Write(mvid.ToByteArray());
        writer.Write(assembly);
        foreach (var (count, offset) in new[] { (strings.Count, stringTable), (types.Count, typeTable), (members.Count, memberTable), (parameters.Count, parameterTable), (sorted.Count, nameTable) })
        {
            writer.Write(count);
            writer.Write(offset);
        }
        var position = 0;
        foreach (var bytes in strings.Encoded)
        {
            writer.Write(position);
            position += bytes.Length;
        }
        writer.Write(position);
        foreach (var bytes in strings.Encoded) writer.Write(bytes);
        while (stream.Position < typeTable) writer.Write((byte)0);
        foreach (var (@namespace, name, kind, arity, flags) in types)
        {
            writer.Write(@namespace);
            writer.Write(name);
            writer.Write(Pack((byte)kind, arity, flags));
        }
        foreach (var (name, type, packed, returnType, first, count) in members)
        {
            writer.Write(name);
            writer.Write(type);
            writer.Write(packed);
            writer.Write(returnType);
            writer.Write(first);
            writer.Write(count);
        }
        foreach (var (type, name, flags) in parameters)
        {
            writer.Write(type);
            writer.Write(name);
            writer.Write(flags);
        }
        foreach (var name in sorted)
        {
            writer.Write(name.Id);
            writer.Write(name.Target);
        }
    }

    private static int Pack(byte kind, int arity, EntryFlags flags) => kind | (Math.Min(arity, 255) << 8) | ((int)flags << 16);

    private static int Align(int offset) => (offset + 3) & ~3;

    private static string SimpleName(string name)
    {
        var dot = name.LastIndexOf('.');
        return dot >= 0 ? name[(dot + 1)..] : name;
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
}
