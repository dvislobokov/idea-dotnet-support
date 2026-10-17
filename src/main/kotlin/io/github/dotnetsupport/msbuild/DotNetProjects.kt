package io.github.dotnetsupport.msbuild

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

object DotNetProjects {
    val PROJECT_EXTENSIONS: Set<String> = setOf("csproj", "fsproj", "vbproj")

    fun isProjectFile(file: VirtualFile): Boolean = !file.isDirectory && file.extension?.lowercase() in PROJECT_EXTENSIONS

    /**
     * [file] itself when it is a project file, otherwise the project file of the nearest directory up the tree. A directory with several
     * project files (Unity generates one per assembly definition, all in the root of the project): the one whose explicit `Compile` items
     * list [file], else the first.
     */
    fun findOwningProject(file: VirtualFile): VirtualFile? {
        if (isProjectFile(file)) return file
        var directory = if (file.isDirectory) file else file.parent
        while (directory != null) {
            val candidates = directory.children.filter(::isProjectFile)
            if (candidates.isNotEmpty()) {
                if (candidates.size == 1 || file.isDirectory) return candidates.first()
                val relative = VfsUtilCore.getRelativePath(file, directory, '/') ?: return candidates.first()
                return candidates.firstOrNull { compileItems(it).contains(relative.lowercase()) } ?: candidates.first()
            }
            directory = directory.parent
        }
        return null
    }

    private val compileItemsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Set<String>>>()

    /** The `<Compile Include="…">` paths of a project file as written (lower case, `/` separators), a cheap static read cached by the file's stamp. */
    fun compileItems(projectFile: VirtualFile): Set<String> {
        val stamp = projectFile.modificationStamp
        compileItemsCache[projectFile.path]?.takeIf { it.first == stamp }?.let { return it.second }
        val text = runCatching { VfsUtilCore.loadText(projectFile) }.getOrDefault("")
        val items = COMPILE_INCLUDE.findAll(text).mapTo(HashSet()) { it.groupValues[1].replace('\\', '/').lowercase() }
        compileItemsCache[projectFile.path] = stamp to items
        return items
    }

    private val COMPILE_INCLUDE = Regex("""<Compile\s+Include\s*=\s*"([^"$*?]+)"""")

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
