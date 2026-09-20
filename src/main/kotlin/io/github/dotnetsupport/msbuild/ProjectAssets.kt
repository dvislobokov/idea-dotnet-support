package io.github.dotnetsupport.msbuild

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A package as it was resolved for one target framework. */
class AssetsPackage(
    val name: String,
    val version: String,
    /** Names of the packages it depends on. */
    val dependencies: List<String>,
    /** File names of the Roslyn analyzers shipped in the package. */
    val analyzers: List<String>,
)

class AssetsProjectReference(val name: String, /** Relative to the project directory. */ val path: String?)

class AssetsTarget(
    /** `net9.0`, `netstandard2.0`, ... */
    val framework: String,
    /** Packages the project references itself (including the ones the SDK adds); the rest of [packages] is transitive. */
    val directPackages: List<String>,
    private val packagesByName: Map<String, AssetsPackage>,
    val projects: List<AssetsProjectReference>,
    /** `Microsoft.NETCore.App`, `Microsoft.AspNetCore.App`, ... */
    val frameworkReferences: List<String>,
) {
    val packages: Collection<AssetsPackage> get() = packagesByName.values
    fun findPackage(name: String): AssetsPackage? = packagesByName[name.lowercase()]
}

/**
 * `obj/project.assets.json`: what `dotnet restore` resolved. Unlike the project file it knows the real versions,
 * the transitive packages and the per-framework differences.
 */
class ProjectAssets(val targets: List<AssetsTarget>, val packageFolders: List<String>) {
    companion object {
        val EMPTY = ProjectAssets(emptyList(), emptyList())

        fun parse(text: CharSequence): ProjectAssets {
            val root = try {
                JsonParser.parseString(text.toString().removePrefix("﻿")) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return EMPTY

            val libraries = root.obj("libraries")
            val frameworks = root.obj("project")?.obj("frameworks")
            val targets = root.obj("targets")?.entrySet().orEmpty()
                // "net9.0/win-x64" repeats "net9.0" for a runtime identifier
                .filter { (key, _) -> '/' !in key }
                .mapNotNull { (key, value) -> (value as? JsonObject)?.let { target(key, it, libraries, frameworks) } }
            return ProjectAssets(targets, root.obj("packageFolders")?.keySet().orEmpty().toList())
        }

        private fun target(key: String, libraries: JsonObject, allLibraries: JsonObject?, frameworks: JsonObject?): AssetsTarget {
            val packages = LinkedHashMap<String, AssetsPackage>()
            val projects = ArrayList<AssetsProjectReference>()
            for ((id, value) in libraries.entrySet()) {
                val library = value as? JsonObject ?: continue
                val name = id.substringBefore('/')
                val details = allLibraries?.obj(id)
                when (library.string("type")) {
                    "project" -> projects += AssetsProjectReference(name, details?.string("path")?.replace('\\', '/'))
                    "package" -> packages[name.lowercase()] = AssetsPackage(
                        name,
                        version = id.substringAfter('/', ""),
                        dependencies = library.obj("dependencies")?.keySet().orEmpty().toList(),
                        analyzers = details?.get("files")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty()
                            .mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
                            .filter { it.startsWith("analyzers/") && it.endsWith(".dll") && !it.endsWith(".resources.dll") }
                            .map { it.substringAfterLast('/') }
                            .distinct(),
                    )
                }
            }
            // The frameworks of the project section are keyed by the short name; older SDKs key the targets by the long one.
            val framework = frameworks?.entrySet()?.firstOrNull { (name, _) -> TargetFrameworks.sameFramework(name, key) }
            val declared = framework?.value as? JsonObject
            return AssetsTarget(
                framework = framework?.key ?: key,
                directPackages = declared?.obj("dependencies")?.entrySet().orEmpty()
                    .filter { (_, dependency) -> (dependency as? JsonObject)?.string("target").let { it == null || it.equals("Package", ignoreCase = true) } }
                    .map { it.key },
                packagesByName = packages,
                projects = projects,
                frameworkReferences = declared?.obj("frameworkReferences")?.keySet().orEmpty().sorted(),
            )
        }

        private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject
        private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
        private fun com.google.gson.JsonArray?.orEmpty(): Iterable<JsonElement> = this ?: emptyList()
    }
}

object TargetFrameworks {
    private val MODERN = Regex("""^net(\d+)\.(\d+)(?:-(.+))?$""")
    private val LEGACY = Regex("""^net(\d)(\d)(\d)?$""")
    private val LONG = Regex("""^\.?(NETCoreApp|NETStandard|NETFramework),Version=v(\d+(?:\.\d+)*)$""", RegexOption.IGNORE_CASE)

    /** `net9.0` -> `.NET 9.0`, `net8.0-windows` -> `.NET 8.0 (windows)`, `netstandard2.0` -> `.NET Standard 2.0`, `net48` -> `.NET Framework 4.8`. */
    fun displayName(framework: String): String {
        val tfm = framework.trim()
        MODERN.find(tfm.lowercase())?.let { match ->
            val (major, minor, platform) = match.destructured
            val family = if (major.toInt() >= 5) ".NET" else ".NET Framework"
            return "$family $major.$minor" + if (platform.isEmpty()) "" else " ($platform)"
        }
        LEGACY.find(tfm.lowercase())?.let { match -> return ".NET Framework " + match.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString(".") }
        LONG.find(tfm)?.let { match ->
            val version = match.groupValues[2]
            return when (match.groupValues[1].lowercase()) {
                "netstandard" -> ".NET Standard $version"
                "netframework" -> ".NET Framework $version"
                else -> (if (version.substringBefore('.').toInt() >= 5) ".NET " else ".NET Core ") + version
            }
        }
        return when {
            tfm.startsWith("netstandard", ignoreCase = true) -> ".NET Standard " + tfm.substring("netstandard".length)
            tfm.startsWith("netcoreapp", ignoreCase = true) -> ".NET Core " + tfm.substring("netcoreapp".length)
            else -> tfm
        }
    }

    fun sameFramework(first: String, second: String): Boolean =
        first.equals(second, ignoreCase = true) || displayName(first) == displayName(second)

    /** `9.0` for `net9.0`: the version the reference packs of a shared framework are looked up by. */
    fun version(framework: String): String? = MODERN.find(framework.lowercase())?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
}
