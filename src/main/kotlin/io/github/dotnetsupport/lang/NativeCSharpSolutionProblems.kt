package io.github.dotnetsupport.lang

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.analysis.problemsView.ProblemsProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.ex.temp.TempFileSystem
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.build.DotNetBuildListener
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.codeanalysis.AnalyzerDiagnostic
import io.github.dotnetsupport.codeanalysis.AnalyzerSeverity
import io.github.dotnetsupport.codeanalysis.CodeAnalysisService
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon

private val LOG = logger<NativeCSharpSolutionProblems>()

/**
 * The «Analysis» scopes of Settings | .NET | Language Server without the server ([AnalysisScopes]): the errors and warnings of the solution
 * in the Problems tool window (its Project Errors tab, through its [ProblemsCollector]), as `RoslynSolutionProblems` of the server module
 * fills it from `workspace/diagnostic` — the same rows, so a switch between the two changes nothing on screen. Only one of them fills the
 * tab: this one when «Errors and warnings» is Built-in (or the server is off), the server's otherwise.
 *
 * Compiler diagnostics: with `fullSolution` every C# file of the solution's projects is checked in the background by the checks the editor
 * annotator runs ([NativeCSharpDiagnostics], [NativeCSharpUsingChecks], [NativeCSharpSemanticDiagnostics]) — one file per non-blocking read
 * action, cancelled by a write action and taken again, so typing never waits; a changed file is checked again after a pause, the whole
 * solution after a build, when the compilation options change and when the IDE leaves dumb mode. With `openFiles` the tab lists the open
 * documents, as the server's `textDocument/diagnostic` did; with `none` nothing. Analyzer diagnostics (the helper, [CodeAnalysisService]):
 * with `fullSolution` the errors and warnings of the whole projects it analyzed, in the same tab; the editor's view of them is the annotator's.
 */
@Service(Service.Level.PROJECT)
class NativeCSharpSolutionProblems(override val project: Project) : Disposable, ProblemsProvider {
    /** One diagnostic of a file, as the Problems view shows it (the same presentation as the server's rows). */
    class Problem(override val provider: ProblemsProvider, override val file: VirtualFile, override val line: Int, override val column: Int, override val text: String, private val code: String?, private val error: Boolean) : FileProblem {
        override val description: String? get() = code
        override val group: String? get() = code
        override val icon: Icon get() = if (error) AllIcons.General.Error else AllIcons.General.Warning

        fun sameAs(other: Problem) = file == other.file && line == other.line && column == other.column && text == other.text && code == other.code && error == other.error
    }

    /** key -> the problems shown for it: the path of a file for the compiler, the path + [ANALYZERS] for the analyzers. */
    private val shown = HashMap<String, List<Problem>>()
    /** Analyzer key -> the project it was analyzed in: what to drop when that project is analyzed again. */
    private val analyzerProjects = HashMap<String, String>()
    /** Files waiting for a check: [urgent] (edited, opened) before [queue] (a full pass); both under the lock of [queue]. */
    private val queue = LinkedHashSet<VirtualFile>()
    private val urgent = LinkedHashSet<VirtualFile>()
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("C# solution problems", 1)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val generation = AtomicInteger()
    @Volatile private var fullPassPending = false
    @Volatile private var looseFilesForTests = false

    /** The last full pass: files checked and milliseconds, for the log and the measurements. */
    @Volatile var lastPass: Pair<Int, Long>? = null
        private set

    init {
        val bus = project.messageBus.connect(this)
        bus.subscribe(DotNetBuildListener.TOPIC, DotNetBuildListener { _, _ -> if (compilerActive()) requestFullPass() })
        bus.subscribe(CompilationModel.CHANGED, CompilationModel.Listener { if (compilerActive()) requestFullPass() })
        bus.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun exitDumbMode() { if (compilerActive()) requestFullPass() }
        })
    }

    override fun dispose() {
        generation.incrementAndGet()
        synchronized(queue) { queue.clear(); urgent.clear() }
    }

    /** The native pass fills the tab: the feature is Built-in (or the server is off) and the scope is not `none`. */
    fun compilerActive(): Boolean = CSharpFeatures.native(CSharpFeature.DIAGNOSTICS) && AnalysisScopes.compiler() != AnalysisScopes.NONE
    fun analyzersActive(): Boolean = AnalysisScopes.analyzer() == AnalysisScopes.FULL_SOLUTION && CodeAnalysisService.getInstance(project).analyzersActive

    /** The page was applied, the project opened: whatever the scopes say now. */
    fun settingsChanged() {
        if (project.isDisposed) return
        if (compilerActive()) requestFullPass() else clear(analyzers = false)
        if (analyzersActive()) {
            val service = CodeAnalysisService.getInstance(project)
            executor.execute { if (!project.isDisposed) for (path in service.analyzedProjects()) publishAnalyzers(path) }
            service.analyzeSolution()
        } else clear(analyzers = true)
    }

    /** Every file the scope covers is checked again; what the tab shows for the others goes. */
    fun requestFullPass() {
        fullPassPending = true
        schedule(FULL_PASS_DELAY_MS)
    }

    /** A C# file changed (typing, a save, a change on disk) or was opened: checked again after a pause. */
    fun fileChanged(file: VirtualFile) {
        if (file.fileType != CSharpFileType || !compilerActive()) return
        // ahead of a full pass in progress: the row of the file being edited follows the edit, the rest of the solution waits
        synchronized(queue) { queue.remove(file); urgent += file }
        schedule(EDIT_DELAY_MS)
    }

    /** A file is gone, or closed under `openFiles`: its rows go. */
    fun fileGone(path: String) {
        synchronized(queue) { queue.removeIf { it.path == path }; urgent.removeIf { it.path == path } }
        publish(path, emptyList())
    }

    fun fileClosed(file: VirtualFile) {
        if (AnalysisScopes.compiler() == AnalysisScopes.OPEN_FILES) fileGone(file.path)
    }

    /** The helper analyzed [projectPath] (a whole project or some of its files): its errors and warnings replace the project's rows. */
    fun analyzersChanged(projectPath: String) {
        if (!analyzersActive()) return
        executor.execute { if (!project.isDisposed) publishAnalyzers(projectPath) }
    }

    private fun schedule(delayMs: Int) {
        if (project.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ executor.execute(::drain) }, delayMs)
    }

    // ---- the pass (one background thread)

    private fun drain() {
        if (project.isDisposed) return
        val myGeneration = generation.get()
        val fullPass = fullPassPending
        if (fullPass) {
            fullPassPending = false
            val covered = read { coveredFiles() } ?: return
            synchronized(queue) { queue.addAll(covered.filter { it !in urgent }) }
            // files the scope no longer covers (closed, deleted, left out of the solution): their rows go
            val keys = covered.mapTo(HashSet()) { it.path }
            synchronized(shown) { shown.keys.filter { !it.endsWith(ANALYZERS) && it !in keys } }.forEach { publish(it, emptyList()) }
        }
        val started = System.nanoTime()
        var checked = 0
        while (generation.get() == myGeneration && !project.isDisposed) {
            val file = synchronized(queue) { urgent.firstOrNull()?.also(urgent::remove) ?: queue.firstOrNull()?.also(queue::remove) } ?: break
            if (!file.isValid) { publish(file.path, emptyList()); continue }
            if (!compilerActive()) { synchronized(queue) { queue.clear(); urgent.clear() }; break }
            val problems = read { if (covers(file)) check(file) else emptyList() } ?: break
            publish(file.path, problems)
            checked++
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        if (fullPass) {
            lastPass = checked to millis
            PluginLog.info(LOG_CATEGORY, "${AnalysisScopes.compiler()}: $checked files checked in $millis ms, ${synchronized(shown) { shown.values.sumOf { it.size } }} problems shown")
        } else if (checked > 0) LOG.info("$checked files checked in $millis ms")
    }

    /** A non-blocking read action: cancelled by a write action and run again, waits for smart mode; null when the project is gone. */
    private fun <T : Any> read(action: () -> T): T? = try {
        ReadAction.nonBlocking<T> { action() }.inSmartMode(project).expireWith(this).executeSynchronously()
    } catch (e: ProcessCanceledException) {
        null
    }

    /** The files the compiler scope covers now: every compiled C# file of the solution's projects, or the open ones. */
    private fun coveredFiles(): List<VirtualFile> = when (AnalysisScopes.compiler()) {
        AnalysisScopes.FULL_SOLUTION -> FileTypeIndex.getFiles(CSharpFileType, GlobalSearchScope.projectScope(project)).filter(::inSolution)
        AnalysisScopes.OPEN_FILES -> FileEditorManager.getInstance(project).openFiles.filter { it.fileType == CSharpFileType }
        else -> emptyList()
    }

    private fun covers(file: VirtualFile): Boolean = when (AnalysisScopes.compiler()) {
        AnalysisScopes.FULL_SOLUTION -> inSolution(file)
        AnalysisScopes.OPEN_FILES -> file in FileEditorManager.getInstance(project).openFiles
        else -> false
    }

    /** Compiled by a project of a solution of the IDE project (a loose `.csproj` counts when there is no solution), as the server's workspace. */
    private fun inSolution(file: VirtualFile): Boolean {
        if (looseFilesForTests) return true
        val service = project.getServiceIfCreated(CodeAnalysisService::class.java)
        if (service?.isGenerated(file) == true) return false
        val projectFile = CompilationModel.getInstance(project).compiledIn(file) ?: return false
        val solutions = SolutionService.getInstance(project).solutionFiles()
        return solutions.isEmpty() || solutions.any { solution -> SolutionService.getInstance(project).solution(solution).allProjects.any { it.resolveFile(solution) == projectFile } }
    }

    /** The checks of the editor annotator on [file]'s tree, errors and warnings only: the gray of unused `using` directives is for the editor. */
    private fun check(file: VirtualFile): List<Problem> {
        val psi = PsiManager.getInstance(project).findFile(file) as? CSharpFile ?: return emptyList()
        if (!NativeCSharpDiagnostics.serves(psi)) return emptyList()
        val text = psi.viewProvider.contents
        val found = ArrayList<Problem>()
        fun add(offset: Int, message: String, code: String, error: Boolean) {
            val at = StringUtil.offsetToLineColumn(text, offset.coerceIn(0, text.length))
            found += Problem(this, file, at.line, at.column, message.trim(), code, error)
        }
        for (d in NativeCSharpDiagnostics.of(psi)) add(d.start, d.message, d.id, !d.isWarning)
        for (p in NativeCSharpUsingChecks.of(psi)) add(p.range.startOffset, p.error.message, p.error.code, true)
        for (p in NativeCSharpSemanticDiagnostics.of(psi)) if (!p.unnecessary) add(p.range.startOffset, p.message, p.code, p.isError)
        return found
    }

    private fun publishAnalyzers(projectPath: String) {
        val service = CodeAnalysisService.getInstance(project)
        val keys = HashSet<String>()
        for (analyzed in service.analyzedOf(projectPath)) {
            val path = analyzed.path ?: continue
            val file = LocalFileSystem.getInstance().findFileByPath(path) ?: TempFileSystem.getInstance().takeIf { ApplicationManager.getApplication().isUnitTestMode }?.findFileByPath(path) ?: continue
            val key = file.path + ANALYZERS
            keys += key
            synchronized(shown) { analyzerProjects[key] = projectPath }
            publish(key, analyzed.diagnostics.mapNotNull { toProblem(file, it) })
        }
        val stale = synchronized(shown) { analyzerProjects.filter { (key, p) -> p == projectPath && key !in keys }.keys.toList() }
        stale.forEach { publish(it, emptyList()) }
    }

    /** Errors and warnings; an Info (a suggestion of Rider) is for the editor, as the server's hints were. */
    fun toProblem(file: VirtualFile, d: AnalyzerDiagnostic): Problem? {
        val error = when (d.severity) {
            AnalyzerSeverity.ERROR -> true
            AnalyzerSeverity.WARNING -> false
            AnalyzerSeverity.INFO -> return null
        }
        return Problem(this, file, d.startLine, d.startColumn, d.message.trim(), d.id, error)
    }

    /** The view learns what appeared and what disappeared; the rest stays as it is (the collector knows a problem by its object). */
    internal fun publish(key: String, problems: List<Problem>): Boolean {
        val old: List<Problem>
        val kept: List<Problem>
        synchronized(shown) {
            old = shown[key].orEmpty()
            kept = problems.map { p -> old.firstOrNull { it.sameAs(p) } ?: p }
            if (kept.isEmpty()) { shown.remove(key); analyzerProjects.remove(key) } else shown[key] = kept
        }
        val gone = old.filter { o -> kept.none { it === o } }
        val new = kept.filter { p -> old.none { it === p } }
        if (gone.isEmpty() && new.isEmpty()) return false
        val collector = ProblemsCollector.getInstance(project)
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            gone.forEach(collector::problemDisappeared)
            new.forEach(collector::problemAppeared)
        })
        return true
    }

    /** Drops the rows of the analyzers or of the compiler, e.g. when a scope went to `none`. */
    private fun clear(analyzers: Boolean) {
        if (!analyzers) synchronized(queue) { queue.clear(); urgent.clear() }
        val keys = synchronized(shown) { shown.keys.filter { it.endsWith(ANALYZERS) == analyzers } }
        keys.forEach { publish(it, emptyList()) }
    }

    /** Runs what is scheduled now and waits for it: the pass of the test is synchronous. */
    @TestOnly
    fun waitForTests() {
        alarm.drainRequestsInTest()
        executor.submit(Runnable {}).get()
    }

    /** Files of the light test project are in no `.csproj`: they count as the solution's until [disposable] goes. */
    @TestOnly
    fun includeLooseFilesForTests(disposable: Disposable) {
        looseFilesForTests = true
        Disposer.register(disposable) { looseFilesForTests = false }
    }

    /** After the project is opened: the scopes as they are. */
    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) = getInstance(project).settingsChanged()
    }

    /** Typing in a C# file: the file is checked again after a pause. */
    class Documents : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
            if (file.fileType != CSharpFileType) return
            for (project in ProjectManager.getInstance().openProjects) if (!project.isDisposed) project.getServiceIfCreated(NativeCSharpSolutionProblems::class.java)?.fileChanged(file)
        }
    }

    /** C# files that appear, go, move or change on disk. */
    class Files(private val project: Project) : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            val service = project.getServiceIfCreated(NativeCSharpSolutionProblems::class.java) ?: return
            for (event in events) {
                if (!event.path.endsWith(".cs", ignoreCase = true)) continue
                when (event) {
                    is VFileDeleteEvent -> service.fileGone(event.path)
                    is VFileMoveEvent -> { service.fileGone(event.oldPath); service.fileChanged(event.file) }
                    is VFilePropertyChangeEvent -> if (event.isRename) { service.fileGone(event.oldPath); service.fileChanged(event.file) }
                    else -> event.file?.let(service::fileChanged)
                }
            }
        }
    }

    /** Open and closed editors, for the `openFiles` scope. */
    class Editors(private val project: Project) : FileEditorManagerListener {
        override fun fileOpened(source: FileEditorManager, file: VirtualFile) { project.getServiceIfCreated(NativeCSharpSolutionProblems::class.java)?.fileChanged(file) }
        override fun fileClosed(source: FileEditorManager, file: VirtualFile) { project.getServiceIfCreated(NativeCSharpSolutionProblems::class.java)?.fileClosed(file) }
    }

    companion object {
        const val LOG_CATEGORY = "solution problems"
        private const val ANALYZERS = "#analyzers"
        private const val EDIT_DELAY_MS = 1_000
        private const val FULL_PASS_DELAY_MS = 500

        fun getInstance(project: Project): NativeCSharpSolutionProblems = project.service()
    }
}
