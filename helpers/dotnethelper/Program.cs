// DotNetHelper: a helper of the plugin that stays running (helpers/README.md). First part: a NuGet client on the NuGet.Client
// libraries, for the feeds the HTTP client of the IDE cannot reach (a corporate proxy, a certificate of the company, credentials of a
// provider) while the dotnet CLI can: the same libraries as the CLI, so the same proxy, certificates and credentials.
//
//   sources               {root}                                                → [{name, url, protocolVersion, isLocal, isHttp}]
//   search                {root, query, prerelease, skip, take, sources?, packageType?}
//                                                                               → [{source, packages?, error?, elapsedMs}]
//   versions              {root, id, prerelease, sources?}                      → [{source, versions?, error?, elapsedMs}]
//   restorePackagesConfig {projectPath, solutionDirectory}                      → {packagesDirectory, configFile, packages: [{id, version, state, source?, message?}]}
//   il                    {assembly, file, line, typeName?, memberName?}        → the IL of the code at the line (Il.cs: ICSharpCode.Decompiler)
//   decompile             {assembly, typeName, memberId?, xmlDoc?, referenceDirs?, languageVersion?}
//                                                                               → the C# of a type and the offsets of its members (Decompile.cs)
//   assemblyTypes         {assembly}                                            → [{name, kind, isPublic}]
//
// `root` is the directory nuget.config files are looked for from (the solution directory), as the CLI run there; `sources` are URLs
// or names of sources, by default every enabled one. A source that fails is an error of its own, the others still answer.

using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json;
using System.Xml.Linq;
using NuGet.Common;
using NuGet.Configuration;
using NuGet.Credentials;
using NuGet.Packaging;
using NuGet.Packaging.Core;
using NuGet.Packaging.Signing;
using NuGet.Protocol;
using NuGet.Protocol.Core.Types;
using NuGet.Protocol.Plugins;
using NuGet.Versioning;

namespace DotNetSupport.Helpers.DotNetHelper;

public static class Program
{
    public static async Task<int> Main(string[] args)
    {
        if (!args.Contains("--serve"))
        {
            Console.Error.WriteLine("usage: dotnet DotNetHelper.dll --serve   (requests on stdin, one JSON object per line)");
            return 2;
        }
        NuGetFeeds.Setup();
        await HelperProtocol.ServeAsync(Handle);
        return 0;
    }

    private static async Task<object?> Handle(string method, JsonElement? p, CancellationToken token) => method switch
    {
        "sources" => NuGetFeeds.Sources(Params.String(p, "root")),
        "search" => await NuGetFeeds.Search(Params.String(p, "root"), Params.String(p, "query") ?? "", Params.Bool(p, "prerelease"), Params.Int(p, "skip", 0),
            Params.Int(p, "take", 40), Params.Strings(p, "sources"), Params.String(p, "packageType"), token),
        "versions" => await NuGetFeeds.Versions(Params.String(p, "root"), Params.String(p, "id") ?? throw new HelperException("`versions` needs an id"),
            Params.Bool(p, "prerelease"), Params.Strings(p, "sources"), token),
        "restorePackagesConfig" => await NuGetFeeds.RestorePackagesConfig(Params.String(p, "projectPath") ?? throw new HelperException("`restorePackagesConfig` needs a projectPath"),
            Params.String(p, "solutionDirectory"), token),
        "il" => IlViewer.Il(Params.String(p, "assembly") ?? throw new HelperException("`il` needs an assembly"),
            Params.String(p, "file") ?? throw new HelperException("`il` needs a file"), Params.Int(p, "line", 0), Params.String(p, "typeName"), Params.String(p, "memberName"), token),
        "decompile" => TypeDecompiler.Decompile(Params.String(p, "assembly") ?? throw new HelperException("`decompile` needs an assembly"),
            Params.String(p, "typeName") ?? throw new HelperException("`decompile` needs a typeName"), Params.String(p, "memberId"), Params.String(p, "xmlDoc"),
            Params.Strings(p, "referenceDirs"), Params.String(p, "languageVersion"), token),
        "assemblyTypes" => TypeDecompiler.Types(Params.String(p, "assembly") ?? throw new HelperException("`assemblyTypes` needs an assembly")),
        "appsettingsSchema" => AppSettingsSchema.Build(p, token), // AppSettings.cs
        _ => throw new HelperException($"DotNetHelper has no method `{method}`"),
    };
}

internal static class Params
{
    public static string? String(JsonElement? p, string name) =>
        p is { ValueKind: JsonValueKind.Object } o && o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    public static bool Bool(JsonElement? p, string name) =>
        p is { ValueKind: JsonValueKind.Object } o && o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.True;

    public static int Int(JsonElement? p, string name, int fallback) =>
        p is { ValueKind: JsonValueKind.Object } o && o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetInt32() : fallback;

    public static List<string>? Strings(JsonElement? p, string name) =>
        p is { ValueKind: JsonValueKind.Object } o && o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Array
            ? v.EnumerateArray().Where(e => e.ValueKind == JsonValueKind.String).Select(e => e.GetString()!).ToList()
            : null;
}

public sealed record SourceInfo(string Name, string Url, int ProtocolVersion, bool IsLocal, bool IsHttp);

public sealed record PackageInfo(string Id, string Version, string? Description, string? Authors, long? Downloads, bool Verified, string? IconUrl,
    string? ProjectUrl, string? LicenseUrl, string? Tags, List<string>? Versions);

public sealed record SourceAnswer<T>(string Source, T? Result, string? Error, long ElapsedMs);

public sealed record RestoredPackage(string Id, string Version, string State, string? Source, string? Message);

public sealed record PackagesConfigRestore(string PackagesDirectory, string ConfigFile, List<RestoredPackage> Packages);

internal static class NuGetFeeds
{
    private static readonly ILogger Logger = new ProtocolLogger();

    /**
     * Repositories by source and its credentials: each keeps its service index, HTTP handler and what it has read, so the second request
     * is fast. Made again after a while, so that a package published meanwhile is seen.
     */
    private static readonly ConcurrentDictionary<string, (SourceRepository Repository, DateTime Made)> Repositories = new();

    private static readonly TimeSpan RepositoryLifetime = TimeSpan.FromMinutes(5);

    public static void Setup()
    {
        UserAgent.SetUserAgentString(new UserAgentStringBuilder("DotNetSupport.DotNetHelper"));
        // the credential providers of the CLI (NuGet plugins: Azure Artifacts and the like), never asking anything: there is no console
        var providers = new AsyncLazy<IEnumerable<ICredentialProvider>>(async () =>
        {
            var list = new List<ICredentialProvider>();
            try { list.AddRange(await new SecurePluginCredentialProviderBuilder(PluginManager.Instance, canShowDialog: false, Logger).BuildAllAsync()); }
            catch (Exception e) { HelperProtocol.Log("warn", $"credential providers could not be loaded: {e.Message}"); }
            return list;
        });
        HttpHandlerResourceV3.CredentialService = new Lazy<ICredentialService>(() => new CredentialService(providers, nonInteractive: true, handlesDefaultCredentials: true));
    }

    private static ISettings Settings(string? root) => NuGet.Configuration.Settings.LoadDefaultSettings(string.IsNullOrEmpty(root) ? null : root);

    private static List<PackageSource> Enabled(ISettings settings) => new PackageSourceProvider(settings).LoadPackageSources().Where(s => s.IsEnabled).ToList();

    public static List<SourceInfo> Sources(string? root) =>
        Enabled(Settings(root)).Select(s => new SourceInfo(s.Name, s.Source, IsV3(s) ? 3 : 2, s.IsLocal, s.IsHttp)).ToList();

    private static bool IsV3(PackageSource source) => source.ProtocolVersion >= 3 || source.Source.EndsWith(".json", StringComparison.OrdinalIgnoreCase);

    /** The sources of the request by URL or name, with the credentials nuget.config has for them; an unknown URL is a source of its own. */
    private static List<PackageSource> Pick(ISettings settings, List<string>? requested)
    {
        var configured = new PackageSourceProvider(settings).LoadPackageSources().ToList();
        if (requested == null || requested.Count == 0) return configured.Where(s => s.IsEnabled).ToList();
        return requested.Select(r => configured.FirstOrDefault(s => Same(s.Source, r)) ?? configured.FirstOrDefault(s => s.Name.Equals(r, StringComparison.OrdinalIgnoreCase))
            ?? new PackageSource(r)).ToList();
    }

    private static bool Same(string a, string b) => string.Equals(a.TrimEnd('/', '\\'), b.TrimEnd('/', '\\'), StringComparison.OrdinalIgnoreCase);

    private static SourceRepository Repository(PackageSource source)
    {
        var key = $"{source.Source}|{source.Credentials?.Username}|{source.Credentials?.PasswordText?.GetHashCode()}|{source.ProtocolVersion}";
        var now = DateTime.UtcNow;
        var entry = Repositories.GetOrAdd(key, _ => (NuGet.Protocol.Core.Types.Repository.Factory.GetCoreV3(source), now));
        if (now - entry.Made < RepositoryLifetime) return entry.Repository;
        var fresh = (Repository: NuGet.Protocol.Core.Types.Repository.Factory.GetCoreV3(source), Made: now);
        Repositories[key] = fresh;
        return fresh.Repository;
    }

    /** Every source at once; each answers or fails on its own. */
    private static async Task<List<SourceAnswer<T>>> PerSource<T>(List<PackageSource> sources, Func<PackageSource, SourceRepository, Task<T>> ask) =>
        (await Task.WhenAll(sources.Select(async source =>
        {
            var clock = Stopwatch.StartNew();
            try
            {
                return new SourceAnswer<T>(source.Source, await ask(source, Repository(source)), null, clock.ElapsedMilliseconds);
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception e) { return new SourceAnswer<T>(source.Source, default, Describe(e), clock.ElapsedMilliseconds); }
        }))).ToList();

    public static async Task<List<SourceAnswer<List<PackageInfo>>>> Search(string? root, string query, bool prerelease, int skip, int take, List<string>? sources,
        string? packageType, CancellationToken token)
    {
        var settings = Settings(root);
        return await PerSource(Pick(settings, sources), async (source, repository) =>
        {
            var search = await repository.GetResourceAsync<PackageSearchResource>(token)
                ?? throw new HelperException($"{source.Source} has no search service");
            var filter = new SearchFilter(prerelease) { PackageTypes = packageType == null ? null : [packageType] };
            var found = await search.SearchAsync(query, filter, skip, take, Logger, token);
            // V3 search results carry their versions; V2 would make a request per package, a folder reads its files
            var cheapVersions = !source.IsHttp || IsV3(source);
            var packages = new List<PackageInfo>();
            foreach (var item in found)
            {
                List<string>? versions = null;
                if (cheapVersions)
                {
                    try { versions = (await item.GetVersionsAsync()).Select(v => v.Version).OrderBy(v => v).Select(v => v.ToNormalizedString()).ToList(); }
                    catch (Exception) { /* the versions are a bonus */ }
                }
                packages.Add(new PackageInfo(item.Identity.Id, item.Identity.Version.ToNormalizedString(), item.Description, item.Authors, item.DownloadCount,
                    item.PrefixReserved, item.IconUrl?.ToString(), item.ProjectUrl?.ToString(), item.LicenseUrl?.ToString(), item.Tags, versions));
            }
            return packages;
        });
    }

    public static async Task<List<SourceAnswer<List<string>>>> Versions(string? root, string id, bool prerelease, List<string>? sources, CancellationToken token)
    {
        var settings = Settings(root);
        using var cache = new SourceCacheContext();
        return await PerSource(Pick(settings, sources), async (source, repository) =>
        {
            var finder = await repository.GetResourceAsync<FindPackageByIdResource>(token)
                ?? throw new HelperException($"{source.Source} cannot list the versions of a package");
            var versions = await finder.GetAllVersionsAsync(id, cache, Logger, token);
            return versions.Where(v => prerelease || !v.IsPrerelease).Distinct().OrderBy(v => v).Select(v => v.ToNormalizedString()).ToList();
        });
    }

    /**
     * The packages of a packages.config into `<solution>/packages/<id>.<version>/` (or `repositoryPath` of nuget.config), as nuget.exe and
     * Visual Studio restore them: the .nupkg and its files, no global packages folder layout. What is there already is not touched.
     */
    public static async Task<PackagesConfigRestore> RestorePackagesConfig(string projectPath, string? solutionDirectory, CancellationToken token)
    {
        var projectDirectory = Path.GetDirectoryName(Path.GetFullPath(projectPath)) ?? throw new HelperException($"no directory of {projectPath}");
        var named = Path.Combine(projectDirectory, $"packages.{Path.GetFileNameWithoutExtension(projectPath)}.config");
        var configFile = File.Exists(named) ? named : Path.Combine(projectDirectory, "packages.config");
        if (!File.Exists(configFile)) throw new HelperException($"{Path.GetFileName(projectPath)} has no packages.config");

        var root = string.IsNullOrEmpty(solutionDirectory) ? projectDirectory : solutionDirectory;
        var settings = Settings(root);
        var packagesDirectory = SettingsUtility.GetRepositoryPath(settings) ?? Path.Combine(root, "packages");
        var resolver = new PackagePathResolver(packagesDirectory);
        var globalFolder = SettingsUtility.GetGlobalPackagesFolder(settings);
        var extraction = new PackageExtractionContext(PackageSaveMode.Defaultv2, XmlDocFileSaveMode.None, ClientPolicyContext.GetClientPolicy(settings, Logger), Logger);
        var sources = Enabled(settings);

        IEnumerable<PackageReference> entries;
        try { entries = new PackagesConfigReader(XDocument.Load(configFile)).GetPackages(allowDuplicatePackageIds: true); }
        catch (Exception e) { throw new HelperException($"{configFile} cannot be read: {e.Message}"); }

        using var cache = new SourceCacheContext();
        var results = new ConcurrentDictionary<int, RestoredPackage>();
        var list = entries.Select(e => e.PackageIdentity).DistinctBy(i => i.Id.ToLowerInvariant()).ToList();
        await Parallel.ForEachAsync(list.Select((identity, index) => (identity, index)), new ParallelOptions { MaxDegreeOfParallelism = 4, CancellationToken = token },
            async (item, ct) => results[item.index] = await Restore(item.identity, resolver, sources, cache, globalFolder, extraction, ct));
        return new PackagesConfigRestore(packagesDirectory, configFile, Enumerable.Range(0, list.Count).Select(i => results[i]).ToList());
    }

    private static async Task<RestoredPackage> Restore(PackageIdentity identity, PackagePathResolver resolver, List<PackageSource> sources, SourceCacheContext cache,
        string globalFolder, PackageExtractionContext extraction, CancellationToken token)
    {
        var version = identity.Version.ToNormalizedString();
        if (!identity.HasVersion) return new RestoredPackage(identity.Id, "", "failed", null, "no version in packages.config");
        if (resolver.GetInstalledPath(identity) != null) return new RestoredPackage(identity.Id, version, "present", null, null);
        var errors = new List<string>();
        foreach (var source in sources)
        {
            try
            {
                var download = await Repository(source).GetResourceAsync<DownloadResource>(token);
                if (download == null) continue;
                using var result = await download.GetDownloadResourceResultAsync(identity, new PackageDownloadContext(cache), globalFolder, Logger, token);
                if (result.Status == DownloadResourceResultStatus.NotFound) continue;
                if (result.Status != DownloadResourceResultStatus.Available) { errors.Add($"{source.Source}: {result.Status}"); continue; }
                if (result.PackageStream != null)
                {
                    await PackageExtractor.ExtractPackageAsync(result.PackageSource ?? source.Source, result.PackageStream, resolver, extraction, token);
                }
                else if (result.PackageReader != null)
                {
                    await PackageExtractor.ExtractPackageAsync(result.PackageSource ?? source.Source, result.PackageReader, resolver, extraction, token);
                }
                else { errors.Add($"{source.Source}: no package in the answer"); continue; }
                return new RestoredPackage(identity.Id, version, "restored", source.Source, null);
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception e) { errors.Add($"{source.Source}: {Describe(e)}"); }
        }
        var why = errors.Count == 0 ? $"not found in {(sources.Count == 0 ? "any source: no source is enabled" : string.Join(", ", sources.Select(s => s.Name)))}" : string.Join("; ", errors);
        return new RestoredPackage(identity.Id, version, "failed", null, why);
    }

    /** The messages of the exception and its causes, each once: NuGet wraps "the host is unknown" in two layers of "unable to load". */
    private static string Describe(Exception e)
    {
        var messages = new List<string>();
        for (Exception? x = e; x != null; x = x.InnerException)
        {
            if (x is AggregateException aggregate && aggregate.InnerExceptions.Count > 1)
            {
                messages.AddRange(aggregate.InnerExceptions.Select(i => i.Message));
                break;
            }
            if (!messages.Any(m => m.Contains(x.Message))) messages.Add(x.Message);
        }
        return string.Join(" -> ", messages.Distinct());
    }

    /** Warnings and errors of the libraries go to the journal of the plugin; their chatter does not. */
    private sealed class ProtocolLogger : LoggerBase
    {
        public override void Log(ILogMessage message)
        {
            if (message.Level >= LogLevel.Warning) HelperProtocol.Log("warn", message.Message);
        }

        public override Task LogAsync(ILogMessage message)
        {
            Log(message);
            return Task.CompletedTask;
        }
    }
}
