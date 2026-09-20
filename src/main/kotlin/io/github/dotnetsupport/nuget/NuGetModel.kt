package io.github.dotnetsupport.nuget

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.JDOMUtil

/** NuGet flavour of SemVer: up to four numeric parts, an optional prerelease label, build metadata is ignored. */
class NuGetVersion private constructor(private val numbers: List<Long>, private val prerelease: List<String>, val text: String) : Comparable<NuGetVersion> {
    val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: NuGetVersion): Int {
        for (i in 0 until 4) {
            val difference = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        // a release is newer than any of its prereleases
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) return other.prerelease.size.coerceAtMost(1) - prerelease.size.coerceAtMost(1)
        for (i in 0 until maxOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease.getOrNull(i) ?: return -1
            val b = other.prerelease.getOrNull(i) ?: return 1
            val (x, y) = a.toLongOrNull() to b.toLongOrNull()
            val difference = when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1 // numeric identifiers have lower precedence
                y != null -> 1
                else -> a.compareTo(b, ignoreCase = true)
            }
            if (difference != 0) return difference
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is NuGetVersion && compareTo(other) == 0
    override fun hashCode(): Int = numbers.dropLastWhile { it == 0L }.hashCode() * 31 + prerelease.map { it.lowercase() }.hashCode()
    override fun toString(): String = text

    companion object {
        fun parse(text: String): NuGetVersion? {
            val version = text.trim().removePrefix("v").substringBefore('+')
            val numbers = version.substringBefore('-').split('.').map { it.toLongOrNull() ?: return null }
            if (numbers.isEmpty() || numbers.size > 4) return null
            val label = version.substringAfter('-', "")
            return NuGetVersion(numbers, if (label.isEmpty()) emptyList() else label.split('.'), text.trim())
        }

        /** The newest of [versions]; prereleases only when asked for, or when there is nothing else. */
        fun latest(versions: List<String>, includePrerelease: Boolean): String? {
            val parsed = versions.mapNotNull(::parse)
            return (parsed.filter { includePrerelease || !it.isPrerelease }.maxOrNull() ?: parsed.maxOrNull())?.text
        }
    }
}

class NuGetPackageInfo(
    val id: String,
    val version: String,
    val description: String,
    val authors: String,
    val totalDownloads: Long,
    val projectUrl: String?,
    val isVerified: Boolean,
    /** Oldest first, as the search service returns them. */
    val versions: List<String>,
    val iconUrl: String? = null,
    val licenseUrl: String? = null,
    val tags: List<String> = emptyList(),
)

class NuGetSource(val name: String, val url: String, val isEnabled: Boolean)

/** Metadata of one version, from its `.nuspec`. Dependency groups are keyed by target framework (`net8.0`, `.NETStandard2.0`, "" for any). */
class NuGetPackageDetails(
    val description: String,
    val authors: String,
    val license: String?,
    val projectUrl: String?,
    val dependencyGroups: List<Pair<String, List<String>>>,
)

/** Responses of the NuGet V3 protocol. */
object NuGetResponses {
    class ServiceIndex(val searchUrl: String?, val packageBaseUrl: String?)

    fun parseServiceIndex(json: String): ServiceIndex {
        val resources = (parse(json)?.get("resources") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun url(vararg types: String) = types.firstNotNullOfOrNull { type -> resources.firstOrNull { it.string("@type") == type }?.string("@id") }
        return ServiceIndex(
            searchUrl = url("SearchQueryService/3.5.0", "SearchQueryService/3.0.0-rc", "SearchQueryService"),
            packageBaseUrl = url("PackageBaseAddress/3.0.0")?.let { if (it.endsWith("/")) it else "$it/" },
        )
    }

    fun parseSearch(json: String): List<NuGetPackageInfo> =
        (parse(json)?.get("data") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { item ->
            NuGetPackageInfo(
                id = item.string("id") ?: return@mapNotNull null,
                version = item.string("version").orEmpty(),
                description = item.string("description").orEmpty().ifBlank { item.string("summary").orEmpty() },
                // a string in some feeds, an array in nuget.org
                authors = item.get("authors")?.let { authors -> if (authors.isJsonArray) authors.asJsonArray.joinToString(", ") { it.asString } else authors.asStringOrNull() }.orEmpty(),
                totalDownloads = item.get("totalDownloads")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0,
                projectUrl = item.string("projectUrl")?.ifBlank { null },
                isVerified = item.get("verified")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
                versions = (item.get("versions") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("version") },
                iconUrl = item.string("iconUrl")?.ifBlank { null },
                licenseUrl = item.string("licenseUrl")?.ifBlank { null },
                tags = item.get("tags")?.let { tags -> if (tags.isJsonArray) tags.asJsonArray.mapNotNull { it.asStringOrNull() } else tags.asStringOrNull()?.split(' ', ',') }
                    .orEmpty().filter { it.isNotBlank() },
            )
        }

    /** `<package><metadata>...` of a `.nuspec`; the namespace differs between schema versions, so elements are matched by local name. */
    fun parseNuspec(xml: String): NuGetPackageDetails? {
        val metadata = try {
            JDOMUtil.load(xml.removePrefix("\uFEFF")).children.firstOrNull { it.name == "metadata" }
        } catch (_: Exception) {
            null
        } ?: return null
        fun text(name: String) = metadata.children.firstOrNull { it.name == name }?.textTrim?.ifEmpty { null }
        fun dependencies(parent: org.jdom.Element) = parent.children.filter { it.name == "dependency" }
            .mapNotNull { d -> d.getAttributeValue("id")?.let { id -> listOfNotNull(id, d.getAttributeValue("version")).joinToString(" ") } }

        val dependencies = metadata.children.firstOrNull { it.name == "dependencies" }
        val groups = dependencies?.children.orEmpty().filter { it.name == "group" }.map { it.getAttributeValue("targetFramework").orEmpty() to dependencies(it) }
        // old packages list dependencies without groups
        val ungrouped = dependencies?.let(::dependencies).orEmpty()
        return NuGetPackageDetails(
            description = text("description").orEmpty(),
            authors = text("authors").orEmpty(),
            license = text("license") ?: text("licenseUrl"),
            projectUrl = text("projectUrl"),
            dependencyGroups = groups + if (ungrouped.isEmpty()) emptyList() else listOf("" to ungrouped),
        )
    }

    /** `{"versions": ["1.0.0", ...]}` of the package base address (flat container). */
    fun parseVersions(json: String): List<String> = (parse(json)?.get("versions") as? JsonArray).orEmpty().mapNotNull { it.asStringOrNull() }

    /**
     * `dotnet nuget list source`: a numbered line with the name and the (localized) state, then the URL:
     * ```
     *   1.  nuget.org [Enabled]
     *       https://api.nuget.org/v3/index.json
     * ```
     * The state is taken from the language-independent [shortOutput] (`E url` / `D url`), matched by URL.
     */
    fun parseSourceList(detailedOutput: String, shortOutput: String): List<NuGetSource> {
        val disabled = shortOutput.lines().map { it.trim() }.filter { it.startsWith("D ") }.map { it.substring(2).trim() }.toSet()
        val lines = detailedOutput.lines()
        return lines.indices.mapNotNull { i ->
            val name = SOURCE_NAME.find(lines[i])?.groupValues?.get(1) ?: return@mapNotNull null
            val url = lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.trim() ?: return@mapNotNull null
            NuGetSource(name, url, isEnabled = url !in disabled)
        }
    }

    private val SOURCE_NAME = Regex("""^\s*\d+\.\s+(.+?)\s+\[[^\[\]]*]\s*$""")

    /** `dotnet nuget list source --format short`: `E https://...` for enabled sources, `D ...` for disabled ones. */
    fun parseSources(output: String): List<String> =
        output.lines().map { it.trim() }.filter { it.startsWith("E ") }.map { it.substring(2).trim() }.filter { it.isNotEmpty() }

    private fun parse(json: String): JsonObject? = try {
        JsonParser.parseString(json) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(name: String): String? = get(name)?.asStringOrNull()
    private fun JsonElement.asStringOrNull(): String? = takeIf { it.isJsonPrimitive }?.asString
    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
}
