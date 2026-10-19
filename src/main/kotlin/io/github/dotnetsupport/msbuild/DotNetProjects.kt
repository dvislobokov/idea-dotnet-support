package io.github.dotnetsupport.msbuild

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
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
            // a project in the home folder (`dotnet new console` run in ~) owns nothing by the walk: the plugin's own files under
            // ~/Library/Caches got it, and MSBuild's `**/*` over the whole home read every app's data (the macOS "data of other apps"
            // prompt) and never finished; such a project is opened by its own file only
            if (isHomeOrAbove(directory)) return null
            val candidates = directory.children.filter(::isProjectFile)
            if (candidates.isNotEmpty()) {
                if (candidates.size == 1) return candidates.first()
                val relative = VfsUtilCore.getRelativePath(file, directory, '/')?.lowercase() ?: return candidates.first()
                // a directory: the project with a file of it among its items (Build 'X' of the folder `Assets/Game/Boot` builds Game.Boot)
                candidates.firstOrNull { project -> compileItems(project).let { items -> if (file.isDirectory) items.any { it.startsWith("$relative/") } else relative in items } }
                    ?.let { return it }
                // none lists it, in a folder a framework writes itself (Unity's Library/: the sources of its packages, which Unity
                // compiles on its own): it is in none of them. It used to get the first project of the folder — a new project there
                // (Unity adds one for a package) gave thousands of those files other `#if` symbols, all of them parsed and indexed
                // again, and the PSI met the stubs of the old symbols until the index caught up (101 IDE errors after a compilation)
                if (!file.isDirectory && inGeneratedDirectory(file, directory)) return null
                // elsewhere (a script just made in Assets/, before Unity lists it) the first by name: the same whatever order the folder has
                return candidates.minBy { it.name.lowercase() }
            }
            directory = directory.parent
        }
        return null
    }

    /** The user's home folder or one of its parents, on the local disk. */
    fun isHomeOrAbove(directory: VirtualFile): Boolean {
        if (!directory.isInLocalFileSystem) return false
        val home = testHome ?: homePath ?: return false
        val path = directory.path.trimEnd('/')
        val ignoreCase = !SystemInfo.isFileSystemCaseSensitive
        return home.equals(path, ignoreCase) || home.startsWith("$path/", ignoreCase)
    }

    private val homePath: String? by lazy {
        System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { FileUtil.toSystemIndependentName(it).trimEnd('/') }
    }

    @Volatile private var testHome: String? = null

    /** Makes [path] the home folder for a test; null restores the real one. */
    @org.jetbrains.annotations.TestOnly
    fun setHomeForTests(path: String?) { testHome = path?.let { FileUtil.toSystemIndependentName(it).trimEnd('/') } }

    /** A folder between [file] and [top] (the folder of the projects) is one a framework generates ([io.github.dotnetsupport.solution.SolutionLayout]). */
    private fun inGeneratedDirectory(file: VirtualFile, top: VirtualFile): Boolean {
        var directory = file.parent
        while (directory != null && directory != top) {
            if (io.github.dotnetsupport.solution.SolutionLayout.isGeneratedDirectory(directory)) return true
            directory = directory.parent
        }
        return false
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
     * between the project and the file, the way the SDK and Rider compute it. A framework with a rule of its own answers first
     * ([io.github.dotnetsupport.solution.SolutionLayout.namespaceFor]: Unity counts the folders from the assembly definition).
     */
    fun namespaceFor(directory: VirtualFile, projectFile: VirtualFile, rootNamespace: String?): String {
        io.github.dotnetsupport.solution.SolutionLayout.namespaceFor(directory, projectFile, rootNamespace)?.let { return it }
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
