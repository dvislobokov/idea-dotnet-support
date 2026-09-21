package io.github.dotnetsupport.format

import com.google.gson.JsonObject
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Formats with CSharpier. Starting the tool costs 200–300 ms per file (600 ms through `dotnet csharpier`), its built-in
 * HTTP server answers in 10–20 ms, so one server per tool is kept for the project and the one-shot run is the fallback.
 * Every method blocks: call from a background thread.
 */
@Service(Service.Level.PROJECT)
class CSharpierServer : Disposable {
    private class Running(val cli: CSharpierCli, val handler: OSProcessHandler, val port: Int)

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private var running: Running? = null

    fun format(cli: CSharpierCli, file: File, text: String): FormatResult {
        // The server looks for the configuration around the file and answers "An exception was thrown" when the
        // directory is not there (an in-memory or remote file); the one-shot run copes with that.
        if (file.parentFile?.isDirectory != true) return formatOnce(cli, file, text)
        val viaServer = runCatching { server(cli)?.let { request(it.port, file, text) } }
            .onFailure { LOG.info("CSharpier server failed, falling back to a one-shot run: ${it.message}"); stop() }
            .getOrNull()
        // an internal error of the server says nothing useful: the one-shot run either works or explains itself
        if (viaServer is FormatResult.Failed && viaServer.message == SERVER_INTERNAL_ERROR) return formatOnce(cli, file, text)
        return viaServer ?: formatOnce(cli, file, text)
    }

    /** `csharpier format --stdin-path <file>` with the text on stdin. */
    fun formatOnce(cli: CSharpierCli, file: File, text: String): FormatResult = try {
        val handler = CapturingProcessHandler(cli.formatStdin(file))
        handler.processInput.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val output = handler.runProcess(ONE_SHOT_TIMEOUT_MS)
        if (output.isTimeout) FormatResult.Failed("CSharpier did not answer in ${ONE_SHOT_TIMEOUT_MS / 1000} s.")
        else CSharpierOutput.parseProcessOutput(output.exitCode, output.stdout, output.stderr)
    } catch (e: Exception) {
        FormatResult.Failed(e.message ?: "CSharpier could not be started.")
    }

    @Synchronized private fun server(cli: CSharpierCli): Running? {
        running?.let { if (it.cli == cli && !it.handler.isProcessTerminated) return it }
        stop()

        val started = CompletableFuture<Int>()
        val output = StringBuffer()
        val handler = OSProcessHandler(cli.server(cli.manifestDirectory))
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
                // "Started on 62697"
                PORT.find(output)?.let { started.complete(it.groupValues[1].toInt()) }
            }

            override fun processTerminated(event: ProcessEvent) {
                started.completeExceptionally(IllegalStateException(output.toString().trim().ifEmpty { "exit code ${event.exitCode}" }))
            }
        })
        handler.startNotify()
        return try {
            Running(cli, handler, started.get(START_TIMEOUT_S, TimeUnit.SECONDS)).also { running = it }
        } catch (e: Exception) {
            handler.destroyProcess()
            LOG.info("CSharpier server did not start: ${e.cause?.message ?: e.message}")
            null
        }
    }

    private fun request(port: Int, file: File, text: String): FormatResult {
        val body = JsonObject().apply {
            addProperty("fileName", file.path)
            addProperty("fileContents", text)
        }.toString()
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/format"))
            .header("Content-Type", "application/json")
            // the server of 1.x never answers a request it cannot parse: never wait for it forever
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        if (response.statusCode() != 200) throw IllegalStateException("HTTP ${response.statusCode()}")
        return CSharpierOutput.parseServerResponse(response.body())
    }

    @Synchronized fun stop() {
        running?.handler?.destroyProcess()
        running = null
    }

    override fun dispose() = stop()

    companion object {
        private val LOG = logger<CSharpierServer>()
        private val PORT = Regex("""Started on (\d+)""")
        private const val SERVER_INTERNAL_ERROR = "An exception was thrown"
        private const val START_TIMEOUT_S = 15L
        private const val REQUEST_TIMEOUT_S = 15L
        private const val ONE_SHOT_TIMEOUT_MS = 30_000

        fun getInstance(project: Project): CSharpierServer = project.service()
    }
}
