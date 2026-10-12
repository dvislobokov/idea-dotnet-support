package io.github.dotnetsupport.publish

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.sdk.SdkFeatures
import io.github.dotnetsupport.solution.SolutionService
import java.io.File
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

enum class DeploymentMode(private val title: String) {
    FRAMEWORK_DEPENDENT("Framework-Dependent"),
    SELF_CONTAINED("Self-Contained");

    override fun toString(): String = title
}

/**
 * The fields of a publish, as in "Publish to folder" of Rider: shared by the Publish dialog and the editor of a ".NET Publish" run
 * configuration. A profile chosen in it is loaded into the fields, so the command below them is what runs.
 */
class PublishForm(private val project: Project, private val onChange: () -> Unit = {}) {
    private val solutions = SolutionService.getInstance(project)
    private val projectModel = CollectionComboBoxModel(DotNetPublisher.publishableProjects(project).toMutableList())
    private val projectCombo = ComboBox(projectModel).apply { renderer = SimpleListCellRenderer.create("") { it.nameWithoutExtension } }
    private val profileCombo = ComboBox<String>().apply { renderer = SimpleListCellRenderer.create("") { it.ifEmpty { "None" } } }
    private val configurationCombo = ComboBox(CollectionComboBoxModel(configurations())).apply { isEditable = true }
    private val frameworkCombo = ComboBox<String>().apply { isEditable = true }
    private val modeCombo = ComboBox(DeploymentMode.entries.toTypedArray())
    private val runtimeCombo = ComboBox(CollectionComboBoxModel(listOf(PORTABLE) + PublishCommand.COMMON_RUNTIMES)).apply { isEditable = true }
    private val singleFile = JBCheckBox("Produce single file")
    private val readyToRun = JBCheckBox("Enable ReadyToRun compilation")
    private val trimmed = JBCheckBox("Trim unused assemblies")
    private val output = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Target Location"))
    }
    private val container = JBCheckBox("Publish as a container image (dotnet publish -t:PublishContainer)")
    private val containerRepository = JBTextField().apply { emptyText.text = "The assembly name in lower case" }
    private val containerTag = JBTextField().apply { emptyText.text = "latest" }
    // a wrapped text area asks for the width of its whole text: the command would make the dialog as wide as the screen
    private val preview = object : JBTextArea(3, 70) {
        override fun getPreferredSize() = java.awt.Dimension(JBUI.scale(560), super.getPreferredSize().height)
        override fun getMinimumSize() = java.awt.Dimension(JBUI.scale(200), super.getMinimumSize().height)
    }.apply {
        isEditable = false; lineWrap = true; wrapStyleWord = false
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
        background = UIUtil.getPanelBackground()
    }

    /** Why the selected project cannot be published as a container; checked in the background, the SDK has to be asked. */
    private var containerProblem: String? = CHECKING
    private var containerChecked: VirtualFile? = null
    private var resetting = false

    val component: JComponent = panel {
        row("Project:") { cell(projectCombo).align(AlignX.FILL) }
        row("Publish profile:") {
            cell(profileCombo).align(AlignX.FILL).comment("Properties/PublishProfiles/*.pubxml: choosing one loads it into the fields below, <code>-p:PublishProfile</code> passes the rest of it")
        }
        row("Configuration:") { cell(configurationCombo) }
        row("Target framework:") { cell(frameworkCombo) }
        row("Target runtime:") { cell(runtimeCombo).comment("Portable: no runtime identifier, runs wherever the framework is installed") }
        row("Deployment mode:") { cell(modeCombo) }
        row { cell(singleFile) }
        row { cell(readyToRun) }
        row { cell(trimmed) }
        row("Target location:") { cell(output).align(AlignX.FILL) }
        row { cell(container) }
        indent {
            row("Image name:") { cell(containerRepository).align(AlignX.FILL) }
            row("Image tag:") { cell(containerTag).align(AlignX.FILL) }
        }
        row { cell(preview).align(AlignX.FILL) }
    }

    init {
        projectCombo.addActionListener { if (!resetting) selectedProject()?.let { reset(defaults(project, it.path)) } }
        profileCombo.addActionListener {
            val name = (profileCombo.selectedItem as? String)?.ifEmpty { null }
            if (!resetting && name != null) reset(PublishProfiles.load(options, name)) else refresh()
        }
        for (combo in listOf(configurationCombo, frameworkCombo, modeCombo, runtimeCombo)) combo.addActionListener { refresh() }
        for (combo in listOf(configurationCombo, frameworkCombo, runtimeCombo)) (combo.editor.editorComponent as? JTextField)?.document?.addDocumentListener(changes())
        for (box in listOf(singleFile, readyToRun, trimmed, container)) box.addActionListener { refresh() }
        for (field in listOf(output.textField, containerRepository, containerTag)) field.document.addDocumentListener(changes())
    }

    private fun changes() = object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = refresh()
    }

    fun selectedProject(): VirtualFile? = projectCombo.selectedItem as? VirtualFile

    /** Disables the choice of the project: the dialog opened for one. */
    fun lockProject() {
        projectCombo.isEnabled = false
    }

    val options: PublishOptions
        get() {
            val runtime = comboText(runtimeCombo)
            val selfContained = runtime != null && modeCombo.selectedItem == DeploymentMode.SELF_CONTAINED
            return PublishOptions(
                projectPath = selectedProject()?.path.orEmpty(),
                configuration = comboText(configurationCombo) ?: "Release",
                framework = comboText(frameworkCombo),
                runtime = runtime,
                selfContained = selfContained,
                singleFile = runtime != null && singleFile.isSelected,
                trimmed = selfContained && trimmed.isSelected,
                readyToRun = runtime != null && readyToRun.isSelected,
                outputDir = output.text.trim().ifEmpty { null },
                profile = (profileCombo.selectedItem as? String)?.ifEmpty { null },
                container = container.isSelected,
                containerRepository = containerRepository.text.trim().ifEmpty { null },
                containerTag = containerTag.text.trim().ifEmpty { null },
            )
        }

    fun reset(options: PublishOptions) {
        resetting = true
        try {
            val file = LocalFileSystem.getInstance().findFileByPath(options.projectPath)
            if (file != null && file !in projectModel.items) projectModel.add(file)
            if (file != null) projectCombo.selectedItem = file
            if (file != null && file != containerChecked) checkContainerSupport(file)
            val frameworks = file?.let { solutions.msBuildProject(it).targetFrameworks }.orEmpty()
            frameworkCombo.model = CollectionComboBoxModel((frameworks + listOfNotNull(options.framework)).distinct())
            frameworkCombo.selectedItem = options.framework ?: frameworks.firstOrNull()
            val profiles = file?.parent?.let { PublishProfiles.list(File(it.path)) }.orEmpty()
            profileCombo.model = CollectionComboBoxModel((listOf("") + profiles + listOfNotNull(options.profile)).distinct())
            profileCombo.selectedItem = options.profile.orEmpty()
            configurationCombo.selectedItem = options.configuration
            runtimeCombo.selectedItem = options.runtime ?: PORTABLE
            modeCombo.selectedItem = if (options.selfContained) DeploymentMode.SELF_CONTAINED else DeploymentMode.FRAMEWORK_DEPENDENT
            singleFile.isSelected = options.singleFile
            readyToRun.isSelected = options.readyToRun
            trimmed.isSelected = options.trimmed
            output.text = options.outputDir.orEmpty()
            container.isSelected = options.container
            containerRepository.text = options.containerRepository.orEmpty()
            containerTag.text = options.containerTag.orEmpty()
        } finally {
            resetting = false
        }
        refresh()
    }

    /** A profile was written: list it and choose it, without loading it again. */
    fun profileSaved(name: String) {
        val profiles = selectedProject()?.parent?.let { PublishProfiles.list(File(it.path)) }.orEmpty()
        resetting = true
        try {
            profileCombo.model = CollectionComboBoxModel((listOf("") + profiles + name).distinct())
            profileCombo.selectedItem = name
        } finally {
            resetting = false
        }
        refresh()
    }

    private fun refresh() {
        if (resetting) return
        val portable = comboText(runtimeCombo) == null
        modeCombo.isEnabled = !portable
        singleFile.isEnabled = !portable
        readyToRun.isEnabled = !portable
        trimmed.isEnabled = !portable && modeCombo.selectedItem == DeploymentMode.SELF_CONTAINED
        // a saved container publish stays switchable off while the SDK is being asked
        container.isEnabled = containerProblem == null || container.isSelected
        container.toolTipText = containerProblem
        containerRepository.isEnabled = container.isEnabled && container.isSelected
        containerTag.isEnabled = containerRepository.isEnabled
        val current = options
        output.textField.let { (it as? JBTextField)?.emptyText?.text = PublishCommand.defaultOutput(current).path }
        preview.text = if (current.projectPath.isEmpty()) "" else PublishCommand.displayString(current)
        onChange()
    }

    /** Why the container switch cannot be used, once the SDK has answered; for the validation of the dialog. */
    fun containerError(): String? = containerProblem?.takeIf { it != CHECKING && container.isSelected }

    private fun checkContainerSupport(file: VirtualFile) {
        containerChecked = file
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode) {
            containerProblem = null // the SDK of the machine is not asked in tests
            return
        }
        containerProblem = CHECKING
        application.executeOnPooledThread {
            val msBuildProject = solutions.msBuildProject(file)
            val enabled = try {
                ENABLE_CONTAINERS.containsMatchIn(String(file.contentsToByteArray(), Charsets.UTF_8))
            } catch (_: Exception) {
                false
            }
            val webOrWorker = msBuildProject.isWebSdk || msBuildProject.sdk.orEmpty().startsWith("Microsoft.NET.Sdk.Worker", ignoreCase = true)
            val problem = PublishCommand.containerUnsupportedReason(SdkFeatures.sdkFor(file.parent), webOrWorker, enabled)
            application.invokeLater({
                if (selectedProject() == file) {
                    containerProblem = problem
                    refresh()
                }
            }, ModalityState.any())
        }
    }

    private fun comboText(combo: ComboBox<*>): String? =
        ((if (combo.isEditable) combo.editor.item else combo.selectedItem) as? String)?.trim()?.takeUnless { it.isEmpty() || it.equals(PORTABLE, ignoreCase = true) }

    private fun configurations(): List<String> = (listOf("Release", "Debug") + DotNetBuildSettings.getInstance(project).availableConfigurations()).distinct()

    companion object {
        /** The runtime of no `-r`, as Rider calls it. */
        private const val PORTABLE = "Portable"
        private const val CHECKING = "Checking the .NET SDK..."
        private val ENABLE_CONTAINERS = Regex("<EnableSdkContainerSupport>\\s*true\\s*</EnableSdkContainerSupport>", RegexOption.IGNORE_CASE)

        /** What the dialog opens with for a project: the last publish of it, otherwise Release of its first framework, portable. */
        fun defaults(project: Project, projectPath: String): PublishOptions {
            PublishSettings.getInstance(project).last(projectPath)?.let { return it }
            val file = LocalFileSystem.getInstance().findFileByPath(projectPath)
            val framework = file?.let { SolutionService.getInstance(project).msBuildProject(it).targetFrameworks.firstOrNull() }
            return PublishOptions(projectPath, framework = framework)
        }
    }
}
