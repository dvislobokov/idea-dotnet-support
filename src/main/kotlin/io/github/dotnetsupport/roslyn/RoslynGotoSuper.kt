package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.hint.HintManager
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile
import com.intellij.ui.SimpleListCellRenderer
import io.github.dotnetsupport.lang.CSharpFile

/**
 * Go to Super (Ctrl+U) on a C# type: its base class and interfaces from the server's type hierarchy; one — a jump, several — a list.
 * On a member the server offers no "base member" request: the hint says where to put the caret. Derived symbols are Go to Implementation.
 */
class RoslynGotoSuperHandler : LanguageCodeInsightActionHandler {
    override fun startInWriteAction(): Boolean = false
    override fun isValidFor(editor: Editor?, file: PsiFile?): Boolean = file is CSharpFile

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val target = RoslynHierarchies.target(project, editor, file) ?: return HintManager.getInstance().showErrorHint(editor, "The C# language server is not loaded yet")
        val type = RoslynHierarchies.prepareType(target).firstOrNull()
            ?: return HintManager.getInstance().showErrorHint(editor, "Put the caret on a type: base symbols of members are not offered by the server")
        val bases = RoslynHierarchies.supertypes(target.client, type).map { HierarchyElement(project, target.client, it) }
        when (bases.size) {
            0 -> HintManager.getInstance().showErrorHint(editor, "${type.name} has no base types in the sources")
            1 -> bases.single().navigate(true)
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(bases)
                .setTitle("Choose Base Type of ${type.name}")
                .setRenderer(SimpleListCellRenderer.create { label, element, _ ->
                    label.text = element.rowText + element.locationText?.let { "  ($it)" }.orEmpty()
                    label.icon = element.item.icon
                })
                .setNamerForFiltering { it.rowText }
                .setItemChosenCallback { it.navigate(true) }
                .createPopup().showInBestPositionFor(editor)
        }
    }
}
