package io.github.dotnetsupport.cli

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap

/**
 * The system refused to start a program: on Linux and macOS a file without the execute bit or on a file system mounted with `noexec`
 * (the home folder of a managed machine), so a global tool in ~/.dotnet/tools does not start. The JDK says only "error=13, Permission
 * denied" or, through jspawnhelper, "Exec failed, error: 1 (Operation not permitted)" in the language of the system; this turns it into a
 * warning that names the file and what to check.
 */
object ExecDenied {
    /** EPERM (1) or EACCES (13) of `Cannot run program "…": error=13, …` and of `…: Exec failed, error: 1 (…)`. */
    private val CODE = Regex("""error(?:=|: )(1|13)\b""")
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** Whether [error] or one of its causes is a refusal of the system to start a program. */
    fun matches(error: Throwable, windows: Boolean = SystemInfo.isWindows): Boolean = !windows &&
        generateSequence(error) { it.cause }.take(10).any { e -> e.message?.let { "Cannot run program" in it && CODE.containsMatchIn(it) } == true }

    /**
     * What to do about it. [executeBit]: the owner's execute bit of the file, null when not known. `File.canExecute` does not tell the
     * two cases apart: it is false on a `noexec` file system too.
     */
    fun explanation(executable: String, executeBit: Boolean?): String {
        // only on Linux and macOS: the path is split by hand, File would turn it into a Windows one in the tests
        val folder = executable.substringBeforeLast('/', "").ifEmpty { executable }
        val reason = if (executeBit == false) "the file has no execute permission. Run <code>chmod +x ${esc(executable)}</code>."
        else "the file system of ${esc(folder)} is probably mounted with <code>noexec</code> (check: <code>findmnt -T ${esc(folder)}</code>). " +
            "Ask the administrator to allow running programs there, or install the tool into a folder where it is allowed."
        return "The system does not allow running ${esc(executable)}: $reason"
    }

    /** Logs and shows the warning when [error] is such a refusal, once per program in a session; true when it was. The program is the one the error names. */
    fun report(project: Project?, error: Throwable): Boolean {
        if (!matches(error)) return false
        val executable = program(error) ?: "the program"
        val text = explanation(executable, executeBit(executable))
        if (reported.add(executable)) {
            PluginLog.warn(LOG_CATEGORY, StringUtil.removeHtmlTags(text) + " (${error.message})")
            NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
                .createNotification("Running programs is not allowed", text, NotificationType.WARNING)
                .apply { if (project != null) addAction(NotificationAction.createSimple("Plugin Logs") { PluginLogsToolWindowFactory.show(project) }) }
                .notify(project)
        }
        return true
    }

    /** The path in `Cannot run program "<path>"`. */
    fun program(error: Throwable): String? = generateSequence(error) { it.cause }.take(10)
        .firstNotNullOfOrNull { e -> e.message?.let { PROGRAM.find(it)?.groupValues?.get(1) } }

    private val PROGRAM = Regex("""Cannot run program "([^"]+)"""")

    private fun executeBit(executable: String): Boolean? =
        runCatching { PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(File(executable).toPath()) }.getOrNull()

    private fun esc(text: String) = StringUtil.escapeXmlEntities(text)

    const val LOG_CATEGORY = "tools"
}
