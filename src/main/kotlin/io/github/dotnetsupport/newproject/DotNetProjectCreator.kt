package io.github.dotnetsupport.newproject

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.solution.SOLUTION_EXTENSIONS
import io.github.dotnetsupport.view.SolutionViewPane
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
        run(project, "Creating .NET project '$name'", File(workDirectory), directory) {
            buildList {
                add(DotNetCli.commandLine(workDirectory, *settings.newArguments(name, directory.path).toTypedArray()))
                if (solutionFile != null) {
                    val folder = solutionFolderPath?.let { listOf("--solution-folder", it) }.orEmpty()
                    val projectFile = File(directory, "$name.${settings.projectExtension}").path
                    add(DotNetCli.commandLine(workDirectory, "sln", solutionFile.path, "add", *folder.toTypedArray(), projectFile))
                }
            }
        }
    }

    private fun run(project: Project, title: String, refresh: File, projectDirectory: File, commands: () -> List<GeneralCommandLine>) {
        val commandLines = DotNetCli.commandLinesOrNotify(project, title, commands) ?: return
        DotNetCli.runInBackground(project, title, commandLines, refresh = listOf(refresh)) {
            ProjectView.getInstance(project).apply {
                if (getProjectViewPaneById(SolutionViewPane.ID) != null) changeView(SolutionViewPane.ID)
            }
            ENTRY_POINTS.firstNotNullOfOrNull { LocalFileSystem.getInstance().findFileByIoFile(File(projectDirectory, it)) }
                ?.let { FileEditorManager.getInstance(project).openFile(it, true) }
        }
    }

    private val ENTRY_POINTS = listOf("Program.cs", "Program.fs", "Program.vb", "Class1.cs", "Library.fs", "Class1.vb")
}
