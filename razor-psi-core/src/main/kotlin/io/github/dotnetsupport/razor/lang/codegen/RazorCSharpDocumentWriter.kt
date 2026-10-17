package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ClassDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentChildContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentTypeArgumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentTypeInferenceMethodIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ExtensionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.FieldDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.FormNameIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeVisitor
import io.github.dotnetsupport.razor.lang.intermediate.MarkupBlockIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MarkupElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MethodDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.NamespaceDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.PropertyDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ReferenceCaptureIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.RenderModeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.SetKeyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.SplatIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UsingDirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic

/**
 * `RazorCSharpDocument` (`RazorCSharpDocument.cs`): the generated C# with its diagnostics, source mappings (by the generated span and by
 * the original one) and line pragmas; [isStubDocument] marks the bodiless type shell of a fallback component's declaration document.
 */
class RazorCSharpDocument(
    val codeDocument: RazorCodeDocument,
    val text: String,
    diagnostics: List<RazorDiagnostic>?,
    sourceMappings: List<SourceMapping>? = null,
    linePragmas: List<LinePragma>? = null,
    val isDeclarationDocument: Boolean = false,
    val isStubDocument: Boolean = false,
) {
    val diagnostics: List<RazorDiagnostic> = diagnostics ?: emptyList()
    val sourceMappingsSortedByGenerated: List<SourceMapping>
    val sourceMappingsSortedByOriginal: List<SourceMapping>
    val linePragmas: List<LinePragma> = linePragmas ?: emptyList()

    /** `GeneratedCode`: the text as a string. */
    val generatedCode: String get() = text

    init {
        var byGenerated = sourceMappings ?: emptyList()
        // The writer gives them in the order of the generated code; the compiler asserts it and sorts when they are not.
        for (i in 0 until byGenerated.size - 1) {
            if (byGenerated[i].generatedSpan.compareByStartThenLength(byGenerated[i + 1].generatedSpan) > 0) {
                byGenerated = byGenerated.sortedWith { m1, m2 -> m1.generatedSpan.compareByStartThenLength(m2.generatedSpan) }
                break
            }
        }
        sourceMappingsSortedByGenerated = byGenerated
        // A stable sort: the compiler's ImmutableArray.Sort is not, mappings of the same original span may come in another order there.
        sourceMappingsSortedByOriginal = byGenerated.sortedWith { m1, m2 -> m1.originalSpan.compareByStartThenLength(m2.originalSpan) }
    }
}

/**
 * `RazorCSharpDocumentWriter` (`RazorCSharpDocumentWriter.cs`): renders a [DocumentIntermediateNode] tree to a [RazorCSharpDocument] with
 * the target of the document; both the implementation and the declaration lowering call [write] with the tree they want lowered.
 */
object RazorCSharpDocumentWriter {
    /** `{8829d00f-…}`: SHA-256, the algorithm of `#pragma checksum` the debugger understands (with SHA-1, which a document never has here). */
    private const val SHA256_ALGORITHM_ID = "{8829d00f-11b8-4213-878b-770e8597ac16}"

    /** [checkCanceled] stands for the compiler's `CancellationToken`, checked on every node. */
    fun write(
        documentNode: DocumentIntermediateNode, codeDocument: RazorCodeDocument, reportDiagnostics: Boolean = true, isDeclarationDocument: Boolean = false,
        isStubDocument: Boolean = false, checkCanceled: () -> Unit = {},
    ): RazorCSharpDocument {
        val codeTarget = documentNode.target ?: throw IllegalStateException("The document node has no code target.")

        CodeRenderingContext(
            codeTarget.createNodeWriter(), codeDocument.source, documentNode, documentNode.options ?: codeDocument.codeGenerationOptions, reportDiagnostics,
        ).use { context ->
            context.setVisitor(Visitor(context, codeTarget, checkCanceled))
            context.visitor.visitDocument(documentNode)

            val text = context.codeWriter.getText()
            return RazorCSharpDocument(
                codeDocument, text, context.getDiagnostics(), context.getSourceMappings(), context.getLinePragmas(), isDeclarationDocument, isStubDocument,
            )
        }
    }

    /**
     * `CloneContainer`: a node with the metadata of [node] (kind, options, target, source) and no children, for the declaration and the
     * implementation phases to build spines that share the leaves of the original tree by reference.
     */
    fun cloneContainer(node: DocumentIntermediateNode): DocumentIntermediateNode = DocumentIntermediateNode().also {
        it.documentKind = node.documentKind
        it.options = node.options
        it.target = node.target
        it.source = node.source
        it.isImported = node.isImported
    }

    fun cloneContainer(node: NamespaceDeclarationIntermediateNode): NamespaceDeclarationIntermediateNode = NamespaceDeclarationIntermediateNode().also {
        it.name = node.name
        it.isPrimaryNamespace = node.isPrimaryNamespace
        it.isGenericTyped = node.isGenericTyped
        it.source = node.source
        it.isImported = node.isImported
    }

    fun cloneContainer(node: ClassDeclarationIntermediateNode): ClassDeclarationIntermediateNode = ClassDeclarationIntermediateNode().also {
        it.name = node.name
        it.baseType = node.baseType
        it.modifiers = node.modifiers
        it.interfaces = node.interfaces
        it.typeParameters = node.typeParameters
        it.isPrimaryClass = node.isPrimaryClass
        it.nullableContext = node.nullableContext
        it.source = node.source
        it.isImported = node.isImported
    }

    private class Visitor(private val context: CodeRenderingContext, private val codeTarget: CodeTarget, private val checkCanceled: () -> Unit) :
        IntermediateNodeVisitor() {
        private val codeWriter: CodeWriter get() = context.codeWriter
        private val nodeWriter: IntermediateNodeWriter get() = context.nodeWriter
        private val options: RazorCodeGenerationOptions get() = context.options

        override fun visitDocument(node: DocumentIntermediateNode) {
            checkCanceled()
            val writer = codeWriter

            if (!options.suppressChecksum) {
                // Only the algorithms the debugger understands: the source document's checksum is SHA-256.
                val sourceDocument = context.sourceDocument
                val checksum = sourceDocument.checksum
                val filePath = sourceDocument.filePath ?: throw IllegalStateException("The source document has no file path.")
                if (checksum.isNotEmpty()) {
                    writer.write("#pragma checksum \"")
                        .writeFilePath(filePath, ensurePathBackslashes = false)
                        .writeLineParts("\" \"", SHA256_ALGORITHM_ID, "\" \"", checksum, "\"")
                }
            }

            writer
                .writeLine("// <auto-generated/>")
                .writeLine("#pragma warning disable 1591")

            visitDefault(node)

            writer.writeLine("#pragma warning restore 1591")
        }

        override fun visitUsingDirective(node: UsingDirectiveIntermediateNode) {
            checkCanceled()
            nodeWriter.writeUsingDirective(context, node)
        }

        override fun visitNamespaceDeclaration(node: NamespaceDeclarationIntermediateNode) {
            checkCanceled()
            val writer = codeWriter

            writer.buildNamespace(node.name, node.source, context).use {
                val hasUsingDirectives = node.children.any { it is UsingDirectiveIntermediateNode }
                // Tooling needs a line directive before the usings to offer a new one; the last using writes the hidden one after itself.
                if (hasUsingDirectives) writer.writeLine("#line default") else writer.writeLine("#line hidden")
                visitDefault(node)
            }
        }

        override fun visitClassDeclaration(node: ClassDeclarationIntermediateNode) {
            checkCanceled()
            codeWriter.buildClassDeclaration(
                node.modifiers, node.name, node.baseType, node.interfaces, node.typeParameters, context,
                useNullableContext = !options.suppressNullabilityEnforcement && node.nullableContext,
            ).use { visitDefault(node) }
        }

        override fun visitMethodDeclaration(node: MethodDeclarationIntermediateNode) {
            checkCanceled()
            val writer = codeWriter

            writer.writeLine("#pragma warning disable 1998")
            codeWriter.buildMethodDeclaration(node.modifiers, node.returnType, node.name, node.parameters).use { visitDefault(node) }
            writer.writeLine("#pragma warning restore 1998")
        }

        override fun visitFieldDeclaration(node: FieldDeclarationIntermediateNode) {
            checkCanceled()
            codeWriter.writeField(node.suppressWarnings, node.modifiers, node.type, node.name)
        }

        override fun visitPropertyDeclaration(node: PropertyDeclarationIntermediateNode) {
            checkCanceled()
            codeWriter.writePropertyDeclaration(node.modifiers, node.type, node.name, node.expressionBody, context)
        }

        override fun visitExtension(node: ExtensionIntermediateNode) {
            checkCanceled()
            node.writeNode(codeTarget, context)
        }

        override fun visitCSharpExpression(node: CSharpExpressionIntermediateNode) {
            checkCanceled()
            nodeWriter.writeCSharpExpression(context, node)
        }

        override fun visitCSharpCode(node: CSharpCodeIntermediateNode) {
            checkCanceled()
            nodeWriter.writeCSharpCode(context, node)
        }

        override fun visitHtmlAttribute(node: HtmlAttributeIntermediateNode) {
            checkCanceled()
            nodeWriter.writeHtmlAttribute(context, node)
        }

        override fun visitHtmlAttributeValue(node: HtmlAttributeValueIntermediateNode) {
            checkCanceled()
            nodeWriter.writeHtmlAttributeValue(context, node)
        }

        override fun visitCSharpExpressionAttributeValue(node: CSharpExpressionAttributeValueIntermediateNode) {
            checkCanceled()
            nodeWriter.writeCSharpExpressionAttributeValue(context, node)
        }

        override fun visitCSharpCodeAttributeValue(node: CSharpCodeAttributeValueIntermediateNode) {
            checkCanceled()
            nodeWriter.writeCSharpCodeAttributeValue(context, node)
        }

        override fun visitHtml(node: HtmlContentIntermediateNode) {
            checkCanceled()
            nodeWriter.writeHtmlContent(context, node)
        }

        override fun visitTagHelper(node: TagHelperIntermediateNode) {
            checkCanceled()
            visitDefault(node)
        }

        override fun visitComponent(node: ComponentIntermediateNode) {
            checkCanceled()
            nodeWriter.writeComponent(context, node)
        }

        override fun visitComponentAttribute(node: ComponentAttributeIntermediateNode) {
            checkCanceled()
            nodeWriter.writeComponentAttribute(context, node)
        }

        override fun visitComponentChildContent(node: ComponentChildContentIntermediateNode) {
            checkCanceled()
            nodeWriter.writeComponentChildContent(context, node)
        }

        override fun visitComponentTypeArgument(node: ComponentTypeArgumentIntermediateNode) {
            checkCanceled()
            nodeWriter.writeComponentTypeArgument(context, node)
        }

        override fun visitComponentTypeInferenceMethod(node: ComponentTypeInferenceMethodIntermediateNode) {
            checkCanceled()
            nodeWriter.writeComponentTypeInferenceMethod(context, node)
        }

        override fun visitMarkupElement(node: MarkupElementIntermediateNode) {
            checkCanceled()
            nodeWriter.writeMarkupElement(context, node)
        }

        override fun visitMarkupBlock(node: MarkupBlockIntermediateNode) {
            checkCanceled()
            nodeWriter.writeMarkupBlock(context, node)
        }

        override fun visitReferenceCapture(node: ReferenceCaptureIntermediateNode) {
            checkCanceled()
            nodeWriter.writeReferenceCapture(context, node)
        }

        override fun visitSetKey(node: SetKeyIntermediateNode) {
            checkCanceled()
            nodeWriter.writeSetKey(context, node)
        }

        override fun visitSplat(node: SplatIntermediateNode) {
            checkCanceled()
            nodeWriter.writeSplat(context, node)
        }

        override fun visitRenderMode(node: RenderModeIntermediateNode) {
            checkCanceled()
            nodeWriter.writeRenderMode(context, node)
        }

        override fun visitFormName(node: FormNameIntermediateNode) {
            checkCanceled()
            nodeWriter.writeFormName(context, node)
        }

        override fun visitDefault(node: IntermediateNode) {
            checkCanceled()
            context.renderChildren(node)
        }
    }
}
