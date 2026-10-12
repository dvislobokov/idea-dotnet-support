package io.github.dotnetsupport.lang

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.NativeCSharpTypingGhost.Suggestion
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.suggest.SuggestionRules

/**
 * The "analogy and value" rules of the gray text (0.1.163, [NativeCSharpGhostRule]): what the sections of a `switch` repeat, the collection
 * of a `foreach` / the bound of a `for`, the parameter of `nameof(`, the string of `string.IsNullOrEmpty(`. Rider's local network writes
 * these lines; here they are rules on the names in scope, silent where two answers are as likely.
 */
internal object AnalogyGhostSupport {
    fun lineStart(text: CharSequence, offset: Int): Int = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
    fun lineBefore(text: CharSequence, offset: Int): String = text.subSequence(lineStart(text, offset), offset).toString()
    fun restOfLine(text: CharSequence, offset: Int): String {
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return text.subSequence(offset, end).toString()
    }
    fun lineIndent(text: CharSequence, offset: Int): String = lineBefore(text, offset).takeWhile { it == ' ' || it == '\t' }
    fun previousNonBlankLine(text: CharSequence, offset: Int): String? {
        var end = lineStart(text, offset)
        while (end > 0) {
            val start = lineStart(text, end - 1)
            val line = text.subSequence(start, end - 1).toString()
            if (line.isNotBlank()) return line
            end = start
        }
        return null
    }
    fun leafAt(file: CSharpFile, offset: Int): PsiElement? = file.findElementAt((offset - 1).coerceAtLeast(0))

    /** The rest of [whole] past [typed] (the line's text up to the caret, trimmed), or null when [whole] does not continue it. */
    fun rest(whole: String, typed: String): String? = if (whole.startsWith(typed) && whole != typed) whole.substring(typed.length) else null

    /** `)` unless one stands right after the caret already. */
    fun close(text: CharSequence, offset: Int): String = if (restOfLine(text, offset).trimStart().startsWith(")")) "" else ")"

    /** A name in scope with its type; [offset] of its declaration, -1 for a member of the types around; [parameter] of the function. */
    class Named(val name: String, val type: SemanticType, val offset: Int, val local: Boolean, val parameter: Boolean)

    /** The locals and parameters visible at [site] and the fields / properties of the types around, as [NativeCSharpTypingGhost.bestValue] sees them. */
    fun namesInScope(r: CSharpNameResolver, site: PsiElement): List<Named> {
        val result = ArrayList<Named>()
        for (symbol in NativeCSharpLocals.visible(r.syntax.scopes, site)) {
            if (symbol.kind != LocalSymbolKind.LOCAL && symbol.kind != LocalSymbolKind.PARAMETER && symbol.kind != LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER) continue
            val type = r.valueType(CSharpSymbol.Local(symbol)) ?: continue
            result += Named(symbol.name, type, symbol.declaration.textRange.startOffset, local = true, parameter = symbol.kind != LocalSymbolKind.LOCAL)
        }
        val static = NativeCSharpLocals.inStaticContext(site)
        for (info in r.syntax.enclosingTypes(site)) for ((key, m) in r.syntax.membersOf(info)) {
            ProgressManager.checkCanceled()
            if ('<' in key || '`' in key || m.nestedType != null || result.any { it.name == key }) continue
            val kind = NativeCSharpMembers.kind(m)
            if (kind != NativeCSharpMembers.Kind.FIELD && kind != NativeCSharpMembers.Kind.PROPERTY) continue
            if (static && !NativeCSharpMembers.isStatic(m)) continue
            val type = r.membersNamed(r.selfType(info), key, 0).firstOrNull()?.let(r::valueType) ?: continue
            result += Named(key, type, -1, local = false, parameter = false)
        }
        return result
    }

    fun isString(r: CSharpNameResolver, type: SemanticType): Boolean = r.definitionName(r.unwrapNullable(type)) == "System.String"

    /** An array or anything enumerable but a string: what `foreach` walks. */
    fun isEnumerable(r: CSharpNameResolver, type: SemanticType): Boolean =
        type is SemanticType.ArrayOf || (!isString(r, type) && r.expressions.instanceOf(type, "System.Collections.IEnumerable") != null)

    /**
     * The one collection in scope ([isEnumerable]) other than [except]; with several, the one named as the plural of [element], else the
     * one whose elements are of the type the element is named after (`preference` → `ICollection<Preference> source`, 0.1.169, Jellyfin № 6).
     */
    fun collection(r: CSharpNameResolver, site: PsiElement, except: String?, element: String?): Named? {
        val collections = namesInScope(r, site).filter { it.name != except && isEnumerable(r, it.type) }
        collections.singleOrNull()?.let { return it }
        if (element == null) return null
        collections.filter { CSharpExpressionNames.singular(it.name).equals(element, ignoreCase = true) || it.name.equals(element + "s", ignoreCase = true) }
            .singleOrNull()?.let { return it }
        val word = element.trimStart('_').replaceFirstChar { it.uppercase() }
        if (word.isEmpty()) return null
        return collections.filter { named ->
            val typeName = r.elementType(named.type)?.let(r::definitionName)?.substringAfterLast('.')?.substringBefore('`') ?: return@filter false
            typeName.equals(word, ignoreCase = true) || typeName.endsWith(word) && typeName.length > word.length && typeName[typeName.length - word.length].isUpperCase()
        }.singleOrNull()
    }

    fun hasValueMember(r: CSharpNameResolver, type: SemanticType, name: String): Boolean = r.membersNamed(type, name, 0).any { !r.isMethod(it) }
}

/**
 * The statement a `switch` repeats section by section (`return "Insert";` under `case OperationType.Insert:`), written for the new section
 * with its member's name: on the first line of the section (or after `=> ` of an arm), when two or more sections before it hold one
 * statement / value each of the very same text but for the member name of their label (as a string, an identifier or a member name).
 * Silent when the texts differ in anything else, when the label is no member, or when the section has statements already.
 */
object SwitchSectionAnalogyRule : NativeCSharpGhostRule {
    private val ARM_LINE = Regex("""^\s*[A-Za-z_][\w.]*\s*=>\s*(.*)$""")
    private const val EXACT = '\u0001'
    private const val CAMEL = '\u0002'
    private const val SWITCH_REACH = 20_000

    override fun place(text: CharSequence, offset: Int): Boolean {
        if (offset <= 0 || offset > text.length) return false
        if (AnalogyGhostSupport.restOfLine(text, offset).isNotBlank()) return false
        if (text.subSequence((offset - SWITCH_REACH).coerceAtLeast(0), offset).lastIndexOf("switch") < 0) return false
        val before = AnalogyGhostSupport.lineBefore(text, offset)
        if (ARM_LINE.matches(before)) return true
        // the first line of a section: the label is the line above
        return AnalogyGhostSupport.previousNonBlankLine(text, offset)?.trimEnd()?.endsWith(":") == true
    }

    override fun suggest(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): Suggestion? {
        val before = AnalogyGhostSupport.lineBefore(text, offset)
        val leaf = AnalogyGhostSupport.leafAt(file, offset) ?: return null
        ARM_LINE.matchEntire(before)?.let { match -> return arm(leaf, text, offset, match.groupValues[1].trim()) }
        return section(leaf, text, offset, before.trimStart())
    }

    /** [member] → [EXACT], its camel-case form → [CAMEL]; null when the text does not mention the member. */
    private fun template(statement: String, member: String): String? {
        val camel = member.replaceFirstChar { it.lowercase() }
        var found = false
        val out = StringBuilder()
        var i = 0
        while (i < statement.length) {
            val c = statement[i]
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < statement.length && (statement[j].isLetterOrDigit() || statement[j] == '_')) j++
                val word = statement.substring(i, j)
                when {
                    word == member -> { out.append(EXACT); found = true }
                    camel != member && word == camel -> { out.append(CAMEL); found = true }
                    else -> out.append(word)
                }
                i = j
            } else { out.append(c); i++ }
        }
        return if (found) out.toString() else null
    }

    private fun fill(template: String, member: String): String = template.replace(EXACT.toString(), member).replace(CAMEL.toString(), member.replaceFirstChar { it.lowercase() })

    /** The member a label names: `case OperationType.Insert:` → `Insert`; null for `default`, patterns with `when`, non-names. */
    private fun memberOf(label: CSharpSwitchLabel): String? {
        val written = when (label) {
            is CSharpCaseSwitchLabel -> label.value?.text
            is CSharpCasePatternSwitchLabel -> label.pattern?.text?.takeIf { label.whenClause == null }
            else -> null
        }?.trim() ?: return null
        val name = written.substringAfterLast('.')
        return name.takeIf { it.isNotEmpty() && (it[0].isLetter() || it[0] == '_') && it.all { c -> c.isLetterOrDigit() || c == '_' } }
    }

    private fun section(leaf: PsiElement, text: CharSequence, offset: Int, typed: String): Suggestion? {
        var current: PsiElement? = leaf
        while (current != null && current !is CSharpSwitchSection && current !is CSharpSwitchStatement && current !is CSharpMemberDeclaration) current = current.parent
        // the blank line under the last label belongs to the switch, not to the section: the section is the last one above the caret
        val section = current as? CSharpSwitchSection ?: (current as? CSharpSwitchStatement)?.sections?.lastOrNull { it.textRange.startOffset < offset } ?: return null
        val switch = section.parent as? CSharpSwitchStatement ?: return null
        val label = section.labels.singleOrNull() ?: return null
        val labelLine = AnalogyGhostSupport.lineStart(text, label.textRange.endOffset)
        // the first line of the section: nothing but blank lines between the label and the caret
        if (text.subSequence(label.textRange.endOffset, AnalogyGhostSupport.lineStart(text, offset)).any { it != ' ' && it != '\t' && it != '\n' && it != '\r' }) return null
        if (labelLine >= AnalogyGhostSupport.lineStart(text, offset)) return null
        val lineStart = AnalogyGhostSupport.lineStart(text, offset)
        // what is typed on this line parses as the section's first statement (the parser may stretch it over the line's end); a statement
        // above or below the line is the section's own business
        if (section.statements.any { it.text.isNotBlank() && (it.textRange.startOffset < lineStart || it.textRange.startOffset > offset) }) return null
        val member = memberOf(label) ?: return null
        val index = switch.sections.indexOf(section)
        if (index < 2) return null
        var template: String? = null
        var indent: String? = null
        for (previous in switch.sections.subList(0, index)) {
            val previousMember = previous.labels.singleOrNull()?.let(::memberOf) ?: return null
            val statements = previous.statements
            val statement = statements.firstOrNull() ?: return null
            if (statements.size > 2 || (statements.size == 2 && statements[1] !is CSharpBreakStatement)) return null
            if (statement is CSharpBlock || statement is CSharpBreakStatement) return null
            val own = template(statement.text, previousMember) ?: return null
            if (template != null && own != template) return null
            template = own
            indent = indent ?: AnalogyGhostSupport.lineIndent(text, statement.textRange.startOffset)
        }
        val whole = fill(template ?: return null, member)
        if (typed.isEmpty() && AnalogyGhostSupport.lineIndent(text, offset) != indent) return null
        return AnalogyGhostSupport.rest(whole, typed)?.let { Suggestion(SuggestionRules.SECTION_ANALOGY, it) }
    }

    private fun arm(leaf: PsiElement, text: CharSequence, offset: Int, typed: String): Suggestion? {
        val expression = PsiTreeUtil.getParentOfType(leaf, CSharpSwitchExpression::class.java, false) ?: return null
        val lineStart = AnalogyGhostSupport.lineStart(text, offset)
        val arm = expression.arms.lastOrNull { it.textRange.startOffset in lineStart until offset } ?: return null
        if (arm.whenClause != null) return null
        arm.expression?.let { if (it.text.isNotBlank() && it.text.trim() != typed) return null }
        val member = arm.pattern?.text?.trim()?.substringAfterLast('.')?.takeIf { n -> n.isNotEmpty() && n.all { it.isLetterOrDigit() || it == '_' } && !n[0].isDigit() } ?: return null
        val index = expression.arms.indexOf(arm)
        if (index < 2) return null
        var template: String? = null
        for (previous in expression.arms.subList(0, index)) {
            if (previous.whenClause != null) return null
            val previousMember = previous.pattern?.text?.trim()?.substringAfterLast('.') ?: return null
            val own = template(previous.expression?.text ?: return null, previousMember) ?: return null
            if (template != null && own != template) return null
            template = own
        }
        val whole = fill(template ?: return null, member) + ","
        return AnalogyGhostSupport.rest(whole, typed)?.let { Suggestion(SuggestionRules.SECTION_ANALOGY, it) }
    }
}

/**
 * `foreach (var item in |` → the collection: the one enumerable local / parameter / member in scope (arrays, lists, `IEnumerable<T>`;
 * strings are not collections here), or among several the one named as the plural of the element (`items`, `orders` for `order`);
 * then `)` unless it is there. Silent otherwise (`foreach (var |` names the element: [NativeCSharpTypingGhost]'s own rule).
 */
object ForeachCollectionRule : NativeCSharpGhostRule {
    private val LINE = Regex("""^\s*(?:await\s+)?foreach\s*\(\s*(?:var|[A-Za-z_][\w.<>,?\[\]]*)\s+@?([A-Za-z_]\w*)\s+in\s+([A-Za-z_][\w.]*)?$""")

    override fun place(text: CharSequence, offset: Int): Boolean {
        if (offset <= 0 || offset > text.length) return false
        val rest = AnalogyGhostSupport.restOfLine(text, offset).trim()
        return (rest.isEmpty() || rest.startsWith(")")) && LINE.matches(AnalogyGhostSupport.lineBefore(text, offset))
    }

    override fun suggest(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): Suggestion? {
        val match = LINE.matchEntire(AnalogyGhostSupport.lineBefore(text, offset)) ?: return null
        val element = match.groupValues[1]
        val typed = match.groupValues[2]
        val leaf = AnalogyGhostSupport.leafAt(file, offset) ?: return null
        val collection = AnalogyGhostSupport.collection(r, leaf, element, element) ?: return null
        return AnalogyGhostSupport.rest(collection.name, typed)?.let { Suggestion(SuggestionRules.FOREACH_COLLECTION, it + AnalogyGhostSupport.close(text, offset)) }
    }
}

/** `for (var i = 0; i < |` → `items.Count; i++` (`.Length` for an array) for the one collection in scope that has the property; then `)`. */
object ForBoundRule : NativeCSharpGhostRule {
    private val LINE = Regex("""^\s*for\s*\(\s*(?:var|int)\s+([A-Za-z_]\w*)\s*=\s*0\s*;\s*([A-Za-z_]\w*)\s*<\s*([A-Za-z_][\w.]*)?$""")

    override fun place(text: CharSequence, offset: Int): Boolean {
        if (offset <= 0 || offset > text.length) return false
        val rest = AnalogyGhostSupport.restOfLine(text, offset).trim()
        if (!(rest.isEmpty() || rest.startsWith(")"))) return false
        val match = LINE.matchEntire(AnalogyGhostSupport.lineBefore(text, offset)) ?: return false
        return match.groupValues[1] == match.groupValues[2]
    }

    override fun suggest(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): Suggestion? {
        val match = LINE.matchEntire(AnalogyGhostSupport.lineBefore(text, offset)) ?: return null
        val index = match.groupValues[1]
        val typed = match.groupValues[3]
        val leaf = AnalogyGhostSupport.leafAt(file, offset) ?: return null
        val collection = AnalogyGhostSupport.collection(r, leaf, index, null) ?: return null
        val property = when {
            collection.type is SemanticType.ArrayOf -> "Length"
            AnalogyGhostSupport.hasValueMember(r, collection.type, "Count") -> "Count"
            AnalogyGhostSupport.hasValueMember(r, collection.type, "Length") -> "Length"
            else -> return null
        }
        val whole = "${collection.name}.$property; $index++"
        return AnalogyGhostSupport.rest(whole, typed)?.let { Suggestion(SuggestionRules.FOR_BOUND, it + AnalogyGhostSupport.close(text, offset)) }
    }
}

/**
 * `nameof(|` in a function with parameters → the one parameter; with several, the one the enclosing `if` tests (`if (order == null)
 * throw new ArgumentNullException(nameof(|`); then `)`. Silent with none or several and no such `if`.
 */
object NameofParameterRule : NativeCSharpGhostRule {
    private val LINE = Regex("""^.*\bnameof\(\s*([A-Za-z_]\w*)?$""")
    private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

    override fun place(text: CharSequence, offset: Int): Boolean {
        if (offset <= 0 || offset > text.length) return false
        val rest = AnalogyGhostSupport.restOfLine(text, offset).trim()
        return (rest.isEmpty() || rest.startsWith(")")) && LINE.matches(AnalogyGhostSupport.lineBefore(text, offset))
    }

    override fun suggest(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): Suggestion? {
        val typed = LINE.matchEntire(AnalogyGhostSupport.lineBefore(text, offset))?.groupValues?.get(1).orEmpty()
        val leaf = AnalogyGhostSupport.leafAt(file, offset) ?: return null
        val parameters = AnalogyGhostSupport.namesInScope(r, leaf).filter { it.parameter }.map { it.name }
        val name = parameters.singleOrNull() ?: run {
            if (parameters.isEmpty()) return null
            val condition = PsiTreeUtil.getParentOfType(leaf, CSharpIfStatement::class.java)?.condition?.text ?: return null
            val tested = IDENTIFIER.findAll(condition).map { it.value }.filter { it in parameters }.distinct().toList()
            tested.singleOrNull() ?: return null
        }
        return AnalogyGhostSupport.rest(name, typed)?.let { Suggestion(SuggestionRules.NAMEOF, it + AnalogyGhostSupport.close(text, offset)) }
    }
}

/** `string.IsNullOrEmpty(|` / `IsNullOrWhiteSpace(|` → the one string parameter or local in scope; then `)`. Silent with none or several. */
object StringArgumentRule : NativeCSharpGhostRule {
    private val LINE = Regex("""^.*\b(?:string|String)\.IsNullOr(?:Empty|WhiteSpace)\(\s*([A-Za-z_][\w.]*)?$""")

    override fun place(text: CharSequence, offset: Int): Boolean {
        if (offset <= 0 || offset > text.length) return false
        val rest = AnalogyGhostSupport.restOfLine(text, offset).trim()
        return (rest.isEmpty() || rest.startsWith(")")) && LINE.matches(AnalogyGhostSupport.lineBefore(text, offset))
    }

    override fun suggest(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): Suggestion? {
        val typed = LINE.matchEntire(AnalogyGhostSupport.lineBefore(text, offset))?.groupValues?.get(1).orEmpty()
        val leaf = AnalogyGhostSupport.leafAt(file, offset) ?: return null
        val strings = AnalogyGhostSupport.namesInScope(r, leaf).filter { it.local && AnalogyGhostSupport.isString(r, it.type) }
        val name = strings.singleOrNull()?.name ?: return null
        return AnalogyGhostSupport.rest(name, typed)?.let { Suggestion(SuggestionRules.STRING_ARGUMENT, it + AnalogyGhostSupport.close(text, offset)) }
    }
}
