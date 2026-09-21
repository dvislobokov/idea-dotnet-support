package io.github.dotnetsupport.roslyn

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.io.File
import java.util.function.Function
import javax.swing.JComponent

/**
 * Go to Declaration of a type of the framework or of a package opens what Roslyn has decompiled into a file of its own
 * (`%TEMP%/MetadataAsSource/<id>/DecompilationMetadataAsSourceFileProvider/<id>/Console.cs`, `tools/roslyn-lsp/capture.py`): a real
 * file, which the server knows (hover and further navigation work inside) and which would otherwise look like a file of the project
 * that can be edited. As in Rider it is read-only, and says where it comes from: in the title of the tab and in a banner.
 */
object RoslynDecompiled {
    /** `#region Assembly System.Console, Version=9.0.0.0, ...` and `// C:\...\System.Console.dll` on the first lines. */
    class Origin(val assembly: String, val version: String?, val path: String?)

    fun isDecompiled(file: VirtualFile): Boolean = isDecompiledPath(file.path) && file.extension.equals("cs", ignoreCase = true)

    fun isDecompiledPath(path: String): Boolean = path.replace('\\', '/').contains("/MetadataAsSource/", ignoreCase = true)

    fun origin(head: CharSequence): Origin? {
        val lines = head.lineSequence().take(4).map { it.trim() }.toList()
        val region = lines.firstOrNull { it.startsWith("#region Assembly ") } ?: return null
        val parts = region.removePrefix("#region Assembly ").split(',').map { it.trim() }
        val version = parts.firstOrNull { it.startsWith("Version=") }?.removePrefix("Version=")
        val path = lines.firstOrNull { it.startsWith("// ") && (it.endsWith(".dll", ignoreCase = true) || it.endsWith(".exe", ignoreCase = true)) }?.removePrefix("// ")
        return Origin(parts.first(), version, path)
    }

    /** The head of the file only: it is decompiled once and never changes, so reading 1 KB is all the title needs. */
    fun origin(file: VirtualFile): Origin? = runCatching {
        file.inputStream.use { stream -> origin(String(stream.readNBytes(HEAD_BYTES), Charsets.UTF_8).removePrefix("\uFEFF")) }
    }.getOrNull()

    private const val HEAD_BYTES = 1024
}

/** Decompiled code is not the code of the project: typing into it asks for nothing and changes nothing. */
class RoslynDecompiledWritingAccess(@Suppress("unused") private val project: Project) : WritingAccessProvider() {
    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> = files.filter(RoslynDecompiled::isDecompiled)
    override fun isPotentiallyWritable(file: VirtualFile): Boolean = !RoslynDecompiled.isDecompiled(file)
}

/** `Console.cs [System.Console]`: the tab of a decompiled file says which assembly it is. */
class RoslynDecompiledTabTitle : EditorTabTitleProvider {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        if (!RoslynDecompiled.isDecompiled(file)) return null
        return "${file.name} [${RoslynDecompiled.origin(file)?.assembly ?: "decompiled"}]"
    }
}

class RoslynDecompiledBanner : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!RoslynDecompiled.isDecompiled(file)) return null
        val origin = RoslynDecompiled.origin(file)
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text = buildString {
                    append("Decompiled from ${origin?.assembly ?: "an assembly"}")
                    origin?.version?.let { append(" $it") }
                    append(". Read-only")
                }
                origin?.path?.let(::File)?.takeIf { it.isFile }?.let { assembly ->
                    createActionLabel("Show Assembly in ${RevealFileAction.getFileManagerName()}") { RevealFileAction.openFile(assembly) }
                }
            }
        }
    }
}
