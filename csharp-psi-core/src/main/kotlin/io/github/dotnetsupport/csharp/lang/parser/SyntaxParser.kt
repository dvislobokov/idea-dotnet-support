// Ported from Roslyn: src/Compilers/CSharp/Portable/Parser/SyntaxParser.cs, SyntaxParser.ResetPoint.cs and the
// infrastructure parts of LanguageParser.cs (TerminatorState 58-135, reset points 14634-14707, SkipBad* 4523-4672,
// ParseCommaSeparatedSyntaxList 14508-14632, ConsumeUnexpectedTokens 14709), roslynCommit
// 35d9211b841e7613c1d2f8f5af6d628ace696c4c. Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License
// (NOTICE.md). Mapping of the token window onto PsiBuilder: docs/csharp-psi/PORTING_MAP.md section 4, docs/csharp-psi/GRAMMAR.md.
package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.lang.PsiBuilder
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.CSharpFeature
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import io.github.dotnetsupport.csharp.lang.CSharpMissingTokenType
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.CSharpTokenType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpDiagnosticAnchor
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpErrorCode
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexerDiagnostics
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLiteralScanner
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes

/**
 * The description of an error element that carries no diagnostic of its own: skipped tokens whose error is elsewhere or not
 * reported, a missing token Roslyn reports through the skipped token after it. Not a [CSharpErrorCode.describe]: nothing is reported.
 */
const val SKIPPED: String = "Skipped tokens"

/**
 * A token as Roslyn's parser sees it: [kind] (contextual keywords are [SyntaxKind.IdentifierToken], as in Roslyn's
 * lexer), [contextualKind] (`SyntaxToken.ContextualKind`: the keyword kind of a contextual keyword spelled by an
 * identifier, the kind itself otherwise), the span and the raw PsiBuilder step. [SyntaxKind.EndOfFileToken] at the end,
 * zero-width at the text length.
 */
class Tok(
    val kind: IElementType,
    val contextualKind: IElementType,
    val rawStep: Int,
    val start: Int,
    val end: Int,
    /**
     * Raw step of the token's last lexeme: [rawStep] except for an interpolated string, which is one token to the parser
     * as Roslyn's `InterpolatedStringToken` (start kind, extent of the whole literal) until `parseInterpolatedStringToken`
     * splits it (`SyntaxParser.splitInterpolatedStringAt`).
     */
    val lastStep: Int = rawStep,
) {
    val isEof: Boolean get() = kind === SyntaxKind.EndOfFileToken
    override fun toString() = "$kind@$start-$end"
}

/**
 * A completed node: the done marker (for `precede()`) and what Roslyn's callers inspect on the built green node
 * (`Kind`, `IsMissing`, `ContainsDiagnostics`, `GetLastToken().IsMissing`, a few structural facts). Hard spot 7.2 of
 * docs/csharp-psi/PORTING_MAP.md: "inspect the built node" decisions are made from these fields.
 */
class Node(
    val marker: PsiBuilder.Marker,
    val kind: IElementType,
    /** Raw step of the first token (or of the position where the node starts when it is empty). */
    val startRaw: Int,
    /** Error count of the parser when the node was opened. */
    val errorsAtStart: Int,
    /** Roslyn `IsMissing`: no real token inside (only missing tokens). */
    val isMissing: Boolean,
    /** Roslyn `ContainsDiagnostics`: parser errors and the lexer errors of literals (`CSharpLexerDiagnostics`). */
    var containsErrors: Boolean,
    /** Roslyn `GetLastToken().IsMissing`. */
    val lastTokenMissing: Boolean,
) {
    /**
     * `LanguageParser.ContainsErrorDiagnostic(node)` (LP 14726): an error on the node's own tokens and nodes. Unlike
     * [containsErrors] it leaves out the errors of skipped tokens, which Roslyn hangs on tokens as trivia and the walk
     * over `ChildNodesAndTokens` does not enter ([SyntaxParser.skippedErrorCount]).
     */
    var containsErrorDiagnostic: Boolean = containsErrors

    /** [SyntaxParser.skippedErrorCount] when the node was opened. */
    var skippedErrorsAtStart: Int = 0

    /** Raw index of the token after the node (where the parser was when it was completed); -1: unknown. */
    var endRaw: Int = -1

    /** The kind of the first token, when the caller needs it (`WhenTrue.GetFirstToken().Kind`). */
    var firstTokenKind: IElementType? = null

    /**
     * A child the caller inspects: the expression of a constant pattern or of an expression statement, the type of a
     * type pattern, the right name of a qualified name.
     */
    var inner: Node? = null

    /** The left operand/receiver, for `ConvertTypeToExpression` on qualified names. */
    var left: Node? = null

    /** Lambda: the return type (`result is ParenthesizedLambdaExpressionSyntax { ReturnType: NullableTypeSyntax }`). */
    var returnType: Node? = null

    /** `IdentifierName` whose identifier is a contextual keyword (the contextual kind), e.g. `var`. */
    var identifierContextualKind: IElementType? = null

    /** Names and member accesses built only of simple names, dots and `::` (convertible to a type, LPP 33). */
    var isNameShaped: Boolean = false

    override fun toString() = "Node($kind)"
}

/** An open marker; [done] turns it into a [Node]. */
class Open(val marker: PsiBuilder.Marker, val startRaw: Int, val errorsAtStart: Int, val skippedErrorsAtStart: Int = 0)

/** Roslyn `TerminatorState`: bit flags, see [SyntaxParser.isTerminator]. */
object TerminatorState {
    const val EndOfFile = 0
    const val IsNamespaceMemberStartOrStop = 1 shl 0
    const val IsAttributeDeclarationTerminator = 1 shl 1
    const val IsPossibleAggregateClauseStartOrStop = 1 shl 2
    const val IsPossibleMemberStartOrStop = 1 shl 3
    const val IsEndOfReturnType = 1 shl 4
    const val IsEndOfParameterList = 1 shl 5
    const val IsEndOfFieldDeclaration = 1 shl 6
    const val IsPossibleEndOfVariableDeclaration = 1 shl 7
    const val IsEndOfTypeArgumentList = 1 shl 8
    const val IsPossibleStatementStartOrStop = 1 shl 9
    const val IsEndOfFixedStatement = 1 shl 10
    const val IsEndOfTryBlock = 1 shl 11
    const val IsEndOfCatchClause = 1 shl 12
    const val IsEndOfFilterClause = 1 shl 13
    const val IsEndOfCatchBlock = 1 shl 14
    const val IsEndOfDoWhileExpression = 1 shl 15
    const val IsEndOfForStatementArgument = 1 shl 16
    const val IsEndOfDeclarationClause = 1 shl 17
    const val IsEndOfArgumentList = 1 shl 18
    const val IsSwitchSectionStart = 1 shl 19
    const val IsEndOfTypeParameterList = 1 shl 20
    const val IsEndOfMethodSignature = 1 shl 21
    const val IsEndOfNameInExplicitInterface = 1 shl 22
    const val IsEndOfFunctionPointerParameterList = 1 shl 23
    const val IsEndOfFunctionPointerParameterListErrored = 1 shl 24
    const val IsEndOfFunctionPointerCallingConvention = 1 shl 25
    const val IsEndOfTypeSignature = 1 shl 26
    const val IsExpressionOrPatternInCaseLabelOfSwitchStatement = 1 shl 27
    const val IsPatternInSwitchExpressionArm = 1 shl 28
}

enum class PostSkipAction { Continue, Abort }

/**
 * Roslyn's `SyntaxParser` on top of a [PsiBuilder]: the token window ([currentToken], [peekToken]), the `EatToken`
 * family, reset points, error helpers and the generic list/skip routines of `LanguageParser`.
 *
 * Differences from Roslyn, all recorded in docs/csharp-psi/GRAMMAR.md:
 *  - a missing token is an empty `CSharpMissingTokenType` composite of its kind holding a zero-width error element
 *    when reported ([createMissingToken]); skipped tokens are wrapped in an error element ([skipTokens]); a token
 *    eaten "even with incorrect kind" stays a bare token preceded by a gap of the expected kind with the diagnostic;
 *  - diagnostics attached to existing nodes/tokens (`AddError`) only count ([errorCount]) and feed
 *    [Node.containsErrors]; they do not change the tree;
 *  - `ConvertToKeyword`/`EatContextualToken` use `remapCurrentToken`. A remap survives `rollbackTo`, so remaps are
 *    logged, undone logically by [reset] and physically when the token is passed again ([advance]). Parsing never
 *    looks at the raw type of a contextual keyword token: [Tok.kind] normalises it to `IdentifierToken`;
 *  - `_recursionDepth` guards against stack overflow by giving up on the expression (missing name) past [MAX_DEPTH].
 */
abstract class SyntaxParser(
    val builder: PsiBuilder,
    /**
     * `Options.LanguageVersion`, effective (`Latest`/`Default` mapped). From [CSharpLanguageLevel.forBuilder] unless
     * given: a parse of a part of a file (a lazy body) must restore the file's version (docs/csharp-psi/GRAMMAR.md, "Language version").
     */
    languageVersion: CSharpLanguageVersion = CSharpLanguageLevel.forBuilder(builder),
) {
    @JvmField val languageVersion: CSharpLanguageVersion = languageVersion.effective()

    /** `SyntaxParser.IsFeatureEnabled` (SP 1165): `Options.IsFeatureEnabled(feature)`. */
    fun isFeatureEnabled(feature: CSharpFeature): Boolean = languageVersion.isFeatureEnabled(feature)

    /**
     * Lexer diagnostics of language features (`Lexer.CheckFeatureAvailability`, `ScanEscapeSequence`) are on the
     * token in Roslyn and make the nodes around it `ContainsDiagnostics`; our lexer carries no diagnostics, so they are
     * counted when the token is eaten ([advance]). Only versions below this one have such diagnostics.
     */
    private val checkLexerFeatures = !languageVersion.isFeatureEnabled(CSharpFeature.StringEscapeCharacter)

    // ---- parser state ------------------------------------------------------------------------------------------

    @JvmField var termState: Int = TerminatorState.EndOfFile

    /** Roslyn `IsScript`: always false here (the oracle parses `SourceCodeKind.Regular`); kept where Roslyn reads it. */
    @JvmField var isScript: Boolean = false
    @JvmField var isInAsync: Boolean = false
    @JvmField var isInQuery: Boolean = false
    @JvmField var isInFieldKeywordContext: Boolean = false
    @JvmField var forceConditionalAccessExpression: Boolean = false
    @JvmField var recursionDepth: Int = 0

    /** Parser errors so far (missing tokens, skipped tokens, `AddError`). */
    @JvmField var errorCount: Int = 0

    /**
     * The part of [errorCount] on skipped tokens (`SkippedTokensTrivia`: [skipTokens], the `SkipBad*` loops, ...):
     * trivia to Roslyn, so not seen by `ContainsErrorDiagnostic` ([Node.containsErrorDiagnostic]).
     */
    @JvmField var skippedErrorCount: Int = 0

    /** Counts the error of a run of skipped tokens (one error element): in [errorCount] and [skippedErrorCount]. */
    fun countSkippedError() {
        errorCount++
        skippedErrorCount++
    }

    /** True when the last token produced was a missing one (`GetLastToken().IsMissing`). */
    @JvmField var prevTokenMissing: Boolean = false

    /** Counts conditional expressions whose `WhenTrue` starts with `[` (hard spot 7.1, `containsTernaryCollectionToReinterpret`). */
    @JvmField var ternaryCollectionCount: Int = 0

    /** Kind of the last token of the type scanned last (`out SyntaxToken lastTokenOfType` of `ScanType`). */
    @JvmField var scanTypeLastTokenKind: IElementType? = null

    private val textLength = builder.originalText.length

    init {
        sync()
    }

    /**
     * PsiBuilder moves to the whitespace after a token on `advanceLexer()` and skips it only when asked for the token
     * type; the raw index, `rawLookup`, `mark()` and `remapCurrentToken` work on the raw position. Asking for the token
     * type keeps them all on the significant token.
     */
    private fun sync() {
        builder.tokenType
    }

    // ---- token window ------------------------------------------------------------------------------------------

    private var cacheRawIndex = -1
    private val cache = ArrayList<Tok>(8)

    /**
     * Depth of interpolation holes being parsed. Roslyn parses the text of a hole with a nested parser whose token
     * stream ends before the `:` of the format clause; here the lexer has already cut the hole, and the end of the
     * hole's expression ([interpolationHoleEnd], the format `:` included) is reported as `EndOfFileToken`
     * (docs/csharp-psi/GRAMMAR.md).
     */
    var interpolationHoleDepth = 0
        set(value) {
            field = value
            cacheRawIndex = -1
        }

    /**
     * Absolute raw index where the expression of the innermost interpolation hole being parsed ends (its closing `}`,
     * or the string part after it when the `}` is missing), -1 outside holes: the token there and after it read as
     * `EndOfFileToken`, as the end of the text of Roslyn's nested hole parser. Set by `parseInterpolation`.
     */
    var interpolationHoleEnd = -1
        set(value) {
            field = value
            cacheRawIndex = -1
        }

    /** `CurrentToken`. */
    val currentToken: Tok get() = peekToken(0)

    val currentKind: IElementType get() = currentToken.kind

    val currentContextualKind: IElementType get() = currentToken.contextualKind

    /** `PeekToken(n)`: the n-th significant token from the current one; trivia (whitespace, comments, directives) is skipped. */
    fun peekToken(n: Int): Tok {
        val rawIndex = builder.rawTokenIndex()
        if (rawIndex != cacheRawIndex) {
            cache.clear()
            cacheRawIndex = rawIndex
        }
        while (cache.size <= n) {
            val step = if (cache.isEmpty()) 0 else cache[cache.size - 1].lastStep + 1
            cache += tokenAt(step)
        }
        return cache[n]
    }

    /** The first significant token at or after raw [step]; EOF when there is none. */
    private fun tokenAt(fromStep: Int): Tok {
        var step = fromStep
        while (true) {
            val type = builder.rawLookup(step)
                ?: return Tok(SyntaxKind.EndOfFileToken, SyntaxKind.EndOfFileToken, step, textLength, textLength)
            if (!isTrivia(type)) {
                val start = builder.rawTokenTypeStart(step)
                if (interpolationHoleEnd >= 0 && builder.rawTokenIndex() + step >= interpolationHoleEnd) {
                    return Tok(SyntaxKind.EndOfFileToken, SyntaxKind.EndOfFileToken, step, start, start)
                }
                if (INTERPOLATED_STRING_STARTS.contains(type) && builder.rawTokenIndex() + step != splitInterpolatedStringAt) {
                    val last = interpolatedStringLastStep(step, start)
                    val end = builder.rawTokenTypeStart(last + 1).let { if (it < 0) textLength else it }
                    return Tok(type, type, step, start, end, last)
                }
                val end = builder.rawTokenTypeStart(step + 1).let { if (it < 0) textLength else it }
                val kind = if (CSharpSyntaxFacts.isContextualKeyword(type)) SyntaxKind.IdentifierToken else type
                val contextual = if (kind === SyntaxKind.IdentifierToken) contextualKindOf(start, end) else kind
                return Tok(kind, contextual, step, start, end)
            }
            step++
        }
    }

    /**
     * Absolute raw index of the start of the interpolated string `parseInterpolatedStringToken` is splitting into its
     * parts (`ParseInterpolatedStringToken` re-lexes Roslyn's single `InterpolatedStringToken`), -1 when none: any other
     * interpolated string is one token of the window ([Tok.lastStep]), so skipping, scanning and `PeekToken` treat it
     * as Roslyn does (a skipped `$"x {d}"` is one skipped token, not a hole whose `d` starts a member).
     */
    var splitInterpolatedStringAt = -1
        set(value) {
            field = value
            cacheRawIndex = -1
        }

    /** Absolute raw index of an interpolated string's start lexeme → absolute raw index of its last lexeme. */
    private val interpolatedStringLast = HashMap<Int, Int>()

    /** Raw step of the last lexeme of the interpolated string starting at raw [step] (text offset [start]). */
    private fun interpolatedStringLastStep(step: Int, start: Int): Int {
        val base = builder.rawTokenIndex()
        val last = interpolatedStringLast.getOrPut(base + step) {
            // The literal's extent as Roslyn's lexer scans it; our lexer's parts cover exactly that (token gate).
            val end = CSharpLiteralScanner(builder.originalText, textLength, start).also { it.scanInterpolatedStringLiteral() }.pos
            var s = step
            while (builder.rawLookup(s + 1) != null) {
                val next = builder.rawTokenTypeStart(s + 1)
                if (next < 0 || next >= end) break
                s++
            }
            base + s
        }
        return last - base
    }

    /**
     * `SyntaxFacts.GetContextualKeywordKind(token.ValueText)` on the *value* text: unlike [Tok.contextualKind], `@or`
     * is `OrKeyword` here (Roslyn's `ContextualKind` of `@or` is `IdentifierToken`, its `ValueText` is `or`).
     */
    fun contextualKindOfValueText(token: Tok): IElementType {
        if (token.kind !== SyntaxKind.IdentifierToken) return token.kind
        val text = builder.originalText
        if (token.end > token.start && text[token.start] == '@') {
            val word = text.subSequence(token.start + 1, token.end).toString()
            return CSharpSyntaxFacts.getContextualKeywordKind(word) ?: SyntaxKind.IdentifierToken
        }
        return token.contextualKind
    }

    /** `SyntaxFacts.GetContextualKeywordKind(ValueText)`: `_` is `UnderscoreToken`, `@x` is never a keyword. */
    private fun contextualKindOf(start: Int, end: Int): IElementType {
        val text = builder.originalText
        val length = end - start
        if (length == 1) return if (text[start] == '_') SyntaxKind.UnderscoreToken else SyntaxKind.IdentifierToken
        if (length > 10 || text[start] == '@') return SyntaxKind.IdentifierToken
        val first = text[start]
        if (first !in 'a'..'z') return SyntaxKind.IdentifierToken
        val word = text.subSequence(start, end).toString()
        return CSharpSyntaxFacts.getContextualKeywordKind(word) ?: SyntaxKind.IdentifierToken
    }

    /** True for trivia the parser never sees: whitespace, comments, directives, disabled text. */
    internal fun isTrivia(type: IElementType): Boolean =
        type === TokenType.WHITE_SPACE || CSharpSyntaxFacts.isTrivia(type) || type === SyntaxKind.EndOfDirectiveToken ||
            CSharpTokenTypes.WHITESPACES.contains(type) || CSharpTokenTypes.COMMENTS.contains(type)

    /** The significant token before the current one (`GetLastToken()` of what was just parsed), null at the start. */
    fun previousToken(): Tok? = tokenBefore(-1)

    /**
     * `node.GetLastToken()` (missing tokens excluded): the significant token before [Node.endRaw]; the token before the
     * current one when the end of [node] is unknown.
     */
    fun lastTokenOf(node: Node): Tok? =
        if (node.endRaw < 0) previousToken() else tokenBefore(node.endRaw - builder.rawTokenIndex() - 1)

    /** The significant token at raw [from] (relative to the current token) or before it. */
    private fun tokenBefore(from: Int): Tok? {
        var step = from
        while (true) {
            val type = builder.rawLookup(step) ?: return null
            if (!isTrivia(type)) {
                val start = builder.rawTokenTypeStart(step)
                val end = builder.rawTokenTypeStart(step + 1)
                val kind = if (CSharpSyntaxFacts.isContextualKeyword(type)) SyntaxKind.IdentifierToken else type
                val contextual = if (kind === SyntaxKind.IdentifierToken) contextualKindOf(start, end) else kind
                return Tok(kind, contextual, step, start, end)
            }
            step--
        }
    }

    /** `NoTriviaBetween(token1, token2)` (LP 5033): the two tokens are adjacent in the text. */
    fun noTriviaBetween(token1: Tok, token2: Tok): Boolean = token1.end == token2.start

    /** `token.TrailingTrivia.Any(EndOfLineTrivia)`: a line break between [token] and the next significant token. */
    fun hasTrailingNewline(token: Tok): Boolean {
        var step = token.lastStep + 1
        while (true) {
            val type = builder.rawLookup(step) ?: return false
            if (!isTrivia(type)) return false
            if (type === SyntaxKind.EndOfLineTrivia) return true
            // A documentation comment is never trailing trivia (`LexSyntaxTrivia`, isTrailing): the trivia ends here.
            if (type === SyntaxKind.SingleLineDocumentationCommentTrivia || type === SyntaxKind.MultiLineDocumentationCommentTrivia) return false
            if (type === TokenType.WHITE_SPACE || type === SyntaxKind.WhitespaceTrivia) {
                val s = builder.rawTokenTypeStart(step)
                val e = builder.rawTokenTypeStart(step + 1).let { if (it < 0) textLength else it }
                val text = builder.originalText
                for (i in s until e) if (text[i] == '\n' || text[i] == '\r') return true
            }
            step++
        }
    }

    val currentOffset: Int get() = currentToken.start

    /** Text of the current token (`CurrentToken.Text`). */
    val currentText: String get() = currentToken.let { builder.originalText.subSequence(it.start, it.end).toString() }

    /** Raw step index: a token position usable for `IsMakingProgress` and `FullWidth == 0` checks. */
    val position: Int get() = builder.rawTokenIndex()

    // ---- remaps (ConvertToKeyword) -------------------------------------------------------------------------------

    private val remapLog = ArrayList<Int>()
    private val staleRemaps = HashSet<Int>()

    fun remapCurrentToken(kind: IElementType) {
        val index = builder.rawTokenIndex()
        staleRemaps.remove(index)
        builder.remapCurrentToken(kind)
        remapLog += index
    }

    // ---- eating tokens -----------------------------------------------------------------------------------------

    /** Consumes the current token (`EatToken()`); false at EOF. */
    fun advance(): Boolean {
        if (builder.eof() || (interpolationHoleDepth > 0 && currentToken.isEof)) return false
        val index = builder.rawTokenIndex()
        if (staleRemaps.isNotEmpty() && staleRemaps.remove(index)) builder.remapCurrentToken(SyntaxKind.IdentifierToken)
        if (checkLexerFeatures) countLexerFeatureDiagnostics()
        countLexerErrors()
        val last = index + currentToken.lastStep
        builder.advanceLexer()
        sync()
        // An unsplit interpolated string: all its lexemes.
        while (builder.rawTokenIndex() <= last && !builder.eof()) {
            builder.advanceLexer()
            sync()
        }
        prevTokenMissing = false
        return true
    }

    /**
     * Roslyn's `CurrentToken.ContainsDiagnostics` for what the lexer reports: a bad character (`BadToken`, `ERR_UnexpectedCharacter`)
     * or a malformed literal ([CSharpLexerDiagnostics.hasError]). The `SkipBad*` loops put no diagnostic of their own on such a token.
     */
    fun currentTokenHasLexerError(): Boolean {
        val type = builder.tokenType ?: return false
        if (type === SyntaxKind.BadToken || type === com.intellij.psi.TokenType.BAD_CHARACTER) return true
        return CSharpLexerDiagnostics.hasError(type, builder.tokenText ?: return false)
    }

    /**
     * The errors Roslyn's lexer puts on the current literal token (`CSharpLexerDiagnostics`: unterminated or malformed
     * character, string and numeric literals), counted as errors on the token ([errorCount]): Roslyn's
     * `ContainsDiagnostics` of the nodes around it holds (`looksLikeVariableInitializer` of `ParseVariableDeclarator`).
     */
    private fun countLexerErrors() {
        val type = builder.tokenType ?: return
        if (type !== SyntaxKind.NumericLiteralToken && type !== SyntaxKind.StringLiteralToken &&
            type !== SyntaxKind.Utf8StringLiteralToken && type !== SyntaxKind.CharacterLiteralToken
        ) return
        val text = builder.tokenText ?: return
        if (CSharpLexerDiagnostics.hasError(type, text)) errorCount++
    }

    /**
     * The language-version diagnostics Roslyn's lexer puts on the current token (`Lexer.CheckFeatureAvailability`):
     * `ScanNumericLiteral` — a `0b` prefix (`IDS_FeatureBinaryLiteral`), a `_` right after `0x`/`0b`
     * (`IDS_FeatureLeadingDigitSeparator`) or elsewhere (`IDS_FeatureDigitSeparator`; a misplaced `_` is
     * `ERR_InvalidNumber` in every version, an error all the same); `ScanEscapeSequence` — `\e` in a regular string or
     * character literal (`IDS_FeatureStringEscapeCharacter`). Counted as errors on the token ([errorCount]).
     * Not covered (docs/csharp-psi/GRAMMAR.md): `\e` in the text of an interpolated string, and directive diagnostics
     * (`#nullable`, `#pragma`) on trivia.
     */
    private fun countLexerFeatureDiagnostics() {
        val type = builder.tokenType ?: return
        if (type !== SyntaxKind.NumericLiteralToken && type !== SyntaxKind.StringLiteralToken &&
            type !== SyntaxKind.Utf8StringLiteralToken && type !== SyntaxKind.CharacterLiteralToken
        ) return
        val text = builder.tokenText ?: return
        if (type === SyntaxKind.NumericLiteralToken) {
            val prefixed = text.length >= 2 && text[0] == '0' && text[1].lowercaseChar().let { it == 'x' || it == 'b' }
            if (prefixed && text[1].lowercaseChar() == 'b' && !isFeatureEnabled(CSharpFeature.BinaryLiteral)) errorCount++
            if (text.indexOf('_') >= 0) {
                val leading = prefixed && text.length > 2 && text[2] == '_'
                if (!isFeatureEnabled(if (leading) CSharpFeature.LeadingDigitSeparator else CSharpFeature.DigitSeparator)) errorCount++
            }
            return
        }
        // Regular literals only: a verbatim string (`@"`) has no escapes.
        if (text.isEmpty() || text[0] == '@') return
        var i = 1
        while (i < text.length - 1) {
            if (text[i] == '\\') {
                if (text[i + 1] == 'e') {
                    errorCount++
                    return
                }
                i += 2
            } else {
                i++
            }
        }
    }

    /** `EatToken()` */
    fun eatToken(): Boolean = advance()

    /** `TryEatToken(kind)` */
    fun tryEatToken(kind: IElementType): Boolean = if (currentKind === kind) advance() else false

    /** `EatToken(kind)`: the token, or a missing token with a diagnostic. Returns true when a real token was eaten. */
    fun eatToken(kind: IElementType): Boolean {
        if (currentKind === kind) return advance()
        createMissingToken(kind)
        return false
    }

    /** `EatToken(kind, reportError)` */
    fun eatToken(kind: IElementType, reportError: Boolean): Boolean {
        if (currentKind === kind) return advance()
        if (reportError) createMissingToken(kind) else createMissingToken(kind, report = false)
        return false
    }

    /**
     * `EatTokenAsKind(expected)`: the token when its kind matches; otherwise a missing token of the expected kind with
     * the current token as skipped syntax. Here: one error element around the wrong token. Returns true when the
     * expected kind was really there.
     */
    fun eatTokenAsKind(expected: IElementType): Boolean {
        if (currentKind === expected) return advance()
        if (currentToken.isEof) {
            createMissingToken(expected)
            return false
        }
        // Roslyn: `AddTrailingSkippedSyntax(CreateMissingToken(expected), EatToken())`: the missing token is positioned
        // before the skipped one, so the zero-width marker comes first.
        createMissingToken(expected)
        skipTokens(1, countError = false)
        return false
    }

    /**
     * `EatTokenEvenWithIncorrectKind(kind)`: eats the current token whatever it is, with a diagnostic when the kind is
     * wrong. The token keeps its kind, as in Roslyn; the diagnostic is a zero-width error element before it.
     */
    fun eatTokenEvenWithIncorrectKind(kind: IElementType) {
        // The gap of the expected kind tells the PSI that the token after it stands in for [kind] (the `;` separator of
        // `ParseCommaSeparatedSyntaxList(allowSemicolonAsSeparator)`, docs/csharp-psi/GRAMMAR.md "PSI").
        if (currentKind !== kind) createMissingToken(kind)
        advance()
    }

    /** `EatTokenWithPrejudice(errorCode)`: [message] is a [CSharpErrorCode.describe], on the token. */
    fun eatTokenWithPrejudice(message: String) {
        addErrorHere(message)
        advance()
    }

    /** `EatContextualToken(kind)`: converts the identifier into the keyword (`ConvertToKeyword`) or produces a missing token. */
    fun eatContextualToken(kind: IElementType): Boolean {
        if (currentContextualKind !== kind) {
            createMissingToken(kind)
            return false
        }
        return convertToKeyword()
    }

    /** `ConvertToKeyword(this.EatToken())`: the current identifier becomes the keyword it spells. */
    fun convertToKeyword(): Boolean {
        val tok = currentToken
        if (tok.kind === SyntaxKind.IdentifierToken && tok.contextualKind !== SyntaxKind.IdentifierToken) remapCurrentToken(tok.contextualKind)
        return advance()
    }

    /** Eats the current identifier as a given (contextual) keyword kind, e.g. `managed` in `EatTokenAsKind(ManagedKeyword)`. */
    fun eatCurrentAs(kind: IElementType): Boolean {
        remapCurrentToken(kind)
        return advance()
    }

    // ---- errors ---------------------------------------------------------------------------------------------------

    /**
     * `CreateMissingToken(expected)` (SP 552) and `EatToken(kind, code)`: an empty composite of
     * `CSharpMissingTokenType.of(expected)` at the current position, the kind of the gap for the PSI
     * (`CSharpSyntaxShape`); with [report] it holds a zero-width error element with [message] (Roslyn's diagnostic on
     * the missing token, a [CSharpErrorCode.describe]: by default `GetExpectedTokenError(expected, CurrentToken.Kind)`),
     * without it is empty (no diagnostic, no highlighting).
     */
    fun createMissingToken(expected: IElementType, report: Boolean = true, message: String = expectedMessage(expected, currentKind)) {
        val gap = builder.mark()
        if (report) {
            builder.mark().error(message)
            errorCount++
        }
        gap.done(CSharpMissingTokenType.of(expected))
        prevTokenMissing = true
    }

    /**
     * `CreateMissingIdentifierName()` (LP 6037) with the diagnostic Roslyn adds to it (`AddError(CreateMissingIdentifierName(), code)`):
     * an `IdentifierName` holding a missing identifier; [message] is a [CSharpErrorCode.describe], `ERR_IdentifierExpected` by default.
     */
    fun createMissingIdentifierName(message: String = CSharpErrorCode.ERR_IdentifierExpected.describe()): Node {
        val m = open()
        createMissingToken(SyntaxKind.IdentifierToken, message = message)
        // An `IdentifierNameSyntax` like any other: convertible to a type (`ConvertExpressionToType` of
        // `ParseTypeOrPatternForIsOperator`, LPP 33: `x is` + `if` is an `IsExpression`).
        return m.done(SyntaxKind.IdentifierName).also { it.isNameShaped = true }
    }

    /** `AddError(node, ...)`: counts a diagnostic on an already built node (the tree does not change). */
    fun addError(node: Node?): Node? {
        errorCount++
        // The diagnostic is on the node: `ContainsDiagnostics` of it (and of what is built around it) holds.
        node?.containsErrors = true
        node?.containsErrorDiagnostic = true
        return node
    }

    /** A zero-width error element at the current position (a diagnostic on the token that follows); [message]: [CSharpErrorCode.describe]. */
    fun addErrorHere(message: String) {
        builder.mark().error(message)
        errorCount++
    }

    /**
     * `AddTrailingSkippedSyntax`/`AddLeadingSkippedSyntax`: the next [count] tokens become an error element (Roslyn:
     * `SkippedTokensTrivia` on the neighbouring token). [message]: the diagnostic Roslyn puts on them ([CSharpErrorCode.describe]),
     * or [SKIPPED] when the skipping reports nothing itself (the diagnostic is elsewhere, or not reported).
     */
    fun skipTokens(count: Int, message: String = SKIPPED, countError: Boolean = true) {
        if (count <= 0 || currentToken.isEof) return
        val m = builder.mark()
        var n = count
        while (n-- > 0 && advance()) { /* eat */ }
        m.error(message)
        if (countError) countSkippedError()
    }

    /**
     * `AddLeadingSkippedSyntax(node, skipped)` for a construct Roslyn parses and then demotes to skipped tokens: [body]
     * is parsed to learn its extent, rolled back, and the same tokens are consumed into one error element.
     */
    fun skipAsError(message: String, body: () -> Unit) {
        val rp = getResetPoint()
        val startRaw = position
        body()
        val consumed = position - startRaw
        rp.reset()
        rp.release()
        if (consumed > 0) {
            val m = builder.mark()
            val target = startRaw + consumed
            while (position < target && advance()) { /* eat */ }
            m.error(message)
            countSkippedError()
        }
    }

    // ---- markers and nodes -----------------------------------------------------------------------------------------

    fun open(): Open = Open(builder.mark(), builder.rawTokenIndex(), errorCount, skippedErrorCount)

    fun Open.done(kind: IElementType): Node = done(kind, kind)

    /**
     * Completes the marker with [markerType] while the parser sees a node of [kind]: a reparseable body is a `Block` to
     * the parser and has a [CSharpBodyBlockType] carrying its parse context in the tree.
     */
    fun Open.done(kind: IElementType, markerType: IElementType): Node {
        marker.done(markerType)
        return Node(
            marker, kind, startRaw, errorsAtStart,
            isMissing = startRaw == builder.rawTokenIndex(),
            containsErrors = errorsAtStart != errorCount,
            lastTokenMissing = prevTokenMissing,
        ).also {
            it.endRaw = builder.rawTokenIndex()
            it.skippedErrorsAtStart = skippedErrorsAtStart
            it.containsErrorDiagnostic = errorCount - skippedErrorCount != errorsAtStart - skippedErrorsAtStart
        }
    }

    fun Open.drop() = marker.drop()

    /** A marker before an already built node (`precede()`): the left-associative and "wrap later" constructions. */
    fun Node.precede(): Open = Open(marker.precede(), startRaw, errorsAtStart, skippedErrorsAtStart)

    /** Wraps [this] into a node of [kind] whose extent is the node plus everything parsed since. */
    fun Node.wrap(kind: IElementType): Node = precede().done(kind)

    // ---- reset points ------------------------------------------------------------------------------------------------

    /** `LanguageParser.ResetPoint`: a PsiBuilder marker plus the parser state Roslyn snapshots (LP 14634-14707). */
    inner class ResetPoint {
        private var marker: PsiBuilder.Marker = builder.mark()
        private val termState = this@SyntaxParser.termState
        private val isInAsync = this@SyntaxParser.isInAsync
        private val isInQuery = this@SyntaxParser.isInQuery
        private val isInFieldKeywordContext = this@SyntaxParser.isInFieldKeywordContext
        private val errorCount = this@SyntaxParser.errorCount
        private val skippedErrorCount = this@SyntaxParser.skippedErrorCount
        private val prevTokenMissing = this@SyntaxParser.prevTokenMissing
        private val remapLogSize = remapLog.size
        private val ternaryCollectionCount = this@SyntaxParser.ternaryCollectionCount
        private val scanTypeLastTokenKind = this@SyntaxParser.scanTypeLastTokenKind
        private var released = false

        /** `Reset(ref point)`: back to the position and state; the point stays usable. */
        fun reset() {
            check(!released) { "reset point already released" }
            marker.rollbackTo()
            sync()
            marker = builder.mark()
            this@SyntaxParser.termState = termState
            this@SyntaxParser.isInAsync = isInAsync
            this@SyntaxParser.isInQuery = isInQuery
            this@SyntaxParser.isInFieldKeywordContext = isInFieldKeywordContext
            this@SyntaxParser.errorCount = errorCount
            this@SyntaxParser.skippedErrorCount = skippedErrorCount
            this@SyntaxParser.prevTokenMissing = prevTokenMissing
            this@SyntaxParser.ternaryCollectionCount = ternaryCollectionCount
            this@SyntaxParser.scanTypeLastTokenKind = scanTypeLastTokenKind
            while (remapLog.size > remapLogSize) staleRemaps += remapLog.removeAt(remapLog.size - 1)
            cacheRawIndex = -1
        }

        /** `Release(ref point)`. */
        fun release() {
            if (!released) {
                released = true
                marker.drop()
            }
        }
    }

    fun getResetPoint(): ResetPoint = ResetPoint()

    /** `using var _ = GetDisposableResetPoint(resetOnDispose: true)`: speculative scan, always rolled back. */
    inline fun <T> speculate(body: () -> T): T {
        val rp = getResetPoint()
        try {
            return body()
        } finally {
            rp.reset()
            rp.release()
        }
    }

    /** `using var rp = GetDisposableResetPoint(resetOnDispose: false)`: the body may call `rp.reset()`. */
    inline fun <T> withResetPoint(body: (ResetPoint) -> T): T {
        val rp = getResetPoint()
        try {
            return body(rp)
        } finally {
            rp.release()
        }
    }

    /** `IsMakingProgress(ref lastTokenPosition)` (SP 1178). */
    fun isMakingProgress(last: IntArray): Boolean {
        val pos = position
        if (pos > last[0]) {
            last[0] = pos
            return true
        }
        return false
    }

    // ---- terminators -----------------------------------------------------------------------------------------------

    /** `IsTerminator()` (LP 94): EOF, or one of the active terminator predicates holds. */
    fun isTerminator(): Boolean {
        if (currentToken.isEof) return true
        var i = 1
        while (i <= TerminatorState.IsPatternInSwitchExpressionArm) {
            if (termState and i != 0 && isTerminatorState(i)) return true
            i = i shl 1
        }
        return false
    }

    /** The predicate of one terminator flag; the derived parser supplies the language-specific ones. */
    protected abstract fun isTerminatorState(flag: Int): Boolean

    // ---- lists and skipping --------------------------------------------------------------------------------------

    /**
     * `ParseCommaSeparatedSyntaxList` (LP 14530). Elements are parsed by [parseElement]; separators are eaten here.
     * The `ref openToken` of Roslyn (where skipped tokens hang) is not needed: skipped tokens become error elements.
     */
    fun parseCommaSeparatedSyntaxList(
        closeTokenKind: IElementType,
        isPossibleElement: () -> Boolean,
        parseElement: () -> Unit,
        skipBadTokens: (expectedKind: IElementType, closeKind: IElementType) -> PostSkipAction,
        allowTrailingSeparator: Boolean,
        requireOneElement: Boolean,
        allowSemicolonAsSeparator: Boolean,
        /** Roslyn `immediatelyAbort(node)`: checked on the last parsed element before continuing the list. */
        immediatelyAbort: (() -> Boolean)? = null,
    ): Int {
        val separatorTokenKind = SyntaxKind.CommaToken
        var count = 0
        var requireOne = requireOneElement

        fun shouldParseSeparatorOrElement(): Boolean {
            if (currentKind === separatorTokenKind) return true
            if (allowSemicolonAsSeparator && currentKind === SyntaxKind.SemicolonToken) return true
            return isPossibleElement()
        }

        tryAgain@ while (true) {
            if (requireOne || currentKind !== closeTokenKind) {
                if (requireOne || shouldParseSeparatorOrElement()) {
                    parseElement()
                    count++
                    requireOne = false
                    val lastTokenPosition = intArrayOf(-1)
                    while (immediatelyAbort?.invoke() != true && isMakingProgress(lastTokenPosition)) {
                        if (currentKind === closeTokenKind) break
                        if (shouldParseSeparatorOrElement()) {
                            if (currentKind === SyntaxKind.SemicolonToken) eatTokenEvenWithIncorrectKind(separatorTokenKind)
                            else eatToken(separatorTokenKind)
                            if (allowTrailingSeparator) {
                                if (currentKind === closeTokenKind) break
                                else if (!isPossibleElement()) continue@tryAgain
                            }
                            parseElement()
                            count++
                            continue
                        }
                        if (skipBadTokens(separatorTokenKind, closeTokenKind) == PostSkipAction.Abort) break
                    }
                } else if (skipBadTokens(SyntaxKind.IdentifierToken, closeTokenKind) == PostSkipAction.Continue) {
                    continue@tryAgain
                }
            }
            return count
        }
    }

    /** `SkipBadSeparatedListTokensWithExpectedKind` → `SkipBadTokensWithExpectedKind` (LP 4523-4620). */
    fun skipBadSeparatedListTokensWithExpectedKind(
        isNotExpected: () -> Boolean,
        abort: (closeKind: IElementType) -> Boolean,
        expected: IElementType,
        closeKind: IElementType,
    ): PostSkipAction {
        var action = PostSkipAction.Continue
        var count = 0
        val m = builder.mark()
        var message = SKIPPED
        while (isNotExpected()) {
            if (abort(closeKind) || isTerminator()) {
                action = PostSkipAction.Abort
                break
            }
            if (count == 0 && !currentTokenHasLexerError()) message = expectedMessage(expected, currentKind, CSharpDiagnosticAnchor.FIRST_EXPECTED)
            advance()
            count++
        }
        if (count > 0) {
            // Roslyn: the first skipped token gets the "expected X" diagnostic (`EatTokenEvenWithIncorrectKind`) unless it has
            // one of the lexer, the others none; all of them become skipped trivia: one error element, one error.
            m.error(message)
            countSkippedError()
        } else {
            m.drop()
        }
        return action
    }

    /** `SkipBadTokensWithErrorCode` (LP 4648); [message] describes the diagnostic on the first token, anchored [CSharpDiagnosticAnchor.FIRST]. */
    fun skipBadTokensWithErrorCode(isNotExpected: () -> Boolean, abort: () -> Boolean, message: String): PostSkipAction {
        var action = PostSkipAction.Continue
        var count = 0
        val m = builder.mark()
        var described = SKIPPED
        while (isNotExpected()) {
            if (abort()) {
                action = PostSkipAction.Abort
                break
            }
            // `EatTokenWithPrejudice(errorCode)` on the first token unless it has a diagnostic of the lexer
            if (count == 0 && !currentTokenHasLexerError()) described = message
            advance()
            count++
        }
        if (count > 0) {
            m.error(described)
            countSkippedError()
        } else {
            m.drop()
        }
        return action
    }

    /**
     * `ConsumeUnexpectedTokens` (LP 14709): everything up to EOF becomes one error element; ERR_UnexpectedToken with the first
     * token's text is on the node before it.
     */
    fun consumeUnexpectedTokens() {
        if (currentToken.isEof) return
        val message = CSharpErrorCode.ERR_UnexpectedToken.describe(currentText, anchor = CSharpDiagnosticAnchor.PREVIOUS_SIBLING)
        val m = builder.mark()
        while (advance()) { /* eat */ }
        m.error(message)
        countSkippedError()
    }

    /** The whole text as one error element: Roslyn's result when the stack guard fires (`ERR_InsufficientStack`). */
    fun consumeAllAsStackOverflow() {
        if (currentToken.isEof) return
        val m = builder.mark()
        while (advance()) { /* eat */ }
        m.error(CSharpErrorCode.ERR_InsufficientStack.describe())
        countSkippedError()
    }

    companion object {
        /** The start lexemes of interpolated strings (our lexer splits Roslyn's `InterpolatedStringToken`). */
        private val INTERPOLATED_STRING_STARTS = com.intellij.psi.tree.TokenSet.create(
            SyntaxKind.InterpolatedStringStartToken, SyntaxKind.InterpolatedVerbatimStringStartToken,
            SyntaxKind.InterpolatedSingleLineRawStringStartToken, SyntaxKind.InterpolatedMultiLineRawStringStartToken,
        )

        /**
         * The default message of the error element of a missing [kind] (`createMissingToken(kind)`) and of the
         * expected-kind diagnostics of skipped tokens: Roslyn's `GetExpectedTokenError(kind, actual)` as a
         * [CSharpErrorCode.describe]. Text only: the PSI reads the kind from the gap's type.
         */
        fun expectedMessage(
            kind: IElementType, actual: IElementType? = SyntaxKind.EndOfFileToken, anchor: CSharpDiagnosticAnchor = CSharpDiagnosticAnchor.ELEMENT,
        ): String = CSharpErrorCode.expectedToken(kind, actual, anchor)

        /**
         * Roslyn guards only real stack exhaustion (`StackGuard.EnsureSufficientExecutionStack`, LP 11500) and then
         * gives up on the whole file (`ParseWithStackGuard`). The JVM cannot measure the remaining stack, so a weighted
         * depth stands in for it: `parseSubExpression`, `parseStatementCore`, `scanType` and `scanDesignation` add 1,
         * `parseLambdaExpression` and `parseCastOrParenExpressionOrTuple` 1 more, `parsePattern` 4 (their frames per level, measured by `DeepNestingTest.testReportStackHeadroom` on the
         * default 1 MB thread stack: lambdas overflow at ~500-750 levels, parenthesized patterns at ~250-500, parens at
         * ~500-750, blocks at ~1250-1500). Past the limit the operand becomes a missing name. [parseWithStackGuard]
         * is the second line: a real overflow degrades to Roslyn's behaviour (the whole text is skipped).
         * Departure from Roslyn: the limit is lower than a 1 MB .NET stack allows (about 450 `?:` terms against
         * roughly a thousand), see docs/csharp-psi/GRAMMAR.md.
         */
        const val MAX_DEPTH = 600

        /**
         * Roslyn `ParseWithStackGuard` (LP ~): runs [body] with [parser] under [root]; on a `StackOverflowError` (the
         * depth guard did not fire first: deeper call stack than measured) the tree is rolled back to [root] and the
         * whole text becomes one error element, as Roslyn's compilation unit of skipped tokens with
         * `ERR_InsufficientStack`. Returns the parser that produced the tree and whether it overflowed.
         */
        fun <P : SyntaxParser> parseWithStackGuard(builder: PsiBuilder, root: PsiBuilder.Marker, newParser: (PsiBuilder) -> P, body: (P) -> Unit): StackGuardResult<P> {
            val parser = newParser(builder)
            try {
                body(parser)
                return StackGuardResult(parser, root, false)
            } catch (e: StackOverflowError) {
                root.rollbackTo() // drops the root marker too
                val newRoot = builder.mark()
                val fresh = newParser(builder)
                fresh.consumeAllAsStackOverflow()
                return StackGuardResult(fresh, newRoot, true)
            }
        }

        /** The parser that built the tree, the root marker to complete (a new one after an overflow), and the verdict. */
        class StackGuardResult<P : SyntaxParser>(val parser: P, val root: PsiBuilder.Marker, val overflowed: Boolean)
    }
}
