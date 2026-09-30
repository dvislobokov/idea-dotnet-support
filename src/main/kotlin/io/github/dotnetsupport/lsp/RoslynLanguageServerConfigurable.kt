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
import io.github.dotnetsupport.DotNetBundle
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.settings.DotNetSettingsConfigurable
import java.io.File

/**
 * Settings | Tools | .NET | Language Server: how `roslyn-language-server` is started and the settings it asks its client for.
 * The second part is generated from [RoslynOptions]: a new option of the server is a line there.
 */
class RoslynLanguageServerConfigurable(private val project: Project) : BoundConfigurable(DotNetBundle.message("page.languageServer")) {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private val state get() = settings.state

    override fun createPanel(): DialogPanel = panel {
        row {
            comment(DotNetBundle.message("server.about"))
        }
        row { checkBox(DotNetBundle.message("server.enabled")).bindSelected(state::enabled) }
        group(DotNetBundle.message("server.group")) {
            row(DotNetBundle.message("server.executable")) {
                label(DotNetTool.ROSLYN_LANGUAGE_SERVER.find()?.path ?: DotNetBundle.message("common.notFound"))
                link(DotNetBundle.message("server.executable.change")) { ShowSettingsUtil.getInstance().showSettingsDialog(project, DotNetSettingsConfigurable::class.java) }
                    .comment(DotNetBundle.message("server.executable.comment", DotNetTool.ROSLYN_LANGUAGE_SERVER.packageId))
            }
            row(DotNetBundle.message("server.logLevel")) { comboBox(RoslynLogLevel.entries).bindItem(state::logLevel.toNullableProperty()).comment("<code>--logLevel</code>") }
            row(DotNetBundle.message("server.logFolder")) {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(DotNetBundle.message("server.logFolder.chooser")), project)
                    .align(AlignX.FILL).bindText({ state.logDirectory.orEmpty() }, { state.logDirectory = it.trim() })
                    .applyToComponent { (textField as? JBTextField)?.emptyText?.text = defaultLogDirectory().path }
                    .comment("<code>--extensionLogDirectory</code>")
            }
            lateinit var autoLoad: com.intellij.ui.dsl.builder.Cell<javax.swing.JCheckBox>
            row {
                autoLoad = checkBox(DotNetBundle.message("server.autoLoad")).bindSelected(state::autoLoadProjects)
                    .comment(DotNetBundle.message("server.autoLoad.comment"))
            }
            indent {
                row(DotNetBundle.message("server.autoLoad.limit")) {
                    intTextField(0..100_000).bindIntText(state::autoLoadProjectsLimit).comment(DotNetBundle.message("server.autoLoad.limit.comment"))
                }.enabledIf(autoLoad.selected)
            }
            row(DotNetBundle.message("server.generators")) {
                comboBox(SourceGeneratorExecution.entries).bindItem(state::sourceGeneratorExecution.toNullableProperty())
                    .comment(DotNetBundle.message("server.generators.comment"))
            }
            row(DotNetBundle.message("server.arguments")) {
                textField().align(AlignX.FILL).bindText({ state.additionalArguments.orEmpty() }, { state.additionalArguments = it.trim() })
                    .comment(DotNetBundle.message("server.arguments.comment"))
            }
        }
        for (group in RoslynOptions.GROUPS) group(RoslynOptions.title(group)) { RoslynOptions.ALL.filter { it.group == group }.forEach { option(it) } }
        group(DotNetBundle.message("server.other")) {
            row {
                textArea().rows(4).align(AlignX.FILL).bindText({ state.additionalOptions.orEmpty() }, { state.additionalOptions = it.trim() })
                    .comment(DotNetBundle.message("server.other.comment"))
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
                checkBox(option.text).bindSelected({ settings.value(option) == "true" }, { settings.setValue(option, it.toString()) }).apply { option.note?.let { comment(it) } }
            }
            option.values != null -> row(option.text) {
                comboBox(option.values).bindItem({ settings.value(option) }, { settings.setValue(option, it ?: option.default) }).apply { option.note?.let { comment(it) } }
            }
            else -> row(option.text) {
                textField().align(AlignX.FILL).bindText({ settings.value(option) }, { settings.setValue(option, it.trim()) }).apply { option.note?.let { comment(it) } }
            }
        }
    }

    companion object {
        fun defaultLogDirectory(): File = io.github.dotnetsupport.cli.DotNetLogs.directory("roslyn-language-server").toFile()
    }
}
