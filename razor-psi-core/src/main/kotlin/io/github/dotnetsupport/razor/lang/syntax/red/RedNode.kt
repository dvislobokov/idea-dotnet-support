package io.github.dotnetsupport.razor.lang.syntax.red

import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorList
import io.github.dotnetsupport.razor.lang.syntax.RazorNode
import io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind
import io.github.dotnetsupport.razor.lang.syntax.RazorToken
import io.github.dotnetsupport.razor.lang.syntax.SourceLocation
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/**
 * The red tree over the green one of R2 (RAZOR_PLAN.md, R3b): the compiler's `SyntaxNode` — a green node with its parent and absolute
 * position, the children created on demand and kept, so that identity, `Parent`, `Position` and the walks the lowering phase relies on
 * (`DescendantNodes`, `FirstAncestorOrSelf`, `GetLastToken`) behave as in the compiler. The typed node classes are generated
 * (`RazorRedNodes.kt`, tools/razor/gen_syntax.py); the lists of the green tree are flattened into the children, as `SyntaxList` is a view.
 */
abstract class RedNode(val green: RazorNode, val parent: RedNode?, val position: Int) {
    val kind: RazorSyntaxKind get() = green.kind
    val width: Int get() = green.width
    val endPosition: Int get() = position + width
    val isMissing: Boolean get() = green.isMissing
    val containsDiagnostics: Boolean get() = green.containsDiagnostics
    open val isToken: Boolean get() = false

    /** `GetDiagnostics`: the diagnostics attached to this node itself. */
    fun getDiagnostics(): List<RazorDiagnostic> = green.diagnostics

    /** `GetContent`: the text of the green node. */
    fun getContent(): String = green.text()

    fun getSourceLocation(source: RazorSourceDocument): SourceLocation = source.location(position)
    fun getSourceSpan(source: RazorSourceDocument): SourceSpan = source.span(position, width)

    private val slots: Array<Any?> = arrayOfNulls(green.slotCount)

    private fun slotOffset(index: Int): Int {
        var offset = position
        for (i in 0 until index) offset += green.slot(i)?.width ?: 0
        return offset
    }

    protected fun slotNode(index: Int): RedNode? {
        slots[index]?.let { return it as RedNode }
        val child = green.slot(index) ?: return null
        return RedNodes.create(child, this, slotOffset(index)).also { slots[index] = it }
    }

    protected fun slotToken(index: Int): RedToken? = slotNode(index) as RedToken?

    protected fun slotList(index: Int): List<RedNode> {
        @Suppress("UNCHECKED_CAST")
        slots[index]?.let { return it as List<RedNode> }
        val list = green.slot(index) as RazorList? ?: return emptyList<RedNode>().also { slots[index] = it }
        var offset = slotOffset(index)
        val result = ArrayList<RedNode>(list.size)
        for (item in list.nodes) {
            result += RedNodes.create(item, this, offset)
            offset += item.width
        }
        slots[index] = result
        return result
    }

    /** `ChildNodesAndTokens`: the slots in order, the lists flattened. */
    fun childNodesAndTokens(): List<RedNode> {
        val result = ArrayList<RedNode>()
        for (i in 0 until green.slotCount) {
            val child = green.slot(i) ?: continue
            if (child is RazorList) result += slotList(i) else slotNode(i)?.let { result += it }
        }
        return result
    }

    fun childNodes(): List<RedNode> = childNodesAndTokens().filter { !it.isToken }

    /** `DescendantNodes`: the nodes below in prefix order, not this one, not the tokens; [descendIntoChildren] stops the walk below a node. */
    fun descendantNodes(descendIntoChildren: ((RedNode) -> Boolean)? = null): Sequence<RedNode> {
        val result = ArrayList<RedNode>()
        collectDescendants(this, descendIntoChildren, result, tokens = false)
        return result.asSequence()
    }

    fun descendantNodesAndSelf(descendIntoChildren: ((RedNode) -> Boolean)? = null): Sequence<RedNode> = listOf(this).asSequence() + descendantNodes(descendIntoChildren)

    /** `DescendantTokens`: the tokens below in order. */
    fun descendantTokens(): Sequence<RedToken> {
        val result = ArrayList<RedNode>()
        collectDescendants(this, null, result, tokens = true)
        @Suppress("UNCHECKED_CAST")
        return (result as List<RedToken>).asSequence()
    }

    private fun collectDescendants(node: RedNode, descendIntoChildren: ((RedNode) -> Boolean)?, out: MutableList<RedNode>, tokens: Boolean) {
        if (!tokens && descendIntoChildren != null && node === this && !descendIntoChildren(node)) return
        for (child in node.childNodesAndTokens()) {
            if (child.isToken) {
                if (tokens) out.add(child)
                continue
            }
            if (!tokens) out.add(child)
            if (tokens || descendIntoChildren == null || descendIntoChildren(child)) collectDescendants(child, descendIntoChildren, out, tokens)
        }
    }

    /** `FirstAncestorOrSelf`: this node or the nearest ancestor [predicate] accepts. */
    fun firstAncestorOrSelf(predicate: (RedNode) -> Boolean): RedNode? {
        var node: RedNode? = this
        while (node != null) {
            if (predicate(node)) return node
            node = node.parent
        }
        return null
    }

    /** `FirstAncestorOrSelf<T>()`: the JVM cannot overload on the type argument, hence the name. */
    inline fun <reified T : RedNode> firstAncestorOrSelfOfType(noinline predicate: ((T) -> Boolean)? = null): T? =
        firstAncestorOrSelf { it is T && (predicate == null || predicate(it)) } as T?

    /** `GetFirstToken`: the first token below with text (or any, with [includeZeroWidth]); null when there is none (`SyntaxToken` `None`). */
    fun getFirstToken(includeZeroWidth: Boolean = false): RedToken? {
        for (child in childNodesAndTokens()) {
            if (child is RedToken) {
                if (includeZeroWidth || child.width > 0) return child
            } else child.getFirstToken(includeZeroWidth)?.let { return it }
        }
        return null
    }

    fun getLastToken(includeZeroWidth: Boolean = false): RedToken? {
        for (child in childNodesAndTokens().asReversed()) {
            if (child is RedToken) {
                if (includeZeroWidth || child.width > 0) return child
            } else child.getLastToken(includeZeroWidth)?.let { return it }
        }
        return null
    }

    abstract fun accept(visitor: RedSyntaxVisitor)

    override fun toString(): String = "$kind [$position..$endPosition)"
}

/** `SyntaxToken` of the red tree. */
class RedToken(green: RazorToken, parent: RedNode?, position: Int) : RedNode(green, parent, position) {
    val token: RazorToken get() = green as RazorToken
    val content: String @JvmName("tokenContent") get() = token.content
    override val isToken: Boolean get() = true
    override fun accept(visitor: RedSyntaxVisitor) = visitor.visitToken(this)
}

/** The root of a red tree: the document over the whole text. */
object RedTree {
    fun of(root: RazorNode): RedNode = RedNodes.create(root, null, 0)
}
