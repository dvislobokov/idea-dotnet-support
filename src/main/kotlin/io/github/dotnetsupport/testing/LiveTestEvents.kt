package io.github.dotnetsupport.testing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import io.github.dotnetsupport.cli.DotNetHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Results while `dotnet test` (VSTest) runs, as in Rider: the plugin carries the source of a VSTest logger and a data collector
 * (`testlogger/` of the repository), builds them once per machine, and passes them to `dotnet test`. They write JSON lines into
 * files of a directory given in [DIRECTORY_VARIABLE]: the collector the start of every test, the logger every result with its
 * duration, message, stack trace and output. The test tree reads the files as they grow ([LiveEventsTail]) and turns the events
 * into service messages ([LiveTestTree]); the TRX report at the end adds whatever the files missed.
 */
object LiveTestLogger {
    const val DIRECTORY_VARIABLE = "DOTNET_SUPPORT_TEST_EVENTS"
    const val LOGGER = "DotNetSupport"
    const val COLLECTOR = "DotNetSupport.TestEvents"
    private const val COLLECTOR_DLL = "DotNetSupport.TestEventsCollector.dll"

    val HELPER = DotNetHelper("testlogger", "DotNetSupport.TestLogger", "HelperFramework", listOf("TestLogger.cs"), library = "netstandard2.0")

    /**
     * The folder with the logger for `--test-adapter-path`, or null: this run then gets its results from TRX at the end. Built the
     * first time on this machine under a modal progress (seconds, and a restore of `Microsoft.TestPlatform.ObjectModel`), so the very
     * first run is live too; a failed build is remembered for the session and logged by [DotNetHelper].
     */
    fun adapterDirectory(project: Project): File? {
        HELPER.existing()?.let { return it.parentFile }
        val application = ApplicationManager.getApplication()
        if (HELPER.failure != null || application.isUnitTestMode) return null
        val dll = if (!application.isDispatchThread) HELPER.ensureBuilt()
        else ProgressManager.getInstance().runProcessWithProgressSynchronously<File?, Exception>({ HELPER.ensureBuilt() }, "Preparing live test results", false, project)
        return dll?.parentFile
    }

    /** The options of the logger and of the collector, which only works when the second assembly is there. */
    fun options(adapterDirectory: File): List<String> =
        listOf("--logger", LOGGER, "--test-adapter-path", adapterDirectory.path) + if (File(adapterDirectory, COLLECTOR_DLL).isFile) listOf("--collect", COLLECTOR) else emptyList()

    /**
     * [options] put after the options of `dotnet test` the plugin makes ([TestingPlatform.arguments]: they end with the results
     * directory) and before the arguments of the configuration, which may end with `-- <inline run settings>`.
     */
    fun insert(arguments: List<String>, options: List<String>): List<String> {
        val index = arguments.indexOf("--results-directory").takeIf { it >= 0 && it + 1 < arguments.size } ?: return arguments
        return arguments.subList(0, index + 2) + options + arguments.subList(index + 2, arguments.size)
    }
}

/** One line of a file of the logger or of the collector, see `testlogger/TestLogger.cs`. */
class LiveTestEvent(
    /** `runStart`, `testStart`, `testEnd`, `result`, `message`, `runComplete`. */
    val kind: String,
    /** The id of the test case; one test case may have several results (data rows the adapter does not expand). */
    val id: String?,
    val fullyQualifiedName: String?,
    val displayName: String?,
    /** `TestCase.ManagedType` / `ManagedMethod`, which MSTest sets and xUnit 2 and NUnit do not. */
    val managedType: String?,
    val managedMethod: String?,
    val source: String?,
    val outcome: String?,
    val resultName: String?,
    val durationMs: Long,
    val message: String?,
    val stackTrace: String?,
    val stdOut: String?,
    val stdErr: String?,
) {
    /** The class as TRX names it: the managed type, or the name before the method (and its arguments, which NUnit puts there). */
    val className: String get() = managedType ?: fullyQualifiedName.orEmpty().substringBefore('(').substringBeforeLast('.', "")
    val methodName: String get() = managedMethod?.substringBefore('(') ?: fullyQualifiedName.orEmpty().substringBefore('(').substringAfterLast('.')

    /** The name inside the class, as [TrxParser] makes it of `testName`. */
    fun nameInClass(name: String?): String = name.orEmpty().removePrefix("$className.").ifEmpty { methodName }

    companion object {
        fun parse(line: String): LiveTestEvent? {
            val json = try {
                JsonParser.parseString(line) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            fun text(name: String): String? = json.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            return LiveTestEvent(
                kind = text("event") ?: return null, id = text("id"), fullyQualifiedName = text("fqn"), displayName = text("displayName"),
                managedType = text("type"), managedMethod = text("method"), source = text("source"), outcome = text("outcome"), resultName = text("resultName"),
                durationMs = text("durationMs")?.toLongOrNull() ?: 0, message = text("message")?.normalizeLines()?.trimEnd(),
                stackTrace = text("stackTrace")?.normalizeLines()?.trimEnd(), stdOut = listOfNotNull(text("stdout"), text("info")).joinToString("").normalizeLines().ifEmpty { null },
                stdErr = text("stderr")?.normalizeLines(),
            )
        }

        private fun String.normalizeLines(): String = replace("\r\n", "\n").replace('\r', '\n')
    }
}

/**
 * The lines added to the `*.jsonl` files of a directory since the last [read]. The collector files come first: a start is read
 * before the result of the same test. A line still being written is kept until its end arrives.
 */
class LiveEventsTail(private val directory: File) {
    private val offsets = HashMap<String, Long>()
    private val partial = HashMap<String, ByteArrayOutputStream>()

    fun read(): List<String> = buildList {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val bytes = try {
                RandomAccessFile(file, "r").use { access ->
                    val offset = offsets[file.name] ?: 0L
                    if (access.length() <= offset) return@use null
                    ByteArray((access.length() - offset).toInt()).also { access.seek(offset); access.readFully(it) }
                }
            } catch (_: Exception) {
                null
            } ?: continue
            offsets[file.name] = (offsets[file.name] ?: 0L) + bytes.size
            val buffer = partial.getOrPut(file.name) { ByteArrayOutputStream() }
            var start = 0
            for (i in bytes.indices) {
                if (bytes[i] != '\n'.code.toByte()) continue
                buffer.write(bytes, start, i - start)
                buffer.toString(Charsets.UTF_8).trimEnd('\r').takeIf { it.isNotBlank() }?.let(::add)
                buffer.reset()
                start = i + 1
            }
            buffer.write(bytes, start, bytes.size - start)
        }
    }
}

/**
 * The test tree of one run as service messages of an id-based tree: a suite per test class, created when its first test starts
 * (tests of several classes run at the same time, so they cannot be nested by order). Suites are finished at the end of the run
 * ([finish]); a test that started and got no result stays running, and the platform marks it as terminated when the process ends.
 */
class LiveTestTree(private val projectDirectory: String) {
    private val suites = LinkedHashSet<String>()
    /** Started and not finished: node -> suite. */
    private val running = LinkedHashMap<String, String>()
    private val finished = HashSet<String>()
    private val nodeOfCase = HashMap<String, String>()
    private val casesWithResults = HashSet<String>()
    /** The outcomes the collector saw at the end of tests whose results had not come yet. */
    private val ended = LinkedHashMap<String, Pair<TestNode, TestOutcome>>()

    fun onEvent(event: LiveTestEvent): List<String> = when (event.kind) {
        "testStart" -> buildList {
            val node = TestNode(event.source, event.className, event.methodName, event.nameInClass(event.displayName))
            if (node.id in finished || node.id in running) return@buildList
            started(node)
            event.id?.let { nodeOfCase[it] = node.id }
        }
        // The logger gets results in batches of the test host (up to a second and a half late), the collector the end of every test at once:
        // the outcome is kept for a Stop that kills the host with the batch, and the result with its details wins when it comes.
        "testEnd" -> buildList {
            val node = TestNode(event.source, event.className, event.methodName, event.nameInClass(event.displayName))
            if (node.id in running) ended[node.id] = node to outcome(event.outcome)
        }
        "result" -> buildList {
            val node = TestNode(event.source, event.className, event.methodName, event.nameInClass(event.resultName ?: event.displayName))
            event.id?.let { casesWithResults.add(it) }
            if (node.id in finished) return@buildList
            if (node.id !in running) started(node)
            result(node, outcome(event.outcome), event.durationMs, event.message ?: if (event.outcome == "NotFound") "Not found" else null, event.stackTrace, event.stdOut, event.stdErr)
        }
        else -> emptyList()
    }

    /** The results of the TRX report the files did not have: nothing is lost when the logger missed a test. */
    fun onReport(results: List<TrxTestResult>): List<String> = buildList {
        for (test in results.sortedWith(compareBy({ it.className }, { it.displayName }))) {
            val node = TestNode(test.source, test.className, test.methodName, test.displayName)
            if (node.id in finished) continue
            if (node.id !in running) started(node)
            result(node, test.outcome, test.durationMs, test.message, test.stackTrace, test.stdOut, null)
        }
    }

    /**
     * The end of the run: a test case whose results came under other names (rows of a theory) is finished, then every suite that
     * has no running test. A test the collector saw end gets that outcome without details: its result was still in the test host when
     * Stop killed it. What still runs was cut off by Stop or by a crash of the test host.
     */
    fun finish(): List<String> = buildList {
        for ((case, node) in nodeOfCase) {
            if (case in casesWithResults && node in running) {
                add(ServiceMessageBuilder.testFinished(node).addAttribute("nodeId", node).toString())
                running.remove(node); finished.add(node)
            }
        }
        for ((node, outcome) in ended.values) {
            if (node.id in running) result(node, outcome, 0, if (outcome == TestOutcome.FAILED) "The run was stopped before the details of the failure came" else null, null, null, null)
        }
        ended.clear()
        val busy = running.values.toSet()
        for (suite in suites.filter { it !in busy }) add(ServiceMessageBuilder.testSuiteFinished(suite).addAttribute("nodeId", suite).toString())
        suites.retainAll(busy)
    }

    private fun MutableList<String>.started(node: TestNode) {
        if (suites.add(node.suiteId)) {
            add(ServiceMessageBuilder.testSuiteStarted(node.className).addAttribute("nodeId", node.suiteId).addAttribute("parentNodeId", "0")
                .addAttribute("locationHint", TrxEventsConverter.locationHint(projectDirectory, node.className, null)).toString())
        }
        add(ServiceMessageBuilder.testStarted(node.name).addAttribute("nodeId", node.id).addAttribute("parentNodeId", node.suiteId)
            .addAttribute("locationHint", TrxEventsConverter.locationHint(projectDirectory, node.className, node.methodName)).toString())
        running[node.id] = node.suiteId
    }

    private fun MutableList<String>.result(node: TestNode, outcome: TestOutcome, durationMs: Long, message: String?, stackTrace: String?, stdOut: String?, stdErr: String?) {
        stdOut?.let { add(ServiceMessageBuilder.testStdOut(node.name).addAttribute("nodeId", node.id).addAttribute("out", it.trimEnd() + "\n").toString()) }
        stdErr?.takeIf { it.isNotBlank() }?.let { add(ServiceMessageBuilder.testStdErr(node.name).addAttribute("nodeId", node.id).addAttribute("out", it.trimEnd() + "\n").toString()) }
        when (outcome) {
            TestOutcome.FAILED -> add(ServiceMessageBuilder.testFailed(node.name).addAttribute("nodeId", node.id)
                .addAttribute("message", message.orEmpty()).addAttribute("details", stackTrace.orEmpty()).toString())
            TestOutcome.SKIPPED -> add(ServiceMessageBuilder.testIgnored(node.name).addAttribute("nodeId", node.id).addAttribute("message", message.orEmpty()).toString())
            TestOutcome.PASSED -> {}
        }
        add(ServiceMessageBuilder.testFinished(node.name).addAttribute("nodeId", node.id).addAttribute("duration", durationMs.toString()).toString())
        running.remove(node.id)
        finished.add(node.id)
    }

    private fun outcome(outcome: String?): TestOutcome = when (outcome) {
        "Passed" -> TestOutcome.PASSED
        "Failed" -> TestOutcome.FAILED
        else -> TestOutcome.SKIPPED // Skipped, None, NotFound
    }

    /** A test of one assembly: the same class of two target frameworks is two suites. */
    private class TestNode(source: String?, val className: String, val methodName: String, val name: String) {
        private val assembly = source.orEmpty().replace('\\', '/').lowercase()
        val suiteId = "$assembly|$className"
        val id = "$suiteId|$name"
    }
}
