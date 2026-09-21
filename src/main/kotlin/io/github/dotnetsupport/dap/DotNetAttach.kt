package io.github.dotnetsupport.dap

import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.platform.dap.DapEventConsumer
import com.intellij.xdebugger.attach.XAttachDebugger
import com.intellij.xdebugger.attach.XAttachDebuggerProvider
import com.intellij.xdebugger.attach.XAttachHost
import com.intellij.xdebugger.attach.XAttachPresentationGroup
import com.intellij.xdebugger.attach.XAttachProcessPresentationGroup
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.run.DotNetProcessAttacher
import io.github.dotnetsupport.run.DotNetProcesses
import io.github.dotnetsupport.run.TestHostDebug
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon

/**
 * What an attach session is started with. The runner of the platform DAP client works on run profiles, so a process to attach to
 * is one: [DotNetDapLaunchArgumentsProvider] answers it with the `attach` request. The state starts nothing, there is nothing to start.
 */
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

    private companion object {
        val LOG = logger<DotNetProcessAttacherImpl>()
    }
}

/**
 * A test host under `VSTEST_HOST_DEBUG` waits for a debugger and then calls `Debugger.Break()`: the session would open stopped in external
 * code, with the breakpoints of the user still ahead. That one stop is answered with `continue` and never shown.
 */
class InitialBreak(private val commandProcessor: DapCommandProcessor) {
    private val skipped = AtomicBoolean()

    fun recording(consumer: DapEventConsumer): DapEventConsumer = object : DapEventConsumer by consumer {
        override fun stopped(args: StoppedEventArguments?) {
            if (args != null && TestHostDebug.isInitialBreak(args.reason, args.description) && skipped.compareAndSet(false, true)) {
                commandProcessor.submitCommand { server.continue_(ContinueArguments().apply { threadId = args.threadId ?: 0 }).await() }
            } else consumer.stopped(args)
        }
    }
}

/** Run | Attach to Process: the .NET debugger for local .NET processes that are not debugged by it already. */
class DotNetAttachDebuggerProvider : XAttachDebuggerProvider {
    override fun isAttachHostApplicable(attachHost: XAttachHost): Boolean = attachHost is com.intellij.xdebugger.attach.LocalAttachHost

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
