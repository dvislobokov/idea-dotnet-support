package io.github.dotnetsupport.roslyn

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import org.eclipse.lsp4j.FileChangeType

/**
 * The file watching the server asks the client for and the platform does not do. After `initialized` the server registers
 * `workspace/didChangeWatchedFiles` for `.cs`, `.razor`, `.cshtml` anywhere below a project folder and for the project file (captured in
 * `capture-5.12/63-server_to_client_requests.json`); the LSP client of the platform drops the registration, so a `.cs` created next
 * to the others reached the project only 15–20 s later, by a roundabout way through `didOpen`. Here the events of the VFS under the
 * opened folder become `didChangeWatchedFiles` notifications, as VS Code sends them. The server matches them against its own
 * patterns, so the set of files here may be wider than its registrations.
 */
object RoslynWatchedFiles {
    private val EXTENSIONS = setOf("cs", "csproj", "fsproj", "vbproj", "props", "targets", "razor", "cshtml", "sln", "slnx")
    private val NAMES = setOf(".editorconfig", "global.json")
    private val SKIPPED_FOLDERS = setOf("bin", "obj", ".git", ".idea", ".vs", "node_modules")

    class Change(val path: String, val type: FileChangeType) {
        override fun equals(other: Any?): Boolean = other is Change && other.path == path && other.type == type
        override fun hashCode(): Int = path.hashCode() * 31 + type.hashCode()
        override fun toString(): String = "$type $path"
    }

    /** Whether the server cares about [path] (with forward slashes) under the opened folder [root]. */
    fun isWatched(path: String, root: String): Boolean {
        val prefix = root.trimEnd('/') + "/"
        if (!path.startsWith(prefix, ignoreCase = true)) return false
        val relative = path.substring(prefix.length)
        val parts = relative.split('/')
        if (parts.dropLast(1).any { it in SKIPPED_FOLDERS }) return false
        val name = parts.last()
        return name in NAMES || name.substringAfterLast('.', "").lowercase() in EXTENSIONS
    }

    /** What to tell the server of a batch of VFS events: a move or a rename is a deletion and a creation, as in VS Code. */
    fun changes(events: List<VFileEvent>, root: String): List<Change> {
        val changes = LinkedHashSet<Change>()
        fun add(path: String?, type: FileChangeType) { if (path != null && isWatched(path, root)) changes += Change(path, type) }
        for (event in events) when (event) {
            is VFileCreateEvent -> add(event.path, FileChangeType.Created)
            is VFileCopyEvent -> add(event.path, FileChangeType.Created)
            is VFileDeleteEvent -> add(event.path, FileChangeType.Deleted)
            is VFileContentChangeEvent -> add(event.path, FileChangeType.Changed)
            is VFileMoveEvent -> { add(event.oldPath, FileChangeType.Deleted); add(event.newPath, FileChangeType.Created) }
            is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) { add(event.oldPath, FileChangeType.Deleted); add(event.newPath, FileChangeType.Created) }
        }
        return changes.toList()
    }
}

/** The VFS listener of the project: what changed under the opened folder goes to the running servers ([RoslynWorkspace.filesChanged]). */
class RoslynFileWatcher(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) return
        val root = project.guessProjectDir()?.path ?: return
        val changes = RoslynWatchedFiles.changes(events, root)
        if (changes.isNotEmpty()) project.service<RoslynWorkspace>().filesChanged(changes)
    }
}
