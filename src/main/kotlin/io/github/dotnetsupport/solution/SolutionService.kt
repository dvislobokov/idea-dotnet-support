package io.github.dotnetsupport.solution

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.msbuild.ProjectAssets
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Parsed solution and MSBuild files of the project, cached until the file (or its unsaved document) changes. */
@Service(Service.Level.PROJECT)
class SolutionService(private val project: Project) {
    private class Cached<T>(val stamp: Long, val value: T)

    private val solutions = ConcurrentHashMap<VirtualFile, Cached<Solution>>()
    private val filters = ConcurrentHashMap<VirtualFile, Cached<SolutionFilter?>>()
    private val msBuildProjects = ConcurrentHashMap<VirtualFile, Cached<MsBuildProject>>()
    private val assetsFiles = ConcurrentHashMap<VirtualFile, Cached<ProjectAssets>>()

    /** The solutions of the opened folder, found by the last walk; dropped by [solutionFilesChanged] when files come and go. */
    @Volatile private var found: Pair<VirtualFile, SolutionFinder.Found>? = null

    /** Solution files (`.sln`, `.slnx`) of the opened directory, the ones in its root first (see [SolutionFinder]). */
    fun solutionFiles(): List<VirtualFile> = find().solutions.filter { it.isValid }

    /** Solution filters (`.slnf`) of the opened directory. */
    fun solutionFilters(): List<VirtualFile> = find().filters.filter { it.isValid }

    /** What the Solution view shows at its root: solutions and the filters of them. */
    fun allSolutionFiles(): List<VirtualFile> = solutionFiles() + solutionFilters()

    /** A solution or a filter file appeared, disappeared or moved: the next question walks the folder again. */
    fun solutionFilesChanged() {
        found = null
    }

    /**
     * Reload Solution / Reload Project: what was parsed is forgotten, whatever the time stamps say. [projectFile] null: everything,
     * the list of solutions included.
     */
    fun reload(projectFile: VirtualFile? = null) {
        if (projectFile == null) {
            found = null
            solutions.clear()
            filters.clear()
            msBuildProjects.clear()
            assetsFiles.clear()
            return
        }
        msBuildProjects.remove(projectFile)
        val directory = projectFile.parent
        assetsFiles.keys.removeIf { it.parent?.parent == directory }
        // the props and targets around it are read through the same cache
        msBuildProjects.keys.removeIf { !it.isValid || it.extension?.lowercase() in IMPORTED }
    }

    /** How many files are parsed and kept: for the tests of the reload. */
    val cachedFiles: Int get() = solutions.size + filters.size + msBuildProjects.size + assetsFiles.size

    private fun find(): SolutionFinder.Found {
        val baseDir = project.guessProjectDir() ?: return SolutionFinder.Found.EMPTY
        found?.takeIf { it.first == baseDir }?.let { return it.second }
        return SolutionFinder.find(baseDir).also { found = baseDir to it }
    }

    /** [file] is a solution or a filter; for a filter, the solution it names as seen through it (nothing when the solution is not on disk). */
    fun solution(file: VirtualFile): Solution {
        if (isSolutionFilterFile(file)) {
            val filter = solutionFilter(file) ?: return Solution(SlnFolder("", Solution.ROOT_ID), filtered = true, total = 0)
            val solutionFile = filter.solutionFile(file) ?: return Solution(SlnFolder("", Solution.ROOT_ID), filtered = true, total = 0)
            return filter.apply(solution(solutionFile))
        }
        return cached(solutions, file) { SolutionParser.parse(it, file.extension) }
    }

    fun solutionFilter(file: VirtualFile): SolutionFilter? = cached(filters, file, SolutionFilter::parse)

    /** The solution a filter file points at. */
    fun SolutionFilter.solutionFile(filterFile: VirtualFile): VirtualFile? =
        filterFile.parent?.findFileByRelativePath(solutionPath)?.takeIf { !it.isDirectory && it.extension?.lowercase() in SOLUTION_EXTENSIONS }

    fun msBuildProject(file: VirtualFile): MsBuildProject =
        cached(msBuildProjects, file, MsBuildProject::parse)

    /** What `dotnet restore` resolved for the project; empty until it is restored. */
    fun assets(projectFile: VirtualFile): ProjectAssets {
        val file = projectFile.parent?.findFileByRelativePath("obj/project.assets.json") ?: return ProjectAssets.EMPTY
        return cached(assetsFiles, file, ProjectAssets::parse)
    }

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
        private val IMPORTED = setOf("props", "targets")

        fun getInstance(project: Project): SolutionService = project.service()
    }
}
