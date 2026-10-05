package io.github.dotnetsupport.index

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.index.ProjectAssemblies.Library
import io.github.dotnetsupport.index.ProjectAssemblies.LibraryKind
import java.io.File
import javax.swing.Icon

/**
 * A package, a framework pack or an assembly the solution is compiled against, as a library of the IDE: Project view → External
 * Libraries, `GlobalSearchScope.allScope` and not the project scope. Its roots are the referenced assemblies themselves and not the
 * folders they are in: a package folder has the XML docs (tens of MB in a reference pack: text, so every text index would read
 * them), the targets of `build` and the sources of `contentFiles` next to them, and a dll is binary, so nothing but the name indexes looks at it.
 */
class AssemblyLibrary(val library: Library, val roots: List<VirtualFile>) : SyntheticLibrary("dotnet:" + library.key, null), ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = emptyList()
    override fun getBinaryRoots(): Collection<VirtualFile> = roots
    override fun getPresentableText(): String = library.presentableName
    override fun getIcon(unused: Boolean): Icon = if (library.kind == LibraryKind.PACKAGE) DotNetIcons.NuGet else DotNetIcons.Assembly
    override fun equals(other: Any?): Boolean = other is AssemblyLibrary && other.library.key == library.key && other.roots == roots
    override fun hashCode(): Int = library.key.hashCode() * 31 + roots.hashCode()
    override fun toString(): String = library.presentableName
}

object AssemblyLibraries {
    /**
     * The libraries of all the projects: one per package and version, the assemblies of every framework the projects are built for
     * together (a package for `net8.0` and `netstandard2.0` is one library with two dlls). Frameworks first, then packages, then
     * assemblies by path, each by name, as Rider lists the Dependencies of a project.
     */
    fun merge(references: Collection<ProjectAssemblies.References>): List<Library> {
        val byKey = LinkedHashMap<String, Library>()
        for (library in references.flatMap { it.libraries }) {
            val known = byKey[library.key]
            byKey[library.key] = if (known == null) library else known.copy(assemblies = (known.assemblies + library.assemblies).distinct())
        }
        return byKey.values.sortedWith(compareBy<Library> { it.kind.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.version.orEmpty() })
    }

    /** [libraries] with their assemblies as files of the VFS; one with none of them on disk is left out. Not on the EDT: it may refresh. */
    fun resolve(libraries: List<Library>): List<AssemblyLibrary> = libraries.mapNotNull { library ->
        val roots = library.assemblies.mapNotNull(::virtualFile)
        if (roots.isEmpty()) null else AssemblyLibrary(library, roots)
    }

    private fun virtualFile(file: File): VirtualFile? {
        val system = LocalFileSystem.getInstance()
        return system.findFileByIoFile(file) ?: system.refreshAndFindFileByIoFile(file)
    }
}

/** The referenced assemblies of the solution as external libraries: what [AssemblyIndexService] has found by the last refresh. */
class AssemblyLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
        if (project.isDisposed) emptyList() else AssemblyIndexService.getInstance(project).libraries
}
