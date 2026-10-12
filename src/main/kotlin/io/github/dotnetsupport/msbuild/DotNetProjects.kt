package io.github.dotnetsupport.msbuild

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

object DotNetProjects {
    val PROJECT_EXTENSIONS: Set<String> = setOf("csproj", "fsproj", "vbproj")

    fun isProjectFile(file: VirtualFile): Boolean = !file.isDirectory && file.extension?.lowercase() in PROJECT_EXTENSIONS

    /** [file] itself when it is a project file, otherwise the project file of the nearest directory up the tree. */
    fun findOwningProject(file: VirtualFile): VirtualFile? {
        if (isProjectFile(file)) return file
        var directory = if (file.isDirectory) file else file.parent
        while (directory != null) {
            directory.children.firstOrNull(::isProjectFile)?.let { return it }
            directory = directory.parent
        }
        return null
    }

    /**
     * Default namespace of a new file in [directory]: root namespace of the project plus the folders
     * between the project and the file, the way the SDK and Rider compute it.
     */
    fun namespaceFor(directory: VirtualFile, projectFile: VirtualFile, rootNamespace: String?): String {
        val root = rootNamespace ?: projectFile.nameWithoutExtension
        val folders = VfsUtilCore.getRelativePath(directory, projectFile.parent, '/').orEmpty()
        return (root.split('.') + folders.split('/'))
            .filter { it.isNotBlank() }
            .joinToString(".", transform = ::toIdentifier)
    }

    fun toIdentifier(text: String): String {
        val identifier = text.trim().map { if (it == '_' || it.isLetterOrDigit()) it else '_' }.joinToString("")
        return if (identifier.firstOrNull()?.isDigit() == true) "_$identifier" else identifier
    }
}
