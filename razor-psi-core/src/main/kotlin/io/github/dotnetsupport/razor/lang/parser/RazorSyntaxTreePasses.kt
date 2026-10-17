package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.razor.lang.syntax.CSharpCodeBlockSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpExplicitExpressionSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpImplicitExpressionSyntax
import io.github.dotnetsupport.razor.lang.syntax.CSharpStatementLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.MarkupTextLiteralSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticFactory
import io.github.dotnetsupport.razor.lang.syntax.RazorDirectiveSyntax
import io.github.dotnetsupport.razor.lang.syntax.RazorDirectives
import io.github.dotnetsupport.razor.lang.syntax.RazorList
import io.github.dotnetsupport.razor.lang.syntax.RazorNode
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/**
 * The engine's `IRazorSyntaxTreePass`es that shape the tree the tooling sees (`DefaultRazorSyntaxTreePhase`): the parser's tree is
 * not the final one. `DefaultDirectiveSyntaxTreePass` (order 75): a `@section` inside a `@section` of a view gets RZ2002.
 * `HtmlNodeOptimizationPass` (order 100, `WhitespaceRewriter`): the whitespace a code block starts with before an expression becomes
 * markup of its own (`    @x` on a line of its own: the indent is markup, not C#).
 */
object RazorSyntaxTreePasses {
    fun verifyNestedSections(root: RazorNode, options: RazorParserOptions, source: SeekableTextReader) {
        if (options.fileKind.isComponent) return
        verify(root, 0, 0, source)
    }

    private fun verify(node: RazorNode, position: Int, nested: Int, source: SeekableTextReader) {
        var level = nested
        if (node is RazorDirectiveSyntax && node.directiveDescriptor === RazorDirectives.section) {
            level++
            if (level > 1) {
                val errorLength = 1 + RazorDirectives.section.directive.length
                node.diagnostics = node.diagnostics + RazorDiagnosticFactory.sectionsCannotBeNested(SourceSpan(source.locationOf(position), errorLength))
            }
        }
        var offset = position
        for (i in 0 until node.slotCount) {
            val child = node.slot(i) ?: continue
            verify(child, offset, level, source)
            offset += child.width
        }
    }

    fun rewriteWhitespace(node: RazorNode) {
        if (node is RazorList) {
            var i = 0
            while (i < node.nodes.size) {
                val child = node.nodes[i]
                val rewritten = if (child is CSharpCodeBlockSyntax) splitLeadingWhitespace(child) else null
                if (rewritten != null) node.replace(i, rewritten)
                i++
            }
        }
        for (i in 0 until node.slotCount) node.slot(i)?.let(::rewriteWhitespace)
    }

    /** `WhitespaceRewriter.TryRewriteWhitespace`: `[whitespace literal, expression, …]` → a markup literal of the whitespace and the rest. */
    private fun splitLeadingWhitespace(codeBlock: CSharpCodeBlockSyntax): List<RazorNode>? {
        val children = codeBlock.children.nodes
        if (children.size < 2) return null
        val literal = children[0] as? CSharpStatementLiteralSyntax ?: return null
        val second = children[1]
        if (second !is CSharpExplicitExpressionSyntax && second !is CSharpImplicitExpressionSyntax) return null
        if (literal.tokens().any { it.content.isNotBlank() }) return null
        val whitespace = MarkupTextLiteralSyntax(literal.literalTokens)
        val rest = CSharpCodeBlockSyntax(RazorList(children.subList(1, children.size)))
        return listOf(whitespace, rest)
    }
}
