package io.github.dotnetsupport.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.toNullableProperty
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.settings.DotNetSettingsConfigurable
import java.io.File

/**
 * Settings | Tools | .NET | Language Server: how `roslyn-language-server` is started and the settings it asks its client for.
 * The second part is generated from [RoslynOptions]: a new option of the server is a line there.
 */
class RoslynLanguageServerConfigurable(private val project: Project) : BoundConfigurable("Language Server") {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private val state get() = settings.state

    override fun createPanel(): DialogPanel = panel {
        row {
            comment("<code>roslyn-language-server</code>, the C# language server of Roslyn: errors of the compiler, completion, navigation, refactorings. It starts when a C# file is opened.")
        }
        row { checkBox("Use the language server for C#").bindSelected(state::enabled) }
        group("Server") {
            row("Executable:") {
                label(DotNetTool.ROSLYN_LANGUAGE_SERVER.find()?.path ?: "not found")
                link("Change or install...") { ShowSettingsUtil.getInstance().showSettingsDialog(project, DotNetSettingsConfigurable::class.java) }
                    .comment("The <code>${DotNetTool.ROSLYN_LANGUAGE_SERVER.packageId}</code> tool on the parent page, Tools | .NET")
            }
            row("Log level:") { comboBox(RoslynLogLevel.entries).bindItem(state::logLevel.toNullableProperty()).comment("<code>--logLevel</code>") }
            row("Log folder:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Language Server Log Folder"), project)
                    .align(AlignX.FILL).bindText({ state.logDirectory.orEmpty() }, { state.logDirectory = it.trim() })
                    .applyToComponent { (textField as? JBTextField)?.emptyText?.text = defaultLogDirectory().path }
                    .comment("<code>--extensionLogDirectory</code>")
            }
            lateinit var autoLoad: com.intellij.ui.dsl.builder.Cell<javax.swing.JCheckBox>
            row {
                autoLoad = checkBox("Let the server find the projects of a folder without a solution").bindSelected(state::autoLoadProjects)
                    .comment("<code>--autoLoadProjects</code>. A solution is always opened by the plugin; of several solutions the one chosen in .NET | Select Solution for Language Server")
            }
            indent {
                row("At most:") {
                    intTextField(0..100_000).bindIntText(state::autoLoadProjectsLimit).comment("projects; 0 is the limit the server recommends")
                }.enabledIf(autoLoad.selected)
            }
            row("Run source generators:") {
                comboBox(SourceGeneratorExecution.entries).bindItem(state::sourceGeneratorExecution.toNullableProperty())
                    .comment("<code>--sourceGeneratorExecutionPreference</code>. Automatic: on every change; Balanced: on save and on build")
            }
            row("Additional arguments:") {
                textField().align(AlignX.FILL).bindText({ state.additionalArguments.orEmpty() }, { state.additionalArguments = it.trim() })
                    .comment("Appended to the command line; <code>--stdio</code> and <code>--clientProcessId</code> are always passed")
            }
        }
        for (group in RoslynOptions.GROUPS) group(group) { RoslynOptions.ALL.filter { it.group == group }.forEach { option(it) } }
        group("Other Settings of the Server") {
            row {
                textArea().rows(4).align(AlignX.FILL).bindText({ state.additionalOptions.orEmpty() }, { state.additionalOptions = it.trim() })
                    .comment("<code>section = value</code> per line, as the server names them, e.g. <code>completion.dotnet_trigger_completion_on_deletion = true</code>; they win over the options above")
            }
        }
    }

    override fun apply() {
        val before = RoslynLanguageServer.commandLineKey(state)
        super.apply()
        val restart = RoslynLanguageServer.commandLineKey(state) != before
        ApplicationManager.getApplication().messageBus.syncPublisher(RoslynLanguageServerSettings.CHANGED).settingsChanged(restart)
    }

    private fun Panel.option(option: RoslynOption) {
        when {
            option.isToggle -> row {
                checkBox(option.label).bindSelected({ settings.value(option) == "true" }, { settings.setValue(option, it.toString()) }).apply { option.comment?.let { comment(it) } }
            }
            option.values != null -> row(option.label) {
                comboBox(option.values).bindItem({ settings.value(option) }, { settings.setValue(option, it ?: option.default) }).apply { option.comment?.let { comment(it) } }
            }
            else -> row(option.label) {
                textField().align(AlignX.FILL).bindText({ settings.value(option) }, { settings.setValue(option, it.trim()) }).apply { option.comment?.let { comment(it) } }
            }
        }
    }

    companion object {
        fun defaultLogDirectory(): File = io.github.dotnetsupport.cli.DotNetLogs.directory("roslyn-language-server").toFile()
    }
}
