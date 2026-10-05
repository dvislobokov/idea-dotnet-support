package io.github.dotnetsupport.newproject

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.solution.SOLUTION_EXTENSIONS
import io.github.dotnetsupport.view.SolutionViewPane
import io.github.dotnetsupport.view.SolutionViewReveal
import java.io.File

object DotNetProjectCreator {
    /** New Project wizard: a solution named after [baseDir] with one project in it. */
    fun createSolutionWithProject(project: Project, baseDir: VirtualFile, settings: DotNetTemplateSettings, sameDirectory: Boolean) {
        val name = baseDir.name
        val directory = baseDir.path
        val projectDirectory = if (sameDirectory) "." else name
        val projectFile = (if (sameDirectory) "" else "$name/") + "$name.${settings.projectExtension}"
        val hasSolution = baseDir.children.any { it.extension?.lowercase() in SOLUTION_EXTENSIONS }

        run(project, "Creating .NET project '$name'", File(directory), File(directory, projectDirectory)) {
            buildList {
                if (!hasSolution) add(DotNetCli.commandLine(directory, "new", "sln", "-n", name))
                add(DotNetCli.commandLine(directory, *settings.newArguments(name, projectDirectory).toTypedArray()))
                add(DotNetCli.commandLine(directory, "sln", "add", projectFile))
            }
        }
    }

    /** A project created in [directory] and, when [solutionFile] is given, added to it (optionally into a solution folder). */
    fun addProject(
        project: Project,
        solutionFile: VirtualFile?,
        solutionFolderPath: String?,
        directory: File,
        name: String,
        settings: DotNetTemplateSettings,
    ) {
        val workDirectory = solutionFile?.parent?.path ?: directory.parent
        val projectFile = File(directory, "$name.${settings.projectExtension}")
        // the new project selected and expanded in the Solution view, as in Rider
        val reveal = { if (solutionFile != null) SolutionViewReveal.revealProject(project, solutionFile, projectFile) }
        run(project, "Creating .NET project '$name'", File(workDirectory), directory, reveal) {
            buildList {
                add(DotNetCli.commandLine(workDirectory, *settings.newArguments(name, directory.path).toTypedArray()))
                if (solutionFile != null) {
                    val folder = solutionFolderPath?.let { listOf("--solution-folder", it) }.orEmpty()
                    add(DotNetCli.commandLine(workDirectory, "sln", solutionFile.path, "add", *folder.toTypedArray(), projectFile.path))
                }
            }
        }
    }

    /**
     * New Solution dialog: the solution directory is opened as a project first (the IDE asks whether in this window or a new one, as for any
     * new project), then the commands run in it with their output in its Build window, as the New Project wizard does.
     */
    fun createSolution(current: Project?, request: NewSolution.Request) {
        val directory = request.solutionDirectory.apply { mkdirs() }
        val opened = ProjectManagerEx.getInstanceEx().openProject(directory.toPath(), OpenProjectTask {
            isNewProject = true
            projectToClose = current
            runConfigurators = true
        }) ?: return
        val title = "Creating .NET solution '${request.solutionName}'"
        run(opened, title, directory, request.projectDirectory ?: directory) {
            NewSolution.commands(request).map { command ->
                if (command.tool == "git") GeneralCommandLine(gitExecutable(), *command.arguments.toTypedArray()).withWorkDirectory(directory)
                else DotNetCli.commandLine(directory.path, *command.arguments.toTypedArray())
            }
        }
    }

    private fun gitExecutable(): String = PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("git")?.path ?: "git"

    private fun run(project: Project, title: String, refresh: File, projectDirectory: File, after: () -> Unit = {}, commands: () -> List<GeneralCommandLine>) {
        val commandLines = DotNetCli.commandLinesOrNotify(project, title, commands) ?: return
        DotNetCli.runInBackground(project, title, commandLines, refresh = listOf(refresh)) {
            ProjectView.getInstance(project).apply {
                if (getProjectViewPaneById(SolutionViewPane.ID) != null) changeView(SolutionViewPane.ID)
            }
            ENTRY_POINTS.firstNotNullOfOrNull { LocalFileSystem.getInstance().findFileByIoFile(File(projectDirectory, it)) }
                ?.let { FileEditorManager.getInstance(project).openFile(it, true) }
            after()
        }
    }

    private val ENTRY_POINTS = listOf("Program.cs", "Program.fs", "Program.vb", "Class1.cs", "Library.fs", "Class1.vb")
}
