package io.github.dotnetsupport.actions

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.msbuild.ProjectProperties
import javax.swing.JComponent

/**
 * "Properties..." of a project node: the properties of the project file a developer changes most (the Application, Build and Package
 * pages of Rider), written back into the unconditional `PropertyGroup` of the file. An empty value means "the default of the SDK".
 */
class ProjectPropertiesAction : SolutionAction() {
    override val worksOnFilter: Boolean get() = true
    override fun isAvailable(context: SolutionContext): Boolean = context.projectFile != null

    override fun perform(project: Project, context: SolutionContext) {
        val projectFile = context.projectFile ?: return
        val xml = PsiManager.getInstance(project).findFile(projectFile) as? XmlFile ?: return
        val dialog = ProjectPropertiesDialog(project, projectFile, xml)
        if (dialog.showAndGet()) dialog.apply()
    }
}

/** One editable property: the tag name, how it is shown, and what the SDK assumes when the tag is absent. */
private class Property(val name: String, val label: String, val kind: Kind, val choices: List<String> = emptyList(), val comment: String? = null) {
    enum class Kind { TEXT, BOOL, CHOICE }
}

private val APPLICATION = listOf(
    Property("AssemblyName", "Assembly name:", Property.Kind.TEXT, comment = "The name of the project by default"),
    Property("RootNamespace", "Default namespace:", Property.Kind.TEXT, comment = "Namespace of new files; the name of the project by default"),
    Property("OutputType", "Output type:", Property.Kind.CHOICE, listOf("", "Library", "Exe", "WinExe"), "Empty: what the SDK assumes (a library; web and worker SDKs make an executable)"),
    Property("LangVersion", "Language version:", Property.Kind.CHOICE, listOf("", "latest", "latestMajor", "preview", "default", "14", "13", "12", "11", "10", "9", "8", "7.3")),
    Property("Nullable", "Nullable reference types:", Property.Kind.CHOICE, listOf("", "enable", "disable", "warnings", "annotations")),
    Property("ImplicitUsings", "Implicit global usings", Property.Kind.BOOL),
    Property("StartupObject", "Startup object:", Property.Kind.TEXT, comment = "The class with Main when there are several"),
)

private val BUILD = listOf(
    Property("TreatWarningsAsErrors", "Treat warnings as errors", Property.Kind.BOOL),
    Property("WarningsAsErrors", "Warnings treated as errors:", Property.Kind.TEXT, comment = "Codes separated by ; e.g. nullable;CS8600"),
    Property("NoWarn", "Suppressed warnings:", Property.Kind.TEXT, comment = "Codes separated by ;"),
    Property("GenerateDocumentationFile", "Generate XML documentation file", Property.Kind.BOOL),
    Property("AllowUnsafeBlocks", "Allow unsafe code", Property.Kind.BOOL),
    Property("EnableNETAnalyzers", "Run .NET analyzers", Property.Kind.BOOL),
    Property("AnalysisLevel", "Analysis level:", Property.Kind.CHOICE, listOf("", "latest", "latest-recommended", "latest-all", "preview", "none", "10.0", "9.0", "8.0")),
    Property("EnforceCodeStyleInBuild", "Enforce code style in build", Property.Kind.BOOL),
    Property("InvariantGlobalization", "Invariant globalization", Property.Kind.BOOL),
    Property("SatelliteResourceLanguages", "Satellite resource languages:", Property.Kind.TEXT, comment = "e.g. en;ru — fewer resource folders in the output"),
)

private val PACKAGE = listOf(
    Property("PackageId", "Package ID:", Property.Kind.TEXT, comment = "The assembly name by default"),
    Property("Version", "Version:", Property.Kind.TEXT, comment = "1.0.0 by default; also the assembly and file version unless they are set"),
    Property("Authors", "Authors:", Property.Kind.TEXT),
    Property("Company", "Company:", Property.Kind.TEXT),
    Property("Description", "Description:", Property.Kind.TEXT),
    Property("PackageLicenseExpression", "License expression:", Property.Kind.TEXT, comment = "SPDX: MIT, Apache-2.0, ..."),
    Property("PackageProjectUrl", "Project URL:", Property.Kind.TEXT),
    Property("RepositoryUrl", "Repository URL:", Property.Kind.TEXT),
    Property("PackageTags", "Tags:", Property.Kind.TEXT, comment = "Separated by spaces or ;"),
    Property("PackageReadmeFile", "README file:", Property.Kind.TEXT, comment = "A file of the project packed as well, e.g. README.md"),
    Property("GeneratePackageOnBuild", "Generate NuGet package on build", Property.Kind.BOOL),
    Property("IsPackable", "Packable", Property.Kind.BOOL),
)

private val COMMON_FRAMEWORKS = listOf("net10.0", "net9.0", "net8.0", "netstandard2.1", "netstandard2.0", "net48", "net472")

class ProjectPropertiesDialog(private val project: Project, private val projectFile: VirtualFile, private val xml: XmlFile) : DialogWrapper(project) {
    private val all = APPLICATION + BUILD + PACKAGE
    private val original: Map<String, String> = ProjectProperties.read(xml, all.map { it.name })
    private val originalFrameworks = ProjectProperties.readTargetFrameworks(xml)

    private val frameworksField = JBTextField(originalFrameworks.joinToString(";"))
    private val texts = HashMap<String, JBTextField>()
    private val checks = HashMap<String, JBCheckBox>()
    private val combos = HashMap<String, ComboBox<String>>()

    init {
        title = "Properties of '${projectFile.nameWithoutExtension}'"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabs = JBTabbedPane()
        tabs.addTab("Application", page(APPLICATION, withFrameworks = true))
        tabs.addTab("Build", page(BUILD))
        tabs.addTab("Package", page(PACKAGE))
        tabs.preferredSize = java.awt.Dimension(620, 420)
        return tabs
    }

    private fun page(properties: List<Property>, withFrameworks: Boolean = false): JComponent = panel {
        if (withFrameworks) {
            row("Target frameworks:") {
                cell(frameworksField).align(AlignX.FILL)
                    .comment("Several separated by ; — ${COMMON_FRAMEWORKS.joinToString(", ")}. Empty: the SDK requires one, the build will say so")
            }
        }
        for (property in properties) {
            when (property.kind) {
                Property.Kind.TEXT -> row(property.label) {
                    val field = JBTextField(original[property.name].orEmpty()).also { texts[property.name] = it }
                    cell(field).align(AlignX.FILL).apply { property.comment?.let { comment(it) } }
                }
                Property.Kind.CHOICE -> row(property.label) {
                    val combo = ComboBox(property.choices.toTypedArray()).also { combos[property.name] = it }
                    combo.isEditable = true
                    combo.selectedItem = original[property.name].orEmpty()
                    cell(combo).apply { property.comment?.let { comment(it) } }
                }
                Property.Kind.BOOL -> row {
                    val check = JBCheckBox(property.label, original[property.name].isTrue()).also { checks[property.name] = it }
                    // three states are one too many for a dialog: unchecked writes nothing when the file has nothing, and "false" when it had "true"
                    cell(check).apply { property.comment?.let { comment(it) } }
                }
            }
        }
    }.apply { border = javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8) }

    override fun doValidate(): ValidationInfo? {
        val frameworks = frameworksField.text.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val bad = frameworks.firstOrNull { !TFM.matches(it) }
        return if (bad != null) ValidationInfo("'$bad' does not look like a target framework moniker (net9.0, netstandard2.0, net48)", frameworksField) else null
    }

    /** What the dialog would write: property -> value, an empty value for "remove". Only what has changed. */
    fun changes(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (property in all) {
            val value = when (property.kind) {
                Property.Kind.TEXT -> texts[property.name]?.text?.trim().orEmpty()
                Property.Kind.CHOICE -> (combos[property.name]?.editor?.item as? String)?.trim().orEmpty()
                Property.Kind.BOOL -> {
                    val checked = checks[property.name]?.isSelected == true
                    val had = original[property.name]
                    when {
                        checked -> "true"
                        had == null -> "" // never there, still not there
                        else -> "false" // was set: say "false" rather than fall back to a default that might be "true"
                    }
                }
            }
            if (value != original[property.name].orEmpty()) result[property.name] = value
        }
        return result
    }

    fun frameworks(): List<String> = frameworksField.text.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    fun apply() {
        val changes = changes()
        val frameworks = frameworks()
        if (changes.isEmpty() && frameworks == originalFrameworks) return
        WriteCommandAction.runWriteCommandAction(project, "Project Properties", null, {
            if (frameworks != originalFrameworks) ProjectProperties.writeTargetFrameworks(xml, frameworks)
            ProjectProperties.write(xml, changes)
            PsiDocumentManager.getInstance(project).getDocument(xml)?.let { document ->
                PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)
            }
        }, xml)
    }

    private fun String?.isTrue(): Boolean = this.equals("true", ignoreCase = true)

    companion object {
        private val TFM = Regex("""^(net\d+(\.\d+)?(-[a-z]+[\d.]*)?|netstandard\d\.\d|netcoreapp\d\.\d|net\d{2,3})$""", RegexOption.IGNORE_CASE)
    }
}
