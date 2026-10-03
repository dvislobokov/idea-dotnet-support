package io.github.dotnetsupport.testing

import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.events.TestOutputEvent
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.run.DotNetRunConfiguration
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import jetbrains.buildServer.messages.serviceMessages.TestStdOut

const val TEST_FRAMEWORK_NAME = "DotNetTest"
private const val LOCATION_PROTOCOL = "dotnet-test"

/**
 * Test tree for `dotnet test`. VSTest has no portable live protocol on stdout (the console logger is localized): with the logger
 * of the plugin ([LiveTestLogger], [eventsDirectory]) the tree fills while the tests run, and the TRX report at the end adds what
 * it missed; without it (Microsoft.Testing.Platform, or a logger that could not be built) the tree is built from the report alone.
 */
class DotNetTestConsoleProperties(
    private val configuration: DotNetRunConfiguration,
    executor: Executor,
    private val resultsDirectory: File,
    private val eventsDirectory: File? = null,
) : SMTRunnerConsoleProperties(configuration, TEST_FRAMEWORK_NAME, executor), SMCustomMessagesParsing {

    init {
        // tests of several classes finish in any order: nodes are found by id, not by nesting
        isIdBasedTestTree = true
        // The platform hides passed tests until "Show Passed" is pressed: a green run would look like an empty tree.
        // Only defaults: the toolbar toggles still work and are remembered.
        setIfUndefined(TestConsoleProperties.HIDE_PASSED_TESTS, false)
        setIfUndefined(TestConsoleProperties.HIDE_IGNORED_TEST, false)
    }

    override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter =
        TrxEventsConverter(testFrameworkName, consoleProperties, resultsDirectory, File(configuration.options.projectPath.orEmpty()).parent.orEmpty(), eventsDirectory)

    override fun getTestLocator(): SMTestLocator = DotNetTestLocator

    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
        RerunFailedDotNetTestsAction(consoleView as ComponentContainer, this, configuration)
}

/**
 * Turns the files of the test logger into test events while `dotnet test` runs ([eventsDirectory], read every [POLL_MS]), and the
 * TRX report into the events of the tests the files did not have once it has finished. The messages go in as SYSTEM output, which
 * the platform splits into lines apart from the stdout of the process, so a line the process is still writing does not glue to them.
 *
 * The output of a test comes only with its result ([LiveTestTree]). The stdout of `dotnet test` itself (build, the console logger
 * of VSTest that prints the results of other tests in batches, the summary) belongs to the run: the platform would give it to the test it
 * thinks is running ([fireOnUncapturedOutput]), so it goes to the root of the tree instead.
 */
class TrxEventsConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
    private val resultsDirectory: File,
    private val projectDirectory: String,
    eventsDirectory: File? = null,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {
    private val tree = LiveTestTree(projectDirectory)
    private val tail = eventsDirectory?.let(::LiveEventsTail)
    @Volatile private var polling: ScheduledFuture<*>? = null
    @Volatile private var terminated = false

    override fun onStartTesting() {
        super.onStartTesting()
        if (tail != null) polling = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ poll() }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
    }

    private fun poll() = synchronized(this) {
        if (terminated) return
        try {
            for (line in tail?.read().orEmpty()) LiveTestEvent.parse(line)?.let { send(tree.onEvent(it)) }
        } catch (e: Exception) {
            PluginLog.warn("tests", "Live test results", e)
        }
    }

    override fun flushBufferOnProcessTermination(exitCode: Int) {
        polling?.cancel(false)
        poll()
        synchronized(this) {
            terminated = true
            val results = resultsDirectory.walkTopDown().filter { it.isFile && it.extension.equals("trx", ignoreCase = true) }.flatMap { TrxParser.parse(it.readText()) }.toList()
            send(tree.onReport(results))
            send(tree.finish())
        }
        super.flushBufferOnProcessTermination(exitCode)
    }

    /** Not to the "active" test of the platform (the first one still running): to the root node, which the id-based tree calls "0". */
    override fun fireOnUncapturedOutput(text: String, outputType: Key<*>) {
        processor?.onTestOutput(TestOutputEvent(ROOT_OUTPUT, text, outputType))
    }

    override fun dispose() {
        polling?.cancel(false)
        super.dispose()
    }

    private fun send(messages: List<String>) {
        for (message in messages) process(message + "\n", ProcessOutputTypes.SYSTEM)
    }

    companion object {
        private const val POLL_MS = 200L
        /** Only names the node; the text and its type (colors of the console) are given apart. */
        private val ROOT_OUTPUT = ServiceMessage.parse(ServiceMessageBuilder.testStdOut("").addAttribute("nodeId", "0").addAttribute("out", "").toString()) as TestStdOut

        /** The tree of a run that has only the TRX report: a suite per test class. */
        fun serviceMessages(results: List<TrxTestResult>, projectDirectory: String): List<String> =
            LiveTestTree(projectDirectory).run { onReport(results) + finish() }

        fun locationHint(projectDirectory: String, className: String, methodName: String?): String =
            "$LOCATION_PROTOCOL://${projectDirectory.replace('\\', '/')}|$className|${methodName.orEmpty()}"
    }
}

/** Double click on a test: the class or method is looked up in the sources of the test project. */
object DotNetTestLocator : SMTestLocator {
    private val SKIPPED_DIRECTORIES = setOf("bin", "obj", ".git", ".vs", ".idea", "node_modules")

    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != LOCATION_PROTOCOL) return emptyList()
        val (directory, className, methodName) = path.split('|').takeIf { it.size == 3 } ?: return emptyList()
        val root = LocalFileSystem.getInstance().findFileByPath(directory) ?: return emptyList()
        val (file, offset) = findSource(root, className, methodName.ifEmpty { null }) ?: return emptyList()
        val element = PsiManager.getInstance(project).findFile(file)?.findElementAt(offset) ?: return emptyList()
        return listOf(PsiLocation(element))
    }

    fun findSource(root: VirtualFile, className: String, methodName: String?): Pair<VirtualFile, Int>? {
        val simpleName = className.substringAfterLast('.').substringAfterLast('+')
        var found: Pair<VirtualFile, Int>? = null
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (found != null) return false
                if (file.isDirectory) return file.name.lowercase() !in SKIPPED_DIRECTORIES
                if (!file.extension.equals("cs", ignoreCase = true)) return true
                val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return true
                if (!text.contains(simpleName)) return true
                // the method, or the class when the method is inherited or generated
                val target = TestDiscovery.find(text, className, methodName) ?: TestDiscovery.find(text, className, null)
                if (target != null) found = file to target.nameRange.startOffset
                return true
            }
        })
        return found
    }
}

/** Runs only the tests that failed, with a `--filter` built from their fully qualified names. */
class RerunFailedDotNetTestsAction(
    container: ComponentContainer,
    properties: DotNetTestConsoleProperties,
    private val configuration: DotNetRunConfiguration,
) : AbstractRerunFailedTestsAction(container) {

    init {
        init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile = object : MyRunProfile(configuration) {
        override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
            val rerun = configuration.clone() as DotNetRunConfiguration
            rerun.options.testFilter = failedTestsFilter(getFailedTests(configuration.project))
            return rerun.getState(executor, environment)
        }
    }

    companion object {
        fun failedTestsFilter(failed: List<AbstractTestProxy>): String =
            failed.filter { it.isLeaf }
                .mapNotNull { test -> test.locationUrl?.substringAfter("://", "")?.split('|')?.takeIf { it.size == 3 && it[2].isNotEmpty() } }
                .map { (_, className, methodName) -> "FullyQualifiedName~" + TestTarget.escape("$className.$methodName") }
                .distinct()
                .joinToString("|")
    }
}
