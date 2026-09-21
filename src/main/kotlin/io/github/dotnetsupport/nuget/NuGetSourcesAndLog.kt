package io.github.dotnetsupport.nuget

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * "Sources" tab: the feeds of every `nuget.config` level that applies to the solution. Changes go through
 * `dotnet nuget add | remove | enable | disable source`, so they land in the same config file the CLI would choose.
 */
class NuGetSourcesPanel(private val project: Project, private val onChanged: () -> Unit) : SimpleToolWindowPanel(false, true) {
    private val service = NuGetService.getInstance(project)
    private val model = SourcesModel()
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        emptyText.text = "Loading sources..."
        columnModel.getColumn(0).maxWidth = JBUI.scale(70)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(180)
        columnModel.getColumn(2).preferredWidth = JBUI.scale(520)
    }
    private val configFiles = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8, 10) }

    init {
        setContent(JPanel(BorderLayout()).apply {
            add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
            add(configFiles, BorderLayout.SOUTH)
        })
        val actions = DefaultActionGroup(
            action("New Feed...", AllIcons.General.Add, { true }) { addSource() },
            action("Edit Feed...", AllIcons.Actions.Edit, { selected() != null }) { editSource(selected()) },
            action("Remove Feed", AllIcons.General.Remove, { selected() != null }) { removeSource() },
            action("Enable / Disable", AllIcons.Actions.Checked, { selected() != null }) { toggleSource() },
            action("Refresh", AllIcons.Actions.Refresh, { true }) { reload() },
        )
        toolbar = ActionManager.getInstance().createActionToolbar("NuGetSources", actions, false).also { it.targetComponent = table }.component
        reload()
    }

    private fun selected(): NuGetSource? = table.selectedRow.takeIf { it >= 0 }?.let { model.sources[it] }

    fun reload() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val sources = service.sourceList()
            val configs = service.configPaths()
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                model.sources = sources
                model.fireTableDataChanged()
                table.emptyText.text = "No package sources"
                configFiles.removeAll()
                if (configs.isNotEmpty()) configFiles.add(JBLabel("Configuration files, the most specific first:"))
                for (path in configs) configFiles.add(ActionLink(path) {
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(path)?.let { OpenFileDescriptor(project, it).navigate(true) }
                })
                configFiles.revalidate(); configFiles.repaint()
            }, ModalityState.any())
        }
    }

    private fun changed() {
        reload()
        onChanged()
    }

    private fun addSource() = editSource(null)

    /** The flags of an existing source are read from its config file first, off the EDT. */
    private fun editSource(existing: NuGetSource?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val flags = existing?.let { service.sourceFlags(it.name) } ?: (false to false)
            ApplicationManager.getApplication().invokeLater({
                val otherNames = model.sources.filter { it !== existing }.map { it.name.lowercase() }.toSet()
                val dialog = NuGetSourceDialog(project, existing, otherNames, flags)
                if (dialog.showAndGet()) service.saveSource(dialog.settings, existing, ::changed)
            }, ModalityState.any())
        }
    }

    private fun removeSource() {
        val source = selected() ?: return
        val answer = Messages.showYesNoDialog(project, "Remove the feed '${source.name}'?", "Remove Feed", Messages.getQuestionIcon())
        if (answer == Messages.YES) service.changeSources("Removing NuGet feed ${source.name}", listOf(listOf("remove", "source", source.name)), ::changed)
    }

    private fun toggleSource() {
        val source = selected() ?: return
        val command = if (source.isEnabled) "disable" else "enable"
        service.changeSources("${command.replaceFirstChar(Char::uppercase)} NuGet feed ${source.name}", listOf(listOf(command, "source", source.name)), ::changed)
    }

    private fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean, perform: () -> Unit): AnAction =
        object : AnAction(text, null, icon), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private class SourcesModel : AbstractTableModel() {
        var sources: List<NuGetSource> = emptyList()

        override fun getRowCount(): Int = sources.size
        override fun getColumnCount(): Int = 3
        override fun getColumnName(column: Int): String = listOf("Enabled", "Name", "URL")[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) java.lang.Boolean::class.java else String::class.java
        override fun getValueAt(row: Int, column: Int): Any = sources[row].let { listOf(it.isEnabled, it.name, it.url)[column] }
    }
}

/** "Folders" tab: where NuGet keeps the packages and its caches (`dotnet nuget locals all --list`), with the way to open and to clear them. */
class NuGetFoldersPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {
    private val service = NuGetService.getInstance(project)
    private val model = FoldersModel()
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        emptyText.text = "Loading folders..."
        columnModel.getColumn(0).preferredWidth = JBUI.scale(160)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(620)
    }

    init {
        setContent(ScrollPaneFactory.createScrollPane(table))
        val actions = DefaultActionGroup(
            action("Open in File Manager", AllIcons.Actions.MenuOpen, { selected() != null }) { selected()?.let { RevealFileAction.openDirectory(java.io.File(it.second)) } },
            action("Clear", AllIcons.Actions.GC, { selected() != null }) { clear() },
            action("Refresh", AllIcons.Actions.Refresh, { true }) { reload() },
        )
        toolbar = ActionManager.getInstance().createActionToolbar("NuGetFolders", actions, false).also { it.targetComponent = table }.component
        reload()
    }

    private fun selected(): Pair<String, String>? = table.selectedRow.takeIf { it >= 0 }?.let { model.folders[it] }

    private fun reload() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val folders = service.localFolders()
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                model.folders = folders
                model.fireTableDataChanged()
                table.emptyText.text = "No folders reported by 'dotnet nuget locals'"
            }, ModalityState.any())
        }
    }

    private fun clear() {
        val (name, path) = selected() ?: return
        val answer = Messages.showYesNoDialog(project, "Delete everything in '$name'?\n$path", "Clear NuGet Folder", Messages.getWarningIcon())
        if (answer == Messages.YES) service.clearLocalFolder(name, ::reload)
    }

    private fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean, perform: () -> Unit): AnAction =
        object : AnAction(text, null, icon), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private class FoldersModel : AbstractTableModel() {
        var folders: List<Pair<String, String>> = emptyList()

        override fun getRowCount(): Int = folders.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = listOf("Folder", "Path")[column]
        override fun getValueAt(row: Int, column: Int): Any = folders[row].let { if (column == 0) it.first else it.second }
    }
}

/** "Log" tab: every `dotnet` command the NuGet window has run, with its output. */
class NuGetLogPanel(project: Project, parentDisposable: Disposable) : SimpleToolWindowPanel(false, true) {
    init {
        val log = NuGetService.getInstance(project).log
        val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        Disposer.register(parentDisposable, console)
        setContent(console.component)

        val clear = object : AnAction("Clear", null, AllIcons.Actions.GC), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) {
                log.clear()
                console.clear()
            }
        }
        toolbar = ActionManager.getInstance().createActionToolbar("NuGetLog", DefaultActionGroup(clear), false).also { it.targetComponent = console.component }.component
        log.subscribe { text, isError -> console.print(text, if (isError) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT) }
    }
}
