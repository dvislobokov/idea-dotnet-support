package io.github.dotnetsupport.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.sdk.DotNetEnvironmentDialog
import io.github.dotnetsupport.sdk.DotNetSdks
import io.github.dotnetsupport.sdk.GlobalJson
import java.io.File

/** Machine-wide settings of the plugin: where the .NET CLI is and what the plugin does on its own. */
@Service(Service.Level.APP)
@State(name = "DotNetSupportSettings", storages = [Storage("dotnet-support.xml")])
class DotNetSettings : SimplePersistentStateComponent<DotNetSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        /** Empty: the executable is looked up on PATH and in the default installation directories. */
        var dotnetPath by string("")
        var createRunConfigurations by property(true)
        var openBuildWindowOnEveryBuild by property(true)
        var switchToSolutionView by property(true)

        /** Package id of a global tool -> its executable; a tool without an entry is looked up on PATH and in `~/.dotnet/tools`. */
        var toolPaths by map<String, String>()
    }

    var dotnetPath: String
        get() = state.dotnetPath.orEmpty()
        set(value) { state.dotnetPath = value.trim() }

    var createRunConfigurations: Boolean
        get() = state.createRunConfigurations
        set(value) { state.createRunConfigurations = value }

    var openBuildWindowOnEveryBuild: Boolean
        get() = state.openBuildWindowOnEveryBuild
        set(value) { state.openBuildWindowOnEveryBuild = value }

    var switchToSolutionView: Boolean
        get() = state.switchToSolutionView
        set(value) { state.switchToSolutionView = value }

    fun toolPath(tool: DotNetTool): String = state.toolPaths[tool.packageId].orEmpty()

    fun setToolPath(tool: DotNetTool, path: String) {
        val trimmed = path.trim()
        if (trimmed == toolPath(tool)) return
        // a new map: that is how BaseState notices the change
        state.toolPaths = state.toolPaths.toMutableMap().apply { if (trimmed.isEmpty()) remove(tool.packageId) else put(tool.packageId, trimmed) }
    }

    companion object {
        fun getInstance(): DotNetSettings = service()
    }
}

/** Settings | Tools | .NET */
class DotNetSettingsConfigurable(private val project: Project) : BoundConfigurable(".NET") {
    private val settings get() = DotNetSettings.getInstance()
    private val pathField = TextFieldWithBrowseButton()
    private val cliStatus = JBLabel()
    private val sdkList = JBLabel()
    private val globalJsonStatus = JBLabel()
    private val toolRows = DotNetTool.entries.associateWith { ToolRow(it) }

    /** Path field, what was found and the Install / Update button of one global tool. */
    private inner class ToolRow(val tool: DotNetTool) {
        val path = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("${tool.packageId} Executable"))
        }
        val install = javax.swing.JButton("Install").apply {
            addActionListener {
                isEnabled = false
                tool.install(project) { refresh() }
            }
        }

        fun refresh() {
            ApplicationManager.getApplication().executeOnPooledThread {
                val detected = tool.detect()
                ApplicationManager.getApplication().invokeLater({
                    (path.textField as? JBTextField)?.emptyText?.text = detected?.let { "Auto-detected: ${it.path}" } ?: "Not installed"
                    // `dotnet tool update` installs a missing tool and updates an installed one
                    install.text = if (detected == null) "Install" else "Update"
                    install.isEnabled = true
                }, ModalityState.any())
            }
        }
    }

    override fun createPanel(): DialogPanel {
        pathField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("dotnet Executable"))
        (pathField.textField as? JBTextField)?.emptyText?.text = DotNetCli.detectExecutable()?.let { "Auto-detected: $it" } ?: "Not found on PATH"

        return panel {
            group(".NET CLI") {
                row("dotnet executable:") {
                    cell(pathField).align(AlignX.FILL)
                        .comment("Empty: the one from PATH. Set it for an SDK installed per user or unpacked from an archive.")
                        .validationOnApply { if (it.text.isNotBlank() && !File(it.text.trim()).isFile) error("The file does not exist") else null }
                }
                // an empty label keeps the button in the column of the field
                row("") {
                    button("Check") { refreshInformation(pathField.text.trim()) }
                    cell(cliStatus)
                }
                row("Installed SDKs:") { cell(sdkList) }.topGap(com.intellij.ui.dsl.builder.TopGap.SMALL)
                row("global.json:") { cell(globalJsonStatus) }
                row("") { link("Support status of SDKs and runtimes, dotnet --info...") { DotNetEnvironmentDialog(project).show() } }
            }
            group(".NET Tools") {
                row {
                    comment("Global tools the plugin runs. An empty path: the tool is looked up on PATH and in ~/.dotnet/tools. Install and Update run 'dotnet tool update --global'.")
                }
                for (toolRow in toolRows.values) {
                    row(toolRow.tool.packageId + ":") {
                        // the field takes the width the button leaves
                        cell(toolRow.path).resizableColumn().align(AlignX.FILL)
                            .validationOnApply { if (it.text.isNotBlank() && !File(it.text.trim()).isFile) error("The file does not exist") else null }
                        cell(toolRow.install)
                    }.rowComment(toolRow.tool.purpose)
                }
            }
            group("Behavior") {
                row { checkBox("Create run configurations for the runnable projects of a solution").bindSelected(settings::createRunConfigurations) }
                row {
                    checkBox("Open the Build tool window on every build").bindSelected(settings::openBuildWindowOnEveryBuild)
                        .comment("When off, it opens only if the build fails")
                }
                row { checkBox("Switch the Project tool window to the Solution view when a solution is opened for the first time").bindSelected(settings::switchToSolutionView) }
            }
        }.also {
            refreshInformation(settings.dotnetPath)
            toolRows.values.forEach { it.refresh() }
        }
    }

    override fun isModified(): Boolean = super.isModified() || pathField.text.trim() != settings.dotnetPath ||
        toolRows.values.any { it.path.text.trim() != settings.toolPath(it.tool) }

    override fun apply() {
        super.apply()
        settings.dotnetPath = pathField.text
        toolRows.values.forEach { settings.setToolPath(it.tool, it.path.text) }
        refreshInformation(settings.dotnetPath)
    }

    override fun reset() {
        super.reset()
        pathField.text = settings.dotnetPath
        toolRows.values.forEach { it.path.text = settings.toolPath(it.tool) }
    }

    /** Version of the CLI at [customPath] (or of the auto-detected one), the SDKs it knows and what `global.json` asks for. */
    private fun refreshInformation(customPath: String) {
        cliStatus.text = "Checking..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val executable = customPath.ifEmpty { DotNetCli.detectExecutable().orEmpty() }
            val sdks = if (executable.isEmpty()) emptyList() else DotNetSdks.installed(executable)
            val globalJson = GlobalJson.find(project.guessProjectDir())
            ApplicationManager.getApplication().invokeLater({
                cliStatus.text = when {
                    executable.isEmpty() -> "The dotnet executable is not found"
                    sdks.isEmpty() -> "No SDKs reported by $executable"
                    else -> "$executable, newest SDK ${sdks.first().version}"
                }
                cliStatus.foreground = if (sdks.isEmpty()) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
                sdkList.text = if (sdks.isEmpty()) "none" else "<html>" + sdks.joinToString("<br>") { "${it.version} &nbsp;<span style='color:gray'>${it.location}</span>" } + "</html>"
                globalJsonStatus.text = describe(globalJson?.second, sdks.map { it.version })
            }, ModalityState.any())
        }
    }

    private fun describe(globalJson: GlobalJson?, installed: List<io.github.dotnetsupport.sdk.SdkVersion>): String {
        if (globalJson == null) return "not used by this project: the newest SDK is taken"
        val requirement = "requires ${globalJson.version ?: "any version"} (rollForward: ${globalJson.rollForward})"
        val resolved = globalJson.resolve(installed)
        return if (resolved != null) "$requirement, resolves to $resolved" else "<html>$requirement. <b>No installed SDK satisfies it.</b></html>"
    }
}
