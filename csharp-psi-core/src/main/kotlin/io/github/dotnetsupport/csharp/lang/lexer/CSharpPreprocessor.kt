// Ported from Roslyn src/Compilers/CSharp/Portable/Parser/DirectiveParser.cs, Directives.cs (DirectiveStack) and the
// directive mode of Lexer.cs (LexDirectiveAndExcludedTrivia, LexExcludedDirectivesAndTrivia, LexSingleDirective,
// LexDisabledText, ScanDirectiveToken, LexDirectiveTrailingTrivia, LexOptionalPreprocessingMessage) at roslynCommit
// 35d9211b841e7613c1d2f8f5af6d628ace696c4c.
// Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
package io.github.dotnetsupport.csharp.lang.lexer

import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpErrorCode

/** Receives the tokens of directives and excluded text, in text order, with the lexer state reported for each. */
fun interface DirectiveTokenSink {
    fun token(type: IElementType, start: Int, end: Int, state: Int)
}

/**
 * Roslyn's `DirectiveStack` (`Directives.cs`): the directives that decide what follows — `#define`/`#undef` and the
 * open `#if`/`#elif`/`#else`/`#region` — as an immutable list, top first. Other directives (`#pragma`, `#line`, bad
 * directives, ...) are not kept: Roslyn pushes them too, but no query of the stack looks at them.
 *
 * [hash] and [significant] serve the lexer state (`CSharpLexer`): a `#region` with no `#if`/`#elif`/`#else` below
 * it cannot change how the rest of the file is lexed (an `#elif`, `#else`, `#endif` or `#endregion` that meets it is a
 * bad directive with or without it), so it is left out of both and a file inside a plain `#region` still has
 * restartable (state 0) positions.
 */
class DirectiveStack private constructor(private val top: Entry?) {

    enum class Kind { If, Elif, Else, EndIf, Region, EndRegion, Define, Undef, Other }

    enum class DefineState { Defined, Undefined, Unspecified }

    private class Entry(
        @JvmField val kind: Kind,
        @JvmField val branchTaken: Boolean,
        @JvmField val name: String?,
        @JvmField val tail: Entry?,
    ) {
        /** An `#if`, `#elif` or `#else` at this entry or below. */
        @JvmField val hasBranching: Boolean = kind == Kind.If || kind == Kind.Elif || kind == Kind.Else || tail?.hasBranching == true
        private val ignored = kind == Kind.Region && tail?.hasBranching != true
        @JvmField val significant: Int = (tail?.significant ?: 0) + if (ignored) 0 else 1
        @JvmField val hash: Int = if (ignored) {
            tail?.hash ?: 0
        } else {
            var h = (tail?.hash ?: 0) * 31 + kind.ordinal + 1
            h = h * 31 + if (branchTaken) 1 else 0
            h * 31 + (name?.hashCode() ?: 0)
        }
    }

    /** Number of entries that matter for the rest of the file; 0 means "as at the start of a file". */
    val significant: Int get() = top?.significant ?: 0

    /** A hash of the entries that matter (see [significant]); 0 for none. */
    val hash: Int get() = top?.hash ?: 0

    /** `DirectiveStack.IsDefined` */
    fun isDefined(id: String): DefineState {
        var current = top
        while (current != null) {
            when (current.kind) {
                Kind.Define -> if (current.name == id) return DefineState.Defined
                Kind.Undef -> if (current.name == id) return DefineState.Undefined
                Kind.Elif, Kind.Else -> {
                    // Skip directives from previous branches of the same #if.
                    do {
                        current = current!!.tail
                        if (current == null) return DefineState.Unspecified
                    } while (current!!.kind != Kind.If)
                }
                else -> {}
            }
            current = current!!.tail
        }
        return DefineState.Unspecified
    }

    /** `DirectiveStack.PreviousBranchTaken`: a previous section of the closest `#if` has its branch taken. */
    fun previousBranchTaken(): Boolean {
        var current = top
        while (current != null) {
            if (current.branchTaken) return true
            if (current.kind == Kind.If) return false
            current = current.tail
        }
        return false
    }

    /** `DirectiveStack.HasUnfinishedIf` */
    fun hasUnfinishedIf(): Boolean = previousIfElifElseOrRegion()?.let { it.kind != Kind.Region } ?: false

    /** `DirectiveStack.HasPreviousIfOrElif` */
    fun hasPreviousIfOrElif(): Boolean = previousIfElifElseOrRegion()?.let { it.kind == Kind.If || it.kind == Kind.Elif } ?: false

    /** `DirectiveStack.HasUnfinishedRegion` */
    fun hasUnfinishedRegion(): Boolean = previousIfElifElseOrRegion()?.kind == Kind.Region

    private fun previousIfElifElseOrRegion(): Entry? {
        var current = top
        while (current != null) {
            when (current.kind) {
                Kind.If, Kind.Elif, Kind.Else, Kind.Region -> return current
                else -> current = current.tail
            }
        }
        return null
    }

    /** `DirectiveStack.Add` (via `DirectiveTriviaSyntax.ApplyDirectives`). */
    fun add(kind: Kind, branchTaken: Boolean, name: String?): DirectiveStack = when (kind) {
        Kind.EndIf -> if (hasIf()) DirectiveStack(completeIf()) else this
        Kind.EndRegion -> if (hasRegion()) DirectiveStack(completeRegion()) else this
        Kind.Other -> this
        else -> DirectiveStack(Entry(kind, branchTaken, name, top))
    }

    private fun hasIf(): Boolean {
        var current = top
        while (current != null) {
            if (current.kind == Kind.If) return true
            current = current.tail
        }
        return false
    }

    private fun hasRegion(): Boolean {
        var current = top
        while (current != null) {
            if (current.kind == Kind.Region) return true
            current = current.tail
        }
        return false
    }

    /**
     * `DirectiveStack.CompleteIf`: removes the closest `#if` with its `#elif`/`#else`, keeping the other directives of
     * the sections that were included (the taken branch). Iterative: the entries above the `#if` are rebuilt bottom up.
     */
    private fun completeIf(): Entry? {
        val above = ArrayList<Entry>()
        var current = top
        while (current!!.kind != Kind.If) {
            above += current
            current = current.tail
        }
        var include = current.branchTaken
        var rebuilt = current.tail
        for (i in above.indices.reversed()) {
            val e = above[i]
            when (e.kind) {
                Kind.Elif, Kind.Else -> include = e.branchTaken
                else -> if (include) rebuilt = Entry(e.kind, e.branchTaken, e.name, rebuilt)
            }
        }
        return rebuilt
    }

    /** `DirectiveStack.CompleteRegion`: removes the closest `#region`, keeping everything else. */
    private fun completeRegion(): Entry? {
        val above = ArrayList<Entry>()
        var current = top
        while (current!!.kind != Kind.Region) {
            above += current
            current = current.tail
        }
        var rebuilt = current.tail
        for (i in above.indices.reversed()) {
            val e = above[i]
            rebuilt = Entry(e.kind, e.branchTaken, e.name, rebuilt)
        }
        return rebuilt
    }

    companion object {
        @JvmField val EMPTY = DirectiveStack(null)
    }
}

/** States of the trivia scanner around a `#` (`Lexer.LexSyntaxTrivia`: `isTrailing`, `onlyWhitespaceOnLine`). */
object LineState {
    /** Leading trivia, only whitespace so far on the line: a `#` starts a directive. */
    const val CLEAN = 0

    /** Leading trivia after a comment on the line: a `#` is a misplaced directive. */
    const val DIRTY = 1

    /** [DIRTY] after a single-line doc comment, which in Roslyn takes the new line: the next new line keeps [DIRTY]. */
    const val DIRTY_DOC = 2

    /** Trailing trivia of a token (to the end of its line): a `#` is a misplaced directive. */
    const val TRAILING = 3

    /** The lexer state of a token start: 0 only where lexing may restart with an empty directive stack. */
    @JvmStatic
    fun state(stack: DirectiveStack, lineState: Int, queued: Boolean): Int {
        if (!queued && lineState == CLEAN && stack.significant == 0) return 0
        var h = stack.hash * 31 + lineState + 1
        h = h * 2 + if (queued) 1 else 0
        return if (h == 0) 1 else h
    }
}

/**
 * Directives and excluded text from a `#` at a line start: `Lexer.LexDirectiveAndExcludedTrivia` with Roslyn's
 * `DirectiveParser` deciding the token kinds and evaluating `#if`/`#elif`. Tokens go to [sink]; [stack] is updated
 * as `Lexer._directives` is. [pos] ends after the last directive or excluded text (after its new line).
 */
class CSharpPreprocessor(
    private val buffer: CharSequence,
    private val end: Int,
    private val symbols: Set<String>,
    @JvmField var stack: DirectiveStack,
    private val sink: DirectiveTokenSink,
) {
    @JvmField var pos: Int = 0

    /**
     * Where `DirectiveParser` diagnostics go (offsets absolute), null while lexing: the lexer has nowhere to keep them, so
     * `CSharpSyntaxDiagnostics` runs the preprocessor again over the directives of a parsed file to collect them.
     */
    @JvmField var diagnostics: MutableList<CSharpLexerDiagnostic>? = null

    /** The start of the first token of the file that is not trivia (`isFollowingToken` of `ParseDirective`). */
    @JvmField var firstTokenStart: Int = Int.MAX_VALUE

    private fun error(code: CSharpErrorCode, start: Int, end: Int, vararg args: Any) {
        diagnostics?.add(CSharpLexerDiagnostic(code, start, end - start, args.toList()))
    }

    /** `Lexer.LexDirectiveAndExcludedTrivia` from the `#` at [hashPosition] (its leading whitespace is already lexed). */
    fun lexDirectiveAndExcludedTrivia(hashPosition: Int) {
        pos = hashPosition
        val directive = lexSingleDirective(isActive = true, endIsActive = true)
        if (directive.isBranching && !directive.branchTaken) lexExcludedDirectivesAndTrivia(true)
    }

    /**
     * A directive that is not first on its line (`LexSyntaxTrivia`, case `#` with `isTrailing || !onlyWhitespaceOnLine`):
     * parsed for its extent only, without effects. Roslyn makes it one `BadToken` in `SkippedTokensTrivia`; here it
     * keeps its directive tokens (`docs/csharp-psi/GRAMMAR.md`).
     */
    fun lexMisplacedDirective(hashPosition: Int) {
        pos = hashPosition
        val saved = diagnostics
        diagnostics = null // Roslyn discards them for ERR_BadDirectivePlacement on the `#`
        DirectiveParser(LineState.state(stack, LineState.CLEAN, true)).parseDirective(isActive = false, endIsActive = false)
        diagnostics = saved
        error(CSharpErrorCode.ERR_BadDirectivePlacement, hashPosition, hashPosition + 1)
    }

    /** `Lexer.LexExcludedDirectivesAndTrivia` */
    private fun lexExcludedDirectivesAndTrivia(endIsActive: Boolean) {
        while (true) {
            val followedByDirective = lexDisabledText()
            if (!followedByDirective) break
            val directive = lexSingleDirective(isActive = false, endIsActive = endIsActive)
            if (directive.kind == DirectiveStack.Kind.EndIf || (directive.isBranching && directive.branchTaken)) break
            if (directive.kind == DirectiveStack.Kind.If) lexExcludedDirectivesAndTrivia(false)
        }
    }

    /** `Lexer.LexSingleDirective`: leading whitespace, the directive, `ApplyDirectives`. */
    private fun lexSingleDirective(isActive: Boolean, endIsActive: Boolean): Directive {
        val state = LineState.state(stack, LineState.CLEAN, true)
        if (pos < end && CSharpLiteralScanner.isWhitespace(buffer[pos])) {
            val start = pos
            while (pos < end && CSharpLiteralScanner.isWhitespace(buffer[pos])) pos++
            sink.token(SyntaxKind.WhitespaceTrivia, start, pos, state)
        }
        val directive = DirectiveParser(state).parseDirective(isActive, endIsActive)
        stack = stack.add(directive.kind, directive.branchTaken, directive.name)
        return directive
    }

    /**
     * `Lexer.LexDisabledText`: text up to the start of the line of the next directive (a `#` after only whitespace on
     * its line) or to the end. Returns whether a directive follows.
     */
    private fun lexDisabledText(): Boolean {
        val start = pos
        var lastLineStart = pos
        var allWhitespace = true
        var followedByDirective = false
        var i = pos
        while (i < end) {
            val ch = buffer[i]
            if (ch == '#' && allWhitespace) {
                followedByDirective = true
                i = lastLineStart
                break
            }
            if (CSharpLiteralScanner.isNewLine(ch)) {
                i += if (ch == '\r' && i + 1 < end && buffer[i + 1] == '\n') 2 else 1
                lastLineStart = i
                allWhitespace = true
                continue
            }
            allWhitespace = allWhitespace && CSharpLiteralScanner.isWhitespace(ch)
            i++
        }
        if (i > start) sink.token(SyntaxKind.DisabledTextTrivia, start, i, LineState.state(stack, LineState.CLEAN, true))
        pos = i
        return followedByDirective
    }

    /** A parsed directive: what `ApplyDirectives` and the excluded-text loop need from Roslyn's node. */
    class Directive(
        @JvmField val kind: DirectiveStack.Kind,
        @JvmField val branchTaken: Boolean = false,
        @JvmField val name: String? = null,
    ) {
        /** `BranchingDirectiveTriviaSyntax`: `#if`, `#elif`, `#else`. */
        val isBranching: Boolean
            get() = kind == DirectiveStack.Kind.If || kind == DirectiveStack.Kind.Elif || kind == DirectiveStack.Kind.Else
    }

    /** A directive token: [kind] is a Roslyn kind (null for `EndOfDirectiveToken`); [index] in [DirectiveParser.out]. */
    private class Tok(
        @JvmField val kind: IElementType?,
        @JvmField val contextualKind: IElementType?,
        @JvmField val start: Int,
        @JvmField val end: Int,
        @JvmField val index: Int,
        @JvmField val hasTrailingTrivia: Boolean,
    )

    /**
     * Roslyn's `DirectiveParser` over the lexer's directive mode (`LexDirectiveToken`). Tokens are lexed lazily, one
     * current token as in `SyntaxParser` without pre-lexing, because `#region`, `#error`, `#!` and `#:` take the rest of
     * the line as a message right after their keyword. Token kinds are those of the parsed tree: preprocessor keywords
     * are identifiers to the lexer and become keywords when the parser eats them as such (`EatContextualToken`).
     * The tokens of one directive are buffered and sent to the sink at the end ([state] for all of them).
     */
    private inner class DirectiveParser(private val state: Int) {
        private val outType = ArrayList<IElementType>()
        private val outStart = ArrayList<Int>()
        private val outEnd = ArrayList<Int>()
        private var current: Tok? = null

        private fun emit(type: IElementType, start: Int, end: Int): Int {
            outType += type
            outStart += start
            outEnd += end
            return outType.size - 1
        }

        private fun flush() {
            for (i in outType.indices) sink.token(outType[i], outStart[i], outEnd[i], state)
        }

        private fun currentToken(): Tok = current ?: lexDirectiveToken().also { current = it }

        private fun eatToken(): Tok = currentToken().also { current = null }

        /** `SyntaxParser.EatContextualToken` + `ConvertToKeyword`: the identifier becomes its contextual keyword. */
        private fun eatContextualToken(): Tok {
            val tok = eatToken()
            if (tok.index >= 0 && tok.contextualKind != null) outType[tok.index] = CSharpTokenTypes.directive(tok.contextualKind)
            return tok
        }

        /** `Token.ContextualKind`: the preprocessor keyword of an identifier, the kind of any other token. */
        private fun contextualKind(tok: Tok): IElementType = tok.contextualKind ?: tok.kind ?: SyntaxKind.EndOfDirectiveToken

        /** `DirectiveParser.ParseDirective` */
        fun parseDirective(isActive: Boolean, endIsActive: Boolean): Directive {
            val hash = eatToken()
            val result = when (contextualKind(currentToken())) {
                SyntaxKind.IfKeyword -> {
                    eatContextualToken()
                    parseIfDirective(isActive)
                }
                SyntaxKind.ElifKeyword -> {
                    eatContextualToken()
                    parseElifDirective(endIsActive, hash)
                }
                SyntaxKind.ElseKeyword -> {
                    eatContextualToken()
                    parseElseDirective(endIsActive, hash)
                }
                SyntaxKind.EndIfKeyword -> {
                    eatContextualToken()
                    parseEndOfDirective(ignoreErrors = false)
                    when {
                        stack.hasUnfinishedIf() -> Directive(DirectiveStack.Kind.EndIf)
                        stack.hasUnfinishedRegion() -> bad(hash, CSharpErrorCode.ERR_EndRegionDirectiveExpected)
                        else -> bad(hash, CSharpErrorCode.ERR_UnexpectedDirective)
                    }
                }
                SyntaxKind.RegionKeyword -> {
                    eatContextualToken()
                    parseEndOfDirectiveWithOptionalPreprocessingMessage()
                    Directive(DirectiveStack.Kind.Region)
                }
                SyntaxKind.EndRegionKeyword -> {
                    eatContextualToken()
                    parseEndOfDirectiveWithOptionalPreprocessingMessage()
                    when {
                        stack.hasUnfinishedRegion() -> Directive(DirectiveStack.Kind.EndRegion)
                        stack.hasUnfinishedIf() -> bad(hash, CSharpErrorCode.ERR_EndifDirectiveExpected)
                        else -> bad(hash, CSharpErrorCode.ERR_UnexpectedDirective)
                    }
                }
                SyntaxKind.DefineKeyword, SyntaxKind.UndefKeyword -> {
                    val keyword = eatContextualToken()
                    if (firstTokenStart < hash.start) error(CSharpErrorCode.ERR_PPDefFollowsToken, keyword.start, keyword.end)
                    parseDefineOrUndefDirective(keyword.contextualKind === SyntaxKind.DefineKeyword)
                }
                SyntaxKind.ErrorKeyword, SyntaxKind.WarningKeyword -> {
                    val keyword = eatContextualToken()
                    // ParseErrorOrWarningDirective: the message from the first trivia after the keyword that is not whitespace
                    val first = outType.size
                    parseEndOfDirectiveWithOptionalPreprocessingMessage()
                    if (isActive) {
                        var start = -1
                        var end = -1
                        for (i in first until outType.size) {
                            val type = outType[i]
                            if (type === SyntaxKind.EndOfLineTrivia || (start < 0 && type === SyntaxKind.WhitespaceTrivia)) continue
                            if (start < 0) start = outStart[i]
                            end = outEnd[i]
                        }
                        if (start < 0) {
                            start = pos
                            while (start > keyword.end && CSharpLiteralScanner.isNewLine(buffer[start - 1])) start--
                            end = start
                        }
                        val code = if (keyword.contextualKind === SyntaxKind.ErrorKeyword) CSharpErrorCode.ERR_ErrorDirective else CSharpErrorCode.WRN_WarningDirective
                        error(code, start, end, buffer.subSequence(start, end).toString())
                    }
                    OTHER
                }
                SyntaxKind.PragmaKeyword -> {
                    eatContextualToken()
                    parsePragmaDirective(isActive)
                    OTHER
                }
                SyntaxKind.NullableKeyword -> {
                    eatContextualToken()
                    parseNullableDirective(isActive)
                    OTHER
                }
                SyntaxKind.LineKeyword, SyntaxKind.ReferenceKeyword, SyntaxKind.LoadKeyword -> {
                    // ParseLineDirective, ParseLineSpanDirective, ParseReferenceDirective, ParseLoadDirective:
                    // EatToken(kind) never changes a kind, so the tokens are as lexed. Their diagnostics are not reported.
                    eatContextualToken()
                    parseEndOfDirective()
                    OTHER
                }
                SyntaxKind.ExclamationToken -> {
                    // ParseShebangDirective
                    if (hash.start != 0 || hash.hasTrailingTrivia) error(CSharpErrorCode.ERR_PPShebangNotOnFirstLine, hash.start, hash.end)
                    val exclamation = eatToken()
                    error(CSharpErrorCode.ERR_PPShebangInProjectBasedProgram, exclamation.start, exclamation.end)
                    parseEndOfDirectiveWithOptionalPreprocessingMessage()
                    OTHER
                }
                else -> {
                    if (contextualKind(currentToken()) === SyntaxKind.ColonToken && !hash.hasTrailingTrivia) {
                        // ParseIgnoredDirective: the rest of the line is one string literal (LexEndOfDirectiveWithOptionalContent).
                        val colon = eatToken()
                        if (isActive) error(CSharpErrorCode.ERR_PPIgnoredNeedsFileBasedProgram, colon.start, colon.end)
                        lexOptionalPreprocessingMessage(CSharpTokenTypes.directive(SyntaxKind.StringLiteralToken))
                        lexDirectiveTrailingTrivia(includeEndOfLine = true)
                    } else {
                        // BadDirectiveTrivia: EatToken(IdentifierToken) and ParseEndOfDirective keep the lexed kinds;
                        // ERR_PPDirectiveExpected on the identifier, or on the `#` when there is none.
                        val id = currentToken()
                        if (id.kind === SyntaxKind.IdentifierToken) error(CSharpErrorCode.ERR_PPDirectiveExpected, id.start, id.end)
                        else error(CSharpErrorCode.ERR_PPDirectiveExpected, hash.start, hash.end)
                        parseEndOfDirective()
                    }
                    BAD
                }
            }
            flush()
            return result
        }

        /** `AddError(BadDirectiveTrivia(...), code)`: on the directive from `#` to its last token or message. */
        private fun bad(hash: Tok, code: CSharpErrorCode): Directive {
            var end = hash.end
            for (i in outType.indices) {
                val type = outType[i]
                if (type !== SyntaxKind.WhitespaceTrivia && type !== SyntaxKind.EndOfLineTrivia && type !== SyntaxKind.SingleLineCommentTrivia) {
                    end = maxOf(end, outEnd[i])
                }
            }
            error(code, hash.start, end)
            return BAD
        }

        /** `SyntaxParser.EatToken(kind, code)` of a token that is not there: the error is on the current token. */
        private fun missing(code: CSharpErrorCode) {
            val current = currentToken()
            error(code, current.start, current.end)
        }

        /** `DirectiveParser.ParsePragmaDirective`: `warning` is eaten as a contextual keyword; the rest keeps its lexed kinds. */
        private fun parsePragmaDirective(isActive: Boolean) {
            if (contextualKind(currentToken()) === SyntaxKind.WarningKeyword) {
                eatContextualToken()
                val style = currentToken().kind
                if (style === SyntaxKind.DisableKeyword || style === SyntaxKind.RestoreKeyword) {
                    eatToken()
                    var hasError = false
                    while (currentToken().kind != null) {
                        val kind = currentToken().kind
                        if (kind === SyntaxKind.NumericLiteralToken || kind === SyntaxKind.IdentifierToken) {
                            eatToken()
                        } else {
                            if (isActive) missing(CSharpErrorCode.WRN_IdentifierOrNumericLiteralExpected)
                            hasError = hasError || isActive
                        }
                        if (currentToken().kind !== SyntaxKind.CommaToken) break
                        eatToken()
                    }
                    parseEndOfDirective(ignoreErrors = hasError || !isActive, code = CSharpErrorCode.WRN_EndOfPPLineExpected)
                } else {
                    if (isActive) missing(CSharpErrorCode.WRN_IllegalPPWarning)
                    parseEndOfDirective()
                }
            } else if (currentToken().kind === SyntaxKind.ChecksumKeyword) {
                parseEndOfDirective() // ParsePragmaChecksum: its diagnostics are not reported
            } else {
                if (isActive) missing(CSharpErrorCode.WRN_IllegalPragma)
                parseEndOfDirective()
            }
        }

        /** `DirectiveParser.ParseNullableDirective` */
        private fun parseNullableDirective(isActive: Boolean) {
            val setting = currentToken().kind
            val settingMissing = setting !== SyntaxKind.EnableKeyword && setting !== SyntaxKind.DisableKeyword && setting !== SyntaxKind.RestoreKeyword
            if (settingMissing) {
                if (isActive) missing(CSharpErrorCode.ERR_NullableDirectiveQualifierExpected)
            } else {
                eatToken()
            }
            val target = currentToken().kind
            var targetMissing = false
            if (target === SyntaxKind.WarningsKeyword || target === SyntaxKind.AnnotationsKeyword) {
                eatToken()
            } else if (target != null) {
                targetMissing = true
                if (!settingMissing && isActive) missing(CSharpErrorCode.ERR_NullableDirectiveTargetExpected)
            }
            parseEndOfDirective(ignoreErrors = settingMissing || targetMissing || !isActive)
        }

        /** `DirectiveParser.ParseIfDirective` */
        private fun parseIfDirective(isActive: Boolean): Directive {
            val isTrue = parseExpression()
            parseEndOfDirective(ignoreErrors = false)
            return Directive(DirectiveStack.Kind.If, branchTaken = isActive && isTrue)
        }

        /**
         * `DirectiveParser.ParseElifDirective`. Without a previous `#if`/`#elif` the directive is bad and the expression
         * becomes `DisabledTextTrivia` (`expr.ToFullString()`: through the trivia after its last token).
         */
        private fun parseElifDirective(endIsActive: Boolean, hash: Tok): Directive {
            val expressionStart = currentToken().start
            val isTrue = parseExpression()
            val expressionEnd = currentToken().start
            parseEndOfDirective(ignoreErrors = false)
            if (stack.hasPreviousIfOrElif()) {
                val branchTaken = endIsActive && isTrue && !stack.previousBranchTaken()
                return Directive(DirectiveStack.Kind.Elif, branchTaken = branchTaken)
            }
            if (expressionEnd > expressionStart) {
                var first = 0
                while (first < outType.size && outStart[first] < expressionStart) first++
                var last = first
                while (last < outType.size && outEnd[last] <= expressionEnd) last++
                outType.subList(first, last).clear()
                outStart.subList(first, last).clear()
                outEnd.subList(first, last).clear()
                outType.add(first, SyntaxKind.DisabledTextTrivia)
                outStart.add(first, expressionStart)
                outEnd.add(first, expressionEnd)
            }
            return badBranch(hash)
        }

        /** A `#elif` / `#else` without `#if`: the code by what is open. */
        private fun badBranch(hash: Tok): Directive = when {
            stack.hasUnfinishedRegion() -> bad(hash, CSharpErrorCode.ERR_EndRegionDirectiveExpected)
            stack.hasUnfinishedIf() -> bad(hash, CSharpErrorCode.ERR_EndifDirectiveExpected)
            else -> bad(hash, CSharpErrorCode.ERR_UnexpectedDirective)
        }

        /** `DirectiveParser.ParseElseDirective` */
        private fun parseElseDirective(endIsActive: Boolean, hash: Tok): Directive {
            parseEndOfDirective(ignoreErrors = false)
            if (stack.hasPreviousIfOrElif()) {
                return Directive(DirectiveStack.Kind.Else, branchTaken = endIsActive && !stack.previousBranchTaken())
            }
            return badBranch(hash)
        }

        /** `DirectiveParser.ParseDefineOrUndefDirective`; a missing name is `""` (its `ValueText`). */
        private fun parseDefineOrUndefDirective(define: Boolean): Directive {
            val nameMissing = currentToken().kind !== SyntaxKind.IdentifierToken
            if (nameMissing) missing(CSharpErrorCode.ERR_IdentifierExpected)
            val name = if (!nameMissing) identifierValue(eatToken()) else ""
            parseEndOfDirective(ignoreErrors = nameMissing)
            return Directive(if (define) DirectiveStack.Kind.Define else DirectiveStack.Kind.Undef, name = name)
        }

        /**
         * `DirectiveParser.ParseEndOfDirective`: extraneous tokens are skipped (they keep their kinds), then the end; [code]
         * on the first of them unless [ignoreErrors].
         */
        private fun parseEndOfDirective(ignoreErrors: Boolean = true, code: CSharpErrorCode = CSharpErrorCode.ERR_EndOfPPLineExpected) {
            if (!ignoreErrors && currentToken().kind != null) missing(code)
            while (currentToken().kind != null) eatToken()
            eatToken()
        }

        /** `Lexer.LexEndOfDirectiveWithOptionalPreprocessingMessage` */
        private fun parseEndOfDirectiveWithOptionalPreprocessingMessage() {
            lexOptionalPreprocessingMessage(SyntaxKind.PreprocessingMessageTrivia)
            lexDirectiveTrailingTrivia(includeEndOfLine = true)
        }

        // ---- expressions: ParseExpression .. ParsePrimary, evaluated as Roslyn's Evaluate does ----

        /** `DirectiveParser.ParseExpression` (= `ParseLogicalOr`) with `EvaluateBool`. */
        private fun parseExpression(): Boolean {
            var left = parseLogicalAnd()
            while (currentToken().kind === SyntaxKind.BarBarToken) {
                eatToken()
                val right = parseLogicalAnd()
                left = left || right
            }
            return left
        }

        /** `DirectiveParser.ParseLogicalAnd` */
        private fun parseLogicalAnd(): Boolean {
            var left = parseEquality()
            while (currentToken().kind === SyntaxKind.AmpersandAmpersandToken) {
                eatToken()
                val right = parseEquality()
                left = left && right
            }
            return left
        }

        /** `DirectiveParser.ParseEquality`: the right operand is itself an equality (right-associative). */
        private fun parseEquality(): Boolean {
            var left = parseLogicalNot()
            while (true) {
                val op = currentToken().kind
                if (op !== SyntaxKind.EqualsEqualsToken && op !== SyntaxKind.ExclamationEqualsToken) break
                eatToken()
                val right = parseEquality()
                left = if (op === SyntaxKind.EqualsEqualsToken) left == right else left != right
            }
            return left
        }

        /** `DirectiveParser.ParseLogicalNot` */
        private fun parseLogicalNot(): Boolean {
            if (currentToken().kind === SyntaxKind.ExclamationToken) {
                eatToken()
                return !parseLogicalNot()
            }
            return parsePrimary()
        }

        /** `DirectiveParser.ParsePrimary` */
        private fun parsePrimary(): Boolean = when (currentToken().kind) {
            SyntaxKind.OpenParenToken -> {
                eatToken()
                val value = parseExpression()
                if (currentToken().kind === SyntaxKind.CloseParenToken) eatToken() else missing(CSharpErrorCode.ERR_CloseParenExpected)
                value
            }
            SyntaxKind.IdentifierToken -> evaluateIdentifier(identifierValue(eatToken()))
            SyntaxKind.TrueKeyword -> {
                eatToken()
                true
            }
            SyntaxKind.FalseKeyword -> {
                eatToken()
                false
            }
            // A missing identifier (ERR_InvalidPreprocExpr): its ValueText is "".
            else -> {
                missing(CSharpErrorCode.ERR_InvalidPreprocExpr)
                evaluateIdentifier("")
            }
        }

        /** `DirectiveParser.Evaluate`, case `IdentifierName`: `bool.TryParse` (case-insensitive), then `IsDefined`. */
        private fun evaluateIdentifier(id: String): Boolean {
            if (id.equals("true", ignoreCase = true)) return true
            if (id.equals("false", ignoreCase = true)) return false
            return when (stack.isDefined(id)) {
                DirectiveStack.DefineState.Defined -> true
                DirectiveStack.DefineState.Undefined -> false
                DirectiveStack.DefineState.Unspecified -> id in symbols
            }
        }

        // ---- the lexer in directive mode ----

        /** `Lexer.LexDirectiveToken`: a token and its trailing trivia (the new line only after `EndOfDirectiveToken`). */
        private fun lexDirectiveToken(): Tok {
            val start = pos
            val scanned = scanDirectiveToken()
            val tokenEnd = pos
            val index = if (scanned.kind != null) emit(CSharpTokenTypes.directive(scanned.kind), start, tokenEnd) else -1
            val triviaStart = pos
            lexDirectiveTrailingTrivia(includeEndOfLine = scanned.kind == null)
            return Tok(scanned.kind, scanned.contextualKind, start, tokenEnd, index, pos > triviaStart)
        }

        private inner class Scanned(@JvmField val kind: IElementType?, @JvmField val contextualKind: IElementType? = null)

        /** `Lexer.ScanDirectiveToken`; `EndOfDirectiveToken` (kind null) is zero-width before the new line or the end. */
        private fun scanDirectiveToken(): Scanned {
            if (pos >= end) return Scanned(null)
            val ch = buffer[pos]
            fun single(kind: IElementType): Scanned {
                pos++
                return Scanned(kind)
            }
            fun pair(next: Char, two: IElementType, one: IElementType?): Scanned {
                if (pos + 1 < end && buffer[pos + 1] == next) {
                    pos += 2
                    return Scanned(two)
                }
                if (one == null) return single(SyntaxKind.BadToken)
                return single(one)
            }
            return when (ch) {
                '#' -> single(SyntaxKind.HashToken)
                '(' -> single(SyntaxKind.OpenParenToken)
                ')' -> single(SyntaxKind.CloseParenToken)
                ',' -> single(SyntaxKind.CommaToken)
                '-' -> single(SyntaxKind.MinusToken)
                ':' -> single(SyntaxKind.ColonToken)
                '!' -> pair('=', SyntaxKind.ExclamationEqualsToken, SyntaxKind.ExclamationToken)
                '=' -> pair('=', SyntaxKind.EqualsEqualsToken, SyntaxKind.EqualsToken)
                '&' -> pair('&', SyntaxKind.AmpersandAmpersandToken, null)
                '|' -> pair('|', SyntaxKind.BarBarToken, null)
                in '0'..'9' -> {
                    // ScanInteger: decimal digits only.
                    while (pos < end && buffer[pos] in '0'..'9') pos++
                    Scanned(SyntaxKind.NumericLiteralToken)
                }
                '"' -> {
                    val scanner = CSharpLiteralScanner(buffer, end, pos)
                    scanner.scanDirectiveStringLiteral()
                    pos = scanner.pos
                    Scanned(SyntaxKind.StringLiteralToken)
                }
                '\\' -> {
                    val next = if (pos + 1 < end) buffer[pos + 1] else CSharpLiteralScanner.INVALID
                    if (next == 'u' || next == 'U') {
                        val scanner = CSharpLiteralScanner(buffer, end, pos)
                        val escaped = scanner.scanUnicodeEscape().toChar()
                        if (CSharpLiteralScanner.isIdentifierStartCharacter(escaped)) {
                            scanIdentifierOrKeyword() ?: run {
                                pos = scanner.pos
                                Scanned(SyntaxKind.BadToken)
                            }
                        } else {
                            pos = scanner.pos
                            Scanned(SyntaxKind.BadToken)
                        }
                    } else {
                        single(SyntaxKind.BadToken)
                    }
                }
                else -> when {
                    CSharpLiteralScanner.isNewLine(ch) -> Scanned(null)
                    CSharpLiteralScanner.isIdentifierStartCharacter(ch) -> scanIdentifierOrKeyword() ?: single(SyntaxKind.BadToken)
                    // An unknown single character (one UTF-16 unit): Roslyn's BadToken.
                    else -> single(SyntaxKind.BadToken)
                }
            }
        }

        /**
         * `Lexer.ScanIdentifierOrKeyword` in directive mode: `GetPreprocessorKeywordKind`; contextual preprocessor
         * keywords stay identifiers with a contextual kind, the others (`true`, `default`, `disable`, ...) are keywords.
         * Escaped identifiers are never keywords.
         */
        private fun scanIdentifierOrKeyword(): Scanned? {
            val start = pos
            val scanner = CSharpLiteralScanner(buffer, end, pos)
            if (!scanner.scanIdentifier()) return null
            pos = scanner.pos
            if (scanner.identifierIsVerbatimOrEscaped) return Scanned(SyntaxKind.IdentifierToken)
            val keyword = PREPROCESSOR_KEYWORDS[buffer.subSequence(start, pos).toString()] ?: return Scanned(SyntaxKind.IdentifierToken)
            return if (keyword in NON_CONTEXTUAL) Scanned(keyword) else Scanned(SyntaxKind.IdentifierToken, keyword)
        }

        /** `Lexer.LexDirectiveTrailingTrivia` with `LexDirectiveTrivia`: whitespace, `//` comments, the new line. */
        fun lexDirectiveTrailingTrivia(includeEndOfLine: Boolean) {
            while (pos < end) {
                val ch = buffer[pos]
                val start = pos
                when {
                    ch == '/' && pos + 1 < end && buffer[pos + 1] == '/' -> {
                        while (pos < end && !CSharpLiteralScanner.isNewLine(buffer[pos])) pos++
                        emit(SyntaxKind.SingleLineCommentTrivia, start, pos)
                    }
                    CSharpLiteralScanner.isNewLine(ch) -> {
                        if (includeEndOfLine) {
                            pos += if (ch == '\r' && pos + 1 < end && buffer[pos + 1] == '\n') 2 else 1
                            emit(SyntaxKind.EndOfLineTrivia, start, pos)
                        }
                        return
                    }
                    CSharpLiteralScanner.isWhitespace(ch) -> {
                        while (pos < end && CSharpLiteralScanner.isWhitespace(buffer[pos])) pos++
                        emit(SyntaxKind.WhitespaceTrivia, start, pos)
                    }
                    else -> return
                }
            }
        }

        /** `Lexer.LexOptionalPreprocessingMessage`: the rest of the line, as one token of [type] when not empty. */
        fun lexOptionalPreprocessingMessage(type: IElementType) {
            val start = pos
            while (pos < end && !CSharpLiteralScanner.isNewLine(buffer[pos])) pos++
            if (pos > start) emit(type, start, pos)
        }
    }

    /**
     * `ValueText` of an identifier token (escapes decoded, formatting characters dropped), truncated as
     * `DirectiveParser.TruncateIdentifier` does: a token wider than 128 characters keeps the first 128 of its text.
     */
    private fun identifierValue(tok: Tok): String {
        if (tok.end - tok.start > MAX_DIRECTIVE_IDENTIFIER_WIDTH) {
            return buffer.subSequence(tok.start, tok.start + MAX_DIRECTIVE_IDENTIFIER_WIDTH).toString()
        }
        val value = StringBuilder()
        var i = tok.start
        while (i < tok.end) {
            var ch = buffer[i]
            if (ch == '\\') {
                val scanner = CSharpLiteralScanner(buffer, tok.end, i)
                ch = scanner.scanUnicodeEscape().toChar()
                i = scanner.pos
            } else {
                i++
            }
            if (value.isNotEmpty() && Character.getType(ch) == Character.FORMAT.toInt()) continue
            value.append(ch)
        }
        return value.toString()
    }

    private companion object {
        const val MAX_DIRECTIVE_IDENTIFIER_WIDTH = 128

        val BAD = Directive(DirectiveStack.Kind.Other)
        val OTHER = Directive(DirectiveStack.Kind.Other)

        /** `SyntaxFacts.GetPreprocessorKeywordKind` */
        val PREPROCESSOR_KEYWORDS: Map<String, IElementType> = hashMapOf(
            "true" to SyntaxKind.TrueKeyword,
            "false" to SyntaxKind.FalseKeyword,
            "default" to SyntaxKind.DefaultKeyword,
            "if" to SyntaxKind.IfKeyword,
            "else" to SyntaxKind.ElseKeyword,
            "elif" to SyntaxKind.ElifKeyword,
            "endif" to SyntaxKind.EndIfKeyword,
            "region" to SyntaxKind.RegionKeyword,
            "endregion" to SyntaxKind.EndRegionKeyword,
            "define" to SyntaxKind.DefineKeyword,
            "undef" to SyntaxKind.UndefKeyword,
            "warning" to SyntaxKind.WarningKeyword,
            "error" to SyntaxKind.ErrorKeyword,
            "line" to SyntaxKind.LineKeyword,
            "pragma" to SyntaxKind.PragmaKeyword,
            "hidden" to SyntaxKind.HiddenKeyword,
            "checksum" to SyntaxKind.ChecksumKeyword,
            "disable" to SyntaxKind.DisableKeyword,
            "restore" to SyntaxKind.RestoreKeyword,
            "r" to SyntaxKind.ReferenceKeyword,
            "load" to SyntaxKind.LoadKeyword,
            "nullable" to SyntaxKind.NullableKeyword,
            "enable" to SyntaxKind.EnableKeyword,
            "warnings" to SyntaxKind.WarningsKeyword,
            "annotations" to SyntaxKind.AnnotationsKeyword,
        )

        /** Preprocessor keywords that are not `SyntaxFacts.IsPreprocessorContextualKeyword`: always keywords. */
        val NON_CONTEXTUAL: Set<IElementType> = setOf(
            SyntaxKind.TrueKeyword, SyntaxKind.FalseKeyword, SyntaxKind.DefaultKeyword, SyntaxKind.HiddenKeyword,
            SyntaxKind.ChecksumKeyword, SyntaxKind.DisableKeyword, SyntaxKind.RestoreKeyword, SyntaxKind.EnableKeyword,
            SyntaxKind.WarningsKeyword, SyntaxKind.AnnotationsKeyword,
        )
    }
}
