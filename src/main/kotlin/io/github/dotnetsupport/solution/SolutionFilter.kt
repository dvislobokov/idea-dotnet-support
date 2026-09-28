package io.github.dotnetsupport.solution

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader

/**
 * A solution filter (`.slnf`): a solution and the subset of its projects to load, the way Visual Studio and `dotnet build`
 * read it. [solutionPath] is relative to the filter file, [projects] are relative to the solution, all with `/` separators.
 */
class SolutionFilter(val solutionPath: String, val projects: List<String>) {
    private val included = projects.mapTo(HashSet()) { it.lowercase() }

    fun includes(project: SlnProject): Boolean = project.path.lowercase() in included

    /** The solution with the projects the filter leaves out removed; folders that are left with nothing in them go as well. */
    fun apply(solution: Solution): Solution = Solution(prune(solution.root)!!, solution.configurations, filtered = true, total = solution.allProjects.size)

    private fun prune(folder: SlnFolder): SlnFolder? {
        val result = SlnFolder(folder.name, folder.id)
        folder.folders.mapNotNullTo(result.folders, ::prune)
        folder.projects.filterTo(result.projects, ::includes)
        result.files += folder.files
        val empty = result.folders.isEmpty() && result.projects.isEmpty() && result.files.isEmpty()
        return if (empty && folder.id != Solution.ROOT_ID) null else result
    }

    companion object {
        private val TRAILING_COMMA = Regex(""",(\s*[}\]])""")

        /** `{ "solution": { "path": "..\\All.sln", "projects": [ "src\\App\\App.csproj" ] } }`; null when the text is not a filter. */
        fun parse(text: CharSequence): SolutionFilter? {
            val root = try {
                JsonParser.parseReader(JsonReader(StringReader(text.toString().replace(TRAILING_COMMA, "$1"))).apply { strictness = Strictness.LENIENT }) as? JsonObject
            } catch (_: Exception) {
                null
            }
            val solution = root?.get("solution") as? JsonObject ?: return null
            val path = solution.get("path")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val projects = solution.get("projects")?.takeIf { it.isJsonArray }?.asJsonArray?.asList().orEmpty()
                .filter { it.isJsonPrimitive }
                .map { normalize(it.asString) }
            return SolutionFilter(normalize(path), projects)
        }

        private fun normalize(path: String): String = path.trim().replace('\\', '/')
    }
}
