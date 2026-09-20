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
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import io.github.dotnetsupport.run.DotNetRunConfiguration
import java.io.File

const val TEST_FRAMEWORK_NAME = "DotNetTest"
private const val LOCATION_PROTOCOL = "dotnet-test"

/**
 * Test tree for `dotnet test`. VSTest has no portable live protocol on stdout (the console logger is localized), so the
 * console shows the output while the tests run and the tree is built from the TRX report when the process ends.
 */
class DotNetTestConsoleProperties(
    private val configuration: DotNetRunConfiguration,
    executor: Executor,
    private val resultsDirectory: File,
) : SMTRunnerConsoleProperties(configuration, TEST_FRAMEWORK_NAME, executor), SMCustomMessagesParsing {

    init {
        isIdBasedTestTree = false
        // The platform hides passed tests until "Show Passed" is pressed: a green run would look like an empty tree.
        // Only defaults: the toolbar toggles still work and are remembered.
        setIfUndefined(TestConsoleProperties.HIDE_PASSED_TESTS, false)
        setIfUndefined(TestConsoleProperties.HIDE_IGNORED_TEST, false)
    }

    override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter =
        TrxEventsConverter(testFrameworkName, consoleProperties, resultsDirectory, File(configuration.options.projectPath.orEmpty()).parent.orEmpty())

    override fun getTestLocator(): SMTestLocator = DotNetTestLocator

    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
        RerunFailedDotNetTestsAction(consoleView as ComponentContainer, this, configuration)
}

/** Replays the TRX report as test events once `dotnet test` has finished. */
class TrxEventsConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
    private val resultsDirectory: File,
    private val projectDirectory: String,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

    override fun flushBufferOnProcessTermination(exitCode: Int) {
        val report = resultsDirectory.walkTopDown().firstOrNull { it.isFile && it.extension.equals("trx", ignoreCase = true) }
        if (report != null) {
            for (message in serviceMessages(TrxParser.parse(report.readText()), projectDirectory)) {
                process(message + "\n", ProcessOutputTypes.STDOUT)
            }
        }
        super.flushBufferOnProcessTermination(exitCode)
    }

    companion object {
        /** TeamCity service messages: a suite per test class, nested by namespace-qualified name. */
        fun serviceMessages(results: List<TrxTestResult>, projectDirectory: String): List<String> = buildList {
            for ((className, tests) in results.groupBy { it.className }.toSortedMap()) {
                add(ServiceMessageBuilder.testSuiteStarted(className).addAttribute("locationHint", locationHint(projectDirectory, className, null)).toString())
                for (test in tests.sortedBy { it.displayName }) {
                    val name = test.displayName
                    add(ServiceMessageBuilder.testStarted(name).addAttribute("locationHint", locationHint(projectDirectory, className, test.methodName)).toString())
                    test.stdOut?.let { add(ServiceMessageBuilder.testStdOut(name).addAttribute("out", it.trimEnd() + "\n").toString()) }
                    when (test.outcome) {
                        TestOutcome.FAILED -> add(
                            ServiceMessageBuilder.testFailed(name)
                                .addAttribute("message", test.message.orEmpty())
                                .addAttribute("details", test.stackTrace.orEmpty())
                                .toString()
                        )
                        TestOutcome.SKIPPED -> add(ServiceMessageBuilder.testIgnored(name).addAttribute("message", test.message.orEmpty()).toString())
                        TestOutcome.PASSED -> {}
                    }
                    add(ServiceMessageBuilder.testFinished(name).addAttribute("duration", test.durationMs.toString()).toString())
                }
                add(ServiceMessageBuilder.testSuiteFinished(className).toString())
            }
        }

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
