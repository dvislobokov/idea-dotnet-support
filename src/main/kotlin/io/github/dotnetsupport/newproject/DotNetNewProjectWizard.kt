package io.github.dotnetsupport.newproject

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Panel
import io.github.dotnetsupport.DotNetIcons
import javax.swing.Icon

/**
 * ".NET" entry of the module-based New Project wizard (IntelliJ IDEA family, including forks such as GIGA IDE). The `directoryProjectGenerator`
 * next to it feeds only the directory-based welcome dialog of the small IDEs (GoLand, PyCharm, WebStorm); the IDEA-family wizard reads this EP,
 * so without it the category is absent there. Both point at the same [DotNetTemplatePanel] and [DotNetProjectCreator].
 */
class DotNetNewProjectWizard : GeneratorNewProjectWizard {
    override val id: String = "DotNet"
    override val name: String = ".NET"
    override val icon: Icon = DotNetIcons.Project

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context)
            .nextStep(::NewProjectWizardBaseStep)
            .nextStep(::Step)

    /** The template / language / framework rows under the shared name and location fields of the base step. */
    private class Step(parent: NewProjectWizardBaseStep) : AbstractNewProjectWizardStep(parent) {
        private val templatePanel = DotNetTemplatePanel()
        private val sameDirectory = JBCheckBox("Put solution and project in the same directory")

        override fun setupUI(builder: Panel) {
            templatePanel.addRows(builder)
            builder.row { cell(sameDirectory) }
        }

        override fun setupProject(project: Project) {
            // The base step has created the project and its directory by now; its path is the base directory the generator worked on.
            val baseDir = project.basePath?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) } ?: return
            DotNetProjectCreator.createSolutionWithProject(project, baseDir, templatePanel.settings, sameDirectory.isSelected)
        }
    }
}
