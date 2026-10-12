package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.semantic.CSharpMemberLookup
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpRequiredMembers
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.settings.DotNetSettings

/**
 * "Mapping" completion (0.1.134): copying properties of one object into another, the DTO ↔ entity routine. Where the caret names a member
 * of an object initializer (`var dto = new UserDto { | }`, `return new Dto { Id = user.Id, | }`) or starts a statement under a block of
 * assignments to one target (`dto.Name = user.Name;⏎|`), every member the target still lets set gets the value at hand whose name is
 * like the member's and whose type converts to it: locals, parameters, the fields and properties of the enclosing type, and their
 * public fields and properties two deep (`user.Name`, `user.Profile.Email`). One row per member — `Name = user.Name,` in an initializer,
 * `dto.Name = user.Name;` as a statement — and one row [MAP_ALL] that writes every confident match at once, in the order the members
 * are declared, at the indentation of the caret line.
 *
 * Score of a candidate for a member ([similarity] of the names + the bonuses of [Candidate.total]): the same name, the same name ignoring
 * case, one name ending or starting with the words of the other (`UserId` ↔ `Id`), the Jaccard overlap of the camel-case words; a
 * bonus for the *mapping partner* — the object the neighbouring assignments of the same block copy from — and for an identical type;
 * the declaration order breaks ties. A member whose best candidate is not clearly the best (a tie: `Name` ↔ `FirstName` / `LastName`)
 * gets no row. The rows stand first when the context is clearly a mapping (an assignment with the partner already written, or an
 * empty initializer with an object at hand that fills two members confidently), else after the usual items. Pure PSI + the plugin's
 * semantics: it works in the plain build; off with `DotNetSettings.mappingCompletion`.
 */
object NativeCSharpMappingCompletion {
    const val MAP_ALL = "Map all remaining members from "
    const val TAIL = " map"

    /** Above every rule of the native list ([NativeCSharpCompletion.COMMON_CALL] is 300 but never stands with these) / below the keywords. */
    const val TOP = 110.0
    const val LOW = -1.0

    private val ROW: Key<Boolean> = Key.create("dotnet.csharp.mappingRow")
    private val TOP_ROW: Key<Boolean> = Key.create("dotnet.csharp.mappingTop")

    /** Candidates at [depth] 2 are visited at most this many times: `a.b.c` of every object at hand is a lot on a big type. */
    private const val MAX_VISITED = 400

    /** The least [similarity] a single row needs, and the least a member needs to go into the "map all" row. */
    private const val SINGLE_MIN = 0.5
    private const val CONFIDENT_MIN = 0.7

    private const val PARTNER_BONUS = 0.25

    fun isEnabled(): Boolean = DotNetSettings.getInstance().mappingCompletion

    fun isMappingRow(element: LookupElement): Boolean = flag(element, ROW)
    fun isTopRow(element: LookupElement): Boolean = flag(element, TOP_ROW)

    private fun flag(element: LookupElement, key: Key<Boolean>): Boolean {
        var current: LookupElement? = element
        while (current != null) {
            if (current.getUserData(key) == true) return true
            current = (current as? LookupElementDecorator<*>)?.delegate
        }
        return false
    }

    // ---- the places

    /** The rows at a member name of an object initializer; empty where the place is no initializer, the type is not known, or the setting is off. */
    fun initializerItems(analysis: NativeCSharpExpectedCompletion.Analysis): List<LookupElement> {
        if (!isEnabled()) return emptyList()
        return runCatching { initializerRows(analysis) }.onFailure { if (it is ProcessCanceledException) throw it }.getOrDefault(emptyList())
    }

    /** The rows at the start of a statement under assignments `target.Member = …;`; empty where there is no such block above the caret. */
    fun statementItems(analysis: NativeCSharpExpectedCompletion.Analysis): List<LookupElement> {
        if (!isEnabled()) return emptyList()
        return runCatching { statementRows(analysis) }.onFailure { if (it is ProcessCanceledException) throw it }.getOrDefault(emptyList())
    }

    private fun initializerRows(analysis: NativeCSharpExpectedCompletion.Analysis): List<LookupElement> {
        val name = analysis.place.name ?: return emptyList()
        val initializer = name.parent as? CSharpInitializerExpression ?: return emptyList()
        if (!initializer.expressions.contains(name)) return emptyList()
        val kind = initializer.node.elementType
        // `new Order { X|` is read as a collection initializer until `=` is typed
        if (kind != SyntaxKind.ObjectInitializerExpression && (kind != SyntaxKind.CollectionInitializerExpression || initializer.expressions.size > 1)) return emptyList()
        val creation = initializer.parent as? CSharpBaseObjectCreationExpression ?: return emptyList()
        val r = analysis.resolver
        val type = NativeCSharpObjectInitializers.createdType(creation, r) ?: return emptyList()
        val assigned = CSharpRequiredMembers.assignedNames(initializer)
        val partners = initializer.expressions.mapNotNull { (it as? CSharpAssignmentExpression)?.right?.let(::rootOf) }
        // the variable the creation is assigned to: `dto.Name` is no value for `new UserDto { Name = | }`
        val target = (creation.parent as? CSharpEqualsValueClause)?.parent?.let { it as? CSharpVariableDeclarator }?.identifier?.text
            ?: ((creation.parent as? CSharpAssignmentExpression)?.takeIf { it.right == creation }?.left?.let(::rootOf))
        val members = NativeCSharpObjectInitializers.settableSymbols(type, r, name, assigned)
        val matches = matches(r, name, members, partners, target, inStatement = false)
        if (matches.isEmpty()) return emptyList()
        val partner = partnerOf(matches, partners)
        val clear = partners.isNotEmpty() && partner != null || assigned.isEmpty() && confidentOf(matches, partner).let { it.size >= 2 || it.size == 1 && members.size == 1 }
        val priority = if (clear) TOP else LOW
        val rows = ArrayList<LookupElement>()
        for (m in matches) rows += row("${m.member} = ${m.best.path}", listOf(m.member), AllIcons.Nodes.Property, priority, m.best, INITIALIZER_ENTRY, clear)
        mapAll(matches, partner, members.size, priority, clear) { context, entries ->
            val text = context.document.charsSequence
            val multiLine = initializerIsMultiLine(initializer, text)
            val indent = lineIndent(text, context.startOffset)
            val joined = if (multiLine) entries.joinToString(",\n$indent", postfix = ",") else entries.joinToString(", ")
            joined to (!multiLine && needsComma(text, context.tailOffset))
        }?.let(rows::add)
        return rows
    }

    private fun statementRows(analysis: NativeCSharpExpectedCompletion.Analysis): List<LookupElement> {
        val name = analysis.place.name ?: return emptyList()
        val statement = NativeCSharpCompletionPlace.statementStart(name) ?: return emptyList()
        val siblings = when (val parent = statement.parent) {
            is CSharpBlock -> parent.statements
            is CSharpGlobalStatement -> (parent.parent as? CSharpCompilationUnit)?.members.orEmpty().mapNotNull { (it as? CSharpGlobalStatement)?.statement }
            else -> return emptyList()
        }
        val index = siblings.indexOf(statement).takeIf { it > 0 } ?: return emptyList()
        // the block of `target.Member = value;` right above the caret, contiguous, one target
        val r = analysis.resolver
        var targetText: String? = null
        var targetExpression: CSharpExpression? = null
        val assigned = HashSet<String>()
        val partners = ArrayList<String>()
        var i = index - 1
        while (i >= 0) {
            val assignment = ((siblings[i] as? CSharpExpressionStatement)?.expression as? CSharpAssignmentExpression)?.takeIf { it.operatorToken?.text == "=" } ?: break
            val left = assignment.left as? CSharpMemberAccessExpression ?: break
            val receiver = left.expression ?: break
            val text = receiver.text.filterNot(Char::isWhitespace)
            if (targetText == null) { targetText = text; targetExpression = receiver } else if (targetText != text) break
            left.nameElement?.identifier?.text?.let(assigned::add)
            assignment.right?.let(::rootOf)?.let(partners::add)
            i--
        }
        val target = targetText ?: return emptyList()
        val receiver = targetExpression ?: return emptyList()
        val type = r.typeOf(receiver)?.takeIf { it is SemanticType.Source || it is SemanticType.Library } ?: return emptyList()
        val members = NativeCSharpObjectInitializers.settableSymbols(type, r, name, assigned).filter { settableOutsideInitializer(it.second) }
        val matches = matches(r, name, members, partners, rootOf(receiver), inStatement = true)
        if (matches.isEmpty()) return emptyList()
        val partner = partnerOf(matches, partners)
        val clear = partner != null
        val priority = if (clear) TOP else LOW
        val rows = ArrayList<LookupElement>()
        for (m in matches) rows += row("$target.${m.member} = ${m.best.path};", listOf(m.member, "$target.${m.member}"), AllIcons.Nodes.Property, priority, m.best, null, clear)
        mapAll(matches, partner, members.size, priority, clear) { context, entries ->
            val text = context.document.charsSequence
            val indent = lineIndent(text, context.startOffset)
            entries.joinToString("\n$indent") { "$target.$it;" } to false
        }?.let(rows::add)
        return rows
    }

    /** A property with `set` (not `init` alone: that is for initializers and constructors), a field that is not `readonly`. */
    private fun settableOutsideInitializer(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.SourceMember -> when (val element = symbol.element) {
            is CSharpPropertyDeclaration -> element.accessorList?.accessors.orEmpty().any { it.keyword?.text == "set" }
            else -> true
        }
        is CSharpSymbol.LibraryMember -> symbol.member.kind != IndexedMemberKind.PROPERTY || symbol.member.hasSetter && !symbol.member.isInitOnly
        else -> false
    }

    // ---- the matching

    /** A value at hand: its text, its type, how deep ([depth] 0: a name, 1: `a.b`, 2: `a.b.c`), the name it starts with and whether that is a local. */
    class Candidate(val path: String, val root: String, val type: SemanticType, val depth: Int, val local: Boolean) {
        val leaf: String get() = path.substringAfterLast('.')
        /** Set by [matches]. */
        var similarity = 0.0
        var total = 0.0
    }

    /** A member with the value chosen for it; [confident]: the row "map all" takes it. */
    class Match(val member: String, val best: Candidate, val confident: Boolean)

    /**
     * For every member of [members] the best candidate among the values at hand at [site], when it is clearly the best: [similarity]
     * ≥ [SINGLE_MIN] and no other candidate scores the same. [partners]: the roots the neighbouring assignments copy from; [target]:
     * the root of the object being filled, never a value for itself.
     */
    fun matches(
        r: CSharpNameResolver, site: PsiElement, members: List<Pair<String, CSharpSymbol>>, partners: List<String>, target: String?, inStatement: Boolean,
    ): List<Match> {
        if (members.isEmpty()) return emptyList()
        val candidates = candidates(r, site, target)
        if (candidates.isEmpty()) return emptyList()
        val partnerSet = partners.toSet()
        val result = ArrayList<Match>()
        for ((member, symbol) in members) {
            ProgressManager.checkCanceled()
            val wanted = r.valueType(symbol) ?: continue
            var best: Candidate? = null
            var second: Candidate? = null
            for (candidate in candidates) {
                val similarity = similarity(member, candidate.leaf)
                if (similarity <= 0.0) continue
                val conversion = r.conversion(candidate.type, wanted)
                if (conversion != CSharpNameResolver.Conversion.IDENTITY && conversion != CSharpNameResolver.Conversion.IMPLICIT) continue
                val scored = Candidate(candidate.path, candidate.root, candidate.type, candidate.depth, candidate.local)
                scored.similarity = similarity
                scored.total = similarity + (if (candidate.root in partnerSet) PARTNER_BONUS else 0.0) + (if (conversion == CSharpNameResolver.Conversion.IDENTITY) 0.05 else 0.0) -
                    0.02 * candidate.depth + (if (candidate.local) 0.01 else 0.0)
                when {
                    best == null || scored.total > best.total -> { second = best; best = scored }
                    second == null || scored.total > second.total -> second = scored
                }
            }
            val chosen = best ?: continue
            if (chosen.similarity < SINGLE_MIN) continue
            if (second != null && second.total >= chosen.total) continue
            val confident = chosen.similarity >= CONFIDENT_MIN && (second == null || second.total < chosen.total - 0.1)
            result += Match(member, chosen, confident)
        }
        return result
    }

    /** The root most confident matches copy from ([partners] first when they do), null when no root fills a member confidently. */
    private fun partnerOf(matches: List<Match>, partners: List<String>): String? {
        val confident = matches.filter { it.confident }
        partners.firstOrNull { p -> confident.any { it.best.root == p } }?.let { return it }
        return confident.groupingBy { it.best.root }.eachCount().maxByOrNull { it.value }?.key
    }

    private fun confidentOf(matches: List<Match>, partner: String?): List<Match> = matches.filter { it.confident && it.best.root == partner }

    /**
     * The values at hand at [site]: locals and parameters, the fields and properties of the enclosing types (the instance ones only in an
     * instance context), then the public / internal instance fields and properties of each, two deep, of types of the solution or of an
     * indexed assembly — not of `System.*` types (`name.Length` is no value to map). [target] and what starts with it are left out.
     */
    fun candidates(r: CSharpNameResolver, site: PsiElement, target: String?): List<Candidate> {
        val roots = ArrayList<Candidate>()
        for (symbol in NativeCSharpLocals.visible(r.syntax.scopes, site)) {
            if (symbol.kind != LocalSymbolKind.LOCAL && symbol.kind != LocalSymbolKind.PARAMETER && symbol.kind != LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER) continue
            if (symbol.name == target) continue
            val type = r.valueType(CSharpSymbol.Local(symbol)) ?: continue
            roots += Candidate(symbol.name, symbol.name, type, 0, local = true)
        }
        val static = NativeCSharpLocals.inStaticContext(site)
        for (info in r.syntax.enclosingTypes(site)) for ((key, m) in r.syntax.membersOf(info)) {
            ProgressManager.checkCanceled()
            if ('<' in key || '`' in key || m.nestedType != null || key == target || roots.any { it.path == key }) continue
            val kind = NativeCSharpMembers.kind(m)
            if (kind != NativeCSharpMembers.Kind.FIELD && kind != NativeCSharpMembers.Kind.PROPERTY && kind != NativeCSharpMembers.Kind.CONSTANT) continue
            if (static && !NativeCSharpMembers.isStatic(m)) continue
            val type = r.membersNamed(r.selfType(info), key, 0).firstOrNull()?.let(r::valueType) ?: continue
            roots += Candidate(key, key, type, 0, local = false)
        }
        val all = ArrayList(roots)
        var level: List<Candidate> = roots
        var visited = 0
        val lookup = CSharpMemberLookup(r)
        for (depth in 1..2) {
            val next = ArrayList<Candidate>()
            for (owner in level) {
                if (!expandable(r, owner.type)) continue
                for (entry in lookup.entries(CSharpNameResolver.Qualifier.Value(owner.type), site)) {
                    ProgressManager.checkCanceled()
                    if (++visited > MAX_VISITED) break
                    if (entry.inaccessible) continue
                    val symbol = entry.first
                    val readable = when (symbol) {
                        is CSharpSymbol.SourceMember -> NativeCSharpMembers.kind(symbol.member).let { it == NativeCSharpMembers.Kind.FIELD || it == NativeCSharpMembers.Kind.PROPERTY } &&
                            !NativeCSharpMembers.isStatic(symbol.member) && symbol.member.modifiers.any { it == "public" || it == "internal" }
                        is CSharpSymbol.LibraryMember -> !symbol.member.isStatic &&
                            (symbol.member.kind == IndexedMemberKind.FIELD || symbol.member.kind == IndexedMemberKind.PROPERTY && symbol.member.hasGetter)
                        else -> false
                    }
                    if (!readable) continue
                    val type = r.valueType(symbol) ?: continue
                    next += Candidate("${owner.path}.${entry.name}", owner.root, type, depth, owner.local)
                }
            }
            all += next
            level = next
        }
        return all
    }

    /** A type of the solution or of an assembly that is not `System.*`: its members are values to map, `string.Length` is not. */
    private fun expandable(r: CSharpNameResolver, type: SemanticType): Boolean {
        if (type !is SemanticType.Source && type !is SemanticType.Library) return false
        if (type is SemanticType.Source && type.info.kind == TypeKind.ENUM) return false
        val name = r.definitionName(type) ?: return false
        return !name.startsWith("System.")
    }

    /**
     * How alike two member names are, 0 to 1: the same 1, the same ignoring case 0.95, the words of one at the end or the start of the
     * other (`UserId` ↔ `Id`, `Name` ↔ `FullName`) 0.7, otherwise 0.3 + 0.4 × the Jaccard overlap of the camel-case words when it is
     * more than a third (`FirstName` ↔ `LastName` is not), else 0. Prefixes `_`, `m_`, `@` do not count.
     */
    fun similarity(wanted: String, candidate: String): Double {
        val a = bare(wanted)
        val b = bare(candidate)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.equals(b, ignoreCase = true)) return 0.95
        val wa = CSharpNameLikeness.words(a)
        val wb = CSharpNameLikeness.words(b)
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        val shorter = if (wa.size <= wb.size) wa else wb
        val longer = if (wa.size <= wb.size) wb else wa
        // `i`, `x`: a one-letter word is no evidence as a part
        if (shorter.size < longer.size && shorter.all { it.length >= 2 } && (longer.takeLast(shorter.size) == shorter || longer.take(shorter.size) == shorter)) return 0.7
        val common = wa.toSet().intersect(wb.toSet()).count { it.length >= 2 }
        val union = (wa.toSet() + wb.toSet()).size
        val jaccard = if (union == 0) 0.0 else common.toDouble() / union
        return if (jaccard > 1.0 / 3) 0.3 + 0.4 * jaccard else 0.0
    }

    private fun bare(name: String): String = name.removePrefix("@").removePrefix("m_").trimStart('_')

    /** The leftmost name of a member chain: `user.Profile.Email` → `user`, `this.user` → `user`; null for anything else (a call, a literal). */
    fun rootOf(expression: CSharpExpression): String? {
        var current: CSharpExpression = expression
        while (true) {
            current = when (current) {
                is CSharpMemberAccessExpression -> {
                    val inner = current.expression ?: return null
                    if (inner is CSharpThisExpression) return current.nameElement?.identifier?.text
                    inner
                }
                is CSharpParenthesizedExpression -> current.expression ?: return null
                is CSharpIdentifierName -> return current.identifier?.text
                else -> return null
            }
        }
    }

    // ---- the rows

    private fun row(lookup: String, alternatives: List<String>, icon: javax.swing.Icon, priority: Double, value: Candidate, handler: InsertHandler<LookupElement>?, top: Boolean): LookupElement {
        var builder = LookupElementBuilder.create(lookup).withIcon(icon).withLookupStrings(alternatives).withTailText(TAIL, true)
        if (value.depth > 0) builder = builder.withTypeText("from ${value.root}", true)
        if (handler != null) builder = builder.withInsertHandler(handler)
        return finish(builder, priority, top)
    }

    /**
     * The row "Map all remaining members from X" when at least two members are confidently filled from [partner] (one: its own row does
     * it). [write] gives the text for the entries (`Name = user.Name` each) and whether a `,` goes after it.
     */
    private fun mapAll(
        matches: List<Match>, partner: String?, remaining: Int, priority: Double, top: Boolean,
        write: (InsertionContext, List<String>) -> Pair<String, Boolean>,
    ): LookupElement? {
        if (partner == null) return null
        val confident = confidentOf(matches, partner)
        if (confident.size < 2) return null
        val entries = confident.map { "${it.member} = ${it.best.path}" }
        val shown = confident.joinToString(", ") { it.member }.let { if (it.length > 60) it.take(57) + "…" else it }
        val builder = LookupElementBuilder.create(MAP_ALL + partner).withIcon(AllIcons.Actions.Lightning).bold().withLookupString("map")
            .withTailText(" $shown$TAIL", true)
            .withTypeText(if (confident.size == remaining) "all $remaining" else "${confident.size} of $remaining", true)
            .withInsertHandler { context, _ ->
                val document = context.document
                document.deleteString(context.startOffset, context.tailOffset)
                val (text, comma) = write(context, entries)
                val inserted = if (comma) "$text," else text
                document.insertString(context.startOffset, inserted)
                context.editor.caretModel.moveToOffset(context.startOffset + inserted.length)
                context.commitDocument()
            }
        return finish(builder, priority, top)
    }

    private fun finish(builder: LookupElementBuilder, priority: Double, top: Boolean): LookupElement {
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        builder.putUserData(ROW, true)
        if (top) builder.putUserData(TOP_ROW, true)
        val row = PrioritizedLookupElement.withPriority(builder, priority)
        row.putUserData(NativeCSharpCompletion.NATIVE, true)
        row.putUserData(ROW, true)
        if (top) row.putUserData(TOP_ROW, true)
        return NativeCSharpMlInfo.attach(row, NativeCSharpMlInfo.OTHER, priority)
    }

    /** `Name = user.Name` in an initializer: `,` after it unless one or the `}` follows on the line (a trailing comma on a line of its own is legal and invites the next member). */
    private val INITIALIZER_ENTRY = InsertHandler<LookupElement> { context, _ ->
        val text = context.document.charsSequence
        if (!needsComma(text, context.tailOffset)) return@InsertHandler
        context.document.insertString(context.tailOffset, ",")
        context.editor.caretModel.moveToOffset(context.tailOffset)
        context.commitDocument()
    }

    private fun needsComma(text: CharSequence, offset: Int): Boolean {
        var next = offset
        while (next < text.length && (text[next] == ' ' || text[next] == '\t')) next++
        val c = text.getOrNull(next)
        return c != ',' && c != '}'
    }

    private fun initializerIsMultiLine(initializer: CSharpInitializerExpression, text: CharSequence): Boolean {
        val open = initializer.openBraceToken?.textRange?.startOffset ?: return false
        val close = initializer.closeBraceToken?.textRange?.startOffset ?: return false
        return text.subSequence(open, minOf(close, text.length)).contains('\n')
    }

    private fun lineIndent(text: CharSequence, offset: Int): String {
        val start = if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        var end = start
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.subSequence(start, end).toString()
    }
}
