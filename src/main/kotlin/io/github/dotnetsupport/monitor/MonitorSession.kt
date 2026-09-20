package io.github.dotnetsupport.monitor

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.run.DotNetRunConfiguration
import java.io.File
import java.io.RandomAccessFile
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What can be monitored: a process started from the IDE (measured with its children) or any .NET process of the machine. */
class MonitorTarget(val pid: Long, val title: String, val withChildren: Boolean) {
    override fun toString(): String = title
    override fun equals(other: Any?): Boolean = other is MonitorTarget && other.pid == pid
    override fun hashCode(): Int = pid.hashCode()
}

class MonitorSample(
    val cpuPercent: Double,
    val workingSetBytes: Long,
    val threads: Int?,
    val processes: Int,
    /** Null while `dotnet-counters` is not attached (not installed, the application has not started yet, ...). */
    val runtime: RuntimeMetrics?,
)

/**
 * Samples one [target] every second until the process exits or the session is disposed. The operating system gives CPU
 * and memory; with [countersExecutable] the counters of the runtime are read from the file `dotnet-counters collect` appends to.
 * The callbacks are invoked on a pooled thread.
 */
class MonitorSession(
    val target: MonitorTarget,
    private val countersExecutable: File?,
    private val onSample: (MonitorSample) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onEnd: () -> Unit,
) : Disposable {
    private var task: ScheduledFuture<*>? = null
    private var previousCpu: Duration? = null
    private var previousTime = 0L
    private var collector: Collector? = null
    private var candidatePid = -1L
    private var candidateTicks = 0
    private val refusedPids = HashSet<Long>()
    @Volatile private var disposed = false

    fun start() {
        task = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ runCatching { tick() } }, 0, 1, TimeUnit.SECONDS)
    }

    private fun tick() {
        if (disposed) return
        val processes = if (target.withChildren) ProcessSampler.tree(target.pid) else ProcessHandle.of(target.pid).filter { it.isAlive }.map { listOf(it) }.orElse(emptyList())
        if (processes.isEmpty()) {
            dispose()
            onEnd()
            return
        }

        // The launcher (`dotnet run`, `dotnet watch`) and the build nodes it leaves behind are not the application:
        // what is measured is the application process with its own children.
        val application = pickApplication(processes, refusedPids)
        val usage = ProcessSampler.sample(listOf(application) + application.descendants().filter { it.isAlive }.toList())
        val now = System.nanoTime()
        val cpu = previousCpu?.let { ProcessSampler.cpuPercent(it, usage.cpuTime, now - previousTime) } ?: 0.0
        previousCpu = usage.cpuTime
        previousTime = now

        followApplication(application.pid())
        onSample(MonitorSample(cpu, usage.workingSetBytes, usage.threads, usage.processes, collector?.read()))
    }

    /**
     * `dotnet run` builds first and starts the application afterwards, as its child; `dotnet watch` restarts it. The
     * counters follow the youngest process of the tree once it has lived for a couple of seconds.
     */
    private fun followApplication(application: Long) {
        val executable = countersExecutable ?: return
        if (application == candidatePid) candidateTicks++ else { candidatePid = application; candidateTicks = 0 }

        val current = collector
        if (current != null && current.isFinished()) {
            // not a .NET process, or the runtime refused the connection
            if (!current.hasData) refusedPids += current.pid
            onStatus(current.failure().ifEmpty { "dotnet-counters has stopped" })
            current.close()
            collector = null
        }
        val attached = collector
        if (attached != null && (attached.pid != application || attached.isTooLarge())) {
            if (candidateTicks < STABLE_TICKS && !attached.isTooLarge()) return
            attached.close()
            collector = null
        }
        if (collector == null && candidateTicks >= STABLE_TICKS && application !in refusedPids) {
            collector = runCatching { Collector(executable, application) }.onFailure { onStatus(it.message.orEmpty()) }.getOrNull()
            if (collector != null) onStatus("")
        }
    }

    override fun dispose() {
        disposed = true
        task?.cancel(false)
        collector?.close()
        collector = null
    }

    /** One `dotnet-counters collect` process and the file it writes. */
    private class Collector(executable: File, val pid: Long) {
        private val file = FileUtil.createTempFile("dotnet-counters-$pid-", ".csv", true)
        private val output = StringBuffer()
        private val handler = OSProcessHandler(DotNetCounters.collectCommand(executable, pid, file))
        private val snapshot = CounterSnapshot()
        private var position = 0L
        private var pending = ""
        var hasData = false
            private set

        init {
            // the tool refuses to overwrite nothing, but it wants to create the file itself
            file.delete()
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (output.length < 4_000) output.append(event.text)
                }
            })
            handler.startNotify()
        }

        fun isFinished(): Boolean = handler.isProcessTerminated
        fun isTooLarge(): Boolean = file.length() > MAX_FILE_BYTES
        fun failure(): String = output.lines().lastOrNull { it.isNotBlank() && !it.startsWith("   at ") }.orEmpty().trim()

        /** The lines appended since the previous call. */
        fun read(): RuntimeMetrics? {
            if (file.length() > position) {
                RandomAccessFile(file, "r").use { reader ->
                    reader.seek(position)
                    val bytes = ByteArray((reader.length() - position).toInt())
                    reader.readFully(bytes)
                    position += bytes.size
                    val text = pending + String(bytes, Charsets.UTF_8)
                    // the last line may be half-written
                    pending = text.substringAfterLast('\n')
                    text.substringBeforeLast('\n', "").lineSequence().mapNotNull { DotNetCounters.parseLine(it.trimEnd('\r')) }.forEach {
                        snapshot.accept(it)
                        hasData = true
                    }
                }
            }
            return if (hasData) snapshot.metrics() else null
        }

        fun close() {
            if (!handler.isProcessTerminated) handler.destroyProcess()
            AppExecutorUtil.getAppScheduledExecutorService().schedule({ file.delete() }, 2, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val STABLE_TICKS = 2
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024

        /**
         * The youngest process of the tree; the root when it has no children (the application was started directly).
         * [refused]: processes the counters could not attach to, i.e. not .NET ones (a console host, a native child).
         */
        fun pickApplication(processes: List<ProcessHandle>, refused: Set<Long> = emptySet()): ProcessHandle =
            processes.drop(1).filter { it.pid() !in refused }
                .maxByOrNull { it.info().startInstant().map { start -> start.toEpochMilli() }.orElse(0) } ?: processes.first()
    }
}

/** The processes of our run configurations that are alive, the newest first. */
@Service(Service.Level.PROJECT)
class RunningDotNetProcesses {
    private val targets = CopyOnWriteArrayList<MonitorTarget>()
    private val listeners = CopyOnWriteArrayList<(MonitorTarget?) -> Unit>()

    fun targets(): List<MonitorTarget> = targets.toList()

    /** [listener] gets the started process, or null when one has exited. */
    fun subscribe(parent: Disposable, listener: (MonitorTarget?) -> Unit) {
        listeners += listener
        com.intellij.openapi.util.Disposer.register(parent) { listeners -= listener }
    }

    fun started(name: String, handler: ProcessHandler) {
        val pid = runCatching { (handler as? BaseProcessHandler<*>)?.process?.pid() }.getOrNull() ?: return
        val target = MonitorTarget(pid, "$name ($pid)", withChildren = true)
        targets.add(0, target)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                targets.remove(target)
                listeners.forEach { it(null) }
            }
        })
        listeners.forEach { it(target) }
    }

    companion object {
        fun getInstance(project: Project): RunningDotNetProcesses = project.service()
    }
}

class DotNetProcessStartListener(private val project: Project) : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val configuration = env.runProfile as? DotNetRunConfiguration ?: return
        RunningDotNetProcesses.getInstance(project).started(configuration.name, handler)
        MonitorToolWindowFactory.revealOnce(project)
    }
}
