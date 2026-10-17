package io.github.dotnetsupport.frameworks.unity

import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.solution.SolutionLayout
import java.io.File
import javax.swing.Icon

object UnityIcons {
    @JvmField val Unity: Icon = IconLoader.getIcon("/icons/unity/unity.svg", UnityIcons::class.java)
}

/**
 * A Unity project (UNITY_PLAN.md): the folder with `ProjectSettings/ProjectVersion.txt` and `Assets/`. The editor generates the solution
 * and the projects of the old format into that folder, so it is the directory of the solution too.
 */
object UnityProjects {
    const val PROJECT_VERSION_FILE = "ProjectSettings/ProjectVersion.txt"

    /** The folders Unity writes and rewrites itself: caches, build output, logs — not a part of the project to anyone. */
    val GENERATED_DIRECTORIES: Set<String> = setOf("Library", "Temp", "Logs", "obj", "UserSettings")

    fun isUnityRoot(directory: VirtualFile): Boolean =
        directory.isDirectory && directory.findChild("Assets")?.isDirectory == true && directory.findFileByRelativePath(PROJECT_VERSION_FILE)?.isDirectory == false

    /** The Unity project [file] is in: the nearest folder up the tree that is one; null outside of any. */
    fun rootOf(file: VirtualFile): VirtualFile? {
        var directory: VirtualFile? = if (file.isDirectory) file else file.parent
        while (directory != null) {
            if (isUnityRoot(directory)) return directory
            directory = directory.parent
        }
        return null
    }

    /** `6000.6.0f1` of `m_EditorVersion: 6000.6.0f1`; null when the file is missing or says nothing. */
    fun editorVersion(root: VirtualFile): String? {
        val file = root.findFileByRelativePath(PROJECT_VERSION_FILE) ?: return null
        val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return null
        return Regex("""^m_EditorVersion:\s*(\S+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
    }

    /** `.meta` beside every asset: the editor's identity of the file, noise in the tree. */
    fun isMetaFile(file: VirtualFile): Boolean = !file.isDirectory && file.name.endsWith(".meta", ignoreCase = true)

    fun isGeneratedDirectory(directory: VirtualFile): Boolean =
        directory.isDirectory && directory.name in GENERATED_DIRECTORIES && directory.parent?.let(::isUnityRoot) == true

    /**
     * The editor of [version] as Unity Hub installs it: `Hub/Editor/<version>/Editor/Unity.exe` under Program Files on Windows,
     * `~/Unity/Hub/Editor/<version>/Editor/Unity` on Linux, `/Applications/Unity/Hub/Editor/<version>/Unity.app` on macOS; null when
     * it is not there.
     */
    fun editorExecutable(version: String): File? {
        val candidates = when {
            SystemInfo.isWindows -> listOf(System.getenv("ProgramFiles"), "C:\\Program Files").filterNotNull().map { File(it, "Unity\\Hub\\Editor\\$version\\Editor\\Unity.exe") }
            SystemInfo.isMac -> listOf(File("/Applications/Unity/Hub/Editor/$version/Unity.app/Contents/MacOS/Unity"))
            else -> listOf(File(System.getProperty("user.home"), "Unity/Hub/Editor/$version/Editor/Unity"))
        }
        return candidates.firstOrNull { it.isFile }
    }
}

/** The generated folders of a Unity project are skipped by the solution finder and hidden in the Solution view ([SolutionLayout]). */
class UnitySolutionLayout : SolutionLayout {
    override fun isGeneratedDirectory(directory: VirtualFile): Boolean = UnityProjects.isGeneratedDirectory(directory)
}
