package io.github.dotnetsupport.frameworks.unity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.frameworks.unity.UnityEditorLink.Status
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The IDE as Unity's external script editor (UNITY_PLAN.md, step 3; `ExternalEditor.cs` of the package): the IDE writes
 * `Temp/DotNetSupport/ide.json` — its process, its launcher (what Preferences | External Tools lists and Unity starts when the IDE is
 * closed), its name, a heartbeat — and opens the files the editor asks for in `Temp/DotNetSupport/ide-commands/<n>.json`. Pure: the tests
 * give it the folder.
 */
object UnityExternalEditor {
    /** A file to open at a line and column (1-based; 0 or less: none); an empty [path]: the project only (Assets | Open C# Project). */
    data class Open(val path: String, val line: Int, val column: Int)

    fun ideFile(root: File): File = File(UnityEditorLink.folder(root), "ide.json")

    fun ideJson(processId: Long, launcher: String, name: String, now: Long): String = JsonObject().apply {
        addProperty("pid", processId)
        addProperty("path", launcher)
        addProperty("name", name)
        addProperty("heartbeat", now)
    }.toString()

    /** Written beside and moved over: the editor never reads half a file. */
    fun writeIde(root: File, json: String) {
        val file = ideFile(root)
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "ide.json.tmp")
        temporary.writeText(json)
        runCatching { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            .recoverCatching { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    /** The commands the editor wrote since the last time, in their order; each file is taken (deleted) once read. */
    fun takeCommands(root: File): List<Open> {
        val folder = File(UnityEditorLink.folder(root), "ide-commands")
        val files = folder.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.sortedBy { it.name } ?: return emptyList()
        return files.mapNotNull { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            file.delete()
            parse(text)
        }
    }

    fun parse(text: String): Open? {
        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        if (json.get("command")?.asString != "open") return null
        return Open(json.get("file")?.asString.orEmpty(), json.get("line")?.asInt ?: -1, json.get("column")?.asInt ?: -1)
    }

    /** What starts this IDE from outside: the launcher in `bin` (Windows, Linux), the application bundle (macOS). */
    fun launcher(): String {
        val home = File(PathManager.getHomePath())
        val script = ApplicationNamesInfo.getInstance().scriptName
        return when {
            SystemInfo.isMac -> (home.takeIf { it.name == "Contents" }?.parentFile ?: home).path
            SystemInfo.isWindows -> File(home, "bin/${script}64.exe").path
            else -> File(home, "bin/$script.sh").path
        }
    }

    fun ideName(): String = "${ApplicationNamesInfo.getInstance().fullProductName} ${ApplicationInfo.getInstance().fullVersion}"

    /** Opens [open] in [project] and brings its window up (the editor allowed it the foreground on Windows). */
    fun open(project: Project, open: Open) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            if (open.path.isNotEmpty()) {
                val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(open.path))
                if (file != null) OpenFileDescriptor(project, file, (open.line - 1).coerceAtLeast(0), (open.column - 1).coerceAtLeast(0)).navigate(true)
            }
            ProjectUtil.focusProjectWindow(project, true)
        }, project.disposed)
    }

    private const val DECLINED_KEY = "dotnet.unity.externalEditor.declined"

    /**
     * Once connected to an editor that opens scripts elsewhere: the offer to open them here — only on the user's word (the choice of
     * Preferences | External Tools is theirs), and never again after "Don't ask again".
     */
    fun offer(project: Project, link: UnityEditorLink.Link) {
        if (link.status != Status.CONNECTED || link.state?.ideIsEditor != false) return
        if (PropertiesComponent.getInstance(project).getBoolean(DECLINED_KEY)) return
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification(
                "Open scripts from Unity in this IDE?",
                "A double click on a script or on a line of the console in Unity would open it here, at the line. Unity keeps generating the " +
                    "projects with its Rider or Visual Studio package. Preferences | External Tools of Unity can change it back.",
                NotificationType.INFORMATION,
            )
            .setIcon(UnityIcons.Unity)
            .addAction(NotificationAction.createSimpleExpiring("Use This IDE") { UnityEditorConnection.getInstance(project).send("use-as-editor") })
            .addAction(NotificationAction.createSimpleExpiring("Don't ask again") { PropertiesComponent.getInstance(project).setValue(DECLINED_KEY, true) })
            .notify(project)
    }
}

/** .NET | Use as Unity's External Script Editor: what Preferences | External Tools of Unity would do, asked from the IDE. */
class UnityUseAsExternalEditorAction : UnityEditorAction(
    "Open Scripts from Unity in This IDE", "Make this IDE the external script editor of Unity (Preferences | External Tools)", UnityIcons.Unity,
) {
    override fun isEnabled(link: UnityEditorLink.Link): Boolean = link.state?.ideIsEditor == false

    override fun actionPerformed(e: AnActionEvent) = send(e, "use-as-editor")
}

/** .NET | Stop Opening Scripts from Unity Here: Unity's external script editor of before `use-as-editor` again. */
class UnityRestoreExternalEditorAction : UnityEditorAction(
    "Stop Opening Scripts from Unity Here", "Give Unity back the external script editor it had before this IDE", UnityIcons.Unity,
) {
    override fun update(e: AnActionEvent, link: UnityEditorLink.Link) {
        e.presentation.isVisible = link.state?.ideIsEditor == true
    }

    override fun actionPerformed(e: AnActionEvent) = send(e, "restore-editor")
}
