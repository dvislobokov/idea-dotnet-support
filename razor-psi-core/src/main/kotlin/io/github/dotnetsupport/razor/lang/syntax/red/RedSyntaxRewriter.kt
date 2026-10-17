package io.github.dotnetsupport.razor.lang.syntax.red

import io.github.dotnetsupport.razor.lang.syntax.RazorList
import io.github.dotnetsupport.razor.lang.syntax.RazorNode
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxNode
import io.github.dotnetsupport.razor.lang.syntax.RazorToken
import io.github.dotnetsupport.razor.lang.syntax.CSharpCodeBlockSyntax as GCSharpCodeBlock
import io.github.dotnetsupport.razor.lang.syntax.CSharpEphemeralTextLiteralSyntax as GCSharpEphemeralTextLiteral
import io.github.dotnetsupport.razor.lang.syntax.CSharpExplicitExpressionBodySyntax as GCSharpExplicitExpressionBody
import io.github.dotnetsupport.razor.lang.syntax.CSharpExplicitExpressionSyntax as GCSharpExplicitExpression
import io.github.dotnetsupport.razor.lang.syntax.CSharpExpressionLiteralSyntax as GCSharpExpressionLiteral
import io.github.dotnetsupport.razor.lang.syntax.CSharpImplicitExpressionBodySyntax as GCSharpImplicitExpressionBody
import io.github.dotnetsupport.razor.lang.syntax.CSharpImplicitExpressionSyntax as GCSharpImplicitExpression
import io.github.dotnetsupport.razor.lang.syntax.CSharpStatementBodySyntax as GCSharpStatementBody
import io.github.dotnetsupport.razor.lang.syntax.CSharpStatementLiteralSyntax as GCSharpStatementLiteral
import io.github.dotnetsupport.razor.lang.syntax.CSharpStatementSyntax as GCSharpStatement
import io.github.dotnetsupport.razor.lang.syntax.CSharpSyntaxNode as GCSharpSyntaxNode
import io.github.dotnetsupport.razor.lang.syntax.CSharpTemplateBlockSyntax as GCSharpTemplateBlock
import io.github.dotnetsupport.razor.lang.syntax.CSharpTransitionSyntax as GCSharpTransition
import io.github.dotnetsupport.razor.lang.syntax.GenericBlockSyntax as GGenericBlock
import io.github.dotnetsupport.razor.lang.syntax.MarkupAttributeBlockSyntax as GMarkupAttributeBlock
import io.github.dotnetsupport.razor.lang.syntax.MarkupBlockSyntax as GMarkupBlock
import io.github.dotnetsupport.razor.lang.syntax.MarkupCommentBlockSyntax as GMarkupCommentBlock
import io.github.dotnetsupport.razor.lang.syntax.MarkupDynamicAttributeValueSyntax as GMarkupDynamicAttributeValue
import io.github.dotnetsupport.razor.lang.syntax.MarkupElementSyntax as GMarkupElement
import io.github.dotnetsupport.razor.lang.syntax.MarkupEndTagSyntax as GMarkupEndTag
import io.github.dotnetsupport.razor.lang.syntax.MarkupEphemeralTextLiteralSyntax as GMarkupEphemeralTextLiteral
import io.github.dotnetsupport.razor.lang.syntax.MarkupLiteralAttributeValueSyntax as GMarkupLiteralAttributeValue
import io.github.dotnetsupport.razor.lang.syntax.MarkupMinimizedAttributeBlockSyntax as GMarkupMinimizedAttributeBlock
import io.github.dotnetsupport.razor.lang.syntax.MarkupMinimizedTagHelperAttributeSyntax as GMarkupMinimizedTagHelperAttribute
import io.github.dotnetsupport.razor.lang.syntax.MarkupMinimizedTagHelperDirectiveAttributeSyntax as GMarkupMinimizedTagHelperDirectiveAttribute
import io.github.dotnetsupport.razor.lang.syntax.MarkupMiscAttributeContentSyntax as GMarkupMiscAttributeContent
import io.github.dotnetsupport.razor.lang.syntax.MarkupStartTagSyntax as GMarkupStartTag
import io.github.dotnetsupport.razor.lang.syntax.MarkupTagHelperAttributeSyntax as GMarkupTagHelperAttribute
import io.github.dotnetsupport.razor.lang.syntax.MarkupTagHelperAttributeValueSyntax as GMarkupTagHelperAttributeValue
import io.github.dotnetsupport.razor.lang.syntax.MarkupTagHelperDirectiveAttributeSyntax as GMarkupTagHelperDirectiveAttribute
import io.github.dotnetsupport.razor.lang.syntax.MarkupTagHelperElementSyntax as GMarkupTagHelperElement
import io.github.dotnetsupport.razor.lang.syntax.MarkupTagHelperEndTagSyntax as GMarkupTagHelperEndTag
import io.github.dotnetsupport.razor.lang.syntax.MarkupTagHelperStartTagSyntax as GMarkupTagHelperStartTag
import io.github.dotnetsupport.razor.lang.syntax.MarkupTextLiteralSyntax as GMarkupTextLiteral
import io.github.dotnetsupport.razor.lang.syntax.MarkupTransitionSyntax as GMarkupTransition
import io.github.dotnetsupport.razor.lang.syntax.RazorBlockSyntax as GRazorBlock
import io.github.dotnetsupport.razor.lang.syntax.RazorCommentBlockSyntax as GRazorCommentBlock
import io.github.dotnetsupport.razor.lang.syntax.RazorDirectiveBodySyntax as GRazorDirectiveBody
import io.github.dotnetsupport.razor.lang.syntax.RazorDirectiveSyntax as GRazorDirective
import io.github.dotnetsupport.razor.lang.syntax.RazorDocumentSyntax as GRazorDocument
import io.github.dotnetsupport.razor.lang.syntax.RazorDocumentationDirectiveSyntax as GRazorDocumentationDirective
import io.github.dotnetsupport.razor.lang.syntax.RazorMetaCodeSyntax as GRazorMetaCode
import io.github.dotnetsupport.razor.lang.syntax.RazorUsingDirectiveSyntax as GRazorUsingDirective
import io.github.dotnetsupport.razor.lang.syntax.UnclassifiedTextLiteralSyntax as GUnclassifiedTextLiteral

/**
 * `SyntaxRewriter` of the compiler over the red tree: a visit returns the node or its replacement (null drops it from a list). By default
 * the slots are visited and, when one of them changed, the green node is rebuilt with the new slots (the generated `Update`): the result
 * is a new red root, as the compiler's `SyntaxFactory` makes it (no parent, position 0), with the old node's diagnostics.
 */
open class RedSyntaxRewriter {
    open fun visit(node: RedNode?): RedNode? = if (node == null) null else dispatch(node)

    private fun dispatch(node: RedNode): RedNode? = when (node) {
        is RedToken -> visitToken(node)
        is RazorDocumentSyntax -> visitRazorDocument(node)
        is RazorCommentBlockSyntax -> visitRazorCommentBlock(node)
        is RazorMetaCodeSyntax -> visitRazorMetaCode(node)
        is GenericBlockSyntax -> visitGenericBlock(node)
        is UnclassifiedTextLiteralSyntax -> visitUnclassifiedTextLiteral(node)
        is MarkupBlockSyntax -> visitMarkupBlock(node)
        is MarkupTransitionSyntax -> visitMarkupTransition(node)
        is MarkupTextLiteralSyntax -> visitMarkupTextLiteral(node)
        is MarkupEphemeralTextLiteralSyntax -> visitMarkupEphemeralTextLiteral(node)
        is MarkupCommentBlockSyntax -> visitMarkupCommentBlock(node)
        is MarkupMinimizedAttributeBlockSyntax -> visitMarkupMinimizedAttributeBlock(node)
        is MarkupAttributeBlockSyntax -> visitMarkupAttributeBlock(node)
        is MarkupMiscAttributeContentSyntax -> visitMarkupMiscAttributeContent(node)
        is MarkupLiteralAttributeValueSyntax -> visitMarkupLiteralAttributeValue(node)
        is MarkupDynamicAttributeValueSyntax -> visitMarkupDynamicAttributeValue(node)
        is MarkupElementSyntax -> visitMarkupElement(node)
        is MarkupStartTagSyntax -> visitMarkupStartTag(node)
        is MarkupEndTagSyntax -> visitMarkupEndTag(node)
        is MarkupTagHelperElementSyntax -> visitMarkupTagHelperElement(node)
        is MarkupTagHelperStartTagSyntax -> visitMarkupTagHelperStartTag(node)
        is MarkupTagHelperEndTagSyntax -> visitMarkupTagHelperEndTag(node)
        is MarkupTagHelperAttributeSyntax -> visitMarkupTagHelperAttribute(node)
        is MarkupMinimizedTagHelperAttributeSyntax -> visitMarkupMinimizedTagHelperAttribute(node)
        is MarkupTagHelperAttributeValueSyntax -> visitMarkupTagHelperAttributeValue(node)
        is MarkupTagHelperDirectiveAttributeSyntax -> visitMarkupTagHelperDirectiveAttribute(node)
        is MarkupMinimizedTagHelperDirectiveAttributeSyntax -> visitMarkupMinimizedTagHelperDirectiveAttribute(node)
        is CSharpCodeBlockSyntax -> visitCSharpCodeBlock(node)
        is CSharpTransitionSyntax -> visitCSharpTransition(node)
        is CSharpStatementLiteralSyntax -> visitCSharpStatementLiteral(node)
        is CSharpExpressionLiteralSyntax -> visitCSharpExpressionLiteral(node)
        is CSharpEphemeralTextLiteralSyntax -> visitCSharpEphemeralTextLiteral(node)
        is CSharpTemplateBlockSyntax -> visitCSharpTemplateBlock(node)
        is CSharpStatementSyntax -> visitCSharpStatement(node)
        is CSharpStatementBodySyntax -> visitCSharpStatementBody(node)
        is CSharpExplicitExpressionSyntax -> visitCSharpExplicitExpression(node)
        is CSharpExplicitExpressionBodySyntax -> visitCSharpExplicitExpressionBody(node)
        is CSharpImplicitExpressionSyntax -> visitCSharpImplicitExpression(node)
        is CSharpImplicitExpressionBodySyntax -> visitCSharpImplicitExpressionBody(node)
        is RazorDirectiveSyntax -> visitRazorDirective(node)
        is RazorUsingDirectiveSyntax -> visitRazorUsingDirective(node)
        is RazorDocumentationDirectiveSyntax -> visitRazorDocumentationDirective(node)
        is RazorDirectiveBodySyntax -> visitRazorDirectiveBody(node)
        else -> visitChildren(node)
    }

    open fun visitToken(token: RedToken): RedNode? = token
    open fun visitRazorDocument(node: RazorDocumentSyntax): RedNode? = visitChildren(node)
    open fun visitRazorCommentBlock(node: RazorCommentBlockSyntax): RedNode? = visitChildren(node)
    open fun visitRazorMetaCode(node: RazorMetaCodeSyntax): RedNode? = visitChildren(node)
    open fun visitGenericBlock(node: GenericBlockSyntax): RedNode? = visitChildren(node)
    open fun visitUnclassifiedTextLiteral(node: UnclassifiedTextLiteralSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupBlock(node: MarkupBlockSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupTransition(node: MarkupTransitionSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupTextLiteral(node: MarkupTextLiteralSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupEphemeralTextLiteral(node: MarkupEphemeralTextLiteralSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupCommentBlock(node: MarkupCommentBlockSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupMinimizedAttributeBlock(node: MarkupMinimizedAttributeBlockSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupAttributeBlock(node: MarkupAttributeBlockSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupMiscAttributeContent(node: MarkupMiscAttributeContentSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupLiteralAttributeValue(node: MarkupLiteralAttributeValueSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupDynamicAttributeValue(node: MarkupDynamicAttributeValueSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupElement(node: MarkupElementSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupStartTag(node: MarkupStartTagSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupEndTag(node: MarkupEndTagSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupTagHelperElement(node: MarkupTagHelperElementSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupTagHelperStartTag(node: MarkupTagHelperStartTagSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupTagHelperEndTag(node: MarkupTagHelperEndTagSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupTagHelperAttribute(node: MarkupTagHelperAttributeSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupMinimizedTagHelperAttribute(node: MarkupMinimizedTagHelperAttributeSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupTagHelperAttributeValue(node: MarkupTagHelperAttributeValueSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupTagHelperDirectiveAttribute(node: MarkupTagHelperDirectiveAttributeSyntax): RedNode? = visitChildren(node)
    open fun visitMarkupMinimizedTagHelperDirectiveAttribute(node: MarkupMinimizedTagHelperDirectiveAttributeSyntax): RedNode? = visitChildren(node)
    open fun visitCSharpCodeBlock(node: CSharpCodeBlockSyntax): RedNode? = visitChildren(node)
    open fun visitCSharpTransition(node: CSharpTransitionSyntax): RedNode? = visitChildren(node)
    open fun visitCSharpStatementLiteral(node: CSharpStatementLiteralSyntax): RedNode? = visitChildren(node)
    open fun visitCSharpExpressionLiteral(node: CSharpExpressionLiteralSyntax): RedNode? = visitChildren(node)
    open fun visitCSharpEphemeralTextLiteral(node: CSharpEphemeralTextLiteralSyntax): RedNode? = visitChildren(node)
    open fun visitCSharpTemplateBlock(node: CSharpTemplateBlockSyntax): RedNode? = visitChildren(node)
    open fun visitCSharpStatement(node: CSharpStatementSyntax): RedNode? = visitChildren(node)
    open fun visitCSharpStatementBody(node: CSharpStatementBodySyntax): RedNode? = visitChildren(node)
    open fun visitCSharpExplicitExpression(node: CSharpExplicitExpressionSyntax): RedNode? = visitChildren(node)
    open fun visitCSharpExplicitExpressionBody(node: CSharpExplicitExpressionBodySyntax): RedNode? = visitChildren(node)
    open fun visitCSharpImplicitExpression(node: CSharpImplicitExpressionSyntax): RedNode? = visitChildren(node)
    open fun visitCSharpImplicitExpressionBody(node: CSharpImplicitExpressionBodySyntax): RedNode? = visitChildren(node)
    open fun visitRazorDirective(node: RazorDirectiveSyntax): RedNode? = visitChildren(node)
    open fun visitRazorUsingDirective(node: RazorUsingDirectiveSyntax): RedNode? = visitChildren(node)
    open fun visitRazorDocumentationDirective(node: RazorDocumentationDirectiveSyntax): RedNode? = visitChildren(node)
    open fun visitRazorDirectiveBody(node: RazorDirectiveBodySyntax): RedNode? = visitChildren(node)

    /** `VisitList`: the visited items, a null result dropped; the same list when nothing changed. */
    open fun visitList(list: List<RedNode>): List<RedNode> {
        var result: ArrayList<RedNode>? = null
        for (i in list.indices) {
            val item = list[i]
            val visited = visit(item)
            if (result == null && visited !== item) result = ArrayList(list.subList(0, i))
            if (result != null && visited != null) result.add(visited)
        }
        return result ?: list
    }

    /** The default of every `Visit*`: the slots visited in order, the node rebuilt (`Update`) when one of them changed. */
    protected fun visitChildren(node: RedNode): RedNode {
        val green = node.green
        val children = node.childNodesAndTokens()
        var cursor = 0
        var changed = false
        val slots = arrayOfNulls<RazorNode>(green.slotCount)
        for (i in 0 until green.slotCount) {
            val slot = green.slot(i) ?: continue
            if (slot is RazorList) {
                val items = children.subList(cursor, cursor + slot.size)
                cursor += slot.size
                val visited = visitList(items)
                if (visited !== items) {
                    changed = true
                    slots[i] = RazorList(visited.map { it.green })
                } else slots[i] = slot
            } else {
                val child = children[cursor++]
                val visited = visit(child)
                if (visited !== child) changed = true
                slots[i] = visited?.green
            }
        }
        return if (changed) RedTree.of(GreenNodeUpdate.update(green, slots)) else node
    }
}

/** The generated `Update` of every green node class: the same node with [slots] in place of its own, the other properties and the diagnostics kept. */
object GreenNodeUpdate {
    fun update(green: RazorNode, s: Array<RazorNode?>): RazorNode {
        val node: RazorSyntaxNode = when (green) {
            is GRazorDocument -> GRazorDocument(s[0] as GRazorBlock, s[1] as RazorToken)
            is GRazorCommentBlock -> GRazorCommentBlock(s[0] as RazorToken, s[1] as RazorToken, s[2] as RazorToken, s[3] as RazorToken, s[4] as RazorToken)
            is GRazorMetaCode -> GRazorMetaCode(list(s[0]), green.chunkGenerator, green.editHandler)
            is GGenericBlock -> GGenericBlock(list(s[0]))
            is GUnclassifiedTextLiteral -> GUnclassifiedTextLiteral(list(s[0]), green.chunkGenerator, green.editHandler)
            is GMarkupBlock -> GMarkupBlock(list(s[0]))
            is GMarkupTransition -> GMarkupTransition(list(s[0]), green.chunkGenerator, green.editHandler)
            is GMarkupTextLiteral -> GMarkupTextLiteral(list(s[0]), green.chunkGenerator, green.editHandler)
            is GMarkupEphemeralTextLiteral -> GMarkupEphemeralTextLiteral(list(s[0]), green.chunkGenerator, green.editHandler)
            is GMarkupCommentBlock -> GMarkupCommentBlock(list(s[0]))
            is GMarkupMinimizedAttributeBlock -> GMarkupMinimizedAttributeBlock(s[0] as GMarkupTextLiteral?, s[1] as GMarkupTextLiteral)
            is GMarkupAttributeBlock -> GMarkupAttributeBlock(
                s[0] as GMarkupTextLiteral?, s[1] as GMarkupTextLiteral, s[2] as GMarkupTextLiteral?, s[3] as RazorToken, s[4] as GMarkupTextLiteral?,
                s[5] as GRazorBlock?, s[6] as GMarkupTextLiteral?,
            )
            is GMarkupMiscAttributeContent -> GMarkupMiscAttributeContent(list(s[0]))
            is GMarkupLiteralAttributeValue -> GMarkupLiteralAttributeValue(s[0] as GMarkupTextLiteral?, s[1] as GMarkupTextLiteral?)
            is GMarkupDynamicAttributeValue -> GMarkupDynamicAttributeValue(s[0] as GMarkupTextLiteral?, s[1] as GRazorBlock)
            is GMarkupElement -> GMarkupElement(s[0] as GMarkupStartTag?, list(s[1]), s[2] as GMarkupEndTag?)
            is GMarkupStartTag -> GMarkupStartTag(
                s[0] as RazorToken, s[1] as RazorToken?, s[2] as RazorToken, list(s[3]), s[4] as RazorToken?, s[5] as RazorToken, green.isMarkupTransition,
                green.chunkGenerator, green.editHandler,
            )
            is GMarkupEndTag -> GMarkupEndTag(
                s[0] as RazorToken, s[1] as RazorToken, s[2] as RazorToken?, s[3] as RazorToken, s[4] as GMarkupMiscAttributeContent?, s[5] as RazorToken,
                green.isMarkupTransition, green.chunkGenerator, green.editHandler,
            )
            is GMarkupTagHelperElement -> GMarkupTagHelperElement(s[0] as GMarkupTagHelperStartTag?, list(s[1]), s[2] as GMarkupTagHelperEndTag?, green.tagHelperInfo)
            is GMarkupTagHelperStartTag -> GMarkupTagHelperStartTag(
                s[0] as RazorToken, s[1] as RazorToken?, s[2] as RazorToken, list(s[3]), s[4] as RazorToken?, s[5] as RazorToken, green.chunkGenerator, green.editHandler,
            )
            is GMarkupTagHelperEndTag -> GMarkupTagHelperEndTag(
                s[0] as RazorToken, s[1] as RazorToken, s[2] as RazorToken?, s[3] as RazorToken, s[4] as GMarkupMiscAttributeContent?, s[5] as RazorToken,
                green.chunkGenerator, green.editHandler,
            )
            is GMarkupTagHelperAttribute -> GMarkupTagHelperAttribute(
                s[0] as GMarkupTextLiteral?, s[1] as GMarkupTextLiteral, s[2] as GMarkupTextLiteral?, s[3] as RazorToken, s[4] as GMarkupTextLiteral?,
                s[5] as GMarkupTagHelperAttributeValue, s[6] as GMarkupTextLiteral?, green.tagHelperAttributeInfo,
            )
            is GMarkupMinimizedTagHelperAttribute -> GMarkupMinimizedTagHelperAttribute(s[0] as GMarkupTextLiteral?, s[1] as GMarkupTextLiteral, green.tagHelperAttributeInfo)
            is GMarkupTagHelperAttributeValue -> GMarkupTagHelperAttributeValue(list(s[0]))
            is GMarkupTagHelperDirectiveAttribute -> GMarkupTagHelperDirectiveAttribute(
                s[0] as GMarkupTextLiteral?, s[1] as GRazorMetaCode, s[2] as GMarkupTextLiteral, s[3] as GRazorMetaCode?, s[4] as GMarkupTextLiteral?,
                s[5] as GMarkupTextLiteral?, s[6] as RazorToken, s[7] as GMarkupTextLiteral?, s[8] as GMarkupTagHelperAttributeValue, s[9] as GMarkupTextLiteral?,
                green.tagHelperAttributeInfo,
            )
            is GMarkupMinimizedTagHelperDirectiveAttribute -> GMarkupMinimizedTagHelperDirectiveAttribute(
                s[0] as GMarkupTextLiteral?, s[1] as GRazorMetaCode, s[2] as GMarkupTextLiteral, s[3] as GRazorMetaCode?, s[4] as GMarkupTextLiteral?,
                green.tagHelperAttributeInfo,
            )
            is GCSharpCodeBlock -> GCSharpCodeBlock(list(s[0]))
            is GCSharpTransition -> GCSharpTransition(s[0] as RazorToken, green.chunkGenerator, green.editHandler)
            is GCSharpStatementLiteral -> GCSharpStatementLiteral(list(s[0]), green.chunkGenerator, green.editHandler)
            is GCSharpExpressionLiteral -> GCSharpExpressionLiteral(list(s[0]), green.chunkGenerator, green.editHandler)
            is GCSharpEphemeralTextLiteral -> GCSharpEphemeralTextLiteral(list(s[0]), green.chunkGenerator, green.editHandler)
            is GCSharpTemplateBlock -> GCSharpTemplateBlock(list(s[0]))
            is GCSharpStatement -> GCSharpStatement(s[0] as GCSharpTransition, s[1] as GCSharpSyntaxNode)
            is GCSharpStatementBody -> GCSharpStatementBody(s[0] as GRazorMetaCode, s[1] as GCSharpCodeBlock, s[2] as GRazorMetaCode)
            is GCSharpExplicitExpression -> GCSharpExplicitExpression(s[0] as GCSharpTransition, s[1] as GCSharpSyntaxNode)
            is GCSharpExplicitExpressionBody -> GCSharpExplicitExpressionBody(s[0] as GRazorMetaCode, s[1] as GCSharpCodeBlock, s[2] as GRazorMetaCode)
            is GCSharpImplicitExpression -> GCSharpImplicitExpression(s[0] as GCSharpTransition, s[1] as GCSharpSyntaxNode)
            is GCSharpImplicitExpressionBody -> GCSharpImplicitExpressionBody(s[0] as GCSharpCodeBlock)
            is GRazorDirective -> GRazorDirective(s[0] as GCSharpTransition, s[1] as GCSharpSyntaxNode, green.directiveDescriptor)
            is GRazorUsingDirective -> GRazorUsingDirective(s[0] as GCSharpTransition, s[1] as GCSharpSyntaxNode, green.directiveDescriptor)
            is GRazorDocumentationDirective -> GRazorDocumentationDirective(s[0] as GCSharpTransition, s[1] as GCSharpSyntaxNode, green.directiveDescriptor)
            is GRazorDirectiveBody -> GRazorDirectiveBody(s[0] as RazorSyntaxNode, s[1] as GCSharpCodeBlock?)
            else -> throw IllegalArgumentException("No update for ${green.kind}")
        }
        node.diagnostics = green.diagnostics
        return node
    }

    private fun list(slot: RazorNode?): RazorList = slot as RazorList? ?: RazorList.EMPTY
}
