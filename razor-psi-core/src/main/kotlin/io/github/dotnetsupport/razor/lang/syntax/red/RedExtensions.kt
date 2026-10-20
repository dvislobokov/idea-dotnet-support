package io.github.dotnetsupport.razor.lang.syntax.red

import io.github.dotnetsupport.razor.lang.parser.ParserHelpers
import io.github.dotnetsupport.razor.lang.syntax.AcceptedCharacters
import io.github.dotnetsupport.razor.lang.syntax.ChunkGenerator
import io.github.dotnetsupport.razor.lang.syntax.RazorList
import io.github.dotnetsupport.razor.lang.syntax.RazorNode
import io.github.dotnetsupport.razor.lang.syntax.RazorToken
import io.github.dotnetsupport.razor.lang.syntax.SpanEditHandler
import io.github.dotnetsupport.razor.lang.syntax.MarkupTextLiteralSyntax as GreenMarkupTextLiteral
import io.github.dotnetsupport.razor.lang.syntax.RazorMetaCodeSyntax as GreenRazorMetaCode

/** The hand-written parts of the compiler's syntax node classes (`Syntax/BaseMarkupStartTagSyntax.cs` and friends). */

/** `SpanEditHandler.GetDefault`. */
fun defaultEditHandler(acceptedCharacters: Int): SpanEditHandler = SpanEditHandler(acceptedCharacters)

/** `GetTagNameWithOptionalBang`: `!name` of an escaped tag, empty for a missing name. */
fun MarkupStartTagSyntax.getTagNameWithOptionalBang(): String = if (name.isMissing) "" else (bang?.content ?: "") + name.content
fun MarkupEndTagSyntax.getTagNameWithOptionalBang(): String = if (name.isMissing) "" else (bang?.content ?: "") + name.content

fun BaseMarkupStartTagSyntax.isVoidElement(): Boolean = ParserHelpers.isVoidElement(name.content)

/** `IsSelfClosing`: a `/` that is there, before a `>` that is there. */
fun BaseMarkupStartTagSyntax.isSelfClosing(): Boolean = forwardSlash.let { it != null && !it.isMissing } && !closeAngle.isMissing

fun BaseMarkupStartTagSyntax.getEndTag(): BaseMarkupEndTagSyntax? = (parent as? BaseMarkupElementSyntax)?.endTag
fun BaseMarkupEndTagSyntax.getStartTag(): BaseMarkupStartTagSyntax? = (parent as? BaseMarkupElementSyntax)?.startTag

val BaseRazorDirectiveSyntax.directiveBody: RazorDirectiveBodySyntax get() = body as RazorDirectiveBodySyntax

private fun RedToken?.valid(): RazorToken? = this?.takeIf { !it.isMissing }?.token

private val legacyChildrenCache = java.util.WeakHashMap<RedNode, List<RedNode>>()

/**
 * The cache under one lock, made inside it: the highlighting passes ask from several threads at once, and an unguarded WeakHashMap broke
 * its chains under them (a test hung in `get` forever); one list per tag, so its red nodes stay the same objects for every caller.
 */
private inline fun RedNode.cachedLegacyChildren(make: () -> List<RedNode>): List<RedNode> =
    synchronized(legacyChildrenCache) { legacyChildrenCache.getOrPut(this, make) }

/**
 * `LegacyChildren` of a start tag: the tag as the literals the legacy tree had — `<` and the name as a text literal, a `!` as meta code,
 * the attributes, `/` and `>` as a literal; red nodes over green ones made here, parented to the tag.
 */
val BaseMarkupStartTagSyntax.legacyChildren: List<RedNode>
    get() = cachedLegacyChildren {
        val nodes = ArrayList<RazorNode>()
        val tokens = ArrayList<RazorToken>()
        var acceptsAnyHandler: SpanEditHandler? = null
        var acceptsNoneHandler: SpanEditHandler? = null
        var containsAttributesContent = false
        val editHandler = editHandler
        if (editHandler != null) {
            acceptsAnyHandler = defaultEditHandler(AcceptedCharacters.ANY)
            acceptsNoneHandler = defaultEditHandler(AcceptedCharacters.NONE)
            outer@ for (attribute in attributes) {
                for (token in attribute.descendantTokens()) {
                    if (token.content.isNotBlank()) {
                        containsAttributesContent = true
                        break@outer
                    }
                }
            }
        }
        val chunkGenerator = chunkGenerator
        fun literal(handler: SpanEditHandler?) = GreenMarkupTextLiteral(RazorList(ArrayList(tokens)), chunkGenerator, handler).also { tokens.clear() }
        openAngle.valid()?.let { tokens += it }
        bang.valid()?.let {
            nodes += literal(acceptsAnyHandler)
            nodes += GreenRazorMetaCode(RazorList(listOf(it)), chunkGenerator, acceptsNoneHandler)
        }
        name.valid()?.let { tokens += it }
        nodes += literal(if (containsAttributesContent) acceptsAnyHandler else editHandler)
        for (attribute in attributes) nodes += attribute.green
        forwardSlash.valid()?.let { tokens += it }
        closeAngle.valid()?.let { tokens += it }
        if (tokens.isNotEmpty()) nodes += literal(editHandler)
        redChildren(nodes)
    }

/** `LegacyChildren` of an end tag: `</`, a `!` as meta code, the name, the misc content and `>` as literals. */
val BaseMarkupEndTagSyntax.legacyChildren: List<RedNode>
    get() = cachedLegacyChildren {
        val nodes = ArrayList<RazorNode>()
        val tokens = ArrayList<RazorToken>()
        val editHandler = editHandler
        val chunkGenerator = chunkGenerator
        fun literal(handler: SpanEditHandler?) = GreenMarkupTextLiteral(RazorList(ArrayList(tokens)), chunkGenerator, handler).also { tokens.clear() }
        openAngle.valid()?.let { tokens += it }
        forwardSlash.valid()?.let { tokens += it }
        bang.valid()?.let {
            var acceptsAnyHandler: SpanEditHandler? = null
            var acceptsNoneHandler: SpanEditHandler? = null
            if (editHandler != null) {
                acceptsAnyHandler = defaultEditHandler(AcceptedCharacters.ANY)
                acceptsNoneHandler = defaultEditHandler(AcceptedCharacters.NONE)
            }
            nodes += literal(acceptsAnyHandler)
            nodes += GreenRazorMetaCode(RazorList(listOf(it)), chunkGenerator, acceptsNoneHandler)
        }
        name.valid()?.let { tokens += it }
        miscAttributeContent?.children?.forEach { content -> tokens += (content as MarkupTextLiteralSyntax).literalTokens.map { it.token } }
        closeAngle.valid()?.let { tokens += it }
        nodes += literal(editHandler)
        redChildren(nodes)
    }

/** Red nodes over [nodes] laid out from this node's position, as `ToListNode(parent: this, Position)` does. */
private fun RedNode.redChildren(nodes: List<RazorNode>): List<RedNode> {
    var offset = position
    return nodes.map { node -> RedNodes.create(node, this, offset).also { offset += node.width } }
}

/** `Green.CreateRed(parent, position)`: a red node over a green one made by the lowering phase. */
fun RazorNode.createRed(parent: RedNode?, position: Int): RedNode = RedNodes.create(this, parent, position)

/** `ChunkGenerator` of the literal nodes, as the lowering phase switches on it; null for the others. */
val RedNode.chunkGeneratorOrNull: ChunkGenerator?
    get() = when (this) {
        is CSharpStatementLiteralSyntax -> chunkGenerator
        is CSharpExpressionLiteralSyntax -> chunkGenerator
        is CSharpEphemeralTextLiteralSyntax -> chunkGenerator
        is MarkupTextLiteralSyntax -> chunkGenerator
        is MarkupEphemeralTextLiteralSyntax -> chunkGenerator
        is RazorMetaCodeSyntax -> chunkGenerator
        is UnclassifiedTextLiteralSyntax -> chunkGenerator
        is MarkupTransitionSyntax -> chunkGenerator
        is CSharpTransitionSyntax -> chunkGenerator
        is BaseMarkupStartTagSyntax -> chunkGenerator
        is BaseMarkupEndTagSyntax -> chunkGenerator
        else -> null
    }
