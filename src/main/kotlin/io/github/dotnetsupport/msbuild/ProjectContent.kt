package io.github.dotnetsupport.msbuild

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/**
 * What of the files on disk is a part of the project, the way the SDK decides it: everything under the project directory is,
 * except what the project file takes out (`<Compile Remove>`, `DefaultItemExcludes`), plus what it brings in from elsewhere
 * (`<Compile Include="..\Shared\X.cs" Link="..." />`). Conditions and imports are not evaluated; see [MsBuildProject].
 *
 * A project of the old format has no default globs: its files are the ones it lists, known from [evaluated] (MsBuildHost, see
 * [MsBuildEvaluation]); everything else on disk is out of it. Without an evaluation (not there yet, or the helper failed) it is read
 * as the SDK would see it, as before.
 */
class ProjectContent(private val project: MsBuildProject, private val evaluated: EvaluatedFiles? = null) {
    /** A file from outside of the project directory shown at [path] inside it (`/` separators). */
    class LinkedFile(val path: String, val file: VirtualFile)

    /** Whether a file at [relativePath] (to the project directory) is left out of the project. */
    fun isExcluded(relativePath: String): Boolean {
        evaluated?.let { return !it.contains(relativePath) }
        val path = MsBuildGlob.normalize(relativePath)
        if (project.defaultItemExcludes.any { it.matches(path) }) return true
        val itemType = defaultItemType(path)
        return project.removes.any { it.itemType == itemType && it.glob.matches(path) }
    }

    /** Whether a directory at [relativePath] and everything in it is left out: a `Remove` of `dir\**` for every kind of file, or an exclude. */
    fun isExcludedDirectory(relativePath: String): Boolean {
        evaluated?.let { return !it.containsDirectory(relativePath) }
        val covered = project.removes.filter { it.glob.coversDirectory(relativePath) }.mapTo(HashSet()) { it.itemType }
        return project.defaultItemExcludes.any { it.coversDirectory(relativePath) } ||
            ("None" in covered && "Compile" in covered) || (project.isWebSdk && "Content" in covered && MsBuildGlob.normalize(relativePath).startsWith("wwwroot/"))
    }

    /** The file a file is nested under (`<DependentUpon>`), a name in the same directory; null when it stands on its own. */
    fun dependentParent(relativePath: String): String? =
        evaluated?.dependentParent(relativePath) ?: project.dependentUpon[MsBuildGlob.normalize(relativePath).lowercase()]

    /** Whether the files are the ones MSBuild has evaluated rather than the ones the SDK globs would take. */
    val isEvaluated: Boolean get() = evaluated != null

    /** Files linked from outside, with the path each one is shown at; wildcards are expanded on disk. Not for EDT on big folders. */
    fun linkedFiles(projectDirectory: VirtualFile): List<LinkedFile> {
        evaluated?.let { files ->
            return files.linked.mapNotNull { linked -> projectDirectory.fileSystem.findFileByPath(linked.file)?.takeIf { !it.isDirectory }?.let { LinkedFile(linked.path, it) } }
        }
        val result = ArrayList<LinkedFile>()
        for (item in project.linkedItems) {
            if (item.include.pattern.startsWith("$(")) continue // a property nobody evaluates here
            val base = projectDirectory.findFileByRelativePath(item.include.fixedDirectory) ?: resolveAbsolute(projectDirectory, item.include.fixedDirectory) ?: continue
            if (!item.include.hasWildcards) {
                val file = projectDirectory.findFileByRelativePath(item.include.pattern) ?: resolveAbsolute(projectDirectory, item.include.pattern) ?: continue
                if (!file.isDirectory) result += LinkedFile(linkPath(item, file.name, ""), file)
                continue
            }
            val prefix = item.include.fixedDirectory
            VfsUtilCore.visitChildrenRecursively(base, object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) return file == base || file.name.lowercase() !in SKIPPED
                    val underBase = VfsUtilCore.getRelativePath(file, base, '/') ?: return true
                    val relativeToProject = if (prefix.isEmpty()) underBase else "$prefix/$underBase"
                    if (item.include.matches(relativeToProject)) result += LinkedFile(linkPath(item, file.name, underBase.substringBeforeLast('/', "")), file)
                    return true
                }
            })
        }
        return result.distinctBy { it.path.lowercase() }
    }

    /**
     * `Link` with `%(RecursiveDir)`, `%(Filename)`, `%(Extension)` expanded; else `LinkBase` + the path under the wildcard;
     * else the path under the wildcard, or the bare file name.
     */
    private fun linkPath(item: LinkedItem, fileName: String, recursiveDir: String): String {
        val recursive = if (recursiveDir.isEmpty()) "" else "$recursiveDir/"
        val link = item.link?.let {
            MsBuildGlob.normalize(it)
                .replace("%(RecursiveDir)", recursive, ignoreCase = true)
                .replace("%(Filename)", fileName.substringBeforeLast('.'), ignoreCase = true)
                .replace("%(Extension)", fileName.substringAfterLast('.', "").let { ext -> if (ext.isEmpty()) "" else ".$ext" }, ignoreCase = true)
        }
        return when {
            link != null && '%' !in link -> link.trimEnd('/').ifEmpty { fileName }
            item.linkBase != null -> "${MsBuildGlob.normalize(item.linkBase).trimEnd('/')}/$recursive$fileName"
            else -> "$recursive$fileName"
        }
    }

    private fun resolveAbsolute(projectDirectory: VirtualFile, path: String): VirtualFile? =
        if (path.startsWith("/") || (path.length > 1 && path[1] == ':')) projectDirectory.fileSystem.findFileByPath(path) else null

    /** Which default glob of the SDK a file falls into: what a `Remove` has to name to take it out. */
    private fun defaultItemType(path: String): String = when {
        path.endsWith(".cs", ignoreCase = true) -> "Compile"
        path.endsWith(".resx", ignoreCase = true) -> "EmbeddedResource"
        project.isWebSdk && path.startsWith("wwwroot/", ignoreCase = true) -> "Content"
        else -> "None"
    }

    companion object {
        private val SKIPPED = setOf("bin", "obj", "node_modules", ".git")
    }
}
