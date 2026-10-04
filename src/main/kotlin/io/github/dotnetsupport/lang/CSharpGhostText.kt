package io.github.dotnetsupport.lang

import com.intellij.codeInsight.inline.completion.DefaultInlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.Project
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.suggest.SuggestionRules
import io.github.dotnetsupport.suggest.SuggestionStats
import io.github.dotnetsupport.templates.CSharpNamespaces

/**
 * The gray text of a declaration that can continue one way only, accepted with Tab: `{ get; set; }` after `public string Name`,
 * `new();` after `private readonly List<int> _items = `, `_name = name;` on an empty line of a constructor, the namespace of the folder,
 * the type named after the file, `ILogger<` of the class, the parameters of a constructor, `catch (Exception e)`. All by tokens and the
 * scanner of declarations, so it is there at once and while the solution is still loading. Silent where two continuations are
 * possible: a wrong gray text costs more than none.
 */
object CSharpGhostText {
    private const val MODIFIERS = "public|internal|protected|private|static|virtual|override|abstract|sealed|required|new|readonly|async|const|volatile|unsafe|extern|partial"
    private const val TYPE = """(?:global::)?[A-Za-z_][\w.]*(?:<[^;={}()]*>)?(?:\[[,\s]*\])*\??"""
    private val DECLARATION = Regex("""^\s*((?:(?:$MODIFIERS)\s+)*)($TYPE)\s+([A-Za-z_]\w*)(\s*)$""")
    private val INITIALIZER = Regex("""^\s*((?:(?:$MODIFIERS)\s+)*)($TYPE)\s+([A-Za-z_]\w*)\s*(?:\{[^{}]*\}\s*)?=(\s*)$""")

    /** Words the regex would take for a type. */
    private val NOT_TYPES = MODIFIERS.split('|').toSet() + setOf(
        "void", "class", "struct", "interface", "enum", "record", "delegate", "event", "operator", "implicit", "explicit", "namespace", "using",
        "return", "var", "throw", "await", "yield", "case", "goto", "else", "in", "is", "as", "out", "ref", "typeof", "nameof", "this", "base",
    )

    /** A member of these types is a method: an action of a controller, something awaited. */
    private val METHOD_TYPES = setOf("Task", "ValueTask", "IAsyncEnumerable", "IActionResult", "ActionResult", "IResult", "IEnumerator")

    /** `GetName`, `Save`, `OnClick`: a verb first is a method. `Settings`, `Address`, `Total` are not: the next letter is small. */
    private val VERB = Regex(
        "^(?:Get|Set|Load|Save|Find|Create|Add|Remove|Delete|Update|Build|Run|Execute|Handle|Process|Try|Parse|Convert|To|Calculate|Compute|Send|Read|Write|Open|Close|" +
            "Validate|Map|Make|Init|Initialize|Dispose|Start|Stop|Check|Apply|Register|Configure|Invoke|Fetch|Resolve|Render|Format|Generate|Ensure|Reset|Clear|Show|Hide|On|" +
            "Equals|Compare|Clone|Copy|Move|Insert|Append|Print|Log|Notify|Publish|Subscribe|Wait|Cancel|Abort|Connect|Disconnect|Select|Sort|Filter|Merge|Split)(?=[A-Z]|$)",
    )

    /** What `new()` is unambiguous for: collections and a few classes that are made with no arguments. */
    private val CONSTRUCTED = setOf(
        "List", "Dictionary", "HashSet", "Queue", "Stack", "LinkedList", "SortedDictionary", "SortedSet", "SortedList", "ConcurrentDictionary", "ConcurrentQueue",
        "ConcurrentBag", "ConcurrentStack", "ObservableCollection", "Collection", "StringBuilder", "object", "Object", "Lock", "CancellationTokenSource", "Random",
        "Stopwatch", "HttpClient", "MemoryStream", "JsonSerializerOptions",
    )
    private val ARGUMENTS = mapOf("SemaphoreSlim" to "1, 1")
    private val IMPLEMENTATIONS = mapOf(
        "IList" to "List", "ICollection" to "List", "IEnumerable" to "List", "IReadOnlyList" to "List", "IReadOnlyCollection" to "List",
        "IDictionary" to "Dictionary", "IReadOnlyDictionary" to "Dictionary", "ISet" to "HashSet", "IReadOnlySet" to "HashSet",
    )

    /** What the text alone does not tell. The namespace and its style are asked for only by the rule that needs them: they cost a look at the files. */
    open class Context(
        /** The language of the project has `new()` (C# 9). */
        open val targetTypedNew: Boolean = true,
        /** `Order` for `Order.cs` and for `Order.Part.cs`. */
        val fileName: String? = null,
    ) {
        /** The namespace of the folder: `RootNamespace` + the path. */
        open val namespace: String? get() = null
        open val fileScopedNamespace: Boolean get() = true
    }

    /** Gray text and the rule it comes from: the rule is what the statistics of suggestions count by. */
    class Ghost(val rule: String, val text: String)

    /** The gray text at [offset], or null. */
    fun suggest(text: CharSequence, offset: Int, context: Context = Context()): String? = ghost(text, offset, context)?.text

    fun ghost(text: CharSequence, offset: Int, context: Context = Context()): Ghost? {
        if (offset < 0 || offset > text.length) return null
        fun of(rule: String, found: String?) = found?.let { Ghost(rule, it) }
        return of(SuggestionRules.CONSTRUCTOR_ASSIGNMENT, constructorAssignment(text, offset))
            ?: of(SuggestionRules.INITIALIZER, initializer(text, offset, context.targetTypedNew))
            ?: of(SuggestionRules.AUTO_PROPERTY, autoProperty(text, offset))
            ?: of(SuggestionRules.NAMESPACE, namespaceName(text, offset, context))
            ?: of(SuggestionRules.TYPE_NAME, typeName(text, offset, context.fileName))
            ?: of(SuggestionRules.LOGGER, loggerType(text, offset))
            ?: of(SuggestionRules.CONSTRUCTOR_PARAMETERS, constructorParameters(text, offset))
            ?: of(SuggestionRules.CATCH, catchClause(text, offset))
            ?: of(SuggestionRules.NOT_IMPLEMENTED, notImplemented(text, offset))
            ?: of(SuggestionRules.BREAK, breakInCase(text, offset))
            ?: of(SuggestionRules.VALUE, CSharpValueGhost.suggest(text, offset))
            ?: of(SuggestionRules.SEMICOLON, semicolon(text, offset))
    }

    /** `break;` on the first (empty) line of a `case` / `default` section — the line right above is the label. */
    fun breakInCase(text: CharSequence, offset: Int): String? {
        if (!isBlankLine(text, offset)) return null
        val previous = previousNonBlankLine(text, offset)?.trim() ?: return null
        val isLabel = previous.endsWith(":") && (previous.startsWith("case ") || previous == "default:" || previous == "default :")
        return if (isLabel) "break;" else null
    }

    /**
     * A gray `;` at the end of a statement that is missing it. Stricter than Complete Statement (an explicit action): only when the
     * statement clearly ends — a call / indexer close or a literal — never after a bare name still being typed, which would fight the value
     * suggestion and flicker on every letter.
     */
    fun semicolon(text: CharSequence, offset: Int): String? {
        val line = lineBefore(text, offset)?.takeIf { it.isNotBlank() } ?: return null
        if (nextCharacter(text, offset) == ';' || !CSharpCompleteStatement.needsSemicolon(line)) return null
        val last = CSharpExpressions.tokenize(line).lastOrNull() ?: return null
        val ends = when (last.type) {
            CSharpTokenTypes.RPAREN, CSharpTokenTypes.RBRACKET, CSharpTokenTypes.NUMBER -> true
            // a closed string / char only: an unterminated literal still has its token, but the statement is not finished
            CSharpTokenTypes.STRING -> last.text.length >= 2 && last.text.endsWith("\"")
            CSharpTokenTypes.CHAR -> last.text.length >= 2 && last.text.endsWith("'")
            else -> false
        }
        return if (ends) ";" else null
    }

    /** `public int Parse(string s)` -> ` => throw new NotImplementedException();`, for a method header in a class / struct / record. */
    fun notImplemented(text: CharSequence, offset: Int): String? {
        val line = lineBefore(text, offset) ?: return null
        val match = METHOD_HEAD.matchEntire(line) ?: return null
        val modifiers = match.groupValues[1].trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (modifiers.any { it in NOT_IMPLEMENTABLE }) return null
        if (baseOf(match.groupValues[2]) in (NOT_TYPES - "void") || match.groupValues[3] in NOT_TYPES) return null
        if (nextCharacter(text, offset).let { it == '{' || it == '=' || it == ';' }) return null
        val enclosing = enclosingType(text, offset) ?: return null
        if (enclosing.kind == DeclarationKind.INTERFACE || enclosing.kind == DeclarationKind.ENUM) return null
        return " => throw new NotImplementedException();"
    }

    /** `public string Name` -> ` { get; set; }`. */
    fun autoProperty(text: CharSequence, offset: Int): String? {
        val line = lineBefore(text, offset) ?: return null
        val match = DECLARATION.matchEntire(line) ?: return null
        val modifiers = match.groupValues[1].trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val type = match.groupValues[2]
        val name = match.groupValues[3]
        if (modifiers.none { it in ACCESS } || modifiers.any { it in NOT_FOR_PROPERTY }) return null
        if (baseOf(type) in NOT_TYPES || baseOf(type) in METHOD_TYPES || name in NOT_TYPES) return null
        if (!name[0].isUpperCase() || name.endsWith("Async") || VERB.containsMatchIn(name)) return null
        // something is already there: the body of a method on the next line, an initializer
        if (nextCharacter(text, offset).let { it == '{' || it == '(' || it == '=' || it == ';' }) return null
        return (if (match.groupValues[4].isEmpty()) " " else "") + "{ get; set; }"
    }

    /**
     * The name of a property is being typed: `public RankedOrder ` (no name yet) or `public RankedOrder Ord` — a line that gets the gray
     * ` { get; set; }` ([autoProperty]) once the name is there. The auto-popup of the server's name suggestions (`rankedOrder`, `order1`,
     * `Order`) is kept away from it ([CSharpPropertyNameConfidence]): open, the list took Tab and hid the gray text (reported, 0.1.44).
     * A small first letter is a field or a variable being named, and gets the suggestions.
     */
    fun awaitsPropertyName(text: CharSequence, offset: Int): Boolean {
        val line = lineBefore(text, offset) ?: return false
        val declaration = when {
            line.isBlank() -> return false
            line.last().isWhitespace() -> line.trimEnd() + " Name"
            else -> line.takeIf { line.takeLastWhile { it.isLetterOrDigit() || it == '_' }.firstOrNull()?.isUpperCase() == true } ?: return false
        }
        return autoProperty(declaration, declaration.length) != null
    }

    /** `private readonly List<int> _items = ` -> `new();`, `IList<int> items = ` -> `new List<int>();`. */
    fun initializer(text: CharSequence, offset: Int, targetTypedNew: Boolean = true): String? {
        val line = lineBefore(text, offset) ?: return null
        val match = INITIALIZER.matchEntire(line) ?: return null
        if ("const" in match.groupValues[1].split(Regex("""\s+"""))) return null
        val construction = construction(match.groupValues[2], targetTypedNew) ?: return null
        return (if (match.groupValues[4].isEmpty()) " " else "") + construction + ";"
    }

    /** `new()` / `new List<int>()` for the declared type, null when what stands on the right is anybody's guess. */
    fun construction(type: String, targetTypedNew: Boolean): String? {
        if (type.endsWith("?") || type.endsWith("]")) return null
        val base = baseOf(type)
        val generic = if ('<' in type) type.substring(type.indexOf('<')) else ""
        IMPLEMENTATIONS[base]?.let { return if (generic.isEmpty()) null else "new $it$generic()" }
        val arguments = ARGUMENTS[base] ?: if (base in CONSTRUCTED) "" else return null
        return if (targetTypedNew) "new($arguments)" else "new $type($arguments)"
    }

    /**
     * On an empty line right inside the body of a constructor: the first parameter the constructor does nothing with yet and the
     * field or property it is for — `_name = name;`, `this.name = name;`, `Name = name;`. No member for it: nothing.
     */
    fun constructorAssignment(text: CharSequence, offset: Int): String? {
        if (!isBlankLine(text, offset)) return null
        val path = CSharpSyntaxModel.current.declarations(text).pathTo(offset)
        val constructor = path.lastOrNull()?.takeIf { it.kind == DeclarationKind.CONSTRUCTOR } ?: return null
        val type = path.getOrNull(path.size - 2)?.takeIf { it.kind.isType } ?: return null
        val body = constructor.body ?: return null
        if (offset <= body.startOffset || offset >= body.endOffset) return null
        val bodyTokens = CSharpExpressions.tokenize(text.subSequence(body.startOffset, body.endOffset))
        // directly in the body, not in a block of it
        val depth = bodyTokens.takeWhile { it.start + body.startOffset < offset }
            .fold(0) { depth, token -> if (token.type == CSharpTokenTypes.LBRACE) depth + 1 else if (token.type == CSharpTokenTypes.RBRACE) depth - 1 else depth }
        if (depth != 1) return null
        val parameters = constructor.parameters ?: return null
        // `: base(name)` between the parameters and the body uses a parameter as well
        val parametersEnd = text.indexOf(parameters, constructor.nameRange.endOffset).takeIf { it >= 0 }?.plus(parameters.length) ?: return null
        val used = (CSharpExpressions.tokenize(text.subSequence(parametersEnd.coerceAtMost(body.startOffset), body.startOffset)) + bodyTokens)
            .filter { it.isIdentifier }.mapTo(HashSet()) { it.text }
        val members = type.children.filter { it.kind == DeclarationKind.FIELD || it.kind == DeclarationKind.PROPERTY }.filter { "const" !in it.modifiers && "static" !in it.modifiers }
            .mapTo(LinkedHashSet()) { it.name }
        for (parameter in CSharpScopeNames.parameterNames(parameters)) {
            if (parameter in used) continue
            val capitalized = parameter.replaceFirstChar { it.uppercase() }
            val member = listOf("_$parameter", parameter, "m_$parameter", capitalized, "_$capitalized").firstOrNull { it in members } ?: continue
            if (member in used) continue
            return if (member == parameter) "this.$member = $parameter;" else "$member = $parameter;"
        }
        return null
    }

    /** `namespace ` in a file that has none -> `Shop.Models;`, the namespace of the folder. */
    fun namespaceName(text: CharSequence, offset: Int, context: Context): String? {
        val line = lineBefore(text, offset) ?: return null
        val match = NAMESPACE.matchEntire(line) ?: return null
        val space = match.groupValues[1]
        val typed = match.groupValues[2]
        if (space.isEmpty() && typed.isNotEmpty()) return null
        val lineStart = offset - line.length
        if (NAMESPACE_LINE.findAll(text).any { it.range.first != lineStart }) return null
        val expected = context.namespace?.takeIf { it.startsWith(typed) && it != typed } ?: return null
        return (if (space.isEmpty()) " " else "") + expected.substring(typed.length) + (if (context.fileScopedNamespace) ";" else "")
    }

    /** `public class ` in a file without types -> `Order`, the name of the file. An interface only for `IOrder.cs`, and nothing else for it. */
    fun typeName(text: CharSequence, offset: Int, fileName: String?): String? {
        val line = lineBefore(text, offset) ?: return null
        val match = TYPE_HEAD.matchEntire(line) ?: return null
        val keyword = match.groupValues[1]
        val space = match.groupValues[2]
        val typed = match.groupValues[3]
        if (space.isEmpty() && typed.isNotEmpty()) return null
        val name = fileName?.substringBefore('.')?.takeIf { IDENTIFIER.matches(it) } ?: return null
        val interfaceName = name.length > 1 && name[0] == 'I' && name[1].isUpperCase()
        if ((keyword == "interface") != interfaceName) return null
        if (!name.startsWith(typed) || name == typed) return null
        if (CSharpSyntaxModel.current.declarations(withoutLine(text, offset)).all().any { it.kind.isType }) return null
        return (if (space.isEmpty()) " " else "") + name.substring(typed.length)
    }

    /** `private readonly ILogger<` in `OrderService` -> `OrderService> _logger;`; in a parameter list -> `OrderService> logger`. */
    fun loggerType(text: CharSequence, offset: Int): String? {
        val (line, rest) = lineAround(text, offset) ?: return null
        if (!line.endsWith("ILogger<") || (rest.isNotEmpty() && rest != ">")) return null
        val head = line.removeSuffix("ILogger<")
        if (head.lastOrNull()?.let { it.isLetterOrDigit() || it == '_' || it == '.' } == true) return null
        val type = enclosingType(text, offset)?.takeIf { "static" !in it.modifiers && it.kind != DeclarationKind.INTERFACE && it.kind != DeclarationKind.ENUM } ?: return null
        if (rest == ">") return type.name
        val members = type.children.filter { it.kind == DeclarationKind.FIELD || it.kind == DeclarationKind.PROPERTY }.map { it.name }
        return when {
            head.trimEnd().let { it.endsWith("(") || it.endsWith(",") } -> "${type.name}> logger"
            !FIELD_HEAD.matches(head) -> null
            "_logger" in members || "logger" in members -> "${type.name}>"
            else -> "${type.name}> ${if (members.isEmpty() || members.any { it.startsWith("_") }) "_logger" else "logger"};"
        }
    }

    /**
     * `public Order(` in a type without a constructor that takes anything -> `string name, int age)`: a parameter for every
     * readonly field and get-only property that has no initializer. The body follows line by line ([constructorAssignment]).
     */
    fun constructorParameters(text: CharSequence, offset: Int): String? {
        val (line, rest) = lineAround(text, offset) ?: return null
        if (rest.isNotEmpty() && rest != ")") return null
        val name = CONSTRUCTOR_HEAD.matchEntire(line)?.groupValues?.get(1) ?: return null
        val type = enclosingType(text, offset)?.takeIf { it.name == name } ?: return null
        if (type.children.any { it.kind == DeclarationKind.CONSTRUCTOR && it.parameters.orEmpty().removePrefix("(").removeSuffix(")").isNotBlank() }) return null
        val parameters = type.children.mapNotNull { member ->
            val declared = member.type?.takeIf { "static" !in member.modifiers && "const" !in member.modifiers } ?: return@mapNotNull null
            val wanted = when (member.kind) {
                DeclarationKind.FIELD -> "readonly" in member.modifiers && '=' !in text.subSequence(member.nameRange.endOffset, member.range.endOffset)
                DeclarationKind.PROPERTY -> member.body?.let { body ->
                    text.subSequence(body.startOffset, body.endOffset).filterNot { it.isWhitespace() }.toString() == "{get;}" && nextCharacter(text, body.endOffset) != '='
                } == true
                else -> false
            }
            if (wanted) "$declared ${parameterName(member.name)}" else null
        }
        if (parameters.isEmpty()) return null
        return parameters.joinToString(", ") + (if (rest == ")") "" else ")")
    }

    /** `catch` -> ` (Exception e)`; `ex` where the file names its exceptions so. */
    fun catchClause(text: CharSequence, offset: Int): String? {
        val line = lineBefore(text, offset) ?: return null
        val space = CATCH.matchEntire(line)?.groupValues?.get(1) ?: return null
        if (nextCharacter(text, offset).let { it == '{' || it == '(' }) return null
        val name = CAUGHT.findAll(text).map { it.groupValues[1] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "e"
        return (if (space.isEmpty()) " " else "") + "(Exception $name)"
    }

    /** `_name` -> `name`, `m_count` -> `count`, `Name` -> `name`, `_event` -> `@event`. */
    fun parameterName(member: String): String {
        val bare = member.removePrefix("m_").trimStart('_').ifEmpty { member }
        val name = bare.replaceFirstChar { it.lowercase() }
        return if (CSharpExpressions.tokenize(name).singleOrNull()?.type == CSharpTokenTypes.KEYWORD) "@$name" else name
    }

    private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")
    private val NAMESPACE = Regex("""^namespace(\s*)([\w.]*)$""")
    private val NAMESPACE_LINE = Regex("""(?m)^[ \t]*namespace\b""")
    private val TYPE_HEAD = Regex("""^\s*(?:(?:$MODIFIERS)\s+)*(class|interface|struct|enum|record(?:\s+(?:struct|class))?)(\s*)([A-Za-z_]\w*)?$""")
    private val FIELD_HEAD = Regex("""^\s*(?:(?:$MODIFIERS)\s+)+$""")
    private val CONSTRUCTOR_HEAD = Regex("""^\s*(?:(?:public|internal|protected|private)\s+)+([A-Za-z_]\w*)\($""")
    private val CATCH = Regex("""^(?:[^"/]*[\s}])?catch(\s*)$""")
    private val CAUGHT = Regex("""\bcatch\s*\(\s*[\w.]+\s+(\w+)\s*\)""")

    private val ACCESS = setOf("public", "internal", "protected", "required")
    private val NOT_FOR_PROPERTY = setOf("readonly", "const", "async", "volatile", "extern", "partial")

    /** A method header `(...)` on its own line, for the NotImplementedException body; nested parens in the parameter list are left out. */
    private val METHOD_HEAD = Regex("""^\s*((?:(?:$MODIFIERS)\s+)*)($TYPE)\s+([A-Za-z_]\w*)\s*(?:<[^<>]*>)?\s*\([^()]*\)$""")

    /** Modifiers whose method has no body on this line: abstract / extern declare it, partial may be the other half. */
    private val NOT_IMPLEMENTABLE = setOf("abstract", "extern", "partial")

    private fun baseOf(type: String): String = type.removePrefix("global::").substringBefore('<').substringBefore('[').removeSuffix("?").substringAfterLast('.')

    /** The line up to [offset], when nothing but whitespace follows on it. */
    private fun lineBefore(text: CharSequence, offset: Int): String? {
        var end = offset
        while (end < text.length && text[end] != '\n') { if (!text[end].isWhitespace()) return null; end++ }
        val start = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(start, offset)
    }

    /** The line up to [offset] and what follows on it, trimmed. */
    private fun lineAround(text: CharSequence, offset: Int): Pair<String, String>? {
        if (offset > text.length) return null
        val start = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return text.substring(start, offset) to text.substring(offset, end).trim()
    }

    /** The text with the line of [offset] turned into spaces: what is being typed there is not a declaration yet, and the scanner need not guess. */
    private fun withoutLine(text: CharSequence, offset: Int): String {
        val start = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return text.substring(0, start) + " ".repeat(end - start) + text.substring(end)
    }

    private fun enclosingType(text: CharSequence, offset: Int): CSharpDeclarationInfo? =
        CSharpSyntaxModel.current.declarations(withoutLine(text, offset)).pathTo(offset).lastOrNull()?.takeIf { it.kind.isType }

    private fun isBlankLine(text: CharSequence, offset: Int): Boolean = lineBefore(text, offset)?.isBlank() == true

    /** The nearest non-blank line above the one [offset] is on, or null. */
    private fun previousNonBlankLine(text: CharSequence, offset: Int): String? {
        var start = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        while (start > 0) {
            val end = start - 1
            val lineStart = if (end == 0) 0 else text.lastIndexOf('\n', end - 1) + 1
            val line = text.subSequence(lineStart, end).toString()
            if (line.isNotBlank()) return line
            start = lineStart
        }
        return null
    }

    private fun nextCharacter(text: CharSequence, offset: Int): Char? = (offset until text.length).firstOrNull { !text[it].isWhitespace() }?.let { text[it] }
}

/** The names a statement can see, by tokens: the parameters of the member, the locals declared above, the fields and properties of the types around. */
object CSharpScopeNames {
    private val TYPE_KEYWORDS = setOf("var", "int", "string", "bool", "double", "long", "object", "char", "byte", "float", "decimal", "short", "uint", "ulong", "ushort", "sbyte", "dynamic")

    fun visibleAt(text: CharSequence, offset: Int): Set<String> {
        val names = LinkedHashSet<String>()
        val path = CSharpSyntaxModel.current.declarations(text).pathTo(offset)
        val member = path.lastOrNull { !it.kind.isType && it.kind != DeclarationKind.NAMESPACE }
        member?.parameters?.let { names += parameterNames(it) }
        for (type in path.filter { it.kind.isType }) {
            // the parameters of a primary constructor are not in the scanner's hands; the members are
            names += type.children.filter { it.kind == DeclarationKind.FIELD || it.kind == DeclarationKind.PROPERTY }.map { it.name }
        }
        val from = member?.body?.startOffset ?: 0
        if (from < offset) names += locals(text.subSequence(from, offset))
        return names
    }

    /** `Type name` followed by `=`, `;`, `,`, `)` or `in`: a local, a lambda parameter with a type, a pattern or `out var` variable. */
    fun locals(text: CharSequence): List<String> {
        val tokens = CSharpExpressions.tokenize(text)
        val names = ArrayList<String>()
        for (i in 1 until tokens.size) {
            val token = tokens[i]
            if (!token.isIdentifier) continue
            val before = tokens[i - 1]
            val typed = before.isIdentifier || (before.type == CSharpTokenTypes.KEYWORD && before.text in TYPE_KEYWORDS) || before.isOperator(">") || before.isOperator("?") ||
                before.type == CSharpTokenTypes.RBRACKET
            if (!typed) continue
            val after = tokens.getOrNull(i + 1)
            val ends = after == null || after.isOperator("=") || after.type == CSharpTokenTypes.SEMICOLON || after.type == CSharpTokenTypes.COMMA ||
                after.type == CSharpTokenTypes.RPAREN || after.isKeyword("in") ||
                // a pattern variable: `order is Paid paid && ...`
                (after.type == CSharpTokenTypes.OPERATOR && (after.text.startsWith("&") || after.text.startsWith("|")))
            if (ends) names += token.text
        }
        return names
    }

    /** `(string name, [FromBody] Order order, int count = 0, params int[] rest)` -> name, order, count, rest. */
    fun parameterNames(parameters: String): List<String> {
        val inner = parameters.trim().removePrefix("(").removeSuffix(")")
        return splitTopLevel(inner).mapNotNull { parameter ->
            val declared = splitTopLevel(parameter, '=').firstOrNull() ?: return@mapNotNull null
            CSharpExpressions.tokenize(declared).lastOrNull { it.isIdentifier }?.text
        }
    }

    fun splitTopLevel(text: String, separator: Char = ','): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in text.withIndex()) {
            when (c) {
                '<', '(', '[', '{' -> depth++
                '>', ')', ']', '}' -> depth--
                separator -> if (depth == 0) { parts += text.substring(start, i).trim(); start = i + 1 }
            }
        }
        parts += text.substring(start).trim()
        return parts.filter { it.isNotEmpty() }
    }
}

/** Where gray text is shown: a file and a line. The same suggestion comes again at every typed letter, and is one suggestion. */
object GhostPlace {
    fun of(request: InlineCompletionRequest): String {
        val offset = request.endOffset.coerceIn(0, request.document.textLength)
        return request.file.virtualFile?.path.orEmpty() + ":" + request.document.getLineNumber(offset)
    }
}

class CSharpGhostTextProvider : InlineCompletionProvider {
    override val id: InlineCompletionProviderID = InlineCompletionProviderID("io.github.dotnetsupport.declarations")

    /** Exact: true only when there is something to show, so the other providers (the lambda of the server, Full Line) get their turn. */
    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val request = event.toRequest() ?: return false
        if (request.file !is CSharpFile) return false
        // Tab belongs to the completion list while it is open
        if (LookupManager.getActiveLookup(request.editor) != null) return false
        return suggestion(request) != null
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val ghost = readAction { suggestion(request) }
        if (ghost != null) {
            shownRule = ghost.rule
            SuggestionStats.getInstance().shown(ghost.rule, GhostPlace.of(request))
        }
        return InlineCompletionSingleSuggestion.build(UserDataHolderBase()) {
            if (ghost != null) emit(InlineCompletionGrayTextElement(ghost.text))
        }
    }

    /** The rule of the gray text that is on the screen: the one that is taken when Tab is pressed. */
    @Volatile
    private var shownRule: String? = null

    override val insertHandler: InlineCompletionInsertHandler = object : InlineCompletionInsertHandler {
        override fun afterInsertion(environment: InlineCompletionInsertEnvironment, elements: List<InlineCompletionElement>) {
            DefaultInlineCompletionInsertHandler.INSTANCE.afterInsertion(environment, elements)
            shownRule?.let { SuggestionStats.getInstance().accepted(it) }
        }
    }

    private fun suggestion(request: InlineCompletionRequest): CSharpGhostText.Ghost? {
        val file = request.file
        val virtualFile = file.virtualFile
        val project = file.project
        // every answer on demand: this runs at each keystroke, and most of them match no rule
        val context = object : CSharpGhostText.Context(fileName = virtualFile?.nameWithoutExtension) {
            override val targetTypedNew: Boolean get() = hasTargetTypedNew(project, virtualFile)
            override val namespace: String? get() = virtualFile?.parent?.let { CSharpNamespaces.forDirectory(project, it) }
            override val fileScopedNamespace: Boolean get() = virtualFile?.parent?.let { CSharpNamespaces.isFileScopedPreferred(project, it) } ?: true
        }
        return CSharpGhostText.ghost(request.document.immutableCharSequence, request.endOffset, context)
    }

    companion object {
        private val MODERN = Regex("""^net(\d+)\.\d+.*$""")

        /** `new()` is C# 9, the default language of .NET 5: a project for .NET Framework, .NET Standard or .NET Core 3 gets `new List<int>()`. */
        fun hasTargetTypedNew(frameworks: List<String>): Boolean =
            frameworks.all { framework -> MODERN.matchEntire(framework.trim().lowercase())?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 5 } == true }

        private fun hasTargetTypedNew(project: Project, file: VirtualFile?): Boolean {
            val projectFile = file?.let(DotNetProjects::findOwningProject) ?: return true
            return hasTargetTypedNew(SolutionService.getInstance(project).msBuildProject(projectFile).targetFrameworks)
        }
    }
}
