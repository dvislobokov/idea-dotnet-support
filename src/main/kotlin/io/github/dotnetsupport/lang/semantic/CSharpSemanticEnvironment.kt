package io.github.dotnetsupport.lang.semantic

import io.github.dotnetsupport.index.AssemblyNavigation
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.IOUtil
import com.intellij.util.io.KeyDescriptor
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.view.resolveFile
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.msbuild.GlobalUsing
import org.jetbrains.annotations.TestOnly
import java.io.DataInput
import java.io.DataOutput

/**
 * What the semantics of a C# file needs beyond the sources (CSHARP_PSI_MIGRATION.md, step 11a): the assemblies its project is compiled
 * against ([AssemblyIndexService.symbols], empty until the indexer has run) and the global usings of the compilation — the `global using`
 * directives of every file of the project ([CSharpGlobalUsingIndex]: `obj/.../GlobalUsings.g.cs` among them when the project shows it) and the
 * `Using` items MSBuild gives the compiler (ImplicitUsings, `<Using Include>`: [CompilationModel]). Tests and the semantic gate give their
 * own assemblies ([setAssembliesForTests]).
 */
object CSharpSemanticEnvironment {
    @Volatile private var testAssemblies: ((PsiFile) -> AssemblyIndexSet?)? = null

    private val EMPTY = AssemblyIndexSet(emptyList())

    /** The project file [file] is compiled in; null for a loose file (and in tests without one). */
    fun projectOf(file: PsiFile): VirtualFile? {
        // the copy completion works in is a light file: its original says the project
        val virtualFile = file.originalFile.viewProvider.virtualFile
        return CompilationModel.getInstance(file.project).projectOf(virtualFile)
    }

    fun assemblies(file: PsiFile): AssemblyIndexSet {
        testAssemblies?.let { return it(file) ?: EMPTY }
        // a metadata view of an assembly belongs to no project: the assemblies of one that refers to it (B4)
        AssemblyNavigation.assembliesOf(file.project, file.viewProvider.virtualFile)?.let { return it }
        val projectFile = projectOf(file) ?: return EMPTY
        return AssemblyIndexService.getInstance(file.project).symbols(projectFile)
    }

    /**
     * The global usings of the compilation of [file]: `global using` of the files of its project (all C# files of the IDE project when it
     * is in none) and the `Using` items of the project. Its own `global using` directives are among them.
     */
    fun globalUsings(file: PsiFile): List<GlobalUsing> {
        val project = file.project
        val found = LinkedHashSet<GlobalUsing>()
        val own = projectOf(file)
        if (!DumbService.isDumb(project)) {
            val model = CompilationModel.getInstance(project)
            FileBasedIndex.getInstance().processValues(CSharpGlobalUsingIndex.NAME, CSharpGlobalUsingIndex.KEY, null, { other, directives ->
                if (own == null || model.projectOf(other) == own) directives.mapNotNullTo(found, CSharpGlobalUsingIndex::parse)
                true
            }, io.github.dotnetsupport.codeanalysis.CSharpSourceScope.of(project))
        }
        if (own != null) found += CompilationModel.getInstance(project).options(own).usings
        return found.toList()
    }

    /**
     * Whether [assemblies] of [file] are all the compilation refers to (task C4c): only then may a name be called missing. A loose file, a
     * project before its restore or with an assembly the indexer could not read is not.
     */
    fun referencesComplete(file: PsiFile): Boolean {
        testAssemblies?.let { return it(file) != null }
        if (AssemblyNavigation.assembliesOf(file.project, file.viewProvider.virtualFile) != null) return false
        val projectFile = projectOf(file) ?: return false
        return AssemblyIndexService.getInstance(file.project).isComplete(projectFile)
    }

    @Volatile private var testGenerates: Boolean? = null

    /**
     * Whether the project of [file] may have types and members no file of it declares: generators of the build that the IDE does not run.
     * There a missing name is no proof of an error: the semantics stays silent. What generates what is known to the framework modules
     * ([CSharpCodeGenerators]); the core knows only the source generators of packages, which the helper runs (D4).
     */
    fun mayGenerateTypes(file: PsiFile): Boolean {
        testGenerates?.let { return it }
        val projectFile = projectOf(file) ?: return false
        val project = file.project
        val msbuild = io.github.dotnetsupport.solution.SolutionService.getInstance(project).msBuildProject(projectFile)
        val directory = projectFile.parent
        val scope by lazy { com.intellij.psi.search.GlobalSearchScopesCore.directoryScope(project, directory!!, true) }
        val generators = { file.project.getServiceIfCreated(io.github.dotnetsupport.codeanalysis.CodeAnalysisService::class.java)?.generators(projectFile).orEmpty() }
        val facts = CSharpProjectFacts(msbuild.sdk.orEmpty(), { msbuild.packages.map { it.name } }, { buildGeneratedKnown(file, projectFile) }, { generatedKnown(file) }, generators) { extension ->
            // while the IDE indexes nothing is known
            directory != null && (DumbService.isDumb(project) || com.intellij.psi.search.FilenameIndex.getAllFilesByExt(project, extension, scope).isNotEmpty())
        }
        return mayGenerateTypes(facts, CSharpCodeGenerators.EP_NAME.extensionList) || directory != null && DumbService.isDumb(project)
    }

    /** [mayGenerateTypes] of [facts] by [generators] and the source generator packages of the core. */
    fun mayGenerateTypes(facts: CSharpProjectFacts, generators: List<CSharpCodeGenerators>): Boolean =
        generators.any { it.mayGenerateTypes(facts) } || !facts.sourceGeneratorsRan && SOURCE_GENERATOR_PACKAGES.any(facts::hasPackage)

    /** Packages known to generate types or members in source generators (`*.SourceGenerator(s)`, `CommunityToolkit.Mvvm`, `Refit`...): until the helper has run them. */
    private val SOURCE_GENERATOR_PACKAGES = listOf("Generator", "CommunityToolkit.Mvvm", "Refit", "Mapperly", "StronglyTypedId", "Vogen")

    @Volatile private var testBuildKnown: Boolean? = null

    /** The C# the targets of the build make of the files of the project of [file] is in `obj/` and newer than they are (0.1.82). */
    fun buildGeneratedKnown(file: PsiFile, projectFile: VirtualFile? = projectOf(file)): Boolean {
        testBuildKnown?.let { return it }
        projectFile ?: return false
        return io.github.dotnetsupport.codeanalysis.CodeAnalysisService.getInstance(file.project).isBuildGeneratedFresh(projectFile)
    }

    @TestOnly
    fun setBuildGeneratedKnownForTests(known: Boolean?) {
        testBuildKnown = known
    }

    @Volatile private var testGeneratedKnown: Boolean? = null

    /**
     * Whether the source generators of the project of [file] have run in CodeAnalysisHelper (task D4) and nothing of the project has been
     * edited since: then what they declare is in the index, and a partial type with a generated part is as complete as any other type.
     */
    fun generatedKnown(file: PsiFile): Boolean {
        testGeneratedKnown?.let { return it }
        val projectFile = projectOf(file) ?: return false
        val service = file.project.getServiceIfCreated(io.github.dotnetsupport.codeanalysis.CodeAnalysisService::class.java)
        if (service?.isGeneratedFresh(projectFile) == true) return true
        // a project of the old format (WPF of .NET Framework) has no source generators of packages, only what its build makes of XAML in
        // obj/ (0.1.82): fresh, that is all a partial type of it gets from elsewhere
        val legacy = io.github.dotnetsupport.solution.SolutionService.getInstance(file.project).msBuildProject(projectFile).sdk == null
        return legacy && buildGeneratedKnown(file, projectFile) && io.github.dotnetsupport.codeanalysis.CodeAnalysisService.getInstance(file.project).buildGenerated(projectFile).files.isNotEmpty()
    }

    /**
     * Whether the sources of the project of [other] are seen from [file]: its own project, or one its project references, directly or
     * through another (the compiler sees transitive `ProjectReference`s). Yes where a project is not known (a loose file, a test without one).
     * eShop's AppHost references no project: the `static class Extensions` of the global namespace of four other projects is not its
     * `eShop.AppHost.Extensions` (CS0117 `IsFoundryEnabled` was ours alone, 0.1.193).
     */
    fun sees(file: PsiFile, other: PsiFile): Boolean = other == file || other.originalFile == file.originalFile || sees(file, other.viewProvider.virtualFile)

    /** As [sees], for a file of the solution by its [VirtualFile]. */
    fun sees(file: PsiFile, other: VirtualFile): Boolean {
        if (other == file.originalFile.viewProvider.virtualFile) return true
        val own = projectOf(file) ?: return true
        val there = CompilationModel.getInstance(file.project).projectOf(other) ?: return true
        if (own == there) return true
        // a project referenced under an `extern alias` only (`<Aliases>`, no `global` among them) is not in the global namespace: its
        // types are reached as `X::N.T` alone — MudBlazor's analyzer tests, whose namespaces match the core's
        // — unless another reference brings it in without one (MSBuild passes a transitive reference to the compiler with no alias:
        // MudBlazor.UnitTests.Analyzers reaches the analyzers through MudBlazor.Analyzers.TestComponents too)
        val aliases = referenceAliases(file.project, own, there)
        val aliasedOnly = aliases.isNotEmpty() && aliases.none { it.equals("global", ignoreCase = true) }
        // the built output of that project among the assemblies of this one says it is referenced, however the reference is written
        if (!aliasedOnly && assemblies(file).indexes.any { it.assemblyName.equals(there.nameWithoutExtension, ignoreCase = true) }) return true
        // references not known for sure: everything of the solution the project works in (Open Solution, 0.1.243), nothing outside it
        val referenced = referencedProjects(file.project, own) ?: return solutionProjects(file.project)?.let { there.path.lowercase() in it } ?: true
        return there.path.lowercase() in referenced
    }

    /** Whether [file] reaches [other] through `extern alias` [alias]: the project of [other] is a reference of [file]'s project with that alias. */
    fun seesThroughAlias(file: PsiFile, other: VirtualFile, alias: String): Boolean {
        val own = projectOf(file) ?: return false
        val there = CompilationModel.getInstance(file.project).projectOf(other) ?: return false
        return own != there && referenceAliases(file.project, own, there).any { it.equals(alias, ignoreCase = true) }
    }

    /** The `<Aliases>` the project [own] references [there] with (a direct reference only); empty without an alias or a reference. */
    private fun referenceAliases(project: com.intellij.openapi.project.Project, own: VirtualFile, there: VirtualFile): List<String> {
        val solutions = io.github.dotnetsupport.solution.SolutionService.getInstance(project)
        val aliased = solutions.msBuildProject(own).projectReferenceAliases
        if (aliased.isEmpty()) return emptyList()
        for ((reference, aliases) in aliased) {
            val target = resolveReference(project, own, reference) ?: continue
            if (target == there) return aliases
        }
        return emptyList()
    }

    /** The file a `ProjectReference` [reference] of [projectFile] names; null when it has a property we cannot expand or is not there. */
    private fun resolveReference(project: com.intellij.openapi.project.Project, projectFile: VirtualFile, reference: String): VirtualFile? {
        val slashed = reference.replace('\\', '/')
        val solutionDir = Regex("^\\$\\(SolutionDir\\)/?", RegexOption.IGNORE_CASE)
        val (base, relative) = if (solutionDir.containsMatchIn(slashed)) (solutionDirectory(project) ?: return null) to solutionDir.replace(slashed, "")
            else (projectFile.parent ?: return null) to slashed
        if ('$' in relative) return null
        return base.findFileByRelativePath(relative)
    }

    /** The project files of the active solution, lower case; null when the folder has no solution (a folder of projects sees them all). */
    fun solutionProjects(project: com.intellij.openapi.project.Project): Set<String>? {
        val solutions = io.github.dotnetsupport.solution.SolutionService.getInstance(project)
        val solution = solutions.solutionFiles().firstOrNull() ?: return null
        return solutions.solution(solution).allProjects.mapNotNull { it.resolveFile(solution)?.path?.lowercase() }.toSet()
    }

    /** The folder of the active solution: what `$(SolutionDir)` stands for in a `ProjectReference` (nopCommerce's plugins reference `$(SolutionDir)\…\Nop.Web.csproj`). */
    private fun solutionDirectory(project: com.intellij.openapi.project.Project): VirtualFile? =
        io.github.dotnetsupport.solution.SolutionService.getInstance(project).solutionFiles().firstOrNull()?.parent

    /**
     * The project files [projectFile] references, transitively, by their paths in lower case; null when they cannot be known for sure — a
     * reference with a property (`$(…)`), one whose file is not found, a `Directory.Build.props` / `.targets` above that adds references.
     */
    fun referencedProjects(project: com.intellij.openapi.project.Project, projectFile: VirtualFile): Set<String>? {
        val found = LinkedHashSet<String>()
        val queue = ArrayDeque<VirtualFile>()
        queue.add(projectFile)
        val solutions = io.github.dotnetsupport.solution.SolutionService.getInstance(project)
        // a direct reference under an `extern alias` is not in the global namespace (reached as `X::N.T`); a transitive one always is
        val rootAliases = solutions.msBuildProject(projectFile).projectReferenceAliases
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (importsAddReferences(current)) return null
            for (reference in solutions.msBuildProject(current).projectReferences) {
                if (current == projectFile && rootAliases[reference]?.none { it.equals("global", ignoreCase = true) } == true) continue
                val slashed = reference.replace('\\', '/')
                // `$(SolutionDir)` is the folder of the active solution (a trailing separator included, as Visual Studio passes it)
                val solutionDir = Regex("^\\$\\(SolutionDir\\)/?", RegexOption.IGNORE_CASE)
                val (base, relative) = if (solutionDir.containsMatchIn(slashed)) (solutionDirectory(project) ?: return null) to solutionDir.replace(slashed, "")
                    else (current.parent ?: return null) to slashed
                if ('$' in relative) return null
                val target = base.findFileByRelativePath(relative) ?: return null
                if (found.add(target.path.lowercase())) queue.add(target)
            }
        }
        return found
    }

    /** Whether a `Directory.Build.props` / `.targets` above [projectFile] mentions `ProjectReference`: references the project file does not show. */
    private fun importsAddReferences(projectFile: VirtualFile): Boolean {
        var directory = projectFile.parent
        while (directory != null) {
            for (name in listOf("Directory.Build.props", "Directory.Build.targets")) {
                val props = directory.findChild(name) ?: continue
                if (runCatching { com.intellij.openapi.vfs.VfsUtilCore.loadText(props) }.getOrNull()?.contains("ProjectReference") == true) return true
            }
            directory = directory.parent
        }
        return false
    }

    @TestOnly
    fun setGeneratedKnownForTests(known: Boolean?) {
        testGeneratedKnown = known
    }

    @TestOnly
    fun setAssembliesForTests(assemblies: ((PsiFile) -> AssemblyIndexSet?)?) {
        testAssemblies = assemblies
    }

    @TestOnly
    fun setGeneratesForTests(generates: Boolean?) {
        testGenerates = generates
    }
}

/**
 * The `global using` directives of a C# file, as written (`System.Linq`, `static System.Math`, `Json=System.Text.Json`), under one key: the
 * files of a project that have any are few (`GlobalUsings.g.cs`, a `Usings.cs`), so the semantics reads them all at once
 * ([CSharpSemanticEnvironment.globalUsings]). Read from the text (the directives head the file), not from a tree: either tree indexes alike.
 */
class CSharpGlobalUsingIndex : FileBasedIndexExtension<String, List<String>>() {
    override fun getName(): ID<String, List<String>> = NAME
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getVersion(): Int = 1
    override fun dependsOnFileContent(): Boolean = true
    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(CSharpFileType)

    override fun getValueExternalizer(): DataExternalizer<List<String>> = object : DataExternalizer<List<String>> {
        override fun save(out: DataOutput, value: List<String>) {
            out.writeInt(value.size)
            value.forEach { IOUtil.writeUTF(out, it) }
        }

        override fun read(input: DataInput): List<String> = List(input.readInt()) { IOUtil.readUTF(input) }
    }

    override fun getIndexer(): DataIndexer<String, List<String>, FileContent> = DataIndexer { content ->
        val directives = directives(content.contentAsText)
        if (directives.isEmpty()) emptyMap() else mapOf(KEY to directives)
    }

    companion object {
        val NAME: ID<String, List<String>> = ID.create("dotnet.csharp.globalUsings")
        const val KEY = "global"

        private val DIRECTIVE = Regex("""(?m)^[ \t]*global[ \t]+using[ \t]+(static[ \t]+)?(?:(@?[\p{L}_][\p{L}\p{N}_]*)[ \t]*=[ \t]*)?([^;=\r\n]+);""")
        private val WHITESPACE = Regex("""\s+""")

        /** `global using static System.Math;` -> `static System.Math`; `global using J = System.Text.Json;` -> `J=System.Text.Json`. */
        fun directives(text: CharSequence): List<String> = DIRECTIVE.findAll(text).map { match ->
            val target = match.groupValues[3].replace(WHITESPACE, "").removePrefix("global::")
            val alias = match.groupValues[2]
            when {
                match.groupValues[1].isNotEmpty() -> "static $target"
                alias.isNotEmpty() -> "$alias=$target"
                else -> target
            }
        }.toList()

        fun parse(directive: String): GlobalUsing? = when {
            directive.startsWith("static ") -> GlobalUsing(directive.removePrefix("static "), isStatic = true)
            '=' in directive -> GlobalUsing(directive.substringAfter('='), alias = directive.substringBefore('='))
            directive.isNotEmpty() -> GlobalUsing(directive)
            else -> null
        }
    }
}
