package io.github.dotnetsupport.razor.lang.codegen.extensions

import io.github.dotnetsupport.razor.lang.syntax.*
import io.github.dotnetsupport.razor.lang.codegen.*
import io.github.dotnetsupport.razor.lang.intermediate.*

/**
 * Pre-allocated tag helper attributes (`Preallocated*IntermediateNode.cs`, `IPreallocatedAttributeTargetExtension.cs`,
 * `PreallocatedAttributeTargetExtension.cs`): an attribute with a literal value becomes a `static readonly TagHelperAttribute` field and
 * a reference to it.
 */

interface IPreallocatedAttributeTargetExtension : ICodeTargetExtension {
    fun writeTagHelperHtmlAttribute(context: CodeRenderingContext, node: PreallocatedTagHelperHtmlAttributeIntermediateNode)
    fun writeTagHelperHtmlAttributeValue(context: CodeRenderingContext, node: PreallocatedTagHelperHtmlAttributeValueIntermediateNode)
    fun writeTagHelperProperty(context: CodeRenderingContext, node: PreallocatedTagHelperPropertyIntermediateNode)
    fun writeTagHelperPropertyValue(context: CodeRenderingContext, node: PreallocatedTagHelperPropertyValueIntermediateNode)
}

/** `PreallocatedTagHelperHtmlAttributeIntermediateNode`: `__tagHelperExecutionContext.AddHtmlAttribute(__tagHelperAttribute_0);`. */
class PreallocatedTagHelperHtmlAttributeIntermediateNode : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY
    var variableName: String? = null

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IPreallocatedAttributeTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IPreallocatedAttributeTargetExtension::class.java)
            return
        }
        extension.writeTagHelperHtmlAttribute(context, this)
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(variableName)
        formatter.writeProperty("VariableName", variableName)
    }
}

/** `PreallocatedTagHelperHtmlAttributeValueIntermediateNode`: the field of an unbound attribute with a literal value. */
class PreallocatedTagHelperHtmlAttributeValueIntermediateNode() : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY
    var variableName: String? = null
    var attributeName: String? = null
    var value: String? = null
    var attributeStructure: AttributeStructure = AttributeStructure.entries[0]

    /** As in the compiler: the children go to the read-only collection (throws when there are any). */
    constructor(htmlAttributeNode: DefaultTagHelperHtmlAttributeIntermediateNode) : this() {
        source = htmlAttributeNode.source
        for (i in 0 until htmlAttributeNode.children.size) children.add(htmlAttributeNode.children[i])
        addDiagnosticsFromNode(htmlAttributeNode)
    }

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IPreallocatedAttributeTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IPreallocatedAttributeTargetExtension::class.java)
            return
        }
        extension.writeTagHelperHtmlAttributeValue(context, this)
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(attributeName)
        formatter.writeProperty("AttributeName", attributeName)
        formatter.writeProperty("AttributeStructure", attributeStructure.toString())
        formatter.writeProperty("Value", value)
        formatter.writeProperty("VariableName", variableName)
    }
}

/** `PreallocatedTagHelperPropertyIntermediateNode`: a bound string property set from a pre-allocated attribute. */
class PreallocatedTagHelperPropertyIntermediateNode() : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY
    var attributeName: String? = null
    var attributeStructure: AttributeStructure = AttributeStructure.entries[0]
    var boundAttribute: BoundAttributeDescriptor? = null
    var fieldName: String? = null
    var isIndexerNameMatch: Boolean = false
    var propertyName: String? = null

    /** `BoundAttribute.Parent`: throws without a bound attribute, as the compiler's. */
    val tagHelper: TagHelperDescriptor? get() = boundAttribute!!.parent
    var variableName: String? = null

    constructor(propertyNode: DefaultTagHelperPropertyIntermediateNode) : this() {
        attributeName = propertyNode.attributeName
        attributeStructure = propertyNode.attributeStructure
        boundAttribute = propertyNode.boundAttribute
        fieldName = propertyNode.fieldName
        isIndexerNameMatch = propertyNode.isIndexerNameMatch
        propertyName = propertyNode.propertyName
        source = propertyNode.source
    }

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IPreallocatedAttributeTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IPreallocatedAttributeTargetExtension::class.java)
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
        formatter.writeProperty("VariableName", variableName)
    }
}

/** `PreallocatedTagHelperPropertyValueIntermediateNode`: the field of a bound string property with a literal value. */
class PreallocatedTagHelperPropertyValueIntermediateNode : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY
    var variableName: String? = null
    var attributeName: String? = null
    var value: String? = null
    var attributeStructure: AttributeStructure = AttributeStructure.entries[0]

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IPreallocatedAttributeTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IPreallocatedAttributeTargetExtension::class.java)
            return
        }
        extension.writeTagHelperPropertyValue(context, this)
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(attributeName)
        formatter.writeProperty("AttributeName", attributeName)
        formatter.writeProperty("AttributeStructure", attributeStructure.toString())
        formatter.writeProperty("Value", value)
        formatter.writeProperty("VariableName", variableName)
    }
}

/** `PreallocatedAttributeTargetExtension`. */
class PreallocatedAttributeTargetExtension : IPreallocatedAttributeTargetExtension {
    var tagHelperAttributeTypeName: String = "Microsoft.AspNetCore.Razor.TagHelpers.TagHelperAttribute"
    var encodedHtmlStringTypeName: String = "Microsoft.AspNetCore.Html.HtmlString"
    var executionContextVariableName: String = "__tagHelperExecutionContext"
    var executionContextAddHtmlAttributeMethodName: String = "AddHtmlAttribute"
    var executionContextAddTagHelperAttributeMethodName: String = "AddTagHelperAttribute"
    var formatInvalidIndexerAssignmentMethodName: String = "InvalidTagHelperIndexerAssignment"

    override fun writeTagHelperHtmlAttributeValue(context: CodeRenderingContext, node: PreallocatedTagHelperHtmlAttributeValueIntermediateNode) {
        context.codeWriter
            .write("private static readonly global::")
            .write(tagHelperAttributeTypeName)
            .write(" ")
            .write(node.variableName!!)
            .write(" = ")
            .writeStartNewObject("global::$tagHelperAttributeTypeName")
            .writeStringLiteral(node.attributeName ?: "")

        if (node.attributeStructure == AttributeStructure.Minimized) {
            context.codeWriter.writeEndMethodInvocation()
        } else {
            context.codeWriter
                .writeParameterSeparator()
                .writeStartNewObject("global::$encodedHtmlStringTypeName")
                .writeStringLiteral(node.value ?: "")
                .writeEndMethodInvocation(false)
                .writeParameterSeparator()
                .write("global::Microsoft.AspNetCore.Razor.TagHelpers.HtmlAttributeValueStyle.${node.attributeStructure}")
                .writeEndMethodInvocation()
        }
    }

    override fun writeTagHelperHtmlAttribute(context: CodeRenderingContext, node: PreallocatedTagHelperHtmlAttributeIntermediateNode) {
        if (context.parent !is TagHelperIntermediateNode) throw IllegalStateException(invalidParentNode(node))

        context.codeWriter
            .writeStartInstanceMethodInvocation(executionContextVariableName, executionContextAddHtmlAttributeMethodName)
            .write(node.variableName!!)
            .writeEndMethodInvocation()
    }

    override fun writeTagHelperPropertyValue(context: CodeRenderingContext, node: PreallocatedTagHelperPropertyValueIntermediateNode) {
        context.codeWriter
            .write("private static readonly global::")
            .write(tagHelperAttributeTypeName)
            .write(" ")
            .write(node.variableName!!)
            .write(" = ")
            .writeStartNewObject("global::$tagHelperAttributeTypeName")
            .writeStringLiteral(node.attributeName ?: "")
            .writeParameterSeparator()
            .writeStringLiteral(node.value ?: "")
            .writeParameterSeparator()
            .write("global::Microsoft.AspNetCore.Razor.TagHelpers.HtmlAttributeValueStyle.${node.attributeStructure}")
            .writeEndMethodInvocation()
    }

    override fun writeTagHelperProperty(context: CodeRenderingContext, node: PreallocatedTagHelperPropertyIntermediateNode) {
        val tagHelperNode = context.parent as? TagHelperIntermediateNode ?: throw IllegalStateException(invalidParentNode(node))

        // Ensure that the property being set has initialized its dictionary bound properties.
        if (node.isIndexerNameMatch && findFirstUseOfIndexer(tagHelperNode, node) === node) {
            // A reasonable exception at runtime if the dictionary property is null.
            context.codeWriter
                .write("if (")
                .write(node.fieldName!!)
                .write(".")
                .write(node.propertyName!!)
                .writeLine(" == null)")
            context.codeWriter.buildScope().use {
                // System is in Host.NamespaceImports of all MVC scenarios: no full name of InvalidOperationException.
                context.codeWriter
                    .write("throw ")
                    .writeStartNewObject("InvalidOperationException")
                    .writeStartMethodInvocation(formatInvalidIndexerAssignmentMethodName)
                    .writeStringLiteral(node.attributeName ?: "")
                    .writeParameterSeparator()
                    .writeStringLiteral(node.tagHelper!!.typeName ?: "")
                    .writeParameterSeparator()
                    .writeStringLiteral(node.propertyName ?: "")
                    .writeEndMethodInvocation(false) // End of method call
                    .writeEndMethodInvocation() // End of new expression / throw statement
            }
        }

        context.codeWriter
            .writeStartAssignment(getPropertyAccessor(node))
            .write("(string)")
            .write("${node.variableName}.Value")
            .writeLine(";")
            .writeStartInstanceMethodInvocation(executionContextVariableName, executionContextAddTagHelperAttributeMethodName)
            .write(node.variableName!!)
            .writeEndMethodInvocation()
    }

    private companion object {
        /** `Resources.FormatIntermediateNodes_InvalidParentNode`. */
        fun invalidParentNode(node: IntermediateNode): String =
            "The '${node.javaClass.name}' node type can only be used as a direct child of a '${TagHelperIntermediateNode::class.java.name}' node."

        fun findFirstUseOfIndexer(tagHelperNode: TagHelperIntermediateNode, propertyNode: PreallocatedTagHelperPropertyIntermediateNode): PreallocatedTagHelperPropertyIntermediateNode {
            assert(tagHelperNode.children.contains(propertyNode)) // Debug.Assert
            assert(propertyNode.isIndexerNameMatch) // Debug.Assert

            for (i in 0 until tagHelperNode.children.size) {
                val otherPropertyNode = tagHelperNode.children[i]
                if (otherPropertyNode is PreallocatedTagHelperPropertyIntermediateNode && otherPropertyNode.tagHelper == propertyNode.tagHelper &&
                    otherPropertyNode.boundAttribute == propertyNode.boundAttribute && otherPropertyNode.isIndexerNameMatch
                ) {
                    return otherPropertyNode
                }
            }

            // Unreachable: 'propertyNode' is among the children.
            throw IllegalStateException()
        }

        fun getPropertyAccessor(node: PreallocatedTagHelperPropertyIntermediateNode): String {
            var propertyAccessor = "${node.fieldName}.${node.propertyName}"

            if (node.isIndexerNameMatch) {
                val dictionaryKey = node.attributeName!!.substring(node.boundAttribute!!.indexerNamePrefix!!.length)
                propertyAccessor += "[\"$dictionaryKey\"]"
            }

            return propertyAccessor
        }
    }
}
