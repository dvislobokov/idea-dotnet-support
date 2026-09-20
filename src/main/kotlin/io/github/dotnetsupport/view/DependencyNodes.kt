package io.github.dotnetsupport.view

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.NodeSortOrder
import com.intellij.ide.projectView.NodeSortSettings
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.cli.DotNetInstallation
import io.github.dotnetsupport.msbuild.AssetsTarget
import io.github.dotnetsupport.msbuild.TargetFrameworks
import io.github.dotnetsupport.solution.SolutionService

/*
 * Dependencies
 * ├─ Imports: Sdk.props, Sdk.targets, Directory.Build.props, ...
 * └─ .NET 9.0                          one node per target framework
 *    ├─ Packages     direct packages with the resolved version, each expandable into what it brings in
 *    ├─ Projects
 *    ├─ Assemblies   <Reference> items
 *    ├─ Analyzers    packages that ship Roslyn analyzers
 *    └─ Frameworks   shared frameworks and their reference assemblies
 *
 * Versions, transitive packages, analyzers and frameworks come from obj/project.assets.json, i.e. they need a
 * restored project; without it the tree shows what is written in the project file.
 */

enum class DependencyKind(val title: String) {
    PACKAGES("Packages"),
    PROJECTS("Projects"),
    ASSEMBLIES("Assemblies"),
    ANALYZERS("Analyzers"),
    FRAMEWORKS("Frameworks"),
}

data class DependenciesKey(val projectFile: VirtualFile)
data class ImportsKey(val projectFile: VirtualFile)
data class ImportKey(val projectFile: VirtualFile, val path: String, val origin: String?)
data class FrameworkKey(val projectFile: VirtualFile, val framework: String)
data class DependencyGroupKey(val projectFile: VirtualFile, val kind: DependencyKind, val framework: String? = null)

/**
 * [name] is a package id, an assembly name, a framework reference or a relative path of a referenced project.
 * [parents] are the nodes above it inside the group: packages that depend on this one, or the package / framework an assembly belongs to.
 */
data class DependencyKey(
    val projectFile: VirtualFile,
    val kind: DependencyKind,
    val name: String,
    val framework: String? = null,
    val parents: List<String> = emptyList(),
)

private fun SolutionService.target(projectFile: VirtualFile, framework: String?): AssetsTarget? =
    assets(projectFile).targets.let { targets -> targets.find { it.framework == framework } ?: targets.singleOrNull().takeIf { framework == null } }

/** Top-level items of a group. */
private fun SolutionService.items(projectFile: VirtualFile, kind: DependencyKind, framework: String?): List<String> {
    val project = msBuildProject(projectFile)
    val target = target(projectFile, framework)
    return when (kind) {
        DependencyKind.PACKAGES -> target?.directPackages ?: project.packages.map { it.name }
        DependencyKind.PROJECTS -> project.projectReferences
        DependencyKind.ASSEMBLIES -> project.assemblies
        DependencyKind.ANALYZERS -> target?.packages.orEmpty().filter { it.analyzers.isNotEmpty() }.map { it.name }
        DependencyKind.FRAMEWORKS -> target?.frameworkReferences.orEmpty()
    }
}

class DependenciesNode(project: Project, key: DependenciesKey, settings: ViewSettings?) :
    SolutionViewNode<DependenciesKey>(project, key, settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val projectFile = value.projectFile
        val result = ArrayList<AbstractTreeNode<*>>()
        if (ImportsNode.collect(solutions, projectFile).isNotEmpty()) result += ImportsNode(nodeProject, ImportsKey(projectFile), settings)

        val frameworks = solutions.assets(projectFile).targets.map { it.framework }.ifEmpty { solutions.msBuildProject(projectFile).targetFrameworks }
        if (frameworks.isEmpty()) {
            // an old-style project that does not say what it targets: the groups without a framework level
            result += groups(nodeProject, solutions, projectFile, null, settings)
        } else {
            frameworks.mapTo(result) { FrameworkNode(nodeProject, FrameworkKey(projectFile, it), settings) }
        }
        return result
    }

    override fun contains(file: VirtualFile): Boolean = false
    override fun getTypeSortWeight(sortByType: Boolean): Int = 0

    // The first child of a project, as in Rider. Sort order is compared before the type weight, and with
    // "Folders Always on Top" directories are FOLDER, which precedes the UNSPECIFIED of ordinary nodes.
    override fun getSortOrder(settings: NodeSortSettings): NodeSortOrder = NodeSortOrder.MODULE_ROOT

    override fun update(presentation: PresentationData) {
        presentation.setIcon(AllIcons.Nodes.PpLibFolder)
        presentation.presentableText = "Dependencies"
    }

    companion object {
        fun groups(project: Project, solutions: SolutionService, projectFile: VirtualFile, framework: String?, settings: ViewSettings?) =
            DependencyKind.entries
                .filter { solutions.items(projectFile, it, framework).isNotEmpty() }
                .map { DependencyGroupNode(project, DependencyGroupKey(projectFile, it, framework), settings) }
    }
}

/** MSBuild files the project is built from besides itself. */
class ImportsNode(project: Project, key: ImportsKey, settings: ViewSettings?) : SolutionViewNode<ImportsKey>(project, key, settings) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> =
        collect(solutions, value.projectFile).map { ImportNode(nodeProject, it, settings) }

    override fun contains(file: VirtualFile): Boolean = false
    override fun getTypeSortWeight(sortByType: Boolean): Int = 0

    override fun update(presentation: PresentationData) {
        presentation.setIcon(AllIcons.General.Settings)
        presentation.presentableText = "Imports"
    }

    companion object {
        private val DIRECTORY_FILES = listOf("Directory.Build.props", "Directory.Build.targets", "Directory.Packages.props")

        /** In the order MSBuild reads them: SDK props, Directory.*.props, explicit imports, Directory.*.targets, SDK targets. */
        fun collect(solutions: SolutionService, projectFile: VirtualFile): List<ImportKey> {
            val project = solutions.msBuildProject(projectFile)
            val sdk = project.sdk?.let { name -> DotNetInstallation.sdkImports(name).map { ImportKey(projectFile, it.path.replace('\\', '/'), name) } }.orEmpty()
            val nearest = DIRECTORY_FILES.mapNotNull { name ->
                generateSequence(projectFile.parent) { it.parent }.firstNotNullOfOrNull { it.findChild(name) }?.let { ImportKey(projectFile, it.path, null) }
            }
            val explicit = project.imports.mapNotNull { projectFile.parent?.findFileByRelativePath(it) }.map { ImportKey(projectFile, it.path, null) }
            val (props, targets) = (nearest + explicit).partition { !it.path.endsWith(".targets", ignoreCase = true) }
            return sdk.filter { it.path.endsWith(".props") } + props + targets + sdk.filter { it.path.endsWith(".targets") }
        }
    }
}

class ImportNode(project: Project, key: ImportKey, settings: ViewSettings?) : SolutionViewNode<ImportKey>(project, key, settings) {
    override val navigationFile: VirtualFile?
        get() = value.projectFile.fileSystem.findFileByPath(value.path) ?: LocalFileSystem.getInstance().findFileByPath(value.path)

    // SDK files are outside of the project and may be unknown to the VFS yet: they are looked up on disk only when opened
    override fun canNavigate(): Boolean = true

    override fun navigate(requestFocus: Boolean) {
        val file = navigationFile ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(value.path) ?: return
        OpenFileDescriptor(nodeProject, file).navigate(requestFocus)
    }

    override fun getChildren(): Collection<AbstractTreeNode<*>> = emptyList()
    override fun contains(file: VirtualFile): Boolean = false
    override fun isAlwaysLeaf(): Boolean = true

    override fun update(presentation: PresentationData) {
        presentation.setIcon(DotNetIcons.MsBuild)
        presentation.presentableText = value.path.substringAfterLast('/')
        presentation.locationString = value.origin
    }
}

/** `.NET 9.0`: what the project depends on when it is built for that framework. */
class FrameworkNode(project: Project, key: FrameworkKey, settings: ViewSettings?) : SolutionViewNode<FrameworkKey>(project, key, settings) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> =
        DependenciesNode.groups(nodeProject, solutions, value.projectFile, value.framework, settings)

    override fun contains(file: VirtualFile): Boolean = false
    override fun getTypeSortWeight(sortByType: Boolean): Int = 1

    override fun update(presentation: PresentationData) {
        presentation.setIcon(AllIcons.Nodes.Module)
        presentation.presentableText = TargetFrameworks.displayName(value.framework)
        if (solutions.assets(value.projectFile).targets.isEmpty()) presentation.locationString = "not restored"
    }
}

class DependencyGroupNode(project: Project, key: DependencyGroupKey, settings: ViewSettings?) :
    SolutionViewNode<DependencyGroupKey>(project, key, settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> =
        solutions.items(value.projectFile, value.kind, value.framework)
            .sortedBy { it.lowercase() }
            .map { DependencyNode(nodeProject, DependencyKey(value.projectFile, value.kind, it, value.framework), settings) }

    override fun contains(file: VirtualFile): Boolean = false
    override fun getTypeSortWeight(sortByType: Boolean): Int = value.kind.ordinal

    override fun update(presentation: PresentationData) {
        presentation.setIcon(
            when (value.kind) {
                DependencyKind.PACKAGES -> DotNetIcons.NuGet
                DependencyKind.PROJECTS -> DotNetIcons.Project
                DependencyKind.ASSEMBLIES, DependencyKind.FRAMEWORKS -> DotNetIcons.Assembly
                DependencyKind.ANALYZERS -> AllIcons.Actions.Lightning
            }
        )
        presentation.presentableText = value.kind.title
    }
}

class DependencyNode(project: Project, key: DependencyKey, settings: ViewSettings?) :
    SolutionViewNode<DependencyKey>(project, key, settings) {

    private val target: AssetsTarget? get() = solutions.target(value.projectFile, value.framework)

    /** Only a project reference can be opened. */
    override val navigationFile: VirtualFile?
        get() = if (value.kind != DependencyKind.PROJECTS) null
        else value.projectFile.parent?.findFileByRelativePath(value.name)?.takeIf { !it.isDirectory }

    /** Names of the nodes below this one. */
    private fun childNames(): List<String> = when {
        // a package brings in its own dependencies; a package already met on the way down is not expanded again
        value.kind == DependencyKind.PACKAGES ->
            target?.findPackage(value.name)?.dependencies.orEmpty().filter { name -> (value.parents + value.name).none { it.equals(name, ignoreCase = true) } }
        value.parents.isNotEmpty() -> emptyList() // an assembly of an analyzer package or of a framework
        value.kind == DependencyKind.ANALYZERS -> target?.findPackage(value.name)?.analyzers.orEmpty()
        value.kind == DependencyKind.FRAMEWORKS ->
            value.framework?.let(TargetFrameworks::version)?.let { DotNetInstallation.frameworkAssemblies(value.name, it) }.orEmpty()
        else -> emptyList()
    }

    override fun getChildren(): Collection<AbstractTreeNode<*>> =
        childNames().sortedBy { it.lowercase() }.map { DependencyNode(nodeProject, value.copy(name = it, parents = value.parents + value.name), settings) }

    override fun contains(file: VirtualFile): Boolean = false

    override fun update(presentation: PresentationData) {
        val isAssembly = value.parents.isNotEmpty() && value.kind != DependencyKind.PACKAGES
        when {
            isAssembly || value.kind == DependencyKind.ASSEMBLIES -> {
                presentation.setIcon(DotNetIcons.Assembly)
                presentation.presentableText = value.name.removeSuffix(".dll")
            }
            value.kind == DependencyKind.PROJECTS -> {
                presentation.setIcon(DotNetIcons.forProjectFile(value.name))
                presentation.presentableText = value.name.substringAfterLast('/').substringBeforeLast('.')
                if (navigationFile == null) presentation.locationString = "not found"
            }
            value.kind == DependencyKind.FRAMEWORKS -> {
                presentation.setIcon(DotNetIcons.Assembly)
                presentation.presentableText = value.name
            }
            else -> { // a package, in Packages or in Analyzers
                val resolved = target?.findPackage(value.name)
                val declared = solutions.msBuildProject(value.projectFile).packages.find { it.name.equals(value.name, ignoreCase = true) }?.version
                presentation.setIcon(DotNetIcons.NuGet)
                presentation.presentableText = resolved?.name ?: value.name
                presentation.locationString = resolved?.version ?: declared ?: solutions.centralPackageVersion(value.projectFile, value.name)
            }
        }
    }
}
