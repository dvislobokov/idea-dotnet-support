package io.github.dotnetsupport.razor.lang.codegen.extensions

import io.github.dotnetsupport.razor.lang.syntax.*
import io.github.dotnetsupport.razor.lang.codegen.*
import io.github.dotnetsupport.razor.lang.intermediate.*

/**
 * The runtime nodes of MVC tag helpers (`DefaultTagHelper*IntermediateNode.cs`, `IDefaultTagHelperTargetExtension.cs`): what
 * `DefaultTagHelperOptimizationPass` makes of a tag helper node, written by [DefaultTagHelperTargetExtension].
 */

interface IDefaultTagHelperTargetExtension : ICodeTargetExtension {
    fun writeTagHelperBody(context: CodeRenderingContext, node: DefaultTagHelperBodyIntermediateNode)
    fun writeTagHelperCreate(context: CodeRenderingContext, node: DefaultTagHelperCreateIntermediateNode)
    fun writeTagHelperExecute(context: CodeRenderingContext, node: DefaultTagHelperExecuteIntermediateNode)
    fun writeTagHelperHtmlAttribute(context: CodeRenderingContext, node: DefaultTagHelperHtmlAttributeIntermediateNode)
    fun writeTagHelperProperty(context: CodeRenderingContext, node: DefaultTagHelperPropertyIntermediateNode)
    fun writeTagHelperRuntime(context: CodeRenderingContext, node: DefaultTagHelperRuntimeIntermediateNode)
}

/** `DefaultTagHelperBodyIntermediateNode`: the body of a tag helper element, run in its own scope. */
class DefaultTagHelperBodyIntermediateNode() : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var tagMode: TagMode = TagMode.entries[0]
    var tagName: String? = null

    constructor(bodyNode: TagHelperBodyIntermediateNode) : this() {
        source = bodyNode.source
        for (i in 0 until bodyNode.children.size) children.add(bodyNode.children[i])
        addDiagnosticsFromNode(bodyNode)
    }

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IDefaultTagHelperTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IDefaultTagHelperTargetExtension::class.java)
            return
        }
        extension.writeTagHelperBody(context, this)
    }
}

/** `DefaultTagHelperCreateIntermediateNode`: `__X = CreateTagHelper<global::X>();` and its registration in the execution context. */
class DefaultTagHelperCreateIntermediateNode : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY
    var fieldName: String? = null
    var tagHelper: TagHelperDescriptor? = null
    var typeName: String? = null

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IDefaultTagHelperTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IDefaultTagHelperTargetExtension::class.java)
            return
        }
        extension.writeTagHelperCreate(context, this)
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(typeName)
        formatter.writeProperty("FieldName", fieldName)
        formatter.writeProperty("TagHelper", tagHelper?.displayName)
        formatter.writeProperty("TypeName", typeName)
    }
}

/** `DefaultTagHelperExecuteIntermediateNode`: `await __tagHelperRunner.RunAsync(…)` and the output of the element. */
class DefaultTagHelperExecuteIntermediateNode : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IDefaultTagHelperTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IDefaultTagHelperTargetExtension::class.java)
            return
        }
        extension.writeTagHelperExecute(context, this)
    }
}

/** `DefaultTagHelperHtmlAttributeIntermediateNode`: an unbound attribute of a tag helper element with a dynamic value. */
class DefaultTagHelperHtmlAttributeIntermediateNode() : ExtensionIntermediateNode() {
    var attributeName: String? = null
    var attributeStructure: AttributeStructure = AttributeStructure.entries[0]
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    constructor(htmlAttributeNode: TagHelperHtmlAttributeIntermediateNode) : this() {
        attributeName = htmlAttributeNode.attributeName
        attributeStructure = htmlAttributeNode.attributeStructure
        source = htmlAttributeNode.source
        for (i in 0 until htmlAttributeNode.children.size) children.add(htmlAttributeNode.children[i])
        addDiagnosticsFromNode(htmlAttributeNode)
    }

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IDefaultTagHelperTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IDefaultTagHelperTargetExtension::class.java)
            return
        }
        extension.writeTagHelperHtmlAttribute(context, this)
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(attributeName)
        formatter.writeProperty("AttributeName", attributeName)
        formatter.writeProperty("AttributeStructure", attributeStructure.toString())
    }
}

/** `DefaultTagHelperPropertyIntermediateNode`: an attribute bound to a tag helper property, `__X.Prop = value;`. */
class DefaultTagHelperPropertyIntermediateNode() : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var attributeName: String? = null
    var attributeStructure: AttributeStructure = AttributeStructure.entries[0]
    var boundAttribute: BoundAttributeDescriptor? = null
    var fieldName: String? = null
    var isIndexerNameMatch: Boolean = false
    var propertyName: String? = null

    /** `BoundAttribute.Parent`: throws without a bound attribute, as the compiler's (`?.` in the formatter is on the result). */
    val tagHelper: TagHelperDescriptor? get() = boundAttribute!!.parent

    constructor(propertyNode: TagHelperPropertyIntermediateNode) : this() {
        attributeName = propertyNode.attributeName
        attributeStructure = propertyNode.attributeStructure
        boundAttribute = propertyNode.boundAttribute
        isIndexerNameMatch = propertyNode.isIndexerNameMatch
        source = propertyNode.source
        for (i in 0 until propertyNode.children.size) children.add(propertyNode.children[i])
        addDiagnosticsFromNode(propertyNode)
    }

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IDefaultTagHelperTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IDefaultTagHelperTargetExtension::class.java)
            return
        }
        extension.writeTagHelperProperty(context, this)
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(attributeName)
        formatter.writeProperty("AttributeName", attributeName)
        formatter.writeProperty("AttributeStructure", attributeStructure.toString())
        formatter.writeProperty("BoundAttribute", boundAttribute?.displayName)
        formatter.writeProperty("FieldName", fieldName)
        formatter.writeProperty("IsIndexerNameMatch", isIndexerNameMatch.toCSharpString())
        formatter.writeProperty("PropertyName", propertyName)
        formatter.writeProperty("TagHelper", tagHelper?.displayName)
    }
}

/** `DefaultTagHelperRuntimeIntermediateNode`: the fields of the tag helper runtime (`__tagHelperExecutionContext`, the runner, the scope manager). */
class DefaultTagHelperRuntimeIntermediateNode : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IDefaultTagHelperTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IDefaultTagHelperTargetExtension::class.java)
            return
        }
        extension.writeTagHelperRuntime(context, this)
    }
}
