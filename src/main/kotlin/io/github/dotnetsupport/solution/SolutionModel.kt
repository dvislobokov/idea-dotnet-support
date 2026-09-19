package io.github.dotnetsupport.solution

/** A project entry of a solution. [path] is relative to the solution directory and uses `/` separators. */
data class SlnProject(val name: String, val path: String, val id: String)

/** A solution folder: a virtual grouping that exists only inside the solution file. */
class SlnFolder(val name: String, val id: String) {
    val folders: MutableList<SlnFolder> = mutableListOf()
    val projects: MutableList<SlnProject> = mutableListOf()

    /** "Solution items": paths of loose files, relative to the solution directory. */
    val files: MutableList<String> = mutableListOf()
}

class Solution(val root: SlnFolder) {
    val allProjects: List<SlnProject> = buildList { collectProjects(root, this) }

    fun findFolder(id: String): SlnFolder? = findFolder(root, id)

    private fun collectProjects(folder: SlnFolder, result: MutableList<SlnProject>) {
        result += folder.projects
        folder.folders.forEach { collectProjects(it, result) }
    }

    private fun findFolder(folder: SlnFolder, id: String): SlnFolder? {
        if (folder.id == id) return folder
        return folder.folders.firstNotNullOfOrNull { findFolder(it, id) }
    }

    companion object {
        const val ROOT_ID = ""
    }
}
