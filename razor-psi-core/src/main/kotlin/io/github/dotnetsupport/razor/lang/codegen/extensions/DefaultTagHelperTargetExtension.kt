package io.github.dotnetsupport.razor.lang.codegen.extensions

import io.github.dotnetsupport.razor.lang.syntax.*
import io.github.dotnetsupport.razor.lang.codegen.*
import io.github.dotnetsupport.razor.lang.codegen.components.*
import io.github.dotnetsupport.razor.lang.intermediate.*
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticFactory
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/** `DefaultTagHelperTargetExtension`: the runtime code of MVC tag helpers — the execution context, the runner, the scope manager. */
class DefaultTagHelperTargetExtension : IDefaultTagHelperTargetExtension {
    var runnerVariableName: String = "__tagHelperRunner"
    var stringValueBufferVariableName: String = "__tagHelperStringValueBuffer"
    var createTagHelperMethodName: String = "CreateTagHelper"
    var executionContextTypeName: String = "global::Microsoft.AspNetCore.Razor.Runtime.TagHelpers.TagHelperExecutionContext"
    var executionContextVariableName: String = "__tagHelperExecutionContext"
    var executionContextAddMethodName: String = "Add"
    var tagHelperRunnerTypeName: String = "global::Microsoft.AspNetCore.Razor.Runtime.TagHelpers.TagHelperRunner"
    var executionContextOutputPropertyName: String = "Output"
    var executionContextSetOutputContentAsyncMethodName: String = "SetOutputContentAsync"
    var executionContextAddHtmlAttributeMethodName: String = "AddHtmlAttribute"
    var executionContextAddTagHelperAttributeMethodName: String = "AddTagHelperAttribute"
    var runnerRunAsyncMethodName: String = "RunAsync"
    var scopeManagerTypeName: String = "global::Microsoft.AspNetCore.Razor.Runtime.TagHelpers.TagHelperScopeManager"
    var scopeManagerVariableName: String = "__tagHelperScopeManager"
    var scopeManagerBeginMethodName: String = "Begin"
    var scopeManagerEndMethodName: String = "End"
    var startTagHelperWritingScopeMethodName: String = "StartTagHelperWritingScope"
    var endTagHelperWritingScopeMethodName: String = "EndTagHelperWritingScope"
    var tagModeTypeName: String = "global::Microsoft.AspNetCore.Razor.TagHelpers.TagMode"
    var htmlAttributeValueStyleTypeName: String = "global::Microsoft.AspNetCore.Razor.TagHelpers.HtmlAttributeValueStyle"
    var tagHelperOutputIsContentModifiedPropertyName: String = "IsContentModified"
    var beginAddHtmlAttributeValuesMethodName: String = "BeginAddHtmlAttributeValues"
    var endAddHtmlAttributeValuesMethodName: String = "EndAddHtmlAttributeValues"
    var beginWriteTagHelperAttributeMethodName: String = "BeginWriteTagHelperAttribute"
    var endWriteTagHelperAttributeMethodName: String = "EndWriteTagHelperAttribute"
    var markAsHtmlEncodedMethodName: String = "Html.Raw"
    var formatInvalidIndexerAssignmentMethodName: String = "InvalidTagHelperIndexerAssignment"
    var writeTagHelperOutputMethod: String = "Write"

    override fun writeTagHelperBody(context: CodeRenderingContext, node: DefaultTagHelperBodyIntermediateNode) {
        if (context.parent !is TagHelperIntermediateNode) throw IllegalStateException(invalidParentNode(node))

        // Start a new tag helper scope in the scope manager and capture it as the current execution context.
        context.codeWriter
            .writeStartAssignment(executionContextVariableName)
            .writeStartInstanceMethodInvocation(scopeManagerVariableName, scopeManagerBeginMethodName)

        // A unique ID per call site of the source HTML tag: a tag on the view twice has two IDs.
        val uniqueId = getDeterministicId(context)

        context.codeWriter.writeStringLiteral(node.tagName ?: "")
            .writeParameterSeparator()
            .write("$tagModeTypeName.${node.tagMode}")
            .writeParameterSeparator()
            .writeStringLiteral(uniqueId)
            .writeParameterSeparator()

        context.codeWriter.buildAsyncLambda().use {
            // The writers are removed and redirected so that tag helper authors can retrieve the content.
            context.renderChildren(node, IntermediateNodeWriter.INSTANCE)
        }

        context.codeWriter.writeEndMethodInvocation()
    }

    override fun writeTagHelperCreate(context: CodeRenderingContext, node: DefaultTagHelperCreateIntermediateNode) {
        if (context.parent !is TagHelperIntermediateNode) throw IllegalStateException(invalidParentNode(node))

        context.codeWriter
            .writeStartAssignment(node.fieldName!!)
            .write(createTagHelperMethodName)
            .writeLine("<global::${node.typeName}>();")

        context.codeWriter.writeInstanceMethodInvocation(executionContextVariableName, executionContextAddMethodName, node.fieldName!!)
    }

    override fun writeTagHelperExecute(context: CodeRenderingContext, node: DefaultTagHelperExecuteIntermediateNode) {
        if (context.parent !is TagHelperIntermediateNode) throw IllegalStateException(invalidParentNode(node))

        // `await __tagHelperRunner.RunAsync(__tagHelperExecutionContext);` always, to tell users the method must be asynchronous.
        context.codeWriter
            .write("await ")
            .writeStartInstanceMethodInvocation(runnerVariableName, runnerRunAsyncMethodName)
            .write(executionContextVariableName)
            .writeEndMethodInvocation()

        val tagHelperOutputAccessor = "$executionContextVariableName.$executionContextOutputPropertyName"

        context.codeWriter.writeLine("if (!$tagHelperOutputAccessor.$tagHelperOutputIsContentModifiedPropertyName)")

        context.codeWriter.buildScope().use {
            context.codeWriter
                .write("await ")
                .writeInstanceMethodInvocation(executionContextVariableName, executionContextSetOutputContentAsyncMethodName)
        }

        context.codeWriter
            .writeStartMethodInvocation(writeTagHelperOutputMethod)
            .write(tagHelperOutputAccessor)
            .writeEndMethodInvocation()
            .writeStartAssignment(executionContextVariableName)
            .writeInstanceMethodInvocation(scopeManagerVariableName, scopeManagerEndMethodName)
    }

    override fun writeTagHelperHtmlAttribute(context: CodeRenderingContext, node: DefaultTagHelperHtmlAttributeIntermediateNode) {
        if (context.parent !is TagHelperIntermediateNode) throw IllegalStateException(invalidParentNode(node))

        val attributeValueStyleParameter = "$htmlAttributeValueStyleTypeName.${node.attributeStructure}"
        val isConditionalAttributeValue = node.children.any { it is CSharpExpressionAttributeValueIntermediateNode || it is CSharpCodeAttributeValueIntermediateNode }

        // All simple text and minimized attributes are pre-allocated.
        if (isConditionalAttributeValue) {
            // A dynamic value (unbound, with C#) goes through the conditional attribute removal. Tag helper attribute rendering is
            // buffered: nothing is written to the current writer.
            val valuePieceCount = node.children.count {
                it is HtmlAttributeValueIntermediateNode || it is CSharpExpressionAttributeValueIntermediateNode ||
                    it is CSharpCodeAttributeValueIntermediateNode || it is ExtensionIntermediateNode
            }

            context.codeWriter
                .writeStartMethodInvocation(beginAddHtmlAttributeValuesMethodName)
                .write(executionContextVariableName)
                .writeParameterSeparator()
                .writeStringLiteral(node.attributeName ?: "")
                .writeParameterSeparator()
                .writeIntegerLiteral(valuePieceCount)
                .writeParameterSeparator()
                .write(attributeValueStyleParameter)
                .writeEndMethodInvocation()

            context.renderChildren(node, TagHelperHtmlAttributeRuntimeNodeWriter.INSTANCE)

            context.codeWriter.writeMethodInvocation(endAddHtmlAttributeValuesMethodName, executionContextVariableName)
        } else {
            // A data-* attribute with C#: no conditional attribute removal, but the value is buffered to get its final value. The writing
            // scope around the children captures everything the page writes, so no other buffer is used.
            context.codeWriter.writeMethodInvocation(beginWriteTagHelperAttributeMethodName)

            context.renderChildren(node, IntermediateNodeWriter.INSTANCE)

            context.codeWriter
                .writeStartAssignment(stringValueBufferVariableName)
                .writeMethodInvocation(endWriteTagHelperAttributeMethodName)
                .writeStartInstanceMethodInvocation(executionContextVariableName, executionContextAddHtmlAttributeMethodName)
                .writeStringLiteral(node.attributeName ?: "")
                .writeParameterSeparator()
                .writeStartMethodInvocation(markAsHtmlEncodedMethodName)
                .write(stringValueBufferVariableName)
                .writeEndMethodInvocation(false)
                .writeParameterSeparator()
                .write(attributeValueStyleParameter)
                .writeEndMethodInvocation()
        }
    }

    override fun writeTagHelperProperty(context: CodeRenderingContext, node: DefaultTagHelperPropertyIntermediateNode) {
        val tagHelperNode = context.parent as? TagHelperIntermediateNode
        // The compiler checks the parent for null here, not the cast.
        if (context.parent == null) throw IllegalStateException(invalidParentNode(node))

        val boundAttribute = node.boundAttribute!!

        // Ensure that the property being set has initialized its dictionary bound properties.
        if (node.isIndexerNameMatch && findFirstUseOfIndexer(tagHelperNode!!, node) === node) {
            // A reasonable exception at runtime if the dictionary property is null.
            context.codeWriter.writeLine("if (${node.fieldName}.${node.propertyName} == null)")
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

        // Not the first use of the attribute value: the value has been computed and assigned to another tag helper, copy it from there
        // (the expression must not be evaluated twice, for its side effects). Else evaluate the expression.
        val firstUseOfAttribute = findFirstUseOfAttribute(tagHelperNode!!, node)
        if (firstUseOfAttribute !== node) {
            writePropertyAccessorStartAssignment(context.codeWriter, node)
            writePropertyAccessor(context.codeWriter, firstUseOfAttribute).writeLine(";")
            return
        }

        // A union-valued attribute can be supplied by a C# expression producing any union member: only literals or mixed content of
        // such attributes need the string-writing scope.
        val requiresStringWritingScope = !boundAttribute.acceptsStringLiteral() || !(node.children.size == 1 && node.children[0] is CSharpExpressionIntermediateNode)

        if (boundAttribute.expectsStringValue(node.attributeName!!) && requiresStringWritingScope) {
            context.codeWriter.writeMethodInvocation(beginWriteTagHelperAttributeMethodName)

            context.renderChildren(node, LiteralRuntimeNodeWriter.INSTANCE)

            context.codeWriter
                .writeStartAssignment(stringValueBufferVariableName)
                .writeMethodInvocation(endWriteTagHelperAttributeMethodName)

            writePropertyAccessorStartAssignment(context.codeWriter, node).writeLine("$stringValueBufferVariableName;")
        } else {
            writePropertyAccessorStartAssignment(context.codeWriter, node)

            if (boundAttribute.isEnum && node.children.size == 1 && node.children[0] is CSharpIntermediateToken) {
                context.codeWriter.write("global::${boundAttribute.typeName}.")
            }

            if (node.children.size == 0 && node.attributeStructure == AttributeStructure.Minimized && boundAttribute.expectsBooleanValue(node.attributeName!!)) {
                // A minimized boolean attribute: true.
                context.codeWriter.write("true")
            } else {
                renderTagHelperAttributeInline(context, node, node.source)
            }

            context.codeWriter.writeLine(";")
        }

        // Inform the context of the attribute value.
        context.codeWriter
            .writeStartInstanceMethodInvocation(executionContextVariableName, executionContextAddTagHelperAttributeMethodName)
            .writeStringLiteral(node.attributeName ?: "")
            .writeParameterSeparator()

        writePropertyAccessor(context.codeWriter, node)
            .writeParameterSeparator()
            .write("global::Microsoft.AspNetCore.Razor.TagHelpers.HtmlAttributeValueStyle.${node.attributeStructure}")
            .writeEndMethodInvocation()
    }

    override fun writeTagHelperRuntime(context: CodeRenderingContext, node: DefaultTagHelperRuntimeIntermediateNode) {
        context.codeWriter.writeLine("#line hidden")
        context.codeWriter.writeField(fieldUninitializedModifiers, privateModifiers, executionContextTypeName, executionContextVariableName)

        context.codeWriter.writeLine("private $tagHelperRunnerTypeName $runnerVariableName = new $tagHelperRunnerTypeName();")

        context.codeWriter.writeField(fieldUnusedModifiers, privateModifiers, "string", stringValueBufferVariableName)

        val backedScopeManageVariableName = "__backed$scopeManagerVariableName"
        context.codeWriter
            .write("private ")
            .writeVariableDeclaration(scopeManagerTypeName, backedScopeManageVariableName, null)

        context.codeWriter.writeLine("private $scopeManagerTypeName $scopeManagerVariableName")

        context.codeWriter.buildScope().use {
            context.codeWriter.writeLine("get")
            context.codeWriter.buildScope().use {
                context.codeWriter.writeLine("if ($backedScopeManageVariableName == null)")

                context.codeWriter.buildScope().use {
                    context.codeWriter
                        .writeStartAssignment(backedScopeManageVariableName)
                        .writeStartNewObject(scopeManagerTypeName)
                        .write(startTagHelperWritingScopeMethodName)
                        .writeParameterSeparator()
                        .write(endTagHelperWritingScopeMethodName)
                        .writeEndMethodInvocation()
                }

                context.codeWriter.writeLine("return $backedScopeManageVariableName;")
            }
        }
    }

    private fun renderTagHelperAttributeInline(context: CodeRenderingContext, property: DefaultTagHelperPropertyIntermediateNode, span: SourceSpan?) {
        for (i in 0 until property.children.size) renderTagHelperAttributeInline(context, property, property.children[i], span)
    }

    internal fun renderTagHelperAttributeInline(context: CodeRenderingContext, property: DefaultTagHelperPropertyIntermediateNode, node: IntermediateNode, span: SourceSpan?) {
        if (node is CSharpExpressionIntermediateNode || node is HtmlContentIntermediateNode) {
            for (i in 0 until node.children.size) renderTagHelperAttributeInline(context, property, node.children[i], span)
        } else if (node is IntermediateToken) {
            context.buildEnhancedLinePragma(node.source).use {
                context.codeWriter.write(node.content)
            }
        } else if (node is CSharpCodeIntermediateNode) {
            context.addDiagnostic(RazorDiagnosticFactory.tagHelper_CodeBlocksNotSupportedInAttributes(span))
        } else if (node is TemplateIntermediateNode) {
            val boundAttribute = property.boundAttribute!!
            val expectedTypeName = if (property.isIndexerNameMatch) boundAttribute.indexerTypeName else boundAttribute.typeName
            context.addDiagnostic(RazorDiagnosticFactory.tagHelper_InlineMarkupBlocksNotSupportedInAttributes(span, expectedTypeName))
        }
    }

    companion object {
        private val fieldUninitializedModifiers: List<String> = listOf("0649")
        private val fieldUnusedModifiers: List<String> = listOf("0169")
        private val privateModifiers: List<String> = listOf("private")

        /** `Resources.FormatIntermediateNodes_InvalidParentNode`. */
        private fun invalidParentNode(node: IntermediateNode): String =
            "The '${node.javaClass.name}' node type can only be used as a direct child of a '${TagHelperIntermediateNode::class.java.name}' node."

        private fun findFirstUseOfIndexer(tagHelperNode: TagHelperIntermediateNode, propertyNode: DefaultTagHelperPropertyIntermediateNode): DefaultTagHelperPropertyIntermediateNode {
            assert(tagHelperNode.children.contains(propertyNode)) // Debug.Assert
            assert(propertyNode.isIndexerNameMatch) // Debug.Assert

            for (i in 0 until tagHelperNode.children.size) {
                val otherPropertyNode = tagHelperNode.children[i]
                if (otherPropertyNode is DefaultTagHelperPropertyIntermediateNode && otherPropertyNode.tagHelper == propertyNode.tagHelper &&
                    otherPropertyNode.boundAttribute == propertyNode.boundAttribute && otherPropertyNode.isIndexerNameMatch
                ) {
                    return otherPropertyNode
                }
            }

            // Unreachable: 'propertyNode' is among the children.
            throw IllegalStateException()
        }

        private fun findFirstUseOfAttribute(tagHelperNode: TagHelperIntermediateNode, propertyNode: DefaultTagHelperPropertyIntermediateNode): DefaultTagHelperPropertyIntermediateNode {
            for (i in 0 until tagHelperNode.children.size) {
                val otherPropertyNode = tagHelperNode.children[i]
                if (otherPropertyNode is DefaultTagHelperPropertyIntermediateNode && otherPropertyNode.attributeName == propertyNode.attributeName) return otherPropertyNode
            }

            // Unreachable: 'propertyNode' is among the children.
            throw IllegalStateException()
        }

        /**
         * `GetDeterministicId`: `SuppressUniqueIds` when set, else the checksum of the file and the absolute position in the generated code,
         * unique per tag helper call site.
         */
        internal fun getDeterministicId(context: CodeRenderingContext): String =
            context.options.suppressUniqueIds ?: (context.sourceDocument.checksum + context.codeWriter.location.absoluteIndex)

        private fun writePropertyAccessor(writer: CodeWriter, node: DefaultTagHelperPropertyIntermediateNode): CodeWriter {
            writer.write("${node.fieldName}.${node.propertyName}")

            if (node.isIndexerNameMatch) {
                val dictionaryKey = node.attributeName!!.substring(node.boundAttribute!!.indexerNamePrefix!!.length)
                writer.write("[\"$dictionaryKey\"]")
            }

            return writer
        }

        private fun writePropertyAccessorStartAssignment(writer: CodeWriter, node: DefaultTagHelperPropertyIntermediateNode): CodeWriter =
            writePropertyAccessor(writer, node).write(" = ")
    }
}
