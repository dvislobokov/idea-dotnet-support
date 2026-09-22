package io.github.dotnetsupport.lang

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** A rule of `resources/csharpIndent/rules.json`; see the `about` of that file for the vocabulary. */
class IndentRule(
    val id: String,
    val about: String,
    val contexts: Set<String>?,
    val first: Set<String>?,
    val firstPattern: Regex?,
    val previous: Set<String>?,
    val scopes: Set<String>?,
    val states: Set<String>,
    val optionName: String?,
    val optionValue: String?,
    val optionDefault: String?,
    val anchor: String,
    val keywords: Set<String>,
    val add: String,
    val examples: List<Example>,
) {
    class Example(val code: List<String>, /** Null: the line is left as it is. */ val expect: Int?)
}

class IndentSizes(val indent: Int = 4, val continuation: Int = 4, val tab: Int = 4, val useTabs: Boolean = false) {
    fun text(columns: Int): String = if (useTabs && tab > 0) "\t".repeat(columns / tab) + " ".repeat(columns % tab) else " ".repeat(columns)
}

/**
 * The indent of a C# line, decided by the rules of a JSON file over the tokens before the line: there is no formatter
 * model (formatting is done by CSharpier or `dotnet format`), but Enter and a typed brace need an answer at once.
 * The engine knows a small vocabulary of conditions and anchors; which of them make a rule, and in which order the rules
 * are tried, is data. Every rule carries examples, and a test runs them all.
 */
class CSharpIndentRules(val rules: List<IndentRule>) {
    /** The indent in columns for the line that starts at [lineStart], with the rule that gave it; null columns: leave the line alone. */
    fun indentOf(text: CharSequence, lineStart: Int, sizes: IndentSizes = IndentSizes(), options: Map<String, String> = emptyMap()): Pair<Int?, IndentRule>? {
        val analysis = IndentAnalysis(text, lineStart.coerceIn(0, text.length), sizes.tab)
        val rule = rules.firstOrNull { matches(it, analysis, options) } ?: return null
        val base = when (rule.anchor) {
            "keep" -> return null to rule
            "column0" -> 0
            "previousLine" -> analysis.previousLineIndent()
            "statementStart" -> analysis.statementStartIndent()
            "scopeOpen" -> analysis.scopeOpenIndent(analysis.scope)
            "matchingOpen" -> analysis.matchingOpenIndent()
            "caseLabel" -> analysis.caseLabelIndent()
            "keyword" -> analysis.keywordIndent(rule.keywords)
            else -> return null
        }
        val extra = when (rule.add) {
            "indent" -> sizes.indent
            "continuation" -> sizes.continuation
            "star" -> if (analysis.previousLineOpensComment()) 1 else 0
            else -> 0
        }
        return (base + extra).coerceAtLeast(0) to rule
    }

    private fun matches(rule: IndentRule, analysis: IndentAnalysis, options: Map<String, String>): Boolean {
        if (rule.contexts != null && analysis.context !in rule.contexts) return false
        // rules about code do not apply to the inside of a comment or a string
        if (rule.contexts == null && analysis.context != "code") return false
        if (rule.first != null && !analysis.firstIs(rule.first)) return false
        if (rule.firstPattern != null && !rule.firstPattern.containsMatchIn(analysis.lineText)) return false
        if (rule.previous != null && !analysis.previousIs(rule.previous)) return false
        if (rule.scopes != null && analysis.scope.kind !in rule.scopes) return false
        if (!rule.states.all(analysis::hasState)) return false
        if (rule.optionName != null && (options[rule.optionName] ?: rule.optionDefault)?.lowercase() != rule.optionValue?.lowercase()) return false
        return true
    }

    companion object {
        val DEFAULT: CSharpIndentRules by lazy {
            parse(CSharpIndentRules::class.java.getResourceAsStream("/csharpIndent/rules.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("No indentation rules"))
        }

        fun parse(json: String): CSharpIndentRules {
            val root = JsonParser.parseString(json).asJsonObject
            val sets = root.getAsJsonObject("sets")?.entrySet().orEmpty().associate { (name, values) -> name to values.asJsonArray.map { it.asString }.toSet() }
            fun literals(element: JsonElement?): Set<String>? = when {
                element == null -> null
                element.isJsonArray -> element.asJsonArray.map { it.asString }.toSet()
                else -> element.asString.let { if (it.startsWith("@")) sets[it.removePrefix("@")] ?: error("Unknown set $it") else setOf(it) }
            }
            return CSharpIndentRules(root.getAsJsonArray("rules").map { it.asJsonObject }.map { rule ->
                val whenPart = rule.getAsJsonObject("when") ?: JsonObject()
                val option = whenPart.getAsJsonObject("option")
                val indent = rule.getAsJsonObject("indent")
                IndentRule(
                    id = rule.get("id").asString,
                    about = rule.get("about")?.asString.orEmpty(),
                    contexts = literals(whenPart.get("context")),
                    first = literals(whenPart.get("first")),
                    firstPattern = whenPart.get("firstMatches")?.asString?.let(::Regex),
                    previous = literals(whenPart.get("previous")),
                    scopes = literals(whenPart.get("scope")),
                    states = literals(whenPart.get("state")).orEmpty(),
                    optionName = option?.get("name")?.asString,
                    optionValue = option?.get("is")?.asString,
                    optionDefault = option?.get("default")?.asString,
                    anchor = indent.get("anchor").asString,
                    keywords = literals(indent.get("keywords")).orEmpty(),
                    add = indent.get("add")?.asString ?: "none",
                    examples = (rule.get("examples") as? JsonArray).orEmpty().map { it.asJsonObject }.map { example ->
                        IndentRule.Example(example.getAsJsonArray("code").map { it.asString }, example.get("expect")?.takeIf { !it.isJsonNull }?.asInt)
                    },
                )
            })
        }

        private fun JsonArray?.orEmpty(): Iterable<JsonElement> = this ?: emptyList()
    }
}

/** What the rules can ask about the line that starts at [lineStart]: the tokens before it, the brackets that are open, where statements start. */
class IndentAnalysis(private val text: CharSequence, private val lineStart: Int, private val tabSize: Int) {
    class Token(val type: IElementType, val text: String, val start: Int, val end: Int)

    /** An open bracket with the statements seen inside of it so far; [open] is null for the file itself. */
    class Scope(val kind: String, val open: Token?, /** Where the statement that has the bracket started. */ val headerStart: Token?) {
        val statement = ArrayList<Token>()
        var previousStatementStart: Token? = null
        var lastCaseLabel: Token? = null
        var afterAttribute = false
    }

    private val tokens = ArrayList<Token>()
    private val stack = ArrayList<Scope>().apply { add(Scope("none", null, null)) }
    var context: String = "code"
        private set

    /** The text of the line in question without its indent. */
    val lineText: String = text.subSequence(lineStart, lineEnd(lineStart)).toString().trim()
    val scope: Scope get() = stack.last()

    init {
        val lexer = CSharpLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            if (start >= lineStart) {
                if (type != TokenType.WHITE_SPACE) {
                    if (type == CSharpTokenTypes.PREPROCESSOR && context == "code") context = "preprocessor"
                    break
                }
            } else if (end > lineStart && (type == CSharpTokenTypes.BLOCK_COMMENT || type in CSharpTokenTypes.STRINGS)) {
                // the line starts inside of a token that spans lines
                context = if (type == CSharpTokenTypes.BLOCK_COMMENT) "blockComment" else "string"
                break
            } else if (type != TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS && type != CSharpTokenTypes.PREPROCESSOR) {
                accept(Token(type, text.subSequence(start, end).toString(), start, end))
            }
            lexer.advance()
        }
    }

    // ---- building the scopes ----

    private fun accept(token: Token) {
        tokens += token
        val current = scope
        when (token.type) {
            CSharpTokenTypes.LBRACE, CSharpTokenTypes.LPAREN, CSharpTokenTypes.LBRACKET -> {
                val kind = when (token.type) {
                    CSharpTokenTypes.LPAREN -> "paren"
                    CSharpTokenTypes.LBRACKET -> "bracket"
                    else -> braceKind(current)
                }
                current.statement += token
                stack += Scope(kind, token, current.statement.first())
            }
            CSharpTokenTypes.RBRACE, CSharpTokenTypes.RPAREN, CSharpTokenTypes.RBRACKET -> {
                val index = stack.indexOfLast { it.open?.type == OPENERS[token.type] }
                if (index <= 0) return // a stray closing bracket of code that is being typed
                val closed = stack[index]
                while (stack.size > index) stack.removeAt(stack.size - 1)
                val parent = scope
                parent.statement += token
                val statementLevel = parent.kind == "block" || parent.kind == "switch" || parent.kind == "none"
                when {
                    closed.kind == "block" || closed.kind == "switch" -> endStatement(parent)
                    // [Attribute] in front of a declaration is a line of its own
                    closed.kind == "bracket" && statementLevel && parent.statement.firstOrNull() === closed.open -> {
                        endStatement(parent)
                        parent.afterAttribute = true
                    }
                }
            }
            else -> {
                current.statement += token
                current.afterAttribute = false
                when {
                    token.type == CSharpTokenTypes.SEMICOLON -> endStatement(current)
                    token.type == CSharpTokenTypes.COMMA && current.kind in COMMA_SEPARATED -> endStatement(current)
                    current.kind == "switch" && token.text == ":" && current.statement.first().text in CASE_KEYWORDS && !isDoubleColon(token) -> {
                        current.lastCaseLabel = current.statement.first()
                        endStatement(current)
                    }
                }
            }
        }
    }

    private fun endStatement(scope: Scope) {
        scope.previousStatementStart = scope.statement.firstOrNull() ?: scope.previousStatementStart
        scope.statement.clear()
    }

    private fun isDoubleColon(token: Token): Boolean = tokens.getOrNull(tokens.size - 2)?.let { it.text == ":" && it.end == token.start } == true

    /** What the brace that is being opened in [parent] is: see the `about` of the rules. */
    private fun braceKind(parent: Scope): String {
        val statement = parent.statement
        val previous = statement.lastOrNull() ?: return "block"
        if (statement.first().text == "switch") return "switch"
        if (statement.any { it.text == "enum" && it.type == CSharpTokenTypes.KEYWORD }) return "list"
        val beforePrevious = statement.getOrNull(statement.size - 2)
        // `=> {` is the body of a lambda or of a member
        if (previous.text == ">" && beforePrevious?.text == "=" && beforePrevious.end == previous.start) return "block"
        if (previous.text in LIST_OPENERS) return "list"
        if (statement.first().text in CONTROL_KEYWORDS) return "block"
        // `new Foo() {`, `new List<int> {`; not the `new` modifier of a member
        val newAt = statement.indexOfLast { it.text == "new" && it.type == CSharpTokenTypes.KEYWORD }
        if (newAt >= 0) {
            val beforeNew = statement.getOrNull(newAt - 1)
            if (beforeNew == null || beforeNew.type != CSharpTokenTypes.KEYWORD || beforeNew.text in EXPRESSION_KEYWORDS) return "list"
        }
        return "block"
    }

    // ---- what the rules ask ----

    fun hasState(state: String): Boolean = when (state) {
        "statementStart" -> scope.statement.isEmpty()
        "inCaseSection" -> scope.kind == "switch" && scope.lastCaseLabel != null
        "afterAttribute" -> scope.afterAttribute
        "afterControlHeader" -> afterControlHeader()
        "previousLineInScope" -> scope.previousStatementStart?.let { previous -> scope.open?.let { lineOf(previous.start) > lineOf(it.start) } } == true
        else -> false
    }

    /** `if (...)`, `for (...)`, ... `else`, `else if (...)`, `do` with nothing after them: a body without braces follows. */
    private fun afterControlHeader(): Boolean {
        val statement = scope.statement.dropWhile { it.text == "await" }
        val first = statement.firstOrNull()?.text ?: return false
        val rest = if (first == "else" && statement.getOrNull(1)?.text == "if") statement.drop(1) else statement
        return when {
            rest.size == 1 -> rest[0].text == "else" || rest[0].text == "do"
            rest.size == 3 -> rest[0].text in HEADER_KEYWORDS && rest[1].type == CSharpTokenTypes.LPAREN && rest[2].type == CSharpTokenTypes.RPAREN
            else -> false
        }
    }

    fun firstIs(literals: Set<String>): Boolean {
        if (lineText.isEmpty()) return "<empty>" in literals
        val word = lineText.takeWhile { it.isLetterOrDigit() || it == '_' }
        if (word.isNotEmpty()) return word in literals
        val run = lineText.takeWhile { it in OPERATOR_CHARACTERS }
        return (if (run.isEmpty()) lineText.take(1) else run) in literals
    }

    fun previousIs(literals: Set<String>): Boolean {
        val last = tokens.lastOrNull() ?: return "<none>" in literals
        if (last.type != CSharpTokenTypes.OPERATOR) return last.text in literals
        // the lexer gives operators character by character: `=>` is `=` and `>` next to each other
        var from = tokens.size - 1
        while (from > 0 && tokens[from - 1].type == CSharpTokenTypes.OPERATOR && tokens[from - 1].end == tokens[from].start) from--
        return tokens.subList(from, tokens.size).joinToString("") { it.text } in literals
    }

    fun previousLineIndent(): Int {
        if (context != "code") {
            // inside a comment: the physical line above
            var start = lineStart
            while (start > 0) {
                start = lineStartOf(start - 1)
                if (text.subSequence(start, lineEnd(start)).isNotBlank()) return indentAt(start)
            }
            return 0
        }
        return tokens.lastOrNull()?.let { indentAt(lineStartOf(it.start)) } ?: 0
    }

    fun previousLineOpensComment(): Boolean {
        if (lineStart == 0) return false
        val start = lineStartOf(lineStart - 1)
        return text.subSequence(start, lineEnd(start)).toString().trim().startsWith("/*")
    }

    /** Where the statement the line belongs to starts; at the start of one, where the one before it started. */
    fun statementStartIndent(): Int {
        val start = scope.statement.firstOrNull() ?: scope.previousStatementStart ?: return scopeOpenIndent(scope)
        return indentAt(lineStartOf(start.start))
    }

    /**
     * The line a scope hangs on. A brace that starts its line, and any parenthesis: that line. A brace at the end of a
     * line: the line where the header started (`void M(int a,` / `int b) {`), unless it is inside parentheses.
     */
    fun scopeOpenIndent(scope: Scope): Int {
        val open = scope.open ?: return 0
        val openLine = lineStartOf(open.start)
        if (open.type != CSharpTokenTypes.LBRACE || firstTokenOffset(openLine) == open.start) return indentAt(openLine)
        val parent = stack.getOrNull(stack.indexOf(scope) - 1)
        val header = scope.headerStart
        return if (header != null && parent != null && parent.kind !in setOf("paren", "bracket")) indentAt(lineStartOf(header.start)) else indentAt(openLine)
    }

    fun matchingOpenIndent(): Int {
        val opener = CLOSERS[lineText.firstOrNull()] ?: return scopeOpenIndent(scope)
        return scopeOpenIndent(stack.lastOrNull { it.open?.type == opener } ?: return 0)
    }

    fun caseLabelIndent(): Int = scope.lastCaseLabel?.let { indentAt(lineStartOf(it.start)) } ?: scopeOpenIndent(scope)

    /** The nearest of [keywords] before the line, at the level of the scope: the `if` an `else` belongs to. */
    fun keywordIndent(keywords: Set<String>): Int {
        val limit = scope.open?.start ?: -1
        var depth = 0
        for (token in tokens.asReversed()) {
            if (token.start <= limit) break
            when (token.type) {
                CSharpTokenTypes.RBRACE, CSharpTokenTypes.RPAREN, CSharpTokenTypes.RBRACKET -> depth++
                CSharpTokenTypes.LBRACE, CSharpTokenTypes.LPAREN, CSharpTokenTypes.LBRACKET -> depth--
                else -> if (depth == 0 && token.type == CSharpTokenTypes.KEYWORD && token.text in keywords) {
                    // `else if`: the chain hangs on the line of the `else`
                    return indentAt(lineStartOf(token.start))
                }
            }
        }
        return statementStartIndent()
    }

    // ---- text ----

    private fun lineStartOf(offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun lineEnd(offset: Int): Int {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun lineOf(offset: Int): Int = lineStartOf(offset)

    private fun firstTokenOffset(lineStart: Int): Int {
        var i = lineStart
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    private fun indentAt(lineStart: Int): Int {
        var columns = 0
        var i = lineStart
        while (i < text.length) {
            when (text[i]) {
                ' ' -> columns++
                '\t' -> columns += if (tabSize > 0) tabSize - columns % tabSize else 1
                else -> return columns
            }
            i++
        }
        return columns
    }

    private companion object {
        val OPENERS = mapOf(CSharpTokenTypes.RBRACE to CSharpTokenTypes.LBRACE, CSharpTokenTypes.RPAREN to CSharpTokenTypes.LPAREN, CSharpTokenTypes.RBRACKET to CSharpTokenTypes.LBRACKET)
        val CLOSERS = mapOf('}' to CSharpTokenTypes.LBRACE, ')' to CSharpTokenTypes.LPAREN, ']' to CSharpTokenTypes.LBRACKET)
        val COMMA_SEPARATED = setOf("list", "paren", "bracket")
        val CASE_KEYWORDS = setOf("case", "default")
        val HEADER_KEYWORDS = setOf("if", "for", "foreach", "while", "using", "lock", "fixed")
        val CONTROL_KEYWORDS = HEADER_KEYWORDS + setOf("else", "do", "try", "catch", "finally", "checked", "unchecked", "unsafe")
        val LIST_OPENERS = setOf("=", ",", "(", "[", "new", "with", "switch", "is", "and", "or", "not")
        val EXPRESSION_KEYWORDS = setOf("return", "await", "throw", "yield", "in", "is", "as", "and", "or", "not", "when", "case")
        const val OPERATOR_CHARACTERS = "+-*/%&|^!~=<>?:."
    }
}
