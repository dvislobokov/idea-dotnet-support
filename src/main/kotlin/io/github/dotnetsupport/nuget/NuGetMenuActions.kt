package io.github.dotnetsupport.nuget

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import io.github.dotnetsupport.build.DotNetBuildService
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.solution.SolutionService
import java.io.File

/** The actions of the `.NET | NuGet` menu; all of them need a solution. */
abstract class NuGetMenuAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let { SolutionService.getInstance(it).solutionFiles().isNotEmpty() } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        perform(e.project ?: return)
    }

    protected abstract fun perform(project: Project)

    /** Runs the blocking [task] under a progress and hands its result over on EDT. */
    protected fun <T> inBackground(project: Project, title: String, task: () -> T, onResult: (T) -> Unit) {
        object : Task.Backgroundable(project, title, true) {
            private var result: T? = null
            override fun run(indicator: ProgressIndicator) {
                result = task()
            }

            override fun onSuccess() {
                result?.let(onResult)
            }
        }.queue()
    }
}

/** Popup with everything of the NuGet menu, as in Rider. */
class NuGetQuickListAction : NuGetMenuAction() {
    override fun perform(project: Project) = Unit

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val menu = ActionManager.getInstance().getAction(GROUP_ID) as? ActionGroup ?: return
        val actions = DefaultActionGroup(menu.getChildren(e).filter { it !== this })
        JBPopupFactory.getInstance()
            .createActionGroupPopup("NuGet", actions, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .showCenteredInCurrentWindow(project)
    }

    private companion object {
        const val GROUP_ID = "DotNet.NuGet"
    }
}

/** `dotnet restore --force`: resolves every dependency again, even when the last restore succeeded. */
class NuGetForceRestoreAction : NuGetMenuAction() {
    override fun perform(project: Project) {
        val solution = SolutionService.getInstance(project).solutionFiles().firstOrNull() ?: return
        val arguments = arrayOf("restore", solution.path, "--force", "--no-cache", "-nologo")
        DotNetBuildService.getInstance(project).run(solution, "Force Restore ${solution.name}", arguments) { perform(project) }
    }
}

/** Opens the global packages folder (`~/.nuget/packages` unless configured otherwise) in the file manager. */
class OpenNuGetPackagesFolderAction : NuGetMenuAction() {
    override fun perform(project: Project) {
        inBackground(project, "Locating the NuGet packages folder", {
            NuGetService.getInstance(project).localFolders().firstOrNull { it.first == "global-packages" }?.second?.let(::File) ?: File("")
        }) { folder ->
            if (folder.isDirectory) RevealFileAction.openDirectory(folder)
            else DotNetCli.notifyError(project, "Open 'packages' Folder", "'dotnet nuget locals global-packages --list' did not report an existing folder.")
        }
    }
}

class ManageNuGetPackagesForSolutionAction : NuGetMenuAction() {
    override fun perform(project: Project) {
        val service = NuGetService.getInstance(project)
        service.requestedProject = null
        service.solutionRequested = true
        showNuGetTab(project, NuGetToolWindowFactory.PACKAGES) { service.requestListeners.toList().forEach { it() } }
    }
}

/** Every package of the solution that has a newer stable version goes to it, after a confirmation that lists them. */
class UpgradePackagesInSolutionAction : NuGetMenuAction() {
    override fun perform(project: Project) {
        val service = NuGetService.getInstance(project)
        inBackground(project, "Looking for newer versions of NuGet packages", { service.outdated() }) { upgrades ->
            if (upgrades.isEmpty()) {
                DotNetCli.notifyInfo(project, "Upgrade Packages in Solution", "Every package is at its latest stable version.")
                return@inBackground
            }
            val shown = upgrades.take(MAX_SHOWN).joinToString("\n") { "${it.projectName}: ${it.packageId} ${it.from} → ${it.to}" }
            val more = if (upgrades.size > MAX_SHOWN) "\n... and ${upgrades.size - MAX_SHOWN} more" else ""
            val answer = Messages.showYesNoDialog(project, "Upgrade ${upgrades.size} package reference(s)?\n\n$shown$more", "Upgrade Packages in Solution", "Upgrade", "Cancel", Messages.getQuestionIcon())
            if (answer != Messages.YES) return@inBackground
            showNuGetTab(project, NuGetToolWindowFactory.LOG)
            service.upgrade(upgrades) { service.packagesChangedListeners.toList().forEach { it() } }
        }
    }

    private companion object {
        const val MAX_SHOWN = 20
    }
}

/** "Show NuGet Tool Window" and the tabs of it. */
abstract class ShowNuGetTabAction(private val tab: String?) : NuGetMenuAction() {
    override fun perform(project: Project) = showNuGetTab(project, tab)
}

class ShowNuGetToolWindowAction : ShowNuGetTabAction(null)
class ShowNuGetPackagesAction : ShowNuGetTabAction(NuGetToolWindowFactory.PACKAGES)
class ShowNuGetSourcesAction : ShowNuGetTabAction(NuGetToolWindowFactory.SOURCES)
class ShowNuGetFoldersAction : ShowNuGetTabAction(NuGetToolWindowFactory.FOLDERS)
class ShowNuGetLogAction : ShowNuGetTabAction(NuGetToolWindowFactory.LOG)

/** NuGet is configured by `nuget.config`: opens the most specific one that applies to the solution. */
class OpenNuGetConfigAction : NuGetMenuAction() {
    override fun perform(project: Project) {
        inBackground(project, "Locating nuget.config", { NuGetService.getInstance(project).configPaths().firstOrNull().orEmpty() }) { path ->
            val file = path.takeIf { it.isNotEmpty() }?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
            if (file != null) OpenFileDescriptor(project, file).navigate(true)
            else DotNetCli.notifyError(project, "NuGet Settings", "'dotnet nuget config paths' did not report a configuration file.")
        }
    }
}

/** Activates the NuGet window on the tab named [tab] (the current one when null), then runs [then]. */
private fun showNuGetTab(project: Project, tab: String?, then: () -> Unit = {}) {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(NuGetToolWindowFactory.ID) ?: return
    toolWindow.activate {
        val contents = toolWindow.contentManager
        tab?.let(contents::findContent)?.let { contents.setSelectedContent(it) }
        then()
    }
}
