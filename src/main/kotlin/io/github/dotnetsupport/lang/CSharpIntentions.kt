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

    /**
     * The caret is on the header of a type (attributes, modifiers, name, base list — before its `{`), or with [members] also of a method
     * or property (before its body): Rider offers the actions of the type only there, not in every body (Jellyfin № 21: «Add a partial
     * part» and «Create test» showed at all 25 places). On the heuristic tree, which has no such nodes, the line of the caret decides.
     */
    protected fun onHeader(editor: Editor?, file: PsiFile, members: Boolean = false): Boolean {
        editor ?: return true
        val offset = editor.caretModel.offset
        val leaf = file.findElementAt(offset) ?: (if (offset > 0) file.findElementAt(offset - 1) else null) ?: return false
        var current: com.intellij.psi.PsiElement? = leaf
        while (current != null && current !is PsiFile) {
            when (current) {
                is io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration ->
                    return current.openBraceToken?.takeIf { it.textLength > 0 }?.let { offset <= it.textRange.startOffset } ?: true
                is io.github.dotnetsupport.csharp.lang.psi.CSharpBaseMethodDeclaration -> {
                    val body = current.body ?: current.expressionBody
                    return members && (body == null || offset <= body.textRange.startOffset)
                }
                is io.github.dotnetsupport.csharp.lang.psi.CSharpBasePropertyDeclaration -> {
                    val body = current.accessorList ?: (current as? io.github.dotnetsupport.csharp.lang.psi.CSharpPropertyDeclaration)?.expressionBody
                    return members && (body == null || offset <= body.textRange.startOffset)
                }
                is io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration -> return false
                is io.github.dotnetsupport.csharp.lang.psi.CSharpCompilationUnit -> break
            }
            current = current.parent
        }
        // the heuristic tree: a line that declares a type
        val text = text(file)
        // at offset 0 the line starts there: searching from 0 found the `\n` of an empty first line, and the line was (1, 0)
        val start = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return TYPE_LINE.containsMatchIn(text.subSequence(start, end))
    }

    private companion object {
        val TYPE_LINE = Regex("""\b(class|struct|record|interface)\s+[A-Za-z_]""")
    }
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
        val fileScoped = io.github.dotnetsupport.templates.CSharpNamespaces.explicitNamespaceStyle(virtualFile.parent) == true
        val split = CSharpFileLayout.split(document.immutableCharSequence, editor?.caretModel?.offset ?: return, fileScoped) ?: return
        val created = WriteCommandAction.writeCommandAction(project).withName("Move ${split.typeName} to ${split.typeName}.cs").compute<VirtualFile, IncorrectOperationException> {
            val target = virtualFile.parent.createChildData(this, "${split.typeName}.cs")
            VfsUtil.saveText(target, split.newFileText)
            document.replaceString(0, document.textLength, split.remainingText)
            FileDocumentManager.getInstance().saveDocument(document)
            target
        }
        removeUnusedUsings(project, created)
        OpenFileDescriptor(project, created).navigate(true)
    }

    companion object {
        /**
         * The `using` directives of the moved type's new file nothing in it needs, as Rider leaves only those (Jellyfin № 64): by the native
         * check of unused directives (CS8019), so not while the IDE indexes or with the semantic diagnostics on the server; a blank line
         * left at the top goes too.
         */
        fun removeUnusedUsings(project: Project, file: VirtualFile) {
            if (com.intellij.openapi.project.DumbService.isDumb(project)) return
            val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file) as? CSharpFile ?: return
            if (!CSharpFeatures.native(CSharpFeature.DIAGNOSTICS, psi)) return
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return
            val ranges = runCatching { CSharpRemoveUnusedUsingsFix.unusedLines(psi, document.charsSequence) }.getOrNull() ?: return
            if (ranges.isEmpty()) return
            WriteCommandAction.runWriteCommandAction(project, "Remove Unused Usings", null, {
                for (range in ranges) document.deleteString(range.startOffset, range.endOffset)
                val text = document.charsSequence
                // a header left with blank lines in a row, or starting with one
                val collapsed = text.toString().replace(Regex("\n{3,}"), "\n\n").trimStart('\n')
                if (collapsed != text.toString()) document.replaceString(0, document.textLength, collapsed)
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)
            })
        }
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
        if (!onHeader(editor, source)) return false
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
        if (!onHeader(editor, source, members = true)) return false
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
     * The new file: everything before the first type (usings, `#nullable` / `#pragma` lines, the namespace line or its opening brace), the
     * type with its doc comment, and the closing brace of a block namespace, with no blank line after the brace; with [fileScoped] a block
     * namespace becomes `namespace N;` and the type moves one level left. The old file without the type, its doc comment and the blank
     * lines around it, the indentation of what follows kept (Jellyfin № 64: Rider's files are clean; ours had a blank line after `{` and
     * the `/// <summary>` of the next type moved to column 0). The `using` directives the type does not need go afterwards
     * ([MoveTypeToFileIntention.removeUnusedUsings]).
     */
    fun split(text: CharSequence, offset: Int, fileScoped: Boolean = false): Split? {
        val type = typeAt(text, offset) ?: return null
        val types = topLevelTypes(text)
        if (types.size < 2) return null
        val namespace = CSharpSyntaxModel.current.declarations(text).declarations.singleOrNull { it.kind == DeclarationKind.NAMESPACE }
        val blockNamespace = namespace?.body != null
        val typeStart = leadingComments(text, lineStart(text, type.range.startOffset))
        val firstTypeStart = types.minOf { leadingComments(text, lineStart(text, it.range.startOffset)) }
        val header = text.subSequence(0, firstTypeStart).toString().trimEnd()
        val indent = CSharpExpressions.indentAt(text, type.range.startOffset)
        val typeText = text.subSequence(typeStart, type.range.endOffset).toString()
        val newFile = if (blockNamespace && fileScoped && namespace != null) {
            val beforeNamespace = text.subSequence(0, lineStart(text, namespace.range.startOffset)).toString().trimEnd()
            val dedented = typeText.lines().joinToString("\n") { it.removePrefix(indent) }
            buildString {
                if (beforeNamespace.isNotEmpty()) { append(beforeNamespace); append("\n\n") }
                append("namespace ").append(namespace.name).append(";\n\n").append(dedented).append("\n")
            }
        } else buildString {
            append(header); append(if (blockNamespace) "\n" else "\n\n"); append(typeText); append("\n")
            if (blockNamespace) append("}\n")
        }
        // the old file: the type's lines out, with the blank lines after it; with nothing but `}` (or the end) after, the blank lines
        // before it go too
        var start = typeStart
        var end = type.range.endOffset
        while (end < text.length && (text[end] == ' ' || text[end] == '\t' || text[end] == '\r')) end++
        if (end < text.length && text[end] == '\n') end++
        while (true) {
            val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
            if (end >= text.length || text.subSequence(end, lineEnd).isNotBlank()) break
            end = if (lineEnd < text.length) lineEnd + 1 else lineEnd
        }
        val rest = text.subSequence(end, text.length).trimStart()
        if (rest.isEmpty() || rest.startsWith("}")) {
            while (start > 0) {
                val previousStart = lineStart(text, start - 1)
                if (text.subSequence(previousStart, start).isNotBlank()) break
                start = previousStart
            }
        }
        val remaining = text.substring(0, start) + text.substring(end)
        return Split(type.name, newFile, remaining)
    }

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    /** [start] (a line start) moved up over the `///` comments and attribute lines right above it. */
    private fun leadingComments(text: CharSequence, start: Int): Int {
        var current = start
        while (current > 0) {
            val previous = lineStart(text, current - 1)
            val line = text.subSequence(previous, current).trim()
            if (!(line.startsWith("///") || line.startsWith("["))) break
            current = previous
        }
        return current
    }
}
