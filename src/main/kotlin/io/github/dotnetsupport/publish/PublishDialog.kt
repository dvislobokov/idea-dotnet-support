package io.github.dotnetsupport.publish

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SolutionService
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

/** Publish of a project: a folder or a container image, optionally saved as a run configuration or a `.pubxml`. */
class PublishDialog(private val project: Project, projectFile: VirtualFile?, lockProject: Boolean) : DialogWrapper(project) {
    private var initialized = false
    private val form = PublishForm(project) { if (initialized) initValidation() }
    private val saveAsRunConfiguration = JBCheckBox("Save as run configuration", PublishSettings.getInstance(project).state.saveAsRunConfiguration)
    private val saveProfileAction = object : DialogWrapperAction("Save as Profile...") {
        override fun doAction(e: ActionEvent?) = saveProfile()
    }

    init {
        title = "Publish"
        setOKButtonText("Publish")
        val initial = projectFile ?: DotNetPublisher.publishableProjects(project).firstOrNull()
        if (initial != null) form.reset(PublishForm.defaults(project, initial.path))
        if (lockProject && projectFile != null) form.lockProject()
        init()
        initialized = true
    }

    val options: PublishOptions get() = form.options
    val saveRunConfiguration: Boolean get() = saveAsRunConfiguration.isSelected

    override fun createCenterPanel(): JComponent = panel {
        row { cell(form.component).align(AlignX.FILL) }
        row { cell(saveAsRunConfiguration).comment("A \".NET Publish\" configuration with these settings, to publish again from the Run widget") }
    }

    override fun createLeftSideActions(): Array<Action> = arrayOf(saveProfileAction)

    override fun doValidate(): ValidationInfo? = (PublishCommand.validate(options) ?: form.containerError())?.let { ValidationInfo(it) }

    override fun getDimensionServiceKey(): String = "DotNet.PublishDialog"

    /** Writes the fields as `Properties/PublishProfiles/<name>.pubxml` and chooses the profile. */
    private fun saveProfile() {
        val current = options
        if (current.projectPath.isEmpty()) return
        val name = Messages.showInputDialog(
            contentPane, "Profile name:", "Save as Profile", null, current.profile ?: "FolderProfile", null,
        )?.trim()?.removeSuffix(".${PublishProfiles.EXTENSION}")?.ifEmpty { null } ?: return
        if (name.any { it in "\\/:*?\"<>|" }) {
            Messages.showErrorDialog(contentPane, "A profile name cannot contain \\ / : * ? \" < > |", "Save as Profile")
            return
        }
        val file = PublishProfiles.file(current.projectDirectory, name)
        if (file.exists() && Messages.showYesNoDialog(contentPane, "${file.name} exists. Replace it?", "Save as Profile", null) != Messages.YES) return
        try {
            file.parentFile.mkdirs()
            file.writeText(PublishProfiles.write(current.copy(profile = name)))
        } catch (e: Exception) {
            Messages.showErrorDialog(contentPane, "Cannot write ${file.path}: ${e.message}", "Save as Profile")
            return
        }
        VfsUtil.markDirtyAndRefresh(true, false, false, file)
        form.profileSaved(name)
    }

    override fun doOKAction() {
        if (doValidate() != null) return
        PublishSettings.getInstance(project).state.saveAsRunConfiguration = saveAsRunConfiguration.isSelected
        super.doOKAction()
    }
}

/** "Publish..." of a project in the Solution view and of the .NET menu: the dialog, then `dotnet publish` in the Build tool window. */
class PublishAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val selected = project?.let { selectedProject(e, it) }
        if (e.isFromContextMenu) {
            e.presentation.isEnabledAndVisible = selected != null
        } else {
            e.presentation.isVisible = project != null
            e.presentation.isEnabled = project != null && SolutionService.getInstance(project).solutionFiles().isNotEmpty()
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = selectedProject(e, project)
        if (selected == null && DotNetPublisher.publishableProjects(project).isEmpty()) {
            DotNetCli.notifyInfo(project, "Publish", "The solution has no application to publish")
            return
        }
        val dialog = PublishDialog(project, selected, lockProject = e.isFromContextMenu && selected != null)
        if (!dialog.showAndGet()) return
        val options = dialog.options
        PublishSettings.getInstance(project).remember(options)
        if (dialog.saveRunConfiguration) DotNetPublishRunConfiguration.save(project, options)
        DotNetPublisher.publish(project, options)
    }

    /** The application selected in the Solution view, or the one the file of the editor belongs to. */
    private fun selectedProject(e: AnActionEvent, project: Project): VirtualFile? {
        val target = SolutionContext.buildTarget(e)?.takeIf { DotNetProjects.isProjectFile(it) } ?: return null
        return target.takeIf { DotNetPublisher.isPublishable(SolutionService.getInstance(project).msBuildProject(it)) }
    }
}
