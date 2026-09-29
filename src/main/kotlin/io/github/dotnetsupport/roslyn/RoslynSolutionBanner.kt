package io.github.dotnetsupport.roslyn

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import io.github.dotnetsupport.lsp.RoslynPhase
import java.util.function.Function
import javax.swing.JComponent

/** Several solutions in the folder and none chosen yet: the file is shown without the server until one is. */
class RoslynSolutionBanner : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!isCSharpSource(file)) return null
        val workspace = project.service<RoslynWorkspace>()
        if (workspace.phase != RoslynPhase.CHOOSING_SOLUTION) return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = "The folder has several solutions: choose the one the C# language server loads."
                createActionLabel("Select Solution...") { workspace.chooseSolution() }
            }
        }
    }
}
