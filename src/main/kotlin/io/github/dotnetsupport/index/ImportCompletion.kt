package io.github.dotnetsupport.index

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.msbuild.CompilationModel
import com.intellij.openapi.Disposable
import io.github.dotnetsupport.lang.CSharpCalls
import io.github.dotnetsupport.lang.CSharpExpectations
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpLeaves
import io.github.dotnetsupport.lang.CSharpTypeNames
import io.github.dotnetsupport.lang.CSharpUsings
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.suggest.SuggestionRules
import io.github.dotnetsupport.suggest.SuggestionStats
import io.github.dotnetsupport.view.resolveFile
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon

/**
 * The indexes of the assemblies of every project of the solution: made in the background by the indexer (one process for all the
 * projects, what is indexed already is only looked up), opened once and shared — an assembly that ten projects refer to is one
 * mapped file.
 */
@Service(Service.Level.PROJECT)
class AssemblyIndexService(private val project: Project) : Disposable {
    private val byProject = ConcurrentHashMap<String, List<AssemblyIndex>>()
    private val references = ConcurrentHashMap<String, ProjectAssemblies.References>()
    private val symbols = ConcurrentHashMap<String, AssemblyIndexSet>()
    private val opened = ConcurrentHashMap<File, AssemblyIndex>()
    /** The assembly an index was made of, by its MVID: the header of the metadata view says where the dll is. */
    private val assemblyFiles = ConcurrentHashMap<String, File>()
    private val running = AtomicBoolean()
    private val again = AtomicBoolean()
    // the projects all of whose referenced assemblies are indexed: only there may the semantics say a name does not exist (task C4c)
    private val complete = ConcurrentHashMap<String, Boolean>()
    // projects in no solution whose files were asked about (a file of the playground's `Broken` in the editor): indexed too, once asked
    private val loose: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Changes when the indexes of a project change: what was computed from them (semantic errors) is computed again. */
    val modificationTracker = com.intellij.openapi.util.SimpleModificationTracker()

    /** The referenced assemblies as libraries of the IDE ([AssemblyLibraryRootsProvider]); empty until the first refresh. */
    @Volatile
    var libraries: List<AssemblyLibrary> = emptyList()
        private set

    /** What is indexed for the project of [projectFile]; empty until the indexer has run (it is started then). */
    fun indexes(projectFile: VirtualFile): List<AssemblyIndex> = byProject[projectFile.path] ?: emptyList<AssemblyIndex>().also {
        loose += projectFile.path
        schedule()
    }

    val isReady: Boolean get() = byProject.isNotEmpty()

    /** The indexes of all the projects, each once (an assembly ten projects refer to is one index): Go to Class over the libraries. */
    fun allIndexes(): List<AssemblyIndex> {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AssemblyIndex, Boolean>())
        return byProject.values.flatMap { it }.filter(seen::add)
    }

    /** The dll the index of [mvid] was made of; null until the indexer has run, or for an index of the tests. */
    fun assemblyFile(mvid: String): File? = assemblyFiles[mvid]

    /** An index of another project of the solution that one project does not refer to, with where that project gets it from ([unreferenced]). */
    class Unreferenced(val index: AssemblyIndex, /** The package or the framework pack it is of; null for the output of a project or an unknown dll. */ val library: ProjectAssemblies.Library?, /** The project whose output the dll is. */ val project: File?)

    /**
     * The indexes of the other projects that [projectFile] is not compiled against (the packages of the solution it does not reference, the
     * outputs of projects it does not refer to): the second Ctrl+Space offers their types (0.1.96). Each index once, the dll of each by [assemblyFile].
     */
    fun unreferenced(projectFile: VirtualFile): List<Unreferenced> {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AssemblyIndex, Boolean>())
        seen += byProject[projectFile.path].orEmpty()
        val result = ArrayList<Unreferenced>()
        for ((path, indexes) in byProject) {
            if (path == projectFile.path) continue
            val references = references[path]
            for (index in indexes) {
                if (!seen.add(index)) continue
                val file = assemblyFiles[index.mvid]
                val library = file?.let { dll -> references?.libraries?.firstOrNull { dll in it.assemblies } }
                val project = file?.takeIf { references?.projectOutputs?.contains(it) == true }
                    ?.let { dll -> references?.projects?.firstOrNull { it.nameWithoutExtension.equals(dll.nameWithoutExtension, ignoreCase = true) } }
                result += Unreferenced(index, library, project)
            }
        }
        return result
    }

    /** The indexes of the project whose references have [index], for what is resolved in the metadata view of a type of it. */
    fun symbolsWith(index: AssemblyIndex): AssemblyIndexSet? =
        byProject.entries.firstOrNull { (_, indexes) -> indexes.any { it === index } }?.let { (path, _) -> symbols.computeIfAbsent(path) { AssemblyIndexSet(byProject[path].orEmpty()) } }

    /**
     * The indexes of a project that is compiled against the dll [assembly] (a decompiled type of it: its names resolve as that project
     * sees them), else of the project with the most of them; null before the indexer has run.
     */
    fun symbolsWithAssembly(assembly: File): AssemblyIndexSet? {
        val path = assembly.path
        val owner = byProject.entries.firstOrNull { (_, indexes) -> indexes.any { assemblyFiles[it.mvid]?.path.equals(path, ignoreCase = true) } }
            ?: byProject.entries.maxByOrNull { it.value.size } ?: return null
        return symbols.computeIfAbsent(owner.key) { AssemblyIndexSet(byProject[owner.key].orEmpty()) }
    }

    /**
     * Whether every assembly the project of [projectFile] is compiled against is indexed (the outputs of referenced projects aside: their
     * sources are in the solution). False until the indexer has run, and when an assembly could not be indexed.
     */
    fun isComplete(projectFile: VirtualFile): Boolean = complete[projectFile.path] ?: false.also { if (loose.add(projectFile.path)) schedule() }

    /** What the project of [projectFile] is compiled against, as the last refresh found it; null until then. */
    fun references(projectFile: VirtualFile): ProjectAssemblies.References? = references[projectFile.path]

    /** The indexes of the project together, for a resolver: types by name, members with the inherited ones, extension methods, docs. */
    fun symbols(projectFile: VirtualFile): AssemblyIndexSet {
        val indexes = indexes(projectFile)
        // the assembly the project compiles to: the libraries whose InternalsVisibleTo name it show it their internals
        val options = CompilationModel.getInstance(project).options(projectFile)
        val assemblyName = options.assemblyName ?: projectFile.nameWithoutExtension
        symbols[projectFile.path]?.takeIf { it.indexes === indexes && it.assemblyName == assemblyName && it.signed == options.signAssembly }?.let { return it }
        return AssemblyIndexSet(indexes, assemblyName, options.signAssembly).also { symbols[projectFile.path] = it }
    }

    init {
        // another framework in the toolbar: another set of assemblies (a null list of projects is that change, not an evaluation)
        project.messageBus.connect(this).subscribe(CompilationModel.CHANGED, CompilationModel.Listener { projectFiles -> if (projectFiles == null) schedule() })
    }

    override fun dispose() = Unit

    @TestOnly
    fun set(projectFile: VirtualFile, indexes: List<AssemblyIndex>, isComplete: Boolean = false) {
        byProject[projectFile.path] = indexes
        complete[projectFile.path] = isComplete
        modificationTracker.incModificationCount()
    }

    @TestOnly
    fun clearIndexes() {
        byProject.clear()
        complete.clear()
        loose.clear()
        symbols.clear()
        assemblyFiles.clear()
    }

    @TestOnly
    fun setAssemblyFile(index: AssemblyIndex, file: File) {
        assemblyFiles[index.mvid] = file
    }

    /** Restore has run, a project has come or gone, Reload was asked for: the lists are made again. */
    fun schedule() {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode || project.isDisposed) return
        if (!running.compareAndSet(false, true)) {
            again.set(true)
            return
        }
        application.executeOnPooledThread {
            try {
                do {
                    again.set(false)
                    runCatching { refresh() }.onFailure { LOG.warn("The index of assemblies could not be refreshed", it) }
                } while (again.get() && !project.isDisposed)
            } finally {
                running.set(false)
            }
        }
    }

    private fun refresh() {
        val solutions = SolutionService.getInstance(project)
        val inSolutions = solutions.solutionFiles().flatMap { solution -> solutions.solution(solution).allProjects.mapNotNull { it.resolveFile(solution) } }
        val outside = loose.mapNotNull { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it)?.takeIf { file -> file.isValid } }
        val projectFiles = (inSolutions + outside).distinctBy { it.path }
        references.keys.retainAll(projectFiles.mapTo(HashSet()) { it.path })
        if (projectFiles.isEmpty()) return publishLibraries()
        val dotnetRoot = DotNetCli.findExecutable()?.let { runCatching { File(it).canonicalFile.parentFile }.getOrNull() }
        val framework = DotNetBuildSettings.getInstance(project).framework
        val lists = LinkedHashMap<String, List<File>>()
        for (projectFile in projectFiles) {
            val directory = File(projectFile.path).parentFile ?: continue
            val msbuild = solutions.msBuildProject(projectFile)
            // a project of the old format has no assets file: its references are its Reference items
            val assets = File(directory, "obj/project.assets.json").takeIf { it.isFile }?.readText()
            if (assets == null && !msbuild.isLegacy && msbuild.hintPaths.isEmpty()) continue
            val found = ProjectAssemblies.references(ProjectAssemblies.Request(assets, directory, dotnetRoot, framework, msbuild))
            references[projectFile.path] = found
            lists[projectFile.path] = found.forIndex
        }
        publishLibraries()
        val all = lists.values.flatten().distinct()
        if (all.isEmpty()) return
        val started = System.nanoTime()
        val indexed = IndexerTool.getInstance().index(all, IndexerTool.indexDirectory())
        if (indexed.isEmpty()) return
        for ((path, assemblies) in lists) {
            byProject[path] = assemblies.mapNotNull { assembly -> indexed[assembly]?.let(::open)?.also { assemblyFiles[it.mvid] = assembly } }
            complete[path] = references[path]?.assemblies?.all { assembly -> indexed[assembly]?.let(::open) != null } == true
        }
        modificationTracker.incModificationCount()
        // the errors of the open files were computed without the indexes (silent): compute them again
        ApplicationManager.getApplication().invokeLater({ com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart() }, project.disposed)
        LOG.info("Index of assemblies: ${lists.size} projects, ${all.size} assemblies, ${indexed.size} indexed, ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /**
     * The libraries made again from [references]; when they differ, the IDE is told (in a write action, as it wants): the new roots are
     * scanned — the names of the dlls, their content is not read — and the old ones are dropped. Not on the EDT: files are looked up.
     */
    fun publishLibraries() {
        val fresh = AssemblyLibraries.resolve(AssemblyLibraries.merge(references.values))
        if (fresh == libraries) return
        ApplicationManager.getApplication().invokeLater({
            val old = libraries
            if (fresh == old) return@invokeLater
            WriteAction.run<RuntimeException> {
                libraries = fresh
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                    project, "C# References", old.flatMap { it.roots }, fresh.flatMap { it.roots }, "C# References",
                )
            }
        }, ModalityState.nonModal(), project.disposed)
    }

    @TestOnly
    fun setReferences(found: Map<VirtualFile, ProjectAssemblies.References>) {
        references.clear()
        found.forEach { (file, value) -> references[file.path] = value }
    }

    private fun open(file: File): AssemblyIndex? = opened[file] ?: runCatching { AssemblyIndex.open(file.toPath()) }
        .onFailure { LOG.warn("Not an index: $file (${it.message})") }.getOrNull()?.also { opened[file] = it }

    companion object {
        private val LOG = logger<AssemblyIndexService>()
        fun getInstance(project: Project): AssemblyIndexService = project.service()
    }
}

/** The indexes are made when the project opens: the first completion has them. */
class AssemblyIndexStartup : ProjectActivity {
    override suspend fun execute(project: Project) = AssemblyIndexService.getInstance(project).schedule()
}

/** A static member of a type, its overloads together: a row of the completion list. */
class ImportItem(val type: IndexedType, val name: String, val overloads: List<IndexedMember>) {
    val first: IndexedMember get() = overloads.first()
    val kind: IndexedMemberKind get() = first.kind
    val isGeneric: Boolean get() = first.arity > 0

    /** `Console.WriteLine`, what is written into the code. */
    val qualifiedName: String get() = "${type.name}.$name"
    val returnsNothing: Boolean get() = kind.isCallable && overloads.all { it.returnType == "void" }
    val takesArguments: Boolean get() = overloads.any { it.parameters.isNotEmpty() }

    /** A generic method that takes nothing has nothing to infer its type arguments from. */
    val needsTypeArguments: Boolean get() = kind.isCallable && isGeneric && overloads.all { it.parameters.isEmpty() }

    /** `(string value)  +17 overloads` */
    val tail: String get() = listOfNotNull(
        first.signature.takeIf { it.isNotEmpty() },
        (overloads.size - 1).takeIf { it > 0 }?.let { "+$it overload" + if (it == 1) "" else "s" },
    ).joinToString("  ")
}

object ImportCompletion {
    const val MIN_PREFIX = 3
    const val MAX_ITEMS = 40
    /** Of a type that is not imported: under the keywords, as the items of unimported namespaces of the server (`RoslynCompletionPolicy.UNIMPORTED`). */
    const val PRIORITY = -5.0
    /** Its namespace is imported after all: 8 as before 0.1.44, above the keywords. */
    const val PRIORITY_IMPORTED = 13.0
    const val PRIORITY_TYPE = 25.0

    private val OFFERED = setOf(IndexedMemberKind.METHOD, IndexedMemberKind.PROPERTY, IndexedMemberKind.FIELD, IndexedMemberKind.CONSTANT)
    private val BEFORE_A_NAME = Regex("""([A-Za-z_]\w*|[>\]?])\s+$""")

    /** After these a name is an expression; after any other word it is the name of what is being declared. Not `as`: a type follows it. */
    private val BEFORE_AN_EXPRESSION = setOf("return", "await", "throw", "yield", "case", "in", "is", "else", "not", "and", "or", "when", "out", "ref", "do", "checked", "unchecked")

    /** `typeof(Str|`: the operators whose parentheses take a type alone. */
    private val TYPE_OPERATOR = Regex("""\b(typeof|sizeof|default)\s*\(\s*$""")

    /** A declaration with a base list or a constraint before the `:` (`class A : Str|`, `where T : Str|`), not a ternary or a named argument. */
    private val BASE_LIST = Regex("""(^|\s)(class|struct|interface|record|enum|where)\s""")

    /**
     * Whether a static member of another type may stand at [start]: not after a dot (there the members of what is before the dot are
     * listed), and not where a name is given to something (`string Wri|`).
     */
    fun isBareName(text: CharSequence, start: Int): Boolean {
        if (start < 0 || start > text.length) return false
        var before = start
        while (before > 0 && (text[before - 1] == ' ' || text[before - 1] == '\t')) before--
        if (before > 0 && text[before - 1] == '.') return false
        val lineStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
        val line = text.subSequence(lineStart, start)
        if (line.trimStart().startsWith("using ") || line.trimStart().startsWith("namespace ")) return false
        if (isTypeOnly(line)) return false
        val word = BEFORE_A_NAME.find(line)?.groupValues?.get(1) ?: return true
        return word in BEFORE_AN_EXPRESSION
    }

    /**
     * Where only a type may stand, so a static member is no answer (`Task<Str|` offered `Conversion.Str` of Microsoft.VisualBasic): a type
     * argument (`List<Str|`, `Dictionary<string, Str|` — a `<` right after a name, not a comparison `a < Str|`), the parentheses of
     * `typeof` / `sizeof` / `default`, and a base list or a constraint after `:`. [line] is the text of the line up to the name.
     */
    private fun isTypeOnly(line: CharSequence): Boolean {
        if (TYPE_OPERATOR.containsMatchIn(line)) return true
        val trimmed = line.trimEnd()
        if (trimmed.endsWith(":")) return BASE_LIST.containsMatchIn(trimmed)
        if (trimmed.endsWith(",") && BASE_LIST.containsMatchIn(trimmed) && trimmed.lastIndexOf(':') > trimmed.lastIndexOf('(')) return true
        // back to the `<` that opens the type argument list the name is in, over the names, commas and nested lists before it
        var depth = 0
        var i = trimmed.length - 1
        while (i >= 0) {
            when (val c = trimmed[i]) {
                '>' -> depth++
                '<' -> if (depth == 0) return i > 0 && (trimmed[i - 1].isLetterOrDigit() || trimmed[i - 1] == '_') else depth--
                ',', '.', '?', '[', ']', ' ', '\t' -> Unit
                else -> if (!c.isLetterOrDigit() && c != '_') return false
            }
            i--
        }
        return false
    }

    /**
     * The rows for [prefix]: static members of the types of [indexes], the overloads of a method in one row, what is obsolete left
     * out. The ones of namespaces that are [visible] already go first, then the ones of `System`, then the rest by their names.
     */
    fun items(indexes: List<AssemblyIndex>, prefix: String, visible: (String) -> Boolean, staticallyImported: Set<String> = emptySet()): List<ImportItem> {
        if (prefix.length < MIN_PREFIX) return emptyList()
        val groups = LinkedHashMap<String, MutableList<IndexedMember>>()
        for (index in indexes) {
            for (member in index.members(prefix)) {
                if (member.kind !in OFFERED || !member.isStatic || member.obsolete || member.type.obsolete) continue
                // `[EditorBrowsable(Never)]`: right to name, not to offer
                if (member.isHidden || member.type.isHidden) continue
                // the members of a generic type want its type arguments first: `Comparer<T>.Default`
                if (member.type.arity > 0 || member.type.qualifiedName in staticallyImported) continue
                groups.getOrPut("${member.type.qualifiedName}.${member.name}/${member.arity > 0}") { ArrayList() } += member
            }
        }
        return groups.values
            .map { overloads -> ImportItem(overloads.first().type, overloads.first().name, overloads.distinctBy { it.signature }.sortedBy { it.parameters.size }) }
            .sortedWith(compareBy<ImportItem> { !visible(it.type.namespace) }
                .thenBy { !(it.type.namespace == "System" || it.type.namespace.startsWith("System.")) }
                .thenBy { it.name.length }.thenBy { it.name }.thenBy { it.type.qualifiedName })
            .take(MAX_ITEMS)
    }

    fun icon(kind: IndexedMemberKind): Icon = when (kind) {
        IndexedMemberKind.METHOD, IndexedMemberKind.EXTENSION_METHOD, IndexedMemberKind.CONSTRUCTOR, IndexedMemberKind.OPERATOR -> AllIcons.Nodes.Method
        IndexedMemberKind.PROPERTY, IndexedMemberKind.INDEXER -> AllIcons.Nodes.Property
        IndexedMemberKind.CONSTANT, IndexedMemberKind.ENUM_MEMBER -> AllIcons.Nodes.Constant
        IndexedMemberKind.FIELD -> AllIcons.Nodes.Field
        IndexedMemberKind.EVENT -> AllIcons.Nodes.Field
    }

    /** The namespaces a file of the project sees without a `using` of its own: the implicit ones of the SDK and the global ones. */
    fun visibleEverywhere(project: Project, projectFile: VirtualFile): Set<String> {
        val msbuild = SolutionService.getInstance(project).msBuildProject(projectFile)
        val found = LinkedHashSet(CSharpUsings.implicit(msbuild.sdk, msbuild.implicitUsings))
        val directory = File(projectFile.path).parentFile ?: return found
        // what the build has generated says exactly what the implicit ones are, `<Using Include>` of the project among them
        val generated = File(directory, "obj").walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".GlobalUsings.g.cs") }
        val written = directory.listFiles { file -> file.isFile && file.name.contains("Using", ignoreCase = true) && file.extension == "cs" }.orEmpty().asSequence()
        for (file in generated + written) runCatching { found += CSharpUsings.global(file.readText()) }
        return found
    }
}

/**
 * Static members of types that are not imported, by the bare name: `WriteLi` offers `Console.WriteLine`, and choosing it writes the
 * type, the parentheses, the semicolon of a statement and the `using` that is missing. The language server lists at a bare name only
 * what is seen without a qualifier; this comes from the index of the assemblies of the project, so it is there before the solution
 * is loaded and costs no request.
 */
class ImportCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? CSharpFile ?: return
        val virtualFile = file.virtualFile ?: return
        val prefix = result.prefixMatcher.prefix
        if (prefix.length < ImportCompletion.MIN_PREFIX) return
        if (CSharpLeaves.isInStringOrComment(parameters.position)) return
        val text = parameters.editor.document.immutableCharSequence
        val start = parameters.offset - prefix.length
        if (!ImportCompletion.isBareName(text, start)) return
        val projectFile = DotNetProjects.findOwningProject(virtualFile) ?: return
        val indexes = AssemblyIndexService.getInstance(file.project).indexes(projectFile)
        if (indexes.isEmpty()) return

        val everywhere = ImportCompletion.visibleEverywhere(file.project, projectFile)
        val visible = { namespace: String -> CSharpUsings.isVisible(namespace, text, everywhere) }
        val expected = CSharpExpectations.at(text, start)
        for (item in ImportCompletion.items(indexes, prefix, visible, CSharpUsings.importedStatically(text))) {
            var priority = ImportCompletion.PRIORITY
            if (visible(item.type.namespace)) priority += ImportCompletion.PRIORITY_IMPORTED
            if (CSharpTypeNames.matches(expected?.type, item.first.returnType)) priority += ImportCompletion.PRIORITY_TYPE
            result.addElement(PrioritizedLookupElement.withPriority(element(item, everywhere), priority))
        }
    }

    private fun element(item: ImportItem, everywhere: Set<String>): LookupElement =
        LookupElementBuilder.create(item, item.name)
            .withPresentableText(item.qualifiedName + if (item.isGeneric) "<>" else "")
            .withTailText(listOf(item.tail, "(${item.type.namespace})").filter { it.isNotEmpty() }.joinToString("  ", prefix = " "), true)
            .withTypeText(item.first.returnType, true)
            .withIcon(ImportCompletion.icon(item.kind))
            .withInsertHandler { context, _ -> insert(context, item, everywhere) }
            .also { it.putUserData(SuggestionStats.SIGNALS, setOf(SuggestionRules.SIGNAL_INDEX)) }

    private fun insert(context: InsertionContext, item: ImportItem, everywhere: Set<String>) {
        val document = context.document
        val start = context.startOffset
        document.replaceString(start, context.tailOffset, item.qualifiedName)
        var end = start + item.qualifiedName.length
        var inside = false
        // Enter, Tab, and the one match of a prefix that the platform inserts by itself
        val chosen = context.completionChar == Lookup.NORMAL_SELECT_CHAR || context.completionChar == Lookup.REPLACE_SELECT_CHAR ||
            context.completionChar == Lookup.AUTO_INSERT_SELECT_CHAR
        if (item.kind.isCallable && chosen && document.charsSequence.getOrNull(end) != '(') {
            val text = document.charsSequence
            val call = CSharpCalls.call(item.returnsNothing, item.takesArguments, item.needsTypeArguments, CSharpCalls.endsStatement(text, start), CSharpCalls.restOfLine(text, end))
            document.insertString(end, call.text)
            inside = call.caret == 1 && !item.needsTypeArguments
            end += call.caret
        }
        context.editor.caretModel.moveToOffset(end)
        // the caret stays where it is in the code: it moves with what is inserted above it
        if (!CSharpUsings.isVisible(item.type.namespace, document.charsSequence, everywhere)) {
            CSharpUsings.insertion(document.charsSequence, item.type.namespace)?.let { document.insertString(it.offset, it.text) }
        }
        context.commitDocument()
        if (inside) AutoPopupController.getInstance(context.project).autoPopupParameterInfo(context.editor, null)
    }
}
