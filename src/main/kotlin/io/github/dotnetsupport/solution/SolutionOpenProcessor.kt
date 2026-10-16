package io.github.dotnetsupport.solution

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.PlatformProjectOpenProcessor
import com.intellij.projectImport.ProjectOpenProcessor
import io.github.dotnetsupport.DotNetIcons
import javax.swing.Icon

/**
 * Open a `.sln` / `.slnx` / `.slnf` as the project (File | Open, the welcome screen, a file dropped on the window), as Rider does: the
 * folder of the solution becomes the project of the IDE and that solution its active one ([ActiveSolution]), whatever else lies in the
 * folder. A folder is still opened as a folder: the choice among several solutions is then [ActiveSolutionActivity]'s.
 */
class SolutionOpenProcessor : ProjectOpenProcessor() {
    override val name: String get() = "C# solution"
    override val icon: Icon get() = DotNetIcons.Solution

    override fun canOpenProject(file: VirtualFile): Boolean = isSolutionOrFilterFile(file)

    // a folder with a solution inside is opened as a folder by the platform; this processor takes solution files only
    override fun lookForProjectsInDirectory(): Boolean = false

    // the external-system autolink would list every solution of the folder as "C# solution build scripts found" (seen live, WSL
    // 2026-10-16): the plugin's own chooser does that; nothing to import afterwards
    override fun canImportProjectAfterwards(): Boolean = false

    override suspend fun openProjectAsync(virtualFile: VirtualFile, projectToClose: Project?, forceOpenInNewFrame: Boolean): Project? {
        val directory = virtualFile.parent ?: return null
        val options = PlatformProjectOpenProcessor.createOptionsToOpenDotIdeaOrCreateNewIfNotExists(directory.toNioPath(), projectToClose).copy(
            forceOpenInNewFrame = forceOpenInNewFrame,
            // the choice is made before anything of the project runs: the Solution view and the activities find it in place
            beforeOpen = { project -> ActiveSolution.getInstance(project).choose(virtualFile, reload = false); true },
        )
        return ProjectManagerEx.getInstanceEx().openProjectAsync(directory.toNioPath(), options)
    }
}

/** A folder with several solutions and none chosen: the chooser on start, as Rider asks which solution to open. */
class ActiveSolutionActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val solutions = SolutionService.getInstance(project)
        val found = solutions.foundSolutionFiles()   // walks the folder: off EDT
        if (ActiveSolution.getInstance(project).needsChoice(found)) ActiveSolution.getInstance(project).chooseInteractively(found)
    }
}

/** Menu .NET | Select Solution…: which of the solutions of the opened folder the plugin works in. */
class SelectSolutionAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // one solution leaves nothing to choose; the walk of the folder is cached by the service
        e.presentation.isEnabled = (e.project?.let { SolutionService.getInstance(it).foundSolutionFiles().size } ?: 0) > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ActiveSolution.getInstance(project).chooseInteractively()
    }
}
