package io.github.dotnetsupport.msbuild

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** An item of an evaluated project: [include] is a full path when it is a file on disk, else what the project says (`Missing.cs`, `System.Xml`). */
data class MsBuildItem(val include: String, val metadata: Map<String, String> = emptyMap())

/** The answer of `evaluate` of MsBuildHost (`helpers/msbuildhost`): what MSBuild of the SDK makes of a project. */
data class MsBuildEvaluationResult(
    val properties: Map<String, String> = emptyMap(),
    val items: Map<String, List<MsBuildItem>> = emptyMap(),
    /** Every file the evaluation read besides the project: props and targets of the SDK, `Directory.Build.props`... */
    val imports: List<String> = emptyList(),
    val targetFrameworks: List<String> = emptyList(),
    /** The imports that are not there and the project was evaluated without (`$(VSToolsPath)\WebApplications`); null when there are none. */
    val warning: String? = null,
    val milliseconds: Long = 0,
    val reused: Boolean = false,
) {
    fun property(name: String): String? = properties[name]?.takeIf { it.isNotEmpty() }

    companion object {
        fun parse(json: JsonElement?): MsBuildEvaluationResult {
            val root = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return MsBuildEvaluationResult()
            val properties = root.objectOf("properties")?.entrySet()?.associate { (name, value) -> name to value.text().orEmpty() }.orEmpty()
            val items = root.objectOf("items")?.entrySet()?.associate { (type, list) ->
                type to (list as? JsonArray).orEmpty().mapNotNull { element ->
                    val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val include = item.get("include")?.text() ?: return@mapNotNull null
                    MsBuildItem(include, item.objectOf("metadata")?.entrySet()?.associate { (name, value) -> name to value.text().orEmpty() }.orEmpty())
                }
            }.orEmpty()
            return MsBuildEvaluationResult(
                properties, items,
                imports = (root.get("imports") as? JsonArray).orEmpty().mapNotNull { it.text() },
                targetFrameworks = (root.get("targetFrameworks") as? JsonArray).orEmpty().mapNotNull { it.text() },
                warning = root.get("warning")?.text(),
                milliseconds = root.get("milliseconds")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0,
                reused = root.get("reused")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        }

        /** The params of `evaluate`; global properties without a value are left out, MSBuild would take them for set to "". */
        fun request(projectPath: String, globalProperties: Map<String, String>, properties: List<String>, itemTypes: List<String>): JsonObject = JsonObject().apply {
            addProperty("projectPath", projectPath)
            add("globalProperties", JsonObject().apply { globalProperties.filter { it.key.isNotBlank() && it.value.isNotEmpty() }.forEach { (name, value) -> addProperty(name, value) } })
            add("properties", JsonArray().apply { properties.forEach(::add) })
            add("itemTypes", JsonArray().apply { itemTypes.forEach(::add) })
        }

        /** `Configuration=Release; Platform = x64` (the global properties of the build options) -> a map. */
        fun globalProperties(text: String?): Map<String, String> = text.orEmpty().split(';', '\n')
            .map { it.trim() }.filter { '=' in it && it.substringBefore('=').isNotBlank() }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

        private fun JsonObject.objectOf(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
        private fun JsonElement.text(): String? = takeIf { it.isJsonPrimitive }?.asString
        private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
    }
}

/**
 * What of the files on disk is a part of a project that lists its files (a project of the old format): the evaluated file items.
 * Paths are relative to the project directory, lower case, with `/`; files from outside of it are [linked], shown where their `Link` says.
 */
class EvaluatedFiles(
    private val files: Set<String>,
    private val directories: Set<String>,
    val linked: List<Linked>,
    private val dependentUpon: Map<String, String>,
) {
    /** A file from outside of the project directory: [file] the full path (`/`), [path] where the project shows it. */
    data class Linked(val path: String, val file: String)

    fun contains(relativePath: String): Boolean = normalize(relativePath).lowercase() in files

    /** Whether a directory has a file of the project somewhere in it, or is a `<Folder Include>` of its own. */
    fun containsDirectory(relativePath: String): Boolean = normalize(relativePath).trimEnd('/').lowercase() in directories

    fun dependentParent(relativePath: String): String? = dependentUpon[normalize(relativePath).lowercase()]

    companion object {
        /** The item types whose items are files the Solution view shows, plus the empty folders a project keeps (`Folder`). */
        val ITEM_TYPES: List<String> = MsBuildProject.FILE_ITEMS.toList() + "Folder"

        fun of(projectDirectory: String, result: MsBuildEvaluationResult): EvaluatedFiles {
            val base = normalize(projectDirectory).trimEnd('/')
            val files = HashSet<String>()
            val directories = HashSet<String>()
            val linked = ArrayList<Linked>()
            val dependent = HashMap<String, String>()
            fun addDirectories(relative: String) {
                var directory = relative.substringBeforeLast('/', "")
                while (directory.isNotEmpty() && directories.add(directory)) directory = directory.substringBeforeLast('/', "")
            }
            for ((type, items) in result.items) {
                for (item in items) {
                    if (item.include.isBlank() || item.include.startsWith("$(") || '*' in item.include) continue
                    val full = absolute(base, item.include)
                    val relative = relativeTo(base, full)
                    if (type == "Folder") {
                        relative?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.lowercase()?.let { directories += it; addDirectories(it) }
                        continue
                    }
                    if (relative == null) {
                        val link = (item.metadata["Link"] ?: item.metadata["LinkBase"]?.let { "${it.trimEnd('/', '\\')}/${full.substringAfterLast('/')}" })
                            ?.let { normalize(it).trim('/') }?.takeIf { it.isNotEmpty() && !it.startsWith("../") }
                        linked += Linked(link ?: full.substringAfterLast('/'), full)
                        continue
                    }
                    val key = relative.lowercase()
                    files += key
                    addDirectories(key)
                    item.metadata["DependentUpon"]?.let { dependent[key] = normalize(it).substringAfterLast('/') }
                }
            }
            return EvaluatedFiles(files, directories, linked.distinctBy { it.path.lowercase() }, dependent)
        }

        /** `C:\a\b\..\c` -> `C:/a/c`; a relative path is left relative. */
        fun normalize(path: String): String {
            val slashed = path.replace('\\', '/')
            val prefix = if (slashed.startsWith("/")) "/" else ""
            val parts = ArrayList<String>()
            for (part in slashed.split('/')) {
                when {
                    part.isEmpty() || part == "." -> {}
                    part == ".." && parts.isNotEmpty() && parts.last() != ".." -> if (!parts.last().endsWith(":")) parts.removeAt(parts.size - 1) // not above a drive
                    else -> parts += part
                }
            }
            return prefix + parts.joinToString("/") + if (slashed.endsWith("/") && parts.isNotEmpty()) "/" else ""
        }

        private fun isAbsolute(path: String): Boolean = path.startsWith("/") || path.startsWith("\\") || (path.length > 1 && path[1] == ':')

        private fun absolute(base: String, include: String): String = normalize(if (isAbsolute(include)) include else "$base/$include")

        /** [full] relative to [base], null when it is not under it; letters compared ignoring case, as on Windows and macOS. */
        private fun relativeTo(base: String, full: String): String? =
            if (full.length > base.length + 1 && full.startsWith("$base/", ignoreCase = true)) full.substring(base.length + 1) else null
    }
}
