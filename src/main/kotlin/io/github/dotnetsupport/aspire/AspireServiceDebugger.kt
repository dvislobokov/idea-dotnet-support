package io.github.dotnetsupport.aspire

import com.intellij.execution.process.OSProcessUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebuggerManager
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.debugger.DotNetAttachProfile
import io.github.dotnetsupport.run.DotNetProcessAttacher
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug of an Aspire AppHost: the debugger is attached to every service of the solution DCP starts (one debug session per process, as
 * Rider shows them), and to the new process when a resource is restarted. DCP is watched by polling: the API server of DCP is looked
 * for among all processes until it is there, after that only its tree is walked. When the AppHost is gone the sessions are stopped.
 *
 * An attach comes after the start of the process, so a breakpoint in the first lines of a service may be passed before it; the IDE
 * execution protocol of Aspire, where the IDE starts the service itself, is the way to stop there.
 */
class AspireServiceDebugger private constructor(private val project: Project, private val appHostPid: Long, private val report: (String) -> Unit) {
    private val attached = ConcurrentHashMap.newKeySet<Long>()
    private val commandLines = ConcurrentHashMap<Long, String>()
    private val notRoots = ConcurrentHashMap.newKeySet<Long>()
    private val stopped = AtomicBoolean()
    @Volatile private var root: ProcessHandle? = null
    @Volatile private var projects: List<String>? = null
    @Volatile private var future: ScheduledFuture<*>? = null
    private val started: Instant = ProcessHandle.of(appHostPid).flatMap { it.info().startInstant() }.orElse(Instant.now())

    private fun schedule() {
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            try {
                poll()
            } catch (e: Exception) {
                PluginLog.warn(LOG_CATEGORY, "watching the services of the AppHost $appHostPid: ${e.message ?: e.javaClass.simpleName}")
            }
        }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
    }

    private fun poll() {
        if (stopped.get() || project.isDisposed) return stop()
        if (ProcessHandle.of(appHostPid).map { it.isAlive }.orElse(false) != true) return stop()
        val projects = projects ?: solutionProjects().also { projects = it }
        val dcp = root?.takeIf { it.isAlive } ?: findRoot() ?: return
        root = dcp
        val services = AspireProcesses.services(tree(dcp), appHostPid, projects)
        val attacher = DotNetProcessAttacher.find() ?: return
        val now = Instant.now()
        for (service in services) {
            if (service.pid in attached) continue
            // a process younger than that may not have loaded the runtime yet: the attach would fail
            val age = ProcessHandle.of(service.pid).flatMap { it.info().startInstant() }.map { Duration.between(it, now) }.orElse(Duration.ZERO)
            if (age.toMillis() < MIN_AGE_MS) continue
            attached += service.pid
            PluginLog.info(LOG_CATEGORY, "attaching to ${service.name} (${service.pid}), a service of the AppHost $appHostPid")
            report("Aspire: attaching the debugger to ${service.name} (${service.pid})\n")
            attacher.attach(project, service.pid, service.name)
        }
        attached.removeIf { pid -> ProcessHandle.of(pid).map { !it.isAlive }.orElse(true) }
    }

    /** The API server of DCP for this AppHost: a `dcp` process started after it, without a `dcp` parent, with `--monitor <pid>`. */
    private fun findRoot(): ProcessHandle? = ProcessHandle.allProcesses().use { stream ->
        stream.filter { it.pid() !in notRoots && AspireProcesses.fileName(it.info().command().orElse(null)).removeSuffix(".exe") == "dcp" }.toList()
    }.firstOrNull { handle ->
        val parent = handle.parent().orElse(null)
        val parentIsDcp = parent != null && AspireProcesses.fileName(parent.info().command().orElse(null)).removeSuffix(".exe") == "dcp"
        val startedBefore = handle.info().startInstant().map { it.isBefore(started) }.orElse(false)
        val found = !parentIsDcp && !startedBefore && AspireProcesses.isDcpRootOf(row(handle, parent?.pid()), appHostPid)
        if (!found) notRoots += handle.pid()
        found
    }

    /** The processes under [dcp], each child list read once per process (one snapshot of the system each, a dozen in all). */
    private fun tree(dcp: ProcessHandle): List<AspireProcesses.Row> {
        val rows = ArrayList<AspireProcesses.Row>()
        val queue = ArrayDeque<Pair<ProcessHandle, AspireProcesses.Row?>>()
        queue += dcp to null
        while (queue.isNotEmpty() && rows.size < MAX_PROCESSES) {
            val (handle, parent) = queue.removeFirst()
            val row = row(handle, parent?.pid, parentIsDcp = parent != null && AspireProcesses.isDcp(parent))
            rows += row
            handle.children().use { children -> children.forEach { queue += it to row } }
        }
        return rows
    }

    private fun row(handle: ProcessHandle, parent: Long?, parentIsDcp: Boolean = false): AspireProcesses.Row {
        val executable = handle.info().command().orElse(null)
        val partial = AspireProcesses.Row(handle.pid(), parent, executable, handle.info().commandLine().orElse(""))
        if (partial.commandLine.isNotEmpty() || !AspireProcesses.needsCommandLine(partial, parentIsDcp)) return partial
        return partial.copy(commandLine = commandLine(handle.pid()))
    }

    /** Java does not read the command line of another process on Windows; the list of the platform does, for all processes at once. */
    private fun commandLine(pid: Long): String {
        commandLines[pid]?.let { return it }
        runCatching { OSProcessUtil.getProcessList() }.getOrNull()?.forEach { commandLines.putIfAbsent(it.pid.toLong(), it.commandLine.orEmpty()) }
        return commandLines.getOrPut(pid) { "" }
    }

    private fun solutionProjects(): List<String> = ReadAction.computeBlocking<List<String>, RuntimeException> {
        if (project.isDisposed) return@computeBlocking emptyList()
        val solutions = SolutionService.getInstance(project)
        solutions.solutionFiles().flatMap { file -> solutions.solution(file).allProjects.mapNotNull { it.resolveFile(file)?.path } }.distinct()
    }

    /** The AppHost has ended, or its session: the sessions of its services end too. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        future?.cancel(false)
        val pids = attached.toSet()
        if (pids.isEmpty()) return
        ApplicationManager.getApplication().invokeLater({
            XDebuggerManager.getInstance(project).debugSessions.filter { (it.runProfile as? DotNetAttachProfile)?.processId in pids }.forEach { it.stop() }
        }, project.disposed)
    }

    companion object {
        const val LOG_CATEGORY = "Aspire"
        private const val POLL_MS = 1_500L
        private const val MIN_AGE_MS = 500L
        private const val MAX_PROCESSES = 500

        /** Starts watching the DCP of the AppHost [appHostPid]; [report] prints to the console of the AppHost session. */
        fun start(project: Project, appHostPid: Long, report: (String) -> Unit): AspireServiceDebugger =
            AspireServiceDebugger(project, appHostPid, report).also { it.schedule() }
    }
}
