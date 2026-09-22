package io.github.dotnetsupport.templates

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.newproject.DotNetTemplate
import io.github.dotnetsupport.newproject.DotNetTemplates
import java.awt.Dimension
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/** Any item template installed in the SDK (`dotnet new list --type item`), including the ones added with `dotnet new install`. */
class SdkItemTemplateAction : AnAction("From SDK Template...", null, AllIcons.Nodes.Template), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.targetDirectory() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = e.targetDirectory(choose = true) ?: return
        val dialog = TemplateDialog(project, directory)
        if (!dialog.showAndGet()) return
        val template = dialog.template ?: return

        val before = directory.children.toSet()
        val title = "Creating ${template.name}"
        val commands = DotNetCli.commandLinesOrNotify(project, title) {
            val name = dialog.itemName.takeIf { it.isNotEmpty() }?.let { listOf("-n", it) }.orEmpty()
            listOf(DotNetCli.commandLine(directory.path, "new", template.shortName, *name.toTypedArray(), "-o", directory.path))
        } ?: return
        DotNetCli.runInBackground(project, title, commands, refresh = listOf(File(directory.path))) {
            openInEditor(project, directory.children.firstOrNull { it !in before && !it.isDirectory })
        }
    }

    private class TemplateDialog(project: Project, private val directory: VirtualFile) : DialogWrapper(project) {
        private val templateCombo = ComboBox<DotNetTemplate>().apply { isSwingPopup = false }
        private val nameField = JBTextField()

        val template: DotNetTemplate? get() = templateCombo.selectedItem as? DotNetTemplate
        val itemName: String get() = nameField.text.trim()

        init {
            title = "New Item from SDK Template"
            init()
            ApplicationManager.getApplication().executeOnPooledThread {
                val templates = DotNetTemplates.loadItemTemplates().sortedBy { it.name.lowercase() }
                ApplicationManager.getApplication().invokeLater({
                    templateCombo.model = DefaultComboBoxModel(templates.toTypedArray())
                    if (templates.isEmpty()) setErrorText("No item templates found: is the .NET SDK installed?")
                }, ModalityState.any())
            }
        }

        override fun getPreferredFocusedComponent(): JComponent = nameField

        override fun createCenterPanel(): JComponent = panel {
            row("Template:") { cell(templateCombo).align(AlignX.FILL) }
            row("Name:") { cell(nameField).align(AlignX.FILL).comment("Optional: some templates create a file with a well-known name") }
            row("Directory:") { label(directory.presentableUrl) }
        }.apply { preferredSize = Dimension(520, preferredSize.height) }

        override fun doValidate(): ValidationInfo? = when {
            template == null -> ValidationInfo("Select a template", templateCombo)
            itemName.any { it in "\\/:*?\"<>|" } -> ValidationInfo("The name contains characters that are not allowed in file names", nameField)
            else -> null
        }
    }
}
