package io.github.dotnetsupport.frameworks.razor

import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlTokenType
import com.intellij.psi.PsiDocumentManager
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandler
import io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.NativeCSharpRename
import io.github.dotnetsupport.lang.NativeCSharpSolutionRename
import io.github.dotnetsupport.lang.semantic.CSharpSearchTarget


/**
 * Rename of a component and of a parameter from Razor's markup and of a `.razor` file (RAZOR_PLAN.md, R5e part 2), as in Rider: a component of
 * a `.razor` file is its file — the file and the files nested under it (`Counter.razor.cs`, `.razor.css`) get the new name, its tags in the
 * markup, the class of its code-behind and the C# that names it; a component of a C# class and a parameter are renamed by the rename of the
 * C# ([NativeCSharpSolutionRename]), which writes the markup through [RazorRenameParticipant].
 */
object RazorComponentRename {
    /** Every place to rename for the component of [file]: in a read action, under the progress of the caller. */
    fun plan(project: Project, file: RazorFile): NativeCSharpSolutionRename.Plan? {
        val typeName = RazorTagHelperUsages.componentName(file) ?: return null
        val name = typeName.substringAfterLast('.')
        // the class of the component in the C# files: its code-behind and the source generator's output (renamed by its next run)
        val declarations = RazorTagHelperUsages.componentDeclarations(file)

        val target = CSharpSearchTarget(name, CSharpSearchTarget.Kind.TYPE, declarations)
        val csharp = if (declarations.isEmpty()) null else NativeCSharpSolutionRename.plan(project, listOf(target))
        val ranges = LinkedHashMap<PsiFile, MutableSet<com.intellij.openapi.util.TextRange>>()
        for ((psi, list) in csharp?.ranges.orEmpty()) ranges.getOrPut(psi) { LinkedHashSet() } += list
        for (place in RazorTagHelperUsages.tags(project, typeName, com.intellij.psi.search.GlobalSearchScope.projectScope(project))) {
            ranges.getOrPut(place.file) { LinkedHashSet() } += place.range
        }
        val files = (RazorTagHelperUsages.componentFiles(project, file) + csharp?.typeFiles.orEmpty()).distinct()
        return NativeCSharpSolutionRename.Plan(listOf(target), ranges.mapValues { (_, set) -> set.sortedBy { it.startOffset } }, name, files, csharp?.insertions.orEmpty())
    }

    /** Asks the new name of the component of [file] (or takes [name]) and renames it; false when nothing was renamed. */
    fun invoke(project: Project, editor: Editor?, file: RazorFile, name: String? = null): Boolean {
        val plan = ProgressManager.getInstance().runProcessWithProgressSynchronously<NativeCSharpSolutionRename.Plan?, RuntimeException>({
            ReadAction.compute<NativeCSharpSolutionRename.Plan?, RuntimeException> { plan(project, file) }
        }, "Searching for Usages of ${file.name}", true, project) ?: return false
        val newName = (name ?: Messages.showInputDialog(project, "Rename component ${plan.oldName} to:", "Rename", null, plan.oldName, null) ?: return false).trim()
        val folder = file.originalFile.viewProvider.virtualFile.parent
        if (newName != plan.oldName && folder?.findChild("$newName.razor") != null) {
            NativeCSharpRename.showError(project, editor, "A file named '$newName.razor' already exists in ${folder.name}")
            return false
        }
        return NativeCSharpSolutionRename.apply(project, editor, plan, newName)
    }

    /** The rename of [declaration], a C# class of a component or a parameter's property, by the rename of the C# (with a dialog). */
    fun renameCSharp(project: Project, editor: Editor, declaration: PsiElement, name: String? = null) {
        val file = declaration.containingFile as? CSharpFile
        val nameElement = CSharpDeclarationNames.nameElement(declaration)
        if (file == null || nameElement == null) {
            NativeCSharpRename.showError(project, editor, "'${declaration.text.take(40)}' is declared in a referenced assembly and cannot be renamed")
            return
        }
        when (val decision = NativeCSharpRename.decide(file, nameElement.textRange.startOffset)) {
            is NativeCSharpRename.Solution -> NativeCSharpSolutionRename.invoke(project, editor, file, decision.target, name, dialog = true)
            is NativeCSharpRename.Refuse -> NativeCSharpRename.showError(project, editor, decision.reason)
            is NativeCSharpRename.Native -> NativeCSharpRename.showError(project, editor, "'${nameElement.text}' is not a symbol the built-in rename knows")
        }
    }

}


/**
 * Shift+F6 on the tag of a component or an attribute of a parameter in Razor's markup, and on a `.razor` file in the project view: the
 * component or the parameter is renamed, not the HTML element (the platform's rename of a tag renames that one tag).
 */
class RazorMarkupRenameHandler : RenameHandler {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val editor = CommonDataKeys.EDITOR.getData(dataContext)
        val file = CommonDataKeys.PSI_FILE.getData(dataContext)
        if (editor != null && file != null) return markupAt(editor, file)?.let(RazorTagHelperEditor::markupBinding).let { it != null && it !is RazorTagHelperEditor.MarkupBinding.TagHelper }
        val element = CommonDataKeys.PSI_ELEMENT.getData(dataContext) as? RazorFile ?: return false
        val virtualFile = element.viewProvider.virtualFile
        return virtualFile.extension == "razor" && !virtualFile.nameWithoutExtension.equals("_Imports", ignoreCase = true)
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null || file == null) return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        rename(project, editor, file, dataContext?.let(PsiElementRenameHandler.DEFAULT_NAME::getData))
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val razor = elements.singleOrNull() as? RazorFile ?: return
        RazorComponentRename.invoke(project, dataContext?.let(CommonDataKeys.EDITOR::getData), razor, dataContext?.let(PsiElementRenameHandler.DEFAULT_NAME::getData))
    }

    private fun rename(project: Project, editor: Editor, file: PsiFile, name: String?) {
        if (DumbService.isDumb(project)) return
        val element = markupAt(editor, file) ?: return
        val binding = RazorTagHelperEditor.markupBinding(element) ?: return
        val target = ProgressManager.getInstance().runProcessWithProgressSynchronously<PsiElement?, RuntimeException>({
            ReadAction.compute<PsiElement?, RuntimeException> { RazorTagHelperEditor.markupTarget(element) }
        }, "Searching for the Declaration", true, project)
        when {
            target is RazorFile -> RazorComponentRename.invoke(project, editor, target, name)
            target != null -> RazorComponentRename.renameCSharp(project, editor, target, name)
            else -> NativeCSharpRename.showError(project, editor, "'${binding.component.name}' is declared in a referenced assembly and cannot be renamed")
        }
    }

    /** The name's token of a tag or attribute at the caret of [editor] (or right before it). */
    private fun markupAt(editor: Editor, file: PsiFile): PsiElement? {
        if (RazorTagHelperEditor.razorFile(file) == null) return null
        val offset = editor.caretModel.offset
        for (at in listOf(offset, offset - 1)) {
            if (at < 0) continue
            val element = file.viewProvider.findElementAt(at, HTMLLanguage.INSTANCE) ?: continue
            if (element.node?.elementType == XmlTokenType.XML_NAME) return element
        }
        return null
    }

    override fun isRenaming(dataContext: DataContext): Boolean = isAvailableOnDataContext(dataContext)
}

/**
 * The platform's rename of an HTML tag (XmlTagRenameHandler, which renames the one tag) stands back on the name of a component's tag or a
 * parameter's attribute: [RazorMarkupRenameHandler] renames those — with both, Shift+F6 would ask which one.
 */
class RazorMarkupVetoRename : com.intellij.openapi.util.Condition<PsiElement> {
    override fun value(element: PsiElement): Boolean =
        element.node?.elementType == XmlTokenType.XML_NAME && RazorTagHelperEditor.razorFile(element.containingFile) != null &&
            !DumbService.isDumb(element.project) && RazorTagHelperEditor.markupBinding(element).let { it != null && it !is RazorTagHelperEditor.MarkupBinding.TagHelper }
}
