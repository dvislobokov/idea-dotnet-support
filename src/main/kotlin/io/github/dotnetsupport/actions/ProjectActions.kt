package io.github.dotnetsupport.actions

import com.intellij.execution.RunManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.msbuild.MsBuildItemEditor
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.solution.SolutionEditor
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import javax.swing.JComponent

/**
 * Rename Project, as in Rider: the project file, optionally its directory, the entry of the solution, the `ProjectReference`s of the other
 * projects and the run configurations follow. The assembly name and the root namespace are not touched: unless the project sets them,
 * the SDK derives them from the new file name anyway.
 */
class RenameProjectAction : SolutionAction() {
    override fun isAvailable(context: SolutionContext): Boolean = context.projectFile != null

    override fun perform(project: Project, context: SolutionContext) = renameWithDialog(project, context)

    private class RenameProjectDialog(project: Project, projectFile: VirtualFile) : DialogWrapper(project) {
        private val oldName = projectFile.nameWithoutExtension
        private val nameField = JBTextField(oldName)
        private val directoryBox = JBCheckBox("Rename the directory '${projectFile.parent.name}' as well", projectFile.parent.name.equals(oldName, ignoreCase = true))

        val newName: String get() = nameField.text.trim()
        val renameDirectory: Boolean get() = directoryBox.isSelected

        init {
            title = "Rename Project '$oldName'"
            init()
        }

        override fun getPreferredFocusedComponent(): JComponent = nameField

        override fun createCenterPanel(): JComponent = panel {
            row("New name:") { cell(nameField).align(AlignX.FILL) }
            row { cell(directoryBox) }
            row { comment("The solution, the project references of the other projects and the run configurations are updated.<br>The assembly name and the root namespace stay as the project sets them; unset, they follow the file name.") }
        }.apply { preferredSize = java.awt.Dimension(460, preferredSize.height) }

        override fun doValidate(): ValidationInfo? = when {
            newName.isEmpty() -> ValidationInfo("Specify the new name", nameField)
            newName.any { it in "\\/:*?\"<>|" } -> ValidationInfo("The name contains characters that are not allowed in file names", nameField)
            newName == oldName -> ValidationInfo("The name has not changed", nameField)
            else -> null
        }
    }

    companion object {
        /** The dialog and the rename: also what F2 on the node does. */
        fun renameWithDialog(project: Project, context: SolutionContext) {
            val projectFile = context.projectFile ?: return
            val dialog = RenameProjectDialog(project, projectFile)
            if (!dialog.showAndGet()) return
            rename(project, context, dialog.newName, dialog.renameDirectory)
        }

        /** The rename itself, in one undoable command. */
        fun rename(project: Project, context: SolutionContext, newName: String, renameDirectory: Boolean) {
            val projectFile = context.projectFile ?: return
            val slnProject = context.project ?: return
            val solutions = SolutionService.getInstance(project)
            val solutionFile = context.solutionFile
            val others = solutions.solution(solutionFile).allProjects.mapNotNull { it.resolveFile(solutionFile) }.filter { it != projectFile }
            // what the other projects call this one now, before anything moves
            val oldIncludes = others.associateWith { other -> other.parent?.let { VfsUtilCore.findRelativePath(it, projectFile, '/') } }
            val oldPath = projectFile.path
            val documents = FileDocumentManager.getInstance()

            WriteCommandAction.runWriteCommandAction(project, "Rename Project", null, {
                val directory = projectFile.parent
                if (renameDirectory && directory != null && !directory.name.equals(newName, ignoreCase = true) && directory.parent?.findChild(newName) == null) {
                    directory.rename(this, newName)
                }
                projectFile.rename(this, "$newName.${projectFile.extension}")

                val solutionDir = solutionFile.parent
                val newRelative = solutionDir?.let { VfsUtilCore.getRelativePath(projectFile, it, '/') } ?: projectFile.path
                documents.getDocument(solutionFile)?.let { document ->
                    document.setText(SolutionEditor.renameProject(document.text, solutionFile.extension, slnProject.path, newRelative, newName))
                    documents.saveDocument(document)
                }
                for ((other, oldInclude) in oldIncludes) {
                    val newInclude = other.parent?.let { VfsUtilCore.findRelativePath(it, projectFile, '/') } ?: continue
                    if (oldInclude == null || oldInclude == newInclude) continue
                    documents.getDocument(other)?.let { document ->
                        val text = MsBuildItemEditor.renameProjectReference(document.text, oldInclude, newInclude)
                        if (text != document.text) {
                            document.setText(text)
                            documents.saveDocument(document)
                        }
                    }
                }
                // the run configurations point at the file by its path
                for (settings in RunManager.getInstance(project).allSettings) {
                    val configuration = settings.configuration as? DotNetRunConfiguration ?: continue
                    if (configuration.options.projectPath == oldPath) {
                        configuration.options.projectPath = projectFile.path
                        if (settings.name == slnProject.name) settings.name = newName
                    }
                }
            })
        }
    }
}

/** Add | Assembly Reference...: `<Reference Include="Name"><HintPath>` for a dll picked on disk, the old-style reference of Rider's "Add Reference". */
class AddAssemblyReferenceAction : SolutionAction() {
    override val worksOnFilter: Boolean get() = true
    override fun isAvailable(context: SolutionContext): Boolean = context.projectFile != null

    override fun perform(project: Project, context: SolutionContext) {
        val projectFile = context.projectFile ?: return
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withFileFilter { it.extension.equals("dll", ignoreCase = true) }
            .withTitle("Add Assembly Reference")
            .withDescription("Assemblies (.dll) referenced by path; a NuGet package is the better way when there is one")
        val files = FileChooser.chooseFiles(descriptor, project, projectFile.parent).filter { it.extension.equals("dll", ignoreCase = true) }
        if (files.isEmpty()) return
        val directory = projectFile.parent
        val hintPaths = files.map { dll -> VfsUtilCore.findRelativePath(directory, dll, '/') ?: dll.path }
        val documents = FileDocumentManager.getInstance()
        WriteCommandAction.runWriteCommandAction(project, "Add Assembly Reference", null, {
            documents.getDocument(projectFile)?.let { document ->
                document.setText(MsBuildItemEditor.addAssemblyReferences(document.text, hintPaths))
                documents.saveDocument(document)
            }
        })
    }
}
