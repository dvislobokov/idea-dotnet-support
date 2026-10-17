package io.github.dotnetsupport.razor.lang.intermediate

import io.github.dotnetsupport.razor.lang.syntax.AttributeStructure
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/**
 * The unresolved nodes of the IR (`Unresolved*IntermediateNode.cs`): markup the lowering cannot classify without the tag helpers. The
 * resolution phase turns them into tag helper / component nodes or back into plain markup; everything it needs from the syntax tree is
 * stored on them. They are not visited by name: `Accept` is `VisitDefault`.
 */

/** `UnresolvedElementIntermediateNode`: an element that may or may not be a tag helper. */
class UnresolvedElementIntermediateNode : IntermediateNode() {
    var tagName: String = ""
    var isComponent: Boolean = false

    /** Escaped with `!` (`<!input>`). */
    var isEscaped: Boolean = false

    /** The start tag ends with `/>`. */
    var isSelfClosing: Boolean = false

    var hasEndTag: Boolean = false

    /** The end tag name (its case may differ from [tagName], for the case-mismatch diagnostics). */
    var endTagName: String? = null

    var endTagSpan: SourceSpan? = null

    /** A void element (`input`, `br`). */
    var isVoidElement: Boolean = false

    /** The span of the start tag's name (the component's `StartTagSpan`). */
    var startTagNameSpan: SourceSpan? = null

    /** The span of the whole start tag, for diagnostics. */
    var startTagSpan: SourceSpan? = null

    /** The attribute name / value pairs for tag helper binding; null is the compiler's default (unset) `ImmutableArray`. */
    var attributeData: List<Pair<String, String>>? = null

    var hasMissingCloseAngle: Boolean = false

    /** C# expression or code children in the start-tag region (`<div @expr>`). */
    var hasDynamicExpressionChild: Boolean = false

    var hasMissingEndCloseAngle: Boolean = false

    /** The index in [children] where the body starts (one past the last start-tag child); -1 if not computed. */
    var startTagEndIndex: Int = -1

    /** The index in [children] where the end-tag children start (one past the last body child); -1 if not computed. */
    var bodyEndIndex: Int = -1

    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitDefault(this)

    override fun cloneNode(): IntermediateNode = UnresolvedElementIntermediateNode().also {
        it.tagName = tagName
        it.isComponent = isComponent
        it.isEscaped = isEscaped
        it.isSelfClosing = isSelfClosing
        it.hasEndTag = hasEndTag
        it.endTagName = endTagName
        it.endTagSpan = endTagSpan
        it.isVoidElement = isVoidElement
        it.startTagNameSpan = startTagNameSpan
        it.startTagSpan = startTagSpan
        it.attributeData = attributeData
        it.hasMissingCloseAngle = hasMissingCloseAngle
        it.hasDynamicExpressionChild = hasDynamicExpressionChild
        it.hasMissingEndCloseAngle = hasMissingEndCloseAngle
        it.startTagEndIndex = startTagEndIndex
        it.bodyEndIndex = bodyEndIndex
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(tagName)
        formatter.writeProperty("TagName", tagName)
        formatter.writeProperty("IsComponent", isComponent.toCSharpString())
    }
}

/**
 * `UnresolvedAttributeIntermediateNode`: an attribute whose final form (plain HTML or a bound tag helper attribute) is not known yet;
 * both fallback forms are lowered in advance.
 */
class UnresolvedAttributeIntermediateNode : IntermediateNode() {
    /** The attribute name (`Value`, `@bind-Value`, `@onclick`). */
    var attributeName: String = ""

    /** A minimized attribute (no value). */
    var isMinimized: Boolean = false

    /** The parser took the leading `@` as a transition, not an escaped literal; the resolution decides whether it binds a directive attribute. */
    var isDirectiveAttributeCandidate: Boolean = false

    /** The raw value (for all-literal values only). */
    var valueContent: String? = null

    /** The span of the value (between the quotes). */
    var valueSourceSpan: SourceSpan? = null

    /** The quote style. */
    var attributeStructure: AttributeStructure = AttributeStructure.entries[0]

    /** The span of the attribute name only (a tag helper property's `OriginalAttributeSpan`). */
    var attributeNameSpan: SourceSpan? = null

    /**
     * The form for an element that is a tag helper while this attribute is unbound: a [TagHelperHtmlAttributeIntermediateNode] around an
     * [HtmlAttributeIntermediateNode] with the literal value tokens merged (`VisitAttributeValue`).
     */
    var asTagHelperAttribute: IntermediateNode? = null

    /** The form for an element that is not a tag helper: the plain HTML attribute with its tokens as parsed (`LowerAttributeAsHtml`). */
    var asMarkupAttribute: IntermediateNode? = null

    /** The [HtmlAttributeIntermediateNode] child holding the unresolved value children (kept to avoid scans). */
    var htmlAttributeNode: HtmlAttributeIntermediateNode? = null

    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitDefault(this)

    override fun cloneNode(): IntermediateNode = UnresolvedAttributeIntermediateNode().also {
        it.attributeName = attributeName
        it.isMinimized = isMinimized
        it.isDirectiveAttributeCandidate = isDirectiveAttributeCandidate
        it.valueContent = valueContent
        it.valueSourceSpan = valueSourceSpan
        it.attributeStructure = attributeStructure
        it.attributeNameSpan = attributeNameSpan
        it.asTagHelperAttribute = asTagHelperAttribute?.clone()
        it.asMarkupAttribute = asMarkupAttribute?.clone()
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    /** [htmlAttributeNode] aliases one of the children: the clone points at its cloned child, not at an independent copy. */
    override fun clone(): IntermediateNode {
        val clone = super.clone() as UnresolvedAttributeIntermediateNode
        val htmlAttributeNode = htmlAttributeNode
        if (htmlAttributeNode != null) {
            val index = children.indexOf(htmlAttributeNode)
            assert(index >= 0) { "HtmlAttributeNode is expected to be one of Children." } // Debug.Assert
            clone.htmlAttributeNode = clone.children[index] as HtmlAttributeIntermediateNode
        }
        return clone
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(attributeName)
        formatter.writeProperty("AttributeName", attributeName)
        formatter.writeProperty("IsMinimized", isMinimized.toCSharpString())
    }
}

/**
 * `UnresolvedAttributeValueIntermediateNode`: a literal attribute value (`123` of `IntProperty="123"`) inside an unresolved element; the
 * resolution makes it a C# token (a bound non-string property), HTML content (a bound string property) or an HTML attribute value.
 */
class UnresolvedAttributeValueIntermediateNode : IntermediateNode() {
    /** The whitespace / text before the value. */
    var prefix: String = ""

    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitDefault(this)

    override fun cloneNode(): IntermediateNode = UnresolvedAttributeValueIntermediateNode().also {
        it.prefix = prefix
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeChildren(children)
        formatter.writeProperty("Prefix", prefix)
    }
}

/**
 * `UnresolvedExpressionAttributeValueIntermediateNode`: a dynamic attribute value (`@someExpr` of `Value="@someExpr"`) inside an
 * unresolved element; the resolution makes it C# tokens (a bound non-string property) or a C# expression / code attribute value.
 */
class UnresolvedExpressionAttributeValueIntermediateNode : IntermediateNode() {
    /** The whitespace / text before the expression. */
    var prefix: String = ""

    /** A top-level (implicit or explicit) expression: the plain HTML form is a C# expression attribute value, else a C# code one. */
    var containsExpression: Boolean = false

    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitDefault(this)

    override fun cloneNode(): IntermediateNode = UnresolvedExpressionAttributeValueIntermediateNode().also {
        it.prefix = prefix
        it.containsExpression = containsExpression
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeChildren(children)
        formatter.writeProperty("Prefix", prefix)
        formatter.writeProperty("ContainsExpression", containsExpression.toCSharpString())
    }
}
