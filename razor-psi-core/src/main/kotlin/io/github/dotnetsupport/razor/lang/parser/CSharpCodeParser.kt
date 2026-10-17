package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.csharp.lang.lexer.CSharpLiteralScanner
import io.github.dotnetsupport.razor.lang.syntax.AcceptedCharacters
import io.github.dotnetsupport.razor.lang.syntax.AutoCompleteEditHandler
import io.github.dotnetsupport.razor.lang.syntax.BaseRazorDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpCodeBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpEphemeralTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpExplicitExpressionBodySyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpExplicitExpressionSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpExpressionLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpImplicitExpressionBodySyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpImplicitExpressionSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpStatementBodySyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpStatementLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpStatementSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpTemplateBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpTransitionSyntax
import io.github.dotnetsupport.razor.lang.syntax.ChunkGenerator
import io.github.dotnetsupport.razor.lang.syntax.CodeBlockEditHandler
import io.github.dotnetsupport.razor.lang.syntax.DirectiveDescriptor
import io.github.dotnetsupport.razor.lang.syntax.DirectiveKind
import io.github.dotnetsupport.razor.lang.syntax.DirectiveTokenEditHandler
import io.github.dotnetsupport.razor.lang.syntax.DirectiveTokenKind
import io.github.dotnetsupport.razor.lang.syntax.DirectiveUsage
import io.github.dotnetsupport.razor.lang.syntax.ImplicitExpressionEditHandler
import io.github.dotnetsupport.razor.lang.syntax.KeywordSet
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticFactory
import io.github.dotnetsupport.razor.lang.syntax.RazorDirectiveBodySyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorDirectives
import io.github.dotnetsupport.razor.lang.syntax.RazorDocumentationDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.RazorListBuilder
import io.github.dotnetsupport.razor.lang.syntax.RazorMetaCodeSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorNode
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind
import io.github.dotnetsupport.razor.lang.syntax.RazorToken
import io.github.dotnetsupport.razor.lang.syntax.RazorUsingDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.SourceLocation
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import io.github.dotnetsupport.razor.lang.syntax.SpanEditHandlerBuilder
import io.github.dotnetsupport.razor.lang.syntax.SyntaxConstants
import io.github.dotnetsupport.razor.lang.syntax.UnclassifiedTextLiteralSyntax

/**
 * `CSharpCodeParser`: the code side of the Razor parser — `@{ }`, `@( )`, implicit expressions, the keyword blocks (`@if`, `@foreach`,
 * `@try`...), the directives (built-in and extensible), templates `@<p>`, the switch back to markup. A port of the compiler's class,
 * method by method; keywords are kept as their text (the compiler's `CSharpSyntaxKind` of the keyword).
 */
class CSharpCodeParser(directives: List<DirectiveDescriptor>, context: ParserContext) :
    TokenizerBackedParser<CSharpTokenizer>(CSharpLanguageCharacteristics(context.options.useRoslynTokenizer, context.options.parseLeadingDirectives), context) {

    private val currentKeywords: KeywordSet
    val keywords: KeywordSet
    private val keywordParserMap = HashMap<String, (RazorListBuilder, CSharpTransitionSyntax?) -> Unit>()
    private val directiveParserMap = HashMap<String, (RazorListBuilder, CSharpTransitionSyntax) -> Unit>()

    lateinit var htmlParser: HtmlMarkupParser
    var isNested: Boolean = false

    init {
        val keywordsSet = HashSet<String>()
        val currentKeywordsSet = HashSet<String>(DEFAULT_KEYWORDS)
        fun mapKeywords(handler: (RazorListBuilder, CSharpTransitionSyntax?) -> Unit, topLevel: Boolean, vararg keywords: String) {
            for (keyword in keywords) {
                keywordParserMap[keyword] = handler
                if (topLevel) keywordsSet.add(keyword)
            }
        }
        mapKeywords(::parseConditionalBlock, true, "for", "foreach", "while", "switch", "lock")
        mapKeywords(::parseCaseStatement, false, "case", "default")
        mapKeywords(::parseIfStatement, true, "if")
        mapKeywords(::parseTryStatement, true, "try")
        mapKeywords(::parseDoStatement, true, "do")
        mapKeywords(::parseUsingKeyword, true, "using")
        keywordParserMap["await"] = ::parseAwaitExpression
        fun mapDirective(directive: String, handler: (RazorListBuilder, CSharpTransitionSyntax) -> Unit) {
            if (directiveParserMap.containsKey(directive)) return
            directiveParserMap[directive] = { builder, transition ->
                handler(builder, transition)
                context.seenDirectives.add(directive)
            }
            keywordsSet.add(directive)
        }
        for (descriptor in directives) {
            currentKeywordsSet.add(descriptor.directive)
            mapDirective(descriptor.directive) { builder, transition -> parseExtensibleDirective(builder, transition, descriptor) }
        }
        mapDirective(SyntaxConstants.CSharp.TAG_HELPER_PREFIX_KEYWORD, ::parseTagHelperPrefixDirective)
        mapDirective(SyntaxConstants.CSharp.ADD_TAG_HELPER_KEYWORD, ::parseAddTagHelperDirective)
        mapDirective(SyntaxConstants.CSharp.REMOVE_TAG_HELPER_KEYWORD, ::parseRemoveTagHelperDirective)
        if (!directiveParserMap.containsKey("class")) mapDirective("class", ::parseReservedDirective)
        if (!directiveParserMap.containsKey("namespace")) mapDirective("namespace", ::parseReservedDirective)
        keywords = KeywordSet(keywordsSet)
        currentKeywords = KeywordSet(currentKeywordsSet)
    }

    fun parseBlock(): CSharpCodeBlockSyntax? {
        if (endOfFile) return null
        startingBlock()
        val builder = RazorListBuilder()
        pushSpanContextConfig(::defaultSpanContextConfig).use {
            try {
                nextToken()
                val precedingWhitespace = readWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives)
                var transitionToken: RazorToken? = null
                if (at(RazorSyntaxKind.StringLiteral) && current.content.isNotEmpty() && current.content[0] == SyntaxConstants.TRANSITION_CHARACTER) {
                    val (left, right) = language.splitToken(current, 1, RazorSyntaxKind.Transition)
                    transitionToken = left
                    tokenizer.reset(context.source.position - right!!.content.length)
                    nextToken()
                } else if (at(RazorSyntaxKind.Transition)) {
                    transitionToken = eatCurrentToken()
                }
                if (transitionToken == null) transitionToken = RazorToken.missing(RazorSyntaxKind.Transition)
                chunkGenerator = ChunkGenerator.None
                setAcceptedCharacters(AcceptedCharacters.NONE)
                val transition = CSharpTransitionSyntax(transitionToken, chunkGenerator, getEditHandler())
                if (at(RazorSyntaxKind.LeftBrace)) {
                    accept(precedingWhitespace)
                    builder.add(outputTokensAsStatementLiteral())
                    val statementBody = parseStatementBody()
                    builder.add(CSharpStatementSyntax(transition, statementBody))
                } else if (at(RazorSyntaxKind.LeftParenthesis)) {
                    accept(precedingWhitespace)
                    builder.add(outputTokensAsStatementLiteral())
                    val expressionBody = parseExplicitExpressionBody()
                    builder.add(CSharpExplicitExpressionSyntax(transition, expressionBody))
                } else if (at(RazorSyntaxKind.Identifier)) {
                    if (!tryParseDirective(builder, precedingWhitespace, transition, current.content)) {
                        accept(precedingWhitespace)
                        builder.add(outputTokensAsStatementLiteral())
                        if (current.content == SyntaxConstants.CSharp.HELPER_KEYWORD) {
                            // the compiler's `CurrentToken.SetDiagnostics` makes a new token it drops: only the sink gets the error
                            context.errorSink.onError(RazorDiagnosticFactory.helperDirectiveNotAvailable(SourceSpan(currentStart, current.content.length)))
                        }
                        val implicitExpressionBody = parseImplicitExpressionBody()
                        builder.add(CSharpImplicitExpressionSyntax(transition, implicitExpressionBody))
                    }
                } else if (at(RazorSyntaxKind.Keyword)) {
                    if (!tryParseDirective(builder, precedingWhitespace, transition, current.content) && !tryParseKeyword(builder, precedingWhitespace, transition)) {
                        accept(precedingWhitespace)
                        builder.add(outputTokensAsStatementLiteral())
                        val implicitExpressionBody = parseImplicitExpressionBody()
                        builder.add(CSharpImplicitExpressionSyntax(transition, implicitExpressionBody))
                    }
                    builder.add(outputTokensAsStatementLiteral())
                } else {
                    accept(precedingWhitespace)
                    builder.add(outputTokensAsStatementLiteral())
                    chunkGenerator = ChunkGenerator.Expression
                    setAcceptedCharacters(AcceptedCharacters.NON_WHITESPACE)
                    editHandlerBuilder?.let { setupImplicitExpressionEditHandler(it, acceptTrailingDot = isNested, keywords = currentKeywords) }
                    accept(language.createMarkerToken())
                    val expressionLiteral = CSharpCodeBlockSyntax(RazorListBuilder().also { it.add(outputTokensAsExpressionLiteral()) }.toList())
                    val expressionBody = CSharpImplicitExpressionBodySyntax(expressionLiteral)
                    builder.add(CSharpImplicitExpressionSyntax(transition, expressionBody))
                    if (at(RazorSyntaxKind.Whitespace) || at(RazorSyntaxKind.NewLine)) {
                        context.errorSink.onError(RazorDiagnosticFactory.unexpectedWhiteSpaceAtStartOfCodeBlock(SourceSpan(currentStart, current.content.length)))
                    } else if (endOfFile) {
                        context.errorSink.onError(RazorDiagnosticFactory.unexpectedEndOfFileAtStartOfCodeBlock(SourceSpan(currentStart, 1)))
                    } else {
                        context.errorSink.onError(RazorDiagnosticFactory.unexpectedCharacterAtStartOfCodeBlock(SourceSpan(currentStart, current.content.length), current.content))
                    }
                }
                return CSharpCodeBlockSyntax(builder.toList())
            } finally {
                putCurrentBack()
            }
        }
    }

    private fun parseExplicitExpressionBody(): CSharpExplicitExpressionBodySyntax {
        val block = Block(BLOCK_NAME_EXPLICIT_EXPRESSION, currentStart)
        val leftParenToken = eatCurrentToken()
        val leftParen = outputAsMetaCode(leftParenToken)
        val expressionBuilder = RazorListBuilder()
        pushSpanContextConfig(::explicitExpressionSpanContextConfig).use {
            val success = balance(
                expressionBuilder, BalancingModes.BACKTRACK_ON_FAILURE or BalancingModes.NO_ERROR_ON_FAILURE or BalancingModes.ALLOW_COMMENTS_AND_TEMPLATES,
                RazorSyntaxKind.LeftParenthesis, RazorSyntaxKind.RightParenthesis, block.start,
            )
            if (!success) {
                acceptUntil(RazorSyntaxKind.LessThan)
                context.errorSink.onError(RazorDiagnosticFactory.expectedEndOfBlockBeforeEOF(SourceSpan(block.start, 1), block.name, ")", "("))
            }
            acceptMarkerTokenIfNecessary()
            expressionBuilder.add(outputTokensAsExpressionLiteral())
        }
        val expressionBlock = CSharpCodeBlockSyntax(expressionBuilder.toList())
        val rightParen: RazorMetaCodeSyntax? = if (at(RazorSyntaxKind.RightParenthesis)) outputAsMetaCode(eatCurrentToken())
        else outputAsMetaCode(RazorToken.missing(RazorSyntaxKind.RightParenthesis), context.currentAcceptedCharacters)
        if (!endOfFile) putCurrentBack()
        return CSharpExplicitExpressionBodySyntax(leftParen!!, expressionBlock, rightParen!!)
    }

    private fun parseImplicitExpressionBody(async: Boolean = false): CSharpImplicitExpressionBodySyntax {
        val accepted = if (async) AcceptedCharacters.ANY_EXCEPT_NEWLINE else AcceptedCharacters.NON_WHITESPACE
        val expressionBuilder = RazorListBuilder()
        parseImplicitExpression(expressionBuilder, accepted)
        return CSharpImplicitExpressionBodySyntax(CSharpCodeBlockSyntax(expressionBuilder.toList()))
    }

    private fun parseImplicitExpression(builder: RazorListBuilder, acceptedCharacters: Int) {
        pushSpanContextConfig { editHandlerBuilder ->
            setAcceptedCharacters(acceptedCharacters)
            editHandlerBuilder?.let { setupImplicitExpressionEditHandler(it, acceptTrailingDot = isNested, keywords = keywords) }
            ChunkGenerator.Expression
        }.use {
            do {
                if (atIdentifier(allowKeywords = true)) acceptAndMoveNext()
            } while (parseMethodCallOrArrayIndex(builder, acceptedCharacters))
            putCurrentBack()
            builder.add(outputTokensAsExpressionLiteral())
        }
    }

    private fun parseMethodCallOrArrayIndex(builder: RazorListBuilder, acceptedCharacters: Int): Boolean {
        if (!endOfFile) {
            if (current.kind == RazorSyntaxKind.LeftParenthesis || current.kind == RazorSyntaxKind.LeftBracket) {
                setAcceptedCharacters(AcceptedCharacters.ANY)
                val right: RazorSyntaxKind
                val success: Boolean
                pushSpanContextConfigWithPrevious { editHandlerBuilder, prev ->
                    val generator = prev?.invoke(editHandlerBuilder)
                    setAcceptedCharacters(AcceptedCharacters.ANY)
                    generator
                }.use {
                    right = language.flipBracket(current.kind)
                    success = balance(builder, BalancingModes.BACKTRACK_ON_FAILURE or BalancingModes.ALLOW_COMMENTS_AND_TEMPLATES)
                }
                if (!success) acceptUntil(RazorSyntaxKind.LessThan)
                if (at(right)) {
                    acceptAndMoveNext()
                    setAcceptedCharacters(acceptedCharacters)
                }
                return parseMethodCallOrArrayIndex(builder, acceptedCharacters)
            }
            if (at(RazorSyntaxKind.QuestionMark)) {
                val next = lookahead(1)
                if (next != null) {
                    if (next.kind == RazorSyntaxKind.Dot) {
                        acceptAndMoveNext()
                        acceptAndMoveNext()
                        return at(RazorSyntaxKind.Identifier) || at(RazorSyntaxKind.Keyword)
                    } else if (next.kind == RazorSyntaxKind.LeftBracket) {
                        acceptAndMoveNext()
                        return parseMethodCallOrArrayIndex(builder, acceptedCharacters)
                    }
                }
            } else if (at(RazorSyntaxKind.Not) && context.options.allowNullableForgivenessOperator) {
                val next = lookahead(1) ?: return false
                if (next.kind == RazorSyntaxKind.Dot) {
                    val nextNext = lookahead(2) ?: return false
                    if (nextNext.kind == RazorSyntaxKind.Identifier || nextNext.kind == RazorSyntaxKind.Keyword) {
                        acceptAndMoveNext()
                        acceptAndMoveNext()
                        return true
                    }
                    return false
                } else if (next.kind == RazorSyntaxKind.QuestionMark) {
                    acceptAndMoveNext()
                    return true
                } else if (next.kind == RazorSyntaxKind.LeftBracket || next.kind == RazorSyntaxKind.LeftParenthesis) {
                    acceptAndMoveNext()
                    return parseMethodCallOrArrayIndex(builder, acceptedCharacters)
                }
                return false
            } else if (at(RazorSyntaxKind.Dot)) {
                val dot = current
                if (nextToken()) {
                    if (at(RazorSyntaxKind.Identifier) || at(RazorSyntaxKind.Keyword)) {
                        accept(dot)
                        return true
                    } else {
                        putCurrentBack()
                    }
                }
                if (!isNested) putBack(dot) else accept(dot)
            } else if (!at(RazorSyntaxKind.Whitespace) && !at(RazorSyntaxKind.NewLine)) {
                putCurrentBack()
            }
        }
        return false
    }

    private fun parseStatementBody(block: Block? = null): CSharpStatementBodySyntax {
        val theBlock = block ?: Block(BLOCK_NAME_CODE, currentStart)
        val leftBrace = outputAsMetaCode(eatExpectedToken(RazorSyntaxKind.LeftBrace))
        val builder = RazorListBuilder()
        var acceptCloseBraceAccessor: AutoCompleteEditHandler.AutoCompleteStringAccessor? = null
        editHandlerBuilder?.let { acceptCloseBraceAccessor = setupAutoCompleteEditHandler(it, autoCompleteAtEndOfSpan = false) }
        parseCodeBlock(builder, theBlock)
        if (endOfFile) {
            context.errorSink.onError(RazorDiagnosticFactory.expectedEndOfBlockBeforeEOF(SourceSpan(theBlock.start, 1), theBlock.name, "}", "{"))
        }
        ensureCurrent()
        chunkGenerator = ChunkGenerator.Statement
        acceptMarkerTokenIfNecessary()
        acceptCloseBraceAccessor?.canAcceptCloseBrace = !at(RazorSyntaxKind.RightBrace)
        builder.add(outputTokensAsStatementLiteral())
        val codeBlock = CSharpCodeBlockSyntax(builder.toList())
        val rightBrace: RazorMetaCodeSyntax? = if (at(RazorSyntaxKind.RightBrace)) outputAsMetaCode(eatCurrentToken())
        else outputAsMetaCode(RazorToken.missing(RazorSyntaxKind.RightBrace), context.currentAcceptedCharacters)
        if (!isNested) {
            ensureCurrent()
            if (at(RazorSyntaxKind.NewLine) || (at(RazorSyntaxKind.Whitespace) && nextIs(RazorSyntaxKind.NewLine))) context.nullGenerateWhitespaceAndNewLine = true
        }
        return CSharpStatementBodySyntax(leftBrace!!, codeBlock, rightBrace!!)
    }

    private fun parseCodeBlock(builder: RazorListBuilder, block: Block) {
        ensureCurrent()
        while (!endOfFile && !at(RazorSyntaxKind.RightBrace)) {
            parseStatement(builder, block, encounteredUnexpectedMarkupTransition = false)
            ensureCurrent()
        }
    }

    private fun parseStatement(builder: RazorListBuilder, block: Block, encounteredUnexpectedMarkupTransition: Boolean) {
        setAcceptedCharacters(AcceptedCharacters.ANY)
        val tokens = readWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives)
        val lastWhitespace = tokens.lastOrNull()?.takeIf { it.kind == RazorSyntaxKind.Whitespace }
        if (lastWhitespace != null) tokens.removeAt(tokens.size - 1)
        accept(tokens)
        if (endOfFile) {
            if (lastWhitespace != null) accept(lastWhitespace)
            builder.add(outputTokensAsStatementLiteral())
            return
        }
        val kind = current.kind
        val location = currentStart
        val isSingleLineMarkup = kind == RazorSyntaxKind.Transition && nextIs(RazorSyntaxKind.Colon, RazorSyntaxKind.DoubleColon)
        val isMarkup = isSingleLineMarkup || kind == RazorSyntaxKind.LessThan || (kind == RazorSyntaxKind.Transition && nextIs(RazorSyntaxKind.LessThan))
        if (!isMarkup) {
            if (lastWhitespace != null) accept(lastWhitespace)
        } else {
            val nextToken = lookahead(1)
            putCurrentBack()
            if (nextToken != null && nextToken.content != SyntaxConstants.TEXT_TAG_NAME) putBack(lastWhitespace) else accept(lastWhitespace)
        }
        if (isMarkup) {
            if (kind == RazorSyntaxKind.Transition && !isSingleLineMarkup) {
                context.errorSink.onError(RazorDiagnosticFactory.atInCodeMustBeFollowedByColonParenOrIdentifierStart(SourceSpan(location, 1)))
            }
            builder.add(outputTokensAsStatementLiteral())
            otherParserBlock(builder)
        } else {
            when (kind) {
                RazorSyntaxKind.RazorCommentTransition -> {
                    acceptMarkerTokenIfNecessary()
                    builder.add(outputTokensAsStatementLiteral())
                    builder.add(parseRazorComment())
                    parseStatement(builder, block, encounteredUnexpectedMarkupTransition)
                }
                RazorSyntaxKind.LeftBrace -> {
                    acceptAndMoveNext()
                    parseCodeBlock(builder, block)
                    if (endOfFile) {
                        context.errorSink.onError(RazorDiagnosticFactory.expectedEndOfBlockBeforeEOF(SourceSpan(block.start, 1), block.name, "}", "{"))
                    } else {
                        setAcceptedCharacters(AcceptedCharacters.NONE)
                        acceptAndMoveNext()
                    }
                }
                RazorSyntaxKind.Keyword -> if (!tryParseKeyword(builder)) parseStandardStatement(builder, encounteredUnexpectedMarkupTransition)
                RazorSyntaxKind.Transition -> parseEmbeddedExpression(builder, encounteredUnexpectedMarkupTransition)
                RazorSyntaxKind.RightBrace -> {}
                RazorSyntaxKind.CSharpComment -> {
                    accept(currentToken)
                    nextToken()
                }
                else -> parseStandardStatement(builder, encounteredUnexpectedMarkupTransition)
            }
        }
    }

    private fun parseEmbeddedExpression(builder: RazorListBuilder, encounteredUnexpectedMarkupTransition: Boolean) {
        val transition = current
        nextToken()
        if (at(RazorSyntaxKind.Transition)) {
            builder.add(outputTokensAsStatementLiteral())
            accept(transition)
            chunkGenerator = ChunkGenerator.None
            builder.add(outputTokensAsEphemeralLiteral())
            acceptAndMoveNext()
            parseStandardStatement(builder, encounteredUnexpectedMarkupTransition)
        } else {
            if (at(RazorSyntaxKind.LeftBrace)) {
                context.errorSink.onError(RazorDiagnosticFactory.unexpectedNestedCodeBlock(SourceSpan(currentStart, 1)))
            }
            putCurrentBack()
            putBack(transition)
            acceptMarkerTokenIfNecessary()
            builder.add(outputTokensAsStatementLiteral())
            builder.add(parseNestedBlock())
        }
    }

    private fun parseNestedBlock(): RazorNode? {
        val wasNested = isNested
        isNested = true
        val nestedBlock: RazorNode?
        pushSpanContextConfig().use {
            nestedBlock = parseBlock()
        }
        initializeContext()
        isNested = wasNested
        nextToken()
        return nestedBlock
    }

    private fun parseStandardStatement(builder: RazorListBuilder, encounteredUnexpectedMarkupTransition: Boolean) {
        while (!endOfFile) {
            val bookmark = currentStart.absoluteIndex
            val read = readWhile { token ->
                token.kind != RazorSyntaxKind.Semicolon && token.kind != RazorSyntaxKind.RazorCommentTransition && token.kind != RazorSyntaxKind.Transition &&
                    token.kind != RazorSyntaxKind.LeftBrace && token.kind != RazorSyntaxKind.LeftParenthesis && token.kind != RazorSyntaxKind.LeftBracket &&
                    token.kind != RazorSyntaxKind.RightBrace && token.kind != RazorSyntaxKind.Keyword
            }
            if ((!context.options.allowRazorInAllCodeBlocks && at(RazorSyntaxKind.LeftBrace)) || at(RazorSyntaxKind.LeftParenthesis) || at(RazorSyntaxKind.LeftBracket)) {
                accept(read)
                if (!tryBalanceBlock(builder)) return
            } else if (context.options.allowRazorInAllCodeBlocks && at(RazorSyntaxKind.LeftBrace)) {
                accept(read)
                return
            } else if (at(RazorSyntaxKind.Transition)) {
                val nextToken = lookahead(1)
                when (nextToken?.kind) {
                    RazorSyntaxKind.LessThan, RazorSyntaxKind.Colon -> {
                        accept(read)
                        builder.add(outputTokensAsStatementLiteral())
                        parseTemplate(builder)
                        continue
                    }
                    RazorSyntaxKind.Keyword, RazorSyntaxKind.Identifier -> {
                        if (nextToken.kind == RazorSyntaxKind.Keyword && encounteredUnexpectedMarkupTransition) {
                            accept(read)
                            return
                        }
                        accept(read)
                        accept(nextAsEscapedIdentifier())
                        continue
                    }
                    RazorSyntaxKind.Transition -> {
                        val third = lookahead(2)
                        if (third == null || (third.kind != RazorSyntaxKind.Identifier && third.kind != RazorSyntaxKind.Keyword)) {
                            unexpectedTransition(builder, read)
                            continue
                        }
                        accept(read)
                        acceptAndMoveNext()
                        accept(nextAsEscapedIdentifier())
                        continue
                    }
                    else -> {
                        unexpectedTransition(builder, read)
                        continue
                    }
                }
            } else if (at(RazorSyntaxKind.RazorCommentTransition)) {
                accept(read)
                acceptMarkerTokenIfNecessary()
                builder.add(outputTokensAsStatementLiteral())
                builder.add(parseRazorComment())
                continue
            } else if (at(RazorSyntaxKind.Semicolon)) {
                accept(read)
                acceptAndMoveNext()
                return
            } else if (at(RazorSyntaxKind.RightBrace)) {
                accept(read)
                return
            } else if (at(RazorSyntaxKind.Keyword)) {
                accept(read)
                if (current.content == "switch") {
                    acceptUntil(RazorSyntaxKind.LeftBrace)
                    if (!at(RazorSyntaxKind.LeftBrace)) return
                    if (!tryBalanceBlock(builder, parseNestedBlocksAsMarkup = context.options.allowRazorInAllCodeBlocks)) return
                } else {
                    acceptAndMoveNext()
                }
            } else {
                tokenizer.reset(bookmark)
                nextToken()
                acceptUntil(RazorSyntaxKind.LessThan, RazorSyntaxKind.LeftBrace, RazorSyntaxKind.RightBrace)
                return
            }
        }
    }

    /** The `default` of the `@` switch of `ParseStandardStatement`: an `@` that starts nothing, kept as an identifier with an error. */
    private fun unexpectedTransition(builder: RazorListBuilder, read: List<RazorToken>) {
        accept(read)
        val transition = current
        context.errorSink.onError(RazorDiagnosticFactory.atInCodeMustBeFollowedByColonParenOrIdentifierStart(SourceSpan(currentStart, 1)))
        nextToken()
        accept(RazorToken(RazorSyntaxKind.Identifier, transition.content))
    }

    private fun tryBalanceBlock(builder: RazorListBuilder, parseNestedBlocksAsMarkup: Boolean = false): Boolean {
        var mode = BalancingModes.ALLOW_COMMENTS_AND_TEMPLATES or BalancingModes.BACKTRACK_ON_FAILURE
        if (parseNestedBlocksAsMarkup) mode = mode or BalancingModes.PARSE_NESTED_BLOCKS_AS_MARKUP
        if (balance(builder, mode)) {
            tryAccept(RazorSyntaxKind.RightBrace)
        } else {
            acceptUntil(RazorSyntaxKind.LessThan, RazorSyntaxKind.RightBrace)
            return false
        }
        return true
    }

    private fun parseTemplate(builder: RazorListBuilder) {
        if (context.inTemplateContext) {
            context.errorSink.onError(RazorDiagnosticFactory.inlineMarkupBlocksCannotBeNested(SourceSpan(currentStart, 1)))
        }
        if (chunkGenerator is ChunkGenerator.Expression) builder.add(outputTokensAsExpressionLiteral()) else builder.add(outputTokensAsStatementLiteral())
        val templateBuilder = RazorListBuilder()
        context.inTemplateContext = true
        putCurrentBack()
        otherParserBlock(templateBuilder)
        builder.add(CSharpTemplateBlockSyntax(templateBuilder.toList()))
        context.inTemplateContext = false
    }

    private fun tryParseDirective(builder: RazorListBuilder, whitespace: List<RazorToken>, transition: CSharpTransitionSyntax, directive: String): Boolean {
        val handler = directiveParserMap[directive]
        if (handler != null) {
            accept(whitespace)
            builder.add(outputTokensAsEphemeralLiteral())
            chunkGenerator = ChunkGenerator.None
            handler(builder, transition)
            return true
        }
        if (context.options.languageVersion < RazorLanguageVersion.VERSION_12_0 && directive == RazorDirectives.documentation.directive) {
            context.errorSink.onError(RazorDiagnosticFactory.documentationDirectiveWillChangeMeaning(SourceSpan(currentStart, current.content.length)))
        }
        return false
    }

    private fun ensureDirectiveIsAtStartOfLine() {
        if (currentStart.characterIndex > 1) {
            var index = currentStart.absoluteIndex - 1
            val lineStart = currentStart.absoluteIndex - currentStart.characterIndex
            while (--index >= lineStart) {
                val c = context.text[index]
                if (!c.isWhitespace()) {
                    val currentDirective = current.content
                    context.errorSink.onError(RazorDiagnosticFactory.directiveMustAppearAtStartOfLine(SourceSpan(currentStart, currentDirective.length), currentDirective))
                    break
                }
            }
        }
    }

    private fun parseTagHelperPrefixDirective(builder: RazorListBuilder, transition: CSharpTransitionSyntax) {
        var duplicateDiagnostic: RazorDiagnostic? = null
        if (SyntaxConstants.CSharp.TAG_HELPER_PREFIX_KEYWORD in context.seenDirectives) {
            var directiveStart = currentStart
            directiveStart = SourceLocation(directiveStart.filePath, directiveStart.absoluteIndex - 1, directiveStart.lineIndex, directiveStart.characterIndex - 1)
            val errorLength = 1 + SyntaxConstants.CSharp.TAG_HELPER_PREFIX_KEYWORD.length
            duplicateDiagnostic = RazorDiagnosticFactory.duplicateDirective(SourceSpan(directiveStart, errorLength), SyntaxConstants.CSharp.TAG_HELPER_PREFIX_KEYWORD)
        }
        val directiveBody = parseTagHelperDirective(SyntaxConstants.CSharp.TAG_HELPER_PREFIX_KEYWORD) { prefix, errors, startLocation ->
            if (duplicateDiagnostic != null) errors.add(duplicateDiagnostic)
            val parsedDirective = parseDirective(prefix, startLocation, TagHelperDirectiveType.TagHelperPrefix, errors)
            ChunkGenerator.TagHelperPrefix(prefix, parsedDirective.directiveText, errors)
        }
        builder.add(RazorDirectiveSyntax(transition, directiveBody))
    }

    private fun parseAddTagHelperDirective(builder: RazorListBuilder, transition: CSharpTransitionSyntax) {
        val directiveBody = parseTagHelperDirective(SyntaxConstants.CSharp.ADD_TAG_HELPER_KEYWORD) { lookupText, errors, startLocation ->
            val parsedDirective = parseDirective(lookupText, startLocation, TagHelperDirectiveType.AddTagHelper, errors)
            ChunkGenerator.AddTagHelper(lookupText, parsedDirective.directiveText, parsedDirective.typePattern, parsedDirective.assemblyName, errors)
        }
        builder.add(RazorDirectiveSyntax(transition, directiveBody))
    }

    private fun parseRemoveTagHelperDirective(builder: RazorListBuilder, transition: CSharpTransitionSyntax) {
        val directiveBody = parseTagHelperDirective(SyntaxConstants.CSharp.REMOVE_TAG_HELPER_KEYWORD) { lookupText, errors, startLocation ->
            val parsedDirective = parseDirective(lookupText, startLocation, TagHelperDirectiveType.RemoveTagHelper, errors)
            ChunkGenerator.RemoveTagHelper(lookupText, parsedDirective.directiveText, parsedDirective.typePattern, parsedDirective.assemblyName, errors)
        }
        builder.add(RazorDirectiveSyntax(transition, directiveBody))
    }

    private fun parseTagHelperDirective(keyword: String, chunkGeneratorFactory: (String, MutableList<RazorDiagnostic>, SourceLocation) -> ChunkGenerator): RazorDirectiveBodySyntax {
        var keywordBlock: RazorMetaCodeSyntax? = null
        val directiveBuilder = RazorListBuilder()
        val directiveErrorSink = ErrorSink()
        context.withErrorScope(directiveErrorSink) {
            val directiveValue: String
            var valueStartLocation: SourceLocation? = null
            ensureDirectiveIsAtStartOfLine()
            val keywordStartLocation = currentStart
            val keywordToken = eatCurrentToken()
            val keywordLength = keywordToken.width + 1
            val foundWhitespace = at(RazorSyntaxKind.Whitespace)
            val acceptedCharacters = if (foundWhitespace) AcceptedCharacters.NONE else AcceptedCharacters.ANY_EXCEPT_NEWLINE
            accept(keywordToken)
            keywordBlock = outputAsMetaCode(output(), acceptedCharacters)
            acceptWhile(RazorSyntaxKind.Whitespace)
            chunkGenerator = ChunkGenerator.None
            setAcceptedCharacters(acceptedCharacters)
            directiveBuilder.add(outputAsMarkupLiteral())
            if (endOfFile || at(RazorSyntaxKind.NewLine)) {
                context.errorSink.onError(RazorDiagnosticFactory.directiveMustHaveValue(SourceSpan(keywordStartLocation, keywordLength), keyword))
                directiveValue = ""
            } else {
                valueStartLocation = currentStart
                acceptUntil(RazorSyntaxKind.NewLine)
                val rawValue = tokenBuilder.joinToString("") { it.content }.trim()
                val startsWithQuote = rawValue.startsWith("\"")
                val endsWithQuote = rawValue.endsWith("\"")
                if (startsWithQuote != endsWithQuote) {
                    context.errorSink.onError(RazorDiagnosticFactory.incompleteQuotesAroundDirective(SourceSpan(valueStartLocation, rawValue.length), keyword))
                }
                directiveValue = rawValue
            }
            chunkGenerator = chunkGeneratorFactory(directiveValue, ArrayList(directiveErrorSink.getErrorsAndClear()), valueStartLocation ?: currentStart)
        }
        completeBlock()
        setAcceptedCharacters(AcceptedCharacters.ANY_EXCEPT_NEWLINE)
        directiveBuilder.add(outputTokensAsStatementLiteral())
        val directiveCodeBlock = CSharpCodeBlockSyntax(directiveBuilder.toList())
        return RazorDirectiveBodySyntax(keywordBlock!!, directiveCodeBlock)
    }

    private class ParsedDirective(var directiveText: String, var assemblyName: String? = null, var typePattern: String? = null)
    private enum class TagHelperDirectiveType { AddTagHelper, RemoveTagHelper, TagHelperPrefix }

    private fun parseDirective(directiveText: String, directiveLocation: SourceLocation, directiveType: TagHelperDirectiveType, errors: MutableList<RazorDiagnostic>): ParsedDirective {
        var offset = 0
        var text = directiveText.trim()
        if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
            text = text.substring(1, text.length - 1)
            if (text.isEmpty()) offset = 1
        }
        var location = directiveLocation
        if (tokenBuilder.size == 1 && tokenBuilder[0].kind == RazorSyntaxKind.StringLiteral) {
            val content = tokenBuilder[0].content
            offset += content.indexOf(text)
            location = SourceLocation(directiveLocation.filePath, directiveLocation.absoluteIndex + offset, directiveLocation.lineIndex, directiveLocation.characterIndex + offset)
        }
        val parsedDirective = ParsedDirective(text)
        if (directiveType == TagHelperDirectiveType.TagHelperPrefix) {
            validateTagHelperPrefix(parsedDirective.directiveText, location, errors)
            return parsedDirective
        }
        return parseAddOrRemoveDirective(parsedDirective, location, errors)
    }

    private fun parseAddOrRemoveDirective(directive: ParsedDirective, directiveLocation: SourceLocation, errors: MutableList<RazorDiagnostic>): ParsedDirective {
        val text = directive.directiveText
        val split = trySplitDirectiveText(text)
        if (split == null) {
            errors.add(RazorDiagnosticFactory.invalidTagHelperLookupText(SourceSpan(directiveLocation, maxOf(text.length, 1)), text))
            return directive
        }
        directive.typePattern = split.first
        directive.assemblyName = split.second
        return directive
    }

    private fun trySplitDirectiveText(directiveText: String): Pair<String, String>? {
        if (directiveText.isEmpty() || directiveText[0] == '\'' || directiveText[directiveText.length - 1] == '\'') return null
        val commaIndex = directiveText.indexOf(',')
        if (commaIndex < 0) return null
        val typeName = directiveText.substring(0, commaIndex).trim()
        val assemblyName = directiveText.substring(commaIndex + 1).trim()
        if (typeName.isEmpty() || assemblyName.isEmpty() || assemblyName.indexOf(',') >= 0) return null
        return typeName to assemblyName
    }

    private fun validateTagHelperPrefix(prefix: String, directiveLocation: SourceLocation, diagnostics: MutableList<RazorDiagnostic>) {
        for (character in prefix) {
            if (character.isWhitespace() || character in INVALID_NON_WHITESPACE_NAME_CHARACTERS) {
                diagnostics.add(RazorDiagnosticFactory.invalidTagHelperPrefixValue(SourceSpan(directiveLocation, prefix.length), SyntaxConstants.CSharp.TAG_HELPER_PREFIX_KEYWORD, character, prefix))
                return
            }
        }
    }

    private fun parseExtensibleDirective(builder: RazorListBuilder, transition: CSharpTransitionSyntax, descriptor: DirectiveDescriptor) {
        val directiveBuilder = RazorListBuilder()
        var keywordBlock: RazorMetaCodeSyntax? = null
        var shouldCaptureWhitespaceToEndOfLine = false
        val directiveErrorSink = ErrorSink()

        fun buildDirective(expectedTokenKindIfMissing: RazorSyntaxKind): BaseRazorDirectiveSyntax {
            var node: RazorNode? = outputTokensAsStatementLiteral()
            if (node == null && directiveBuilder.count == 0) {
                node = CSharpStatementLiteralSyntax(io.github.dotnetsupport.razor.lang.syntax.RazorList(listOf(RazorToken.missing(expectedTokenKindIfMissing))), chunkGenerator, editHandler = null)
            }
            directiveBuilder.add(node)
            val directiveCodeBlock = CSharpCodeBlockSyntax(directiveBuilder.toList())
            val directiveBody = RazorDirectiveBodySyntax(keywordBlock!!, directiveCodeBlock)
            val directive: BaseRazorDirectiveSyntax = if (descriptor === RazorDirectives.documentation) RazorDocumentationDirectiveSyntax(transition, directiveBody, descriptor)
            else RazorDirectiveSyntax(transition, directiveBody, descriptor)
            val diagnostics = directiveErrorSink.getErrorsAndClear()
            return directive.withDiagnostics(diagnostics)
        }

        context.withErrorScope(directiveErrorSink) {
            ensureDirectiveIsAtStartOfLine()
            var directiveStart = currentStart
            directiveStart = SourceLocation(directiveStart.filePath, directiveStart.absoluteIndex - 1, directiveStart.lineIndex, directiveStart.characterIndex - 1)
            acceptAndMoveNext()
            keywordBlock = outputAsMetaCode(output())
            validateDirectiveUsage(descriptor, directiveStart)
            var lastSeenMemberIdentifier: String? = null
            for (i in descriptor.tokens.indices) {
                if (!at(RazorSyntaxKind.Whitespace) && !at(RazorSyntaxKind.NewLine) && !at(RazorSyntaxKind.Semicolon) && !endOfFile) {
                    context.errorSink.onError(RazorDiagnosticFactory.directiveTokensMustBeSeparatedByWhitespace(SourceSpan(currentStart, current.content.length), descriptor.directive))
                    builder.add(buildDirective(RazorSyntaxKind.Whitespace))
                    return
                }
                val tokenDescriptor = descriptor.tokens[i]
                if (at(RazorSyntaxKind.Whitespace)) {
                    acceptWhile(isSpacingTokenIncludingComments)
                    chunkGenerator = ChunkGenerator.None
                    setAcceptedCharacters(AcceptedCharacters.WHITESPACE)
                    if (tokenDescriptor.kind == DirectiveTokenKind.Member || tokenDescriptor.kind == DirectiveTokenKind.Namespace || tokenDescriptor.kind == DirectiveTokenKind.Type ||
                        tokenDescriptor.kind == DirectiveTokenKind.Attribute || tokenDescriptor.kind == DirectiveTokenKind.GenericTypeConstraint ||
                        tokenDescriptor.kind == DirectiveTokenKind.Boolean || tokenDescriptor.kind == DirectiveTokenKind.IdentifierOrExpression
                    ) {
                        directiveBuilder.add(outputTokensAsStatementLiteral())
                        if (endOfFile || at(RazorSyntaxKind.NewLine)) {
                            acceptMarkerTokenIfNecessary()
                            chunkGenerator = ChunkGenerator.DirectiveToken(tokenDescriptor)
                            setAcceptedCharacters(AcceptedCharacters.NON_WHITESPACE)
                            editHandlerBuilder?.let { setupDirectiveTokenEditHandler(it) }
                            directiveBuilder.add(outputTokensAsStatementLiteral())
                        }
                    } else {
                        directiveBuilder.add(outputAsMarkupEphemeralLiteral())
                    }
                }
                if (tokenDescriptor.optional && (endOfFile || at(RazorSyntaxKind.NewLine))) {
                    break
                } else if (endOfFile) {
                    context.errorSink.onError(RazorDiagnosticFactory.unexpectedEOFAfterDirective(SourceSpan(currentStart, 1), descriptor.directive, tokenDescriptor.kind.name.lowercase()))
                    builder.add(buildDirective(RazorSyntaxKind.Identifier))
                    return
                }
                when (tokenDescriptor.kind) {
                    DirectiveTokenKind.Type -> if (!tryParseNamespaceOrTypeName(directiveBuilder)) {
                        context.errorSink.onError(RazorDiagnosticFactory.directiveExpectsTypeName(SourceSpan(currentStart, current.content.length), descriptor.directive))
                        builder.add(buildDirective(RazorSyntaxKind.Identifier))
                        return
                    }
                    DirectiveTokenKind.Namespace -> {
                        val (ok, identifierLength) = tryParseQualifiedIdentifier()
                        if (!ok) {
                            context.errorSink.onError(RazorDiagnosticFactory.directiveExpectsNamespace(SourceSpan(currentStart, identifierLength), descriptor.directive))
                            builder.add(buildDirective(RazorSyntaxKind.Identifier))
                            return
                        }
                    }
                    DirectiveTokenKind.Member -> if (at(RazorSyntaxKind.Identifier)) {
                        lastSeenMemberIdentifier = current.content
                        acceptAndMoveNext()
                    } else {
                        context.errorSink.onError(RazorDiagnosticFactory.directiveExpectsIdentifier(SourceSpan(currentStart, current.content.length), descriptor.directive))
                        builder.add(buildDirective(RazorSyntaxKind.Identifier))
                        return
                    }
                    DirectiveTokenKind.String -> if (at(RazorSyntaxKind.StringLiteral) && !current.containsDiagnostics) {
                        acceptAndMoveNext()
                    } else {
                        context.errorSink.onError(RazorDiagnosticFactory.directiveExpectsQuotedStringLiteral(SourceSpan(currentStart, current.content.length), descriptor.directive))
                        builder.add(buildDirective(RazorSyntaxKind.StringLiteral))
                        return
                    }
                    DirectiveTokenKind.Boolean -> if (atBooleanLiteral() && !current.containsDiagnostics) {
                        acceptAndMoveNext()
                    } else {
                        context.errorSink.onError(RazorDiagnosticFactory.directiveExpectsBooleanLiteral(SourceSpan(currentStart, current.content.length), descriptor.directive))
                        builder.add(buildDirective(RazorSyntaxKind.CSharpExpressionLiteral))
                        return
                    }
                    DirectiveTokenKind.Attribute -> if (at(RazorSyntaxKind.LeftBracket)) {
                        if (balance(directiveBuilder, BalancingModes.NO_ERROR_ON_FAILURE)) tryAccept(RazorSyntaxKind.RightBracket)
                    } else {
                        context.errorSink.onError(RazorDiagnosticFactory.directiveExpectsCSharpAttribute(SourceSpan(currentStart, current.content.length), descriptor.directive))
                        builder.add(buildDirective(RazorSyntaxKind.LeftBracket))
                        return
                    }
                    DirectiveTokenKind.GenericTypeConstraint -> if (at(RazorSyntaxKind.Keyword) && current.content == "where") {
                        acceptAndMoveNext()
                        acceptWhile(RazorSyntaxKind.Whitespace)
                        if (current.content != lastSeenMemberIdentifier) {
                            context.errorSink.onError(
                                RazorDiagnosticFactory.genericTypeParameterIdentifierMismatch(SourceSpan(currentStart, current.content.length), descriptor.directive, current.content, lastSeenMemberIdentifier ?: ""),
                            )
                            builder.add(buildDirective(RazorSyntaxKind.Identifier))
                            return
                        } else {
                            while (!at(RazorSyntaxKind.NewLine)) {
                                if (at(RazorSyntaxKind.Semicolon)) break
                                acceptAndMoveNext()
                                if (endOfFile) break
                            }
                        }
                    } else if (at(RazorSyntaxKind.Semicolon)) {
                        // nothing: the constraint is absent
                    } else {
                        context.errorSink.onError(RazorDiagnosticFactory.unexpectedIdentifier(SourceSpan(currentStart, current.content.length), current.content, "where"))
                        builder.add(buildDirective(RazorSyntaxKind.Keyword))
                        return
                    }
                    DirectiveTokenKind.IdentifierOrExpression -> if (at(RazorSyntaxKind.Transition) && nextIs(RazorSyntaxKind.LeftParenthesis)) {
                        acceptAndMoveNext()
                        directiveBuilder.add(outputAsMetaCode(output()))
                        directiveBuilder.add(parseExplicitExpressionBody())
                    } else {
                        val (ok, identifierLength) = tryParseQualifiedIdentifier()
                        if (!ok) {
                            context.errorSink.onError(RazorDiagnosticFactory.directiveExpectsIdentifierOrExpression(SourceSpan(currentStart, identifierLength), descriptor.directive))
                            builder.add(buildDirective(RazorSyntaxKind.Identifier))
                            return
                        }
                    }
                }
                chunkGenerator = ChunkGenerator.DirectiveToken(tokenDescriptor)
                setAcceptedCharacters(AcceptedCharacters.NON_WHITESPACE)
                editHandlerBuilder?.let { setupDirectiveTokenEditHandler(it) }
                directiveBuilder.add(outputTokensAsStatementLiteral())
            }
            acceptWhile(isSpacingTokenIncludingComments)
            chunkGenerator = ChunkGenerator.None
            when (descriptor.kind) {
                DirectiveKind.SingleLine -> {
                    setAcceptedCharacters(AcceptedCharacters.WHITESPACE)
                    directiveBuilder.add(outputTokensAsUnclassifiedLiteral())
                    tryAccept(RazorSyntaxKind.Semicolon)
                    directiveBuilder.add(outputAsMetaCode(output(), AcceptedCharacters.WHITESPACE))
                    acceptWhile(isSpacingTokenIncludingComments)
                    if (at(RazorSyntaxKind.NewLine)) {
                        acceptAndMoveNext()
                    } else if (!endOfFile) {
                        context.errorSink.onError(RazorDiagnosticFactory.unexpectedDirectiveLiteral(SourceSpan(currentStart, current.content.length), descriptor.directive, ERROR_COMPONENT_NEWLINE))
                    }
                    chunkGenerator = ChunkGenerator.None
                    setAcceptedCharacters(AcceptedCharacters.WHITESPACE)
                    directiveBuilder.add(outputAsMarkupEphemeralLiteral())
                }
                DirectiveKind.RazorBlock -> {
                    shouldCaptureWhitespaceToEndOfLine = true
                    acceptWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives)
                    setAcceptedCharacters(AcceptedCharacters.ALL_WHITESPACE)
                    directiveBuilder.add(outputTokensAsUnclassifiedLiteral())
                    parseDirectiveBlock(directiveBuilder, descriptor) { _, _ ->
                        val wasNested = isNested
                        isNested = false
                        pushSpanContextConfig().use {
                            endingBlock()
                            val razorBlock = htmlParser.parseRazorBlock("{" to "}")
                            directiveBuilder.add(razorBlock)
                            startingBlock()
                        }
                        initializeContext()
                        isNested = wasNested
                        nextToken()
                    }
                }
                DirectiveKind.CodeBlock -> {
                    shouldCaptureWhitespaceToEndOfLine = true
                    acceptWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives)
                    setAcceptedCharacters(AcceptedCharacters.ALL_WHITESPACE)
                    directiveBuilder.add(outputTokensAsUnclassifiedLiteral())
                    parseDirectiveBlock(directiveBuilder, descriptor) { childBuilder, startingBraceLocation ->
                        if (descriptor === RazorDirectives.documentation) {
                            parseDocumentationBody(childBuilder, startingBraceLocation.absoluteIndex + 1)
                            return@parseDirectiveBlock
                        }
                        nextToken()
                        val existingEditHandler = editHandlerBuilder
                        editHandlerBuilder?.let { setupCodeBlockEditHandler(it) }
                        if (context.options.allowRazorInAllCodeBlocks) {
                            parseCodeBlock(childBuilder, Block(descriptor.directive, directiveStart))
                        } else {
                            balance(childBuilder, BalancingModes.NO_ERROR_ON_FAILURE, RazorSyntaxKind.LeftBrace, RazorSyntaxKind.RightBrace, startingBraceLocation)
                        }
                        chunkGenerator = ChunkGenerator.Statement
                        acceptMarkerTokenIfNecessary()
                        childBuilder.add(outputTokensAsStatementLiteral())
                        editHandlerBuilder = existingEditHandler
                    }
                }
            }
        }
        builder.add(buildDirective(RazorSyntaxKind.Identifier))
        if (shouldCaptureWhitespaceToEndOfLine) {
            captureWhitespaceToEndOfLine()
            builder.add(outputAsMetaCode(output(), context.currentAcceptedCharacters))
        }
    }

    private fun validateDirectiveUsage(descriptor: DirectiveDescriptor, directiveStart: SourceLocation) {
        if (descriptor.usage == DirectiveUsage.FileScopedSinglyOccurring) {
            if (descriptor.directive in context.seenDirectives) {
                val errorLength = 1 + descriptor.directive.length
                context.errorSink.onError(RazorDiagnosticFactory.duplicateDirective(SourceSpan(directiveStart, errorLength), descriptor.directive))
            }
        }
    }

    /** `TryParseQualifiedIdentifier`: whether a dotted identifier is here (accepted then), and the length seen. */
    private fun tryParseQualifiedIdentifier(): Pair<Boolean, Int> {
        var currentIdentifierLength = 0
        var expectingDot = false
        val tokens = readWhile { token ->
            val type = token.kind
            if ((expectingDot && type == RazorSyntaxKind.Dot) || (!expectingDot && type == RazorSyntaxKind.Identifier)) {
                expectingDot = !expectingDot
                true
            } else {
                if (type != RazorSyntaxKind.Whitespace && type != RazorSyntaxKind.NewLine) {
                    expectingDot = false
                    currentIdentifierLength += token.content.length
                }
                false
            }
        }
        var identifierLength = currentIdentifierLength
        val validQualifiedIdentifier = expectingDot
        if (validQualifiedIdentifier) {
            for (token in tokens) {
                identifierLength += token.content.length
                accept(token)
            }
            return true to identifierLength
        } else {
            putCurrentBack()
            for (token in tokens) {
                identifierLength += token.content.length
                putBack(token)
            }
            ensureCurrent()
            return false to identifierLength
        }
    }

    private fun parseDocumentationBody(builder: RazorListBuilder, start: Int) {
        val source = context.text
        val end = findDocumentationEnd(start)
        val content = source.subSequence(start, end).toString()
        val terminator = content.indexOf("*/")
        if (terminator >= 0) {
            val absoluteIndex = start + terminator
            context.errorSink.onError(RazorDiagnosticFactory.documentationCommentTerminator(SourceSpan(context.source.locationOf(absoluteIndex), 2)))
        } else {
            val trimmedContent = content.trimStart()
            if (trimmedContent.isNotEmpty() && trimmedContent[0] != '<') {
                val absoluteIndex = start + content.length - trimmedContent.length
                context.errorSink.onError(RazorDiagnosticFactory.documentationShouldStartWithTag(SourceSpan(context.source.locationOf(absoluteIndex), 1)))
            }
        }
        builder.add(CSharpStatementLiteralSyntax(io.github.dotnetsupport.razor.lang.syntax.RazorList(listOf(RazorToken(RazorSyntaxKind.Text, content))), ChunkGenerator.Statement, editHandler = null))
        tokenizer.reset(end)
        nextToken()
    }

    private fun findDocumentationEnd(start: Int): Int {
        val source = context.text
        val end = HtmlMarkupParser.parseXmlBody(context, start)
        if (end < source.length) return end
        var recoveryPosition: Int? = null
        var recoveryDirectivePosition: Int? = null
        val lineStarts = context.source.lineStarts
        fun lineBounds(position: Int): Pair<Int, Int> {
            val line = context.source.lineOf(position)
            val lineStart = lineStarts[line]
            var lineEnd = if (line + 1 < lineStarts.size) lineStarts[line + 1] else source.length
            // `TextLine.End` excludes the line break
            while (lineEnd > lineStart && (source[lineEnd - 1] == '\n' || source[lineEnd - 1] == '\r')) lineEnd--
            return lineStart to lineEnd
        }
        fun considerRecoveryBrace(position: Int) {
            if (recoveryPosition != null || recoveryDirectivePosition != null) return
            val (_, lineEnd) = lineBounds(position)
            for (i in position + 1 until lineEnd) {
                if (!source[i].isWhitespace()) {
                    if (source[i] != '<' && source[i] != '@') return
                    break
                }
            }
            recoveryPosition = position
        }
        fun considerRecoveryDirective(position: Int) {
            if (recoveryDirectivePosition != null || source[position] != '@' || position + 1 == source.length) return
            val (lineStart, _) = lineBounds(position)
            for (i in lineStart until position) if (!source[i].isWhitespace()) return
            val nameStart = position + 1
            var nameEnd = nameStart
            while (nameEnd < source.length && CSharpLiteralScanner.isIdentifierPartCharacter(source[nameEnd])) nameEnd++
            if (source[nameStart] != '{') {
                val name = source.subSequence(nameStart, nameEnd).toString()
                if (!directiveParserMap.containsKey(name) && name !in keywords) return
            }
            recoveryDirectivePosition = position
        }
        for (position in start until end) {
            considerRecoveryDirective(position)
            if (source[position] == '}') considerRecoveryBrace(position)
        }
        return recoveryPosition ?: recoveryDirectivePosition ?: end
    }

    private fun parseDirectiveBlock(builder: RazorListBuilder, descriptor: DirectiveDescriptor, parseChildren: (RazorListBuilder, SourceLocation) -> Unit) {
        if (endOfFile) {
            context.errorSink.onError(RazorDiagnosticFactory.unexpectedEOFAfterDirective(SourceSpan(currentStart, 1), descriptor.directive, "{"))
        } else if (!at(RazorSyntaxKind.LeftBrace)) {
            context.errorSink.onError(RazorDiagnosticFactory.unexpectedDirectiveLiteral(SourceSpan(currentStart, current.content.length), descriptor.directive, "{"))
        } else {
            var autoCompleteStringAccessor: AutoCompleteEditHandler.AutoCompleteStringAccessor? = null
            editHandlerBuilder?.let { autoCompleteStringAccessor = setupAutoCompleteEditHandler(it, autoCompleteAtEndOfSpan = true) }
            val startingBraceLocation = currentStart
            accept(currentToken)
            builder.add(outputAsMetaCode(output()))
            val childBuilder = RazorListBuilder()
            parseChildren(childBuilder, startingBraceLocation)
            if (childBuilder.count > 0) builder.add(CSharpCodeBlockSyntax(childBuilder.toList()))
            chunkGenerator = ChunkGenerator.None
            val canAcceptCloseBrace: Boolean
            if (!tryAccept(RazorSyntaxKind.RightBrace)) {
                canAcceptCloseBrace = true
                context.errorSink.onError(RazorDiagnosticFactory.expectedEndOfBlockBeforeEOF(SourceSpan(startingBraceLocation, 1), descriptor.directive, "}", "{"))
                accept(RazorToken.missing(RazorSyntaxKind.RightBrace))
            } else {
                canAcceptCloseBrace = false
                setAcceptedCharacters(AcceptedCharacters.NONE)
            }
            autoCompleteStringAccessor?.canAcceptCloseBrace = canAcceptCloseBrace
            builder.add(outputAsMetaCode(output(), context.currentAcceptedCharacters))
        }
    }

    private fun tryParseKeyword(builder: RazorListBuilder, whitespace: List<RazorToken>, transition: CSharpTransitionSyntax?): Boolean {
        val keyword = tokenizer.tokenizer.getTokenKeyword(currentToken) ?: return false
        val handler = keywordParserMap[keyword] ?: return false
        accept(whitespace)
        builder.add(outputTokensAsStatementLiteral())
        handler(builder, transition)
        return true
    }

    private fun tryParseKeyword(builder: RazorListBuilder): Boolean {
        val keyword = tokenizer.tokenizer.getTokenKeyword(currentToken) ?: return false
        val handler = keywordParserMap[keyword] ?: return false
        handler(builder, null)
        return true
    }

    private fun atBooleanLiteral(): Boolean = tokenizer.tokenizer.getTokenKeyword(currentToken).let { it == "true" || it == "false" }

    private fun parseAwaitExpression(builder: RazorListBuilder, transition: CSharpTransitionSyntax?) {
        acceptAndMoveNext()
        acceptWhile(isSpacingTokenIncludingComments)
        val topLevel = transition != null
        if (!topLevel) return
        if (atKeyword("foreach")) {
            parseConditionalBlock(builder, transition)
        } else {
            val implicitExpressionBody = parseImplicitExpressionBody(async = true)
            builder.add(CSharpImplicitExpressionSyntax(transition!!, implicitExpressionBody))
        }
    }

    private fun parseConditionalBlock(builder: RazorListBuilder, transition: CSharpTransitionSyntax?) {
        parseConditionalBlock(builder, transition, topLevel = transition != null)
    }

    private fun parseConditionalBlock(builder: RazorListBuilder, transition: CSharpTransitionSyntax?, topLevel: Boolean) {
        if (transition != null) builder.add(transition)
        val block = Block(getBlockName(current), currentStart)
        parseConditionalBlock(builder, block)
        if (topLevel) completeBlock()
    }

    private fun parseConditionalBlock(builder: RazorListBuilder, block: Block) {
        acceptAndMoveNext()
        acceptWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives)
        if (tryParseCondition(builder)) {
            acceptWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives)
            parseExpectedCodeBlock(builder, block)
        }
    }

    private fun tryParseCondition(builder: RazorListBuilder): Boolean {
        if (at(RazorSyntaxKind.LeftParenthesis)) {
            val complete = balance(builder, BalancingModes.BACKTRACK_ON_FAILURE or BalancingModes.ALLOW_COMMENTS_AND_TEMPLATES)
            if (!complete) acceptUntil(RazorSyntaxKind.NewLine) else tryAccept(RazorSyntaxKind.RightParenthesis)
            return complete
        }
        return true
    }

    private fun parseExpectedCodeBlock(builder: RazorListBuilder, block: Block) {
        if (!endOfFile) {
            var encounteredUnexpectedMarkupTransition = false
            if (at(RazorSyntaxKind.LessThan)) {
                context.errorSink.onError(RazorDiagnosticFactory.singleLineControlFlowStatementsCannotContainMarkup(SourceSpan(currentStart, current.content.length)))
                encounteredUnexpectedMarkupTransition = true
            } else if (at(RazorSyntaxKind.Transition) && nextIs(RazorSyntaxKind.Colon)) {
                context.errorSink.onError(RazorDiagnosticFactory.singleLineControlFlowStatementsCannotContainMarkup(SourceSpan(currentStart, 2)))
                encounteredUnexpectedMarkupTransition = true
            } else if (at(RazorSyntaxKind.Transition) && nextIs(RazorSyntaxKind.Transition)) {
                context.errorSink.onError(RazorDiagnosticFactory.singleLineControlFlowStatementsCannotContainMarkup(SourceSpan(currentStart, 2)))
                encounteredUnexpectedMarkupTransition = true
            }
            parseStatement(builder, block, encounteredUnexpectedMarkupTransition)
        }
    }

    private fun parseUnconditionalBlock(builder: RazorListBuilder) {
        val block = Block(getBlockName(current), currentStart)
        acceptAndMoveNext()
        acceptWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives)
        parseExpectedCodeBlock(builder, block)
    }

    private fun parseCaseStatement(builder: RazorListBuilder, transition: CSharpTransitionSyntax?) {
        if (transition != null) builder.add(transition)
        acceptAndMoveNext()
        while (ensureCurrent() && current.kind != RazorSyntaxKind.Colon) {
            when (current.kind) {
                RazorSyntaxKind.LeftBrace, RazorSyntaxKind.LeftParenthesis, RazorSyntaxKind.LeftBracket -> balance(builder, BalancingModes.NONE)
                else -> acceptAndMoveNext()
            }
        }
        tryAccept(RazorSyntaxKind.Colon)
    }

    private fun parseIfStatement(builder: RazorListBuilder, transition: CSharpTransitionSyntax?) {
        parseConditionalBlock(builder, transition, topLevel = false)
        parseAfterIfClause(builder)
        if (transition != null) completeBlock()
    }

    private fun parseAfterIfClause(builder: RazorListBuilder) {
        val whitespace = ArrayList<RazorToken>()
        skipToNextImportantToken(builder, whitespace)
        if (atKeyword("else")) {
            accept(whitespace)
            parseElseClause(builder)
        } else {
            putCurrentBack()
            putBack(whitespace)
            setAcceptedCharacters(AcceptedCharacters.ANY)
        }
    }

    private fun parseElseClause(builder: RazorListBuilder) {
        if (!atKeyword("else")) return
        val block = Block(getBlockName(current), currentStart)
        acceptAndMoveNext()
        acceptWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives)
        if (atKeyword("if")) {
            block.name = SyntaxConstants.CSharp.ELSE_IF_KEYWORD
            parseConditionalBlock(builder, block)
            parseAfterIfClause(builder)
        } else if (!endOfFile) {
            parseExpectedCodeBlock(builder, block)
        }
    }

    private fun parseTryStatement(builder: RazorListBuilder, transition: CSharpTransitionSyntax?) {
        val topLevel = transition != null
        if (topLevel) builder.add(transition)
        parseUnconditionalBlock(builder)
        parseAfterTryClause(builder)
        if (topLevel) completeBlock()
    }

    private fun parseAfterTryClause(builder: RazorListBuilder) {
        val whitespace = ArrayList<RazorToken>()
        skipToNextImportantToken(builder, whitespace)
        if (atKeyword("catch")) {
            accept(whitespace)
            parseFilterableCatchBlock(builder)
            parseAfterTryClause(builder)
        } else if (atKeyword("finally")) {
            accept(whitespace)
            parseUnconditionalBlock(builder)
        } else {
            putCurrentBack()
            putBack(whitespace)
            setAcceptedCharacters(AcceptedCharacters.ANY)
        }
    }

    private fun parseFilterableCatchBlock(builder: RazorListBuilder) {
        val block = Block(getBlockName(current), currentStart)
        acceptAndMoveNext()
        acceptWhile(isValidStatementSpacingToken)
        if (tryParseCondition(builder)) {
            acceptWhile(isValidStatementSpacingToken)
            if (atKeyword("when")) {
                acceptAndMoveNext()
                acceptWhile(isValidStatementSpacingToken)
                if (!tryParseCondition(builder)) return
                acceptWhile(isValidStatementSpacingToken)
            }
            parseExpectedCodeBlock(builder, block)
        }
    }

    private fun parseDoStatement(builder: RazorListBuilder, transition: CSharpTransitionSyntax?) {
        if (transition != null) builder.add(transition)
        parseUnconditionalBlock(builder)
        parseWhileClause(builder)
        if (transition != null) completeBlock()
    }

    private fun parseWhileClause(builder: RazorListBuilder) {
        setAcceptedCharacters(AcceptedCharacters.ANY)
        val whitespace = ArrayList<RazorToken>()
        skipToNextImportantToken(builder, whitespace)
        if (atKeyword("while")) {
            accept(whitespace)
            acceptAndMoveNext()
            acceptWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives)
            if (tryParseCondition(builder) && tryAccept(RazorSyntaxKind.Semicolon)) setAcceptedCharacters(AcceptedCharacters.NONE)
        } else {
            putCurrentBack()
            putBack(whitespace)
        }
    }

    private fun parseUsingKeyword(builder: RazorListBuilder, transition: CSharpTransitionSyntax?) {
        val topLevel = transition != null
        val block = Block(getBlockName(current), currentStart)
        val usingToken = eatCurrentToken()
        val whitespaceOrComments = readWhile(isSpacingTokenIncludingComments)
        val atLeftParen = at(RazorSyntaxKind.LeftParenthesis)
        val atIdentifier = at(RazorSyntaxKind.Identifier)
        val atStaticOrGlobal = atKeyword("static", "global")
        putCurrentBack()
        putBack(whitespaceOrComments)
        putBack(usingToken)
        ensureCurrent()
        if (atLeftParen) {
            parseUsingStatement(builder, transition, block)
        } else if (atIdentifier || atStaticOrGlobal) {
            if (!topLevel) {
                if (!context.options.allowUsingVariableDeclarations) {
                    context.errorSink.onError(RazorDiagnosticFactory.namespaceImportAndTypeAliasCannotExistWithinCodeBlock(SourceSpan(block.start, block.name.length)))
                }
                if (transition != null) builder.add(transition)
                acceptAndMoveNext()
                acceptWhile(isSpacingTokenIncludingComments)
                parseStandardStatement(builder, encounteredUnexpectedMarkupTransition = false)
            } else {
                parseUsingDeclaration(builder, transition!!)
                return
            }
        } else {
            if (transition != null) builder.add(transition)
            acceptAndMoveNext()
            acceptWhile(isSpacingTokenIncludingComments)
        }
        if (topLevel) completeBlock()
    }

    private fun parseUsingStatement(builder: RazorListBuilder, transition: CSharpTransitionSyntax?, block: Block) {
        acceptAndMoveNext()
        acceptWhile(isSpacingTokenIncludingComments)
        if (transition != null) builder.add(transition)
        if (tryParseCondition(builder)) {
            acceptWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives)
            parseExpectedCodeBlock(builder, block)
        }
    }

    private fun parseUsingDeclaration(builder: RazorListBuilder, transition: CSharpTransitionSyntax) {
        val directiveBuilder = RazorListBuilder()
        acceptAndMoveNext()
        var isStatic = false
        var nonNamespaceTokenCount = tokenBuilder.size
        acceptWhile(isSpacingTokenIncludingComments)
        if (at(RazorSyntaxKind.Identifier) || atKeyword("global")) {
            nonNamespaceTokenCount = tokenBuilder.size
            tryParseNamespaceOrTypeName(directiveBuilder)
            val whitespace = readWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives)
            if (at(RazorSyntaxKind.Assign)) {
                accept(whitespace)
                acceptAndMoveNext()
                acceptWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives)
                tryParseNamespaceOrTypeName(directiveBuilder)
            } else {
                putCurrentBack()
                putBack(whitespace)
            }
        } else if (atKeyword("static")) {
            isStatic = true
            acceptAndMoveNext()
            acceptWhile(isSpacingTokenIncludingComments)
            nonNamespaceTokenCount = tokenBuilder.size
            tryParseNamespaceOrTypeName(directiveBuilder)
        }
        val usingStatementTokens = ArrayList(tokenBuilder)
        setAcceptedCharacters(AcceptedCharacters.ANY_EXCEPT_NEWLINE)
        var hasExplicitSemicolon = false
        if (ensureCurrent()) hasExplicitSemicolon = tryAccept(RazorSyntaxKind.Semicolon)
        val usingContent = StringBuilder()
        val parsedNamespace = StringBuilder()
        for (i in usingStatementTokens.indices) {
            val token = usingStatementTokens[i]
            if (i >= 1) usingContent.append(token.content)
            if (i >= nonNamespaceTokenCount && token.kind != RazorSyntaxKind.CSharpComment && token.kind != RazorSyntaxKind.Whitespace && token.kind != RazorSyntaxKind.NewLine) {
                parsedNamespace.append(token.content)
            }
        }
        chunkGenerator = ChunkGenerator.AddImport(usingContent.toString().trim(), parsedNamespace.toString(), isStatic, hasExplicitSemicolon)
        val keywordTokens = outputTokensAsStatementLiteral()
        val directiveBody = RazorDirectiveBodySyntax(keywordTokens!!, null)
        builder.add(RazorUsingDirectiveSyntax(transition, directiveBody))
        captureWhitespaceToEndOfLine()
        builder.add(outputAsMetaCode(output(), context.currentAcceptedCharacters))
    }

    private fun tryParseNamespaceOrTypeName(builder: RazorListBuilder): Boolean {
        if (tryAccept(RazorSyntaxKind.LeftParenthesis)) {
            while (!tryAccept(RazorSyntaxKind.RightParenthesis) && !endOfFile) {
                tryAccept(RazorSyntaxKind.Whitespace)
                if (!tryParseNamespaceOrTypeName(builder)) return false
                tryAccept(RazorSyntaxKind.Whitespace)
                tryAccept(RazorSyntaxKind.Identifier)
                tryAccept(RazorSyntaxKind.Whitespace)
                tryAccept(RazorSyntaxKind.Comma)
            }
            if (at(RazorSyntaxKind.Whitespace) && nextIs(RazorSyntaxKind.QuestionMark)) acceptAndMoveNext()
            tryAccept(RazorSyntaxKind.QuestionMark)
            return true
        } else if (tryAccept(RazorSyntaxKind.Identifier) || tryAccept(RazorSyntaxKind.Keyword)) {
            if (tryAccept(RazorSyntaxKind.DoubleColon)) {
                if (!tryAccept(RazorSyntaxKind.Identifier)) tryAccept(RazorSyntaxKind.Keyword)
            }
            if (at(RazorSyntaxKind.LessThan)) parseTypeArgumentList(builder)
            if (tryAccept(RazorSyntaxKind.Dot)) tryParseNamespaceOrTypeName(builder)
            if (at(RazorSyntaxKind.Whitespace) && nextIs(RazorSyntaxKind.QuestionMark)) acceptAndMoveNext()
            tryAccept(RazorSyntaxKind.QuestionMark)
            if (at(RazorSyntaxKind.Whitespace) && nextIs(RazorSyntaxKind.LeftBracket)) acceptAndMoveNext()
            while (at(RazorSyntaxKind.LeftBracket)) {
                balance(builder, BalancingModes.NONE)
                if (!tryAccept(RazorSyntaxKind.RightBracket)) accept(RazorToken.missing(RazorSyntaxKind.RightBracket))
            }
            return true
        } else {
            return false
        }
    }

    private fun parseTypeArgumentList(builder: RazorListBuilder) {
        balance(builder, BalancingModes.NONE)
        if (!tryAccept(RazorSyntaxKind.GreaterThan)) accept(RazorToken.missing(RazorSyntaxKind.GreaterThan))
    }

    private fun parseReservedDirective(builder: RazorListBuilder, transition: CSharpTransitionSyntax) {
        context.errorSink.onError(RazorDiagnosticFactory.reservedWord(SourceSpan(currentStart, current.content.length), current.content))
        acceptAndMoveNext()
        setAcceptedCharacters(AcceptedCharacters.NONE)
        chunkGenerator = ChunkGenerator.None
        completeBlock()
        val keyword = outputAsMetaCode(output())
        val directiveBody = RazorDirectiveBodySyntax(keyword!!, csharpCode = null)
        builder.add(RazorDirectiveSyntax(transition, directiveBody))
    }

    private fun completeBlock() {
        acceptMarkerTokenIfNecessary()
        captureWhitespaceToEndOfLine()
    }

    private fun captureWhitespaceToEndOfLine() {
        ensureCurrent()
        if (!context.whiteSpaceIsSignificantToAncestorBlock && !isNested) {
            val whitespace = readWhile { it.kind == RazorSyntaxKind.Whitespace }
            if (at(RazorSyntaxKind.NewLine)) {
                accept(whitespace)
                acceptAndMoveNext()
                putCurrentBack()
            } else {
                putCurrentBack()
                putBack(whitespace)
            }
        } else {
            putCurrentBack()
        }
    }

    private fun skipToNextImportantToken(builder: RazorListBuilder, whitespace: MutableList<RazorToken>) {
        while (!endOfFile) {
            readWhile(isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives, whitespace)
            if (at(RazorSyntaxKind.RazorCommentTransition)) {
                accept(whitespace)
                setAcceptedCharacters(AcceptedCharacters.ANY)
                acceptMarkerTokenIfNecessary()
                builder.add(outputTokensAsStatementLiteral())
                builder.add(parseRazorComment())
            } else {
                return
            }
            whitespace.clear()
        }
    }

    private fun defaultSpanContextConfig(editHandlerBuilder: SpanEditHandlerBuilder?): ChunkGenerator {
        setAcceptedCharacters(AcceptedCharacters.ANY)
        if (editHandlerBuilder != null) {
            editHandlerBuilder.reset()
            editHandlerBuilder.hasTokenizer = true
        }
        return ChunkGenerator.Statement
    }

    private fun explicitExpressionSpanContextConfig(editHandlerBuilder: SpanEditHandlerBuilder?): ChunkGenerator {
        setAcceptedCharacters(AcceptedCharacters.ANY)
        if (editHandlerBuilder != null) {
            editHandlerBuilder.reset()
            editHandlerBuilder.hasTokenizer = true
        }
        return ChunkGenerator.Expression
    }

    private fun outputTokensAsStatementLiteral(): CSharpStatementLiteralSyntax? {
        val tokens = output()
        if (tokens.size == 0) return null
        return CSharpStatementLiteralSyntax(tokens, chunkGenerator, getEditHandler())
    }

    private fun outputTokensAsExpressionLiteral(): CSharpExpressionLiteralSyntax? {
        val tokens = output()
        if (tokens.size == 0) return null
        return CSharpExpressionLiteralSyntax(tokens, chunkGenerator, getEditHandler())
    }

    private fun outputTokensAsEphemeralLiteral(): CSharpEphemeralTextLiteralSyntax? {
        val tokens = output()
        if (tokens.size == 0) return null
        return CSharpEphemeralTextLiteralSyntax(tokens, chunkGenerator, getEditHandler())
    }

    private fun outputTokensAsUnclassifiedLiteral(): UnclassifiedTextLiteralSyntax? {
        val tokens = output()
        if (tokens.size == 0) return null
        return UnclassifiedTextLiteralSyntax(tokens, chunkGenerator, getEditHandler())
    }

    private fun otherParserBlock(builder: RazorListBuilder) {
        val wasNested = isNested
        isNested = false
        endingBlock()
        val htmlBlock: RazorNode?
        pushSpanContextConfig().use {
            htmlBlock = htmlParser.parseBlock()
        }
        builder.add(htmlBlock)
        initializeContext()
        startingBlock()
        isNested = wasNested
        nextToken()
    }

    private fun balance(builder: RazorListBuilder, mode: Int): Boolean {
        val left = current.kind
        val right = language.flipBracket(left)
        val start = currentStart
        acceptAndMoveNext()
        if (endOfFile && (mode and BalancingModes.NO_ERROR_ON_FAILURE) != BalancingModes.NO_ERROR_ON_FAILURE) {
            context.errorSink.onError(RazorDiagnosticFactory.expectedCloseBracketBeforeEOF(SourceSpan(start, 1), language.getSample(left), language.getSample(right)))
        }
        return balance(builder, mode, left, right, start)
    }

    private fun balance(builder: RazorListBuilder, mode: Int, left: RazorSyntaxKind, right: RazorSyntaxKind, start: SourceLocation): Boolean {
        var startPosition = currentStart.absoluteIndex
        var nesting = 1
        val stopAtEndOfLine = (mode and BalancingModes.STOP_AT_END_OF_LINE) == BalancingModes.STOP_AT_END_OF_LINE
        if (!endOfFile && !(stopAtEndOfLine && at(RazorSyntaxKind.NewLine))) {
            val tokens = ArrayList<RazorToken>()
            do {
                if (isAtEmbeddedTransition((mode and BalancingModes.ALLOW_COMMENTS_AND_TEMPLATES) == BalancingModes.ALLOW_COMMENTS_AND_TEMPLATES)) {
                    accept(tokens)
                    tokens.clear()
                    parseEmbeddedTransition(builder)
                    startPosition = currentStart.absoluteIndex
                    continue
                }
                if (at(RazorSyntaxKind.Transition)) {
                    if (nextIs(RazorSyntaxKind.Transition) && lookahead(2).let { it != null && (it.kind == RazorSyntaxKind.Identifier || it.kind == RazorSyntaxKind.Keyword) }) {
                        accept(tokens)
                        tokens.clear()
                        builder.add(outputTokensAsStatementLiteral())
                        acceptAndMoveNext()
                        builder.add(outputTokensAsEphemeralLiteral())
                        startPosition = currentStart.absoluteIndex
                        continue
                    } else if (nextIs(RazorSyntaxKind.Keyword, RazorSyntaxKind.Identifier)) {
                        tokens.add(nextAsEscapedIdentifier())
                        continue
                    }
                }
                if ((mode and BalancingModes.PARSE_NESTED_BLOCKS_AS_MARKUP) == BalancingModes.PARSE_NESTED_BLOCKS_AS_MARKUP && at(left)) {
                    accept(tokens)
                    tokens.clear()
                    acceptAndMoveNext()
                    builder.add(outputTokensAsStatementLiteral())
                    parseCodeBlock(builder, Block(BLOCK_NAME_CODE, currentStart))
                    if (at(right)) acceptAndMoveNext()
                    startPosition = currentStart.absoluteIndex
                    continue
                }
                if (at(left)) nesting++ else if (at(right)) nesting--
                if (nesting > 0) {
                    tokens.add(current)
                    nextToken()
                }
            } while (nesting > 0 && ensureCurrent() && !(stopAtEndOfLine && at(RazorSyntaxKind.NewLine)))
            if (nesting > 0) {
                if ((mode and BalancingModes.NO_ERROR_ON_FAILURE) != BalancingModes.NO_ERROR_ON_FAILURE) {
                    context.errorSink.onError(RazorDiagnosticFactory.expectedCloseBracketBeforeEOF(SourceSpan(start, 1), language.getSample(left), language.getSample(right)))
                }
                if ((mode and BalancingModes.BACKTRACK_ON_FAILURE) == BalancingModes.BACKTRACK_ON_FAILURE) {
                    tokenizer.reset(startPosition)
                    nextToken()
                } else {
                    accept(tokens)
                }
            } else {
                accept(tokens)
            }
        }
        return nesting == 0
    }

    private fun isAtEmbeddedTransition(allowTemplatesAndComments: Boolean): Boolean =
        allowTemplatesAndComments && ((language.isTransition(currentToken) && nextIs(RazorSyntaxKind.LessThan, RazorSyntaxKind.Colon, RazorSyntaxKind.DoubleColon)) || language.isCommentStart(currentToken))

    private fun parseEmbeddedTransition(builder: RazorListBuilder) {
        if (language.isTransition(currentToken)) {
            putCurrentBack()
            parseTemplate(builder)
        } else if (language.isCommentStart(currentToken)) {
            acceptMarkerTokenIfNecessary()
            if (chunkGenerator is ChunkGenerator.Expression) builder.add(outputTokensAsExpressionLiteral()) else builder.add(outputTokensAsStatementLiteral())
            builder.add(parseRazorComment())
        }
    }

    private fun nextAsEscapedIdentifier(): RazorToken {
        val transition = current
        nextToken()
        val identifier = current
        nextToken()
        return RazorToken(RazorSyntaxKind.Identifier, transition.content + identifier.content)
    }

    private fun atKeyword(vararg keywords: String): Boolean {
        if (!at(RazorSyntaxKind.Keyword)) return false
        val keyword = tokenizer.tokenizer.getTokenKeyword(currentToken) ?: return false
        return keyword in keywords
    }

    private fun getBlockName(token: RazorToken): String {
        val keyword = tokenizer.tokenizer.getTokenKeyword(token)
        if (keyword != null && token.kind == RazorSyntaxKind.Keyword) return keyword
        return token.content
    }

    private fun setupImplicitExpressionEditHandler(builder: SpanEditHandlerBuilder, acceptTrailingDot: Boolean, keywords: KeywordSet) {
        builder.hasTokenizer = true
        builder.factory = { accepted -> ImplicitExpressionEditHandler(accepted, acceptTrailingDot, keywords) }
    }

    private fun setupCodeBlockEditHandler(builder: SpanEditHandlerBuilder) {
        builder.factory = { accepted -> CodeBlockEditHandler(accepted) }
    }

    private fun setupDirectiveTokenEditHandler(builder: SpanEditHandlerBuilder) {
        builder.factory = { accepted -> DirectiveTokenEditHandler(accepted) }
    }

    private fun setupAutoCompleteEditHandler(builder: SpanEditHandlerBuilder, autoCompleteAtEndOfSpan: Boolean): AutoCompleteEditHandler.AutoCompleteStringAccessor {
        val accessor = AutoCompleteEditHandler.AutoCompleteStringAccessor()
        builder.factory = { accepted -> AutoCompleteEditHandler(accepted, accessor, autoCompleteAtEndOfSpan) }
        return accessor
    }

    private class Block(var name: String, val start: SourceLocation)

    private object BalancingModes {
        const val NONE = 0
        const val BACKTRACK_ON_FAILURE = 1
        const val NO_ERROR_ON_FAILURE = 2
        const val ALLOW_COMMENTS_AND_TEMPLATES = 4
        const val ALLOW_EMBEDDED_TRANSITIONS = 8
        const val STOP_AT_END_OF_LINE = 16
        const val PARSE_NESTED_BLOCKS_AS_MARKUP = 32
    }

    companion object {
        private const val BLOCK_NAME_CODE = "code"
        private const val BLOCK_NAME_EXPLICIT_EXPRESSION = "explicit expression"
        private const val ERROR_COMPONENT_NEWLINE = "line break"
        private val INVALID_NON_WHITESPACE_NAME_CHARACTERS = setOf('@', '!', '<', '/', '?', '[', '>', ']', '=', '"', '\'', '*')
        private val isValidStatementSpacingToken = isSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives

        /** `s_defaultKeywords`: the keywords an implicit expression stops at before the directives are added (`K14` of the baselines). */
        val DEFAULT_KEYWORDS: List<String> = listOf(
            SyntaxConstants.CSharp.TAG_HELPER_PREFIX_KEYWORD, SyntaxConstants.CSharp.ADD_TAG_HELPER_KEYWORD, SyntaxConstants.CSharp.REMOVE_TAG_HELPER_KEYWORD,
            "if", "do", "try", "for", "foreach", "while", "switch", "lock", "using", "namespace", "class", "where",
        )
    }
}
