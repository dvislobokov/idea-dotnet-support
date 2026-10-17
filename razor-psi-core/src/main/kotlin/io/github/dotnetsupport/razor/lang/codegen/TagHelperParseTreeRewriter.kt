package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.codegen.components.isAnyComponentDocumentTagHelper
import io.github.dotnetsupport.razor.lang.codegen.components.isComponentOrChildContentTagHelper
import io.github.dotnetsupport.razor.lang.parser.CSharpCodeParser
import io.github.dotnetsupport.razor.lang.parser.ErrorSink
import io.github.dotnetsupport.razor.lang.parser.ParserHelpers
import io.github.dotnetsupport.razor.lang.parser.RazorSyntaxTree
import io.github.dotnetsupport.razor.lang.parser.SourceLocationTracker
import io.github.dotnetsupport.razor.lang.syntax.AcceptedCharacters
import io.github.dotnetsupport.razor.lang.syntax.AttributeStructure
import io.github.dotnetsupport.razor.lang.syntax.ChunkGenerator
import io.github.dotnetsupport.razor.lang.syntax.ImplicitExpressionEditHandler
import io.github.dotnetsupport.razor.lang.syntax.KeywordSet
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticFactory
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind
import io.github.dotnetsupport.razor.lang.syntax.RazorList
import io.github.dotnetsupport.razor.lang.syntax.RazorNode
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions
import io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind
import io.github.dotnetsupport.razor.lang.syntax.RazorToken
import io.github.dotnetsupport.razor.lang.syntax.SourceLocation
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import io.github.dotnetsupport.razor.lang.syntax.SpanEditHandler
import io.github.dotnetsupport.razor.lang.syntax.SpanEditHandlerBuilder
import io.github.dotnetsupport.razor.lang.syntax.TagHelperAttributeInfo
import io.github.dotnetsupport.razor.lang.syntax.TagHelperBinder
import io.github.dotnetsupport.razor.lang.syntax.TagHelperBinding
import io.github.dotnetsupport.razor.lang.syntax.TagHelperCollection
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperInfo
import io.github.dotnetsupport.razor.lang.syntax.TagHelperMatchingConventions
import io.github.dotnetsupport.razor.lang.syntax.TagMode
import io.github.dotnetsupport.razor.lang.syntax.TagStructure
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpCodeBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpEphemeralTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpExplicitExpressionBodySyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpExplicitExpressionSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpExpressionLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpImplicitExpressionBodySyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpImplicitExpressionSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpStatementLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpStatementSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.CSharpTransitionSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.GenericBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.GreenNodeUpdate
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupAttributeBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupCommentBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupDynamicAttributeValueSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupElementSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupEndTagSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupEphemeralTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupLiteralAttributeValueSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupMinimizedAttributeBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupMiscAttributeContentSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupStartTagSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.MarkupTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RazorBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RazorCommentBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RazorDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RazorDocumentationDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RazorMetaCodeSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RazorUsingDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.red.RedNode
import io.github.dotnetsupport.razor.lang.syntax.red.RedSyntaxRewriter
import io.github.dotnetsupport.razor.lang.syntax.red.RedTree
import io.github.dotnetsupport.razor.lang.syntax.red.chunkGeneratorOrNull
import io.github.dotnetsupport.razor.lang.syntax.red.defaultEditHandler
import io.github.dotnetsupport.razor.lang.syntax.red.getTagNameWithOptionalBang
import io.github.dotnetsupport.razor.lang.syntax.red.isSelfClosing
import io.github.dotnetsupport.razor.lang.syntax.red.legacyChildren
import java.util.TreeSet
import io.github.dotnetsupport.razor.lang.syntax.CSharpCodeBlockSyntax as GCSharpCodeBlock
import io.github.dotnetsupport.razor.lang.syntax.CSharpEphemeralTextLiteralSyntax as GCSharpEphemeralTextLiteral
import io.github.dotnetsupport.razor.lang.syntax.CSharpExpressionLiteralSyntax as GCSharpExpressionLiteral
import io.github.dotnetsupport.razor.lang.syntax.CSharpImplicitExpressionBodySyntax as GCSharpImplicitExpressionBody
import io.github.dotnetsupport.razor.lang.syntax.CSharpImplicitExpressionSyntax as GCSharpImplicitExpression
import io.github.dotnetsupport.razor.lang.syntax.CSharpTransitionSyntax as GCSharpTransition
import io.github.dotnetsupport.razor.lang.syntax.GenericBlockSyntax as GGenericBlock
import io.github.dotnetsupport.razor.lang.syntax.MarkupAttributeBlockSyntax as GMarkupAttributeBlock
import io.github.dotnetsupport.razor.lang.syntax.MarkupBlockSyntax as GMarkupBlock
import io.github.dotnetsupport.razor.lang.syntax.MarkupElementSyntax as GMarkupElement
import io.github.dotnetsupport.razor.lang.syntax.MarkupEndTagSyntax as GMarkupEndTag
import io.github.dotnetsupport.razor.lang.syntax.MarkupEphemeralTextLiteralSyntax as GMarkupEphemeralTextLiteral
import io.github.dotnetsupport.razor.lang.syntax.MarkupMinimizedTagHelperAttributeSyntax as GMarkupMinimizedTagHelperAttribute
import io.github.dotnetsupport.razor.lang.syntax.MarkupMinimizedTagHelperDirectiveAttributeSyntax as GMarkupMinimizedTagHelperDirectiveAttribute
import io.github.dotnetsupport.razor.lang.syntax.MarkupStartTagSyntax as GMarkupStartTag
import io.github.dotnetsupport.razor.lang.syntax.MarkupTagHelperAttributeSyntax as GMarkupTagHelperAttribute
import io.github.dotnetsupport.razor.lang.syntax.MarkupTagHelperAttributeValueSyntax as GMarkupTagHelperAttributeValue
import io.github.dotnetsupport.razor.lang.syntax.MarkupTagHelperDirectiveAttributeSyntax as GMarkupTagHelperDirectiveAttribute
import io.github.dotnetsupport.razor.lang.syntax.MarkupTagHelperElementSyntax as GMarkupTagHelperElement
import io.github.dotnetsupport.razor.lang.syntax.MarkupTagHelperEndTagSyntax as GMarkupTagHelperEndTag
import io.github.dotnetsupport.razor.lang.syntax.MarkupTagHelperStartTagSyntax as GMarkupTagHelperStartTag
import io.github.dotnetsupport.razor.lang.syntax.MarkupTextLiteralSyntax as GMarkupTextLiteral
import io.github.dotnetsupport.razor.lang.syntax.RazorBlockSyntax as GRazorBlock
import io.github.dotnetsupport.razor.lang.syntax.RazorDocumentSyntax as GRazorDocument
import io.github.dotnetsupport.razor.lang.syntax.RazorMetaCodeSyntax as GRazorMetaCode

/**
 * `DefaultRazorTagHelperRewritePhase`: the syntax tree rewritten with the tag helpers bound (`MarkupTagHelperElement` and friends) for the
 * tooling; without descriptors the canonical tree is the rewritten one.
 */
class DefaultRazorTagHelperRewritePhase : RazorEnginePhaseBase() {
    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        val syntaxTree = codeDocument.getSyntaxTree() ?: return codeDocument.withReferencedTagHelpers(TagHelperCollection.EMPTY)

        val context = codeDocument.getTagHelperContext()
        if (context == null || context.tagHelpers.isEmpty()) {
            // No tag helpers to rewrite: the rewritten tree is the canonical tree (the tooling expects one after the full pipeline).
            return codeDocument.withReferencedTagHelpers(TagHelperCollection.EMPTY).withTagHelperRewrittenSyntaxTree(syntaxTree)
        }

        val binder = context.binder
        val usedHelpers = LinkedHashSet<TagHelperDescriptor>()
        val rewrittenSyntaxTree = TagHelperParseTreeRewriter.rewrite(syntaxTree, binder, usedHelpers)

        return codeDocument.withReferencedTagHelpers(TagHelperCollection(usedHelpers.toList())).withTagHelperRewrittenSyntaxTree(rewrittenSyntaxTree)
    }
}

/**
 * `Legacy/TagHelperParseTreeRewriter`: the elements of the syntax tree that bind to tag helpers rewritten as `MarkupTagHelperElement` with
 * tag helper start / end tags and attributes ([TagHelperBlockRewriter]), the structure and the allowed children validated. The new nodes
 * are green ones; [RedSyntaxRewriter] rebuilds the ancestors.
 */
object TagHelperParseTreeRewriter {
    fun rewrite(syntaxTree: RazorSyntaxTree, binder: TagHelperBinder, usedDescriptors: MutableSet<TagHelperDescriptor>? = null): RazorSyntaxTree {
        val errorSink = ErrorSink()
        val source = syntaxTree.source ?: RazorSourceDocument(syntaxTree.text, null)

        val rewriter = Rewriter(source, binder, syntaxTree.options, errorSink, usedDescriptors)

        val rewritten = rewriter.visit(syntaxTree.redRoot)!!

        val treeDiagnostics = syntaxTree.diagnostics
        val sinkDiagnostics = errorSink.getErrorsAndClear()

        val builder = ArrayList<RazorDiagnostic>(treeDiagnostics.size + sinkDiagnostics.size)
        builder.addAll(treeDiagnostics)
        builder.addAll(sinkDiagnostics)
        for (descriptor in binder.tagHelpers) builder.addAll(descriptor.getAllDiagnostics())

        val diagnostics = builder.sortedBy { it.span.absoluteIndex }

        return RazorSyntaxTree(rewritten.green as GRazorDocument, syntaxTree.text, syntaxTree.options, diagnostics, syntaxTree.source)
    }

    /** `Rewriter`: the syntax rewriter over the tree, with the stack of the open tags. */
    internal class Rewriter(
        private val source: RazorSourceDocument,
        private val binder: TagHelperBinder,
        private val options: RazorParserOptions,
        private val errorSink: ErrorSink,
        private val usedDescriptors: MutableSet<TagHelperDescriptor>?,
    ) : RedSyntaxRewriter() {
        // A stack: the top is the last element.
        private val trackerStack = ArrayList<TagTracker>()

        private val currentTracker: TagTracker? get() = trackerStack.lastOrNull()

        private val currentParentTagName: String? get() = currentTracker?.tagName

        private val currentParentIsTagHelper: Boolean get() = currentTracker?.isTagHelper ?: false

        private val currentTagHelperTracker: TagHelperTracker?
            get() {
                for (tracker in trackerStack.asReversed()) if (tracker.isTagHelper) return tracker as? TagHelperTracker
                return null
            }

        override fun visitMarkupElement(node: MarkupElementSyntax): RedNode? {
            if (isPartOfStartTag(node)) {
                // An element inside a start tag is a malformed case like <p @do { someattribute=\"btn\"></p>, where the end "p" tag is inside the
                // start "p" tag: no tag helper parsing for it.
                return super.visitMarkupElement(node)
            }

            var tagHelperStart: GMarkupTagHelperStartTag? = null
            var tagHelperEnd: GMarkupTagHelperEndTag? = null
            var tagHelperInfo: TagHelperInfo? = null

            // The start tag.
            val startTag = visit(node.markupStartTag) as MarkupStartTagSyntax?
            if (startTag != null) {
                val tagName = startTag.getTagNameWithOptionalBang()
                val rewrittenStart = tryRewriteTagHelperStart(startTag, node.markupEndTag)
                if (rewrittenStart != null) {
                    tagHelperStart = rewrittenStart.first
                    tagHelperInfo = rewrittenStart.second
                    // This is a tag helper.
                    if (tagHelperInfo.tagMode == TagMode.SelfClosing || tagHelperInfo.tagMode == TagMode.StartTagOnly) {
                        val tagHelperElement = GMarkupTagHelperElement(tagHelperStart, RazorList.EMPTY, null, tagHelperInfo)

                        if (node.body.isEmpty() && node.markupEndTag == null) return RedTree.of(tagHelperElement)

                        // A body and / or an end tag that move to the parent.
                        val rewrittenNodes = ArrayList<RazorNode>()

                        rewrittenNodes.add(tagHelperElement)
                        val rewrittenBody = visitList(node.body)
                        for (n in rewrittenBody) rewrittenNodes.add(n.green)

                        return RedTree.of(GMarkupElement(null, RazorList(rewrittenNodes), node.markupEndTag?.greenNode))
                    } else if (node.endTag == null) {
                        // A tag helper start tag without its end tag.
                        val span = SourceSpan(SourceLocationTracker.advance(startTag.getSourceLocation(source), "<"), tagName.length)
                        errorSink.onError(
                            if (isVoidElement(startTag)) RazorDiagnosticFactory.parsing_VoidElement(span, tagName)
                            else RazorDiagnosticFactory.parsing_TagHelperFoundMalformedTagHelper(span, tagName),
                        )
                    } else {
                        // A tag helper start tag: tracked.
                        trackerStack.add(TagHelperTracker(binder.tagNamePrefix, tagHelperInfo))
                    }
                } else {
                    // Not a tag helper tag.
                    validateParentAllowsPlainStartTag(startTag)

                    if (node.endTag != null || (!startTag.isSelfClosing() && !isVoidElement(startTag))) {
                        // Self-closing and void tags are not tracked, unless an end tag matches: then the start tag is tracked whatever it is,
                        // the parser had a good reason.
                        trackerStack.add(TagTracker(tagName, isTagHelper = false))
                    }
                }
            }

            // The body between the start and the end tags.
            val body = visitList(node.body)

            // The end tag.
            val endTag = visit(node.markupEndTag) as MarkupEndTagSyntax?
            if (endTag != null) {
                val tagName = endTag.getTagNameWithOptionalBang()
                val rewrittenEnd = tryRewriteTagHelperEnd(startTag, endTag)
                if (rewrittenEnd != null) {
                    tagHelperEnd = rewrittenEnd
                    // This is a tag helper; an end tag helper without its start tag is an error.
                    if (startTag == null) {
                        errorSink.onError(
                            RazorDiagnosticFactory.parsing_TagHelperFoundMalformedTagHelper(
                                SourceSpan(SourceLocationTracker.advance(endTag.getSourceLocation(source), "</"), tagName.length), tagName,
                            ),
                        )
                    }
                } else {
                    // Not a tag helper end tag.
                    if (startTag == null) {
                        // A standalone end tag, maybe not allowed here (with a start tag the error would have been reported already).
                        validateParentAllowsPlainEndTag(endTag)
                    } else {
                        // The start tag is tracked: popped, the end tag is done.
                        trackerStack.removeAt(trackerStack.size - 1)
                    }
                }
            }

            // This element was rewritten as a tag helper.
            if (tagHelperInfo != null) return RedTree.of(GMarkupTagHelperElement(tagHelperStart, RazorList(body.map { it.green }), tagHelperEnd, tagHelperInfo))

            // No tag helper matched this element (`node.Update(startTag, body, endTag)`).
            if (startTag === node.markupStartTag && body === node.body && endTag === node.markupEndTag) return node
            val updated = GMarkupElement(startTag?.greenNode, RazorList(body.map { it.green }), endTag?.greenNode)
            updated.diagnostics = node.green.diagnostics
            return RedTree.of(updated)
        }

        override fun visitMarkupTextLiteral(node: MarkupTextLiteralSyntax): RedNode? {
            val tagParent = node.firstAncestorOrSelf { it is MarkupStartTagSyntax || it is MarkupEndTagSyntax }
            val isPartOfTagBlock = tagParent != null
            if (!isPartOfTagBlock) validateParentAllowsContent(node)

            return super.visitMarkupTextLiteral(node)
        }

        /** `TryRewriteTagHelperStart`: the rewritten start tag and the binding info of a tag helper start tag, null when it is not one. */
        private fun tryRewriteTagHelperStart(startTag: MarkupStartTagSyntax, endTag: MarkupEndTagSyntax?): Pair<GMarkupTagHelperStartTag, TagHelperInfo>? {
            // The tag name of the current block.
            val tagName = startTag.getTagNameWithOptionalBang()

            // No tag name: not a tag helper, the element is tracked.
            if (tagName.isEmpty() || tagName.startsWith("!")) return null

            if (!isPotentialTagHelperStart(tagName, startTag)) return null

            val tracker = currentTagHelperTracker
            val tagNameScope = tracker?.tagName ?: ""

            // In a start tag block: is it a tag helper?
            val elementAttributes = getAttributeNameValuePairs(startTag)

            val tagHelperBinding = binder.getBinding(tagName, elementAttributes, currentParentTagName, currentParentIsTagHelper)

            // No descriptors: not a tag helper.
            if (tagHelperBinding == null) {
                // The tag of the current tag helper scope: the parent tag helper matched all required attributes but this one did not, the
                // open matching tags counted so that the tag helper block does not end too early (<myth req="..."><myth></myth></myth>: the
                // first myth must not close on the inner tag).
                if (tagNameScope.equals(tagName, ignoreCase = true) && !startTag.isSelfClosing()) tracker!!.openMatchingTags++

                return null
            }

            usedDescriptors?.addAll(tagHelperBinding.tagHelpers)

            validateParentAllowsTagHelper(tagName, startTag)
            validateBinding(tagHelperBinding, tagName, startTag)

            // In a start tag helper block.
            validateStartTagSyntax(tagName, startTag)

            val rewrittenStartTag = TagHelperBlockRewriter.rewrite(tagName, options, startTag, tagHelperBinding, errorSink, source)

            val tagMode = TagHelperBlockRewriter.getTagMode(startTag, endTag, tagHelperBinding)
            return rewrittenStartTag to TagHelperInfo(tagName, tagMode, tagHelperBinding)
        }

        /** `TryRewriteTagHelperEnd`: the rewritten end tag of a tag helper, null when it is not one. */
        private fun tryRewriteTagHelperEnd(startTag: MarkupStartTagSyntax?, endTag: MarkupEndTagSyntax): GMarkupTagHelperEndTag? {
            val tagName = endTag.getTagNameWithOptionalBang()
            // No tag name: not a tag helper, the element is tracked.
            if (tagName.isEmpty() || tagName.startsWith("!")) return null

            val tracker = currentTagHelperTracker
            val tagNameScope = tracker?.tagName ?: ""
            if (!isPotentialTagHelperEnd(tagName, endTag)) return null

            // The end tag against the currently scoped tag, maybe an error.
            if (startTag != null && tagNameScope.equals(tagName, ignoreCase = true)) {
                // More end tags needed before the block is built: <myth req="..."><myth></myth></myth> at the inner </myth>.
                if (tracker!!.openMatchingTags > 0) {
                    tracker.openMatchingTags--

                    return null
                }

                validateEndTagSyntax(tagName, endTag)

                trackerStack.removeAt(trackerStack.size - 1)
            } else {
                // No descriptors of the end tag block that also have no required attributes: not a tag helper.
                val tagHelperBinding = binder.getBinding(tagName, emptyList(), currentParentTagName, currentParentIsTagHelper) ?: return null

                for (boundRulesInfo in tagHelperBinding.allBoundRules) {
                    val invalidRule = boundRulesInfo.rules.firstOrNull { it.tagStructure == TagStructure.WithoutEndTag }

                    if (invalidRule != null) {
                        // An end tag of a tag helper that states it has none.
                        errorSink.onError(
                            RazorDiagnosticFactory.parsing_TagHelperMustNotHaveAnEndTag(
                                SourceSpan(SourceLocationTracker.advance(endTag.getSourceLocation(source), "</"), tagName.length), tagName,
                                boundRulesInfo.descriptor.displayName, invalidRule.tagStructure,
                            ),
                        )

                        return null
                    }
                }
            }

            return GMarkupTagHelperEndTag(
                endTag.openAngle.token, endTag.forwardSlash.token, endTag.bang?.token, endTag.name.token, endTag.miscAttributeContent?.greenNode, endTag.closeAngle.token,
                chunkGenerator = null, editHandler = null,
            )
        }

        private fun validateParentAllowsTagHelper(tagName: String, tagBlock: MarkupStartTagSyntax) {
            if (hasAllowedChildren() && !currentTagHelperTracker!!.allowsChild(tagName, nameIncludesPrefix = true)) {
                onAllowedChildrenStartTagError(currentTagHelperTracker!!, tagName, tagBlock, errorSink, source)
            }
        }

        /** `ValidateBinding`: the descriptors of the tag must agree on their TagStructure (other than Unspecified). */
        private fun validateBinding(bindingResult: TagHelperBinding, tagName: String, tagBlock: MarkupStartTagSyntax) {
            var baseDescriptor: TagHelperDescriptor? = null
            var baseStructure: TagStructure? = null

            for (boundRulesInfo in bindingResult.allBoundRules) {
                val descriptor = boundRulesInfo.descriptor

                for (rule in boundRulesInfo.rules) {
                    if (rule.tagStructure != TagStructure.Unspecified) {
                        // No set of TagHelpers expecting different structures.
                        if (baseStructure != null && baseStructure != rule.tagStructure) {
                            errorSink.onError(
                                RazorDiagnosticFactory.tagHelper_InconsistentTagStructure(
                                    SourceSpan(tagBlock.getSourceLocation(source), tagBlock.width), baseDescriptor!!.displayName, descriptor.displayName, tagName,
                                ),
                            )
                        }

                        baseDescriptor = descriptor
                        baseStructure = rule.tagStructure
                    }
                }
            }
        }

        private fun validateStartTagSyntax(tagName: String, tag: MarkupStartTagSyntax): Boolean {
            // An invalid syntax assumed until the tag meets all the criteria of a valid one.
            if (isPartialStartTag(tag)) {
                val errorStart = getStartTagDeclarationErrorStart(tag, source)

                errorSink.onError(RazorDiagnosticFactory.parsing_TagHelperMissingCloseAngle(SourceSpan(errorStart, tagName.length), tagName))

                return false
            }

            return true
        }

        private fun validateEndTagSyntax(tagName: String, tag: MarkupEndTagSyntax): Boolean {
            // An invalid syntax assumed until the tag meets all the criteria of a valid one.
            if (isPartialEndTag(tag)) {
                val errorStart = getEndTagDeclarationErrorStart(tag, source)

                errorSink.onError(RazorDiagnosticFactory.parsing_TagHelperMissingCloseAngle(SourceSpan(errorStart, tagName.length), tagName))

                return false
            }

            return true
        }

        private fun validateParentAllowsContent(child: RedNode) {
            if (hasAllowedChildren()) {
                var isDisallowedContent = true
                if (options.allowHtmlCommentsInTagHelpers) isDisallowedContent = !isComment(child) && !isTransitionSpanKind(child) && !isCodeSpanKind(child)

                if (isDisallowedContent) {
                    val content = child.getContent()
                    if (content.isNotBlank()) {
                        val trimmedStart = content.trimStart()
                        val whitespace = content.substring(0, content.length - trimmedStart.length)
                        val errorStart = SourceLocationTracker.advance(child.getSourceLocation(source), whitespace)
                        val length = trimmedStart.trimEnd().length
                        val tracker = currentTagHelperTracker!!
                        val allowedChildrenString = tracker.allowedChildren.joinToString(", ")
                        errorSink.onError(RazorDiagnosticFactory.tagHelper_CannotHaveNonTagContent(SourceSpan(errorStart, length), tracker.tagName, allowedChildrenString))
                    }
                }
            }
        }

        private fun validateParentAllowsPlainStartTag(tagBlock: MarkupStartTagSyntax) {
            val tagName = tagBlock.getTagNameWithOptionalBang()

            // A partial tag like '</' without a tag name is content.
            if (tagName.isEmpty()) {
                val firstChild = tagBlock.legacyChildren.first()
                assert(firstChild is MarkupTextLiteralSyntax) // Debug.Assert

                validateParentAllowsContent(firstChild)
                return
            }

            if (!hasAllowedChildren()) return

            val binding = binder.getBinding(tagName, emptyList(), currentParentTagName, currentParentIsTagHelper)

            // A binding for the tag: it is a tag helper, the prefixed allowed children are compared.
            if (!currentTagHelperTracker!!.allowsChild(tagName, nameIncludesPrefix = binding != null)) {
                onAllowedChildrenStartTagError(currentTagHelperTracker!!, tagName, tagBlock, errorSink, source)
            }
        }

        private fun validateParentAllowsPlainEndTag(tagBlock: MarkupEndTagSyntax) {
            val tagName = tagBlock.getTagNameWithOptionalBang()

            // A partial tag like '</' without a tag name is content.
            if (tagName.isEmpty()) {
                val firstChild = tagBlock.legacyChildren.first()
                assert(firstChild is MarkupTextLiteralSyntax) // Debug.Assert

                validateParentAllowsContent(firstChild)
                return
            }

            if (!hasAllowedChildren()) return

            val binding = binder.getBinding(tagName, emptyList(), currentParentTagName, currentParentIsTagHelper)

            // A binding for the tag: it is a tag helper, the prefixed allowed children are compared.
            if (!currentTagHelperTracker!!.allowsChild(tagName, nameIncludesPrefix = binding != null)) {
                onAllowedChildrenEndTagError(currentTagHelperTracker!!, tagName, tagBlock, errorSink, source)
            }
        }

        private fun hasAllowedChildren(): Boolean {
            // TODO: Questionable logic. Need to revisit (as in the compiler).
            val currentTracker = trackerStack.lastOrNull()

            // The current tracker not a TagHelper: no AllowedChildren to enforce.
            if (currentTracker == null || !currentTracker.isTagHelper) return false

            return currentTagHelperTracker!!.allowedChildren.isNotEmpty()
        }

        /** `TagTracker`: an open tag of the stack. */
        private open class TagTracker(val tagName: String, val isTagHelper: Boolean)

        /** `TagHelperTracker`: an open tag helper tag, with its allowed children (with and without the prefix). */
        private class TagHelperTracker(private val tagNamePrefix: String?, info: TagHelperInfo) : TagTracker(info.tagName, isTagHelper = true) {
            var openMatchingTags: Int = 0

            private val binding: TagHelperBinding = info.bindingResult

            private val lazyAllowedChildren: Pair<List<String>, Set<String>> by lazy(LazyThreadSafetyMode.NONE) { createAllowedChildren() }
            private val lazyPrefixedAllowedChildrenNameSet: Set<String> by lazy(LazyThreadSafetyMode.NONE) { createPrefixedAllowedChildren() }

            val allowedChildren: List<String> get() = lazyAllowedChildren.first

            fun allowsChild(tagName: String, nameIncludesPrefix: Boolean): Boolean =
                if (nameIncludesPrefix) tagName in lazyPrefixedAllowedChildrenNameSet else tagName in lazyAllowedChildren.second

            private fun createAllowedChildren(): Pair<List<String>, Set<String>> {
                val distinctSet = TreeSet(String.CASE_INSENSITIVE_ORDER)

                val result = ArrayList<String>()

                for (tagHelper in binding.tagHelpers) {
                    for (allowedChildTag in tagHelper.allowedChildTags) {
                        val name = allowedChildTag.name

                        if (distinctSet.add(name)) result.add(name)
                    }
                }

                return result to distinctSet
            }

            private fun createPrefixedAllowedChildren(): Set<String> {
                val tagNamePrefix = tagNamePrefix ?: return lazyAllowedChildren.second

                val distinctSet = TreeSet(String.CASE_INSENSITIVE_ORDER)

                for (childName in allowedChildren) distinctSet.add(tagNamePrefix + childName)

                return distinctSet
            }
        }

        companion object {
            /** `InvalidAttributeValueMarker`: null characters are invalid markup for HTML attribute values. */
            internal const val INVALID_ATTRIBUTE_VALUE_MARKER = '\u0000'

            /** `GetAttributeNameValuePairs`: the name / value pairs of the attributes of a start tag, for the binder. */
            internal fun getAttributeNameValuePairs(tagBlock: MarkupStartTagSyntax): List<Pair<String, String>> {
                val tagAttributes = tagBlock.attributes
                if (tagAttributes.isEmpty()) return emptyList()

                val attributeValueBuilder = StringBuilder()
                val attributes = ArrayList<Pair<String, String>>()

                for (attribute in tagAttributes) {
                    // Code blocks in the attribute area of tags mangle the following attributes; not supported by TagHelpers either: bail
                    // early to avoid bad attribute value pairs.
                    if (attribute is CSharpCodeBlockSyntax) break

                    if (attribute is MarkupMinimizedAttributeBlockSyntax) {
                        attributes.add(attribute.name.getContent() to "")
                        continue
                    }

                    // Not attributes to the parser: ignored.
                    if (attribute !is MarkupAttributeBlockSyntax) continue

                    val value = attribute.value
                    if (value != null) {
                        for (child in value.children) {
                            if (child is MarkupLiteralAttributeValueSyntax) attributeValueBuilder.append(child.getContent())
                            else attributeValueBuilder.append(INVALID_ATTRIBUTE_VALUE_MARKER)
                        }
                    }

                    val attributeName = attribute.name.getContent()
                    val attributeValue = attributeValueBuilder.toString()
                    attributes.add(attributeName to attributeValue)

                    attributeValueBuilder.setLength(0)
                }

                return attributes
            }

            private fun isPotentialTagHelperStart(tagName: String, startTag: MarkupStartTagSyntax): Boolean =
                !tagName.equals(io.github.dotnetsupport.razor.lang.syntax.SyntaxConstants.TEXT_TAG_NAME, ignoreCase = true) || !startTag.isMarkupTransition

            private fun isPotentialTagHelperEnd(tagName: String, endTag: MarkupEndTagSyntax): Boolean =
                !tagName.equals(io.github.dotnetsupport.razor.lang.syntax.SyntaxConstants.TEXT_TAG_NAME, ignoreCase = true) || !endTag.isMarkupTransition

            private fun isPartialStartTag(startTag: MarkupStartTagSyntax): Boolean = startTag.closeAngle.isMissing

            private fun isPartialEndTag(endTag: MarkupEndTagSyntax): Boolean = endTag.closeAngle.isMissing

            private fun isPartOfStartTag(node: RedNode): Boolean {
                // An ancestor that is the start tag of a MarkupElement?
                val parent = node.firstAncestorOrSelf { n ->
                    val element = n.parent
                    element is MarkupElementSyntax && element.startTag === n
                }

                return parent != null
            }

            private fun isComment(node: RedNode): Boolean = node.firstAncestorOrSelf { it is RazorCommentBlockSyntax || it is MarkupCommentBlockSyntax } != null

            /** `IsTransitionSpanKind`. */
            private fun isTransitionSpanKind(node: RedNode): Boolean = node.kind == RazorSyntaxKind.CSharpTransition || node.kind == RazorSyntaxKind.MarkupTransition

            /** `IsCodeSpanKind`. */
            private fun isCodeSpanKind(node: RedNode): Boolean =
                node.kind == RazorSyntaxKind.CSharpStatementLiteral || node.kind == RazorSyntaxKind.CSharpExpressionLiteral || node.kind == RazorSyntaxKind.CSharpEphemeralTextLiteral

            /** `BaseMarkupStartTagSyntax.IsVoidElement`: by the name, ignoring the case (`ParserHelpers.VoidElements` is OrdinalIgnoreCase). */
            internal fun isVoidElement(startTag: MarkupStartTagSyntax): Boolean = ParserHelpers.isVoidElement(startTag.name.content)

            private fun onAllowedChildrenStartTagError(tracker: TagHelperTracker, tagName: String, tagBlock: MarkupStartTagSyntax, errorSink: ErrorSink, source: RazorSourceDocument) {
                val allowedChildrenString = tracker.allowedChildren.joinToString(", ")
                val errorStart = getStartTagDeclarationErrorStart(tagBlock, source)

                errorSink.onError(RazorDiagnosticFactory.tagHelper_InvalidNestedTag(SourceSpan(errorStart, tagName.length), tagName, tracker.tagName, allowedChildrenString))
            }

            private fun onAllowedChildrenEndTagError(tracker: TagHelperTracker, tagName: String, tagBlock: MarkupEndTagSyntax, errorSink: ErrorSink, source: RazorSourceDocument) {
                val allowedChildrenString = tracker.allowedChildren.joinToString(", ")
                val errorStart = getEndTagDeclarationErrorStart(tagBlock, source)

                errorSink.onError(RazorDiagnosticFactory.tagHelper_InvalidNestedTag(SourceSpan(errorStart, tagName.length), tagName, tracker.tagName, allowedChildrenString))
            }

            private fun getStartTagDeclarationErrorStart(tagBlock: MarkupStartTagSyntax, source: RazorSourceDocument): SourceLocation =
                SourceLocationTracker.advance(tagBlock.getSourceLocation(source), "<")

            private fun getEndTagDeclarationErrorStart(tagBlock: MarkupEndTagSyntax, source: RazorSourceDocument): SourceLocation =
                SourceLocationTracker.advance(tagBlock.getSourceLocation(source), "</")
        }
    }
}

/** `Legacy/TagHelperBlockRewriter`: the start tag of a bound element as a tag helper start tag, its attributes as tag helper attributes. */
object TagHelperBlockRewriter {
    fun getTagMode(startTag: MarkupStartTagSyntax, endTag: MarkupEndTagSyntax?, bindingResult: TagHelperBinding): TagMode {
        val childSpan = startTag.getLastToken()?.parent

        // Self-closing tags are always valid whatever the TagStructure of the descriptors.
        if (childSpan?.getContent()?.endsWith("/>") == true) return TagMode.SelfClosing

        var hasDirectiveAttribute = false
        for (boundRulesInfo in bindingResult.allBoundRules) {
            val nonDefaultRule = boundRulesInfo.rules.firstOrNull { it.tagStructure != TagStructure.Unspecified }

            if (nonDefaultRule?.tagStructure == TagStructure.WithoutEndTag) return TagMode.StartTagOnly

            // A directive attribute tolerates forms that do not work for tag helpers (<input @onclick="..."> vs <input onclick="..." />): adding
            // one must not make it an error.
            val descriptor = boundRulesInfo.descriptor
            if (descriptor.isAnyComponentDocumentTagHelper() && !descriptor.isComponentOrChildContentTagHelper()) hasDirectiveAttribute = true
        }

        if (hasDirectiveAttribute && TagHelperParseTreeRewriter.Rewriter.isVoidElement(startTag) && endTag == null) return TagMode.StartTagOnly

        return TagMode.StartTagAndEndTag
    }

    fun rewrite(
        tagName: String, options: RazorParserOptions, startTag: MarkupStartTagSyntax, binding: TagHelperBinding, errorSink: ErrorSink, source: RazorSourceDocument,
    ): GMarkupTagHelperStartTag {
        val processedBoundAttributeNames = TreeSet(String.CASE_INSENSITIVE_ORDER)

        val attributeBuilder = ArrayList<RazorNode>()

        var attributes: RazorList = startTag.greenNode.attributes
        val startTagAttributes = startTag.attributes

        for (i in startTagAttributes.indices) {
            var isMinimized = false
            var attributeNameLocation = SourceLocation.UNDEFINED
            val child = startTagAttributes[i]
            val result: TryParseResult?
            if (child is MarkupAttributeBlockSyntax) {
                attributeNameLocation = child.name.getSourceLocation(source)
                result = tryParseAttribute(tagName, child, binding.tagHelpers, errorSink, processedBoundAttributeNames, options)
                attributeBuilder.add(result.rewrittenAttribute!!)
            } else if (child is MarkupMinimizedAttributeBlockSyntax) {
                isMinimized = true
                attributeNameLocation = child.name.getSourceLocation(source)
                result = tryParseMinimizedAttribute(tagName, child, binding.tagHelpers, errorSink, processedBoundAttributeNames)
                attributeBuilder.add(result.rewrittenAttribute!!)
            } else if (child is MarkupMiscAttributeContentSyntax) {
                for (contentChild in child.children) {
                    if (contentChild is CSharpCodeBlockSyntax) {
                        // TODO: Accept more than just Markup attributes (aspnet/Razor#96): <input @checked />.
                        val location = SourceSpan(contentChild.getSourceLocation(source), contentChild.width)
                        errorSink.onError(RazorDiagnosticFactory.parsing_TagHelpersCannotHaveCSharpInTagDeclaration(location, tagName))
                        break
                    } else {
                        // Whitespace content means the tag owning this "attribute" is malformed, a user typing a new attribute:
                        // <myTH class="btn"| |
                        val literalContent = contentChild.getContent()
                        if (literalContent.isNotBlank()) {
                            val location = contentChild.getSourceSpan(source)
                            errorSink.onError(RazorDiagnosticFactory.parsing_TagHelperAttributeListMustBeWellFormed(location))
                            break
                        }
                    }
                }

                result = null
            } else if (child is RazorCommentBlockSyntax) {
                // Razor comments in attribute lists are kept, not treated as attributes; the following attributes processed.
                attributeBuilder.add(child.green)
                continue
            } else if (child is MarkupTextLiteralSyntax) {
                // Whitespace between attributes is kept, not treated as an attribute; the following attributes processed.
                val content = child.getContent()
                if (content.isBlank()) {
                    attributeBuilder.add(child.green)
                    continue
                }

                result = null
            } else if (child is MarkupEphemeralTextLiteralSyntax) {
                // Ephemeral literals (escaped @@ in attribute names) are kept; the following attributes processed.
                attributeBuilder.add(child.green)
                continue
            } else {
                result = null
            }

            // The attribute is tracked only if its Block / Span parsed.
            if (result == null) {
                // An error parsing the attribute: the rest not parsed, to avoid misleading errors.
                for (j in i until startTagAttributes.size) attributeBuilder.add(startTagAttributes[j].green)

                break
            }

            // A minimized non-boolean bound attribute, or a bound non-string attribute of null or whitespace content.
            val isValidMinimizedAttribute = options.allowMinimizedBooleanTagHelperAttributes && result.isBoundBooleanAttribute
            if ((isMinimized && result.isBoundAttribute && !isValidMinimizedAttribute) ||
                (!isMinimized && result.isBoundNonStringAttribute && getAttributeValueContent(result.rewrittenAttribute).isNullOrBlank())
            ) {
                val errorLocation = SourceSpan(attributeNameLocation, result.attributeName.length)
                val propertyTypeName = getPropertyType(result.attributeName, binding.tagHelpers)
                errorSink.onError(RazorDiagnosticFactory.tagHelper_EmptyBoundAttribute(errorLocation, result.attributeName, tagName, propertyTypeName))
            }

            // A prefix match of a tag helper dictionary property whose dictionary key would be the empty string.
            if (result.isMissingDictionaryKey) {
                val errorLocation = SourceSpan(attributeNameLocation, result.attributeName.length)
                errorSink.onError(RazorDiagnosticFactory.parsing_TagHelperIndexerAttributeNameMustIncludeKey(errorLocation, result.attributeName, tagName))
            }
        }

        // Something was rewritten: the new set of attributes.
        if (attributeBuilder.size > 0) attributes = RazorList(attributeBuilder)

        return GMarkupTagHelperStartTag(
            startTag.openAngle.token, startTag.bang?.token, startTag.name.token, attributes, startTag.forwardSlash?.token, startTag.closeAngle.token,
            startTag.chunkGenerator, startTag.editHandler,
        )
    }

    private fun tryParseMinimizedAttribute(
        tagName: String, attributeBlock: MarkupMinimizedAttributeBlockSyntax, tagHelpers: TagHelperCollection, errorSink: ErrorSink, processedBoundAttributeNames: MutableSet<String>,
    ): TryParseResult {
        // A name now: the right isBoundNonStringAttribute value.
        val result = createTryParseResult(attributeBlock.name.getContent(), tagHelpers, processedBoundAttributeNames)

        result.attributeStructure = AttributeStructure.Minimized

        if (result.isDirectiveAttribute) {
            // Directive attributes have a different syntax.
            result.rewrittenAttribute = rewriteToMinimizedDirectiveAttribute(attributeBlock, result)

            return result
        }

        result.rewrittenAttribute = GMarkupMinimizedTagHelperAttribute(
            attributeBlock.namePrefix?.greenNode, attributeBlock.name.greenNode,
            TagHelperAttributeInfo(result.attributeName, null, result.attributeStructure, result.isBoundAttribute, isDirectiveAttribute = false),
        )

        return result
    }

    private fun tryParseAttribute(
        tagName: String, attributeBlock: MarkupAttributeBlockSyntax, tagHelpers: TagHelperCollection, errorSink: ErrorSink, processedBoundAttributeNames: MutableSet<String>,
        options: RazorParserOptions,
    ): TryParseResult {
        // A name now: the right isBoundNonStringAttribute value.
        val result = createTryParseResult(attributeBlock.name.getContent(), tagHelpers, processedBoundAttributeNames)

        val valuePrefix = attributeBlock.valuePrefix
        if (valuePrefix == null) {
            // NoQuotes is not persisted even for unbound attributes: the values can still be rewritten with a space (UrlResolutionTagHelper);
            // an expression may be one too: NoQuotes and DoubleQuotes alike, so that dynamic content with spaces does not break attributes
            // (<tag my-attribute=@value /> with "hello world" renders <tag my-attribute="hello world" />).
            result.attributeStructure = AttributeStructure.DoubleQuotes
        } else {
            val lastToken = valuePrefix.getLastToken()
            result.attributeStructure = when (lastToken?.kind) {
                RazorSyntaxKind.DoubleQuote -> AttributeStructure.DoubleQuotes
                RazorSyntaxKind.SingleQuote -> AttributeStructure.SingleQuotes
                else -> AttributeStructure.Minimized
            }
        }

        var attributeValue = attributeBlock.value
        if (attributeValue == null) {
            // A marker for the value when there are no quotes: <p class= >.
            attributeValue = RedTree.of(GGenericBlock(RazorList(listOf(GMarkupTextLiteral(RazorList.EMPTY))))) as RazorBlockSyntax
        }
        val rewrittenValue = rewriteAttributeValue(result, attributeValue, options)

        if (result.isDirectiveAttribute) {
            // Directive attributes have a different syntax.
            result.rewrittenAttribute = rewriteToDirectiveAttribute(attributeBlock, result, rewrittenValue)

            return result
        }

        result.rewrittenAttribute = GMarkupTagHelperAttribute(
            attributeBlock.namePrefix?.greenNode, attributeBlock.name.greenNode, attributeBlock.nameSuffix?.greenNode, attributeBlock.equalsToken.token,
            attributeBlock.valuePrefix?.greenNode, rewrittenValue, attributeBlock.valueSuffix?.greenNode,
            TagHelperAttributeInfo(result.attributeName, null, result.attributeStructure, result.isBoundAttribute, isDirectiveAttribute = false),
        )

        return result
    }

    /**
     * `RewriteToDirectiveAttribute`: `<Foo @bind:param="..." />` — the name `@bind:param` of a MarkupAttributeBlock split in four for a
     * MarkupTagHelperDirectiveAttribute: `@` the transition (meta code), `bind` the name (text), `:` the colon (meta code), `param` the
     * parameter name (text).
     */
    private fun rewriteToDirectiveAttribute(attributeBlock: MarkupAttributeBlockSyntax, result: TryParseResult, rewrittenValue: GMarkupTagHelperAttributeValue): GMarkupTagHelperDirectiveAttribute {
        val parts = splitDirectiveAttributeName(result.attributeName, attributeBlock.name.greenNode)

        return GMarkupTagHelperDirectiveAttribute(
            attributeBlock.namePrefix?.greenNode, parts.transition, parts.name, parts.colon, parts.parameterName, attributeBlock.nameSuffix?.greenNode,
            attributeBlock.equalsToken.token, attributeBlock.valuePrefix?.greenNode, rewrittenValue, attributeBlock.valueSuffix?.greenNode,
            TagHelperAttributeInfo(result.attributeName, parts.parameterName?.text(), result.attributeStructure, result.isBoundAttribute, isDirectiveAttribute = true),
        )
    }

    /** `RewriteToMinimizedDirectiveAttribute`: `<Foo @bind:param />` split as [rewriteToDirectiveAttribute] does. */
    private fun rewriteToMinimizedDirectiveAttribute(attributeBlock: MarkupMinimizedAttributeBlockSyntax, result: TryParseResult): GMarkupMinimizedTagHelperDirectiveAttribute {
        val parts = splitDirectiveAttributeName(result.attributeName, attributeBlock.name.greenNode)

        return GMarkupMinimizedTagHelperDirectiveAttribute(
            attributeBlock.namePrefix?.greenNode, parts.transition, parts.name, parts.colon, parts.parameterName,
            TagHelperAttributeInfo(result.attributeName, parts.parameterName?.text(), result.attributeStructure, result.isBoundAttribute, isDirectiveAttribute = true),
        )
    }

    private class DirectiveAttributeParts(val transition: GRazorMetaCode, val name: GMarkupTextLiteral, val colon: GRazorMetaCode?, val parameterName: GMarkupTextLiteral?)

    /** The common part of the two `RewriteTo*DirectiveAttribute` (the same statements in the compiler). */
    private fun splitDirectiveAttributeName(fullName: String, nameSyntax: GMarkupTextLiteral): DirectiveAttributeParts {
        var attributeName = fullName
        var attributeNameSyntax = nameSyntax
        var transition = GRazorMetaCode(RazorList(listOf(RazorToken.missing(RazorSyntaxKind.Transition))))
        var colon: GRazorMetaCode? = null
        var parameterName: GMarkupTextLiteral? = null
        if (attributeName.startsWith("@")) {
            attributeName = attributeName.substring(1)
            val attributeNameToken = RazorToken(RazorSyntaxKind.Text, attributeName)
            attributeNameSyntax = GMarkupTextLiteral(RazorList(listOf(attributeNameToken)))

            val transitionToken = RazorToken(RazorSyntaxKind.Transition, "@")
            transition = GRazorMetaCode(RazorList(listOf(transitionToken)))
        }

        if (attributeName.indexOf(':') != -1) {
            val segments = attributeName.split(':', limit = 2)

            val attributeNameToken = RazorToken(RazorSyntaxKind.Text, segments[0])
            attributeNameSyntax = GMarkupTextLiteral(RazorList(listOf(attributeNameToken)))

            val colonToken = RazorToken(RazorSyntaxKind.Colon, ":")
            colon = GRazorMetaCode(RazorList(listOf(colonToken)))

            val parameterNameToken = RazorToken(RazorSyntaxKind.Text, segments[1])
            parameterName = GMarkupTextLiteral(RazorList(listOf(parameterNameToken)))
        }

        return DirectiveAttributeParts(transition, attributeNameSyntax, colon, parameterName)
    }

    private fun rewriteAttributeValue(result: TryParseResult, attributeValue: RazorBlockSyntax, options: RazorParserOptions): GMarkupTagHelperAttributeValue {
        val rewriter = AttributeValueRewriter(result, options)
        var rewrittenValue = attributeValue
        if (result.isBoundAttribute) {
            // An attribute requested by a tag helper whose property is not a string: its value treated as code (any C# value), and the tree
            // must reflect that.
            rewrittenValue = rewriter.visit(attributeValue) as RazorBlockSyntax
        }

        return GMarkupTagHelperAttributeValue((rewrittenValue.green as GRazorBlock).children)
    }

    /** `GetPropertyType`: the full type name of the property an attribute of the name corresponds to. */
    private fun getPropertyType(name: String, tagHelpers: TagHelperCollection): String? {
        for (tagHelper in tagHelpers) {
            val match = TagHelperMatchingConventions.tryGetFirstBoundAttributeMatch(tagHelper, name)
            if (match != null) return if (match.isIndexerMatch) match.attribute.indexerTypeName else match.attribute.typeName
        }

        return null
    }

    /** `CreateTryParseResult`: the result for a name, with the binding details filled in. */
    private fun createTryParseResult(name: String, tagHelpers: TagHelperCollection, processedBoundAttributeNames: MutableSet<String>): TryParseResult {
        var isBoundAttribute = false
        var isBoundNonStringAttribute = false
        var isBoundBooleanAttribute = false
        var isMissingDictionaryKey = false
        var isDirectiveAttribute = false
        var isDuplicateAttribute = false

        for (tagHelper in tagHelpers) {
            val match = TagHelperMatchingConventions.tryGetFirstBoundAttributeMatch(tagHelper, name)
            if (match != null) {
                isBoundAttribute = true
                isBoundNonStringAttribute = !match.expectsStringValue
                isBoundBooleanAttribute = match.expectsBooleanValue

                if (!match.isParameterMatch) isMissingDictionaryKey = match.attribute.indexerNamePrefix?.length == name.length

                isDirectiveAttribute = match.attribute.isDirectiveAttribute

                // A bound attribute of the same name already processed.
                if (!processedBoundAttributeNames.add(name)) isDuplicateAttribute = true

                break
            }
        }

        return TryParseResult(name).also {
            it.isBoundAttribute = isBoundAttribute
            it.isBoundNonStringAttribute = isBoundNonStringAttribute
            it.isBoundBooleanAttribute = isBoundBooleanAttribute
            it.isMissingDictionaryKey = isMissingDictionaryKey
            it.isDuplicateAttribute = isDuplicateAttribute
            it.isDirectiveAttribute = isDirectiveAttribute
        }
    }

    private fun getAttributeValueContent(attributeBlock: RazorNode?): String? = when (attributeBlock) {
        is GMarkupTagHelperAttribute -> attributeBlock.value.text()
        is GMarkupTagHelperDirectiveAttribute -> attributeBlock.value.text()
        is GMarkupAttributeBlock -> attributeBlock.value?.text()
        else -> null
    }

    /** `AttributeValueRewriter`: the value of a bound attribute; a non-string one rewritten as C# (the transitions after the first as code). */
    private class AttributeValueRewriter(private val tryParseResult: TryParseResult, private val options: RazorParserOptions) : RedSyntaxRewriter() {
        private var rewriteAsMarkup = false

        override fun visitGenericBlock(node: GenericBlockSyntax): RedNode? {
            if (tryParseResult.isBoundNonStringAttribute && canBeCollapsed(node)) {
                var tokens = node.descendantTokens().map { it.token }.toList()

                val transition = tokens.firstOrNull()
                if (transition != null && transition.kind == RazorSyntaxKind.Transition) {
                    // The transition lifted, so that it is not part of the generated output.
                    tokens = tokens.subList(1, tokens.size)
                    val children = createExpressionLiteral(tokens)
                    return RedTree.of(
                        GMarkupBlock(
                            RazorList(
                                listOf(GCSharpCodeBlock(RazorList(listOf(GCSharpImplicitExpression(GCSharpTransition(transition), GCSharpImplicitExpressionBody(GCSharpCodeBlock(children))))))),
                            ),
                        ),
                    )
                }

                val updated = GGenericBlock(createExpressionLiteral(tokens))
                updated.diagnostics = node.green.diagnostics
                return RedTree.of(updated)
            }

            return super.visitGenericBlock(node)
        }

        private fun createExpressionLiteral(tokens: List<RazorToken>): RazorList {
            val expression = RedTree.of(GCSharpExpressionLiteral(RazorList(tokens))) as CSharpExpressionLiteralSyntax
            val rewrittenExpression = visitCSharpExpressionLiteral(expression) as CSharpExpressionLiteralSyntax
            return RazorList(listOf(rewrittenExpression.green))
        }

        override fun visitCSharpTransition(node: CSharpTransitionSyntax): RedNode? {
            if (!tryParseResult.isBoundNonStringAttribute) return super.visitCSharpTransition(node)

            // A bound non-string attribute allows a transition only at the very beginning of the expression; the later transitions are code,
            // part of the generated output:
            // key="@value" -> MyTagHelper.key = value
            // key=" @value" -> MyTagHelper.key =  @value
            // key="1 + @case" -> MyTagHelper.key = 1 + @case
            // key="@int + @case" -> MyTagHelper.key = int + @case
            // key="@(a + b) -> MyTagHelper.key = a + b
            // key="4 + @(a + b)" -> MyTagHelper.key = 4 + @(a + b)
            if (rewriteAsMarkup) {
                // A MarkupChunkGenerator, so that the '@' / parenthesis is generated as part of the output.
                val expression = RedTree.of(GCSharpExpressionLiteral(RazorList(listOf(node.transition.token)), ChunkGenerator.Markup, node.editHandler)) as CSharpExpressionLiteralSyntax

                return super.visitCSharpExpressionLiteral(expression)
            }

            rewriteAsMarkup = true
            return super.visitCSharpTransition(node)
        }

        override fun visitCSharpImplicitExpression(node: CSharpImplicitExpressionSyntax): RedNode? {
            if (rewriteAsMarkup) {
                val builder = ArrayList<RazorNode>()

                val rewrittenBody = visitCSharpCodeBlock((node.body as CSharpImplicitExpressionBodySyntax).csharpCode) as CSharpCodeBlockSyntax

                // The transition becomes part of the first token of the rewritten body, so that the tracking spans and the C# written do not
                // treat the `@` as a separate token from the content after it.
                val bodyChildren = rewrittenBody.children
                val firstChild = bodyChildren[0]
                val firstToken = firstChild.getFirstToken()
                var newFirstChild = firstChild.green
                if (firstToken != null) {
                    val newFirstToken = RazorToken(firstToken.kind, node.transition.transition.content + firstToken.content)
                    newFirstChild = replaceToken(firstChild.green, firstToken.token, newFirstToken)
                }
                builder.add(newFirstChild)
                for (k in 1 until bodyChildren.size) builder.add(bodyChildren[k].green)

                // The original transition is part of the body: something takes its place.
                val transition = GCSharpTransition(RazorToken.missing(RazorSyntaxKind.Transition))

                val rewrittenCodeBlock = GCSharpCodeBlock(RazorList(builder))
                return RedTree.of(GCSharpImplicitExpression(transition, GCSharpImplicitExpressionBody(rewrittenCodeBlock)))
            }

            return super.visitCSharpImplicitExpression(node)
        }

        override fun visitCSharpExplicitExpression(node: CSharpExplicitExpressionSyntax): RedNode? {
            val transition: GCSharpTransition
            val builder = ArrayList<RazorNode>()

            if (rewriteAsMarkup) {
                // The transition converted: a MarkupChunkGenerator, so that the '@' / parenthesis is generated as part of the output. Bad code,
                // since @( is never valid C#: the @ and the ( are not stitched together.
                val editHandler = if (options.enableSpanEditHandlers) defaultEditHandler(AcceptedCharacters.ANY) else null

                var expression = RedTree.of(GCSharpExpressionLiteral(RazorList(listOf(node.transition.transition.token)), ChunkGenerator.Markup, editHandler)) as CSharpExpressionLiteralSyntax
                expression = visitCSharpExpressionLiteral(expression) as CSharpExpressionLiteralSyntax
                builder.add(expression.green)

                // The original transition is part of the body: something takes its place.
                transition = GCSharpTransition(RazorToken.missing(RazorSyntaxKind.Transition))
            } else {
                // The first expression of a non-string attribute like attr=@(a + b): made an implicit expression so that the parens are part of
                // the expression, rendered.
                transition = (visit(node.transition) as CSharpTransitionSyntax).greenNode
            }

            val body = node.body as CSharpExplicitExpressionBodySyntax
            val rewrittenOpenParen = visitRazorMetaCode(body.openParen)!!
            val rewrittenBody = visitCSharpCodeBlock(body.csharpCode) as CSharpCodeBlockSyntax
            val rewrittenCloseParen = visitRazorMetaCode(body.closeParen)!!
            builder.add(rewrittenOpenParen.green)
            for (child in rewrittenBody.children) builder.add(child.green)
            builder.add(rewrittenCloseParen.green)

            val rewrittenCodeBlock = GCSharpCodeBlock(RazorList(builder))
            return RedTree.of(GCSharpImplicitExpression(transition, GCSharpImplicitExpressionBody(rewrittenCodeBlock)))
        }

        override fun visitRazorMetaCode(node: RazorMetaCodeSyntax): RedNode? {
            if (!tryParseResult.isBoundNonStringAttribute) return super.visitRazorMetaCode(node)

            if (rewriteAsMarkup) {
                // A MarkupChunkGenerator, so that the '@' / parenthesis is generated as part of the output.
                val expression = RedTree.of(GCSharpExpressionLiteral(node.greenNode.metaCode, ChunkGenerator.Markup, node.editHandler)) as CSharpExpressionLiteralSyntax

                return visitCSharpExpressionLiteral(expression)
            }

            rewriteAsMarkup = true
            return super.visitRazorMetaCode(node)
        }

        // Code blocks are not supported inside tag helper attributes (<p age="@{1 + 2}">): nothing rewritten inside.
        override fun visitCSharpStatement(node: CSharpStatementSyntax): RedNode? = node

        // Directives are not supported inside tag helper attributes: nothing rewritten inside.
        override fun visitRazorUsingDirective(node: RazorUsingDirectiveSyntax): RedNode? = node

        override fun visitRazorDocumentationDirective(node: RazorDocumentationDirectiveSyntax): RedNode? = node

        // <p age="@functions { }"> is not supported: nothing rewritten inside.
        override fun visitRazorDirective(node: RazorDirectiveSyntax): RedNode? = node

        // A MarkupElement in an attribute value means the value is invalid (<my age="@if (true) { <my4 age=... }"></my4>): nothing rewritten.
        override fun visitMarkupElement(node: MarkupElementSyntax): RedNode? = node

        override fun visitCSharpExpressionLiteral(node: CSharpExpressionLiteralSyntax): RedNode? {
            if (!tryParseResult.isBoundNonStringAttribute) return super.visitCSharpExpressionLiteral(node)

            val configured = configureNonStringAttribute(node) as CSharpExpressionLiteralSyntax

            rewriteAsMarkup = true
            return super.visitCSharpExpressionLiteral(configured)
        }

        override fun visitMarkupLiteralAttributeValue(node: MarkupLiteralAttributeValueSyntax): RedNode? {
            val builder = ArrayList<RazorToken>()

            node.prefix?.let { builder.addAll(it.greenNode.literalTokens.tokens) }

            node.value?.let { builder.addAll(it.greenNode.literalTokens.tokens) }

            if (tryParseResult.isBoundNonStringAttribute) {
                rewriteAsMarkup = true
                // A bound non-string attribute: the LiteralAttributeValue becomes just a C# expression literal.
                val expression = RedTree.of(GCSharpExpressionLiteral(RazorList(builder))) as CSharpExpressionLiteralSyntax
                return visitCSharpExpressionLiteral(expression)
            }

            val literal = RedTree.of(GMarkupTextLiteral(RazorList(builder), node.value?.chunkGenerator, node.value?.editHandler))

            return visit(literal)
        }

        override fun visitMarkupDynamicAttributeValue(node: MarkupDynamicAttributeValueSyntax): RedNode? {
            // The prefix moved into the actual value.
            val builder = ArrayList<RazorNode>()

            node.prefix?.let { builder.add(it.green) }

            for (child in node.value.children) builder.add(child.green)

            val rewrittenValue = RedTree.of(GMarkupBlock(RazorList(builder))) as MarkupBlockSyntax

            return super.visitMarkupBlock(rewrittenValue)
        }

        override fun visitCSharpStatementLiteral(node: CSharpStatementLiteralSyntax): RedNode? {
            if (!tryParseResult.isBoundNonStringAttribute) return super.visitCSharpStatementLiteral(node)

            rewriteAsMarkup = true
            return super.visitCSharpStatementLiteral(node)
        }

        override fun visitMarkupTextLiteral(node: MarkupTextLiteralSyntax): RedNode? {
            if (!tryParseResult.isBoundNonStringAttribute) return super.visitMarkupTextLiteral(node)

            rewriteAsMarkup = true
            val configured = configureNonStringAttribute(node) as MarkupTextLiteralSyntax
            return RedTree.of(GCSharpExpressionLiteral(configured.greenNode.literalTokens, configured.chunkGenerator, configured.editHandler))
        }

        override fun visitMarkupEphemeralTextLiteral(node: MarkupEphemeralTextLiteralSyntax): RedNode? {
            if (!tryParseResult.isBoundNonStringAttribute) return super.visitMarkupEphemeralTextLiteral(node)

            // A non-string attribute: rewritten as code, a CSharpEphemeralTextLiteral so that it is not rendered to the output.
            rewriteAsMarkup = true
            val configured = configureNonStringAttribute(node) as MarkupEphemeralTextLiteralSyntax
            return RedTree.of(GCSharpEphemeralTextLiteral(configured.greenNode.literalTokens, configured.chunkGenerator, configured.editHandler))
        }

        /**
         * `CanBeCollapsed`: a block of several identical-looking literal attribute values (`@onclick="() => SomeMethod()"` is 3 children:
         * `()`, ` =>`, ` SomeMethod()`; the parser splits attribute values at whitespace).
         */
        private fun canBeCollapsed(node: GenericBlockSyntax): Boolean {
            // Already collapsed, or no children.
            if (node.children.size <= 1) return false

            for (child in node.children) {
                val kind = child.kind
                // Dynamic values are collapsed in a legacy file only: mixed C# / HTML content is not allowed in components.
                if (kind != RazorSyntaxKind.MarkupLiteralAttributeValue && (kind != RazorSyntaxKind.MarkupDynamicAttributeValue || options.fileKind != RazorFileKind.Legacy)) return false
            }

            return true
        }

        private fun configureNonStringAttribute(node: RedNode): RedNode {
            var context: SpanEditHandler? = editHandlerOf(node)
            val builder = if (options.enableSpanEditHandlers) {
                SpanEditHandlerBuilder().also { builder ->
                    builder.hasTokenizer = context != null
                    builder.factory = { acceptedCharacters -> ImplicitExpressionEditHandler(acceptedCharacters, acceptTrailingDot = true, keywords = DEFAULT_KEYWORDS) }
                }
            } else null

            val originalGenerator = node.chunkGeneratorOrNull
            var newGenerator: ChunkGenerator = originalGenerator ?: ChunkGenerator.None
            if (!tryParseResult.isDuplicateAttribute && originalGenerator != null && originalGenerator !== ChunkGenerator.None) {
                // The value of a non-string bound attribute marked as C#, except when the span is not rendered (the transition '@') and for a
                // duplicate of a bound attribute (rendered as html).
                newGenerator = ChunkGenerator.Expression
            }

            context = builder?.build(AcceptedCharacters.ANY_EXCEPT_NEWLINE)

            return if (originalGenerator !== newGenerator) updateLiteral(node, newGenerator, context) else withEditHandler(node, context)
        }

        private companion object {
            /** `CSharpCodeParser.DefaultKeywords`. */
            val DEFAULT_KEYWORDS = KeywordSet(CSharpCodeParser.DEFAULT_KEYWORDS.toHashSet())

            fun editHandlerOf(node: RedNode): SpanEditHandler? = when (node) {
                is CSharpExpressionLiteralSyntax -> node.editHandler
                is MarkupTextLiteralSyntax -> node.editHandler
                is MarkupEphemeralTextLiteralSyntax -> node.editHandler
                else -> null
            }

            /** `ILegacySyntax.Update(chunkGenerator, editHandler)` of the literal nodes the rewriter configures. */
            fun updateLiteral(node: RedNode, chunkGenerator: ChunkGenerator?, editHandler: SpanEditHandler?): RedNode {
                val updated: io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxNode = when (node) {
                    is CSharpExpressionLiteralSyntax -> {
                        if (chunkGenerator === node.chunkGenerator && editHandler === node.editHandler) return node
                        GCSharpExpressionLiteral(node.greenNode.literalTokens, chunkGenerator, editHandler)
                    }
                    is MarkupTextLiteralSyntax -> {
                        if (chunkGenerator === node.chunkGenerator && editHandler === node.editHandler) return node
                        GMarkupTextLiteral(node.greenNode.literalTokens, chunkGenerator, editHandler)
                    }
                    is MarkupEphemeralTextLiteralSyntax -> {
                        if (chunkGenerator === node.chunkGenerator && editHandler === node.editHandler) return node
                        GMarkupEphemeralTextLiteral(node.greenNode.literalTokens, chunkGenerator, editHandler)
                    }
                    else -> throw IllegalStateException("Unexpected node type ${node.kind}")
                }
                updated.diagnostics = node.green.diagnostics
                return RedTree.of(updated)
            }

            /** `ILegacySyntax.WithEditHandler`. */
            fun withEditHandler(node: RedNode, editHandler: SpanEditHandler?): RedNode = updateLiteral(node, node.chunkGeneratorOrNull, editHandler)

            /** `SyntaxNode.ReplaceToken` of the green tree: [node] with [oldToken] (by identity) replaced. */
            fun replaceToken(node: RazorNode, oldToken: RazorToken, newToken: RazorToken): RazorNode {
                if (node === oldToken) return newToken
                if (node is RazorToken) return node
                var changed = false
                val slots = arrayOfNulls<RazorNode>(node.slotCount)
                for (i in 0 until node.slotCount) {
                    val slot = node.slot(i) ?: continue
                    if (slot is RazorList) {
                        val items = slot.nodes.map { replaceToken(it, oldToken, newToken) }
                        if (items.indices.any { items[it] !== slot.nodes[it] }) {
                            changed = true
                            slots[i] = RazorList(items)
                        } else slots[i] = slot
                    } else {
                        val replaced = replaceToken(slot, oldToken, newToken)
                        if (replaced !== slot) changed = true
                        slots[i] = replaced
                    }
                }
                return if (changed) GreenNodeUpdate.update(node, slots) else node
            }
        }
    }

    /** `TryParseResult`. */
    private class TryParseResult(val attributeName: String) {
        var rewrittenAttribute: RazorNode? = null
        var attributeStructure: AttributeStructure = AttributeStructure.DoubleQuotes
        var isBoundAttribute: Boolean = false
        var isBoundNonStringAttribute: Boolean = false
        var isBoundBooleanAttribute: Boolean = false
        var isMissingDictionaryKey: Boolean = false
        var isDuplicateAttribute: Boolean = false
        var isDirectiveAttribute: Boolean = false
    }
}
