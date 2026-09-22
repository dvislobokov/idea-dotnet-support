package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspClientManager
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.lsp.RoslynLanguageServer
import io.github.dotnetsupport.lsp.RoslynLanguageServerConfigurable
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynPhase
import io.github.dotnetsupport.lsp.RoslynPolicy
import io.github.dotnetsupport.lsp.RoslynServerStatus
import io.github.dotnetsupport.lsp.RoslynWorkspaceTarget
import io.github.dotnetsupport.msbuild.DotNetProjects
import org.eclipse.lsp4j.DidChangeConfigurationParams
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<RoslynWorkspace>()

/**
 * What the server of this project loads and has loaded, and the talk with it that is specific to Roslyn. A solution is always named
 * by the plugin (`solution/open`); of several solutions under the opened folder the user chooses one, and the choice is kept with
 * the project.
 */
@Service(Service.Level.PROJECT)
@State(name = "DotNetRoslynWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class RoslynWorkspace(private val project: Project) : SimplePersistentStateComponent<RoslynWorkspace.Choice>(Choice()), RoslynLsp4jClient.Events, Disposable {
    class Choice : BaseState() {
        /** The solution to load when the folder has several, relative to the folder. */
        var solution by string()
    }

    /** Solution and project files under the opened folder, as absolute paths. */
    class Found(val solutions: List<String>, val projects: List<String>)

    @Volatile
    var isLoaded: Boolean = false
        private set

    /** For the widget of language services; [statusChanged] redraws it. */
    @Volatile
    var phase: RoslynPhase = RoslynPhase.STARTING
        private set

    /** The file name of the solution the server loads, null for loose projects. */
    @Volatile
    var target: String? = null
        private set

    /** Set by [RoslynWidgetUpdater]: the platform redraws the widget on the states of the process, not on what Roslyn is loading. */
    @Volatile
    var statusChanged: () -> Unit = {}

    private fun phase(phase: RoslynPhase, target: String? = this.target) {
        this.phase = phase
        this.target = target
        statusChanged()
    }

    /** The server of this session has been told what to load (or loads by itself). */
    @Volatile
    private var opened = false

    @Volatile
    private var found: Found? = null
    private val installationOffered = AtomicBoolean()
    private val serverWrapped = AtomicBoolean()
    private val restoreOffered = AtomicBoolean()
    private val crashReported = AtomicBoolean()

    /** The client of the protocol of the running server: set by the descriptor, used to have the platform ask for tokens anew. */
    @Volatile
    var lsp4jClient: RoslynLsp4jClient? = null

    /** file -> (modification stamp, whether the cache has tokens for that text): asked by every highlighting pass while the solution loads. */
    private val cachedTokens = ConcurrentHashMap<VirtualFile, Pair<Long, Boolean>>()

    override fun dispose() = Unit

    /** Before the first server of the project starts: see [RoslynServerWrapper]. */
    @Suppress("UnstableApiUsage")
    fun wrapServer() {
        if (serverWrapped.compareAndSet(false, true)) LspClientManager.getInstance(project).addLsp4jServerWrapper(RoslynServerWrapper(), this)
    }

    val clients: Collection<LspClient> get() = LspClientManager.getInstance(project).getClients(RoslynLspIntegrationProvider::class.java)

    /** The solutions found by the last [scan]: for the places that must not walk the folder themselves (the update of an action). */
    val knownSolutions: List<String> get() = found?.solutions.orEmpty()

    /** Walks the opened folder; a solution deep inside counts as well as the one in its root. Not for EDT. */
    fun scan(): Found {
        val solutions = mutableListOf<String>()
        val projects = mutableListOf<String>()
        project.guessProjectDir()?.let { root ->
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) return file == root || (file.name !in SKIPPED_DIRECTORIES && !file.name.startsWith("."))
                    when {
                        file.extension?.lowercase() in SOLUTION_EXTENSIONS -> solutions += file.path
                        DotNetProjects.isProjectFile(file) -> projects += file.path
                    }
                    return true
                }
            })
        }
        return Found(solutions, projects).also { found = it }
    }

    fun workspaceTarget(files: Found = scan()): RoslynWorkspaceTarget? = RoslynLanguageServer.workspaceTarget(files.solutions, absolute(state.solution), files.projects)

    fun serverInitialized() {
        isLoaded = false
        phase(RoslynPhase.STARTING, null)
        project.service<RoslynServerStatus>().isReady = false
        project.service<RoslynResponseMemo>().invalidate()
        // "the first request of a kind" means the first one of this process
        project.service<RoslynRequestStats>().reset()
        project.service<RoslynServerStatus>().coloredFromCache.clear()
        cachedTokens.clear()
        // the platform decided whether to ask for tokens when the files were opened, before there was a server to key the cache by
        lsp4jClient?.refreshSemanticTokens()
        opened = false
        ApplicationManager.getApplication().executeOnPooledThread { open(workspaceTarget(found ?: scan())) }
    }

    private fun open(target: RoslynWorkspaceTarget?) {
        val client = clients.firstOrNull() ?: return LOG.warn("No LSP client for the Roslyn server")
        LOG.info("Roslyn workspace: $target")
        when (target) {
            is RoslynWorkspaceTarget.Choice -> phase(RoslynPhase.CHOOSING_SOLUTION, null)
            is RoslynWorkspaceTarget.Solution -> phase(RoslynPhase.LOADING, target.path.substringAfterLast('/'))
            else -> phase(RoslynPhase.LOADING, null)
        }
        // what the server will know about: Go to Class / Symbol of the plugin leaves these files to it, see RoslynServerStatus.covers
        project.service<RoslynServerStatus>().loadedRoots = when (target) {
            is RoslynWorkspaceTarget.Solution -> listOf(target.path.substringBeforeLast('/'))
            is RoslynWorkspaceTarget.Projects -> target.paths.map { it.substringBeforeLast('/') }
            else -> emptyList()
        }
        when (target) {
            is RoslynWorkspaceTarget.Solution -> client.sendNotification { (it as RoslynServer).openSolution(SolutionOpenParams(uri(client.descriptor, target.path))) }
            // nothing is loaded before the user has answered, and nothing is reported as an error meanwhile
            is RoslynWorkspaceTarget.Choice -> return chooseSolution(target.solutions)
            // with --autoLoadProjects the server has found the projects itself, naming them again would load them twice
            is RoslynWorkspaceTarget.Projects -> if (!RoslynLanguageServerSettings.getInstance().state.autoLoadProjects) {
                client.sendNotification { (it as RoslynServer).openProjects(ProjectOpenParams(target.paths.map { path -> uri(client.descriptor, path) })) }
            }
            null -> projectsLoaded() // loose files: nothing to wait for
        }
        opened = true
    }

    /** The list of solutions to choose from; dismissed, it leaves a notification to come back to. */
    fun chooseSolution(solutions: List<String> = knownSolutions) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || solutions.isEmpty()) return@invokeLater
            val current = state.solution
            JBPopupFactory.getInstance().createPopupChooserBuilder(solutions.map(::relative))
                .setTitle("Select Solution for C# Language Server")
                .apply { if (current != null) setSelectedValue(current, true) }
                .setItemChosenCallback { solutionChosen(it) }
                .addListener(object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        if (!event.isOk && !opened) remindToChoose(solutions.size)
                    }
                })
                .createPopup().showCenteredInCurrentWindow(project)
        }, ModalityState.nonModal())
    }

    private fun remindToChoose(count: Int) {
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification("C# language server", "$count solutions are found in the opened folder. The server loads one of them: errors, completion and navigation wait for the choice.", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Select Solution...") { chooseSolution() })
            .notify(project)
    }

    /** [solution] is relative to the opened folder. The server holds one solution: another one means another server. */
    fun solutionChosen(solution: String) {
        val changed = state.solution != solution
        state.solution = solution
        when {
            !opened && clients.isNotEmpty() -> ApplicationManager.getApplication().executeOnPooledThread { open(workspaceTarget(found ?: scan())) }
            changed -> LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(RoslynLspIntegrationProvider::class.java)
        }
    }

    /**
     * Whether the platform should ask for the semantic tokens of [file] while the solution loads: only when the cache has them for its
     * text, otherwise the server would answer for a half-loaded workspace and the heuristics of the plugin are the better colors.
     */
    fun hasCachedTokens(file: VirtualFile): Boolean {
        // asked while the server is still initializing: no legend yet, so no key, and nothing to remember either
        val client = clients.firstOrNull { it.initializeResult?.capabilities?.semanticTokensProvider != null } ?: return false
        val stamp = FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: return false
        cachedTokens[file]?.takeIf { it.first == stamp }?.let { return it.second }
        val text = FileDocumentManager.getInstance().getCachedDocument(file)?.immutableCharSequence ?: return false
        val cached = service<RoslynTokensCache>().store.contains(RoslynTokenStore.key(RoslynServerWrapper.legendKey(client), text))
        cachedTokens[file] = stamp to cached
        return cached
    }

    fun serverStopped(shutdownNormally: Boolean) {
        isLoaded = false
        opened = false
        if (project.isDisposed) return
        phase(RoslynPhase.STARTING, null)
        if (!shutdownNormally) ApplicationManager.getApplication().executeOnPooledThread { explainCrash() }
        // back to the heuristics: colors, folding and the problems of the last build are theirs again
        project.service<RoslynServerStatus>().isReady = false
        project.service<RoslynServerStatus>().coloredFromCache.clear()
        project.service<RoslynServerStatus>().loadedRoots = emptyList()
        project.service<RoslynResponseMemo>().invalidate()
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    private fun relative(path: String): String = project.guessProjectDir()?.let { FileUtil.getRelativePath(it.path, path, '/') } ?: path

    private fun absolute(relative: String?): String? = relative?.let { path -> project.guessProjectDir()?.let { "${it.path}/$path" } }

    private fun uri(descriptor: LspClientDescriptor, path: String): String =
        LocalFileSystem.getInstance().findFileByPath(path)?.let(descriptor::getFileUri) ?: File(path).toURI().toString()

    override fun projectsLoaded() {
        isLoaded = true
        phase(RoslynPhase.READY)
        // from here on the heuristics of the plugin step aside, see RoslynServerStatus
        project.service<RoslynServerStatus>().isReady = true
        // the answers given while the projects were loading are of a workspace that is not there any more
        project.service<RoslynResponseMemo>().invalidate()
        LOG.info("Roslyn workspace is loaded")
        // the files opened while it was loading have been shown without the errors of the compiler
        if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
        // the tokens shown so far came from the cache; the platform keeps what it has got until it is told to ask again
        lsp4jClient?.refreshSemanticTokens()
        project.service<RoslynServerStatus>().coloredFromCache.clear()
        val client = clients.firstOrNull()
        val file = if (project.isDisposed) null else RoslynWarmUp.fileToWarmUp(project)
        if (client != null && file != null) ApplicationManager.getApplication().executeOnPooledThread { RoslynWarmUp.run(project, client, file) }
    }

    /**
     * Server 5.12 restores by itself while it loads (checked with `tools/roslyn-lsp`), so this comes only with "Restore NuGet packages
     * when a project needs it" switched off: the user has asked not to restore silently, and gets a button.
     */
    override fun projectsNeedRestore(projectFiles: List<String>) {
        LOG.info("Roslyn: packages are not restored for $projectFiles")
        if (projectFiles.isEmpty() || !restoreOffered.compareAndSet(false, true)) return
        val names = projectFiles.joinToString(", ") { File(it).name }
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification("C# language server", "NuGet packages are not restored: $names. Until they are, the code of these projects is full of unresolved references.", NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring("Restore") {
                restoreOffered.set(false)
                val commands = DotNetCli.commandLinesOrNotify(project, "Restore") { projectFiles.map { DotNetCli.commandLine(File(it).parent, "restore", it) } } ?: return@createSimpleExpiring
                DotNetCli.runInBackground(project, "Restore", commands, refresh = projectFiles.map { File(File(it).parentFile, "obj") })
            })
            .notify(project)
    }

    /** The process is gone without a shutdown. The usual reason on a fresh machine is the runtime: the tool installs without .NET 10 and cannot run. */
    private fun explainCrash() {
        if (project.isDisposed || !crashReported.compareAndSet(false, true)) return
        val runtimes = runCatching { DotNetCli.execute(DotNetCli.commandLine(null, "--list-runtimes")).stdout }.getOrDefault("")
        val missingRuntime = runtimes.isNotBlank() && !RoslynPolicy.hasRuntime(runtimes, SERVER_RUNTIME)
        val text = if (missingRuntime) "The server needs the .NET $SERVER_RUNTIME runtime, and <code>dotnet --list-runtimes</code> has no Microsoft.NETCore.App $SERVER_RUNTIME.x."
        else "The server has stopped unexpectedly. Its log says why."
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification("C# language server", text, NotificationType.ERROR)
        if (missingRuntime) notification.addAction(NotificationAction.createSimple("Download .NET $SERVER_RUNTIME") { BrowserUtil.browse("https://dotnet.microsoft.com/download/dotnet/$SERVER_RUNTIME.0") })
        notification.addAction(NotificationAction.createSimpleExpiring("Restart") { crashReported.set(false); restart() })
            .addAction(NotificationAction.createSimple("Show Log") { showLog() })
            .notify(project)
    }

    fun restart() = LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(RoslynLspIntegrationProvider::class.java)

    fun showLog() = RevealFileAction.openDirectory(logDirectory().apply { mkdirs() })

    fun settingsChanged(restart: Boolean) {
        if (project.isDisposed) return
        val manager = LspClientManager.getInstance(project)
        when {
            !RoslynLanguageServerSettings.getInstance().state.enabled -> manager.stopClients(RoslynLspIntegrationProvider::class.java)
            restart || clients.isEmpty() -> manager.stopAndRestartClientsIfNeeded(RoslynLspIntegrationProvider::class.java)
            // the server answers with `workspace/configuration` requests for every section it knows
            else -> clients.forEach { client -> client.sendNotification { it.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(emptyMap<String, Any>())) } }
        }
    }

    fun offerInstallation() {
        if (!installationOffered.compareAndSet(false, true)) return
        DotNetTool.ROSLYN_LANGUAGE_SERVER.offerInstallation(project, "C# language server") {
            LspClientManager.getInstance(project).startClientsIfNeeded(RoslynLspIntegrationProvider::class.java)
        }
    }

    companion object {
        /** `roslyn-language-server` 5.x is built for .NET 10. */
        const val SERVER_RUNTIME = 10

        /** Where the server writes its log: the folder of the settings page, or the one next to the logs of the IDE. */
        fun logDirectory(): File = RoslynLanguageServerSettings.getInstance().state.logDirectory?.takeIf { it.isNotBlank() }?.let(::File) ?: RoslynLanguageServerConfigurable.defaultLogDirectory()

        private val SOLUTION_EXTENSIONS = setOf("sln", "slnx")
        private val SKIPPED_DIRECTORIES = setOf("bin", "obj", "node_modules", "packages")
    }
}

/** Menu .NET | Select Solution for Language Server: which of the solutions of the opened folder the server loads. */
class SelectRoslynSolutionAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // one solution leaves nothing to choose; the folder is walked when the server starts, not here
        e.presentation.isEnabled = (e.project?.service<RoslynWorkspace>()?.knownSolutions?.size ?: 0) > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<RoslynWorkspace>()?.chooseSolution()
    }
}

/** Menu .NET | Restart C# Language Server: a new process and a fresh load of the solution. */
class RestartRoslynServerAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && RoslynLanguageServerSettings.getInstance().state.enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<RoslynWorkspace>()?.restart()
    }
}

/** Menu .NET | Show Language Server Log: the folder the server writes its log to. */
class ShowRoslynServerLogAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        RevealFileAction.openDirectory(RoslynWorkspace.logDirectory().apply { mkdirs() })
    }
}
