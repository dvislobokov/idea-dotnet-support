package io.github.dotnetsupport.frameworks.unity

import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * The package of the link with the Unity editor (UNITY_PLAN.md, "Интеграция с редактором"): its sources are in the plugin
 * (`unity-package/` of the repository), the plugin writes them into `Packages/` of the Unity project — an embedded package, which Unity
 * loads without a line in `manifest.json` (it adds one to `packages-lock.json`, the user is told) — and hides the folder from git by
 * `.git/info/exclude`, not by the project's `.gitignore`. The `.meta` files Unity makes beside them stay on an update.
 */
object UnityEditorPackage {
    const val NAME = "io.github.dotnetsupport.unity"

    /** The files of the package, as the build of the plugin carries them. */
    val FILES = listOf("package.json", "Editor/DotNetSupport.Unity.Editor.asmdef", "Editor/IdeConnection.cs", "Editor/ConsoleLog.cs", "Editor/ExternalEditor.cs")

    enum class Status { MISSING, OUTDATED, CURRENT }

    fun bundled(path: String): ByteArray =
        UnityIcons::class.java.getResourceAsStream("/unity-package/$NAME/$path")?.use { it.readBytes() } ?: error("the plugin carries no $NAME/$path")

    /** `1.0.0` of the bundled package.json: what the editor reports in its state once it loaded this version. */
    val version: String by lazy { Regex(""""version"\s*:\s*"([^"]+)"""").find(String(bundled("package.json")))?.groupValues?.get(1) ?: "" }

    fun folder(root: File): File = File(root, "Packages/$NAME")

    fun status(root: File): Status {
        val folder = folder(root)
        if (!folder.isDirectory) return Status.MISSING
        return if (FILES.all { path -> File(folder, path).let { it.isFile && it.readBytes().contentEquals(bundled(path)) } }) Status.CURRENT else Status.OUTDATED
    }

    /** Writes the package into the project [root] (a new one, or the files of another version over the old), hides it from git. */
    fun install(root: File) {
        val folder = folder(root)
        for (path in FILES) {
            val file = File(folder, path)
            file.parentFile.mkdirs()
            val bytes = bundled(path)
            if (!file.isFile || !file.readBytes().contentEquals(bytes)) file.writeBytes(bytes)
        }
        excludeFromGit(root)
        LocalFileSystem.getInstance().refreshIoFiles(listOf(folder), true, true, null)
    }

    /** `/Packages/io.github.dotnetsupport.unity/` (from the root of the repository) in `.git/info/exclude` of the repository of [root], once. */
    fun excludeFromGit(root: File) {
        var top: File? = root.absoluteFile
        while (top != null && !File(top, ".git").isDirectory) top = top.parentFile
        top ?: return
        val relative = folder(root).absoluteFile.relativeTo(top).invariantSeparatorsPath
        val exclude = File(top, ".git/info/exclude")
        val line = "/$relative/"
        val text = if (exclude.isFile) exclude.readText() else ""
        if (text.lines().any { it.trim() == line }) return
        exclude.parentFile.mkdirs()
        exclude.writeText(text + (if (text.isEmpty() || text.endsWith("\n")) "" else "\n") + "# the link of C# Project Support with the Unity editor (installed by the IDE)\n$line\n")
    }
}
