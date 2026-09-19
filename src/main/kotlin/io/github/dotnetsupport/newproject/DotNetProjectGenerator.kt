package io.github.dotnetsupport.newproject

import com.intellij.facet.ui.ValidationResult
import com.intellij.ide.util.projectWizard.SettingsStep
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGeneratorBase
import com.intellij.platform.ProjectGeneratorPeer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.cli.DotNetCli
import javax.swing.Icon
import javax.swing.JComponent

class DotNetNewProjectSettings(val template: DotNetTemplateSettings, val sameDirectory: Boolean)

/** ".NET" entry of the New Project dialog in the IDEs that use directory-based generators (GoLand, PyCharm, WebStorm, ...). */
class DotNetProjectGenerator : DirectoryProjectGeneratorBase<DotNetNewProjectSettings>() {
    override fun getName(): String = ".NET"
    override fun getLogo(): Icon = DotNetIcons.Project
    override fun createPeer(): ProjectGeneratorPeer<DotNetNewProjectSettings> = Peer()

    override fun validate(baseDirPath: String): ValidationResult =
        if (DotNetCli.findExecutable() == null) ValidationResult("The 'dotnet' executable is not found. Install the .NET SDK.")
        else ValidationResult.OK

    override fun generateProject(project: Project, baseDir: VirtualFile, settings: DotNetNewProjectSettings, module: Module) {
        DotNetProjectCreator.createSolutionWithProject(project, baseDir, settings.template, settings.sameDirectory)
    }

    private class Peer : ProjectGeneratorPeer<DotNetNewProjectSettings> {
        private val templatePanel = DotNetTemplatePanel()
        private val sameDirectory = JBCheckBox("Put solution and project in the same directory")
        private val settingsPanel: JComponent by lazy {
            panel {
                templatePanel.addRows(this)
                row { cell(sameDirectory) }
            }
        }

        override fun getComponent(myLocationField: TextFieldWithBrowseButton, checkValid: Runnable): JComponent = settingsPanel
        override fun buildUI(settingsStep: SettingsStep) = settingsStep.addSettingsComponent(settingsPanel)
        override fun getSettings(): DotNetNewProjectSettings = DotNetNewProjectSettings(templatePanel.settings, sameDirectory.isSelected)
        override fun validate(): ValidationInfo? = null
        override fun isBackgroundJobRunning(): Boolean = false
    }
}
