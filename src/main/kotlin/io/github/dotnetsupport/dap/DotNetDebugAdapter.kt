package io.github.dotnetsupport.dap

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.dap.DapBreakpointsDescription
import com.intellij.platform.dap.DapClient
import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.platform.dap.DapEventConsumer
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.DebugAdapterDescriptor
import com.intellij.platform.dap.DebugAdapterSupportProvider
import com.intellij.platform.dap.connection.DebugAdapterHandle
import com.intellij.platform.dap.xdebugger.DapXDebugProcess
import com.intellij.xdebugger.XDebugSession
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.monitor.RunningDotNetProcesses
import io.github.dotnetsupport.run.DotNetDebugBuild
import io.github.dotnetsupport.run.DotNetLaunchArguments
import io.github.dotnetsupport.run.DotNetRunConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File

class DotNetDebugAdapterSupportProvider : DebugAdapterSupportProvider<DotNetDebugAdapterId> {
    override val adapterId: DotNetDebugAdapterId get() = DotNetDebugAdapterId
    override fun createDebugAdapterDescriptor(project: Project): DebugAdapterDescriptor<DotNetDebugAdapterId> = DotNetDebugAdapterDescriptor(project)
}

/** `dotnet-debugger` (the `dotnet-debugger-dap` global tool): DAP over the standard streams of the process, no arguments. */
class DotNetDebugAdapterDescriptor(private val project: Project) : DebugAdapterDescriptor<DotNetDebugAdapterId>() {
    override val id: DotNetDebugAdapterId get() = DotNetDebugAdapterId

    override val breakpointsDescription: DapBreakpointsDescription =
        DapBreakpointsDescription(CSharpLineBreakpointType::class.java, DotNetExceptionBreakpointType::class.java)

    /**
     * The arguments of `launch` the debug process holds: the very map, so that [launchDebugAdapter], which runs later but still before the
     * request is sent, can complete it. The platform has no asynchronous step of its own between the launch and the request.
     */
    private var launchArguments: MutableMap<String, Any?>? = null

    /** A descriptor is made for every session, so this is the stopped thread of one session. */
    private val stoppedThread = StoppedThread()

    /**
     * The client of the platform; the consumer is wrapped to learn the thread of `stopped` first-hand (see [DotNetPresentationFactory]),
     * the process id of the program for the .NET Monitor, and to tell the adapter when there are no exception breakpoints.
     */
    override fun createClient(
        eventConsumer: DapEventConsumer, environment: ExecutionEnvironment, executionResult: ExecutionResult?, commandProcessor: DapCommandProcessor,
        sessionScope: CoroutineScope,
    ): DapClient {
        val debuggee = MonitoredDebuggee(environment.runProfile.name, RunningDotNetProcesses.getInstance(project))
        // a killed adapter sends neither `exited` nor `terminated`
        sessionScope.coroutineContext[Job]?.invokeOnCompletion { debuggee.forget() }
        var consumer = NoExceptionBreakpoints(project, commandProcessor).recording(debuggee.recording(stoppedThread.recording(eventConsumer)))
        if ((environment.runProfile as? DotNetAttachProfile)?.skipInitialBreak == true) consumer = InitialBreak(commandProcessor).recording(consumer)
        return super.createClient(consumer, environment, executionResult, commandProcessor, sessionScope)
    }

    override suspend fun launchDebugAdapter(environment: ExecutionEnvironment, executionResult: ExecutionResult?, sessionId: String): DebugAdapterHandle {
        val adapter = DotNetTool.DEBUGGER.find()
        if (adapter == null) {
            DotNetTool.DEBUGGER.offerInstallation(project, "Debug")
            throw ExecutionException("The debug adapter is not installed: the ${DotNetTool.DEBUGGER.packageId} global tool is not found")
        }
        buildIfNotBuilt(environment)
        val log = DotNetDebuggerLogs.newAdapterLog()
        LOG.info("Starting $adapter for session $sessionId, log: ${log ?: "off"}")
        return DotNetDebugAdapterHandle(adapterCommandLine(adapter, log)) { path, line -> CSharpLineBreakpointType.extrasAt(project, path, line) }
    }

    /**
     * A configuration without the "Build .NET Project" task (every one saved before the task existed) comes here not built. The build of
     * the adapter is not an option: its failure does not end the session and its errors do not reach the Build tool window.
     */
    private suspend fun buildIfNotBuilt(environment: ExecutionEnvironment) {
        val arguments = launchArguments ?: return
        val configuration = environment.runProfile as? DotNetRunConfiguration ?: return
        if (environment.getUserData(DotNetLaunchArguments.BUILT) == true) return
        val projectFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(configuration.options.projectPath.orEmpty()) ?: return
        val targetPath = withContext(Dispatchers.IO) { DotNetDebugBuild.buildAndLocate(configuration, projectFile) }
            ?: throw ExecutionException("Build failed: see the Build tool window")
        DotNetLaunchArguments.setProgram(arguments, targetPath)
    }

    /**
     * Overridden for the arguments and for the process with our presentation factory (see [DotNetPresentationFactory]): the build before the launch has left the path of the assembly in the environment, and the
     * provider of the arguments, which is asked earlier and sees no environment, could not know it.
     */
    override fun createXDebugProcess(
        session: XDebugSession, dapDebugSession: DapDebugSession, xDebugProcessScope: CoroutineScope, globalScope: CoroutineScope,
        debugAdapterDescriptor: DebugAdapterDescriptor<*>, executionEnvironment: ExecutionEnvironment, executionResult: ExecutionResult?,
        startRequestType: DapStartRequest, startRequestArguments: Map<String, Any?>,
    ): DapXDebugProcess {
        val arguments = LinkedHashMap(startRequestArguments)
        val built = executionEnvironment.getUserData(DotNetLaunchArguments.BUILT) == true
        DotNetLaunchArguments.setProgram(arguments, executionEnvironment.getUserData(DotNetLaunchArguments.TARGET_PATH) ?: if (built) "" else null)
        launchArguments = arguments
        return DotNetDebugProcess(
            session, dapDebugSession, xDebugProcessScope, globalScope, debugAdapterDescriptor, executionEnvironment, executionResult, startRequestType, arguments, stoppedThread,
        )
    }

    companion object {
        private val LOG = logger<DotNetDebugAdapterDescriptor>()

        fun adapterCommandLine(adapter: File, log: File?): GeneralCommandLine =
            GeneralCommandLine(listOfNotNull(adapter.path, log?.let { "--log=${it.path}" })).withWorkDirectory(adapter.parentFile)
    }
}
