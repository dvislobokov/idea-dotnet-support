package io.github.dotnetsupport.frameworks.unity

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.dotnetsupport.view.SolutionNode
import io.github.dotnetsupport.view.SolutionTreeStructure
import io.github.dotnetsupport.view.SolutionViewPane

/** The Solution view of a Unity project (UNITY_PLAN.md): without the editor's caches and `.meta` files, as Rider's Unity Explorer shows it. */
object UnityViewSettings {
    private const val SHOW_META_FILES = "dotnet.unity.view.showMetaFiles"

    fun isShowMetaFiles(project: Project): Boolean = PropertiesComponent.getInstance(project).getBoolean(SHOW_META_FILES)

    fun setShowMetaFiles(project: Project, value: Boolean) {
        PropertiesComponent.getInstance(project).setValue(SHOW_META_FILES, value)
        ProjectView.getInstance(project).getProjectViewPaneById(SolutionViewPane.ID)?.updateFromRoot(true)
    }
}

/** `Library`, `Temp`, `Logs`, `obj`, `UserSettings` of a Unity project never show; `.meta` files only when asked for. */
class UnityTreeStructureProvider : TreeStructureProvider {
    override fun modify(parent: AbstractTreeNode<*>, children: MutableCollection<AbstractTreeNode<*>>, settings: ViewSettings?): MutableCollection<AbstractTreeNode<*>> {
        if (settings !is SolutionTreeStructure) return children
        val project = parent.project ?: return children
        val showMeta = UnityViewSettings.isShowMetaFiles(project)
        var touched = false
        val kept = ArrayList<AbstractTreeNode<*>>(children.size)
        for (child in children) {
            val file = (child as? ProjectViewNode<*>)?.virtualFile
            val hidden = file != null && (UnityProjects.isGeneratedDirectory(file) || !showMeta && UnityProjects.isMetaFile(file) && UnityProjects.rootOf(file) != null)
            if (hidden) touched = true else kept += child
        }
        return if (touched) kept else children
    }
}

/** The solution of a Unity project: the Unity icon and the editor's version beside the count of projects. */
class UnitySolutionDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        if (node !is SolutionNode) return
        val root = node.virtualFile?.parent?.takeIf(UnityProjects::isUnityRoot) ?: return
        data.setIcon(UnityIcons.Unity)
        val version = UnityProjects.editorVersion(root)
        data.locationString = listOfNotNull(version?.let { "Unity $it" }, data.locationString).joinToString(" · ")
    }
}

/** View options of the Project tool window: the `.meta` files of a Unity project, off by default. */
class ShowMetaFilesAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && ProjectView.getInstance(project).currentViewId == SolutionViewPane.ID &&
            io.github.dotnetsupport.solution.SolutionService.getInstance(project).allSolutionFiles().any { it.parent?.let(UnityProjects::isUnityRoot) == true }
    }

    override fun isSelected(e: AnActionEvent): Boolean = e.project?.let(UnityViewSettings::isShowMetaFiles) == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.project?.let { UnityViewSettings.setShowMetaFiles(it, state) }
    }
}
