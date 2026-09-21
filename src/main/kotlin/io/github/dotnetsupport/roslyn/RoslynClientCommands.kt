package io.github.dotnetsupport.roslyn

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.customization.LspCommandsSupport
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler

private val LOG = logger<RoslynClientCommands>()

/**
 * The commands Roslyn leaves to its client (`roslyn.client.*`): the server only describes them, and sent back to
 * `workspace/executeCommand` they do nothing at all. That is what "N references" above a declaration, "Fix All: ..." and every
 * code action with variants ("Introduce constant" -> for '1' / for all occurrences) are. The shapes are the ones recorded by
 * `tools/roslyn-lsp/capture.py` from server 5.12.
 */
class RoslynClientCommands : LspCommandsSupport() {
    override fun executeCommand(lspClient: LspClient, contextFile: VirtualFile, command: Command) {
        val arguments = command.arguments.orEmpty().map { it as? JsonElement ?: GSON.toJsonTree(it) }
        when (command.command) {
            PEEK_REFERENCES -> peekReferences(arguments)?.let { (uri, position) -> showReferences(lspClient, uri, position) }
            NESTED_CODE_ACTION -> choose(lspClient, command.title, nestedActions(arguments).associateBy { it.title }) { resolveAndApply(lspClient, contextFile, it) }
            FIX_ALL -> fixAll(arguments)?.let { fix ->
                choose(lspClient, command.title, fix.scopes.associateBy(::scopeTitle)) { scope -> fixAll(lspClient, contextFile, command.title, fix.data, scope) }
            }
            else -> super.executeCommand(lspClient, contextFile, command)
        } ?: LOG.warn("Roslyn command ${command.command}: unexpected arguments $arguments")
    }

    // The platform still has both generations of its API: completion runs commands through the deprecated overload, code actions
    // through the new one, and the default of each sends the command to the server.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun executeCommand(server: LspServer, contextFile: VirtualFile, command: Command) = executeCommand(server as LspClient, contextFile, command)

    /** The references of what stands at [position]: the usual Show Usages of the IDE, which asks the server. */
    private fun showReferences(client: LspClient, uri: String, position: Position) = onEdt(client) {
        val file = client.descriptor.findFileByUri(uri) ?: return@onEdt
        OpenFileDescriptor(client.project, file, position.line, position.character).navigate(true)
        val editor = FileEditorManager.getInstance(client.project).selectedTextEditor ?: return@onEdt
        ActionUtil.invokeAction(ActionManager.getInstance().getAction("ShowUsages"), DataManager.getInstance().getDataContext(editor.contentComponent), ActionPlaces.UNKNOWN, null, null)
    }

    /** One variant is not a choice; several are a list under the caret, as the variants of an intention. */
    private fun <T> choose(client: LspClient, title: String?, variants: Map<String, T>, chosen: (T) -> Unit): Unit? {
        if (variants.isEmpty()) return null
        if (variants.size == 1) return chosen(variants.values.single())
        return onEdt(client) {
            val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(variants.keys.toList()).setTitle(title.orEmpty())
                .setItemChosenCallback { chosen(variants.getValue(it)) }.createPopup()
            val editor = FileEditorManager.getInstance(client.project).selectedTextEditor
            if (editor != null) popup.showInBestPositionFor(editor) else popup.showCenteredInCurrentWindow(client.project)
        }
    }

    /** A variant comes without its edit: `codeAction/resolve` computes it. A variant with variants of its own is a command again. */
    private fun resolveAndApply(client: LspClient, file: VirtualFile, action: CodeAction) = inBackground {
        val resolved = if (action.edit != null || action.command != null) action else client.sendRequestSync(RESOLVE_TIMEOUT_MS) { it.textDocumentService.resolveCodeAction(action) }
        LOG.info("Roslyn code action '${action.title}': ${if (resolved == null) "no answer to codeAction/resolve" else if (resolved.edit != null) "an edit" else "command ${resolved.command?.command}"}")
        apply(client, file, resolved ?: return@inBackground)
    }

    private fun fixAll(client: LspClient, file: VirtualFile, title: String?, data: JsonElement, scope: String) = inBackground {
        val resolved = client.sendRequestSync(FIX_ALL_TIMEOUT_MS) { (it as RoslynServer).resolveFixAll(FixAllParams(title.orEmpty(), data, scope)) }
        apply(client, file, resolved ?: return@inBackground)
    }

    /** The platform applies the edit of a code action and runs its command; one command of the IDE, so one Undo. */
    private fun apply(client: LspClient, file: VirtualFile, action: CodeAction) = onEdt(client) {
        val intention = LspIntentionAction(client, action)
        // not a formality: isAvailable() is what finds the documents of the edit, and invoke() silently does nothing without it
        if (!intention.isAvailable()) return@onEdt LOG.warn("Roslyn code action '${action.title}': its edit does not apply to the documents as they are now")
        CommandProcessor.getInstance().executeCommand(client.project, { intention.invoke(file) }, action.title, null)
    }

    private fun onEdt(client: LspClient, run: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ if (!client.project.isDisposed) run() }, ModalityState.nonModal())

    private fun inBackground(run: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread { runCatching(run).onFailure { LOG.warn("Roslyn code action failed", it) } }
    }

    class FixAll(val data: JsonElement, val scopes: List<String>)

    companion object {
        const val PEEK_REFERENCES = "roslyn.client.peekReferences"
        const val NESTED_CODE_ACTION = "roslyn.client.nestedCodeAction"
        const val FIX_ALL = "roslyn.client.fixAllCodeAction"
        private const val RESOLVE_TIMEOUT_MS = 10_000
        private const val FIX_ALL_TIMEOUT_MS = 120_000 // a whole solution

        /** The gson of lsp4j: a code action has `Either` fields a plain Gson does not read. */
        private val GSON = MessageJsonHandler(emptyMap()).gson

        /** `[uri, {line, character}]` */
        fun peekReferences(arguments: List<JsonElement>): Pair<String, Position>? {
            val uri = arguments.getOrNull(0)?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val position = arguments.getOrNull(1)?.takeIf { it.isJsonObject } ?: return null
            return uri to GSON.fromJson(position, Position::class.java)
        }

        /** `[{..., NestedCodeActions: [code action with `data` and without `edit`, ...]}]` */
        fun nestedActions(arguments: List<JsonElement>): List<CodeAction> =
            (arguments.firstOrNull() as? JsonObject)?.getAsJsonArray("NestedCodeActions")?.map { GSON.fromJson(it, CodeAction::class.java) }.orEmpty()

        /** `[{..., FixAllFlavors: ["Document", "Project", "Solution", ...]}]`: the argument is also the `data` of the request. */
        fun fixAll(arguments: List<JsonElement>): FixAll? {
            val data = arguments.firstOrNull() as? JsonObject ?: return null
            val scopes = data.getAsJsonArray("FixAllFlavors")?.map { it.asString }.orEmpty()
            return FixAll(data, scopes).takeIf { scopes.isNotEmpty() }
        }

        /** "ContainingMember" -> "Containing Member" */
        fun scopeTitle(scope: String): String = scope.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
    }
}
