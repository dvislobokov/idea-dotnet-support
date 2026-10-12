package io.github.dotnetsupport

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.newproject.DotNetNewProjectWizard

/** The ".NET" entry of the New Project dialog of IntelliJ IDEA. */
class NewProjectWizardTest : BasePlatformTestCase() {
    fun testWizardIsRegisteredAndBuildsItsSteps() {
        val wizards = ExtensionPointName.create<GeneratorNewProjectWizard>("com.intellij.newProjectWizard.generator").extensionList
        val wizard = wizards.filterIsInstance<DotNetNewProjectWizard>().single()
        assertEquals(".NET", wizard.name)

        val disposable = Disposer.newDisposable()
        try {
            val context = WizardContext(null, disposable)
            val step = wizard.createStep(context)
            // the chain ends with our step; building the UI must not need a dotnet on the machine
            panel { step.setupUI(this) }
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
