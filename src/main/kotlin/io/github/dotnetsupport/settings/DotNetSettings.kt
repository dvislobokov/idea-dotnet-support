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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.DotNetBundle
import io.github.dotnetsupport.PluginLanguage
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.format.CSharpierLocator
import io.github.dotnetsupport.format.CSharpierUnavailable
import io.github.dotnetsupport.format.DotNetFormattingSettings
import io.github.dotnetsupport.format.FormatterChoice
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

        /** Of the settings pages: the one of the IDE, or chosen here (there is no Russian language pack for the IDE itself). */
        var language by enum(PluginLanguage.AUTO)

        /** Package id of a global tool -> its executable; a tool without an entry is looked up on PATH and in `~/.dotnet/tools`. */
        var toolPaths by map<String, String>()

        // Settings | Tools | .NET | Debugger
        /** Off, unlike in Rider: there is no decompiler behind it, stepping into code without symbols ends in frames with no source. */
        var debugExternalSource by property(false)
        var debugAllowImplicitEvaluation by property(true)
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

    var language: PluginLanguage
        get() = state.language
        set(value) { state.language = value }

    var debugExternalSource: Boolean
        get() = state.debugExternalSource
        set(value) { state.debugExternalSource = value }

    var debugAllowImplicitEvaluation: Boolean
        get() = state.debugAllowImplicitEvaluation
        set(value) { state.debugAllowImplicitEvaluation = value }

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
class DotNetSettingsConfigurable(private val project: Project) : BoundConfigurable(DotNetBundle.message("page.dotnet")) {
    private val settings get() = DotNetSettings.getInstance()
    private val pathField = TextFieldWithBrowseButton()
    private val cliStatus = JBLabel()
    private val sdkList = JBLabel()
    private val globalJsonStatus = JBLabel()
    private val toolRows = DotNetTool.entries.associateWith { ToolRow(it) }
    private val formatting get() = DotNetFormattingSettings.getInstance(project)
    private val formatterStatus = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    /** What the choice means for this project right now: which tool, which version, from where. */
    private fun refreshFormatter(choice: FormatterChoice) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val directory = project.guessProjectDir()?.let { File(it.path) }
            val text = describeFormatter(choice, directory)
            ApplicationManager.getApplication().invokeLater({ formatterStatus.text = text }, ModalityState.any())
        }
    }

    /** Path field, what was found and the Install / Update button of one global tool. */
    private inner class ToolRow(val tool: DotNetTool) {
        val path = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle(DotNetBundle.message("settings.tools.chooser", tool.packageId)))
        }
        val install = javax.swing.JButton(DotNetBundle.message("settings.tools.install")).apply { addActionListener { runInstallation() } }
        val progress = AsyncProcessIcon("installing ${tool.packageId}").apply { isVisible = false }
        val status = JBLabel().apply { isVisible = false }

        /**
         * The page lives in a modal dialog: a background task reporting to the Build tool window would be invisible, and
         * its completion callback would wait for the dialog to close. So the command runs here, and its last line is shown.
         */
        private fun runInstallation() {
            install.isEnabled = false
            progress.isVisible = true
            progress.resume()
            show(DotNetBundle.message("settings.tools.running", tool.installCommand().joinToString(" ")), isError = false)
            val output = StringBuffer()
            ApplicationManager.getApplication().executeOnPooledThread {
                val exitCode = tool.installBlocking { text ->
                    output.append(text)
                    val line = text.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: return@installBlocking
                    ApplicationManager.getApplication().invokeLater({ show(line, isError = false) }, ModalityState.any())
                }
                ApplicationManager.getApplication().invokeLater({
                    progress.suspend()
                    progress.isVisible = false
                    show(installationSummary(exitCode, output.toString()), isError = exitCode != 0)
                    status.toolTipText = "<html><pre>" + StringUtil.escapeXmlEntities(output.toString().trim()) + "</pre></html>"
                    refresh()
                }, ModalityState.any())
            }
        }

        private fun show(text: String, isError: Boolean) {
            status.text = text
            status.foreground = if (isError) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
            status.isVisible = true
        }

        fun refresh() {
            ApplicationManager.getApplication().executeOnPooledThread {
                val detected = tool.detect()
                ApplicationManager.getApplication().invokeLater({
                    (path.textField as? JBTextField)?.emptyText?.text = detected?.let { DotNetBundle.message("settings.cli.autoDetected", it.path) } ?: DotNetBundle.message("settings.tools.notInstalled")
                    // `dotnet tool update` installs a missing tool and updates an installed one
                    install.text = DotNetBundle.message(if (detected == null) "settings.tools.install" else "settings.tools.update")
                    install.isEnabled = true
                }, ModalityState.any())
            }
        }
    }

    override fun createPanel(): DialogPanel {
        pathField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle(DotNetBundle.message("settings.cli.executable.chooser")))
        (pathField.textField as? JBTextField)?.emptyText?.text = DotNetCli.detectExecutable()?.let { DotNetBundle.message("settings.cli.autoDetected", it) } ?: DotNetBundle.message("settings.cli.notOnPath")

        return panel {
            group(DotNetBundle.message("settings.cli.group")) {
                row(DotNetBundle.message("settings.cli.executable")) {
                    cell(pathField).align(AlignX.FILL)
                        .comment(DotNetBundle.message("settings.cli.executable.comment"))
                        .validationOnApply { if (it.text.isNotBlank() && !File(it.text.trim()).isFile) error(DotNetBundle.message("common.fileMissing")) else null }
                }
                // an empty label keeps the button in the column of the field
                row("") {
                    button(DotNetBundle.message("settings.cli.check")) { refreshInformation(pathField.text.trim()) }
                    cell(cliStatus)
                }
                row(DotNetBundle.message("settings.cli.sdks")) { cell(sdkList) }.topGap(com.intellij.ui.dsl.builder.TopGap.SMALL)
                row(DotNetBundle.message("settings.cli.globalJson")) { cell(globalJsonStatus) }
                row("") { link(DotNetBundle.message("settings.cli.environment")) { DotNetEnvironmentDialog(project).show() } }
            }
            group(DotNetBundle.message("settings.tools.group")) {
                row {
                    comment(DotNetBundle.message("settings.tools.comment"))
                }
                for (toolRow in toolRows.values) {
                    row(toolRow.tool.packageId + ":") {
                        // the field takes the width the button leaves
                        cell(toolRow.path).resizableColumn().align(AlignX.FILL)
                            .validationOnApply { if (it.text.isNotBlank() && !File(it.text.trim()).isFile) error(DotNetBundle.message("common.fileMissing")) else null }
                        cell(toolRow.install)
                        cell(toolRow.progress)
                    }.rowComment(DotNetBundle.messageOr("tool.purpose." + toolRow.tool.packageId, toolRow.tool.purpose))
                    // what the installation is doing and how it ended; empty until Install is pressed
                    row("") { cell(toolRow.status) }
                }
            }
            group(DotNetBundle.message("settings.formatting.group")) {
                row(DotNetBundle.message("settings.formatting.formatter")) {
                    comboBox(FormatterChoice.entries, textListCellRenderer { it?.label }).bindItem({ formatting.formatter }, { formatting.formatter = it ?: FormatterChoice.AUTO })
                        .onChanged { refreshFormatter(it.selectedItem as? FormatterChoice ?: FormatterChoice.AUTO) }
                        .comment(DotNetBundle.message("settings.formatting.comment"))
                }
                row("") { cell(formatterStatus) }
            }
            group(DotNetBundle.message("settings.behavior.group")) {
                row { checkBox(DotNetBundle.message("settings.behavior.runConfigurations")).bindSelected(settings::createRunConfigurations) }
                row {
                    checkBox(DotNetBundle.message("settings.behavior.buildWindow")).bindSelected(settings::openBuildWindowOnEveryBuild)
                        .comment(DotNetBundle.message("settings.behavior.buildWindow.comment"))
                }
                row { checkBox(DotNetBundle.message("settings.behavior.solutionView")).bindSelected(settings::switchToSolutionView) }
                row(DotNetBundle.message("settings.language")) {
                    comboBox(PluginLanguage.entries, textListCellRenderer { it?.label }).bindItem({ settings.language }, { settings.language = it ?: PluginLanguage.AUTO })
                        .comment(DotNetBundle.message("settings.language.comment"))
                }
                row { link(DotNetBundle.message("settings.documentation")) { io.github.dotnetsupport.welcome.WelcomePage.open(project, io.github.dotnetsupport.welcome.WelcomePage.GUIDE, "settings", inBrowser = true) } }
            }
        }.also {
            refreshInformation(settings.dotnetPath)
            toolRows.values.forEach { it.refresh() }
            refreshFormatter(formatting.formatter)
        }
    }

    companion object {
        /** Blocking: a global CSharpier is asked for its version. */
        fun describeFormatter(choice: FormatterChoice, directory: File?): String {
            val resolved = if (choice != FormatterChoice.AUTO) choice else if (CSharpierLocator.isUsedBy(directory)) FormatterChoice.CSHARPIER else FormatterChoice.DOTNET_FORMAT
            fun forProject(text: String) = if (choice == FormatterChoice.AUTO) DotNetBundle.message("settings.formatting.forProject", text) else text
            return when (resolved) {
                FormatterChoice.CSHARPIER -> try {
                    forProject(CSharpierLocator.find(directory).description)
                } catch (e: CSharpierUnavailable) {
                    forProject(DotNetBundle.message("settings.formatting.csharpierMissing", e.message.orEmpty().replaceFirstChar { it.lowercase() }))
                }
                FormatterChoice.DOTNET_FORMAT ->
                    forProject(DotNetBundle.message(if (choice == FormatterChoice.AUTO) "settings.formatting.dotnetFormat.auto" else "settings.formatting.dotnetFormat"))
                else -> DotNetBundle.message("settings.formatting.none")
            }
        }

        /** The last meaningful line of `dotnet tool update`: "Tool 'x' (version '1.2.3') was successfully installed." or the error. */
        fun installationSummary(exitCode: Int, output: String): String {
            val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (exitCode == 0) return lines.lastOrNull() ?: DotNetBundle.message("settings.tools.done")
            val reason = lines.lastOrNull { "error" in it.lowercase() } ?: lines.lastOrNull() ?: DotNetBundle.message("settings.tools.noOutput")
            return DotNetBundle.message("settings.tools.failed", exitCode, reason)
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
        cliStatus.text = DotNetBundle.message("settings.cli.checking")
        ApplicationManager.getApplication().executeOnPooledThread {
            val executable = customPath.ifEmpty { DotNetCli.detectExecutable().orEmpty() }
            val sdks = if (executable.isEmpty()) emptyList() else DotNetSdks.installed(executable)
            val globalJson = GlobalJson.find(project.guessProjectDir())
            ApplicationManager.getApplication().invokeLater({
                cliStatus.text = when {
                    executable.isEmpty() -> DotNetBundle.message("settings.cli.notFound")
                    sdks.isEmpty() -> DotNetBundle.message("settings.cli.noSdks", executable)
                    else -> DotNetBundle.message("settings.cli.newest", executable, sdks.first().version)
                }
                cliStatus.foreground = if (sdks.isEmpty()) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
                sdkList.text = if (sdks.isEmpty()) DotNetBundle.message("settings.cli.none") else "<html>" + sdks.joinToString("<br>") { "${it.version} &nbsp;<span style='color:gray'>${it.location}</span>" } + "</html>"
                globalJsonStatus.text = describe(globalJson?.second, sdks.map { it.version })
            }, ModalityState.any())
        }
    }

    private fun describe(globalJson: GlobalJson?, installed: List<io.github.dotnetsupport.sdk.SdkVersion>): String {
        if (globalJson == null) return DotNetBundle.message("settings.globalJson.notUsed")
        val requirement = DotNetBundle.message("settings.globalJson.requires", globalJson.version ?: DotNetBundle.message("settings.globalJson.anyVersion"), globalJson.rollForward)
        val resolved = globalJson.resolve(installed)
        return if (resolved != null) DotNetBundle.message("settings.globalJson.resolves", requirement, resolved) else DotNetBundle.message("settings.globalJson.unsatisfied", requirement)
    }
}
