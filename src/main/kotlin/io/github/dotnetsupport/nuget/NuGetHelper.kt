package io.github.dotnetsupport.nuget

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.JDOMUtil
import io.github.dotnetsupport.cli.DotNetHelper
import io.github.dotnetsupport.cli.HelperConnection
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.msbuild.PackagesConfigEntry
import java.io.File

/**
 * The NuGet client of DotNetHelper (`helpers/dotnethelper`): the NuGet.Client libraries in a .NET process, so a feed is reached the way
 * the `dotnet` CLI reaches it — the proxy of the system and of nuget.config, the certificates of the OS, the credentials of nuget.config
 * and of the credential providers. The HTTP client of the IDE stays the first way to a feed (fast, cached); [NuGetClient] comes here
 * for a feed the IDE cannot reach and for local folder feeds, which have no HTTP API at all. Every method blocks: not for the EDT.
 * The first request builds the helper (seconds, once per version of its source and per SDK) and starts it; it then stays running.
 */
@Service(Service.Level.APP)
class NuGetHelper : Disposable {
    /** The one DotNetHelper process of the IDE: the IL viewer (il/IlHelperSource) asks it too. */
    val connection = HelperConnection.of(HELPER, NuGetClient.LOG_CATEGORY)

    /** Enabled sources of all nuget.config levels as the CLI sees them from [root]. */
    fun sources(root: String?): List<NuGetHelperResponses.Source> =
        NuGetHelperResponses.parseSources(connection.request("sources", JsonObject().apply { addProperty("root", root) }))

    /** Search in each of [sources] (URLs or names; all enabled ones when empty): an answer per source, the packages or an error. */
    fun search(root: String?, query: String, prerelease: Boolean, take: Int, sources: List<String>, packageType: String? = null, skip: Int = 0):
        List<NuGetHelperResponses.SourceAnswer<List<NuGetPackageInfo>>> {
        val params = JsonObject().apply {
            addProperty("root", root); addProperty("query", query); addProperty("prerelease", prerelease); addProperty("skip", skip); addProperty("take", take)
            add("sources", JsonArray().apply { sources.forEach(::add) }); packageType?.let { addProperty("packageType", it) }
        }
        return NuGetHelperResponses.parseSearch(connection.request("search", params, FEED_TIMEOUT_MS))
    }

    /** Versions of [id] in each of [sources], oldest first. */
    fun versions(root: String?, id: String, prerelease: Boolean, sources: List<String>): List<NuGetHelperResponses.SourceAnswer<List<String>>> {
        val params = JsonObject().apply {
            addProperty("root", root); addProperty("id", id); addProperty("prerelease", prerelease); add("sources", JsonArray().apply { sources.forEach(::add) })
        }
        return NuGetHelperResponses.parseVersions(connection.request("versions", params, FEED_TIMEOUT_MS))
    }

    /** The packages of the packages.config of [projectPath] into the packages folder of [solutionDirectory], what is there already skipped. */
    fun restorePackagesConfig(projectPath: String, solutionDirectory: String?): NuGetHelperResponses.PackagesConfigRestore {
        val params = JsonObject().apply { addProperty("projectPath", projectPath); addProperty("solutionDirectory", solutionDirectory) }
        return NuGetHelperResponses.parseRestore(connection.request("restorePackagesConfig", params, RESTORE_TIMEOUT_MS))
            ?: throw HelperException("the helper gave no result for the restore of $projectPath")
    }

    override fun dispose() = connection.dispose()

    companion object {
        val HELPER = DotNetHelper("dotnethelper", "DotNetHelper", "HelperFramework", listOf("Program.cs", "Il.cs", "Decompile.cs", "SourceLink.cs", "AppSettings.cs", "Protocol.cs"))

        /** A feed behind a slow proxy, and NuGet retries a failed request itself before it says so. */
        private const val FEED_TIMEOUT_MS = 90_000L
        private const val RESTORE_TIMEOUT_MS = 15 * 60_000L

        fun getInstance(): NuGetHelper = service()
    }
}

/** The answers of DotNetHelper ([NuGetHelper]), parsed without the helper: the tests read saved answers of a real run. */
object NuGetHelperResponses {
    class Source(val name: String, val url: String, val protocolVersion: Int, val isLocal: Boolean, val isHttp: Boolean)

    /** What one source answered: [result], or [error] (the messages of the exception and its causes). */
    class SourceAnswer<T>(val source: String, val result: T?, val error: String?, val elapsedMs: Long)

    class RestoredPackage(val id: String, val version: String, val state: State, val source: String?, val message: String?) {
        enum class State { RESTORED, PRESENT, FAILED }
    }

    class PackagesConfigRestore(val packagesDirectory: String, val configFile: String, val packages: List<RestoredPackage>) {
        val failed: List<RestoredPackage> get() = packages.filter { it.state == RestoredPackage.State.FAILED }

        /** One line per package for the Log tab of the NuGet window. */
        fun lines(): List<String> = packages.map { p ->
            "  ${p.id} ${p.version}: " + when (p.state) {
                RestoredPackage.State.RESTORED -> "restored from ${p.source}"
                RestoredPackage.State.PRESENT -> "already there"
                RestoredPackage.State.FAILED -> "failed: ${p.message ?: "no reason given"}"
            }
        }
    }

    fun parseSources(json: JsonElement?): List<Source> = objects(json).mapNotNull { o ->
        Source(o.string("name") ?: return@mapNotNull null, o.string("url") ?: return@mapNotNull null, o.get("protocolVersion")?.asIntOrNull() ?: 2,
            o.bool("isLocal"), o.bool("isHttp"))
    }

    fun parseSearch(json: JsonElement?): List<SourceAnswer<List<NuGetPackageInfo>>> = answers(json) { result ->
        objects(result).mapNotNull { item ->
            NuGetPackageInfo(
                id = item.string("id") ?: return@mapNotNull null,
                version = item.string("version").orEmpty(),
                description = item.string("description").orEmpty(),
                authors = item.string("authors").orEmpty(),
                totalDownloads = item.get("downloads")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0,
                projectUrl = item.string("projectUrl")?.ifBlank { null },
                isVerified = item.bool("verified"),
                versions = strings(item.get("versions")),
                iconUrl = item.string("iconUrl")?.ifBlank { null },
                licenseUrl = item.string("licenseUrl")?.ifBlank { null },
                tags = item.string("tags").orEmpty().split(' ', ',').filter { it.isNotBlank() },
            )
        }
    }

    fun parseVersions(json: JsonElement?): List<SourceAnswer<List<String>>> = answers(json, ::strings)

    fun parseRestore(json: JsonElement?): PackagesConfigRestore? {
        val o = json as? JsonObject ?: return null
        val packages = objects(o.get("packages")).mapNotNull { p ->
            val state = when (p.string("state")) {
                "restored" -> RestoredPackage.State.RESTORED
                "present" -> RestoredPackage.State.PRESENT
                else -> RestoredPackage.State.FAILED
            }
            RestoredPackage(p.string("id") ?: return@mapNotNull null, p.string("version").orEmpty(), state, p.string("source"), p.string("message"))
        }
        return PackagesConfigRestore(o.string("packagesDirectory").orEmpty(), o.string("configFile").orEmpty(), packages)
    }

    private fun <T> answers(json: JsonElement?, result: (JsonElement) -> T): List<SourceAnswer<T>> = objects(json).mapNotNull { o ->
        val source = o.string("source") ?: return@mapNotNull null
        val error = o.string("error")
        SourceAnswer(source, o.get("result")?.takeIf { error == null && !it.isJsonNull }?.let(result), error ?: if (o.get("result")?.isJsonNull != false) "no answer" else null,
            o.get("elapsedMs")?.asLongOrNull() ?: 0)
    }

    private fun objects(json: JsonElement?): List<JsonObject> = (json as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun strings(json: JsonElement?): List<String> = (json as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.bool(name: String): Boolean = get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    private fun JsonElement.asIntOrNull(): Int? = takeIf { it.isJsonPrimitive }?.asInt
    private fun JsonElement.asLongOrNull(): Long? = takeIf { it.isJsonPrimitive }?.asLong
}

/**
 * The `packages` folder of the packages.config projects, where nuget.exe, Visual Studio and DotNetHelper put `<id>.<version>/`: next to
 * the solution, unless the nearest nuget.config that says `repositoryPath` puts it elsewhere (a relative path is relative to that file).
 */
object PackagesFolder {
    private val CONFIG_NAMES = setOf("nuget.config")

    /** `<config><add key="repositoryPath" value="..."/></config>` of a nuget.config; null when it does not say. */
    fun parseRepositoryPath(text: String): String? {
        val root = try {
            JDOMUtil.load(text.removePrefix("﻿"))
        } catch (_: Exception) {
            return null
        }
        return root.children.filter { it.name == "config" }.flatMap { it.children }
            .lastOrNull { it.name == "add" && it.getAttributeValue("key").equals("repositoryPath", ignoreCase = true) }
            ?.getAttributeValue("value")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The nuget.config files that apply to [directory], the most specific first: up the directory tree, then the one of the user. */
    fun configFiles(directory: File, userConfig: File? = userConfig()): List<File> {
        val walked = generateSequence(directory.absoluteFile) { it.parentFile }
            .flatMap { dir -> dir.listFiles { file -> file.isFile && file.name.lowercase() in CONFIG_NAMES }.orEmpty().sortedBy { it.name }.asSequence() }
            .toList()
        return walked + listOfNotNull(userConfig?.takeIf { it.isFile })
    }

    /** `%APPDATA%\NuGet\NuGet.Config` on Windows, `~/.nuget/NuGet/NuGet.Config` elsewhere. */
    private fun userConfig(): File? {
        val appData = System.getenv("APPDATA")
        val base = if (appData != null && System.getProperty("os.name").startsWith("Windows")) File(appData, "NuGet") else File(System.getProperty("user.home"), ".nuget/NuGet")
        return base.listFiles { file -> file.isFile && file.name.lowercase() in CONFIG_NAMES }?.firstOrNull()
    }

    /** The folder for the solution in [solutionDirectory], given the configs that apply to it ([configFiles]). */
    fun of(solutionDirectory: File, configs: List<File> = configFiles(solutionDirectory)): File {
        for (config in configs) {
            val path = runCatching { parseRepositoryPath(config.readText()) }.getOrNull() ?: continue
            val file = File(path)
            return (if (file.isAbsolute) file else File(config.parentFile, path)).normalize()
        }
        return File(solutionDirectory, "packages")
    }

    /**
     * The names the folder of a package can have: NuGet writes `<id>.<normalized version>` (`1.0` → `1.0.0`, `1.2.3.0` → `1.2.3`), older
     * tools wrote the version as it is in packages.config. Lowercased: the folder is found whatever the case.
     */
    fun folderNames(id: String, version: String): Set<String> {
        val written = version.trim()
        val label = written.substringAfter('-', "").substringBefore('+')
        val numbers = written.substringBefore('-').substringBefore('+').split('.').mapNotNull { it.toLongOrNull() }
        val forms = mutableSetOf(written)
        if (numbers.isNotEmpty()) {
            val padded = (numbers + List(4) { 0L }).take(4)
            val normalized = (if (padded[3] == 0L) padded.take(3) else padded).joinToString(".")
            forms += normalized
            forms += padded.joinToString(".")
            if (label.isNotEmpty()) { forms += "$normalized-$label"; forms += "${padded.joinToString(".")}-$label" }
            if (label.isNotEmpty()) forms.removeAll { !it.contains('-') }
        }
        return forms.map { "$id.$it".lowercase() }.toSet()
    }

    /** The entries of a packages.config whose folder is not among [present] (lowercased names of the children of the packages folder). */
    fun missing(entries: List<PackagesConfigEntry>, present: Set<String>): List<PackagesConfigEntry> =
        entries.filter { entry -> entry.version == null || folderNames(entry.id, entry.version).none { it in present } }
}
