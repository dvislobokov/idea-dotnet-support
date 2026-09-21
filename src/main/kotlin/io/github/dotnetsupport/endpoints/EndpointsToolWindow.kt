package io.github.dotnetsupport.endpoints

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
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
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.tree.TreeUtil
import io.github.dotnetsupport.DotNetIcons
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class EndpointsToolWindowFactory : ToolWindowFactory, DumbAware {
    /** The id of the window is internal (see plugin.xml); this is what the stripe button and the header say. */
    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = TITLE
    }

    companion object {
        const val ID = "DotNetEndpoints"
        const val TITLE = "Endpoints"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EndpointsPanel(project, toolWindow)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false))
    }
}

/** Creates the request of [endpoint] in `<Project>.http` next to the project file (or finds it there) and opens it. */
object EndpointRequests {
    fun openRequest(project: Project, owner: EndpointsOfProject, endpoint: Endpoint) {
        val directory = owner.projectFile.parent ?: return
        val fileName = owner.projectFile.nameWithoutExtension + ".http"
        val baseUrl = owner.baseUrl
        val (file, offset) = WriteCommandAction.writeCommandAction(project).withName("Generate HTTP Request").compute<Pair<VirtualFile, Int>, Exception> {
            val file = directory.findChild(fileName) ?: directory.createChildData(this, fileName)
            val document = FileDocumentManager.getInstance().getDocument(file) ?: error("Cannot edit $fileName")
            val (text, offset) = HttpRequestGenerator.append(document.text, endpoint, owner.name, baseUrl)
            if (text != document.text) {
                document.setText(text)
                FileDocumentManager.getInstance().saveDocument(document)
            }
            file to offset
        }
        OpenFileDescriptor(project, file, offset).navigate(true)
    }

    fun url(owner: EndpointsOfProject, endpoint: Endpoint): String = (owner.baseUrl ?: "http://localhost:5000") + endpoint.route
}

private class EndpointsPanel(private val project: Project, toolWindow: ToolWindow) : SimpleToolWindowPanel(true, true) {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        emptyText.text = "No endpoints found: MapGet / MapPost / ... calls and [HttpGet]-like controller actions are looked for"
    }
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, toolWindow.disposable)

    init {
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                    is EndpointsOfProject -> {
                        icon = DotNetIcons.forProjectFile(item.projectFile.name)
                        append(item.name)
                        append("  ${item.endpoints.size} endpoints", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        item.baseUrl?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    }
                    is FoundEndpoint -> {
                        val endpoint = item.endpoint
                        append(endpoint.method.padEnd(METHOD_WIDTH), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, methodColor(endpoint.method)))
                        append(" " + endpoint.route)
                        append("   " + (endpoint.handler ?: item.file.name), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }
        TreeSpeedSearch.installOn(tree)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = selected()?.let { (_, found) ->
                OpenFileDescriptor(project, found.file, found.endpoint.offset).navigate(true)
                true
            } ?: false
        }.installOn(tree)

        val actions = DefaultActionGroup(
            action("Generate HTTP Request", AllIcons.Actions.Execute, { selected() != null }) { selected()?.let { (owner, found) -> EndpointRequests.openRequest(project, owner, found.endpoint) } },
            action("Open in Browser", AllIcons.General.Web, { selected()?.second?.endpoint?.let { it.method == "GET" && it.parameters.isEmpty() } == true }) {
                selected()?.let { (owner, found) -> BrowserUtil.browse(EndpointRequests.url(owner, found.endpoint)) }
            },
            action("Copy URL", AllIcons.Actions.Copy, { selected() != null }) {
                selected()?.let { (owner, found) -> CopyPasteManager.getInstance().setContents(StringSelection(EndpointRequests.url(owner, found.endpoint))) }
            },
            action("Refresh", AllIcons.Actions.Refresh, { true }) { reload() },
        )
        toolbar = ActionManager.getInstance().createActionToolbar("DotNetEndpoints", actions, true).also { it.targetComponent = tree }.component
        PopupHandler.installPopupMenu(tree, actions, "DotNetEndpointsPopup")
        setContent(ScrollPaneFactory.createScrollPane(tree))

        // sources change all the time: rescan shortly after C# files or launch settings are saved
        project.messageBus.connect(toolWindow.disposable).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.endsWith(".cs") || it.path.endsWith("/launchSettings.json") }) scheduleReload()
            }
        })
        reload()
    }

    private fun selected(): Pair<EndpointsOfProject, FoundEndpoint>? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        val found = node.userObject as? FoundEndpoint ?: return null
        val owner = (node.parent as? DefaultMutableTreeNode)?.userObject as? EndpointsOfProject ?: return null
        return owner to found
    }

    private fun scheduleReload() {
        alarm.cancelAllRequests()
        alarm.addRequest({ reload() }, RELOAD_DELAY_MS)
    }

    private fun reload() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val projects = ReadAction.computeBlocking<List<EndpointsOfProject>, RuntimeException> { if (project.isDisposed) emptyList() else EndpointsModel.discover(project) }
            ApplicationManager.getApplication().invokeLater({
                val expanded = TreeUtil.collectExpandedPaths(tree).isNotEmpty()
                root.removeAllChildren()
                for (owner in projects) root.add(DefaultMutableTreeNode(owner).apply { owner.endpoints.forEach { add(DefaultMutableTreeNode(it)) } })
                model.reload()
                if (expanded || projects.size <= 3) TreeUtil.expandAll(tree)
            }, ModalityState.any())
        }
    }

    private fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean, perform: () -> Unit): AnAction =
        object : AnAction(text, null, icon), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private companion object {
        const val RELOAD_DELAY_MS = 1000
        const val METHOD_WIDTH = 7

        /** The palette of the HTTP Client and of Swagger UI. */
        fun methodColor(method: String): Color = when (method) {
            "GET" -> JBColor(Color(0x2E8B57), Color(0x6AAB73))
            "POST" -> JBColor(Color(0xC27D00), Color(0xE0A84B))
            "PUT", "PATCH" -> JBColor(Color(0x2F6FD0), Color(0x6B9BFA))
            "DELETE" -> JBColor(Color(0xC0392B), Color(0xF75464))
            else -> JBColor.GRAY
        }
    }
}
