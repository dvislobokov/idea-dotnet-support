package io.github.dotnetsupport.upgrade

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.build.BuildViewCommandOutput
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.newproject.DotNetTemplates
import io.github.dotnetsupport.solution.SolutionService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/** Severities of the report, the blocking ones first. */
enum class UpgradeSeverity { MANDATORY, OPTIONAL, POTENTIAL, INFORMATION }

/** One place that needs attention: a package reference, a project property or a use of an API. */
class UpgradeIncident(
    val ruleId: String,
    val severity: UpgradeSeverity,
    /** What the rule is about: "NuGet package upgrade is recommended". */
    val title: String,
    /** What exactly was found: `Newtonsoft.Json 9.0.1`, `T:System.Net.WebRequest`. */
    val subject: String,
    val details: String,
    val file: File?,
    val line: Int,
    val column: Int,
    val link: String?,
    val effort: Int,
)

/** The JSON report of `upgrade-assistant analyze --serializer json`. */
class UpgradeReport(val target: String, val projects: Int, val effort: Int, val incidents: List<UpgradeIncident>) {
    fun count(severity: UpgradeSeverity): Int = incidents.count { it.severity == severity }

    companion object {
        /** [anchor]: the analyzed project or solution; the report may shorten paths, they are resolved against its directories. */
        fun parse(json: String, anchor: File): UpgradeReport? {
            val root = runCatching { JsonParser.parseString(json.removePrefix("\uFEFF")) as? JsonObject }.getOrNull() ?: return null
            val rules = root.obj("rules")
            val projects = root.array("projects")
            val incidents = projects.flatMap { it.array("ruleInstances") }.mapNotNull { instance ->
                val ruleId = instance.string("ruleId") ?: return@mapNotNull null
                val rule = rules?.obj(ruleId)
                val location = instance.obj("location")
                val properties = location?.obj("properties")
                val newVersion = properties?.string("PackageNewVersion")
                UpgradeIncident(
                    ruleId,
                    severity = UpgradeSeverity.entries.firstOrNull { it.name.equals(rule?.string("severity"), ignoreCase = true) } ?: UpgradeSeverity.INFORMATION,
                    title = rule?.string("label") ?: ruleId,
                    subject = (location?.string("label") ?: location?.string("snippet").orEmpty()).lineSequence().firstOrNull().orEmpty() +
                        if (newVersion != null) " → $newVersion" else "",
                    details = rule?.string("description").orEmpty(),
                    file = location?.string("path")?.let { resolvePath(it, anchor) },
                    line = location?.int("line") ?: 0,
                    column = location?.int("column") ?: 0,
                    link = (location?.array("links").orEmpty() + rule?.array("links").orEmpty()).firstNotNullOfOrNull { it.string("url") },
                    effort = rule?.int("effort") ?: 0,
                )
            }.sortedWith(compareBy<UpgradeIncident> { it.severity }.thenBy { it.ruleId }.thenBy { it.file?.path.orEmpty() }.thenBy { it.line })

            val summary = root.obj("stats")?.obj("summary")
            return UpgradeReport(
                target = root.obj("settings")?.string("targetDisplayName").orEmpty(),
                projects = summary?.int("projects") ?: projects.size,
                effort = summary?.int("effort") ?: incidents.sumOf { it.effort },
                incidents = incidents,
            )
        }

        /**
         * In the default privacy mode the beginning of a path is cut off (`src\App\Program.cs` instead of `C:\work\src\App\Program.cs`),
         * so every tail of the path is tried against the directories above [anchor].
         */
        fun resolvePath(reportPath: String, anchor: File): File? {
            File(reportPath).takeIf { it.isAbsolute && it.exists() }?.let { return it }
            val segments = reportPath.split('\\', '/').filter { it.isNotEmpty() }
            val directories = generateSequence(if (anchor.isDirectory) anchor else anchor.parentFile) { it.parentFile }.toList()
            for (start in segments.indices) {
                val tail = segments.drop(start).joinToString(File.separator)
                directories.firstNotNullOfOrNull { directory -> File(directory, tail).takeIf { it.exists() } }?.let { return it }
            }
            return null
        }

        private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject
        private fun JsonObject.array(name: String): List<JsonObject> = (get(name) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        private fun JsonObject.primitive(name: String): JsonElement? = get(name)?.takeIf { it.isJsonPrimitive }
        private fun JsonObject.string(name: String): String? = primitive(name)?.asString?.ifBlank { null }
        private fun JsonObject.int(name: String): Int? = primitive(name)?.let { runCatching { it.asInt }.getOrNull() }
    }
}

object UpgradeAssistant {
    const val PACKAGE = "upgrade-assistant"

    fun findExecutable(): File? = DotNetTool.UPGRADE_ASSISTANT.find()

    fun commandLine(executable: File, target: File, targetFramework: String, report: File): GeneralCommandLine =
        GeneralCommandLine(executable.path, "analyze", target.path, "--non-interactive", "--targetFramework", targetFramework,
            "--report", report.path, "--serializer", "json", "--privacyMode", "Unrestricted")
            .withWorkDirectory(target.parentFile)
            .withCharset(Charsets.UTF_8)
            .withEnvironment("NO_COLOR", "1")
            // The tool ignores DOTNET_CLI_UI_LANGUAGE, and its localized console output does not survive a redirect
            // (the letters arrive as "?"); the invariant culture makes both the log and the report English.
            .withEnvironment("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", "1")
}

/** "Analyze Upgrade to Newer .NET...": what stands between a project (or a solution) and a newer target framework. */
class AnalyzeUpgradeAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = target(e) != null
    }

    private fun target(e: AnActionEvent): VirtualFile? =
        SolutionContext.buildTarget(e) ?: e.project?.let { SolutionService.getInstance(it).solutionFiles().firstOrNull() }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = target(e) ?: return
        val executable = UpgradeAssistant.findExecutable() ?: return DotNetTool.UPGRADE_ASSISTANT.offerInstallation(project, TITLE)

        val frameworks = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<String>, Exception>(
            { DotNetTemplates.loadFrameworks() }, "Looking for Installed SDKs", true, project)
        val choices = (frameworks + listOf("LTS", "STS")).toTypedArray()
        val framework = Messages.showEditableChooseDialog(
            "Target framework to analyze '${target.name}' against:", TITLE, Messages.getQuestionIcon(), choices, choices.first(), null,
        )?.trim()?.ifEmpty { null } ?: return
        analyze(project, executable, target, framework)
    }

    private fun analyze(project: Project, executable: File, target: VirtualFile, framework: String) {
        val title = "Upgrade analysis of ${target.name} for $framework"
        val report = FileUtil.createTempFile("upgrade-assistant", ".json", true)
        val command = UpgradeAssistant.commandLine(executable, File(target.path), framework, report)
        val capture = AnsiFree(BuildViewCommandOutput(project, title))
        // minutes, not seconds: the tool restores and compiles the projects before it looks at them
        DotNetCli.runInBackground(project, title, listOf(command), output = capture) {
            val parsed = report.takeIf { it.length() > 0 }?.let { UpgradeReport.parse(it.readText(), File(target.path)) }
            when {
                parsed == null -> DotNetCli.notifyError(project, title, "The tool produced no report.")
                parsed.incidents.isEmpty() -> DotNetCli.notifyInfo(project, title, "Nothing to fix: no incidents found.")
                else -> UpgradeReportDialog(project, target.name, parsed).show()
            }
        }
    }

    /** Progress bars of the tool are drawn with escape sequences even when colors are off. */
    private class AnsiFree(private val delegate: io.github.dotnetsupport.cli.CommandOutput) : io.github.dotnetsupport.cli.CommandOutput by delegate {
        override fun text(text: String, isError: Boolean) = delegate.text(text.replace(ANSI, ""), isError)
    }

    private companion object {
        const val TITLE = "Analyze Upgrade to Newer .NET"
        val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    }
}

class UpgradeReportDialog(private val project: Project, targetName: String, private val report: UpgradeReport) :
    DialogWrapper(project, false, IdeModalityType.MODELESS) {
    private val table = JBTable(object : AbstractTableModel() {
        override fun getRowCount(): Int = report.incidents.size
        override fun getColumnCount(): Int = 4
        override fun getColumnName(column: Int): String = listOf("Severity", "Issue", "Found", "Location")[column]
        override fun getValueAt(row: Int, column: Int): Any = report.incidents[row].let {
            listOf(it.severity.name.lowercase().replaceFirstChar(Char::uppercase), it.title, it.subject, location(it))[column]
        }
    })

    init {
        title = "Upgrade Analysis: $targetName"
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : AbstractAction("Open Documentation") {
            override fun actionPerformed(e: ActionEvent) {
                selected()?.link?.let(BrowserUtil::browse)
            }
        },
        okAction.apply { putValue(Action.NAME, "Close") },
    )

    override fun createCenterPanel(): JComponent {
        table.setDefaultRenderer(Any::class.java, object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
                val incident = report.incidents[table.convertRowIndexToModel(row)]
                if (column == 0) icon = when (incident.severity) {
                    UpgradeSeverity.MANDATORY -> AllIcons.General.Error
                    UpgradeSeverity.OPTIONAL, UpgradeSeverity.POTENTIAL -> AllIcons.General.Warning
                    UpgradeSeverity.INFORMATION -> AllIcons.General.Information
                }
                append(value?.toString().orEmpty(), if (column == 3 && incident.file != null) SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                toolTipText = incident.details.ifEmpty { null }
            }
        })
        listOf(90, 300, 260, 220).forEachIndexed { index, width -> table.columnModel.getColumn(index).preferredWidth = JBUI.scale(width) }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigate()
        }.installOn(table)

        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JBLabel(summary(report)), BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
            add(JBLabel("Double-click opens the place in the editor; the tooltip explains the rule.").apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(900), JBUI.scale(480))
        }
    }

    private fun selected(): UpgradeIncident? = table.selectedRow.takeIf { it >= 0 }?.let { report.incidents[table.convertRowIndexToModel(it)] }

    private fun navigate(): Boolean {
        val incident = selected() ?: return false
        val file = incident.file?.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) } ?: return false
        OpenFileDescriptor(project, file, (incident.line - 1).coerceAtLeast(0), (incident.column - 1).coerceAtLeast(0)).navigate(true)
        return true
    }

    private fun location(incident: UpgradeIncident): String {
        val file = incident.file ?: return ""
        return file.name + if (incident.line > 0) ":${incident.line}" else ""
    }

    companion object {
        fun summary(report: UpgradeReport): String {
            val severities = UpgradeSeverity.entries.mapNotNull { severity -> report.count(severity).takeIf { it > 0 }?.let { "$it ${severity.name.lowercase()}" } }
            return "<html>Target: <b>${report.target}</b> · ${report.projects} project${if (report.projects == 1) "" else "s"} · " +
                "${report.incidents.size} incidents (${severities.joinToString(", ")}) · estimated effort: ${report.effort} story points</html>"
        }
    }
}
