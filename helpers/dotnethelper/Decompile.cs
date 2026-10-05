// The decompiler of the plugin (decompiler/AssemblyDecompiler), as the decompiled sources of Rider: the C# of a type of an assembly,
// made by ICSharpCode.Decompiler (the engine of ILSpy), with the offsets of its members for the caret.
//
//   decompile      {assembly, typeName, memberId?, xmlDoc?, referenceDirs?, languageVersion?}
//                  → {assembly, assemblyName, assemblyVersion, assemblyModified, typeName, text, members: [{id, offset, line}], warning?, elapsedMs}
//   assemblyTypes  {assembly} → [{name, kind, isPublic}]   (the types of an assembly, for a chooser when the index of assemblies has none yet)
//
// `typeName`: the metadata name (`System.Collections.Generic.List`1`, nested with `+`; the arity may be left out, `.` for `+` is taken too).
// `members`: XML documentation ids (`M:System.Console.WriteLine(System.String)`, `T:...`, `P:...`) and the offset of the name of each member in
// `text` (0-based, `\n` line ends), `line` 0-based. A type forwarded to another assembly (`System.Runtime` -> `System.Private.CoreLib`) is
// decompiled from where it lives: `assembly` of the answer says which. The decompiler of an assembly is kept by path and time, the next type of
// the same assembly does not read it again.

using System.Collections.Concurrent;
using System.Diagnostics;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Text;
using ICSharpCode.Decompiler;
using ICSharpCode.Decompiler.CSharp;
using ICSharpCode.Decompiler.CSharp.OutputVisitor;
using ICSharpCode.Decompiler.CSharp.Syntax;
using ICSharpCode.Decompiler.Documentation;
using ICSharpCode.Decompiler.Metadata;
using ICSharpCode.Decompiler.TypeSystem;

namespace DotNetSupport.Helpers.DotNetHelper;

public sealed record DecompiledMember(string Id, int Offset, int Line);

public sealed record DecompiledType(string Assembly, string AssemblyName, string AssemblyVersion, long AssemblyModified, string TypeName, string Text,
    List<DecompiledMember> Members, string? Warning, long ElapsedMs);

public sealed record AssemblyTypeInfo(string Name, string Kind, bool IsPublic);

internal static class TypeDecompiler
{
    private const int CacheSize = 8;
    private static readonly StringComparer PathComparer = OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;
    private static readonly ConcurrentDictionary<string, Loaded> Cache = new(PathComparer);

    public static DecompiledType Decompile(string assembly, string typeName, string? memberId, string? xmlDoc, List<string>? referenceDirs, string? languageVersion,
        CancellationToken token)
    {
        var clock = Stopwatch.StartNew();
        var version = Language(languageVersion);
        var loaded = Load(Path.GetFullPath(assembly), xmlDoc, referenceDirs ?? [], version);
        lock (loaded)
        {
            token.ThrowIfCancellationRequested();
            var definition = loaded.Find(typeName) ?? throw new HelperException($"{Path.GetFileName(loaded.Path)} has no type {typeName}");
            // a type forwarded elsewhere (a reference assembly of the framework names its implementation): decompiled where it is defined
            var file = definition.ParentModule?.MetadataFile?.FileName;
            if (definition.ParentModule != loaded.TypeSystem.MainModule && file != null && File.Exists(file))
            {
                var target = Load(Path.GetFullPath(file), null, referenceDirs ?? [], version);
                if (target != loaded)
                {
                    lock (target) return target.Decompile(definition.ReflectionName, memberId, clock, token);
                }
            }
            return loaded.Decompile(definition.ReflectionName, memberId, clock, token);
        }
    }

    public static List<AssemblyTypeInfo> Types(string assembly)
    {
        var loaded = Load(Path.GetFullPath(assembly), null, [], LanguageVersion.Latest);
        lock (loaded)
        {
            return loaded.TypeSystem.MainModule.TypeDefinitions
                .Where(t => !t.Name.StartsWith('<') && t.Name != "<Module>")
                .Select(t => new AssemblyTypeInfo(t.ReflectionName, t.Kind.ToString().ToLowerInvariant(), t.Accessibility is Accessibility.Public
                    || (t.Accessibility is Accessibility.Protected or Accessibility.ProtectedOrInternal && t.DeclaringTypeDefinition?.Accessibility == Accessibility.Public)))
                .OrderBy(t => t.Name, StringComparer.Ordinal).ToList();
        }
    }

    /** `latest`, `default`, `preview`, `7.3`, `12`, `12.0` → the version of the decompiler; what it does not know is the latest it knows. */
    internal static LanguageVersion Language(string? text)
    {
        var value = text?.Trim().ToLowerInvariant();
        if (string.IsNullOrEmpty(value) || value is "latest" or "default" or "latestmajor") return LanguageVersion.Latest;
        if (value == "preview") return LanguageVersion.Preview;
        var parts = value.Split('.');
        if (!int.TryParse(parts[0], out var major)) return LanguageVersion.Latest;
        var minor = parts.Length > 1 && int.TryParse(parts[1], out var m) ? m : 0;
        foreach (var name in new[] { $"CSharp{major}_{minor}", $"CSharp{major}" })
        {
            if (Enum.TryParse<LanguageVersion>(name, out var found)) return found;
        }
        return LanguageVersion.Latest;
    }

    private static Loaded Load(string path, string? xmlDoc, List<string> referenceDirs, LanguageVersion version)
    {
        if (!File.Exists(path)) throw new HelperException($"{path} is not there");
        var stamp = Stamp(path);
        var key = path + "|" + version;
        if (Cache.TryGetValue(key, out var cached) && cached.Stamp == stamp)
        {
            cached.LastUse = DateTime.UtcNow;
            cached.AddSearchDirectories(referenceDirs);
            return cached;
        }
        Loaded loaded;
        try { loaded = new Loaded(path, stamp, xmlDoc, referenceDirs, version); }
        catch (Exception e) when (e is BadImageFormatException or InvalidOperationException or EndOfStreamException or MetadataFileNotSupportedException)
        {
            throw new HelperException($"{Path.GetFileName(path)} cannot be decompiled: {e.Message}");
        }
        Cache[key] = loaded;
        foreach (var old in Cache.OrderByDescending(e => e.Value.LastUse).Skip(CacheSize).ToList()) Cache.TryRemove(old.Key, out _);
        return loaded;
    }

    private static (long, long) Stamp(string path)
    {
        var info = new FileInfo(path);
        return info.Exists ? (info.LastWriteTimeUtc.Ticks, info.Length) : (0, 0);
    }

    /** An assembly read into memory with its type system and decompiler: the first type of it pays for the references, the next ones do not. */
    private sealed class Loaded
    {
        public readonly string Path;
        public readonly (long, long) Stamp;
        public readonly DecompilerTypeSystem TypeSystem;
        public DateTime LastUse = DateTime.UtcNow;
        private readonly PEFile module;
        private readonly DecompilerSettings settings;
        private readonly UniversalAssemblyResolver resolver;
        private readonly HashSet<string> directories = new(PathComparer);
        private readonly IDocumentationProvider? docs;
        private readonly bool isReference;

        public Loaded(string path, (long, long) stamp, string? xmlDoc, List<string> referenceDirs, LanguageVersion version)
        {
            Path = path;
            Stamp = stamp;
            module = new PEFile(path, new MemoryStream(IlViewer.ReadShared(path)), PEStreamOptions.PrefetchEntireImage);
            resolver = new UniversalAssemblyResolver(path, false, module.DetectTargetFrameworkId(), module.DetectRuntimePack(),
                PEStreamOptions.PrefetchMetadata, MetadataReaderOptions.ApplyWindowsRuntimeProjections);
            AddSearchDirectories(referenceDirs);
            // as ILSpy and Rider show it by default: the language of the project, no expanded `using` declarations, XML docs
            settings = new DecompilerSettings(version)
            {
                ThrowOnAssemblyResolveErrors = false, ShowXmlDocumentation = true, RemoveDeadCode = false, RemoveDeadStores = false,
                UseSdkStyleProjectFormat = true, LoadInMemory = true,
            };
            settings.CSharpFormattingOptions.IndentationString = "    ";
            TypeSystem = new DecompilerTypeSystem(module, resolver, settings);
            docs = Docs(path, xmlDoc);
            isReference = module.Metadata.GetAssemblyDefinition().GetCustomAttributes().Any(h => IsReferenceAssemblyAttribute(module.Metadata, h));
        }

        public void AddSearchDirectories(IEnumerable<string> dirs)
        {
            foreach (var dir in dirs)
            {
                if (Directory.Exists(dir) && directories.Add(dir)) resolver.AddSearchDirectory(dir);
            }
        }

        /** The XML documentation the plugin names (the one of the reference pack for an implementation assembly), else the one next to the dll. */
        private static IDocumentationProvider? Docs(string path, string? xmlDoc)
        {
            try
            {
                if (xmlDoc != null && File.Exists(xmlDoc)) return new XmlDocumentationProvider(xmlDoc);
                var next = System.IO.Path.ChangeExtension(path, ".xml");
                return File.Exists(next) ? new XmlDocumentationProvider(next) : null;
            }
            catch (Exception e)
            {
                HelperProtocol.Log("warn", $"the XML documentation of {System.IO.Path.GetFileName(path)} cannot be read: {e.Message}");
                return null;
            }
        }

        public ITypeDefinition? Find(string name)
        {
            foreach (var candidate in Candidates(name))
            {
                var found = TypeSystem.FindType(new FullTypeName(candidate)).GetDefinition();
                if (found != null) return found;
            }
            // the arity left out or `.` for `+`: by the plain names of the types of the assembly
            var plain = Plain(name);
            return TypeSystem.MainModule.TypeDefinitions.FirstOrDefault(t => Plain(t.ReflectionName) == plain)
                ?? TypeSystem.MainModule.TypeDefinitions.FirstOrDefault(t => Plain(t.ReflectionName).Replace('+', '.') == plain.Replace('+', '.'));
        }

        private static IEnumerable<string> Candidates(string name)
        {
            yield return name;
            if (name.Contains('.') && !name.Contains('+'))
            {
                // `Outer.Inner` might be a nested type: try `+` from the right
                var dots = Enumerable.Range(0, name.Length).Where(i => name[i] == '.').Reverse().ToList();
                for (var k = 1; k < dots.Count; k++)
                {
                    var chars = name.ToCharArray();
                    foreach (var i in dots.Take(k)) chars[i] = '+';
                    yield return new string(chars);
                }
            }
        }

        private static string Plain(string name) => System.Text.RegularExpressions.Regex.Replace(name, @"`\d+", "");

        public DecompiledType Decompile(string reflectionName, string? memberId, Stopwatch clock, CancellationToken token)
        {
            var decompiler = new CSharpDecompiler(TypeSystem, settings) { CancellationToken = token, DocumentationProvider = docs };
            var full = new FullTypeName(reflectionName);
            // a nested type is shown inside the type that holds it, as Rider does; the caret goes to it
            var top = full;
            while (top.IsNested) top = top.GetDeclaringType();
            var tree = decompiler.DecompileType(top);

            var body = new StringWriter { NewLine = "\n" };
            var writer = TokenWriter.CreateWriterThatSetsLocationsInAST(body, "    ");
            tree.AcceptVisitor(new CSharpOutputVisitor(writer, settings.CSharpFormattingOptions));

            var definition = module.Metadata.GetAssemblyDefinition();
            var assemblyName = module.Metadata.GetString(definition.Name);
            var version = definition.Version.ToString();
            var header = new StringBuilder()
                .Append("// Decompiled with ICSharpCode.Decompiler ").Append(typeof(CSharpDecompiler).Assembly.GetName().Version).Append('\n')
                .Append("// Type: ").Append(reflectionName).Append('\n')
                .Append("// Assembly: ").Append(module.FullName).Append('\n')
                .Append("// MVID: ").Append(module.Metadata.GetGuid(module.Metadata.GetModuleDefinition().Mvid).ToString().ToUpperInvariant()).Append('\n')
                .Append("// Assembly location: ").Append(Path).Append('\n');
            if (isReference) header.Append("// This is a reference assembly: its methods have no bodies (`throw null`).\n");
            header.Append('\n');
            var text = header + body.ToString().TrimEnd('\n') + "\n";
            var headerLines = header.ToString().Count(c => c == '\n');

            var lineStarts = new List<int> { 0 };
            for (var i = 0; i < text.Length; i++) if (text[i] == '\n') lineStarts.Add(i + 1);
            var members = new List<DecompiledMember>();
            var seen = new HashSet<string>();
            void Add(AstNode node, AstNode? name, string? keyword = null)
            {
                if (node.GetSymbol() is not IEntity entity) return;
                string id;
                try { id = IdStringProvider.GetIdString(entity); }
                catch (Exception) { return; }
                var named = name != null && !name.StartLocation.IsEmpty && name.ToString() != "";
                var location = named ? name!.StartLocation : node.StartLocation;
                if (location.IsEmpty) return;
                var line = location.Line - 1 + headerLines;
                if (line >= lineStarts.Count || !seen.Add(id)) return;
                var offset = Math.Min(text.Length, lineStarts[line] + location.Column - 1);
                // no name token (an indexer, an operator): the keyword that names it, on its first lines
                if (!named && keyword != null && text.IndexOf(keyword, offset, Math.Min(400, text.Length - offset), StringComparison.Ordinal) is var at and >= 0)
                {
                    offset = at;
                    var index = lineStarts.BinarySearch(at);
                    line = index >= 0 ? index : ~index - 1;
                }
                members.Add(new DecompiledMember(id, offset, line));
            }
            void Variables(EntityDeclaration declaration, AstNodeCollection<VariableInitializer> variables)
            {
                foreach (var variable in variables)
                {
                    // the symbol is on the declaration when it declares one variable, as the decompiler writes them
                    if (variable.GetSymbol() != null) Add(variable, variable.NameToken);
                    else if (variables.Count == 1) Add(declaration, variable.NameToken);
                }
            }
            foreach (var node in tree.Descendants)
            {
                switch (node)
                {
                    // a field or an event declared as a field is named by its variables (`int a, b;` holds two)
                    case FieldDeclaration field: Variables(field, field.Variables); break;
                    case EventDeclaration field: Variables(field, field.Variables); break;
                    case IndexerDeclaration indexer: Add(indexer, null, "this["); break;
                    case OperatorDeclaration op: Add(op, null, "operator "); break;
                    case EntityDeclaration entity: Add(entity, entity.NameToken); break;
                }
            }
            members.Sort((a, b) => a.Offset.CompareTo(b.Offset));

            string? warning = null;
            if (memberId != null && members.All(m => m.Id != memberId))
                warning = $"{memberId} is not among the members of the decompiled type (compiler-generated, or another overload)";
            var modified = new DateTimeOffset(Stamp.Item1, TimeSpan.Zero).ToUnixTimeMilliseconds();
            return new DecompiledType(Path, assemblyName, version, modified, reflectionName, text, members, warning, clock.ElapsedMilliseconds);
        }

        private static bool IsReferenceAssemblyAttribute(MetadataReader metadata, CustomAttributeHandle handle)
        {
            var attribute = metadata.GetCustomAttribute(handle);
            if (attribute.Constructor.Kind != HandleKind.MemberReference) return false;
            var parent = metadata.GetMemberReference((MemberReferenceHandle)attribute.Constructor).Parent;
            return parent.Kind == HandleKind.TypeReference
                && metadata.GetString(metadata.GetTypeReference((TypeReferenceHandle)parent).Name) == "ReferenceAssemblyAttribute";
        }
    }
}
