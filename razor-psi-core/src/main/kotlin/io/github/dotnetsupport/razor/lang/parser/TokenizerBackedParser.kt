package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.razor.lang.syntax.AcceptedCharacters
import io.github.dotnetsupport.razor.lang.syntax.ChunkGenerator
import io.github.dotnetsupport.razor.lang.syntax.MarkupEphemeralTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorCommentBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticFactory
import io.github.dotnetsupport.razor.lang.syntax.RazorList
import io.github.dotnetsupport.razor.lang.syntax.RazorMetaCodeSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind
import io.github.dotnetsupport.razor.lang.syntax.RazorToken
import io.github.dotnetsupport.razor.lang.syntax.SourceLocation
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import io.github.dotnetsupport.razor.lang.syntax.SpanEditHandler
import io.github.dotnetsupport.razor.lang.syntax.SpanEditHandlerBuilder

/** `SpanContextConfigAction`: configures the chunk generator and the edit handler of the spans output next. */
typealias SpanContextConfig = (editHandlerBuilder: SpanEditHandlerBuilder?) -> ChunkGenerator?

/**
 * `TokenizerBackedParser<TTokenizer>`: what the markup and the code parser share — the token stream with lookahead and put-back, the
 * token builder of the span being gathered, the span context (chunk generator, edit handler, accepted characters), the Razor comment.
 */
abstract class TokenizerBackedParser<T : Tokenizer>(val language: LanguageCharacteristics<T>, val context: ParserContext) {
    protected val tokenizer: TokenizerView<T> = TokenizerView(language.createTokenizer(context.source))
    protected val tokenBuilder = ArrayList<RazorToken>()

    protected var editHandlerBuilder: SpanEditHandlerBuilder? = if (context.options.enableSpanEditHandlers) SpanEditHandlerBuilder() else null
    protected var chunkGenerator: ChunkGenerator? = null

    protected var spanContextConfig: SpanContextConfig? = null

    val currentToken: RazorToken? get() = tokenizer.current
    var previousToken: RazorToken? = null
        private set

    val currentStart: SourceLocation get() = tokenizer.tokenizer.currentStart
    val endOfFile: Boolean get() = tokenizer.endOfFile

    /** The current token, which the caller has checked exists (`CurrentToken` where the compiler does not null-check). */
    protected val current: RazorToken get() = tokenizer.current ?: throw IllegalStateException("no current token at ${context.source.position}")

    protected fun lookahead(count: Int): RazorToken? {
        require(count >= 0)
        if (count == 0) return currentToken
        val tokens = ArrayList<RazorToken?>(count + 1)
        tokens.add(currentToken)
        for (i in 1..count) {
            nextToken()
            tokens.add(currentToken)
        }
        for (i in count downTo 0) putBack(tokens[i])
        ensureCurrent()
        return tokens[count]
    }

    /** `LookaheadUntil`: reads ahead until [condition] holds for a token (given the tokens before it), then puts everything back. */
    protected fun lookaheadUntil(condition: (token: RazorToken, previous: List<RazorToken?>) -> Boolean): Boolean {
        var matchFound = false
        val tokens = ArrayList<RazorToken?>()
        tokens.add(currentToken)
        while (true) {
            if (!nextToken()) break
            tokens.add(currentToken)
            if (condition(current, tokens)) {
                matchFound = true
                break
            }
        }
        for (i in tokens.indices.reversed()) putBack(tokens[i])
        ensureCurrent()
        return matchFound
    }

    fun nextToken(): Boolean {
        previousToken = currentToken
        return tokenizer.next()
    }

    protected fun putBack(token: RazorToken?) {
        if (token != null) tokenizer.putBack(token)
    }

    protected fun putBack(tokens: List<RazorToken?>) {
        for (i in tokens.indices.reversed()) putBack(tokens[i])
    }

    protected fun putCurrentBack() {
        if (!endOfFile && currentToken != null) putBack(currentToken)
    }

    protected fun nextIs(type: RazorSyntaxKind): Boolean {
        val cur = currentToken
        var result = false
        if (nextToken()) {
            result = type == currentToken?.kind
            putCurrentBack()
        }
        putBack(cur)
        ensureCurrent()
        return result
    }

    protected fun nextIs(vararg types: RazorSyntaxKind): Boolean = nextIs { token -> types.any { it == token.kind } }

    protected fun nextIs(condition: (RazorToken) -> Boolean): Boolean {
        val cur = currentToken
        var result = false
        if (nextToken()) {
            result = condition(current)
            putCurrentBack()
        }
        putBack(cur)
        ensureCurrent()
        return result
    }

    protected fun was(type: RazorSyntaxKind): Boolean = previousToken?.kind == type

    protected fun at(type: RazorSyntaxKind): Boolean = !endOfFile && currentToken?.kind == type

    protected fun tokenExistsAfterWhitespace(kind: RazorSyntaxKind, includeNewLines: Boolean = true): Boolean {
        val whitespace = ArrayList<RazorToken>()
        readWhile({ token -> token.kind == RazorSyntaxKind.Whitespace || (includeNewLines && token.kind == RazorSyntaxKind.NewLine) }, whitespace)
        val tokenFound = at(kind)
        putCurrentBack()
        putBack(whitespace)
        ensureCurrent()
        return tokenFound
    }

    protected fun ensureCurrent(): Boolean = if (currentToken == null) nextToken() else true

    protected fun readWhile(predicate: (RazorToken) -> Boolean, result: MutableList<RazorToken>) {
        if (!ensureCurrent() || !predicate(current)) return
        do {
            result.add(current)
            nextToken()
        } while (ensureCurrent() && predicate(current))
    }

    protected fun readWhile(predicate: (RazorToken) -> Boolean): ArrayList<RazorToken> = ArrayList<RazorToken>().also { readWhile(predicate, it) }

    protected fun skipWhile(predicate: (RazorToken) -> Boolean) {
        if (!ensureCurrent() || !predicate(current)) return
        do {
            nextToken()
        } while (ensureCurrent() && predicate(current))
    }

    protected fun atIdentifier(allowKeywords: Boolean): Boolean =
        currentToken != null && (language.isIdentifier(currentToken) || (allowKeywords && language.isKeyword(currentToken)))

    protected fun parseRazorComment(): RazorCommentBlockSyntax {
        check(language.knowsTokenType(KnownTokenType.CommentStart) && language.knowsTokenType(KnownTokenType.CommentStar) && language.knowsTokenType(KnownTokenType.CommentBody))
        val commentBlock: RazorCommentBlockSyntax
        pushSpanContextConfig(::commentSpanContextConfig).use {
            ensureCurrent()
            val start = currentStart
            val startTransition = eatExpectedToken(RazorSyntaxKind.RazorCommentTransition)
            val startStar = eatExpectedToken(RazorSyntaxKind.RazorCommentStar)
            val comment = getOptionalToken(RazorSyntaxKind.RazorCommentLiteral) ?: RazorToken.missing(RazorSyntaxKind.RazorCommentLiteral)
            var endStar = getOptionalToken(RazorSyntaxKind.RazorCommentStar)
            if (endStar == null) {
                val diagnostic = RazorDiagnosticFactory.razorCommentNotTerminated(SourceSpan(start, 2))
                endStar = RazorToken.missing(RazorSyntaxKind.RazorCommentStar, diagnostic)
                context.errorSink.onError(diagnostic)
            }
            var endTransition = getOptionalToken(RazorSyntaxKind.RazorCommentTransition)
            if (endTransition == null) {
                if (!endStar.isMissing) {
                    val diagnostic = RazorDiagnosticFactory.razorCommentNotTerminated(SourceSpan(start, 2))
                    context.errorSink.onError(diagnostic)
                }
                // as the compiler: the missing transition carries no diagnostic even when the star was there (the error is reported above)
                endTransition = RazorToken.missing(RazorSyntaxKind.RazorCommentTransition)
            }
            commentBlock = RazorCommentBlockSyntax(startTransition, startStar, comment, endStar, endTransition)
            if (!comment.isMissing || !endStar.isMissing || !endTransition.isMissing) context.makeMarkerNode = true
        }
        initializeContext()
        return commentBlock
    }

    private fun commentSpanContextConfig(editHandler: SpanEditHandlerBuilder?): ChunkGenerator? {
        setAcceptedCharacters(AcceptedCharacters.ANY)
        if (editHandler != null) {
            editHandler.reset()
            editHandler.hasTokenizer = true
        }
        return ChunkGenerator.None
    }

    protected fun eatCurrentToken(): RazorToken {
        val token = current
        nextToken()
        return token
    }

    protected fun eatExpectedToken(kind: RazorSyntaxKind): RazorToken {
        val token = current
        check(token.kind == kind) { "expected $kind, at ${token.kind}" }
        nextToken()
        return token
    }

    protected fun getOptionalToken(kind: RazorSyntaxKind): RazorToken? {
        if (at(kind)) {
            val token = currentToken
            nextToken()
            return token
        }
        return null
    }

    protected fun acceptWhile(type: RazorSyntaxKind) = acceptWhile { token -> type == token.kind }
    protected fun acceptWhile(type1: RazorSyntaxKind, type2: RazorSyntaxKind) = acceptWhile { token -> type1 == token.kind || type2 == token.kind }
    protected fun acceptWhile(type1: RazorSyntaxKind, type2: RazorSyntaxKind, type3: RazorSyntaxKind) =
        acceptWhile { token -> type1 == token.kind || type2 == token.kind || type3 == token.kind }

    protected fun acceptUntil(type: RazorSyntaxKind) = acceptWhile { token -> type != token.kind }
    protected fun acceptUntil(type1: RazorSyntaxKind, type2: RazorSyntaxKind) = acceptWhile { token -> type1 != token.kind && type2 != token.kind }
    protected fun acceptUntil(type1: RazorSyntaxKind, type2: RazorSyntaxKind, type3: RazorSyntaxKind) =
        acceptWhile { token -> type1 != token.kind && type2 != token.kind && type3 != token.kind }

    protected fun acceptWhile(condition: (RazorToken) -> Boolean) {
        val tokens = readWhile(condition)
        accept(tokens)
    }

    protected fun accept(tokens: List<RazorToken?>) {
        for (token in tokens) accept(token)
    }

    protected fun accept(token: RazorToken?) {
        if (token != null) {
            if (token.kind == RazorSyntaxKind.NewLine) context.startOfLine = true
            else if (token.kind != RazorSyntaxKind.Whitespace) context.startOfLine = false
            for (error in token.diagnostics) context.errorSink.onError(error)
            tokenBuilder.add(token)
        }
    }

    protected fun acceptAll(vararg kinds: RazorSyntaxKind): Boolean {
        for (kind in kinds) {
            if (currentToken == null || currentToken?.kind != kind) return false
            acceptAndMoveNext()
        }
        return true
    }

    protected fun acceptAndMoveNext(): Boolean {
        accept(currentToken)
        return nextToken()
    }

    protected fun output(): RazorList {
        val list = if (tokenBuilder.isEmpty()) RazorList.EMPTY else RazorList(ArrayList(tokenBuilder))
        tokenBuilder.clear()
        return list
    }

    /** `AcceptWhitespaceInLines`: accepts whitespace and new lines, keeping the last run of whitespace of the last line out (returned). */
    protected fun acceptWhitespaceInLines(): RazorToken? {
        var lastWs: RazorToken? = null
        while (language.isWhitespace(currentToken) || language.isNewLine(currentToken)) {
            if (lastWs != null) accept(lastWs)
            if (language.isWhitespace(currentToken)) {
                lastWs = currentToken
            } else if (language.isNewLine(currentToken)) {
                accept(currentToken)
                lastWs = null
            }
            nextToken()
        }
        return lastWs
    }

    protected fun tryAccept(type: RazorSyntaxKind): Boolean {
        if (at(type)) {
            acceptAndMoveNext()
            return true
        }
        return false
    }

    protected fun acceptMarkerTokenIfNecessary() {
        if (tokenBuilder.isEmpty() && context.makeMarkerNode) accept(language.createMarkerToken())
    }

    protected fun outputAsMarkupLiteralRequired(): MarkupTextLiteralSyntax {
        val tokens = output()
        check(tokens.size > 0) { "No tokens to output." }
        return MarkupTextLiteralSyntax(tokens, chunkGenerator, getEditHandler())
    }

    protected fun outputAsMarkupLiteral(): MarkupTextLiteralSyntax? {
        val tokens = output()
        if (tokens.size == 0) return null
        return MarkupTextLiteralSyntax(tokens, chunkGenerator, getEditHandler())
    }

    protected fun outputAsMarkupEphemeralLiteral(): MarkupEphemeralTextLiteralSyntax? {
        val tokens = output()
        if (tokens.size == 0) return null
        return MarkupEphemeralTextLiteralSyntax(tokens, chunkGenerator, getEditHandler())
    }

    protected fun outputAsMetaCode(tokens: RazorList, accepted: Int? = null): RazorMetaCodeSyntax? {
        if (tokens.size == 0) return null
        chunkGenerator = ChunkGenerator.None
        context.currentAcceptedCharacters = accepted ?: AcceptedCharacters.NONE
        return RazorMetaCodeSyntax(tokens, ChunkGenerator.None, getEditHandler())
    }

    protected fun outputAsMetaCode(token: RazorToken, accepted: Int? = null): RazorMetaCodeSyntax? = outputAsMetaCode(RazorList(listOf(token)), accepted)

    protected fun getEditHandler(): SpanEditHandler? {
        context.makeMarkerNode = context.currentAcceptedCharacters != AcceptedCharacters.ANY
        val builder = editHandlerBuilder
        if (builder == null) {
            initializeContext()
            return null
        }
        val editHandler = builder.build(context.currentAcceptedCharacters)
        initializeContext()
        return editHandler
    }

    /** `PushSpanContextConfig`: the config of the spans to come, restored by [SpanContextScope.close]. */
    protected fun pushSpanContextConfig(newConfig: SpanContextConfig? = null): SpanContextScope {
        val old = spanContextConfig
        configureSpanContext(newConfig)
        return SpanContextScope(this, old)
    }

    /** The overload that gets the previous config to call first (`SpanContextConfigActionWithPreviousConfig`). */
    protected fun pushSpanContextConfigWithPrevious(newConfig: (editHandlerBuilder: SpanEditHandlerBuilder?, prev: SpanContextConfig?) -> ChunkGenerator?): SpanContextScope {
        val old = spanContextConfig
        val prev = spanContextConfig
        spanContextConfig = { builder -> newConfig(builder, prev) }
        initializeContext()
        return SpanContextScope(this, old)
    }

    protected fun configureSpanContext(config: SpanContextConfig?) {
        spanContextConfig = config
        initializeContext()
    }

    protected fun initializeContext() {
        val config = spanContextConfig ?: return
        chunkGenerator = config(editHandlerBuilder)
    }

    protected fun setAcceptedCharacters(acceptedCharacters: Int?) {
        context.currentAcceptedCharacters = acceptedCharacters ?: AcceptedCharacters.NONE
    }

    fun startingBlock() = tokenizer.tokenizer.startingBlock()
    fun endingBlock() = tokenizer.tokenizer.endingBlock()

    class SpanContextScope(private val parser: TokenizerBackedParser<*>, private val old: SpanContextConfig?) : AutoCloseable {
        override fun close() {
            parser.spanContextConfig = old
        }
    }

    companion object {
        val isSpacingToken: (RazorToken) -> Boolean = { it.kind == RazorSyntaxKind.Whitespace }
        val isSpacingTokenIncludingNewLines: (RazorToken) -> Boolean = { isSpacingToken(it) || it.kind == RazorSyntaxKind.NewLine }
        val isSpacingTokenIncludingComments: (RazorToken) -> Boolean = { isSpacingToken(it) || it.kind == RazorSyntaxKind.CSharpComment }
        val isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives: (RazorToken) -> Boolean = {
            isSpacingTokenIncludingNewLines(it) || it.kind == RazorSyntaxKind.CSharpComment || it.kind == RazorSyntaxKind.CSharpDirective || it.kind == RazorSyntaxKind.CSharpDisabledText
        }
    }
}
