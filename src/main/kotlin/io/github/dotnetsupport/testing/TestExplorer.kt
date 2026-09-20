package io.github.dotnetsupport.testing

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetConfigurationType
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** A test found in the sources, with the file it is in. */
class DiscoveredTest(val target: TestTarget, val file: VirtualFile)

class TestProject(val name: String, val projectFile: VirtualFile, val tests: List<DiscoveredTest>) {
    /** Classes in the order of their names, each with its methods in the order of the source. */
    val classes: List<Pair<DiscoveredTest, List<DiscoveredTest>>>
        get() = tests.filter { it.target.methodName == null }.sortedBy { it.target.className.lowercase() }
            .map { type -> type to tests.filter { it.target.methodName != null && it.target.className == type.target.className } }
}

object TestExplorerModel {
    private val SKIPPED_DIRECTORIES = setOf("bin", "obj", ".git", ".vs", ".idea", "node_modules")

    /** Tests of every test project of the solution, found by tokens without building anything. Blocking. */
    fun discover(project: Project): List<TestProject> {
        val solutions = SolutionService.getInstance(project)
        return solutions.solutionFiles()
            .flatMap { file -> solutions.solution(file).allProjects.mapNotNull { p -> p.resolveFile(file)?.let { p.name to it } } }
            .distinctBy { it.second }
            .filter { solutions.msBuildProject(it.second).isTestProject }
            .map { (name, projectFile) -> TestProject(name, projectFile, testsIn(projectFile.parent)) }
            .sortedBy { it.name.lowercase() }
    }

    fun testsIn(directory: VirtualFile): List<DiscoveredTest> {
        val result = ArrayList<DiscoveredTest>()
        VfsUtilCore.visitChildrenRecursively(directory, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) return file.name.lowercase() !in SKIPPED_DIRECTORIES
                if (!file.extension.equals("cs", ignoreCase = true)) return true
                val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return true
                TestDiscovery.targets(text).mapTo(result) { DiscoveredTest(it, file) }
                return true
            }
        })
        return result
    }

    /** `--filter` for a selection inside one project; null runs everything. */
    fun filter(selection: List<TestTarget>): String? = selection.map { it.filter }.distinct().joinToString("|").ifEmpty { null }
}

class TestExplorerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TestExplorerPanel(project)
        // The first tab, never closed; the test runs add their sessions after it (see DotNetTestRunner).
        val explorer = toolWindow.contentManager.factory.createContent(panel, "Explorer", false).apply { isCloseable = false }
        toolWindow.contentManager.addContent(explorer, 0)
    }
}

private class TestExplorerPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        emptyText.text = "No tests found. Test projects reference Microsoft.NET.Test.Sdk."
    }

    init {
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                    is TestProject -> {
                        icon = DotNetIcons.forProjectFile(item.projectFile.name)
                        append(item.name)
                        append("  ${item.tests.count { it.target.methodName != null }} tests", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is DiscoveredTest -> {
                        val target = item.target
                        icon = if (target.methodName == null) AllIcons.Nodes.Class else AllIcons.Nodes.Test
                        append(target.methodName ?: target.className.substringAfterLast('.').replace('+', '.'))
                        if (target.methodName == null) append("  " + target.className.substringBeforeLast('.', ""), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }
        TreeSpeedSearch.installOn(tree)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val test = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? DiscoveredTest ?: return false
                OpenFileDescriptor(project, test.file, test.target.nameRange.startOffset).navigate(true)
                return true
            }
        }.installOn(tree)

        setContent(ScrollPaneFactory.createScrollPane(tree))
        val actions = DefaultActionGroup(
            action("Run Selected Tests", AllIcons.Actions.Execute, { tree.selectionCount > 0 }) { runSelected(coverage = false) },
            action("Run Selected Tests with Coverage", AllIcons.General.RunWithCoverage, { tree.selectionCount > 0 }) { runSelected(coverage = true) },
            action("Refresh", AllIcons.Actions.Refresh, { true }) { reload() },
            action("Expand All", AllIcons.Actions.Expandall, { true }) { TreeUtil.expandAll(tree) },
            action("Collapse All", AllIcons.Actions.Collapseall, { true }) { TreeUtil.collapseAll(tree, 0) },
        )
        toolbar = ActionManager.getInstance().createActionToolbar("DotNetTestExplorer", actions, true).also { it.targetComponent = tree }.component
        reload()
    }

    private fun reload() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val projects = ReadAction.compute<List<TestProject>, RuntimeException> { if (project.isDisposed) emptyList() else TestExplorerModel.discover(project) }
            ApplicationManager.getApplication().invokeLater({
                root.removeAllChildren()
                for (testProject in projects) {
                    val projectNode = DefaultMutableTreeNode(testProject)
                    for ((type, methods) in testProject.classes) {
                        projectNode.add(DefaultMutableTreeNode(type).apply { methods.forEach { add(DefaultMutableTreeNode(it)) } })
                    }
                    root.add(projectNode)
                }
                model.reload()
                TreeUtil.expand(tree, 2)
            }, ModalityState.any())
        }
    }

    /** One run per project: `dotnet test` takes a single project, the selection inside it becomes the filter. */
    private fun runSelected(coverage: Boolean) {
        val selected = tree.selectionPaths.orEmpty().map { it.lastPathComponent as DefaultMutableTreeNode }
        val byProject = LinkedHashMap<TestProject, MutableList<TestTarget>?>()
        for (node in selected) {
            val testProject = generateSequence(node) { it.parent as? DefaultMutableTreeNode }.firstNotNullOfOrNull { it.userObject as? TestProject } ?: continue
            when (val item = node.userObject) {
                is TestProject -> byProject[testProject] = null // the whole project wins over parts of it
                is DiscoveredTest -> if (!byProject.containsKey(testProject) || byProject[testProject] != null) byProject.getOrPut(testProject) { ArrayList() }?.add(item.target)
            }
        }
        val runManager = RunManager.getInstance(project)
        for ((testProject, targets) in byProject) {
            val name = targets?.singleOrNull()?.displayName ?: testProject.name
            val settings = runManager.createConfiguration(name + if (coverage) " with Coverage" else "", DotNetConfigurationType.instance.factory)
            (settings.configuration as DotNetRunConfiguration).options.apply {
                projectPath = testProject.projectFile.path
                command = DotNetCommand.TEST
                testFilter = targets?.let(TestExplorerModel::filter)
                collectCoverage = coverage
            }
            runManager.setTemporaryConfiguration(settings)
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
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
}
