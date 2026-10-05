package io.github.dotnetsupport.codeanalysis

import com.google.gson.JsonObject
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.build.BuildViewEvents
import io.github.dotnetsupport.build.DotNetBuildListener
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetHelper
import io.github.dotnetsupport.cli.HelperConnection
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.settings.DotNetSettings
import io.github.dotnetsupport.solution.SolutionService
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What the generators of a project made the last time; [stale] while a C# file of the project is edited and not yet generated again. */
class GeneratedState(val projectPath: String, val run: GeneratedRun, val output: String) {
    @Volatile var stale = false
}

/** The analyzer diagnostics of one file, with the text of each one's line then: the way to find it again after edits (as [io.github.dotnetsupport.build.BuildProblems]). */
class AnalyzedFile(val projectPath: String, val diagnostics: List<AnalyzerDiagnostic>, val lineTexts: List<String?>, val target: JsonObject)

/**
 * Source generators and Roslyn analyzers without the language server (CSHARP_PSI_MIGRATION.md, D3 and D4), through CodeAnalysisHelper
 * (`helpers/codeanalysis`): one helper per solution, started at the first request, stopped after [DotNetSettings.codeAnalysisIdleMinutes]
 * without one. Generators run on the save of a C# file of the project, after a build, when a project file changes and on Refresh Generated
 * Files; their files go to the caches of the IDE ([outputRoot]) and are a library of the project ([GeneratedSourcesRootsProvider]), so the
 * semantics of the plugin sees what they declare. Analyzers run on the save of a file (that file) and on Run Code Analysis (the project);
 * their diagnostics are annotations ([AnalyzerAnnotator]) with the code fixes of the analyzers, which the helper applies.
 *
 * The analyzers stand back while the language server is enabled: it runs them itself, a second copy would double every warning.
 */
@Service(Service.Level.PROJECT)
class CodeAnalysisService(private val project: Project) : Disposable {
    private val generated = ConcurrentHashMap<String, GeneratedState>()
    private val analyzed = ConcurrentHashMap<String, AnalyzedFile>()
    /** Changes when the generated files of a project become fresh or stale: the semantic errors that trust them are computed again. */
    val modificationTracker = com.intellij.openapi.util.SimpleModificationTracker()
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("CodeAnalysis", 1)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val idleAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    /** Projects waiting for the next run: path -> what to do (generate, the files to analyze). */
    private val pending = ConcurrentHashMap<String, Pending>()
    private val inFlight = AtomicInteger()
    @Volatile private var connection: HelperConnection? = null
    @Volatile private var lastUse = 0L
    @Volatile private var disposed = false
    @Volatile private var testConnection: HelperConnection? = null

    private class Pending(val projectFile: VirtualFile) {
        @Volatile var generate = false
        val paths: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    init {
        project.messageBus.connect(this).subscribe(DotNetBuildListener.TOPIC, DotNetBuildListener { _, _ -> afterBuild() })
    }

    // ---- what the rest of the plugin asks

    /** Where the generated files of this solution go: `<caches>/dotnet-support/generated/<folder of the IDE project>-<hash>`. */
    fun outputRoot(): String = root

    private val root: String by lazy {
        CodeAnalysisAnswers.outputFolder(CodeAnalysisAnswers.normalize(File(DotNetHelper.root(), "generated").path), project.basePath ?: project.name)
    }

    fun generatedState(projectFile: VirtualFile): GeneratedState? = generated[key(projectFile.path)]

    /**
     * The generated files of [projectFile] and of the projects it references are there, no generator failed, and nothing of those projects
     * has been edited since they were made: what the generators declare is all in the index.
     */
    fun isGeneratedFresh(projectFile: VirtualFile): Boolean = fresh(projectFile, HashSet())

    private fun fresh(projectFile: VirtualFile, seen: MutableSet<String>): Boolean {
        if (!seen.add(key(projectFile.path))) return true
        val state = generated[key(projectFile.path)] ?: return false
        if (state.stale || state.run.errors.isNotEmpty()) return false
        return references(projectFile).all { fresh(it, seen) }
    }

    private fun references(projectFile: VirtualFile): List<VirtualFile> =
        SolutionService.getInstance(project).msBuildProject(projectFile).projectReferences.mapNotNull { projectFile.parent?.findFileByRelativePath(it.replace('\\', '/')) }

    /** The project whose generators made [file]; null for any other file. Cheap: a prefix and a map. */
    fun projectOfGenerated(file: VirtualFile): VirtualFile? {
        val path = file.path
        if (generated.isEmpty() || !path.startsWith("$root/", ignoreCase = true)) return null
        val state = generated.values.firstOrNull { path.startsWith(it.output + "/", ignoreCase = true) } ?: return null
        return LocalFileSystem.getInstance().findFileByPath(state.projectPath)
    }

    fun isGenerated(file: VirtualFile): Boolean = projectOfGenerated(file) != null

    /** Under the folder of the generated files of the solution: what the helper writes there is no change of the project. */
    fun isGeneratedPath(path: String): Boolean = path.startsWith("$root/", ignoreCase = true)

    /** The folders of generated files that are on disk: the roots of the library of generated sources. */
    fun generatedRoots(): List<VirtualFile> = generated.values.mapNotNull { LocalFileSystem.getInstance().findFileByPath(it.output) }

    fun analyzedFile(path: String): AnalyzedFile? = analyzed[key(path)]

    val generatedCount: Int get() = generated.size

    private val projectOfFile = ConcurrentHashMap<String, String>()
    private val lastEdit = ConcurrentHashMap<String, Long>()

    /** A C# document has been edited: what the generators of its project made may not fit it any more until they run again. */
    fun documentChanged(file: VirtualFile) {
        if (generated.isEmpty() || isGenerated(file)) return
        val projectPath = projectOfFile.getOrPut(key(file.path)) { CompilationModel.getInstance(project).projectOf(file)?.path.orEmpty() }
        if (projectPath.isEmpty()) return
        lastEdit[key(projectPath)] = System.nanoTime()
        generated[key(projectPath)]?.let { if (!it.stale) { it.stale = true; modificationTracker.incModificationCount() } }
    }

    /** Whether [projectFile] has a C# document edited after [since] (nanoTime) or not saved: then the files just made may not fit it. */
    private fun editedSince(projectFile: VirtualFile, since: Long): Boolean {
        if ((lastEdit[key(projectFile.path)] ?: Long.MIN_VALUE) > since) return true
        val unsaved = FileDocumentManager.getInstance().unsavedDocuments.mapNotNull { FileDocumentManager.getInstance().getFile(it) }.filter { it.fileType == CSharpFileType }
        return unsaved.any { projectOfFile[key(it.path)]?.equals(projectFile.path, ignoreCase = true) == true }
    }

    val analyzersActive: Boolean get() = !RoslynLanguageServerSettings.getInstance().state.enabled

    // ---- triggers

    /** A C# file was saved: its project generates again (if it has generators or has not run yet) and the file is analyzed. */
    fun saved(file: VirtualFile) {
        val projectFile = ReadAction.compute<VirtualFile?, RuntimeException> { CompilationModel.getInstance(project).projectOf(file) } ?: return
        schedule(projectFile, generate = needsGeneration(projectFile), paths = if (analyzersActive && DotNetSettings.getInstance().runAnalyzersOnSave) listOf(file.path) else emptyList())
    }

    /** A C# file was opened: its project generates once, the file is analyzed when it has not been. */
    fun opened(file: VirtualFile) {
        if (file.fileType != CSharpFileType || isGenerated(file)) return
        val projectFile = ReadAction.compute<VirtualFile?, RuntimeException> { CompilationModel.getInstance(project).projectOf(file) } ?: return
        val generate = DotNetSettings.getInstance().runSourceGenerators && generated[key(projectFile.path)] == null
        val analyze = analyzersActive && DotNetSettings.getInstance().runAnalyzersOnSave && analyzed[key(file.path)] == null
        if (generate || analyze) schedule(projectFile, generate, if (analyze) listOf(file.path) else emptyList())
    }

    /** Project files, props, `.editorconfig` or the set of sources changed: the helper loads those projects again, they generate again. */
    fun projectFilesChanged(paths: Collection<String>) {
        if (connection == null && generated.isEmpty()) return
        executor.execute { runCatching { request("invalidate", CodeAnalysisAnswers.invalidateParams(paths), INVALIDATE_TIMEOUT_MS) } }
        for (state in generated.values) LocalFileSystem.getInstance().findFileByPath(state.projectPath)?.let { schedule(it, generate = true, paths = emptyList()) }
    }

    private fun afterBuild() {
        if (disposed) return
        for (state in generated.values) LocalFileSystem.getInstance().findFileByPath(state.projectPath)?.let { schedule(it, generate = true, paths = emptyList()) }
    }

    /** Refresh Generated Files: every C# project of the solution (or [projects]) generates now. */
    fun refreshGenerated(projects: Collection<VirtualFile>) {
        for (projectFile in projects) schedule(projectFile, generate = true, paths = emptyList(), delayMs = 0)
    }

    private fun needsGeneration(projectFile: VirtualFile): Boolean {
        if (!DotNetSettings.getInstance().runSourceGenerators) return false
        val state = generated[key(projectFile.path)] ?: return true
        // a project without generators has nothing to make again on a save; a build or a change of the project file asks anyway
        return state.run.files.isNotEmpty() || state.run.errors.isNotEmpty()
    }

    private fun unanalyzedOpenFiles(projectFile: VirtualFile): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        if (project.isDisposed) return@compute emptyList()
        FileEditorManager.getInstance(project).openFiles.filter {
            it.fileType == CSharpFileType && !isGenerated(it) && analyzed[key(it.path)] == null && CompilationModel.getInstance(project).projectOf(it) == projectFile
        }.map { it.path }
    }

    private fun schedule(projectFile: VirtualFile, generate: Boolean, paths: Collection<String>, delayMs: Int = DEBOUNCE_MS) {
        if (disposed || (!generate && paths.isEmpty())) return
        if (ApplicationManager.getApplication().isUnitTestMode && testConnection == null) return
        if (testConnection == null && !com.intellij.ide.trustedProjects.TrustedProjects.isProjectTrusted(project)) return   // Safe Mode: see connection()
        val entry = pending.computeIfAbsent(key(projectFile.path)) { Pending(projectFile) }
        if (generate) entry.generate = true
        entry.paths += paths
        alarm.cancelAllRequests()
        alarm.addRequest({ executor.execute(::drain) }, delayMs)
    }

    private fun drain() {
        for (projectKey in pending.keys.toList()) {
            val entry = pending.remove(projectKey) ?: continue
            if (disposed || !entry.projectFile.isValid) continue
            try {
                if (entry.generate && DotNetSettings.getInstance().runSourceGenerators) {
                    generate(entry.projectFile)
                    // files opened before the solution was known (projectOf was null then) are analyzed with their project
                    if (analyzersActive && DotNetSettings.getInstance().runAnalyzersOnSave) entry.paths += unanalyzedOpenFiles(entry.projectFile)
                }
                if (entry.paths.isNotEmpty() && analyzersActive) analyze(entry.projectFile, entry.paths.toList(), fixes = true)
            } catch (e: HelperException) {
                PluginLog.warn(LOG_CATEGORY, "${entry.projectFile.name}: ${e.message}")
            }
        }
    }

    // ---- requests (blocking, not on the EDT)

    private fun target(projectFile: VirtualFile): JsonObject {
        val options = ReadAction.compute<io.github.dotnetsupport.msbuild.CompilationOptions, RuntimeException> { CompilationModel.getInstance(project).options(projectFile) }
        return CodeAnalysisAnswers.target(projectFile.path, options.configuration, options.targetFramework, outputRoot())
    }

    fun generate(projectFile: VirtualFile): GeneratedRun {
        val started = System.nanoTime()
        val run = CodeAnalysisAnswers.generated(request("generate", target(projectFile), GENERATE_TIMEOUT_MS))
        val output = CodeAnalysisAnswers.outputFolder(outputRoot(), projectFile.path)
        val state = GeneratedState(projectFile.path, run, output)
        state.stale = editedSince(projectFile, started)
        val previous = generated.put(key(projectFile.path), state)
        modificationTracker.incModificationCount()
        PluginLog.info(LOG_CATEGORY, "${projectFile.name}: ${run.files.size} generated files in ${run.milliseconds} ms (helper ${run.workingSet / MB} MB)" +
            run.errors.joinToString("") { "\n  $it" })
        val roots = previous?.run?.files?.isEmpty() != run.files.isEmpty() || previous == null
        // the referenced projects have their own generators, whose types this one uses: they are made (the helper has them loaded already)
        for (reference in references(projectFile)) if (generated[key(reference.path)] == null) schedule(reference, generate = true, paths = emptyList(), delayMs = 0)
        File(output).takeIf { it.isDirectory }?.let { folder ->
            // the helper wrote the files: the VFS learns of them before the indexes and the tree look
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(folder)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
        }
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            if (roots) com.intellij.openapi.application.WriteAction.run<RuntimeException> {
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, GeneratedSourcesRootsProvider.LIBRARY_NAME, emptyList(), generatedRoots(), GeneratedSourcesRootsProvider::class.java.name)
            }
            ProjectView.getInstance(project).getProjectViewPaneById(io.github.dotnetsupport.view.SolutionViewPane.ID)?.updateFromRoot(true)
            DaemonCodeAnalyzer.getInstance(project).restart("generated files of ${projectFile.name}")
        }, ModalityState.nonModal())
        return run
    }

    fun analyze(projectFile: VirtualFile, paths: List<String>, fixes: Boolean): AnalysisRun {
        val target = target(projectFile)
        val params = CodeAnalysisAnswers.analyzeParams(target, paths, EXCLUDED_IDS, fixes)
        val run = CodeAnalysisAnswers.analysis(request("analyze", params, ANALYZE_TIMEOUT_MS))
        store(projectFile, target, paths, run)
        PluginLog.info(LOG_CATEGORY, "${projectFile.name}: ${run.diagnostics.size} analyzer diagnostics in ${run.milliseconds} ms (${run.analyzers} analyzers, helper ${run.workingSet / MB} MB)")
        return run
    }

    /** The results of [run] replace what was known of [paths] (of every file of the project when it was analyzed as a whole). */
    private fun store(projectFile: VirtualFile, target: JsonObject, paths: List<String>, run: AnalysisRun) {
        val byFile = run.diagnostics.groupBy { key(it.path) }
        if (paths.isEmpty()) analyzed.entries.removeIf { it.value.projectPath == projectFile.path } else paths.forEach { analyzed.remove(key(it)) }
        for (path in paths) if (key(path) !in byFile) analyzed[key(path)] = AnalyzedFile(projectFile.path, emptyList(), emptyList(), target)
        for ((path, diagnostics) in byFile) {
            val lines = runCatching { File(diagnostics.first().path).readLines() }.getOrNull()
            analyzed[path] = AnalyzedFile(projectFile.path, diagnostics, diagnostics.map { lines?.getOrNull(it.startLine)?.trim() }, target)
        }
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("analyzer diagnostics of ${projectFile.name}") }, ModalityState.any())
    }

    /**
     * Run Code Analysis: the analyzers of [projects] as a whole, the list in the Build tool window (as a build), the diagnostics in the
     * editors. Saves the documents first: the helper reads the files from disk.
     */
    fun runCodeAnalysis(projects: List<VirtualFile>) {
        FileDocumentManager.getInstance().saveAllDocuments()
        val buildId = Any()
        val title = "Code Analysis " + (projects.singleOrNull()?.nameWithoutExtension ?: "Solution")
        val buildView = project.service<BuildViewManager>()
        val descriptor = DefaultBuildDescriptor(buildId, title, project.basePath ?: "", System.currentTimeMillis()).apply { isActivateToolWindowWhenAdded = true }
        buildView.onEvent(buildId, BuildViewEvents.started(descriptor, "running analyzers..."))
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                var errors = 0
                var warnings = 0
                var infos = 0
                for (projectFile in projects) {
                    indicator.checkCanceled()
                    indicator.text = "Analyzing ${projectFile.nameWithoutExtension}..."
                    val run = try {
                        analyze(projectFile, emptyList(), fixes = true)
                    } catch (e: HelperException) {
                        buildView.onEvent(buildId, BuildViewEvents.message(buildId, MessageEvent.Kind.ERROR, GROUP, "${projectFile.nameWithoutExtension}: ${e.message}", e.message.orEmpty(), null))
                        continue
                    }
                    val shown = run.diagnostics.filter { it.severity != AnalyzerSeverity.INFO || DotNetSettings.getInstance().showAnalyzerSuggestions }
                    for (diagnostic in shown) {
                        val kind = when (diagnostic.severity) {
                            AnalyzerSeverity.ERROR -> MessageEvent.Kind.ERROR.also { errors++ }
                            AnalyzerSeverity.WARNING -> MessageEvent.Kind.WARNING.also { warnings++ }
                            AnalyzerSeverity.INFO -> MessageEvent.Kind.INFO.also { infos++ }
                        }
                        val text = "${diagnostic.id}: ${diagnostic.message}"
                        buildView.onEvent(buildId, BuildViewEvents.message(buildId, kind, GROUP, text, text + (diagnostic.helpLink?.let { "\n$it" } ?: ""),
                            FilePosition(File(diagnostic.path), diagnostic.startLine, diagnostic.startColumn)))
                    }
                }
                buildView.onEvent(buildId, BuildViewEvents.finished(buildId, "$errors errors, $warnings warnings, $infos suggestions", SuccessResultImpl()))
            }

            override fun onThrowable(error: Throwable) {
                buildView.onEvent(buildId, BuildViewEvents.finished(buildId, error.message ?: "failed", com.intellij.build.events.impl.FailureResultImpl(error)))
            }

            override fun onCancel() {
                buildView.onEvent(buildId, BuildViewEvents.finished(buildId, "cancelled", com.intellij.build.events.impl.SkippedResultImpl()))
            }
        })
    }

    /**
     * Applies the code fix [title] of [diagnostic] (on the EDT): the documents are saved, the helper works out the edits from the files on
     * disk, and they are applied as one command (one Ctrl+Z) if nothing has been typed meanwhile.
     */
    fun applyFix(file: AnalyzedFile, diagnostic: AnalyzerDiagnostic, title: String) {
        FileDocumentManager.getInstance().saveAllDocuments()
        val stamps = FileDocumentManager.getInstance().unsavedDocuments.isEmpty()
        if (!stamps) PluginLog.warn(LOG_CATEGORY, "fix $title: some documents could not be saved")
        val documents = FileEditorManager.getInstance(project).openFiles.mapNotNull { FileDocumentManager.getInstance().getDocument(it) }.associateWith { it.modificationStamp }
        ProgressManager.getInstance().run(object : Task.Modal(project, "Applying '$title'", true) {
            private var result: FixResult? = null

            override fun run(indicator: ProgressIndicator) {
                result = CodeAnalysisAnswers.fix(request("fix", CodeAnalysisAnswers.fixParams(file.target, diagnostic, title), FIX_TIMEOUT_MS))
            }

            override fun onSuccess() {
                val fix = result ?: return
                if (documents.any { (document, stamp) -> document.modificationStamp != stamp }) {
                    DotNetCli.notifyError(project, "Code fix", "The file has changed while the fix was worked out: try again")
                    return
                }
                apply(fix)
            }

            override fun onThrowable(error: Throwable) {
                DotNetCli.notifyError(project, "Code fix '$title'", error.message.orEmpty())
            }
        })
    }

    private fun apply(fix: FixResult) {
        val changed = ArrayList<VirtualFile>()
        WriteCommandAction.runWriteCommandAction(project, fix.title, null, {
            for ((path, edits) in fix.edits.groupBy { it.path }) {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: continue
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: continue
                val located = edits.mapNotNull { edit ->
                    val start = TextPositions.offset(document.immutableCharSequence, edit.startLine, edit.startColumn) ?: return@mapNotNull null
                    val end = TextPositions.offset(document.immutableCharSequence, edit.endLine, edit.endColumn) ?: return@mapNotNull null
                    Triple(start, maxOf(start, end), edit.text.replace("\r\n", "\n"))
                }.sortedByDescending { it.first }
                for ((start, end, text) in located) document.replaceString(start, end, text)
                changed += virtualFile
            }
            for ((path, text) in fix.created) {
                val target = File(path)
                val parent = VfsUtil.createDirectoryIfMissing(target.parent) ?: continue
                val created = parent.findChild(target.name) ?: parent.createChildData(this, target.name)
                VfsUtil.saveText(created, text.replace("\r\n", "\n"))
                changed += created
            }
        })
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        changed.forEach { FileDocumentManager.getInstance().getDocument(it)?.let(FileDocumentManager.getInstance()::saveDocument) }
        // saved: the listener of saves analyzes them again
    }

    private fun request(method: String, params: JsonObject, timeoutMs: Long): com.google.gson.JsonElement {
        inFlight.incrementAndGet()
        try {
            lastUse = System.currentTimeMillis()
            return connection().request(method, params, timeoutMs)
        } finally {
            lastUse = System.currentTimeMillis()
            inFlight.decrementAndGet()
            scheduleIdleCheck()
        }
    }

    @Synchronized
    private fun connection(): HelperConnection {
        testConnection?.let { return it }
        if (disposed || project.isDisposed) throw HelperException("the project is closed")
        connection?.let { return it }
        if (ApplicationManager.getApplication().isUnitTestMode) throw HelperException("CodeAnalysisHelper is not started in tests")
        // the helper runs code of the project: MSBuild targets (design-time build), analyzer and generator dlls of its packages — never for
        // a project the user has not trusted (Safe Mode), as MsBuildEvaluation and CompilationModel
        if (!com.intellij.ide.trustedProjects.TrustedProjects.isProjectTrusted(project)) throw HelperException("the project is not trusted: analyzers and source generators do not run in Safe Mode")
        return HelperConnection.of(HELPER, LOG_CATEGORY, ::workDirectory).also { connection = it; Disposer.register(this, it) }
    }

    /** Where `dotnet` resolves the SDK the way a build of the solution does: the folder of the solution. */
    private fun workDirectory(): String = SolutionService.getInstance(project).solutionFiles().firstOrNull()?.parent?.path ?: project.basePath ?: DotNetHelper.root().path

    private fun scheduleIdleCheck() {
        if (disposed) return
        idleAlarm.cancelAllRequests()
        idleAlarm.addRequest(::stopIfIdle, DotNetSettings.getInstance().codeAnalysisIdleMinutes * 60_000L + 1_000)
    }

    /** No request for the idle time: the helper ends (its memory goes back), the next request starts it again. */
    @Synchronized
    private fun stopIfIdle() {
        val current = connection ?: return
        val idle = System.currentTimeMillis() - lastUse
        if (inFlight.get() > 0 || idle < DotNetSettings.getInstance().codeAnalysisIdleMinutes * 60_000L) return scheduleIdleCheck()
        connection = null
        Disposer.dispose(current)
        PluginLog.info(LOG_CATEGORY, "CodeAnalysisHelper stopped after ${idle / 60_000} min without requests")
    }

    override fun dispose() {
        disposed = true
        pending.clear()
    }

    @TestOnly
    fun setConnectionForTests(connection: HelperConnection?) {
        testConnection = connection
    }

    @TestOnly
    fun putGeneratedForTests(projectPath: String, run: GeneratedRun?) {
        if (run == null) generated.remove(key(projectPath)) else generated[key(projectPath)] = GeneratedState(projectPath, run, CodeAnalysisAnswers.outputFolder(outputRoot(), projectPath))
    }

    @TestOnly
    fun putAnalyzedForTests(path: String, file: AnalyzedFile?) {
        if (file == null) analyzed.remove(key(path)) else analyzed[key(path)] = file
    }

    companion object {
        const val LOG_CATEGORY = "codeanalysis"
        const val GROUP = "Roslyn analyzers"
        val HELPER = DotNetHelper("codeanalysis", "CodeAnalysisHelper", "HelperFramework", listOf("Program.cs", "Protocol.cs"), perSdk = true)

        /** IDE0005 is the plugin's own gray of unused `using` directives (CS8019): not twice. */
        val EXCLUDED_IDS = listOf("IDE0005")

        private const val DEBOUNCE_MS = 800
        private const val MB = 1024 * 1024
        private const val GENERATE_TIMEOUT_MS = 5 * 60_000L
        private const val ANALYZE_TIMEOUT_MS = 10 * 60_000L
        private const val FIX_TIMEOUT_MS = 2 * 60_000L
        private const val INVALIDATE_TIMEOUT_MS = 20_000L

        fun getInstance(project: Project): CodeAnalysisService = project.service()

        private fun key(path: String): String = CodeAnalysisAnswers.normalize(path).lowercase()
    }
}
