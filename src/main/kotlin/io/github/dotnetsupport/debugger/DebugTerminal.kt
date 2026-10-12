package io.github.dotnetsupport.debugger

import com.google.gson.JsonObject
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.encoding.EncodingManager
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.Charset

private val LOG = logger<DebugTerminal>()

/**
 * `runInTerminal` of the adapter, for a program that reads its input: the adapter asks to start itself in a helper mode, the helper
 * creates the program with its own standard streams and reports the process id to the adapter. The helper runs here with pipes, so
 * the output of the program goes to the debug console and what is typed there goes to its input, as with Run. Without it the program
 * gets no input at all (the adapter itself starts it with the output redirected and nothing to read).
 *
 * The streams are UTF-8, as with Run. On Windows the program shares the hidden console of the helper, whose code page is the OEM one
 * (866, 437...), and .NET encodes redirected output with it: the console is switched to UTF-8 the way the adapter does it for a program
 * it starts itself, by its own helper mode `--console-utf8 <pid>`. That races the start of the program (the helper does not wait for
 * it), but wins by far: the program only resumes after the adapter has attached to it, and reads the code page at its first output.
 */
class DebugTerminal(private val print: (String, Key<*>) -> Unit) {
    @Volatile private var handler: KillableProcessHandler? = null

    /** The input of the debug console; there from the start, as the console takes it once when it is attached to the session. */
    val input: OutputStream = Input()

    /** Starts the helper; the answer to the adapter. Fails with the reason, which the adapter shows as the reason of the failed launch. */
    fun start(arguments: JsonObject): JsonObject {
        val command = arguments.getAsJsonArray("args")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
        if (command.isEmpty()) throw ExecutionException("runInTerminal without a command")
        val environment = arguments.getAsJsonObject("env")?.entrySet()?.filter { it.value.isJsonPrimitive }?.associate { it.key to it.value.asString }.orEmpty()
        val commandLine = GeneralCommandLine(command).withWorkDirectory(arguments.string("cwd")?.takeIf { it.isNotBlank() })
            .withEnvironment(environment).withCharset(Charsets.UTF_8)
        val started = KillableProcessHandler(commandLine)
        started.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.STDOUT || outputType == ProcessOutputTypes.STDERR) print(event.text, outputType)
            }
        })
        started.startNotify()
        handler = started
        if (SystemInfo.isWindows) utf8ConsoleCommand(command, started.process.pid())?.let(::switchConsole)
        return json("processId" to started.process.pid())
    }

    /** The helper ignores Ctrl+C (the program's to handle): killed outright, once the adapter has ended the program. */
    fun stop() {
        handler?.takeIf { !it.isProcessTerminated }?.killProcess()
    }

    private fun switchConsole(command: List<String>) {
        val result = runCatching { CapturingProcessHandler(GeneralCommandLine(command)).runProcess(SWITCH_TIMEOUT_MS) }
        val output = result.getOrNull()
        if (output == null || output.exitCode != 0 || output.isTimeout) {
            LOG.info("Cannot switch the console of the debuggee to UTF-8 (${output?.exitCode ?: result.exceptionOrNull()?.message})")
            print("The console of the program could not be switched to UTF-8: output that is not ASCII may be garbled\n", ProcessOutputTypes.SYSTEM)
        }
    }

    /**
     * What is typed in the console, encoded by the console for a process handler that is not an OS one (the default charset of the IDE),
     * in the encoding the program reads. Kept until the program is there.
     */
    private inner class Input : OutputStream() {
        private val buffer = ByteArrayOutputStream()

        override fun write(b: Int) = synchronized(buffer) { buffer.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = synchronized(buffer) { buffer.write(b, off, len) }

        override fun flush() {
            val target = handler?.takeIf { !it.isProcessTerminated }?.processInput ?: return
            val bytes = synchronized(buffer) { buffer.toByteArray().also { buffer.reset() } }
            if (bytes.isEmpty()) return
            target.write(transcode(bytes, EncodingManager.getInstance().defaultCharset, Charsets.UTF_8))
            target.flush()
        }
    }

    companion object {
        private const val SWITCH_TIMEOUT_MS = 5_000

        fun transcode(bytes: ByteArray, from: Charset, to: Charset): ByteArray = if (from == to) bytes else String(bytes, from).toByteArray(to)

        /**
         * The adapter asks to run itself: `<adapter> --run-in-terminal <port> <token> -- <program> <args>`, where `<adapter>` is the
         * executable, or `dotnet` and the dll. The same `<adapter>` with `--console-utf8 <pid>` switches the console of the process to UTF-8.
         */
        fun utf8ConsoleCommand(runInTerminal: List<String>, processId: Long): List<String>? {
            val self = runInTerminal.indexOf("--run-in-terminal").takeIf { it > 0 } ?: return null
            return runInTerminal.take(self) + listOf("--console-utf8", processId.toString())
        }
    }
}
