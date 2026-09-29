package io.github.dotnetsupport.actions

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import com.intellij.openapi.wm.StatusBar
import com.intellij.util.messages.Topic
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.run.DotNetRunConfigurationGenerator
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.SolutionViewPane

/** A solution or a project has been read anew from the disk; the language server of the other module reloads it as well. */
interface SolutionReloadListener {
    /** [projectFile] is null when the whole solution is reloaded. */
    fun reloaded(projectFile: VirtualFile?)

    companion object {
        @JvmField val TOPIC: Topic<SolutionReloadListener> = Topic.create("DotNet solution reloaded", SolutionReloadListener::class.java)
    }
}

/**
 * Reload Solution / Reload Project of Rider: what is on the disk is read again, whatever the IDE has thought of it so far. Files that
 * came from outside (a branch switched in a terminal, a generator, another editor) while the window of the IDE stayed active are not
 * seen until the IDE looks at the disk, and it looks when it gets the focus back; the project files are cached by their time stamps,
 * which a tool may keep. The reload reads the folder again, forgets what was parsed, redraws the Solution view and tells the server.
 */
object SolutionReload {
    fun reload(project: Project, projectFile: VirtualFile? = null, done: () -> Unit = {}) {
        val root = projectFile?.parent ?: project.guessProjectDir() ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        val finish = Runnable {
            if (project.isDisposed) return@Runnable
            SolutionService.getInstance(project).reload(projectFile)
            DotNetRunConfigurationGenerator.getInstance(project).schedule()
            ProjectView.getInstance(project).getProjectViewPaneById(SolutionViewPane.ID)?.updateFromRoot(true)
            project.messageBus.syncPublisher(SolutionReloadListener.TOPIC).reloaded(projectFile)
            StatusBar.Info.set(if (projectFile == null) "Solution reloaded" else "${projectFile.nameWithoutExtension} reloaded", project)
            done()
        }
        VfsUtil.markDirty(true, true, root)
        // a test waits for nothing: the files are in memory
        if (ApplicationManager.getApplication().isUnitTestMode) {
            root.refresh(false, true)
            finish.run()
        } else {
            RefreshQueue.getInstance().refresh(true, true, { ApplicationManager.getApplication().invokeLater(finish, ModalityState.nonModal()) }, root)
        }
    }
}

class ReloadSolutionAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val available = project != null && SolutionService.getInstance(project).allSolutionFiles().isNotEmpty()
        // the button of the title of the Project tool window is for the Solution view; in a menu the action stays, disabled
        val inToolbar = e.place.contains("Toolbar", ignoreCase = true) || e.place.contains("TITLE", ignoreCase = true)
        if (inToolbar) e.presentation.isEnabledAndVisible = available && ProjectView.getInstance(project!!).currentViewId == SolutionViewPane.ID
        else if (e.isFromContextMenu) e.presentation.isEnabledAndVisible = available && SolutionContext.fromSelection(e)?.let { it.project == null && it.folderId == null } == true
        else e.presentation.isEnabled = available
    }

    override fun actionPerformed(e: AnActionEvent) {
        SolutionReload.reload(e.project ?: return)
    }
}

class ReloadProjectAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val projectFile = projectFile(e)
        e.presentation.text = if (projectFile != null && !e.isFromContextMenu) "Reload Project '${projectFile.nameWithoutExtension}'" else "Reload Project"
        if (e.isFromContextMenu) e.presentation.isEnabledAndVisible = projectFile != null else e.presentation.isEnabled = projectFile != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        SolutionReload.reload(e.project ?: return, projectFile(e) ?: return)
    }

    companion object {
        /** The project of the selected node, else the one the file of the editor belongs to. */
        fun projectFile(e: AnActionEvent): VirtualFile? {
            SolutionContext.fromSelection(e)?.let { return it.projectFile }
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
            return if (DotNetProjects.isProjectFile(file)) file else DotNetProjects.findOwningProject(file)
        }
    }
}
