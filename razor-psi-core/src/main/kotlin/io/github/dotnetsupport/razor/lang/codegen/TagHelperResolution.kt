package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.codegen.components.ComponentDiagnosticFactory
import io.github.dotnetsupport.razor.lang.codegen.components.isAnyComponentDocumentTagHelper
import io.github.dotnetsupport.razor.lang.codegen.components.isComponentOrChildContentTagHelper
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.ComponentAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeCollection
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeFactory
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.MarkupBlockIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MarkupElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ReferenceCaptureIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.SetKeyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.SplatIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperBodyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperDirectiveAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperDirectiveAttributeParameterIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperHtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperPropertyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UnresolvedAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UnresolvedAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UnresolvedElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UnresolvedExpressionAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.parser.ParserHelpers
import io.github.dotnetsupport.razor.lang.syntax.AttributeStructure
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticFactory
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import io.github.dotnetsupport.razor.lang.syntax.TagHelperAttributeMatch
import io.github.dotnetsupport.razor.lang.syntax.TagHelperBinder
import io.github.dotnetsupport.razor.lang.syntax.TagHelperBinding
import io.github.dotnetsupport.razor.lang.syntax.TagHelperCollection
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperMatchingConventions
import io.github.dotnetsupport.razor.lang.syntax.TagMode
import io.github.dotnetsupport.razor.lang.syntax.TagStructure
import io.github.dotnetsupport.razor.lang.syntax.isComponentOrChildContentKind
import java.util.TreeSet

/**
 * `DefaultTagHelperResolutionPhase` (with its `LegacyTagHelperResolver` and `ComponentTagHelperResolver` partials): after the lowering and the
 * tag helper discovery, every [UnresolvedElementIntermediateNode] becomes a [TagHelperIntermediateNode] when the element binds to tag
 * helpers, or plain markup when it does not (`HtmlContent` of a view, a [MarkupElementIntermediateNode] of a component). Works on the IR
 * only, no syntax tree access; the source document is read for the spans the IR lost (explicit expressions, `@@` escapes).
 */
class DefaultTagHelperResolutionPhase : RazorEnginePhaseBase() {
    /** The entry: resolves the unresolved elements of the tree; without descriptors, [unwrapAllElements] turns them all to their fallback. */
    override fun executeCore(codeDocument: RazorCodeDocument): RazorCodeDocument {
        val documentNode = codeDocument.getDocumentNode() ?: return codeDocument

        val tagHelperContext = codeDocument.getTagHelperContext()
        val parserOptions = codeDocument.parserOptions

        // The resolver by file kind and language version: the component features (MarkupElementIntermediateNode, RZ10012) need 3.0+,
        // as the ComponentDocumentClassifierPass is registered from that version only.
        val resolver: TagHelperResolver =
            if ((codeDocument.fileKind.isComponent || codeDocument.fileKind == RazorFileKind.ComponentImport) && parserOptions.languageVersion >= RazorLanguageVersion.VERSION_3_0) {
                ComponentTagHelperResolver()
            } else LegacyTagHelperResolver()

        if (tagHelperContext == null || tagHelperContext.tagHelpers.isEmpty()) {
            // No tag helpers discovered: all UnresolvedElement nodes unwrapped to their fallback.
            unwrapAllElements(documentNode, resolver, documentNode)

            // The referenced tag helpers are still set for the downstream phases.
            return codeDocument.withReferencedTagHelpers(TagHelperCollection.EMPTY)
        }

        val binder = tagHelperContext.binder
        val prefix = tagHelperContext.prefix

        val usedHelpers = LinkedHashSet<TagHelperDescriptor>()
        val context = ResolutionContext(codeDocument.source, documentNode, resolver)
        resolveElements(documentNode, binder, prefix, usedHelpers, context)

        // The descriptor validation diagnostics (e.g. RZ3003).
        val descriptorDiagnostics = ArrayList<RazorDiagnostic>()
        for (descriptor in tagHelperContext.tagHelpers) descriptorDiagnostics.addAll(descriptor.getAllDiagnostics())
        for (diagnostic in descriptorDiagnostics) documentNode.addDiagnostic(diagnostic)

        return codeDocument.withReferencedTagHelpers(TagHelperCollection(usedHelpers.toList()))
    }

    /** `ResolutionContext`: the ambient state of the resolution. */
    private class ResolutionContext(val sourceDocument: RazorSourceDocument, val documentNode: DocumentIntermediateNode, val resolver: TagHelperResolver)

    private fun resolveElements(
        node: IntermediateNode, binder: TagHelperBinder, prefix: String?, usedHelpers: MutableSet<TagHelperDescriptor>, context: ResolutionContext,
        unresolvedParentElement: UnresolvedElementIntermediateNode? = null,
    ) {
        // The children in reverse order, since nodes may be replaced.
        for (i in node.children.size - 1 downTo 0) {
            val child = node.children[i]
            if (child is UnresolvedElementIntermediateNode) {
                // This element first: a component tag helper moves the body children into its body node, and the re-resolution of the body
                // handles them with the parent context (child content elements like <ChildContent> bind by their parent tag helper).
                resolveElement(node, i, child, binder, prefix, usedHelpers, context, unresolvedParentElement = unresolvedParentElement)
            } else {
                // Not an element: its children.
                resolveElements(child, binder, prefix, usedHelpers, context, unresolvedParentElement)
            }
        }
    }

    /**
     * `ResolveElement`: binds one unresolved element by its tag name and attributes; a match becomes a [TagHelperIntermediateNode] in its
     * place, otherwise the resolver turns the element back into plain markup. Returns the tag helper node, null for plain HTML.
     */
    private fun resolveElement(
        parent: IntermediateNode, index: Int, elementNode: UnresolvedElementIntermediateNode, binder: TagHelperBinder, prefix: String?,
        usedHelpers: MutableSet<TagHelperDescriptor>, context: ResolutionContext, tagHelperParent: TagHelperIntermediateNode? = null,
        unresolvedParentElement: UnresolvedElementIntermediateNode? = null,
    ): TagHelperIntermediateNode? {
        // An escaped tag helper (<!tagname>) never matches.
        if (elementNode.isEscaped) {
            convertToPlainElementAndResolve(parent, index, elementNode, binder, prefix, usedHelpers, context, emitDiagnostics = false)
            return null
        }

        // The pre-extracted attribute data for the binding.
        val tagName = elementNode.tagName
        val attributes = elementNode.attributeData ?: emptyList()

        // An end-tag-only element (</body> without its <body>) does not match tag helpers.
        if (elementNode.startTagNameSpan == null && !elementNode.isSelfClosing && attributes.isEmpty()) {
            tryAddMalformedEndTagDiagnostic(elementNode, tagName, binder, attributes, parent, tagHelperParent, prefix)

            context.resolver.convertToPlainElement(parent, index, elementNode)
            return null
        }

        val (parentTagName, parentIsTagHelper) = getParentTagInfo(parent, tagHelperParent, prefix, unresolvedParentElement)
        val binding = binder.getBinding(tagName, attributes, parentTagName, parentIsTagHelper)
        if (binding == null) {
            convertToPlainElementAndResolve(parent, index, elementNode, binder, prefix, usedHelpers, context)
            return null
        }

        // The tag helper node (binding validation, node, diagnostics, body).
        val (tagHelperNode, bodyNode) = buildTagHelperNode(elementNode, binding, tagName, prefix, usedHelpers, context)

        // The body children still unresolved.
        resolveBodyChildren(bodyNode, binder, prefix, usedHelpers, context, tagHelperNode, binding)

        // The UnresolvedElement replaced with the TagHelperIntermediateNode.
        parent.children[index] = tagHelperNode

        // A StartTagOnly element: the body content of the original element belongs to the parent, not to the tag helper.
        if (tagHelperNode.tagMode == TagMode.StartTagOnly) {
            val startTagEndIdx = elementNode.startTagEndIndex
            val bodyEndIdx = elementNode.bodyEndIndex

            if (startTagEndIdx >= 0 && bodyEndIdx >= 0) {
                var insertIdx = index + 1
                for (i in startTagEndIdx until bodyEndIdx) parent.children.add(insertIdx++, elementNode.children[i])

                // The promoted nodes were parsed as body children because the HTML parser nests unclosed tags (`<a><b><c>` is a > b > c);
                // bound as StartTagOnly, they are siblings that may be tag helpers themselves. The outer walk goes in reverse and does not
                // revisit the inserted positions: resolved here, in reverse (a promoted StartTagOnly sibling can insert siblings after it).
                for (j in insertIdx - 1 downTo index + 1) {
                    if (j < parent.children.size) {
                        val promoted = parent.children[j]
                        if (promoted is UnresolvedElementIntermediateNode) {
                            // tagHelperParent forwarded: the promoted siblings share this element's container and parent tag context
                            // (RequireParentTag, component child content).
                            resolveElement(parent, j, promoted, binder, prefix, usedHelpers, context, tagHelperParent, unresolvedParentElement)
                        } else {
                            resolveElements(parent.children[j], binder, prefix, usedHelpers, context, unresolvedParentElement)
                        }
                    }
                }
            }
        }

        return tagHelperNode
    }

    /**
     * `BuildTagHelperNode`: the [TagHelperIntermediateNode] of a confirmed binding with the binding-level diagnostics; the body node is built
     * by the resolver.
     */
    private fun buildTagHelperNode(
        elementNode: UnresolvedElementIntermediateNode, binding: TagHelperBinding, tagName: String, prefix: String?,
        usedHelpers: MutableSet<TagHelperDescriptor>, context: ResolutionContext,
    ): Pair<TagHelperIntermediateNode, TagHelperBodyIntermediateNode> {
        // It IS a tag helper: the used helpers tracked.
        usedHelpers.addAll(binding.tagHelpers)

        // The tag name without the prefix.
        var resolvedTagName = tagName
        if (prefix != null && resolvedTagName.startsWith(prefix, ignoreCase = true)) resolvedTagName = resolvedTagName.substring(prefix.length)

        val tagMode = getTagMode(elementNode, binding)

        val tagHelperNode = TagHelperIntermediateNode(tagMode, resolvedTagName, elementNode.startTagNameSpan, binding.tagHelpers)
        tagHelperNode.source = elementNode.source

        // The resolver's diagnostics (RZ10012 of component-like elements, the case mismatch of the start and end tags).
        context.resolver.addMatchedElementDiagnostics(tagHelperNode, elementNode, binding, context)

        // A void element by the resolved name too (prefixed elements like th:input).
        val isResolvedVoidElement = elementNode.isVoidElement || ParserHelpers.isVoidElement(resolvedTagName)

        // Directive-attribute-only matches (@bind, @onclick alone) get no structural diagnostics, as the rewriter emits none for them.
        if (!isDirectiveAttributeOnly(binding)) addStructuralDiagnostics(tagHelperNode, elementNode, tagName, tagMode, isResolvedVoidElement)

        // An inconsistent TagStructure across the bound rules (RZ2011).
        validateConsistentTagStructure(tagHelperNode, binding, elementNode, tagName)

        // The body and the attributes.
        val bodyNode = TagHelperBodyIntermediateNode()
        context.resolver.buildTagHelper(tagHelperNode, bodyNode, elementNode, binding, context.sourceDocument, context)

        return tagHelperNode to bodyNode
    }

    /**
     * `ResolveBodyChildren`: the body children of a new tag helper node (those in C# blocks too), resolved in reverse with the tag helper as
     * the parent context — the binder needs the parent tag name for component child content (Found / NotFound of Router) and for the
     * RequireParentTag of tag helpers (<td> in <tr>).
     */
    private fun resolveBodyChildren(
        parent: IntermediateNode, binder: TagHelperBinder, prefix: String?, usedHelpers: MutableSet<TagHelperDescriptor>, context: ResolutionContext,
        tagHelperParent: TagHelperIntermediateNode, binding: TagHelperBinding,
    ) {
        val allowedNames = ArrayList<String>()
        for (tagHelper in binding.tagHelpers) for (childTag in tagHelper.allowedChildTags) allowedNames.add(childTag.name)

        val allowedChildrenString = if (allowedNames.size > 0) allowedNames.joinToString(", ") else null

        if (allowedChildrenString != null) validateAllowedChildren(tagHelperParent.tagName, parent.children, allowedNames, allowedChildrenString, prefix)

        for (i in parent.children.size - 1 downTo 0) {
            val child = parent.children[i]
            if (child is UnresolvedElementIntermediateNode) {
                // The unresolved node is kept so that plain HTML still has a tag name after the legacy resolution flattens it to content.
                val resolvedTagHelper = resolveElement(parent, i, child, binder, prefix, usedHelpers, context, tagHelperParent)

                if (allowedChildrenString != null) {
                    validateAllowedChild(tagHelperParent, child, resolvedTagHelper?.tagName ?: child.tagName, allowedNames, allowedChildrenString)
                }
            } else if (allowedChildrenString != null && child is CSharpCodeIntermediateNode) {
                resolveBodyChildren(child, binder, prefix, usedHelpers, context, tagHelperParent, binding)
            } else {
                resolveElements(child, binder, prefix, usedHelpers, context)
            }
        }

        // RZ1033 (an end tag of a WithoutEndTag tag helper) is not emitted here: it is for orphan end tags only, which the rewriter (still
        // running after this phase) reports.
    }

    /** `ConvertToPlainElementAndResolve`: the unmatched element as plain markup, the resolver's diagnostics, the resulting children resolved. */
    private fun convertToPlainElementAndResolve(
        parent: IntermediateNode, index: Int, elementNode: UnresolvedElementIntermediateNode, binder: TagHelperBinder, prefix: String?,
        usedHelpers: MutableSet<TagHelperDescriptor>, context: ResolutionContext, emitDiagnostics: Boolean = true,
    ) {
        val childCountBefore = parent.children.size
        context.resolver.convertToPlainElement(parent, index, elementNode)
        val resultCount = parent.children.size - childCountBefore + 1 // +1 because the original was removed

        if (emitDiagnostics && resultCount > 0) context.resolver.addUnmatchedElementDiagnostic(parent.children[index], elementNode, context.documentNode)

        for (j in index + resultCount - 1 downTo index) {
            // ResolveElement can change parent.children (a StartTagOnly promotion inserts siblings): j may exceed the count.
            if (j < parent.children.size) {
                val promoted = parent.children[j]
                if (promoted is UnresolvedElementIntermediateNode) {
                    resolveElement(parent, j, promoted, binder, prefix, usedHelpers, context, unresolvedParentElement = elementNode)
                } else {
                    resolveElements(parent.children[j], binder, prefix, usedHelpers, context, unresolvedParentElement = elementNode)
                }
            }
        }
    }

    /** `TagHelperResolver`: the resolution strategy of a legacy (.cshtml) or a component (.razor) document. */
    private abstract class TagHelperResolver {
        /** An element bound to tag helpers, built into [tagHelperNode]. */
        abstract fun buildTagHelper(
            tagHelperNode: TagHelperIntermediateNode, bodyNode: TagHelperBodyIntermediateNode, elementNode: UnresolvedElementIntermediateNode,
            binding: TagHelperBinding, sourceDocument: RazorSourceDocument, context: ResolutionContext,
        )

        /** An element bound to nothing, back to plain HTML markup: the conversion only, no recursion, no diagnostics. */
        abstract fun convertToPlainElement(parent: IntermediateNode, index: Int, elementNode: UnresolvedElementIntermediateNode)

        /** The resolver's diagnostics of a matched element (RZ10012, the case mismatch); none by default. */
        open fun addMatchedElementDiagnostics(
            tagHelperNode: TagHelperIntermediateNode, elementNode: UnresolvedElementIntermediateNode, binding: TagHelperBinding, context: ResolutionContext,
        ) {
        }

        /** The resolver's diagnostics of an unmatched element after [convertToPlainElement]; none by default. */
        open fun addUnmatchedElementDiagnostic(convertedNode: IntermediateNode, originalNode: UnresolvedElementIntermediateNode, documentNode: DocumentIntermediateNode?) {
        }

        /**
         * `LowerUnresolvedAttributeValues`: the unresolved values of a bound property in the IR shape of the property — the shared
         * all-literal fast path, then [lowerComplexNonStringValues] or [lowerComplexStringValues] for mixed content.
         */
        protected fun lowerUnresolvedAttributeValues(
            htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode, expectsStringValue: Boolean, valueSourceSpan: SourceSpan?,
            sourceDocument: RazorSourceDocument,
        ) {
            if (htmlAttr.children.size == 0) return

            if (areAllChildrenOfType<UnresolvedAttributeValueIntermediateNode>(htmlAttr.children) && htmlAttr.children.size > 0) {
                val (mergedContent, mergedSpan) = collectUnresolvedLiteralContent(htmlAttr, valueSourceSpan)

                if (expectsStringValue) {
                    val htmlContent = HtmlContentIntermediateNode().also { it.source = mergedSpan }
                    htmlContent.children.add(HtmlIntermediateToken(mergedContent, mergedSpan))
                    target.children.add(htmlContent)
                } else {
                    target.children.add(CSharpIntermediateToken(mergedContent, mergedSpan))
                }
                return
            }

            if (expectsStringValue) lowerComplexStringValues(htmlAttr, target, sourceDocument)
            else lowerComplexNonStringValues(htmlAttr, target, valueSourceSpan, sourceDocument)
        }

        /** The complex (not all-literal) non-string values. */
        protected abstract fun lowerComplexNonStringValues(
            htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode, valueSourceSpan: SourceSpan?, sourceDocument: RazorSourceDocument,
        )

        /** The complex (not all-literal) string values. */
        protected abstract fun lowerComplexStringValues(htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode, sourceDocument: RazorSourceDocument)
    }

    /** `LegacyTagHelperResolver`: the resolution of a view (.cshtml) or of a component document before 3.0. */
    private class LegacyTagHelperResolver : TagHelperResolver() {
        override fun lowerComplexNonStringValues(
            htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode, valueSourceSpan: SourceSpan?, sourceDocument: RazorSourceDocument,
        ) = lowerUnresolvedNonStringAttributeValuesLegacy(htmlAttr, target, valueSourceSpan, sourceDocument)

        override fun lowerComplexStringValues(htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode, sourceDocument: RazorSourceDocument) =
            lowerUnresolvedStringAttributeValuesLegacy(htmlAttr, target, sourceDocument)

        override fun buildTagHelper(
            tagHelperNode: TagHelperIntermediateNode, bodyNode: TagHelperBodyIntermediateNode, elementNode: UnresolvedElementIntermediateNode,
            binding: TagHelperBinding, sourceDocument: RazorSourceDocument, context: ResolutionContext,
        ) {
            val renderedBoundAttributeNames = TreeSet(String.CASE_INSENSITIVE_ORDER)

            // The body node first (as the original lowering).
            tagHelperNode.children.add(bodyNode)

            // The pre-computed boundary indices: the body content and the attribute region.
            val startTagEndIdx = elementNode.startTagEndIndex
            val bodyEndIdx = elementNode.bodyEndIndex

            // The body content is between the boundary indices.
            if (startTagEndIdx >= 0 && bodyEndIdx >= 0 && tagHelperNode.tagMode != TagMode.StartTagOnly) {
                for (i in startTagEndIdx until bodyEndIdx) bodyNode.children.add(elementNode.children[i])
            }

            // Dynamic C# expression children (@s) exclude the unbound html attributes from the tag helper.
            val attrEnd = if (startTagEndIdx >= 0) startTagEndIdx else elementNode.children.size
            val hasDynamicExpressionChild = elementNode.hasDynamicExpressionChild

            // RZ1031: no C# in the element's attribute declaration area of a tag helper.
            if (hasDynamicExpressionChild) tryAddCSharpInDeclarationDiagnostic(tagHelperNode, elementNode, attrEnd)

            // The attributes before StartTagEnd.
            for (i in 0 until attrEnd) {
                val child = elementNode.children[i]
                if (child is UnresolvedAttributeIntermediateNode) {
                    if (hasDynamicExpressionChild && !TagHelperMatchingConventions.hasAttributeMatches(binding.tagHelpers, child.attributeName)) continue

                    convertUnresolvedLegacyAttribute(tagHelperNode, child, binding, renderedBoundAttributeNames, sourceDocument)
                } else if (child is HtmlAttributeIntermediateNode) {
                    if (hasDynamicExpressionChild) continue

                    convertAttributeToTagHelper(tagHelperNode, child, binding, renderedBoundAttributeNames, sourceDocument)
                }
            }
        }

        /**
         * `ConvertUnresolvedLegacyAttribute`: an unresolved attribute of a legacy tag helper — a [TagHelperPropertyIntermediateNode] per bound
         * match with the value lowered, a [TagHelperHtmlAttributeIntermediateNode] otherwise.
         */
        private fun convertUnresolvedLegacyAttribute(
            tagHelperNode: TagHelperIntermediateNode, unresolvedAttr: UnresolvedAttributeIntermediateNode, binding: TagHelperBinding,
            renderedBoundAttributeNames: MutableSet<String>, sourceDocument: RazorSourceDocument,
        ) {
            val attributeName = unresolvedAttr.attributeName
            val matches = ArrayList<TagHelperAttributeMatch>()
            TagHelperMatchingConventions.getAttributeMatches(binding.tagHelpers, attributeName, matches)

            // The name tracked for the duplicates; short-circuited: without matches it is not added, so that a later minimized attribute of
            // the same name can still match.
            if (matches.isEmpty() || !renderedBoundAttributeNames.add(attributeName)) {
                // Not bound: the unresolved children as a TagHelperHtmlAttribute.
                convertUnresolvedToUnboundLegacyAttribute(tagHelperNode, unresolvedAttr)
                return
            }

            // Bound: a TagHelperPropertyIntermediateNode per match.
            for (match in matches) {
                val attrStructure = unresolvedAttr.attributeStructure

                if (attrStructure == AttributeStructure.Minimized) {
                    convertMinimizedBoundAttribute(tagHelperNode, unresolvedAttr, attributeName, match)
                    continue
                }

                // Not minimized: the value children lowered.
                val prop = TagHelperPropertyIntermediateNode(match, attributeName, attrStructure)

                lowerBoundLegacyAttributeValue(prop, unresolvedAttr, match, sourceDocument)

                // RZ2008: a non-string bound attribute with an empty or whitespace value.
                if (!match.expectsStringValue && hasOnlyWhitespaceContent(prop)) {
                    val propertyType = if (match.isIndexerMatch) match.attribute.indexerTypeName else match.attribute.typeName
                    tagHelperNode.addDiagnostic(
                        RazorDiagnosticFactory.tagHelper_EmptyBoundAttribute(unresolvedAttr.attributeNameSpan ?: SourceSpan.UNDEFINED, attributeName, tagHelperNode.tagName, propertyType!!),
                    )
                }

                prop.source = unresolvedAttr.valueSourceSpan ?: (if (prop.children.size > 0) prop.children[0].source else null)

                tagHelperNode.children.add(prop)
            }
        }

        /** `ConvertMinimizedBoundAttribute` (`<input disabled>`): a minimized property node for a boolean property, RZ2008 otherwise. */
        private fun convertMinimizedBoundAttribute(
            tagHelperNode: TagHelperIntermediateNode, unresolvedAttr: UnresolvedAttributeIntermediateNode, attributeName: String, match: TagHelperAttributeMatch,
        ) {
            if (!match.expectsBooleanValue) {
                // RZ2008: a minimized non-boolean bound attribute requires a value.
                val propertyType = if (match.isIndexerMatch) match.attribute.indexerTypeName else match.attribute.typeName
                tagHelperNode.addDiagnostic(
                    RazorDiagnosticFactory.tagHelper_EmptyBoundAttribute(unresolvedAttr.attributeNameSpan ?: SourceSpan.UNDEFINED, attributeName, tagHelperNode.tagName, propertyType!!),
                )
                return
            }

            val prop = TagHelperPropertyIntermediateNode(match, attributeName, AttributeStructure.Minimized)
            prop.source = null
            tagHelperNode.children.add(prop)
        }

        /**
         * `LowerBoundLegacyAttributeValue`: the value of a non-minimized bound attribute as the property's children (HtmlContent of a string
         * property, C# tokens otherwise); an empty value (`type=""`) gets an empty placeholder child.
         */
        private fun lowerBoundLegacyAttributeValue(
            prop: TagHelperPropertyIntermediateNode, unresolvedAttr: UnresolvedAttributeIntermediateNode, match: TagHelperAttributeMatch, sourceDocument: RazorSourceDocument,
        ) {
            val htmlAttrChild = unresolvedAttr.htmlAttributeNode

            if (htmlAttrChild != null) lowerUnresolvedAttributeValues(htmlAttrChild, prop, match.expectsStringValue, unresolvedAttr.valueSourceSpan, sourceDocument)

            // Still no children (`type=""`, `checked=`): a synthetic empty child, so that the consumers tell an empty value from no value
            // (a minimized attribute).
            if (prop.children.size == 0) {
                val emptySpan = unresolvedAttr.valueSourceSpan
                if (match.expectsStringValue) prop.children.add(createEmptyHtmlContent(emptySpan)) else prop.children.add(createEmptyCSharpToken(emptySpan))
            }
        }

        private fun convertUnresolvedToUnboundLegacyAttribute(tagHelperNode: TagHelperIntermediateNode, unresolvedAttr: UnresolvedAttributeIntermediateNode) {
            val htmlAttrNode = TagHelperHtmlAttributeIntermediateNode(unresolvedAttr.attributeName, unresolvedAttr.attributeStructure)

            if (!unresolvedAttr.isMinimized) {
                val htmlAttrChild = unresolvedAttr.htmlAttributeNode

                if (htmlAttrChild != null) convertUnresolvedValuesToBasicForm(htmlAttrChild, htmlAttrNode)

                if (htmlAttrNode.children.size == 0) htmlAttrNode.children.add(createEmptyHtmlContent(unresolvedAttr.valueSourceSpan))
            }

            tagHelperNode.children.add(htmlAttrNode)
        }

        private fun convertAttributeToTagHelper(
            tagHelperNode: TagHelperIntermediateNode, htmlAttr: HtmlAttributeIntermediateNode, binding: TagHelperBinding,
            renderedBoundAttributeNames: MutableSet<String>, sourceDocument: RazorSourceDocument,
        ) {
            val attributeName = htmlAttr.attributeName ?: ""
            val attributeStructure = inferAttributeStructure(htmlAttr)

            val matches = ArrayList<TagHelperAttributeMatch>()
            TagHelperMatchingConventions.getAttributeMatches(binding.tagHelpers, attributeName, matches)

            // Add returns false for the duplicates: only the first match of each name is converted.
            if (matches.isNotEmpty() && renderedBoundAttributeNames.add(attributeName)) {
                for (match in matches) {
                    if (attributeStructure == AttributeStructure.Minimized) {
                        // A minimized bound attribute (<input disabled>).
                        if (!match.expectsBooleanValue) return

                        val setTagHelperProperty = TagHelperPropertyIntermediateNode(match, attributeName, AttributeStructure.Minimized)
                        setTagHelperProperty.source = null
                        tagHelperNode.children.add(setTagHelperProperty)
                    } else {
                        val isBoundStringProperty = match.expectsStringValue

                        val setTagHelperProperty = TagHelperPropertyIntermediateNode(match, attributeName, attributeStructure)
                        setTagHelperProperty.source = computeValueSource(htmlAttr)

                        convertValueChildren(setTagHelperProperty, htmlAttr, isBoundStringProperty, sourceDocument)
                        tagHelperNode.children.add(setTagHelperProperty)
                    }
                }
            } else {
                val htmlAttribute = TagHelperHtmlAttributeIntermediateNode(attributeName, attributeStructure)

                if (attributeStructure != AttributeStructure.Minimized) populateUnboundAttributeValue(htmlAttribute, htmlAttr, sourceDocument)

                tagHelperNode.children.add(htmlAttribute)
            }
        }

        private companion object {
            /**
             * `PopulateUnboundAttributeValue`: the value children of an unbound attribute by their content — dynamic expressions kept as they
             * are, all-literal values flattened for the preallocation, mixed values unwrapped one by one, an empty value a placeholder.
             */
            fun populateUnboundAttributeValue(htmlAttribute: TagHelperHtmlAttributeIntermediateNode, htmlAttr: HtmlAttributeIntermediateNode, sourceDocument: RazorSourceDocument) {
                // The value children classified.
                var hasTrueDynamicSegments = false
                var hasHtmlAttributeValues = false
                for (child in htmlAttr.children) {
                    if (child is CSharpExpressionAttributeValueIntermediateNode) hasTrueDynamicSegments = hasTrueDynamicSegments or !isLiteralEscapeSegment(child)
                    else if (child is CSharpCodeAttributeValueIntermediateNode) hasTrueDynamicSegments = true
                    else if (child is HtmlAttributeValueIntermediateNode) hasHtmlAttributeValues = true
                }

                if (hasTrueDynamicSegments) {
                    // True dynamic segments: the children kept as they are, for the AddHtmlAttributeValue pattern.
                    htmlAttribute.children.addAll(htmlAttr.children)
                } else if (hasHtmlAttributeValues) {
                    populateUnboundLiteralOrMixedValue(htmlAttribute, htmlAttr)
                } else if (htmlAttr.children.size == 0) {
                    // An empty value (class=""): empty HtmlContent for the preallocation.
                    val emptyHtml = HtmlContentIntermediateNode().also { it.source = htmlAttr.source }
                    emptyHtml.children.add(HtmlIntermediateToken("", htmlAttr.source))
                    htmlAttribute.children.add(emptyHtml)
                } else {
                    // No HtmlAttributeValues, no dynamic segments, but children (@@-escaped content): one HtmlContentIntermediateNode.
                    convertValueChildren(htmlAttribute, htmlAttr, isBoundStringProperty = true, sourceDocument = sourceDocument)
                }

                // The adjacent HtmlContent merged, only when the children were processed (not moved as they are).
                if (hasTrueDynamicSegments || hasHtmlAttributeValues) mergeAdjacentHtmlContent(htmlAttribute)
            }

            /**
             * `PopulateUnboundLiteralOrMixedValue`: an unbound value with [HtmlAttributeValueIntermediateNode] children (and maybe literal @@
             * escapes): all-literal flattened to one HtmlContent, mixed unwrapped one by one.
             */
            fun populateUnboundLiteralOrMixedValue(htmlAttribute: TagHelperHtmlAttributeIntermediateNode, htmlAttr: HtmlAttributeIntermediateNode) {
                // All literal?
                var allLiteral = true
                for (child in htmlAttr.children) {
                    if (child is HtmlAttributeValueIntermediateNode) continue // literal
                    else if (child is CSharpExpressionAttributeValueIntermediateNode && isLiteralEscapeSegment(child)) continue // @@ escape, treated as literal
                    else {
                        allLiteral = false
                        break
                    }
                }

                if (allLiteral) {
                    // All literal segments: one HtmlContentIntermediateNode for the preallocation.
                    flattenLiteralAttributeValue(htmlAttribute, htmlAttr)
                } else {
                    // Mixed HtmlAttributeValue + CSharpExpression (data-dash style): HtmlAttributeValue unwrapped to HtmlContent, the
                    // CSharpExpression kept.
                    for (child in htmlAttr.children) {
                        if (child is HtmlAttributeValueIntermediateNode) {
                            val (content, tokenSource) = collectAttributeValueContent(child)
                            if (content.isNotEmpty()) {
                                val htmlContent = HtmlContentIntermediateNode().also { it.source = tokenSource }
                                htmlContent.children.add(HtmlIntermediateToken(content, tokenSource))
                                htmlAttribute.children.add(htmlContent)
                            }
                        } else {
                            htmlAttribute.children.add(child)
                        }
                    }
                }
            }

            /**
             * `ConvertValueChildren`: the value children of an [HtmlAttributeIntermediateNode] onto [targetNode] — HtmlContent of a bound string
             * property, C# token(s) of a bound non-string one.
             */
            fun convertValueChildren(targetNode: IntermediateNode, htmlAttr: HtmlAttributeIntermediateNode, isBoundStringProperty: Boolean, sourceDocument: RazorSourceDocument) {
                if (htmlAttr.children.size == 0) {
                    // An empty value: the source span from the position of the attribute prefix.
                    if (isBoundStringProperty) {
                        val htmlContent = HtmlContentIntermediateNode()
                        htmlContent.children.add(HtmlIntermediateToken("", null))
                        targetNode.children.add(htmlContent)
                    } else {
                        val emptySource = computeEmptyValueSource(htmlAttr)
                        targetNode.children.add(CSharpIntermediateToken("", emptySource))
                    }
                    return
                }

                // All simple HtmlAttributeValueIntermediateNode (literal segments)?
                var allLiteral = true
                var hasDynamicContent = false
                for (child in htmlAttr.children) {
                    if (child is CSharpExpressionAttributeValueIntermediateNode || child is CSharpCodeAttributeValueIntermediateNode ||
                        child is CSharpExpressionIntermediateNode || child is CSharpCodeIntermediateNode
                    ) {
                        allLiteral = false
                        hasDynamicContent = true
                        break
                    } else if (child !is HtmlAttributeValueIntermediateNode && child !is HtmlContentIntermediateNode) {
                        // Non-literal, non-dynamic (unresolved wrapper nodes).
                        allLiteral = false
                        break
                    }
                }

                if (allLiteral) {
                    // All literal content: the text collected.
                    val content = collectLiteralContent(htmlAttr)
                    val source = computeValueSource(htmlAttr)

                    if (isBoundStringProperty) {
                        val htmlContent = HtmlContentIntermediateNode().also { it.source = source }
                        htmlContent.children.add(HtmlIntermediateToken(content, source))
                        targetNode.children.add(htmlContent)
                    } else {
                        // A non-string bound property: a CSharpIntermediateToken directly (not in a CSharpExpressionIntermediateNode), so that
                        // the optimization pass can detect enum types and add the type prefix.
                        targetNode.children.add(CSharpIntermediateToken(content, source))
                    }
                } else if (!isBoundStringProperty && hasDynamicContent) {
                    convertDynamicNonStringValueChildren(targetNode, htmlAttr, sourceDocument)
                } else {
                    // A bound string property with dynamic content, or the complex non-dynamic fallback: the attribute value nodes unwrapped to
                    // content nodes for the BeginWriteTagHelperAttribute pattern.
                    unwrapValueChildrenToTokens(targetNode, htmlAttr)
                }

                // The adjacent HtmlContentIntermediateNode children merged.
                mergeAdjacentHtmlContent(targetNode)
            }

            /**
             * `ConvertDynamicNonStringValueChildren`: a non-string bound property with dynamic content — literal + expression (with or without
             * the @@ escape), or pure C# expressions.
             */
            fun convertDynamicNonStringValueChildren(targetNode: IntermediateNode, htmlAttr: HtmlAttributeIntermediateNode, sourceDocument: RazorSourceDocument) {
                // Literal HTML segments mixed with C# expressions?
                var hasLiteralSegments = false
                for (child in htmlAttr.children) {
                    if (child is HtmlAttributeValueIntermediateNode || child is HtmlContentIntermediateNode) {
                        hasLiteralSegments = true
                        break
                    }
                }

                if (hasLiteralSegments) convertMixedLiteralAndExpressionValue(targetNode, htmlAttr, sourceDocument)
                else convertPureCSharpExpressionValue(targetNode, htmlAttr, sourceDocument)
            }

            /** `ConvertMixedLiteralAndExpressionValue`: literal + C# expression content; the @@ escape pattern, or the value read from the source. */
            fun convertMixedLiteralAndExpressionValue(targetNode: IntermediateNode, htmlAttr: HtmlAttributeIntermediateNode, sourceDocument: RazorSourceDocument) {
                // A @@expr pattern (escape + explicit expression) needs the special token handling: an @@ literal escape segment among the children.
                for (child in htmlAttr.children) {
                    if (child is CSharpExpressionAttributeValueIntermediateNode && isLiteralEscapeSegment(child)) {
                        convertEscapedAtExpressionValue(targetNode, htmlAttr)
                        return
                    }
                }

                val attrSource = htmlAttr.source
                if (attrSource != null) {
                    // Mixed content without the escape: the value from the source text.
                    val prefix = htmlAttr.prefix ?: ""
                    val suffix = htmlAttr.suffix ?: ""
                    var valueStart = attrSource.absoluteIndex + prefix.length
                    var valueLength = attrSource.length - prefix.length - suffix.length
                    if (valueLength > 0) {
                        // A value starting with @ (the transition of the first expression): stripped to the C# code
                        // (@DateTimeOffset.Now.Year -> DateTimeOffset.Now.Year).
                        if (sourceDocument.text[valueStart] == '@' && htmlAttr.children.size > 0 && htmlAttr.children[0] is CSharpExpressionAttributeValueIntermediateNode) {
                            valueStart++
                            valueLength--
                        }

                        val sourceText = sourceDocument.text.subSequence(valueStart, valueStart + valueLength).toString()

                        // The value span with the right line / character positions.
                        val valueCharIndex = attrSource.characterIndex + prefix.length + (if (attrSource.absoluteIndex + prefix.length < valueStart) 1 else 0)
                        val valueEndCharIndex = attrSource.endCharacterIndex - suffix.length
                        val valueSource = SourceSpan(attrSource.filePath, valueStart, attrSource.lineIndex, valueCharIndex, valueLength, attrSource.lineCount, valueEndCharIndex)
                        targetNode.children.add(CSharpIntermediateToken(sourceText, valueSource))
                    }
                } else {
                    unwrapValueChildrenToTokens(targetNode, htmlAttr)
                }
            }

            /**
             * `ConvertEscapedAtExpressionValue`: the @@expr escape of a non-string bound property — the duplicate literal @ tokens dropped,
             * the synthetic empty + transition tokens inserted, an explicit @(expr) split into tokens.
             */
            fun convertEscapedAtExpressionValue(targetNode: IntermediateNode, htmlAttr: HtmlAttributeIntermediateNode) {
                var lastAtTokenSource: SourceSpan? = null
                for (child in htmlAttr.children) {
                    if (child is HtmlContentIntermediateNode) {
                        for (token in child.children) {
                            if (token is IntermediateToken) {
                                // A duplicate @ token at the same position skipped.
                                val src = token.source
                                if (token.content == "@" && src != null) {
                                    val last = lastAtTokenSource
                                    if (last != null && last.absoluteIndex == src.absoluteIndex) continue // duplicate
                                    targetNode.children.add(CSharpIntermediateToken(token.content, token.source))
                                    lastAtTokenSource = src
                                } else {
                                    targetNode.children.add(CSharpIntermediateToken(token.content, token.source))
                                }
                            }
                        }
                    } else if (child is HtmlAttributeValueIntermediateNode) {
                        var attrContent = child.prefix ?: ""
                        for (token in child.children) if (token is IntermediateToken) attrContent += token.content
                        if (attrContent.isNotEmpty()) {
                            val tokenSource = if (child.children.size > 0) child.children[0].source else child.source
                            if (attrContent == "@" && tokenSource != null) {
                                val last = lastAtTokenSource
                                if (last != null && last.absoluteIndex == tokenSource.absoluteIndex) continue
                                lastAtTokenSource = tokenSource
                            }
                            targetNode.children.add(CSharpIntermediateToken(attrContent, tokenSource))
                        }
                    } else if (child is CSharpExpressionAttributeValueIntermediateNode) {
                        // Before the explicit expression, empty + @ transition tokens, as the baseline's VisitAttributeValue handles the @@ pattern.
                        val atSrc = lastAtTokenSource
                        if (atSrc != null) {
                            val transAbsIdx = atSrc.absoluteIndex + 2
                            val transCharIdx = atSrc.characterIndex + 2
                            // The empty token (the MarkupEphemeralTextLiteral equivalent).
                            val emptySource = SourceSpan(atSrc.filePath, transAbsIdx, atSrc.lineIndex, transCharIdx, 0, 0, transCharIdx)
                            targetNode.children.add(CSharpIntermediateToken("", emptySource))
                            // The @ transition token.
                            val transSource = SourceSpan(atSrc.filePath, transAbsIdx, atSrc.lineIndex, transCharIdx, 1, 0, transCharIdx + 1)
                            targetNode.children.add(CSharpIntermediateToken("@", transSource))
                        }

                        // An explicit expression @(expr): the (, expr, ) tokens.
                        if (child.children.size > 0) {
                            val firstInnerChild = child.children[0]
                            val innerSource = firstInnerChild.source
                            if (innerSource != null) {
                                val openAbsIndex = innerSource.absoluteIndex - 1
                                val openCharIndex = innerSource.characterIndex - 1
                                val openSource = SourceSpan(innerSource.filePath, openAbsIndex, innerSource.lineIndex, openCharIndex, 1, 0, openCharIndex + 1)
                                targetNode.children.add(CSharpIntermediateToken("(", openSource))

                                for (innerChild in child.children) targetNode.children.add(innerChild)

                                val lastInnerChild = child.children[child.children.size - 1]
                                val lastSource = lastInnerChild.source
                                if (lastSource != null) {
                                    val closeAbsIndex = lastSource.absoluteIndex + lastSource.length
                                    val closeCharIndex = lastSource.endCharacterIndex
                                    val closeSource = SourceSpan(lastSource.filePath, closeAbsIndex, lastSource.lineIndex, closeCharIndex, 1, 0, closeCharIndex + 1)
                                    targetNode.children.add(CSharpIntermediateToken(")", closeSource))
                                }
                            }
                        }
                    }
                }
            }

            /**
             * `ConvertPureCSharpExpressionValue`: a non-string bound property of C# expressions only — an explicit @(expr) split into tokens,
             * an implicit @expr through [unwrapValueChildrenToTokens].
             */
            fun convertPureCSharpExpressionValue(targetNode: IntermediateNode, htmlAttr: HtmlAttributeIntermediateNode, sourceDocument: RazorSourceDocument) {
                val attrSource = htmlAttr.source
                if (attrSource != null) {
                    val prefix = htmlAttr.prefix ?: ""
                    val suffix = htmlAttr.suffix ?: ""
                    val valueStart = attrSource.absoluteIndex + prefix.length
                    val valueLength = attrSource.length - prefix.length - suffix.length
                    if (valueLength > 0) {
                        val rawText = sourceDocument.text.subSequence(valueStart, valueStart + valueLength).toString()
                        // The source text only for the explicit expressions @(...), whose delimiters the IR lost; an implicit @expr goes through
                        // UnwrapValueChildrenToTokens (a CSharpExpressionIntermediateNode, no enum prefix).
                        if (rawText.startsWith("@(")) {
                            // @(expr): the (, expr, ) tokens, as the baseline's separate tokens.
                            val openParenAbsIndex = valueStart + 1 // after @
                            val openParenCharIndex = attrSource.characterIndex + prefix.length + 1
                            val openParenSource = SourceSpan(attrSource.filePath, openParenAbsIndex, attrSource.lineIndex, openParenCharIndex, 1, 0, openParenCharIndex + 1)
                            targetNode.children.add(CSharpIntermediateToken("(", openParenSource))

                            // The inner expression from the CSharpExpressionAttributeValueIntermediateNode.
                            for (child in htmlAttr.children) {
                                if (child is CSharpExpressionAttributeValueIntermediateNode) {
                                    for (innerChild in child.children) {
                                        if (innerChild is CSharpIntermediateToken) targetNode.children.add(CSharpIntermediateToken(innerChild.content, innerChild.source))
                                        else targetNode.children.add(innerChild)
                                    }
                                }
                            }

                            val closeParenAbsIndex = valueStart + valueLength - 1 // the last character
                            val closeParenCharIndex = attrSource.characterIndex + prefix.length + valueLength - 1
                            val closeParenSource = SourceSpan(attrSource.filePath, closeParenAbsIndex, attrSource.lineIndex, closeParenCharIndex, 1, 0, closeParenCharIndex + 1)
                            targetNode.children.add(CSharpIntermediateToken(")", closeParenSource))
                            return
                        }
                    }
                }

                unwrapValueChildrenToTokens(targetNode, htmlAttr)
            }

            fun unwrapValueChildrenToTokens(targetNode: IntermediateNode, htmlAttr: HtmlAttributeIntermediateNode) {
                for (child in htmlAttr.children) {
                    if (child is HtmlAttributeValueIntermediateNode) {
                        val (content, tokenSource) = collectAttributeValueContent(child)
                        if (content.isNotEmpty()) {
                            val htmlContent = HtmlContentIntermediateNode().also { it.source = tokenSource }
                            htmlContent.children.add(HtmlIntermediateToken(content, tokenSource))
                            targetNode.children.add(htmlContent)
                        }
                    } else if (child is CSharpExpressionAttributeValueIntermediateNode) {
                        unwrapCSharpAttributeValue(targetNode, child.prefix, child.children) { token -> CSharpExpressionIntermediateNode().also { it.source = token.source } }
                    } else if (child is CSharpCodeAttributeValueIntermediateNode) {
                        unwrapCSharpAttributeValue(targetNode, child.prefix, child.children) { token -> CSharpCodeIntermediateNode().also { it.source = token.source } }
                    } else {
                        targetNode.children.add(child)
                    }
                }
            }

            /**
             * `UnwrapCSharpAttributeValue`: a C# attribute value (expression or code) onto [targetNode] — the prefix as HtmlContent, each C#
             * token in a node of [createCSharpWrapper], each HTML token in an HtmlContent.
             */
            fun unwrapCSharpAttributeValue(
                targetNode: IntermediateNode, prefix: String?, children: IntermediateNodeCollection, createCSharpWrapper: (CSharpIntermediateToken) -> IntermediateNode,
            ) {
                if (!prefix.isNullOrEmpty()) {
                    val prefixContent = HtmlContentIntermediateNode()
                    prefixContent.children.add(HtmlIntermediateToken(prefix, null))
                    targetNode.children.add(prefixContent)
                }

                for (innerChild in children) {
                    if (innerChild is CSharpIntermediateToken) {
                        val wrapper = createCSharpWrapper(innerChild)
                        wrapper.children.add(innerChild)
                        targetNode.children.add(wrapper)
                    } else if (innerChild is HtmlIntermediateToken) {
                        val htmlContent = HtmlContentIntermediateNode().also { it.source = innerChild.source }
                        htmlContent.children.add(innerChild)
                        targetNode.children.add(htmlContent)
                    } else {
                        targetNode.children.add(innerChild)
                    }
                }
            }

            /** `ComputeEmptyValueSource`: the span of an empty value (between the quotes, or after the =). */
            fun computeEmptyValueSource(htmlAttr: HtmlAttributeIntermediateNode): SourceSpan? {
                val attrSource = htmlAttr.source ?: return null

                val prefix = htmlAttr.prefix ?: ""

                // The value is after the prefix, before the suffix.
                val valueAbsIndex = attrSource.absoluteIndex + prefix.length
                val valueCharIndex = attrSource.characterIndex + prefix.length

                return SourceSpan(attrSource.filePath, valueAbsIndex, attrSource.lineIndex, valueCharIndex, 0, 0, valueCharIndex)
            }

            /** `CollectLiteralContent`: the literal text of the children of an HtmlAttributeIntermediateNode. */
            fun collectLiteralContent(htmlAttr: HtmlAttributeIntermediateNode): String {
                val sb = StringBuilder()

                for (child in htmlAttr.children) {
                    if (child is HtmlAttributeValueIntermediateNode) {
                        sb.append(collectAttributeValueContent(child).first)
                    } else if (child is CSharpExpressionAttributeValueIntermediateNode && isLiteralEscapeSegment(child)) {
                        // An @@ escape: the literal content ("@" of @@).
                        sb.append(child.prefix ?: "")
                        appendTokenContent(sb, child.children)
                    } else if (child is HtmlContentIntermediateNode) {
                        appendTokenContent(sb, child.children)
                    }
                }

                return sb.toString()
            }

            /** `IsLiteralEscapeSegment`: an @@ escape (`@@` -> "@"): a single token of literal content "@". */
            fun isLiteralEscapeSegment(segment: CSharpExpressionAttributeValueIntermediateNode): Boolean =
                segment.children.size == 1 && (segment.children[0] as? IntermediateToken)?.content == "@"

            fun appendTokenContent(sb: StringBuilder, children: IntermediateNodeCollection) {
                for (token in children) if (token is IntermediateToken) sb.append(token.content)
            }

            /** `FlattenLiteralAttributeValue`: the literal HtmlAttributeValueIntermediateNode children as one HtmlContentIntermediateNode. */
            fun flattenLiteralAttributeValue(targetNode: IntermediateNode, htmlAttr: HtmlAttributeIntermediateNode) {
                val content = collectLiteralContent(htmlAttr)
                val source = htmlAttr.source

                val htmlContent = HtmlContentIntermediateNode().also { it.source = source }
                htmlContent.children.add(HtmlIntermediateToken(content, source))
                targetNode.children.add(htmlContent)
            }

            fun unwrapHtmlAttribute(parent: IntermediateNode, insertIndex: Int, htmlAttr: HtmlAttributeIntermediateNode): Int {
                var insertIndex = insertIndex
                val attrName = htmlAttr.attributeName ?: ""
                val isDataDash = attrName.startsWith("data-", ignoreCase = true)
                var hasDynamicChildren = false
                for (attrChild in htmlAttr.children) {
                    if (attrChild is CSharpExpressionAttributeValueIntermediateNode || attrChild is CSharpCodeAttributeValueIntermediateNode || attrChild is CSharpExpressionIntermediateNode) {
                        hasDynamicChildren = true
                        break
                    }
                }

                if (isDataDash) return unwrapDataDashAttribute(parent, insertIndex, htmlAttr, hasDynamicChildren)

                // Not data-dash: flattened to text?
                // - literal value (HtmlAttributeValueIntermediateNode children) -> flattened
                // - minimized (no children, no = in the prefix) -> flattened
                // - empty value with = (no children, = in the prefix) -> kept as HtmlAttributeIntermediateNode
                // - dynamic content -> kept as HtmlAttributeIntermediateNode
                var shouldFlatten = false
                if (htmlAttr.children.size > 0) {
                    // All children literal?
                    shouldFlatten = true
                    for (attrChild in htmlAttr.children) {
                        if (attrChild !is HtmlAttributeValueIntermediateNode && attrChild !is HtmlContentIntermediateNode) {
                            shouldFlatten = false
                            break
                        }
                    }
                } else if (!(htmlAttr.prefix ?: "").contains('=')) {
                    // No children: minimized (no =) -> flattened; an empty value (=) -> kept.
                    shouldFlatten = true
                }

                if (shouldFlatten) {
                    // Flattened to HtmlContent (merges with the surrounding text).
                    val attrContent = flattenAttributeToHtml(htmlAttr)
                    if (attrContent.isNotEmpty()) {
                        val htmlContent = HtmlContentIntermediateNode().also { it.source = htmlAttr.source }
                        htmlContent.children.add(HtmlIntermediateToken(attrContent, htmlAttr.source))
                        parent.children.add(insertIndex++, htmlContent)
                    }
                } else {
                    // Empty or dynamic: kept as HtmlAttributeIntermediateNode (the BeginWriteAttribute pattern).
                    parent.children.add(insertIndex++, htmlAttr)
                }

                return insertIndex
            }

            fun unwrapDataDashAttribute(parent: IntermediateNode, insertIndex: Int, htmlAttr: HtmlAttributeIntermediateNode, hasDynamicChildren: Boolean): Int {
                var insertIndex = insertIndex
                if (!hasDynamicChildren) {
                    // Data-dash of literal content only: flattened to HtmlContent.
                    val attrContent = flattenAttributeToHtml(htmlAttr)
                    if (attrContent.isNotEmpty()) {
                        val htmlContent = HtmlContentIntermediateNode().also { it.source = htmlAttr.source }
                        htmlContent.children.add(HtmlIntermediateToken(attrContent, htmlAttr.source))
                        parent.children.add(insertIndex++, htmlContent)
                    }

                    return insertIndex
                }

                // Data-dash with dynamic content: the prefix flattened to HtmlContent, each child promoted (HtmlAttributeValue unwrapped to
                // HtmlContent).
                val prefix = htmlAttr.prefix ?: ""
                if (prefix.isNotEmpty()) {
                    val prefixHtml = HtmlContentIntermediateNode().also { it.source = htmlAttr.source }
                    prefixHtml.children.add(HtmlIntermediateToken(prefix, htmlAttr.source))
                    parent.children.add(insertIndex++, prefixHtml)
                }
                for (attrChild in htmlAttr.children) {
                    if (attrChild is HtmlAttributeValueIntermediateNode) {
                        val (content, tokenSource) = collectAttributeValueContent(attrChild)
                        if (content.isNotEmpty()) {
                            val hc = HtmlContentIntermediateNode().also { it.source = tokenSource }
                            hc.children.add(HtmlIntermediateToken(content, tokenSource))
                            parent.children.add(insertIndex++, hc)
                        }
                    } else if (attrChild is CSharpExpressionAttributeValueIntermediateNode) {
                        // Unwrapped: the prefix as text, the inner expression.
                        val exprPrefix = attrChild.prefix
                        if (!exprPrefix.isNullOrEmpty()) {
                            val pHtml = HtmlContentIntermediateNode()
                            pHtml.children.add(HtmlIntermediateToken(exprPrefix, null))
                            parent.children.add(insertIndex++, pHtml)
                        }
                        for (innerChild in attrChild.children) {
                            if (innerChild is CSharpIntermediateToken) {
                                val expr = CSharpExpressionIntermediateNode().also { it.source = innerChild.source }
                                expr.children.add(innerChild)
                                parent.children.add(insertIndex++, expr)
                            } else {
                                parent.children.add(insertIndex++, innerChild)
                            }
                        }
                    } else {
                        parent.children.add(insertIndex++, attrChild)
                    }
                }
                val suffix = htmlAttr.suffix ?: ""
                if (suffix.isNotEmpty()) {
                    val suffixHtml = HtmlContentIntermediateNode()
                    suffixHtml.children.add(HtmlIntermediateToken(suffix, null))
                    parent.children.add(insertIndex++, suffixHtml)
                }

                return insertIndex
            }

            /**
             * The resolver's own `MergeAdjacentHtmlContent(parent, index, insertIndex)` (renamed beside the phase's one-argument overload): after
             * the unwrapping, the adjacent HtmlContent of the affected range and its boundaries merged unconditionally (flattened attributes may
             * have non-adjacent spans).
             */
            fun mergeAdjacentHtmlContentInRange(parent: IntermediateNode, index: Int, insertIndex: Int) {
                val mergeStart = maxOf(0, index - 1)
                var mergeEnd = minOf(parent.children.size - 1, insertIndex)

                var i = mergeStart
                while (i < mergeEnd) {
                    val current = parent.children[i]
                    val next = parent.children[i + 1]
                    if (current is HtmlContentIntermediateNode && next is HtmlContentIntermediateNode && canMerge(current, next)) {
                        // next merged into current.
                        current.children.addAll(next.children)

                        // The diagnostics of the removed sibling kept (the orphan-tag diagnostics ConvertToPlainElement attached to the first
                        // inserted child).
                        current.addDiagnosticsFromNode(next)
                        val currentSource = current.source
                        val nextSource = next.source
                        if (currentSource != null && nextSource != null) current.source = mergeSourceSpans(currentSource, nextSource)
                        else if (currentSource == null) current.source = nextSource
                        parent.children.removeAt(i + 1)
                        mergeEnd--
                    } else {
                        i++
                    }
                }
            }

            /** `FlattenAttributeToHtml`: an HtmlAttributeIntermediateNode back to its HTML text — the prefix, the content of the children, the suffix. */
            fun flattenAttributeToHtml(htmlAttr: HtmlAttributeIntermediateNode): String {
                val sb = StringBuilder()

                sb.append(htmlAttr.prefix ?: "")

                for (child in htmlAttr.children) {
                    if (child is HtmlAttributeValueIntermediateNode) {
                        sb.append(collectAttributeValueContent(child).first)
                    } else if (child is IntermediateToken) {
                        sb.append(child.content)
                    } else if (child is HtmlContentIntermediateNode) {
                        for (token in child.children) if (token is IntermediateToken) sb.append(token.content)
                    } else if (child is CSharpExpressionAttributeValueIntermediateNode) {
                        sb.append(child.prefix ?: "")
                        for (exprChild in child.children) {
                            if (exprChild is IntermediateToken) {
                                sb.append(exprChild.content)
                            } else if (exprChild is CSharpExpressionIntermediateNode) {
                                for (innerToken in exprChild.children) if (innerToken is IntermediateToken) sb.append(innerToken.content)
                            }
                        }
                    }
                }

                sb.append(htmlAttr.suffix ?: "")

                return sb.toString()
            }

            fun canMerge(a: HtmlContentIntermediateNode, b: HtmlContentIntermediateNode): Boolean {
                val aSource = a.source
                val bSource = b.source
                if (aSource == null || bSource == null) return true
                return aSource.filePath == bSource.filePath && aSource.absoluteIndex + aSource.length == bSource.absoluteIndex
            }

            /** `LowerUnresolvedNonStringAttributeValues_Legacy`: the whole value of a non-string property is C# code. */
            fun lowerUnresolvedNonStringAttributeValuesLegacy(
                htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode, valueSourceSpan: SourceSpan?, sourceDocument: RazorSourceDocument,
            ) {
                // Case 1: an implicit / explicit expression (@int, @new string(...), @(@object)).
                if (htmlAttr.children.size >= 1) {
                    val firstExpr = htmlAttr.children[0]
                    if (firstExpr is UnresolvedExpressionAttributeValueIntermediateNode && firstExpr.prefix.isEmpty() && firstExpr.containsExpression) {
                        lowerImplicitExpressionAttributeLegacy(htmlAttr, target, firstExpr, sourceDocument)
                        return
                    }
                }

                // Case 2: a code block as the sole content (@{1 + 2}).
                if (htmlAttr.children.size == 1) {
                    val soleCodeBlock = htmlAttr.children[0]
                    if (soleCodeBlock is UnresolvedExpressionAttributeValueIntermediateNode && !soleCodeBlock.containsExpression && soleCodeBlock.prefix.isEmpty()) {
                        lowerCodeBlockAttributeLegacy(soleCodeBlock, target)
                        return
                    }
                }

                // Case 3: mixed content (literals + expressions). An @@ escape collects from the children, not to count twice the escaped @
                // already added as an unresolved literal.
                var hasEscapedAt = false
                for (child in htmlAttr.children) {
                    if (child is UnresolvedAttributeValueIntermediateNode && isSingleHtmlAt(child)) {
                        hasEscapedAt = true
                        break
                    }
                }

                if (!hasEscapedAt && valueSourceSpan != null && valueSourceSpan.length > 0) lowerMixedContentFromSourceLegacy(target, valueSourceSpan, sourceDocument)
                else lowerMixedContentFromChildrenLegacy(htmlAttr, target, sourceDocument)
            }

            /** `Children is [HtmlIntermediateToken { Content: "@" }]`. */
            fun isSingleHtmlAt(node: IntermediateNode): Boolean = node.children.size == 1 && (node.children[0] as? HtmlIntermediateToken)?.content == "@"

            /**
             * `LowerImplicitExpressionAttribute_Legacy`: an implicit (@expr, one token) or explicit (@(expr): `(`, the content, `)`) expression,
             * the content read from the source document without the @ transition.
             */
            fun lowerImplicitExpressionAttributeLegacy(
                htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode, firstExpr: UnresolvedExpressionAttributeValueIntermediateNode, sourceDocument: RazorSourceDocument,
            ) {
                val expr = CSharpExpressionIntermediateNode()

                // The expression from the source document without the @ transition: the parens of an explicit expression preserved
                // (@(@object) -> (@object), @(1+2) -> (1+2)); an implicit one: @int -> int.
                val exprSource = firstExpr.source
                if (exprSource != null && exprSource.length > 1) {
                    // The @ transition skipped.
                    val contentStart = exprSource.absoluteIndex + 1
                    var contentLength = exprSource.length - 1

                    // The content of the following literal children included.
                    for (i in 1 until htmlAttr.children.size) {
                        val lit = htmlAttr.children[i]
                        val litSrc = lit.source
                        if (lit is UnresolvedAttributeValueIntermediateNode && litSrc != null) {
                            val litEnd = litSrc.absoluteIndex + litSrc.length
                            contentLength = litEnd - contentStart
                        }
                    }

                    val text = sourceDocument.text.subSequence(contentStart, contentStart + contentLength).toString()

                    // An explicit expression @(...): 3 tokens, (, the content, ).
                    if (text.length >= 2 && text[0] == '(' && text[text.length - 1] == ')') {
                        emitParenthesizedExpressionTokens(expr, contentStart, contentLength, sourceDocument)

                        val openLoc = sourceDocument.linePosition(contentStart)
                        val closeLoc = sourceDocument.linePosition(contentStart + contentLength - 1)
                        expr.source = SourceSpan(exprSource.filePath, contentStart, openLoc.line, openLoc.character, contentLength, 0, closeLoc.character + 1)
                    } else {
                        // An implicit expression: one token.
                        val contentLocation = sourceDocument.linePosition(contentStart)
                        val contentSpan = SourceSpan(exprSource.filePath, contentStart, contentLocation.line, contentLocation.character, contentLength, 0, contentLocation.character + contentLength)
                        expr.children.add(CSharpIntermediateToken(text, contentSpan))
                        expr.source = contentSpan
                    }
                } else {
                    // The fallback: collected from the children.
                    val sb = StringBuilder()
                    val spans = arrayOfNulls<SourceSpan>(2) // firstSpan, lastSpan
                    for (child in htmlAttr.children) {
                        if (child is UnresolvedExpressionAttributeValueIntermediateNode && child.prefix.isNotEmpty()) sb.append(child.prefix)
                        else if (child is UnresolvedAttributeValueIntermediateNode && child.prefix.isNotEmpty()) sb.append(child.prefix)

                        collectAllTokenContent(child, sb, spans)
                    }

                    val mergedContent = sb.toString()
                    val f = spans[0]
                    val l = spans[1]
                    val tokenSpan = if (f != null && l != null) mergeSourceSpans(f, l) else f
                    expr.children.add(CSharpIntermediateToken(mergedContent, tokenSpan))
                    expr.source = tokenSpan
                }

                target.children.add(expr)
            }

            /** `LowerCodeBlockAttribute_Legacy`: a code block value (@{ ... }) keeps its CSharpCode structure, not wrapped as an expression. */
            fun lowerCodeBlockAttributeLegacy(soleCodeBlock: UnresolvedExpressionAttributeValueIntermediateNode, target: IntermediateNode) {
                target.children.addAll(soleCodeBlock.children)
            }

            /** `LowerMixedContentFromSource_Legacy`: mixed literal + expression content as one C# token of the source text. */
            fun lowerMixedContentFromSourceLegacy(target: IntermediateNode, vss: SourceSpan, sourceDocument: RazorSourceDocument) {
                val text = sourceDocument.text.subSequence(vss.absoluteIndex, vss.absoluteIndex + vss.length).toString()
                target.children.add(CSharpIntermediateToken(text, vss))
            }

            /**
             * `LowerMixedContentFromChildren_Legacy`: mixed content without a source span — flat C# tokens of the literals, CSharpExpression
             * wrappers of the expressions; the @@ escape by the literal @ token after the ephemeral marker.
             */
            fun lowerMixedContentFromChildrenLegacy(htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode, sourceDocument: RazorSourceDocument) {
                // The @@ literal becomes a flat @ token; the expression children a CSharpExpression with the content from the source.
                for (i in 0 until htmlAttr.children.size) {
                    val child = htmlAttr.children[i]
                    if (child is UnresolvedAttributeValueIntermediateNode) {
                        // The literal children (the @ of @@ too): flat C# tokens.
                        for (valueChild in child.children) if (valueChild is HtmlIntermediateToken) target.children.add(toCSharpToken(valueChild))

                        // An empty token after the @@ literal: the escape yields a single @ token and a trailing empty token stands for the
                        // ephemeral second @ the escape consumed.
                        if (isSingleHtmlAt(child)) {
                            // The span of the empty token at the next child's position.
                            var emptySpan: SourceSpan? = null
                            val nextSrc = if (i + 1 < htmlAttr.children.size) htmlAttr.children[i + 1].source else null
                            if (nextSrc != null) {
                                val loc = sourceDocument.linePosition(nextSrc.absoluteIndex)
                                emptySpan = SourceSpan(nextSrc.filePath, nextSrc.absoluteIndex, loc.line, loc.character, 0, 0, loc.character)
                            }

                            target.children.add(createEmptyCSharpToken(emptySpan))
                        }
                    } else if (child is UnresolvedExpressionAttributeValueIntermediateNode) {
                        val exprSrc = child.source
                        if (child.containsExpression && exprSrc != null && exprSrc.length > 1) {
                            // The expression after the @@ escape: CSharpExpression(@, (, content, )).
                            emitEscapedAtCSharpExpression(target, exprSrc, sourceDocument)
                        } else {
                            // A code block or no source: flattened to tokens.
                            target.children.addAll(child.children)
                        }
                    }
                }
            }

            /**
             * `LowerUnresolvedStringAttributeValues_Legacy`: the unresolved values as the legacy string attribute pipeline makes them — adjacent
             * literals batched into one HtmlContent of their tokens (the prefix merged into the first token, its span extended backward), the
             * expressions as CSharpExpression nodes between the literal groups.
             */
            fun lowerUnresolvedStringAttributeValuesLegacy(htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode, sourceDocument: RazorSourceDocument) {
                val pendingLiteralParts = ArrayList<Pair<String, SourceSpan?>>()
                var pendingFirstSpan: SourceSpan? = null
                var pendingLastSpan: SourceSpan? = null

                // `FlushPendingLiterals`: the pending literal parts as one HtmlContentIntermediateNode of their tokens, the pending state cleared.
                fun flushPendingLiterals() {
                    if (pendingLiteralParts.size == 0) return

                    val htmlContent = HtmlContentIntermediateNode().also { it.source = pendingFirstSpan }
                    for ((text, tokenSource) in pendingLiteralParts) htmlContent.children.add(HtmlIntermediateToken(text, tokenSource))

                    val f = pendingFirstSpan
                    val l = pendingLastSpan
                    if (f != null && l != null) htmlContent.source = mergeSourceSpans(f, l)

                    target.children.add(htmlContent)
                    pendingLiteralParts.clear()
                    pendingFirstSpan = null
                    pendingLastSpan = null
                }

                for (child in htmlAttr.children) {
                    if (child is UnresolvedAttributeValueIntermediateNode) {
                        val prefix = child.prefix
                        var mergedPrefixWithFirst = false

                        for (valueChild in child.children) {
                            if (valueChild is HtmlIntermediateToken) {
                                if (!mergedPrefixWithFirst && prefix.isNotEmpty()) {
                                    // The prefix merged into the first token's content, its span extended backward.
                                    val mergedContent = prefix + valueChild.content
                                    val mergedSource = extendSpanBackward(valueChild.source, prefix.length)

                                    pendingLiteralParts.add(mergedContent to mergedSource)
                                    if (mergedSource != null) {
                                        if (pendingFirstSpan == null) pendingFirstSpan = mergedSource
                                        pendingLastSpan = mergedSource
                                    }

                                    mergedPrefixWithFirst = true
                                } else {
                                    pendingLiteralParts.add(valueChild.content to valueChild.source)
                                    val s = valueChild.source
                                    if (s != null) {
                                        if (pendingFirstSpan == null) pendingFirstSpan = s
                                        pendingLastSpan = s
                                    }
                                }
                            }
                        }

                        // A prefix not merged (no children) added on its own.
                        if (!mergedPrefixWithFirst && prefix.isNotEmpty()) pendingLiteralParts.add(prefix to null)
                    } else {
                        // The expression's prefix (the space before @expr) among the pending literals.
                        if (child is UnresolvedExpressionAttributeValueIntermediateNode && child.prefix.isNotEmpty()) pendingLiteralParts.add(child.prefix to null)

                        // The pending literals flushed as HtmlContent of their tokens.
                        flushPendingLiterals()

                        if (child is UnresolvedExpressionAttributeValueIntermediateNode) {
                            if (child.containsExpression) {
                                // An implicit / explicit expression: wrapped in CSharpExpression.
                                val expr = CSharpExpressionIntermediateNode()
                                flattenToDirectCSharpTokens(child, expr)
                                expr.source = if (expr.children.size > 0) expr.children[0].source else child.source
                                target.children.add(expr)
                            } else {
                                // A code block (@if{}, @{...}): its structure kept (CSharpCode and HtmlContent interleaved).
                                target.children.addAll(child.children)
                            }
                        } else {
                            target.children.add(child)
                        }
                    }
                }

                flushPendingLiterals()
            }

            fun tryAddCSharpInDeclarationDiagnostic(tagHelperNode: TagHelperIntermediateNode, elementNode: UnresolvedElementIntermediateNode, attrEnd: Int) {
                for (i in 0 until attrEnd) {
                    val codeChild = elementNode.children[i]
                    if (codeChild is CSharpCodeIntermediateNode) {
                        tagHelperNode.addDiagnostic(
                            RazorDiagnosticFactory.parsing_TagHelpersCannotHaveCSharpInTagDeclaration(codeChild.source ?: elementNode.source ?: SourceSpan.UNDEFINED, elementNode.tagName),
                        )
                        return
                    }

                    if (codeChild is CSharpExpressionIntermediateNode) {
                        // The CSharpExpressionIntermediateNode source lacks the @ transition: the span moved back by 1 to point at the whole @expression.
                        var diagSource = codeChild.source ?: elementNode.source ?: SourceSpan.UNDEFINED
                        if (diagSource.absoluteIndex > 0) {
                            diagSource = SourceSpan(
                                diagSource.filePath, diagSource.absoluteIndex - 1, diagSource.lineIndex, diagSource.characterIndex - 1, diagSource.length + 1,
                                diagSource.lineCount, diagSource.endCharacterIndex,
                            )
                        }

                        tagHelperNode.addDiagnostic(RazorDiagnosticFactory.parsing_TagHelpersCannotHaveCSharpInTagDeclaration(diagSource, elementNode.tagName))
                        return
                    }
                }
            }

            /** `CollectAllTokenContent`: the text of the tokens of a node and its descendants, with the first and the last span ([spans]). */
            fun collectAllTokenContent(node: IntermediateNode, sb: StringBuilder, spans: Array<SourceSpan?>) {
                if (node is IntermediateToken) {
                    sb.append(node.content)
                    val s = node.source
                    if (s != null) {
                        if (spans[0] == null) spans[0] = s
                        spans[1] = s
                    }
                } else {
                    for (child in node.children) collectAllTokenContent(child, sb, spans)
                }
            }
        }

        override fun convertToPlainElement(parent: IntermediateNode, index: Int, elementNode: UnresolvedElementIntermediateNode) {
            // The wrapper removed, its children promoted to the parent (the unresolved attributes and HtmlAttributeIntermediateNode handled).
            parent.children.removeAt(index)

            var insertIndex = index
            var firstInserted: IntermediateNode? = null
            for (child in elementNode.children) {
                if (child is UnresolvedAttributeIntermediateNode) {
                    // The pre-lowered AsMarkupAttribute fallback.
                    val asMarkupAttribute = child.asMarkupAttribute
                    if (asMarkupAttribute is MarkupElementIntermediateNode) {
                        for (lowered in asMarkupAttribute.children) {
                            parent.children.add(insertIndex++, lowered)
                            if (firstInserted == null) firstInserted = lowered
                        }
                    } else if (asMarkupAttribute != null) {
                        parent.children.add(insertIndex++, asMarkupAttribute)
                        if (firstInserted == null) firstInserted = asMarkupAttribute
                    }
                    continue
                }

                if (child is HtmlAttributeIntermediateNode) {
                    val beforeUnwrap = insertIndex
                    insertIndex = unwrapHtmlAttribute(parent, insertIndex, child)
                    if (firstInserted == null && insertIndex > beforeUnwrap) firstInserted = parent.children[beforeUnwrap]
                    continue
                }

                parent.children.add(insertIndex++, child)
                if (firstInserted == null) firstInserted = child
            }

            // The element-level diagnostics onto the first inserted child before the merge: the merge carries the diagnostics of a removed
            // sibling into its target, so they ride along even when firstInserted merges into parent.children[index - 1].
            firstInserted?.addDiagnosticsFromNode(elementNode)

            mergeAdjacentHtmlContentInRange(parent, index, insertIndex)
        }
    }

    /** `ComponentTagHelperResolver`: the resolution of a component document (.razor, language version 3.0+). */
    private class ComponentTagHelperResolver : TagHelperResolver() {
        override fun addMatchedElementDiagnostics(
            tagHelperNode: TagHelperIntermediateNode, elementNode: UnresolvedElementIntermediateNode, binding: TagHelperBinding, context: ResolutionContext,
        ) {
            val tagName = elementNode.tagName

            // RZ10012 for an element that looks like a component but matched no Component or ChildContent tag helper: the catch-all directive
            // attribute helpers (@key, @ref, @rendermode) match any element, they do not count.
            if (looksLikeUnexpectedComponent(context.documentNode, tagName) && !binding.tagHelpers.any { it.kind.isComponentOrChildContentKind }) {
                tagHelperNode.addDiagnostic(ComponentDiagnosticFactory.create_UnexpectedMarkupElement(tagName, elementNode.startTagSpan ?: elementNode.source))
            }

            // The case of the start and end tag names.
            val endTagName = elementNode.endTagName
            if (endTagName != null) {
                val startTagName = elementNode.tagName
                if (startTagName != endTagName) {
                    tagHelperNode.addDiagnostic(ComponentDiagnosticFactory.create_InconsistentStartAndEndTagName(startTagName, endTagName, elementNode.endTagSpan))
                }
            }
        }

        override fun addUnmatchedElementDiagnostic(convertedNode: IntermediateNode, originalNode: UnresolvedElementIntermediateNode, documentNode: DocumentIntermediateNode?) {
            if (looksLikeUnexpectedComponent(documentNode, originalNode.tagName)) {
                convertedNode.addDiagnostic(ComponentDiagnosticFactory.create_UnexpectedMarkupElement(originalNode.tagName, originalNode.startTagSpan ?: originalNode.source))
            }

            for (child in originalNode.children) {
                if (child is UnresolvedAttributeIntermediateNode) addUnboundDirectiveAttributeDiagnostic(documentNode, child)
            }
        }

        override fun lowerComplexNonStringValues(
            htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode, valueSourceSpan: SourceSpan?, sourceDocument: RazorSourceDocument,
        ) = lowerUnresolvedNonStringAttributeValuesComponent(htmlAttr, target)

        override fun lowerComplexStringValues(htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode, sourceDocument: RazorSourceDocument) =
            lowerUnresolvedStringAttributeValuesComponent(htmlAttr, target)

        /**
         * `BuildTagHelper`: the element's children — the unresolved and HTML attributes as tag helper attribute nodes, the already-resolved
         * attribute nodes as they are, the rest (the body content) into the body node.
         */
        override fun buildTagHelper(
            tagHelperNode: TagHelperIntermediateNode, bodyNode: TagHelperBodyIntermediateNode, elementNode: UnresolvedElementIntermediateNode,
            binding: TagHelperBinding, sourceDocument: RazorSourceDocument, context: ResolutionContext,
        ) {
            val renderedBoundAttributeNames = TreeSet(String.CASE_INSENSITIVE_ORDER)
            tagHelperNode.children.add(bodyNode)

            for (child in elementNode.children) {
                if (child is UnresolvedAttributeIntermediateNode) {
                    convertUnresolvedAttributeToTagHelper(tagHelperNode, child, binding, renderedBoundAttributeNames, sourceDocument, context)
                } else if (child is HtmlAttributeIntermediateNode) {
                    convertComponentAttributeToTagHelper(tagHelperNode, child, binding)
                } else if (child is ComponentAttributeIntermediateNode || child is SplatIntermediateNode || child is SetKeyIntermediateNode || child is ReferenceCaptureIntermediateNode) {
                    // The attribute types already resolved need no conversion.
                    tagHelperNode.children.add(child)
                } else if (tagHelperNode.tagMode != TagMode.StartTagOnly) {
                    bodyNode.children.add(child)
                }
            }
        }

        /**
         * `ConvertUnresolvedAttributeToTagHelper`: an unresolved attribute of a component element — directive attributes (@bind-Value: the
         * parameter matches, the mixed content, the bind:get/set wrapping), bound properties, unbound HTML attributes.
         */
        private fun convertUnresolvedAttributeToTagHelper(
            tagHelperNode: TagHelperIntermediateNode, unresolvedAttr: UnresolvedAttributeIntermediateNode, binding: TagHelperBinding,
            renderedBoundAttributeNames: MutableSet<String>, sourceDocument: RazorSourceDocument, context: ResolutionContext,
        ) {
            val attributeName = unresolvedAttr.attributeName
            val matches = ArrayList<TagHelperAttributeMatch>()
            TagHelperMatchingConventions.getAttributeMatches(binding.tagHelpers, attributeName, matches)

            val hasMatches = matches.isNotEmpty()
            val isDuplicateBound = hasMatches && !renderedBoundAttributeNames.add(attributeName)

            if (hasMatches && !isDuplicateBound) {
                val hasDirectiveMatch = matches.any { it.attribute.isDirectiveAttribute }

                for (match in matches) {
                    if (hasDirectiveMatch && !match.attribute.isDirectiveAttribute) continue

                    if (match.attribute.isDirectiveAttribute) convertToUnresolvedDirectiveAttribute(tagHelperNode, unresolvedAttr, match, attributeName, sourceDocument)
                    else convertToUnresolvedBoundProperty(tagHelperNode, unresolvedAttr, match, attributeName, sourceDocument)
                }
            } else {
                if (!hasMatches) addUnboundDirectiveAttributeDiagnostic(context.documentNode, unresolvedAttr)

                convertToUnresolvedUnboundAttribute(tagHelperNode, unresolvedAttr, attributeName, isDuplicateBound)
            }
        }

        /**
         * `ConvertToUnresolvedDirectiveAttribute` (@bind-Value, @onclick, @ref): a [TagHelperDirectiveAttributeIntermediateNode] (the Parameter
         * variant for @bind-Value:get); mixed literal + expression content through [lowerUnresolvedAttributeValues]; the bind:get/set
         * parameters wrapped in a [CSharpExpressionIntermediateNode].
         */
        private fun convertToUnresolvedDirectiveAttribute(
            tagHelperNode: TagHelperIntermediateNode, unresolvedAttr: UnresolvedAttributeIntermediateNode, match: TagHelperAttributeMatch, attributeName: String,
            sourceDocument: RazorSourceDocument,
        ) {
            val attrStructure = unresolvedAttr.attributeStructure
            val directiveAttributeName = DirectiveAttributeName(attributeName)
            val nameSpan = unresolvedAttr.attributeNameSpan
            var directiveNameSpan = nameSpan
            if (directiveNameSpan != null && attributeName.startsWith('@')) {
                directiveNameSpan = directiveNameSpan.copy(
                    absoluteIndex = directiveNameSpan.absoluteIndex + 1, characterIndex = directiveNameSpan.characterIndex + 1, length = directiveNameSpan.length - 1,
                )
            }

            // The parameter suffix stripped from the OriginalAttributeSpan of a parameter match.
            var parameterOriginalSpan = directiveNameSpan
            if (match.isParameterMatch && directiveAttributeName.hasParameter && parameterOriginalSpan != null) {
                val nameWithoutParamLen = directiveAttributeName.textWithoutParameter.length
                parameterOriginalSpan = parameterOriginalSpan.copy(length = nameWithoutParamLen, endCharacterIndex = parameterOriginalSpan.characterIndex + nameWithoutParamLen)
            }

            val directiveNode: IntermediateNode = if (match.isParameterMatch && directiveAttributeName.hasParameter) {
                TagHelperDirectiveAttributeParameterIntermediateNode(
                    match, directiveAttributeName.text, directiveAttributeName.textWithoutParameter, attributeName, attrStructure, parameterOriginalSpan,
                )
            } else {
                TagHelperDirectiveAttributeIntermediateNode(match, directiveAttributeName.text, attributeName, attrStructure, directiveNameSpan)
            }

            if (!unresolvedAttr.isMinimized) {
                // The unresolved children for MIXED string content (literal + expressions), avoiding the MarkupBlock issues of LowerAttributeValue.
                val htmlAttrChild = unresolvedAttr.htmlAttributeNode
                if (htmlAttrChild != null) {
                    // Both literal and expression children?
                    var hasLiteral = false
                    var hasExpression = false
                    for (vc in htmlAttrChild.children) {
                        if (vc is UnresolvedAttributeValueIntermediateNode) hasLiteral = true
                        if (vc is UnresolvedExpressionAttributeValueIntermediateNode) hasExpression = true
                    }

                    val hasMixedStringContent = hasLiteral && hasExpression

                    if (hasMixedStringContent && match.expectsStringValue) {
                        // The unresolved string path for mixed content: HtmlContent + CSharpExpression.
                        lowerUnresolvedAttributeValues(htmlAttrChild, directiveNode, true, unresolvedAttr.valueSourceSpan, sourceDocument)
                    } else if (!match.expectsStringValue && !hasMixedStringContent) {
                        // A non-string directive attribute of a single expression: the unresolved non-string path, flat C# tokens.
                        lowerUnresolvedAttributeValues(htmlAttrChild, directiveNode, false, unresolvedAttr.valueSourceSpan, sourceDocument)
                    } else {
                        // The rest (a string of a single expression, a non-string of mixed content): ConvertUnresolvedValuesToBasicForm into the
                        // standard node types, then post-processed.
                        convertUnresolvedValuesToBasicForm(htmlAttrChild, directiveNode)

                        if (match.expectsStringValue) convertExpressionAttributeValuesToCSharpExpression(directiveNode)
                    }
                }

                directiveNode.source = unresolvedAttr.valueSourceSpan ?: (if (directiveNode.children.size > 0) directiveNode.children[0].source else null)

                if (!match.expectsStringValue) {
                    if (match.isParameterMatch) flattenDirectiveChildrenToCSharpTokens(directiveNode)
                    else normalizeBoundPropertyChildren(directiveNode, wrapLiteralsInCSharpExpression = true)
                }

                // The bind:get / bind:set parameter matches need the CSharpExpression wrapping.
                val parameterName = match.parameter?.name
                if (match.isParameterMatch && (parameterName == "get" || parameterName == "set") && directiveNode.children.size > 0 &&
                    directiveNode.children[0] !is CSharpExpressionIntermediateNode
                ) {
                    val expr = CSharpExpressionIntermediateNode()
                    expr.children.addAll(directiveNode.children)
                    directiveNode.children.clear()
                    expr.source = if (expr.children.size > 0) expr.children[0].source else directiveNode.source
                    directiveNode.children.add(expr)
                }
            }

            tagHelperNode.children.add(directiveNode)
        }

        /**
         * `ConvertToUnresolvedBoundProperty` (Value, Class of a tag helper): a [TagHelperPropertyIntermediateNode], the value through
         * [lowerUnresolvedAttributeValues]; an empty value gets a synthetic child (an empty value is not no value, a minimized attribute).
         */
        private fun convertToUnresolvedBoundProperty(
            tagHelperNode: TagHelperIntermediateNode, unresolvedAttr: UnresolvedAttributeIntermediateNode, match: TagHelperAttributeMatch, attributeName: String,
            sourceDocument: RazorSourceDocument,
        ) {
            val attrStructure = unresolvedAttr.attributeStructure
            val nameSpan = unresolvedAttr.attributeNameSpan
            val prop = TagHelperPropertyIntermediateNode(match, attributeName, attrStructure, nameSpan)

            if (!unresolvedAttr.isMinimized) {
                val htmlAttrChild = unresolvedAttr.htmlAttributeNode

                if (htmlAttrChild != null) lowerUnresolvedAttributeValues(htmlAttrChild, prop, match.expectsStringValue, unresolvedAttr.valueSourceSpan, sourceDocument)

                // Still no children (Href=""): a synthetic empty child, as the legacy tag helper path does.
                if (prop.children.size == 0) {
                    val emptySpan = unresolvedAttr.valueSourceSpan
                    if (match.expectsStringValue) prop.children.add(createEmptyHtmlContent(emptySpan)) else prop.children.add(createEmptyCSharpToken(emptySpan))
                }
            }

            prop.source = unresolvedAttr.valueSourceSpan ?: (if (prop.children.size > 0) prop.children[0].source else null)

            tagHelperNode.children.add(prop)
        }

        private companion object {
            /**
             * `ConvertToUnresolvedUnboundAttribute`: an attribute without matches, a [TagHelperHtmlAttributeIntermediateNode] of the pre-lowered
             * [UnresolvedAttributeIntermediateNode.asTagHelperAttribute]; a duplicate bound directive attribute gets its expression values as
             * [CSharpExpressionIntermediateNode].
             */
            fun convertToUnresolvedUnboundAttribute(
                tagHelperNode: TagHelperIntermediateNode, unresolvedAttr: UnresolvedAttributeIntermediateNode, attributeName: String, isDuplicateBound: Boolean,
            ) {
                // Not bound: lowered again as a regular HTML attribute.
                val htmlAttrNode = TagHelperHtmlAttributeIntermediateNode(
                    attributeName, unresolvedAttr.attributeStructure, unresolvedAttr.attributeNameSpan, unresolvedAttr.isDirectiveAttributeCandidate,
                )

                val fallbackAttr = unresolvedAttr.asTagHelperAttribute
                if (!unresolvedAttr.isMinimized && fallbackAttr is HtmlAttributeIntermediateNode) {
                    // The value children of the pre-lowered fallback form.
                    htmlAttrNode.children.addAll(fallbackAttr.children)

                    // A duplicate bound directive attribute (the second @formname="@y"): CSharpExpressionAttributeValue to CSharpExpression.
                    if (isDuplicateBound) convertExpressionAttributeValuesToCSharpExpression(htmlAttrNode)

                    if (htmlAttrNode.children.size == 0) htmlAttrNode.children.add(createEmptyHtmlContent(null))
                } else if (!unresolvedAttr.isMinimized && unresolvedAttr.attributeStructure != AttributeStructure.Minimized) {
                    // An empty value with quotes (onsubmit=""): empty content.
                    htmlAttrNode.children.add(createEmptyHtmlContent(null))
                }

                tagHelperNode.children.add(htmlAttrNode)
            }

            /**
             * `NormalizeBoundPropertyChildren`: the markup-shaped children of a bound property (HtmlContent, CSharpExpressionAttributeValue...;
             * the resolution works on the unrewritten syntax) in the C# shapes the tag helper properties expect. [wrapLiteralsInCSharpExpression]
             * (directive attributes): the literals wrapped in a [CSharpExpressionIntermediateNode]; else (bound properties) direct C# tokens.
             */
            fun normalizeBoundPropertyChildren(prop: IntermediateNode, wrapLiteralsInCSharpExpression: Boolean) {
                val newChildren = ArrayList<IntermediateNode>()

                for (child in prop.children) {
                    if (child is CSharpExpressionAttributeValueIntermediateNode) {
                        // An expression after literal content (not at the start of the value): the @ transition and the prefix whitespace merged
                        // into the first C# token when the prefix is not empty; with an empty prefix the @ was a normal transition, not output.
                        val exprPrefix = child.prefix
                        if (wrapLiteralsInCSharpExpression && !exprPrefix.isNullOrEmpty()) {
                            // The only child a CSharpExpression: prefix + @ prepended to its first token, the node reused (no double wrapping).
                            val innerExpr = child.children.singleOrNull() as? CSharpExpressionIntermediateNode
                            if (innerExpr != null) {
                                val firstToken = innerExpr.children.firstOrNull()
                                if (firstToken is CSharpIntermediateToken) innerExpr.children[0] = CSharpIntermediateToken(exprPrefix + "@" + firstToken.content, firstToken.source)

                                newChildren.add(innerExpr)
                            } else {
                                val expr = CSharpExpressionIntermediateNode().also { it.source = child.source }
                                var prefixedFirst = false
                                for (token in child.children) {
                                    if (!prefixedFirst && token is CSharpIntermediateToken) {
                                        expr.children.add(CSharpIntermediateToken(exprPrefix + "@" + token.content, token.source))
                                        prefixedFirst = true
                                    } else {
                                        expr.children.add(token)
                                    }
                                }

                                newChildren.add(expr)
                            }
                        } else {
                            // No double wrapping: the only child already a CSharpExpression (VisitCSharpImplicitExpression) is reused; a single
                            // expression value yields direct tokens without an extra wrapper.
                            val existingExpr = child.children.singleOrNull() as? CSharpExpressionIntermediateNode
                            if (existingExpr != null) {
                                newChildren.add(existingExpr)
                            } else {
                                // CSharpExpressionAttributeValue -> CSharpExpression (always wrapped).
                                val expr = CSharpExpressionIntermediateNode().also { it.source = child.source }
                                expr.children.addAll(child.children)

                                newChildren.add(expr)
                            }
                        }
                    } else if (child is CSharpCodeAttributeValueIntermediateNode) {
                        // CSharpCodeAttributeValue -> CSharpExpression (always wrapped).
                        val expr = CSharpExpressionIntermediateNode().also { it.source = child.source }
                        expr.children.addAll(child.children)

                        newChildren.add(expr)
                    } else if (child is HtmlContentIntermediateNode || child is HtmlAttributeValueIntermediateNode) {
                        convertHtmlTokensToCSharp(child.children, newChildren, child.source, wrapLiteralsInCSharpExpression)
                    } else {
                        newChildren.add(child)
                    }
                }

                prop.children.clear()
                prop.children.addAll(newChildren)

                // The C# tokens merged into one token for a non-directive bound property; a directive keeps its CSharpExpression wrappers for
                // the passes downstream (ComponentBindLoweringPass).
                if (!wrapLiteralsInCSharpExpression) mergeAdjacentCSharpTokens(prop)
            }

            /**
             * `MergeAdjacentCSharpTokens`: the [CSharpIntermediateToken] children (and the content of [CSharpExpressionIntermediateNode]
             * wrappers) as one token — a tag helper property expects one token of the whole expression; only when every child is a C# token or
             * an expression of C# tokens.
             */
            fun mergeAdjacentCSharpTokens(node: IntermediateNode) {
                // All children flattenable to C# tokens?
                var canMerge = node.children.size > 1
                for (child in node.children) {
                    if (child is CSharpIntermediateToken) continue

                    if (child is CSharpExpressionIntermediateNode && child.children.all { it is CSharpIntermediateToken }) continue

                    // A non-flattenable child: no merge.
                    canMerge = false
                    break
                }

                if (!canMerge) return

                val sb = StringBuilder()
                var firstSpan: SourceSpan? = null
                var lastSpan: SourceSpan? = null

                for (child in node.children) {
                    if (child is CSharpIntermediateToken) {
                        sb.append(child.content)
                        val s = child.source
                        if (s != null) {
                            if (firstSpan == null) firstSpan = s
                            lastSpan = s
                        }
                    } else if (child is CSharpExpressionIntermediateNode) {
                        for (inner in child.children) {
                            if (inner is CSharpIntermediateToken) {
                                sb.append(inner.content)
                                val s = inner.source
                                if (s != null) {
                                    if (firstSpan == null) firstSpan = s
                                    lastSpan = s
                                }
                            }
                        }
                    }
                }

                var mergedSpan: SourceSpan? = null
                if (firstSpan != null && lastSpan != null) mergedSpan = mergeSourceSpans(firstSpan, lastSpan)

                val content = sb.toString()
                node.children.clear()
                node.children.add(CSharpIntermediateToken(content, mergedSpan))
            }

            /** `LowerUnresolvedNonStringAttributeValues_Component`: each child flattened on its own (no merging). */
            fun lowerUnresolvedNonStringAttributeValuesComponent(htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode) {
                for (child in htmlAttr.children) {
                    if (child is UnresolvedAttributeValueIntermediateNode) {
                        for (valueChild in child.children) if (valueChild is HtmlIntermediateToken) target.children.add(toCSharpToken(valueChild))
                    } else if (child is UnresolvedExpressionAttributeValueIntermediateNode) {
                        flattenToDirectCSharpTokens(child, target)
                    } else {
                        flattenToDirectCSharpTokens(child, target)
                    }
                }
            }

            /** `LowerUnresolvedStringAttributeValues_Component`: each child processed on its own (no merging), the adjacent HtmlContent merged after. */
            fun lowerUnresolvedStringAttributeValuesComponent(htmlAttr: HtmlAttributeIntermediateNode, target: IntermediateNode) {
                for (child in htmlAttr.children) {
                    if (child is UnresolvedAttributeValueIntermediateNode) {
                        val htmlContent = HtmlContentIntermediateNode()
                        val prefix = child.prefix
                        var mergedFirst = false

                        for (valueChild in child.children) {
                            if (!mergedFirst && prefix.isNotEmpty() && valueChild is HtmlIntermediateToken) {
                                // The prefix merged into the first token.
                                val mergedContent = prefix + valueChild.content
                                val mergedSource = extendSpanBackward(valueChild.source, prefix.length)

                                htmlContent.children.add(HtmlIntermediateToken(mergedContent, mergedSource))
                                if (htmlContent.source == null) htmlContent.source = mergedSource
                                mergedFirst = true
                            } else {
                                htmlContent.children.add(valueChild)
                                if (htmlContent.source == null) htmlContent.source = valueChild.source
                            }
                        }

                        if (!mergedFirst && prefix.isNotEmpty()) htmlContent.children.add(0, HtmlIntermediateToken(prefix, null))

                        target.children.add(htmlContent)
                    } else if (child is UnresolvedExpressionAttributeValueIntermediateNode) {
                        // The prefix (the space before @expr) as HtmlContent.
                        if (child.prefix.isNotEmpty()) {
                            val prefixContent = HtmlContentIntermediateNode()
                            prefixContent.children.add(HtmlIntermediateToken(child.prefix, null))
                            target.children.add(prefixContent)
                        }

                        // Wrapped in a CSharpExpression.
                        val expr = CSharpExpressionIntermediateNode()
                        flattenToDirectCSharpTokens(child, expr)
                        expr.source = if (expr.children.size > 0) expr.children[0].source else child.source
                        target.children.add(expr)
                    } else if (child is IntermediateToken) {
                        // A bare token (of an @@ escape) wrapped in HtmlContent.
                        val htmlContent = HtmlContentIntermediateNode().also { it.source = child.source }
                        htmlContent.children.add(child)
                        target.children.add(htmlContent)
                    } else {
                        target.children.add(child)
                    }
                }

                // The adjacent HtmlContent merged (the @@ escape "@" + "currentCount" -> "@currentCount").
                mergeAdjacentHtmlContent(target)
            }

            /** `FlattenDirectiveChildrenToCSharpTokens`: the wrapper nodes of a directive attribute removed, direct C# tokens (a parameter match of a simple identifier). */
            fun flattenDirectiveChildrenToCSharpTokens(directiveNode: IntermediateNode) {
                val newChildren = ArrayList<IntermediateNode>()
                for (child in directiveNode.children) {
                    if (child is HtmlContentIntermediateNode || child is UnresolvedAttributeValueIntermediateNode) {
                        for (token in child.children) {
                            if (token is HtmlIntermediateToken) newChildren.add(toCSharpToken(token)) else newChildren.add(token)
                        }
                    } else if (child is CSharpExpressionIntermediateNode || child is CSharpExpressionAttributeValueIntermediateNode || child is UnresolvedExpressionAttributeValueIntermediateNode) {
                        // The expression children flattened to direct tokens.
                        for (token in child.children) newChildren.add(token)
                    } else {
                        newChildren.add(child)
                    }
                }
                directiveNode.children.clear()
                directiveNode.children.addAll(newChildren)
            }

            /**
             * `ConvertExpressionAttributeValuesToCSharpExpression`: the [CSharpExpressionAttributeValueIntermediateNode] children as
             * [CSharpExpressionIntermediateNode], no HTML converted, no structure flattened (string-valued directive attributes, which the old
             * pipeline made CSharpExpression of from the rewritten syntax).
             */
            fun convertExpressionAttributeValuesToCSharpExpression(node: IntermediateNode) {
                var i = node.children.size - 1
                while (i >= 0) {
                    val child = node.children[i]
                    if (child is CSharpExpressionAttributeValueIntermediateNode) {
                        convertExpressionChildToCSharpExpression(node, i, child.prefix, child.children, child.source)
                        // A prefix node inserted: i adjusted.
                        if (node.children[i] is HtmlContentIntermediateNode) i++ // skip past the inserted prefix to the expression just placed
                    } else if (child is UnresolvedExpressionAttributeValueIntermediateNode) {
                        convertExpressionChildToCSharpExpression(node, i, child.prefix, child.children, child.source)
                        if (node.children[i] is HtmlContentIntermediateNode) i++
                    } else if (child is HtmlAttributeValueIntermediateNode) {
                        // HtmlAttributeValue -> HtmlContent, the prefix merged into the first token.
                        val htmlContent = HtmlContentIntermediateNode()
                        val prefix = child.prefix
                        val firstToken = child.children.firstOrNull()

                        if (!prefix.isNullOrEmpty() && firstToken is IntermediateToken) {
                            val mergedContent = prefix + firstToken.content
                            val mergedSource = extendSpanBackward(firstToken.source, prefix.length)

                            htmlContent.children.add(HtmlIntermediateToken(mergedContent, mergedSource))
                            htmlContent.source = mergedSource ?: child.source

                            for (j in 1 until child.children.size) htmlContent.children.add(child.children[j])
                        } else {
                            htmlContent.source = child.source
                            htmlContent.children.addAll(child.children)
                        }

                        node.children[i] = htmlContent
                    } else if (node.children[i] is CSharpCodeAttributeValueIntermediateNode) {
                        // CSharpCodeAttributeValue -> CSharpCode.
                        val csharpCode = CSharpCodeIntermediateNode().also { it.source = child.source }
                        csharpCode.children.addAll(child.children)
                        csharpCode.source = if (csharpCode.children.size > 0) csharpCode.children[0].source else child.source

                        node.children[i] = csharpCode
                    } else if (node.children[i] is MarkupBlockIntermediateNode) {
                        // MarkupBlock -> HtmlContent.
                        val htmlContent = HtmlContentIntermediateNode().also { it.source = child.source }
                        htmlContent.children.addAll(child.children)

                        node.children[i] = htmlContent
                    }
                    i--
                }
            }

            /**
             * `ConvertExpressionChildToCSharpExpression`: an expression attribute value child ([CSharpExpressionAttributeValueIntermediateNode]
             * or [UnresolvedExpressionAttributeValueIntermediateNode]) as a [CSharpExpressionIntermediateNode], a prefix HtmlContent inserted.
             */
            fun convertExpressionChildToCSharpExpression(parent: IntermediateNode, index: Int, prefix: String?, children: IntermediateNodeCollection, fallbackSource: SourceSpan?) {
                var index = index
                if (!prefix.isNullOrEmpty()) {
                    val prefixContent = HtmlContentIntermediateNode()
                    prefixContent.children.add(HtmlIntermediateToken(prefix, null))
                    parent.children.add(index, prefixContent)
                    index++
                }

                val expr = CSharpExpressionIntermediateNode()
                for (token in children) expr.children.add(token)
                expr.source = if (expr.children.size > 0) expr.children[0].source else fallbackSource

                parent.children[index] = expr
            }

            fun convertComponentAttributeToTagHelper(tagHelperNode: TagHelperIntermediateNode, htmlAttr: HtmlAttributeIntermediateNode, binding: TagHelperBinding) {
                val attributeName = htmlAttr.attributeName ?: ""
                val matches = ArrayList<TagHelperAttributeMatch>()
                TagHelperMatchingConventions.getAttributeMatches(binding.tagHelpers, attributeName, matches)

                // The attribute name span from the HtmlAttributeIntermediateNode: the source covers the whole attribute, the name is at the
                // start of the prefix.
                val attributeNameSpan = computeAttributeNameSpan(htmlAttr)
                val attributeValueSpan = computeAttributeValueSpan(htmlAttr)

                if (matches.isNotEmpty()) {
                    for (match in matches) {
                        if (match.attribute.isDirectiveAttribute) {
                            val directiveAttributeName = DirectiveAttributeName(attributeName)

                            // The OriginalAttributeSpan of a directive attribute is the name WITHOUT the leading '@' ("bind-Value", not
                            // "@bind-Value"): the passes downstream (ComponentBindLoweringPass) offset from it.
                            var directiveNameSpan = attributeNameSpan
                            if (directiveNameSpan != null && attributeName.startsWith('@')) {
                                directiveNameSpan = directiveNameSpan.copy(
                                    absoluteIndex = directiveNameSpan.absoluteIndex + 1, characterIndex = directiveNameSpan.characterIndex + 1, length = directiveNameSpan.length - 1,
                                )
                            }

                            val directiveNode: IntermediateNode = if (match.isParameterMatch && directiveAttributeName.hasParameter) {
                                TagHelperDirectiveAttributeParameterIntermediateNode(
                                    match, directiveAttributeName.text, directiveAttributeName.textWithoutParameter, attributeName, inferAttributeStructure(htmlAttr),
                                    directiveNameSpan,
                                )
                            } else {
                                TagHelperDirectiveAttributeIntermediateNode(match, directiveAttributeName.text, attributeName, inferAttributeStructure(htmlAttr), directiveNameSpan)
                            }
                            directiveNode.source = attributeValueSpan

                            copyAsTagHelperAttributeValues(htmlAttr, directiveNode)

                            if (!match.expectsStringValue) normalizeBoundPropertyChildren(directiveNode, wrapLiteralsInCSharpExpression = true)

                            tagHelperNode.children.add(directiveNode)
                        } else {
                            val prop = TagHelperPropertyIntermediateNode(match, attributeName, inferAttributeStructure(htmlAttr), attributeNameSpan)
                            prop.source = attributeValueSpan

                            copyAsTagHelperAttributeValues(htmlAttr, prop)

                            if (!match.expectsStringValue) normalizeBoundPropertyChildren(prop, wrapLiteralsInCSharpExpression = false)

                            tagHelperNode.children.add(prop)
                        }
                    }
                } else {
                    val thHtml = TagHelperHtmlAttributeIntermediateNode(attributeName, inferAttributeStructure(htmlAttr), attributeNameSpan)

                    thHtml.children.addAll(htmlAttr.children)

                    // CSharpExpressionAttributeValue to CSharpExpression for an unbound attribute with expression values (a duplicate
                    // @formname="@y").
                    convertExpressionAttributeValuesToCSharpExpression(thHtml)

                    tagHelperNode.children.add(thHtml)
                }
            }

            /**
             * `CopyAsTagHelperAttributeValues`: the value children in the IR structure of a tag helper property — literal values as
             * HtmlContent of HtmlIntermediateToken (the adjacent ones merged into one), expression values as direct C# tokens (no wrapper).
             */
            fun copyAsTagHelperAttributeValues(source: HtmlAttributeIntermediateNode, target: IntermediateNode) {
                // All children literal attribute values: merged into one HtmlContent (the adjacent literal tokens combined).
                if (areAllChildrenOfType<HtmlAttributeValueIntermediateNode>(source.children) && source.children.size > 1) {
                    // All literal pieces (with their prefixes) in one HtmlContent.
                    val mergedContent = HtmlContentIntermediateNode().also { it.source = source.source }

                    val sb = StringBuilder()
                    for (child in source.children) sb.append(collectAttributeValueContent(child as HtmlAttributeValueIntermediateNode).first)

                    val mergedText = sb.toString()

                    // The span of the value of the HtmlAttribute if any, else from the first child.
                    val firstValue = source.children[0] as HtmlAttributeValueIntermediateNode
                    var spanSource = firstValue.source
                    val fs = spanSource
                    if (fs != null) {
                        // From the start of the first value to the end of the whole value.
                        val lastValue = source.children[source.children.size - 1]
                        val ls = lastValue.source
                        val totalLength = if (lastValue is HtmlAttributeValueIntermediateNode && ls != null) (ls.absoluteIndex + ls.length) - fs.absoluteIndex else mergedText.length
                        spanSource = fs.copy(length = totalLength)
                    }

                    mergedContent.source = spanSource
                    mergedContent.children.add(HtmlIntermediateToken(mergedText, spanSource))

                    target.children.add(mergedContent)
                    return
                }

                for (child in source.children) {
                    if (child is HtmlAttributeValueIntermediateNode) {
                        // A literal value: VisitAttributeValue -> MarkupTextLiteral -> VisitMarkupTextLiteral makes HtmlContentIntermediateNode ->
                        // HtmlIntermediateToken.
                        val htmlContent = HtmlContentIntermediateNode().also { it.source = child.source }

                        htmlContent.children.addAll(child.children)

                        target.children.add(htmlContent)
                    } else if (child is CSharpExpressionAttributeValueIntermediateNode || child is CSharpCodeAttributeValueIntermediateNode) {
                        // An expression / code value: flattened to direct C# tokens.
                        flattenToDirectCSharpTokens(child, target)
                    } else {
                        target.children.add(child)
                    }
                }
            }

            fun computeAttributeNameSpan(htmlAttr: HtmlAttributeIntermediateNode): SourceSpan? {
                val attrSource = htmlAttr.source ?: return null

                val nameLength = htmlAttr.attributeName?.length ?: 0
                if (nameLength == 0) return attrSource

                // The source of an HtmlAttributeIntermediateNode includes the leading whitespace; the prefix is " name=\"": where the name starts.
                val prefix = htmlAttr.prefix ?: ""
                var nameIndex = prefix.indexOf(htmlAttr.attributeName!!)
                if (nameIndex < 0) nameIndex = 0

                val nameCharIndex = attrSource.characterIndex + nameIndex

                return attrSource.copy(
                    absoluteIndex = attrSource.absoluteIndex + nameIndex, characterIndex = nameCharIndex, length = nameLength, lineCount = 0,
                    endCharacterIndex = nameCharIndex + nameLength,
                )
            }

            fun computeAttributeValueSpan(htmlAttr: HtmlAttributeIntermediateNode): SourceSpan? {
                // The value span from the children, more accurate.
                val childSource = htmlAttr.children.firstOrNull()?.source
                if (childSource != null) {
                    // One child: its source; more: the spans merged.
                    if (htmlAttr.children.size == 1) return childSource

                    // The spans of all children merged.
                    val lastChild = htmlAttr.children[htmlAttr.children.size - 1]
                    val lastSource = lastChild.source
                    if (lastSource != null) {
                        val endIndex = lastSource.absoluteIndex + lastSource.length
                        val length = endIndex - childSource.absoluteIndex
                        val endCharIndex = lastSource.characterIndex + lastSource.length
                        // Not lastSource.lineCount: multi-line attribute values are uncommon and the old pipeline had the same limitation.
                        return childSource.copy(length = length, lineCount = lastSource.lineIndex - childSource.lineIndex, endCharacterIndex = endCharIndex)
                    }

                    return childSource
                }

                val attrSource = htmlAttr.source ?: return null

                // The fallback: from the prefix and the suffix.
                val prefix = htmlAttr.prefix ?: ""
                val suffix = htmlAttr.suffix ?: ""

                val valueStart = prefix.length
                val valueLength = attrSource.length - prefix.length - suffix.length
                if (valueLength <= 0) return null

                val valueCharIndex = attrSource.characterIndex + valueStart

                return attrSource.copy(
                    absoluteIndex = attrSource.absoluteIndex + valueStart, characterIndex = valueCharIndex, length = valueLength, lineCount = 0,
                    endCharacterIndex = valueCharIndex + valueLength,
                )
            }

            fun addUnboundDirectiveAttributeDiagnostic(documentNode: DocumentIntermediateNode?, attribute: UnresolvedAttributeIntermediateNode) {
                if (attribute.isDirectiveAttributeCandidate) {
                    documentNode!!.addDiagnostic(ComponentDiagnosticFactory.create_UnboundDirectiveAttribute(attribute.attributeNameSpan, attribute.attributeName))
                }
            }

            fun looksLikeUnexpectedComponent(documentNode: DocumentIntermediateNode?, tagName: String?): Boolean =
                documentNode != null && documentNode.options?.suppressPrimaryMethodBody != true && !tagName.isNullOrEmpty() &&
                    DefaultRazorIntermediateNodeLoweringPhase.looksLikeAComponentName(documentNode, tagName)
        }

        /**
         * `ComponentTagHelperResolver.ConvertToPlainElement`: a non-tag-helper element of a component file as a [MarkupElementIntermediateNode]
         * (the tag name and the source kept), the unresolved attributes replaced with their [UnresolvedAttributeIntermediateNode.asMarkupAttribute].
         */
        override fun convertToPlainElement(parent: IntermediateNode, index: Int, elementNode: UnresolvedElementIntermediateNode) {
            val markupElement = MarkupElementIntermediateNode()
            markupElement.source = elementNode.source
            markupElement.tagName = elementNode.tagName

            // The diagnostics moved.
            markupElement.addDiagnosticsFromNode(elementNode)

            // All children moved, the unresolved attributes lowered to their fallback form.
            for (child in elementNode.children) {
                if (child is UnresolvedAttributeIntermediateNode) {
                    // The pre-lowered AsMarkupAttribute fallback form.
                    val asMarkupAttribute = child.asMarkupAttribute
                    if (asMarkupAttribute != null) markupElement.children.add(asMarkupAttribute)
                } else {
                    markupElement.children.add(child)
                }
            }

            parent.children[index] = markupElement
        }
    }

    /** `DirectiveAttributeName`: a directive attribute name like `@bind-Value:event` in its parts. */
    private class DirectiveAttributeName(fullAttributeName: String) {
        val text: String
        val textWithoutParameter: String
        val hasParameter: Boolean

        init {
            // Directive attribute names: @bind-Value, @bind-Value:event, @onclick, @onclick:preventDefault. The leading @ stripped.
            val span = if (fullAttributeName.startsWith('@')) fullAttributeName.substring(1) else fullAttributeName

            val colonIndex = span.indexOf(':')
            hasParameter = colonIndex >= 0
            textWithoutParameter = if (hasParameter) span.substring(0, colonIndex) else span
            text = span
        }
    }

    companion object {
        /**
         * `MergeAdjacentHtmlContent`: the post-pass consolidating adjacent [HtmlContentIntermediateNode] children (tokens merged, spans extended,
         * diagnostics kept), after the unwrapping of elements.
         */
        private fun mergeAdjacentHtmlContent(parent: IntermediateNode) {
            var i = 0
            while (i < parent.children.size - 1) {
                val current = parent.children[i]
                val next = parent.children[i + 1]
                if (current is HtmlContentIntermediateNode && next is HtmlContentIntermediateNode) {
                    current.children.addAll(next.children)

                    // The diagnostics of the removed sibling kept.
                    current.addDiagnosticsFromNode(next)
                    val cs = current.source
                    val ns = next.source
                    if (cs != null && ns != null) {
                        // Adjacent nodes are sequential: next always ends after current.
                        current.source = mergeSourceSpans(cs, ns)
                    } else if (cs == null) {
                        current.source = ns
                    }
                    parent.children.removeAt(i + 1)
                    i--
                }
                i++
            }
        }

        private fun getTagMode(elementNode: UnresolvedElementIntermediateNode, binding: TagHelperBinding): TagMode {
            if (elementNode.isSelfClosing) return TagMode.SelfClosing

            var hasDirectiveAttribute = false
            for (boundRulesInfo in binding.allBoundRules) {
                if (boundRulesInfo.rules.any { it.tagStructure == TagStructure.WithoutEndTag }) return TagMode.StartTagOnly

                val descriptor = boundRulesInfo.descriptor
                hasDirectiveAttribute = hasDirectiveAttribute or (descriptor.isAnyComponentDocumentTagHelper() && !descriptor.isComponentOrChildContentTagHelper())
            }

            if (hasDirectiveAttribute && elementNode.isVoidElement && !elementNode.hasEndTag) return TagMode.StartTagOnly

            return TagMode.StartTagAndEndTag
        }

        /** `AddStructuralDiagnostics`: RZ1035 (missing close angle), RZ1034 (malformed tag helper), RZ1042 (void element) of a matched node. */
        private fun addStructuralDiagnostics(
            tagHelperNode: TagHelperIntermediateNode, elementNode: UnresolvedElementIntermediateNode, tagName: String, tagMode: TagMode, isResolvedVoidElement: Boolean,
        ) {
            // RZ1035 before RZ1034, in the rewriter's order.
            if (elementNode.hasMissingCloseAngle) {
                val ds = elementNode.startTagNameSpan ?: elementNode.source
                if (ds != null) tagHelperNode.addDiagnostic(RazorDiagnosticFactory.parsing_TagHelperMissingCloseAngle(ds, tagName))
            }

            val endDs = elementNode.endTagSpan
            if (elementNode.hasMissingEndCloseAngle && endDs != null) {
                tagHelperNode.addDiagnostic(RazorDiagnosticFactory.parsing_TagHelperMissingCloseAngle(endDs, elementNode.endTagName ?: tagName))
            }

            // RZ1034 (a malformed tag helper without an end tag): a non-void, non-self-closing element without an end tag whose TagMode
            // expects one (StartTagAndEndTag).
            if (!elementNode.hasEndTag && !elementNode.isSelfClosing && !isResolvedVoidElement && tagMode == TagMode.StartTagAndEndTag) {
                val ds = elementNode.startTagNameSpan ?: elementNode.source
                if (ds != null) tagHelperNode.addDiagnostic(RazorDiagnosticFactory.parsing_TagHelperFoundMalformedTagHelper(ds, tagName))
            }

            // RZ1042: a tag helper named as a void element without an end tag whose binding expected one (StartTagAndEndTag): the parser took
            // it for void. StartTagOnly (TagStructure.WithoutEndTag) explicitly permits no end tag.
            if (isResolvedVoidElement && !elementNode.hasEndTag && tagMode == TagMode.StartTagAndEndTag) {
                val ds = elementNode.startTagNameSpan ?: elementNode.source
                if (ds != null) tagHelperNode.addDiagnostic(RazorDiagnosticFactory.parsing_VoidElement(ds, tagName))
            }
        }

        private fun validateAllowedChild(
            diagnosticTarget: TagHelperIntermediateNode, elementNode: UnresolvedElementIntermediateNode, childTagName: String?, allowedNames: List<String>,
            allowedChildrenString: String,
        ) {
            if (childTagName.isNullOrEmpty()) {
                diagnosticTarget.addDiagnostic(
                    RazorDiagnosticFactory.tagHelper_CannotHaveNonTagContent(elementNode.source ?: SourceSpan.UNDEFINED, diagnosticTarget.tagName, allowedChildrenString),
                )
            } else if (!isAllowedChild(childTagName, allowedNames)) {
                diagnosticTarget.addDiagnostic(
                    RazorDiagnosticFactory.tagHelper_InvalidNestedTag(elementNode.source ?: SourceSpan.UNDEFINED, childTagName, diagnosticTarget.tagName, allowedChildrenString),
                )
            }
        }

        private fun validateAllowedChildren(
            parentTagName: String, children: IntermediateNodeCollection, allowedNames: List<String>, allowedChildrenString: String, prefix: String?,
        ) {
            for (child in children) {
                if (child is TagHelperIntermediateNode) {
                    val childTagName = child.tagName
                    if (!isAllowedChild(childTagName, allowedNames)) {
                        child.addDiagnostic(
                            RazorDiagnosticFactory.tagHelper_InvalidNestedTag(child.source ?: SourceSpan.UNDEFINED, childTagName, parentTagName, allowedChildrenString),
                        )
                    }
                } else if (child is MarkupElementIntermediateNode) {
                    var childTagName = child.tagName
                    // The prefix stripped.
                    if (prefix != null && childTagName != null && childTagName.startsWith(prefix, ignoreCase = true)) childTagName = childTagName.substring(prefix.length)
                    if (childTagName != null && !isAllowedChild(childTagName, allowedNames)) {
                        child.addDiagnostic(
                            RazorDiagnosticFactory.tagHelper_InvalidNestedTag(child.source ?: SourceSpan.UNDEFINED, childTagName, parentTagName, allowedChildrenString),
                        )
                    }
                } else if (child is HtmlContentIntermediateNode) {
                    // Non-whitespace content?
                    for (token in child.children) {
                        if (token is IntermediateToken && token.content.isNotBlank()) {
                            child.addDiagnostic(
                                RazorDiagnosticFactory.tagHelper_CannotHaveNonTagContent(child.source ?: SourceSpan.UNDEFINED, parentTagName, allowedChildrenString),
                            )
                            break
                        }
                    }
                } else if (child is CSharpExpressionIntermediateNode) {
                    child.addDiagnostic(RazorDiagnosticFactory.tagHelper_CannotHaveNonTagContent(child.source ?: SourceSpan.UNDEFINED, parentTagName, allowedChildrenString))
                }
            }
        }

        private fun isAllowedChild(tagName: String, allowedNames: List<String>): Boolean {
            for (name in allowedNames) if (tagName.equals(name, ignoreCase = true)) return true
            return false
        }

        private fun convertUnresolvedValuesToBasicForm(source: HtmlAttributeIntermediateNode, target: IntermediateNode) {
            // All children unresolved literal values: merged into one HtmlContent, for the preallocated attribute optimization.
            if (areAllChildrenOfType<UnresolvedAttributeValueIntermediateNode>(source.children) && source.children.size > 0) {
                val htmlContent = HtmlContentIntermediateNode()
                for (child in source.children) {
                    val unresolvedLiteral = child as UnresolvedAttributeValueIntermediateNode
                    for (valueChild in unresolvedLiteral.children) {
                        htmlContent.children.add(valueChild)
                        if (htmlContent.source == null) htmlContent.source = valueChild.source
                    }
                }

                // The merged span of several children (the source of a single one is set above).
                if (htmlContent.children.size > 1) {
                    val firstSrc = htmlContent.children[0].source
                    val lastSrc = htmlContent.children[htmlContent.children.size - 1].source
                    if (firstSrc != null && lastSrc != null) htmlContent.source = mergeSourceSpans(firstSrc, lastSrc)
                }

                target.children.add(htmlContent)
                return
            }

            for (child in source.children) {
                if (child is UnresolvedAttributeValueIntermediateNode) {
                    val htmlAttrValue = HtmlAttributeValueIntermediateNode()
                    htmlAttrValue.prefix = child.prefix
                    htmlAttrValue.source = child.source

                    htmlAttrValue.children.addAll(child.children)

                    target.children.add(htmlAttrValue)
                } else if (child is UnresolvedExpressionAttributeValueIntermediateNode) {
                    val exprNode: IntermediateNode = if (child.containsExpression) {
                        CSharpExpressionAttributeValueIntermediateNode().also {
                            it.prefix = child.prefix
                            it.source = child.source
                        }
                    } else {
                        CSharpCodeAttributeValueIntermediateNode().also {
                            it.prefix = child.prefix
                            it.source = child.source
                        }
                    }

                    // An expression attribute value: CSharpExpression and CSharpCode unwrapped; a code attribute value: only CSharpCode unwrapped,
                    // CSharpExpression kept.
                    val unwrapExpressions = child.containsExpression
                    for (valueChild in child.children) {
                        if (unwrapExpressions && valueChild is CSharpExpressionIntermediateNode) exprNode.children.addAll(valueChild.children)
                        else if (valueChild is CSharpCodeIntermediateNode) exprNode.children.addAll(valueChild.children)
                        else exprNode.children.add(valueChild)
                    }

                    target.children.add(exprNode)
                } else {
                    target.children.add(child)
                }
            }
        }

        private fun convertHtmlTokensToCSharp(tokens: IntermediateNodeCollection, output: MutableList<IntermediateNode>, source: SourceSpan?, wrapInCSharpExpression: Boolean) {
            if (wrapInCSharpExpression) {
                val expr = CSharpExpressionIntermediateNode().also { it.source = source }
                for (token in tokens) {
                    if (token is HtmlIntermediateToken) expr.children.add(toCSharpToken(token)) else expr.children.add(token)
                }

                output.add(expr)
            } else {
                for (token in tokens) {
                    if (token is HtmlIntermediateToken) output.add(toCSharpToken(token)) else output.add(token)
                }
            }
        }

        /** `FlattenToDirectCSharpTokens`: a node as direct [CSharpIntermediateToken] children of [target], HTML tokens converted, wrappers unwrapped. */
        private fun flattenToDirectCSharpTokens(source: IntermediateNode, target: IntermediateNode) {
            if (source is CSharpIntermediateToken) {
                target.children.add(source)
            } else if (source is HtmlIntermediateToken) {
                target.children.add(toCSharpToken(source))
            } else if (source is IntermediateToken) {
                target.children.add(CSharpIntermediateToken(source.content, source.source))
            } else {
                // The container nodes (HtmlContentIntermediateNode, CSharpExpressionIntermediateNode...) unwrapped.
                for (child in source.children) flattenToDirectCSharpTokens(child, target)
            }
        }

        /**
         * `UnwrapAllElements`: the final pass over the unresolved elements tag helper matching left (all of them without descriptors), each
         * converted to a plain element by the resolver, the tree walked recursively for the nested ones.
         */
        private fun unwrapAllElements(node: IntermediateNode, resolver: TagHelperResolver, documentNode: DocumentIntermediateNode? = null) {
            var documentNode = documentNode
            if (node is DocumentIntermediateNode) documentNode = node

            for (i in node.children.size - 1 downTo 0) {
                val child = node.children[i]
                unwrapAllElements(child, resolver, documentNode)

                if (child is UnresolvedElementIntermediateNode) {
                    val countBefore = node.children.size
                    resolver.convertToPlainElement(node, i, child)
                    val resultCount = node.children.size - countBefore + 1

                    if (resultCount > 0) resolver.addUnmatchedElementDiagnostic(node.children[i], child, documentNode)
                }
            }
        }

        private fun inferAttributeStructure(htmlAttr: HtmlAttributeIntermediateNode): AttributeStructure {
            val prefix = htmlAttr.prefix
            if (prefix != null) {
                if (prefix.contains('\'')) return AttributeStructure.SingleQuotes
                if (prefix.contains('"')) return AttributeStructure.DoubleQuotes
                // An = without quotes (type=, checked=): not minimized, as DoubleQuotes.
                if (prefix.contains('=')) return AttributeStructure.DoubleQuotes
            }

            if (htmlAttr.children.size == 0) return AttributeStructure.Minimized

            return AttributeStructure.DoubleQuotes
        }

        /** `ComputeValueSource`: the span of the attribute value, the spans of the value children merged. */
        private fun computeValueSource(htmlAttr: HtmlAttributeIntermediateNode): SourceSpan? {
            var result: SourceSpan? = null
            for (child in htmlAttr.children) {
                // HtmlAttributeValueIntermediateNode and CSharpExpressionAttributeValueIntermediateNode: the inner token sources, not the wrapper.
                if (child is HtmlAttributeValueIntermediateNode || child is CSharpExpressionAttributeValueIntermediateNode) {
                    for (token in child.children) {
                        val tokenSource = token.source
                        if (tokenSource != null) result = if (result == null) tokenSource else mergeSpans(result, tokenSource)
                    }
                } else {
                    val childSource = child.source
                    if (childSource != null) result = if (result == null) childSource else mergeSpans(result, childSource)
                }
            }
            return result
        }

        private fun mergeSpans(a: SourceSpan, b: SourceSpan): SourceSpan {
            val start = minOf(a.absoluteIndex, b.absoluteIndex)
            val end = maxOf(a.absoluteIndex + a.length, b.absoluteIndex + b.length)
            val first = if (a.absoluteIndex <= b.absoluteIndex) a else b
            val last = if (a.absoluteIndex + a.length >= b.absoluteIndex + b.length) a else b
            val lineCount = (last.lineIndex + last.lineCount) - first.lineIndex
            return first.copy(absoluteIndex = start, length = end - start, lineCount = lineCount, endCharacterIndex = last.endCharacterIndex)
        }

        /** `MergeSourceSpans`: two ordered spans as one covering both; [first] starts at or before [last]. */
        internal fun mergeSourceSpans(first: SourceSpan, last: SourceSpan): SourceSpan {
            assert(first.absoluteIndex <= last.absoluteIndex) { "first span must start at or before the last span" } // Debug.Assert
            return SourceSpan(
                first.filePath, first.absoluteIndex, first.lineIndex, first.characterIndex, last.absoluteIndex + last.length - first.absoluteIndex,
                last.lineIndex + last.lineCount - first.lineIndex, last.endCharacterIndex,
            )
        }

        /**
         * `EmitExplicitExpressionTokens`: an explicit expression `@(expr)` at the source position split into [CSharpIntermediateToken] children:
         * `@`, `(`, the content, `)`; anything else one token.
         */
        private fun emitExplicitExpressionTokens(target: IntermediateNode, exprStart: Int, exprLength: Int, sourceDocument: RazorSourceDocument) {
            val exprText = sourceDocument.text.subSequence(exprStart, exprStart + exprLength).toString()
            val filePath = sourceDocument.filePath

            if (exprText.length >= 3 && exprText[0] == '@' && exprText[1] == '(') {
                // @
                val atLoc = sourceDocument.linePosition(exprStart)
                target.children.add(IntermediateNodeFactory.csharpToken("@", SourceSpan(filePath, exprStart, atLoc.line, atLoc.character, 1, 0, atLoc.character + 1)))

                // (, the content, )
                emitParenthesizedExpressionTokens(target, exprStart + 1, exprLength - 1, sourceDocument)
            } else {
                // Not @(): one token.
                val loc = sourceDocument.linePosition(exprStart)
                target.children.add(IntermediateNodeFactory.csharpToken(exprText, SourceSpan(filePath, exprStart, loc.line, loc.character, exprLength, 0, loc.character + exprLength)))
            }
        }

        /** `EmitParenthesizedExpressionTokens`: `(expr)` as three tokens, `(`, the content, `)`; [parenStart] at the `(`, [parenLength] with both parens. */
        private fun emitParenthesizedExpressionTokens(target: IntermediateNode, parenStart: Int, parenLength: Int, sourceDocument: RazorSourceDocument) {
            val filePath = sourceDocument.filePath

            // (
            val openLoc = sourceDocument.linePosition(parenStart)
            target.children.add(IntermediateNodeFactory.csharpToken("(", SourceSpan(filePath, parenStart, openLoc.line, openLoc.character, 1, 0, openLoc.character + 1)))

            // the content
            val innerStart = parenStart + 1
            val innerLen = parenLength - 2 // without ( and )
            if (innerLen > 0) {
                val innerText = sourceDocument.text.subSequence(innerStart, innerStart + innerLen).toString()
                val innerLoc = sourceDocument.linePosition(innerStart)
                target.children.add(
                    IntermediateNodeFactory.csharpToken(innerText, SourceSpan(filePath, innerStart, innerLoc.line, innerLoc.character, innerLen, 0, innerLoc.character + innerLen)),
                )
            }

            // )
            val closePos = parenStart + parenLength - 1
            val closeLoc = sourceDocument.linePosition(closePos)
            target.children.add(IntermediateNodeFactory.csharpToken(")", SourceSpan(filePath, closePos, closeLoc.line, closeLoc.character, 1, 0, closeLoc.character + 1)))
        }

        /**
         * `EmitEscapedAtCSharpExpression`: the whole `@@(expr)` escape as a [CSharpExpressionIntermediateNode] of structured tokens; the
         * expression source points at the `@(expr)` part (after the literal `@` of `@@`).
         */
        private fun emitEscapedAtCSharpExpression(target: IntermediateNode, expressionSource: SourceSpan, sourceDocument: RazorSourceDocument) {
            val expr = CSharpExpressionIntermediateNode()
            emitExplicitExpressionTokens(expr, expressionSource.absoluteIndex, expressionSource.length, sourceDocument)

            val exprLoc = sourceDocument.linePosition(expressionSource.absoluteIndex)
            expr.source = expressionSource.copy(lineIndex = exprLoc.line, characterIndex = exprLoc.character, lineCount = 0, endCharacterIndex = exprLoc.character + expressionSource.length)
            target.children.add(expr)
        }

        /** `CollectAttributeValueContent`: the prefix and the token content of an [HtmlAttributeValueIntermediateNode], with the first child's source. */
        private fun collectAttributeValueContent(attrValue: HtmlAttributeValueIntermediateNode): Pair<String, SourceSpan?> {
            var content = attrValue.prefix ?: ""
            for (token in attrValue.children) if (token is IntermediateToken) content += token.content

            val source = if (attrValue.children.size > 0) attrValue.children[0].source else attrValue.source
            return content to source
        }

        /** `ToCSharpToken`: an [HtmlIntermediateToken] as a [CSharpIntermediateToken], lazy content kept lazy. */
        private fun toCSharpToken(htmlToken: HtmlIntermediateToken): CSharpIntermediateToken =
            if (htmlToken.isLazy) IntermediateNodeFactory.csharpToken(htmlToken, { t -> t.content }, htmlToken.source) else CSharpIntermediateToken(htmlToken.content, htmlToken.source)

        /** `CreateEmptyHtmlContent`: an [HtmlContentIntermediateNode] of one empty token. */
        private fun createEmptyHtmlContent(source: SourceSpan?): HtmlContentIntermediateNode =
            HtmlContentIntermediateNode().also {
                it.source = source
                it.children.add(IntermediateNodeFactory.htmlToken("", source))
            }

        /** `CreateEmptyCSharpToken`. */
        private fun createEmptyCSharpToken(source: SourceSpan?): CSharpIntermediateToken = IntermediateNodeFactory.csharpToken("", source)

        /** `AreAllChildrenOfType<T>`. */
        private inline fun <reified T : IntermediateNode> areAllChildrenOfType(children: IntermediateNodeCollection): Boolean {
            for (child in children) if (child !is T) return false
            return true
        }

        /** `ExtendSpanBackward`: a span extended back by [prefixLength] characters (an attribute prefix merged into the first token). */
        private fun extendSpanBackward(source: SourceSpan?, prefixLength: Int): SourceSpan? {
            val s = source ?: return null
            return s.copy(absoluteIndex = s.absoluteIndex - prefixLength, characterIndex = s.characterIndex - prefixLength, length = s.length + prefixLength)
        }

        /** `CollectUnresolvedLiteralContent`: the literal content and the merged span of the unresolved value children (string and non-string paths). */
        private fun collectUnresolvedLiteralContent(htmlAttr: HtmlAttributeIntermediateNode, valueSourceSpan: SourceSpan?): Pair<String, SourceSpan?> {
            val sb = StringBuilder()
            var firstSpan: SourceSpan? = null
            var lastSpan: SourceSpan? = null

            for (child in htmlAttr.children) {
                val unresolvedValue = child as UnresolvedAttributeValueIntermediateNode

                if (unresolvedValue.prefix.isNotEmpty()) sb.append(unresolvedValue.prefix)

                for (valueChild in unresolvedValue.children) {
                    if (valueChild is HtmlIntermediateToken) {
                        sb.append(valueChild.content)
                        val s = valueChild.source
                        if (s != null) {
                            if (firstSpan == null) firstSpan = s
                            lastSpan = s
                        }
                    }
                }
            }

            var mergedSpan = valueSourceSpan
            if (mergedSpan == null && firstSpan != null && lastSpan != null) mergedSpan = mergeSpans(firstSpan, lastSpan)

            return sb.toString() to mergedSpan
        }

        /**
         * `GetParentTagInfo`: the parent tag name and whether it is a tag helper, for the binder — the explicit [tagHelperParent] (body
         * resolution), else a tag helper [parent], else the unresolved HTML [unresolvedParent]. The IR keeps tag helper names without the
         * prefix: it is put back, as the binder expects it.
         */
        private fun getParentTagInfo(
            parent: IntermediateNode, tagHelperParent: TagHelperIntermediateNode?, prefix: String?, unresolvedParent: UnresolvedElementIntermediateNode? = null,
        ): Pair<String?, Boolean> {
            if (tagHelperParent != null) return ((prefix ?: "") + tagHelperParent.tagName) to true

            if (parent is TagHelperIntermediateNode) return ((prefix ?: "") + parent.tagName) to true

            if (unresolvedParent != null) {
                val parentTagName = if (unresolvedParent.isEscaped) "!" + unresolvedParent.tagName else unresolvedParent.tagName
                return parentTagName to false
            }

            return null to false
        }

        /**
         * `TryAddMalformedEndTagDiagnostic`: an end-tag-only element that matches a tag helper gets RZ1034 (malformed tag helper) on the element
         * node, which the conversion that follows carries over to its replacement.
         */
        private fun tryAddMalformedEndTagDiagnostic(
            elementNode: UnresolvedElementIntermediateNode, tagName: String, binder: TagHelperBinder, attributes: List<Pair<String, String>>, parent: IntermediateNode,
            tagHelperParent: TagHelperIntermediateNode?, prefix: String?,
        ) {
            val (endParentTagName, endParentIsTagHelper) = getParentTagInfo(parent, tagHelperParent, prefix)
            binder.getBinding(tagName, attributes, endParentTagName, endParentIsTagHelper) ?: return

            // The position of the tag name in the end tag (after "</").
            var diagSource = elementNode.source
            val ets = elementNode.endTagSpan
            if (ets != null) {
                diagSource = ets.copy(
                    absoluteIndex = ets.absoluteIndex + 2, characterIndex = ets.characterIndex + 2, length = tagName.length, lineCount = 0,
                    endCharacterIndex = ets.characterIndex + 2 + tagName.length,
                )
            }

            if (diagSource != null) elementNode.addDiagnostic(RazorDiagnosticFactory.parsing_TagHelperFoundMalformedTagHelper(diagSource, tagName))
        }

        /**
         * `IsDirectiveAttributeOnly`: every tag helper of the binding a directive attribute helper (@bind, @onclick), no component or child
         * content: the rewriter makes no structural diagnostics for those.
         */
        private fun isDirectiveAttributeOnly(binding: TagHelperBinding): Boolean {
            for (th in binding.tagHelpers) if (!th.isAnyComponentDocumentTagHelper() || th.isComponentOrChildContentTagHelper()) return false
            return true
        }

        /** `ValidateConsistentTagStructure`: RZ2011 for conflicting [TagStructure] values across the bound rules. */
        private fun validateConsistentTagStructure(tagHelperNode: TagHelperIntermediateNode, binding: TagHelperBinding, elementNode: UnresolvedElementIntermediateNode, tagName: String) {
            var baseStructure: TagStructure? = null
            var baseDisplayName: String? = null
            for (boundRulesInfo in binding.allBoundRules) {
                for (rule in boundRulesInfo.rules) {
                    if (rule.tagStructure != TagStructure.Unspecified) {
                        if (baseStructure != null && baseStructure != rule.tagStructure) {
                            tagHelperNode.addDiagnostic(
                                RazorDiagnosticFactory.tagHelper_InconsistentTagStructure(
                                    elementNode.source ?: SourceSpan.UNDEFINED, baseDisplayName, boundRulesInfo.descriptor.displayName, tagName,
                                ),
                            )
                        }
                        if (baseStructure == null) baseStructure = rule.tagStructure
                        if (baseDisplayName == null) baseDisplayName = boundRulesInfo.descriptor.displayName
                    }
                }
            }
        }

        /** `HasOnlyWhitespaceContent`: all children empty or whitespace-only tokens (the empty bound values of RZ2008). */
        private fun hasOnlyWhitespaceContent(node: IntermediateNode): Boolean {
            for (child in node.children) {
                if (child is IntermediateToken) {
                    if (child.content.isNotBlank()) return false
                } else if (child is HtmlContentIntermediateNode) {
                    for (hc in child.children) if (hc is IntermediateToken && hc.content.isNotBlank()) return false
                } else {
                    return false
                }
            }

            return true
        }
    }
}
