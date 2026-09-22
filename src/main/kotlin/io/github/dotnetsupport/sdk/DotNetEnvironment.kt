package io.github.dotnetsupport.sdk

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.dotnetsupport.cli.DotNetCli
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

enum class SupportState { UP_TO_DATE, ATTENTION, OUT_OF_SUPPORT }

/** A row of `dotnet sdk check`: an SDK ([name] is null) or a shared runtime like `Microsoft.AspNetCore.App`. */
class CheckedComponent(val name: String?, val version: String, val status: String, val state: SupportState) {
    val isSdk: Boolean get() = name == null
}

object SdkCheck {
    private val COLUMNS = Regex("""\s{2,}""")

    /**
     * Two tables, each underlined with one run of dashes: `Version  Status` for SDKs and `Name  Version  Status` for
     * runtimes, so the kind of a row is the number of its columns. The status is classified by its English text:
     * the command is run with `DOTNET_CLI_UI_LANGUAGE=en`.
     * ```
     * 9.0.301       .NET 9.0 is going out of support soon.
     * Microsoft.NETCore.App             10.0.12      Up to date.
     * ```
     */
    fun parse(output: String): List<CheckedComponent> {
        val result = ArrayList<CheckedComponent>()
        var inTable = false
        for (line in output.lineSequence()) {
            when {
                line.isBlank() -> inTable = false
                line.all { it == '-' } -> inTable = true
                inTable -> {
                    val cells = line.trim().split(COLUMNS)
                    val versionIndex = if (SdkVersion.parse(cells[0]) != null) 0 else 1
                    if (versionIndex >= cells.lastIndex || SdkVersion.parse(cells[versionIndex]) == null) continue
                    val status = cells.drop(versionIndex + 1).joinToString(" ")
                    result += CheckedComponent(cells.getOrNull(versionIndex - 1), cells[versionIndex], status, classify(status))
                }
            }
        }
        return result
    }

    fun classify(status: String): SupportState = when {
        status.startsWith("Up to date", ignoreCase = true) -> SupportState.UP_TO_DATE
        // "is out of support." against "is going out of support soon."
        status.contains("is out of support", ignoreCase = true) -> SupportState.OUT_OF_SUPPORT
        else -> SupportState.ATTENTION
    }
}

/** `dotnet --info` as sections of `key: value` lines; sections without keys (the lists of SDKs and runtimes) keep their lines. */
class DotNetInfo(val text: String, private val sections: Map<String, List<String>>) {
    /** Value of `Key:` inside the section whose title starts with [section]; the titles and keys are English. */
    fun value(section: String, key: String): String? =
        sections.entries.firstOrNull { it.key.startsWith(section, ignoreCase = true) }?.value
            ?.firstOrNull { it.startsWith("$key:", ignoreCase = true) }?.substringAfter(':')?.trim()?.ifEmpty { null }

    /** The facts worth a line at the top of the page. */
    fun summary(): List<Pair<String, String>> = listOfNotNull(
        value(".NET SDK", "Version")?.let { "SDK in use" to it },
        value(".NET SDK", "MSBuild version")?.let { "MSBuild" to it },
        value("Host", "Version")?.let { host -> "Host" to listOfNotNull(host, value("Host", "Architecture")).joinToString(", ") },
        value("Runtime Environment", "RID")?.let { "Runtime identifier" to it },
        value("Runtime Environment", "Base Path")?.let { "Base path" to it },
    )

    companion object {
        fun parse(output: String): DotNetInfo {
            val sections = LinkedHashMap<String, MutableList<String>>()
            var current: MutableList<String>? = null
            for (line in output.lineSequence()) {
                if (line.isBlank()) continue
                if (!line[0].isWhitespace()) current = sections.getOrPut(line.trim().trimEnd(':')) { ArrayList() }
                else current?.add(line.trim())
            }
            return DotNetInfo(output.trim(), sections)
        }
    }
}

/** Everything the page shows; [checkError] is set when `dotnet sdk check` could not answer (it needs the network). */
class DotNetEnvironment(
    val executable: String?,
    val info: DotNetInfo?,
    val components: List<CheckedComponent>,
    val checkError: String?,
    val globalJson: GlobalJson?,
    val resolvedSdk: SdkVersion?,
) {
    companion object {
        /** Blocking: three short commands. */
        fun load(project: Project?): DotNetEnvironment {
            val executable = DotNetCli.findExecutable()
            val globalJson = project?.let { GlobalJson.find(it.guessProjectDir()) }?.second
            if (executable == null) return DotNetEnvironment(null, null, emptyList(), null, globalJson, null)

            // global.json applies to the directory the command runs in
            val directory = project?.guessProjectDir()?.path?.takeIf { java.io.File(it).isDirectory }
            fun run(vararg arguments: String) = runCatching {
                DotNetCli.execute(DotNetCli.commandLine(directory, *arguments).withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en"), 60_000)
            }.getOrNull()

            val info = run("--info")?.stdout?.takeIf { it.isNotBlank() }?.let(DotNetInfo::parse)
            val check = run("sdk", "check")
            val components = check?.stdout?.let(SdkCheck::parse).orEmpty()
            val checkError = if (components.isNotEmpty()) null
            else (check?.let { it.stderr.ifBlank { it.stdout } }?.trim()?.lines()?.lastOrNull { it.isNotBlank() } ?: "The command did not answer")
            val installed = DotNetSdks.installed().map { it.version }
            return DotNetEnvironment(executable, info, components, checkError, globalJson, globalJson?.resolve(installed) ?: if (globalJson == null) installed.maxOrNull() else null)
        }
    }
}

/** .NET | .NET on This Machine */
class DotNetEnvironmentAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = DotNetEnvironmentDialog(e.project).show()
}

class DotNetEnvironmentDialog(private val project: Project?) : DialogWrapper(project, false) {
    private val summary = JBLabel("Asking the .NET CLI...").apply { border = JBUI.Borders.emptyBottom(8) }
    private val sdkModel = ComponentsModel(listOf("SDK", "Status"))
    private val runtimeModel = ComponentsModel(listOf("Runtime", "Version", "Status"))
    private val infoText = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }

    init {
        title = ".NET on This Machine"
        init()
        refresh()
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : AbstractAction("Refresh") {
            override fun actionPerformed(e: ActionEvent) = refresh()
        },
        object : AbstractAction("Copy dotnet --info") {
            override fun actionPerformed(e: ActionEvent) = CopyPasteManager.getInstance().setContents(StringSelection(infoText.text))
        },
        object : AbstractAction("Download .NET") {
            override fun actionPerformed(e: ActionEvent) = BrowserUtil.browse("https://dotnet.microsoft.com/download")
        },
        okAction.apply { putValue(Action.NAME, "Close") },
    )

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(summary, BorderLayout.NORTH)
        add(JBTabbedPane().apply {
            addTab("SDKs", table(sdkModel, statusColumn = 1))
            addTab("Runtimes", table(runtimeModel, statusColumn = 2))
            addTab("dotnet --info", ScrollPaneFactory.createScrollPane(infoText))
        }, BorderLayout.CENTER)
        preferredSize = Dimension(JBUI.scale(760), JBUI.scale(480))
    }

    private fun table(model: ComponentsModel, statusColumn: Int): JComponent {
        val table = JBTable(model)
        table.setDefaultRenderer(Any::class.java, object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
                val component = model.rows[row]
                if (column == statusColumn) {
                    icon = when (component.state) {
                        SupportState.UP_TO_DATE -> AllIcons.General.InspectionsOK
                        SupportState.ATTENTION -> AllIcons.General.Warning
                        SupportState.OUT_OF_SUPPORT -> AllIcons.General.Error
                    }
                }
                append(value?.toString().orEmpty(), if (component.state == SupportState.OUT_OF_SUPPORT) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        })
        table.columnModel.getColumn(statusColumn).preferredWidth = JBUI.scale(420)
        return ScrollPaneFactory.createScrollPane(table)
    }

    private fun refresh() {
        summary.text = "Asking the .NET CLI..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val environment = DotNetEnvironment.load(project)
            ApplicationManager.getApplication().invokeLater({ show(environment) }, ModalityState.any())
        }
    }

    private fun show(environment: DotNetEnvironment) {
        summary.text = summaryHtml(environment)
        sdkModel.update(environment.components.filter { it.isSdk }, environment.resolvedSdk)
        runtimeModel.update(environment.components.filter { !it.isSdk }, null)
        infoText.text = environment.info?.text.orEmpty()
        infoText.caretPosition = 0
    }

    private class ComponentsModel(private val columns: List<String>) : AbstractTableModel() {
        var rows: List<CheckedComponent> = emptyList()
        private var inUse: SdkVersion? = null

        fun update(rows: List<CheckedComponent>, inUse: SdkVersion?) {
            // the newest first, as everywhere else
            this.rows = rows.sortedWith(compareBy<CheckedComponent> { it.name.orEmpty() }.thenByDescending { SdkVersion.parse(it.version) })
            this.inUse = inUse
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getValueAt(row: Int, column: Int): Any = rows[row].let { component ->
            val version = component.version + if (inUse != null && SdkVersion.parse(component.version) == inUse) "  (used by this project)" else ""
            if (component.isSdk) listOf(version, component.status)[column] else listOf(component.name.orEmpty(), version, component.status)[column]
        }
    }

    companion object {
        fun summaryHtml(environment: DotNetEnvironment): String {
            if (environment.executable == null) return "<html><b>The dotnet executable is not found.</b> Set it in Settings | Tools | .NET or install the .NET SDK.</html>"
            val rows = ArrayList<Pair<String, String>>()
            rows += "Executable" to environment.executable
            rows += environment.info?.summary().orEmpty()
            val globalJson = environment.globalJson
            rows += "global.json" to when {
                globalJson == null -> "not used by this project"
                environment.resolvedSdk != null -> "requires ${globalJson.version ?: "any version"} (rollForward: ${globalJson.rollForward}), resolves to ${environment.resolvedSdk}"
                else -> "requires ${globalJson.version ?: "any version"} (rollForward: ${globalJson.rollForward}). <b>No installed SDK satisfies it.</b>"
            }
            val outdated = environment.components.count { it.state == SupportState.OUT_OF_SUPPORT }
            val attention = environment.components.count { it.state == SupportState.ATTENTION }
            rows += "Support" to when {
                environment.checkError != null -> "dotnet sdk check failed: ${environment.checkError}"
                outdated > 0 -> "<b>$outdated out of support</b>" + if (attention > 0) ", $attention need attention" else ""
                attention > 0 -> "$attention need attention (a patch is available or the support ends soon)"
                else -> "everything is up to date"
            }
            return "<html><table cellpadding='1'>" + rows.joinToString("") { (name, value) -> "<tr><td><font color='gray'>$name:&nbsp;&nbsp;</font></td><td>$value</td></tr>" } + "</table></html>"
        }
    }
}
