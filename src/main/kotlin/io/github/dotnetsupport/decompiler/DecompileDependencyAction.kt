package io.github.dotnetsupport.decompiler

import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetInstallation
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.index.ProjectAssemblies
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.DependencyKey
import io.github.dotnetsupport.view.DependencyKind
import java.io.File
import javax.swing.JList

/**
 * The assemblies behind a node of Dependencies in the Solution view: a package (its compile assets), an assembly of a framework
 * (`Frameworks → Microsoft.NETCore.App → System.Text.Json`), a `<Reference>` of the project. From the assets file and the project
 * file, as the index of assemblies finds them ([ProjectAssemblies]), for the framework of the node.
 */
object DependencyAssemblies {
    /** Whether the node stands for assemblies at all: a project reference or an analyzer does not, nor a framework as a whole. */
    fun isDecompilable(key: DependencyKey): Boolean = when (key.kind) {
        DependencyKind.PACKAGES, DependencyKind.ASSEMBLIES -> true
        DependencyKind.FRAMEWORKS -> key.parents.isNotEmpty()
        DependencyKind.PROJECTS, DependencyKind.ANALYZERS -> false
    }

    /** Reads the assets file: not for the EDT. */
    fun of(project: Project, key: DependencyKey): List<File> {
        val directory = File(key.projectFile.path).parentFile ?: return emptyList()
        val assets = File(directory, "obj/project.assets.json").takeIf { it.isFile }?.readText()
        val msbuild = SolutionService.getInstance(project).msBuildProject(key.projectFile)
        val references = ProjectAssemblies.references(ProjectAssemblies.Request(assets, directory, DotNetInstallation.root(), key.framework, msbuild))
        return select(references, key)
    }

    fun select(references: ProjectAssemblies.References, key: DependencyKey): List<File> {
        fun named(files: List<File>, name: String) = files.filter { it.nameWithoutExtension.equals(name.removeSuffix(".dll").substringBefore(','), ignoreCase = true) }
        return when (key.kind) {
            DependencyKind.PACKAGES -> references.libraries.filter { it.kind == ProjectAssemblies.LibraryKind.PACKAGE && it.name.equals(key.name, ignoreCase = true) }
                .flatMap { it.assemblies }
            DependencyKind.FRAMEWORKS -> {
                val pack = references.libraries.filter { it.kind == ProjectAssemblies.LibraryKind.FRAMEWORK && it.name.equals("${key.parents.first()}.Ref", ignoreCase = true) }
                    .flatMap { it.assemblies }
                named(pack, key.name).ifEmpty { named(references.assemblies, key.name) }.ifEmpty { referencePack(key) }
            }
            DependencyKind.ASSEMBLIES -> named(references.assemblies, key.name)
            else -> emptyList()
        }.distinct()
    }

    /** A project not restored yet still shows its frameworks: the assembly of the reference pack of the SDK. */
    private fun referencePack(key: DependencyKey): List<File> =
        ProjectAssemblies.referencePack(DotNetInstallation.root(), key.parents.first(), key.framework ?: return emptyList())
            .filter { it.nameWithoutExtension.equals(key.name, ignoreCase = true) }
}

/**
 * The types of the assemblies of a Dependencies node in a chooser with speed search, then the chosen one decompiled ([AssemblyDecompiler]):
 * the Solution view has no level of types under an assembly, as the Assembly Explorer of Rider has.
 */
object DecompileTypeChooser {
    fun show(project: Project, key: DependencyKey) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Listing the types of ${key.name}", true) {
            private var assemblies: List<File> = emptyList()
            private var types: List<Pair<File, AssemblyTypeInfo>> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                assemblies = DependencyAssemblies.of(project, key)
                types = AssemblyDecompiler.getInstance(project).types(assemblies, key.projectFile)
            }

            override fun onSuccess() {
                when {
                    assemblies.isEmpty() -> DotNetCli.notifyError(project, "Nothing to decompile in ${key.name}",
                        "No assembly of ${key.name} is found for ${key.framework ?: "the project"}: is the project restored?")
                    types.isEmpty() -> DotNetCli.notifyError(project, "Nothing to decompile in ${key.name}", "${assemblies.joinToString { it.name }} has no public types")
                    else -> popup(project, key, types, assemblies.size > 1)
                }
            }

            override fun onThrowable(error: Throwable) {
                val message = if (error is HelperException) error.message.orEmpty() else PluginLog.describe(error)
                PluginLog.warn(AssemblyDecompiler.LOG_CATEGORY, "The types of ${key.name} could not be listed: $message")
                DotNetCli.notifyError(project, "Cannot list the types of ${key.name}", message)
            }
        })
    }

    private fun popup(project: Project, key: DependencyKey, types: List<Pair<File, AssemblyTypeInfo>>, showAssembly: Boolean) {
        if (project.isDisposed) return
        val sorted = types.sortedWith(compareBy({ it.second.displayName.lowercase() }, { it.second.namespace }))
        JBPopupFactory.getInstance().createPopupChooserBuilder(sorted)
            .setTitle("Decompile a Type of ${key.name.removeSuffix(".dll")}")
            .setNamerForFiltering { "${it.second.displayName} ${it.second.namespace}" }
            .setRenderer(object : ColoredListCellRenderer<Pair<File, AssemblyTypeInfo>>() {
                override fun customizeCellRenderer(list: JList<out Pair<File, AssemblyTypeInfo>>, value: Pair<File, AssemblyTypeInfo>, index: Int, selected: Boolean, focus: Boolean) {
                    icon = icon(value.second.kind)
                    append(value.second.displayName)
                    if (value.second.namespace.isNotEmpty()) append("  ${value.second.namespace}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (showAssembly) append("  ${value.first.nameWithoutExtension}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            })
            .setItemChosenCallback { (assembly, type) ->
                AssemblyDecompiler.getInstance(project).open(FileUtil.toSystemDependentName(assembly.path), type.name, projectFile = key.projectFile)
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    fun icon(kind: String) = when (kind) {
        "interface" -> AllIcons.Nodes.Interface
        "enum" -> AllIcons.Nodes.Enum
        "struct" -> AllIcons.Nodes.Static
        "delegate" -> AllIcons.Nodes.Lambda
        "record" -> AllIcons.Nodes.Record
        else -> AllIcons.Nodes.Class
    }
}

/** Dependencies → a package or an assembly → Decompile...: a type of it as C#, read-only. */
class DecompileDependencyAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun key(e: AnActionEvent): DependencyKey? {
        val item = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS)?.singleOrNull() ?: return null
        return ((item as? AbstractTreeNode<*>)?.value ?: item) as? DependencyKey
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && key(e)?.let(DependencyAssemblies::isDecompilable) == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DecompileTypeChooser.show(project, key(e) ?: return)
    }
}
