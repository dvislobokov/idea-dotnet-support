package io.github.dotnetsupport.cli

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Writer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** A request the helper answered with an error, or could not answer: the message is for the user, without a stack trace. */
class HelperException(message: String) : Exception(message)

/**
 * A helper on .NET that stays running and answers requests (`helpers/protocol/Protocol.cs`): `dotnet <dll> --serve`, one JSON object
 * per line both ways, requests matched to answers by their id, so several can be in flight. The process starts with the first request
 * and again after it has died; its `log` notifications and stderr go to the journal of the plugin under [logCategory].
 *
 * [start] gives the process its streams; [HelperConnection.of] is the usual one, tests give a fake.
 */
class HelperConnection(private val logCategory: String, private val start: () -> Transport) : Disposable {
    /** The streams of a running helper; [stop] ends it. */
    class Transport(val input: InputStream, val output: OutputStream, val errors: InputStream?, val isAlive: () -> Boolean, val stop: () -> Unit)

    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    @Volatile private var transport: Transport? = null
    private var writer: Writer? = null
    @Volatile private var disposed = false

    /** Sends [method] and waits for the answer; blocking, not for the EDT. Throws [HelperException] on an error, a timeout or a dead helper. */
    fun request(method: String, params: JsonElement? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JsonElement {
        val id = ids.incrementAndGet()
        val answer = CompletableFuture<JsonElement>()
        pending[id] = answer
        try {
            send(JsonObject().apply { addProperty("id", id); addProperty("method", method); params?.let { add("params", it) } })
            return answer.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            runCatching { send(JsonObject().apply { addProperty("method", "cancel"); add("params", JsonObject().apply { addProperty("id", id) }) }) }
            throw HelperException("`$method` got no answer in ${timeoutMs / 1000} s")
        } catch (e: ExecutionException) {
            throw e.cause as? HelperException ?: HelperException(e.cause?.message ?: e.toString())
        } finally {
            pending.remove(id)
        }
    }

    @Synchronized
    private fun send(message: JsonObject) {
        if (disposed) throw HelperException("the helper is stopped")
        val writer = running()
        try {
            writer.write(message.toString())
            writer.write("\n")
            writer.flush()
        } catch (e: java.io.IOException) {
            throw HelperException("the helper does not take requests: ${e.message}")
        }
    }

    private fun running(): Writer {
        transport?.takeIf { it.isAlive() }?.let { return writer!! }
        transport?.let { stopped(it, "exited") }
        val started = try {
            start()
        } catch (e: HelperException) {
            throw e
        } catch (e: Exception) {
            throw HelperException("the helper could not start: ${e.message ?: e.javaClass.simpleName}")
        }
        transport = started
        writer = started.output.bufferedWriter(Charsets.UTF_8)
        pool { read(started) }
        started.errors?.let { errors -> pool { errors.bufferedReader(Charsets.UTF_8).forEachLine { PluginLog.warn(logCategory, "stderr: $it") } } }
        return writer!!
    }

    private fun read(from: Transport) {
        try {
            BufferedReader(InputStreamReader(from.input, Charsets.UTF_8)).forEachLine(::line)
        } catch (_: java.io.IOException) {
        }
        if (transport === from) stopped(from, "exited")
    }

    private fun line(text: String) {
        val message = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return PluginLog.warn(logCategory, "not a message of the protocol: ${text.take(200)}")
        val id = message.get("id")?.takeIf { !it.isJsonNull }?.asLong
        if (id == null) {
            if (message.get("method")?.asString == "log") log(message.getAsJsonObject("params"))
            return
        }
        val answer = pending[id] ?: return
        val error = message.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
        if (error != null) answer.completeExceptionally(HelperException(error.get("message")?.asString ?: error.toString()))
        else answer.complete(message.get("result") ?: JsonNull.INSTANCE)
    }

    private fun log(params: JsonObject?) {
        val text = params?.get("message")?.asString ?: return
        when (params.get("level")?.asString) {
            "error" -> PluginLog.error(logCategory, text)
            "warn" -> PluginLog.warn(logCategory, text)
            else -> PluginLog.info(logCategory, text)
        }
    }

    /** The requests waiting for a helper that has gone fail at once instead of at their timeout. */
    private fun stopped(from: Transport, why: String) {
        synchronized(this) { if (transport === from) { transport = null; writer = null } }
        val waiting = pending.values.toList()
        if (waiting.isNotEmpty() && !disposed) PluginLog.warn(logCategory, "the helper $why with ${waiting.size} request(s) unanswered")
        waiting.forEach { it.completeExceptionally(HelperException("the helper $why")) }
    }

    override fun dispose() {
        disposed = true
        val current = synchronized(this) { transport.also { transport = null; writer = null } } ?: return
        runCatching { current.output.close() } // the end of stdin ends the helper
        runCatching { current.stop() }
        pending.values.forEach { it.completeExceptionally(HelperException("the helper is stopped")) }
    }

    private fun pool(task: () -> Unit) {
        ApplicationManager.getApplication()?.executeOnPooledThread(task) ?: Thread(task, "$logCategory helper").apply { isDaemon = true }.start()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L

        /** The usual transport: [helper] built if needed, then `dotnet <dll> --serve` in [workDirectory]. Blocking on the first call. */
        fun of(helper: DotNetHelper, logCategory: String, workDirectory: () -> String = { DotNetHelper.root().path }): HelperConnection =
            HelperConnection(logCategory) {
                val dll = helper.ensureBuilt() ?: throw HelperException(helper.failure ?: "${helper.assembly} could not be built")
                val command = DotNetCli.commandLine(workDirectory(), dll.path, "--serve").withCharset(Charsets.UTF_8)
                PluginLog.info(logCategory, "starting ${helper.assembly}: ${command.commandLineString}")
                val process = command.createProcess()
                Transport(process.inputStream, process.outputStream, process.errorStream, process::isAlive) { process.destroy() }
            }
    }
}
