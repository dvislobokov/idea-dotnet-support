package io.github.dotnetsupport.frameworks.razor

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.dotnetsupport.codeanalysis.CSharpSourceScope
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lang.CSharpLanguage
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.razor.lang.codegen.RazorGeneratorEngine
import io.github.dotnetsupport.razor.lang.discovery.RAssembly
import io.github.dotnetsupport.razor.lang.discovery.RCompilation
import io.github.dotnetsupport.razor.lang.discovery.RTypeDefinition
import io.github.dotnetsupport.razor.lang.discovery.TagHelperDiscovery
import io.github.dotnetsupport.razor.lang.syntax.TagHelperCollection
import java.io.File

/**
 * The tag helpers and components a Razor file of a project can use (RAZOR_PLAN.md, R5c): the compiler's discovery (razor-psi-core,
 * [TagHelperDiscovery]) over the compilation of the project as the plugin sees it — its own assembly from its sources ([SourceRazorSymbols]:
 * the C# files of the project and the declarations of its `.razor` components), the projects it references from their sources, every other
 * assembly from the index ([IndexRazorCompilation]).
 */
object RazorTagHelpers {
    /**
     * The descriptors of the project [projectFile]. [generated]: the files other source generators made for it are part of its sources (the
     * Razor generator's own output never: the declarations of the components stand for it). In a read action.
     */
    fun discover(project: Project, projectFile: VirtualFile, generated: Boolean = true, assemblies: AssemblyIndexSet? = null, cache: Cache? = null): TagHelperCollection {
        val model = CompilationModel.getInstance(project)
        val options = model.options(projectFile)
        val assemblyName = options.assemblyName?.takeIf { it.isNotBlank() } ?: projectFile.nameWithoutExtension
        val service = AssemblyIndexService.getInstance(project)
        val referenced = service.references(projectFile)?.projects.orEmpty().mapNotNull { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(it) }
        // the projects it references come from their sources: their built outputs among the assemblies would be the same types twice
        val sourceAssemblies = (referenced.map { model.options(it).assemblyName?.takeIf { n -> n.isNotBlank() } ?: it.nameWithoutExtension } + assemblyName).toSet()
        val all = assemblies ?: service.symbols(projectFile)
        val set = AssemblyIndexSet(all.indexes.filter { it.assemblyName !in sourceAssemblies }, all.assemblyName, all.signed)
        val library = IndexRazorCompilation(assemblyName, set)
        val sources = SourceRazorSymbols(project, library)
        val referenceNames = set.indexes.map { it.assemblyName }
        val ownFiles = sourceFiles(project, projectFile, generated)
        val referencedFiles = referenced.associateWith { sourceFiles(project, it, generated) }
        sources.register(ownFiles + referencedFiles.values.flatten())
        val own = sources.assembly(assemblyName, referenceNames, ownFiles)
        val references = referenced.map { reference -> sources.assembly(sources.assemblyName(reference), referenceNames, referencedFiles.getValue(reference)) }
        val compilation = object : RCompilation {
            override val assemblyName: String = assemblyName
            override val assemblies: List<RAssembly> = listOf(own) + references + library.assemblies
            override fun typeByMetadataName(metadataName: String): RTypeDefinition? = library.typeByMetadataName(metadataName)
            override fun typesByMetadataName(metadataName: String): List<RTypeDefinition> = library.typesByMetadataName(metadataName)
        }
        val version = RazorGeneratorEngine.versionOf(options.targetFramework ?: options.targetFrameworks.firstOrNull())
        val discovery = TagHelperDiscovery.forCompilation(compilation, version)
        if (cache == null) return discovery.discover(compilation)
        // the assemblies' descriptors depend on them and the producers alone: the same set of assemblies, the same descriptors
        val fromLibrary = cache.library?.takeIf { it.first == set.indexes && it.second == version }?.third
            ?: library.assemblies.flatMap(discovery::discoverAssembly).also { cache.library = Triple(set.indexes, version, it) }
        val everything = (listOf(own) + references).flatMap(discovery::discoverAssembly) + fromLibrary
        val seen = HashSet<String>()
        return TagHelperCollection(everything.filter { seen.add(io.github.dotnetsupport.razor.lang.discovery.tagHelperKey(it)) })
    }

    /** What [discover] keeps between the runs of a project: the descriptors of its assemblies for one set of them. */
    class Cache {
        @Volatile var library: Triple<List<io.github.dotnetsupport.index.AssemblyIndex>, io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion, List<io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor>>? = null
    }

    /**
     * The C# of the compilation of [projectFile]: the declarations of its components (`.razor`, ordered by path), then its C# files (ordered by
     * path) — the order of razordump's compilation, which the gate compares with.
     */
    fun sourceFiles(project: Project, projectFile: VirtualFile, generated: Boolean): List<CSharpFile> {
        val model = CompilationModel.getInstance(project)
        val psi = PsiManager.getInstance(project)
        val scope = CSharpSourceScope.of(project)
        val root = projectFile.parent
        fun relative(file: VirtualFile) = root?.let { VfsUtilCore.getRelativePath(file, it, '/') } ?: file.path
        val razor = FileTypeIndex.getFiles(RazorFileType, GlobalSearchScope.projectScope(project))
            .filter { model.projectOf(it) == projectFile }.sortedBy(::relative)
            .mapNotNull { (psi.findFile(it) as? RazorFile)?.let(::declarationFile) }
        val csharp = FileTypeIndex.getFiles(CSharpFileType, scope)
            .filter { model.projectOf(it) == projectFile && (generated || !isGenerated(project, it, root)) && !isRazorOutput(it) }.sortedBy(::relative)
            .mapNotNull { psi.findFile(it) as? CSharpFile }
        return razor + csharp
    }

    private fun isGenerated(project: Project, file: VirtualFile, root: VirtualFile?): Boolean {
        val path = root?.let { VfsUtilCore.getRelativePath(file, it, '/') } ?: file.path
        return project.getServiceIfCreated(io.github.dotnetsupport.codeanalysis.CodeAnalysisService::class.java)?.isGenerated(file) == true ||
            path.split('/').any { it == "obj" || it == "~obj" || it == "bin" }
    }

    /** What the Razor source generator wrote (`*_razor.g.cs`, `*_cshtml.g.cs` under its folder): the declarations of the components stand for it. */
    fun isRazorOutput(file: VirtualFile): Boolean = file.path.contains("Microsoft.CodeAnalysis.Razor.Compiler") || file.name.endsWith("_razor.g.cs") || file.name.endsWith("_cshtml.g.cs")

    private val DECLARATION_FILE = Key.create<com.intellij.psi.util.CachedValue<CSharpFile?>>("razor.declaration.file")

    /**
     * The declaration of the component [file] as a C# file the semantics reads: a light file whose original is the `.razor` file, so it is
     * compiled in that file's project. Cached until the `.razor` file changes.
     */
    fun declarationFile(file: RazorFile): CSharpFile? = CachedValuesManager.getManager(file.project).getCachedValue(file, DECLARATION_FILE, {
        val text = RazorCSharpDocument.declaration(file)
        val csharp = text?.let {
            PsiFileFactory.getInstance(file.project).createFileFromText(file.name + ".g.cs", CSharpLanguage, it, false, false) as? CSharpFile
        }
        (csharp as? PsiFileImpl)?.originalFile = file
        CachedValueProvider.Result.create(csharp, file)
    }, false)

    private val CONTEXT_FILE = Key.create<com.intellij.psi.util.CachedValue<CSharpFile?>>("razor.context.file")

    /**
     * A C# file compiled in the project of [file], to resolve the names of types there: the declaration of a component, else (a view or a
     * page of `.cshtml`, `_Imports.razor`) an empty light file whose original is [file].
     */
    fun contextFile(file: RazorFile): CSharpFile? = declarationFile(file) ?: CachedValuesManager.getManager(file.project).getCachedValue(file, CONTEXT_FILE, {
        val csharp = PsiFileFactory.getInstance(file.project).createFileFromText(file.name + ".context.cs", CSharpLanguage, "", false, false) as? CSharpFile
        (csharp as? PsiFileImpl)?.originalFile = file
        CachedValueProvider.Result.create(csharp, file)
    }, false)
}
