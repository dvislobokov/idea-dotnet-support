package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.CSharpCodeWritingScope
import io.github.dotnetsupport.razor.lang.codegen.CodeRenderingContext
import io.github.dotnetsupport.razor.lang.codegen.CodeWriter
import io.github.dotnetsupport.razor.lang.codegen.IWriteableValue
import io.github.dotnetsupport.razor.lang.codegen.IntermediateNodeWriter
import io.github.dotnetsupport.razor.lang.codegen.buildLambda
import io.github.dotnetsupport.razor.lang.codegen.buildScope
import io.github.dotnetsupport.razor.lang.codegen.extensions.ITemplateTargetExtension
import io.github.dotnetsupport.razor.lang.codegen.extensions.TemplateIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.identifierRequiresEscaping
import io.github.dotnetsupport.razor.lang.codegen.isNullOrDotNetWhiteSpace
import io.github.dotnetsupport.razor.lang.codegen.writeBooleanLiteral
import io.github.dotnetsupport.razor.lang.codegen.writeEndMethodInvocation
import io.github.dotnetsupport.razor.lang.codegen.writeIdentifierEscapeIfNeeded
import io.github.dotnetsupport.razor.lang.codegen.writeInstanceMethodInvocation
import io.github.dotnetsupport.razor.lang.codegen.writeIntegerLiteral
import io.github.dotnetsupport.razor.lang.codegen.writeParameterSeparator
import io.github.dotnetsupport.razor.lang.codegen.writeStartInstanceMethodInvocation
import io.github.dotnetsupport.razor.lang.codegen.writeStartMethodInvocationParts
import io.github.dotnetsupport.razor.lang.codegen.writeStringLiteral
import io.github.dotnetsupport.razor.lang.codegen.writeUsing
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.CascadingGenericTypeParameter
import io.github.dotnetsupport.razor.lang.intermediate.ComponentAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentChildContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentTypeArgumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentTypeInferenceMethodIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.FormNameIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeFactory
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.MarkupBlockIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MarkupElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ReferenceCaptureIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.RenderModeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.SetKeyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.SplatIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UsingDirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantNodes
import io.github.dotnetsupport.razor.lang.syntax.AttributeStructure
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.TypeParameterMetadata
import io.github.dotnetsupport.razor.lang.syntax.acceptsStringLiteral
import io.github.dotnetsupport.razor.lang.syntax.getGloballyQualifiedTypeName

/**
 * `ComponentNodeWriter` (`ComponentNodeWriter.cs`): the runtime writer of component documents — the render tree builder calls
 * (`__builder.OpenElement(0, "div")`, `AddAttribute`, `AddContent`, `OpenComponent<T>`, `AddComponentParameter`...) with their sequence
 * numbers, the child content and template lambdas through the [ScopeStack], and the type inference methods of generic components. It
 * also writes the templates of a component document ([ITemplateTargetExtension], through [ComponentTemplateTargetExtension]).
 */
class ComponentNodeWriter(private val version: RazorLanguageVersion) : IntermediateNodeWriter(), ITemplateTargetExtension {
    private val scopeStack = ScopeStack()

    val builderVariableName: BuilderVariableName get() = scopeStack.builderVariableName
    val renderModeVariableName: RenderModeVariableName get() = scopeStack.renderModeVariableName
    val formNameVariableName: FormNameVariableName get() = scopeStack.formNameVariableName

    private fun canUseAddComponentParameter(context: CodeRenderingContext): Boolean =
        !context.options.suppressAddComponentParameter && version >= RazorLanguageVersion.VERSION_8_0

    private fun getAddComponentParameterMethodName(context: CodeRenderingContext): String =
        if (canUseAddComponentParameter(context)) ComponentsApi.RenderTreeBuilder.addComponentParameter else ComponentsApi.RenderTreeBuilder.addAttribute

    override fun beginWriterScope(context: CodeRenderingContext, writer: String): Unit = throw NotImplementedError("beginWriterScope")

    override fun endWriterScope(context: CodeRenderingContext): Unit = throw NotImplementedError("endWriterScope")

    override fun writeCSharpCodeAttributeValue(context: CodeRenderingContext, node: CSharpCodeAttributeValueIntermediateNode) {
        // We used to support syntaxes like <elem onsomeevent=@{ /* some C# code */ } /> but this is no longer the case. We provide an
        // error for this case just to be friendly.
        val content = node.children.filterIsInstance<IntermediateToken>().joinToString("") { it.content }
        context.addDiagnostic(ComponentDiagnosticFactory.create_CodeBlockInAttribute(node.source, content))
    }

    /** When RZ10001 (the type of the component cannot be inferred) is reported, the equivalent CS0411 errors are suppressed: no call to the inference method. */
    private fun shouldSuppressTypeInferenceCall(node: ComponentIntermediateNode): Boolean =
        node.diagnostics.any { it.id == ComponentDiagnosticFactory.GenericComponentTypeInferenceUnderspecified.id }

    /**
     * `WriteComponentTypeInferenceMethod`: like the code of [writeComponentAttribute] and [writeComponentChildContent], but simpler since the
     * attributes and child contents look like variables:
     *
     * ```
     * public static void CreateFoo_0<T1, T2>(RenderTreeBuilder __builder, int seq, int __seq0, T1 __arg0, int __seq1, global::System.Collections.Generic.List<T2> __arg1, int __seq2, string __arg2)
     * {
     *     builder.OpenComponent<Foo<T1, T2>>();
     *     builder.AddComponentParameter(__seq0, nameof(Foo<T1, T2>.Attr0), __arg0);
     *     ...
     *     builder.CloseComponent();
     * }
     * ```
     *
     * The captures get a thunk instead of being used verbatim: the RenderTreeBuilder wants an `Action<object>`, and the caller can neither
     * write the type name if it contains generics nor the variable they want to assign to.
     */
    private fun writeComponentTypeInferenceMethod(
        context: CodeRenderingContext, node: ComponentTypeInferenceMethodIntermediateNode, returnComponentType: Boolean, allowNameof: Boolean, mapComponentStartTag: Boolean,
    ) {
        val parameters = getTypeInferenceMethodParameters(node)
        val component = node.component!!

        val writer = context.codeWriter

        writer.write("public static ")
        if (returnComponentType) writer.write(component.typeName ?: "") else writer.write("void")
        writer.write(" ")
        writer.write(node.methodName ?: "")
        writer.write("<")
        writer.write(component.component!!.getTypeParameters().joinToString(", ") { serializeTypeParameter(it) })
        writer.write(">")

        writer.write("(")
        writer.write("global::")
        writer.write(ComponentsApi.RenderTreeBuilder.fullTypeName)
        writer.write(" ")
        writer.write(ComponentsApi.RenderTreeBuilder.builderParameter)
        writer.write(", ")
        writer.write("int seq")

        if (parameters.size > 0) writer.write(", ")

        for (i in parameters.indices) {
            val seqName = parameters[i].seqName
            if (seqName != null) writer.writeParts("int ", seqName, ", ")

            writer.write(parameters[i].typeName ?: "")
            writer.write(" ")
            writer.write(parameters[i].parameterName)

            if (i < parameters.size - 1) writer.write(", ")
        }

        writer.write(")")

        writeConstraints(writer, node)

        writer.writeLine("{")

        // _builder.OpenComponent<TComponent>(42);
        context.codeWriter.write(ComponentsApi.RenderTreeBuilder.builderParameter)
        context.codeWriter.write(".")
        context.codeWriter.write(ComponentsApi.RenderTreeBuilder.openComponent)
        context.codeWriter.write("<")

        if (mapComponentStartTag) {
            val (nonGenericTypeName, genericTypeParameterList) = TypeNameHelper.getNonGenericTypeName(component.typeName ?: "")
            writeComponentTypeName(context, component, nonGenericTypeName)
            if (genericTypeParameterList != null) context.codeWriter.write(genericTypeParameterList)
        } else {
            context.codeWriter.write(component.typeName ?: "")
        }

        context.codeWriter.write(">(")
        context.codeWriter.write("seq")
        context.codeWriter.write(");")
        context.codeWriter.writeLine()

        var renderModeParameterName: ParameterName? = null

        for (parameter in parameters) {
            when (val source = parameter.source) {
                is ComponentAttributeIntermediateNode -> {
                    context.codeWriter.writeStartInstanceMethodInvocation(ComponentsApi.RenderTreeBuilder.builderParameter, getAddComponentParameterMethodName(context))
                    context.codeWriter.write(parameter.seqName!!)
                    context.codeWriter.write(", ")
                    writeComponentAttributeName(context, source, allowNameof)
                    context.codeWriter.write(", ")

                    if (!canUseAddComponentParameter(context)) context.codeWriter.write("(object)")

                    context.codeWriter.write(parameter.parameterName)
                    context.codeWriter.writeEndMethodInvocation()
                }

                is SplatIntermediateNode -> {
                    context.codeWriter.writeStartInstanceMethodInvocation(ComponentsApi.RenderTreeBuilder.builderParameter, ComponentsApi.RenderTreeBuilder.addMultipleAttributes)
                    context.codeWriter.write(parameter.seqName!!)
                    context.codeWriter.write(", ")

                    context.codeWriter.write(parameter.parameterName)
                    context.codeWriter.writeEndMethodInvocation()
                }

                is ComponentChildContentIntermediateNode -> {
                    context.codeWriter.writeStartInstanceMethodInvocation(ComponentsApi.RenderTreeBuilder.builderParameter, getAddComponentParameterMethodName(context))
                    context.codeWriter.write(parameter.seqName!!)
                    context.codeWriter.write(", ")

                    context.codeWriter.writeParts("\"", source.attributeName, "\"")
                    context.codeWriter.write(", ")

                    if (!canUseAddComponentParameter(context)) context.codeWriter.write("(object)")

                    context.codeWriter.write(parameter.parameterName)
                    context.codeWriter.writeEndMethodInvocation()
                }

                is SetKeyIntermediateNode -> {
                    context.codeWriter.writeStartInstanceMethodInvocation(ComponentsApi.RenderTreeBuilder.builderParameter, ComponentsApi.RenderTreeBuilder.setKey)
                    context.codeWriter.write(parameter.parameterName)
                    context.codeWriter.writeEndMethodInvocation()
                }

                is ReferenceCaptureIntermediateNode -> {
                    val methodName = if (source.isComponentCapture) ComponentsApi.RenderTreeBuilder.addComponentReferenceCapture
                    else ComponentsApi.RenderTreeBuilder.addElementReferenceCapture

                    context.codeWriter.writeStartInstanceMethodInvocation(ComponentsApi.RenderTreeBuilder.builderParameter, methodName)
                    context.codeWriter.write(parameter.seqName!!)
                    context.codeWriter.write(", ")

                    val cast = if (source.isComponentCapture) "(${source.fieldTypeName})" else ""
                    context.codeWriter.writeParts("(__value) => { ", parameter.parameterName, "(", cast, "__value); }")
                    context.codeWriter.writeEndMethodInvocation()
                }

                is CascadingGenericTypeParameter -> {
                    // We only use the synthetic cascading parameters for type inference
                }

                is RenderModeIntermediateNode -> renderModeParameterName = parameter.parameterName

                else -> throw IllegalStateException("Not implemented: type inference method parameter from source $source")
            }
        }

        val parameterName = renderModeParameterName
        if (parameterName != null) writeAddComponentRenderMode(context, BuilderVariableName.DEFAULT, parameterName)

        context.codeWriter.writeInstanceMethodInvocation(ComponentsApi.RenderTreeBuilder.builderParameter, ComponentsApi.RenderTreeBuilder.closeComponent)

        if (returnComponentType) writer.writeLine("return default;")

        writer.writeLine("}")

        if (component.component!!.suppliesCascadingGenericParameters()) {
            // A component that cascades generic parameters needs its type inference arguments captured at the call site, so that each
            // expression is evaluated once, in the order of the source, and lambdas and other expressions that cannot be assigned to an
            // implicitly typed var can still be captured. A method with the same type inference characteristics as CreateFoo_0 does it:
            //
            //  public static void CreateFoo_0_CaptureParameters<T1, T2>(T1 __arg0, out T1 __arg0_out, ...)
            //  {
            //      __arg0_out = __arg0;
            //      ...
            //  }
            writer.writeLine()
            writer.write("public static void ")
            writer.write(node.methodName ?: "")
            writer.write("_CaptureParameters<")
            writer.write(component.component!!.getTypeParameters().joinToString(", ") { it.name })
            writer.write(">")

            writer.write("(")
            var isFirst = true
            for (parameter in parameters.filter { it.usedForTypeInference }) {
                if (isFirst) isFirst = false else writer.write(", ")

                writer.write(parameter.typeName ?: "")
                writer.write(" ")
                writer.write(parameter.parameterName)
                writer.write(", out ")
                writer.write(parameter.typeName ?: "")
                writer.write(" ")
                writer.write(parameter.parameterName)
                writer.write("_out")
            }

            writer.write(")")

            writeConstraints(writer, node)

            writer.writeLine("{")
            for (parameter in parameters.filter { it.usedForTypeInference }) {
                writer.write("    ")
                writer.write(parameter.parameterName)
                writer.write("_out = ")
                writer.write(parameter.parameterName)
                writer.writeLine(";")
            }
            writer.writeLine("}")
        }
    }

    /** `writeConstraints`: the generic type constraints, one per line, indented one level deeper than the declaration. */
    private fun writeConstraints(writer: CodeWriter, node: ComponentTypeInferenceMethodIntermediateNode) {
        for (constraint in node.genericTypeConstraints ?: emptyList()) {
            writer.writeLine()
            writer.indent(writer.currentIndent + writer.tabSize)
            writer.write(constraint)
        }

        writer.writeLine()
    }

    private fun serializeTypeParameter(attribute: BoundAttributeDescriptor): String =
        (attribute.metadata as? TypeParameterMetadata)?.nameWithAttributes ?: attribute.name

    private fun getTypeInferenceMethodParameters(node: ComponentTypeInferenceMethodIntermediateNode): MutableList<TypeInferenceMethodParameter> {
        val p = ArrayList<TypeInferenceMethodParameter>()
        val component = node.component!!

        // Preserve order between attributes and splats
        for (child in component.children) {
            if (child is ComponentAttributeIntermediateNode) {
                // Some nodes just exist to help with property access at design time, and don't need anything else written
                if (child.isDesignTimePropertyAccessHelper) continue

                var typeName: String?
                if (child.globallyQualifiedTypeName != null) {
                    typeName = child.globallyQualifiedTypeName
                } else {
                    typeName = child.typeName
                    if (child.boundAttribute != null && !child.boundAttribute!!.isGenericTypedProperty()) {
                        typeName = if (typeName != null && typeName.startsWith("global::")) typeName else "global::$typeName"
                    }
                }

                p.add(TypeInferenceMethodParameter(SeqName(p.size), typeName, ParameterName(p.size), usedForTypeInference = true, child))
            } else if (child is SplatIntermediateNode) {
                val typeName = ComponentsApi.addMultipleAttributesTypeFullName
                p.add(TypeInferenceMethodParameter(SeqName(p.size), typeName, ParameterName(p.size), usedForTypeInference = false, child))
            } else if (child is RenderModeIntermediateNode) {
                val typeName = ComponentsApi.IComponentRenderMode.fullTypeName
                p.add(TypeInferenceMethodParameter(SeqName(p.size), typeName, ParameterName(p.size), usedForTypeInference = false, child))
            }
        }

        for (childContent in component.childContents) {
            var typeName = childContent.typeName
            val boundAttribute = childContent.boundAttribute
            if (boundAttribute != null && !boundAttribute.isGenericTypedProperty()) typeName = boundAttribute.getGloballyQualifiedTypeName()
            p.add(TypeInferenceMethodParameter(SeqName(p.size), typeName, ParameterName(p.size), usedForTypeInference = false, childContent))
        }

        for (capture in component.setKeys) {
            p.add(TypeInferenceMethodParameter(SeqName(p.size), "object", ParameterName(p.size), usedForTypeInference = false, capture))
        }

        for (capture in component.captures) {
            // The capture type name should already contain the global:: prefix.
            p.add(TypeInferenceMethodParameter(SeqName(p.size), capture.typeName, ParameterName(p.size), usedForTypeInference = false, capture))
        }

        // Insert synthetic args for cascaded type inference at the start of the list. We do this last so that the indices above aren't affected.
        val receivesCascadingGenericTypes = node.receivesCascadingGenericTypes
        if (receivesCascadingGenericTypes != null) {
            var i = 0
            for (cascadingGenericType in receivesCascadingGenericTypes) {
                p.add(i, TypeInferenceMethodParameter(null, cascadingGenericType.valueType, ParameterName(i, isSynthetic = true), usedForTypeInference = true, cascadingGenericType))
                i++
            }
        }

        return p
    }

    private fun isDefaultExpression(expression: String): Boolean = expression == "default" || expression.startsWith("default(")

    private fun writeAddComponentRenderMode(context: CodeRenderingContext, builderName: BuilderVariableName, renderModeName: IWriteableValue) {
        context.codeWriter.writeLineParts(builderName, ".", ComponentsApi.RenderTreeBuilder.addComponentRenderMode, "(", renderModeName, ");")
    }

    private val sourceSequenceDigits: Int get() = sourceSequence.toString().length // CountDigits

    private val currentAttributeValues = ArrayList<IntermediateToken>()

    private var sourceSequence = 0

    override fun writeCSharpCode(context: CodeRenderingContext, node: CSharpCodeIntermediateNode) {
        var isWhitespaceStatement = true
        for (child in node.children) {
            if (child !is IntermediateToken || !child.content.isNullOrDotNetWhiteSpace()) {
                isWhitespaceStatement = false
                break
            }
        }

        if (node.source == null && isWhitespaceStatement) {
            // If source is null, we won't create source mappings, and if we're not creating source mappings, there is no point emitting whitespace
            return
        }

        for (child in node.children) {
            if (child is CSharpIntermediateToken) {
                writeCSharpToken(context, child)
            } else {
                // There may be something else inside the statement like an extension node.
                context.renderNode(child)
            }
        }

        context.codeWriter.writeLine()
    }

    override fun writeCSharpExpression(context: CodeRenderingContext, node: CSharpExpressionIntermediateNode) {
        val characterOffset = builderVariableName.length + // for "__builder"
            1 + // for '.'
            ComponentsApi.RenderTreeBuilder.addContent.length +
            1 + // for '('
            sourceSequenceDigits + // for the sequence number
            2 // for ', '

        // Sequence points can only be emitted when the eval stack is empty: the pragma goes before the invocation, offset to map the first
        // C# child only. Trailing C# after a render template is not mapped; with no C# child nothing is mapped.
        val firstCSharpChild = node.children.firstOrNull { it is CSharpIntermediateToken } as CSharpIntermediateToken?
        context.buildEnhancedLinePragma(firstCSharpChild?.source, characterOffset).use {
            context.codeWriter
                .writeStartMethodInvocationParts(builderVariableName, ".", ComponentsApi.RenderTreeBuilder.addContent)
                .writeIntegerLiteral(sourceSequence++)
                .writeParameterSeparator()

            if (firstCSharpChild != null) context.codeWriter.write(firstCSharpChild.content)
        }

        // The remaining children: the #line pragmas of the remaining C# tokens produce no sequence points.
        for (child in node.children) {
            if (child === firstCSharpChild) continue

            if (child is CSharpIntermediateToken) {
                writeCSharpToken(context, child)
            } else {
                // There may be something else inside the expression like a Template or another extension node.
                context.renderNode(child)
            }
        }

        context.codeWriter.writeEndMethodInvocation()
    }

    override fun writeCSharpExpressionAttributeValue(context: CodeRenderingContext, node: CSharpExpressionAttributeValueIntermediateNode) {
        // In cases like "somestring @variable", Razor tokenizes it as [0] HtmlContent="somestring", [1] CSharpContent="variable" Prefix=" ";
        // so to avoid losing whitespace, the prefix becomes a further token in the list.
        val prefix = node.prefix
        if (!prefix.isNullOrEmpty()) currentAttributeValues.add(IntermediateNodeFactory.htmlToken(prefix))

        for (child in node.children) currentAttributeValues.add(child as IntermediateToken)
    }

    override fun writeMarkupBlock(context: CodeRenderingContext, node: MarkupBlockIntermediateNode) {
        context.codeWriter
            .writeStartMethodInvocationParts(builderVariableName, ".", ComponentsApi.RenderTreeBuilder.addMarkupContent)
            .writeIntegerLiteral(sourceSequence++)
            .writeParameterSeparator()
            .writeStringLiteral(node.content ?: "")
            .writeEndMethodInvocation()
    }

    override fun writeMarkupElement(context: CodeRenderingContext, node: MarkupElementIntermediateNode) {
        context.codeWriter
            .writeStartMethodInvocationParts(builderVariableName, ".", ComponentsApi.RenderTreeBuilder.openElement)
            .writeIntegerLiteral(sourceSequence++)
            .writeParameterSeparator()
            .writeStringLiteral(node.tagName ?: "")
            .writeEndMethodInvocation()

        var hasFormName = false

        // Render attributes and splats (in order) before creating the scope.
        for (child in node.children) {
            if (child is HtmlAttributeIntermediateNode) {
                context.renderNode(child)
            } else if (child is ComponentAttributeIntermediateNode) {
                context.renderNode(child)
            } else if (child is SplatIntermediateNode) {
                context.renderNode(child)
            } else if (child is FormNameIntermediateNode) {
                assert(!hasFormName) // Debug.Assert
                context.renderNode(child)
                hasFormName = true
            }
        }

        for (setKey in node.setKeys) context.renderNode(setKey)

        for (capture in node.captures) context.renderNode(capture)

        // AddNamedEvent must be called after all attributes (but before child content).
        if (hasFormName) {
            // _builder.AddNamedEvent("onsubmit", __formName);
            context.codeWriter.writeLineParts(builderVariableName, ".", ComponentsApi.RenderTreeBuilder.addNamedEvent, "(\"onsubmit\", ", formNameVariableName, ");")
            scopeStack.incrementFormName()
        }

        // Render body of the tag inside the scope
        for (child in node.body) context.renderNode(child)

        context.codeWriter
            .writeStartMethodInvocationParts(builderVariableName, ".", ComponentsApi.RenderTreeBuilder.closeElement)
            .writeEndMethodInvocation()
    }

    override fun writeHtmlAttribute(context: CodeRenderingContext, node: HtmlAttributeIntermediateNode) {
        assert(currentAttributeValues.isEmpty()) // Debug.Assert
        context.renderChildren(node)

        val values = currentAttributeValues.toList().also { currentAttributeValues.clear() } // ToImmutableAndClear
        val nameExpression = node.attributeNameExpression
        if (nameExpression == null) writeAttribute(context, node.attributeName ?: "", values) else writeAttribute(context, nameExpression, values)

        val eventUpdatesAttributeName = node.eventUpdatesAttributeName
        if (!eventUpdatesAttributeName.isNullOrEmpty()) {
            context.codeWriter
                .writeStartMethodInvocationParts(builderVariableName, ".", ComponentsApi.RenderTreeBuilder.setUpdatesAttributeName)
                .writeStringLiteral(eventUpdatesAttributeName)
                .writeEndMethodInvocation()
        }
    }

    override fun writeHtmlAttributeValue(context: CodeRenderingContext, node: HtmlAttributeValueIntermediateNode) {
        val stringContent = (node.children.single() as IntermediateToken).content
        currentAttributeValues.add(IntermediateNodeFactory.htmlToken((node.prefix ?: "") + stringContent))
    }

    override fun writeHtmlContent(context: CodeRenderingContext, node: HtmlContentIntermediateNode) {
        // Text node
        val content = getHtmlContent(node)
        var renderApi = ComponentsApi.RenderTreeBuilder.addContent
        if (node.hasEncodedContent) {
            // This content is already encoded.
            renderApi = ComponentsApi.RenderTreeBuilder.addMarkupContent
        }

        context.codeWriter
            .writeStartMethodInvocationParts(builderVariableName, ".", renderApi)
            .writeIntegerLiteral(sourceSequence++)
            .writeParameterSeparator()
            .writeStringLiteral(content)
            .writeEndMethodInvocation()
    }

    override fun writeUsingDirective(context: CodeRenderingContext, node: UsingDirectiveIntermediateNode) {
        val sourceSpan = node.source
        if (sourceSpan != null && sourceSpan.filePath != null) {
            context.buildEnhancedLinePragma(sourceSpan, suppressLineDefaultAndHidden = true).use {
                context.codeWriter.writeUsing(node.content ?: "", endLine = node.hasExplicitSemicolon)
            }
            if (!node.hasExplicitSemicolon) context.codeWriter.writeLine(";")
            if (node.appendLineDefaultAndHidden) {
                context.codeWriter.writeLine("#line default")
                context.codeWriter.writeLine("#line hidden")
            }
        } else {
            context.codeWriter.writeUsing(node.content ?: "", endLine = true)

            if (node.appendLineDefaultAndHidden) {
                context.codeWriter.writeLine("#line default")
                context.codeWriter.writeLine("#line hidden")
            }
        }
    }

    override fun writeComponent(context: CodeRenderingContext, node: ComponentIntermediateNode) {
        val typeInferenceNode = node.typeInferenceNode
        if (shouldSuppressTypeInferenceCall(node)) {
            // Nothing: no call to the inference method.
        } else if (typeInferenceNode == null) {
            // No type inference: an open/close with the add attribute calls in between.
            //
            // _builder.OpenComponent<MyComponent>(0);
            // _builder.AddComponentParameter(1, "Foo", ...);
            // _builder.AddComponentParameter(2, "ChildContent", ...);
            // _builder.SetKey(someValue);
            // _builder.AddElementCapture(3, (__value) => _field = __value);
            // _builder.CloseComponent();

            // _builder.OpenComponent<TComponent>(42);
            context.codeWriter.write(builderVariableName)
            context.codeWriter.write(".")
            context.codeWriter.write(ComponentsApi.RenderTreeBuilder.openComponent)
            context.codeWriter.write("<")

            val (nonGenericTypeName, _) = TypeNameHelper.getNonGenericTypeName(node.typeName ?: "")
            TypeNameHelper.writeGlobalPrefixIfNeeded(context.codeWriter, nonGenericTypeName)
            writeComponentTypeName(context, node, nonGenericTypeName)

            val orderedTypeArguments = node.orderedTypeArguments
            if (!orderedTypeArguments.isNullOrEmpty()) {
                context.codeWriter.write("<")
                for (i in orderedTypeArguments.indices) {
                    val typeArg = orderedTypeArguments[i]
                    writeComponentTypeArgument(context, typeArg)
                    if (i != orderedTypeArguments.size - 1) context.codeWriter.write(", ")
                }
                context.codeWriter.write(">")
            }

            context.codeWriter.write(">(")
            context.codeWriter.writeIntegerLiteral(sourceSequence++)
            context.codeWriter.write(");")
            context.codeWriter.writeLine()

            // We can skip type arguments during runtime codegen, they are handled in the type/parameter declarations.

            var hasRenderMode = false

            // Preserve order of attributes and splats
            for (child in node.children) {
                if (child is ComponentAttributeIntermediateNode) {
                    context.renderNode(child)
                } else if (child is SplatIntermediateNode) {
                    context.renderNode(child)
                } else if (child is RenderModeIntermediateNode) {
                    assert(!hasRenderMode) // Debug.Assert
                    context.renderNode(child)
                    hasRenderMode = true
                }
            }

            for (childContent in node.childContents) context.renderNode(childContent)

            for (setKey in node.setKeys) context.renderNode(setKey)

            for (capture in node.captures) context.renderNode(capture)

            if (hasRenderMode) {
                // _builder.AddComponentRenderMode(__renderMode_0);
                writeAddComponentRenderMode(context, builderVariableName, renderModeVariableName)
                scopeStack.incrementRenderMode()
            }

            // _builder.CloseComponent();
            context.codeWriter.write(builderVariableName)
            context.codeWriter.write(".")
            context.codeWriter.write(ComponentsApi.RenderTreeBuilder.closeComponent)
            context.codeWriter.write("();")
            context.codeWriter.writeLine()
        } else {
            val parameters = getTypeInferenceMethodParameters(typeInferenceNode)

            // A component that cascades any of its generic types splits its type inference in two: an inference method that captures all
            // the parameters in local variables, then the real type inference method called with them, so that descendants can use the
            // captured variables without re-evaluating the expressions.
            var typeInferenceCaptureScope: CSharpCodeWritingScope? = null
            if (node.component!!.suppliesCascadingGenericParameters()) {
                typeInferenceCaptureScope = context.codeWriter.buildScope()
                TypeNameHelper.writeGloballyQualifiedName(context.codeWriter, typeInferenceNode.fullTypeName ?: "")
                context.codeWriter.write(".")
                context.codeWriter.write(typeInferenceNode.methodName ?: "")
                context.codeWriter.write("_CaptureParameters(")
                var isFirst = true
                for (parameter in parameters.filter { it.usedForTypeInference }) {
                    if (isFirst) isFirst = false else context.codeWriter.write(", ")

                    writeTypeInferenceMethodParameterInnards(context, parameter)
                    context.codeWriter.write(", out var ")

                    val variableName = TypeInferenceArgName(scopeStack.depth, parameter.parameterName)
                    context.codeWriter.write(variableName)

                    useCapturedCascadingGenericParameterVariable(node, parameter, variableName)
                }
                context.codeWriter.writeLine(");")
            }

            // With type inference the component cannot be initialized inline: the method generated elsewhere is passed all the
            // attribute values and the sequence numbers.
            //
            // __Blazor.MyComponent.TypeInference.CreateMyComponent_0(builder, 0, 1, ..., 2, ..., 3, ...);

            TypeNameHelper.writeGloballyQualifiedName(context.codeWriter, typeInferenceNode.fullTypeName ?: "")
            context.codeWriter.write(".")
            context.codeWriter.write(typeInferenceNode.methodName ?: "")
            context.codeWriter.write("(")

            context.codeWriter.write(builderVariableName)
            context.codeWriter.write(", ")

            context.codeWriter.writeIntegerLiteral(sourceSequence++)

            for (parameter in parameters) {
                context.codeWriter.write(", ")

                if (parameter.seqName != null) {
                    context.codeWriter.writeIntegerLiteral(sourceSequence++)
                    context.codeWriter.write(", ")
                }

                writeTypeInferenceMethodParameterInnards(context, parameter)
            }

            context.codeWriter.write(");")
            context.codeWriter.writeLine()

            if (typeInferenceCaptureScope != null) {
                for (localToClear in parameters.map { it.source }.filterIsInstance<TypeInferenceCapturedVariable>()) {
                    // Not interfering with the GC lifetime of the captured values: the closures only use their types for compile-time inference.
                    context.codeWriter.write(localToClear.variableName)
                    context.codeWriter.writeLine(" = default;")
                }

                typeInferenceCaptureScope.close()
            }
        }
    }

    override fun writeComponentTypeInferenceMethod(context: CodeRenderingContext, node: ComponentTypeInferenceMethodIntermediateNode) {
        writeComponentTypeInferenceMethod(context, node, returnComponentType = false, allowNameof = true, mapComponentStartTag = true)
    }

    private fun writeTypeInferenceMethodParameterInnards(context: CodeRenderingContext, parameter: TypeInferenceMethodParameter) {
        when (val source = parameter.source) {
            // Generics are not type checked (the type name cannot be written): the generated method and the call to it check them anyway.
            is ComponentAttributeIntermediateNode -> writeComponentAttributeInnards(context, source, canTypeCheck = false)
            is SplatIntermediateNode -> writeSplatInnards(context, source, canTypeCheck = false)
            is ComponentChildContentIntermediateNode -> writeComponentChildContentInnards(context, source)
            is SetKeyIntermediateNode -> writeSetKeyInnards(context, source)
            is ReferenceCaptureIntermediateNode -> writeReferenceCaptureInnards(context, source, shouldTypeCheck = false)
            is CascadingGenericTypeParameter -> {
                // The value is populated before it is used (the code of the ancestors is emitted first); a fallback if it was not.
                val valueExpression = source.valueExpression
                if (valueExpression is IWriteableValue) {
                    valueExpression.writeTo(context.codeWriter)
                } else {
                    val expression = valueExpression as? String ?: "default"
                    context.codeWriter.write(expression)

                    if (!context.options.suppressNullabilityEnforcement && isDefaultExpression(expression)) context.codeWriter.write("!")
                }
            }

            is TypeInferenceCapturedVariable -> context.codeWriter.write(source.variableName)
            is RenderModeIntermediateNode ->
                writeCSharpCode(context, CSharpCodeIntermediateNode().also { it.source = source.source; it.children.add(source.children[0]) })

            else -> throw IllegalStateException("Not implemented: type inference method parameter from source $source")
        }
    }

    override fun writeComponentAttribute(context: CodeRenderingContext, node: ComponentAttributeIntermediateNode) {
        if (node.isDesignTimePropertyAccessHelper) {
            writeDesignTimePropertyAccessor(context, node)
            return
        }

        val addAttributeMethod = node.addAttributeMethodName ?: getAddComponentParameterMethodName(context)

        // _builder.AddComponentParameter(1, nameof(Component.Property), 42);
        context.codeWriter.write(builderVariableName)
        context.codeWriter.write(".")
        context.codeWriter.write(addAttributeMethod)
        context.codeWriter.write("(")
        context.codeWriter.writeIntegerLiteral(sourceSequence++)
        context.codeWriter.write(", ")

        writeComponentAttributeName(context, node)
        context.codeWriter.write(", ")

        if (addAttributeMethod == ComponentsApi.RenderTreeBuilder.addAttribute) context.codeWriter.write("(object)(")

        writeComponentAttributeInnards(context, node, canTypeCheck = true)

        if (addAttributeMethod == ComponentsApi.RenderTreeBuilder.addAttribute) context.codeWriter.write(")")

        context.codeWriter.write(");")
        context.codeWriter.writeLine()
    }

    private fun writeComponentAttributeInnards(context: CodeRenderingContext, node: ComponentAttributeIntermediateNode, canTypeCheck: Boolean) {
        if (node.children.size > 1) {
            // We should have reported an error for mixed content; the children are rendered anyway, so tooling works.
            assert(node.hasDiagnostics) { "We should have reported an error for mixed content." }
        }

        val only = if (node.children.size == 1) node.children[0] else null
        if (node.attributeStructure == AttributeStructure.Minimized) {
            // Minimized attributes always map to 'true'
            context.codeWriter.write("true")
        } else if (only is HtmlContentIntermediateNode) {
            // This is how string attributes are lowered by default, a single HTML node with a single HTML token.
            val content = getHtmlTokens(only).joinToString("") { it.content }
            if (canTypeCheck && node.boundAttribute?.acceptsStringLiteral() == true) {
                context.codeWriter.write(ComponentsApi.RuntimeHelpers.typeCheck)
                context.codeWriter.write("<")
                writeGloballyQualifiedTypeName(context, node)
                context.codeWriter.write(">(")
                context.codeWriter.writeStringLiteral(content)
                context.codeWriter.write(")")
            } else {
                context.codeWriter.writeStringLiteral(content)
            }
        } else {
            // Handle delegate or child-content attributes specially.
            val tokens = getCSharpTokens(node)
            if ((node.boundAttribute?.isDelegateProperty() ?: false) || (node.boundAttribute?.isChildContentProperty() ?: false)) {
                if (canTypeCheck) {
                    context.codeWriter.write("(")
                    writeGloballyQualifiedTypeName(context, node)
                    context.codeWriter.write(")")
                    context.codeWriter.write("(")
                }

                writeCSharpTokens(context, tokens)

                if (canTypeCheck) context.codeWriter.write(")")
            } else if (node.boundAttribute?.isEventCallbackProperty() ?: false) {
                val explicitType = node.hasExplicitTypeName
                val isInferred = node.isOpenGeneric
                if (canTypeCheck && needsTypeCheck(node)) {
                    context.codeWriter.write(ComponentsApi.RuntimeHelpers.typeCheck)
                    context.codeWriter.write("<")
                    qualifyEventCallback(context.codeWriter, node.typeName, explicitType)
                    context.codeWriter.write(">")
                    context.codeWriter.write("(")
                }

                // Microsoft.AspNetCore.Components.EventCallback.Factory.Create(this, ...) OR
                // Microsoft.AspNetCore.Components.EventCallback.Factory.Create<T>(this, ...)

                context.codeWriter.write("global::")
                context.codeWriter.write(ComponentsApi.EventCallback.factoryAccessor)
                context.codeWriter.write(".")
                context.codeWriter.write(ComponentsApi.EventCallbackFactory.createMethod)

                val argument = if (!isInferred) node.tryParseEventCallbackTypeArgument() else null
                if (argument != null) {
                    context.codeWriter.write("<")
                    if (explicitType) context.codeWriter.write(argument) else TypeNameHelper.writeGloballyQualifiedName(context.codeWriter, argument)
                    context.codeWriter.write(">")
                }

                context.codeWriter.write("(")
                context.codeWriter.write("this")
                context.codeWriter.write(", ")

                writeCSharpTokens(context, tokens)

                context.codeWriter.write(")")

                if (canTypeCheck && needsTypeCheck(node)) context.codeWriter.write(")")
            } else {
                if (canTypeCheck && needsTypeCheck(node)) {
                    context.codeWriter.write(ComponentsApi.RuntimeHelpers.typeCheck)
                    context.codeWriter.write("<")
                    writeGloballyQualifiedTypeName(context, node)
                    context.codeWriter.write(">")
                    context.codeWriter.write("(")
                }

                writeCSharpTokens(context, tokens)

                if (canTypeCheck && needsTypeCheck(node)) context.codeWriter.write(")")
            }
        }
    }

    private fun qualifyEventCallback(codeWriter: CodeWriter, typeName: String?, explicitType: Boolean?) {
        val argument = ComponentAttributeIntermediateNode.tryGetEventCallbackArgument(typeName ?: "")
        if (argument != null) {
            codeWriter.write("global::")
            codeWriter.write(ComponentsApi.EventCallback.fullTypeName)
            codeWriter.write("<")
            if (explicitType == true) codeWriter.write(argument) else TypeNameHelper.writeGloballyQualifiedName(codeWriter, argument)
            codeWriter.write(">")
        } else {
            TypeNameHelper.writeGloballyQualifiedName(codeWriter, typeName ?: "")
        }
    }

    private fun needsTypeCheck(n: ComponentAttributeIntermediateNode): Boolean = n.boundAttribute != null && !n.boundAttribute!!.isWeaklyTyped

    override fun writeComponentChildContent(context: CodeRenderingContext, node: ComponentChildContentIntermediateNode) {
        // _builder.AddComponentParameter(1, "ChildContent", (RenderFragment)((__builder73) => { ... }));
        // OR
        // _builder.AddComponentParameter(1, "ChildContent", (RenderFragment<Person>)((person) => (__builder73) => { ... }));
        beginWriteAttribute(context, node.attributeName)
        context.codeWriter.writeParameterSeparator()
        context.codeWriter.write("(")
        writeGloballyQualifiedTypeName(context, node)
        context.codeWriter.write(")(")

        writeComponentChildContentInnards(context, node)

        context.codeWriter.write(")")
        context.codeWriter.writeEndMethodInvocation()
    }

    private fun writeComponentChildContentInnards(context: CodeRenderingContext, node: ComponentChildContentIntermediateNode) {
        // ((__builder73) => { ... })
        // OR
        // ((person) => (__builder73) => { })
        val parameterName = if (node.isParameterized) node.parameterName else null

        scopeStack.openComponentScope(context, parameterName).use {
            for (child in node.children) context.renderNode(child)
        }
    }

    override fun writeComponentTypeArgument(context: CodeRenderingContext, node: ComponentTypeArgumentIntermediateNode) {
        writeCSharpToken(context, node.value)
    }

    /** `WriteTemplate`: `(__builder73) => { ... }`. */
    override fun writeTemplate(context: CodeRenderingContext, node: TemplateIntermediateNode) {
        scopeStack.openTemplateScope(context).use {
            context.renderChildren(node)
        }
    }

    override fun writeSetKey(context: CodeRenderingContext, node: SetKeyIntermediateNode) {
        // _builder.SetKey(_keyValue);
        val codeWriter = context.codeWriter

        codeWriter.writeStartMethodInvocationParts(builderVariableName, ".", ComponentsApi.RenderTreeBuilder.setKey)
        writeSetKeyInnards(context, node)
        codeWriter.writeEndMethodInvocation()
    }

    private fun writeSetKeyInnards(context: CodeRenderingContext, node: SetKeyIntermediateNode) {
        writeCSharpCode(context, CSharpCodeIntermediateNode().also { it.source = node.source; it.children.add(node.keyValueToken) })
    }

    override fun writeSplat(context: CodeRenderingContext, node: SplatIntermediateNode) {
        // _builder.AddMultipleAttributes(2, ...);
        context.codeWriter.writeStartMethodInvocationParts(builderVariableName, ".", ComponentsApi.RenderTreeBuilder.addMultipleAttributes)
        context.codeWriter.writeIntegerLiteral(sourceSequence++)
        context.codeWriter.writeParameterSeparator()

        writeSplatInnards(context, node, canTypeCheck = true)

        context.codeWriter.writeEndMethodInvocation()
    }

    private fun writeSplatInnards(context: CodeRenderingContext, node: SplatIntermediateNode, canTypeCheck: Boolean) {
        val writer = context.codeWriter

        if (canTypeCheck) writer.writeParts(ComponentsApi.RuntimeHelpers.typeCheck, "<", ComponentsApi.addMultipleAttributesTypeFullName, ">(")

        val tokens = node.findDescendantNodes<CSharpIntermediateToken>() // CollectDescendantNodes

        writeCSharpTokens(context, tokens)

        if (canTypeCheck) writer.write(")")
    }

    override fun writeFormName(context: CodeRenderingContext, node: FormNameIntermediateNode) {
        if (node.children.size > 1) assert(node.hasDiagnostics) { "We should have reported an error for mixed content." }

        // string __formName = expression;
        context.codeWriter.writeParts("string ", formNameVariableName, " = ", ComponentsApi.RuntimeHelpers.typeCheck, "<string>(")
        writeAttributeValue(context, node.findDescendantNodes<IntermediateToken>())
        context.codeWriter.writeLine(");")
    }

    override fun writeReferenceCapture(context: CodeRenderingContext, node: ReferenceCaptureIntermediateNode) {
        // _builder.AddComponentReferenceCapture(2, (__value) = { _field = (MyComponent)__value; });
        // OR
        // _builder.AddElementReferenceCapture(2, (__value) = { _field = (ElementReference)__value; });
        val codeWriter = context.codeWriter

        val methodName = if (node.isComponentCapture) ComponentsApi.RenderTreeBuilder.addComponentReferenceCapture
        else ComponentsApi.RenderTreeBuilder.addElementReferenceCapture
        codeWriter
            .writeStartMethodInvocationParts(builderVariableName, ".", methodName)
            .writeIntegerLiteral(sourceSequence++)
            .writeParameterSeparator()

        writeReferenceCaptureInnards(context, node, shouldTypeCheck = true)

        codeWriter.writeEndMethodInvocation()
    }

    private fun writeReferenceCaptureInnards(context: CodeRenderingContext, node: ReferenceCaptureIntermediateNode, shouldTypeCheck: Boolean) {
        // (__value) = { _field = (MyComponent)__value; }
        // OR
        // (__value) = { _field = (ElementRef)__value; }
        val refCaptureParamName = "__value"
        val defaultAssignment = " = $refCaptureParamName;"

        context.codeWriter.buildLambda(refCaptureParamName).use {
            val typeCheck = shouldTypeCheck && node.isComponentCapture

            val assignmentToken = if (typeCheck) IntermediateNodeFactory.csharpToken(" = (${node.fieldTypeName})$refCaptureParamName;")
            else IntermediateNodeFactory.csharpToken(defaultAssignment)

            writeCSharpCode(
                context,
                CSharpCodeIntermediateNode().also {
                    it.source = node.source
                    it.children.add(node.identifierToken)
                    it.children.add(assignmentToken)
                },
            )
        }
    }

    override fun writeRenderMode(context: CodeRenderingContext, node: RenderModeIntermediateNode) {
        // global::Microsoft.AspNetCore.Components.IComponentRenderMode __renderMode0 = expression;
        context.codeWriter.writeParts("global::", ComponentsApi.IComponentRenderMode.fullTypeName, " ", renderModeVariableName, " = ")

        writeCSharpCode(context, CSharpCodeIntermediateNode().also { it.source = node.source; it.children.add(node.children[0]) })

        context.codeWriter.writeLine(";")
    }

    private fun writeAttribute(context: CodeRenderingContext, key: String, value: List<IntermediateToken>) {
        beginWriteAttribute(context, key)

        if (value.isNotEmpty()) {
            context.codeWriter.writeParameterSeparator()
            writeAttributeValue(context, value)
        } else if (!context.options.omitMinimizedComponentAttributeValues) {
            // In version 5+, there's no need to supply a value for a minimized attribute. But for older language versions, minimized
            // attributes were represented as "true".
            context.codeWriter.writeParameterSeparator()
            context.codeWriter.writeBooleanLiteral(true)
        }

        context.codeWriter.writeEndMethodInvocation()
    }

    private fun writeAttribute(context: CodeRenderingContext, nameExpression: IntermediateNode, value: List<IntermediateToken>) {
        beginWriteAttribute(context, nameExpression)

        if (value.isNotEmpty()) {
            context.codeWriter.writeParameterSeparator()
            writeAttributeValue(context, value)
        }

        context.codeWriter.writeEndMethodInvocation()
    }

    private fun beginWriteAttribute(context: CodeRenderingContext, key: String) {
        context.codeWriter
            .writeStartMethodInvocationParts(builderVariableName, ".", ComponentsApi.RenderTreeBuilder.addAttribute)
            .writeIntegerLiteral(sourceSequence++)
            .writeParameterSeparator()
            .writeStringLiteral(key)
    }

    private fun beginWriteAttribute(context: CodeRenderingContext, nameExpression: IntermediateNode) {
        context.codeWriter.writeStartMethodInvocationParts(builderVariableName, ".", ComponentsApi.RenderTreeBuilder.addAttribute)
        context.codeWriter.writeIntegerLiteral(sourceSequence++)
        context.codeWriter.writeParameterSeparator()

        val tokens = getCSharpTokens(nameExpression)
        for (i in tokens.indices) writeCSharpToken(context, tokens[i])
    }

    private companion object {
        fun writeComponentAttributeName(context: CodeRenderingContext, attribute: ComponentAttributeIntermediateNode, allowNameof: Boolean = true) {
            val boundContainingType = attribute.boundAttribute?.containingType
            if (allowNameof && boundContainingType != null) {
                val containingType = attribute.concreteContainingType ?: boundContainingType

                // nameof(containingType.PropertyName): an actual reference to the parameter, for Find All References in the IDE.
                context.codeWriter.write("nameof(")
                TypeNameHelper.writeGloballyQualifiedName(context.codeWriter, containingType)
                context.codeWriter.write(".")

                val propertyName = attribute.propertyName!!
                if (!attribute.isSynthesized) {
                    val attributeSourceSpan = (attribute.propertySpan ?: attribute.originalAttributeSpan)!!
                    val requiresEscaping = propertyName.identifierRequiresEscaping()
                    context.buildEnhancedLinePragma(attributeSourceSpan, characterOffset = if (requiresEscaping) 1 else 0).use {
                        context.codeWriter.writeIdentifierEscapeIfNeeded(propertyName)
                        context.codeWriter.writeLine(propertyName)
                    }
                } else {
                    context.codeWriter.write(propertyName)
                }
                context.codeWriter.write(")")
            } else {
                context.codeWriter.writeStringLiteral(attribute.attributeName ?: "")
            }
        }

        fun useCapturedCascadingGenericParameterVariable(node: ComponentIntermediateNode, parameter: TypeInferenceMethodParameter, variableName: TypeInferenceArgName) {
            // If this captured variable corresponds to a generic type we want to cascade to descendants, supply that info to descendants
            val provides = node.providesCascadingGenericTypes
            if (provides != null) {
                for (cascadeGeneric in provides.values) {
                    if (cascadeGeneric.valueSourceNode === parameter.source) cascadeGeneric.valueExpression = variableName
                }
            }

            // Since we've now evaluated and captured this expression, use the variable instead of the expression from now on
            parameter.replaceSourceWithCapturedVariable(variableName)
        }

        fun writeGloballyQualifiedTypeName(context: CodeRenderingContext, node: ComponentAttributeIntermediateNode) {
            val typeName = node.boundAttribute?.getGloballyQualifiedTypeName()
            if (node.hasExplicitTypeName) {
                context.codeWriter.write(node.typeName ?: "")
            } else if (typeName != null) {
                context.codeWriter.write(typeName)
            } else {
                TypeNameHelper.writeGloballyQualifiedName(context.codeWriter, node.typeName ?: "")
            }
        }

        fun writeGloballyQualifiedTypeName(context: CodeRenderingContext, node: ComponentChildContentIntermediateNode) {
            val boundAttribute = node.boundAttribute
            val typeName = boundAttribute?.getGloballyQualifiedTypeName()
            if (typeName != null && !boundAttribute!!.isGenericTypedProperty()) {
                context.codeWriter.write(typeName)
            } else {
                TypeNameHelper.writeGloballyQualifiedName(context.codeWriter, node.typeName ?: "")
            }
        }

        /**
         * `WriteComponentTypeName`: the type name mapped to the start tag, which may or may not be fully qualified
         * (`global::My.Fun.Component` may map to just `Component`).
         */
        fun writeComponentTypeName(context: CodeRenderingContext, node: ComponentIntermediateNode, nonGenericTypeName: CharSequence) {
            var name = nonGenericTypeName

            // Write out "global::" if it's present, and trim it off
            var lastColon = name.lastIndexOf(':')
            if (lastColon > -1) {
                lastColon++
                context.codeWriter.write(name.subSequence(0, lastColon))
                name = name.subSequence(lastColon, name.length)
            }

            // A start tag shorter than the type name is not fully qualified: the namespace parts are written out and trimmed. Razor
            // components don't support nested types, so this logic doesn't either.
            if (node.startTagSpan.length < name.length) {
                var lastDot = name.lastIndexOf('.')
                if (lastDot > -1) {
                    lastDot++
                    context.codeWriter.write(name.subSequence(0, lastDot))
                    name = name.subSequence(lastDot, name.length)
                }
            }

            val offset = if (name.startsWith('@')) 1 else 0
            context.addSourceMappingFor(node.startTagSpan, offset)
            context.codeWriter.write(name)
        }

        fun getHtmlTokens(node: IntermediateNode): List<HtmlIntermediateToken> {
            // We generally expect all children to be HTML, this is here just in case.
            return node.findDescendantNodes<HtmlIntermediateToken>()
        }

        fun getCSharpTokens(node: IntermediateNode): List<CSharpIntermediateToken> {
            // We generally expect all children to be CSharp, this is here just in case.
            return node.findDescendantNodes<CSharpIntermediateToken>()
        }

        /** `WriteDesignTimePropertyAccessor`: `var (_, _) = (nameof(...), 0);` — a discard pattern that no outer `_` lambda parameter captures. */
        fun writeDesignTimePropertyAccessor(context: CodeRenderingContext, attribute: ComponentAttributeIntermediateNode) {
            // These attributes don't exist in the emitted code but have a representation in the razor document: a small piece of code the
            // compiler elides gives the IDE something to reference for Find All References. A bare `_ = ...` would bind to an outer `_`
            // lambda parameter (`Context="_"` on a templated parent component) and fail with CS0029 / CS1662.
            assert(attribute.boundAttribute?.containingType != null) // Debug.Assert
            context.codeWriter.write(" var (_, _) = (")
            writeComponentAttributeName(context, attribute)
            context.codeWriter.writeLine(", 0);")
        }

        fun getHtmlContent(node: HtmlContentIntermediateNode): String {
            val builder = StringBuilder()
            for (child in node.children) if (child is HtmlIntermediateToken) builder.append(child.content)
            return builder.toString()
        }

        /**
         * `WriteAttributeValue`: pure HTML, pure C#, or mixed — the mixed case concatenates the values at runtime, each run of contiguous C#
         * tokens one parenthesized expression (so that ternaries compose with the concatenation).
         */
        fun writeAttributeValue(context: CodeRenderingContext, tokens: List<IntermediateToken>) {
            if (tokens.isEmpty()) return

            val writer = context.codeWriter
            var hasHtml = false
            var hasCSharp = false

            for (token in tokens) {
                if (token is CSharpIntermediateToken) {
                    hasCSharp = true
                } else {
                    assert(token is HtmlIntermediateToken) // Debug.Assert
                    hasHtml = true
                }
            }

            if (!hasCSharp && !hasHtml) throw IllegalStateException("Found attribute whose value is neither HTML nor CSharp")

            // If we only have C# tokens, we write them out directly.
            if (hasCSharp && !hasHtml) {
                for (token in tokens) writeCSharpToken(context, token as CSharpIntermediateToken)
                return
            }

            // If we only have HTML tokens, we write out a single string literal.
            if (hasHtml && !hasCSharp) {
                val builder = StringBuilder()
                for (token in tokens) builder.append(token.content)
                writer.writeStringLiteral(builder.toString())
                return
            }

            var insideCSharp = false
            var first = true
            for (token in tokens) {
                if (token is CSharpIntermediateToken) {
                    if (!insideCSharp) {
                        // Transition to a new C# expression
                        if (!first) writer.write(" + ")

                        writer.write("(")
                        insideCSharp = true
                    }

                    writeCSharpToken(context, token)
                } else {
                    if (insideCSharp) {
                        // Transition to HTML, close out the C# expression
                        writer.write(")")
                        insideCSharp = false
                    }

                    if (!first) writer.write(" + ")

                    writer.writeStringLiteral(token.content)
                }

                if (first) first = false
            }

            if (insideCSharp) writer.write(")")
        }

        fun writeCSharpTokens(context: CodeRenderingContext, tokens: List<CSharpIntermediateToken>) {
            for (token in tokens) writeCSharpToken(context, token)
        }

        fun writeCSharpToken(context: CodeRenderingContext, token: CSharpIntermediateToken) {
            if (token.source?.filePath == null) {
                context.codeWriter.write(token.content)
                return
            }

            context.buildEnhancedLinePragma(token.source).use {
                context.codeWriter.write(token.content)
            }
        }
    }

    /** `SeqName`: `__seq0`, the sequence number parameter of a type inference method. */
    internal data class SeqName(private val index: Int) : IWriteableValue {
        override fun writeTo(writer: CodeWriter) {
            writer.write("__seq")
            writer.writeIntegerLiteral(index)
        }

        override fun toString(): String = "__seq$index"
    }

    /** `ParameterName`: `__arg0`, or `__syntheticArg0` for a cascaded type argument. */
    internal data class ParameterName(private val index: Int, private val isSynthetic: Boolean = false) : IWriteableValue {
        override fun writeTo(writer: CodeWriter) {
            if (isSynthetic) writer.write("__syntheticArg") else writer.write("__arg")
            writer.writeIntegerLiteral(index)
        }

        override fun toString(): String = if (isSynthetic) "__syntheticArg$index" else "__arg$index"
    }

    /** `TypeInferenceArgName`: `__typeInferenceArg_{depth}_{parameter}`, a captured type inference argument. */
    internal data class TypeInferenceArgName(private val depth: Int, private val parameterName: ParameterName) : IWriteableValue {
        override fun writeTo(writer: CodeWriter) {
            writer.write("__typeInferenceArg_")
            writer.writeIntegerLiteral(depth)
            writer.writeParts("_", parameterName)
        }

        override fun toString(): String = "__typeInferenceArg_${depth}_$parameterName"
    }

    /** `TypeInferenceMethodParameter`: [source] is the node the argument comes from, or the captured variable that replaced it. */
    private class TypeInferenceMethodParameter(
        val seqName: SeqName?, val typeName: String?, val parameterName: ParameterName, val usedForTypeInference: Boolean, source: Any,
    ) {
        var source: Any = source
            private set

        fun replaceSourceWithCapturedVariable(variableName: TypeInferenceArgName) {
            source = TypeInferenceCapturedVariable(variableName)
        }
    }

    private class TypeInferenceCapturedVariable(val variableName: TypeInferenceArgName)
}
