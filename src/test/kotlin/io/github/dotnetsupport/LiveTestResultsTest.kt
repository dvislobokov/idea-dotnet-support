package io.github.dotnetsupport

import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.Printable
import com.intellij.execution.testframework.Printer
import com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.run.DotNetConfigurationType
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.testing.DotNetTestConsoleProperties
import io.github.dotnetsupport.testing.LiveEventsTail
import io.github.dotnetsupport.testing.LiveTestEvent
import io.github.dotnetsupport.testing.LiveTestLogger
import io.github.dotnetsupport.testing.LiveTestTree
import io.github.dotnetsupport.testing.TrxEventsConverter
import io.github.dotnetsupport.testing.TrxParser
import java.io.File

/**
 * Live results of `dotnet test` on the files of the plugin's test logger (`testlogger/`), saved from real runs (SDK 10.0.401):
 * `xunit` — `debug-playground/Tests` (xUnit 2.9, `LiveResultsTests` + `PricingTests`), `xunit-stopped` — the same run killed during
 * `Slow3`, `xunit-stopped-batch` — killed during `Slow3` before the logger got the batch with `Slow2` (the collector wrote its end), `nunit` (NUnit 4.3 + adapter 5.0) and `mstest` (MSTest 4.0 through VSTest) — temporary projects with the same tests.
 */
class LiveTestResultsTest : BasePlatformTestCase() {
    private fun lines(run: String, file: String): List<String> = javaClass.getResource("/testing/$run/$file")?.readText()?.lines()?.filter { it.isNotBlank() }.orEmpty()
    private fun events(run: String, file: String) = lines(run, file).mapNotNull { LiveTestEvent.parse(it) }
    private fun trx(run: String) = javaClass.getResource("/testing/$run/results.trx")?.readText()?.let { TrxParser.parse(it) }.orEmpty()

    fun testEventsOfTheLogger() {
        val results = events("xunit", "logger.jsonl").filter { it.kind == "result" }
        assertEquals(9, results.size)
        val theory = results.first { it.displayName!!.contains("discount: 0") }
        assertEquals("Playground.Tests.PricingTests", theory.className)
        assertEquals("DiscountBounds", theory.methodName)
        assertEquals("DiscountBounds(discount: 0, expected: 21)", theory.nameInClass(theory.displayName))
        val failed = results.single { it.outcome == "Failed" }
        assertTrue(failed.message!!, failed.message!!.startsWith("Assert.Equal() Failure: Values differ\nExpected: 2"))
        assertTrue(failed.stackTrace!!, failed.stackTrace!!.contains("LiveResultsTests.cs:line "))
        assertEquals("first line of the test output\nsecond line of the test output\n", results.single { it.methodName == "WritesOutput" }.stdOut)
        assertTrue(results.single { it.methodName == "Slow3" }.durationMs >= 3000)
        assertEquals("Skipped", results.single { it.methodName == "Skipped" }.outcome)

        // NUnit puts the arguments into the full name, and nested classes after '+'
        val nunit = events("nunit", "logger.jsonl").filter { it.kind == "result" }
        assertEquals(setOf("NUnitLive.LiveTests", "NUnitLive.LiveTests+Nested"), nunit.map { it.className }.toSet())
        assertEquals("Doubles(1,2)", nunit.first { it.fullyQualifiedName!!.endsWith("Doubles(1,2)") }.let { it.nameInClass(it.displayName) })
        // MSTest names the managed type and method
        val mstest = events("mstest", "logger.jsonl").filter { it.kind == "result" }
        assertEquals("Doubles", mstest.first { it.displayName == "Doubles (1,2)" }.methodName)
        assertTrue(mstest.single { it.methodName == "WritesOutput" }.stdOut!!.contains("TestContext Messages:\nsecond line"))
        assertNull(LiveTestEvent.parse("{\"event\":"))
    }

    /** Collector first, as [LiveEventsTail] reads it: every test starts, then gets its result; the report adds nothing. */
    fun testTreeOfWholeRuns() {
        for ((run, total) in listOf("xunit" to 9, "nunit" to 8, "mstest" to 7)) {
            val tree = LiveTestTree("C:/src")
            val live = (events(run, "collector.jsonl") + events(run, "logger.jsonl")).flatMap { tree.onEvent(it) }
            assertEquals(run, total, live.count { it.startsWith("##teamcity[testStarted") })
            assertEquals(run, total, live.count { it.startsWith("##teamcity[testFinished") })
            assertEquals(run, total, trx(run).size)
            assertEquals(run, emptyList<String>(), tree.onReport(trx(run)))
            assertTrue(run, tree.finish().all { it.startsWith("##teamcity[testSuiteFinished") })
        }
    }

    fun testTheReportAddsWhatTheLoggerMissed() {
        val tree = LiveTestTree("C:/src")
        val logger = events("xunit", "logger.jsonl")
        tree.onEvent(logger.first { it.kind == "result" && it.outcome == "Passed" })
        val added = tree.onReport(trx("xunit"))
        assertEquals(8, added.count { it.startsWith("##teamcity[testStarted") })
        assertTrue(added.any { it.startsWith("##teamcity[testFailed") && it.contains("Values differ") })
    }

    fun testLiveTreeInTheTestRunner() {
        val root = runConverter("xunit")
        val suites = root.children.associateBy { it.name }
        assertEquals(setOf("Playground.Tests.LiveResultsTests", "Playground.Tests.PricingTests"), suites.keys)
        val tests = suites.getValue("Playground.Tests.LiveResultsTests").children.associateBy { it.name }
        assertEquals(setOf("Slow1", "Slow2", "Slow3", "Fails", "Skipped", "WritesOutput"), tests.keys)
        assertTrue(tests.getValue("Slow1").isPassed)
        assertTrue(tests.getValue("Fails").isDefect)
        assertTrue(tests.getValue("Fails").stacktrace.orEmpty().contains("LiveResultsTests.cs:line"))
        assertTrue(tests.getValue("Skipped").isIgnored)
        assertTrue((tests.getValue("Slow3").duration ?: 0) >= 3000)
        assertEquals("dotnet-test://C:/src/Tests|Playground.Tests.LiveResultsTests|Fails", tests.getValue("Fails").locationUrl)
        assertEquals(3, suites.getValue("Playground.Tests.PricingTests").children.size)
        assertFalse(root.children.any { it.isInProgress })
    }

    /** Stop during `Slow3`: the tests that finished keep their results, the one that ran is not shown as passed. */
    fun testStoppedRun() {
        val root = runConverter("xunit-stopped")
        val tests = root.children.single { it.name == "Playground.Tests.LiveResultsTests" }.children.associateBy { it.name }
        assertEquals(setOf("WritesOutput", "Slow2", "Slow3"), tests.keys)
        assertTrue(tests.getValue("Slow2").isPassed)
        assertFalse(tests.getValue("Slow3").isPassed)
        assertTrue(tests.getValue("Slow3").isInterrupted)
        assertTrue(root.children.single { it.name == "Playground.Tests.PricingTests" }.isPassed)
    }

    fun testTailKeepsALineBeingWritten() {
        val directory = FileUtil.createTempDirectory("events", null, true)
        val file = File(directory, "logger-1.jsonl")
        val tail = LiveEventsTail(directory)
        file.writeText("{\"event\":\"runStart\"}\n{\"event\":\"res")
        assertEquals(listOf("{\"event\":\"runStart\"}"), tail.read())
        file.appendText("ult\",\"fqn\":\"Ä.B\"}\r\n")
        File(directory, "collector-1.jsonl").writeText("{\"event\":\"testStart\"}\n")
        assertEquals(listOf("{\"event\":\"testStart\"}", "{\"event\":\"result\",\"fqn\":\"Ä.B\"}"), tail.read())
        assertEquals(emptyList<String>(), tail.read())
    }

    fun testOptionsGoBeforeTheArgumentsOfTheConfiguration() {
        val options = LiveTestLogger.options(File("C:/cache/testlogger"))
        assertEquals(listOf("--logger", "DotNetSupport", "--test-adapter-path", File("C:/cache/testlogger").path), options)
        assertEquals(
            listOf("test", "A.csproj", "--logger", "trx;LogFileName=results.trx", "--results-directory", "C:/r", "--logger", "DotNetSupport", "--test-adapter-path", "C:/x", "--", "RunConfiguration.X=1"),
            LiveTestLogger.insert(listOf("test", "A.csproj", "--logger", "trx;LogFileName=results.trx", "--results-directory", "C:/r", "--", "RunConfiguration.X=1"), listOf("--logger", "DotNetSupport", "--test-adapter-path", "C:/x")),
        )
        assertEquals(listOf("test", "A.csproj"), LiveTestLogger.insert(listOf("test", "A.csproj"), listOf("--logger", "x")))
    }

    /**
     * The console logger of VSTest prints the results of other tests while one runs ("Skipped …", "Failed …" with a stack trace): the
     * stdout of the process goes to the root of the tree, a test shows only the output that came with its result.
     */
    fun testOutputOfTheProcessGoesToTheRootNotToTheRunningTest() {
        val root = runConverter(null) { converter ->
            fun system(message: String) = converter.process(message + "\n", ProcessOutputTypes.SYSTEM)
            system("##teamcity[testSuiteStarted name='A' nodeId='s' parentNodeId='0']")
            system("##teamcity[testStarted name='Slow1' nodeId='s|Slow1' parentNodeId='s']")
            converter.process("  Skipped Playground.Tests.LiveResultsTests.Skipped [1 ms]\n", ProcessOutputTypes.STDOUT)
            converter.process("  Failed Playground.Tests.LiveResultsTests.Fails [500 ms]\n", ProcessOutputTypes.STDERR)
            system("##teamcity[testStdOut name='Slow1' nodeId='s|Slow1' out='own line|n']")
            system("##teamcity[testFinished name='Slow1' nodeId='s|Slow1']")
            system("##teamcity[testSuiteFinished name='A' nodeId='s']")
        }
        val test = root.children.single().children.single()
        assertEquals("own line\n", printed(test))
        val all = printed(root)
        assertTrue(all, all.contains("Skipped Playground.Tests.LiveResultsTests.Skipped") && all.contains("Failed Playground.Tests.LiveResultsTests.Fails"))
    }

    /** Stop that kills the test host with a batch of results in it: the collector saw the tests end, they keep their outcome. */
    fun testStopKeepsTheOutcomeTheCollectorSaw() {
        val tree = LiveTestTree("C:/src")
        val collector = events("xunit-stopped-batch", "collector.jsonl")
        val logger = events("xunit-stopped-batch", "logger.jsonl")
        assertTrue(collector.any { it.kind == "testEnd" && it.methodName == "Slow2" && it.outcome == "Passed" })
        assertFalse("the logger had not got Slow2", logger.any { it.kind == "result" && it.methodName == "Slow2" })
        val messages = (collector + logger).flatMap { tree.onEvent(it) } + tree.finish()
        fun finished(name: String) = messages.any { it.startsWith("##teamcity[testFinished") && it.contains("|$name'") }
        assertTrue(finished("Slow2"))
        assertFalse("Slow3 was cut off", finished("Slow3"))

        val root = runConverter("xunit-stopped-batch")
        val tests = root.children.single { it.name == "Playground.Tests.LiveResultsTests" }.children.associateBy { it.name }
        assertTrue(tests.getValue("Slow2").isPassed)
        assertTrue(tests.getValue("Slow3").isInterrupted)
    }

    /** A result that comes after the end of its test keeps its details: the end alone is only for a run cut off. */
    fun testTheResultWinsOverTheEnd() {
        val tree = LiveTestTree("C:/src")
        fun event(kind: String, extra: String) = LiveTestEvent.parse("""{"event":"$kind","fqn":"A.B.Fails","displayName":"A.B.Fails","source":"t.dll"$extra}""")!!
        val messages = listOf(event("testStart", ""), event("testEnd", ""","outcome":"Failed""""), event("result", ""","outcome":"Failed","message":"Values differ""""))
            .flatMap { tree.onEvent(it) } + tree.finish()
        assertTrue(messages.single { it.startsWith("##teamcity[testFailed") }.contains("Values differ"))
        assertEquals(1, messages.count { it.startsWith("##teamcity[testFinished") })
    }

    private fun printed(test: SMTestProxy): String {
        val text = StringBuilder()
        test.printOn(object : Printer {
            override fun print(s: String, contentType: ConsoleViewContentType) {
                text.append(s)
            }

            override fun onNewAvailable(printable: Printable) = printable.printOn(this)
            override fun printHyperlink(s: String, info: HyperlinkInfo?) {
                text.append(s)
            }

            override fun mark() {}
        })
        return text.toString()
    }

    /** The converter of the console fed with the saved files (and [feed] while the process runs), as at the end of a run, into the tree of the platform. */
    private fun runConverter(run: String?, feed: (TrxEventsConverter) -> Unit = {}): SMTestProxy.SMRootTestProxy {
        val events = FileUtil.createTempDirectory("events", null, true)
        val results = FileUtil.createTempDirectory("results", null, true)
        for (name in listOf("logger.jsonl", "collector.jsonl")) run?.let { javaClass.getResource("/testing/$it/$name") }?.readText()?.let { File(events, name).writeText(it) }
        run?.let { javaClass.getResource("/testing/$it/results.trx") }?.readText()?.let { File(results, "results.trx").writeText(it) }

        val settings = RunManager.getInstance(project).createConfiguration("live", DotNetConfigurationType.instance.factory)
        (settings.configuration as DotNetRunConfiguration).options.projectPath = "C:/src/Tests/Tests.csproj"
        val properties = DotNetTestConsoleProperties(settings.configuration as DotNetRunConfiguration, DefaultRunExecutor.getRunExecutorInstance(), results, events)
        val converter = properties.createTestEventsConverter("DotNetTest", properties) as TrxEventsConverter
        val root = SMTestProxy.SMRootTestProxy()
        val processor = GeneralIdBasedToSMTRunnerEventsConvertor(project, root, "DotNetTest")
        converter.setProcessor(processor)
        try {
            processor.onStartTesting()
            feed(converter)
            converter.flushBufferOnProcessTermination(0)
            processor.onFinishTesting()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        } finally {
            converter.dispose()
            processor.dispose()
        }
        return root
    }
}
