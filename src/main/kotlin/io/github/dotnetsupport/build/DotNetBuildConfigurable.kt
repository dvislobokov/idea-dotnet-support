package io.github.dotnetsupport.build

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.toNullableProperty
import io.github.dotnetsupport.DotNetBundle
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.settings.DotNetSettingsConfigurable

/** Settings | .NET | Toolset and Build: the groups and the wording of Rider, only the options the plugin has something behind. */
class DotNetBuildConfigurable(private val project: Project) : BoundConfigurable(DotNetBundle.message("page.build")) {
    private val options get() = DotNetBuildOptions.getInstance(project).state

    /** "Auto" and the numbers up to the cores of the machine: more processes than cores only get in each other's way. */
    private class Parallelism(val processes: Int) {
        override fun toString(): String = if (processes == 0) DotNetBundle.message("build.parallel.auto") else processes.toString()
        override fun equals(other: Any?): Boolean = other is Parallelism && other.processes == processes
        override fun hashCode(): Int = processes
    }

    /** An item of "MSBuild version": [value] is what [DotNetBuildOptions.Settings.msBuild] keeps. */
    private class MsBuildChoice(val value: String, val label: String) {
        override fun equals(other: Any?): Boolean = other is MsBuildChoice && other.value == value
        override fun hashCode(): Int = value.hashCode()
    }

    /** Auto, the .NET SDK, every Visual Studio found and a path set before that is not among them. Only what is known: the EDT does not run `vswhere`. */
    private fun msBuildChoices(): List<MsBuildChoice> {
        val found = VisualStudioToolset.knownInstances().mapNotNull { instance -> instance.msBuild()?.let { MsBuildChoice(it.path, instance.title) } }
        val saved = options.msBuild.orEmpty().takeIf { it != VisualStudioToolset.AUTO && it != VisualStudioToolset.DOTNET && found.none { f -> f.value.equals(it, ignoreCase = true) } }
        return listOf(MsBuildChoice(VisualStudioToolset.AUTO, DotNetBundle.message("build.msbuild.auto")), MsBuildChoice(VisualStudioToolset.DOTNET, DotNetBundle.message("build.msbuild.dotnet"))) + found +
            listOfNotNull(saved?.let { MsBuildChoice(it, it) })
    }

    override fun createPanel(): DialogPanel = panel {
        group(DotNetBundle.message("build.toolset")) {
            row(DotNetBundle.message("build.cli")) {
                label(DotNetCli.findExecutable() ?: DotNetBundle.message("common.notFound"))
                link(DotNetBundle.message("build.cli.change")) { ShowSettingsUtil.getInstance().showSettingsDialog(project, DotNetSettingsConfigurable::class.java) }
                    .comment(DotNetBundle.message("build.cli.comment"))
            }
            // MSBuild of Visual Studio is a thing of Windows: elsewhere the .NET SDK builds everything
            if (SystemInfo.isWindows) row(DotNetBundle.message("build.msbuild")) {
                val choices = msBuildChoices()
                comboBox(choices, textListCellRenderer { it?.label })
                    .bindItem({ choices.firstOrNull { it.value.equals(options.msBuild.orEmpty(), ignoreCase = true) } ?: choices.first() }, { options.msBuild = it?.value ?: VisualStudioToolset.AUTO })
                    .comment(DotNetBundle.message("build.msbuild.comment") + " " + (VisualStudioToolset.knownInstances().firstOrNull()?.let { DotNetBundle.message("build.msbuild.found", it.title, it.path) }
                        ?: DotNetBundle.message("build.msbuild.notFound", VisualStudioToolset.BUILD_TOOLS_URL)))
            }
            row(DotNetBundle.message("build.properties")) {
                textField().align(AlignX.FILL).bindText({ options.globalProperties.orEmpty() }, { options.globalProperties = it.trim() })
                    .comment(DotNetBundle.message("build.properties.comment"))
            }
        }
        group(DotNetBundle.message("build.group")) {
            row { checkBox(DotNetBundle.message("build.afterLoad")).bindSelected(options::buildAfterSolutionIsLoaded) }
            row {
                checkBox(DotNetBundle.message("build.restore")).bindSelected(options::restoreBeforeBuild)
                    .comment(DotNetBundle.message("build.restore.comment"))
            }
            row(DotNetBundle.message("build.parallel.before")) {
                comboBox(listOf(Parallelism(0)) + (1..Runtime.getRuntime().availableProcessors()).map(::Parallelism))
                    .bindItem({ Parallelism(options.parallelProcesses) }, { options.parallelProcesses = it?.processes ?: 0 })
                label(DotNetBundle.message("build.parallel.after"))
            }
        }
        group(DotNetBundle.message("build.logging")) {
            row(DotNetBundle.message("build.verbosity.output")) { comboBox(MsBuildVerbosity.entries, textListCellRenderer { it?.label }).bindItem(options::outputVerbosity.toNullableProperty()) }
            row { checkBox(DotNetBundle.message("build.logToFile")).bindSelected(options::logToFile) }
            row(DotNetBundle.message("build.verbosity.file")) { comboBox(MsBuildVerbosity.entries, textListCellRenderer { it?.label }).bindItem(options::fileVerbosity.toNullableProperty()) }
            row {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(DotNetBundle.message("build.logFolder.chooser")), project)
                    .align(AlignX.FILL).bindText({ options.logFolder.orEmpty() }, { options.logFolder = it.trim() })
                    .applyToComponent { (textField as? com.intellij.ui.components.JBTextField)?.emptyText?.text = DotNetBuildOptions.defaultLogFolder().path }
                    .comment(DotNetBundle.message("build.logFolder.comment"))
            }
            row { link(DotNetBundle.message("build.logFolder.open")) { RevealFileAction.openDirectory(DotNetBuildOptions.getInstance(project).logFolder.also { it.mkdirs() }) } }
        }
    }
}
