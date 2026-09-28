package io.github.dotnetsupport.newproject

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GitNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.PlatformUtils
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.cli.DotNetCli
import java.io.File
import javax.swing.Icon

/**
 * ".NET" in the New Project dialog of IntelliJ IDEA, which lists generator wizards rather than the directory generators the other IDEs
 * show ([DotNetProjectGenerator]). The same rows: template, language, framework; the wizard adds the name, the location and Git.
 */
class DotNetNewProjectWizard : GeneratorNewProjectWizard {
    override val id: String get() = "io.github.dotnetsupport.newProject"
    override val name: String get() = ".NET"
    override val icon: Icon get() = DotNetIcons.Project
    override val description: String get() = "A .NET solution with a project from a template of the SDK: console, web API, Blazor, class library, tests..."

    // GoLand, PyCharm and WebStorm show the directory generator; both at once would be two ".NET" entries
    override fun isEnabled(): Boolean = PlatformUtils.isIntelliJ()

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context).nextStep(::NewProjectWizardBaseStep).nextStep(::GitNewProjectWizardStep).nextStep(::DotNetStep)

    class DotNetStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
        private val templatePanel = DotNetTemplatePanel()
        private val sameDirectory = JBCheckBox("Put solution and project in the same directory")

        val settings: DotNetNewProjectSettings get() = DotNetNewProjectSettings(templatePanel.settings, sameDirectory.isSelected)

        override fun setupUI(builder: Panel) {
            templatePanel.addRows(builder)
            builder.row { cell(sameDirectory) }
            if (DotNetCli.findExecutable() == null) {
                builder.row { comment("The 'dotnet' executable is not found: install the .NET SDK, or set its path in Settings | Tools | .NET.") }
            }
        }

        override fun setupProject(project: Project) {
            val base = baseData ?: return
            val directory = File(base.path, base.name).apply { mkdirs() }
            val baseDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory) ?: return
            val settings = settings
            DotNetProjectCreator.createSolutionWithProject(project, baseDir, settings.template, settings.sameDirectory)
        }
    }
}
