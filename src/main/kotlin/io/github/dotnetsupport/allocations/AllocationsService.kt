package io.github.dotnetsupport.allocations

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetHelper
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.lang.CSharpDeclarations
import io.github.dotnetsupport.lang.DeclarationKind
import io.github.dotnetsupport.monitor.MonitorTarget
import io.github.dotnetsupport.monitor.ProcessSampler
import io.github.dotnetsupport.monitor.RunningDotNetProcesses
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * What the lines of the sources allocate, written next to them while the program runs: the bytes and the objects a second, the
 * types, the share — measured, where the Heap Allocations Viewer of Rider marks what may allocate and cannot tell a line that runs
 * once from one that runs a million times. A program started from the IDE is listened to by the watcher (allocwatch), which the
 * plugin builds on this machine; its report comes once a second.
 */
@Service(Service.Level.PROJECT)
class AllocationsService(private val project: Project) : Disposable {
    /** What is drawn for a line: the numbers of the line, or the total of a method at its declaration. */
    class Shown(@Volatile var line: AllocationLine?, @Volatile var total: Pair<Long, Double>?)

    private val caseSensitive = SystemInfo.isFileSystemCaseSensitive
    private val byFile = ConcurrentHashMap<String, List<AllocationLine>>()
    private val highlighters = ConcurrentHashMap<String, MutableMap<String, RangeHighlighter>>()
    @Volatile private var watcher: OSProcessHandler? = null
    @Volatile private var target: MonitorTarget? = null
    @Volatile var last: AllocationMessage.Snapshot? = null
        private set

    /** The file of a path the watcher names; another one in the tests, whose files are not on the disk. */
    @Volatile var resolver: (String) -> VirtualFile? = { path -> com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) }

    var isEnabled: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(ENABLED_KEY, false)
        set(value) {
            PropertiesComponent.getInstance(project).setValue(ENABLED_KEY, value, false)
            PluginLog.info(LOG_CATEGORY, "allocations in the editor are switched ${if (value) "on" else "off"}")
            if (!value) return stop()
            // the one who has switched it on is told what has come of it; a program started later is listened to in silence
            val running = RunningDotNetProcesses.getInstance(project).targets().firstOrNull()
            if (running != null) attach(running, announce = true)
            else DotNetCli.notifyInfo(project, TITLE, "No .NET program is running. Start one with Run or Debug: what its lines allocate is written at their ends.")
        }

    val isWatching: Boolean get() = watcher?.isProcessTerminated == false

    /** A program of a run configuration has started (or the last of them has ended). */
    fun processesChanged(started: MonitorTarget?) {
        if (!isEnabled) return
        if (started != null) attach(started) else if (target != null && RunningDotNetProcesses.getInstance(project).targets().none { it == target }) stop()
    }

    fun attach(target: MonitorTarget, announce: Boolean = false) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        stop()
        this.target = target
        PluginLog.info(LOG_CATEGORY, "the program to listen to is $target")
        ApplicationManager.getApplication().executeOnPooledThread {
            val dll = HELPER.ensureBuilt()
            if (dll == null) {
                PluginLog.error(LOG_CATEGORY, "the watcher could not be built: ${HELPER.failure.orEmpty().lines().firstOrNull().orEmpty()}")
                DotNetCli.notifyError(project, TITLE, "The watcher of allocations could not be built. Its first build needs the NuGet feed.<br>" +
                    StringUtil.escapeXmlEntities(HELPER.failure.orEmpty().takeLast(400)))
                return@executeOnPooledThread
            }
            val candidates = candidates(target)
            if (this.target != target || project.isDisposed) {
                PluginLog.info(LOG_CATEGORY, "$target is not listened to, another one has been chosen since")
                return@executeOnPooledThread
            }
            if (candidates == null) {
                // a run that has ended before its program was seen is no failure
                if (ProcessSampler.tree(target.pid).isEmpty()) PluginLog.info(LOG_CATEGORY, "$target has ended before it could be listened to")
                else failed("The program of $target was not found among the processes of the run.")
                return@executeOnPooledThread
            }
            val root = project.guessProjectDir()?.path
            if (root == null) {
                failed("The folder of the project is not known.")
                return@executeOnPooledThread
            }
            // `commandLine` throws when dotnet has gone since the build of the watcher
            val handler = runCatching {
                val command = DotNetCli.commandLine(root, dll.path, "--pid", candidates.joinToString(","), "--root", root, "--window", WINDOW_SECONDS.toString())
                PluginLog.info(LOG_CATEGORY, "starting the watcher: ${command.commandLineString}")
                OSProcessHandler(command)
            }.getOrElse {
                failed("The watcher could not be started: ${PluginLog.describe(it)}")
                return@executeOnPooledThread
            }
            handler.addProcessListener(Reader(handler, target, announce))
            watcher = handler
            handler.startNotify()
        }
    }

    private fun failed(reason: String) {
        PluginLog.error(LOG_CATEGORY, reason)
        if (!project.isDisposed) DotNetCli.notifyError(project, TITLE, StringUtil.escapeXmlEntities(reason))
    }

    /** The program is started by its launcher some seconds after the run has begun: the tree is looked at until it is there. */
    private fun candidates(target: MonitorTarget): List<Long>? {
        if (!target.withChildren) return listOf(target.pid)
        val deadline = System.currentTimeMillis() + FIND_TIMEOUT_MS
        val commandLines = HashMap<Long, String>()
        while (System.currentTimeMillis() < deadline && this.target == target && !project.isDisposed) {
            val tree = ProcessSampler.tree(target.pid)
            if (tree.isEmpty()) return null
            val found = AllocationTargets.order(described(tree, commandLines))
            if (found.isNotEmpty()) return found.also { PluginLog.info(LOG_CATEGORY, "of ${tree.size} processes of the run, the program is among $it") }
            Thread.sleep(FIND_PERIOD_MS)
        }
        return null
    }

    /**
     * Java tells no command line of a process on Windows, and without it the launcher (`dotnet run`) is not told from the program:
     * there the list of processes of the platform is asked, once for every process that has appeared in the tree.
     */
    private fun described(tree: List<ProcessHandle>, commandLines: MutableMap<Long, String>): List<AllocationTargets.Candidate> {
        if (tree.any { !it.info().commandLine().isPresent && it.pid() !in commandLines }) {
            val listed = runCatching { OSProcessUtil.getProcessList().associate { it.pid.toLong() to it.commandLine.orEmpty() } }
                .getOrElse { PluginLog.warn(LOG_CATEGORY, "the list of processes is not available", it); emptyMap() }
            for (handle in tree) commandLines[handle.pid()] = listed[handle.pid()].orEmpty()
        }
        return tree.map { handle ->
            val info = handle.info()
            val fromJava = info.commandLine().orElse(info.command().orElse(""))
            AllocationTargets.Candidate(handle.pid(), AllocationTargets.commandLine(fromJava, commandLines[handle.pid()]), info.startInstant().map { it.toEpochMilli() }.orElse(0))
        }
    }

    private inner class Reader(private val handler: OSProcessHandler, private val target: MonitorTarget, private val announce: Boolean) : ProcessListener {
        private val pending = StringBuilder()
        private val errors = StringBuilder()
        @Volatile private var listening = false

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            if (outputType == ProcessOutputTypes.STDERR && errors.length < ERRORS_KEPT) errors.append(event.text)
            if (outputType != ProcessOutputTypes.STDOUT) return
            pending.append(event.text)
            while (true) {
                val end = pending.indexOf("\n")
                if (end < 0) break
                val line = pending.substring(0, end).trim()
                pending.delete(0, end + 1)
                when (val message = AllocationReports.parse(line)) {
                    is AllocationMessage.Snapshot -> ApplicationManager.getApplication().invokeLater({ if (watcher === handler) apply(message) }, ModalityState.any())
                    is AllocationMessage.Stopped -> {
                        PluginLog.info(LOG_CATEGORY, "the watcher has stopped: ${message.reason}")
                        // it has never listened: the reason is why nothing is written in the editor
                        if (!listening && watcher === handler) failed("The watcher could not listen to $target: ${message.reason}")
                    }
                    is AllocationMessage.Started -> {
                        listening = true
                        PluginLog.info(LOG_CATEGORY, "the watcher listens to the process ${message.pid}")
                        if (announce && !project.isDisposed) DotNetCli.notifyInfo(project, TITLE, "Listening to $target. The numbers come in a few seconds, at the lines of the files that are open.")
                    }
                    null -> Unit
                }
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            val tail = errors.toString().trim().lines().takeLast(5).joinToString("\n")
            if (event.exitCode != 0 || tail.isNotBlank()) PluginLog.warn(LOG_CATEGORY, "the watcher has exited with code ${event.exitCode}" + if (tail.isNotBlank()) ", it said:\n$tail" else "")
            else PluginLog.info(LOG_CATEGORY, "the watcher has exited")
            if (watcher === handler) {
                watcher = null
                if (!listening && event.exitCode != 0 && errors.isNotBlank()) failed("The watcher has failed: " + errors.toString().trim().takeLast(400))
                ApplicationManager.getApplication().invokeLater({ clear() }, ModalityState.any())
            }
        }
    }

    /** The watcher is asked to leave by the end of its input; the program it has listened to runs on. */
    fun stop() {
        target = null
        val handler = watcher
        watcher = null
        if (handler != null) {
            runCatching { handler.processInput?.close() }
            ApplicationManager.getApplication().executeOnPooledThread {
                if (!handler.waitFor(STOP_TIMEOUT_MS)) handler.destroyProcess()
            }
        }
        // what was written stays no longer than the watcher that has measured it
        ApplicationManager.getApplication().invokeLater({ clear() }, ModalityState.any())
    }

    /** On the UI thread: the numbers of a report go to the lines of the documents that are open. */
    fun apply(snapshot: AllocationMessage.Snapshot) {
        if (project.isDisposed) return
        last = snapshot
        val grouped = snapshot.lines.groupBy { AllocationTargets.key(it.file, caseSensitive) }
        val gone = byFile.keys - grouped.keys
        byFile.clear()
        byFile.putAll(grouped)
        for (file in gone) remove(file)
        for ((file, lines) in grouped) {
            val virtualFile = resolver(lines.first().file.replace('\\', '/')) ?: continue
            val document = FileDocumentManager.getInstance().getCachedDocument(virtualFile) ?: continue
            show(file, document, lines)
        }
        repaint()
    }

    private fun show(file: String, document: Document, lines: List<AllocationLine>) {
        val markup = DocumentMarkupModel.forDocument(document, project, true)
        val shown = highlighters.getOrPut(file) { HashMap() }
        val wanted = HashMap<String, Shown>()
        for (line in lines) if (line.line in 1..document.lineCount) wanted["line:${line.line}"] = Shown(line, null)
        for ((declaration, total) in AllocationTotals.of(document.immutableCharSequence, lines)) wanted["total:$declaration"] = Shown(null, total)

        for (key in shown.keys - wanted.keys) shown.remove(key)?.let { markup.removeHighlighter(it) }
        for ((key, value) in wanted) {
            val existing = shown[key]?.takeIf { it.isValid }
            if (existing != null) {
                existing.getUserData(SHOWN)?.let { it.line = value.line; it.total = value.total }
                continue
            }
            // by the line of the build: where that line is after what has been typed since is what the highlighter keeps track of
            val number = key.substringAfter(':').toInt() - 1
            if (number !in 0 until document.lineCount) continue
            val highlighter = markup.addLineHighlighter(number, HighlighterLayer.ADDITIONAL_SYNTAX, null)
            highlighter.putUserData(SHOWN, value)
            if (value.line != null) highlighter.lineMarkerRenderer = Stripe(value)
            shown[key] = highlighter
        }
    }

    private fun remove(file: String) {
        val shown = highlighters.remove(file) ?: return
        for (highlighter in shown.values) if (highlighter.isValid) highlighter.dispose()
    }

    private fun clear() {
        last = null
        byFile.clear()
        for (file in highlighters.keys.toList()) remove(file)
        repaint()
    }

    private fun repaint() {
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project == project) {
                editor.contentComponent.repaint()
                (editor as? com.intellij.openapi.editor.ex.EditorEx)?.gutterComponentEx?.repaint()
            }
        }
    }

    /** What is drawn at the line [line] (from 0) of the file, as the document is now. */
    fun shownAt(file: VirtualFile, line: Int): Shown? {
        val shown = highlighters[AllocationTargets.key(file.path, caseSensitive)] ?: return null
        for (highlighter in shown.values) {
            if (!highlighter.isValid) continue
            if (highlighter.document.getLineNumber(highlighter.startOffset) == line) return highlighter.getUserData(SHOWN)
        }
        return null
    }

    override fun dispose() {
        watcher?.destroyProcess()
    }

    /** The stripe in the gutter: the more of the allocations is of the line, the denser. */
    private class Stripe(private val shown: Shown) : ActiveGutterRenderer {
        override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
            val line = shown.line ?: return
            val alpha = (70 + 185 * (line.share / 50.0).coerceIn(0.0, 1.0)).toInt()
            g.color = Color(HOT.red, HOT.green, HOT.blue, alpha)
            g.fillRect(r.x, r.y, JBUI.scale(3), r.height)
        }

        override fun getTooltipText(): String? = shown.line?.let(AllocationText::tooltip)
        override fun canDoAction(editor: Editor, e: MouseEvent): Boolean = false
        override fun doAction(editor: Editor, e: MouseEvent) = Unit
    }

    companion object {
        /** The category of the journal of the plugin for the watcher of allocations. */
        const val LOG_CATEGORY = "allocations"
        val HELPER = DotNetHelper("allocwatch", "AllocWatch", "HelperFramework")
        val SHOWN: Key<Shown> = Key.create("dotnet.allocations.shown")
        val HOT: JBColor = JBColor(Color(0xE6, 0x6D, 0x17), Color(0xC7, 0x7D, 0x55))
        const val ENABLED_KEY = "dotnet.allocations.enabled"
        const val WINDOW_SECONDS = 10
        private const val TITLE = "Allocations in the editor"
        private const val ERRORS_KEPT = 4000
        private const val FIND_TIMEOUT_MS = 120_000L
        private const val FIND_PERIOD_MS = 500L
        private const val STOP_TIMEOUT_MS = 3_000L

        fun getInstance(project: Project): AllocationsService = project.service()
    }
}

/** The total of a method that has several lines which allocate, for the line of its declaration. */
object AllocationTotals {
    /** The line of the declaration (from 1) -> bytes a second and the share. */
    fun of(text: CharSequence, lines: List<AllocationLine>): Map<Int, Pair<Long, Double>> {
        if (lines.size < 2) return emptyMap()
        val totals = LinkedHashMap<Int, Pair<Long, Double>>()
        val starts = lineStarts(text)
        for (method in CSharpDeclarations.scan(text).all()) {
            if (method.kind != DeclarationKind.METHOD && method.kind != DeclarationKind.CONSTRUCTOR && method.kind != DeclarationKind.PROPERTY) continue
            val first = lineOf(starts, method.nameRange.startOffset) + 1
            val last = lineOf(starts, method.range.endOffset) + 1
            val inside = lines.filter { it.line in first..last }
            // one line: its own numbers say it all
            if (inside.size < 2) continue
            // a line of the declaration that allocates itself has its own numbers there
            if (inside.any { it.line == first }) continue
            totals[first] = inside.sumOf { it.bytesPerSecond } to inside.sumOf { it.share }
        }
        return totals
    }

    private fun lineStarts(text: CharSequence): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        for (i in text.indices) if (text[i] == '\n') starts += i + 1
        return starts.toIntArray()
    }

    private fun lineOf(starts: IntArray, offset: Int): Int {
        val found = starts.binarySearch(offset)
        return if (found >= 0) found else -found - 2
    }
}

/** The numbers at the end of a line, in the color of a comment; of what allocates a quarter of everything, in the color of the stripe. */
class AllocationsLinePainter : EditorLinePainter() {
    override fun getLineExtensions(project: Project, file: VirtualFile, lineNumber: Int): Collection<LineExtensionInfo>? {
        val service = project.getServiceIfCreated(AllocationsService::class.java) ?: return null
        if (service.last == null) return null
        val shown = service.shownAt(file, lineNumber) ?: return null
        val line = shown.line
        val total = shown.total
        val text = when {
            line != null -> AllocationText.line(line)
            total != null -> AllocationText.total(total.first, total.second)
            else -> return null
        }
        val hot = (line?.share ?: total?.second ?: 0.0) >= HOT_SHARE
        return listOf(LineExtensionInfo("    $text", TextAttributes(if (hot) AllocationsService.HOT else JBColor.GRAY, null, null, null, Font.ITALIC)))
    }

    private companion object {
        const val HOT_SHARE = 25.0
    }
}

/** Follows the programs the IDE starts: with the switch on, the newest of them is listened to. */
class AllocationsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = AllocationsService.getInstance(project)
        RunningDotNetProcesses.getInstance(project).subscribe(service) { started -> service.processesChanged(started) }
    }
}

class ToggleAllocationsAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.project != null
        // in the menu of the editor: only where the numbers are written
        if (e.isFromContextMenu) e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE)?.extension.equals("cs", ignoreCase = true)
    }

    override fun isSelected(e: AnActionEvent): Boolean = e.project?.let { AllocationsService.getInstance(it).isEnabled } == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AllocationsService.getInstance(e.project ?: return).isEnabled = state
    }
}
