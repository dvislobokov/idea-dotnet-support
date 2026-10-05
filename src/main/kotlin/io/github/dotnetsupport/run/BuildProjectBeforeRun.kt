package io.github.dotnetsupport.run

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.build.DotNetBuildCommand
import io.github.dotnetsupport.build.DotNetBuildOptions
import io.github.dotnetsupport.build.DotNetBuildService
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.msbuild.MsBuildEvaluation
import io.github.dotnetsupport.msbuild.MsBuildEvaluationResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import javax.swing.Icon

class BuildProjectBeforeRunTask : BeforeRunTask<BuildProjectBeforeRunTask>(BuildProjectBeforeRunTaskProvider.ID)

/**
 * "Build .NET Project" before a launch, as in Rider: the build goes to the Build tool window and a failed one cancels the launch.
 * Launches started together (a compound configuration, Run N Projects, several started quickly) are built at once by [LaunchBuilds]:
 * one build of their projects, shared dependencies once, and every `dotnet run` then starts with `--no-build`, so their builds do not
 * fight over `obj/`. For Debug a debug adapter starts the built assembly: its path is handed over ([BuiltBeforeLaunch]). So it is for
 * Run of a project of the old format, whose program is started directly ([ExecutableLaunch]). `dotnet test` builds itself.
 * After the build the step waits as "Wait for" of the configuration says ([LaunchWait]).
 */
class BuildProjectBeforeRunTaskProvider : BeforeRunTaskProvider<BuildProjectBeforeRunTask>() {
    override fun getId(): Key<BuildProjectBeforeRunTask> = ID
    override fun getName(): String = "Build .NET Project"
    override fun getIcon(): Icon = DotNetIcons.Project

    /** Enabled, so a new ".NET Project" configuration has the task from the start. */
    override fun createTask(runConfiguration: RunConfiguration): BuildProjectBeforeRunTask? =
        if (runConfiguration is DotNetRunConfiguration) BuildProjectBeforeRunTask().apply { isEnabled = true } else null

    override fun executeTask(context: DataContext, configuration: RunConfiguration, environment: ExecutionEnvironment, task: BuildProjectBeforeRunTask): Boolean {
        if (configuration !is DotNetRunConfiguration) return true
        return build(configuration, environment) && LaunchWait.await(configuration, environment)
    }

    private fun build(configuration: DotNetRunConfiguration, environment: ExecutionEnvironment): Boolean {
        val options = configuration.options
        val debug = environment.executor.id == DefaultDebugExecutor.EXECUTOR_ID
        val executable = ExecutableLaunch.applies(configuration.project, options)
        // no file: the launch reports it better than a build would
        val projectFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(options.projectPath.orEmpty()) ?: return true
        if (executable) {
            val targetPath = DotNetDebugBuild.buildAndLocate(configuration, projectFile) ?: return false
            handOver(environment, configuration, targetPath)
            return true
        }
        val command = options.command
        if (command == DotNetCommand.TEST || debug && command != DotNetCommand.RUN) return true
        val member = LaunchBuildPlan.Member(projectFile.path, DotNetBuildSettings.getInstance(configuration.project).frameworkArguments(projectFile), watch = command == DotNetCommand.WATCH)
        when (LaunchBuilds.getInstance(configuration.project).buildBefore(LaunchBuilds.Request(configuration.name, member))) {
            LaunchBuilds.Outcome.FAILED -> return false
            LaunchBuilds.Outcome.NOT_BUILT -> return true
            LaunchBuilds.Outcome.BUILT -> when {
                debug -> handOver(environment, configuration, DotNetDebugBuild.locate(configuration, projectFile).orEmpty())
                // `dotnet run --no-build`; `dotnet watch` builds again, now without anything to compile in the shared projects
                command == DotNetCommand.RUN -> BuiltBeforeLaunch.put(environment.executionId, "", configuration.name)
                else -> {}
            }
        }
        return true
    }

    private fun handOver(environment: ExecutionEnvironment, configuration: DotNetRunConfiguration, targetPath: String) {
        environment.putUserData(DotNetLaunchArguments.BUILT, true)
        targetPath.ifEmpty { null }?.let { environment.putUserData(DotNetLaunchArguments.TARGET_PATH, it) }
        BuiltBeforeLaunch.put(environment.executionId, targetPath, configuration.name)
        PluginLog.info(DotNetDebugBuild.LOG_CATEGORY, "built before the launch ${environment.executionId} of ${configuration.name}: ${targetPath.ifEmpty { "the output is unknown" }}")
    }

    companion object {
        val ID: Key<BuildProjectBeforeRunTask> = Key.create("DotNet.BuildProject.Before.Run")

        fun isNeeded(executorId: String, command: DotNetCommand): Boolean = executorId == DefaultDebugExecutor.EXECUTOR_ID && command == DotNetCommand.RUN
    }
}

/** The build a debugger needs before it starts the assembly; blocking, for a background thread. */
object DotNetDebugBuild {
    /**
     * Builds [projectFile] with the Build tool window and asks MSBuild where the assembly is. Null: the build has failed.
     * An empty string is not a failure: the path is unknown, the adapter is given the project then and finds the output itself.
     */
    fun buildAndLocate(configuration: DotNetRunConfiguration, projectFile: VirtualFile): String? =
        if (build(configuration.project, projectFile)) targetPath(configuration, projectFile).orEmpty() else null

    /** Where the assembly of [projectFile] is, without a build; null when MSBuild cannot tell. Blocking. */
    fun locate(configuration: DotNetRunConfiguration, projectFile: VirtualFile): String? = targetPath(configuration, projectFile)

    // A compound configuration (or Debug of several projects) starts its launches together; two builds of the same dependencies at once
    // fight over the files in obj/ and bin/, so the builds a launch waits for go one after another (LaunchBuilds takes the same lock).
    private val builds = Semaphore(1, true)

    /** Builds [projectFile] with the Build tool window and waits for it; blocking, for a background thread. */
    fun build(project: Project, projectFile: VirtualFile): Boolean = exclusively { buildNow(project, projectFile) }

    /** [action] while no other build before a launch runs. */
    fun <T> exclusively(action: () -> T): T {
        builds.acquire()
        try {
            return action()
        } finally {
            builds.release()
        }
    }

    /** [exclusively] when the lock is free within [millis]; null when it is not. */
    fun <T> exclusivelyOrNull(millis: Long, action: () -> T): T? {
        if (!builds.tryAcquire(millis, java.util.concurrent.TimeUnit.MILLISECONDS)) return null
        try {
            return action()
        } finally {
            builds.release()
        }
    }

    /** Builds [target] (a project, a solution or a solution filter) with the Build tool window and waits for it; the caller holds the lock. */
    fun buildNow(project: Project, target: VirtualFile): Boolean {
        val built = CompletableFuture<Boolean>()
        // The build saves the documents, which takes the EDT in a write-safe context:
        // not ModalityState.any(), under which saving is an error of the platform ("Write-unsafe context").
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) built.complete(false)
            else DotNetBuildService.getInstance(project).run(target, DotNetBuildCommand.BUILD) { built.complete(it) }
        }, ModalityState.nonModal())
        return built.get()
    }

    /** MsBuildHost first (no process per launch, and it evaluates as a build does); `dotnet msbuild -getProperty` when it cannot tell. */
    private fun targetPath(configuration: DotNetRunConfiguration, projectFile: VirtualFile): String? {
        val project = configuration.project
        val settings = DotNetBuildSettings.getInstance(project)
        val globals = MsBuildEvaluationResult.globalProperties(DotNetBuildOptions.getInstance(project).state.globalProperties)
        MsBuildEvaluation.getInstance(project).targetPath(projectFile, settings.configuration, settings.launchFramework(projectFile), globals)
            ?.let { return it.also { PluginLog.info(LOG_CATEGORY, "TargetPath of ${projectFile.name} (MsBuildHost): $it") } }
        return targetPathOfCli(configuration, projectFile)
    }

    private fun targetPathOfCli(configuration: DotNetRunConfiguration, projectFile: VirtualFile): String? = try {
        val project = configuration.project
        val settings = DotNetBuildSettings.getInstance(project)
        val properties = DotNetBuildOptions.propertyArguments(DotNetBuildOptions.getInstance(project).state.globalProperties)
        val arguments = MsBuildTargetPath.arguments(projectFile.path, settings.configuration, settings.launchFramework(projectFile), properties)
        val output = DotNetCli.execute(DotNetCli.commandLine(projectFile.parent.path, *arguments.toTypedArray()))
        if (output.exitCode == 0) MsBuildTargetPath.parse(output.stdout)
        else null.also { PluginLog.warn(LOG_CATEGORY, "`msbuild -getProperty:TargetPath` of ${projectFile.name} exit code ${output.exitCode}: ${DotNetCli.lastLines(output, 1)}") }
    } catch (e: ExecutionException) {
        PluginLog.warn(LOG_CATEGORY, "`msbuild -getProperty:TargetPath` of ${projectFile.name} could not run", e)
        null
    }

    /** The category of the journal of the plugin for running and debugging a project: the build before the run and what it finds. */
    const val LOG_CATEGORY = "run"
}
