package io.github.dotnetsupport.cli

import com.intellij.openapi.util.SystemInfo

/**
 * Paths read from files of a project (`project.assets.json`, an assembly) are not followed to the network: on Windows even a check that a
 * file exists at `\\host\share` makes the system log on to that host and give it the NTLM hash of the user. Such a file may come with a
 * cloned repository, so only local paths are followed — and the network packages folder the user has set up himself (`NUGET_PACKAGES`).
 */
object LocalPaths {
    /** `C:\...` on Windows, `/...` elsewhere; never `\\host`, `//host`, `/\host`, `\\?\UNC\...`, whatever the slashes. */
    fun isLocal(path: String, windows: Boolean = SystemInfo.isWindows): Boolean =
        if (windows) path.length >= 3 && path[0].isAsciiLetter() && path[1] == ':' && (path[2] == '\\' || path[2] == '/')
        else path.startsWith('/') && !path.startsWith("//")

    /** A packages folder named by `project.assets.json` that may be read: a local one, or the one of `NUGET_PACKAGES`. */
    fun isReadablePackagesFolder(path: String, nugetPackages: String? = System.getenv("NUGET_PACKAGES")): Boolean =
        isLocal(path) || (nugetPackages != null && normalize(path) == normalize(nugetPackages))

    private fun normalize(path: String) = path.replace('\\', '/').trimEnd('/').lowercase()

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}
