package io.github.dotnetsupport.lang

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException
import io.github.dotnetsupport.templates.PartialPartAction
import io.github.dotnetsupport.templates.TestForClassAction
import io.github.dotnetsupport.templates.TypeDeclarationScanner

/*
 * Alt+Enter on a C# file, the things Rider offers at the file level that need no semantics: the namespace of the folder, a type
 * into a file of its own, the file named after its type, a partial part, a test class. All by the scanner of declarations.
 */

abstract class CSharpIntention : IntentionAction, PriorityAction, DumbAware {
    override fun getFamilyName(): String = "C# file"
    override fun startInWriteAction(): Boolean = false
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW

    protected fun source(file: PsiFile?): CSharpFile? = file as? CSharpFile

    protected fun text(file: PsiFile): CharSequence = file.viewProvider.document?.immutableCharSequence ?: file.text
}

/** `namespace Shop;` in `Shop/Models/Order.cs` -> "Change namespace to 'Shop.Models'", with the usages when the server is loaded. */
class AdjustNamespaceIntention : CSharpIntention() {
    private var expected: String = ""

    override fun getText(): String = "Change namespace to '$expected'"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val virtualFile = source(file)?.virtualFile ?: return false
        val (_, new) = CSharpNamespaceSync.mismatch(project, virtualFile) ?: return false
        expected = new
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val virtualFile = source(file)?.virtualFile ?: return
        val (old, new) = CSharpNamespaceSync.mismatch(project, virtualFile) ?: return
        CSharpNamespaceSync.adjust(project, virtualFile, old, new)
    }
}

/** A file with several top-level types, the caret in one that is not the file's own: "Move 'Bar' to Bar.cs". */
class MoveTypeToFileIntention : CSharpIntention() {
    private var typeName: String = ""

    override fun getText(): String = "Move '$typeName' to $typeName.cs"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val source = source(file) ?: return false
        val offset = editor?.caretModel?.offset ?: return false
        val declaration = CSharpFileLayout.typeAt(text(source), offset) ?: return false
        if (CSharpFileLayout.topLevelTypes(text(source)).size < 2 || declaration.name == source.virtualFile?.nameWithoutExtension) return false
        if (source.virtualFile?.parent?.findChild("${declaration.name}.cs") != null) return false
        typeName = declaration.name
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val source = source(file) ?: return
        val virtualFile = source.virtualFile ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
        val split = CSharpFileLayout.split(document.immutableCharSequence, editor?.caretModel?.offset ?: return) ?: return
        val created = WriteCommandAction.writeCommandAction(project).withName("Move ${split.typeName} to ${split.typeName}.cs").compute<VirtualFile, IncorrectOperationException> {
            val target = virtualFile.parent.createChildData(this, "${split.typeName}.cs")
            VfsUtil.saveText(target, split.newFileText)
            document.replaceString(0, document.textLength, split.remainingText)
            FileDocumentManager.getInstance().saveDocument(document)
            target
        }
        OpenFileDescriptor(project, created).navigate(true)
    }
}

/** One type in the file, the file named otherwise: "Rename file to 'Bar.cs'". */
class RenameFileToTypeIntention : CSharpIntention() {
    private var typeName: String = ""

    override fun getText(): String = "Rename file to '$typeName.cs'"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val source = source(file) ?: return false
        val virtualFile = source.virtualFile ?: return false
        val type = CSharpFileLayout.topLevelTypes(text(source)).singleOrNull() ?: return false
        if (type.name == virtualFile.nameWithoutExtension || !type.name.all { it.isLetterOrDigit() || it == '_' }) return false
        if (virtualFile.parent?.findChild("${type.name}.cs") != null) return false
        typeName = type.name
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val virtualFile = source(file)?.virtualFile ?: return
        WriteCommandAction.runWriteCommandAction(project, "Rename File to $typeName.cs", null, { virtualFile.rename(this, "$typeName.cs") })
    }
}

/** The type of the file gets `partial` and a second file `Type.Part.cs`, as the New menu offers on the file. */
class AddPartialPartIntention : CSharpIntention() {
    private var typeName: String = ""

    override fun getText(): String = "Add a partial part of '$typeName'"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val source = source(file) ?: return false
        val type = TypeDeclarationScanner.firstType(text(source)) ?: return false
        if (type.kind == "interface") return false
        typeName = type.name
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val source = source(file) ?: return
        val virtualFile = source.virtualFile ?: return
        val type = TypeDeclarationScanner.firstType(text(source)) ?: return
        val part = Messages.showInputDialog(project, "Name of the part (${type.name}.<part>.cs):", "New Partial Part", null, "", null)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        PartialPartAction.create(project, virtualFile, type, part)?.let { OpenFileDescriptor(project, it).navigate(true) }
    }
}

/** A test class for the type of the file, in the test project of the solution: "Create test 'BarTests'". */
class CreateTestIntention : CSharpIntention() {
    private var typeName: String = ""

    override fun getText(): String = "Create test '${typeName}Tests'"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val source = source(file) ?: return false
        val virtualFile = source.virtualFile ?: return false
        val type = TypeDeclarationScanner.firstType(text(source)) ?: return false
        if (type.name.endsWith("Tests") || type.name.endsWith("Test")) return false
        if (TestForClassAction.findTarget(project, virtualFile) == null) return false
        typeName = type.name
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val virtualFile = source(file)?.virtualFile ?: return
        TestForClassAction.create(project, virtualFile)?.let { OpenFileDescriptor(project, it).navigate(true) }
    }
}

/** The types of a file and how to take one out into a file of its own; by the scanner of declarations. */
object CSharpFileLayout {
    class Split(val typeName: String, val newFileText: String, val remainingText: String)

    fun topLevelTypes(text: CharSequence): List<CSharpDeclarationInfo> {
        val structure = CSharpSyntaxModel.current.declarations(text)
        val namespace = structure.declarations.singleOrNull { it.kind == DeclarationKind.NAMESPACE }
        return (namespace?.children ?: structure.declarations).filter { it.kind.isType }
    }

    /** The top-level type the caret is in. */
    fun typeAt(text: CharSequence, offset: Int): CSharpDeclarationInfo? = topLevelTypes(text).firstOrNull { it.range.containsOffset(offset) }

    /**
     * The new file: everything before the first type (usings, the namespace line or its opening brace), the type, and the closing
     * brace of a block namespace; the old file without the type and with one blank line where it was.
     */
    fun split(text: CharSequence, offset: Int): Split? {
        val type = typeAt(text, offset) ?: return null
        val types = topLevelTypes(text)
        if (types.size < 2) return null
        val namespace = CSharpSyntaxModel.current.declarations(text).declarations.singleOrNull { it.kind == DeclarationKind.NAMESPACE }
        val firstTypeStart = types.minOf { it.range.startOffset }
        val header = text.subSequence(0, firstTypeStart).toString().trimEnd()
        val blockNamespace = namespace?.body != null
        // with the indentation of its first line: inside a block namespace the type is indented
        val typeText = CSharpExpressions.indentAt(text, type.range.startOffset) + text.subSequence(type.range.startOffset, type.range.endOffset)
        val newFile = buildString {
            append(header); append("\n\n"); append(typeText); append("\n")
            if (blockNamespace) append("}\n")
        }
        var start = type.range.startOffset
        while (start > 0 && text[start - 1] == ' ') start--
        var end = type.range.endOffset
        while (end < text.length && (text[end] == ' ' || text[end] == '\r' || text[end] == '\n')) end++
        while (start > 0 && (text[start - 1] == '\n' || text[start - 1] == '\r')) start--
        val remaining = (text.substring(0, start) + "\n\n" + text.substring(end)).replace(Regex("\n{3,}"), "\n\n")
        return Split(type.name, newFile, remaining)
    }
}
