package io.github.dotnetsupport.publish

import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import java.io.File
import javax.swing.JComponent

/**
 * ".NET Publish": a saved `dotnet publish`, as "Publish to folder" of Rider. A type of its own rather than a command of ".NET Project":
 * it has nothing to debug, no console, no row in the Services tool window and no build before it; its output goes to the Build tool window.
 */
class DotNetPublishConfigurationType : ConfigurationTypeBase(
    ID, ".NET Publish", "Publish a .NET project to a folder or a container image with dotnet publish",
    NotNullLazyValue.createValue { AllIcons.Nodes.Deploy },
) {
    val factory: ConfigurationFactory = object : ConfigurationFactory(this) {
        override fun getId(): String = "DotNetPublish"
        override fun createTemplateConfiguration(project: Project): RunConfiguration = DotNetPublishRunConfiguration(project, this, "")
        override fun getOptionsClass(): Class<out BaseState> = DotNetPublishRunConfigurationOptions::class.java
    }

    init {
        addFactory(factory)
    }

    companion object {
        const val ID = "DotNetPublishRunConfiguration"

        val instance: DotNetPublishConfigurationType
            get() = ConfigurationTypeUtil.findConfigurationType(DotNetPublishConfigurationType::class.java)
    }
}

class DotNetPublishRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var projectPath by string()

    /** [PublishOptions.toProperties]: named as the MSBuild properties they set, readable in a shared `.run.xml`. */
    var properties by map<String, String>()
}

class DotNetPublishRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<DotNetPublishRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): DotNetPublishRunConfigurationOptions = super.getOptions() as DotNetPublishRunConfigurationOptions

    var publishOptions: PublishOptions
        get() = PublishOptions.fromProperties(options.projectPath.orEmpty(), options.properties)
        set(value) {
            options.projectPath = value.projectPath
            options.properties = value.toProperties().toMutableMap()
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = DotNetPublishSettingsEditor(project)

    override fun checkConfiguration() {
        val publish = publishOptions
        PublishCommand.validate(publish)?.let { throw RuntimeConfigurationError(it) }
        if (!File(publish.projectPath).isFile) throw RuntimeConfigurationError("Project file not found: ${publish.projectPath}")
    }

    /** Nothing runs in a console: the publish reports to the Build tool window, so there is no descriptor to show. */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = RunProfileState { _, _ ->
        DotNetPublisher.publish(project, publishOptions)
        null
    }

    override fun suggestedName(): String? = options.projectPath?.let { "Publish ${File(it).nameWithoutExtension}" }

    companion object {
        /** Creates or updates the configuration "Publish <project>" with [options] and selects it. */
        fun save(project: Project, options: PublishOptions): RunnerAndConfigurationSettings {
            val runManager = RunManager.getInstance(project)
            val name = "Publish ${options.projectName}"
            val settings = runManager.findConfigurationByTypeAndName(DotNetPublishConfigurationType.instance, name)
                ?: runManager.createConfiguration(name, DotNetPublishConfigurationType.instance.factory).also { runManager.addConfiguration(it) }
            (settings.configuration as DotNetPublishRunConfiguration).publishOptions = options
            runManager.selectedConfiguration = settings
            return settings
        }
    }
}

class DotNetPublishSettingsEditor(private val project: Project) : SettingsEditor<DotNetPublishRunConfiguration>() {
    private val form by lazy { PublishForm(project) { fireEditorStateChanged() } }

    override fun createEditor(): JComponent = form.component

    override fun resetEditorFrom(configuration: DotNetPublishRunConfiguration) {
        val options = configuration.publishOptions
        form.reset(if (options.projectPath.isEmpty()) firstProjectDefaults() ?: options else options)
    }

    override fun applyEditorTo(configuration: DotNetPublishRunConfiguration) {
        configuration.publishOptions = form.options
    }

    private fun firstProjectDefaults(): PublishOptions? =
        DotNetPublisher.publishableProjects(project).firstOrNull()?.let { PublishForm.defaults(project, it.path) }
}
