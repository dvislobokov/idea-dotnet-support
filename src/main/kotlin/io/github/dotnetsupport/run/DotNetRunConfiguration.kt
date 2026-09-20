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
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.util.execution.ParametersListUtil
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.cli.DotNetCli
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

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        if (options.command == DotNetCommand.TEST) DotNetTestRunState(this, environment) else runState(environment)

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

    /** `launchUrl` of the selected profile, or of the first one, which is what `dotnet run` uses by default. */
    private fun launchUrl(): String? {
        val profiles = LaunchSettings.profiles(File(options.projectPath.orEmpty()))
        val selected = options.launchProfile?.takeIf { it.isNotBlank() }
        return (if (selected == null) profiles.firstOrNull() else profiles.find { it.name == selected })?.launchUrl
    }

    /** [testResultsDirectory]: where `dotnet test` writes the TRX report (and coverage) the test tree is built from. */
    fun buildCommandLine(testResultsDirectory: File? = null): GeneralCommandLine {
        val options = options
        val projectPath = options.projectPath.orEmpty()
        val programArguments = ParametersListUtil.parse(options.programArguments.orEmpty())
        val profile = options.launchProfile?.takeIf { it.isNotBlank() }?.let { listOf("--launch-profile", it) }.orEmpty()

        // Debug / Release and the target framework chosen in the toolbar
        val selected = DotNetBuildSettings.getInstance(project).runArguments(projectPath)
        val arguments = when (options.command) {
            DotNetCommand.RUN -> listOf("run", "--project", projectPath) + selected + profile + separated(programArguments)
            DotNetCommand.WATCH -> listOf("watch", "--project", projectPath, "run") + selected + profile + separated(programArguments)
            // For tests the arguments are options of `dotnet test` itself (--filter, --logger, ...).
            DotNetCommand.TEST -> listOf("test", projectPath) + selected + testArguments(testResultsDirectory) + programArguments
        }
        val workDirectory = options.workingDirectory?.takeIf { it.isNotBlank() } ?: File(projectPath).parent
        return DotNetCli.commandLine(workDirectory, *arguments.toTypedArray())
            .withEnvironment(options.environment)
            .withParentEnvironmentType(
                if (options.passParentEnvironment) GeneralCommandLine.ParentEnvironmentType.CONSOLE
                else GeneralCommandLine.ParentEnvironmentType.NONE
            )
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
}
