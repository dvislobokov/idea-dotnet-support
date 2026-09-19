package io.github.dotnetsupport.solution

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.msbuild.MsBuildProject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Parsed solution and MSBuild files of the project, cached until the file (or its unsaved document) changes. */
@Service(Service.Level.PROJECT)
class SolutionService(private val project: Project) {
    private class Cached<T>(val stamp: Long, val value: T)

    private val solutions = ConcurrentHashMap<VirtualFile, Cached<Solution>>()
    private val msBuildProjects = ConcurrentHashMap<VirtualFile, Cached<MsBuildProject>>()

    /** Solution files in the root of the opened directory. */
    fun solutionFiles(): List<VirtualFile> {
        val baseDir = project.guessProjectDir() ?: return emptyList()
        return baseDir.children
            .filter { !it.isDirectory && it.extension?.lowercase() in SOLUTION_EXTENSIONS }
            .sortedBy { it.name.lowercase() }
    }

    fun solution(file: VirtualFile): Solution =
        cached(solutions, file) { SolutionParser.parse(it, file.extension) }

    fun msBuildProject(file: VirtualFile): MsBuildProject =
        cached(msBuildProjects, file, MsBuildProject::parse)

    /** Version from the nearest `Directory.Packages.props` up the directory tree. */
    fun centralPackageVersion(projectFile: VirtualFile, packageName: String): String? {
        var dir = projectFile.parent
        while (dir != null) {
            val props = dir.findChild("Directory.Packages.props")
            if (props != null) return msBuildProject(props).packageVersions[packageName.lowercase()]
            dir = dir.parent
        }
        return null
    }

    private fun <T> cached(cache: ConcurrentHashMap<VirtualFile, Cached<T>>, file: VirtualFile, parse: (CharSequence) -> T): T {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        cache[file]?.takeIf { it.stamp == stamp }?.let { return it.value }

        val text = document?.immutableCharSequence ?: try {
            VfsUtilCore.loadText(file)
        } catch (_: IOException) {
            ""
        }
        return parse(text).also { cache[file] = Cached(stamp, it) }
    }

    companion object {
        fun getInstance(project: Project): SolutionService = project.service()
    }
}
