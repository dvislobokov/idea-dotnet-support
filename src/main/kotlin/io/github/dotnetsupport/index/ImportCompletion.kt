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
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.lang.CSharpCalls
import io.github.dotnetsupport.lang.CSharpExpectations
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpTokenTypes
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
class AssemblyIndexService(private val project: Project) {
    private val byProject = ConcurrentHashMap<String, List<AssemblyIndex>>()
    private val opened = ConcurrentHashMap<File, AssemblyIndex>()
    private val running = AtomicBoolean()
    private val again = AtomicBoolean()

    /** What is indexed for the project of [projectFile]; empty until the indexer has run (it is started then). */
    fun indexes(projectFile: VirtualFile): List<AssemblyIndex> = byProject[projectFile.path] ?: emptyList<AssemblyIndex>().also { schedule() }

    val isReady: Boolean get() = byProject.isNotEmpty()

    @TestOnly
    fun set(projectFile: VirtualFile, indexes: List<AssemblyIndex>) {
        byProject[projectFile.path] = indexes
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
        val projectFiles = solutions.solutionFiles().flatMap { solution -> solutions.solution(solution).allProjects.mapNotNull { it.resolveFile(solution) } }.distinct()
        if (projectFiles.isEmpty()) return
        val dotnetRoot = DotNetCli.findExecutable()?.let { runCatching { File(it).canonicalFile.parentFile }.getOrNull() }
        val lists = LinkedHashMap<String, List<File>>()
        for (projectFile in projectFiles) {
            val directory = File(projectFile.path).parentFile ?: continue
            val assets = File(directory, "obj/project.assets.json").takeIf { it.isFile } ?: continue
            lists[projectFile.path] = ProjectAssemblies.of(ProjectAssemblies.Request(assets.readText(), directory, dotnetRoot))
        }
        val all = lists.values.flatten().distinct()
        if (all.isEmpty()) return
        val started = System.nanoTime()
        val indexed = IndexerTool.getInstance().index(all, IndexerTool.indexDirectory())
        if (indexed.isEmpty()) return
        for ((path, assemblies) in lists) {
            byProject[path] = assemblies.mapNotNull { assembly -> indexed[assembly]?.let(::open) }
        }
        LOG.info("Index of assemblies: ${lists.size} projects, ${all.size} assemblies, ${indexed.size} indexed, ${(System.nanoTime() - started) / 1_000_000} ms")
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

    /** The names of the type parameters are not in the index: a generic method that takes nothing has nothing to infer them from. */
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
    const val PRIORITY = 5.0
    const val PRIORITY_IMPORTED = 3.0
    const val PRIORITY_TYPE = 25.0

    private val OFFERED = setOf(IndexedMemberKind.METHOD, IndexedMemberKind.PROPERTY, IndexedMemberKind.FIELD, IndexedMemberKind.CONSTANT)
    private val BEFORE_A_NAME = Regex("""([A-Za-z_]\w*|[>\]?])\s+$""")

    /** After these a name is an expression; after any other word it is the name of what is being declared. */
    private val BEFORE_AN_EXPRESSION = setOf("return", "await", "throw", "yield", "case", "in", "is", "as", "else", "not", "and", "or", "when", "out", "ref", "do", "checked", "unchecked")

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
        val word = BEFORE_A_NAME.find(line)?.groupValues?.get(1) ?: return true
        return word in BEFORE_AN_EXPRESSION
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
                if (member.kind !in OFFERED || member.obsolete || member.type.obsolete) continue
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
        IndexedMemberKind.METHOD, IndexedMemberKind.EXTENSION_METHOD -> AllIcons.Nodes.Method
        IndexedMemberKind.PROPERTY -> AllIcons.Nodes.Property
        IndexedMemberKind.CONSTANT, IndexedMemberKind.ENUM_MEMBER -> AllIcons.Nodes.Constant
        IndexedMemberKind.FIELD -> AllIcons.Nodes.Field
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
        val type = parameters.position.node?.elementType
        if (type != null && (CSharpTokenTypes.STRINGS.contains(type) || CSharpTokenTypes.COMMENTS.contains(type))) return
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
