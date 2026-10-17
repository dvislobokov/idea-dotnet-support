package io.github.dotnetsupport.razor.lang.syntax

/** A walk over the tree with absolute positions, the base of the serializer and of the host's bridges to PSI. */
abstract class RazorSyntaxWalker {
    fun visit(node: RazorNode, position: Int = 0) {
        if (node is RazorToken) {
            visitToken(node, position)
            return
        }
        if (node.isList) {
            var offset = position
            for (i in 0 until node.slotCount) {
                val child = node.slot(i) ?: continue
                visit(child, offset)
                offset += child.width
            }
            return
        }
        if (visitNode(node as RazorSyntaxNode, position)) {
            var offset = position
            for (i in 0 until node.slotCount) {
                val child = node.slot(i) ?: continue
                visit(child, offset)
                offset += child.width
            }
            leaveNode(node, position)
        }
    }

    /** Returns false to skip the children. */
    protected abstract fun visitNode(node: RazorSyntaxNode, position: Int): Boolean
    protected open fun leaveNode(node: RazorSyntaxNode, position: Int) {}
    protected abstract fun visitToken(token: RazorToken, position: Int)
}

/**
 * `SyntaxSerializer`: the tree in the format of the compiler's test baselines (`.stree.txt`) and of `razordump tree` — one line per
 * node (`Kind - [start..end) - Width: n` plus the content of literal nodes, the chunk generator, the edit handler, the directive) and
 * per token (`Kind;[content];diagnostics`). A line break inside content is written `LF` (the compiler replaces its line break so).
 */
class SyntaxSerializer private constructor(private val out: StringBuilder, private val newLine: String) : RazorSyntaxWalker() {
    private var depth = 0
    private var visitedRoot = false

    override fun visitNode(node: RazorSyntaxNode, position: Int): Boolean {
        indent()
        out.append(node.kind.name)
        out.append(SEPARATOR).append("[").append(position).append("..").append(position + node.width).append(")").append(SEPARATOR).append("Width: ").append(node.width)
        when (node) {
            is BaseRazorDirectiveSyntax -> {
                val descriptor = node.directiveDescriptor
                if (descriptor != null) {
                    out.append(SEPARATOR).append("Directive:{").append(descriptor.directive).append(';').append(descriptor.kind).append(';').append(descriptor.usage).append('}')
                    if (node.diagnostics.isNotEmpty()) out.append(" [").append(node.diagnostics.joinToString(", ")).append(']')
                }
            }
            is MarkupStartTagSyntax -> if (node.isMarkupTransition) out.append(SEPARATOR).append("MarkupTransition")
            is MarkupEndTagSyntax -> if (node.isMarkupTransition) out.append(SEPARATOR).append("MarkupTransition")
            else -> {}
        }
        if (shouldDisplayNodeContent(node.kind)) out.append(SEPARATOR).append('[').append(value(node.text())).append(']')
        chunkGeneratorOf(node)?.let { out.append(SEPARATOR).append("Gen<").append(value(it.toString())).append('>') }
        editHandlerOf(node)?.let { out.append(SEPARATOR).append(value(it.toString())) }
        if (!visitedRoot) {
            out.append(SEPARATOR).append('[').append(value(node.text())).append(']')
            visitedRoot = true
        }
        out.append(newLine)
        depth++
        return true
    }

    override fun leaveNode(node: RazorSyntaxNode, position: Int) {
        depth--
    }

    override fun visitToken(token: RazorToken, position: Int) {
        indent()
        val content = if (token.isMissing) "<Missing>" else token.content
        out.append(token.kind.name).append(";[").append(value(content)).append("];").append(token.diagnostics.joinToString(", ")).append(newLine)
    }

    private fun indent() {
        repeat(depth * INDENT_SIZE) { out.append(' ') }
    }

    private fun value(text: String): String = text.replace("\r\n", "LF").replace("\n", "LF")

    companion object {
        private const val INDENT_SIZE = 4
        private const val SEPARATOR = " - "

        fun serialize(node: RazorNode, newLine: String = "\n"): String {
            val out = StringBuilder()
            SyntaxSerializer(out, newLine).visit(node)
            return out.toString()
        }

        private fun shouldDisplayNodeContent(kind: RazorSyntaxKind): Boolean = when (kind) {
            RazorSyntaxKind.MarkupTextLiteral, RazorSyntaxKind.MarkupEphemeralTextLiteral, RazorSyntaxKind.MarkupStartTag, RazorSyntaxKind.MarkupEndTag,
            RazorSyntaxKind.MarkupTagHelperStartTag, RazorSyntaxKind.MarkupTagHelperEndTag, RazorSyntaxKind.MarkupAttributeBlock,
            RazorSyntaxKind.MarkupMinimizedAttributeBlock, RazorSyntaxKind.MarkupTagHelperAttribute, RazorSyntaxKind.MarkupMinimizedTagHelperAttribute,
            RazorSyntaxKind.MarkupLiteralAttributeValue, RazorSyntaxKind.MarkupDynamicAttributeValue, RazorSyntaxKind.CSharpStatementLiteral,
            RazorSyntaxKind.CSharpExpressionLiteral, RazorSyntaxKind.CSharpEphemeralTextLiteral, RazorSyntaxKind.UnclassifiedTextLiteral -> true
            else -> false
        }

        /** `GetChunkGenerator`: the generated nodes with a `ChunkGenerator` field. */
        fun chunkGeneratorOf(node: RazorSyntaxNode): ChunkGenerator? = when (node) {
            is RazorMetaCodeSyntax -> node.chunkGenerator
            is UnclassifiedTextLiteralSyntax -> node.chunkGenerator
            is MarkupTransitionSyntax -> node.chunkGenerator
            is MarkupTextLiteralSyntax -> node.chunkGenerator
            is MarkupEphemeralTextLiteralSyntax -> node.chunkGenerator
            is BaseMarkupStartTagSyntax -> node.chunkGenerator
            is BaseMarkupEndTagSyntax -> node.chunkGenerator
            is CSharpTransitionSyntax -> node.chunkGenerator
            is CSharpStatementLiteralSyntax -> node.chunkGenerator
            is CSharpExpressionLiteralSyntax -> node.chunkGenerator
            is CSharpEphemeralTextLiteralSyntax -> node.chunkGenerator
            else -> null
        }

        fun editHandlerOf(node: RazorSyntaxNode): SpanEditHandler? = when (node) {
            is RazorMetaCodeSyntax -> node.editHandler
            is UnclassifiedTextLiteralSyntax -> node.editHandler
            is MarkupTransitionSyntax -> node.editHandler
            is MarkupTextLiteralSyntax -> node.editHandler
            is MarkupEphemeralTextLiteralSyntax -> node.editHandler
            is BaseMarkupStartTagSyntax -> node.editHandler
            is BaseMarkupEndTagSyntax -> node.editHandler
            is CSharpTransitionSyntax -> node.editHandler
            is CSharpStatementLiteralSyntax -> node.editHandler
            is CSharpExpressionLiteralSyntax -> node.editHandler
            is CSharpEphemeralTextLiteralSyntax -> node.editHandler
            else -> null
        }
    }
}
