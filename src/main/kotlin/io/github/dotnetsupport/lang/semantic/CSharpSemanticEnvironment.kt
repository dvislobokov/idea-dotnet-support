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
            }, GlobalSearchScope.projectScope(project))
        }
        if (own != null) found += CompilationModel.getInstance(project).options(own).usings
        return found.toList()
    }

    @TestOnly
    fun setAssembliesForTests(assemblies: ((PsiFile) -> AssemblyIndexSet?)?) {
        testAssemblies = assemblies
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
