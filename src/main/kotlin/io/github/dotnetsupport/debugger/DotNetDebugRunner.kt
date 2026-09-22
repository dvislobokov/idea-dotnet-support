package io.github.dotnetsupport.debugger

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.attach.LocalAttachHost
import com.intellij.xdebugger.attach.XAttachDebugger
import com.intellij.xdebugger.attach.XAttachDebuggerProvider
import com.intellij.xdebugger.attach.XAttachHost
import com.intellij.xdebugger.attach.XAttachPresentationGroup
import com.intellij.xdebugger.attach.XAttachProcessPresentationGroup
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetDebugBuild
import io.github.dotnetsupport.run.DotNetLaunchArguments
import io.github.dotnetsupport.run.DotNetProcessAttacher
import io.github.dotnetsupport.run.DotNetProcesses
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.settings.DotNetSettings
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

private val LOG = logger<DotNetDebugRunner>()

/**
 * Debug of a ".NET Project" configuration (`dotnet run`) and of an attach: the adapter starts the program, the plugin's own client talks
 * to it. Debug of `dotnet test` is not here: that is a run of the tests with an attach to the test host, see `DotNetTestRunner`.
 */
class DotNetDebugRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "DotNetDebugRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean = executorId == DefaultDebugExecutor.EXECUTOR_ID &&
        (profile is DotNetAttachProfile || profile is DotNetRunConfiguration && profile.options.command == DotNetCommand.RUN)

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val result = AsyncPromise<RunContentDescriptor?>()
        val project = environment.project
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val start = debugStart(environment)
                val adapterPath = DotNetTool.DEBUGGER.find() ?: run {
                    DotNetTool.DEBUGGER.offerInstallation(project, "Debug")
                    throw ExecutionException("The debug adapter is not installed: the ${DotNetTool.DEBUGGER.packageId} global tool is not found")
                }
                val log = DotNetDebuggerLogs.newAdapterLog()
                LOG.info("Starting $adapterPath, log: ${log ?: "off"}")
                val adapter = DebugAdapterProcess(DebugAdapterProcess.commandLine(adapterPath, log))
                ApplicationManager.getApplication().invokeLater({
                    try {
                        val session = XDebuggerManager.getInstance(project).startSession(environment, object : XDebugProcessStarter() {
                            override fun start(session: XDebugSession): XDebugProcess = DotNetDebugProcess(session, adapter, start, DotNetDebuggerLogs.newProtocolTrace())
                        })
                        result.setResult(session.runContentDescriptor)
                    } catch (e: Exception) {
                        adapter.stop(0)
                        result.setError(e)
                    }
                }, ModalityState.nonModal())
            } catch (e: Exception) {
                result.setError(e)
            }
        }
        return result
    }

    /** On a background thread: a configuration without the "Build .NET Project" task is built here, so the path of the assembly is known. */
    private fun debugStart(environment: ExecutionEnvironment): DebugStart {
        val settings = DotNetSettings.getInstance()
        return when (val profile = environment.runProfile) {
            is DotNetAttachProfile -> DebugStart(
                attach = true, arguments = DotNetLaunchArguments.attach(profile.processId, !settings.debugExternalSource, settings.debugAllowImplicitEvaluation),
                name = profile.name, skipInitialBreak = profile.skipInitialBreak,
            )
            is DotNetRunConfiguration -> {
                val arguments: MutableMap<String, Any?> = LinkedHashMap(profile.debugLaunchArguments())
                val built = environment.getUserData(DotNetLaunchArguments.BUILT) == true
                val targetPath = environment.getUserData(DotNetLaunchArguments.TARGET_PATH) ?: if (built) "" else buildNow(profile)
                DotNetLaunchArguments.setProgram(arguments, targetPath)
                DebugStart(attach = false, arguments = arguments, name = profile.name, launchUrl = profile.launchProfile()?.launchUrl, openBrowser = profile.options.openBrowser)
            }
            else -> throw ExecutionException("${profile.name} cannot be debugged by the .NET debugger")
        }
    }

    /**
     * A configuration saved before the "Build .NET Project" task existed comes here not built. The build of the adapter is not an option:
     * its failure does not end the session and its errors do not reach the Build tool window.
     */
    private fun buildNow(configuration: DotNetRunConfiguration): String? {
        val projectFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(configuration.options.projectPath.orEmpty()) ?: return null
        return DotNetDebugBuild.buildAndLocate(configuration, projectFile) ?: throw ExecutionException("Build failed: see the Build tool window")
    }
}

/** A process to attach to, as a run profile: that is what an execution environment and the runner work on. The state starts nothing. */
class DotNetAttachProfile(val processId: Long, private val title: String, val skipInitialBreak: Boolean = false) : RunProfile {
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = RunProfileState { _, _ -> null }
    override fun getName(): String = title
    override fun getIcon(): Icon = DotNetIcons.Project
}

/** Processes under this debugger: a second debugger attached to a process kills it on Windows, so such a process is never offered again. */
object DebuggedProcesses {
    private val ids = ConcurrentHashMap.newKeySet<Long>()

    fun add(processId: Long) = ids.add(processId)
    fun remove(processId: Long) = ids.remove(processId)
    operator fun contains(processId: Long): Boolean = processId in ids
}

/** The attach of the rest of the plugin (Debug of tests attaches to the test host). */
class DotNetProcessAttacherImpl : DotNetProcessAttacher {
    override fun attach(project: Project, processId: Long, title: String, skipInitialBreak: Boolean) {
        if (processId in DebuggedProcesses) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            try {
                val profile = DotNetAttachProfile(processId, "$title ($processId)", skipInitialBreak)
                val environment = ExecutionEnvironmentBuilder.create(project, DefaultDebugExecutor.getDebugExecutorInstance(), profile).build()
                ExecutionManager.getInstance(project).restartRunProfile(environment)
            } catch (e: Exception) {
                LOG.warn("Cannot attach to $processId", e)
                DotNetCli.notifyError(project, "Attach to Process", e.message ?: e.javaClass.simpleName)
            }
        }, project.disposed)
    }
}

/** Run | Attach to Process: the .NET debugger for local .NET processes that are not debugged by it already. */
class DotNetAttachDebuggerProvider : XAttachDebuggerProvider {
    override fun isAttachHostApplicable(attachHost: XAttachHost): Boolean = attachHost is LocalAttachHost

    override fun getPresentationGroup(): XAttachPresentationGroup<ProcessInfo> = Group

    override fun getAvailableDebuggers(project: Project, hostInfo: XAttachHost, process: ProcessInfo, contextHolder: UserDataHolder): List<XAttachDebugger> {
        val processId = process.pid.toLong()
        if (processId == ProcessHandle.current().pid() || processId in DebuggedProcesses) return emptyList()
        val executable = process.executableCannonicalPath.orElse(null)
        return if (DotNetProcesses.isDotNet(executable, process.executableName, process.commandLine)) listOf(Debugger) else emptyList()
    }

    /** ".NET" in the list of Attach to Process, with the icon of a project instead of the generic one. */
    private object Group : XAttachProcessPresentationGroup {
        override fun getOrder(): Int = 0
        override fun getGroupName(): String = ".NET"
        override fun getItemIcon(project: Project, info: ProcessInfo, dataHolder: UserDataHolder): Icon = DotNetIcons.Project
        override fun getItemDisplayText(project: Project, info: ProcessInfo, dataHolder: UserDataHolder): String = info.executableDisplayName
    }

    private object Debugger : XAttachDebugger {
        override fun getDebuggerDisplayName(): String = ".NET Debugger"

        override fun attachDebugSession(project: Project, hostInfo: XAttachHost, info: ProcessInfo) {
            DotNetProcessAttacher.find()?.attach(project, info.pid.toLong(), info.executableDisplayName)
        }
    }
}
