package io.github.dotnetsupport.solution

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import io.github.dotnetsupport.cli.DotNetCli

/**
 * The solution the IDE project works in, as Rider opens one solution: of the solution files (and filters) under the opened folder, the one
 * chosen — by opening it as the project ([SolutionOpenProcessor]), by .NET | Select Solution, or by the chooser the folder with several
 * solutions shows on start ([ActiveSolutionActivity]). The choice is kept with the project (`workspace.xml`), relative to the folder, as
 * the language server's choice was. One solution under the folder needs no choice; of several and none chosen, [pick] takes the first
 * solution of the walk (the root's first) until the user chooses.
 */
@Service(Service.Level.PROJECT)
@State(name = "DotNetActiveSolution", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ActiveSolution(private val project: Project) : SimplePersistentStateComponent<ActiveSolution.Choice>(Choice()) {
    class Choice : BaseState() {
        /** The solution or filter file chosen, relative to the opened folder; null when none was. */
        var solution by string()
    }

    /** The file chosen, when it is still there; null when none was chosen or it is gone. */
    fun chosen(): VirtualFile? {
        val relative = state.solution ?: return null
        return project.guessProjectDir()?.findFileByRelativePath(relative)?.takeIf { it.isValid && isSolutionOrFilterFile(it) }
    }

    /** Of [found] (solutions and filters, the root's first), the active one: the chosen when among them, else the first solution, else the first filter. */
    fun pick(found: List<VirtualFile>): VirtualFile? {
        val chosen = chosen()
        if (chosen != null && chosen in found) return chosen
        return found.firstOrNull { !isSolutionFilterFile(it) } ?: found.firstOrNull()
    }

    /** Whether the user has to choose: several solutions under the folder and none chosen yet. */
    fun needsChoice(found: List<VirtualFile>): Boolean = found.count { !isSolutionFilterFile(it) } > 1 && (chosen() == null || chosen() !in found)

    /**
     * [file] becomes the active solution. [reload]: the model is reloaded and the listeners told (the Solution view, the language server);
     * false while the project opens ([SolutionOpenProcessor]), when nothing is built yet.
     */
    fun choose(file: VirtualFile, reload: Boolean = true) {
        val relative = relative(file)
        if (state.solution == relative) return
        state.solution = relative
        if (!reload || project.isDisposed) return
        SolutionService.getInstance(project).reload()
        project.messageBus.syncPublisher(CHANGED).activeSolutionChanged(file)
    }

    /** The popup of the solutions and filters of the folder; dismissed with none chosen, it leaves a notification to come back to. */
    fun chooseInteractively(found: List<VirtualFile> = SolutionService.getInstance(project).foundSolutionFiles()) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || found.isEmpty()) return@invokeLater
            val current = state.solution
            val names = found.map(::relative)
            JBPopupFactory.getInstance().createPopupChooserBuilder(names)
                .setTitle("Select Solution")
                .apply { if (current != null && current in names) setSelectedValue(current, true) }
                .setItemChosenCallback { name -> found.getOrNull(names.indexOf(name))?.let { choose(it) } }
                .addListener(object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        if (!event.isOk && chosen() == null) remindToChoose(found.count { !isSolutionFilterFile(it) })
                    }
                })
                .createPopup().showCenteredInCurrentWindow(project)
        }, ModalityState.nonModal())
    }

    private fun remindToChoose(count: Int) {
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification("Several solutions", "$count solutions are found in the opened folder; the first one is open. Choose another with .NET | Select Solution.", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Select Solution...") { chooseInteractively() })
            .notify(project)
    }

    fun relative(file: VirtualFile): String = project.guessProjectDir()?.let { FileUtil.getRelativePath(it.path, file.path, '/') } ?: file.path

    interface Listener {
        fun activeSolutionChanged(file: VirtualFile)
    }

    companion object {
        @JvmField val CHANGED: Topic<Listener> = Topic.create("dotnet.activeSolution.changed", Listener::class.java)

        fun getInstance(project: Project): ActiveSolution = project.service()
    }
}
