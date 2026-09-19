package io.github.dotnetsupport.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class DotNetSettingsEditor(private val project: Project) : SettingsEditor<DotNetRunConfiguration>() {
    private val projectCombo = ComboBox<String>().apply { isEditable = true }
    private val commandCombo = ComboBox(DotNetCommand.entries.toTypedArray())
    private val profileCombo = ComboBox<String>().apply { isEditable = true }
    private val arguments = RawCommandLineEditor()
    private val workingDirectory = TextFieldWithBrowseButton()
    private val environment = EnvironmentVariablesComponent()

    override fun createEditor(): JComponent {
        projectCombo.model = DefaultComboBoxModel(solutionProjectPaths().toTypedArray())
        projectCombo.addActionListener { reloadProfiles(selectedProfile()) }
        workingDirectory.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Working Directory"),
        )
        environment.labelLocation = java.awt.BorderLayout.WEST

        return panel {
            row("Project:") { cell(projectCombo).align(AlignX.FILL) }
            row("Command:") { cell(commandCombo) }
            row("Launch profile:") {
                cell(profileCombo).align(AlignX.FILL).comment("From Properties/launchSettings.json; empty for the default one")
            }
            row("Arguments:") {
                cell(arguments).align(AlignX.FILL).comment("Program arguments for run and watch, <code>dotnet test</code> options for test")
            }
            row("Working directory:") { cell(workingDirectory).align(AlignX.FILL).comment("Project directory by default") }
            row { cell(environment).align(AlignX.FILL) }
        }
    }

    override fun resetEditorFrom(configuration: DotNetRunConfiguration) {
        val options = configuration.options
        projectCombo.editor.item = options.projectPath.orEmpty()
        commandCombo.selectedItem = options.command
        reloadProfiles(options.launchProfile.orEmpty())
        arguments.text = options.programArguments.orEmpty()
        workingDirectory.text = options.workingDirectory.orEmpty()
        environment.envs = options.environment
        environment.isPassParentEnvs = options.passParentEnvironment
    }

    override fun applyEditorTo(configuration: DotNetRunConfiguration) {
        val options = configuration.options
        options.projectPath = selectedProjectPath().ifBlank { null }
        options.command = commandCombo.selectedItem as DotNetCommand
        options.launchProfile = selectedProfile().ifBlank { null }
        options.programArguments = arguments.text.ifBlank { null }
        options.workingDirectory = workingDirectory.text.ifBlank { null }
        options.environment = environment.envs.toMutableMap()
        options.passParentEnvironment = environment.isPassParentEnvs
    }

    private fun selectedProjectPath(): String = (projectCombo.editor.item as? String).orEmpty().trim()
    private fun selectedProfile(): String = (profileCombo.editor.item as? String).orEmpty().trim()

    private fun reloadProfiles(selected: String) {
        val profiles = LaunchSettings.projectProfiles(File(selectedProjectPath()))
        profileCombo.model = DefaultComboBoxModel((listOf("") + profiles).toTypedArray())
        profileCombo.editor.item = selected
    }

    private fun solutionProjectPaths(): List<String> {
        val solutions = SolutionService.getInstance(project)
        return solutions.solutionFiles()
            .flatMap { solutionFile -> solutions.solution(solutionFile).allProjects.mapNotNull { it.resolveFile(solutionFile)?.path } }
            .distinct()
    }
}
