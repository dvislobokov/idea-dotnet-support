package io.github.dotnetsupport.view

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.msbuild.MsBuildProject

enum class DependencyKind(val title: String) {
    PACKAGES("Packages"),
    PROJECTS("Projects"),
    ASSEMBLIES("Assemblies"),
}

data class DependenciesKey(val projectFile: VirtualFile)
data class DependencyGroupKey(val projectFile: VirtualFile, val kind: DependencyKind)

/** [name] is a package id, an assembly name or a relative path of a referenced project. */
data class DependencyKey(val projectFile: VirtualFile, val kind: DependencyKind, val name: String)

private fun MsBuildProject.names(kind: DependencyKind): List<String> = when (kind) {
    DependencyKind.PACKAGES -> packages.map { it.name }
    DependencyKind.PROJECTS -> projectReferences
    DependencyKind.ASSEMBLIES -> assemblies
}

class DependenciesNode(project: Project, key: DependenciesKey, settings: ViewSettings?) :
    SolutionViewNode<DependenciesKey>(project, key, settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val msBuildProject = solutions.msBuildProject(value.projectFile)
        return DependencyKind.entries
            .filter { msBuildProject.names(it).isNotEmpty() }
            .map { DependencyGroupNode(nodeProject, DependencyGroupKey(value.projectFile, it), settings) }
    }

    override fun contains(file: VirtualFile): Boolean = false

    override fun update(presentation: PresentationData) {
        presentation.setIcon(AllIcons.Nodes.PpLibFolder)
        presentation.presentableText = "Dependencies"
    }
}

class DependencyGroupNode(project: Project, key: DependencyGroupKey, settings: ViewSettings?) :
    SolutionViewNode<DependencyGroupKey>(project, key, settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> =
        solutions.msBuildProject(value.projectFile).names(value.kind)
            .sortedBy { it.lowercase() }
            .map { DependencyNode(nodeProject, DependencyKey(value.projectFile, value.kind, it), settings) }

    override fun contains(file: VirtualFile): Boolean = false
    override fun getTypeSortWeight(sortByType: Boolean): Int = value.kind.ordinal

    override fun update(presentation: PresentationData) {
        presentation.setIcon(
            when (value.kind) {
                DependencyKind.PACKAGES -> DotNetIcons.NuGet
                DependencyKind.PROJECTS -> DotNetIcons.Project
                DependencyKind.ASSEMBLIES -> DotNetIcons.Assembly
            }
        )
        presentation.presentableText = value.kind.title
    }
}

class DependencyNode(project: Project, key: DependencyKey, settings: ViewSettings?) :
    SolutionViewNode<DependencyKey>(project, key, settings) {

    /** Only a project reference can be opened. */
    override val navigationFile: VirtualFile?
        get() = if (value.kind != DependencyKind.PROJECTS) null
        else value.projectFile.parent?.findFileByRelativePath(value.name)?.takeIf { !it.isDirectory }

    override fun getChildren(): Collection<AbstractTreeNode<*>> = emptyList()
    override fun contains(file: VirtualFile): Boolean = false
    override fun isAlwaysLeaf(): Boolean = true

    override fun update(presentation: PresentationData) {
        when (value.kind) {
            DependencyKind.PACKAGES -> {
                val declared = solutions.msBuildProject(value.projectFile).packages.find { it.name == value.name }?.version
                presentation.setIcon(DotNetIcons.NuGet)
                presentation.presentableText = value.name
                presentation.locationString = declared ?: solutions.centralPackageVersion(value.projectFile, value.name)
            }
            DependencyKind.PROJECTS -> {
                presentation.setIcon(DotNetIcons.forProjectFile(value.name))
                presentation.presentableText = value.name.substringAfterLast('/').substringBeforeLast('.')
                if (navigationFile == null) presentation.locationString = "not found"
            }
            DependencyKind.ASSEMBLIES -> {
                presentation.setIcon(DotNetIcons.Assembly)
                presentation.presentableText = value.name
            }
        }
    }
}
