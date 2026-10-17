package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.ComponentAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentChildContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentTypeArgumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentTypeInferenceMethodIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ExtensionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.FormNameIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeCollection
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.MarkupBlockIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MarkupElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ReferenceCaptureIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.RenderModeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.SetKeyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.SplatIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UsingDirectiveIntermediateNode

/**
 * `IntermediateNodeWriter` (`CodeGeneration/IntermediateNodeWriter.cs`): the runtime writer of the content nodes — `Write(expr)`,
 * `WriteLiteral("html")`, `BeginWriteAttribute` / `WriteAttributeValue` / `EndWriteAttribute` — with their line pragmas. The method names
 * are open: [LiteralRuntimeNodeWriter] and [TagHelperHtmlAttributeRuntimeNodeWriter] rename one each; the component writers override the
 * component methods, which this writer does not support.
 */
open class IntermediateNodeWriter protected constructor() {
    open val writeCSharpExpressionMethod: String get() = "Write"
    open val writeHtmlContentMethod: String get() = "WriteLiteral"
    open val beginWriteAttributeMethod: String get() = "BeginWriteAttribute"
    open val endWriteAttributeMethod: String get() = "EndWriteAttribute"
    open val writeAttributeValueMethod: String get() = "WriteAttributeValue"
    open val pushWriterMethod: String get() = "PushWriter"
    open val popWriterMethod: String get() = "PopWriter"

    open fun writeUsingDirective(context: CodeRenderingContext, node: UsingDirectiveIntermediateNode) {
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
            context.codeWriter.writeUsing(node.content ?: "")
            if (node.appendLineDefaultAndHidden) {
                context.codeWriter.writeLine("#line default")
                context.codeWriter.writeLine("#line hidden")
            }
        }
    }

    open fun writeCSharpExpression(context: CodeRenderingContext, node: CSharpExpressionIntermediateNode) {
        // The pragma wraps the whole invocation (a sequence point needs an empty eval stack) and is offset past "Write(" so that it maps the
        // first C# token; the other tokens get their own pragmas, which produce no sequence points.
        val characterOffset = writeCSharpExpressionMethod.length + 1 // '('

        val firstCSharpChild = node.children.firstOrNull { it is CSharpIntermediateToken } as CSharpIntermediateToken?
        context.buildEnhancedLinePragma(firstCSharpChild?.source, characterOffset).use {
            context.codeWriter.writeStartMethodInvocation(writeCSharpExpressionMethod)
            if (firstCSharpChild != null) context.codeWriter.write(firstCSharpChild.content)
        }

        for (child in node.children) {
            if (child === firstCSharpChild) continue
            if (child is CSharpIntermediateToken) {
                context.buildEnhancedLinePragma(child.source).use { context.codeWriter.write(child.content) }
            } else {
                // Something else inside the expression, like an extension node.
                context.renderNode(child)
            }
        }

        context.codeWriter.writeEndMethodInvocation()
    }

    open fun writeCSharpCode(context: CodeRenderingContext, node: CSharpCodeIntermediateNode) {
        var isWhitespaceStatement = true
        for (i in node.children.indices) {
            val token = node.children[i] as? IntermediateToken
            if (token == null || !token.content.isNullOrDotNetWhiteSpace()) {
                isWhitespaceStatement = false
                break
            }
        }
        if (isWhitespaceStatement) return

        writeCSharpChildren(node.children, context)
        context.codeWriter.writeLine()
    }

    private fun writeCSharpChildren(children: IntermediateNodeCollection, context: CodeRenderingContext) {
        for (i in children.indices) {
            val child = children[i]
            if (child is CSharpIntermediateToken) {
                context.buildEnhancedLinePragma(child.source).use { context.codeWriter.write(child.content) }
            } else {
                // Something else inside the statement, like an extension node.
                context.renderNode(child)
            }
        }
    }

    open fun writeHtmlAttribute(context: CodeRenderingContext, node: HtmlAttributeIntermediateNode) {
        val valuePieceCount = node.children.count {
            it is HtmlAttributeValueIntermediateNode || it is CSharpExpressionAttributeValueIntermediateNode ||
                it is CSharpCodeAttributeValueIntermediateNode || it is ExtensionIntermediateNode
        }

        val source = node.source!!
        val suffix = node.suffix ?: ""
        val prefixLocation = source.absoluteIndex
        val suffixLocation = source.absoluteIndex + source.length - suffix.length
        context.codeWriter
            .writeStartMethodInvocation(beginWriteAttributeMethod)
            .writeStringLiteral(node.attributeName ?: "")
            .writeParameterSeparator()
            .writeStringLiteral(node.prefix ?: "")
            .writeParameterSeparator()
            .writeIntegerLiteral(prefixLocation)
            .writeParameterSeparator()
            .writeStringLiteral(suffix)
            .writeParameterSeparator()
            .writeIntegerLiteral(suffixLocation)
            .writeParameterSeparator()
            .writeIntegerLiteral(valuePieceCount)
            .writeEndMethodInvocation()

        context.renderChildren(node)

        context.codeWriter
            .writeStartMethodInvocation(endWriteAttributeMethod)
            .writeEndMethodInvocation()
    }

    open fun writeHtmlAttributeValue(context: CodeRenderingContext, node: HtmlAttributeValueIntermediateNode) {
        val source = node.source!!
        val prefix = node.prefix ?: ""
        val prefixLocation = source.absoluteIndex
        val valueLocation = source.absoluteIndex + prefix.length
        val valueLength = source.length
        context.codeWriter
            .writeStartMethodInvocation(writeAttributeValueMethod)
            .writeStringLiteral(prefix)
            .writeParameterSeparator()
            .writeIntegerLiteral(prefixLocation)
            .writeParameterSeparator()

        for (i in node.children.indices) {
            val child = node.children[i]
            if (child is HtmlIntermediateToken) {
                context.codeWriter.writeStringLiteral(child.content)
            } else {
                // Something else inside the attribute value, like an extension node.
                context.renderNode(child)
            }
        }

        context.codeWriter
            .writeParameterSeparator()
            .writeIntegerLiteral(valueLocation)
            .writeParameterSeparator()
            .writeIntegerLiteral(valueLength)
            .writeParameterSeparator()
            .writeBooleanLiteral(true)
            .writeEndMethodInvocation()
    }

    open fun writeCSharpExpressionAttributeValue(context: CodeRenderingContext, node: CSharpExpressionAttributeValueIntermediateNode) {
        val source = node.source!!
        val prefix = node.prefix ?: ""
        val prefixLocation = source.absoluteIndex
        context.codeWriter
            .writeStartMethodInvocation(writeAttributeValueMethod)
            .writeStringLiteral(prefix)
            .writeParameterSeparator()
            .writeIntegerLiteral(prefixLocation)
            .writeParameterSeparator()

        writeCSharpChildren(node.children, context)

        val valueLocation = source.absoluteIndex + prefix.length
        val valueLength = source.length - prefix.length
        context.codeWriter
            .writeParameterSeparator()
            .writeIntegerLiteral(valueLocation)
            .writeParameterSeparator()
            .writeIntegerLiteral(valueLength)
            .writeParameterSeparator()
            .writeBooleanLiteral(false)
            .writeEndMethodInvocation()
    }

    open fun writeCSharpCodeAttributeValue(context: CodeRenderingContext, node: CSharpCodeAttributeValueIntermediateNode) {
        val valueWriterName = "__razor_attribute_value_writer"

        val source = node.source!!
        val prefix = node.prefix ?: ""
        val prefixLocation = source.absoluteIndex
        val valueLocation = source.absoluteIndex + prefix.length
        val valueLength = source.length - prefix.length
        context.codeWriter
            .writeStartMethodInvocation(writeAttributeValueMethod)
            .writeStringLiteral(prefix)
            .writeParameterSeparator()
            .writeIntegerLiteral(prefixLocation)
            .writeParameterSeparator()

        context.codeWriter.writeStartNewObject(TEMPLATE_TYPE_NAME)

        context.codeWriter.buildAsyncLambda(valueWriterName).use {
            beginWriterScope(context, valueWriterName)
            writeCSharpChildren(node.children, context)
            endWriterScope(context)
        }

        context.codeWriter.writeEndMethodInvocation(false)

        context.codeWriter
            .writeParameterSeparator()
            .writeIntegerLiteral(valueLocation)
            .writeParameterSeparator()
            .writeIntegerLiteral(valueLength)
            .writeParameterSeparator()
            .writeBooleanLiteral(false)
            .writeEndMethodInvocation()
    }

    open fun writeHtmlContent(context: CodeRenderingContext, node: HtmlContentIntermediateNode) {
        val content = StringBuilder()
        for (child in node.children) if (child is HtmlIntermediateToken) content.append(child.content)
        writeHtmlLiteral(context, MAX_STRING_LITERAL_LENGTH, content.toString())
    }

    /**
     * `WriteHtmlLiteral`: the literal in pieces of [maxStringLiteralLength] (Roslyn ran out of memory on huge literals, aspnet/External#54),
     * one character less when the piece would end on a high surrogate, so that a pair is never split.
     */
    fun writeHtmlLiteral(context: CodeRenderingContext, maxStringLiteralLength: Int, literal: String) {
        fun writeLiteral(content: String) {
            context.codeWriter
                .writeStartMethodInvocation(writeHtmlContentMethod)
                .writeStringLiteral(content, context.options.writeHtmlUtf8StringLiterals)
                .writeEndMethodInvocation()
        }

        var rest = literal
        while (rest.length > maxStringLiteralLength) {
            val lastCharBeforeSplit = rest[maxStringLiteralLength - 1]
            val renderCharCount = if (Character.isHighSurrogate(lastCharBeforeSplit)) maxStringLiteralLength - 1 else maxStringLiteralLength
            writeLiteral(rest.substring(0, renderCharCount))
            rest = rest.substring(renderCharCount)
        }
        writeLiteral(rest)
    }

    open fun beginWriterScope(context: CodeRenderingContext, writer: String) {
        context.codeWriter.writeMethodInvocation(pushWriterMethod, writer)
    }

    open fun endWriterScope(context: CodeRenderingContext) {
        context.codeWriter.writeMethodInvocation(popWriterMethod)
    }

    open fun writeComponent(context: CodeRenderingContext, node: ComponentIntermediateNode): Unit = throw componentsNotSupported()
    open fun writeComponentAttribute(context: CodeRenderingContext, node: ComponentAttributeIntermediateNode): Unit = throw componentsNotSupported()
    open fun writeComponentChildContent(context: CodeRenderingContext, node: ComponentChildContentIntermediateNode): Unit = throw componentsNotSupported()
    open fun writeComponentTypeArgument(context: CodeRenderingContext, node: ComponentTypeArgumentIntermediateNode): Unit = throw componentsNotSupported()
    open fun writeComponentTypeInferenceMethod(context: CodeRenderingContext, node: ComponentTypeInferenceMethodIntermediateNode): Unit = throw componentsNotSupported()
    open fun writeMarkupElement(context: CodeRenderingContext, node: MarkupElementIntermediateNode): Unit = throw componentsNotSupported()
    open fun writeMarkupBlock(context: CodeRenderingContext, node: MarkupBlockIntermediateNode): Unit = throw componentsNotSupported()
    open fun writeReferenceCapture(context: CodeRenderingContext, node: ReferenceCaptureIntermediateNode): Unit = throw componentsNotSupported()
    open fun writeSetKey(context: CodeRenderingContext, node: SetKeyIntermediateNode): Unit = throw componentsNotSupported()
    open fun writeSplat(context: CodeRenderingContext, node: SplatIntermediateNode): Unit = throw componentsNotSupported()
    open fun writeRenderMode(context: CodeRenderingContext, node: RenderModeIntermediateNode): Unit = throw componentsNotSupported()
    open fun writeFormName(context: CodeRenderingContext, node: FormNameIntermediateNode): Unit = throw componentsNotSupported()

    private fun componentsNotSupported() = UnsupportedOperationException("This writer does not support components.")

    /** `RenderCSharpCode` (protected static): the tokens with a source mapping each, no pragmas (the design-time and component writers). */
    protected fun renderCSharpCode(context: CodeRenderingContext, node: CSharpCodeIntermediateNode) {
        val writer = context.codeWriter
        for (child in node.children) {
            if (child is CSharpIntermediateToken) {
                context.addSourceMappingFor(child)
                writer.write(child.content)
            } else {
                // Something else inside the expression, like an extension node.
                context.renderNode(child)
            }
        }
    }

    companion object {
        val INSTANCE: IntermediateNodeWriter = IntermediateNodeWriter()

        const val TEMPLATE_TYPE_NAME = "Microsoft.AspNetCore.Mvc.Razor.HelperResult"

        private const val MAX_STRING_LITERAL_LENGTH = 1024
    }
}

/** `LiteralRuntimeNodeWriter` (`LiteralRuntimeNodeWriter.cs`): expressions written with `WriteLiteral`. */
class LiteralRuntimeNodeWriter private constructor() : IntermediateNodeWriter() {
    override val writeCSharpExpressionMethod: String get() = "WriteLiteral"

    companion object {
        val INSTANCE = LiteralRuntimeNodeWriter()
    }
}

/** `TagHelperHtmlAttributeRuntimeNodeWriter` (`TagHelperHtmlAttributeRuntimeNodeWriter.cs`): attribute values of a tag helper's HTML attributes. */
class TagHelperHtmlAttributeRuntimeNodeWriter private constructor() : IntermediateNodeWriter() {
    override val writeAttributeValueMethod: String get() = "AddHtmlAttributeValue"

    companion object {
        val INSTANCE = TagHelperHtmlAttributeRuntimeNodeWriter()
    }
}
