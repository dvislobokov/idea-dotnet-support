package io.github.dotnetsupport.dap

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DapLaunchArgumentsProvider
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.DebugAdapterId
import com.intellij.platform.dap.LaunchRequestArguments
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetLaunchArguments
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.settings.DotNetSettings

/** The adapter of `dotnet-debugger`; the id is what launch arguments and the adapter descriptor are matched by. */
object DotNetDebugAdapterId : DebugAdapterId("coreclr", ".NET")

/**
 * Makes the runner of the platform DAP client take Debug of a ".NET Project" configuration and gives it the `launch` request.
 * The arguments themselves are made by the configuration (`DotNetLaunchArguments`), which knows nothing about DAP.
 */
class DotNetDapLaunchArgumentsProvider : DapLaunchArgumentsProvider {
    /**
     * Debug only: the runner would take Run as well (a `noDebug` launch), and Run is `dotnet run` of the configuration itself.
     * Debug of `dotnet test` is not here either: that is a run of the tests with an attach to the test host, see `DotNetTestRunner`.
     */
    override fun isApplicable(executorId: String, profile: RunProfile): Boolean = executorId == DefaultDebugExecutor.EXECUTOR_ID &&
        (profile is DotNetAttachProfile || profile is DotNetRunConfiguration && profile.options.command == DotNetCommand.RUN)

    /** Called on the thread of the launch, so nothing slow here: the path of the assembly is added later, see [DotNetDebugAdapterDescriptor]. */
    override fun getLaunchArguments(project: Project, profile: RunProfile): LaunchRequestArguments = when (profile) {
        is DotNetAttachProfile -> {
            val settings = DotNetSettings.getInstance()
            val arguments = DotNetLaunchArguments.attach(profile.processId, justMyCode = !settings.debugExternalSource, allowImplicitEvaluation = settings.debugAllowImplicitEvaluation)
            LaunchRequestArguments(DotNetDebugAdapterId, DapStartRequest.Attach, arguments)
        }
        else -> LaunchRequestArguments(DotNetDebugAdapterId, DapStartRequest.Launch, (profile as DotNetRunConfiguration).debugLaunchArguments())
    }
}
