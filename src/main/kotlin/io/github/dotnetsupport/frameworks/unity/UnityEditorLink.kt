package io.github.dotnetsupport.frameworks.unity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.solution.SolutionService
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The link of the IDE with the Unity editor of a project (UNITY_PLAN.md, "Интеграция с редактором"): files in `Temp/DotNetSupport` the
 * package of [UnityEditorPackage] reads and writes — commands to it in `commands/`, its state in `state.json` with a heartbeat every 2 s.
 * Pure: the tests give it the files and the clock.
 */
object UnityEditorLink {
    /**
     * [NO_EDITOR]: no editor has the project open (`Library/EditorInstance.json` is gone, or its process is — a crashed editor leaves the
     * file); [NO_PACKAGE] / [OLD_PACKAGE]: the package is not in the project, or another version of it is; [LOADING]: the editor has not
     * loaded the package on disk yet (it looks at the files of the project when its window gets the focus); [BUSY]: no heartbeat for a
     * while (importing, compiling, reloading the domain); [CONNECTED]: the editor follows the commands.
     */
    enum class Status { NO_EDITOR, NO_PACKAGE, OLD_PACKAGE, LOADING, BUSY, CONNECTED }

    /** What the editor wrote into `state.json`. */
    data class EditorState(
        val packageVersion: String, val unityVersion: String, val processId: Long, val playing: Boolean, val paused: Boolean, val compiling: Boolean,
        val updating: Boolean, val compileErrors: Int, val compiledAt: Long, val heartbeat: Long,
        /** Whether Unity opens scripts in this IDE (step 3); null from a package before 1.2.0. */
        val ideIsEditor: Boolean? = null,
        /** `Debug` / `Release`, the code optimization of the editor's session (a debugger needs Debug); null from a package before 1.4.0. */
        val codeOptimization: String? = null,
    ) {
        val debugMode: Boolean get() = codeOptimization == "Debug"
    }

    data class Link(val root: File, val status: Status, val state: EditorState?, val editorProcessId: Long?)

    /** A heartbeat older than this: the editor is busy (its main thread is), not gone — that the process says. */
    const val HEARTBEAT_TIMEOUT_MS = 6_000L

    fun folder(root: File): File = File(root, "Temp/DotNetSupport")

    /**
     * The port the Mono debugger agent of the editor listens on, at 127.0.0.1 (as Rider and Visual Studio find it; seen on Unity 6000.6:
     * pid 28276 → 56276). Every Unity process has one by the same rule, the workers that import assets too: the pid is the editor's, of
     * `Library/EditorInstance.json`.
     */
    fun debuggerPort(editorProcessId: Long): Int = 56000 + (editorProcessId % 1000).toInt()

    fun link(root: File, now: Long = System.currentTimeMillis(), isAlive: (Long) -> Boolean = ::isAlive): Link {
        val instance = readJson(File(root, "Library/EditorInstance.json"))
        val pid = instance?.get("process_id")?.takeIf { it.isJsonPrimitive }?.asLong
        if (pid == null || !isAlive(pid)) return Link(root, Status.NO_EDITOR, null, null)
        val packageStatus = UnityEditorPackage.status(root)
        if (packageStatus == UnityEditorPackage.Status.MISSING) return Link(root, Status.NO_PACKAGE, null, pid)
        if (packageStatus == UnityEditorPackage.Status.OUTDATED) return Link(root, Status.OLD_PACKAGE, null, pid)
        val state = readState(File(folder(root), "state.json"))?.takeIf { it.processId == pid }
        val status = when {
            state == null || state.packageVersion != UnityEditorPackage.version -> Status.LOADING
            now - state.heartbeat > HEARTBEAT_TIMEOUT_MS -> Status.BUSY
            else -> Status.CONNECTED
        }
        return Link(root, status, state, pid)
    }

    fun stateOf(json: JsonObject): EditorState? = runCatching {
        EditorState(
            json.get("package").asString, json.get("unity").asString, json.get("processId").asLong, json.get("playing").asBoolean,
            json.get("paused").asBoolean, json.get("compiling").asBoolean, json.get("updating").asBoolean, json.get("compileErrors").asInt,
            json.get("compiledAt").asLong, json.get("heartbeat").asLong, json.get("ideIsEditor")?.takeIf { it.isJsonPrimitive }?.asBoolean,
            json.get("codeOptimization")?.takeIf { it.isJsonPrimitive }?.asString,
        )
    }.getOrNull()

    /** A command for the editor: a file of its own, named so they run in the order they were sent. */
    fun send(root: File, command: String, sequence: Long) {
        val commands = File(folder(root), "commands")
        commands.mkdirs()
        val name = "%013d-%06d".format(System.currentTimeMillis(), sequence % 1_000_000)
        val temporary = File(commands, "$name.tmp")
        temporary.writeText(command)
        // renamed when complete: the editor takes `*.txt` only
        if (!temporary.renameTo(File(commands, "$name.txt"))) temporary.delete()
    }

    /**
     * The state, read again a few times when the file is there but cannot be read: the editor moves a new one over it, and Windows refuses
     * the file for that moment (seen live: "switch to Unity" for a second after Pause).
     */
    private fun readState(file: File): EditorState? {
        repeat(3) { attempt ->
            if (!file.isFile) return null
            readJson(file)?.let(::stateOf)?.let { return it }
            if (attempt < 2) Thread.sleep(30)
        }
        return null
    }

    private fun readJson(file: File): JsonObject? =
        runCatching { if (file.isFile) JsonParser.parseString(file.readText()).asJsonObject else null }.getOrNull()

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    /**
     * A file the editor should import when the IDE saved, made, deleted or moved it: under `Assets/` or `Packages/` of the project, not a
     * `.meta` (the editor writes those) — [relativePath] from the project's root, with `/`.
     */
    fun refreshes(relativePath: String): Boolean =
        (relativePath.startsWith("Assets/") || relativePath.startsWith("Packages/")) && !relativePath.endsWith(".meta", ignoreCase = true)
}

/**
 * The link with the Unity editor of the project's Unity solution, looked at every second while there is one; the actions, the status bar
 * and the refresh on save take [link]. A command goes when the editor is connected and is dropped otherwise.
 */
@Service(Service.Level.PROJECT)
class UnityEditorConnection(private val project: Project) : Disposable {
    @Volatile var link: UnityEditorLink.Link? = null
        private set

    private var sequence = 0L
    private var poll: ScheduledFuture<*>? = null
    private var commands: ScheduledFuture<*>? = null
    private var ideWrittenAt = 0L
    private var offered = false
    private var refreshPending: ScheduledFuture<*>? = null

    /** The Unity project of the solution (its folder holds the solution Unity generates); null in any other project. */
    fun root(): VirtualFile? = SolutionService.getInstance(project).allSolutionFiles().firstNotNullOfOrNull { it.parent?.takeIf(UnityProjects::isUnityRoot) }

    fun start() {
        if (poll != null || root() == null) return
        poll = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(::update, 0, 1, TimeUnit.SECONDS)
        // a double click in Unity opens the file here: looked for four times a second, so the IDE answers as an editor does
        commands = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(::takeCommands, 250, 250, TimeUnit.MILLISECONDS)
    }

    fun update() {
        if (project.isDisposed) return
        val root = root()?.path?.let(::File)
        val fresh = root?.let { runCatching { UnityEditorLink.link(it) }.getOrNull() }
        // the heartbeat changes every 2 s: the actions and the status bar are updated when what they show does
        fun shown(link: UnityEditorLink.Link?) = link?.copy(state = link.state?.copy(heartbeat = 0))
        val changed = shown(fresh) != shown(link)
        link = fresh
        upgradeLoaded(fresh)
        if (root != null && fresh != null && fresh.status != UnityEditorLink.Status.NO_EDITOR) writeIde(root)
        if (!changed) return
        if (fresh?.status == UnityEditorLink.Status.CONNECTED && !offered && fresh.state?.ideIsEditor == false) {
            offered = true
            ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) UnityExternalEditor.offer(project, fresh) }, project.disposed)
        }
        // the editor imported or compiled: it has written the solution and the projects again (a renamed script is in them now), and the
        // IDE would notice only at its next look at the disk — 14 s on the probe, a renamed file was missing from the tree meanwhile
        if (fresh?.status == UnityEditorLink.Status.CONNECTED) root?.let(::refreshProjectFiles)
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            ActivityTracker.getInstance().inc()
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(UnityEditorStatusWidgetFactory.ID)
        }, project.disposed)
    }

    private var upgradeSentTo: Long? = null

    /**
     * The files of a new version of the package are on disk while the editor runs the old one (it looks at the disk when its window gets
     * the focus): the old one imports the new on a refresh — no switch to Unity after an update of the plugin.
     */
    private fun upgradeLoaded(link: UnityEditorLink.Link?) {
        val state = link?.state ?: return
        if (link.status != UnityEditorLink.Status.LOADING || state.packageVersion == UnityEditorPackage.version) return
        if (System.currentTimeMillis() - state.heartbeat > UnityEditorLink.HEARTBEAT_TIMEOUT_MS || upgradeSentTo == state.processId) return
        upgradeSentTo = state.processId
        synchronized(this) { UnityEditorLink.send(link.root, "refresh", sequence++) }
    }

    /**
     * `ide.json` for the package (step 3), every 2 s while an editor has the project open: Temp is the editor's, it removes it when it
     * closes, and the IDE does not make it then.
     */
    private fun writeIde(root: File) {
        val now = System.currentTimeMillis()
        if (now - ideWrittenAt < 2_000) return
        ideWrittenAt = now
        runCatching {
            UnityExternalEditor.writeIde(root, UnityExternalEditor.ideJson(ProcessHandle.current().pid(), UnityExternalEditor.launcher(), UnityExternalEditor.ideName(), now))
        }
    }

    private fun takeCommands() {
        if (project.isDisposed) return
        val root = link?.takeIf { it.status != UnityEditorLink.Status.NO_EDITOR }?.root ?: return
        UnityExternalEditor.takeCommands(root).forEach { UnityExternalEditor.open(project, it) }
    }

    /** The solution and the projects the editor generates beside `Assets/`, read from disk again. */

    private fun refreshProjectFiles(root: File) {
        val files = root.listFiles { file -> file.isFile && (file.extension.equals("csproj", true) || file.extension.equals("sln", true)) }.orEmpty().toList()
        if (files.isNotEmpty()) com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshIoFiles(files, true, false, null)
    }

    /** Sends [command] when the editor is connected; false otherwise. */
    fun send(command: String): Boolean {
        val current = link ?: return false
        if (current.status != UnityEditorLink.Status.CONNECTED) return false
        synchronized(this) { UnityEditorLink.send(current.root, command, sequence++) }
        AppExecutorUtil.getAppScheduledExecutorService().schedule(::update, 300, TimeUnit.MILLISECONDS)
        return true
    }

    /**
     * The IDE saved a script or an asset: the editor imports it now — it does not look at the files while it is in the background, the
     * user would wait for the switch to it. Not in Play mode (a compilation there stops the game or is put off, as the editor's settings
     * say); a burst of saves is one refresh.
     */
    fun refreshSoon() {
        synchronized(this) {
            refreshPending?.cancel(false)
            refreshPending = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                update()
                if (link?.state?.playing == false) send("refresh")
            }, 400, TimeUnit.MILLISECONDS)
        }
    }

    override fun dispose() {
        poll?.cancel(false)
        commands?.cancel(false)
        refreshPending?.cancel(false)
    }

    companion object {
        fun getInstance(project: Project): UnityEditorConnection = project.service()
    }
}
