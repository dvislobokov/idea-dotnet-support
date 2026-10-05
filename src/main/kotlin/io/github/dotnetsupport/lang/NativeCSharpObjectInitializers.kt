package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Key
import javax.swing.Icon
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.semantic.CSharpMemberLookup
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpRequiredMembers
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * Object initializers and the `required` members of C# 11 (0.1.98), as in Rider:
 *  - completion of `new T` for a type with required members writes the initializer with them instead of `()` (a live template with a
 *    stop at each value, the values at hand written in, 0.1.102); a type that has no parameterless constructor keeps `new T(|)`, its
 *    arguments come first ([afterNewType]); a type without required members has the row `T { … }` beside `T` ([initializerRow], 0.1.102);
 *  - in `new T { | }` the rows "Fill required members" and "Fill all members" ([fillItems]);
 *  - CS9035 has the quick fix «Add initializer for required members» (Rider's name, [AddRequiredMembersFix]); Alt+Enter in an
 *    initializer «Initialize members» / «Initialize required members».
 * The layout is Rider's default: one member a line, `{` on a line of its own ([fill]); a single member stays on the line: `{ Name =  }`.
 */
object NativeCSharpObjectInitializers {
    const val FILL_REQUIRED = "Fill required members"
    const val FILL_ALL = "Fill all members"

    // ---- the text

    /** The edit that writes `Name = ` for each of [names] into the initializer of [creation], adding one when it has none. */
    fun fill(creation: CSharpBaseObjectCreationExpression, names: List<String>, text: CharSequence, unit: String): CSharpTextEdit? {
        if (names.isEmpty()) return null
        val indent = lineIndent(text, creation.textRange.startOffset)
        val initializer = creation.initializer
        if (initializer == null) {
            val arguments = creation.argumentList
            // `new Order()` → `new Order { … }`: the empty parentheses are redundant with an initializer (Rider's default style); `new()` keeps them
            val start = if (creation is CSharpObjectCreationExpression && arguments != null && arguments.arguments.isEmpty() && present(arguments.openParenToken) &&
                present(arguments.closeParenToken)) arguments.textRange.startOffset else creation.textRange.endOffset
            return block(TextRange(start, creation.textRange.endOffset), emptyList(), names, indent, unit)
        }
        val open = initializer.openBraceToken?.takeIf(::present) ?: return null
        val close = initializer.closeBraceToken?.takeIf(::present) ?: return null
        val elements = initializer.expressions.filter { it.textLength > 0 }
        val inside = text.subSequence(open.textRange.endOffset, close.textRange.startOffset)
        if ('\n' !in inside) {
            if (PsiTreeUtil.findChildOfType(initializer, PsiComment::class.java) != null) return null
            var start = open.textRange.startOffset
            while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
            return block(TextRange(start, close.textRange.endOffset), elements.map { it.text }, names, indent, unit)
        }
        val braceIndent = lineIndent(text, open.textRange.startOffset)
        if (elements.isEmpty()) {
            val member = braceIndent + unit
            val body = names.joinToString(",") { "\n$member$it = " }
            return CSharpTextEdit(TextRange(open.textRange.endOffset, close.textRange.startOffset), body + "\n" + lineIndent(text, close.textRange.startOffset),
                1 + member.length + names.first().length + 3)
        }
        val first = elements.first().textRange.startOffset
        val member = if (startsLine(text, first)) lineIndent(text, first) else braceIndent + unit
        val separators = initializer.expressionsSeparators
        val trailing = separators.size >= elements.size
        val at = if (trailing) separators.last().textRange.endOffset else elements.last().textRange.endOffset
        val out = StringBuilder()
        if (!trailing) out.append(',')
        var caret = -1
        for ((i, name) in names.withIndex()) {
            if (i > 0) out.append(',')
            out.append('\n').append(member).append(name).append(" = ")
            if (caret < 0) caret = out.length
        }
        if (trailing) out.append(',')
        return CSharpTextEdit(TextRange(at, at), out.toString(), caret)
    }

    /** `{ A = 1 }` one entry on the line; more: `{` on a line of its own and an entry a line, indented by [unit] from the line of the creation. */
    fun block(range: TextRange, existing: List<String>, names: List<String>, indent: String, unit: String): CSharpTextEdit {
        val added = names.map { "$it = " }
        val all = existing + added
        if (all.size == 1) return CSharpTextEdit(range, " { ${all.single()} }", 3 + all.single().length)
        val out = StringBuilder("\n").append(indent).append('{')
        var caret = -1
        for ((i, entry) in all.withIndex()) {
            if (i > 0) out.append(',')
            out.append('\n').append(indent).append(unit).append(entry)
            if (caret < 0 && i >= existing.size) caret = out.length
        }
        out.append('\n').append(indent).append('}')
        return CSharpTextEdit(range, out.toString(), caret)
    }

    private fun present(token: PsiElement?): Boolean = (token?.textLength ?: 0) > 0

    private fun lineIndent(text: CharSequence, offset: Int): String {
        val start = if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        var end = start
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.subSequence(start, end).toString()
    }

    private fun startsLine(text: CharSequence, offset: Int): Boolean {
        var i = offset
        while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t')) i--
        return i == 0 || text[i - 1] == '\n'
    }

    // ---- what to fill

    /** The created type of an object creation, null where it is not known. */
    fun createdType(creation: CSharpBaseObjectCreationExpression, r: CSharpNameResolver): SemanticType? =
        r.typeOf(creation)?.takeIf { it is SemanticType.Source || it is SemanticType.Library }

    /** The required members [creation] leaves unset (an empty list when none or not known). */
    fun missingRequired(creation: CSharpBaseObjectCreationExpression, r: CSharpNameResolver): List<String> {
        val type = createdType(creation, r) ?: return emptyList()
        return CSharpRequiredMembers(r).missing(creation, type)?.map { it.name }.orEmpty()
    }

    /** The members of [type] an initializer may set with `Name = value` and [taken] does not set yet: the bases' first, in the order written. */
    fun settable(type: SemanticType, r: CSharpNameResolver, site: PsiElement, taken: Set<String>): List<String> {
        val found = ArrayList<Pair<String, CSharpSymbol>>()
        for (entry in CSharpMemberLookup(r).entries(CSharpNameResolver.Qualifier.Value(type), site)) {
            ProgressManager.checkCanceled()
            if (entry.inaccessible || entry.name in taken) continue
            val symbol = entry.first
            val member = when (symbol) {
                is CSharpSymbol.SourceMember -> NativeCSharpMembers.kind(symbol.member).let { it == NativeCSharpMembers.Kind.PROPERTY || it == NativeCSharpMembers.Kind.FIELD } &&
                    !NativeCSharpMembers.isStatic(symbol.member)
                is CSharpSymbol.LibraryMember -> (symbol.member.kind == IndexedMemberKind.PROPERTY || symbol.member.kind == IndexedMemberKind.FIELD) && !symbol.member.isStatic
                else -> false
            }
            if (member && NativeCSharpExpectedCompletion.writable(symbol, site)) found += entry.name to symbol
        }
        val depth = HashMap<String, Int>()
        var current: SemanticType? = type
        var level = 0
        while (current is SemanticType.Source && level < 16) {
            depth[current.info.key] = level++
            current = r.baseTypes(current).firstOrNull { it is SemanticType.Source }
        }
        val required = CSharpRequiredMembers(r).of(type).map { it.name }
        // the required ones first, then the bases' members before the type's own, each type's in the order written
        return found.sortedWith(compareBy<Pair<String, CSharpSymbol>>({ it.first !in required }, { required.indexOf(it.first) }, { pair ->
            when (val s = pair.second) {
                is CSharpSymbol.LibraryMember -> -1
                is CSharpSymbol.SourceMember -> -(PsiTreeUtil.getParentOfType(s.element, CSharpBaseTypeDeclaration::class.java)
                    ?.let { r.syntax.declaredType(it)?.key }?.let { depth[it] } ?: 0)
                else -> 0
            }
        }, { pair -> (pair.second as? CSharpSymbol.SourceMember)?.element?.textRange?.startOffset ?: 0 })).map { it.first }
    }

    // ---- completion

    /**
     * `new OrderLine` chosen from the list for a type with required members: ` { Sku = |, Title =  }` (laid out by [fill]) instead of
     * `()`. False where nothing was written (no required members, no parameterless constructor, a `{` follows already): `()` then.
     */
    fun afterNewType(context: InsertionContext): Boolean {
        val file = context.file as? CSharpFile ?: return false
        if (DumbService.isDumb(context.project)) return false
        val document = context.document
        val offset = context.tailOffset
        val text = document.charsSequence
        var next = offset
        while (next < text.length && (text[next] == ' ' || text[next] == '\t')) next++
        if (text.getOrNull(next) == '{') return false
        return writeInitializer(context, requiredOnly = true)
    }

    /**
     * After `new Member` chosen from the list: the initializer as a live template ([startTemplate]) — of the required members only
     * ([requiredOnly], the type row of 0.1.98) or of every member it may set, the required ones first (the row `Member { … }`, 0.1.102).
     * False where nothing was written: a `(` or `{` follows, the type is not known, it needs arguments, it has no required member.
     */
    private fun writeInitializer(context: InsertionContext, requiredOnly: Boolean): Boolean {
        val file = context.file as? CSharpFile ?: return false
        if (DumbService.isDumb(context.project)) return false
        val offset = context.tailOffset
        val text = context.document.charsSequence
        var next = offset
        while (next < text.length && (text[next] == ' ' || text[next] == '\t')) next++
        if (text.getOrNull(next) == '{' || text.getOrNull(next) == '(') return false
        context.commitDocument()
        val leaf = file.findElementAt(offset - 1) ?: return false
        val creation = PsiTreeUtil.getParentOfType(leaf, CSharpObjectCreationExpression::class.java) ?: return false
        val type = creation.type?.takeIf { it.textRange.endOffset == offset } ?: return false
        if (creation.initializer != null) return false
        val r = CSharpSemanticSession(file.project).resolver(file)
        val created = r.resolveType(type)?.takeIf { it is SemanticType.Source || it is SemanticType.Library } ?: return false
        val required = CSharpRequiredMembers(r)
        val requiredNames = required.of(created).map { it.name }
        val names = if (requiredOnly) {
            if (requiredNames.isEmpty() || !required.createdWithoutArguments(created)) return false
            requiredNames
        } else {
            if (!required.createdWithoutArguments(created) && !NativeCSharpTypingGhost.parameterless(created)) return false
            settable(created, r, creation, emptySet()).let { all -> if (all.size > MAX_MEMBERS) requiredNames else all }
        }
        startTemplate(context.editor, file, r, creation, created, names, offset, lineIndent(text, creation.textRange.startOffset))
        return true
    }

    /** More members than this: the row writes the required ones (or the braces alone), as the gray text of an empty initializer does. */
    private const val MAX_MEMBERS = 12

    /**
     * `{ Name = name, Email = dto.Email, Age = | }` at [offset] laid out as [block] (one member stays on the line, more go a line each), as a
     * live template: a stop at each value, the value found at hand written in and selected ([NativeCSharpTypingGhost.bestValue], as the
     * gray text of 0.1.101 fills them) — Tab keeps it and goes on, typing replaces it; the empty ones wait for theirs. Then after the `}`.
     */
    private fun startTemplate(editor: Editor, file: CSharpFile, r: CSharpNameResolver, site: PsiElement, type: SemanticType, names: List<String>, offset: Int, indent: String) {
        val unit = NativeCSharpContextEdits.unit(file)
        val values = names.map { name -> NativeCSharpTypingGhost.memberType(r, type, name)?.let { NativeCSharpTypingGhost.bestValue(r, site, name, it, "") }.orEmpty() }
        val body = when (names.size) {
            0 -> "\n$indent{\n$indent$unit\$END\$\n$indent}"
            1 -> " { ${names.single()} = \$V0\$ }\$END\$"
            else -> names.withIndex().joinToString(",", "\n$indent{", "\n$indent}\$END\$") { (i, name) -> "\n$indent$unit$name = \$V$i\$" }
        }
        val manager = TemplateManager.getInstance(file.project)
        val template = manager.createTemplate("", "", body)
        template.isToReformat = false
        (template as? com.intellij.codeInsight.template.impl.TemplateImpl)?.isToIndent = false
        for (i in names.indices) template.addVariable("V$i", ConstantNode(values[i]), true)
        editor.caretModel.moveToOffset(offset)
        manager.startTemplate(editor, template)
    }

    // ---- the row `Member { … }` (0.1.102)

    private val INITIALIZER_ROW: Key<Boolean> = Key.create("dotnet.csharp.initializerRow")

    /** The row `Member { … }` (its lookup string is the type's name, as the row of the type has it). */
    fun isInitializerRow(element: LookupElement): Boolean {
        var current: LookupElement? = element
        while (current != null) {
            if (current.getUserData(INITIALIZER_ROW) == true) return true
            current = (current as? com.intellij.codeInsight.lookup.LookupElementDecorator<*>)?.delegate
        }
        return false
    }

    /**
     * The row `Member { … }` under `Member` after `new` (Rider lists the creation with an initializer beside the one with `()`): choosing
     * it writes `new Member` + the initializer of every member the type lets set, with their values ([writeInitializer]). Typing a
     * character that chooses rows (`(`, `.`) writes the name alone, as the row of the type would before its `()`.
     */
    fun initializerRow(name: String, icon: Icon, tail: String?, priority: Double): LookupElement {
        var builder = LookupElementBuilder.create(name).withPresentableText("$name { … }").withIcon(icon).withTypeText("initializer", true)
        if (tail != null) builder = builder.withTailText(tail, true)
        builder = builder.withInsertHandler { context, _ ->
            val char = context.completionChar
            if (char != Lookup.NORMAL_SELECT_CHAR && char != Lookup.REPLACE_SELECT_CHAR && char != Lookup.AUTO_INSERT_SELECT_CHAR) return@withInsertHandler
            runCatching { writeInitializer(context, requiredOnly = false) }.onFailure { if (it is com.intellij.openapi.progress.ProcessCanceledException) throw it }
        }
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        builder.putUserData(INITIALIZER_ROW, true)
        return PrioritizedLookupElement.withPriority(builder, priority).also {
            it.putUserData(NativeCSharpCompletion.NATIVE, true)
            it.putUserData(INITIALIZER_ROW, true)
        }
    }

    /**
     * Whether a type of the solution gets the row `Member { … }`, by its declarations alone (the list may hold hundreds of types): a class,
     * record or struct created without arguments, not generic, with a member of its own that an initializer sets (a public or internal
     * property with `set` / `init`, a field that is not `readonly` / `const`), and no `required` member (its own row writes those already).
     */
    fun wantsInitializerRow(info: TypeInfo): Boolean {
        val kind = info.kind ?: return false
        if (kind != TypeKind.CLASS && kind != TypeKind.RECORD && kind != TypeKind.STRUCT && kind != TypeKind.RECORD_STRUCT || info.arity != 0) return false
        if (info.parts.any { "abstract" in it.modifiers || "static" in it.modifiers }) return false
        val declarations = info.parts.mapNotNull { it.element() as? CSharpTypeDeclaration }
        if (declarations.isEmpty() || declarations.any { it.parameterList?.parameters?.isNotEmpty() == true }) return false
        val constructors = declarations.flatMap { d -> d.members.filterIsInstance<CSharpConstructorDeclaration>().filter { c -> c.modifiers.none { it.text == "static" } } }
        if (constructors.isNotEmpty() && constructors.none { c -> c.parameterList?.parameters.orEmpty().all { it.default != null } && c.modifiers.none { it.text == "private" } }) return false
        var settable = false
        for (member in declarations.asSequence().flatMap { it.members.asSequence() }) {
            val modifiers = member.modifiers.map { it.text }
            if ("required" in modifiers) return false
            if (settable || "static" in modifiers || "public" !in modifiers && "internal" !in modifiers) continue
            settable = when (member) {
                is CSharpPropertyDeclaration -> member.accessorList?.accessors.orEmpty().any { a ->
                    (a.keyword?.text == "set" || a.keyword?.text == "init") && a.modifiers.none { it.text == "private" || it.text == "protected" }
                }
                is CSharpBaseFieldDeclaration -> "readonly" !in modifiers && "const" !in modifiers
                else -> false
            }
        }
        return settable
    }

    /** What the row of `new T` shows when choosing it writes the initializer: `OrderLine { Sku, Title }`. */
    fun presentation(shown: String, type: SemanticType, r: CSharpNameResolver): String? {
        val required = runCatching { CSharpRequiredMembers(r) }.getOrNull() ?: return null
        val names = runCatching { required.of(type).map { it.name } }.getOrNull().orEmpty()
        if (names.isEmpty() || !required.createdWithoutArguments(type)) return null
        return "$shown { ${names.joinToString(", ")} }"
    }

    /** In `new T { | }`: "Fill required members" while one is unset, "Fill all members" while a settable one is (Rider's rows). */
    fun fillItems(initializer: CSharpInitializerExpression, r: CSharpNameResolver, site: PsiElement, priority: Double): List<LookupElement> {
        val creation = initializer.parent as? CSharpBaseObjectCreationExpression ?: return emptyList()
        if (creation.initializer != initializer) return emptyList()
        val type = createdType(creation, r) ?: return emptyList()
        val assigned = CSharpRequiredMembers.assignedNames(initializer)
        val requiredMembers = CSharpRequiredMembers(r)
        val setsAll = requiredMembers.setsRequired(type, creation.argumentList?.arguments.orEmpty()) == true
        val required = if (setsAll) emptyList() else requiredMembers.of(type).map { it.name }.filter { it !in assigned }
        val all = settable(type, r, site, assigned)
        val result = ArrayList<LookupElement>()
        if (required.isNotEmpty()) result += fillRow(FILL_REQUIRED, required, priority + 1)
        if (all.isNotEmpty() && all != required) result += fillRow(FILL_ALL, all, priority)
        return result
    }

    private fun fillRow(title: String, names: List<String>, priority: Double): LookupElement {
        val builder = LookupElementBuilder.create(title).withIcon(AllIcons.Actions.RealIntentionBulb).bold()
            .withTailText(" " + names.joinToString(", ").let { if (it.length > 60) it.take(57) + "…" else it }, true)
            .withInsertHandler { context, _ ->
                val document = context.document
                document.deleteString(context.startOffset, context.tailOffset)
                context.commitDocument()
                val file = context.file as? CSharpFile ?: return@withInsertHandler
                val creation = PsiTreeUtil.getParentOfType(file.findElementAt(context.startOffset), CSharpBaseObjectCreationExpression::class.java) ?: return@withInsertHandler
                val edit = fill(creation, names, document.charsSequence, NativeCSharpContextEdits.unit(file)) ?: return@withInsertHandler
                document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
                context.editor.caretModel.moveToOffset(edit.range.startOffset + edit.caret)
                context.commitDocument()
            }
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        return PrioritizedLookupElement.withPriority(builder, priority).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
    }

    /** The object creation whose initializer (or `new T(...)` itself) holds [offset]; not inside an argument or a value of a member. */
    fun creationAt(file: CSharpFile, offset: Int): CSharpBaseObjectCreationExpression? {
        for (leaf in listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)) {
            val creation = PsiTreeUtil.getParentOfType(leaf, CSharpBaseObjectCreationExpression::class.java) ?: continue
            val initializer = creation.initializer
            val inInitializer = initializer != null && PsiTreeUtil.isAncestor(initializer, leaf, false) &&
                initializer.expressions.none { e -> (e as? CSharpAssignmentExpression)?.right?.let { PsiTreeUtil.isAncestor(it, leaf, false) } == true }
            val inHead = leaf.textRange.endOffset <= (creation.argumentList?.textRange?.startOffset ?: initializer?.textRange?.startOffset ?: creation.textRange.endOffset)
            if (inInitializer || inHead) {
                if (initializer != null && initializer.node.elementType != SyntaxKind.ObjectInitializerExpression && initializer.expressions.isNotEmpty()) return null
                return creation
            }
        }
        return null
    }
}

/** Alt+Enter on CS9035: `Sku = , Title = ` for every required member the creation leaves unset (Rider: "Add initializer for required members"). */
class AddRequiredMembersFix(private val at: SmartPsiElementPointer<PsiElement>) : IntentionAction, PriorityAction {
    override fun getText(): String = "Add initializer for required members"
    override fun getFamilyName(): String = text
    override fun startInWriteAction(): Boolean = true
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.TOP

    private fun creation(): CSharpBaseObjectCreationExpression? = at.element?.let { PsiTreeUtil.getParentOfType(it, CSharpBaseObjectCreationExpression::class.java, false) }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        file is CSharpFile && !DumbService.isDumb(project) && creation()?.let { edit(file, it) } != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file !is CSharpFile) return
        val creation = creation() ?: return
        val edit = edit(file, creation) ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
        editor?.caretModel?.moveToOffset(edit.range.startOffset + edit.caret)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    private fun edit(file: CSharpFile, creation: CSharpBaseObjectCreationExpression): CSharpTextEdit? {
        val names = NativeCSharpObjectInitializers.missingRequired(creation, CSharpSemanticSession(file.project).resolver(file))
        val text = PsiDocumentManager.getInstance(file.project).getDocument(file)?.charsSequence ?: file.text
        return NativeCSharpObjectInitializers.fill(creation, names, text, NativeCSharpContextEdits.unit(file))
    }

    companion object {
        fun at(file: CSharpFile, offset: Int): AddRequiredMembersFix? = file.findElementAt(offset)?.let { AddRequiredMembersFix(SmartPointerManager.createPointer(it)) }
    }
}

/**
 * Alt+Enter in `new T { | }` (or on `new T(...)`): `Name = ` for each required member left unset. On the type CS9035 underlines its quick
 * fix «Add initializer for required members» does the same, so this one stands back there.
 */
class NativeCSharpInitializeRequiredMembersIntention : NativeCSharpContextAction("Initialize required members", serverHasIt = false, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val creation = NativeCSharpObjectInitializers.creationAt(file, editor.caretModel.offset) ?: return null
        val caret = editor.caretModel.offset
        val shown = NativeCSharpSemanticDiagnostics.of(file).any { it.code == "CS9035" && it.range.grown(1).contains(caret) }
        if (shown) return null
        val names = NativeCSharpObjectInitializers.missingRequired(creation, resolver(file))
        return NativeCSharpObjectInitializers.fill(creation, names, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}

/** Alt+Enter in `new T { | }` (or on `new T(...)`): `Name = ` for every member the initializer may set and does not set yet, the required ones first. */
class NativeCSharpInitializeMembersIntention : NativeCSharpContextAction("Initialize members", serverHasIt = false, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val creation = NativeCSharpObjectInitializers.creationAt(file, editor.caretModel.offset) ?: return null
        val r = resolver(file)
        val type = NativeCSharpObjectInitializers.createdType(creation, r) ?: return null
        val names = NativeCSharpObjectInitializers.settable(type, r, creation, CSharpRequiredMembers.assignedNames(creation.initializer))
        return NativeCSharpObjectInitializers.fill(creation, names, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}
