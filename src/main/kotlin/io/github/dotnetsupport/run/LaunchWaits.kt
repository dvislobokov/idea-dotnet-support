package io.github.dotnetsupport.run

import com.intellij.execution.ExecutionListener
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.PluginLog
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * "Wait for" of a ".NET Project" configuration: its launch waits until another configuration has started, listens on its address, or
 * answers on a health URL, as `WaitFor` of .NET Aspire. It lives in the configuration, not in the compound: an entry of the platform's
 * compound configuration is a type, a name and a target ([com.intellij.execution.compound.TypeNameTarget]), with no room for options,
 * and a configuration that waits for its dependency should do so however it is started (a compound, Run N Projects, the toolbar).
 */
enum class LaunchWaitCondition(private val title: String) {
    STARTED("has started"),
    LISTENING("listens on its address"),
    HEALTHY("answers on the health URL");

    override fun toString(): String = title
}

object LaunchWaits {
    const val DEFAULT_TIMEOUT_SECONDS = 60

    /**
     * Where the configuration waited for listens: the address it has printed ("Now listening on"), else `applicationUrl` of its launch
     * profile. `http` first: a check of `https` would need the development certificate trusted by the IDE.
     */
    fun address(applicationUrls: List<String>, listening: String?): String? {
        val all = listOfNotNull(listening) + applicationUrls
        return all.firstOrNull { it.startsWith("http://", ignoreCase = true) } ?: all.firstOrNull()
    }

    /** [healthUrl] as is when it is absolute, else a path on [address]; empty: the address itself. */
    fun healthUrl(healthUrl: String?, address: String?): String? {
        val url = healthUrl?.trim().orEmpty()
        if (url.contains("://")) return url
        val base = address?.let { local(it) }?.trimEnd('/') ?: return null
        return if (url.isEmpty()) base else "$base/${url.trimStart('/')}"
    }

    /** Host and port to connect to: `*`, `+` and the "any" addresses an application listens on are reached at localhost. */
    fun endpoint(url: String): Pair<String, Int>? {
        val match = ENDPOINT.find(url.trim()) ?: return null
        val scheme = match.groupValues[1].lowercase()
        val host = match.groupValues[2].removePrefix("[").removeSuffix("]").let { if (it in ANY_HOST) "localhost" else it }
        val port = match.groupValues[3].toIntOrNull() ?: when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> return null
        }
        return host to port
    }

    /** The configurations a chain of "Wait for" from [start] goes through when it comes back to one of them; null when it ends. */
    fun cycle(start: String, waitsFor: (String) -> String?): List<String>? {
        val chain = mutableListOf(start)
        var next = waitsFor(start)
        while (next != null) {
            if (next in chain) return chain.subList(chain.indexOf(next), chain.size) + next
            chain += next
            next = waitsFor(next)
        }
        return null
    }

    fun waitingText(dependency: String, condition: LaunchWaitCondition, url: String?): String = when (condition) {
        LaunchWaitCondition.STARTED -> "Waiting for '$dependency' to start"
        LaunchWaitCondition.LISTENING -> "Waiting for '$dependency' to listen" + url?.let { " on $it" }.orEmpty()
        LaunchWaitCondition.HEALTHY -> "Waiting for '$dependency' to answer" + url?.let { " on $it" }.orEmpty()
    }

    fun timeoutText(waiter: String, dependency: String, condition: LaunchWaitCondition, url: String?, seconds: Int, running: Boolean): String {
        val what = when (condition) {
            LaunchWaitCondition.STARTED -> "has not started"
            LaunchWaitCondition.LISTENING -> "does not listen" + url?.let { " on $it" }.orEmpty()
            LaunchWaitCondition.HEALTHY -> url?.let { "does not answer on $it" } ?: "has no address to check (set the health URL)"
        }
        val state = if (running || condition == LaunchWaitCondition.STARTED) "" else " (it is not running)"
        return "'$dependency' $what$state after $seconds s, so '$waiter' was not started."
    }

    private fun local(url: String): String = url.replace(Regex("""://(\*|\+|0\.0\.0\.0|\[::])"""), "://localhost")

    private val ENDPOINT = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*)://(\[[^\]]+]|[^/:?#]+)(?::(\d+))?""")
    private val ANY_HOST = setOf("*", "+", "0.0.0.0", "::")
}

/** The processes of the run configurations of the project that are alive, by the name of the configuration: what "Wait for" looks at. */
@Service(Service.Level.PROJECT)
class RunningLaunches {
    private class Launch(val settings: RunnerAndConfigurationSettings?, val handler: ProcessHandler)

    private val launches = ConcurrentHashMap<String, Launch>()

    fun handler(name: String): ProcessHandler? = launches[name]?.handler?.takeIf { !it.isProcessTerminated }

    /** The ".NET Project" configurations that are running now, in the order they were started. */
    fun runningDotNet(): List<RunnerAndConfigurationSettings> =
        launches.values.filter { !it.handler.isProcessTerminated }.mapNotNull { it.settings?.takeIf { s -> s.configuration is DotNetRunConfiguration } }

    fun started(name: String, settings: RunnerAndConfigurationSettings?, handler: ProcessHandler) {
        launches[name] = Launch(settings, handler)
    }

    class Listener(private val project: Project) : ExecutionListener {
        override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
            val settings = env.runnerAndConfigurationSettings
            getInstance(project).started(settings?.name ?: env.runProfile.name, settings, handler)
        }
    }

    companion object {
        fun getInstance(project: Project): RunningLaunches = project.service()
    }
}

/** Waits as the "Wait for" of a configuration says; blocking, for the thread of the step before the launch. */
object LaunchWait {
    private const val POLL_MILLIS = 300L

    /** True: the launch goes on. False: it does not, and the user has been told why. */
    fun await(configuration: DotNetRunConfiguration, environment: ExecutionEnvironment): Boolean {
        val options = configuration.options
        val dependency = options.waitFor?.trim()?.ifEmpty { null } ?: return true
        val project = configuration.project
        val runManager = RunManager.getInstance(project)
        fun fail(text: String): Boolean = false.also {
            PluginLog.warn(DotNetDebugBuild.LOG_CATEGORY, text)
            DotNetCli.notifyError(project, "'${configuration.name}' Was Not Started", text)
        }
        val target = runManager.allSettings.firstOrNull { it.name == dependency }
            ?: return fail("'${configuration.name}' waits for '$dependency', and there is no run configuration with that name.")
        LaunchWaits.cycle(configuration.name) { name -> (runManager.allSettings.firstOrNull { it.name == name }?.configuration as? DotNetRunConfiguration)?.options?.waitFor?.trim()?.ifEmpty { null } }
            ?.let { return fail("The configurations wait for each other: ${it.joinToString(" → ")}.") }

        val condition = options.waitCondition
        val seconds = options.waitTimeoutSeconds.takeIf { it > 0 } ?: LaunchWaits.DEFAULT_TIMEOUT_SECONDS
        val running = RunningLaunches.getInstance(project)
        val profileUrls = (target.configuration as? DotNetRunConfiguration)?.launchProfile()?.applicationUrls.orEmpty()
        fun address(): String? = LaunchWaits.address(profileUrls, running.handler(dependency)?.getUserData(ListeningAddressRecorder.KEY))
        fun url(): String? = if (condition == LaunchWaitCondition.HEALTHY) LaunchWaits.healthUrl(options.waitHealthUrl, address()) else address()

        val result = CompletableFuture<String?>() // null: ready, else why not
        val task = object : Task.Backgroundable(project, "${configuration.name}: ${LaunchWaits.waitingText(dependency, condition, url())}", true) {
            override fun run(indicator: ProgressIndicator) {
                val deadline = System.currentTimeMillis() + seconds * 1000L
                while (true) {
                    if (ready(condition, running.handler(dependency), url())) {
                        result.complete(null)
                        return
                    }
                    if (System.currentTimeMillis() > deadline) {
                        result.complete(LaunchWaits.timeoutText(configuration.name, dependency, condition, url(), seconds, running.handler(dependency) != null))
                        return
                    }
                    indicator.text = LaunchWaits.waitingText(dependency, condition, url())
                    indicator.text2 = "'${configuration.name}' starts after it; ${(deadline - System.currentTimeMillis()) / 1000} s left"
                    indicator.checkCanceled()
                    Thread.sleep(POLL_MILLIS)
                }
            }

            override fun onCancel() {
                result.complete("")
            }

            override fun onThrowable(error: Throwable) {
                result.complete("Waiting for '$dependency' has failed: ${error.message}")
            }
        }
        PluginLog.info(DotNetDebugBuild.LOG_CATEGORY, "${configuration.name} (${environment.executionId}): ${LaunchWaits.waitingText(dependency, condition, url())}, up to $seconds s")
        ApplicationManager.getApplication().invokeLater({ task.queue() }, ModalityState.any())
        val why = result.get() ?: return true.also { PluginLog.info(DotNetDebugBuild.LOG_CATEGORY, "${configuration.name}: '$dependency' is ready") }
        return if (why.isEmpty()) false else fail(why)
    }

    private fun ready(condition: LaunchWaitCondition, handler: ProcessHandler?, url: String?): Boolean = when (condition) {
        LaunchWaitCondition.STARTED -> handler != null
        LaunchWaitCondition.LISTENING -> handler?.getUserData(ListeningAddressRecorder.KEY) != null || url != null && accepts(url)
        LaunchWaitCondition.HEALTHY -> url != null && healthy(url)
    }

    private fun accepts(url: String): Boolean {
        val (host, port) = LaunchWaits.endpoint(url) ?: return false
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 500) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    private fun healthy(url: String): Boolean = try {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(2)).GET().build()
        http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
    } catch (_: Exception) {
        false
    }
}
