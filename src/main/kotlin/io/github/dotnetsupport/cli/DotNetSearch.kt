package io.github.dotnetsupport.cli

import java.io.File

/**
 * Finding the `dotnet` host in folders the user points at (Settings | .NET) or lists in the
 * [SEARCH_PATHS_ENV] environment variable — for a corporate install in a non-standard directory like
 * `/usr/share/dotnet-sdk-8.8.403`. Pure, so it is tested without a real installation.
 */
object DotNetSearch {
    /** A list of directories, split like a PATH; set it by policy to point every machine at its install. */
    const val SEARCH_PATHS_ENV = "DOTNET_SUPPORT_SEARCH_PATHS"

    fun executableName(windows: Boolean): String = if (windows) "dotnet.exe" else "dotnet"

    /** Directories from the environment variable, split on the OS path separator; blanks dropped. */
    fun envSearchPaths(value: String?, separator: Char = File.pathSeparatorChar): List<String> =
        value?.split(separator)?.map(String::trim)?.filter { it.isNotEmpty() }.orEmpty()

    /**
     * The newest `dotnet` host in [searchPaths]: each directory is checked for the executable directly and in its
     * immediate `dotnet*` subdirectories (so `/usr/share` finds `/usr/share/dotnet-sdk-8.8.403`). One level deep, no
     * recursion. When several match, the one whose folder name carries the newest version wins; a name without a
     * version sorts oldest.
     */
    fun findIn(searchPaths: List<File>, executableName: String): File? =
        searchPaths.flatMap { hostsUnder(it, executableName) }
            .maxWithOrNull(compareBy(BY_VERSION) { it.parentFile?.name.orEmpty() })

    private fun hostsUnder(root: File, executableName: String): List<File> {
        if (!root.isDirectory) return emptyList()
        val here = File(root, executableName).takeIf { it.isFile }
        val subdirectories = root.listFiles { file -> file.isDirectory && file.name.startsWith("dotnet", ignoreCase = true) }
            .orEmpty().mapNotNull { File(it, executableName).takeIf { host -> host.isFile } }
        return listOfNotNull(here) + subdirectories
    }

    private val VERSION = Regex("""\d+(?:\.\d+)+""")

    /** Folder names by the version embedded in them (`dotnet-sdk-8.8.403` -> 8.8.403); nameless ones sort first. */
    private val BY_VERSION = Comparator<String> { a, b ->
        val first = version(a)
        val second = version(b)
        for (i in 0 until maxOf(first.size, second.size)) {
            val difference = first.getOrElse(i) { 0 } - second.getOrElse(i) { 0 }
            if (difference != 0) return@Comparator difference
        }
        a.compareTo(b)
    }

    private fun version(name: String): List<Int> =
        VERSION.findAll(name).lastOrNull()?.value?.split('.')?.map { it.toIntOrNull() ?: 0 }.orEmpty()
}
