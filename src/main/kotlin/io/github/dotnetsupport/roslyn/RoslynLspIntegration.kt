package io.github.dotnetsupport.roslyn

import com.intellij.application.options.CodeStyle
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItem
import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItemsProvider
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.customization.LspCodeLensCustomizer
import com.intellij.platform.lsp.api.customization.LspCodeLensSupport
import com.intellij.platform.lsp.api.customization.LspCommandsCustomizer
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDiagnosticsCustomizer
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsCustomizer
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsSupport
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.customization.LspOnTypeFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspOnTypeFormattingSupport
import com.intellij.platform.lsp.api.customization.LspRenameCustomizer
import com.intellij.platform.lsp.api.customization.LspRenameSupport
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.format.DotNetFormattingSettings
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lsp.RoslynCodeStyle
import io.github.dotnetsupport.lsp.RoslynLanguageServer
import io.github.dotnetsupport.lsp.RoslynLanguageServerConfigurable
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynPolicy
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.services.LanguageServer
import java.awt.event.MouseEvent
import java.io.File
import java.nio.charset.StandardCharsets

private val LOG = logger<RoslynLspIntegrationProvider>()

fun isCSharpSource(file: VirtualFile): Boolean = !file.isDirectory && file.extension.equals("cs", ignoreCase = true)

/** Starts `roslyn-language-server` on the platform LSP client when the first C# file of the opened folder shows up in an editor. */
class RoslynLspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
        if (!isCSharpSource(file) || !RoslynLanguageServerSettings.getInstance().state.enabled) return
        // every test that opens a C# file would start the real server of the machine
        if (ApplicationManager.getApplication().isUnitTestMode && !startInTests) return
        val root = project.guessProjectDir() ?: return
        if (!VfsUtilCore.isAncestor(root, file, true)) return
        val executable = DotNetTool.ROSLYN_LANGUAGE_SERVER.find()
        val workspace = project.service<RoslynWorkspace>()
        if (executable == null) return workspace.offerInstallation()
        workspace.wrapServer()
        clientStarter.ensureClientStarted(RoslynClientDescriptor(project, root, executable))
    }

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem = RoslynWidgetItem(lspClient, currentFile)

    companion object {
        /** For a test of [fileOpened] itself, with a starter that starts nothing. */
        @Volatile
        var startInTests: Boolean = false
    }
}

/** The line of the server in the widget of language services: what it loads or waits for, next to Restart and the settings of the platform. */
class RoslynWidgetItem(client: LspClient, file: VirtualFile?) : LspClientWidgetItem(client, file, DotNetIcons.CSharp, RoslynLanguageServerConfigurable::class.java) {
    private val workspace get() = lspClient.project.service<RoslynWorkspace>()

    override val versionPostfix: String get() = RoslynPolicy.statusText(workspace.phase, workspace.target)

    override fun createAdditionalInlineActions(): List<AnAction> = buildList {
        if (workspace.knownSolutions.size > 1) add(DumbAwareAction.create("Select Solution...", AllIcons.Actions.ListFiles) { workspace.chooseSolution() })
        add(DumbAwareAction.create("Show Log", AllIcons.FileTypes.Text) { workspace.showLog() })
    }
}

/**
 * The platform redraws the widget of language services when a server starts or stops; what Roslyn is loading changes in between.
 * A provider of that widget gets the function that redraws it, and that is all this one is for: it has no items of its own.
 */
class RoslynWidgetUpdater : LanguageServiceWidgetItemsProvider() {
    override fun createWidgetItems(project: Project, currentFile: VirtualFile?): List<LanguageServiceWidgetItem> = emptyList()

    override fun registerWidgetUpdaters(project: Project, widgetDisposable: Disposable, updateWidget: () -> Unit) {
        val workspace = project.service<RoslynWorkspace>()
        workspace.statusChanged = updateWidget
        Disposer.register(widgetDisposable) { workspace.statusChanged = {} }
    }
}

/** One server per opened folder: Roslyn holds one solution, and the plugin shows one. */
class RoslynClientDescriptor(project: Project, private val root: VirtualFile, private val executable: File) : LspClientDescriptor(project, "Roslyn", root) {
    private val workspace get() = project.service<RoslynWorkspace>()

    override fun isSupportedFile(file: VirtualFile): Boolean = isCSharpSource(file)

    override fun getLanguageId(file: VirtualFile): String = "csharp"

    override fun createCommandLine(): GeneralCommandLine {
        val settings = RoslynLanguageServerSettings.getInstance().state
        val logDirectory = RoslynLanguageServerConfigurable.defaultLogDirectory().apply { mkdirs() }
        // a solution is opened by the plugin, so the server is not told to look for one: the folder is walked here, once per start
        val solutionFound = workspace.scan().solutions.isNotEmpty()
        return GeneralCommandLine(executable.path)
            .withParameters(RoslynLanguageServer.arguments(settings, logDirectory.path, ProcessHandle.current().pid(), solutionFound))
            .withWorkDirectory(root.path).withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en")
    }

    /**
     * The platform writes `file:///c%3A/...` as VS Code does, and server 5.12 then takes the document for a loose file outside the
     * solution (no errors of the compiler, every `using` "unnecessary") and the workspace folder for a missing `/c:/...`. Checked with
     * `tools/roslyn-lsp`: a plain colon is what it maps to the loaded projects.
     */
    override fun getFileUri(file: VirtualFile): String = RoslynLanguageServer.plainDriveUri(super.getFileUri(file))

    override fun createInitializeParams(): InitializeParams = super.createInitializeParams().apply {
        workspaceFolders = workspaceFolders?.map { WorkspaceFolder(RoslynLanguageServer.plainDriveUri(it.uri), it.name) }
        // code lenses and messages in the language of the UI of the plugin, not of the OS
        locale = "en"
    }

    override val lsp4jServerClass: Class<out LanguageServer> = RoslynServer::class.java

    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient = RoslynLsp4jClient(handler, workspace).also { workspace.lsp4jClient = it }

    /** The server asks section by section; what is neither on the settings page nor in the code style is left to its default. */
    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any? =
        RoslynLanguageServer.configuration(listOf(item.section), RoslynLanguageServerSettings.getInstance(), codeStyle()).single()

    private fun codeStyle(): RoslynCodeStyle {
        val indent = CodeStyle.getSettings(project).getIndentOptions(CSharpFileType)
        return RoslynCodeStyle(indent.TAB_SIZE, indent.INDENT_SIZE, indent.USE_TAB_CHARACTER, endOfLine = null, insertFinalNewline = null)
    }

    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) = workspace.serverInitialized()
        override fun serverStopped(shutdownNormally: Boolean) = workspace.serverStopped(shutdownNormally)
    }

    override val lspCustomization: LspCustomization = object : LspCustomization() {
        // a half-loaded workspace reports every type of another project as an error
        override val diagnosticsCustomizer: LspDiagnosticsCustomizer = object : LspDiagnosticsSupport() {
            override fun shouldAskServerForDiagnostics(file: VirtualFile): Boolean = workspace.isLoaded
        }

        // Three defaults of the platform are "only for plain text and TextMate files": semantic tokens (below), rename and the
        // highlighting of the usages under the caret. C# is a language of the plugin, so without these Shift+F6 finds no handler at all.
        override val renameCustomizer: LspRenameCustomizer = object : LspRenameSupport() {
            override fun shouldRunRename(psiFile: PsiFile): Boolean = true
        }
        override val documentHighlightsCustomizer: LspDocumentHighlightsCustomizer = object : LspDocumentHighlightsSupport() {
            override fun shouldAskServerForDocumentHighlights(psiFile: PsiFile): Boolean = workspace.isLoaded
        }

        // "N references", "Fix All", code actions with variants: commands the server leaves to its client
        private val commands = RoslynClientCommands()
        override val commandsCustomizer: LspCommandsCustomizer = commands

        // a click on "N references" does not go through the customizer above: a code lens has a way of its own
        override val codeLensCustomizer: LspCodeLensCustomizer = object : LspCodeLensSupport() {
            override fun codeLensClicked(lspClient: LspClient, contextFile: VirtualFile, command: Command, mouseEvent: MouseEvent?) = commands.executeCommand(lspClient, contextFile, command)

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun codeLensClicked(lspServer: LspServer, contextFile: VirtualFile, command: Command, mouseEvent: MouseEvent?) = commands.executeCommand(lspServer as LspClient, contextFile, command)
        }

        // the palette of the plugin (the one of Rider), so a file looks the same before and after the server is ready
        override val semanticTokensCustomizer: LspSemanticTokensCustomizer = object : LspSemanticTokensSupport() {
            // the default asks only for plain text and TextMate files, and C# is a language of the plugin
            override fun shouldAskServerForSemanticTokens(psiFile: PsiFile): Boolean = workspace.isLoaded || psiFile.virtualFile?.let(workspace::hasCachedTokens) == true

            override fun getTextAttributesKey(tokenType: String, modifiers: List<String>): TextAttributesKey? = RoslynPolicy.textAttributesKey(tokenType)
        }

        // the server is the authority on the layout of code: it corrects a statement on `;`, a block on `}`, a line on Enter
        override val onTypeFormattingCustomizer: LspOnTypeFormattingCustomizer = LspOnTypeFormattingSupport()

        // Reformat Code: the server does the work of `dotnet format whitespace`; CSharpier and "None" stay what the project has chosen
        override val formattingCustomizer: LspFormattingCustomizer = object : LspFormattingSupport() {
            override fun shouldFormatThisFileExclusivelyByServer(file: VirtualFile, ideCanFormatThisFileItself: Boolean, serverExplicitlyWantsToFormatThisFile: Boolean): Boolean =
                RoslynPolicy.formatsByServer(DotNetFormattingSettings.getInstance(project).resolve(file), workspace.isLoaded)
        }
    }
}

/** Settings | Tools | .NET | Language Server was applied: registered in the descriptor of the module, so it works before any server has started. */
class RoslynSettingsListener : RoslynLanguageServerSettings.Listener {
    override fun settingsChanged(restart: Boolean) = ProjectManager.getInstance().openProjects.forEach { it.service<RoslynWorkspace>().settingsChanged(restart) }
}
