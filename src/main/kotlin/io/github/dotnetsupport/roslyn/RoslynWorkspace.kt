package io.github.dotnetsupport.roslyn

import io.github.dotnetsupport.lang.CSharpFeatures
import io.github.dotnetsupport.lang.CSharpFeature
import com.intellij.openapi.fileEditor.FileEditorManager
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
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspClientManager
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.cli.PluginLogsToolWindowFactory
import io.github.dotnetsupport.lsp.RoslynLanguageServer
import io.github.dotnetsupport.lsp.RoslynLanguageServerConfigurable
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynPhase
import io.github.dotnetsupport.lsp.RoslynPolicy
import io.github.dotnetsupport.lsp.RoslynServerStatus
import io.github.dotnetsupport.lsp.RoslynWorkspaceTarget
import io.github.dotnetsupport.solution.SolutionFinder
import org.eclipse.lsp4j.DidChangeConfigurationParams
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean


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

    /** For the widget of the status bar ([RoslynStatusWidget]). */
    @Volatile
    var phase: RoslynPhase = RoslynPhase.STARTING
        private set

    /** The file name of the solution the server loads, null for loose projects. */
    @Volatile
    var target: String? = null
        private set

    /** The absolute path of the solution the server of this session has been told to open, null for loose projects or no server. */
    @Volatile
    var loadedSolution: String? = null
        private set

    private fun phase(phase: RoslynPhase, target: String? = this.target) {
        this.phase = phase
        this.target = target
        RoslynStatusWidgetFactory.refresh(project)
    }

    /** The process the platform has started for the server (on Windows the `.cmd` of the tool: the server is its child). */
    @Volatile
    var serverProcess: ProcessHandle? = null
        private set

    /** Alive from the start of the process to its end, the loading of the solution included: what the widget of the status bar shows. */
    val isServerRunning: Boolean get() = serverProcess?.isAlive == true

    fun serverStarted(process: ProcessHandle?) {
        serverProcess = process
        synchronized(serverErrors) { serverErrors.setLength(0) }
        exitCode = null
        PluginLog.info(LOG_CATEGORY, "server process started" + (process?.let { ", pid ${it.pid()}" } ?: ""))
        RoslynStatusWidgetFactory.refresh(project)
    }

    /** The error stream of the server of this session: the host of .NET explains there why the server could not start. */
    private val serverErrors = StringBuilder()

    @Volatile
    private var exitCode: Int? = null

    fun serverPrinted(text: String) {
        val line = text.trimEnd()
        if (line.isEmpty()) return
        synchronized(serverErrors) { if (serverErrors.length < MAX_SERVER_ERRORS) serverErrors.append(line).append('\n') }
        PluginLog.warn(LOG_CATEGORY, "server stderr: $line")
    }

    fun serverExited(code: Int) {
        exitCode = code
        if (code == 0) PluginLog.info(LOG_CATEGORY, "server process exited") else PluginLog.warn(LOG_CATEGORY, "server process exited with code $code")
    }

    /** The platform could not even start the process: a path from the settings that is not there any more, a file without the right to run. */
    fun serverFailedToStart(e: Exception) {
        PluginLog.error(LOG_CATEGORY, "cannot start the server process", e)
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification("C# language server", "The server process could not be started: ${PluginLog.describe(e)}", NotificationType.ERROR)
            .addAction(NotificationAction.createSimple("Plugin Logs") { PluginLogsToolWindowFactory.show(project) })
            .addAction(NotificationAction.createSimple("Configure...") { com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, io.github.dotnetsupport.settings.DotNetSettingsConfigurable::class.java) })
            .notify(project)
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

    override fun semanticTokensRefresh() = refreshSemanticTokens()

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
        // the same walk as the Solution view: the same solutions in the same order
        val files = project.guessProjectDir()?.let { SolutionFinder.find(it, includeProjects = true) } ?: SolutionFinder.Found.EMPTY
        return Found(files.solutions.map { it.path }, files.projects.map { it.path }).also { found = it }
    }

    fun workspaceTarget(files: Found = scan()): RoslynWorkspaceTarget? = RoslynLanguageServer.workspaceTarget(files.solutions, absolute(state.solution), files.projects)

    fun serverInitialized() {
        if (project.isDisposed) return
        PluginLog.info(LOG_CATEGORY, "server initialized (handshake done), loading the workspace")
        isLoaded = false
        phase(RoslynPhase.STARTING, null)
        project.service<RoslynServerStatus>().isReady = false
        project.service<RoslynResponseMemo>().invalidate()
        // "the first request of a kind" means the first one of this process
        project.service<RoslynRequestStats>().reset()
        project.service<RoslynServerStatus>().coloredByServer.clear()
        cachedTokens.clear()
        // the platform decided whether to ask for tokens when the files were opened, before there was a server to key the cache by:
        // asked again when the platform opens each of them with the server, see documentOpened
        opened = false
        ApplicationManager.getApplication().executeOnPooledThread { open(workspaceTarget(found ?: scan())) }
    }

    private fun open(target: RoslynWorkspaceTarget?) {
        val client = clients.firstOrNull() ?: return PluginLog.warn(LOG_CATEGORY, "no LSP client for the server: nothing is loaded")
        PluginLog.info(LOG_CATEGORY, "workspace: $target")
        when (target) {
            is RoslynWorkspaceTarget.Choice -> phase(RoslynPhase.CHOOSING_SOLUTION, null)
            is RoslynWorkspaceTarget.Solution -> phase(RoslynPhase.LOADING, target.path.substringAfterLast('/'))
            else -> phase(RoslynPhase.LOADING, null)
        }
        loadedSolution(if (target is RoslynWorkspaceTarget.Solution) target.path else null)
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

    /** The banner of a file of another solution ([RoslynUnloadedSolutionBanner]) follows what the server loads. */
    private fun loadedSolution(path: String?) {
        if (loadedSolution == path) return
        loadedSolution = path
        if (!project.isDisposed) com.intellij.ui.EditorNotifications.getInstance(project).updateAllNotifications()
    }

    /** [path] is absolute: what Load <solution> of [RoslynUnloadedSolutionBanner] chooses, as if picked in [chooseSolution]. */
    fun loadSolution(path: String) = solutionChosen(relative(path))

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

    /**
     * The platform has opened [file] with the server (`didOpen`). A file shown before the server was initialized was asked about
     * ([hasCachedTokens]) when there was no legend to key the cache by, and the platform asks again only on the next pass of the daemon,
     * which nothing started before the solution was loaded: the cache of tokens never served a file opened with the project.
     */
    fun documentOpened(file: VirtualFile) {
        if (project.isDisposed || isLoaded || !hasCachedTokens(file)) return
        // the platform counts the file as open with the server only after this message is on its way, and a pass of the daemon before
        // that asks nobody: a moment later
        com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().schedule({ askForTokens(listOf(file)) }, OPENED_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * `workspace/semanticTokens/refresh`, of the server or of the plugin: the platform's own handling forgets the tokens of every file
     * at once and shows none until the new ones come (the colors of identifiers were gone for 100–300 ms, four times while aspnetcore
     * loaded). Here the tokens it has are only made stale — they are cached against the modification count of the PSI — and the daemon
     * is restarted on the open C# files: the platform shows what it has and asks again, and the answer replaces the old tokens in place.
     */
    fun refreshSemanticTokens() {
        askForTokens(null)
    }

    /** Has the platform ask for the tokens of [files] (null: of every open C# file) and keep showing the ones it has meanwhile. */
    private fun askForTokens(files: List<VirtualFile>?) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            // the platform keeps the tokens of a file against the modification count of the PSI and asks again once it has changed
            com.intellij.psi.PsiManager.getInstance(project).dropPsiCaches()
            restartHighlighting(files ?: com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles.filter(::isCSharpSource))
        }, ModalityState.nonModal(), project.disposed)
    }

    /** Restarts the daemon on [files] only: the rest of the project keeps what it shows. */
    fun restartHighlighting(files: Collection<VirtualFile>) {
        if (files.isEmpty()) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val psiManager = com.intellij.psi.PsiManager.getInstance(project)
            for (file in files) if (file.isValid) psiManager.findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
        }, project.disposed)
    }

    fun serverStopped(shutdownNormally: Boolean) {
        if (shutdownNormally) PluginLog.info(LOG_CATEGORY, "server stopped")
        else PluginLog.error(LOG_CATEGORY, "server stopped unexpectedly" + (exitCode?.let { " (exit code $it)" } ?: ""))
        isLoaded = false
        serverProcess = null
        opened = false
        // the server of a closed project stops after the project is disposed: no service of it may be looked up then
        if (project.isDisposed) return
        loadedSolution(null)
        project.service<RoslynSolutionProblems>().stop()
        phase(RoslynPhase.STARTING, null)
        if (!shutdownNormally) ApplicationManager.getApplication().executeOnPooledThread { explainCrash() }
        // back to the heuristics: colors, folding and the problems of the last build are theirs again
        project.service<RoslynServerStatus>().isReady = false
        project.service<RoslynServerStatus>().coloredByServer.clear()
        project.service<RoslynServerStatus>().loadedRoots = emptyList()
        project.service<RoslynResponseMemo>().invalidate()
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    private fun relative(path: String): String = project.guessProjectDir()?.let { FileUtil.getRelativePath(it.path, path, '/') } ?: path

    private fun absolute(relative: String?): String? = relative?.let { path -> project.guessProjectDir()?.let { "${it.path}/$path" } }

    // `Path.toUri` gives `file:///C:/...`, as the descriptor does; `File.toURI` would give `file:/C:/...`, which the server does not match
    private fun uri(descriptor: LspClientDescriptor, path: String): String =
        LocalFileSystem.getInstance().findFileByPath(path)?.let(descriptor::getFileUri) ?: RoslynLanguageServer.plainDriveUri(java.nio.file.Path.of(path).toUri().toString())

    override fun projectsLoaded() {
        if (project.isDisposed) return
        isLoaded = true
        phase(RoslynPhase.READY)
        // from here on the heuristics of the plugin step aside, see RoslynServerStatus
        project.service<RoslynServerStatus>().isReady = true
        // the answers given while the projects were loading are of a workspace that is not there any more
        project.service<RoslynResponseMemo>().invalidate()
        PluginLog.info(LOG_CATEGORY, "workspace is loaded" + (target?.let { ": $it" } ?: ""))
        // the files opened while it was loading have been shown without the errors of the compiler
        if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
        // the tokens shown so far came from the cache; they stay on screen until the answer of the loaded server replaces them
        refreshSemanticTokens()
        val client = clients.firstOrNull()
        val file = if (project.isDisposed) null else RoslynWarmUp.fileToWarmUp(project)
        if (client != null && file != null) ApplicationManager.getApplication().executeOnPooledThread { RoslynWarmUp.run(project, client, file) }
        // the errors of the whole solution for the Problems tool window
        if (client != null) project.service<RoslynSolutionProblems>().start(client)
    }

    /**
     * Server 5.12 restores by itself while it loads (checked with `tools/roslyn-lsp`), so this comes only with "Restore NuGet packages
     * when a project needs it" switched off: the user has asked not to restore silently, and gets a button.
     */
    override fun projectsNeedRestore(projectFiles: List<String>) {
        PluginLog.warn(LOG_CATEGORY, "packages are not restored for ${projectFiles.joinToString(", ") { File(it).name }}")
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
        val errors = synchronized(serverErrors) { serverErrors.toString() }
        val missingFramework = RoslynPolicy.missingFramework(errors)
        val runtimes = try {
            DotNetCli.execute(DotNetCli.commandLine(null, "--list-runtimes")).stdout
        } catch (e: Exception) {
            PluginLog.warn(LOG_CATEGORY, "cannot list the runtimes", e)
            ""
        }
        val missingRuntime = missingFramework != null || runtimes.isNotBlank() && !RoslynPolicy.hasRuntime(runtimes, SERVER_RUNTIME)
        val dotnet = DotNetCli.findExecutable()
        val text = when {
            missingFramework != null -> missingFramework.describe() + " Install the .NET $SERVER_RUNTIME runtime there, or point the plugin (Settings | .NET) at a <code>dotnet</code> that has it: the server is started with that installation as <code>DOTNET_ROOT</code>."
            missingRuntime -> "The server needs the .NET $SERVER_RUNTIME runtime, and <code>dotnet --list-runtimes</code> of <code>$dotnet</code> has no Microsoft.NETCore.App $SERVER_RUNTIME.x."
            errors.isNotBlank() -> "The server has stopped unexpectedly. It printed: <code>${errors.trim().lines().last()}</code>"
            else -> "The server has stopped unexpectedly. Its log says why."
        }
        PluginLog.error(LOG_CATEGORY, "crash explained: " + text.replace(Regex("</?code>"), "`") +
            "\n  dotnet: ${dotnet ?: "not found"}\n  runtimes: ${runtimes.trim().lines().filter { it.isNotBlank() }.joinToString("; ").ifEmpty { "unknown" }}")
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification("C# language server", text, NotificationType.ERROR)
        if (missingRuntime) notification.addAction(NotificationAction.createSimple("Download .NET $SERVER_RUNTIME") { BrowserUtil.browse("https://dotnet.microsoft.com/download/dotnet/$SERVER_RUNTIME.0") })
        notification.addAction(NotificationAction.createSimpleExpiring("Restart") { crashReported.set(false); restart() })
            .addAction(NotificationAction.createSimple("Plugin Logs") { PluginLogsToolWindowFactory.show(project) })
            .addAction(NotificationAction.createSimple("Server Log") { showLog() })
            .notify(project)
    }

    fun restart() {
        PluginLog.info(LOG_CATEGORY, "restart requested")
        LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(RoslynLspIntegrationProvider::class.java)
    }

    /**
     * Reload Solution starts the server anew: it is the one way to make it forget everything. Reload Project tells it that the file
     * of the project has changed, which makes it load that project again and leaves the rest of the solution as it is.
     */
    fun reloaded(projectFile: VirtualFile?) {
        if (projectFile == null) return restart()
        for (client in clients) {
            val uri = runCatching { client.descriptor.getFileUri(projectFile) }.getOrNull() ?: continue
            val event = org.eclipse.lsp4j.FileEvent(uri, org.eclipse.lsp4j.FileChangeType.Changed)
            client.sendNotification { it.workspaceService.didChangeWatchedFiles(org.eclipse.lsp4j.DidChangeWatchedFilesParams(listOf(event))) }
        }
        project.service<RoslynResponseMemo>().invalidate()
    }

    /**
     * Files created, deleted, renamed or changed on disk under the opened folder ([RoslynFileWatcher]): the notification the server
     * registered for and the platform never sends. A file that is gone has no VirtualFile any more, its URI is made from the path.
     */
    fun filesChanged(changes: List<RoslynWatchedFiles.Change>) {
        if (changes.isEmpty()) return
        for (client in clients) {
            val events = changes.map { org.eclipse.lsp4j.FileEvent(uri(client.descriptor, it.path), it.type) }
            client.sendNotification { it.workspaceService.didChangeWatchedFiles(org.eclipse.lsp4j.DidChangeWatchedFilesParams(events)) }
        }
        if (changes.any { it.type != org.eclipse.lsp4j.FileChangeType.Changed || !it.path.endsWith(".cs", ignoreCase = true) }) project.service<RoslynResponseMemo>().invalidate()
    }

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
        highlightingSwitched()
    }

    /** The switches of «Errors and warnings» and «Colors of identifiers» the highlights of the server were made with: from the start of the workspace. */
    private var nativeHighlighting = HIGHLIGHTING_FEATURES.map { CSharpFeatures.native(it, project) }

    /**
     * «Errors and warnings» or «Colors of identifiers» switched: the platform makes the highlights of the server's diagnostics and semantic
     * tokens when they arrive and keeps them (a restart of the daemon does not redo them, `workspace/diagnostic/refresh` of the client does
     * nothing), and which of them are shown depends on the switch (`createAnnotation` of [RoslynClientDescriptor], `getTextAttributesKey` of
     * its tokens): make them again from the answers it has, for the open C# files.
     */
    fun highlightingSwitched() {
        val before = nativeHighlighting
        val native = HIGHLIGHTING_FEATURES.map { CSharpFeatures.native(it, project) }
        nativeHighlighting = native
        if (before == native) return
        ApplicationManager.getApplication().invokeLater({
            // internal in Kotlin, public in the bytecode: by reflection, as LspCompletionObject (RoslynCompletionItems)
            runCatching {
                val type = Class.forName(HIGHLIGHTING_APPLIER, true, LspClientManager::class.java.classLoader)
                val companion = type.getField("Companion").get(null)
                val applier = companion.javaClass.getMethod("getInstance", Project::class.java).invoke(companion, project)
                val refresh = type.getMethod("scheduleHighlightingRefresh", VirtualFile::class.java)
                for (file in FileEditorManager.getInstance(project).openFiles) if (file.extension.equals("cs", ignoreCase = true)) refresh.invoke(applier, file)
            }.onFailure { PluginLog.warn(LOG_CATEGORY, "the server's errors and colors were not shown again after their source switched", it) }
        }, ModalityState.nonModal(), project.disposed)
    }

    fun offerInstallation() {
        if (!installationOffered.compareAndSet(false, true)) return
        DotNetTool.ROSLYN_LANGUAGE_SERVER.offerInstallation(project, "C# language server") {
            LspClientManager.getInstance(project).startClientsIfNeeded(RoslynLspIntegrationProvider::class.java)
        }
    }

    companion object {
        /** Makes the highlights of an LSP server for a file again, from the diagnostics and tokens the platform has (see [highlightingSwitched]). */
        private const val HIGHLIGHTING_APPLIER = "com.intellij.platform.lsp.impl.features.highlighting.LspHighlightingApplier"

        private val HIGHLIGHTING_FEATURES = listOf(CSharpFeature.DIAGNOSTICS, CSharpFeature.SEMANTIC_COLORS)

        /** `roslyn-language-server` 5.x is built for .NET 10. */
        const val SERVER_RUNTIME = 10

        /** The category of the journal of the plugin ([PluginLog]) for the server. */
        const val LOG_CATEGORY = "roslyn"

        /** From `didOpen` on its way to the server to the platform counting the file as open with it. */
        private const val OPENED_DELAY_MS = 300L

        /** How much of the error stream of a server is kept for the explanation of its end. */
        private const val MAX_SERVER_ERRORS = 8_000

        /** Where the server writes its log: the folder of the settings page, or the one next to the logs of the IDE. */
        fun logDirectory(): File = RoslynLanguageServerSettings.getInstance().state.logDirectory?.takeIf { it.isNotBlank() }?.let(::File) ?: RoslynLanguageServerConfigurable.defaultLogDirectory()
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

/** Reload Solution / Reload Project of the main part: the server reloads with them. */
class RoslynReloadListener(private val project: Project) : io.github.dotnetsupport.actions.SolutionReloadListener {
    override fun reloaded(projectFile: VirtualFile?) = project.service<RoslynWorkspace>().reloaded(projectFile)
}

/**
 * Indexing starts or ends: the features of the plugin that need the indexes give way to the server while it runs (`CSharpFeatures.native`),
 * so the highlights of the server's answers are made again, as on a switch of their source.
 */
class RoslynDumbModeListener(private val project: Project) : DumbService.DumbModeListener {
    override fun enteredDumbMode() { project.serviceIfCreated<RoslynWorkspace>()?.highlightingSwitched() }

    override fun exitDumbMode() { project.serviceIfCreated<RoslynWorkspace>()?.highlightingSwitched() }
}

