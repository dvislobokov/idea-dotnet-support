package io.github.dotnetsupport.razor.lang.intermediate

import io.github.dotnetsupport.razor.lang.syntax.*
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/** The tag helper nodes of the IR (`TagHelper*IntermediateNode.cs`): what the resolution phase makes of an element bound to tag helpers or components. */

/** `TagHelperIntermediateNode`: an element bound to [tagHelpers]; [startTagSpan] is set for a component only. */
class TagHelperIntermediateNode(
    val tagMode: TagMode,
    val tagName: String,
    val startTagSpan: SourceSpan? = null,
    tagHelpers: TagHelperCollection? = null,
) : IntermediateNode() {
    /** `TagHelpers { init => field = value ?? []; }`. */
    val tagHelpers: TagHelperCollection = tagHelpers ?: TagHelperCollection.EMPTY

    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitTagHelper(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(tagName)
        formatter.writeProperty("TagHelpers", tagHelpers.joinToString(", ") { it.displayName })
        formatter.writeProperty("TagMode", tagMode.toString())
        formatter.writeProperty("TagName", tagName)
    }
}

/** `TagHelperBodyIntermediateNode`. */
class TagHelperBodyIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitTagHelperBody(this)
}

/** `TagHelperPropertyIntermediateNode`: an attribute bound to a property of a tag helper ([match] is the bound attribute it matched). */
class TagHelperPropertyIntermediateNode internal constructor(
    private val match: TagHelperAttributeMatch,
    val attributeName: String,
    val attributeStructure: AttributeStructure,
    val originalAttributeSpan: SourceSpan? = null,
) : IntermediateNode() {
    val isIndexerNameMatch: Boolean get() = match.isIndexerMatch
    val boundAttribute: BoundAttributeDescriptor get() = match.attribute
    val tagHelper: TagHelperDescriptor get() = boundAttribute.parent

    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitTagHelperProperty(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(attributeName)
        formatter.writeProperty("AttributeName", attributeName)
        formatter.writeProperty("AttributeStructure", attributeStructure.toString())
        formatter.writeProperty("BoundAttribute", boundAttribute.displayName)
        formatter.writeProperty("IsIndexerNameMatch", isIndexerNameMatch.toCSharpString())
        formatter.writeProperty("TagHelper", tagHelper.displayName)
    }
}

/** `TagHelperHtmlAttributeIntermediateNode`: an attribute of a tag helper element that no tag helper binds. */
class TagHelperHtmlAttributeIntermediateNode(
    val attributeName: String,
    val attributeStructure: AttributeStructure,
    internal val attributeNameSpan: SourceSpan? = null,
    internal val isDirectiveAttributeCandidate: Boolean = false,
) : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitTagHelperHtmlAttribute(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(attributeName)
        formatter.writeProperty("AttributeName", attributeName)
        formatter.writeProperty("AttributeStructure", attributeStructure.toString())
    }
}

/** `TagHelperDirectiveAttributeIntermediateNode`: a directive attribute (`@bind`, `@onclick`...) bound to a component tag helper. */
class TagHelperDirectiveAttributeIntermediateNode internal constructor(
    private val match: TagHelperAttributeMatch,
    val attributeName: String,
    val originalAttributeName: String,
    val attributeStructure: AttributeStructure,
    val originalAttributeSpan: SourceSpan? = null,
) : IntermediateNode() {
    init {
        assert(!match.isParameterMatch) // Debug.Assert
    }

    val isIndexerNameMatch: Boolean get() = match.isIndexerMatch
    val boundAttribute: BoundAttributeDescriptor get() = match.attribute
    val tagHelper: TagHelperDescriptor get() = boundAttribute.parent

    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitTagHelperDirectiveAttribute(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(attributeName)
        formatter.writeProperty("AttributeName", attributeName)
        formatter.writeProperty("OriginalAttributeName", originalAttributeName)
        formatter.writeProperty("AttributeStructure", attributeStructure.toString())
        formatter.writeProperty("BoundAttribute", boundAttribute.displayName)
        formatter.writeProperty("IsIndexerNameMatch", isIndexerNameMatch.toCSharpString())
        formatter.writeProperty("TagHelper", tagHelper.displayName)
    }
}

/** `TagHelperDirectiveAttributeParameterIntermediateNode`: the parameter of a directive attribute (`@bind-Value:event`). */
class TagHelperDirectiveAttributeParameterIntermediateNode internal constructor(
    private val match: TagHelperAttributeMatch,
    val attributeName: String,
    val attributeNameWithoutParameter: String,
    val originalAttributeName: String,
    val attributeStructure: AttributeStructure,
    val originalAttributeSpan: SourceSpan? = null,
) : IntermediateNode() {
    val isIndexerNameMatch: Boolean get() = match.isIndexerMatch
    val boundAttributeParameter: BoundAttributeParameterDescriptor get() = match.parameter!!
    val boundAttribute: BoundAttributeDescriptor get() = boundAttributeParameter.parent
    val tagHelper: TagHelperDescriptor get() = boundAttribute.parent

    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitTagHelperDirectiveAttributeParameter(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(attributeName)
        formatter.writeProperty("AttributeName", attributeName)
        formatter.writeProperty("OriginalAttributeName", originalAttributeName)
        formatter.writeProperty("AttributeStructure", attributeStructure.toString())
        formatter.writeProperty("BoundAttribute", boundAttribute.displayName)
        formatter.writeProperty("BoundAttributeParameter", boundAttributeParameter.displayName)
        formatter.writeProperty("TagHelper", tagHelper.displayName)
    }
}
