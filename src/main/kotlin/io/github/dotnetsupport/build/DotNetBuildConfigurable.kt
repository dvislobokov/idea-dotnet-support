package io.github.dotnetsupport.build

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.settings.DotNetSettingsConfigurable

/** Settings | Tools | .NET | Toolset and Build: the groups and the wording of Rider, only the options the plugin has something behind. */
class DotNetBuildConfigurable(private val project: Project) : BoundConfigurable("Toolset and Build") {
    private val options get() = DotNetBuildOptions.getInstance(project).state

    /** "Auto" and the numbers up to the cores of the machine: more processes than cores only get in each other's way. */
    private class Parallelism(val processes: Int) {
        override fun toString(): String = if (processes == 0) "Auto (by MSBuild, from the number of cores)" else processes.toString()
        override fun equals(other: Any?): Boolean = other is Parallelism && other.processes == processes
        override fun hashCode(): Int = processes
    }

    override fun createPanel(): DialogPanel = panel {
        group("Toolset") {
            row(".NET CLI executable path:") {
                label(DotNetCli.findExecutable() ?: "not found")
                link("Change...") { ShowSettingsUtil.getInstance().showSettingsDialog(project, DotNetSettingsConfigurable::class.java) }
                    .comment("Set on the parent page, Tools | .NET")
            }
            row("MSBuild global properties:") {
                textField().align(AlignX.FILL).bindText({ options.globalProperties.orEmpty() }, { options.globalProperties = it.trim() })
                    .comment("<code>Name=Value;Other=Value</code>, passed as <code>-p:</code> to build, rebuild, clean, restore and run of this project")
            }
        }
        group("Build") {
            row { checkBox("Run build after solution is loaded").bindSelected(options::buildAfterSolutionIsLoaded) }
            row {
                checkBox("Restore NuGet packages before build").bindSelected(options::restoreBeforeBuild)
                    .comment("Off: <code>--no-restore</code>. On, with Smart Restore of the NuGet page: only when something that decides the packages has changed")
            }
            row("Use up to") {
                comboBox(listOf(Parallelism(0)) + (1..Runtime.getRuntime().availableProcessors()).map(::Parallelism))
                    .bindItem({ Parallelism(options.parallelProcesses) }, { options.parallelProcesses = it?.processes ?: 0 })
                label("processes in parallel")
            }
        }
        group("Build Logging") {
            row("Verbosity of output logger") { comboBox(MsBuildVerbosity.entries).bindItem(options::outputVerbosity.toNullableProperty()) }
            row { checkBox("Write MSBuild log to file").bindSelected(options::logToFile) }
            row("Verbosity of file logger") { comboBox(MsBuildVerbosity.entries).bindItem(options::fileVerbosity.toNullableProperty()) }
            row {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("MSBuild Log Folder"), project)
                    .align(AlignX.FILL).bindText({ options.logFolder.orEmpty() }, { options.logFolder = it.trim() })
                    .applyToComponent { (textField as? com.intellij.ui.components.JBTextField)?.emptyText?.text = DotNetBuildOptions.defaultLogFolder().path }
                    .comment("A log file per build: <i>Build_2026_09_21_04_03_37.log</i>")
            }
            row { link("Open the log folder") { RevealFileAction.openDirectory(DotNetBuildOptions.getInstance(project).logFolder.also { it.mkdirs() }) } }
        }
    }
}
