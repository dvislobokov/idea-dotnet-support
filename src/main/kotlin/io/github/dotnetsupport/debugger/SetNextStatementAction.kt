package io.github.dotnetsupport.debugger

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition

/**
 * Set Next Statement, as in Rider: the line under the caret runs next, the code in between does not run. The platform has no action for it
 * (Java has none: the JVM cannot), so it is the plugin's, for a suspended session of the .NET debugger.
 */
class SetNextStatementAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val available = target(e) != null
        e.presentation.isEnabled = available
        e.presentation.isVisible = available || !e.isFromContextMenu
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (process, position) = target(e) ?: return
        process.setNextStatement(position)
    }

    private fun target(e: AnActionEvent): Pair<DotNetDebugProcess, XSourcePosition>? {
        val project = e.project ?: return null
        val session = XDebuggerManager.getInstance(project).currentSession ?: return null
        val process = session.debugProcess as? DotNetDebugProcess ?: return null
        if (!session.isSuspended || !process.canSetNextStatement) return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = FileDocumentManager.getInstance().getFile(editor.document)?.takeIf { it.extension.equals("cs", ignoreCase = true) } ?: return null
        val position = XDebuggerUtil.getInstance().createPosition(file, editor.caretModel.logicalPosition.line) ?: return null
        return process to position
    }
}
