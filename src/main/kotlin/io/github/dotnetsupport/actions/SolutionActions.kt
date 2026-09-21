package io.github.dotnetsupport.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.newproject.DotNetProjectCreator
import io.github.dotnetsupport.newproject.DotNetTemplatePanel
import io.github.dotnetsupport.solution.SolutionEditor
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.io.File
import javax.swing.JComponent

abstract class SolutionAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val context = SolutionContext.fromSelection(e)
        e.presentation.isEnabledAndVisible = context != null && isAvailable(context)
    }

    override fun actionPerformed(e: AnActionEvent) {
        perform(e.project ?: return, SolutionContext.fromSelection(e) ?: return)
    }

    protected open fun isAvailable(context: SolutionContext): Boolean = true
    protected abstract fun perform(project: Project, context: SolutionContext)

    protected fun runDotNet(project: Project, title: String, context: SolutionContext, vararg arguments: String) {
        val directory = context.solutionFile.parent.path
        val commands = DotNetCli.commandLinesOrNotify(project, title) { listOf(DotNetCli.commandLine(directory, *arguments)) } ?: return
        DotNetCli.runInBackground(project, title, commands, refresh = listOf(File(directory)))
    }
}

/** "Add" submenu of the Solution view popup; does not show up for nodes it has nothing to offer. */
class AddToSolutionGroup : DefaultActionGroup() {
    init {
        templatePresentation.isHideGroupIfEmpty = true
    }
}

/** Main menu: without a selected solution the project is created in the first solution, or just in the project directory. */
open class NewDotNetProjectAction @JvmOverloads constructor(private val requiresSelection: Boolean = false) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && (!requiresSelection || SolutionContext.fromSelection(e) != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = SolutionContext.fromSelection(e)
        val solutionFile = context?.solutionFile ?: SolutionService.getInstance(project).solutionFiles().firstOrNull()
        val baseDirectory = solutionFile?.parent?.path ?: project.guessProjectDir()?.path ?: return
        val folderPath = context?.folderId?.let { SolutionService.getInstance(project).solution(context.solutionFile).folderPath(it) }

        val dialog = NewProjectDialog(project, baseDirectory, solutionFile?.name, folderPath)
        if (!dialog.showAndGet()) return
        DotNetProjectCreator.addProject(project, solutionFile, folderPath, File(dialog.location), dialog.projectName, dialog.templateSettings)
    }

    private class NewProjectDialog(project: Project, private val baseDirectory: String, solutionName: String?, folderPath: String?) :
        DialogWrapper(project) {

        private val templatePanel = DotNetTemplatePanel()
        private val nameField = JBTextField("NewProject")
        private val locationField = TextFieldWithBrowseButton()
        private var locationEdited = false
        private val destination = listOfNotNull(solutionName, folderPath).joinToString(" / ").ifEmpty { null }

        val projectName: String get() = nameField.text.trim()
        val location: String get() = locationField.text.trim()
        val templateSettings get() = templatePanel.settings

        init {
            title = "New .NET Project"
            locationField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Project Directory"))
            locationField.text = defaultLocation()
            nameField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: javax.swing.event.DocumentEvent) {
                    if (!locationEdited) locationField.text = defaultLocation()
                }
            })
            locationField.textField.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyTyped(e: java.awt.event.KeyEvent) {
                    locationEdited = true
                }
            })
            init()
        }

        private fun defaultLocation(): String = File(baseDirectory, nameField.text.trim()).path

        override fun getPreferredFocusedComponent(): JComponent = nameField

        override fun createCenterPanel(): JComponent = panel {
            row("Name:") { cell(nameField).align(AlignX.FILL) }
            templatePanel.addRows(this)
            row("Location:") { cell(locationField).align(AlignX.FILL) }
            if (destination != null) row("Add to:") { label(destination) }
        }.apply { preferredSize = java.awt.Dimension(560, preferredSize.height) }

        override fun doValidate(): ValidationInfo? = when {
            projectName.isEmpty() -> ValidationInfo("Specify the project name", nameField)
            projectName.any { it in "\\/:*?\"<>|" } -> ValidationInfo("The name contains characters that are not allowed in file names", nameField)
            location.isEmpty() -> ValidationInfo("Specify the project directory", locationField)
            File(location).let { it.isDirectory && !it.list().isNullOrEmpty() } -> ValidationInfo("The directory is not empty", locationField)
            else -> null
        }
    }
}

/** The same dialog in the popup of a solution, a solution folder or a project. */
class AddNewProjectToSolutionAction : NewDotNetProjectAction(requiresSelection = true)

class AddExistingProjectAction : SolutionAction() {
    override fun perform(project: Project, context: SolutionContext) {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withFileFilter(DotNetProjects::isProjectFile)
            .withTitle("Add Existing Project")
        val projectFile = FileChooser.chooseFile(descriptor, project, context.solutionFile.parent) ?: return
        val folder = context.folderId
            ?.let { SolutionService.getInstance(project).solution(context.solutionFile).folderPath(it) }
            ?.let { arrayOf("--solution-folder", it) }
            ?: emptyArray()
        runDotNet(project, "Adding ${projectFile.name} to ${context.solutionFile.name}", context,
            "sln", context.solutionFile.path, "add", *folder, projectFile.path)
    }
}

class RemoveProjectFromSolutionAction : SolutionAction() {
    override fun isAvailable(context: SolutionContext): Boolean = context.project != null

    override fun perform(project: Project, context: SolutionContext) {
        val slnProject = context.project ?: return
        val answer = Messages.showYesNoDialog(
            project,
            "Remove project '${slnProject.name}' from ${context.solutionFile.name}?\nThe files stay on disk.",
            "Remove from Solution", Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        // The path as it is written in the solution: works for projects whose files are gone as well.
        runDotNet(project, "Removing ${slnProject.name} from ${context.solutionFile.name}", context,
            "sln", context.solutionFile.path, "remove", slnProject.path)
    }
}

class NewSolutionFolderAction : SolutionAction() {
    override fun isAvailable(context: SolutionContext): Boolean = context.project == null

    override fun perform(project: Project, context: SolutionContext) {
        val solutionFile = context.solutionFile
        val siblings = SolutionService.getInstance(project).solution(solutionFile)
            .let { solution -> context.folderId?.let(solution::findFolder) ?: solution.root }
            .folders.map { it.name.lowercase() }

        val name = Messages.showInputDialog(project, "Folder name:", "New Solution Folder", null, "NewFolder", object : com.intellij.openapi.ui.InputValidatorEx {
            override fun getErrorText(input: String): String? = when {
                input.isBlank() -> "Specify the folder name"
                input.any { it in "\\/:*?\"<>|" } -> "The name contains characters that are not allowed"
                input.trim().lowercase() in siblings -> "A folder with this name already exists"
                else -> null
            }
        })?.trim() ?: return

        val document = FileDocumentManager.getInstance().getDocument(solutionFile) ?: return
        WriteCommandAction.runWriteCommandAction(project, "New Solution Folder", null, {
            document.setText(SolutionEditor.addFolder(document.text, solutionFile.extension, name, context.folderId).replace("\r\n", "\n"))
            FileDocumentManager.getInstance().saveDocument(document)
        })
    }
}

class AddProjectReferenceAction : SolutionAction() {
    override fun isAvailable(context: SolutionContext): Boolean = context.projectFile != null

    override fun perform(project: Project, context: SolutionContext) {
        val projectFile = context.projectFile ?: return
        val solutions = SolutionService.getInstance(project)
        val candidates = solutions.solution(context.solutionFile).allProjects
            .mapNotNull { other -> other.resolveFile(context.solutionFile)?.let { other.name to it } }
            .filter { it.second != projectFile }
            .sortedBy { it.first.lowercase() }
        if (candidates.isEmpty()) {
            Messages.showInfoMessage(project, "There are no other projects in the solution.", "Add Project Reference")
            return
        }
        val referenced = solutions.msBuildProject(projectFile).projectReferences
            .mapNotNullTo(HashSet()) { projectFile.parent.findFileByRelativePath(it) }

        val dialog = ReferencesDialog(project, context.project?.name.orEmpty(), candidates, referenced)
        if (!dialog.showAndGet()) return

        val selected = dialog.selected
        val added = selected - referenced
        val removed = referenced.filter { it !in selected && candidates.any { candidate -> candidate.second == it } }
        val directory = projectFile.parent.path
        val title = "Updating references of ${projectFile.name}"
        val commands = DotNetCli.commandLinesOrNotify(project, title) {
          buildList {
            if (added.isNotEmpty()) add(DotNetCli.commandLine(directory, "add", projectFile.path, "reference", *added.map { it.path }.toTypedArray()))
            if (removed.isNotEmpty()) add(DotNetCli.commandLine(directory, "remove", projectFile.path, "reference", *removed.map { it.path }.toTypedArray()))
          }
        } ?: return
        if (commands.isNotEmpty()) DotNetCli.runInBackground(project, title, commands, refresh = listOf(File(projectFile.path)))
    }

    private class ReferencesDialog(
        project: Project,
        projectName: String,
        private val candidates: List<Pair<String, VirtualFile>>,
        referenced: Set<VirtualFile>,
    ) : DialogWrapper(project) {
        private val list = CheckBoxList<VirtualFile>()

        val selected: Set<VirtualFile> get() = candidates.map { it.second }.filterTo(LinkedHashSet()) { list.isItemSelected(it) }

        init {
            title = "References of '$projectName'"
            for ((name, file) in candidates) list.addItem(file, name, file in referenced)
            init()
        }

        override fun createCenterPanel(): JComponent =
            ScrollPaneFactory.createScrollPane(list).apply { preferredSize = java.awt.Dimension(360, 280) }

        override fun getPreferredFocusedComponent(): JComponent = list
    }
}
