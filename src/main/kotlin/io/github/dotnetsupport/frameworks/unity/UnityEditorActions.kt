package io.github.dotnetsupport.frameworks.unity

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.frameworks.unity.UnityEditorLink.Status
import java.awt.Component
import java.awt.event.MouseEvent
import java.io.File

/** What the actions of the editor share: the link of the project, and why they are off when it is not connected. */
abstract class UnityEditorAction(text: String, description: String, icon: javax.swing.Icon) : AnAction(text, description, icon), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val connection = e.project?.let(UnityEditorConnection::getInstance)
        val link = connection?.link
        e.presentation.isVisible = link != null
        e.presentation.isEnabled = link?.status == Status.CONNECTED && isEnabled(link)
        if (link != null) update(e, link)
    }

    protected open fun isEnabled(link: UnityEditorLink.Link): Boolean = true
    protected open fun update(e: AnActionEvent, link: UnityEditorLink.Link) {}

    protected fun send(e: AnActionEvent, command: String) {
        val project = e.project ?: return
        if (!UnityEditorConnection.getInstance(project).send(command)) UnityEditorNotifications.notConnected(project)
    }
}

/** Play in the editor; in Play mode the same button stops it, as the Play button of the editor does. */
class UnityPlayAction : UnityEditorAction("Play in Unity", "Enter Play mode in the Unity editor", AllIcons.Actions.Execute) {
    override fun update(e: AnActionEvent, link: UnityEditorLink.Link) {
        val playing = link.state?.playing == true
        e.presentation.text = if (playing) "Stop in Unity" else "Play in Unity"
        e.presentation.description = if (playing) "Leave Play mode in the Unity editor" else "Enter Play mode in the Unity editor"
        e.presentation.icon = if (playing) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
    }

    override fun actionPerformed(e: AnActionEvent) {
        val playing = e.project?.let(UnityEditorConnection::getInstance)?.link?.state?.playing == true
        send(e, if (playing) "stop" else "play")
    }
}

class UnityPauseAction : UnityEditorAction("Pause in Unity", "Pause or resume the game in the Unity editor", AllIcons.Actions.Pause) {
    override fun isEnabled(link: UnityEditorLink.Link): Boolean = link.state?.playing == true

    override fun update(e: AnActionEvent, link: UnityEditorLink.Link) {
        e.presentation.text = if (link.state?.paused == true) "Resume in Unity" else "Pause in Unity"
        e.presentation.icon = if (link.state?.paused == true) AllIcons.Actions.Resume else AllIcons.Actions.Pause
    }

    override fun actionPerformed(e: AnActionEvent) = send(e, "pause")
}

class UnityStepAction : UnityEditorAction("Step in Unity", "Advance the paused game by one frame in the Unity editor", AllIcons.Actions.TraceOver) {
    override fun isEnabled(link: UnityEditorLink.Link): Boolean = link.state?.playing == true

    override fun actionPerformed(e: AnActionEvent) = send(e, "step")
}

class UnityRefreshAction : UnityEditorAction("Refresh Assets in Unity", "Import the changed files and compile the scripts in the Unity editor", AllIcons.Actions.Refresh) {
    override fun actionPerformed(e: AnActionEvent) = send(e, "refresh")
}

/** Puts the package of the link into the Unity project (or a new version of it over the old one), after the user agreed. */
class UnityInstallPackageAction : AnAction("Connect to Unity Editor…", "Install the package that links the Unity editor with the IDE into the project", UnityIcons.Unity), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val link = e.project?.let(UnityEditorConnection::getInstance)?.link
        e.presentation.isEnabledAndVisible = link != null && (link.status == Status.NO_PACKAGE || link.status == Status.OLD_PACKAGE ||
            link.status == Status.NO_EDITOR && UnityEditorPackage.status(link.root) != UnityEditorPackage.Status.CURRENT)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = UnityEditorConnection.getInstance(project).root()?.path?.let(::File) ?: return
        UnityEditorNotifications.install(project, root)
    }
}

/** The messages of the link: the offer to install the package, its installation, why a command did not go. */
object UnityEditorNotifications {
    private const val DECLINED_KEY = "dotnet.unity.editorPackage.declined"
    const val PACKAGE_LOCK_NOTE = "Unity adds it to Packages/packages-lock.json; the folder of the package is hidden from git by .git/info/exclude."

    /** At the opening of a Unity project: an old version of the package is replaced (the user agreed to it once), a missing one offered. */
    fun offer(project: Project, root: File) {
        when (UnityEditorPackage.status(root)) {
            UnityEditorPackage.Status.CURRENT -> Unit
            UnityEditorPackage.Status.OUTDATED -> UnityEditorPackage.install(root)
            UnityEditorPackage.Status.MISSING -> {
                if (PropertiesComponent.getInstance(project).getBoolean(DECLINED_KEY)) return
                NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
                    .createNotification(
                        "Connect to the Unity editor",
                        "Play, Pause and Stop the game from the IDE, and let Unity compile what the IDE saves without switching to it: the package " +
                            "${UnityEditorPackage.NAME} goes into Packages/ of ${root.name}. $PACKAGE_LOCK_NOTE",
                        NotificationType.INFORMATION,
                    )
                    .setIcon(UnityIcons.Unity)
                    .addAction(NotificationAction.createSimpleExpiring("Install") { install(project, root) })
                    .addAction(NotificationAction.createSimpleExpiring("Don't ask again") { PropertiesComponent.getInstance(project).setValue(DECLINED_KEY, true) })
                    .notify(project)
            }
        }
    }

    fun install(project: Project, root: File) {
        runCatching { UnityEditorPackage.install(root) }
            .onSuccess {
                PropertiesComponent.getInstance(project).unsetValue(DECLINED_KEY)
                DotNetCli.notifyInfo(project, "Unity editor package installed",
                    "Unity loads it when its window gets the focus — switch to it once. ${PACKAGE_LOCK_NOTE}")
                UnityEditorConnection.getInstance(project).update()
            }
            .onFailure { DotNetCli.notifyError(project, "Unity editor package", "The package could not be written into ${root.name}/Packages: ${it.message}") }
    }

    fun notConnected(project: Project) {
        val link = UnityEditorConnection.getInstance(project).link
        DotNetCli.notifyInfo(project, "Unity editor", UnityEditorStatusWidget.describe(link))
    }
}

/** The link is looked at from the opening of a Unity project on; a missing or old package is offered or replaced then. */
class UnityEditorStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val connection = UnityEditorConnection.getInstance(project)
        val root = connection.root()?.path?.let(::File) ?: return
        connection.start()
        UnityConsoleService.getInstance(project).start(root)
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) UnityLogToolWindowFactory.makeAvailable(project) }, project.disposed)
        UnityEditorNotifications.offer(project, root)
    }
}

/** A script or an asset the IDE saved, made, deleted or moved in a Unity project: the editor imports it ([UnityEditorConnection.refreshSoon]). */
class UnityEditorSaveListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) return
        val connection = project.getServiceIfCreated(UnityEditorConnection::class.java) ?: return
        val link = connection.link ?: return
        if (link.status != Status.CONNECTED) return
        val root = connection.root() ?: return
        val touched = events.any { event ->
            // what the IDE did; a change found by a refresh of the disk is the editor's own or another tool's
            val ours = if (event is VFileContentChangeEvent) event.isFromSave else !event.isFromRefresh
            val file = event.file ?: return@any false
            ours && VfsUtilCore.isAncestor(root, file, true) && UnityEditorLink.refreshes(VfsUtilCore.getRelativePath(file, root, '/') ?: "")
        }
        if (touched) connection.refreshSoon()
    }
}

/** The state of the editor in the status bar of a Unity project; a click lists the actions of the editor. */
class UnityEditorStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "Unity Editor"
    override fun isAvailable(project: Project): Boolean = UnityEditorConnection.getInstance(project).root() != null
    override fun createWidget(project: Project): StatusBarWidget = UnityEditorStatusWidget(project)

    companion object {
        const val ID = "DotNet.UnityEditor"
    }
}

class UnityEditorStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    override fun ID(): String = UnityEditorStatusWidgetFactory.ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) {
        UnityEditorConnection.getInstance(project).start()
    }

    override fun getText(): String = "Unity: " + short(UnityEditorConnection.getInstance(project).link)
    override fun getTooltipText(): String = describe(UnityEditorConnection.getInstance(project).link)
    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        val group = ActionManager.getInstance().getAction("Unity.EditorActions") as? ActionGroup ?: return@Consumer
        val context = DataContext { key -> if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(key)) project else null }
        JBPopupFactory.getInstance().createActionGroupPopup("Unity Editor", group, context, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true, ActionPlaces.STATUS_BAR_PLACE)
            .show(RelativePoint(event.component, java.awt.Point(0, -150)))
    }

    companion object {
        fun short(link: UnityEditorLink.Link?): String = when (link?.status) {
            null, Status.NO_EDITOR -> "closed"
            Status.NO_PACKAGE, Status.OLD_PACKAGE -> "not connected"
            Status.LOADING -> "switch to Unity"
            Status.BUSY -> "busy"
            Status.CONNECTED -> {
                val state = link.state!!
                when {
                    state.compiling -> "compiling"
                    state.playing && state.paused -> "paused"
                    state.playing -> "playing"
                    state.compileErrors > 0 -> "${state.compileErrors} compile error" + if (state.compileErrors == 1) "" else "s"
                    else -> "edit mode"
                }
            }
        }

        fun describe(link: UnityEditorLink.Link?): String = when (link?.status) {
            null, Status.NO_EDITOR -> "The Unity editor does not have the project open: .NET → Open in Unity Editor."
            Status.NO_PACKAGE -> "The project has no package of the link with the IDE: .NET → Connect to Unity Editor."
            Status.OLD_PACKAGE -> "The package of the link in the project is of another version: .NET → Connect to Unity Editor updates it."
            Status.LOADING -> "The Unity editor has not loaded the package of the link yet: it does when its window gets the focus."
            Status.BUSY -> "The Unity editor does not answer for a while: it imports, compiles or reloads the scripts."
            Status.CONNECTED -> "Unity ${link.state?.unityVersion} is linked with the IDE: Play, Pause, Step, and the scripts the IDE saves are compiled at once."
        }
    }
}
