package io.github.dotnetsupport.roslyn

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.analysis.problemsView.ProblemsProvider
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.lsp.RoslynLanguageServer
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PreviousResultId
import org.eclipse.lsp4j.WorkspaceDiagnosticParams
import org.eclipse.lsp4j.WorkspaceDiagnosticReport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon

private val LOG = logger<RoslynSolutionProblems>()

/**
 * Errors and warnings of the whole solution in the Problems tool window (its Project Errors tab, through its [ProblemsCollector]), from `workspace/diagnostic` of the server —
 * the solution-wide analysis of Rider, closed files included. What `tools/roslyn-lsp` found (2026-09-29, server 5.12): the server answers only when
 * "Compiler diagnostics for" is `fullSolution` (3.9 s for the playground), and a request after that hangs until something in the workspace changes
 * — so one request is kept open at all times, and every answer is the next state of the solution, as VS Code does it.
 */
@Service(Service.Level.PROJECT)
class RoslynSolutionProblems(override val project: Project) : Disposable, ProblemsProvider {
    /** One diagnostic of a file, as the Problems view shows it. */
    class Problem(override val provider: ProblemsProvider, override val file: VirtualFile, override val line: Int, override val column: Int, override val text: String, private val code: String?, private val error: Boolean) : FileProblem {
        override val description: String? get() = code
        override val group: String? get() = code
        override val icon: Icon get() = if (error) AllIcons.General.Error else AllIcons.General.Warning

        /** The same problem in the next answer of the server: nothing to tell the view. */
        fun sameAs(other: Problem) = file == other.file && line == other.line && column == other.column && text == other.text && code == other.code && error == other.error
    }

    /** uri -> the problems shown for it. */
    private val shown = HashMap<String, List<Problem>>()
    private val resultIds = HashMap<String, String>()
    private val generation = AtomicInteger()
    private val scopeHintShown = AtomicBoolean()
    private var detailed = 0

    override fun dispose() = stop()

    /** After the projects are loaded: the loop of requests, one at a time, until [stop] or a restart of the server. */
    fun start(client: LspClient) {
        val myGeneration = generation.incrementAndGet()
        if (!isFullSolutionScope()) {
            loopRunning = false
            clear()
            hintAboutScope()
            return
        }
        loopRunning = true
        ApplicationManager.getApplication().executeOnPooledThread {
            while (generation.get() == myGeneration && !project.isDisposed) {
                val report = try {
                    client.sendRequestSync(REQUEST_TIMEOUT_MS) { it.workspaceService.diagnostic(WorkspaceDiagnosticParams(previousIds())) }
                } catch (e: Exception) {
                    LOG.info("workspace/diagnostic: ${e.message}")
                    null
                }
                if (generation.get() != myGeneration) break
                if (report == null) { LOG.info("workspace/diagnostic: no answer, asking again in ${RETRY_MS / 1000} s"); Thread.sleep(RETRY_MS); continue }
                val changed = apply(client, report)
                LOG.info("workspace/diagnostic: ${report.items.orEmpty().size} documents, $changed changed, ${synchronized(shown) { shown.values.sumOf { it.size } }} problems shown")
                if (changed == 0) Thread.sleep(IDLE_MS) // an answer without changes: not a busy loop
            }
        }
    }

    /**
     * The diagnostics of an open document, from the `textDocument/diagnostic` the platform asks for the editor: Roslyn leaves the open
     * documents out of `workspace/diagnostic` (seen in the sandbox 2026-09-29: the errors of the open Program.cs were nowhere in the tab),
     * so the tab gets them from here. The key is the same uri: when the document is closed, the workspace answer takes over.
     */
    fun documentReport(uri: String, file: VirtualFile, diagnostics: List<Diagnostic>) {
        publish(uri, diagnostics.mapNotNull { toProblem(file, it) })
    }

    /**
     * A closed document: while the workspace answers cover the solution, its problems stay as they are until the next answer replaces
     * them (the same ones keep their rows: clearing here and adding again a moment later made the tab flicker on every closed tab).
     * Without the workspace loop nothing would replace them, so they go.
     */
    fun documentClosed(uri: String) {
        if (!loopRunning) publish(uri, emptyList())
    }

    @Volatile private var loopRunning = false

    /** The server is gone: what it reported is gone too. */
    fun stop() {
        generation.incrementAndGet()
        loopRunning = false
        clear()
    }

    private fun previousIds(): List<PreviousResultId> = synchronized(shown) { resultIds.map { (uri, id) -> PreviousResultId(uri, id) } }

    /** Publishes the differences to the Problems view; how many files changed. */
    internal fun apply(client: LspClient, report: WorkspaceDiagnosticReport): Int {
        var changed = 0
        for (entry in report.items.orEmpty()) {
            if (!entry.isLeft) continue // unchanged since the previous result id
            val full = entry.left
            // the result id first: a document that is skipped below would come back "changed" with every answer otherwise
            synchronized(shown) { full.resultId?.let { resultIds[full.uri] = it } }
            // documents of source generators (`roslyn-source-generated://`) are not files of the project
            if (!full.uri.startsWith("file:")) continue
            val file = client.descriptor.findFileByUri(full.uri)
            val problems = if (file == null) emptyList() else full.items.orEmpty().mapNotNull { toProblem(file, it) }
            // what a document with diagnostics turned into: the trail for "the tab is empty" (2026-09-29, seen in the sandbox)
            if (full.items.orEmpty().isNotEmpty() && problems.isEmpty() && detailed++ < DETAILED_LINES) {
                LOG.info("workspace/diagnostic: ${full.uri.substringAfterLast('/')}: ${full.items.size} diagnostics, severities ${full.items.map { it.severity }.distinct()}, file ${if (file == null) "not found" else "found"}, 0 problems")
            }
            if (publish(full.uri, problems)) changed++
        }
        return changed
    }

    /** The view learns what appeared and what disappeared; the rest stays as it is. */
    internal fun publish(uri: String, problems: List<Problem>): Boolean {
        val old: List<Problem>
        val kept: List<Problem>
        synchronized(shown) {
            old = shown[uri].orEmpty()
            // the collector knows a problem by the object it was given: a problem that is still there keeps its object
            kept = problems.map { p -> old.firstOrNull { it.sameAs(p) } ?: p }
            if (kept.isEmpty()) shown.remove(uri) else shown[uri] = kept
        }
        val gone = old.filter { o -> kept.none { it === o } }
        val new = kept.filter { p -> old.none { it === p } }
        if (gone.isEmpty() && new.isEmpty()) return false
        // the collector of the Project Errors tab keeps the counts and tells the view; it is the entry, not the topic
        val collector = ProblemsCollector.getInstance(project)
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            gone.forEach(collector::problemDisappeared)
            new.forEach(collector::problemAppeared)
        })
        return true
    }

    private fun clear() {
        val all = synchronized(shown) { shown.values.flatten().also { shown.clear(); resultIds.clear() } }
        if (all.isEmpty()) return
        val collector = ProblemsCollector.getInstance(project)
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) all.forEach(collector::problemDisappeared) })
    }

    /** Errors and warnings; the hints of the analyzers (IDE0300 "collection initialization can be simplified" ...) are for the editor. */
    fun toProblem(file: VirtualFile, diagnostic: Diagnostic): Problem? {
        val error = when (diagnostic.severity) {
            DiagnosticSeverity.Error -> true
            DiagnosticSeverity.Warning -> false
            else -> return null
        }
        val code = diagnostic.code?.let { if (it.isLeft) it.left else it.right?.toString() }
        val start = diagnostic.range?.start
        return Problem(this, file, start?.line ?: 0, start?.character ?: 0, diagnostic.message.orEmpty().trim(), code, error)
    }

    /** `background_analysis.dotnet_compiler_diagnostics_scope` of Settings | .NET | Language Server. */
    private fun isFullSolutionScope(): Boolean =
        RoslynLanguageServer.configuration(listOf(COMPILER_SCOPE), RoslynLanguageServerSettings.getInstance(), null).single()?.toString() == "fullSolution"

    private fun hintAboutScope() {
        if (!scopeHintShown.compareAndSet(false, true)) return
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification("Solution-wide problems are off", "The Problems tool window lists the errors of the whole solution when \"Compiler diagnostics for\" is fullSolution (Settings | .NET | Language Server).", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Open Settings") { ShowSettingsUtil.getInstance().showSettingsDialog(project, "Language Server") })
            .notify(project)
    }

    companion object {
        const val COMPILER_SCOPE = "background_analysis.dotnet_compiler_diagnostics_scope"
        private const val REQUEST_TIMEOUT_MS = 10 * 60 * 1000 // the server holds the request until the workspace changes
        private const val RETRY_MS = 5_000L
        private const val IDLE_MS = 1_000L
        private const val DETAILED_LINES = 12
    }
}
