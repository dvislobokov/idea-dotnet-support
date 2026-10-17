package io.github.dotnetsupport.razor.lang.parser

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpDirectiveTokenType
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLiteralScanner
import io.github.dotnetsupport.csharp.lang.lexer.LineState
import io.github.dotnetsupport.csharp.lang.lexer._CSharpLexer
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticFactory
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind
import io.github.dotnetsupport.razor.lang.syntax.RazorToken
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import java.util.TreeMap

/**
 * `RoslynCSharpTokenizer`: the C# tokenizer the SDK uses since .NET 10 (`use-roslyn-tokenizer`) — Roslyn's lexer behind the Razor
 * token kinds, csharp-psi's lexer (the port of Roslyn's) playing Roslyn's part here. What Razor does itself stays Razor's: `@` is a
 * transition (never a verbatim identifier), `@* *@` comments, the unterminated-literal and misplaced-directive errors. Differences
 * from the compiler's class come from the lexer's shape: trivia are plain tokens (no leading/trailing split: the token sequence is the
 * same), a directive is a run of tokens up to its line break (one `CSharpDirective` here, as Roslyn's structured trivia), an
 * interpolated string is a run of parts (one `StringLiteral`, as Roslyn's single token). [CSharpLexerCursor] keeps the lexer aligned
 * with the source position the parser moves (put-backs restart it from a snapshot of its directive state).
 */
class RoslynCSharpTokenizer(source: SeekableTextReader) : CSharpTokenizer(source) {
    private val cursor = CSharpLexerCursor(source.text)

    init {
        currentState = startState
    }

    override val startState: Int get() = START
    override val razorCommentKind get() = RazorSyntaxKind.RazorCommentLiteral
    override val razorCommentTransitionKind get() = RazorSyntaxKind.RazorCommentTransition
    override val razorCommentStarKind get() = RazorSyntaxKind.RazorCommentStar

    override fun createToken(content: String, kind: RazorSyntaxKind, errors: List<RazorDiagnostic>): RazorToken = RazorToken(kind, content, errors)

    /** `GetTokenKeyword`: a reserved or contextual keyword's text (`SyntaxFacts.GetKeywordKind` / `GetContextualKeywordKind`), else null. */
    override fun getTokenKeyword(token: RazorToken?): String? {
        val content = token?.content ?: return null
        return if (CSharpSyntaxFacts.getKeywordKind(content) != null || CSharpSyntaxFacts.getContextualKeywordKind(content) != null) content else null
    }

    override fun reset(position: Int) {
        currentState = START
    }

    private var newBlock = true

    override fun startingBlock() {
        newBlock = true
    }

    override fun dispatch(): StateResult = when (currentState) {
        START -> start()
        ON_RAZOR_COMMENT_STAR -> onRazorCommentStar()
        RazorCommentTokenizerState.AFTER_RAZOR_COMMENT_TRANSITION -> afterRazorCommentTransition()
        RazorCommentTokenizerState.RAZOR_COMMENT_BODY -> razorCommentBody()
        RazorCommentTokenizerState.STAR_AFTER_RAZOR_COMMENT_BODY -> starAfterRazorCommentBody()
        RazorCommentTokenizerState.AT_TOKEN_AFTER_RAZOR_COMMENT_BODY -> atTokenAfterRazorCommentBody(START)
        else -> throw IllegalStateException("Invalid TokenizerState $currentState")
    }

    private fun start(): StateResult {
        if (newBlock) {
            cursor.restartFresh(source.position)
            newBlock = false
        }
        val t = cursor.peek(source.position) ?: return stop()
        if (isTrivia(t.type)) return trivia(t)
        return token(t)
    }

    private fun isTrivia(type: IElementType): Boolean =
        type === SyntaxKind.WhitespaceTrivia || type === SyntaxKind.EndOfLineTrivia || type === SyntaxKind.SingleLineCommentTrivia || type === SyntaxKind.MultiLineCommentTrivia ||
            type === SyntaxKind.SingleLineDocumentationCommentTrivia || type === SyntaxKind.MultiLineDocumentationCommentTrivia || type === SyntaxKind.DisabledTextTrivia ||
            type === SyntaxKind.ConflictMarkerTrivia || type === SyntaxKind.PreprocessingMessageTrivia || type is CSharpDirectiveTokenType

    /** `Trivia()`: one Razor token per trivia; a directive line is one token, so is a misplaced one (Roslyn's skipped tokens). */
    private fun trivia(t: CSharpLexerCursor.Tok): StateResult {
        val type = t.type
        when {
            type === SyntaxKind.WhitespaceTrivia -> {
                takeTo(t.end)
                return stay(endToken(RazorSyntaxKind.Whitespace))
            }
            type === SyntaxKind.EndOfLineTrivia -> {
                takeTo(t.end)
                return stay(endToken(RazorSyntaxKind.NewLine))
            }
            type === SyntaxKind.MultiLineCommentTrivia && source.text[t.start] == '@' -> {
                // `@* … *@` inside C#: the lexer knows the comment, Razor tokenizes it itself
                takeCurrent()
                return transition(ON_RAZOR_COMMENT_STAR, endToken(RazorSyntaxKind.RazorCommentTransition))
            }
            type === SyntaxKind.SingleLineCommentTrivia || type === SyntaxKind.MultiLineCommentTrivia ||
                type === SyntaxKind.SingleLineDocumentationCommentTrivia || type === SyntaxKind.MultiLineDocumentationCommentTrivia -> {
                takeTo(t.end)
                // Roslyn's `///` comment ends with its line break; csharp-psi's lexer leaves the break a token of its own
                if (type === SyntaxKind.SingleLineDocumentationCommentTrivia) cursor.peek(source.position)?.let { next -> if (next.type === SyntaxKind.EndOfLineTrivia) takeTo(next.end) }
                if (endOfFile && (type === SyntaxKind.MultiLineCommentTrivia || type === SyntaxKind.MultiLineDocumentationCommentTrivia) && !buffer.endsWith("*/")) {
                    currentErrors += RazorDiagnosticFactory.blockCommentNotTerminated(SourceSpan(currentStart, 1))
                }
                return stay(endToken(RazorSyntaxKind.CSharpComment))
            }
            type === SyntaxKind.ConflictMarkerTrivia -> {
                takeTo(t.end)
                return stay(endToken(RazorSyntaxKind.ConflictMarkerTrivia))
            }
            type === SyntaxKind.DisabledTextTrivia -> {
                takeTo(t.end)
                disabledTextErrors()
                return stay(endToken(RazorSyntaxKind.CSharpDisabledText))
            }
            else -> return directive(t)
        }
    }

    /** A `#` line: `CSharpDirective` with RZ1043/RZ1045 when the lexer took it as a directive; `CSharpComment` with RZ1043 when it is misplaced. */
    private fun directive(first: CSharpLexerCursor.Tok): StateResult {
        val misplaced = first.lineStateBefore != LineState.CLEAN
        var keyword: IElementType? = null
        var t: CSharpLexerCursor.Tok? = first
        var endsWithNewLine = false
        // Roslyn's directive trivia (and its skipped tokens of a misplaced one) run through the line break, whatever the line holds
        while (t != null && t.start < source.length) {
            val type = t.type
            if (keyword == null && type is CSharpDirectiveTokenType && type.roslynKind !== SyntaxKind.HashToken) keyword = type.roslynKind
            takeTo(t.end)
            if (type === SyntaxKind.EndOfLineTrivia) {
                endsWithNewLine = true
                break
            }
            t = cursor.peek(source.position)
        }
        if (misplaced) {
            currentErrors += RazorDiagnosticFactory.preprocessorDirectivesMustBeAtTheStartOfLine(SourceSpan(currentStart, buffer.length))
            return stay(endToken(RazorSyntaxKind.CSharpComment))
        }
        if (!onlyWhitespaceBefore(currentStart.absoluteIndex)) {
            currentErrors += RazorDiagnosticFactory.preprocessorDirectivesMustBeAtTheStartOfLine(SourceSpan(currentStart, buffer.length))
        }
        if (keyword === SyntaxKind.DefineKeyword || keyword === SyntaxKind.UndefKeyword) {
            currentErrors += RazorDiagnosticFactory.defineAndUndefNotAllowed(SourceSpan(currentStart, buffer.length))
        }
        return stay(endToken(RazorSyntaxKind.CSharpDirective))
    }

    /** The `#else` / `#endif` inside disabled text that are not at a line start: RZ1044 (the compiler's scan of `DisabledTextTrivia`). */
    private fun disabledTextErrors() {
        var onlyWhitespace = true
        var i = 0
        val text = buffer
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\r' || c == '\n' -> onlyWhitespace = true
                c == '#' && !onlyWhitespace -> {
                    val rest = text.substring(i + 1)
                    val length = if (rest.startsWith("else")) "#else".length else if (rest.startsWith("endif")) "#endif".length else 0
                    if (length > 0) {
                        val start = currentStart.absoluteIndex + i
                        currentErrors += RazorDiagnosticFactory.possibleMisplacedPreprocessorDirective(SourceSpan(source.locationOf(start), length))
                        i += length - 1
                    }
                }
                !isWhitespace(c) -> onlyWhitespace = false
            }
            i++
        }
    }

    /** `Token()`: dispatch on the character, as the compiler does, the lexer's token giving the extent and the kind. */
    private fun token(t: CSharpLexerCursor.Tok): StateResult {
        val c = currentCharacter
        if (CSharpLiteralScanner.isIdentifierStartCharacter(c)) return identifier(t)
        if (c.isDigit()) return numericLiteral(t)
        return when (c) {
            '@' -> atToken(t)
            '\'' -> literal(t, StringKind.Character)
            '"' -> literal(t, StringKind.StringOrRawString)
            '$' -> when (peek()) {
                '"', '$' -> literal(t, StringKind.InterpolatedOrRawInterpolatedString)
                '@' -> if (peek(2) == '"') literal(t, StringKind.VerbatimInterpolatedDollarFirst) else operator(t)
                else -> operator(t)
            }
            '.' -> when {
                peek().isDigit() -> numericLiteral(t)
                else -> operator(t)
            }
            else -> operator(t)
        }
    }

    private fun atToken(t: CSharpLexerCursor.Tok): StateResult {
        return when (peek()) {
            '"' -> literal(t, StringKind.VerbatimString)
            '$' -> if (peek(2) == '"') literal(t, StringKind.VerbatimInterpolatedAtFirst) else transitionToken()
            else -> transitionToken()
        }
    }

    private fun transitionToken(): StateResult {
        takeCurrent()
        return stay(endToken(RazorSyntaxKind.Transition))
    }

    private fun identifier(t: CSharpLexerCursor.Tok): StateResult {
        // the lexer lexes `@name` as one identifier when the tokenizer is restarted after an `@`: not here, `@` is a transition first
        takeTo(t.end)
        val text = buffer.toString()
        val type = if (t.type !== SyntaxKind.IdentifierToken || CSharpSyntaxFacts.getContextualKeywordKind(text) != null) RazorSyntaxKind.Keyword else RazorSyntaxKind.Identifier
        return stay(endToken(type))
    }

    private fun numericLiteral(t: CSharpLexerCursor.Tok): StateResult {
        takeTo(t.end)
        return stay(endToken(RazorSyntaxKind.NumericLiteral))
    }

    private fun operator(t: CSharpLexerCursor.Tok): StateResult {
        takeTo(t.end)
        val type = t.type
        val kind = when {
            type === SyntaxKind.ExclamationToken -> RazorSyntaxKind.Not
            type === SyntaxKind.OpenParenToken -> RazorSyntaxKind.LeftParenthesis
            type === SyntaxKind.CloseParenToken -> RazorSyntaxKind.RightParenthesis
            type === SyntaxKind.CommaToken -> RazorSyntaxKind.Comma
            type === SyntaxKind.DotToken -> RazorSyntaxKind.Dot
            type === SyntaxKind.ColonColonToken -> RazorSyntaxKind.DoubleColon
            type === SyntaxKind.ColonToken -> RazorSyntaxKind.Colon
            type === SyntaxKind.OpenBraceToken -> RazorSyntaxKind.LeftBrace
            type === SyntaxKind.CloseBraceToken -> RazorSyntaxKind.RightBrace
            type === SyntaxKind.LessThanToken -> RazorSyntaxKind.LessThan
            type === SyntaxKind.GreaterThanToken -> RazorSyntaxKind.GreaterThan
            type === SyntaxKind.EqualsToken -> RazorSyntaxKind.Assign
            type === SyntaxKind.OpenBracketToken -> RazorSyntaxKind.LeftBracket
            type === SyntaxKind.CloseBracketToken -> RazorSyntaxKind.RightBracket
            type === SyntaxKind.QuestionToken -> RazorSyntaxKind.QuestionMark
            type === SyntaxKind.SemicolonToken -> RazorSyntaxKind.Semicolon
            CSharpSyntaxFacts.isPunctuation(type) -> RazorSyntaxKind.CSharpOperator
            else -> RazorSyntaxKind.Marker
        }
        return stay(endToken(kind))
    }

    private enum class StringKind { Character, StringOrRawString, InterpolatedOrRawInterpolatedString, VerbatimString, VerbatimInterpolatedAtFirst, VerbatimInterpolatedDollarFirst }

    /** `TokenizedExpectedStringOrCharacterLiteral`: the literal's extent from the lexer, the unterminated check from its text. */
    private fun literal(t: CSharpLexerCursor.Tok, expectedKind: StringKind): StateResult {
        val end = cursor.literalEnd(t)
        takeTo(end)
        val text = buffer.toString()
        var expectedPrefix: String
        var expectedPostfix: String
        var lookForPrePostfix = false
        when (expectedKind) {
            StringKind.Character -> { expectedPrefix = "'"; expectedPostfix = "'" }
            StringKind.VerbatimString -> { expectedPrefix = "@\""; expectedPostfix = "\"" }
            StringKind.VerbatimInterpolatedAtFirst -> { expectedPrefix = "@$\""; expectedPostfix = "\"" }
            StringKind.VerbatimInterpolatedDollarFirst -> { expectedPrefix = "$@\""; expectedPostfix = "\"" }
            StringKind.StringOrRawString -> if (text == "\"\"") { expectedPrefix = "\""; expectedPostfix = "\"" } else { expectedPrefix = ""; expectedPostfix = ""; lookForPrePostfix = true }
            StringKind.InterpolatedOrRawInterpolatedString -> if (text == "$\"\"") { expectedPrefix = "$\""; expectedPostfix = "\"" } else { expectedPrefix = ""; expectedPostfix = ""; lookForPrePostfix = true }
        }
        if (lookForPrePostfix) {
            // the prefix is the run of `$` and `"` the literal starts with; the postfix the same count of quotes
            var i = 0
            var stop = false
            while (i < text.length && !stop) {
                val c = text[i]
                when (expectedKind) {
                    StringKind.StringOrRawString -> if (c == '"') { expectedPrefix += '"'; expectedPostfix += '"' } else stop = true
                    else -> if (expectedPrefix.isEmpty() || expectedPrefix.last() == '$') {
                        if (c == '$') expectedPrefix += '$' else if (c == '"') { expectedPrefix += '"'; expectedPostfix += '"' } else stop = true
                    } else if (c == '"') { expectedPrefix += '"'; expectedPostfix += '"' } else stop = true
                }
                if (!stop) i++
            }
            lookForPrePostfix = i >= text.length
        }
        if (lookForPrePostfix || text == expectedPrefix || !isStringProperlyTerminated(text, expectedPostfix)) {
            currentErrors += RazorDiagnosticFactory.unterminatedStringLiteral(SourceSpan(currentStart, expectedPrefix.length))
        }
        val kind = if (expectedKind == StringKind.Character) RazorSyntaxKind.CharacterLiteral else RazorSyntaxKind.StringLiteral
        return stay(endToken(kind))
    }

    private fun isStringProperlyTerminated(tokenText: String, expectedPostfix: String): Boolean {
        if (tokenText.endsWith(expectedPostfix)) return true
        if (tokenText.length >= expectedPostfix.length + 2) {
            val suffix = tokenText.substring(tokenText.length - 2)
            if ((suffix[0] == 'u' || suffix[0] == 'U') && suffix[1] == '8') return tokenText.substring(0, tokenText.length - 2).endsWith(expectedPostfix)
        }
        return false
    }

    private fun onRazorCommentStar(): StateResult {
        takeCurrent()
        return transition(RazorCommentTokenizerState.RAZOR_COMMENT_BODY, endToken(RazorSyntaxKind.RazorCommentStar))
    }

    private fun takeTo(end: Int) {
        while (source.position < end && !endOfFile) takeCurrent()
    }

    /** `_isOnlyWhitespaceOnLine` at [position]: the compiler keeps the flag as it tokenizes, which amounts to the text before on the line. */
    private fun onlyWhitespaceBefore(position: Int): Boolean {
        var i = position - 1
        while (i >= 0) {
            val c = source.text[i]
            if (isNewLine(c)) return true
            if (!isWhitespace(c)) return false
            i--
        }
        return true
    }

    private companion object {
        const val START = 0
        const val ON_RAZOR_COMMENT_STAR = 1
    }
}

/**
 * csharp-psi's lexer kept in step with the parser's position: [peek] gives the token at a position, restarting the lexer when the
 * parser moved elsewhere (put-backs, resets), from the nearest snapshot of the lexer's directive state before that position.
 */
class CSharpLexerCursor(private val text: CharSequence) {
    class Tok(val type: IElementType, val start: Int, val end: Int, val lineStateBefore: Int)

    private val lexer = _CSharpLexer()
    private var started = false

    /** The last token lexed; the lexer stands at its end ([lexerEnd]). */
    private var pending: Tok? = null
    private var lexerEnd = 0
    private val snapshots = TreeMap<Int, Array<Any>>()

    /** The lexer's token starting at [position], or null at the end of the text. */
    fun peek(position: Int): Tok? {
        val p = pending
        if (p != null && p.start == position) return p
        if (!started || position < (p?.start ?: lexerEnd)) {
            restartAt(position)
        } else if (p != null && p.start < position && position < p.end) {
            // the parser stands inside a lexer token (a reset into the middle of a run): lex afresh from there
            restartAt(position, fresh = true)
        }
        var next = if (pending != null && pending!!.end > position) pending else advance()
        while (next != null && next.end <= position) next = advance()
        if (next == null) return null
        if (next.start != position) {
            restartAt(position, fresh = true)
            next = advance()
        }
        return next
    }

    /** The end of a literal that starts with [first]: an interpolated string spans its parts up to the matching end token. */
    fun literalEnd(first: Tok): Int {
        val type = first.type
        if (!(type === SyntaxKind.InterpolatedStringStartToken || type === SyntaxKind.InterpolatedVerbatimStringStartToken ||
                type === SyntaxKind.InterpolatedSingleLineRawStringStartToken || type === SyntaxKind.InterpolatedMultiLineRawStringStartToken)
        ) return first.end
        var depth = 0
        var t: Tok? = first
        var end = first.end
        while (t != null) {
            val k = t.type
            if (k === SyntaxKind.InterpolatedStringStartToken || k === SyntaxKind.InterpolatedVerbatimStringStartToken ||
                k === SyntaxKind.InterpolatedSingleLineRawStringStartToken || k === SyntaxKind.InterpolatedMultiLineRawStringStartToken
            ) depth++
            if (k === SyntaxKind.InterpolatedStringEndToken || k === SyntaxKind.InterpolatedRawStringEndToken) depth--
            end = t.end
            if (depth == 0) break
            t = advance()
        }
        return end
    }

    private fun advance(): Tok? {
        val lineState = lexer.lineStateValue()
        if (!lexer.inQueue()) snapshots[lexerEnd] = lexer.contextSnapshot()
        val type = lexer.advance()
        if (type == null) {
            pending = null
            return null
        }
        val tok = Tok(type, lexer.tokenStart, lexer.tokenEnd, lineState)
        pending = tok
        lexerEnd = tok.end
        return tok
    }

    /** A new C# block starts at [position] (`SkipForwardTo` of Roslyn's token parser): lexing starts afresh there, whatever was lexed
     *  through it as lookahead before (`Foo #@item`: the `#` of the markup had made a directive run, the C# begins at the `@`). */
    fun restartFresh(position: Int) = restartAt(position, fresh = true)

    private fun restartAt(position: Int, fresh: Boolean = false) {
        pending = null
        val entry = if (fresh) null else snapshots.floorEntry(position)
        lexer.clearQueue()
        lexer.resetContext()
        started = true
        if (entry == null) {
            lexer.reset(text, position, text.length, _CSharpLexer.YYINITIAL)
            lexerEnd = position
            return
        }
        lexer.reset(text, entry.key, text.length, _CSharpLexer.YYINITIAL)
        lexer.restoreContext(entry.value)
        lexerEnd = entry.key
        // the snapshots after the restart point are stale
        snapshots.tailMap(entry.key, false).clear()
    }
}

private val IElementType?.isBadCharacter: Boolean get() = this === TokenType.BAD_CHARACTER
