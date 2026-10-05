package io.github.dotnetsupport.roslyn

import io.github.dotnetsupport.lang.NativeCSharpDiagnostics
import org.eclipse.lsp4j.Diagnostic
import com.intellij.openapi.util.TextRange
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.application.options.CodeStyle
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItem
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.customization.LspCodeActionsCustomizer
import com.intellij.platform.lsp.api.customization.LspCodeLensCustomizer
import com.intellij.platform.lsp.api.customization.LspCompletionCustomizer
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
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.NativeCSharpRename
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.cli.PluginLog
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


// not the metadata view of an assembly (B4): a file of no project, the server has nothing to say about it
fun isCSharpSource(file: VirtualFile): Boolean = !file.isDirectory && file.extension.equals("cs", ignoreCase = true) && !io.github.dotnetsupport.index.AssemblyNavigation.isMetadata(file)

/** Starts `roslyn-language-server` on the platform LSP client when the first C# file of the opened folder shows up in an editor. */
class RoslynLspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
        if (!isCSharpSource(file)) return
        val root = project.guessProjectDir() ?: return
        if (!VfsUtilCore.isAncestor(root, file, true)) return
        descriptor(project)?.let(clientStarter::ensureClientStarted)
    }

    /**
     * No row in the widget of language services: the server has a widget of its own in the status bar ([RoslynStatusWidget]), there
     * whatever file is open, and two icons of one server next to each other said nothing more than one (asked by the user).
     */
    override fun createWidgetItems(project: Project, currentFile: VirtualFile?): List<LanguageServiceWidgetItem> = emptyList()

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem? = null

    companion object {
        /** For a test of [fileOpened] itself, with a starter that starts nothing. */
        @Volatile
        var startInTests: Boolean = false

        /** The descriptor of the server of the opened folder, or null when the server is switched off or not installed (then the installation is offered). */
        fun descriptor(project: Project): RoslynClientDescriptor? {
            if (!RoslynLanguageServerSettings.getInstance().state.enabled) return null
            // every test that opens a C# file would start the real server of the machine
            if (ApplicationManager.getApplication().isUnitTestMode && !startInTests) return null
            val root = project.guessProjectDir() ?: return null
            val workspace = project.service<RoslynWorkspace>()
            val executable = DotNetTool.ROSLYN_LANGUAGE_SERVER.find() ?: run {
                PluginLog.warn(RoslynWorkspace.LOG_CATEGORY, "${DotNetTool.ROSLYN_LANGUAGE_SERVER.packageId} is not installed: the server is not started, the installation is offered")
                workspace.offerInstallation()
                return null
            }
            workspace.wrapServer()
            return RoslynClientDescriptor(project, root, executable)
        }

        /** Starts the server of the folder when it is not running: at the opening of the project ([RoslynStartupActivity]), not only of a file. */
        fun ensureStarted(project: Project) {
            val descriptor = descriptor(project) ?: return
            LspClientManager.getInstance(project).ensureClientStarted(RoslynLspIntegrationProvider::class.java, descriptor)
        }
    }
}

/** One server per opened folder: Roslyn holds one solution, and the plugin shows one. */
class RoslynClientDescriptor(project: Project, private val root: VirtualFile, private val executable: File) : LspClientDescriptor(project, "Roslyn", root) {
    /**
     * Taken once, while the project is open: the process of the server ends (and prints its last lines) after the project is closed,
     * and a lookup of the service then threw `AlreadyDisposedException` from the listeners of the process.
     */
    private val workspace: RoslynWorkspace = project.service<RoslynWorkspace>()

    override fun isSupportedFile(file: VirtualFile): Boolean = isCSharpSource(file)

    override fun getLanguageId(file: VirtualFile): String = "csharp"

    override fun createCommandLine(): GeneralCommandLine {
        val settings = RoslynLanguageServerSettings.getInstance().state
        val logDirectory = RoslynLanguageServerConfigurable.defaultLogDirectory().apply { mkdirs() }
        // a solution is opened by the plugin, so the server is not told to look for one: the folder is walked here, once per start
        val solutionFound = workspace.scan().solutions.isNotEmpty()
        val command = GeneralCommandLine(executable.path)
            .withParameters(RoslynLanguageServer.arguments(settings, logDirectory.path, ProcessHandle.current().pid(), solutionFound))
            .withWorkDirectory(root.path).withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en")
        // the host of the tool must find the runtime where the `dotnet` of the plugin is, not in whatever installation the machine registers
        val dotnetRoot = RoslynPolicy.dotnetRoot(command.parentEnvironment["DOTNET_ROOT"], DotNetCli.findExecutable())
        if (dotnetRoot != null) command.withEnvironment("DOTNET_ROOT", dotnetRoot)
        PluginLog.info(RoslynWorkspace.LOG_CATEGORY, "starting: ${command.commandLineString}\n  in ${root.path}, DOTNET_ROOT=${dotnetRoot ?: "not set"}, log: ${logDirectory.path}")
        return command
    }

    /** The process itself is kept: the widget of the status bar shows what it costs, and what it prints to its error stream explains a crash. */
    override fun startServerProcess(): com.intellij.execution.process.BaseProcessHandler<*> {
        val handler = try {
            super.startServerProcess()
        } catch (e: Exception) {
            workspace.serverFailedToStart(e)
            throw e
        }
        workspace.serverStarted(runCatching { handler.process.toHandle() }.getOrNull())
        handler.addProcessListener(processListener())
        return handler
    }

    /** What the process prints to its error stream and its end, for the workspace; called after the project is closed too. */
    internal fun processListener(): com.intellij.execution.process.ProcessListener = object : com.intellij.execution.process.ProcessListener {
        override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
            if (outputType === com.intellij.execution.process.ProcessOutputTypes.STDERR) workspace.serverPrinted(event.text)
        }

        override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) = workspace.serverExited(event.exitCode)
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
        override fun serverInitialized(params: InitializeResult) {
            if (!project.isDisposed) workspace.serverInitialized()
        }

        override fun serverStopped(shutdownNormally: Boolean) = workspace.serverStopped(shutdownNormally)
    }

    override val lspCustomization: LspCustomization = object : LspCustomization() {
        // a half-loaded workspace reports every type of another project as an error
        override val diagnosticsCustomizer: LspDiagnosticsCustomizer = object : LspDiagnosticsSupport() {
            // whatever the switch of DIAGNOSTICS: with NATIVE the tree reports only the syntax errors, the semantic ones are still the server's
            override fun shouldAskServerForDiagnostics(file: VirtualFile): Boolean = workspace.isLoaded

            // with NATIVE a syntax error the tree shows too is shown once, by NativeCSharpDiagnosticsAnnotator. The platform makes these
            // annotations when the server answers, not on every pass: a switch of «Errors and warnings» asks it to make them again
            // (RoslynWorkspace.highlightingSwitched)
            override fun createAnnotation(holder: AnnotationHolder, diagnostic: Diagnostic, textRange: TextRange, quickFixes: List<IntentionAction>) {
                if (NativeCSharpDiagnostics.repeatsNative(holder.currentAnnotationSession.file, diagnostic.message, textRange.startOffset)) return
                super.createAnnotation(holder, diagnostic, textRange, quickFixes)
            }

            // `CS0230: …` as the native errors: the code is a field of the diagnostic, the server leaves it out of the message
            override fun getMessage(diagnostic: Diagnostic): String = RoslynPolicy.diagnosticText(diagnostic.code?.get()?.toString(), diagnostic.message)

            override fun getTooltip(diagnostic: Diagnostic): String =
                com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(RoslynPolicy.diagnosticText(diagnostic.code?.get()?.toString(), diagnostic.message))
        }

        // Three defaults of the platform are "only for plain text and TextMate files": semantic tokens (below), rename and the
        // highlighting of the usages under the caret. C# is a language of the plugin, so without these Shift+F6 finds no handler at all.
        override val renameCustomizer: LspRenameCustomizer = object : LspRenameSupport() {
            // NATIVE: the plugin's handler (first) takes Shift+F6 and hands over to this one what it does not rename itself
            override fun shouldRunRename(psiFile: PsiFile): Boolean = serves(CSharpFeature.RENAME) || NativeCSharpRename.serverRenames(psiFile)
        }
        override val documentHighlightsCustomizer: LspDocumentHighlightsCustomizer = object : LspDocumentHighlightsSupport() {
            // whatever the switch of NAVIGATION: with NATIVE the plugin's factory (first) answers for what the tree resolves (locals), and
            // only what it cannot (members, types) reaches the server's factory (last), so NATIVE loses no highlighting
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
            override fun shouldAskServerForSemanticTokens(psiFile: PsiFile): Boolean =
                serves(CSharpFeature.SEMANTIC_COLORS) && (workspace.isLoaded || psiFile.virtualFile?.let(workspace::hasCachedTokens) == true)

            // asked each time the platform makes highlights of the tokens it keeps, not when they arrive: with «Colors of identifiers» = Built-in
            // the tokens of the last answer would stay beside the plugin's colors (robot 0.1.60), see RoslynWorkspace.highlightingSwitched
            override fun getTextAttributesKey(tokenType: String, modifiers: List<String>): TextAttributesKey? =
                if (serves(CSharpFeature.SEMANTIC_COLORS)) RoslynPolicy.textAttributesKey(tokenType, modifiers) else null
        }

        // Go to Symbol / Class: a symbol of a C# file of the project is the declaration of the plugin there, see the class
        override val workspaceSymbolCustomizer: com.intellij.platform.lsp.api.customization.LspWorkspaceSymbolCustomizer = RoslynWorkspaceSymbolSupport()

        // the server is the authority on the layout of code: it corrects a statement on `;`, a block on `}`, a line on Enter
        override val onTypeFormattingCustomizer: LspOnTypeFormattingCustomizer = LspOnTypeFormattingSupport()

        // a chosen method gets its parentheses and the parameter info, see the class
        override val completionCustomizer: LspCompletionCustomizer = RoslynCompletionSupport()

        // the switches of CSharpFeatures, read per request (RoslynFeatures)
        private fun serves(feature: CSharpFeature): Boolean = RoslynFeatures.serves(feature, project)

        // Alt+Enter without the same row twice, see the class
        override val codeActionsCustomizer: LspCodeActionsCustomizer = RoslynCodeActionsSupport()

        // Reformat Code: the server does the work of `dotnet format whitespace`; CSharpier and "None" stay what the project has chosen.
        // "Built-in" (chosen, or "Auto" with FORMATTING NATIVE): the plugin's formatter answers for a file of the native tree, the server
        // for one of the other tree.
        override val formattingCustomizer: LspFormattingCustomizer = object : LspFormattingSupport() {
            override fun shouldFormatThisFileExclusivelyByServer(file: VirtualFile, ideCanFormatThisFileItself: Boolean, serverExplicitlyWantsToFormatThisFile: Boolean): Boolean =
                RoslynPolicy.formatsByServer(DotNetFormattingSettings.getInstance(project).resolve(file), workspace.isLoaded, CSharpSyntaxTrees.nativeTree())
        }
    }
}

/** Settings | .NET | Language Server was applied: registered in the descriptor of the module, so it works before any server has started. */
class RoslynSettingsListener : RoslynLanguageServerSettings.Listener {
    override fun settingsChanged(restart: Boolean) = ProjectManager.getInstance().openProjects.forEach { it.service<RoslynWorkspace>().settingsChanged(restart) }
}
