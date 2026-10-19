package io.github.dotnetsupport.testing

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.autotest.ToggleAutoTestAction
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.coverage.DotNetCoverageService
import io.github.dotnetsupport.run.DotNetProcessAttacher
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.run.TestHostDebug
import io.github.dotnetsupport.run.TestingPlatformDebug
import java.io.File

/** `dotnet test` with the test tree instead of a plain console. */
class DotNetTestRunState(private val configuration: DotNetRunConfiguration, environment: ExecutionEnvironment, private val debug: Boolean = false) :
    CommandLineState(environment) {
    // TRX report and coverage files of this run
    private val resultsDirectory = io.github.dotnetsupport.cli.PluginData.tempDirectory("dotnet-test")
    // the files of the live test logger, when it is there (VSTest only)
    private var eventsDirectory: File? = null

    override fun startProcess(): ProcessHandler {
        val commandLine = configuration.buildCommandLine(resultsDirectory)
        val vsTest = configuration.testMode() == TestMode.VSTEST
        val adapterDirectory = if (vsTest) LiveTestLogger.adapterDirectory(configuration.project) else null
        if (adapterDirectory != null) {
            val events = io.github.dotnetsupport.cli.PluginData.tempDirectory("dotnet-test-events").also { eventsDirectory = it }
            val arguments = LiveTestLogger.insert(commandLine.parametersList.list, LiveTestLogger.options(adapterDirectory))
            commandLine.parametersList.clearAll()
            commandLine.addParameters(arguments)
            commandLine.withEnvironment(LiveTestLogger.DIRECTORY_VARIABLE, events.path)
        }
        // The test host prints its process id and waits for a debugger. In English: the line is found by its words first.
        if (debug && vsTest) commandLine.withEnvironment(TestHostDebug.VARIABLE, "1").withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en").withEnvironment("VSTEST_UI_LANGUAGE", "en")
        if (debug && !vsTest) commandLine.withEnvironment(TestingPlatformDebug.VARIABLE, "1")
        val handler = KillableColoredProcessHandler(commandLine)
        if (debug && vsTest) attachDebuggerToTestHosts(handler)
        if (debug && !vsTest) attachDebuggerToTestApplications(handler)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    /** A run may start several hosts (projects, target frameworks): each one is attached to, once. */
    private fun attachDebuggerToTestHosts(handler: ProcessHandler) {
        val attacher = DotNetProcessAttacher.find() ?: return
        val attached = HashSet<Long>()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                val processId = TestHostDebug.processId(event.text) ?: return
                if (attached.add(processId)) attacher.attach(configuration.project, processId, "Tests of ${configuration.name}", skipInitialBreak = true)
            }
        })
    }

    /**
     * Microsoft.Testing.Platform: the test applications `dotnet test` starts wait for a debugger ([TestingPlatformDebug]); they are looked
     * for among its descendants while it runs, each attached to once. The process list is asked only for a new descendant (its command
     * line: the arguments of a process are not in [ProcessHandle] on Windows).
     */
    private fun attachDebuggerToTestApplications(handler: KillableColoredProcessHandler) {
        val attacher = DotNetProcessAttacher.find() ?: return
        val names = testAssemblyNames()
        val seen = HashSet<Long>()
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            while (!handler.isProcessTerminated && !configuration.project.isDisposed) {
                val fresh = runCatching { handler.process.toHandle().descendants().map { it.pid() }.toList() }.getOrDefault(emptyList()).filter { it !in seen }
                if (fresh.isNotEmpty()) {
                    val lines = com.intellij.execution.process.impl.ProcessListUtil.getProcessList().associate { it.pid.toLong() to it.commandLine }
                    for (pid in fresh) {
                        val line = lines[pid] ?: continue
                        seen.add(pid)
                        // the platform greets its debugger with `Debugger.Break()` too (seen live): not the user's stop
                        if (TestingPlatformDebug.isTestApplication(line, names)) attacher.attach(configuration.project, pid, "Tests of ${configuration.name}", skipInitialBreak = true)
                    }
                }
                Thread.sleep(300)
            }
        }
    }

    /** The assemblies of the test projects of the configuration: its project, or those of its solution. */
    private fun testAssemblyNames(): Set<String> {
        val path = configuration.options.projectPath.orEmpty()
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) ?: return emptySet()
        if (file.isDirectory || !file.name.endsWith("proj", ignoreCase = true)) return emptySet()
        val options = runCatching { io.github.dotnetsupport.msbuild.CompilationModel.getInstance(configuration.project).options(file) }.getOrNull()
        return setOf(options?.assemblyName?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension)
    }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = startProcess()
        val properties = DotNetTestConsoleProperties(configuration, executor, resultsDirectory, eventsDirectory)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(TEST_FRAMEWORK_NAME, handler, properties)

        if (configuration.options.collectCoverage) {
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) =
                    DotNetCoverageService.getInstance(configuration.project).loadResults(resultsDirectory, configuration.name)
            })
        }

        val rerunFailed = properties.createRerunFailedTestsAction(console)
        rerunFailed.setModelProvider { (console as SMTRunnerConsoleView).resultsViewer }
        return DefaultExecutionResult(console, handler, *createActions(console, handler, executor)).apply {
            setRestartActions(rerunFailed, ToggleAutoTestAction())
        }
    }
}
