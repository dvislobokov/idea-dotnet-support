package io.github.dotnetsupport.roslyn

import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/** `solution/open`: the URI of a `.sln` / `.slnx`. */
class SolutionOpenParams(val solution: String)

/** `project/open`: the URIs of project files, for a folder without a solution. */
class ProjectOpenParams(val projects: List<String>)

/** `codeAction/resolveFixAll`: a "Fix All: ..." action (its title and `data`) and one of its `FixAllFlavors` ("Document", "Project", "Solution"...). */
class FixAllParams(val title: String, val data: Any?, val scope: String)

/** `workspace/_roslyn_projectNeedsRestore`: the projects whose packages are not restored. */
class ProjectNeedsRestoreParams {
    var projectFilePaths: List<String> = emptyList()
}

/**
 * What `roslyn-language-server` understands on top of LSP. Without `--autoLoadProjects` the server loads nothing until the client
 * names a solution or projects. The shapes are the ones `tools/roslyn-lsp/bench.py` has checked against server 5.12.
 */
interface RoslynServer : LanguageServer {
    @JsonNotification("solution/open")
    fun openSolution(params: SolutionOpenParams)

    @JsonNotification("project/open")
    fun openProjects(params: ProjectOpenParams)

    /** The answer is the code action with its `edit` for the whole scope. */
    @JsonRequest("codeAction/resolveFixAll")
    fun resolveFixAll(params: FixAllParams): CompletableFuture<CodeAction?>
}

/** What the server says on top of LSP: the end of project loading and the projects that want `dotnet restore`. */
class RoslynLsp4jClient(handler: LspServerNotificationsHandler, private val events: Events) : Lsp4jClient(handler) {
    interface Events {
        fun projectsLoaded()
        fun projectsNeedRestore(projectFiles: List<String>)
    }

    @JsonNotification("workspace/projectInitializationComplete")
    fun projectInitializationComplete() = events.projectsLoaded()

    @JsonRequest("workspace/_roslyn_projectNeedsRestore")
    fun projectNeedsRestore(params: ProjectNeedsRestoreParams?): CompletableFuture<Void?> {
        events.projectsNeedRestore(params?.projectFilePaths.orEmpty())
        return CompletableFuture.completedFuture(null)
    }
}
