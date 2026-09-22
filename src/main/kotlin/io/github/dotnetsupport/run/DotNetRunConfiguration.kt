package io.github.dotnetsupport.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.execution.ParametersListUtil
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.sdk.SdkFeatures
import io.github.dotnetsupport.settings.DotNetSettings
import io.github.dotnetsupport.testing.DotNetTestRunState
import java.io.File

enum class DotNetCommand(val title: String) {
    RUN("dotnet run"),
    WATCH("dotnet watch"),
    TEST("dotnet test");

    override fun toString(): String = title
}

class DotNetConfigurationType : ConfigurationTypeBase(
    "DotNetProjectRunConfiguration",
    ".NET Project",
    "Run, watch or test a .NET project with the dotnet CLI",
    NotNullLazyValue.createValue { DotNetIcons.Project },
) {
    val factory: ConfigurationFactory = object : ConfigurationFactory(this) {
        override fun getId(): String = "DotNetProject"
        override fun createTemplateConfiguration(project: Project): RunConfiguration = DotNetRunConfiguration(project, this, "")
        override fun getOptionsClass(): Class<out BaseState> = DotNetRunConfigurationOptions::class.java
    }

    init {
        addFactory(factory)
    }

    companion object {
        val instance: DotNetConfigurationType
            get() = ConfigurationTypeUtil.findConfigurationType(DotNetConfigurationType::class.java)
    }
}

class DotNetRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var projectPath by string()
    var command by enum(DotNetCommand.RUN)
    var launchProfile by string()
    var programArguments by string()
    var workingDirectory by string()
    var environment by map<String, String>()
    var passParentEnvironment by property(true)
    var openBrowser by property(false)

    /** `Development`, `Staging`, ...: becomes ASPNETCORE_ENVIRONMENT and DOTNET_ENVIRONMENT. */
    var environmentName by string()

    /** `dotnet test --filter`: set by the gutter icons and by "Rerun Failed Tests". */
    var testFilter by string()
    var collectCoverage by property(false)
}

class DotNetRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<DotNetRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): DotNetRunConfigurationOptions = super.getOptions() as DotNetRunConfigurationOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = DotNetSettingsEditor(project)

    override fun checkConfiguration() {
        val path = options.projectPath
        if (path.isNullOrBlank()) throw RuntimeConfigurationError("Project is not specified")
        if (!File(path).isFile) throw RuntimeConfigurationError("Project file not found: $path")
        if (DotNetCli.findExecutable() == null) throw RuntimeConfigurationError("The 'dotnet' executable is not found on PATH")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = when {
        // under Debug the test host waits for the debugger that DotNetTestRunner attaches
        options.command == DotNetCommand.TEST -> DotNetTestRunState(this, environment, debug = executor.id == DefaultDebugExecutor.EXECUTOR_ID)
        // A debugger starts the program itself (see debugLaunchArguments), and the runner of the platform still executes the state first.
        executor.id == DefaultDebugExecutor.EXECUTOR_ID && options.command == DotNetCommand.RUN -> RunProfileState { _, _ -> null }
        else -> runState(environment)
    }

    private fun runState(environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                val handler = KillableColoredProcessHandler(buildCommandLine())
                // `dotnet watch` opens the browser itself when the profile asks for it
                if (options.openBrowser && options.command == DotNetCommand.RUN) handler.addProcessListener(ListeningUrlListener(launchUrl()))
                ProcessTerminatedListener.attach(handler)
                return handler
            }
        }

    /** The selected profile, or the first one, which is what `dotnet run` uses by default. */
    fun launchProfile(): LaunchSettings.Profile? {
        val profiles = LaunchSettings.profiles(File(options.projectPath.orEmpty()))
        val selected = options.launchProfile?.takeIf { it.isNotBlank() }
        return if (selected == null) profiles.firstOrNull() else profiles.find { it.name == selected }
    }

    private fun launchUrl(): String? = launchProfile()?.launchUrl

    /** The `launch` request for a debug adapter, see [DotNetLaunchArguments]. */
    fun debugLaunchArguments(): Map<String, Any> = DotNetLaunchArguments.build(
        options.projectPath.orEmpty(), ParametersListUtil.parse(options.programArguments.orEmpty()), options.workingDirectory,
        options.environment, options.environmentName, launchProfile(), DotNetBuildSettings.getInstance(project).configuration,
        justMyCode = !DotNetSettings.getInstance().debugExternalSource, allowImplicitEvaluation = DotNetSettings.getInstance().debugAllowImplicitEvaluation,
    )

    /** [testResultsDirectory]: where `dotnet test` writes the TRX report (and coverage) the test tree is built from. */
    fun buildCommandLine(testResultsDirectory: File? = null): GeneralCommandLine {
        val options = options
        val projectPath = options.projectPath.orEmpty()
        val programArguments = ParametersListUtil.parse(options.programArguments.orEmpty())
        val profile = options.launchProfile?.takeIf { it.isNotBlank() }?.let { listOf("--launch-profile", it) }.orEmpty()

        // Debug / Release and the target framework chosen in the toolbar
        val selected = DotNetBuildSettings.getInstance(project).runArguments(projectPath)
        val arguments = when (options.command) {
            DotNetCommand.RUN -> listOf("run", "--project", projectPath) + selected + profile + environmentArguments(projectPath) + separated(programArguments)
            DotNetCommand.WATCH -> listOf("watch", "--project", projectPath, "run") + selected + profile + environmentArguments(projectPath) + separated(programArguments)
            // For tests the arguments are options of `dotnet test` itself (--filter, --logger, ...).
            DotNetCommand.TEST -> listOf("test", projectPath) + selected + testArguments(testResultsDirectory) + programArguments
        }
        val workDirectory = options.workingDirectory?.takeIf { it.isNotBlank() } ?: File(projectPath).parent
        return DotNetCli.commandLine(workDirectory, *arguments.toTypedArray())
            .withEnvironment(hostingEnvironment().toMap())
            .withEnvironment(options.environment)
            .withParentEnvironmentType(
                if (options.passParentEnvironment) GeneralCommandLine.ParentEnvironmentType.CONSOLE
                else GeneralCommandLine.ParentEnvironmentType.NONE
            )
    }

    /** The variables of the chosen environment; the ones set explicitly in the environment table are left alone. */
    private fun hostingEnvironment(): List<Pair<String, String>> {
        val name = options.environmentName?.trim().orEmpty()
        if (name.isEmpty()) return emptyList()
        return HOSTING_VARIABLES.filter { it !in options.environment }.map { it to name }
    }

    /**
     * `environmentVariables` of a launch profile win over the environment of the process (checked on the real CLI: with
     * ASPNETCORE_ENVIRONMENT=Staging outside and Development in the profile the application sees Development), and only
     * `-e` wins over the profile. The option exists since SDK 9.0.200; an older CLI gets the variables alone.
     */
    private fun environmentArguments(projectPath: String): List<String> {
        val variables = hostingEnvironment()
        if (variables.isEmpty()) return emptyList()
        val directory = LocalFileSystem.getInstance().findFileByPath(projectPath)?.parent
        if (!SdkFeatures.supportsRunEnvironmentOption(SdkFeatures.sdkFor(directory))) return emptyList()
        return variables.flatMap { (name, value) -> listOf("-e", "$name=$value") }
    }

    private fun testArguments(resultsDirectory: File?): List<String> = buildList {
        options.testFilter?.takeIf { it.isNotBlank() }?.let { add("--filter"); add(it) }
        if (resultsDirectory != null) {
            add("--logger"); add("trx;LogFileName=results.trx")
            add("--results-directory"); add(resultsDirectory.path)
        }
        // coverlet: needs the coverlet.collector package in the test project (the templates of `dotnet new` have it)
        if (options.collectCoverage) add("--collect:XPlat Code Coverage")
    }

    private fun separated(programArguments: List<String>): List<String> =
        if (programArguments.isEmpty()) emptyList() else listOf("--") + programArguments

    companion object {
        val HOSTING_VARIABLES = listOf("ASPNETCORE_ENVIRONMENT", "DOTNET_ENVIRONMENT")

        /** Environments a project is prepared for: `appsettings.Staging.json` -> `Staging`, after the three standard ones. */
        fun environmentNames(projectFile: File): List<String> {
            val found = projectFile.parentFile?.listFiles().orEmpty().mapNotNull { file ->
                APPSETTINGS.matchEntire(file.name)?.groupValues?.get(1)
            }
            return (listOf("Development", "Staging", "Production") + found.sorted()).distinct()
        }

        private val APPSETTINGS = Regex("""appsettings\.([A-Za-z0-9_-]+)\.json""", RegexOption.IGNORE_CASE)
    }
}
