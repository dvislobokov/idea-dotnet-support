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
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.DotNetBundle
import io.github.dotnetsupport.PluginLanguage
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.format.CSharpierLocator
import io.github.dotnetsupport.format.CSharpierUnavailable
import io.github.dotnetsupport.format.DotNetFormattingSettings
import io.github.dotnetsupport.format.FormatterChoice
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatures
import io.github.dotnetsupport.lang.palette.CSharpPaletteChoice
import io.github.dotnetsupport.lang.palette.CSharpPaletteService
import io.github.dotnetsupport.lang.palette.CSharpPalettes
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
        /** Extra directories to scan for a `dotnet` host, ahead of PATH; see [io.github.dotnetsupport.cli.DotNetSearch]. */
        var dotnetSearchPaths by list<String>()
        var createRunConfigurations by property(true)
        var openBuildWindowOnEveryBuild by property(true)
        var switchToSolutionView by property(true)

        /** Types and namespaces (`System.Data.*`) the C# completion never offers, see [io.github.dotnetsupport.lang.CSharpCompletionExclusions]. */
        var completionExclusions by list<String>()
        /** Matching brackets of C# by depth, see [io.github.dotnetsupport.lang.CSharpBracketColors]. */
        var colorizeBrackets by property(true)
        /** `Name = user.Name,` rows in initializers and assignment blocks, see [io.github.dotnetsupport.lang.NativeCSharpMappingCompletion] (0.1.134). */
        var mappingCompletion by property(true)
        /** The per-project memory of chosen completion items, see [io.github.dotnetsupport.suggest.CSharpAcceptanceMemory] (0.1.135). */
        var rememberChoices by property(true)
        /** The order of the namespaces of the «Import type» fix and of the not-imported types in the list, see [io.github.dotnetsupport.ml.CSharpImportStats] (0.1.138). */
        var importStatistics by property(true)
        /** No type hint after `var` when the initializer says it (`new T()`, a literal, a cast, an enum member), see [io.github.dotnetsupport.lang.NativeCSharpInlayHints] (0.1.152). */
        var hideObviousTypeHints by property(true)

        /** Of the settings pages: the one of the IDE, or chosen here (there is no Russian language pack for the IDE itself). */
        var language by enum(PluginLanguage.AUTO)

        /** Package id of a global tool -> its executable; a tool without an entry is looked up on PATH and in `~/.dotnet/tools`. */
        var toolPaths by map<String, String>()

        // Settings | .NET | Debugger
        /** Off, unlike in Rider: there is no decompiler behind it, stepping into code without symbols ends in frames with no source. */
        var debugExternalSource by property(false)
        var debugAllowImplicitEvaluation by property(true)

        // Settings | .NET | Analyzers and Generators (codeanalysis/CodeAnalysisService)
        var runSourceGenerators by property(true)
        var runAnalyzersOnSave by property(true)
        /**
         * Diagnostics of severity Info (Rider's suggestions: IDE0290, CA1859…) as weak warnings. Off by default since 0.1.82, as VS and Rider
         * show only warnings and errors loudly: an Info is then no annotation to see, only its code fixes on Alt+Enter at the caret.
         */
        var showAnalyzerSuggestions by property(false)
        var codeAnalysisIdleMinutes by property(DEFAULT_IDLE_MINUTES)
    }

    var runSourceGenerators: Boolean
        get() = state.runSourceGenerators
        set(value) { state.runSourceGenerators = value }

    var runAnalyzersOnSave: Boolean
        get() = state.runAnalyzersOnSave
        set(value) { state.runAnalyzersOnSave = value }

    var showAnalyzerSuggestions: Boolean
        get() = state.showAnalyzerSuggestions
        set(value) { state.showAnalyzerSuggestions = value }

    var codeAnalysisIdleMinutes: Int
        get() = state.codeAnalysisIdleMinutes.coerceIn(1, 240)
        set(value) { state.codeAnalysisIdleMinutes = value.coerceIn(1, 240) }

    var dotnetPath: String
        get() = state.dotnetPath.orEmpty()
        set(value) { state.dotnetPath = value.trim() }

    var dotnetSearchPaths: List<String>
        get() = state.dotnetSearchPaths.toList()
        set(value) {
            val trimmed = value.map { it.trim() }.filter { it.isNotEmpty() }
            if (trimmed == state.dotnetSearchPaths) return
            // a new list: that is how BaseState notices the change (as with toolPaths)
            state.dotnetSearchPaths = trimmed.toMutableList()
        }

    var mappingCompletion: Boolean
        get() = state.mappingCompletion
        set(value) { state.mappingCompletion = value }

    var rememberChoices: Boolean
        get() = state.rememberChoices
        set(value) { state.rememberChoices = value }

    var importStatistics: Boolean
        get() = state.importStatistics
        set(value) { state.importStatistics = value }

    var hideObviousTypeHints: Boolean
        get() = state.hideObviousTypeHints
        set(value) { state.hideObviousTypeHints = value }

    var completionExclusions: List<String>
        get() = state.completionExclusions.toList()
        set(value) {
            val trimmed = value.map { it.trim() }.filter { it.isNotEmpty() }
            if (trimmed == state.completionExclusions) return
            // a new list: that is how BaseState notices the change
            state.completionExclusions = trimmed.toMutableList()
        }

    var colorizeBrackets: Boolean
        get() = state.colorizeBrackets
        set(value) { state.colorizeBrackets = value }

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
        const val DEFAULT_IDLE_MINUTES = 10

        fun getInstance(): DotNetSettings = service()
    }
}

/** Settings | .NET */
class DotNetSettingsConfigurable(private val project: Project) : BoundConfigurable(DotNetBundle.message("page.dotnet")) {
    private val settings get() = DotNetSettings.getInstance()
    private val pathField = TextFieldWithBrowseButton()
    private val searchPathsModel = com.intellij.ui.CollectionListModel<String>()
    private val searchPaths = com.intellij.ui.components.JBList(searchPathsModel).apply { visibleRowCount = 3 }
    private val cliStatus = JBLabel()
    private val sdkList = JBLabel()
    private val globalJsonStatus = JBLabel()
    private val choicesStatus = JBLabel()
    private val toolRows = DotNetTool.entries.associateWith { ToolRow(it) }
    private val formatting get() = DotNetFormattingSettings.getInstance(project)
    // a comment of the DSL, not a label: it wraps at the width of the page, a label makes the page as wide as its text
    private lateinit var formatterStatus: javax.swing.JEditorPane

    /** What the choice means for this project right now: which tool, which version, from where. */
    private fun refreshFormatter(choice: FormatterChoice) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val directory = project.guessProjectDir()?.let { File(it.path) }
            val text = describeFormatter(choice, directory, CSharpFeatures.native(CSharpFeature.FORMATTING, project))
            ApplicationManager.getApplication().invokeLater({ formatterStatus.text = text }, ModalityState.any())
        }
    }

    /** Path field, the Install / Update button and, under them, where the tool was found, of one global tool. */
    private inner class ToolRow(val tool: DotNetTool) {
        val path = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle(DotNetBundle.message("settings.tools.chooser", tool.packageId)))
        }
        val install = javax.swing.JButton(DotNetBundle.message("settings.tools.install")).apply { addActionListener { runInstallation() } }
        lateinit var status: javax.swing.JEditorPane

        /**
         * A progress in the status bar of the IDE, as every other long command of the plugin has, and the last line of the output here:
         * the page is a modal dialog, which hides both the Build tool window and the status bar behind it.
         */
        private fun runInstallation() {
            install.isEnabled = false
            show(DotNetBundle.message("settings.tools.running", tool.installCommand().joinToString(" ")), isError = false)
            val title = DotNetBundle.message(if (tool.find() == null) "settings.tools.installing" else "settings.tools.updating", tool.packageId)
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "dotnet " + tool.installCommand().joinToString(" ")
                    val output = StringBuffer()
                    val exitCode = tool.installBlocking { text ->
                        output.append(text)
                        val line = text.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: return@installBlocking
                        indicator.text2 = line
                        ApplicationManager.getApplication().invokeLater({ show(line, isError = false) }, ModalityState.any())
                    }
                    ApplicationManager.getApplication().invokeLater({
                        install.isEnabled = true
                        status.toolTipText = "<html><pre>" + StringUtil.escapeXmlEntities(output.toString().trim()) + "</pre></html>"
                        if (exitCode == 0) refresh() else show(installationSummary(exitCode, output.toString()), isError = true)
                    }, ModalityState.any())
                }
            })
        }

        private fun show(text: String, isError: Boolean) {
            status.text = text
            status.foreground = if (isError) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
        }

        /** Off the EDT: the lookup walks PATH. The field holds an override and stays empty while the plugin finds the tool itself. */
        fun refresh() {
            ApplicationManager.getApplication().executeOnPooledThread {
                val found = tool.find()
                val configured = tool.isConfigured()
                val onPath = found != null && !configured && com.intellij.execution.configurations.PathEnvironmentVariableUtil.findInPath(found.name)?.path == found.path
                ApplicationManager.getApplication().invokeLater({
                    show(
                        when {
                            found == null -> DotNetBundle.message("settings.tools.notInstalled", tool.installCommand().joinToString(" "))
                            configured -> DotNetBundle.message("settings.tools.fromSettings")
                            onPath -> DotNetBundle.message("settings.tools.onPath")
                            else -> DotNetBundle.message("settings.tools.inDirectory", found.parent)
                        },
                        isError = false,
                    )
                    // the path the plugin found is the text of the empty field, as the dotnet executable above shows its own
                    (path.textField as? JBTextField)?.emptyText?.text = found?.path ?: DotNetBundle.message("settings.tools.missing")
                    // `dotnet tool update` installs a missing tool and updates an installed one
                    install.text = DotNetBundle.message(if (found == null) "settings.tools.install" else "settings.tools.update")
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
                        .comment(DotNetBundle.message("settings.cli.executable.comment"), maxLineLength = COMMENT_WIDTH)
                        .validationOnApply { if (it.text.isNotBlank() && !File(it.text.trim()).isFile) error(DotNetBundle.message("common.fileMissing")) else null }
                }
                // an empty label keeps the button in the column of the field
                row("") {
                    button(DotNetBundle.message("settings.cli.check")) { refreshInformation(pathField.text.trim()) }
                    cell(cliStatus)
                }
                row(DotNetBundle.message("settings.cli.searchPaths")) {
                    val decorator = com.intellij.ui.ToolbarDecorator.createDecorator(searchPaths).setAddAction {
                        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(DotNetBundle.message("settings.cli.searchPaths.chooser"))
                        com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null)?.let { if (searchPathsModel.getElementIndex(it.path) < 0) searchPathsModel.add(it.path) }
                    }
                    cell(decorator.createPanel()).align(AlignX.FILL).comment(DotNetBundle.message("settings.cli.searchPaths.comment"), maxLineLength = COMMENT_WIDTH)
                }.topGap(com.intellij.ui.dsl.builder.TopGap.SMALL)
                row(DotNetBundle.message("settings.cli.sdks")) { cell(sdkList) }.topGap(com.intellij.ui.dsl.builder.TopGap.SMALL)
                row(DotNetBundle.message("settings.cli.globalJson")) { cell(globalJsonStatus) }
                row("") { link(DotNetBundle.message("settings.cli.environment")) { DotNetEnvironmentDialog(project).show() } }
            }
            group(DotNetBundle.message("settings.tools.group")) {
                row {
                    comment(DotNetBundle.message("settings.tools.comment"), maxLineLength = COMMENT_WIDTH)
                }
                for (toolRow in toolRows.values) {
                    row(toolRow.tool.packageId + ":") {
                        // resizableColumn: in a row of several cells the free width goes to the one that asks for it, and without it
                        // the field keeps its preferred size while the page grows
                        cell(toolRow.path).resizableColumn().align(AlignX.FILL)
                            .validationOnApply { if (it.text.isNotBlank() && !File(it.text.trim()).isFile) error(DotNetBundle.message("common.fileMissing")) else null }
                            .comment(DotNetBundle.messageOr("tool.purpose." + toolRow.tool.packageId, toolRow.tool.purpose), maxLineLength = COMMENT_WIDTH)
                        cell(toolRow.install).align(AlignY.TOP)
                    }
                    // where the tool was found (a path of its own line: it does not fit the placeholder of the field), or how to get it;
                    // during an installation — what the command is saying
                    row("") { toolRow.status = comment("", maxLineLength = COMMENT_WIDTH).component }
                }
            }
            group(DotNetBundle.message("settings.formatting.group")) {
                row(DotNetBundle.message("settings.formatting.formatter")) {
                    comboBox(FormatterChoice.entries, textListCellRenderer { it?.label }).bindItem({ formatting.formatter }, { formatting.formatter = it ?: FormatterChoice.AUTO })
                        .onChanged { refreshFormatter(it.selectedItem as? FormatterChoice ?: FormatterChoice.AUTO) }
                        .comment(DotNetBundle.message("settings.formatting.comment"), maxLineLength = COMMENT_WIDTH)
                }
                row("") { formatterStatus = comment("", maxLineLength = COMMENT_WIDTH).component }
            }
            group(DotNetBundle.message("settings.behavior.group")) {
                row { checkBox(DotNetBundle.message("settings.behavior.runConfigurations")).bindSelected(settings::createRunConfigurations) }
                row {
                    checkBox(DotNetBundle.message("settings.behavior.buildWindow")).bindSelected(settings::openBuildWindowOnEveryBuild)
                        .comment(DotNetBundle.message("settings.behavior.buildWindow.comment"), maxLineLength = COMMENT_WIDTH)
                }
                row { checkBox(DotNetBundle.message("settings.behavior.solutionView")).bindSelected(settings::switchToSolutionView).comment(DotNetBundle.message("settings.behavior.solutionView.comment"), maxLineLength = COMMENT_WIDTH) }
                row { checkBox(DotNetBundle.message("settings.behavior.bracketColors")).bindSelected(settings::colorizeBrackets).comment(DotNetBundle.message("settings.behavior.bracketColors.comment"), maxLineLength = COMMENT_WIDTH) }
                row { checkBox(DotNetBundle.message("settings.behavior.mapping")).bindSelected(settings::mappingCompletion).comment(DotNetBundle.message("settings.behavior.mapping.comment"), maxLineLength = COMMENT_WIDTH) }
                row { checkBox(DotNetBundle.message("settings.behavior.hideObviousHints")).bindSelected(settings::hideObviousTypeHints).comment(DotNetBundle.message("settings.behavior.hideObviousHints.comment"), maxLineLength = COMMENT_WIDTH) }
                row { checkBox(DotNetBundle.message("settings.behavior.importStats")).bindSelected(settings::importStatistics).comment(DotNetBundle.message("settings.behavior.importStats.comment"), maxLineLength = COMMENT_WIDTH) }
                row {
                    checkBox(DotNetBundle.message("settings.behavior.rememberChoices")).bindSelected(settings::rememberChoices).comment(DotNetBundle.message("settings.behavior.rememberChoices.comment"), maxLineLength = COMMENT_WIDTH)
                }
                row("") {
                    button(DotNetBundle.message("settings.behavior.resetChoices")) {
                        io.github.dotnetsupport.suggest.CSharpAcceptanceMemory.getInstance(project).reset()
                        choicesStatus.text = DotNetBundle.message("settings.behavior.resetChoices.done")
                    }
                    cell(choicesStatus)
                }
                row(DotNetBundle.message("settings.language")) {
                    comboBox(PluginLanguage.entries, textListCellRenderer { it?.label }).bindItem({ settings.language }, { settings.language = it ?: PluginLanguage.AUTO })
                        .comment(DotNetBundle.message("settings.language.comment"), maxLineLength = COMMENT_WIDTH)
                }
                row(DotNetBundle.message("settings.palette")) {
                    val palettes = CSharpPaletteService.getInstance()
                    comboBox(CSharpPaletteChoice.all(), textListCellRenderer { it?.name })
                        .bindItem({ CSharpPaletteChoice.of(palettes.paletteId) }, { palettes.choose(it?.id ?: CSharpPalettes.DEFAULT_ID) })
                        .comment(DotNetBundle.message("settings.palette.comment"), maxLineLength = COMMENT_WIDTH)
                }
                row(DotNetBundle.message("settings.completion.exclude")) {
                    textArea().rows(4).align(AlignX.FILL)
                        .bindText({ settings.completionExclusions.joinToString("\n") }, { settings.completionExclusions = io.github.dotnetsupport.lang.CSharpCompletionExclusions.parse(it) })
                        .comment(DotNetBundle.message("settings.completion.exclude.comment"), maxLineLength = COMMENT_WIDTH)
                }.topGap(com.intellij.ui.dsl.builder.TopGap.SMALL)
                row { link(DotNetBundle.message("settings.documentation")) { io.github.dotnetsupport.welcome.WelcomePage.open(project, io.github.dotnetsupport.welcome.WelcomePage.GUIDE, "settings", inBrowser = true) } }
            }
        }.also {
            choicesStatus.text = DotNetBundle.message("settings.behavior.resetChoices.count", io.github.dotnetsupport.suggest.CSharpAcceptanceMemory.getInstance(project).size())
            refreshInformation(settings.dotnetPath)
            toolRows.values.forEach { it.refresh() }
            refreshFormatter(formatting.formatter)
        }
    }

    companion object {
        /**
         * Characters per line of a comment of this page. The 70 of the DSL make a comment 560 px wide, and with the column of labels
         * the page asks for 840: a Settings dialog of a laptop got a horizontal scroll bar (seen live). The status rows use it too.
         */
        private const val COMMENT_WIDTH = 56

        /** Blocking: a global CSharpier is asked for its version. [builtIn]: what "Auto" comes to past CSharpier (`CSharpFeature.FORMATTING`). */
        fun describeFormatter(choice: FormatterChoice, directory: File?, builtIn: Boolean = false): String {
            val resolved = when {
                choice != FormatterChoice.AUTO -> choice
                CSharpierLocator.isUsedBy(directory) -> FormatterChoice.CSHARPIER
                builtIn -> FormatterChoice.BUILT_IN
                else -> FormatterChoice.DOTNET_FORMAT
            }
            fun forProject(text: String) = if (choice == FormatterChoice.AUTO) DotNetBundle.message("settings.formatting.forProject", text) else text
            return when (resolved) {
                FormatterChoice.CSHARPIER -> try {
                    forProject(CSharpierLocator.find(directory).description)
                } catch (e: CSharpierUnavailable) {
                    forProject(DotNetBundle.message("settings.formatting.csharpierMissing", e.message.orEmpty().replaceFirstChar { it.lowercase() }))
                }
                FormatterChoice.DOTNET_FORMAT ->
                    forProject(DotNetBundle.message(if (choice == FormatterChoice.AUTO) "settings.formatting.dotnetFormat.auto" else "settings.formatting.dotnetFormat"))
                FormatterChoice.BUILT_IN -> forProject(DotNetBundle.message("settings.formatting.builtIn"))
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
        searchPathsModel.items != settings.dotnetSearchPaths ||
        toolRows.values.any { it.path.text.trim() != settings.toolPath(it.tool) }

    override fun apply() {
        val bracketColors = settings.colorizeBrackets
        super.apply()
        if (settings.colorizeBrackets != bracketColors) io.github.dotnetsupport.lang.CSharpBracketColors.rehighlight()
        settings.dotnetPath = pathField.text
        settings.dotnetSearchPaths = searchPathsModel.items
        toolRows.values.forEach { settings.setToolPath(it.tool, it.path.text) }
        refreshInformation(settings.dotnetPath)
    }

    override fun reset() {
        super.reset()
        pathField.text = settings.dotnetPath
        searchPathsModel.replaceAll(settings.dotnetSearchPaths)
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
