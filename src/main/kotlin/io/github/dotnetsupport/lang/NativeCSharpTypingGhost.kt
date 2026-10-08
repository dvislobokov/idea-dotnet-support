package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.inline.completion.DefaultInlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionSkipTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpRequiredMembers
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.suggest.SuggestionRules
import io.github.dotnetsupport.suggest.SuggestionStats

/**
 * Gray text while typing that needs the plugin's semantics (0.1.101), rule-based like [CSharpGhostText], no ML:
 *  - `var user = new |` → `User();`: the type named as the variable (`users` → `List<User>();`), when one type of that name is visible and
 *    is created with no arguments;
 *  - `new User(|)` → `)` passed over and `;`, when the type (or every overload of the call) takes no arguments and `;` is all the statement lacks;
 *  - on the empty line of `new User() {` / `}`: a member a line, the required ones first, each with its value when one is found (`Name = userDto.Name,`);
 *  - `Name = |` in an object initializer: the variable, parameter or member at hand, or a property of one (`userDto.Name`), of a fitting type —
 *    the name equal ignoring case first, then the same type, then the shorter path;
 *  - a name after a type: `UserDto |` → `userDto` (parameters, locals, fields, properties, `foreach`), as the completion list names it;
 *  - with the completion list open (0.1.102): the rest of the selected row and what the rules above give once it is chosen ([afterItem]).
 * The places are told by the text first ([place], cheap, on the EDT); the semantics run in the background on the committed tree. Silent where
 * two continuations are as likely, and never something that does not compile — except the empty values of the members filled in.
 */
object NativeCSharpTypingGhost {
    enum class Place { NEW_TYPE, CLOSE_CALL, FILL, VALUE, ASSIGN, NAME, OVERRIDE }

    /**
     * [skip]: what is in the document already right after the caret and the gray text goes over (`)` of `new User(|)`), then [text].
     * [member]: the member to override [text] writes — once inserted, its `using` directives are added and the caret goes [caretFromEnd]
     * characters back from the end of the text, into the body, as the row of the list does.
     */
    class Suggestion(val rule: String, val text: String, val skip: String = "", val member: NativeCSharpOverrides.Candidate? = null, val caretFromEnd: Int = 0)

    /** `public ov|`, `public override |`, `public override str|`, `public override string D|`: a member to override being typed (0.1.126). */
    private val OVERRIDE_LINE = Regex(
        """^\s*((?:(?:public|protected|internal|private|async|sealed|unsafe|extern)\s+)*)(?:(override)\s+(?:((?:global::)?[A-Za-z_][\w.]*(?:<[^;={}()]*>)?(?:\[[,\s]*\])*\??)\s+)?)?([A-Za-z_]\w*)?$""",
    )

    private val NEW_BY_NAME = Regex("""^\s*var\s+@?([A-Za-z_]\w*)\s*=\s*new(\s+)([A-Za-z_]\w*)?$""")
    private val NEW_CALL = Regex("""(?:\bnew\s+[A-Za-z_][\w.]*(?:<[^()]*>)?|[A-Za-z_]\w*(?:<[^()]*>)?)\s*\($""")
    private val VALUE = Regex("""(?:^|[{,])\s*@?([A-Za-z_]\w*)\s*=\s*([A-Za-z_][\w.]*)?$""")
    /** `member.Name = |` / `this.Name = dt|`: a statement that gives a member of a value its value. */
    private val ASSIGN = Regex("""^\s*(?:this|@?[a-z_]\w*)(?:\.[A-Za-z_]\w*)+\s*=\s*([A-Za-z_]\w*)?$""")

    /** `member.|` at the start of a statement: the member chosen there is assigned ([afterItem]). */
    private val MEMBER_STATEMENT = Regex("""^\s*(?:this|@?[a-z_]\w*)(?:\.[A-Za-z_]\w*)*\.$""")
    private const val TYPE = """(?:global::)?[A-Za-z_][\w.]*(?:<[^;={}()]*>)?(?:\[[,\s]*\])*\??"""
    private val NAME_AFTER_TYPE = Regex("""(?:^|[\s(,\[\]])($TYPE)\s+([A-Za-z_]\w*)?$""")
    private const val MAX_FILLED = 12
    private const val MAX_NESTED = 400

    /** Words the regex of a name takes for a type. */
    private val NOT_TYPES = NativeCSharpCompletionPlace.ALL_KEYWORDS.toSet() - NativeCSharpCompletionPlace.PREDEFINED_TYPES.toSet()

    /** Member types of methods: `public Task |` is a method being named, its name is no noun of the type. */
    private val METHOD_TYPES = setOf("Task", "ValueTask", "IActionResult", "ActionResult", "IResult", "IAsyncEnumerable", "IEnumerator", "void")

    // ---- the text: which rule may have something to say here

    /** The rule whose place [offset] is, by the text alone (it runs on every keystroke): null almost always. */
    fun place(text: CharSequence, offset: Int): Place? {
        if (offset <= 0 || offset > text.length) return null
        val start = lineStart(text, offset)
        val before = text.subSequence(start, offset).toString()
        val after = restOfLine(text, offset)
        if (text.getOrNull(offset) == ')' && text[offset - 1] == '(' && after.trim() == ")" && NEW_CALL.containsMatchIn(before)) return Place.CLOSE_CALL
        val restBlank = after.isBlank()
        if (before.isBlank() && restBlank && previousCode(text, start) == '{' && nextCode(text, offset) == '}' && initializerBrace(text, offset) != null) return Place.FILL
        if (restBlank && NEW_BY_NAME.matches(before)) return Place.NEW_TYPE
        if ((restBlank || after.trimStart().let { it.startsWith("}") || it.startsWith(",") }) && VALUE.containsMatchIn(before) && initializerBrace(text, offset) != null) return Place.VALUE
        if (restBlank && ASSIGN.matches(before) && initializerBrace(text, offset) == null) return Place.ASSIGN
        if (restBlank && overrideLine(before) != null) return Place.OVERRIDE
        if (nameAfterType(before, after)) return Place.NAME
        return null
    }

    /** The parts of a line where a member to override is being typed ([OVERRIDE_LINE]). */
    private class OverrideLine(val modifiers: List<String>, val access: String, val override: Boolean, val written: String)

    /**
     * [before] (the line up to the caret) types a member to override: `override` and what follows it, or the start of `override`
     * ([NativeCSharpOverrides.startsOverride]). The accessibility typed: the gray text only adds, it cannot put the base's one before.
     */
    private fun overrideLine(before: String): OverrideLine? {
        val match = OVERRIDE_LINE.matchEntire(before) ?: return null
        val modifiers = match.groupValues[1].split(' ', '\t').filter { it.isNotEmpty() }
        val access = modifiers.filter { it in NativeCSharpOverrides.ACCESS }.joinToString(" ")
        if (access.isEmpty()) return null
        val override = match.groups[2] != null
        val word = match.groupValues[4]
        if (!override && !NativeCSharpOverrides.startsOverride(word, modifiers)) return null
        val written = if (override) before.substring(match.groups[2]!!.range.last + 1).trimStart() else word
        return OverrideLine(modifiers, access, override, written)
    }

    /** `public override str|`: the rest of the best member to override that begins so, the real base's before `object`'s. */
    private fun override(file: CSharpFile, text: CharSequence, offset: Int): Suggestion? {
        val start = lineStart(text, offset)
        val line = overrideLine(text.subSequence(start, offset).toString()) ?: return null
        val type = PsiTreeUtil.getParentOfType(file.findElementAt(offset - 1), CSharpTypeDeclaration::class.java) ?: return null
        val candidates = NativeCSharpOverrides.overrideCandidates(type, file, offset, "async" in line.modifiers)
        return candidates.sortedByDescending(NativeCSharpOverrides::isOwnBase).firstNotNullOfOrNull { overrideText(file, text, offset, line, it) }
    }

    /** The gray text of [candidate] at [offset]: what its row writes, past what is typed of it; null when that is not a continuation. */
    private fun overrideText(file: CSharpFile, text: CharSequence, offset: Int, line: OverrideLine, candidate: NativeCSharpOverrides.Candidate): Suggestion? {
        // `public override` of a protected member does not compile
        if (candidate.access != line.access) return null
        val whole = if (line.override) candidate else NativeCSharpOverrides.early(candidate, typedAccess = true)
        val indent = text.subSequence(lineStart(text, offset), offset).takeWhile { it == ' ' || it == '\t' }.toString()
        val member = NativeCSharpOverrides.memberText(whole, indent, NativeCSharpOverrides.unit(file))
        if (!member.startsWith(line.written) || member == line.written) return null
        val caretFromEnd = member.length - NativeCSharpOverrides.caretIn(member, whole, indent)
        return Suggestion(SuggestionRules.OVERRIDE, member.substring(line.written.length), member = whole, caretFromEnd = caretFromEnd)
    }

    /**
     * With the list open at a member to override: the row selected — a member of `override |` or `override Name` of `public ov|` — in
     * gray, or the best one under the keyword `override`. Null for other rows and where the row's text does not continue the line.
     */
    fun afterOverrideItem(file: CSharpFile, text: CharSequence, offset: Int, item: LookupElement): Suggestion? {
        if (offset > text.length || restOfLine(text, offset).isNotBlank()) return null
        val line = overrideLine(text.subSequence(lineStart(text, offset), offset).toString()) ?: return null
        if (item.lookupString == "override" && NativeCSharpOverrides.rowOf(item) == null) return if (line.override) null else override(file, text, offset)
        val candidate = NativeCSharpOverrides.rowOf(item) ?: return null
        if (NativeCSharpOverrides.isEarlyRow(item) == line.override) return null
        return overrideText(file, text, offset, line, candidate)
    }

    /** The list at [offset] is one [afterOverrideItem] may follow, its selected row [item]. */
    fun followsOverrideItem(text: CharSequence, offset: Int, item: LookupElement): Boolean =
        (NativeCSharpOverrides.rowOf(item) != null || item.lookupString == "override") && place(text, offset) == Place.OVERRIDE

    /** [CSharpValueGhost] keeps silent where this one answers: `Name = ` of an initializer is no statement, a `;` is wrong there. */
    fun claims(text: CharSequence, offset: Int): Boolean = place(text, offset).let { it == Place.VALUE || it == Place.ASSIGN }

    private fun nameAfterType(before: String, after: String): Boolean {
        val rest = after.trim()
        if (!(rest.isEmpty() || rest.startsWith(")") || rest.startsWith(",") || rest.startsWith("=") || rest.startsWith(";") || rest.startsWith("in ") || rest == "in")) return false
        val match = NAME_AFTER_TYPE.find(before) ?: return false
        val group = match.groups[1] ?: return false
        val head = group.value.substringBefore('<').substringBefore('[').removeSuffix("?").substringAfterLast('.')
        // `member.Admin |`: a small letter before a dot is a value (a local, `this`), the path a member of it and no type
        if (group.value.substringBefore('<').removePrefix("global::").split('.').dropLast(1).any { it.isNotEmpty() && !it[0].isUpperCase() }) return false
        val lead = before.substring(0, group.range.first).trimEnd()
        // `foreach (var |`: the element of the collection after `in`
        if (head == "var") return lead.endsWith("(") && lead.removeSuffix("(").trimEnd().endsWith("foreach")
        // a small first letter is a local or a call being written (`count |`), a keyword no type
        if (head.isEmpty() || !head[0].isUpperCase() || head in NOT_TYPES) return false
        // `x = y |`, `a + b |`: a type stands after a modifier, `(`, `,`, `[...]` or at the start of the line only
        if (lead.isEmpty() || lead.endsWith("(") || lead.endsWith(",") || lead.endsWith("]")) return true
        return lead.takeLastWhile { it.isLetterOrDigit() || it == '_' } in MODIFIERS
    }

    private val QUALIFIED_BEFORE_NAME = Regex("""([A-Za-z_]\w*)\.([A-Za-z_]\w*)(?:<[^;={}()]*>)?\s+[A-Za-z_]?\w*$""")

    /**
     * `JsonSerializer.Serialize |`, `Console.Out |`: the text reads as a declaration of a nested type, but the name after the dot is a
     * member of the type before it (a call, a property being written): no variable to name. By the semantics, as the text cannot tell.
     */
    private fun memberOfType(file: CSharpFile, text: CharSequence, offset: Int): Boolean {
        val line = text.subSequence(lineStart(text, offset), offset)
        val match = QUALIFIED_BEFORE_NAME.find(line) ?: return false
        if (DumbService.isDumb(file.project) || file.compilationUnit == null) return false
        val (qualifier, member) = match.destructured
        return runCatching {
            val r = CSharpSemanticSession(file.project).resolver(file)
            val at = file.findElementAt(lineStart(text, offset).coerceAtMost(file.textLength - 1)) ?: return@runCatching false
            val owner = types(r, at, qualifier).singleOrNull() ?: return@runCatching false
            r.membersNamed(owner, member, 0).any { it is CSharpSymbol.SourceMember || it is CSharpSymbol.LibraryMember }
        }.getOrElse { if (it is com.intellij.openapi.progress.ProcessCanceledException) throw it else false }
    }

    private val MODIFIERS = setOf(
        "public", "internal", "protected", "private", "static", "readonly", "required", "virtual", "override", "abstract", "sealed", "new", "const", "volatile",
        "this", "params", "ref", "out", "in", "scoped", "unsafe", "extern", "partial", "foreach",
    )

    // ---- the semantics

    /** The gray text at [offset] of [file] ([text]: its document, committed). */
    fun suggestion(file: CSharpFile, text: CharSequence, offset: Int): Suggestion? {
        val place = place(text, offset) ?: return null
        if (place == Place.NAME) return if (memberOfType(file, text, offset)) null else name(text, offset)
        if (DumbService.isDumb(file.project) || file.compilationUnit == null) return null
        return runCatching {
            val r = CSharpSemanticSession(file.project).resolver(file)
            when (place) {
                Place.NEW_TYPE -> newType(file, r, text, offset)
                Place.CLOSE_CALL -> closeCall(file, r, offset)
                Place.FILL -> fill(file, r, text, offset)
                Place.VALUE -> value(file, r, text, offset)
                // the semantics know the members of the members (`dto.Name`); where they find nothing, the names of the file ([CSharpValueGhost])
                Place.ASSIGN -> assignment(file, r, text, offset) ?: CSharpValueGhost.suggest(text, offset)?.let { Suggestion(SuggestionRules.VALUE, it) }
                Place.NAME -> null
                Place.OVERRIDE -> override(file, text, offset)
            }
        }.getOrElse { if (it is com.intellij.openapi.progress.ProcessCanceledException) throw it else null }
    }

    /** `var user = new |` → `User();`, `var users = new |` → `List<User>();`. */
    private fun newType(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): Suggestion? {
        val start = lineStart(text, offset)
        val match = NEW_BY_NAME.matchEntire(text.subSequence(start, offset)) ?: return null
        val variable = match.groupValues[1].trimStart('_')
        val typed = match.groupValues[3]
        if (variable.isEmpty()) return null
        val at = file.findElementAt(start + (text.subSequence(start, offset).indexOfFirst { !it.isWhitespace() })) ?: return null
        if (PsiTreeUtil.getParentOfType(at, CSharpBlock::class.java, CSharpGlobalStatement::class.java) == null) return null
        val capitalized = variable.replaceFirstChar { it.uppercase() }
        val written = creatable(r, at, capitalized)?.let { capitalized } ?: run {
            val singular = CSharpExpressionNames.singular(variable).takeIf { it != variable } ?: return null
            val element = singular.replaceFirstChar { it.uppercase() }
            // `users`: a `List<User>` when no type is named `Users` and `List<T>` is imported
            if (types(r, at, capitalized).isNotEmpty() || creatable(r, at, element) == null) return null
            val list = types(r, at, "List", 1).singleOrNull() as? SemanticType.Library ?: return null
            if (list.fullName != "System.Collections.Generic.List`1") return null
            "List<$element>"
        }
        if (!written.startsWith(typed) || written == typed) return null
        return Suggestion(SuggestionRules.NEW_BY_NAME, written.substring(typed.length) + "();")
    }

    private fun types(r: CSharpNameResolver, at: PsiElement, name: String, arity: Int = 0): List<SemanticType> =
        r.typeOrNamespace(at, name, arity).mapNotNull { symbol ->
            when (symbol) {
                is CSharpSymbol.SourceType -> r.selfType(symbol.info)
                is CSharpSymbol.LibraryType -> SemanticType.Library(symbol.type, emptyList())
                else -> null
            }
        }

    /** The one type [name] visible at [at] that `new T()` makes as it is: a class or struct, no arguments wanted, no required members. */
    private fun creatable(r: CSharpNameResolver, at: PsiElement, name: String): SemanticType? {
        val type = types(r, at, name).singleOrNull() ?: return null
        val constructible = when (type) {
            is SemanticType.Source -> (type.info.kind == TypeKind.CLASS || type.info.kind == TypeKind.RECORD || type.info.kind == TypeKind.STRUCT || type.info.kind == TypeKind.RECORD_STRUCT) &&
                type.info.parts.none { "abstract" in it.modifiers || "static" in it.modifiers } && type.info.arity == 0
            is SemanticType.Library -> (type.type.kind == io.github.dotnetsupport.index.IndexedTypeKind.CLASS && !type.type.isAbstract && !type.type.isStatic ||
                type.type.kind == io.github.dotnetsupport.index.IndexedTypeKind.STRUCT) && type.type.ownArity == 0
            else -> false
        }
        if (!constructible) return null
        val required = CSharpRequiredMembers(r)
        if (required.of(type).isNotEmpty() || !required.createdWithoutArguments(type) || !parameterless(type)) return null
        return type
    }

    /** A constructor without arguments is there (or none is written): [CSharpRequiredMembers.createdWithoutArguments] lets a struct through without one. */
    fun parameterless(type: SemanticType): Boolean = when (type) {
        is SemanticType.Source -> type.info.parts.mapNotNull { it.element() as? CSharpTypeDeclaration }.let { declarations ->
            val constructors = declarations.flatMap { d -> d.members.filterIsInstance<CSharpConstructorDeclaration>().filter { c -> c.modifiers.none { it.text == "static" } } }
            declarations.none { it.parameterList?.parameters?.isNotEmpty() == true } &&
                (constructors.isEmpty() || constructors.any { c -> c.parameterList?.parameters.orEmpty().all { it.default != null } && c.modifiers.none { it.text == "private" } })
        }
        is SemanticType.Library -> type.type.members.none { it.kind == io.github.dotnetsupport.index.IndexedMemberKind.CONSTRUCTOR && !it.isStatic } ||
            type.type.members.any { it.kind == io.github.dotnetsupport.index.IndexedMemberKind.CONSTRUCTOR && !it.isStatic && !it.isHidden && it.parameters.all { p -> p.isOptional } }
        else -> false
    }

    /** `new User(|)` → over `)` and `;`, when nothing goes between the parentheses and `;` is what the statement lacks. */
    private fun closeCall(file: CSharpFile, r: CSharpNameResolver, offset: Int): Suggestion? {
        val close = file.findElementAt(offset)?.takeIf { it.text == ")" } ?: return null
        val list = close.parent as? CSharpBaseArgumentList ?: return null
        if (list.arguments.isNotEmpty()) return null
        val takesNothing = when (val owner = list.parent) {
            is CSharpObjectCreationExpression -> {
                if (owner.initializer != null) return null
                val type = NativeCSharpObjectInitializers.createdType(owner, r) ?: return null
                CSharpRequiredMembers(r).of(type).isEmpty() && parameterless(type) && noArguments(type)
            }
            is CSharpInvocationExpression -> {
                val candidates = NativeCSharpParameterInfo.candidates(owner, r)
                candidates.isNotEmpty() && candidates.all { r.signature(it, false)?.isEmpty() == true }
            }
            else -> false
        }
        if (!takesNothing) return null
        if (!NativeCSharpGhostText.needsSemicolon(file, offset + 1)) return null
        return Suggestion(SuggestionRules.CLOSE_CALL, ";", skip = ")")
    }

    /** No constructor of [type] takes arguments: where one does, the arguments are as likely as `()`. */
    private fun noArguments(type: SemanticType): Boolean = when (type) {
        is SemanticType.Source -> type.info.parts.mapNotNull { it.element() as? CSharpTypeDeclaration }
            .flatMap { d -> d.members.filterIsInstance<CSharpConstructorDeclaration>() }.none { c -> c.parameterList?.parameters?.isNotEmpty() == true }
        is SemanticType.Library -> type.type.members.none { it.kind == io.github.dotnetsupport.index.IndexedMemberKind.CONSTRUCTOR && !it.isStatic && !it.isHidden && it.parameters.isNotEmpty() }
        else -> false
    }

    // ---- the open completion list (0.1.102): the gray text after the selected row, what the rules give once it is chosen

    private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")
    private val NEW_BEFORE = Regex("""\bnew\s+$""")

    /**
     * The rule that continues the row [item] (its lookup string) selected in the list open at [offset], by the text alone (it runs on
     * every move of the selection): `new |` + a type, a type where a name follows (`Draft(|`), the value of `Name = |`. Null elsewhere:
     * the gray text can only add to what is typed, so the typed part must begin the row.
     */
    fun itemPlace(text: CharSequence, offset: Int, item: String): Place? {
        if (offset <= 0 || offset > text.length || !IDENTIFIER.matches(item)) return null
        val start = wordStart(text, offset)
        val typed = text.subSequence(start, offset).toString()
        if (!item.startsWith(typed)) return null
        val before = text.subSequence(lineStart(text, start), start).toString()
        val after = restOfLine(text, offset)
        val rest = after.trimStart()
        if (item[0].isUpperCase() && NEW_BEFORE.containsMatchIn(before)) {
            return if (rest.isEmpty() || rest[0] == ')' || rest[0] == ',') Place.NEW_TYPE else null
        }
        if (item[0].isUpperCase() && rest.isEmpty() && MEMBER_STATEMENT.matches(before)) return Place.ASSIGN
        if (item[0].isUpperCase() && nameAfterType("$before$item ", after)) return Place.NAME
        if (place(text, offset) == Place.VALUE) return Place.VALUE
        return null
    }

    /** The gray text after the selected row [item] at [offset] ([itemPlace]): the rest of the row and what follows it once chosen. */
    fun afterItem(file: CSharpFile, text: CharSequence, offset: Int, item: String): Suggestion? {
        val place = itemPlace(text, offset, item) ?: return null
        val start = wordStart(text, offset)
        val rest = item.substring(offset - start)
        if (place == Place.NAME) {
            // the text as it is once the row is chosen and a space typed: the name the rule gives there
            val probe = StringBuilder(text).replace(start, offset, "$item ")
            if (memberOfType(file, probe, start + item.length + 1)) return null
            val name = name(probe, start + item.length + 1)?.text ?: return null
            return Suggestion(SuggestionRules.AFTER_LOOKUP_ITEM, "$rest $name")
        }
        if (DumbService.isDumb(file.project) || file.compilationUnit == null) return null
        return runCatching {
            val r = CSharpSemanticSession(file.project).resolver(file)
            when (place) {
                // `new |` + `Member`: `Member();` where `new Member()` is all there is to write (the 0.1.101 rules of `new` and `)`)
                Place.NEW_TYPE -> {
                    val keyword = text.subSequence(0, start).toString().trimEnd().length - 3
                    val at = file.findElementAt(keyword)?.takeIf { it.text == "new" } ?: return@runCatching null
                    val type = creatable(r, at, item) ?: return@runCatching null
                    if (!noArguments(type)) return@runCatching null
                    val semicolon = restOfLine(text, offset).isBlank() && CSharpCalls.endsStatement(text, keyword)
                    Suggestion(SuggestionRules.AFTER_LOOKUP_ITEM, rest + "()" + if (semicolon) ";" else "")
                }
                // `Email = |` with `dto` selected: `dto.Email`, the value the rule ranks first, when it begins with the row
                // `member.|` + `Admin`: `Admin = isAdmin;`, the value at hand for the member, not the member itself
                Place.ASSIGN -> {
                    val dot = file.findElementAt(start - 1)?.takeIf { it.text == "." } ?: return@runCatching null
                    val access = dot.parent as? CSharpMemberAccessExpression ?: return@runCatching null
                    val receiver = access.expression ?: return@runCatching null
                    val wanted = memberType(r, r.typeOf(receiver) ?: return@runCatching null, item) ?: return@runCatching null
                    val found = bestValue(r, access, item, wanted, "", exclude = receiver.text.trim() + "." + item) ?: return@runCatching null
                    Suggestion(SuggestionRules.AFTER_LOOKUP_ITEM, "$rest = $found;")
                }
                Place.VALUE -> value(file, r, text, offset)?.takeIf { (text.subSequence(start, offset).toString() + it.text).startsWith(item) }
                    ?.let { Suggestion(SuggestionRules.AFTER_LOOKUP_ITEM, it.text) }
                else -> null
            }
        }.getOrElse { if (it is com.intellij.openapi.progress.ProcessCanceledException) throw it else null }
    }

    private fun wordStart(text: CharSequence, offset: Int): Int {
        var start = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        return start
    }

    /** The empty line of `new User() {` … `}`: `Name = value,` a member a line, the required ones first. */
    private fun fill(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): Suggestion? {
        var brace = lineStart(text, offset) - 1
        while (brace >= 0 && text[brace].isWhitespace()) brace--
        val open = file.findElementAt(brace)?.takeIf { it.text == "{" } ?: return null
        val initializer = open.parent as? CSharpInitializerExpression ?: return null
        if (initializer.expressions.any { it.textLength > 0 }) return null
        val creation = initializer.parent as? CSharpBaseObjectCreationExpression ?: return null
        if (creation.initializer != initializer) return null
        val type = NativeCSharpObjectInitializers.createdType(creation, r) ?: return null
        if (isCollection(type, r)) return null
        val required = CSharpRequiredMembers(r).of(type).map { it.name }
        var names = NativeCSharpObjectInitializers.settable(type, r, creation, emptySet())
        if (names.size > MAX_FILLED) names = names.filter { it in required }
        if (names.isEmpty()) return null
        val site = file.findElementAt(offset) ?: open
        val indent = text.subSequence(lineStart(text, offset), offset).toString()
        val unit = NativeCSharpContextEdits.unit(file)
        val memberIndent = indent.ifEmpty { lineIndent(text, open.textRange.startOffset) + unit }
        val lines = names.map { name ->
            val wanted = memberType(r, type, name)
            val value = wanted?.let { bestValue(r, site, name, it, "") }.orEmpty()
            "$name = $value"
        }
        val body = lines.withIndex().joinToString("") { (i, line) -> (if (i == 0) "" else "\n$memberIndent") + line + if (i < lines.size - 1) "," else "" }
        return Suggestion(SuggestionRules.FILL_INITIALIZER, (if (indent.isEmpty()) memberIndent else "") + body)
    }

    private fun isCollection(type: SemanticType, r: CSharpNameResolver): Boolean =
        type is SemanticType.ArrayOf || r.expressions.instanceOf(type, "System.Collections.IEnumerable") != null

    /** `Name = |` / `Name = user|` of an object initializer: the value at hand ([bestValue]). */
    private fun value(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): Suggestion? {
        val start = lineStart(text, offset)
        val match = VALUE.findAll(text.subSequence(start, offset)).lastOrNull() ?: return null
        val member = match.groupValues[1]
        val typed = match.groupValues[2]
        val equals = start + match.range.first + match.value.indexOf('=')
        val assignment = file.findElementAt(equals)?.parent as? CSharpAssignmentExpression ?: return null
        val initializer = assignment.parent as? CSharpInitializerExpression ?: return null
        if (initializer.node.elementType != SyntaxKind.ObjectInitializerExpression) return null
        val creation = initializer.parent as? CSharpBaseObjectCreationExpression ?: return null
        if ((assignment.left as? CSharpIdentifierName)?.identifier?.text != member) return null
        val type = NativeCSharpObjectInitializers.createdType(creation, r) ?: return null
        val wanted = memberType(r, type, member) ?: return null
        val found = bestValue(r, assignment, member, wanted, typed) ?: return null
        return Suggestion(SuggestionRules.MEMBER_VALUE, spaced(text, offset, found.substring(typed.length)))
    }

    /** `member.Name = |` as a statement: the value at hand ([bestValue]), not `member.Name` itself, and `;`. */
    private fun assignment(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): Suggestion? {
        val target = assignmentTarget(file, r, text, offset) ?: return null
        val found = bestValue(r, target.site, target.member, target.wanted, target.typed, target.exclude) ?: return null
        return Suggestion(SuggestionRules.MEMBER_VALUE, spaced(text, offset, found.substring(target.typed.length) + ";"))
    }

    /** `Name =|`: the value comes after a space, as it is written with one typed (`Name = name`) — the same gray text either way. */
    private fun spaced(text: CharSequence, offset: Int, value: String): String = if (offset > 0 && text[offset - 1] == '=') " $value" else value

    /** What a value is wanted for at an offset: the member, its type, what is typed of the value and what it must not be (the target itself). */
    class ValueTarget(val site: PsiElement, val member: String, val wanted: SemanticType, val typed: String, val exclude: String?)

    private fun assignmentTarget(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): ValueTarget? {
        val start = lineStart(text, offset)
        val line = text.subSequence(start, offset)
        val match = ASSIGN.matchEntire(line) ?: return null
        val typed = match.groupValues[1]
        val equals = start + line.lastIndexOf('=')
        val assignment = file.findElementAt(equals)?.parent as? CSharpAssignmentExpression ?: return null
        val left = assignment.left as? CSharpMemberAccessExpression ?: return null
        val member = left.text.substringAfterLast('.').trim()
        val wanted = r.typeOf(left) ?: return null
        return ValueTarget(assignment, member, wanted, typed, left.text.filterNot { it.isWhitespace() })
    }

    private fun initializerTarget(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): ValueTarget? {
        val start = lineStart(text, offset)
        val match = VALUE.findAll(text.subSequence(start, offset)).lastOrNull() ?: return null
        val member = match.groupValues[1]
        val equals = start + match.range.first + match.value.indexOf('=')
        val assignment = file.findElementAt(equals)?.parent as? CSharpAssignmentExpression ?: return null
        val initializer = assignment.parent as? CSharpInitializerExpression ?: return null
        if (initializer.node.elementType != SyntaxKind.ObjectInitializerExpression) return null
        val creation = initializer.parent as? CSharpBaseObjectCreationExpression ?: return null
        if ((assignment.left as? CSharpIdentifierName)?.identifier?.text != member) return null
        val type = NativeCSharpObjectInitializers.createdType(creation, r) ?: return null
        val wanted = memberType(r, type, member) ?: return null
        return ValueTarget(assignment, member, wanted, match.groupValues[2], null)
    }

    /** `Console.BackgroundColor |`, `member.Name na|`: a member at the start of a statement, a space, maybe the start of a value; no `=` yet. */
    private val MEMBER_THEN_SPACE = Regex("""^\s*((?:this|@?[A-Za-z_]\w*)(?:\.[A-Za-z_]\w*)+)\s+([A-Za-z_]\w*)?$""")

    /** `Console.BackgroundColor|` before a space is typed: the list may open after it ([NativeCSharpAssignmentCompletion]). */
    private val MEMBER_AT_END = Regex("""^\s*(?:this|@?[A-Za-z_]\w*)(?:\.[A-Za-z_]\w*)+$""")

    fun memberAtLineEnd(text: CharSequence, offset: Int): Boolean =
        offset <= text.length && restOfLine(text, offset).isBlank() && MEMBER_AT_END.matches(text.subSequence(lineStart(text, offset), offset))

    /**
     * `member.Status`, `Console.BackgroundColor`, `this.Name`, `_order.Status`: the member the chain ends with and its type, segment by
     * segment from a local, a parameter, a member of the types around or a type; null where a segment is not known.
     */
    fun chainMember(r: CSharpNameResolver, site: PsiElement, chain: String): Pair<CSharpSymbol, SemanticType>? {
        val segments = chain.split('.')
        if (segments.size < 2) return null
        val head = segments[0].removePrefix("@")
        var type: SemanticType? = when (head) {
            "this" -> r.syntax.enclosingTypes(site).firstOrNull()?.let(r::selfType)
            else -> NativeCSharpLocals.visible(r.syntax.scopes, site).firstOrNull { it.name == head }?.let { r.valueType(CSharpSymbol.Local(it)) }
                ?: r.syntax.enclosingTypes(site).firstNotNullOfOrNull { info ->
                    r.membersNamed(r.selfType(info), head, 0).firstOrNull { it is CSharpSymbol.SourceMember && !r.isMethod(it) }?.let(r::valueType)
                }
                ?: types(r, site, head).singleOrNull()
        }
        var symbol: CSharpSymbol? = null
        for (segment in segments.drop(1)) {
            val owner = type ?: return null
            symbol = r.membersNamed(owner, segment, 0).firstOrNull { (it is CSharpSymbol.SourceMember || it is CSharpSymbol.LibraryMember) && !r.isMethod(it) } ?: return null
            type = r.valueType(symbol)
        }
        return (symbol ?: return null) to (type ?: return null)
    }

    /**
     * `Console.BackgroundColor |` with no `=` typed: rows that write it with the value, `= ConsoleColor.Black` for each member of an enum, else
     * `= name` / `= dto.Name` for the values at hand; their `;` with them. Null where the line is no member of a value to set; empty where it
     * is one but nothing is found (the names of a declaration, which the text reads there, are no use either).
     */
    fun assignmentRows(file: CSharpFile, text: CharSequence, offset: Int): List<LookupElement>? {
        if (offset > text.length || restOfLine(text, offset).isNotBlank()) return null
        val start = lineStart(text, offset)
        val match = MEMBER_THEN_SPACE.matchEntire(text.subSequence(start, offset)) ?: return null
        if (DumbService.isDumb(file.project) || file.compilationUnit == null) return null
        val chain = match.groupValues[1]
        return runCatching {
            val r = CSharpSemanticSession(file.project).resolver(file)
            val site = file.findElementAt(start + match.groups[1]!!.range.last) ?: return@runCatching null
            val (symbol, type) = chainMember(r, site, chain) ?: return@runCatching null
            if (!NativeCSharpExpectedCompletion.writable(symbol, site)) return@runCatching null
            val enum = (type as? SemanticType.Library)?.type?.kind == io.github.dotnetsupport.index.IndexedTypeKind.ENUM || (type as? SemanticType.Source)?.info?.kind == TypeKind.ENUM
            val values: List<Pair<String, String?>> = if (enum) {
                val qualifier = type.minimalDisplay ?: type.name
                NativeCSharpExpectedCompletion.enumMembers(type).map { (member, value) -> "$qualifier.$member" to value }
            } else ranked(r, site, chain.substringAfterLast('.'), type, "", chain).take(MAX_ROWS).map { it.first to null }
            values.mapIndexed { i, (value, right) ->
                val written = "= $value"
                val element = LookupElementBuilder.create(value.substringAfterLast('.')).withLookupStrings(listOf(value.substringAfterLast('.'), value))
                    .withPresentableText(written).withTypeText(right).withIcon(if (enum) AllIcons.Nodes.Constant else AllIcons.Nodes.Variable)
                    .withInsertHandler { context, _ ->
                        context.document.replaceString(context.startOffset, context.tailOffset, written)
                        context.tailOffset = context.startOffset + written.length
                        context.editor.caretModel.moveToOffset(context.tailOffset)
                        NativeCSharpExpectedCompletion.closeStatement(context)
                        context.commitDocument()
                    }
                element.putUserData(NativeCSharpCompletion.NATIVE, true)
                PrioritizedLookupElement.withPriority(element, NativeCSharpCompletion.DECLARATION + 10 - i * 0.01)
            }
        }.getOrElse { if (it is com.intellij.openapi.progress.ProcessCanceledException) throw it else null }
    }

    private const val MAX_ROWS = 6

    /** The values the list offers at `Name = |` / `member.Name = |`, best first: the paths into the values at hand (`dto.Name`) among them. */
    fun valuesAt(file: CSharpFile, text: CharSequence, offset: Int): List<String> {
        val place = place(text, offset)
        if (place != Place.VALUE && place != Place.ASSIGN) return emptyList()
        if (DumbService.isDumb(file.project) || file.compilationUnit == null) return emptyList()
        return runCatching {
            val r = CSharpSemanticSession(file.project).resolver(file)
            val target = (if (place == Place.VALUE) initializerTarget(file, r, text, offset) else assignmentTarget(file, r, text, offset)) ?: return@runCatching emptyList()
            ranked(r, target.site, target.member, target.wanted, "", target.exclude).map { it.first }
        }.getOrElse { if (it is com.intellij.openapi.progress.ProcessCanceledException) throw it else emptyList() }
    }

    fun memberType(r: CSharpNameResolver, type: SemanticType, member: String): SemanticType? =
        r.membersNamed(type, member, 0).firstOrNull { !r.isMethod(it) }?.let(r::valueType)

    private class Candidate(val path: String, val type: SemanticType, val depth: Int, val local: Boolean)

    /**
     * What to give the member [member] of type [wanted] at [site]: the locals and parameters at hand, the fields and properties of the
     * types around, and their properties (`userDto.Name`) two deep, of a type that converts to [wanted] and named as the member (ignoring
     * case) or ending with its name. The equal name first, then the same type, then the shorter path; null when two are as good.
     */
    fun bestValue(r: CSharpNameResolver, site: PsiElement, member: String, wanted: SemanticType, typed: String, exclude: String? = null): String? {
        val scored = ranked(r, site, member, wanted, typed, exclude)
        val best = scored.firstOrNull() ?: return null
        if (scored.size > 1 && scored[1].second == best.second) return null
        return best.first.takeIf { it != typed }
    }

    /** The candidates of [bestValue] with their scores, best first; [exclude]: the place the value goes to (`member.Name = member.Name`). */
    private fun ranked(r: CSharpNameResolver, site: PsiElement, member: String, wanted: SemanticType, typed: String, exclude: String?): List<Pair<String, Int>> {
        val roots = ArrayList<Candidate>()
        for (symbol in NativeCSharpLocals.visible(r.syntax.scopes, site)) {
            if (symbol.kind != LocalSymbolKind.LOCAL && symbol.kind != LocalSymbolKind.PARAMETER && symbol.kind != LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER) continue
            val type = r.valueType(CSharpSymbol.Local(symbol)) ?: continue
            roots += Candidate(symbol.name, type, 0, local = true)
        }
        val static = NativeCSharpLocals.inStaticContext(site)
        for (info in r.syntax.enclosingTypes(site)) for ((key, m) in r.syntax.membersOf(info)) {
            ProgressManager.checkCanceled()
            if ('<' in key || '`' in key || m.nestedType != null || roots.any { it.path == key }) continue
            val kind = NativeCSharpMembers.kind(m)
            if (kind != NativeCSharpMembers.Kind.FIELD && kind != NativeCSharpMembers.Kind.PROPERTY && kind != NativeCSharpMembers.Kind.CONSTANT) continue
            if (static && !NativeCSharpMembers.isStatic(m)) continue
            val type = r.membersNamed(r.selfType(info), key, 0).firstOrNull()?.let(r::valueType) ?: continue
            roots += Candidate(key, type, 0, local = false)
        }
        val all = ArrayList<Candidate>(roots)
        var visited = 0
        var level = roots.toList()
        for (depth in 1..2) {
            val next = ArrayList<Candidate>()
            for (owner in level) {
                val source = owner.type as? SemanticType.Source ?: continue
                if (source.info.kind == TypeKind.ENUM) continue
                for ((key, m) in r.syntax.membersOf(source.info)) {
                    ProgressManager.checkCanceled()
                    if (++visited > MAX_NESTED) break
                    if ('<' in key || '`' in key || m.nestedType != null || NativeCSharpMembers.isStatic(m)) continue
                    val kind = NativeCSharpMembers.kind(m)
                    if (kind != NativeCSharpMembers.Kind.FIELD && kind != NativeCSharpMembers.Kind.PROPERTY) continue
                    if (m.modifiers.none { it == "public" || it == "internal" }) continue
                    val type = r.membersNamed(source, key, 0).firstOrNull()?.let(r::valueType) ?: continue
                    next += Candidate("${owner.path}.$key", type, depth, owner.local)
                }
            }
            all += next
            level = next
        }
        val scored = all.mapNotNull { candidate ->
            if (!candidate.path.startsWith(typed) || candidate.path == exclude) return@mapNotNull null
            val likeness = CSharpNameLikeness.of(member, candidate.path.substringAfterLast('.'))
            if (likeness == CSharpNameLikeness.Likeness.NONE) return@mapNotNull null
            val conversion = r.conversion(candidate.type, wanted)
            if (conversion != CSharpNameResolver.Conversion.IDENTITY && conversion != CSharpNameResolver.Conversion.IMPLICIT) return@mapNotNull null
            var score = if (likeness == CSharpNameLikeness.Likeness.EXACT) 100 else 40
            if (conversion == CSharpNameResolver.Conversion.IDENTITY) score += 20
            score -= 10 * candidate.depth
            if (candidate.local) score += 3
            candidate.path to score
        }.distinctBy { it.first }.sortedByDescending { it.second }
        return scored
    }

    /** `UserDto |` → `userDto`: the name the completion list gives to the declaration, the type's whole name (Rider's first). */
    private fun name(text: CharSequence, offset: Int): Suggestion? {
        var start = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        val typed = text.subSequence(start, offset).toString()
        val probe = NativeCSharpSyntaxModel.parse(StringBuilder(text).insert(offset, PROBE))
        val leaf = probe.findElementAt(start) ?: return null
        if (leaf.text != typed + PROBE) return null
        val name = when (val parent = leaf.parent) {
            is CSharpForEachStatement -> {
                if (parent.identifier != leaf) return null
                val type = parent.type?.text?.trim() ?: return null
                if (type == "var") {
                    val collection = parent.expression?.text?.trim()?.takeIf { it.matches(Regex("""@?[A-Za-z_][\w.]*""")) } ?: return null
                    CSharpExpressionNames.forElement(collection).firstOrNull()?.takeIf { it != "item" }
                } else CSharpVariableNames.full(type)
            }
            else -> {
                val place = NativeCSharpCompletionPlace.of(leaf)?.takeIf { it.kind == NativeCompletionKind.DECLARATION_NAME } ?: return null
                val type = place.declaredType ?: return null
                if (place.nameStyle == NativeCSharpCompletionPlace.NameStyle.PUBLIC_MEMBER && type.substringBefore('<').substringAfterLast('.') in METHOD_TYPES) return null
                CSharpVariableNames.full(type, place.nameStyle)
            }
        } ?: return null
        // the names declared around (other parameters, locals above): a second `userDto` does not compile
        val taken = NativeCSharpLocals.visible(NativeCSharpScopes.of(probe), leaf).mapTo(HashSet()) { it.name }
        val unique = CSharpVariableNames.unique(name, taken)
        if (!unique.startsWith(typed) || unique == typed) return null
        return Suggestion(SuggestionRules.DECLARATION_NAME, unique.substring(typed.length))
    }

    private const val PROBE = "IntellijIdeaRulezzz"

    // ---- text

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    private fun restOfLine(text: CharSequence, offset: Int): String {
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return text.substring(offset, end)
    }

    private fun lineIndent(text: CharSequence, offset: Int): String = text.subSequence(lineStart(text, offset), offset).takeWhile { it == ' ' || it == '\t' }.toString()

    private fun previousCode(text: CharSequence, from: Int): Char? {
        var i = from - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        return text.getOrNull(i)
    }

    private fun nextCode(text: CharSequence, from: Int): Char? {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return text.getOrNull(i)
    }

    /** The `{` of `new T(...) {` / `new T {` that [offset] is directly in, by the text: null in a block, a lambda, an array or a collection of other kind. */
    fun initializerBrace(text: CharSequence, offset: Int): Int? {
        var depth = 0
        var i = offset - 1
        val limit = (offset - 20_000).coerceAtLeast(0)
        while (i >= limit) {
            when (text[i]) {
                '}', ')', ']' -> depth++
                '(', '[' -> if (depth == 0) return null else depth--
                ';' -> if (depth == 0) return null
                '{' -> if (depth == 0) break else depth--
            }
            i--
        }
        if (i < limit) return null
        // `new User() {`, `new User {`, `new List<User>\n{`, `new() {`
        return if (NEW_HEAD.containsMatchIn(text.subSequence((i - 300).coerceAtLeast(0), i))) i else null
    }

    private val NEW_HEAD = Regex("""\bnew\s*(?:[A-Za-z_][\w.]*\s*(?:<[^;{}]*>)?\s*)?(?:\([^;{}]*\))?\s*$""")
}

/** The provider of [NativeCSharpTypingGhost]: the text tells the place on the EDT, the semantics answer in the background. */
class NativeCSharpTypingGhostProvider : InlineCompletionProvider {
    override val id: InlineCompletionProviderID = InlineCompletionProviderID("io.github.dotnetsupport.typing")
    override val providerPresentation = CSharpGhostTextProvider.presentation()

    /**
     * With the completion list open (0.1.102) the gray text follows its selected row, as the platform's Full Line completion does: each
     * move of the selection is a [InlineCompletionEvent.LookupChange] ([restartOn] asks again), Tab takes the gray text (the platform's
     * `InlineCompletionActionsPromoter` puts the insertion before the list's Tab and closes the list), Enter still chooses the row.
     * Any other event while the list is open says nothing: what is under the caret is the list's.
     */
    // the events of the list (its cancel among them) come outside a read action: the tree is read under one
    override fun isEnabled(event: InlineCompletionEvent): Boolean = com.intellij.openapi.application.runReadAction { enabled(event) }

    private fun enabled(event: InlineCompletionEvent): Boolean {
        val request = event.toRequest() ?: return false
        val file = request.file as? CSharpFile ?: return false
        if (!NativeCSharpEditing.usable(file)) return false
        val text = request.document.immutableCharSequence
        if (event is InlineCompletionEvent.LookupChange) {
            val item = request.lookupElement ?: return false
            // `Member { … }`: its initializer is the gray text's own business once written (the members of an empty one)
            if (NativeCSharpObjectInitializers.isInitializerRow(item)) return false
            if (NativeCSharpTypingGhost.followsOverrideItem(text, request.endOffset, item)) return true
            return NativeCSharpTypingGhost.itemPlace(text, request.endOffset, item.lookupString) != null
        }
        if (event !is InlineCompletionEvent.LookupCancelled && LookupManager.getActiveLookup(request.editor) != null) return false
        return NativeCSharpTypingGhost.place(text, request.endOffset) != null
    }

    /** The gray text of one row is wrong for the next one: the platform keeps a session over lookup events unless asked to restart. */
    override fun restartOn(event: InlineCompletionEvent): Boolean = event is InlineCompletionEvent.InlineLookupEvent

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val file = request.file as? CSharpFile
        val element = request.lookupElement?.takeIf { request.event is InlineCompletionEvent.LookupChange }
        val item = element?.lookupString
        val ghost = if (file == null) null else constrainedReadAction(ReadConstraint.withDocumentsCommitted(file.project)) {
            val text = request.document.immutableCharSequence
            val offset = request.endOffset
            // a member to override selected (or the keyword `override` at `public ov|`): what choosing it writes (0.1.126)
            if (element != null && NativeCSharpTypingGhost.followsOverrideItem(text, offset, element)) {
                return@constrainedReadAction NativeCSharpTypingGhost.afterOverrideItem(file, text, offset, element)
            }
            if (item != null) return@constrainedReadAction NativeCSharpTypingGhost.afterItem(file, text, offset, item)
            NativeCSharpTypingGhost.suggestion(file, text, offset)
                // `Save(|)`: this provider stands before the one of the arguments, which gets the place back when there is no `;` to give
                ?: if (NativeCSharpTypingGhost.place(text, offset) == NativeCSharpTypingGhost.Place.CLOSE_CALL && CSharpFeatures.native(CSharpFeature.DOCUMENTATION, file)) {
                    NativeCSharpLambdaGhost.suggestion(file, text, offset)?.let { NativeCSharpTypingGhost.Suggestion(it.rule, it.text) }
                } else null
        }
        if (ghost != null) {
            shownRule = ghost.rule
            shownSkip = ghost.skip
            shownMember = ghost.member
            shownCaretFromEnd = ghost.caretFromEnd
            SuggestionStats.getInstance().shown(ghost.rule, readAction { GhostPlace.of(request) })
        }
        return InlineCompletionSingleSuggestion.build(UserDataHolderBase()) {
            if (ghost != null) {
                if (ghost.skip.isNotEmpty()) emit(InlineCompletionSkipTextElement(ghost.skip))
                emit(InlineCompletionGrayTextElement(ghost.text))
            }
        }
    }

    @Volatile
    private var shownRule: String? = null

    @Volatile
    private var shownSkip: String = ""

    @Volatile
    private var shownMember: NativeCSharpOverrides.Candidate? = null

    @Volatile
    private var shownCaretFromEnd: Int = 0

    override val insertHandler: InlineCompletionInsertHandler = object : InlineCompletionInsertHandler {
        override fun afterInsertion(environment: InlineCompletionInsertEnvironment, elements: List<InlineCompletionElement>) {
            DefaultInlineCompletionInsertHandler.INSTANCE.afterInsertion(environment, elements)
            shownRule?.let { SuggestionStats.getInstance().accepted(it) }
            // should the platform write the skipped `)` again with the text, the one that was there goes (only `)` stood after the caret)
            val skip = shownSkip
            if (skip.isNotEmpty()) {
                val document = environment.editor.document
                val end = environment.insertedRange.endOffset
                if (document.charsSequence.startsWith(skip, end)) {
                    document.deleteString(end, end + skip.length)
                    PsiDocumentManager.getInstance(environment.file.project).commitDocument(document)
                }
            }
            // a member to override: its `using` directives and the caret in its body, as its row of the list leaves them
            val member = shownMember
            if (member != null && shownRule == SuggestionRules.OVERRIDE) {
                val document = environment.editor.document
                val project = environment.file.project
                val caret = NativeCSharpOverrides.addUsings(project, document, member, environment.insertedRange.endOffset - shownCaretFromEnd)
                environment.editor.caretModel.moveToOffset(caret)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        }
    }
}

/**
 * `public clas|` listed `class` twice: the keyword and the live template `class` (which writes `public class …` and would double the
 * `public`). Where the native list has the keyword, the template of that name is left out — Ctrl+J still has it. In front of the platform's
 * live templates, so their rows come through here; they wait for the end of the list, the keywords may come after them.
 */
class CSharpTemplateKeywordDedupe : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile !is CSharpFile || !CSharpFeatures.native(CSharpFeature.COMPLETION, parameters.originalFile)) return
        val keywords = HashSet<String>()
        val templates = ArrayList<com.intellij.codeInsight.completion.CompletionResult>()
        result.runRemainingContributors(parameters) { found ->
            val element = found.lookupElement
            when {
                LiveTemplateRows.isLiveTemplate(element) -> templates += found
                else -> {
                    if (CSharpCompletionBehaviourContributor.isNative(element) && CSharpCompletionBehaviourContributor.isKeyword(element)) keywords += element.lookupString
                    result.passResult(found)
                }
            }
        }
        for (template in templates) if (template.lookupElement.lookupString !in keywords) result.passResult(template)
    }
}

private object LiveTemplateRows {
    fun isLiveTemplate(item: com.intellij.codeInsight.lookup.LookupElement): Boolean {
        var current: com.intellij.codeInsight.lookup.LookupElement? = item
        while (current != null) {
            if (current.javaClass.name.contains("LiveTemplateLookupElement")) return true
            current = (current as? com.intellij.codeInsight.lookup.LookupElementDecorator<*>)?.delegate
        }
        return false
    }
}

/**
 * The name of the file after `class ` / `record ` / `struct ` / `interface ` / `enum ` (Rider offers it): `OrderEndpoints`, and the body when
 * none follows, the name left to edit as in a template. Not when a type of that name is in the file already.
 */
/**
 * `Name = |` of an initializer, `member.Name = |`: the values the gray text weighs, as rows of the list, the paths into the values at
 * hand among them (`dto.Name`, `dto.Email`) — the list itself offers `dto` and no more.
 */
class NativeCSharpValueCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? CSharpFile ?: return
        if (!NativeCSharpEditing.usable(file)) return
        val text = parameters.editor.document.immutableCharSequence
        val values = NativeCSharpTypingGhost.valuesAt(file, text, parameters.offset)
        // the order of the gray text: a path under a local or parameter it ranks lower (`name` before `dto.Name`), at the top before the rest
        val firstLocal = values.indexOfFirst { '.' !in it }.let { if (it < 0) Int.MAX_VALUE else it }
        for ((i, path) in values.withIndex().filter { '.' in it.value }.take(MAX).map { it.index to it.value }) {
            val element = LookupElementBuilder.create(path).withIcon(AllIcons.Nodes.Property).withTypeText("value")
            element.putUserData(NativeCSharpCompletion.NATIVE, true)
            val priority = if (i < firstLocal) NativeCSharpCompletion.LOCAL + 1 else NativeCSharpCompletion.PARAMETER - 1
            result.addElement(PrioritizedLookupElement.withPriority(element, priority - i * 0.01))
        }
    }

    private companion object {
        const val MAX = 6
    }
}

/**
 * `Console.BackgroundColor |`: no need to type `=` — the list opens by itself after the space with `= ConsoleColor.Black`… (the members of
 * an enum) or `= name`, `= dto.Name` (the values at hand), and Enter writes the assignment with its `;`. Where the line is a member of a
 * value, the names of a declaration (`backgroundColor`) that the text also reads there are not offered.
 */
class NativeCSharpAssignmentCompletion : CompletionContributor() {
    override fun invokeAutoPopup(position: PsiElement, typeChar: Char): Boolean {
        if (typeChar != ' ') return false
        val file = position.containingFile as? CSharpFile ?: return false
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, file)) return false
        val text = file.viewProvider.document?.charsSequence ?: return false
        return NativeCSharpTypingGhost.memberAtLineEnd(text, position.textRange.endOffset)
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, file)) return
        val rows = NativeCSharpTypingGhost.assignmentRows(file, parameters.editor.document.immutableCharSequence, parameters.offset) ?: return
        rows.forEach(result::addElement)
        result.stopHere()
    }
}

class NativeCSharpTypeNameCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? CSharpFile ?: return
        val leaf = parameters.position
        val declaration = leaf.parent as? CSharpBaseTypeDeclaration ?: return
        if (CSharpDeclarationNames.nameElement(declaration) != leaf) return
        // `partial class |` lists the other parts of a type (NativeCSharpLanguageCompletion), not a new name
        if (declaration.modifiers.any { it.text == "partial" }) return
        val name = file.virtualFile?.nameWithoutExtension?.substringBefore('.')?.takeIf { it.matches(Regex("""[A-Za-z_]\w*""")) } ?: return
        val isInterface = declaration is CSharpInterfaceDeclaration
        if (isInterface != (name.length > 1 && name[0] == 'I' && name[1].isUpperCase())) return
        val taken = PsiTreeUtil.findChildrenOfType(file, CSharpBaseTypeDeclaration::class.java)
            .any { it.textRange != declaration.textRange && CSharpDeclarationNames.nameElement(it)?.text == name }
        if (taken || !result.prefixMatcher.prefixMatches(name)) return
        val element = LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Class).bold().withTypeText("file name")
            .withInsertHandler { context, _ ->
                val document = context.document
                val text = document.charsSequence
                var next = context.tailOffset
                while (next < text.length && (text[next] == ' ' || text[next] == '\t')) next++
                // something follows the name already (a body, a base list, parameters): the name alone
                if (next < text.length && text[next] != '\n' && text[next] != '\r') return@withInsertHandler
                val end = text.indexOf('\n', context.tailOffset).let { if (it < 0) text.length else it }
                if (text.substring(context.tailOffset, end).isNotBlank()) return@withInsertHandler
                val after = text.subSequence(end, text.length).trimStart()
                if (after.startsWith("{") || after.startsWith(":") || after.startsWith("where")) return@withInsertHandler
                val indent = text.subSequence(text.lastIndexOf('\n', context.startOffset - 1) + 1, context.startOffset).takeWhile { it == ' ' || it == '\t' }.toString()
                val unit = NativeCSharpContextEdits.unit(context.file)
                document.deleteString(context.startOffset, context.tailOffset)
                context.editor.caretModel.moveToOffset(context.startOffset)
                val manager = TemplateManager.getInstance(context.project)
                val template = manager.createTemplate("", "", "\$NAME$\n$indent{\n$indent$unit\$END$\n$indent}")
                template.isToReformat = false
                template.addVariable("NAME", ConstantNode(name), true)
                manager.startTemplate(context.editor, template)
            }
        element.putUserData(NativeCSharpCompletion.NATIVE, true)
        result.addElement(PrioritizedLookupElement.withPriority(element, NativeCSharpCompletion.DECLARATION).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) })
    }
}
