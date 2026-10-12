package io.github.dotnetsupport.view

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.AbstractProjectViewPane
import com.intellij.ide.projectView.impl.ProjectViewListener
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.dotnetsupport.settings.DotNetSettings
import io.github.dotnetsupport.solution.SolutionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Switches the Project tool window to the Solution pane once per project, as Rider opens a solution in its Solution view.
 *
 * The switch is not a one-shot `changeView` at startup: the Project tool window builds its content lazily (`ProjectViewImpl.setupImpl`
 * from the tool window factory, after the frame and the layout are restored), and before that `changeView` finds no content to select,
 * returns `REJECTED` and does nothing, while the platform later selects the pane saved in `workspace.xml` or its default. The old
 * activator marked the project as switched before that and never tried again, so the view stayed on Project or Project Files whenever
 * the startup activity ran first (a reopen from Recent Projects or the command line, where the frame comes up later than the activity).
 * The decision is [SolutionViewSwitch.decide]; while the view is not initialized the activator waits for its first pane
 * ([ProjectViewListener.paneShown]) and switches then.
 *
 * Only the first successful switch is automatic: after it the pane belongs to the user, a reopen keeps whatever was chosen last (the
 * platform restores it). The flag lives in the workspace of the project and is new since this version, so a project the old activator
 * marked without actually switching gets its one switch now.
 */
class SolutionViewActivator : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!DotNetSettings.getInstance().switchToSolutionView || SolutionViewSwitch.isActivated(project)) return
        if (SolutionService.getInstance(project).solutionFiles().isEmpty()) return   // walks the folder: off EDT
        withContext(Dispatchers.EDT) { SolutionViewSwitch.install(project) }
    }
}

/** The decision of [SolutionViewActivator], separated from the platform to be tested. */
object SolutionViewSwitch {
    /** Replaces `dotnet.solution.view.activated` of the activator that could mark without switching (see [SolutionViewActivator]). */
    const val ACTIVATED_KEY = "dotnet.solution.view.switched"

    enum class Decision {
        /** Select the Solution pane now (a no-op when it is current) and remember that the project has been switched. */
        SWITCH,
        /** The Project tool window has no pane yet: wait for its first one and decide again. */
        WAIT,
        /** Nothing to do: switched before, turned off, no solution, or no Solution pane in this IDE. */
        SKIP,
    }

    /** What the view looks like when the decision is made. */
    data class State(
        val switchEnabled: Boolean,
        val activatedBefore: Boolean,
        val hasSolutions: Boolean,
        /** The pane of the Solution view is registered (it is, unless the extension failed to load). */
        val paneRegistered: Boolean,
        /** `ProjectView.getCurrentViewId()`: null until the tool window content is set up and its first pane shown. */
        val currentViewId: String?,
    )

    fun decide(state: State): Decision = when {
        !state.switchEnabled || state.activatedBefore || !state.hasSolutions -> Decision.SKIP
        state.currentViewId == null -> Decision.WAIT
        !state.paneRegistered -> Decision.SKIP
        else -> Decision.SWITCH
    }

    fun isActivated(project: Project): Boolean = PropertiesComponent.getInstance(project).getBoolean(ACTIVATED_KEY)

    /** On EDT. Switches now or subscribes until the view shows its first pane; [hasSolutions] is known by the caller (found off EDT). */
    fun install(project: Project, hasSolutions: Boolean = true) {
        com.intellij.util.concurrency.ThreadingAssertions.assertEventDispatchThread()
        if (project.isDisposed || apply(project, hasSolutions) != Decision.WAIT) return
        val connection = project.messageBus.connect()
        connection.subscribe(ProjectViewListener.TOPIC, object : ProjectViewListener {
            override fun paneShown(current: AbstractProjectViewPane, previous: AbstractProjectViewPane?) {
                // fired from inside the selection of the pane: switching re-entrantly would nest two selections
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed && apply(project, hasSolutions) != Decision.WAIT) connection.disconnect()
                }, project.disposed)
            }
        })
    }

    private fun apply(project: Project, hasSolutions: Boolean): Decision {
        val projectView = ProjectView.getInstance(project)
        val state = State(
            switchEnabled = DotNetSettings.getInstance().switchToSolutionView,
            activatedBefore = isActivated(project),
            hasSolutions = hasSolutions,
            paneRegistered = projectView.getProjectViewPaneById(SolutionViewPane.ID) != null,
            currentViewId = projectView.currentViewId,
        )
        val decision = decide(state)
        if (decision == Decision.SWITCH) {
            PropertiesComponent.getInstance(project).setValue(ACTIVATED_KEY, true)
            if (state.currentViewId != SolutionViewPane.ID) projectView.changeView(SolutionViewPane.ID)
        }
        return decision
    }
}
