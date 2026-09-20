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

/** [configurations]: build configurations the solution declares (`Debug`, `Release`, ...), in the order of the file. */
class Solution(val root: SlnFolder, val configurations: List<String> = emptyList()) {
    val allProjects: List<SlnProject> = buildList { collectProjects(root, this) }

    fun findFolder(id: String): SlnFolder? = findFolder(root, id)

    /** Names of the folder and its ancestors joined with `/`, the form `dotnet sln add --solution-folder` expects. */
    fun folderPath(id: String): String? = folderPath(root, id, "")

    private fun folderPath(folder: SlnFolder, id: String, prefix: String): String? {
        for (child in folder.folders) {
            val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.id == id) return path
            folderPath(child, id, path)?.let { return it }
        }
        return null
    }

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
