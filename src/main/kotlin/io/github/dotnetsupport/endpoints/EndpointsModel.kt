package io.github.dotnetsupport.endpoints

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import io.github.dotnetsupport.run.LaunchSettings
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile

class FoundEndpoint(val endpoint: Endpoint, val file: VirtualFile)

class EndpointsOfProject(val name: String, val projectFile: VirtualFile, val endpoints: List<FoundEndpoint>) {
    /** Where the application listens according to its first launch profile; null without `launchSettings.json`. */
    val baseUrl: String? get() = LaunchSettings.profiles(projectFile).firstNotNullOfOrNull { it.applicationUrls.firstOrNull() }
}

object EndpointsModel {
    private val SKIPPED_DIRECTORIES = setOf("bin", "obj", ".git", ".vs", ".idea", "node_modules", "wwwroot")

    /** Endpoints of every project of the solution that has any. Blocking: reads the sources. */
    fun discover(project: Project): List<EndpointsOfProject> {
        val solutions = SolutionService.getInstance(project)
        val projects = solutions.solutionFiles()
            .flatMap { file -> solutions.solution(file).allProjects.mapNotNull { p -> p.resolveFile(file)?.let { p.name to it } } }
            .distinctBy { it.second }
        val projectDirectories = projects.map { it.second.parent }.toSet()
        return projects
            // a project nested into the directory of another one is scanned on its own
            .map { (name, projectFile) -> EndpointsOfProject(name, projectFile, endpointsIn(projectFile.parent, projectDirectories - projectFile.parent)) }
            .filter { it.endpoints.isNotEmpty() }
            .sortedBy { it.name.lowercase() }
    }

    fun endpointsIn(directory: VirtualFile, excluded: Set<VirtualFile> = emptySet()): List<FoundEndpoint> {
        val result = ArrayList<FoundEndpoint>()
        VfsUtilCore.visitChildrenRecursively(directory, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) return file.name.lowercase() !in SKIPPED_DIRECTORIES && file !in excluded
                if (!file.extension.equals("cs", ignoreCase = true)) return true
                val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return true
                EndpointScanner.scan(text).mapTo(result) { FoundEndpoint(it, file) }
                return true
            }
        })
        return result.sortedWith(compareBy({ it.endpoint.route.lowercase() }, { METHOD_ORDER.indexOf(it.endpoint.method).let { i -> if (i < 0) METHOD_ORDER.size else i } }))
    }

    private val METHOD_ORDER = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
}

/** Requests for the HTTP Client, in the conventions of the `.http` file the ASP.NET templates create. */
object HttpRequestGenerator {
    private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
    private val REQUEST_LINE = Regex("""^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s""", RegexOption.MULTILINE)
    private const val DEFAULT_BASE_URL = "http://localhost:5000"

    /** `@Shop_HostAddress`: the name the templates of `dotnet new webapi` use. */
    fun hostVariable(projectName: String): String = projectName.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") + "_HostAddress"

    /** `/todos/{id:int}` -> `/todos/{{id}}`: route parameters become variables of the HTTP Client. */
    fun path(route: String): String = Endpoint.PARAMETER.replace(route) { "{{" + it.groupValues[1] + "}}" }

    fun request(endpoint: Endpoint, hostVariable: String): String = buildString {
        val method = if (endpoint.method == "*") "GET" else endpoint.method
        append("$method {{$hostVariable}}${path(endpoint.route)}\n")
        append("Accept: application/json\n")
        if (method in BODY_METHODS) append("Content-Type: application/json\n\n{\n}\n")
    }

    /**
     * [existing] is the current text of the `.http` file (empty for a new one). The host variable is declared when
     * missing; a request that is already there is not added twice. Returns the new text and the offset of the request.
     */
    fun append(existing: String, endpoint: Endpoint, projectName: String, baseUrl: String?): Pair<String, Int> {
        val variable = hostVariable(projectName)
        val request = request(endpoint, variable)
        val firstLine = request.lineSequence().first()
        existing.indexOf(firstLine).takeIf { it >= 0 }?.let { return existing to it }

        var text = existing.trimEnd()
        if (!Regex("""^@${Regex.escape(variable)}\s*=""", RegexOption.MULTILINE).containsMatchIn(text)) {
            text = "@$variable = ${baseUrl ?: DEFAULT_BASE_URL}" + if (text.isEmpty()) "" else "\n\n$text"
        }
        // requests are separated with "###"; the templates end the file with one, a hand-written file may not
        val hasRequests = REQUEST_LINE.containsMatchIn(text)
        if (hasRequests && !text.endsWith("###")) text += "\n\n###"
        text += "\n\n"
        return (text + request + "\n###\n") to text.length
    }
}
