package io.github.dotnetsupport.msbuild

import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Where the build of a project keeps its intermediate and output files: `obj/` and `bin/` beside the project file, or — with
 * `UseArtifactsOutput` (the .NET 8 SDK, the Aspire templates, eShop) — `artifacts/obj/<project>/` and `artifacts/bin/<project>/` at the
 * root of the repository, the pivot of a build being `debug` / `release` (`debug_net8.0` with several frameworks) instead of
 * `Debug/net8.0`. The artifacts folder is found by walking up from the project; `ArtifactsPath` naming another place is not read, the
 * project then looks unrestored (0.1.199: eShop's Catalog.API had no assets to the IDE, so a missing name was no error).
 */
object ProjectLayout {
    const val ARTIFACTS = "artifacts"
    private const val LEVELS = 6

    /** The intermediate folder of [projectFile]: `obj` beside it when it holds the assets, else `artifacts/obj/<name>` up the tree, else `obj`. */
    fun intermediate(projectFile: File): File? {
        val beside = File(projectFile.parentFile ?: return null, "obj")
        if (File(beside, "project.assets.json").isFile) return beside
        return artifacts(projectFile, "obj") ?: beside.takeIf { it.isDirectory }
    }

    /** The output folder of [projectFile]: `bin` beside it, else `artifacts/bin/<name>` up the tree. */
    fun output(projectFile: File): File? {
        val beside = File(projectFile.parentFile ?: return null, "bin")
        return beside.takeIf { it.isDirectory } ?: artifacts(projectFile, "bin")
    }

    /** `project.assets.json` of [projectFile] in either layout; null when it is not restored. */
    fun assets(projectFile: File): File? = intermediate(projectFile)?.let { File(it, "project.assets.json") }?.takeIf { it.isFile }

    fun intermediate(projectFile: VirtualFile): VirtualFile? {
        val beside = projectFile.parent?.findChild("obj")
        if (beside?.findChild("project.assets.json") != null) return beside
        return artifacts(projectFile, "obj") ?: beside
    }

    fun assets(projectFile: VirtualFile): VirtualFile? = intermediate(projectFile)?.findChild("project.assets.json")

    /** Whether [path] is under an artifacts intermediate folder (`…/artifacts/obj/<project>/…`). */
    fun isArtifactsIntermediate(path: String): Boolean = ARTIFACTS_OBJ.containsMatchIn(path)

    /** The part of [path] after `…/artifacts/obj/<project>/`, or null. */
    fun underArtifactsIntermediate(path: String): String? = ARTIFACTS_OBJ.find(path)?.let { path.substring(it.range.last + 1) }

    /**
     * Whether the pivot folder [pivot] of the artifacts layout (`debug`, `release_net8.0`) is the build of [configuration] and
     * [framework] (null: any).
     */
    fun pivotMatches(pivot: String, configuration: String, framework: String?): Boolean {
        if (pivot.equals(configuration, ignoreCase = true)) return true
        if (!pivot.startsWith("${configuration}_", ignoreCase = true)) return false
        return framework == null || pivot.substring(configuration.length + 1).equals(framework, ignoreCase = true)
    }

    private fun artifacts(projectFile: File, folder: String): File? {
        val name = projectFile.nameWithoutExtension
        return generateSequence(projectFile.parentFile) { it.parentFile }.take(LEVELS).map { File(it, "$ARTIFACTS/$folder/$name") }.firstOrNull { it.isDirectory }
    }

    private fun artifacts(projectFile: VirtualFile, folder: String): VirtualFile? {
        val name = projectFile.nameWithoutExtension
        return generateSequence(projectFile.parent) { it.parent }.take(LEVELS).mapNotNull { it.findFileByRelativePath("$ARTIFACTS/$folder/$name") }.firstOrNull { it.isDirectory }
    }

    private val ARTIFACTS_OBJ = Regex("(?i)/artifacts/obj/[^/]+/")
}
