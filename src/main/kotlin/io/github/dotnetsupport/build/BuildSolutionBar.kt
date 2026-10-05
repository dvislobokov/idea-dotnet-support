package io.github.dotnetsupport.build

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.impl.AbstractToolbarCombo
import com.intellij.openapi.wm.impl.SplitButtonAction
import io.github.dotnetsupport.msbuild.TargetFrameworks
import io.github.dotnetsupport.solution.SolutionService
import javax.swing.JComponent

/**
 * The Build Solution button of the main toolbar, as Rider's `BuildSolutionBar`: the hammer builds the solution, the arrow next to it
 * opens Build / Rebuild / Clean / Restore / Cancel Build and the choice of the configuration and the target framework. Rider keeps the
 * configuration in this widget and has no combo box of its own for it; the button stands right before the Run widget, where Rider has it.
 */
class BuildSolutionBar : SplitButtonAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val solution = project?.let(::solution)
        e.presentation.isEnabledAndVisible = solution != null
        if (project == null || solution == null) return
        e.presentation.icon = AllIcons.Actions.Compile
        e.presentation.text = "Build Solution"
        e.presentation.description = "Build ${solution.name} (${selection(project)})"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DotNetBuildService.getInstance(project).run(solution(project) ?: return, DotNetBuildCommand.BUILD)
    }

    override fun createPopup(event: AnActionEvent): JBPopup? {
        val group = ActionManager.getInstance().getAction(POPUP_GROUP) as? ActionGroup ?: return null
        return JBPopupFactory.getInstance().createActionGroupPopup(null, group, event.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
    }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        super.updateCustomComponent(component, presentation)
        // the hammer alone, as in Rider: what it builds and with which configuration is in the tooltip and in the menu of the arrow
        (component as? AbstractToolbarCombo)?.text = ""
        component.toolTipText = presentation.description
    }

    companion object {
        /** The menu of the arrow; also a place other parts of the plugin can add to. */
        const val POPUP_GROUP = "DotNet.BuildSolutionBar.Popup"

        fun solution(project: Project): VirtualFile? = SolutionService.getInstance(project).solutionFiles().firstOrNull()

        /** `Debug`, `Release | .NET 8.0`: what a build of the button uses. */
        fun selection(project: Project): String {
            val settings = DotNetBuildSettings.getInstance(project)
            return listOfNotNull(settings.configuration, settings.framework?.let(TargetFrameworks::displayName)).joinToString(" | ")
        }
    }
}

/** Cancel Build (Rider: `CancelBuildAction`): stops the builds started by the plugin. */
class CancelBuildAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && BuildSolutionBar.solution(project) != null
        e.presentation.isEnabled = project != null && DotNetBuildService.getInstance(project).isBuilding
    }

    override fun actionPerformed(e: AnActionEvent) {
        DotNetBuildService.getInstance(e.project ?: return).cancel()
    }
}

/**
 * The configurations of the solution and, for multi-targeted projects, the target frameworks, as checked items of the menu of the
 * Build Solution button. The choice is [DotNetBuildSettings]: build, run and tests use it.
 */
class BuildConfigurationChoices : ActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        val settings = DotNetBuildSettings.getInstance(project)
        val children = ArrayList<AnAction>()
        children += Separator.create("Configuration")
        settings.availableConfigurations().forEach { name -> children += choice(name, { settings.configuration == name }) { settings.configuration = name } }
        val frameworks = settings.availableFrameworks()
        if (frameworks.isNotEmpty()) {
            children += Separator.create("Target Framework")
            children += choice("Default", { settings.framework == null }) { settings.framework = null }
            frameworks.forEach { tfm -> children += choice(TargetFrameworks.displayName(tfm), { settings.framework == tfm }) { settings.framework = tfm } }
        }
        return children.toTypedArray()
    }

    private fun choice(text: String, isSelected: () -> Boolean, select: () -> Unit): AnAction = object : ToggleAction(text), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent): Boolean = isSelected()
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) select()
        }
    }
}
