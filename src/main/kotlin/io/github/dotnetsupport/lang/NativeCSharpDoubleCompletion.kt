package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.csharp.lang.psi.CSharpObjectCreationExpression
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.ProjectAssemblies
import io.github.dotnetsupport.lang.semantic.CSharpMemberLookup
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.CSharpSymbolText
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.nuget.NuGetService
import org.jetbrains.annotations.TestOnly
import java.io.File
import javax.swing.Icon

/**
 * Double completion (COMPLETION_GAPS 3.12, 0.1.96): the second Ctrl+Space (`invocationCount >= 2`) widens the list, as in Rider and IntelliJ,
 * and the first one says so in the advertisement line of the list.
 *  - After a dot: the members the place does not see (private / protected / internal of other types, protected of library types), grayed
 *    with "(not accessible)"; choosing one writes it as any member — the error of the compiler is then the user's to deal with, as in Rider.
 *  - Where a type may stand: the types of the assemblies of the other projects of the solution that this project does not reference
 *    ([AssemblyIndexService.unreferenced]), as `Name (in Namespace, Package 1.2.3)`; choosing one writes the `using` and offers to add the
 *    package or project reference in a notification (`dotnet add package` / `dotnet add reference`).
 *  - The second Ctrl+Shift+Space: the chains — a member of a local, a parameter or a member of the enclosing type whose value is of the
 *    expected type (`order.Customer` where a `Customer` is wanted), one access deep, as IntelliJ's chain completion.
 * Everything is capped and checks for cancellation: the second press may be slower than the first, not stall.
 */
object NativeCSharpDoubleCompletion {
    /** On a row of a member the place does not see. */
    val INACCESSIBLE: Key<Boolean> = Key.create("dotnet.completion.inaccessible")

    /** On a row of a type of an assembly the project does not reference: where it comes from, as shown. */
    val UNREFERENCED: Key<String> = Key.create("dotnet.completion.unreferenced")

    /** On a chain row: what is written (`order.Customer`). */
    val CHAIN: Key<String> = Key.create("dotnet.completion.chain")

    /** Under the keywords: what cannot be used as is. */
    const val INACCESSIBLE_PRIORITY = -5.0
    const val UNREFERENCED_PRIORITY = NativeCSharpImportCompletion.UNIMPORTED - 1
    /** Chains under the values of the expected type the first press lists, above the keywords. */
    const val CHAIN_PRIORITY = NativeCSharpCompletion.VALUE_MEMBER - 2

    const val MAX_UNREFERENCED = 100
    private const val MAX_CHAIN_ROOTS = 40
    private const val MAX_CHAINS = 120

    fun isSecond(parameters: CompletionParameters): Boolean = parameters.invocationCount >= 2

    private val TYPE_PLACES = setOf(NativeCompletionKind.TYPE, NativeCompletionKind.STATEMENT, NativeCompletionKind.EXPRESSION, NativeCompletionKind.MEMBER_START)
    private val MEMBER_PLACES = setOf(NativeCompletionKind.MEMBER_ACCESS, NativeCompletionKind.THIS_MEMBERS)

    // ---- the basic list

    /** The second Ctrl+Space adds its rows; the first one advertises it. [taken]: the names the list has. */
    fun basic(parameters: CompletionParameters, place: NativeCSharpCompletionPlace, file: CSharpFile, result: CompletionResultSet, taken: Set<String>) {
        if (!isSecond(parameters)) {
            // no promise of what there is none of: a solution of one project has no unreferenced packages
            if (parameters.invocationCount == 1 && (place.kind in MEMBER_PLACES || sources(file).isNotEmpty())) advertisement(place.kind)?.let(result::addLookupAdvertisement)
            return
        }
        val rows = when (place.kind) {
            NativeCompletionKind.MEMBER_ACCESS -> NativeCSharpMemberCompletion.items(place, file, result.prefixMatcher, inaccessible = true)
            NativeCompletionKind.THIS_MEMBERS -> NativeCSharpMemberCompletion.thisItems(place, file, result.prefixMatcher, inaccessible = true)
            in TYPE_PLACES -> unreferencedTypes(place, file, result.prefixMatcher, taken)
            else -> emptyList()
        }
        rows.forEach(result::addElement)
    }

    /** "Press Ctrl+Space again to show …" for the places the second press widens; null elsewhere. */
    fun advertisement(kind: NativeCompletionKind): String? {
        val what = when (kind) {
            in MEMBER_PLACES -> "members that are not accessible here"
            in TYPE_PLACES -> "types of packages the project does not reference"
            else -> return null
        }
        return "Press ${shortcut(IdeActions.ACTION_CODE_COMPLETION, "Ctrl+Space")} again to show $what"
    }

    fun smartAdvertisement(): String = "Press ${shortcut(IdeActions.ACTION_SMART_TYPE_COMPLETION, "Ctrl+Shift+Space")} again to show members of values of the expected type"

    private fun shortcut(actionId: String, fallback: String): String = KeymapUtil.getFirstKeyboardShortcutText(actionId).ifEmpty { fallback }

    /** A row of a member the place does not see: gray, "(not accessible)" after it, under everything. */
    fun inaccessible(element: LookupElement): LookupElement {
        val decorated = LookupElementDecorator.withRenderer(element, object : LookupElementRenderer<LookupElementDecorator<LookupElement>>() {
            override fun renderElement(element: LookupElementDecorator<LookupElement>, presentation: LookupElementPresentation) {
                element.delegate.renderElement(presentation)
                presentation.itemTextForeground = JBColor.GRAY
                presentation.appendTailText(" (not accessible)", true)
            }
        })
        decorated.putUserData(NativeCSharpCompletion.NATIVE, true)
        decorated.putUserData(INACCESSIBLE, true)
        return PrioritizedLookupElement.withPriority(decorated, INACCESSIBLE_PRIORITY).also {
            it.putUserData(NativeCSharpCompletion.NATIVE, true)
            it.putUserData(INACCESSIBLE, true)
        }
    }

    // ---- types of what is not referenced

    /** An index the project is not compiled against and where it comes from, as the row says it (`Newtonsoft.Json 13.0.3`, `Shop.Models`). */
    class Source(val index: AssemblyIndex, val origin: String, val package_: Pair<String, String>? = null, val project: File? = null)

    @Volatile private var testSources: List<Source>? = null

    @TestOnly
    fun setSourcesForTests(sources: List<Source>?) {
        testSources = sources
    }

    /** What was offered last by a chosen row (tests: no process runs). */
    @Volatile var lastOfferForTests: String? = null
        private set

    private fun sources(file: CSharpFile): List<Source> {
        testSources?.let { return it }
        val projectFile = CSharpSemanticEnvironment.projectOf(file) ?: return emptyList()
        return AssemblyIndexService.getInstance(file.project).unreferenced(projectFile).map { found ->
            val library = found.library
            when {
                library != null && library.kind == ProjectAssemblies.LibraryKind.PACKAGE -> Source(found.index, library.presentableName, package_ = library.name to library.version.orEmpty())
                found.project != null -> Source(found.index, found.project.nameWithoutExtension, project = found.project)
                else -> Source(found.index, library?.presentableName ?: found.index.assemblyName)
            }
        }
    }

    /**
     * The types of the unreferenced assemblies whose name begins with what is typed (nothing before the first letter: the whole cache would
     * come), not the ones the referenced assemblies have too, the `System` ones first, then the shorter namespaces, capped.
     */
    fun unreferencedTypes(place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher, taken: Set<String>): List<LookupElement> {
        val prefix = matcher.prefix
        if (prefix.length < NativeCSharpImportCompletion.MIN_PREFIX) return emptyList()
        val sources = sources(file).ifEmpty { return emptyList() }
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        val at = place.name ?: place.leaf
        val visible = resolver.visibleNamespaces(at)
        val own = resolver.assemblies
        val afterNew = place.name?.parent is CSharpObjectCreationExpression
        val seen = HashSet<String>()
        val found = ArrayList<Pair<IndexedType, Source>>()
        for (source in sources) {
            for (type in source.index.types(prefix, MAX_UNREFERENCED * 4)) {
                ProgressManager.checkCanceled()
                if (type.declaringType != null || type.isHidden || type.isProtected || type.simpleName.startsWith("<")) continue
                if (!matcher.prefixMatches(type.simpleName) || !seen.add("${type.fullName}`${type.arity}")) continue
                if (own.findType(type.fullName) != null) continue
                found += type to source
            }
        }
        return found.sortedWith(compareBy<Pair<IndexedType, Source>> { !isSystem(it.first.namespace) }.thenBy { it.first.namespace.length }.thenBy { it.first.simpleName })
            .take(MAX_UNREFERENCED).map { (type, source) -> typeRow(type, source, type.namespace in visible, afterNew, file) }
    }

    private fun typeRow(type: IndexedType, source: Source, imported: Boolean, afterNew: Boolean, file: CSharpFile): LookupElement {
        val name = type.simpleName
        val generic = type.ownArity > 0
        val presentable = if (generic) "$name<${"".padEnd(type.ownArity - 1, ',')}>" else name
        val constructed = afterNew && (type.kind == IndexedTypeKind.STRUCT || type.kind == IndexedTypeKind.CLASS && !type.isAbstract && !type.isStatic)
        val typeHandler = if (generic || constructed) NativeCSharpCalls.typeHandler(generic, constructed) else null
        val where = if (type.namespace.isEmpty()) source.origin else "${type.namespace}, ${source.origin}"
        val projectFile = CSharpSemanticEnvironment.projectOf(file)
        val handler = InsertHandler<LookupElement> { context, item ->
            typeHandler?.handleInsert(context, item)
            if (!imported) NativeCSharpImportCompletion.addUsing(context, type.namespace)
            offerReference(context.project, projectFile, source, name)
        }
        val builder = LookupElementBuilder.create("${source.origin}:${type.fullName}", name).withIcon(icon(type)).withPresentableText(presentable)
            .withTailText(" (in $where)", true).withStrikeoutness(type.obsolete).withInsertHandler(handler)
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        builder.putUserData(UNREFERENCED, source.origin)
        return PrioritizedLookupElement.withPriority(builder, UNREFERENCED_PRIORITY).also {
            it.putUserData(NativeCSharpCompletion.NATIVE, true)
            it.putUserData(UNREFERENCED, source.origin)
        }
    }

    /** A balloon with the one thing to do for the type to compile: add the package, or the project reference; nothing runs by itself. */
    private fun offerReference(project: Project, projectFile: VirtualFile?, source: Source, typeName: String) {
        val offer = when {
            source.package_ != null -> "Add package ${source.package_.first} ${source.package_.second}".trimEnd()
            source.project != null -> "Add reference to ${source.project.nameWithoutExtension}"
            else -> null
        }
        lastOfferForTests = offer ?: source.origin
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) return
        val projectName = projectFile?.nameWithoutExtension ?: "the project"
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification("`$typeName` is in ${source.origin}", "$projectName does not reference it yet.", NotificationType.INFORMATION)
        if (offer != null && projectFile != null) notification.addAction(NotificationAction.createSimpleExpiring(offer) { addReference(project, projectFile, source) })
        notification.notify(project)
    }

    private fun addReference(project: Project, projectFile: VirtualFile, source: Source) {
        val package_ = source.package_
        val other = source.project
        when {
            package_ != null -> NuGetService.getInstance(project).install(listOf(projectFile), package_.first, package_.second) {}
            other != null -> {
                val title = "Adding a reference to ${other.nameWithoutExtension}"
                val commands = DotNetCli.commandLinesOrNotify(project, title) { listOf(DotNetCli.commandLine(projectFile.parent.path, "add", projectFile.path, "reference", other.path)) } ?: return
                DotNetCli.runInBackground(project, title, commands, refresh = listOf(File(projectFile.path)))
            }
        }
    }

    private fun isSystem(namespace: String): Boolean = namespace == "System" || namespace.startsWith("System.")

    private fun icon(type: IndexedType): Icon = when (type.kind) {
        IndexedTypeKind.INTERFACE -> AllIcons.Nodes.Interface
        IndexedTypeKind.ENUM -> AllIcons.Nodes.Enum
        IndexedTypeKind.DELEGATE -> AllIcons.Nodes.Lambda
        IndexedTypeKind.STATIC_CLASS -> AllIcons.Nodes.Static
        else -> if (type.isStatic) AllIcons.Nodes.Static else if (type.isRecord) AllIcons.Nodes.Record else AllIcons.Nodes.Class
    }

    // ---- the smart list: chains

    /** The second Ctrl+Shift+Space adds the chains; the first one advertises them. */
    fun smart(parameters: CompletionParameters, place: NativeCSharpCompletionPlace, file: CSharpFile, result: CompletionResultSet) {
        if (place.kind != NativeCompletionKind.EXPRESSION) return
        if (!isSecond(parameters)) {
            if (parameters.invocationCount == 1) result.addLookupAdvertisement(smartAdvertisement())
            return
        }
        chains(place, file, result.prefixMatcher).forEach(result::addElement)
    }

    /**
     * `order.Customer`, `this.Repository.Current`-like rows one access deep: a member of each local, parameter and member of the enclosing
     * types (the roots) whose value is of the expected type; of the members, properties, fields and parameterless methods.
     */
    fun chains(place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher): List<LookupElement> {
        val analysis = NativeCSharpExpectedCompletion.Analysis(place, file)
        val expected = analysis.expected ?: return emptyList()
        if (analysis.pattern || analysis.expectedEnum != null) return emptyList()
        val r = analysis.resolver
        val name = place.name ?: return emptyList()
        val at = analysis.at
        val roots = ArrayList<Pair<String, SemanticType>>()
        for (symbol in NativeCSharpLocals.visible(r.syntax.scopes, place.leaf)) {
            if (symbol.kind != LocalSymbolKind.LOCAL && symbol.kind != LocalSymbolKind.PARAMETER && symbol.kind != LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER) continue
            r.valueType(CSharpSymbol.Local(symbol))?.let { roots += symbol.name to it }
        }
        val static = NativeCSharpLocals.inStaticContext(at)
        for (info in r.syntax.enclosingTypes(at)) for ((key, member) in r.syntax.membersOf(info)) {
            ProgressManager.checkCanceled()
            if ('<' in key || '`' in key || member.nestedType != null || static && !NativeCSharpMembers.isStatic(member)) continue
            val kind = NativeCSharpMembers.kind(member)
            if (kind != NativeCSharpMembers.Kind.PROPERTY && kind != NativeCSharpMembers.Kind.FIELD) continue
            val first = r.membersNamed(r.selfType(info), key, 0).firstOrNull() ?: continue
            r.valueType(first)?.let { roots += key to it }
        }
        val lookup = CSharpMemberLookup(r)
        val text = CSharpSymbolText(r)
        val result = ArrayList<LookupElement>()
        val seen = HashSet<String>()
        for ((root, type) in roots.take(MAX_CHAIN_ROOTS)) {
            if (type is SemanticType.Parameter || r.definitionName(type) == "System.String") continue
            for (entry in lookup.entries(CSharpNameResolver.Qualifier.Value(type), name)) {
                ProgressManager.checkCanceled()
                val symbol = entry.first
                if (symbol is CSharpSymbol.SourceType || symbol is CSharpSymbol.LibraryType || symbol is CSharpSymbol.Namespace || r.isExtension(symbol)) continue
                val method = r.isMethod(symbol)
                if (method && entry.symbols.none { r.signature(it, false)?.isEmpty() == true }) continue
                val chain = "$root.${entry.name}"
                if (!matcher.prefixMatches(chain) && !matcher.prefixMatches(entry.name)) continue
                val valueType = r.valueType(symbol) ?: continue
                if (!analysis.fits(valueType, expected) || !seen.add(chain)) continue
                result += chainRow(entry, chain, valueType, method, text)
                if (result.size >= MAX_CHAINS) return result
            }
        }
        return result
    }

    private fun chainRow(entry: CSharpMemberLookup.Entry, chain: String, type: SemanticType, method: Boolean, text: CSharpSymbolText): LookupElement {
        val icon = when {
            method -> AllIcons.Nodes.Method
            entry.first.let { it is CSharpSymbol.SourceMember && NativeCSharpMembers.kind(it.member) == NativeCSharpMembers.Kind.FIELD } -> AllIcons.Nodes.Field
            else -> AllIcons.Nodes.Property
        }
        var builder = LookupElementBuilder.create(chain).withLookupStrings(setOf(chain, entry.name)).withIcon(icon).withTypeText(type.minimalDisplay)
            .withPresentableText(chain)
        if (method) builder = builder.withTailText("()", true).withInsertHandler(NativeCSharpCalls.callHandler { entry.symbols.all(text::returnsNothing) to false })
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        builder.putUserData(CHAIN, chain)
        return PrioritizedLookupElement.withPriority(builder, CHAIN_PRIORITY).also {
            it.putUserData(NativeCSharpCompletion.NATIVE, true)
            it.putUserData(CHAIN, chain)
        }
    }
}
