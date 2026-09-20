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
import com.intellij.openapi.util.io.FileUtil
import io.github.dotnetsupport.coverage.DotNetCoverageService
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.run.DotNetStackTraceFilter
import io.github.dotnetsupport.run.MsBuildConsoleFilter

/** `dotnet test` with the test tree instead of a plain console. */
class DotNetTestRunState(private val configuration: DotNetRunConfiguration, environment: ExecutionEnvironment) : CommandLineState(environment) {
    // TRX report and coverage files of this run
    private val resultsDirectory = FileUtil.createTempDirectory("dotnet-test", null, true)

    override fun startProcess(): ProcessHandler {
        val handler = KillableColoredProcessHandler(configuration.buildCommandLine(resultsDirectory))
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = startProcess()
        val properties = DotNetTestConsoleProperties(configuration, executor, resultsDirectory)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(TEST_FRAMEWORK_NAME, handler, properties)
        console.addMessageFilter(MsBuildConsoleFilter(configuration.project))
        console.addMessageFilter(DotNetStackTraceFilter(configuration.project))

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
