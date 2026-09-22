package io.github.dotnetsupport.roslyn

import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind

/**
 * Alt+Enter without the same row twice. The platform asks the server for the actions of every diagnostic under the caret (its range) and
 * once more for the caret itself, and Roslyn answers each of them with everything that applies there: the refactorings came back among the
 * fixes of a diagnostic and among the context actions (`Use implicit type` twice), the fixes of a diagnostic among the context actions as
 * well (`Use expression body for method` and its Fix All twice). Seen live on `int unused = 1;` and on a method with a block body.
 */
class RoslynCodeActionsSupport : LspCodeActionsSupport() {
    override fun createQuickFix(lspClient: LspClient, codeAction: CodeAction): LspIntentionAction? =
        if (RoslynCodeActionPolicy.isFixOfDiagnostic(codeAction)) super.createQuickFix(lspClient, codeAction) else null

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun createQuickFix(lspServer: LspServer, codeAction: CodeAction): LspIntentionAction? =
        if (RoslynCodeActionPolicy.isFixOfDiagnostic(codeAction)) super.createQuickFix(lspServer, codeAction) else null

    override fun createIntentionAction(lspClient: LspClient, codeAction: CodeAction): LspIntentionAction? =
        if (RoslynCodeActionPolicy.isContextAction(codeAction)) super.createIntentionAction(lspClient, codeAction) else null

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun createIntentionAction(lspServer: LspServer, codeAction: CodeAction): LspIntentionAction? =
        if (RoslynCodeActionPolicy.isContextAction(codeAction)) super.createIntentionAction(lspServer, codeAction) else null
}

object RoslynCodeActionPolicy {
    /** Among the fixes of a diagnostic: anything but a refactoring (`refactor`, `refactor.extract`...), which is a context action of the caret. */
    fun isFixOfDiagnostic(action: CodeAction): Boolean = action.kind?.let { it == CodeActionKind.Refactor || it.startsWith(CodeActionKind.Refactor + ".") } != true

    /** Among the context actions: anything but a fix that names its diagnostic, which is listed with that diagnostic already. */
    fun isContextAction(action: CodeAction): Boolean =
        !(action.kind?.let { it == CodeActionKind.QuickFix || it.startsWith(CodeActionKind.QuickFix + ".") } == true && !action.diagnostics.isNullOrEmpty())
}
