package io.github.dotnetsupport.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.SemanticType
import javax.swing.JComponent

/**
 * Rider's hierarchy refactorings of Refactor This (Jellyfin № 53, № 55): Pull Members Up, Push Members Down, Use Base Type Where Possible.
 * The plans come from the tree and the resolver in a read action and are tested without the dialogs; the rows ask, then apply a plan in
 * one command. Conflicts (a member left behind that a moved one uses, a base that uses a pushed member, a name already declared) are
 * listed before anything changes, as Rider lists them.
 */
object NativeCSharpHierarchyEdits {
    /** Tests: what the dialogs answer — the target type, the members chosen, those made abstract, the answer to the conflicts. */
    class Answer(val target: String? = null, val members: List<String> = emptyList(), val abstract: Set<String> = emptySet(), val confirm: Boolean = true)

    @Volatile var answer: Answer? = null

    /** Replacements per file, the `using` directives a file needs, the conflicts found and the places changed. */
    class Plan {
        val edits = LinkedHashMap<VirtualFile, MutableList<CSharpTextEdit>>()
        val usings = LinkedHashMap<VirtualFile, MutableSet<String>>()
        val conflicts = ArrayList<String>()
        val places = ArrayList<String>()

        fun edit(element: PsiElement, range: TextRange, text: String) {
            edits.getOrPut(fileOf(element)) { ArrayList() } += CSharpTextEdit(range, text)
        }

        fun using(element: PsiElement, namespace: String) {
            if (namespace.isNotEmpty()) usings.getOrPut(fileOf(element)) { LinkedHashSet() } += namespace
        }
    }

    private fun fileOf(element: PsiElement): VirtualFile = element.containingFile.viewProvider.virtualFile

    /** [plan] in one command: the replacements of each file from its end (overlapping deletions merged), then its `using` directives. */
    fun apply(project: Project, plan: Plan, title: String) {
        WriteCommandAction.writeCommandAction(project).withName(title).run<RuntimeException> {
            val documents = FileDocumentManager.getInstance()
            for ((file, edits) in plan.edits) {
                val document = documents.getDocument(file) ?: continue
                for (edit in merged(edits, document.charsSequence).sortedByDescending { it.range.startOffset }) document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
            }
            for ((file, namespaces) in plan.usings) {
                val document = documents.getDocument(file) ?: continue
                for (namespace in namespaces) {
                    if (CSharpUsings.isVisible(namespace, document.charsSequence)) continue
                    CSharpUsings.insertion(document.charsSequence, namespace)?.let { document.insertString(it.offset, it.text) }
                }
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    /**
     * Deletions that overlap or touch (members side by side) become one; one right after a `{` takes the blank line after it too, so the
     * body does not start with an empty line.
     */
    private fun merged(edits: List<CSharpTextEdit>, text: CharSequence): List<CSharpTextEdit> {
        val result = ArrayList<CSharpTextEdit>()
        for (edit in edits.sortedWith(compareBy({ it.range.startOffset }, { it.range.endOffset }))) {
            val last = result.lastOrNull()
            if (last != null && last.text.isEmpty() && edit.text.isEmpty() && edit.range.startOffset <= last.range.endOffset) {
                result[result.size - 1] = CSharpTextEdit(TextRange(last.range.startOffset, maxOf(last.range.endOffset, edit.range.endOffset)), "")
            } else result += edit
        }
        return result.map { edit ->
            if (edit.text.isNotEmpty() || edit.range.startOffset == 0) return@map edit
            val before = text.subSequence(lineStart(text, edit.range.startOffset - 1), edit.range.startOffset).trim()
            val end = edit.range.endOffset
            val next = lineEnd(text, end)
            if (before.endsWith("{") && end < text.length && text.subSequence(end, next).isBlank() && next < text.length) {
                CSharpTextEdit(TextRange(edit.range.startOffset, next + 1), "")
            } else edit
        }
    }

    // ---- the members

    private val ACCESS = setOf("public", "private", "protected", "internal")

    fun nameOf(member: CSharpMemberDeclaration): String? = when (member) {
        is CSharpBaseFieldDeclaration -> member.declaration?.variables?.mapNotNull { it.identifier?.text }?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        else -> CSharpDeclarationNames.nameElement(member)?.text
    }

    private fun has(member: CSharpMemberDeclaration, modifier: String): Boolean = member.modifiers.any { it.text == modifier }

    private fun movable(member: CSharpMemberDeclaration): Boolean = nameOf(member) != null && when (member) {
        is CSharpMethodDeclaration -> member.explicitInterfaceSpecifier == null
        is CSharpPropertyDeclaration -> member.explicitInterfaceSpecifier == null
        is CSharpEventDeclaration -> member.explicitInterfaceSpecifier == null
        is CSharpFieldDeclaration, is CSharpEventFieldDeclaration -> true
        else -> false
    }

    /** The members Pull Members Up offers: methods, properties, fields, events, not overrides (the base declares those already). */
    fun pullCandidates(type: CSharpTypeDeclaration): List<CSharpMemberDeclaration> = type.members.filter { movable(it) && !has(it, "override") }

    /** The members Push Members Down offers: instance members that are not abstract (a copy needs a body). */
    fun pushCandidates(type: CSharpTypeDeclaration): List<CSharpMemberDeclaration> =
        type.members.filter { movable(it) && !has(it, "abstract") && !has(it, "static") && !has(it, "const") }

    /** Whether [member] can be declared abstract (pulled up as a declaration, kept as one when pushed down): methods and properties. */
    fun abstractable(member: CSharpMemberDeclaration): Boolean =
        (member is CSharpMethodDeclaration || member is CSharpPropertyDeclaration) && !has(member, "static")

    /** The class (or struct, record, interface) whose header holds [offset], with the member whose header it is (null on the type's header). */
    fun siteAt(file: CSharpFile, offset: Int): Pair<CSharpTypeDeclaration, CSharpMemberDeclaration?>? {
        for (candidate in listOf(offset, offset - 1)) {
            val leaf = file.findElementAt(candidate.coerceAtLeast(0)) ?: continue
            val member = PsiTreeUtil.getParentOfType(leaf, CSharpMemberDeclaration::class.java, false) ?: continue
            if (member is CSharpTypeDeclaration) {
                val brace = member.openBraceToken?.takeIf { it.textLength > 0 } ?: continue
                if (offset <= brace.textRange.startOffset) return member to null
                continue
            }
            val type = member.parent as? CSharpTypeDeclaration ?: continue
            val bodyStart = when (member) {
                is CSharpBaseMethodDeclaration -> (member.body ?: member.expressionBody)?.textRange?.startOffset
                is CSharpPropertyDeclaration -> (member.accessorList ?: member.expressionBody)?.textRange?.startOffset
                is CSharpBasePropertyDeclaration -> member.accessorList?.textRange?.startOffset
                else -> null
            } ?: member.textRange.endOffset
            if (offset <= bodyStart) return type to member
        }
        return null
    }

    // ---- text helpers

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }

    private fun lineEnd(text: CharSequence, offset: Int): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it }

    private fun indentAt(text: CharSequence, offset: Int): String {
        val start = lineStart(text, offset)
        var end = start
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.subSequence(start, end).toString()
    }

    /** Where [member] starts with its `///` comment (attributes are inside the member already). */
    private fun fullStart(member: PsiElement): Int {
        var start = member.textRange.startOffset
        var previous = member.prevSibling
        while (previous != null) {
            when {
                previous is PsiWhiteSpace -> if (previous.text.count { it == '\n' } > 1) break
                previous is PsiComment && previous.text.startsWith("///") -> start = previous.textRange.startOffset
                else -> break
            }
            previous = previous.prevSibling
        }
        return start
    }

    /** The lines of [member] (its comment too) and one blank line next to them: before it when there is one, else after it. */
    private fun removal(text: CharSequence, member: PsiElement): TextRange {
        var start = lineStart(text, fullStart(member))
        var end = lineEnd(text, member.textRange.endOffset)
        if (end < text.length) end++
        val previous = if (start > 0) lineStart(text, start - 1) else -1
        if (previous >= 0 && text.subSequence(previous, start).isBlank()) start = previous
        else if (end < text.length) {
            val next = lineEnd(text, end)
            if (text.subSequence(end, next).isBlank()) end = if (next < text.length) next + 1 else next
        }
        return TextRange(start, end)
    }

    /** [text] moved from lines indented by [from] to lines indented by [to]. */
    private fun reindent(text: String, from: String, to: String): String = text.lines().joinToString("\n") { line ->
        when {
            line.isBlank() -> ""
            line.startsWith(from) -> to + line.substring(from.length)
            else -> to + line.trimStart()
        }
    }

    /** The text of [member] with its comment from the start of its first line, [edits] (in file offsets, inside it) applied. */
    private fun memberText(text: CharSequence, member: PsiElement, edits: List<CSharpTextEdit> = emptyList()): String {
        val start = lineStart(text, fullStart(member))
        val builder = StringBuilder(text.subSequence(start, member.textRange.endOffset))
        for (edit in edits.sortedByDescending { it.range.startOffset }) builder.replace(edit.range.startOffset - start, edit.range.endOffset - start, edit.text)
        return builder.toString()
    }

    /** The indent of a member of [type]: its first member's, else one unit more than the type's. */
    private fun memberIndent(type: CSharpTypeDeclaration): String {
        val text = type.containingFile.text
        type.members.firstOrNull()?.let { return indentAt(text, it.textRange.startOffset) }
        return indentAt(text, type.textRange.startOffset) + NativeCSharpContextEdits.unit(type.containingFile)
    }

    /** [blocks] (already at the indent of a member of [type]) at the end of [type], a blank line between them and its last member. */
    private fun insertAtEnd(plan: Plan, type: CSharpTypeDeclaration, blocks: List<String>) {
        if (blocks.isEmpty()) return
        val text = type.containingFile.text
        val open = type.openBraceToken ?: return
        val close = type.closeBraceToken?.takeIf { it.textLength > 0 } ?: return
        val body = blocks.joinToString("\n\n")
        val closeLine = lineStart(text, close.textRange.startOffset)
        when {
            closeLine <= open.textRange.startOffset ->
                plan.edit(type, TextRange(open.textRange.endOffset, close.textRange.startOffset), "\n$body\n${indentAt(text, type.textRange.startOffset)}")
            type.members.isEmpty() -> plan.edit(type, TextRange(closeLine, closeLine), "$body\n")
            else -> plan.edit(type, TextRange(closeLine, closeLine), "\n$body\n")
        }
    }

    /** `abstract` before the keyword of [type] when it has none (an abstract member needs an abstract class). */
    private fun makeAbstract(plan: Plan, type: CSharpTypeDeclaration) {
        if (has(type, "abstract")) return
        if (has(type, "sealed") || has(type, "static")) {
            plan.conflicts += "'${type.identifier?.text}' is ${if (has(type, "sealed")) "sealed" else "static"}: it cannot become abstract"
            return
        }
        val keyword = type.keyword ?: return
        plan.edit(type, TextRange(keyword.textRange.startOffset, keyword.textRange.startOffset), "abstract ")
    }

    // ---- what a member uses

    private fun identifiers(element: PsiElement, range: TextRange? = null): List<PsiElement> =
        PsiTreeUtil.collectElements(element) { CSharpLeaves.isIdentifier(it) && (range == null || range.contains(it.textRange)) }.toList()

    /** The namespaces of the types [element] names (inside [range]): what a file it goes to has to import. */
    private fun namespacesUsed(element: PsiElement, r: CSharpNameResolver, range: TextRange? = null): Set<String> {
        val found = LinkedHashSet<String>()
        for (leaf in identifiers(element, range)) {
            for (symbol in r.resolve(leaf)?.symbols.orEmpty()) {
                when (symbol) {
                    is CSharpSymbol.SourceType -> symbol.info.qualifiedName.substringBeforeLast('.', "").let { if (it.isNotEmpty()) found += it }
                    is CSharpSymbol.LibraryType -> symbol.type.namespace.let { if (it.isNotEmpty()) found += it }
                    else -> {}
                }
            }
        }
        return found
    }

    /** The member declaration [element] (a declarator, a member) belongs to. */
    private fun memberOf(element: PsiElement): CSharpMemberDeclaration? =
        if (element is CSharpMemberDeclaration) element else PsiTreeUtil.getParentOfType(element, CSharpMemberDeclaration::class.java, true)

    /** The names of the members of [type] outside [moving] that [member] uses: they stay behind, the target cannot see them. */
    private fun leftBehind(member: CSharpMemberDeclaration, type: CSharpTypeDeclaration, moving: Set<CSharpMemberDeclaration>, r: CSharpNameResolver): Set<String> {
        val names = LinkedHashSet<String>()
        for (leaf in identifiers(member)) {
            for (symbol in r.resolve(leaf)?.symbols.orEmpty()) {
                val element = (symbol as? CSharpSymbol.SourceMember)?.element ?: continue
                val owner = memberOf(element) ?: continue
                if (owner.parent == type && owner !in moving && owner != member) names += leaf.text
            }
        }
        return names
    }

    /** Whether [type] uses [member] outside it (its rest, other members). */
    private fun usedInRest(member: CSharpMemberDeclaration, type: CSharpTypeDeclaration, r: CSharpNameResolver, except: Set<CSharpMemberDeclaration> = emptySet()): Boolean {
        val names = (nameOf(member) ?: return false).split(", ").toSet()
        return identifiers(type).any { leaf ->
            leaf.text in names && !PsiTreeUtil.isAncestor(member, leaf, false) && except.none { PsiTreeUtil.isAncestor(it, leaf, false) } &&
                r.resolve(leaf)?.symbols.orEmpty().any { symbol -> symbol.declarations.any { PsiTreeUtil.isAncestor(member, it, false) } }
        }
    }

    private fun declares(type: CSharpTypeDeclaration, name: String): Boolean = type.members.any { m -> nameOf(m)?.split(", ")?.contains(name) == true }

    // ---- the signature of a member, for an abstract declaration or an interface

    /** The accessibility written on [member] (`private` when none). */
    private fun access(member: CSharpMemberDeclaration): String = member.modifiers.filter { it.text in ACCESS }.joinToString(" ") { it.text }.ifEmpty { "private" }

    /** `T Name(params)` / `T Name { get; set; }` / `event T Name`, without modifiers and body; the `;` it needs is added. */
    private fun declaration(member: CSharpMemberDeclaration): String? {
        val text = member.containingFile.text
        return when (member) {
            is CSharpMethodDeclaration -> {
                val start = member.returnType?.textRange?.startOffset ?: return null
                val end = (member.constraintClauses.lastOrNull() ?: member.parameterList)?.textRange?.endOffset ?: return null
                text.substring(start, end).replace(Regex("\\s*\\n\\s*"), " ") + ";"
            }
            is CSharpPropertyDeclaration -> {
                val start = member.type?.textRange?.startOffset ?: return null
                val end = member.identifier?.textRange?.endOffset ?: return null
                val accessors = member.accessorList?.accessors?.filter { a -> a.modifiers.none { it.text == "private" } }?.mapNotNull { it.keyword?.text }
                    ?.takeIf { it.isNotEmpty() } ?: listOf("get")
                text.substring(start, end) + " { " + accessors.joinToString(" ") { "$it;" } + " }"
            }
            is CSharpEventFieldDeclaration -> member.declaration?.text?.let { "event $it;" }
            else -> null
        }
    }

    /** The element after the modifiers of [member]: its type (where `override ` / `protected ` go). */
    private fun afterModifiers(member: CSharpMemberDeclaration): PsiElement? = when (member) {
        is CSharpMethodDeclaration -> member.returnType
        is CSharpBasePropertyDeclaration -> member.type
        is CSharpBaseFieldDeclaration -> member.declaration
        else -> null
    }

    /** The edits that make [member] `override` (of an abstract declaration of [accessibility]): `virtual` → `override`, `private` → `protected`. */
    private fun overrideEdits(member: CSharpMemberDeclaration, accessibility: String): List<CSharpTextEdit> {
        val edits = ArrayList<CSharpTextEdit>()
        val at = afterModifiers(member)?.textRange?.startOffset ?: return edits
        var insert = ""
        member.modifiers.firstOrNull { it.text == "private" }?.let { edits += CSharpTextEdit(it.textRange, "protected") }
        if (member.modifiers.none { it.text in ACCESS }) insert += "$accessibility "
        val virtual = member.modifiers.firstOrNull { it.text == "virtual" }
        if (virtual != null) edits += CSharpTextEdit(virtual.textRange, "override") else insert += "override "
        if (insert.isNotEmpty()) edits += CSharpTextEdit(TextRange(at, at), insert)
        return edits
    }

    private fun abstractAccess(member: CSharpMemberDeclaration): String = access(member).replace("private", "protected")

    // ---- Pull Members Up

    /** A base type of the solution a member can go to: a class or an interface, its first part. */
    class Base(val name: String, val declaration: CSharpTypeDeclaration, val isInterface: Boolean)

    fun bases(type: CSharpTypeDeclaration, session: CSharpSemanticSession): List<Base> =
        CSharpSolutionSearch.supertypes(type, session).mapNotNull { supertype ->
            val source = supertype as? SemanticType.Source ?: return@mapNotNull null
            val declaration = source.info.targets().filterIsInstance<CSharpTypeDeclaration>().firstOrNull() ?: return@mapNotNull null
            Base(source.name, declaration, source.info.kind == TypeKind.INTERFACE)
        }

    /**
     * The plan of moving [chosen] members of [type] to [base] (Rider's Pull Members Up): to a class — the member itself (a `private` one
     * used by what stays becomes `protected`), or an abstract declaration with the member made `override` ([abstract]; the base made
     * abstract); to an interface — the declaration, the member stays and implements it. The `using` directives the moved text needs go to
     * the base's file.
     */
    fun pullUp(type: CSharpTypeDeclaration, base: Base, chosen: List<CSharpMemberDeclaration>, abstract: Set<CSharpMemberDeclaration>, session: CSharpSemanticSession): Plan {
        val plan = Plan()
        val file = type.containingFile as CSharpFile
        val r = session.resolver(file)
        val text = file.text
        val target = base.declaration
        val indent = memberIndent(target)
        val blocks = ArrayList<String>()
        val moving = chosen.filter { !base.isInterface && it !in abstract }.toSet()
        var anyAbstract = false
        for (member in chosen) {
            val name = nameOf(member) ?: continue
            if (name.split(", ").any { declares(target, it) }) {
                plan.conflicts += "'${base.name}' already declares '$name'"
                continue
            }
            val from = indentAt(text, member.textRange.startOffset)
            when {
                base.isInterface -> {
                    if (!has(member, "public")) plan.conflicts += "'$name' is not public: it cannot implement a member of '${base.name}'"
                    if (has(member, "static")) plan.conflicts += "'$name' is static: an interface member it would implement is not"
                    val declaration = declaration(member) ?: continue.also { plan.conflicts += "'$name' cannot be declared in an interface" }
                    blocks += indent + declaration
                    namespacesUsed(member, r, signatureRange(member)).forEach { plan.using(target, it) }
                }
                member in abstract && abstractable(member) -> {
                    anyAbstract = true
                    val accessibility = abstractAccess(member)
                    val declaration = declaration(member) ?: continue
                    blocks += "$indent$accessibility abstract $declaration"
                    overrideEdits(member, accessibility).forEach { plan.edit(member, it.range, it.text) }
                    namespacesUsed(member, r, signatureRange(member)).forEach { plan.using(target, it) }
                }
                else -> {
                    leftBehind(member, type, moving, r).forEach { plan.conflicts += "'$name' uses '$it', which stays in '${type.identifier?.text}'" }
                    val edits = ArrayList<CSharpTextEdit>()
                    if (usedInRest(member, type, r, moving)) member.modifiers.firstOrNull { it.text == "private" }?.let { edits += CSharpTextEdit(it.textRange, "protected") }
                    if (member.modifiers.none { it.text in ACCESS } && usedInRest(member, type, r, moving)) {
                        afterModifiers(member)?.textRange?.startOffset?.let { edits += CSharpTextEdit(TextRange(it, it), "protected ") }
                    }
                    blocks += reindent(memberText(text, member, edits), from, indent)
                    plan.edit(member, removal(text, member), "")
                    namespacesUsed(member, r).forEach { plan.using(target, it) }
                }
            }
            plan.places += "$name → ${base.name}"
        }
        if (anyAbstract) makeAbstract(plan, target)
        insertAtEnd(plan, target, blocks)
        return plan
    }

    /** The header of [member] (its declaration without the body). */
    private fun signatureRange(member: CSharpMemberDeclaration): TextRange {
        val end = when (member) {
            is CSharpBaseMethodDeclaration -> (member.body ?: member.expressionBody)?.textRange?.startOffset
            is CSharpPropertyDeclaration -> (member.accessorList ?: member.expressionBody)?.textRange?.startOffset
            else -> null
        } ?: member.textRange.endOffset
        return TextRange(member.textRange.startOffset, end)
    }

    // ---- Push Members Down

    fun derivedTypes(type: CSharpTypeDeclaration, project: Project, session: CSharpSemanticSession): List<CSharpTypeDeclaration> {
        val target = CSharpSolutionSearch.targetOf(type) ?: return emptyList()
        return CSharpSolutionSearch.directSubtypes(project, target, session).filterIsInstance<CSharpTypeDeclaration>()
    }

    /**
     * The plan of moving [chosen] members of [type] into its direct derived types of the solution (Rider's Push Members Down): a copy in
     * each, the member gone from [type] — or, for [keepAbstract], an abstract declaration left in [type] (made abstract) and the copies
     * made `override`. Conflicts: [type] itself uses the member (when it goes), a derived type declares the name already (no copy there).
     */
    fun pushDown(type: CSharpTypeDeclaration, derived: List<CSharpTypeDeclaration>, chosen: List<CSharpMemberDeclaration>, keepAbstract: Set<CSharpMemberDeclaration>,
                 session: CSharpSemanticSession): Plan {
        val plan = Plan()
        val file = type.containingFile as CSharpFile
        val r = session.resolver(file)
        val text = file.text
        if (derived.isEmpty()) plan.conflicts += "'${type.identifier?.text}' has no derived types in the solution"
        val blocks = LinkedHashMap<CSharpTypeDeclaration, MutableList<String>>()
        var anyAbstract = false
        val chosenSet = chosen.toSet()
        for (member in chosen) {
            val name = nameOf(member) ?: continue
            val from = indentAt(text, member.textRange.startOffset)
            val abstract = member in keepAbstract && abstractable(member)
            if (!abstract && usedInRest(member, type, r, chosenSet)) plan.conflicts += "'${type.identifier?.text}' uses '$name', which goes to the derived types"
            val copyEdits = if (abstract) overrideEdits(member, abstractAccess(member)) else emptyList()
            val copy = memberText(text, member, copyEdits)
            val namespaces = namespacesUsed(member, r)
            for (sub in derived) {
                val subName = sub.identifier?.text
                if (name.split(", ").any { declares(sub, it) }) {
                    plan.conflicts += "'$subName' already declares '$name'"
                    continue
                }
                blocks.getOrPut(sub) { ArrayList() } += reindent(copy, from, memberIndent(sub))
                namespaces.forEach { plan.using(sub, it) }
                plan.places += "$name → $subName"
            }
            if (abstract) {
                anyAbstract = true
                val declaration = declaration(member) ?: continue
                val start = lineStart(text, fullStart(member))
                plan.edit(member, TextRange(start, member.textRange.endOffset), "$from${abstractAccess(member)} abstract $declaration")
            } else plan.edit(member, removal(text, member), "")
        }
        if (anyAbstract) makeAbstract(plan, type)
        for ((sub, list) in blocks) insertAtEnd(plan, sub, list)
        return plan
    }

    // ---- Use Base Type Where Possible

    /** A declaration of a variable of the type: its written type, a description for the confirmation, the base types that fit all its uses. */
    class Place(val typeElement: PsiElement, val description: String, val fits: List<SemanticType>)

    /** The base types and interfaces of [type], the nearer first, `object` left out. */
    fun baseTypes(type: CSharpTypeDeclaration, session: CSharpSemanticSession): List<SemanticType> {
        val found = LinkedHashMap<String, SemanticType>()
        val queue = ArrayDeque<PsiElement>(listOf(type))
        val seen = HashSet<PsiElement>()
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            for (supertype in CSharpSolutionSearch.supertypes(next, session)) {
                val key = supertype.display ?: supertype.name
                if (key == "object" || key == "System.Object") continue
                found.putIfAbsent(key, supertype)
                (supertype as? SemanticType.Source)?.info?.targets()?.firstOrNull()?.let { queue += it }
            }
        }
        return found.values.toList()
    }

    /**
     * Rider's Use Base Type Where Possible: the parameters, locals and fields declared as [type] (found by the usages of the type across
     * the solution), each with the base types of [type] every use of the variable allows — a member of that base, a null check, an
     * assignment to it. A use that passes the variable on (an argument, a returned value) keeps the type.
     */
    fun useBaseType(type: CSharpTypeDeclaration, project: Project, session: CSharpSemanticSession): List<Place> {
        if (type.typeParameterList != null) return emptyList()
        val candidates = baseTypes(type, session)
        if (candidates.isEmpty()) return emptyList()
        val target = CSharpSolutionSearch.targetOf(type) ?: return emptyList()
        val places = ArrayList<Place>()
        for (usage in CSharpSolutionSearch.usages(project, target, GlobalSearchScope.projectScope(project), session)) {
            ProgressManager.checkCanceled()
            val name = usage.leaf.parent as? CSharpIdentifierName ?: continue
            val file = name.containingFile as? CSharpFile ?: continue
            val r = session.resolver(file)
            val holder = name.parent
            val (variables, kind) = when {
                holder is CSharpVariableDeclaration && holder.type == name ->
                    holder.variables.mapNotNull { it.identifier } to (if (holder.parent is CSharpBaseFieldDeclaration) "field" else "local")
                holder is CSharpParameter && holder.type == name -> listOfNotNull(holder.identifier) to "parameter"
                else -> continue
            }
            if (variables.isEmpty()) continue
            val references = variables.flatMap { identifier -> references(identifier, kind, project, session, r) ?: return@flatMap listOf(identifier) }
            val fits = candidates.filter { base -> references.all { reference -> allows(reference, base, session) } }
            if (fits.isEmpty()) continue
            val where = PsiTreeUtil.getParentOfType(name, CSharpMemberDeclaration::class.java)?.let { nameOf(it) }
            places += Place(name, "$kind ${variables.joinToString(", ") { it.text }}${where?.let { " in $it" } ?: ""} (${file.name})", fits)
        }
        return places
    }

    /** The uses of the variable [identifier] declares: a local's or parameter's in its scope, a field's across the solution. */
    private fun references(identifier: PsiElement, kind: String, project: Project, session: CSharpSemanticSession, r: CSharpNameResolver): List<PsiElement>? {
        if (kind != "field") return r.syntax.symbolAt(identifier)?.references?.toList()
        val declarator = identifier.parent as? CSharpVariableDeclarator ?: return null
        val target = CSharpSolutionSearch.targetOf(declarator) ?: return null
        return CSharpSolutionSearch.usages(project, target, GlobalSearchScope.projectScope(project), session).map { it.leaf }
    }

    private val OBJECT_MEMBERS = setOf("ToString", "Equals", "GetHashCode", "GetType")

    /** Whether the use [reference] of a variable allows its type to be [base]: a member of the base, a null check, an assignment to it. */
    private fun allows(reference: PsiElement, base: SemanticType, session: CSharpSemanticSession): Boolean {
        val file = reference.containingFile as? CSharpFile ?: return false
        val r = session.resolver(file)
        var value: PsiElement = reference.parent as? CSharpSimpleName ?: return false
        (value.parent as? CSharpMemberAccessExpression)?.let { access -> if (access.nameElement == value && access.expression is CSharpThisExpression) value = access }
        val parent = value.parent
        fun member(name: CSharpSimpleName?): Boolean {
            val text = ((name as? CSharpIdentifierName)?.identifier ?: (name as? CSharpGenericName)?.identifier)?.text ?: return false
            if (text in OBJECT_MEMBERS) return true
            val arity = (name as? CSharpGenericName)?.typeArgumentList?.arguments?.size ?: 0
            return r.membersNamed(base, text, arity).isNotEmpty() || arity == 0 && (1..3).any { r.membersNamed(base, text, it).isNotEmpty() }
        }
        return when {
            parent is CSharpMemberAccessExpression && parent.expression == value -> member(parent.nameElement)
            parent is CSharpConditionalAccessExpression && parent.expression == value ->
                member(PsiTreeUtil.findChildOfType(parent.whenNotNull, CSharpMemberBindingExpression::class.java, false)?.nameElement
                    ?: (parent.whenNotNull as? CSharpMemberBindingExpression)?.nameElement)
            parent is CSharpBinaryExpression && parent.operatorToken?.text in setOf("==", "!=") ->
                (if (parent.left == value) parent.right else parent.left)?.text?.trim() == "null"
            parent is CSharpIsPatternExpression && parent.expression == value -> parent.pattern?.text?.trim() in setOf("null", "not null")
            parent is CSharpAssignmentExpression && parent.left == value && parent.operatorToken?.text == "=" -> true
            else -> false
        }
    }

    /** The plan of writing [base] for the type of every place of [places] it fits. */
    fun useBaseTypePlan(places: List<Place>, base: SemanticType): Plan {
        val plan = Plan()
        val key = base.display ?: base.name
        val written = base.minimalDisplay ?: base.name
        val namespace = when (base) {
            is SemanticType.Source -> base.info.qualifiedName.substringBeforeLast('.', "")
            is SemanticType.Library -> base.type.namespace
            else -> ""
        }
        for (place in places) {
            if (place.fits.none { (it.display ?: it.name) == key }) continue
            plan.edit(place.typeElement, place.typeElement.textRange, written)
            plan.using(place.typeElement, namespace)
            plan.places += place.description
        }
        return plan
    }

    // ---- running

    /** [compute] in a read action under a modal progress (directly in tests). */
    fun <T> compute(project: Project, title: String, compute: () -> T): T? {
        if (ApplicationManager.getApplication().isUnitTestMode) return ReadAction.compute<T, RuntimeException> { compute() }
        var result: T? = null
        val done = ProgressManager.getInstance().runProcessWithProgressSynchronously({
            result = ReadAction.compute<T, RuntimeException> { compute() }
        }, title, true, project)
        return if (done) result else null
    }

    /** The conflicts of [plan] shown before it is applied; true to go on (always without conflicts, the test answer otherwise). */
    fun goOn(project: Project, plan: Plan, title: String): Boolean {
        if (plan.conflicts.isEmpty()) return true
        answer?.let { return it.confirm }
        return Messages.showYesNoDialog(project, "Conflicts:\n" + plan.conflicts.joinToString("\n") { "• $it" } + "\n\nContinue anyway?", title,
            "Continue", "Cancel", Messages.getWarningIcon()) == Messages.YES
    }
}

/** The members to move, which of them go as abstract declarations, and the type they go to: Rider's dialog of Pull Up / Push Down. */
class NativeCSharpHierarchyDialog(
    project: Project, title: String, private val targets: List<String>, members: List<String>, preselected: Set<String>,
    abstractLabel: String, abstractable: (String) -> Boolean,
) : DialogWrapper(project) {
    private val targetBox = ComboBox(targets.toTypedArray())
    private val checks = members.map { JBCheckBox(it, it in preselected || preselected.isEmpty()) }
    private val abstracts = members.map { name -> JBCheckBox(abstractLabel).apply { isEnabled = abstractable(name) } }

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (targets.isNotEmpty()) row("Target type:") { cell(targetBox) }
        for (i in checks.indices) row { cell(checks[i]); cell(abstracts[i]) }
    }

    fun answer(): NativeCSharpHierarchyEdits.Answer = NativeCSharpHierarchyEdits.Answer(
        target = targetBox.selectedItem as? String,
        members = checks.filter { it.isSelected }.map { it.text },
        abstract = checks.indices.filter { checks[it].isSelected && abstracts[it].isSelected && abstracts[it].isEnabled }.map { checks[it].text }.toSet(),
    )
}

/** Refactor This «Pull Members Up» (Rider): members of a class to a base class or interface of the solution. */
class NativeCSharpPullMembersUpRow : NativeCSharpRefactorRow("Pull Members Up") {
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is CSharpFile || file.compilationUnit == null || DumbService.isDumb(project)) return false
        val (type, member) = NativeCSharpHierarchyEdits.siteAt(file, editor.caretModel.offset) ?: return false
        if (type.baseList == null) return false
        val candidates = NativeCSharpHierarchyEdits.pullCandidates(type)
        return if (member != null) member in candidates else candidates.isNotEmpty()
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val (type, member) = NativeCSharpHierarchyEdits.siteAt(file, editor.caretModel.offset) ?: return
        val session = CSharpSemanticSession(project)
        val bases = NativeCSharpHierarchyEdits.compute(project, "Looking for Base Types") { NativeCSharpHierarchyEdits.bases(type, session) } ?: return
        if (bases.isEmpty()) return Messages.showInfoMessage(project, "'${type.identifier?.text}' has no base type of the solution to pull members to", text)
        val candidates = NativeCSharpHierarchyEdits.pullCandidates(type)
        val names = candidates.mapNotNull { NativeCSharpHierarchyEdits.nameOf(it) }
        val preselected = setOfNotNull(member?.let(NativeCSharpHierarchyEdits::nameOf))
        val answer = NativeCSharpHierarchyEdits.answer ?: NativeCSharpHierarchyDialog(project, text, bases.map { it.name }, names, preselected, "Make abstract") { name ->
            candidates.firstOrNull { NativeCSharpHierarchyEdits.nameOf(it) == name }?.let(NativeCSharpHierarchyEdits::abstractable) == true
        }.let { if (it.showAndGet()) it.answer() else null } ?: return
        val base = bases.firstOrNull { it.name == answer.target } ?: bases.first()
        val chosen = candidates.filter { NativeCSharpHierarchyEdits.nameOf(it) in answer.members }
        if (chosen.isEmpty()) return
        val abstract = chosen.filter { NativeCSharpHierarchyEdits.nameOf(it) in answer.abstract }.toSet()
        val plan = NativeCSharpHierarchyEdits.compute(project, "Pull Members Up") { NativeCSharpHierarchyEdits.pullUp(type, base, chosen, abstract, session) } ?: return
        if (!NativeCSharpHierarchyEdits.goOn(project, plan, text)) return
        NativeCSharpHierarchyEdits.apply(project, plan, "Pull Members Up to ${base.name}")
    }
}

/** Refactor This «Push Members Down» (Rider): members of a class into its derived types of the solution. */
class NativeCSharpPushMembersDownRow : NativeCSharpRefactorRow("Push Members Down") {
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is CSharpFile || file.compilationUnit == null || DumbService.isDumb(project)) return false
        val (type, member) = NativeCSharpHierarchyEdits.siteAt(file, editor.caretModel.offset) ?: return false
        if (type.keyword?.text != "class" && type.keyword?.text != "record" || type.modifiers.any { it.text == "sealed" || it.text == "static" }) return false
        val candidates = NativeCSharpHierarchyEdits.pushCandidates(type)
        return if (member != null) member in candidates else candidates.isNotEmpty()
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val (type, member) = NativeCSharpHierarchyEdits.siteAt(file, editor.caretModel.offset) ?: return
        val session = CSharpSemanticSession(project)
        val derived = NativeCSharpHierarchyEdits.compute(project, "Looking for Derived Types") { NativeCSharpHierarchyEdits.derivedTypes(type, project, session) } ?: return
        if (derived.isEmpty()) return Messages.showInfoMessage(project, "'${type.identifier?.text}' has no derived types in the solution", text)
        val candidates = NativeCSharpHierarchyEdits.pushCandidates(type)
        val names = candidates.mapNotNull { NativeCSharpHierarchyEdits.nameOf(it) }
        val preselected = setOfNotNull(member?.let(NativeCSharpHierarchyEdits::nameOf))
        val answer = NativeCSharpHierarchyEdits.answer ?: NativeCSharpHierarchyDialog(project, text, emptyList(), names, preselected, "Keep abstract declaration") { name ->
            candidates.firstOrNull { NativeCSharpHierarchyEdits.nameOf(it) == name }?.let(NativeCSharpHierarchyEdits::abstractable) == true
        }.let { if (it.showAndGet()) it.answer() else null } ?: return
        val chosen = candidates.filter { NativeCSharpHierarchyEdits.nameOf(it) in answer.members }
        if (chosen.isEmpty()) return
        val keep = chosen.filter { NativeCSharpHierarchyEdits.nameOf(it) in answer.abstract }.toSet()
        val plan = NativeCSharpHierarchyEdits.compute(project, "Push Members Down") { NativeCSharpHierarchyEdits.pushDown(type, derived, chosen, keep, session) } ?: return
        if (!NativeCSharpHierarchyEdits.goOn(project, plan, text)) return
        NativeCSharpHierarchyEdits.apply(project, plan, "Push Members Down from ${type.identifier?.text}")
    }
}

/** Refactor This «Use Base Type Where Possible» (Rider): variables of a type declared as a base type all their uses allow. */
class NativeCSharpUseBaseTypeRow : NativeCSharpRefactorRow("Use Base Type Where Possible") {
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is CSharpFile || file.compilationUnit == null || DumbService.isDumb(project)) return false
        val (type, member) = NativeCSharpHierarchyEdits.siteAt(file, editor.caretModel.offset) ?: return false
        return member == null && type.baseList != null && type.typeParameterList == null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val (type, _) = NativeCSharpHierarchyEdits.siteAt(file, editor.caretModel.offset) ?: return
        val session = CSharpSemanticSession(project)
        val places = NativeCSharpHierarchyEdits.compute(project, "Looking for Usages") { NativeCSharpHierarchyEdits.useBaseType(type, project, session) } ?: return
        val bases = places.flatMap { it.fits }.distinctBy { it.display ?: it.name }
        if (bases.isEmpty()) return Messages.showInfoMessage(project, "No variable of '${type.identifier?.text}' can be declared as a base type", text)
        val names = bases.map { it.minimalDisplay ?: it.name }
        val test = NativeCSharpHierarchyEdits.answer
        val chosen = if (test != null) names.indexOf(test.target).coerceAtLeast(0)
        else if (names.size == 1) 0 else Messages.showChooseDialog(project, "Use base type:", text, null, names.toTypedArray(), names.first())
        if (chosen < 0) return
        val plan = NativeCSharpHierarchyEdits.compute(project, text) { NativeCSharpHierarchyEdits.useBaseTypePlan(places, bases[chosen]) } ?: return
        if (plan.places.isEmpty()) return
        val confirmed = test?.confirm ?: (Messages.showYesNoDialog(project, "Declare as '${names[chosen]}':\n" + plan.places.joinToString("\n") { "• $it" }, text,
            "Change", "Cancel", null) == Messages.YES)
        if (!confirmed) return
        NativeCSharpHierarchyEdits.apply(project, plan, "Use '${names[chosen]}' Where Possible")
    }
}
