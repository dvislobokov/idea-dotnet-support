package io.github.dotnetsupport.roslyn

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.lang.CSharpDeclarations
import org.eclipse.lsp4j.WorkspaceEdit
import java.util.concurrent.TimeUnit

private val LOG = logger<RoslynFileRename>()

/**
 * Rename of a type renames its file too, when the file is named as the type (`Order.cs` for `Order`), as in Rider. The server renames
 * only the text (`tools/roslyn-lsp/capture.py`, the rename of `Scenarios`), and the platform could not apply a rename of a file in a
 * workspace edit anyway: its client says `resourceOperations: ["create"]`. So the plugin does it, once the platform has applied the edit.
 */
object RoslynFileRename {
    /**
     * [edited]: path -> (its text, the offsets where the edits of the rename start). The file to rename is the one named `<oldName>.cs`
     * where an edit starts at the name of a type declared in it, under the old name or, when the edit is applied already, the new one.
     * That is what tells the rename of the type from the rename of a method that happens to be named as the file.
     */
    fun fileToRename(edited: Map<String, Pair<CharSequence, List<Int>>>, oldName: String, newName: String): String? {
        if (oldName.isEmpty() || newName == oldName || !isIdentifier(newName)) return null
        return edited.entries.firstOrNull { (path, value) ->
            val (text, starts) = value
            path.substringAfterLast('/').substringAfterLast('\\') == "$oldName.cs" &&
                CSharpDeclarations.scan(text).all().any { it.kind.isType && (it.name == oldName || it.name == newName) && it.nameRange.startOffset in starts }
        }?.key
    }

    /** The edit is in: the type is declared under the new name, and nowhere under the old one. */
    fun isApplied(text: CharSequence, oldName: String, newName: String): Boolean {
        val types = CSharpDeclarations.scan(text).all().filter { it.kind.isType }.map { it.name }.toSet()
        return newName in types && oldName !in types
    }

    fun isIdentifier(name: String): Boolean = name.isNotEmpty() && Character.isJavaIdentifierStart(name.first().let { if (it == '@') 'a' else it }) &&
        name.drop(1).all(Character::isJavaIdentifierPart)

    /** After the answer of `textDocument/rename`: find the file, wait for the platform to apply the edit, rename the file. */
    fun afterRename(project: Project, client: LspClient, edit: WorkspaceEdit, oldName: String, newName: String) {
        val files = HashMap<String, VirtualFile>()
        val edited = ReadAction.compute<Map<String, Pair<CharSequence, List<Int>>>, RuntimeException> {
            edits(edit).mapNotNull { (uri, starts) ->
                val file = client.descriptor.findFileByUri(uri) ?: return@mapNotNull null
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
                files[file.path] = file
                file.path to (document.immutableCharSequence to starts.mapNotNull { RoslynCtrlHoverReferenceProvider.offset(document, it) })
            }.toMap()
        }
        val file = fileToRename(edited, oldName, newName)?.let(files::get) ?: return
        whenApplied(project, file, oldName, newName, attempts = ATTEMPTS)
    }

    private fun edits(edit: WorkspaceEdit): Map<String, List<org.eclipse.lsp4j.Position>> {
        val result = LinkedHashMap<String, MutableList<org.eclipse.lsp4j.Position>>()
        edit.documentChanges?.forEach { change ->
            if (change.isLeft) change.left.let { result.getOrPut(it.textDocument.uri) { mutableListOf() } += it.edits.map { e -> e.range.start } }
        }
        edit.changes?.forEach { (uri, edits) -> result.getOrPut(uri) { mutableListOf() } += edits.map { it.range.start } }
        return result
    }

    private fun whenApplied(project: Project, file: VirtualFile, oldName: String, newName: String, attempts: Int) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !file.isValid) return@invokeLater
            val text = FileDocumentManager.getInstance().getDocument(file)?.immutableCharSequence ?: return@invokeLater
            when {
                isApplied(text, oldName, newName) -> rename(project, file, newName)
                attempts > 0 -> AppExecutorUtil.getAppScheduledExecutorService().schedule({ whenApplied(project, file, oldName, newName, attempts - 1) }, 100, TimeUnit.MILLISECONDS)
                else -> LOG.info("Rename of $oldName: the edit has not come to ${file.name}, the file keeps its name")
            }
        }, ModalityState.nonModal())
    }

    private fun rename(project: Project, file: VirtualFile, newName: String) {
        val target = "$newName.cs"
        if (file.name == target) return
        if (file.parent?.findChild(target) != null) return LOG.info("Rename of ${file.name}: $target is there already, the file keeps its name")
        runCatching {
            WriteCommandAction.writeCommandAction(project).withName("Rename File to $target").run<java.io.IOException> { file.rename(RoslynFileRename, target) }
        }.onFailure { LOG.warn("Cannot rename ${file.path} to $target", it) }
    }

    // the edits of the platform go in on EDT after the answer arrives: 5 s is far more than it takes
    private const val ATTEMPTS = 50
}
