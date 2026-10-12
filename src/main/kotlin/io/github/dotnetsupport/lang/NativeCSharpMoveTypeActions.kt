package io.github.dotnetsupport.lang

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpSearchTarget
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch

/*
 * Refactor This on a type and on members, as Rider lists them (Jellyfin № 54, № 57): "Move Type to Another Namespace", "Move to Another
 * File", "Move to Folder" on the header of a top-level type of the solution, "Extract Members to Partial" on a member (or the members of
 * a selection). Rows of Refactor This only ([CSharpRiderPopups.nativeActions]): Rider does not list them in Alt+Enter. Each asks one
 * name (a namespace, a file, a folder, a part) — [NativeCSharpMoveTypeEdits.answer] replaces the dialog in tests.
 */

/** The text edits and lookups of the moves, testable without a dialog. */
object NativeCSharpMoveTypeEdits {
    /** Tests: the answer of the dialog (null: the dialog). */
    @Volatile var answer: String? = null

    fun ask(project: Project, message: String, title: String, initial: String): String? =
        answer ?: Messages.showInputDialog(project, message, title, null, initial, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** The top-level type whose header (attributes, modifiers, name, base list — before its `{`) holds [offset]. */
    fun typeHeaderAt(file: CSharpFile, offset: Int): CSharpBaseTypeDeclaration? {
        for (candidate in listOf(offset, offset - 1)) {
            val leaf = file.findElementAt(candidate.coerceAtLeast(0)) ?: continue
            val type = PsiTreeUtil.getParentOfType(leaf, CSharpBaseTypeDeclaration::class.java, false) ?: continue
            if (type.parent !is CSharpBaseNamespaceDeclaration && type.parent !is CSharpCompilationUnit) continue
            val brace = type.openBraceToken?.takeIf { it.textLength > 0 } ?: return type
            if (offset <= brace.textRange.startOffset) return type
        }
        return null
    }

    fun nameOf(type: CSharpBaseTypeDeclaration): String? = type.identifier?.text

    /** The top-level types of the file of [type]. */
    fun topLevelTypes(file: CSharpFile): List<CSharpBaseTypeDeclaration> = PsiTreeUtil.findChildrenOfType(file, CSharpBaseTypeDeclaration::class.java)
        .filter { it.parent is CSharpBaseNamespaceDeclaration || it.parent is CSharpCompilationUnit }

    // ---- Extract Members to Partial

    /** The members to extract: those the selection touches, or the one whose header or body holds the caret; all of one type. */
    fun membersAt(file: CSharpFile, selection: TextRange?, offset: Int): List<CSharpMemberDeclaration> {
        if (selection != null && !selection.isEmpty) {
            val members = PsiTreeUtil.findChildrenOfType(file, CSharpMemberDeclaration::class.java).filter { member ->
                member !is CSharpBaseTypeDeclaration && member.parent is CSharpTypeDeclaration && member.textRange.intersects(selection)
            }
            return members.takeIf { it.isNotEmpty() && it.map { m -> m.parent }.distinct().size == 1 } ?: emptyList()
        }
        val leaf = file.findElementAt(offset) ?: return emptyList()
        var current: com.intellij.psi.PsiElement? = leaf
        while (current != null && current !is PsiFile) {
            if (current is CSharpMemberDeclaration && current !is CSharpBaseTypeDeclaration && current.parent is CSharpTypeDeclaration) return listOf(current)
            current = current.parent
        }
        return emptyList()
    }

    /** The type the members are of, when it is a top-level class / struct / record / interface (nested types are not split, as their outer one would have to be). */
    fun ownerOf(members: List<CSharpMemberDeclaration>): CSharpTypeDeclaration? {
        val owner = members.firstOrNull()?.parent as? CSharpTypeDeclaration ?: return null
        if (owner.parent !is CSharpBaseNamespaceDeclaration && owner.parent !is CSharpCompilationUnit) return null
        return owner
    }

    class PartialSplit(val fileName: String, val newFileText: String, val remainingText: String)

    /**
     * [members] of their type into a part of its own (`Type.[part].cs`): the type made `partial` in the old file if it is not, the new file
     * with what is before the type (usings, namespace) and `<modifiers> partial <kind> Name<T> { members }`; the members leave the old one
     * with the blank lines around them. The usings the part does not need go afterwards ([MoveTypeToFileIntention.removeUnusedUsings]).
     */
    fun extractToPartial(text: CharSequence, owner: CSharpTypeDeclaration, members: List<CSharpMemberDeclaration>, part: String): PartialSplit? {
        val name = owner.identifier?.text ?: return null
        val keyword = owner.keyword?.text ?: return null
        val unit = (owner.containingFile as? CSharpFile)?.let(NativeCSharpContextEdits::unit) ?: "    "
        val typeIndent = CSharpExpressions.indentAt(text, owner.textRange.startOffset)
        val memberIndent = typeIndent + unit
        val namespace = owner.parent as? CSharpBaseNamespaceDeclaration
        val blockNamespace = namespace is CSharpNamespaceDeclaration
        val headerEnd = lineStart(text, leadingComments(text, lineStart(text, (owner.attributeLists.firstOrNull() ?: owner.modifiers.firstOrNull() ?: owner.keyword!!).textRange.startOffset)))
        val header = when {
            namespace == null -> text.subSequence(0, headerEnd).toString().trimEnd()
            blockNamespace -> text.subSequence(0, (namespace as CSharpNamespaceDeclaration).openBraceToken!!.textRange.endOffset).toString()
            else -> text.subSequence(0, namespace.textRange.startOffset).toString().trimEnd() + "\n\n" + text.subSequence(namespace.textRange.startOffset, (namespace as CSharpFileScopedNamespaceDeclaration).semicolonToken!!.textRange.endOffset)
        }
        val modifiers = owner.modifiers.map { it.text }.filter { it != "partial" && it != "sealed" && it != "abstract" }
        val typeParameters = owner.typeParameterList?.text.orEmpty()
        val body = members.sortedBy { it.textRange.startOffset }.joinToString("\n\n") { member ->
            val start = leadingComments(text, lineStart(text, member.textRange.startOffset))
            text.subSequence(start, member.textRange.endOffset).toString().lines().joinToString("\n") { line ->
                if (line.isBlank()) "" else memberIndent + line.removePrefix(CSharpExpressions.indentAt(text, member.textRange.startOffset))
            }
        }
        val declaration = buildString {
            append(typeIndent).append((modifiers + "partial").joinToString(" ")).append(' ').append(keyword).append(' ').append(name).append(typeParameters).append('\n')
            append(typeIndent).append("{\n").append(body).append('\n').append(typeIndent).append("}\n")
        }
        val newFile = buildString {
            append(header)
            append(if (blockNamespace) "\n" else "\n\n")
            append(declaration)
            if (blockNamespace) append("}\n")
        }
        // the old file: the members out (last first), then `partial` on the type
        var remaining = text.toString()
        for (member in members.sortedByDescending { it.textRange.startOffset }) {
            var start = leadingComments(remaining, lineStart(remaining, member.textRange.startOffset))
            var end = member.textRange.endOffset
            while (end < remaining.length && (remaining[end] == ' ' || remaining[end] == '\t' || remaining[end] == '\r')) end++
            if (end < remaining.length && remaining[end] == '\n') end++
            while (true) {
                val lineEnd = remaining.indexOf('\n', end).let { if (it < 0) remaining.length else it }
                if (end >= remaining.length || remaining.substring(end, lineEnd).isNotBlank()) break
                end = if (lineEnd < remaining.length) lineEnd + 1 else lineEnd
            }
            if (remaining.substring(end).trimStart().startsWith("}")) {
                while (start > 0) {
                    val previous = lineStart(remaining, start - 1)
                    if (remaining.substring(previous, start).isNotBlank()) break
                    start = previous
                }
            }
            remaining = remaining.substring(0, start) + remaining.substring(end)
        }
        if (owner.modifiers.none { it.text == "partial" }) {
            val at = owner.keyword!!.textRange.startOffset
            remaining = remaining.substring(0, at) + "partial " + remaining.substring(at)
        }
        return PartialSplit("$name.$part.cs", newFile, remaining)
    }

    // ---- Move Type to Another Namespace

    /** The files that use [type] by its name and do not see [namespace]: they get `using namespace;`. Under a read action. */
    fun filesNeedingUsing(project: Project, type: CSharpBaseTypeDeclaration, namespace: String): List<VirtualFile> {
        val target: CSharpSearchTarget = CSharpSolutionSearch.targetOf(type) ?: return emptyList()
        val own = type.containingFile.virtualFile
        val files = CSharpSolutionSearch.usages(project, target, GlobalSearchScope.projectScope(project), CSharpSemanticSession(project))
            .mapNotNull { it.leaf.containingFile?.virtualFile }.filter { it != own }.distinct()
        return files.filter { file ->
            val text = FileDocumentManager.getInstance().getDocument(file)?.immutableCharSequence ?: return@filter false
            val declared = CSharpNamespaceSync.declaredNamespace(text)
            !(declared != null && (declared == namespace || declared.startsWith("$namespace."))) && namespace !in CSharpUsings.imported(text)
        }
    }

    internal fun lineStart(text: CharSequence, offset: Int): Int = if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    /** [start] (a line start) moved up over the `///` comments and attribute lines right above it. */
    internal fun leadingComments(text: CharSequence, start: Int): Int {
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

/** A row of Refactor This: an intention not registered for Alt+Enter, run by [IntentionRowAction]. */
abstract class NativeCSharpRefactorRow(private val title: String) : IntentionAction, DumbAware {
    override fun getText(): String = title
    override fun getFamilyName(): String = title
    override fun startInWriteAction(): Boolean = false
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    protected fun typeAt(editor: Editor?, file: PsiFile?): CSharpBaseTypeDeclaration? {
        if (editor == null || file !is CSharpFile || file.compilationUnit == null) return null
        return NativeCSharpMoveTypeEdits.typeHeaderAt(file, editor.caretModel.offset)?.takeIf { NativeCSharpMoveTypeEdits.nameOf(it) != null }
    }
}

/** "Move to Another File": the type into a file of the name asked (`Type.cs` first), the old file without it; a lone type renames its file. */
class NativeCSharpMoveToAnotherFileRow : NativeCSharpRefactorRow("Move to Another File") {
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = typeAt(editor, file) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val source = file as? CSharpFile ?: return
        val type = typeAt(editor, source) ?: return
        val name = NativeCSharpMoveTypeEdits.nameOf(type) ?: return
        val virtualFile = source.virtualFile ?: return
        val fileName = NativeCSharpMoveTypeEdits.ask(project, "Move '$name' to the file:", "Move to Another File", "$name.cs")?.let { if (it.endsWith(".cs")) it else "$it.cs" } ?: return
        if (virtualFile.parent.findChild(fileName) != null) return Messages.showErrorDialog(project, "$fileName already exists", "Move to Another File")
        if (NativeCSharpMoveTypeEdits.topLevelTypes(source).size < 2) {
            WriteCommandAction.runWriteCommandAction(project, "Move $name to $fileName", null, { virtualFile.rename(this, fileName) })
            return
        }
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
        val fileScoped = io.github.dotnetsupport.templates.CSharpNamespaces.explicitNamespaceStyle(virtualFile.parent) == true
        val split = CSharpFileLayout.split(document.immutableCharSequence, type.textRange.startOffset.coerceAtLeast(type.identifier!!.textRange.startOffset), fileScoped) ?: return
        val created = WriteCommandAction.writeCommandAction(project).withName("Move $name to $fileName").compute<VirtualFile, RuntimeException> {
            val target = virtualFile.parent.createChildData(this, fileName)
            VfsUtil.saveText(target, split.newFileText)
            document.replaceString(0, document.textLength, split.remainingText)
            FileDocumentManager.getInstance().saveDocument(document)
            target
        }
        MoveTypeToFileIntention.removeUnusedUsings(project, created)
        OpenFileDescriptor(project, created).navigate(true)
    }
}

/**
 * "Move Type to Another Namespace": the namespace declaration of a file with this one type renamed, and `using new;` added to the files
 * that use the type and do not see the new namespace (found by the native search of usages). A file with several types is not offered:
 * move the type to a file of its own first.
 */
class NativeCSharpMoveToNamespaceRow : NativeCSharpRefactorRow("Move Type to Another Namespace") {
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val source = file as? CSharpFile ?: return false
        typeAt(editor, source) ?: return false
        return NativeCSharpMoveTypeEdits.topLevelTypes(source).size == 1 && CSharpNamespaceSync.declaredNamespace(source.text) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val source = file as? CSharpFile ?: return
        val type = typeAt(editor, source) ?: return
        val virtualFile = source.virtualFile ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
        val old = CSharpNamespaceSync.declaredNamespace(document.immutableCharSequence) ?: return
        val new = NativeCSharpMoveTypeEdits.ask(project, "Move '${NativeCSharpMoveTypeEdits.nameOf(type)}' to the namespace:", "Move Type to Another Namespace", old) ?: return
        if (new == old || !new.split('.').all { part -> part.isNotEmpty() && (part[0].isLetter() || part[0] == '_') && part.all { it.isLetterOrDigit() || it == '_' } }) return
        val users = if (DumbService.isDumb(project)) emptyList() else ProgressManager.getInstance().runProcessWithProgressSynchronously<List<VirtualFile>, RuntimeException>({
            ReadAction.compute<List<VirtualFile>, RuntimeException> { NativeCSharpMoveTypeEdits.filesNeedingUsing(project, type, new) }
        }, "Looking for Usages", true, project)
        val edited = CSharpNamespaceRename.rename(document.immutableCharSequence, old, new) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Move Type to Another Namespace", null, {
            document.setText(edited)
            for (user in users) {
                val userDocument = FileDocumentManager.getInstance().getDocument(user) ?: continue
                CSharpUsings.insertion(userDocument.charsSequence, new)?.let { userDocument.insertString(it.offset, it.text) }
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        })
    }
}

/** "Move to Folder": the file of the type (a file with this one type) into a folder of the project, its namespace following ([CSharpMoveFileHandler]). */
class NativeCSharpMoveToFolderRow : NativeCSharpRefactorRow("Move to Folder") {
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val source = file as? CSharpFile ?: return false
        return typeAt(editor, source) != null && NativeCSharpMoveTypeEdits.topLevelTypes(source).size == 1
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val virtualFile = (file as? CSharpFile)?.virtualFile ?: return
        val project0 = io.github.dotnetsupport.msbuild.DotNetProjects.findOwningProject(virtualFile)?.parent ?: virtualFile.parent
        val current = VfsUtil.getRelativePath(virtualFile.parent, project0) ?: ""
        val relative = NativeCSharpMoveTypeEdits.ask(project, "Move ${virtualFile.name} to the folder (relative to the project):", "Move to Folder", current) ?: return
        if (relative.replace('\\', '/').trim('/') == current) return
        WriteCommandAction.writeCommandAction(project).withName("Move ${virtualFile.name}").run<RuntimeException> {
            val target = VfsUtil.createDirectoryIfMissing(project0, relative.replace('\\', '/').trim('/')) ?: return@run
            if (target.findChild(virtualFile.name) != null) return@run
            virtualFile.move(this, target)
        }
        val (old, new) = CSharpNamespaceSync.mismatch(project, virtualFile) ?: return
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed && virtualFile.isValid && CSharpNamespaceSync.confirm(project, virtualFile, old, new)) CSharpNamespaceSync.adjust(project, virtualFile, old, new)
        }, com.intellij.openapi.application.ModalityState.nonModal())
    }
}

/** "Extract Members to Partial": the member at the caret (or the members of the selection) into `Type.Part.cs`, the type made `partial`. */
class NativeCSharpExtractToPartialRow : NativeCSharpRefactorRow("Extract Members to Partial") {
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is CSharpFile || file.compilationUnit == null) return false
        val selection = editor.selectionModel.takeIf { it.hasSelection() }?.let { TextRange(it.selectionStart, it.selectionEnd) }
        val members = NativeCSharpMoveTypeEdits.membersAt(file, selection, editor.caretModel.offset)
        return NativeCSharpMoveTypeEdits.ownerOf(members) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val virtualFile = file.virtualFile ?: return
        val selection = editor.selectionModel.takeIf { it.hasSelection() }?.let { TextRange(it.selectionStart, it.selectionEnd) }
        val members = NativeCSharpMoveTypeEdits.membersAt(file, selection, editor.caretModel.offset)
        val owner = NativeCSharpMoveTypeEdits.ownerOf(members) ?: return
        val typeName = owner.identifier?.text ?: return
        val suggested = members.singleOrNull()?.let(CSharpDeclarationNames::name)?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } } ?: "Part"
        val part = NativeCSharpMoveTypeEdits.ask(project, "Name of the part ($typeName.<part>.cs):", "Extract Members to Partial", suggested) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
        val split = NativeCSharpMoveTypeEdits.extractToPartial(document.immutableCharSequence, owner, members, part) ?: return
        if (virtualFile.parent.findChild(split.fileName) != null) return Messages.showErrorDialog(project, "${split.fileName} already exists", "Extract Members to Partial")
        val created = WriteCommandAction.writeCommandAction(project).withName("Extract Members to Partial").compute<VirtualFile, RuntimeException> {
            val target = virtualFile.parent.createChildData(this, split.fileName)
            VfsUtil.saveText(target, split.newFileText)
            document.replaceString(0, document.textLength, split.remainingText)
            FileDocumentManager.getInstance().saveDocument(document)
            target
        }
        MoveTypeToFileIntention.removeUnusedUsings(project, created)
        OpenFileDescriptor(project, created).navigate(true)
    }
}
