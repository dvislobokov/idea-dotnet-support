package io.github.dotnetsupport.solution

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import io.github.dotnetsupport.msbuild.DotNetProjects

/**
 * Solutions of an opened folder: the ones in its root and the ones deeper down (a repository with `samples/Sample.sln`,
 * a solution next to the sources in `src/`). Build output, package folders and dot-directories are not walked.
 */
object SolutionFinder {
    val SKIPPED_DIRECTORIES: Set<String> = setOf("bin", "obj", "node_modules", "packages", "bower_components", "wwwroot")

    class Found(val solutions: List<VirtualFile>, val filters: List<VirtualFile>, val projects: List<VirtualFile>) {
        companion object {
            val EMPTY = Found(emptyList(), emptyList(), emptyList())
        }
    }

    fun isSkipped(directory: VirtualFile): Boolean = directory.name.lowercase() in SKIPPED_DIRECTORIES || directory.name.startsWith(".")

    /** Shallow files first, then by name: the solution in the root of the folder stays "the" solution of the tools. Not for EDT on big folders. */
    fun find(root: VirtualFile, includeProjects: Boolean = false): Found {
        val solutions = ArrayList<VirtualFile>()
        val filters = ArrayList<VirtualFile>()
        val projects = ArrayList<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) return file == root || !isSkipped(file)
                val extension = file.extension?.lowercase()
                when {
                    extension in SOLUTION_EXTENSIONS -> solutions += file
                    extension == SOLUTION_FILTER_EXTENSION -> filters += file
                    includeProjects && DotNetProjects.isProjectFile(file) -> projects += file
                }
                return true
            }
        })
        val order = compareBy<VirtualFile>({ depth(root, it) }, { it.name.lowercase() })
        return Found(solutions.sortedWith(order), filters.sortedWith(order), projects.sortedWith(order))
    }

    private fun depth(root: VirtualFile, file: VirtualFile): Int = VfsUtilCore.getRelativePath(file, root, '/')?.count { it == '/' } ?: Int.MAX_VALUE
}
