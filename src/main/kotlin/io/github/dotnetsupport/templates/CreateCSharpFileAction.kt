package io.github.dotnetsupport.templates

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.fileTemplates.DefaultTemplatePropertiesProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SolutionService
import java.util.Properties

/** New → Class/Interface: the Rider-like popup with a name field and the list of kinds. Inside .NET projects only. */
class CreateCSharpFileAction :
    CreateFileFromTemplateAction("Class/Interface", "Create a new C# class, interface, record, struct or enum", DotNetIcons.CSharpType), DumbAware {

    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Class/Interface")
            .addKind("Class", DotNetIcons.CSharpType, "CSharp Class")
            .addKind("Interface", DotNetIcons.CSharpType, "CSharp Interface")
            .addKind("Record", DotNetIcons.CSharpType, "CSharp Record")
            .addKind("Struct", DotNetIcons.CSharpType, "CSharp Struct")
            .addKind("Enum", DotNetIcons.CSharpType, "CSharp Enum")
    }

    override fun getActionName(directory: PsiDirectory, newName: String, templateName: String): String = "Create C# Type $newName"

    override fun isAvailable(dataContext: DataContext): Boolean =
        super.isAvailable(dataContext) &&
            LangDataKeys.IDE_VIEW.getData(dataContext)?.directories.orEmpty()
                .any { DotNetProjects.findOwningProject(it.virtualFile) != null }
}

/**
 * Variables of the C# templates. The namespace wrapping is precomputed here rather than written with
 * Velocity directives in the templates: they leave stray blank lines and the block form changes indentation.
 */
class CSharpTemplatePropertiesProvider : DefaultTemplatePropertiesProvider {
    override fun fillProperties(directory: PsiDirectory, props: Properties) {
        val namespace = CSharpNamespaces.forDirectory(directory.project, directory.virtualFile)
        val fileScoped = namespace != null && CSharpNamespaces.isFileScopedPreferred(directory.project, directory.virtualFile)
        props.setProperty("NAMESPACE", namespace.orEmpty())
        props.setProperty("NAMESPACE_HEADER", when {
            namespace == null -> ""
            fileScoped -> "namespace $namespace;\n\n"
            else -> "namespace $namespace\n{\n"
        })
        props.setProperty("NAMESPACE_FOOTER", if (namespace == null || fileScoped) "" else "\n}")
        props.setProperty("INDENT", if (namespace == null || fileScoped) "" else "    ")
    }
}

object CSharpNamespaces {
    private val NAMESPACE_STYLE = Regex("""^\s*csharp_style_namespace_declarations\s*=\s*(\w+)""", RegexOption.MULTILINE)
    private val ROOT = Regex("""^\s*root\s*=\s*true""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    fun forDirectory(project: Project, directory: VirtualFile): String? {
        val projectFile = DotNetProjects.findOwningProject(directory) ?: return null
        val rootNamespace = SolutionService.getInstance(project).msBuildProject(projectFile).rootNamespace
        return DotNetProjects.namespaceFor(directory, projectFile, rootNamespace)
    }

    /** `.editorconfig` decides; without it file-scoped namespaces are used wherever the language version has them (C# 10, .NET 6+). */
    fun isFileScopedPreferred(project: Project, directory: VirtualFile): Boolean {
        var current: VirtualFile? = directory
        while (current != null) {
            val text = current.findChild(".editorconfig")?.let { runCatching { String(it.contentsToByteArray(), it.charset) }.getOrNull() }
            if (text != null) {
                namespaceStyle(text)?.let { return it }
                if (ROOT.containsMatchIn(text)) break
            }
            current = current.parent
        }
        val frameworks = DotNetProjects.findOwningProject(directory)
            ?.let { SolutionService.getInstance(project).msBuildProject(it).targetFrameworks }
            .orEmpty()
        return frameworks.none(::isLegacyFramework)
    }

    /** True for `file_scoped`, false for `block_scoped`, null when the option is not set. */
    fun namespaceStyle(editorConfig: String): Boolean? =
        NAMESPACE_STYLE.findAll(editorConfig).lastOrNull()?.groupValues?.get(1)?.let { it.equals("file_scoped", ignoreCase = true) }

    /** net48, netstandard2.0, netcoreapp3.1, net5.0: the default C# version is older than 10. */
    fun isLegacyFramework(framework: String): Boolean {
        val tfm = framework.lowercase()
        if (tfm.startsWith("netstandard") || tfm.startsWith("netcoreapp")) return true
        val version = tfm.removePrefix("net").substringBefore('-')
        return '.' !in version || (version.substringBefore('.').toIntOrNull() ?: 0) < 6
    }
}
