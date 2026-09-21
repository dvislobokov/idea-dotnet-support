package io.github.dotnetsupport.ef

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.tree.TreeUtil
import io.github.dotnetsupport.DotNetIcons
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class EfToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EfMigrationsPanel(project, toolWindow)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false))
    }

    /** No stripe button without EF in the solution; .NET | EF Core | Show Migrations brings the window up when EF comes later. */
    override suspend fun isApplicableAsync(project: Project): Boolean = readAction { EfProjects.migrationsProjects(project).isNotEmpty() }

    companion object {
        const val ID = "EF Core"

        fun show(project: Project) {
            val manager = ToolWindowManager.getInstance(project)
            // not registered at startup for a solution that had no EF then
            val window = manager.getToolWindow(ID) ?: manager.registerToolWindow(ID) {
                anchor = ToolWindowAnchor.BOTTOM
                icon = AllIcons.Nodes.DataTables
                contentFactory = EfToolWindowFactory()
            }
            window.activate(null)
        }
    }
}

class ShowEfToolWindowAction : AnAction("Show Migrations"), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        EfToolWindowFactory.show(e.project ?: return)
    }
}

/** A migrations project of the window with what its sources say; the database side comes from [EfMigrationsService]. */
class EfProjectNode(val file: VirtualFile, val contexts: List<EfContextNode>)

class EfContextNode(val projectFile: VirtualFile, /** Empty: migrations that name no context. */ val name: String, val files: List<EfMigrationFile>) {
    val key: EfMigrationsService.Key get() = EfMigrationsService.Key(projectFile.path, name)
}

class EfMigrationNode(val context: EfContextNode, val row: EfMigrationRow, val isNewest: Boolean)

object EfToolWindowModel {
    /** Reads the sources of every EF project: not for EDT. */
    fun load(project: Project): List<EfProjectNode> = EfProjects.migrationsProjects(project).mapNotNull { file ->
        val contexts = EfMigrationsModel.byContext(EfSources.migrations(file), EfSources.dbContexts(file)).map { (name, files) -> EfContextNode(file, name, files) }
        // a web project that only references the data project has the package, but nothing of EF in it
        if (contexts.isEmpty()) null else EfProjectNode(file, contexts)
    }

    fun migrations(context: EfContextNode, status: EfDatabaseStatus?): List<EfMigrationNode> {
        val rows = EfMigrationsModel.rows(context.files, (status as? EfDatabaseStatus.Loaded)?.migrations)
        return rows.mapIndexed { index, row -> EfMigrationNode(context, row, isNewest = index == 0) }
    }

    /** `2 applied, 1 pending` of a loaded status. */
    fun summary(rows: List<EfMigrationRow>): String {
        val applied = rows.count { it.status == EfMigrationStatus.APPLIED }
        val pending = rows.count { it.status == EfMigrationStatus.PENDING }
        // the tool lists the migrations without `applied` when it cannot connect
        return if (rows.any { it.status == EfMigrationStatus.UNKNOWN }) "database is not reachable" else listOfNotNull("$applied applied", "$pending pending".takeIf { pending > 0 }).joinToString(", ")
    }
}

private class EfMigrationsPanel(private val project: Project, toolWindow: ToolWindow) : SimpleToolWindowPanel(true, true) {
    private val service = EfMigrationsService.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        emptyText.text = "No DbContext or migrations found in the projects that reference Entity Framework Core"
    }
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, toolWindow.disposable)
    private var projects: List<EfProjectNode> = emptyList()
    private var loadedOnce = false

    /** What the status of a context was asked with, for its node: the answer depends on the startup project and the environment. */
    private val options = HashMap<EfMigrationsService.Key, String>()

    init {
        tree.cellRenderer = Renderer()
        TreeSpeedSearch.installOn(tree)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = selectedMigration()?.let { open(it.row.file?.source ?: it.row.file?.designer); true } ?: false
        }.installOn(tree)

        val refresh = action("Refresh Status", "Build the startup project and ask the database which migrations are applied", AllIcons.Actions.Refresh, { projects.isNotEmpty() }) {
            service.refresh(selectedContexts().ifEmpty { projects.flatMap { it.contexts } }.map { it.key })
        }
        val add = command("Add Migration...", AllIcons.General.Add, EfCommandKind.ADD)
        val removeLast = command("Remove Last Migration...", AllIcons.General.Remove, EfCommandKind.REMOVE)
        val update = command("Update Database...", AllIcons.Actions.Execute, EfCommandKind.UPDATE)
        val script = command("Generate SQL Script...", AllIcons.Actions.MenuSaveall, EfCommandKind.SCRIPT)
        val drop = command("Drop Database...", null, EfCommandKind.DROP)
        val bundle = command("Create Migration Bundle...", null, EfCommandKind.BUNDLE)
        val scaffold = command("Scaffold DbContext from Database...", null, EfCommandKind.SCAFFOLD)

        val updateToHere = forMigration("Update Database to Here...", AllIcons.Actions.Execute, EfCommandKind.UPDATE) { EfPreset(it.context.name, target = it.row.name) }
        val scriptFromHere = forMigration("Generate SQL Script from Here...", null, EfCommandKind.SCRIPT) { EfPreset(it.context.name, from = it.row.name) }
        val scriptToHere = forMigration("Generate SQL Script to Here...", null, EfCommandKind.SCRIPT) { EfPreset(it.context.name, to = it.row.name) }
        // EF removes the last migration only
        val remove = forMigration("Remove...", AllIcons.General.Remove, EfCommandKind.REMOVE, { it.isNewest && it.row.file != null }) { EfPreset(it.context.name) }
        val openMigration = action("Open Migration", null, AllIcons.Actions.EditSource, { selectedMigration()?.row?.file?.source != null }) { open(selectedMigration()?.row?.file?.source) }
        val openDesigner = action("Open Designer File", null, null, { selectedMigration()?.row?.file != null }) { open(selectedMigration()?.row?.file?.designer) }
        val copyName = action("Copy Name", null, AllIcons.Actions.Copy, { selectedMigration() != null }) {
            selectedMigration()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.row.name)) }
        }

        val toolbarActions = DefaultActionGroup(add, removeLast, Separator.create(), update, script, Separator.create(), refresh)
        toolbar = ActionManager.getInstance().createActionToolbar("DotNetEfMigrations", toolbarActions, true).also { it.targetComponent = tree }.component
        val popup = DefaultActionGroup(
            updateToHere, scriptFromHere, scriptToHere, remove, Separator.create(), openMigration, openDesigner, copyName, Separator.create(),
            add, update, script, drop, Separator.create(), bundle, scaffold, Separator.create(), refresh,
        )
        PopupHandler.installPopupMenu(tree, popup, "DotNetEfMigrationsPopup")
        setContent(ScrollPaneFactory.createScrollPane(tree))

        val statusListener = { rebuild() }
        service.listeners += statusListener
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable) { service.listeners -= statusListener }
        // migrations come and go as files: rescan shortly after C# or project files change
        project.messageBus.connect(toolWindow.disposable).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.endsWith(".cs") || it.path.endsWith("proj") || it.path.endsWith(".slnx") || it.path.endsWith(".sln") }) scheduleReload()
            }
        })
        reload()
    }

    private fun scheduleReload() {
        alarm.cancelAllRequests()
        alarm.addRequest({ reload() }, RELOAD_DELAY_MS)
    }

    private fun reload() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = ReadAction.compute<List<EfProjectNode>, RuntimeException> { if (project.isDisposed) emptyList() else EfToolWindowModel.load(project) }
            ApplicationManager.getApplication().invokeLater({
                projects = loaded
                rebuild()
            }, ModalityState.any())
        }
    }

    /** The tree from the sources read last and the statuses of the moment; keeps what is expanded and selected. */
    private fun rebuild() {
        if (project.isDisposed) return
        val expanded = TreeUtil.collectExpandedPaths(tree).mapNotNullTo(HashSet()) { id(it.lastPathComponent) }
        val selected = tree.selectionPath?.lastPathComponent?.let(::id)
        root.removeAllChildren()
        options.clear()
        for (context in projects.flatMap { it.contexts }) {
            if (service.status(context.key) == null) continue
            val asked = service.context(context.key)
            val startup = asked.startupProject?.let { File(it).nameWithoutExtension }
            options[context.key] = listOfNotNull(startup?.let { "startup: $it" }, asked.environment?.let { "environment: $it" }).joinToString(", ")
        }
        for (owner in projects) {
            val projectNode = DefaultMutableTreeNode(owner)
            for (context in owner.contexts) {
                val contextNode = DefaultMutableTreeNode(context)
                EfToolWindowModel.migrations(context, service.status(context.key)).forEach { contextNode.add(DefaultMutableTreeNode(it)) }
                projectNode.add(contextNode)
            }
            root.add(projectNode)
        }
        model.reload()
        if (!loadedOnce && projects.isNotEmpty()) {
            loadedOnce = true
            TreeUtil.expandAll(tree)
            return
        }
        TreeUtil.treeNodeTraverser(root).forEach { node ->
            val path = TreePath((node as DefaultMutableTreeNode).path)
            val nodeId = id(node) ?: return@forEach
            if (nodeId in expanded) tree.expandPath(path)
            if (nodeId == selected) tree.selectionPath = path
        }
    }

    private fun id(node: Any?): String? = when (val item = (node as? DefaultMutableTreeNode)?.userObject) {
        is EfProjectNode -> item.file.path
        is EfContextNode -> item.projectFile.path + "|" + item.name
        is EfMigrationNode -> item.context.projectFile.path + "|" + item.context.name + "|" + item.row.id
        else -> null
    }

    private fun selectedItem(): Any? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject

    private fun selectedMigration(): EfMigrationNode? = selectedItem() as? EfMigrationNode

    private fun selectedContexts(): List<EfContextNode> = when (val item = selectedItem()) {
        is EfProjectNode -> item.contexts
        is EfContextNode -> listOf(item)
        is EfMigrationNode -> listOf(item.context)
        else -> emptyList()
    }

    private fun open(file: VirtualFile?) {
        if (file != null && file.isValid) FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** A command for what is selected: its project and, under a context, the context. */
    private fun command(text: String, icon: Icon?, kind: EfCommandKind): AnAction = action(text, null, icon, { projects.isNotEmpty() }) {
        val contexts = selectedContexts()
        val projectFile = contexts.firstOrNull()?.projectFile ?: projects.singleOrNull()?.file
        EfCommands.ask(project, kind, projectFile, EfPreset(dbContext = contexts.singleOrNull()?.name))
    }

    private fun forMigration(text: String, icon: Icon?, kind: EfCommandKind, enabled: (EfMigrationNode) -> Boolean = { true }, preset: (EfMigrationNode) -> EfPreset): AnAction =
        action(text, null, icon, { selectedMigration()?.let(enabled) == true }, hideWhenDisabled = true) {
            selectedMigration()?.let { EfCommands.ask(project, kind, it.context.projectFile, preset(it)) }
        }

    private fun action(text: String, description: String?, icon: Icon?, enabled: () -> Boolean, hideWhenDisabled: Boolean = false, perform: () -> Unit): AnAction =
        object : AnAction(text, description, icon), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
                e.presentation.isVisible = e.presentation.isEnabled || !hideWhenDisabled
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is EfProjectNode -> {
                    icon = DotNetIcons.forProjectFile(item.file.name)
                    append(item.file.nameWithoutExtension)
                }
                is EfContextNode -> {
                    icon = AllIcons.Nodes.DataTables
                    append(item.name.ifEmpty { "(default DbContext)" })
                    when (val status = service.status(item.key)) {
                        null -> append("  ${item.files.size} migrations, status is not loaded", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        EfDatabaseStatus.Loading -> append("  loading the status...", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        is EfDatabaseStatus.Failed -> append("  ${status.message}", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        is EfDatabaseStatus.Loaded -> {
                            append("  " + EfToolWindowModel.summary(EfMigrationsModel.rows(item.files, status.migrations)), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                            if (status.modelChanged == true) append("  model has changes that are not in a migration", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, com.intellij.ui.JBColor.ORANGE))
                        }
                    }
                    options[item.key]?.let { append("   $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                }
                is EfMigrationNode -> {
                    icon = when (item.row.status) {
                        EfMigrationStatus.APPLIED -> AllIcons.RunConfigurations.TestPassed
                        EfMigrationStatus.PENDING -> AllIcons.RunConfigurations.TestNotRan
                        EfMigrationStatus.UNKNOWN -> AllIcons.RunConfigurations.TestUnknown
                    }
                    append(item.row.name, if (item.row.status == EfMigrationStatus.PENDING) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    EfMigrationsModel.timestamp(item.row.id)?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    if (item.row.status == EfMigrationStatus.PENDING) append("  pending", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (item.row.file == null) append("  not in the sources", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }

    private companion object {
        const val RELOAD_DELAY_MS = 1000
    }
}
