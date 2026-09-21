package io.github.dotnetsupport.dap

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.dap.connection.DebugAdapterHandle
import io.github.dotnetsupport.run.BreakpointExtras
import io.github.dotnetsupport.run.DapMessageRewritingStream
import io.github.dotnetsupport.run.DapSetBreakpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * The process of `dotnet-debugger`. Not the `CommandLineDebugAdapterHandle` of the platform because of how that one stops the process:
 * a soft kill first, which on Windows is Ctrl+C sent through a helper and for this adapter ends in "Cannot send Ctrl+C" reported as an
 * error of the IDE whenever the adapter is still alive at that moment. And an adapter that is busy with a long request answers nothing at
 * all, `disconnect` included, so the only dependable stop is to kill it: the debuggee goes with it (on Linux it would be left running).
 */
class DotNetDebugAdapterHandle(commandLine: GeneralCommandLine, breakpointExtras: (path: String, line: Int) -> BreakpointExtras?) : DebugAdapterHandle {
    private val process: Process = commandLine.createProcess()

    override val input: InputStream get() = process.inputStream

    /** What the client writes goes to the adapter with the hit counts and log messages of the breakpoints added, see [DapSetBreakpoints]. */
    override val output: OutputStream = DapMessageRewritingStream(process.outputStream) { body ->
        DapSetBreakpoints.rewrite(body, breakpointExtras) { LOG.warn("Cannot add hit counts and log messages to setBreakpoints", it) }
    }

    init {
        // DAP goes through stdout; stderr has to be drained, or the adapter blocks on a full pipe
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { process.errorStream.bufferedReader().forEachLine { LOG.info("dotnet-debugger: $it") } }
        }
    }

    /** Called after the `disconnect` request of the protocol (or its timeout): a moment to exit by itself, then the whole process tree. */
    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (process.waitFor(EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return@withContext
        LOG.info("dotnet-debugger (pid ${process.pid()}) has not exited by itself, killing the process tree")
        if (!OSProcessUtil.killProcessTree(process)) process.destroyForcibly()
    }

    companion object {
        private const val EXIT_TIMEOUT_MS = 1000L
        private val LOG = logger<DotNetDebugAdapterHandle>()
    }
}
