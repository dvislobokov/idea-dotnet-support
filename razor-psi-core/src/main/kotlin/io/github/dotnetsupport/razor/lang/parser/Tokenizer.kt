package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.csharp.lang.lexer.CSharpLiteralScanner
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticFactory
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind
import io.github.dotnetsupport.razor.lang.syntax.RazorToken
import io.github.dotnetsupport.razor.lang.syntax.SourceLocation
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/** `Tokenizer.StateResult`: the next state (null stops) and the token produced, if any. */
class StateResult(val state: Int?, val result: RazorToken?)

/** The states every tokenizer shares for `@* … *@` (`RazorCommentTokenizerState`); a tokenizer's own states start at 0 and avoid these. */
object RazorCommentTokenizerState {
    const val AFTER_RAZOR_COMMENT_TRANSITION = 1000
    const val RAZOR_COMMENT_BODY = 1001
    const val STAR_AFTER_RAZOR_COMMENT_BODY = 1002
    const val AT_TOKEN_AFTER_RAZOR_COMMENT_BODY = 1003
}

/**
 * `Tokenizer`: a state machine over a [SeekableTextReader] — [dispatch] runs the state, [turn] loops until a token is out. The token's
 * text is gathered in [buffer] with [takeCurrent]; [endToken] makes the token with the errors collected since [startToken].
 */
abstract class Tokenizer(val source: SeekableTextReader) {
    protected val buffer = StringBuilder()
    protected val currentErrors = ArrayList<RazorDiagnostic>()

    protected abstract val startState: Int
    var currentState: Int? = null
        protected set

    protected var currentSyntaxToken: RazorToken? = null
        private set

    val endOfFile: Boolean get() = source.peek() == -1

    /** A tokenizer that ends before the text does by design (the directive tokenizers of `ParseLeadingDirectives`). */
    open val stopsEarly: Boolean get() = false

    abstract val razorCommentStarKind: RazorSyntaxKind
    abstract val razorCommentKind: RazorSyntaxKind
    abstract val razorCommentTransitionKind: RazorSyntaxKind

    protected val haveContent: Boolean get() = buffer.isNotEmpty()

    protected val currentCharacter: Char
        get() {
            val peek = source.peek()
            return if (peek == -1) '\u0000' else peek.toChar()
        }

    val currentLocation: SourceLocation get() = source.location

    var currentStart: SourceLocation = source.location
        private set

    protected abstract fun createToken(content: String, kind: RazorSyntaxKind, errors: List<RazorDiagnostic>): RazorToken
    protected abstract fun dispatch(): StateResult

    /** `GetTokenKeyword`: the C# keyword a token is (null for an identifier); only the C# tokenizers answer. */
    open fun getTokenKeyword(token: RazorToken?): String? = null

    open fun startingBlock() {}
    open fun endingBlock() {}

    open fun nextToken(): RazorToken? {
        check(buffer.isEmpty())
        startToken()
        if (endOfFile) return null
        val token = turn()
        check(buffer.isEmpty())
        return token
    }

    protected open fun turn(): RazorToken? {
        if (currentState != null) {
            do {
                val position = source.position
                val state = currentState
                val next = dispatch()
                currentState = next.state
                currentSyntaxToken = next.result
                // a state that neither moves nor changes would spin forever: a bug of the tokenizer, not of the text
                check(currentSyntaxToken != null || currentState == null || source.position != position || currentState != state) {
                    "${javaClass.simpleName} made no progress at $position in state $state"
                }
            } while (currentState != null && currentSyntaxToken == null)
            if (currentState == null) return null
            return currentSyntaxToken
        }
        return null
    }

    open fun reset(position: Int) {
        currentState = startState
    }

    protected fun stop(): StateResult = StateResult(null, null)
    protected fun transition(state: Int): StateResult = StateResult(state, null)
    protected fun transition(state: Int, result: RazorToken?): StateResult = StateResult(state, result)
    protected fun stay(): StateResult = StateResult(currentState, null)
    protected fun stay(result: RazorToken?): StateResult = StateResult(currentState, result)

    protected fun single(type: RazorSyntaxKind): RazorToken? {
        takeCurrent()
        return endToken(type)
    }

    protected fun startToken() {
        check(buffer.isEmpty())
        check(currentErrors.isEmpty())
        currentStart = currentLocation
    }

    protected fun endToken(type: RazorSyntaxKind): RazorToken? {
        var token: RazorToken? = null
        if (haveContent) {
            token = endToken(buffer.toString(), type)
            buffer.setLength(0)
        }
        return token
    }

    protected fun endToken(tokenContent: String?, type: RazorSyntaxKind): RazorToken? {
        var token: RazorToken? = null
        if (tokenContent != null) {
            val errors = if (currentErrors.isEmpty()) emptyList() else ArrayList(currentErrors)
            token = createToken(tokenContent, type, errors)
            currentErrors.clear()
        }
        return token
    }

    protected fun takeUntil(predicate: (Char) -> Boolean): Boolean {
        while (!endOfFile && !predicate(currentCharacter)) takeCurrent()
        return !endOfFile
    }

    protected fun takeCurrent() {
        if (endOfFile) return
        buffer.append(currentCharacter)
        moveNext()
    }

    protected fun moveNext() {
        source.read()
    }

    protected fun takeAll(expected: String, caseSensitive: Boolean): Boolean = lookahead(expected, takeIfMatch = true, caseSensitive = caseSensitive)

    protected fun peek(charactersAhead: Int = 1): Char {
        val offset = source.position + charactersAhead
        return if (offset < source.length) source.text[offset] else '\u0000'
    }

    protected fun afterRazorCommentTransition(): StateResult {
        if (currentCharacter != '*') return transition(startState)
        takeCurrent()
        return transition(RazorCommentTokenizerState.RAZOR_COMMENT_BODY, endToken(razorCommentStarKind))
    }

    protected fun razorCommentBody(): StateResult {
        takeUntil { it == '*' }
        if (currentCharacter == '*') {
            if (peek() == '@') {
                return if (haveContent) transition(RazorCommentTokenizerState.STAR_AFTER_RAZOR_COMMENT_BODY, endToken(razorCommentKind))
                else transition(RazorCommentTokenizerState.STAR_AFTER_RAZOR_COMMENT_BODY)
            } else {
                takeCurrent()
                return stay()
            }
        }
        return transition(startState, endToken(razorCommentKind))
    }

    protected fun starAfterRazorCommentBody(): StateResult {
        takeCurrent()
        return transition(RazorCommentTokenizerState.AT_TOKEN_AFTER_RAZOR_COMMENT_BODY, endToken(razorCommentStarKind))
    }

    protected fun atTokenAfterRazorCommentBody(nextState: Int): StateResult {
        takeCurrent()
        return transition(nextState, endToken(razorCommentTransitionKind))
    }

    /** `Tokenizer.Lookahead`: whether [expected] is ahead; taken into the buffer when [takeIfMatch]. */
    fun lookahead(expected: String, takeIfMatch: Boolean, caseSensitive: Boolean): Boolean {
        fun filter(c: Char) = if (caseSensitive) c else c.lowercaseChar()
        if (expected.isEmpty() || filter(currentCharacter) != filter(expected[0])) return false
        val start = source.position
        val oldBuffer = if (takeIfMatch) buffer.toString() else null
        var matched = true
        for (i in expected.indices) {
            if (filter(currentCharacter) != filter(expected[i])) {
                matched = false
                break
            }
            if (takeIfMatch) takeCurrent() else moveNext()
        }
        if (!matched || !takeIfMatch) {
            source.position = start
            if (takeIfMatch) {
                buffer.setLength(0)
                buffer.append(oldBuffer)
            }
        }
        return matched
    }

    companion object {
        fun isNewLine(c: Char) = CSharpLiteralScanner.isNewLine(c)
        fun isWhitespace(c: Char) = CSharpLiteralScanner.isWhitespace(c)
    }
}

/** `HtmlTokenizer`: text, whitespace, new lines, the markup characters (`<`, `/`, `>`, `=`, quotes, `!`, `?`, `[`, `]`, `--`), `@`. */
open class HtmlTokenizer(source: SeekableTextReader) : Tokenizer(source) {
    init {
        currentState = startState
    }

    override val startState: Int get() = DATA

    /** XML bodies (`@documentation`): `@` is text. */
    var ignoreRazorTransitions: Boolean = false

    override val razorCommentKind get() = RazorSyntaxKind.RazorCommentLiteral
    override val razorCommentTransitionKind get() = RazorSyntaxKind.RazorCommentTransition
    override val razorCommentStarKind get() = RazorSyntaxKind.RazorCommentStar

    override fun createToken(content: String, kind: RazorSyntaxKind, errors: List<RazorDiagnostic>): RazorToken = RazorToken(kind, content, errors)

    override fun dispatch(): StateResult = dispatchHtml()

    private fun dispatchHtml(): StateResult = when (currentState) {
        DATA -> data()
        TEXT -> text()
        ESCAPED_RAZOR_COMMENT_TRANSITION -> escapedRazorCommentTransition()
        RazorCommentTokenizerState.AFTER_RAZOR_COMMENT_TRANSITION -> afterRazorCommentTransition()
        RazorCommentTokenizerState.RAZOR_COMMENT_BODY -> razorCommentBody()
        RazorCommentTokenizerState.STAR_AFTER_RAZOR_COMMENT_BODY -> starAfterRazorCommentBody()
        RazorCommentTokenizerState.AT_TOKEN_AFTER_RAZOR_COMMENT_BODY -> atTokenAfterRazorCommentBody(startState)
        else -> throw IllegalStateException("Invalid TokenizerState $currentState")
    }

    private fun data(): StateResult {
        if (isWhitespace(currentCharacter)) return stay(whitespace())
        if (isNewLine(currentCharacter)) return stay(newline())
        if (currentCharacter == '@' && !ignoreRazorTransitions) {
            takeCurrent()
            if (currentCharacter == '*') return transition(RazorCommentTokenizerState.AFTER_RAZOR_COMMENT_TRANSITION, endToken(RazorSyntaxKind.RazorCommentTransition))
            if (currentCharacter == '@') return transition(ESCAPED_RAZOR_COMMENT_TRANSITION, endToken(RazorSyntaxKind.Transition))
            return stay(endToken(RazorSyntaxKind.Transition))
        }
        if (atToken()) return stay(token())
        return transition(TEXT)
    }

    private fun escapedRazorCommentTransition(): StateResult {
        takeCurrent()
        return transition(DATA, endToken(RazorSyntaxKind.Transition))
    }

    private fun text(): StateResult {
        var prev = '\u0000'
        while (!endOfFile && !(isWhitespace(currentCharacter) || isNewLine(currentCharacter)) && !atToken()) {
            prev = currentCharacter
            takeCurrent()
        }
        if (currentCharacter == '@') {
            val next = peek()
            // an email address: `a@b` stays text
            if ((prev.isLetter() || prev.isDigit()) && (next.isLetter() || next.isDigit())) {
                takeCurrent()
                return stay()
            }
        }
        return transition(DATA, endToken(RazorSyntaxKind.Text))
    }

    private fun token(): RazorToken? {
        val sym = currentCharacter
        takeCurrent()
        return when (sym) {
            '<' -> endToken(RazorSyntaxKind.OpenAngle)
            '!' -> endToken(RazorSyntaxKind.Bang)
            '/' -> endToken(RazorSyntaxKind.ForwardSlash)
            '?' -> endToken(RazorSyntaxKind.QuestionMark)
            '[' -> endToken(RazorSyntaxKind.LeftBracket)
            '>' -> endToken(RazorSyntaxKind.CloseAngle)
            ']' -> endToken(RazorSyntaxKind.RightBracket)
            '=' -> endToken(RazorSyntaxKind.Equals)
            '"' -> endToken(RazorSyntaxKind.DoubleQuote)
            '\'' -> endToken(RazorSyntaxKind.SingleQuote)
            '-' -> {
                takeCurrent()
                endToken(RazorSyntaxKind.DoubleHyphen)
            }
            else -> endToken(RazorSyntaxKind.Marker)
        }
    }

    private fun whitespace(): RazorToken? {
        while (isWhitespace(currentCharacter)) takeCurrent()
        return endToken(RazorSyntaxKind.Whitespace)
    }

    private fun newline(): RazorToken? {
        val checkTwoCharNewline = currentCharacter == '\r'
        takeCurrent()
        if (checkTwoCharNewline && currentCharacter == '\n') takeCurrent()
        return endToken(RazorSyntaxKind.NewLine)
    }

    private fun atToken(): Boolean = when (currentCharacter) {
        '<', '!', '/', '?', '[', '>', ']', '=', '"', '\'' -> true
        '@' -> !ignoreRazorTransitions
        '-' -> peek() == '-'
        else -> false
    }

    private companion object {
        const val DATA = 0
        const val TEXT = 1
        const val ESCAPED_RAZOR_COMMENT_TRANSITION = 2
    }
}

/** `CSharpTokenizer`: the base of the two C# tokenizers; [getTokenKeyword] gives the keyword text of a `Keyword` token. */
abstract class CSharpTokenizer(source: SeekableTextReader) : Tokenizer(source)

/**
 * `NativeCSharpTokenizer`: the compiler's own C# tokenizer, the one its parser tests are written against. Identifiers and keywords,
 * numbers, simple string and character literals, comments, operators; no interpolated or raw strings (they are tokenized as the
 * compiler would, piece by piece).
 */
open class NativeCSharpTokenizer(source: SeekableTextReader) : CSharpTokenizer(source) {
    init {
        currentState = startState
    }

    override val startState: Int get() = DATA
    override val razorCommentKind get() = RazorSyntaxKind.RazorCommentLiteral
    override val razorCommentTransitionKind get() = RazorSyntaxKind.RazorCommentTransition
    override val razorCommentStarKind get() = RazorSyntaxKind.RazorCommentStar

    override fun createToken(content: String, kind: RazorSyntaxKind, errors: List<RazorDiagnostic>): RazorToken = RazorToken(kind, content, errors)

    override fun getTokenKeyword(token: RazorToken?): String? = token?.content?.takeIf { it in KEYWORDS }

    override fun dispatch(): StateResult = dispatchCSharp()

    private fun dispatchCSharp(): StateResult = when (currentState) {
        DATA -> data()
        BLOCK_COMMENT -> blockComment()
        QUOTED_CHARACTER_LITERAL -> quotedLiteral('\'', RazorSyntaxKind.CharacterLiteral)
        QUOTED_STRING_LITERAL -> quotedLiteral('"', RazorSyntaxKind.StringLiteral)
        VERBATIM_STRING_LITERAL -> verbatimStringLiteral()
        RazorCommentTokenizerState.AFTER_RAZOR_COMMENT_TRANSITION -> afterRazorCommentTransition()
        ESCAPED_RAZOR_COMMENT_TRANSITION -> escapedRazorCommentTransition()
        RazorCommentTokenizerState.RAZOR_COMMENT_BODY -> razorCommentBody()
        RazorCommentTokenizerState.STAR_AFTER_RAZOR_COMMENT_BODY -> starAfterRazorCommentBody()
        RazorCommentTokenizerState.AT_TOKEN_AFTER_RAZOR_COMMENT_BODY -> atTokenAfterRazorCommentBody(startState)
        else -> throw IllegalStateException("Invalid TokenizerState $currentState")
    }

    private fun data(): StateResult {
        if (isNewLine(currentCharacter)) {
            val checkTwoCharNewline = currentCharacter == '\r'
            takeCurrent()
            if (checkTwoCharNewline && currentCharacter == '\n') takeCurrent()
            return stay(endToken(RazorSyntaxKind.NewLine))
        }
        if (isWhitespace(currentCharacter)) {
            takeUntil { !isWhitespace(it) }
            return stay(endToken(RazorSyntaxKind.Whitespace))
        }
        if (CSharpLiteralScanner.isIdentifierStartCharacter(currentCharacter)) return identifier()
        if (currentCharacter.isDigit()) return numericLiteral()
        return when (currentCharacter) {
            '@' -> atToken()
            '\'' -> {
                takeCurrent()
                transition(QUOTED_CHARACTER_LITERAL)
            }
            '"' -> {
                takeCurrent()
                transition(QUOTED_STRING_LITERAL)
            }
            '.' -> if (peek().isDigit()) realLiteral() else stay(single(RazorSyntaxKind.Dot))
            '/' -> {
                takeCurrent()
                when (currentCharacter) {
                    '/' -> {
                        takeCurrent()
                        singleLineComment()
                    }
                    '*' -> {
                        takeCurrent()
                        transition(BLOCK_COMMENT)
                    }
                    '=' -> {
                        takeCurrent()
                        stay(endToken(RazorSyntaxKind.DivideAssign))
                    }
                    else -> stay(endToken(RazorSyntaxKind.Slash))
                }
            }
            else -> stay(endToken(operator()))
        }
    }

    private fun atToken(): StateResult {
        takeCurrent()
        if (currentCharacter == '"') {
            takeCurrent()
            return transition(VERBATIM_STRING_LITERAL)
        }
        if (currentCharacter == '*') return transition(RazorCommentTokenizerState.AFTER_RAZOR_COMMENT_TRANSITION, endToken(RazorSyntaxKind.RazorCommentTransition))
        if (currentCharacter == '@') return transition(ESCAPED_RAZOR_COMMENT_TRANSITION, endToken(RazorSyntaxKind.Transition))
        return stay(endToken(RazorSyntaxKind.Transition))
    }

    private fun escapedRazorCommentTransition(): StateResult {
        takeCurrent()
        return transition(DATA, endToken(RazorSyntaxKind.Transition))
    }

    private fun operator(): RazorSyntaxKind {
        val first = currentCharacter
        takeCurrent()
        return when (first) {
            '-' -> when (currentCharacter) {
                '>' -> take(RazorSyntaxKind.Arrow)
                '-' -> take(RazorSyntaxKind.Decrement)
                '=' -> take(RazorSyntaxKind.MinusAssign)
                else -> RazorSyntaxKind.Minus
            }
            '<' -> if (currentCharacter == '=') take(RazorSyntaxKind.LessThanEqual) else RazorSyntaxKind.LessThan
            '>' -> if (currentCharacter == '=') take(RazorSyntaxKind.GreaterThanEqual) else RazorSyntaxKind.GreaterThan
            '&' -> twoChar(RazorSyntaxKind.And, '=', RazorSyntaxKind.AndAssign, '&', RazorSyntaxKind.DoubleAnd)
            '|' -> twoChar(RazorSyntaxKind.Or, '=', RazorSyntaxKind.OrAssign, '|', RazorSyntaxKind.DoubleOr)
            '+' -> twoChar(RazorSyntaxKind.Plus, '=', RazorSyntaxKind.PlusAssign, '+', RazorSyntaxKind.Increment)
            '=' -> twoChar(RazorSyntaxKind.Assign, '=', RazorSyntaxKind.Equals, '>', RazorSyntaxKind.GreaterThanEqual)
            '!' -> twoChar(RazorSyntaxKind.Not, '=', RazorSyntaxKind.NotEqual)
            '%' -> twoChar(RazorSyntaxKind.Modulo, '=', RazorSyntaxKind.ModuloAssign)
            '*' -> twoChar(RazorSyntaxKind.Star, '=', RazorSyntaxKind.MultiplyAssign)
            ':' -> twoChar(RazorSyntaxKind.Colon, ':', RazorSyntaxKind.DoubleColon)
            '?' -> twoChar(RazorSyntaxKind.QuestionMark, '?', RazorSyntaxKind.NullCoalesce)
            '^' -> twoChar(RazorSyntaxKind.Xor, '=', RazorSyntaxKind.XorAssign)
            '(' -> RazorSyntaxKind.LeftParenthesis
            ')' -> RazorSyntaxKind.RightParenthesis
            '{' -> RazorSyntaxKind.LeftBrace
            '}' -> RazorSyntaxKind.RightBrace
            '[' -> RazorSyntaxKind.LeftBracket
            ']' -> RazorSyntaxKind.RightBracket
            ',' -> RazorSyntaxKind.Comma
            ';' -> RazorSyntaxKind.Semicolon
            '~' -> RazorSyntaxKind.Tilde
            '#' -> RazorSyntaxKind.Hash
            else -> RazorSyntaxKind.Marker
        }
    }

    private fun take(kind: RazorSyntaxKind): RazorSyntaxKind {
        takeCurrent()
        return kind
    }

    private fun twoChar(typeIfOnlyFirst: RazorSyntaxKind, second: Char, typeIfBoth: RazorSyntaxKind): RazorSyntaxKind =
        if (currentCharacter == second) take(typeIfBoth) else typeIfOnlyFirst

    private fun twoChar(typeIfOnlyFirst: RazorSyntaxKind, option1: Char, typeIfOption1: RazorSyntaxKind, option2: Char, typeIfOption2: RazorSyntaxKind): RazorSyntaxKind = when (currentCharacter) {
        option1 -> take(typeIfOption1)
        option2 -> take(typeIfOption2)
        else -> typeIfOnlyFirst
    }

    private fun verbatimStringLiteral(): StateResult {
        takeUntil { it == '"' }
        if (currentCharacter == '"') {
            takeCurrent()
            if (currentCharacter == '"') {
                takeCurrent()
                return stay()
            }
        } else if (endOfFile) {
            currentErrors += RazorDiagnosticFactory.unterminatedStringLiteral(SourceSpan(currentStart, 1))
        }
        return transition(DATA, endToken(RazorSyntaxKind.StringLiteral))
    }

    private fun quotedLiteral(quote: Char, literalType: RazorSyntaxKind): StateResult {
        takeUntil { it == '\\' || it == quote || isNewLine(it) }
        if (currentCharacter == '\\') {
            takeCurrent()
            if (currentCharacter == quote || currentCharacter == '\\') takeCurrent()
            return stay()
        } else if (endOfFile || isNewLine(currentCharacter)) {
            currentErrors += RazorDiagnosticFactory.unterminatedStringLiteral(SourceSpan(currentStart, 1))
        } else {
            takeCurrent()
        }
        return transition(DATA, endToken(literalType))
    }

    private fun blockComment(): StateResult {
        takeUntil { it == '*' }
        if (endOfFile) {
            currentErrors += RazorDiagnosticFactory.blockCommentNotTerminated(SourceSpan(currentStart, 1))
            return transition(DATA, endToken(RazorSyntaxKind.CSharpComment))
        }
        if (currentCharacter == '*') {
            takeCurrent()
            if (currentCharacter == '/') {
                takeCurrent()
                return transition(DATA, endToken(RazorSyntaxKind.CSharpComment))
            }
        }
        return stay()
    }

    private fun singleLineComment(): StateResult {
        takeUntil { isNewLine(it) }
        return stay(endToken(RazorSyntaxKind.CSharpComment))
    }

    private fun numericLiteral(): StateResult = if (takeAll("0x", caseSensitive = true)) hexLiteral() else decimalLiteral()

    private fun hexLiteral(): StateResult {
        takeUntil { !isHexDigit(it) }
        takeIntegerSuffix()
        return stay(endToken(RazorSyntaxKind.IntegerLiteral))
    }

    private fun decimalLiteral(): StateResult {
        takeUntil { !it.isDigit() }
        return if (currentCharacter == '.' && peek().isDigit()) realLiteral()
        else if (isRealLiteralSuffix(currentCharacter) || currentCharacter == 'E' || currentCharacter == 'e') realLiteralExponentPart()
        else {
            takeIntegerSuffix()
            stay(endToken(RazorSyntaxKind.IntegerLiteral))
        }
    }

    private fun realLiteralExponentPart(): StateResult {
        if (currentCharacter == 'E' || currentCharacter == 'e') {
            takeCurrent()
            if (currentCharacter == '+' || currentCharacter == '-') takeCurrent()
            takeUntil { !it.isDigit() }
        }
        if (isRealLiteralSuffix(currentCharacter)) takeCurrent()
        return stay(endToken(RazorSyntaxKind.RealLiteral))
    }

    private fun realLiteral(): StateResult {
        takeCurrent()
        takeUntil { !it.isDigit() }
        return realLiteralExponentPart()
    }

    private fun takeIntegerSuffix() {
        if (currentCharacter.lowercaseChar() == 'u') {
            takeCurrent()
            if (currentCharacter.lowercaseChar() == 'l') takeCurrent()
        } else if (currentCharacter.lowercaseChar() == 'l') {
            takeCurrent()
            if (currentCharacter.lowercaseChar() == 'u') takeCurrent()
        }
    }

    private fun identifier(): StateResult {
        takeCurrent()
        takeUntil { !CSharpLiteralScanner.isIdentifierPartCharacter(it) }
        var token: RazorToken? = null
        if (haveContent) {
            val content = buffer.toString()
            val type = if (content in KEYWORDS) RazorSyntaxKind.Keyword else RazorSyntaxKind.Identifier
            token = RazorToken(type, content)
            buffer.setLength(0)
            currentErrors.clear()
        }
        return stay(token)
    }

    private fun isRealLiteralSuffix(c: Char) = c == 'F' || c == 'f' || c == 'D' || c == 'd' || c == 'M' || c == 'm'
    private fun isHexDigit(c: Char) = c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'

    companion object {
        const val DATA = 0
        const val BLOCK_COMMENT = 1
        const val QUOTED_CHARACTER_LITERAL = 2
        const val QUOTED_STRING_LITERAL = 3
        const val VERBATIM_STRING_LITERAL = 4
        const val ESCAPED_RAZOR_COMMENT_TRANSITION = 5

        /** The keywords the native tokenizer knows (`_keywords`): the reserved ones plus `await`, `when`, `where`. */
        val KEYWORDS: Set<String> = hashSetOf(
            "await", "abstract", "byte", "class", "delegate", "event", "fixed", "if", "internal", "new", "override", "readonly", "short", "struct", "try",
            "unsafe", "volatile", "as", "do", "is", "params", "ref", "switch", "ushort", "while", "case", "const", "explicit", "float", "null", "sizeof",
            "typeof", "implicit", "private", "this", "using", "extern", "return", "stackalloc", "uint", "base", "catch", "continue", "double", "for", "in",
            "lock", "object", "protected", "static", "false", "public", "sbyte", "throw", "virtual", "decimal", "else", "operator", "string", "ulong",
            "bool", "char", "default", "foreach", "long", "void", "enum", "finally", "int", "out", "sealed", "true", "goto", "unchecked", "interface",
            "break", "checked", "namespace", "when", "where",
        )
    }
}

/** `TokenizerView`: the parser's window on a tokenizer — the current token, end of file, putting tokens back ([reset] to a position). */
class TokenizerView<T : Tokenizer>(val tokenizer: T) {
    var endOfFile: Boolean = false
        private set
    var current: RazorToken? = null
        private set

    val source: SeekableTextReader get() = tokenizer.source

    fun next(): Boolean {
        current = tokenizer.nextToken()
        endOfFile = current == null
        // a tokenizer that gives up before the end would leave the parsers looping on the same position: a bug, reported as one
        check(!endOfFile || tokenizer.stopsEarly || source.position >= source.length) { "${tokenizer.javaClass.simpleName} stopped at ${source.position} of ${source.length}" }
        return !endOfFile
    }

    fun putBack(token: RazorToken) {
        reset(source.position - token.content.length)
    }

    fun reset(position: Int) {
        source.position = position
        current = null
        endOfFile = source.position >= source.length
        tokenizer.reset(source.position)
    }
}

/** `LanguageCharacteristics<TTokenizer>`: what the parsers ask about a language's tokens; [Html] and [CSharp] are the two. */
abstract class LanguageCharacteristics<T : Tokenizer> {
    abstract fun createTokenizer(source: SeekableTextReader): T
    abstract fun flipBracket(bracket: RazorSyntaxKind): RazorSyntaxKind
    abstract fun createMarkerToken(): RazorToken
    abstract fun getKnownTokenType(type: KnownTokenType): RazorSyntaxKind
    abstract fun getSample(kind: RazorSyntaxKind): String
    protected abstract fun createToken(content: String, kind: RazorSyntaxKind): RazorToken

    fun isWhitespace(token: RazorToken?) = isKnownTokenType(token, KnownTokenType.Whitespace)
    fun isNewLine(token: RazorToken?) = isKnownTokenType(token, KnownTokenType.NewLine)
    fun isIdentifier(token: RazorToken?) = isKnownTokenType(token, KnownTokenType.Identifier)
    fun isKeyword(token: RazorToken?) = isKnownTokenType(token, KnownTokenType.Keyword)
    fun isTransition(token: RazorToken?) = isKnownTokenType(token, KnownTokenType.Transition)
    fun isCommentStart(token: RazorToken?) = isKnownTokenType(token, KnownTokenType.CommentStart)
    fun isCommentStar(token: RazorToken?) = isKnownTokenType(token, KnownTokenType.CommentStar)
    fun isCommentBody(token: RazorToken?) = isKnownTokenType(token, KnownTokenType.CommentBody)
    fun isUnknown(token: RazorToken?) = isKnownTokenType(token, KnownTokenType.Unknown)
    fun isKnownTokenType(token: RazorToken?, type: KnownTokenType) = token != null && token.kind == getKnownTokenType(type)

    /** `SplitToken`: [token] cut at [splitAt], the left part of [leftType], the right (null when nothing is left) of the token's kind. */
    fun splitToken(token: RazorToken, splitAt: Int, leftType: RazorSyntaxKind): Pair<RazorToken, RazorToken?> {
        val left = createToken(token.content.substring(0, splitAt), leftType)
        val right = if (splitAt < token.content.length) createToken(token.content.substring(splitAt), token.kind) else null
        return left to right
    }

    fun knowsTokenType(type: KnownTokenType): Boolean = type == KnownTokenType.Unknown || getKnownTokenType(type) != RazorSyntaxKind.Marker
}

enum class KnownTokenType { Unknown, Identifier, Keyword, NewLine, Whitespace, Transition, CommentStart, CommentStar, CommentBody }

open class HtmlLanguageCharacteristicsBase : LanguageCharacteristics<HtmlTokenizer>() {
    override fun createTokenizer(source: SeekableTextReader) = HtmlTokenizer(source)
    override fun createToken(content: String, kind: RazorSyntaxKind) = RazorToken(kind, content)
    override fun createMarkerToken() = RazorToken(RazorSyntaxKind.Marker, "")

    override fun flipBracket(bracket: RazorSyntaxKind): RazorSyntaxKind = when (bracket) {
        RazorSyntaxKind.LeftBracket -> RazorSyntaxKind.RightBracket
        RazorSyntaxKind.OpenAngle -> RazorSyntaxKind.CloseAngle
        RazorSyntaxKind.RightBracket -> RazorSyntaxKind.LeftBracket
        RazorSyntaxKind.CloseAngle -> RazorSyntaxKind.OpenAngle
        else -> RazorSyntaxKind.Marker
    }

    override fun getKnownTokenType(type: KnownTokenType): RazorSyntaxKind = when (type) {
        KnownTokenType.CommentStart -> RazorSyntaxKind.RazorCommentTransition
        KnownTokenType.CommentStar -> RazorSyntaxKind.RazorCommentStar
        KnownTokenType.CommentBody -> RazorSyntaxKind.RazorCommentLiteral
        KnownTokenType.Identifier -> RazorSyntaxKind.Text
        KnownTokenType.Keyword -> RazorSyntaxKind.Text
        KnownTokenType.NewLine -> RazorSyntaxKind.NewLine
        KnownTokenType.Transition -> RazorSyntaxKind.Transition
        KnownTokenType.Whitespace -> RazorSyntaxKind.Whitespace
        KnownTokenType.Unknown -> RazorSyntaxKind.Marker
    }

    override fun getSample(kind: RazorSyntaxKind): String = when (kind) {
        RazorSyntaxKind.Text -> "<<text>>"
        RazorSyntaxKind.Whitespace -> "<<whitespace>>"
        RazorSyntaxKind.NewLine -> "<<newline>>"
        RazorSyntaxKind.OpenAngle -> "<"
        RazorSyntaxKind.Bang -> "!"
        RazorSyntaxKind.ForwardSlash -> "/"
        RazorSyntaxKind.QuestionMark -> "?"
        RazorSyntaxKind.DoubleHyphen -> "--"
        RazorSyntaxKind.LeftBracket -> "["
        RazorSyntaxKind.CloseAngle -> ">"
        RazorSyntaxKind.RightBracket -> "]"
        RazorSyntaxKind.Equals -> "="
        RazorSyntaxKind.DoubleQuote -> "\""
        RazorSyntaxKind.SingleQuote -> "'"
        RazorSyntaxKind.Transition -> "@"
        RazorSyntaxKind.Colon -> ":"
        RazorSyntaxKind.RazorCommentLiteral -> "<<razor comment>>"
        RazorSyntaxKind.RazorCommentStar -> "*"
        RazorSyntaxKind.RazorCommentTransition -> "@"
        else -> "<<unknown>>"
    }
}

/** The C# side: [createTokenizer] picks the tokenizer the options ask for (the compiler's own, or Roslyn's — csharp-psi's lexer). */
class CSharpLanguageCharacteristics(private val useRoslynTokenizer: Boolean, private val parseLeadingDirectives: Boolean = false) : LanguageCharacteristics<CSharpTokenizer>() {
    /** `FirstDirectiveCSharpLanguageCharacteristics` when [parseLeadingDirectives]: the directive tokenizer, whatever the tokenizer option. */
    override fun createTokenizer(source: SeekableTextReader): CSharpTokenizer =
        if (parseLeadingDirectives) DirectiveCSharpTokenizer(source) else if (useRoslynTokenizer) RoslynCSharpTokenizer(source) else NativeCSharpTokenizer(source)

    override fun createToken(content: String, kind: RazorSyntaxKind) = RazorToken(kind, content)
    override fun createMarkerToken() = RazorToken(RazorSyntaxKind.Marker, "")

    override fun getKnownTokenType(type: KnownTokenType): RazorSyntaxKind = when (type) {
        KnownTokenType.Identifier -> RazorSyntaxKind.Identifier
        KnownTokenType.Keyword -> RazorSyntaxKind.Keyword
        KnownTokenType.NewLine -> RazorSyntaxKind.NewLine
        KnownTokenType.Whitespace -> RazorSyntaxKind.Whitespace
        KnownTokenType.Transition -> RazorSyntaxKind.Transition
        KnownTokenType.CommentStart -> RazorSyntaxKind.RazorCommentTransition
        KnownTokenType.CommentStar -> RazorSyntaxKind.RazorCommentStar
        KnownTokenType.CommentBody -> RazorSyntaxKind.RazorCommentLiteral
        KnownTokenType.Unknown -> RazorSyntaxKind.Marker
    }

    override fun flipBracket(bracket: RazorSyntaxKind): RazorSyntaxKind = when (bracket) {
        RazorSyntaxKind.LeftBrace -> RazorSyntaxKind.RightBrace
        RazorSyntaxKind.LeftBracket -> RazorSyntaxKind.RightBracket
        RazorSyntaxKind.LeftParenthesis -> RazorSyntaxKind.RightParenthesis
        RazorSyntaxKind.LessThan -> RazorSyntaxKind.GreaterThan
        RazorSyntaxKind.RightBrace -> RazorSyntaxKind.LeftBrace
        RazorSyntaxKind.RightBracket -> RazorSyntaxKind.LeftBracket
        RazorSyntaxKind.RightParenthesis -> RazorSyntaxKind.LeftParenthesis
        RazorSyntaxKind.GreaterThan -> RazorSyntaxKind.LessThan
        else -> RazorSyntaxKind.Marker
    }

    override fun getSample(kind: RazorSyntaxKind): String = SAMPLES[kind] ?: when (kind) {
        RazorSyntaxKind.Identifier -> "<<identifier>>"
        RazorSyntaxKind.Keyword -> "<<keyword>>"
        RazorSyntaxKind.IntegerLiteral -> "<<integer literal>>"
        RazorSyntaxKind.NewLine -> "<<newline>>"
        RazorSyntaxKind.Whitespace -> "<<whitespace>>"
        RazorSyntaxKind.CSharpComment -> "<<comment>>"
        RazorSyntaxKind.RealLiteral -> "<<real literal>>"
        RazorSyntaxKind.CharacterLiteral -> "<<character literal>>"
        RazorSyntaxKind.StringLiteral -> "<<string literal>>"
        else -> "<<unknown>>"
    }

    private companion object {
        val SAMPLES: Map<RazorSyntaxKind, String> = mapOf(
            RazorSyntaxKind.Arrow to "->", RazorSyntaxKind.Minus to "-", RazorSyntaxKind.Decrement to "--", RazorSyntaxKind.MinusAssign to "-=",
            RazorSyntaxKind.NotEqual to "!=", RazorSyntaxKind.Not to "!", RazorSyntaxKind.Modulo to "%", RazorSyntaxKind.ModuloAssign to "%=",
            RazorSyntaxKind.AndAssign to "&=", RazorSyntaxKind.And to "&", RazorSyntaxKind.DoubleAnd to "&&", RazorSyntaxKind.LeftParenthesis to "(",
            RazorSyntaxKind.RightParenthesis to ")", RazorSyntaxKind.Star to "*", RazorSyntaxKind.MultiplyAssign to "*=", RazorSyntaxKind.Comma to ",",
            RazorSyntaxKind.Dot to ".", RazorSyntaxKind.Slash to "/", RazorSyntaxKind.DivideAssign to "/=", RazorSyntaxKind.DoubleColon to "::",
            RazorSyntaxKind.Colon to ":", RazorSyntaxKind.Semicolon to ";", RazorSyntaxKind.QuestionMark to "?", RazorSyntaxKind.NullCoalesce to "??",
            RazorSyntaxKind.RightBracket to "]", RazorSyntaxKind.LeftBracket to "[", RazorSyntaxKind.XorAssign to "^=", RazorSyntaxKind.Xor to "^",
            RazorSyntaxKind.LeftBrace to "{", RazorSyntaxKind.OrAssign to "|=", RazorSyntaxKind.DoubleOr to "||", RazorSyntaxKind.Or to "|",
            RazorSyntaxKind.RightBrace to "}", RazorSyntaxKind.Tilde to "~", RazorSyntaxKind.Plus to "+", RazorSyntaxKind.PlusAssign to "+=",
            RazorSyntaxKind.Increment to "++", RazorSyntaxKind.LessThan to "<", RazorSyntaxKind.LessThanEqual to "<=", RazorSyntaxKind.LeftShift to "<<",
            RazorSyntaxKind.LeftShiftAssign to "<<=", RazorSyntaxKind.Assign to "=", RazorSyntaxKind.Equals to "==", RazorSyntaxKind.GreaterThan to ">",
            RazorSyntaxKind.GreaterThanEqual to ">=", RazorSyntaxKind.RightShift to ">>", RazorSyntaxKind.RightShiftAssign to ">>=", RazorSyntaxKind.Hash to "#",
            RazorSyntaxKind.Transition to "@",
        )
    }
}

/** The HTML side as the parser uses it. */
object HtmlLanguageCharacteristics : HtmlLanguageCharacteristicsBase()

/** `FirstDirectiveHtmlLanguageCharacteristics`: the HTML side of `ParseLeadingDirectives` — a tokenizer that stops after the first token. */
object FirstDirectiveHtmlLanguageCharacteristics : HtmlLanguageCharacteristicsBase() {
    override fun createTokenizer(source: SeekableTextReader) = DirectiveHtmlTokenizer(source)
}

/** `DirectiveHtmlTokenizer`: tokens until the first one that is not whitespace, a line break, a Razor comment or a transition — then nothing. */
class DirectiveHtmlTokenizer(source: SeekableTextReader) : HtmlTokenizer(source) {
    override val stopsEarly: Boolean get() = true
    private var visitedFirstTokenStart = false
    private var firstTokenVisitLocation: SourceLocation = SourceLocation.UNDEFINED

    override fun dispatch(): StateResult {
        val location = currentLocation
        val result = super.dispatch()
        val token = result.result
        if (token != null && isValidTokenType(token.kind)) {
            visitedFirstTokenStart = true
            firstTokenVisitLocation = location
        }
        return result
    }

    override fun nextToken(): RazorToken? {
        check(buffer.isEmpty())
        startToken()
        if (endOfFile || (visitedFirstTokenStart && firstTokenVisitLocation != currentLocation)) return null
        val token = turn()
        check(buffer.isEmpty())
        return token
    }

    private fun isValidTokenType(kind: RazorSyntaxKind): Boolean =
        kind != RazorSyntaxKind.Whitespace && kind != RazorSyntaxKind.NewLine && kind != RazorSyntaxKind.RazorCommentLiteral &&
            kind != RazorSyntaxKind.RazorCommentStar && kind != RazorSyntaxKind.RazorCommentTransition && kind != RazorSyntaxKind.Transition
}

/** `DirectiveCSharpTokenizer`: the first directive's line only — tokens until the line break after the first real token. */
class DirectiveCSharpTokenizer(source: SeekableTextReader) : NativeCSharpTokenizer(source) {
    override val stopsEarly: Boolean get() = true
    private var visitedFirstTokenStart = false
    private var visitedFirstTokenLineEnd = false

    override fun dispatch(): StateResult {
        val result = super.dispatch()
        val token = result.result
        if (token != null && !visitedFirstTokenStart && isValidTokenType(token.kind)) visitedFirstTokenStart = true
        else if (token != null && visitedFirstTokenStart && token.kind == RazorSyntaxKind.NewLine) visitedFirstTokenLineEnd = true
        return result
    }

    override fun nextToken(): RazorToken? {
        check(buffer.isEmpty())
        startToken()
        if (endOfFile || (visitedFirstTokenStart && visitedFirstTokenLineEnd)) return null
        val token = turn()
        check(buffer.isEmpty())
        return token
    }

    private fun isValidTokenType(kind: RazorSyntaxKind): Boolean =
        kind != RazorSyntaxKind.Whitespace && kind != RazorSyntaxKind.NewLine && kind != RazorSyntaxKind.CSharpComment && kind != RazorSyntaxKind.RazorCommentLiteral &&
            kind != RazorSyntaxKind.RazorCommentStar && kind != RazorSyntaxKind.RazorCommentTransition && kind != RazorSyntaxKind.Transition
}
