package io.github.dotnetsupport.debugger

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

private val LOG = logger<DebugAdapterProcess>()

/**
 * The process of `dotnet-debugger` (the `dotnet-debugger-dap` global tool): DAP over its standard streams. Stopped by killing its process
 * tree: a soft stop is Ctrl+C sent through a helper on Windows, which this adapter answers with "Cannot send Ctrl+C", and an adapter
 * busy with a long request answers nothing at all, `disconnect` included (`dap-probe/FINDINGS.md`). The debuggee goes with it.
 */
class DebugAdapterProcess(commandLine: GeneralCommandLine) {
    private val process: Process = commandLine.createProcess()

    val input: InputStream get() = process.inputStream
    val output: OutputStream get() = process.outputStream
    val pid: Long get() = process.pid()

    init {
        // DAP goes through stdout; stderr has to be drained, or the adapter blocks on a full pipe
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { process.errorStream.bufferedReader().forEachLine { LOG.info("dotnet-debugger: $it") } }
        }
    }

    val isAlive: Boolean get() = process.isAlive

    /** A moment to exit by itself after `disconnect`, then the whole process tree. */
    fun stop(graceMs: Long = 1000) {
        if (process.waitFor(graceMs, TimeUnit.MILLISECONDS)) return
        LOG.info("dotnet-debugger (pid ${process.pid()}) has not exited by itself, killing the process tree")
        if (!OSProcessUtil.killProcessTree(process)) process.destroyForcibly()
    }

    companion object {
        fun commandLine(adapter: File, log: File?): GeneralCommandLine =
            GeneralCommandLine(listOfNotNull(adapter.path, log?.let { "--log=${it.path}" })).withWorkDirectory(adapter.parentFile)
    }
}

/** What a debug session starts with: the `launch` of a program, or the `attach` to a process. */
class DebugStart(
    val attach: Boolean,
    /** The arguments of the request, as `DotNetLaunchArguments` makes them. */
    val arguments: Map<String, Any?>,
    /** The name the .NET Monitor shows the program under. */
    val name: String,
    /** A test host under `VSTEST_HOST_DEBUG` calls `Debugger.Break()` once it has a debugger: that stop is not the user's, see [io.github.dotnetsupport.run.TestHostDebug]. */
    val skipInitialBreak: Boolean = false,
    /** `launchBrowser` of the profile: the URL the program says it listens on is opened. */
    val launchUrl: String? = null,
    val openBrowser: Boolean = false,
)
