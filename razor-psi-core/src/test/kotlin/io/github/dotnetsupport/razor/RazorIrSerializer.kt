package io.github.dotnetsupport.razor

import io.github.dotnetsupport.razor.lang.codegen.components.RouteAttributeExtensionNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperCreateIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperExecuteIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperHtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperPropertyIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperRuntimeIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.PreallocatedTagHelperHtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.PreallocatedTagHelperHtmlAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.PreallocatedTagHelperPropertyIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.PreallocatedTagHelperPropertyValueIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.SectionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.ClassDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentChildContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentTypeArgumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentTypeInferenceMethodIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DirectiveTokenIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ExtensionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.FieldDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeVisitor
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeWalker
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.MalformedDirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MarkupBlockIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MarkupElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MethodDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.NamespaceDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.PropertyDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ReferenceCaptureIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.SetKeyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperDirectiveAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperDirectiveAttributeParameterIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperHtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperPropertyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UsingDirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/**
 * The compiler tests' `IntermediateNodeSerializer` (`Microsoft.AspNetCore.Razor.Test.Common/Language/IntegrationTests/`): the `.ir.txt`
 * baselines — every node on its line, four spaces per depth, `Name - (source) - content - …` as [RazorIrWriter] writes it. Line breaks LF
 * (the C# writes `Environment.NewLine`; the gates compare LF).
 */
object RazorIrSerializer {
    fun serialize(node: IntermediateNode): String {
        val out = StringBuilder()
        Walker(out).visit(node)
        return out.toString()
    }

    private class Walker(private val out: StringBuilder) : IntermediateNodeWalker() {
        private val visitor = RazorIrWriter(out)

        override fun visitDefault(node: IntermediateNode) {
            visitor.visit(node)
            out.append('\n')

            visitor.depth++
            super.visitDefault(node)
            visitor.depth--
        }
    }
}

/**
 * The compiler tests' `IntermediateNodeWriter`: one node, shallow. The C# implements `IExtensionIntermediateNodeVisitor<T>` for
 * `SectionIntermediateNode` and `RouteAttributeExtensionNode`; a Kotlin class cannot implement one generic interface twice, so both are
 * cases of [visitExtension] (the node's `accept` falls back to `visitExtension` for a visitor that does not know it — the same output).
 */
class RazorIrWriter(private val out: StringBuilder) : IntermediateNodeVisitor() {
    var depth: Int = 0

    override fun visitDefault(node: IntermediateNode) = writeBasicNode(node)

    override fun visitClassDeclaration(node: ClassDeclarationIntermediateNode) {
        val baseType = node.baseType
        val entries = arrayListOf(
            node.modifiers.joinToString(" "),
            node.name,
            if (baseType != null) "${baseType.baseType.content}${baseType.greaterThan?.content ?: ""}${baseType.modelType?.content ?: ""}${baseType.lessThan?.content ?: ""}" else "",
            node.interfaces.joinToString(", ") { it.content },
        )

        // Avoid adding the type parameters to the baseline if they aren't present.
        if (node.typeParameters.isNotEmpty()) entries.add(node.typeParameters.joinToString(", ") { it.name.content })

        writeContentNode(node, *entries.toTypedArray())
    }

    override fun visitCSharpExpressionAttributeValue(node: CSharpExpressionAttributeValueIntermediateNode) = writeContentNode(node, node.prefix)

    override fun visitCSharpCodeAttributeValue(node: CSharpCodeAttributeValueIntermediateNode) = writeContentNode(node, node.prefix)

    override fun visitToken(node: IntermediateToken) {
        val kind = when (node) {
            is CSharpIntermediateToken -> "CSharp"
            is HtmlIntermediateToken -> "Html"
            else -> "Unknown"
        }
        writeContentNode(node, kind, node.content)
    }

    override fun visitMalformedDirective(node: MalformedDirectiveIntermediateNode) = writeContentNode(node, node.directiveName)

    override fun visitDirective(node: DirectiveIntermediateNode) = writeContentNode(node, node.directiveName)

    override fun visitDirectiveToken(node: DirectiveTokenIntermediateNode) = writeContentNode(node, node.content)

    override fun visitFieldDeclaration(node: FieldDeclarationIntermediateNode) = writeContentNode(node, node.modifiers.joinToString(" "), node.type, node.name)

    override fun visitHtmlAttribute(node: HtmlAttributeIntermediateNode) = writeContentNode(node, node.prefix, node.suffix)

    override fun visitHtmlAttributeValue(node: HtmlAttributeValueIntermediateNode) = writeContentNode(node, node.prefix)

    override fun visitNamespaceDeclaration(node: NamespaceDeclarationIntermediateNode) = writeContentNode(node, node.name)

    override fun visitMethodDeclaration(node: MethodDeclarationIntermediateNode) = writeContentNode(node, node.modifiers.joinToString(" "), node.returnType, node.name)

    override fun visitUsingDirective(node: UsingDirectiveIntermediateNode) = writeContentNode(node, node.content)

    override fun visitTagHelper(node: TagHelperIntermediateNode) = writeContentNode(node, node.tagName, "TagMode.${node.tagMode}")

    override fun visitTagHelperProperty(node: TagHelperPropertyIntermediateNode) =
        writeContentNode(node, node.attributeName, node.boundAttribute.displayName, "HtmlAttributeValueStyle.${node.attributeStructure}")

    override fun visitTagHelperHtmlAttribute(node: TagHelperHtmlAttributeIntermediateNode) =
        writeContentNode(node, node.attributeName, "HtmlAttributeValueStyle.${node.attributeStructure}")

    override fun visitTagHelperDirectiveAttribute(node: TagHelperDirectiveAttributeIntermediateNode) =
        writeContentNode(node, node.attributeName, node.boundAttribute.displayName, "HtmlAttributeValueStyle.${node.attributeStructure}")

    override fun visitTagHelperDirectiveAttributeParameter(node: TagHelperDirectiveAttributeParameterIntermediateNode) =
        writeContentNode(node, node.attributeName, "HtmlAttributeValueStyle.${node.attributeStructure}")

    override fun visitComponent(node: ComponentIntermediateNode) = writeContentNode(node, node.tagName)

    override fun visitComponentAttribute(node: ComponentAttributeIntermediateNode) =
        writeContentNode(node, node.attributeName, node.propertyName, "AttributeStructure.${node.attributeStructure}")

    override fun visitComponentChildContent(node: ComponentChildContentIntermediateNode) = writeContentNode(node, node.attributeName, node.parameterName)

    override fun visitComponentTypeArgument(node: ComponentTypeArgumentIntermediateNode) = writeContentNode(node, node.typeParameterName)

    override fun visitComponentTypeInferenceMethod(node: ComponentTypeInferenceMethodIntermediateNode) = writeContentNode(node, node.fullTypeName, node.methodName)

    override fun visitMarkupElement(node: MarkupElementIntermediateNode) = writeContentNode(node, node.tagName)

    override fun visitMarkupBlock(node: MarkupBlockIntermediateNode) = writeContentNode(node, node.content)

    override fun visitReferenceCapture(node: ReferenceCaptureIntermediateNode) = writeContentNode(node, node.identifierToken.content)

    override fun visitSetKey(node: SetKeyIntermediateNode) = writeContentNode(node, node.keyValueToken.content)

    override fun visitPropertyDeclaration(node: PropertyDeclarationIntermediateNode) = writeContentNode(node, node.type.content, node.name, node.expressionBody)

    override fun visitExtension(node: ExtensionIntermediateNode) {
        when (node) {
            is RouteAttributeExtensionNode -> writeContentNode(node, node.template)
            is SectionIntermediateNode -> writeContentNode(node, node.sectionName)
            is PreallocatedTagHelperHtmlAttributeIntermediateNode -> writeContentNode(node, node.variableName)
            is PreallocatedTagHelperHtmlAttributeValueIntermediateNode ->
                writeContentNode(node, node.variableName, node.attributeName, node.value, "HtmlAttributeValueStyle.${node.attributeStructure}")
            is PreallocatedTagHelperPropertyIntermediateNode -> writeContentNode(node, node.variableName, node.attributeName, node.propertyName)
            is PreallocatedTagHelperPropertyValueIntermediateNode ->
                writeContentNode(node, node.variableName, node.attributeName, node.value, "HtmlAttributeValueStyle.${node.attributeStructure}")
            is DefaultTagHelperCreateIntermediateNode -> writeContentNode(node, node.typeName)
            is DefaultTagHelperExecuteIntermediateNode -> writeBasicNode(node)
            is DefaultTagHelperHtmlAttributeIntermediateNode -> writeContentNode(node, node.attributeName, "HtmlAttributeValueStyle.${node.attributeStructure}")
            is DefaultTagHelperPropertyIntermediateNode ->
                writeContentNode(node, node.attributeName, node.boundAttribute?.displayName, "HtmlAttributeValueStyle.${node.attributeStructure}")
            is DefaultTagHelperRuntimeIntermediateNode -> writeBasicNode(node)
            else -> super.visitExtension(node)
        }
    }

    private fun writeBasicNode(node: IntermediateNode) {
        writeIndent()
        writeName(node)
        writeSeparator()
        writeSourceRange(node)
    }

    private fun writeContentNode(node: IntermediateNode, vararg content: String?) {
        writeIndent()
        writeName(node)
        writeSeparator()
        writeSourceRange(node)

        for (c in content) {
            writeSeparator()
            writeContent(c)
        }
    }

    private fun writeIndent() {
        repeat(depth * 4) { out.append(' ') }
    }

    private fun writeSeparator() {
        out.append(" - ")
    }

    private fun writeName(node: IntermediateNode) {
        val typeName = node.javaClass.simpleName
        when {
            typeName.endsWith("IntermediateNode") -> out.append(typeName, 0, typeName.length - "IntermediateNode".length)
            node is IntermediateToken -> out.append(if (node.isLazy) "LazyIntermediateToken" else "IntermediateToken")
            else -> out.append(typeName)
        }
    }

    private fun writeSourceRange(node: IntermediateNode) {
        node.source?.let { writeSourceRange(it) }
    }

    private fun writeSourceRange(sourceRange: SourceSpan) {
        out.append('(').append(sourceRange.absoluteIndex).append(':').append(sourceRange.lineIndex).append(',').append(sourceRange.characterIndex)
        out.append(" [").append(sourceRange.length).append("] ")
        val filePath = sourceRange.filePath
        if (filePath != null) out.append(filePath.substring(filePath.lastIndexOf('/') + 1))
        out.append(')')
    }

    /** Newlines escaped (the `\r` dropped) so the IR compares line by line; the separator ` - ` escaped as `\-`. */
    private fun writeContent(content: String?) {
        if (content == null) return
        out.append(content.replace("\r", "").replace("\n", "\\n").replace(" - ", "\\-"))
    }
}

/** The compiler tests' `RazorDiagnosticSerializer`: `RazorDiagnostic.ToString()`, `path(line,character): Severity RZ0000: message` (1-based). */
object RazorDiagnosticSerializer {
    fun serialize(diagnostic: RazorDiagnostic): String {
        val span = diagnostic.span
        val severity = diagnostic.severity.name.lowercase().replaceFirstChar { it.uppercaseChar() }
        return "${span.filePath ?: ""}(${span.lineIndex + 1},${span.characterIndex + 1}): $severity ${diagnostic.id}: ${diagnostic.message}"
    }

    /** The `.diagnostics.txt` text: one diagnostic per line (the tests write CRLF; LF here), the line breaks of a message normalized. */
    fun serializeAll(diagnostics: List<RazorDiagnostic>): String = diagnostics.joinToString("") { serialize(it).replace("\r\n", "\n") + "\n" }
}
