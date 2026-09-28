package io.github.dotnetsupport.lang

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.usageView.UsageInfo
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SolutionService

/**
 * A `.cs` file moved to another folder gets the namespace of that folder, as in Rider: the language server changes the declaration and
 * every usage in the solution ([CSharpNamespaceAdjuster], the module of the server); without a server only the declaration in the file
 * is changed, and the notification says so.
 */
class CSharpMoveFileHandler : MoveFileHandler() {
    override fun canProcessElement(element: PsiFile): Boolean =
        element is CSharpFile && element.virtualFile?.let(DotNetProjects::findOwningProject) != null

    override fun prepareMovedFile(file: PsiFile, moveDestination: PsiDirectory, oldToNewMap: MutableMap<PsiElement, PsiElement>) = Unit
    override fun findUsages(psiFile: PsiFile, newParent: PsiDirectory, searchInComments: Boolean, searchInNonJavaFiles: Boolean): List<UsageInfo>? = null
    override fun retargetUsages(usageInfos: List<UsageInfo>, oldToNewMap: Map<PsiElement, PsiElement>) = Unit

    // inside the write action of the refactoring: the question and the server come after it
    override fun updateMovedFile(file: PsiFile) {
        val virtualFile = file.virtualFile ?: return
        val project = file.project
        val (old, new) = CSharpNamespaceSync.mismatch(project, virtualFile) ?: return
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed && virtualFile.isValid && CSharpNamespaceSync.confirm(project, virtualFile, old, new)) CSharpNamespaceSync.adjust(project, virtualFile, old, new)
        }, ModalityState.nonModal())
    }
}

/** Who changes a namespace with its usages: the module of the language server, when it is loaded. */
interface CSharpNamespaceAdjuster {
    /** Calls [done] with true when the namespace and its usages are changed, false when it could not do it (no server, nothing offered). */
    fun adjust(project: Project, file: VirtualFile, oldNamespace: String, newNamespace: String, done: (Boolean) -> Unit)

    companion object {
        val EP_NAME: ExtensionPointName<CSharpNamespaceAdjuster> = ExtensionPointName.create("io.github.dotnetsupport.namespaceAdjuster")
    }
}

object CSharpNamespaceSync {
    /** Tests: no dialog. */
    @Volatile var askBeforeAdjusting: Boolean = !ApplicationManager.getApplication().isUnitTestMode

    /** (declared, expected) when the top-level namespace of the file is not the one of its folder. */
    fun mismatch(project: Project, file: VirtualFile): Pair<String, String>? {
        val directory = file.parent ?: return null
        val expected = io.github.dotnetsupport.templates.CSharpNamespaces.forDirectory(project, directory) ?: return null
        val text = FileDocumentManager.getInstance().getDocument(file)?.immutableCharSequence ?: return null
        val declared = declaredNamespace(text) ?: return null
        return if (declared == expected) null else declared to expected
    }

    /** The one top-level namespace of the file; null when there is none or several. */
    fun declaredNamespace(text: CharSequence): String? =
        CSharpDeclarations.scan(text).declarations.filter { it.kind == DeclarationKind.NAMESPACE }.singleOrNull()?.name

    fun confirm(project: Project, file: VirtualFile, old: String, new: String): Boolean {
        if (!askBeforeAdjusting) return true
        return Messages.showYesNoDialog(project, "Change the namespace of ${file.name} from '$old' to '$new', the one of its new folder?\nThe usages are updated by the C# language server.", "Move File", "Change", "Keep", Messages.getQuestionIcon()) == Messages.YES
    }

    /** The server first; the file alone when it cannot. */
    fun adjust(project: Project, file: VirtualFile, old: String, new: String) {
        val adjuster = CSharpNamespaceAdjuster.EP_NAME.extensionList.firstOrNull()
        if (adjuster == null) return adjustInFile(project, file, old, new)
        adjuster.adjust(project, file, old, new) { done ->
            if (!done) ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed && file.isValid) adjustInFile(project, file, old, new) }, ModalityState.nonModal())
        }
    }

    fun adjustInFile(project: Project, file: VirtualFile, old: String, new: String) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val edited = CSharpNamespaceRename.rename(document.immutableCharSequence, old, new) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Change Namespace", null, { document.setText(edited) })
        NotificationGroupManager.getInstance().getNotificationGroup(".NET")
            .createNotification("Namespace of ${file.name} changed to '$new'", "The usages are not updated: the C# language server is not loaded. Rename the namespace with Shift+F6 once it is.", NotificationType.INFORMATION)
            .notify(project)
    }
}

/** The text change alone: the name of the top-level `namespace` declaration, file-scoped or block. */
object CSharpNamespaceRename {
    fun rename(text: CharSequence, old: String, new: String): String? {
        val declaration = CSharpDeclarations.scan(text).declarations.singleOrNull { it.kind == DeclarationKind.NAMESPACE && it.name == old } ?: return null
        return text.replaceRange(declaration.nameRange.startOffset, declaration.nameRange.endOffset, new).toString()
    }
}
