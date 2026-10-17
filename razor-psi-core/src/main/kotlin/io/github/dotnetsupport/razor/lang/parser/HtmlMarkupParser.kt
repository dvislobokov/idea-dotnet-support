package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.razor.lang.syntax.AcceptedCharacters
import io.github.dotnetsupport.razor.lang.syntax.ChunkGenerator
import io.github.dotnetsupport.razor.lang.syntax.GenericBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupAttributeBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupCommentBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupDynamicAttributeValueSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupElementSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupEndTagSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupLiteralAttributeValueSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupMinimizedAttributeBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupMiscAttributeContentSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupStartTagSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupTransitionSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticFactory
import io.github.dotnetsupport.razor.lang.syntax.RazorDocumentSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorList
import io.github.dotnetsupport.razor.lang.syntax.RazorListBuilder
import io.github.dotnetsupport.razor.lang.syntax.RazorNode
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxNode
import io.github.dotnetsupport.razor.lang.syntax.RazorToken
import io.github.dotnetsupport.razor.lang.syntax.SourceLocation
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import io.github.dotnetsupport.razor.lang.syntax.SpanEditHandlerBuilder
import io.github.dotnetsupport.razor.lang.syntax.SyntaxConstants

/**
 * `HtmlMarkupParser`: the markup side of the Razor parser — elements with their tracking of open tags, attributes (literal, dynamic,
 * conditional), comments, CDATA, `<text>`, `@:` lines, the switch to the code parser at `@`. A port of the compiler's class, method by
 * method; the comments of the original are not repeated.
 */
class HtmlMarkupParser private constructor(context: ParserContext, private val parseAsXml: Boolean) : TokenizerBackedParser<HtmlTokenizer>(if (!parseAsXml && context.options.parseLeadingDirectives) FirstDirectiveHtmlLanguageCharacteristics else HtmlLanguageCharacteristics, context) {
    constructor(context: ParserContext) : this(context, parseAsXml = false)

    init {
        tokenizer.tokenizer.ignoreRazorTransitions = parseAsXml
    }

    private var tagTracker = ArrayDeque<TagTracker>()

    lateinit var codeParser: CSharpCodeParser

    private val currentTracker: TagTracker? get() = tagTracker.lastOrNull()
    private val currentStartTagName: String? get() = currentTracker?.tagName

    private fun tagNamesEqual(a: String?, b: String?): Boolean = if (parseAsXml) a == b else a.equals(b, ignoreCase = true)

    fun parseDocument(): RazorDocumentSyntax {
        val builder = RazorListBuilder()
        pushSpanContextConfig(::defaultMarkupSpanContext).use {
            nextToken()
            parseMarkupNodes(builder, ParseMode.Markup)
            acceptMarkerTokenIfNecessary()
            builder.add(outputAsMarkupLiteral())
            while (tagTracker.isNotEmpty()) {
                val tracker = tagTracker.removeLast()
                if (isVoidElement(tracker.tagName)) {
                    val children = builder.consume()
                    val voidElement = MarkupElementSyntax(tracker.startTag, RazorList.EMPTY, markupEndTag = null)
                    builder.addRange(tracker.previousNodes)
                    builder.add(voidElement)
                    builder.addRange(children)
                } else {
                    val element = MarkupElementSyntax(tracker.startTag, builder.consume(), markupEndTag = null)
                    builder.addRange(tracker.previousNodes)
                    builder.add(element)
                }
            }
            val markup = MarkupBlockSyntax(builder.toList())
            return RazorDocumentSyntax(markup, RazorToken(RazorSyntaxKind.EndOfFile, ""))
        }
    }

    fun parseBlock(): MarkupBlockSyntax? {
        val oldTagTracker = tagTracker
        try {
            tagTracker = ArrayDeque()
            val builder = RazorListBuilder()
            pushSpanContextConfig(::defaultMarkupSpanContext).use {
                if (!nextToken()) return null
                acceptWhile(isSpacingTokenIncludingNewLines)
                builder.add(outputAsMarkupLiteral())
                if (at(RazorSyntaxKind.OpenAngle)) {
                    parseMarkupInCodeBlock(builder)
                } else if (at(RazorSyntaxKind.Transition)) {
                    parseMarkupTransition(builder)
                } else {
                    context.errorSink.onError(RazorDiagnosticFactory.markupBlockMustStartWithTag(SourceSpan(currentStart, current.content.length)))
                }
                builder.add(outputAsMarkupLiteral())
                return MarkupBlockSyntax(builder.toList())
            }
        } finally {
            tagTracker = oldTagTracker
        }
    }

    fun parseRazorBlock(nestingSequences: Pair<String, String>): MarkupBlockSyntax {
        val oldTagTracker = tagTracker
        try {
            tagTracker = ArrayDeque()
            val builder = RazorListBuilder()
            pushSpanContextConfig(::defaultMarkupSpanContext).use {
                nextToken()
                nestingBlock(builder, nestingSequences)
                acceptMarkerTokenIfNecessary()
                builder.add(outputAsMarkupLiteral())
                return MarkupBlockSyntax(builder.toList())
            }
        } finally {
            tagTracker = oldTagTracker
        }
    }

    private fun parseMarkupNodes(builder: RazorListBuilder, mode: ParseMode, stopCondition: ((RazorToken) -> Boolean)? = null) {
        val stop = stopCondition ?: { false }
        while (!endOfFile && !stop(current)) parseMarkupNode(builder, mode)
    }

    private fun parseMarkupNode(builder: RazorListBuilder, mode: ParseMode) {
        when (getParserState(mode)) {
            ParserState.MarkupText -> parseMarkupText(builder)
            ParserState.Tag -> parseMarkupElement(builder, mode)
            ParserState.SpecialTag -> parseSpecialTag(builder)
            ParserState.XmlPI -> parseXmlPI(builder)
            ParserState.CData -> parseCData(builder)
            ParserState.MarkupComment -> parseMarkupComment(builder)
            ParserState.RazorComment -> parseRazorCommentWithLeadingAndTrailingWhitespace(builder)
            ParserState.DoubleTransition -> parseDoubleTransition(builder)
            ParserState.CodeTransition -> parseCodeTransition(builder)
            ParserState.Misc -> parseMisc(builder)
            ParserState.Unknown -> acceptAndMoveNext()
            ParserState.EOF -> builder.add(outputAsMarkupLiteral())
        }
    }

    private fun parseMarkupText(builder: RazorListBuilder) {
        acceptAndMoveNext()
    }

    private fun parseMarkupInCodeBlock(builder: RazorListBuilder) {
        do {
            when (getParserState(ParseMode.MarkupInCodeBlock)) {
                ParserState.EOF -> {}
                ParserState.Tag -> parseMarkupElement(builder, ParseMode.MarkupInCodeBlock)
                ParserState.SpecialTag, ParserState.XmlPI, ParserState.MarkupComment, ParserState.CData -> {
                    parseMarkupNode(builder, ParseMode.MarkupInCodeBlock)
                    setAcceptedCharacters(AcceptedCharacters.NONE)
                    builder.add(outputAsMarkupLiteral())
                }
                else -> parseMarkupNode(builder, ParseMode.Text)
            }
        } while (!endOfFile && tagTracker.isNotEmpty())
        completeMarkupInCodeBlock(builder)
    }

    private fun completeMarkupInCodeBlock(builder: RazorListBuilder) {
        builder.add(outputAsMarkupLiteral())
        var isOuterTagWellFormed = true
        while (tagTracker.isNotEmpty()) {
            val tracker = tagTracker.removeLast()
            val element = MarkupElementSyntax(tracker.startTag, builder.consume(), markupEndTag = null)
            builder.addRange(tracker.previousNodes)
            builder.add(element)
            if (tagTracker.isEmpty()) {
                isOuterTagWellFormed = tracker.isWellFormed
                if (isOuterTagWellFormed) {
                    context.errorSink.onError(RazorDiagnosticFactory.missingEndTag(SourceSpan(SourceLocationTracker.advance(tracker.tagLocation, "<"), tracker.tagName.length), tracker.tagName))
                }
            }
        }
        var shouldAcceptWhitespaceAndNewLine = true
        val previousSpan = if (builder.count > 0) getLastSpan(builder[builder.count - 1]) else null
        if (previousSpan != null && ((previousSpan is MarkupStartTagSyntax && previousSpan.isMarkupTransition) || (previousSpan is MarkupEndTagSyntax && previousSpan.isMarkupTransition))) {
            val tokens = readWhile { it.kind == RazorSyntaxKind.Whitespace || it.kind == RazorSyntaxKind.NewLine }
            if (!(at(RazorSyntaxKind.OpenAngle) || (at(RazorSyntaxKind.Transition) && lookahead(1)?.content?.startsWith(":") == true))) {
                shouldAcceptWhitespaceAndNewLine = false
            }
            putCurrentBack()
            putBack(tokens)
            ensureCurrent()
        }
        if (shouldAcceptWhitespaceAndNewLine) {
            acceptWhile(RazorSyntaxKind.Whitespace)
            tryAccept(RazorSyntaxKind.NewLine)
            if (isOuterTagWellFormed) setAcceptedCharacters(AcceptedCharacters.NONE)
        }
        putCurrentBack()
        if (!isOuterTagWellFormed) acceptMarkerTokenIfNecessary()
        builder.add(outputAsMarkupLiteral())
    }

    private fun parseMarkupTransition(builder: RazorListBuilder) {
        acceptAndMoveNext()
        setAcceptedCharacters(AcceptedCharacters.NONE)
        chunkGenerator = ChunkGenerator.None
        val transition = MarkupTransitionSyntax(output(), chunkGenerator, getEditHandler())
        builder.add(transition)
        val token = currentToken
        if (token != null && token.kind == RazorSyntaxKind.Text && token.content.isNotEmpty() && token.content[0] == ':') {
            val (left, right) = language.splitToken(token, 1, RazorSyntaxKind.Colon)
            accept(left)
            chunkGenerator = ChunkGenerator.None
            builder.add(outputAsMetaCode(output(), AcceptedCharacters.ANY))
            if (right != null) accept(right)
            nextToken()
            parseSingleLineMarkup(builder)
        } else if (token != null && token.kind == RazorSyntaxKind.OpenAngle) {
            parseMarkupInCodeBlock(builder)
        }
    }

    private fun parseSingleLineMarkup(builder: RazorListBuilder) {
        val old = context.whiteSpaceIsSignificantToAncestorBlock
        context.whiteSpaceIsSignificantToAncestorBlock = true
        setAcceptedCharacters(AcceptedCharacters.ANY)
        editHandlerBuilder?.let {
            it.reset()
            it.hasTokenizer = true
        }
        do {
            parseMarkupNodes(builder, ParseMode.Text) { it.kind == RazorSyntaxKind.Whitespace || it.kind == RazorSyntaxKind.NewLine }
            if (at(RazorSyntaxKind.Whitespace)) acceptAndMoveNext()
        } while (!endOfFile && current.kind != RazorSyntaxKind.NewLine)
        context.nullGenerateWhitespaceAndNewLine = false
        if (!endOfFile && current.kind == RazorSyntaxKind.NewLine) {
            acceptAndMoveNext()
            setAcceptedCharacters(AcceptedCharacters.NONE)
        }
        putCurrentBack()
        context.whiteSpaceIsSignificantToAncestorBlock = old
        builder.add(outputAsMarkupLiteral())
    }

    private fun parseMarkupElement(builder: RazorListBuilder, mode: ParseMode) {
        builder.add(outputAsMarkupLiteral())
        if (!nextIs(RazorSyntaxKind.ForwardSlash)) {
            val tagStart = currentStart
            val result = parseStartTag(mode, tagStart)
            val startTag = result.startTag
            if (result.tagMode == MarkupTagMode.Script) {
                val acceptedCharacters = if (mode == ParseMode.MarkupInCodeBlock) AcceptedCharacters.NONE else AcceptedCharacters.ANY
                parseJavascriptAndEndScriptTag(builder, startTag, acceptedCharacters)
                return
            }
            if (result.tagMode == MarkupTagMode.SelfClosing || result.tagMode == MarkupTagMode.Invalid || result.tagMode == MarkupTagMode.Void) {
                builder.add(MarkupElementSyntax(startTag, RazorList.EMPTY, markupEndTag = null))
                return
            } else {
                tagTracker.addLast(TagTracker(result.tagName, startTag, tagStart, builder.consume(), result.isWellFormed))
                return
            }
        } else {
            val endTagStart = currentStart
            val (endTag, endTagName) = parseEndTag(mode)
            val matchesStartTag = tagNamesEqual(currentStartTagName, endTagName)
            if (matchesStartTag) {
                val tracker = tagTracker.removeLast()
                val element = MarkupElementSyntax(tracker.startTag, builder.consume(), endTag)
                builder.addRange(tracker.previousNodes)
                builder.add(element)
                return
            } else {
                if (!tryRecoverStartTag(builder, endTagName, endTag)) {
                    builder.add(MarkupElementSyntax(markupStartTag = null, body = RazorList.EMPTY, markupEndTag = endTag))
                    if (mode == ParseMode.MarkupInCodeBlock) completeEndTag(builder, endTagName, endTagStart, endTag)
                }
            }
        }
    }

    private fun completeEndTag(builder: RazorListBuilder, endTagName: String, endTagStartLocation: SourceLocation, endTag: MarkupEndTagSyntax) {
        if (tagTracker.isEmpty()) {
            context.errorSink.onError(RazorDiagnosticFactory.unexpectedEndTag(SourceSpan(SourceLocationTracker.advance(endTagStartLocation, "</"), maxOf(endTagName.length, 1)), endTagName))
            return
        }
        while (tagTracker.isNotEmpty()) {
            val tracker = tagTracker.removeLast()
            val unclosedElement = MarkupElementSyntax(tracker.startTag, builder.consume(), markupEndTag = null)
            builder.addRange(tracker.previousNodes)
            builder.add(unclosedElement)
            if (tagTracker.isEmpty()) {
                context.errorSink.onError(RazorDiagnosticFactory.missingEndTag(SourceSpan(SourceLocationTracker.advance(tracker.tagLocation, "<"), tracker.tagName.length), tracker.tagName))
            }
        }
    }

    private fun tryRecoverStartTag(builder: RazorListBuilder, endTagName: String, endTag: MarkupEndTagSyntax): Boolean {
        while (!parseAsXml && tagTracker.isNotEmpty() && !tagNamesEqual(currentStartTagName, endTagName) && isVoidElement(currentStartTagName)) {
            val tracker = tagTracker.removeLast()
            val children = builder.consume()
            val voidElement = MarkupElementSyntax(tracker.startTag, RazorList.EMPTY, markupEndTag = null)
            builder.addRange(tracker.previousNodes)
            builder.add(voidElement)
            builder.addRange(children)
        }
        var malformedTagCount = 0
        // the compiler walks the stack from the top (the innermost tag)
        for (tag in tagTracker.asReversed()) {
            if (tagNamesEqual(tag.tagName, endTagName)) break
            malformedTagCount++
        }
        if (malformedTagCount != tagTracker.size) {
            repeat(malformedTagCount) {
                val tracker = tagTracker.removeLast()
                val malformedElement = MarkupElementSyntax(tracker.startTag, builder.consume(), markupEndTag = null)
                builder.addRange(tracker.previousNodes)
                builder.add(malformedElement)
            }
            val tracker = tagTracker.removeLast()
            val element = MarkupElementSyntax(tracker.startTag, builder.consume(), endTag)
            builder.addRange(tracker.previousNodes)
            builder.add(element)
            return true
        }
        return false
    }

    private class StartTagResult(val startTag: MarkupStartTagSyntax, val tagName: String, val tagMode: MarkupTagMode, val isWellFormed: Boolean)

    private fun parseStartTag(mode: ParseMode, tagStartLocation: SourceLocation): StartTagResult {
        var tagName = ""
        var tagMode = MarkupTagMode.Normal
        var isWellFormed = false
        val openAngleToken = eatCurrentToken()
        val bangToken = tryParseBangEscape()
        val isBangEscape = bangToken != null
        if (at(RazorSyntaxKind.Text)) {
            tagName = current.content
            if (isBangEscape) tagName = "!$tagName"
        }
        if (mode == ParseMode.MarkupInCodeBlock && tagTracker.isEmpty() && tagName.equals(SyntaxConstants.TEXT_TAG_NAME, ignoreCase = true)) {
            return parseStartTextTag(openAngleToken)
        }
        if (mode == ParseMode.MarkupInCodeBlock && tagTracker.isEmpty() && tagName.isEmpty() && at(RazorSyntaxKind.CloseAngle)) {
            context.errorSink.onError(RazorDiagnosticFactory.outerTagMissingName(SourceSpan(tagStartLocation, 1)))
        }
        val tagNameToken = if (at(RazorSyntaxKind.Text)) eatCurrentToken() else RazorToken.missing(RazorSyntaxKind.Text)
        val attributeBuilder = RazorListBuilder()
        parseAttributes(attributeBuilder)
        val attributes = attributeBuilder.toList()
        var forwardSlashToken: RazorToken? = null
        if (at(RazorSyntaxKind.ForwardSlash)) {
            tagMode = MarkupTagMode.SelfClosing
            forwardSlashToken = eatCurrentToken()
        }
        var closeAngleToken = RazorToken.missing(RazorSyntaxKind.CloseAngle)
        if (mode == ParseMode.MarkupInCodeBlock) {
            if (endOfFile || !at(RazorSyntaxKind.CloseAngle)) {
                context.errorSink.onError(
                    RazorDiagnosticFactory.unfinishedTag(
                        SourceSpan(if (tagName.isEmpty()) tagStartLocation else SourceLocationTracker.advance(tagStartLocation, "<"), maxOf(tagName.length, 1)), tagName,
                    ),
                )
            } else {
                if (at(RazorSyntaxKind.CloseAngle)) {
                    isWellFormed = true
                    closeAngleToken = eatCurrentToken()
                }
                setAcceptedCharacters(AcceptedCharacters.NONE)
                if (tagMode != MarkupTagMode.SelfClosing && isVoidElement(tagName)) {
                    val bookmark = currentStart.absoluteIndex
                    skipWhile(isSpacingTokenIncludingNewLines)
                    if (at(RazorSyntaxKind.OpenAngle) && nextIs(RazorSyntaxKind.ForwardSlash)) {
                        nextToken()
                        nextToken()
                        if (!at(RazorSyntaxKind.Text) || !current.content.equals(tagName, ignoreCase = true)) tagMode = MarkupTagMode.Void
                    } else {
                        tagMode = MarkupTagMode.Void
                    }
                    context.source.position = bookmark
                    nextToken()
                }
            }
        } else if (at(RazorSyntaxKind.CloseAngle)) {
            isWellFormed = true
            closeAngleToken = eatCurrentToken()
        }
        val startTag = MarkupStartTagSyntax(openAngleToken, bangToken, tagNameToken, attributes, forwardSlashToken, closeAngleToken, isMarkupTransition = false, chunkGenerator, getEditHandler())
        if (!parseAsXml && tagNamesEqual(tagName, SCRIPT_TAG_NAME)) {
            if (!scriptTagExpectsHtml(startTag)) tagMode = MarkupTagMode.Script
        }
        if (tagNameToken.isMissing && closeAngleToken.isMissing) tagMode = MarkupTagMode.Invalid
        return StartTagResult(startTag, tagName, tagMode, isWellFormed)
    }

    private fun parseStartTextTag(openAngleToken: RazorToken): StartTagResult {
        var tagMode = MarkupTagMode.Normal
        val textLocation = currentStart
        val tagNameToken = eatCurrentToken()
        val miscAttributeContentBuilder = RazorListBuilder()
        var forwardSlashToken: RazorToken? = null
        val closeAngleToken: RazorToken
        acceptWhile(isSpacingToken)
        miscAttributeContentBuilder.add(outputAsMarkupLiteral())
        if (at(RazorSyntaxKind.CloseAngle) || (at(RazorSyntaxKind.ForwardSlash) && nextIs(RazorSyntaxKind.CloseAngle))) {
            if (at(RazorSyntaxKind.ForwardSlash)) {
                tagMode = MarkupTagMode.SelfClosing
                forwardSlashToken = eatCurrentToken()
            }
            closeAngleToken = eatCurrentToken()
            setAcceptedCharacters(AcceptedCharacters.NONE)
        } else {
            context.errorSink.onError(RazorDiagnosticFactory.textTagCannotContainAttributes(SourceSpan(textLocation, 4)))
            val (miscContent, closeAngle) = recoverTextTag()
            miscAttributeContentBuilder.add(miscContent)
            closeAngleToken = closeAngle
        }
        chunkGenerator = ChunkGenerator.None
        val startTag = MarkupStartTagSyntax(
            openAngleToken, bang = null, name = tagNameToken, attributes = miscAttributeContentBuilder.toList(), forwardSlash = forwardSlashToken,
            closeAngle = closeAngleToken, isMarkupTransition = true, chunkGenerator = chunkGenerator, editHandler = getEditHandler(),
        )
        return StartTagResult(startTag, SyntaxConstants.TEXT_TAG_NAME, tagMode, isWellFormed = true)
    }

    private fun recoverTextTag(): Pair<MarkupTextLiteralSyntax?, RazorToken> {
        acceptUntil(RazorSyntaxKind.CloseAngle, RazorSyntaxKind.NewLine)
        val miscContent = outputAsMarkupLiteral()
        val closeAngleToken = if (at(RazorSyntaxKind.CloseAngle)) eatCurrentToken() else RazorToken.missing(RazorSyntaxKind.CloseAngle)
        return miscContent to closeAngleToken
    }

    private fun parseEndTag(mode: ParseMode): Pair<MarkupEndTagSyntax, String> {
        var tagName = ""
        val tagNameToken: RazorToken
        val openAngleToken = eatCurrentToken()
        val forwardSlashToken = if (at(RazorSyntaxKind.ForwardSlash)) eatCurrentToken() else RazorToken.missing(RazorSyntaxKind.ForwardSlash)
        val bangToken = tryParseBangEscape()
        val isBangEscape = bangToken != null
        if (at(RazorSyntaxKind.Text)) {
            tagName = if (isBangEscape) "!" else ""
            tagName += current.content
            if (mode == ParseMode.MarkupInCodeBlock && tagName.equals(SyntaxConstants.TEXT_TAG_NAME, ignoreCase = true)) {
                var openTextTagCount = 0
                for (tracker in tagTracker) if (tracker.tagName.equals(SyntaxConstants.TEXT_TAG_NAME, ignoreCase = true)) openTextTagCount++
                // `_tagTracker.Last()` of a Stack is its bottom: the outermost tag
                if (openTextTagCount == 1 && tagTracker.first().tagName.equals(SyntaxConstants.TEXT_TAG_NAME, ignoreCase = true)) {
                    return parseEndTextTag(openAngleToken, forwardSlashToken) to tagName
                }
            }
            tagNameToken = eatCurrentToken()
        } else {
            tagNameToken = RazorToken.missing(RazorSyntaxKind.Text)
        }
        val closeAngleToken: RazorToken
        var miscAttributeContent: MarkupMiscAttributeContentSyntax? = null
        val miscAttributeBuilder = RazorListBuilder()
        acceptWhile(if (parseAsXml) isSpacingTokenIncludingNewLines else isSpacingToken)
        miscAttributeBuilder.add(outputAsMarkupLiteral())
        if (mode == ParseMode.MarkupInCodeBlock) {
            acceptUntil(RazorSyntaxKind.CloseAngle, RazorSyntaxKind.OpenAngle)
            miscAttributeBuilder.add(outputAsMarkupLiteral())
            if (at(RazorSyntaxKind.CloseAngle)) setAcceptedCharacters(AcceptedCharacters.NONE)
        }
        if (miscAttributeBuilder.count > 0) miscAttributeContent = MarkupMiscAttributeContentSyntax(miscAttributeBuilder.toList())
        closeAngleToken = if (at(RazorSyntaxKind.CloseAngle)) eatCurrentToken() else RazorToken.missing(RazorSyntaxKind.CloseAngle)
        val endTag = MarkupEndTagSyntax(openAngleToken, forwardSlashToken, bangToken, tagNameToken, miscAttributeContent, closeAngleToken, isMarkupTransition = false, chunkGenerator, getEditHandler())
        return endTag to tagName
    }

    private fun parseEndTextTag(openAngleToken: RazorToken, forwardSlashToken: RazorToken): MarkupEndTagSyntax {
        val textLocation = currentStart
        val tagNameToken = eatCurrentToken()
        var miscAttributeContent: MarkupMiscAttributeContentSyntax? = null
        val closeAngleToken: RazorToken
        val miscAttributeBuilder = RazorListBuilder()
        if (!at(RazorSyntaxKind.CloseAngle)) {
            context.errorSink.onError(RazorDiagnosticFactory.textTagCannotContainAttributes(SourceSpan(textLocation, 4)))
            setAcceptedCharacters(AcceptedCharacters.ANY)
            val (miscContent, closeAngle) = recoverTextTag()
            miscAttributeBuilder.add(miscContent)
            closeAngleToken = closeAngle
        } else {
            setAcceptedCharacters(AcceptedCharacters.NONE)
            closeAngleToken = eatCurrentToken()
        }
        if (miscAttributeBuilder.count > 0) miscAttributeContent = MarkupMiscAttributeContentSyntax(miscAttributeBuilder.toList())
        chunkGenerator = ChunkGenerator.None
        return MarkupEndTagSyntax(openAngleToken, forwardSlashToken, bang = null, name = tagNameToken, miscAttributeContent = miscAttributeContent, closeAngle = closeAngleToken, isMarkupTransition = true, chunkGenerator = chunkGenerator, editHandler = getEditHandler())
    }

    private fun parseAttributes(builder: RazorListBuilder) {
        if (!at(RazorSyntaxKind.Whitespace) && !at(RazorSyntaxKind.NewLine)) {
            parseMiscAttribute(builder)
            return
        }
        while (!endOfFile && !isEndOfTag()) {
            if (at(RazorSyntaxKind.ForwardSlash)) acceptAndMoveNext()
            parseAttribute(builder)
        }
    }

    private fun isEndOfTag(): Boolean {
        if (at(RazorSyntaxKind.ForwardSlash)) {
            if (nextIs(RazorSyntaxKind.CloseAngle) || nextIs(RazorSyntaxKind.OpenAngle)) return true
        }
        return at(RazorSyntaxKind.CloseAngle) || at(RazorSyntaxKind.OpenAngle)
    }

    private fun parseMiscAttribute(builder: RazorListBuilder) {
        val miscAttributeContentBuilder = RazorListBuilder()
        while (!endOfFile) {
            parseMarkupNodes(miscAttributeContentBuilder, ParseMode.Text, ::isTagRecoveryStopPoint)
            if (!endOfFile) {
                ensureCurrent()
                when (current.kind) {
                    RazorSyntaxKind.SingleQuote, RazorSyntaxKind.DoubleQuote -> {
                        val openQuoteKind = current.kind
                        acceptAndMoveNext()
                        parseMarkupNodes(miscAttributeContentBuilder, ParseMode.Text) { it.kind == openQuoteKind }
                        if (!endOfFile) acceptAndMoveNext()
                    }
                    RazorSyntaxKind.OpenAngle, RazorSyntaxKind.ForwardSlash, RazorSyntaxKind.CloseAngle -> {
                        miscAttributeContentBuilder.add(outputAsMarkupLiteral())
                        if (miscAttributeContentBuilder.count > 0) builder.add(MarkupMiscAttributeContentSyntax(miscAttributeContentBuilder.toList()))
                        return
                    }
                    else -> acceptAndMoveNext()
                }
            }
        }
        miscAttributeContentBuilder.add(outputAsMarkupLiteral())
        if (miscAttributeContentBuilder.count > 0) builder.add(MarkupMiscAttributeContentSyntax(miscAttributeContentBuilder.toList()))
    }

    private fun parseAttribute(builder: RazorListBuilder) {
        run {
            val miscAttributeContentBuilder = RazorListBuilder()
            miscAttributeContentBuilder.add(outputAsMarkupLiteral())
            if (miscAttributeContentBuilder.count > 0) builder.add(MarkupMiscAttributeContentSyntax(miscAttributeContentBuilder.toList()))
        }
        val attributePrefixWhitespace = readWhile { it.kind == RazorSyntaxKind.Whitespace || it.kind == RazorSyntaxKind.NewLine }
        val nameTokens = ArrayList<RazorToken>()
        val (nameParsingResult, ephemeralToken) = tryParseAttributeName(nameTokens)
        when (nameParsingResult) {
            AttributeNameParsingResult.CSharp -> {
                accept(attributePrefixWhitespace)
                putCurrentBack()
                val dynamicAttributeValueBuilder = RazorListBuilder()
                otherParserBlock(dynamicAttributeValueBuilder)
                builder.add(MarkupMiscAttributeContentSyntax(dynamicAttributeValueBuilder.toList()))
                return
            }
            AttributeNameParsingResult.RazorComment -> {
                accept(attributePrefixWhitespace)
                putCurrentBack()
                parseRazorCommentWithLeadingAndTrailingWhitespace(builder)
                return
            }
            AttributeNameParsingResult.Other -> {
                accept(attributePrefixWhitespace)
                parseMiscAttribute(builder)
                return
            }
            AttributeNameParsingResult.Success -> {}
        }
        accept(attributePrefixWhitespace)
        var namePrefix = outputAsMarkupLiteral()
        if (ephemeralToken != null) {
            builder.add(namePrefix)
            accept(ephemeralToken)
            builder.add(outputAsMarkupEphemeralLiteral())
            namePrefix = null
        }
        accept(nameTokens)
        val name = outputAsMarkupLiteralRequired()
        val atMinimizedAttribute = !tokenExistsAfterWhitespace(RazorSyntaxKind.Equals)
        if (atMinimizedAttribute) {
            builder.add(MarkupMinimizedAttributeBlockSyntax(namePrefix, name))
        } else {
            builder.add(parseRemainingAttribute(namePrefix, name))
        }
    }

    private enum class AttributeNameParsingResult { Success, Other, CSharp, RazorComment }

    private fun tryParseAttributeName(nameTokens: MutableList<RazorToken>): Pair<AttributeNameParsingResult, RazorToken?> {
        var ephemeralToken: RazorToken? = null
        if (at(RazorSyntaxKind.RazorCommentTransition)) return AttributeNameParsingResult.RazorComment to null
        if (context.options.allowCSharpInMarkupAttributeArea) {
            if (at(RazorSyntaxKind.Transition)) {
                if (nextIs(RazorSyntaxKind.Transition)) {
                    ephemeralToken = current
                    nextToken()
                    nameTokens.add(current)
                    nextToken()
                } else {
                    return AttributeNameParsingResult.CSharp to null
                }
            }
        }
        if (ephemeralToken != null || isValidAttributeNameToken(currentToken)) {
            readWhile(
                { token ->
                    token.kind != RazorSyntaxKind.Whitespace &&
                        token.kind != RazorSyntaxKind.NewLine &&
                        token.kind != RazorSyntaxKind.Equals &&
                        token.kind != RazorSyntaxKind.CloseAngle &&
                        token.kind != RazorSyntaxKind.OpenAngle &&
                        (token.kind != RazorSyntaxKind.Transition || !context.options.allowCSharpInMarkupAttributeArea) &&
                        (token.kind != RazorSyntaxKind.ForwardSlash || !nextIs(RazorSyntaxKind.CloseAngle))
                },
                nameTokens,
            )
            return AttributeNameParsingResult.Success to ephemeralToken
        }
        return AttributeNameParsingResult.Other to null
    }

    private fun parseRemainingAttribute(namePrefix: MarkupTextLiteralSyntax?, name: MarkupTextLiteralSyntax): MarkupAttributeBlockSyntax {
        acceptWhile { it.kind == RazorSyntaxKind.Whitespace || it.kind == RazorSyntaxKind.NewLine }
        val nameSuffix = outputAsMarkupLiteral()
        val equalsToken = eatCurrentToken()
        val whitespaceAfterEquals = readWhile { it.kind == RazorSyntaxKind.Whitespace || it.kind == RazorSyntaxKind.NewLine }
        var quote = RazorSyntaxKind.Marker
        if (at(RazorSyntaxKind.SingleQuote) || at(RazorSyntaxKind.DoubleQuote)) {
            accept(whitespaceAfterEquals)
            quote = current.kind
            acceptAndMoveNext()
        } else if (whitespaceAfterEquals.isNotEmpty()) {
            putCurrentBack()
            putBack(whitespaceAfterEquals)
        }
        var valuePrefix: MarkupTextLiteralSyntax? = null
        var attributeValue: RazorBlockSyntax? = null
        var valueSuffix: MarkupTextLiteralSyntax? = null
        val nameContent = name.literalTokens.text()
        if (isConditionalAttributeName(nameContent)) {
            valuePrefix = outputAsMarkupLiteral()
            if (quote != RazorSyntaxKind.Marker || whitespaceAfterEquals.isEmpty()) {
                val attributeValueBuilder = RazorListBuilder()
                while (!endOfFile && !isEndOfAttributeValue(quote, currentToken)) parseConditionalAttributeValue(attributeValueBuilder, quote)
                if (attributeValueBuilder.count > 0) attributeValue = GenericBlockSyntax(attributeValueBuilder.toList())
            }
            if (quote != RazorSyntaxKind.Marker && at(quote)) {
                acceptAndMoveNext()
                valueSuffix = outputAsMarkupLiteral()
            }
        } else if (quote != RazorSyntaxKind.Marker || whitespaceAfterEquals.isEmpty()) {
            valuePrefix = outputAsMarkupLiteral()
            attributeValue = parseNonConditionalAttributeValue(quote)
            if (quote != RazorSyntaxKind.Marker) {
                tryAccept(quote)
                valueSuffix = outputAsMarkupLiteral()
            }
        }
        return MarkupAttributeBlockSyntax(namePrefix, name, nameSuffix, equalsToken, valuePrefix, attributeValue, valueSuffix)
    }

    private fun parseNonConditionalAttributeValue(quote: RazorSyntaxKind): RazorBlockSyntax {
        val attributeValueBuilder = RazorListBuilder()
        parseMarkupNodes(attributeValueBuilder, ParseMode.Text) { isEndOfAttributeValue(quote, it) }
        attributeValueBuilder.add(outputAsMarkupLiteral())
        return GenericBlockSyntax(attributeValueBuilder.toList())
    }

    private fun parseConditionalAttributeValue(builder: RazorListBuilder, quote: RazorSyntaxKind) {
        val prefixStart = currentStart
        val prefixTokens = readWhile { it.kind == RazorSyntaxKind.Whitespace || it.kind == RazorSyntaxKind.NewLine }
        if (at(RazorSyntaxKind.Transition)) {
            if (nextIs(RazorSyntaxKind.Transition)) {
                val markupBuilder = RazorListBuilder()
                accept(prefixTokens)
                val prefixContent = prefixTokens.joinToString("") { it.content }
                chunkGenerator = ChunkGenerator.LiteralAttribute(prefixContent, prefixStart, current.content, currentStart)
                acceptAndMoveNext()
                setAcceptedCharacters(AcceptedCharacters.NONE)
                markupBuilder.add(outputAsMarkupLiteral())
                chunkGenerator = ChunkGenerator.None
                acceptAndMoveNext()
                setAcceptedCharacters(AcceptedCharacters.NONE)
                markupBuilder.add(outputAsMarkupEphemeralLiteral())
                builder.add(MarkupBlockSyntax(markupBuilder.toList()))
            } else {
                accept(prefixTokens)
                putCurrentBack()
                val prefix = outputAsMarkupLiteral()
                val dynamicAttributeValueBuilder = RazorListBuilder()
                otherParserBlock(dynamicAttributeValueBuilder)
                builder.add(MarkupDynamicAttributeValueSyntax(prefix, GenericBlockSyntax(dynamicAttributeValueBuilder.toList())))
            }
        } else {
            accept(prefixTokens)
            val prefix = outputAsMarkupLiteral()
            val valueTokens = readWhile { token ->
                token.kind != RazorSyntaxKind.Whitespace && token.kind != RazorSyntaxKind.NewLine && token.kind != RazorSyntaxKind.Transition && !isEndOfAttributeValue(quote, token)
            }
            accept(valueTokens)
            val value = outputAsMarkupLiteral()
            builder.add(MarkupLiteralAttributeValueSyntax(prefix, value))
        }
    }

    private fun isEndOfAttributeValue(quote: RazorSyntaxKind, token: RazorToken?): Boolean =
        endOfFile || token == null || (if (quote != RazorSyntaxKind.Marker) token.kind == quote else isUnquotedEndOfAttributeValue(token))

    private fun isUnquotedEndOfAttributeValue(token: RazorToken): Boolean =
        token.kind == RazorSyntaxKind.DoubleQuote || token.kind == RazorSyntaxKind.SingleQuote || token.kind == RazorSyntaxKind.OpenAngle ||
            token.kind == RazorSyntaxKind.Equals || (token.kind == RazorSyntaxKind.ForwardSlash && nextIs(RazorSyntaxKind.CloseAngle)) ||
            token.kind == RazorSyntaxKind.CloseAngle || token.kind == RazorSyntaxKind.Whitespace || token.kind == RazorSyntaxKind.NewLine

    private fun parseJavascriptAndEndScriptTag(builder: RazorListBuilder, startTag: MarkupStartTagSyntax, endTagAcceptedCharacters: Int) {
        val previousNodes = builder.consume()
        var seenEndScript = false
        while (!seenEndScript && !endOfFile) {
            parseMarkupNodes(builder, ParseMode.Text) { it.kind == RazorSyntaxKind.OpenAngle }
            if (nextIs(RazorSyntaxKind.ForwardSlash)) {
                val openAngle = currentToken
                nextToken()
                val solidus = currentToken
                nextToken()
                if (at(RazorSyntaxKind.Text) && current.content.equals(SCRIPT_TAG_NAME, ignoreCase = true)) seenEndScript = true
                putCurrentBack()
                putBack(solidus)
                putBack(openAngle)
                nextToken()
            }
            if (!seenEndScript) acceptAndMoveNext()
        }
        var endTag: MarkupEndTagSyntax? = null
        if (seenEndScript) {
            val tagStart = currentStart
            builder.add(outputAsMarkupLiteral())
            val openAngleToken = eatCurrentToken()
            val forwardSlashToken = eatCurrentToken()
            val tagNameToken = eatCurrentToken()
            var miscContent: MarkupMiscAttributeContentSyntax? = null
            val closeAngleToken: RazorToken
            val miscAttributeBuilder = RazorListBuilder()
            acceptUntil(RazorSyntaxKind.CloseAngle, RazorSyntaxKind.OpenAngle)
            miscAttributeBuilder.add(outputAsMarkupLiteral())
            if (miscAttributeBuilder.count > 0) miscContent = MarkupMiscAttributeContentSyntax(miscAttributeBuilder.toList())
            if (!at(RazorSyntaxKind.CloseAngle)) {
                context.errorSink.onError(RazorDiagnosticFactory.unfinishedTag(SourceSpan(SourceLocationTracker.advance(tagStart, "</"), SCRIPT_TAG_NAME.length), SCRIPT_TAG_NAME))
                closeAngleToken = RazorToken.missing(RazorSyntaxKind.CloseAngle)
            } else {
                closeAngleToken = eatCurrentToken()
            }
            setAcceptedCharacters(endTagAcceptedCharacters)
            endTag = MarkupEndTagSyntax(openAngleToken, forwardSlashToken, bang = null, name = tagNameToken, miscAttributeContent = miscContent, closeAngle = closeAngleToken, isMarkupTransition = false, chunkGenerator = chunkGenerator, editHandler = getEditHandler())
        }
        val element = MarkupElementSyntax(startTag, builder.consume(), endTag)
        builder.addRange(previousNodes)
        builder.add(element)
    }

    private fun parseSpecialTag(builder: RazorListBuilder) {
        builder.add(outputAsMarkupLiteral())
        acceptTokenUntilAll(builder, RazorSyntaxKind.CloseAngle)
    }

    private fun parseXmlPI(builder: RazorListBuilder) {
        acceptAndMoveNext()
        acceptAndMoveNext()
        acceptTokenUntilAll(builder, RazorSyntaxKind.QuestionMark, RazorSyntaxKind.CloseAngle)
    }

    private fun parseCData(builder: RazorListBuilder) {
        acceptAndMoveNext()
        acceptAndMoveNext()
        acceptAndMoveNext()
        acceptAndMoveNext()
        acceptTokenUntilAll(builder, RazorSyntaxKind.RightBracket, RazorSyntaxKind.RightBracket, RazorSyntaxKind.CloseAngle)
    }

    private fun parseDoubleTransition(builder: RazorListBuilder) {
        acceptWhile(isSpacingTokenIncludingNewLines)
        builder.add(outputAsMarkupLiteral())
        acceptAndMoveNext()
        chunkGenerator = ChunkGenerator.None
        builder.add(outputAsMarkupEphemeralLiteral())
        acceptAndMoveNext()
    }

    private fun parseCodeTransition(builder: RazorListBuilder) {
        nullGenerateWhitespaceAndNewLine(builder)
        var lastWhitespace = acceptWhitespaceInLines()
        if (lastWhitespace != null) {
            if (!context.startOfLine) {
                accept(lastWhitespace)
                lastWhitespace = null
            }
        }
        putCurrentBack()
        if (lastWhitespace != null) putBack(lastWhitespace)
        otherParserBlock(builder)
    }

    private fun parseMarkupComment(builder: RazorListBuilder) {
        builder.add(outputAsMarkupLiteral())
        val htmlCommentBuilder = RazorListBuilder()
        acceptAndMoveNext()
        acceptAndMoveNext()
        acceptAndMoveNext()
        setAcceptedCharacters(AcceptedCharacters.NONE)
        htmlCommentBuilder.add(outputAsMarkupLiteral())
        setAcceptedCharacters(AcceptedCharacters.WHITESPACE)
        while (!endOfFile) {
            parseMarkupNodes(htmlCommentBuilder, ParseMode.Text) { it.kind == RazorSyntaxKind.DoubleHyphen }
            val lastDoubleHyphen = acceptAllButLastDoubleHyphens()
            if (at(RazorSyntaxKind.CloseAngle)) {
                setAcceptedCharacters(AcceptedCharacters.WHITESPACE)
                htmlCommentBuilder.add(outputAsMarkupLiteral())
                accept(lastDoubleHyphen)
                acceptAndMoveNext()
                setAcceptedCharacters(AcceptedCharacters.NONE)
                htmlCommentBuilder.add(outputAsMarkupLiteral())
                builder.add(MarkupCommentBlockSyntax(htmlCommentBuilder.toList()))
                return
            } else if (lastDoubleHyphen != null) {
                accept(lastDoubleHyphen)
            }
        }
        builder.add(outputAsMarkupLiteral())
    }

    private fun parseRazorCommentWithLeadingAndTrailingWhitespace(builder: RazorListBuilder) {
        nullGenerateWhitespaceAndNewLine(builder)
        var shouldRenderWhitespace = true
        var lastWhitespace = acceptWhitespaceInLines()
        val startOfLine = context.startOfLine
        if (lastWhitespace != null) {
            if (startOfLine) {
                acceptMarkerTokenIfNecessary()
                builder.add(outputAsMarkupLiteral())
                chunkGenerator = ChunkGenerator.None
                shouldRenderWhitespace = false
            }
            accept(lastWhitespace)
            lastWhitespace = null
        }
        acceptMarkerTokenIfNecessary()
        if (shouldRenderWhitespace) builder.add(outputAsMarkupLiteral()) else builder.add(outputAsMarkupEphemeralLiteral())
        val comment = parseRazorComment()
        builder.add(comment)
        if (startOfLine && (at(RazorSyntaxKind.NewLine) || (at(RazorSyntaxKind.Whitespace) && nextIs(RazorSyntaxKind.NewLine)))) {
            acceptWhile(isSpacingToken)
            acceptAndMoveNext()
            chunkGenerator = ChunkGenerator.None
            builder.add(outputAsMarkupEphemeralLiteral())
        }
    }

    private fun parseMisc(builder: RazorListBuilder) {
        nullGenerateWhitespaceAndNewLine(builder)
        acceptWhile(isSpacingTokenIncludingNewLines)
    }

    private fun nullGenerateWhitespaceAndNewLine(builder: RazorListBuilder) {
        if (context.nullGenerateWhitespaceAndNewLine) {
            if (tokenBuilder.isNotEmpty()) return
            context.nullGenerateWhitespaceAndNewLine = false
            chunkGenerator = ChunkGenerator.None
            acceptWhile(isSpacingToken)
            if (at(RazorSyntaxKind.NewLine)) acceptAndMoveNext()
            builder.add(outputAsMarkupEphemeralLiteral())
        }
    }

    private fun scriptTagExpectsHtml(tagBlock: MarkupStartTagSyntax): Boolean {
        var typeAttribute: MarkupAttributeBlockSyntax? = null
        for (node in tagBlock.attributes.nodes) {
            if (node is MarkupAttributeBlockSyntax && node.value != null && node.value.children.size > 0 && isTypeAttribute(node)) {
                typeAttribute = node
                break
            }
        }
        if (typeAttribute != null) {
            val scriptType = typeAttribute.value!!.text().trim()
            return scriptType.equals("text/html", ignoreCase = true)
        }
        return false
    }

    private fun isTypeAttribute(attributeBlock: MarkupAttributeBlockSyntax): Boolean {
        if (attributeBlock.name.literalTokens.size == 0) return false
        val trimmedStartContent = attributeBlock.name.text().trimStart()
        return trimmedStartContent.startsWith("type", ignoreCase = true) && (trimmedStartContent.length == 4 || trimmedStartContent[4] in VALID_AFTER_TYPE_ATTRIBUTE_NAME_CHARACTERS)
    }

    private fun acceptAllButLastDoubleHyphens(): RazorToken? {
        var lastDoubleHyphen = currentToken
        acceptWhile { s ->
            if (nextIs(RazorSyntaxKind.DoubleHyphen)) {
                lastDoubleHyphen = s
                true
            } else false
        }
        nextToken()
        if (at(RazorSyntaxKind.Text) && isHyphen(current)) {
            if (!nextIs(RazorSyntaxKind.CloseAngle)) {
                accept(lastDoubleHyphen)
                lastDoubleHyphen = null
            }
            acceptAndMoveNext()
        }
        return lastDoubleHyphen
    }

    private fun acceptTokenUntilAll(builder: RazorListBuilder, vararg endSequence: RazorSyntaxKind) {
        while (!endOfFile) {
            parseMarkupNodes(builder, ParseMode.Text) { it.kind == endSequence[0] }
            var matchLength = 0
            while (matchLength < endSequence.size && lookahead(matchLength)?.kind == endSequence[matchLength]) matchLength++
            if (matchLength == endSequence.size) {
                acceptAll(*endSequence)
                return
            }
            acceptAndMoveNext()
        }
        setAcceptedCharacters(AcceptedCharacters.ANY)
    }

    private fun getParserState(mode: ParseMode): ParserState {
        val whitespace = readWhile { it.kind == RazorSyntaxKind.Whitespace || it.kind == RazorSyntaxKind.NewLine }
        try {
            if (whitespace.isEmpty() && endOfFile) return ParserState.EOF
            if (at(RazorSyntaxKind.RazorCommentTransition)) return ParserState.RazorComment
            if (at(RazorSyntaxKind.Transition)) {
                if (nextIs(RazorSyntaxKind.Transition)) return ParserState.DoubleTransition
                return ParserState.CodeTransition
            }
            if (whitespace.isNotEmpty()) return ParserState.Misc
            if (mode == ParseMode.Text) return ParserState.MarkupText
            if (at(RazorSyntaxKind.OpenAngle)) {
                if (nextIs(RazorSyntaxKind.Bang)) {
                    if (!isBangEscape(lookahead = 1)) {
                        if (if (parseAsXml) lookahead(2)?.kind == RazorSyntaxKind.DoubleHyphen else isHtmlCommentAhead()) {
                            return ParserState.MarkupComment
                        } else if (lookahead(2)?.kind == RazorSyntaxKind.LeftBracket && lookahead(3)?.let { tagNamesEqual(it.content, "CDATA") } == true && lookahead(4)?.kind == RazorSyntaxKind.LeftBracket) {
                            return ParserState.CData
                        } else {
                            return ParserState.SpecialTag
                        }
                    }
                } else if (nextIs(RazorSyntaxKind.QuestionMark)) {
                    return ParserState.XmlPI
                }
                return ParserState.Tag
            }
            return ParserState.Unknown
        } finally {
            if (whitespace.isNotEmpty()) {
                putCurrentBack()
                putBack(whitespace)
                ensureCurrent()
            }
        }
    }

    private fun tryParseBangEscape(): RazorToken? {
        if (isBangEscape(lookahead = 0)) return eatCurrentToken()
        return null
    }

    private fun isBangEscape(lookahead: Int): Boolean {
        if (parseAsXml) return false
        val potentialBang = lookahead(lookahead)
        if (potentialBang != null && potentialBang.kind == RazorSyntaxKind.Bang) {
            val afterBang = lookahead(lookahead + 1)
            return afterBang != null && afterBang.kind == RazorSyntaxKind.Text && !afterBang.content.equals("DOCTYPE", ignoreCase = true)
        }
        return false
    }

    internal fun isHtmlCommentAhead(): Boolean {
        if (!(at(RazorSyntaxKind.OpenAngle) && nextIs(RazorSyntaxKind.Bang))) return false
        val openAngle = eatCurrentToken()
        val bangToken = eatCurrentToken()
        try {
            if (endOfFile || current.kind != RazorSyntaxKind.DoubleHyphen) return false
            if (nextIs(RazorSyntaxKind.CloseAngle) || nextIs { next -> isHyphen(next) && nextIs(RazorSyntaxKind.CloseAngle) }) return false
            var isValidComment = false
            lookaheadUntil { token, prevTokens ->
                if (token.kind == RazorSyntaxKind.DoubleHyphen) {
                    if (nextIs(RazorSyntaxKind.CloseAngle)) {
                        isValidComment = !isCommentContentEndingInvalid(prevTokens)
                        return@lookaheadUntil true
                    } else if (nextIs { ns -> isHyphen(ns) && nextIs(RazorSyntaxKind.CloseAngle) }) {
                        isValidComment = true
                        return@lookaheadUntil true
                    } else if (nextIs { ns -> ns.kind == RazorSyntaxKind.Bang && nextIs(RazorSyntaxKind.CloseAngle) }) {
                        isValidComment = false
                        return@lookaheadUntil true
                    }
                } else if (token.kind == RazorSyntaxKind.OpenAngle) {
                    if (nextIs { ns -> ns.kind == RazorSyntaxKind.Bang && nextIs(RazorSyntaxKind.DoubleHyphen) }) {
                        isValidComment = false
                        return@lookaheadUntil true
                    }
                }
                false
            }
            return isValidComment
        } finally {
            putCurrentBack()
            putBack(bangToken)
            putBack(openAngle)
            ensureCurrent()
        }
    }

    private fun isConditionalAttributeName(name: String): Boolean {
        if (context.options.allowConditionalDataDashAttributes) return true
        return !name.startsWith("data-", ignoreCase = true)
    }

    private fun nestingBlock(builder: RazorListBuilder, nestingSequences: Pair<String, String>) {
        var nesting = 1
        while (nesting > 0 && !endOfFile) {
            parseMarkupNodes(builder, ParseMode.Text) { it.kind == RazorSyntaxKind.Text || it.kind == RazorSyntaxKind.OpenAngle }
            if (at(RazorSyntaxKind.Text)) {
                if (!parseAsXml || tagTracker.isEmpty()) nesting += processTextToken(builder, nestingSequences, nesting)
                if (currentToken != null) acceptAndMoveNext() else if (nesting > 0) nextToken()
            } else {
                parseMarkupNode(builder, ParseMode.Markup)
            }
        }
        while (tagTracker.isNotEmpty()) {
            val tracker = tagTracker.removeLast()
            val element = MarkupElementSyntax(tracker.startTag, builder.consume(), markupEndTag = null)
            builder.addRange(tracker.previousNodes)
            builder.add(element)
        }
    }

    private fun processTextToken(builder: RazorListBuilder, nestingSequences: Pair<String, String>, currentNesting: Int): Int {
        for (i in current.content.indices) {
            var nestingDelta = handleNestingSequence(builder, nestingSequences.first, i, currentNesting, 1)
            if (nestingDelta == 0) nestingDelta = handleNestingSequence(builder, nestingSequences.second, i, currentNesting, -1)
            if (nestingDelta != 0) return nestingDelta
        }
        return 0
    }

    private fun handleNestingSequence(builder: RazorListBuilder, sequence: String?, position: Int, currentNesting: Int, retIfMatched: Int): Int {
        val content = current.content
        if (sequence != null && content[position] == sequence[0] && position + sequence.length <= content.length) {
            if (content.regionMatches(position, sequence, 0, sequence.length)) {
                val bookmark = currentStart
                val token = current
                putCurrentBack()
                val (preSequence, right) = language.splitToken(token, position, RazorSyntaxKind.Text)
                val (sequenceToken, _) = language.splitToken(right!!, sequence.length, RazorSyntaxKind.Text)
                val postSequenceBookmark = bookmark.absoluteIndex + preSequence.content.length + sequenceToken.content.length
                val closesBlock = currentNesting + retIfMatched == 0
                if (preSequence.content.isNotEmpty()) accept(preSequence)
                if (closesBlock) {
                    context.source.position = bookmark.absoluteIndex + preSequence.content.length
                } else {
                    accept(sequenceToken)
                    context.source.position = postSequenceBookmark
                }
                return retIfMatched
            }
        }
        return 0
    }

    private fun otherParserBlock(builder: RazorListBuilder) {
        acceptMarkerTokenIfNecessary()
        builder.add(outputAsMarkupLiteral())
        val codeBlock: RazorNode?
        pushSpanContextConfig().use {
            codeBlock = codeParser.parseBlock()
        }
        builder.add(codeBlock)
        initializeContext()
        nextToken()
    }

    private fun defaultMarkupSpanContext(editHandlerBuilder: SpanEditHandlerBuilder?): ChunkGenerator {
        setAcceptedCharacters(AcceptedCharacters.ANY)
        if (editHandlerBuilder != null) {
            editHandlerBuilder.reset()
            editHandlerBuilder.hasTokenizer = true
        }
        return ChunkGenerator.Markup
    }

    private enum class ParseMode { Markup, MarkupInCodeBlock, Text }
    private enum class MarkupTagMode { Normal, Void, SelfClosing, Script, Invalid }
    private enum class ParserState { MarkupText, Tag, SpecialTag, XmlPI, CData, MarkupComment, RazorComment, DoubleTransition, CodeTransition, Misc, Unknown, EOF }

    private class TagTracker(val tagName: String, val startTag: MarkupStartTagSyntax, val tagLocation: SourceLocation, val previousNodes: RazorList, val isWellFormed: Boolean)

    companion object {
        private const val SCRIPT_TAG_NAME = "script"
        private val VALID_AFTER_TYPE_ATTRIBUTE_NAME_CHARACTERS = charArrayOf(' ', '\t', '\r', '\n', '\u000C', '=')

        /** `ParseXmlBody` (the `@documentation` directive): the end of the XML body that starts at [start]. */
        fun parseXmlBody(context: ParserContext, start: Int): Int {
            val xmlContext = ParserContext(context.text, context.options, context.filePath)
            xmlContext.source.position = start
            val parser = HtmlMarkupParser(xmlContext, parseAsXml = true)
            parser.parseRazorBlock("{" to "}")
            return xmlContext.source.position
        }

        /** `GetLastSpan`: the node that holds the last token of [node] (the parent of that token). */
        private fun getLastSpan(node: RazorNode): RazorSyntaxNode? {
            var current: RazorNode = node
            var parent: RazorSyntaxNode? = null
            while (true) {
                if (current is RazorToken) return parent
                var last: RazorNode? = null
                for (i in current.slotCount - 1 downTo 0) {
                    val child = current.slot(i)
                    if (child != null && (child.isToken || child.width > 0 || child.tokens().isNotEmpty())) {
                        last = child
                        break
                    }
                }
                if (last == null) return null
                if (current is RazorSyntaxNode) parent = current
                current = last
            }
        }

        internal fun isCommentContentEndingInvalid(tokens: List<RazorToken?>): Boolean {
            var index = 0
            for (i in tokens.indices.reversed()) {
                val token = tokens[i] ?: return false
                if (!token.isEquivalentTo(NON_ALLOWED_HTML_COMMENT_ENDING[index++])) return false
                if (index == NON_ALLOWED_HTML_COMMENT_ENDING.size) return true
            }
            return false
        }

        private val NON_ALLOWED_HTML_COMMENT_ENDING = arrayOf(RazorToken(RazorSyntaxKind.Text, "-"), RazorToken(RazorSyntaxKind.Bang, "!"), RazorToken(RazorSyntaxKind.OpenAngle, "<"))

        internal fun isHyphen(token: RazorToken): Boolean = token.kind == RazorSyntaxKind.Text && token.content == "-"

        internal fun isValidAttributeNameToken(token: RazorToken?): Boolean {
            if (token == null) return false
            val kind = token.kind
            return kind != RazorSyntaxKind.Whitespace && kind != RazorSyntaxKind.NewLine && kind != RazorSyntaxKind.CloseAngle && kind != RazorSyntaxKind.OpenAngle &&
                kind != RazorSyntaxKind.ForwardSlash && kind != RazorSyntaxKind.Equals && kind != RazorSyntaxKind.Marker
        }

        private fun isTagRecoveryStopPoint(token: RazorToken): Boolean =
            token.kind == RazorSyntaxKind.CloseAngle || token.kind == RazorSyntaxKind.ForwardSlash || token.kind == RazorSyntaxKind.OpenAngle ||
                token.kind == RazorSyntaxKind.SingleQuote || token.kind == RazorSyntaxKind.DoubleQuote

        private fun isVoidElement(tagName: String?): Boolean {
            if (tagName.isNullOrEmpty()) return false
            val name = if (tagName.startsWith("!")) tagName.substring(1) else tagName
            return ParserHelpers.isVoidElement(name)
        }
    }
}
