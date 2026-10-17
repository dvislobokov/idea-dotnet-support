package io.github.dotnetsupport.razor.lang.syntax

/**
 * The tree the Razor parser builds (RAZOR_PLAN.md, R2): the compiler's green tree (`Syntax/GreenNode`, `InternalSyntax/SyntaxToken`),
 * node kinds of [RazorSyntaxKind], widths summed from the children, no positions — [RazorSyntaxWalker] computes them while walking.
 * The classes of the nodes are generated from the compiler's Syntax.xml (`RazorSyntaxNodes.kt`, tools/razor/gen_syntax.py).
 */
abstract class RazorNode(val kind: RazorSyntaxKind) {
    abstract val width: Int
    abstract val slotCount: Int
    abstract fun slot(index: Int): RazorNode?

    open val isToken: Boolean get() = false
    open val isList: Boolean get() = false

    /** A token the parser expected and did not find: zero width, kept in the tree (`SyntaxFactory.MissingToken`). */
    open val isMissing: Boolean get() = false

    /** The diagnostics attached to this node itself (a token's, or a directive's `WithDiagnosticsGreen`). */
    var diagnostics: List<RazorDiagnostic> = emptyList()

    /** `GreenNode.ContainsDiagnostics`: this node or one below carries a diagnostic. */
    val containsDiagnostics: Boolean
        get() {
            if (diagnostics.isNotEmpty()) return true
            for (i in 0 until slotCount) if (slot(i)?.containsDiagnostics == true) return true
            return false
        }

    /** The text of the node: the contents of its tokens (`GreenNode.ToString`). */
    fun text(): String = StringBuilder(width).also { write(it) }.toString()

    open fun write(out: StringBuilder) {
        for (i in 0 until slotCount) slot(i)?.write(out)
    }

    /** The tokens of the node in order, the missing ones included. */
    fun tokens(): List<RazorToken> = ArrayList<RazorToken>().also { collectTokens(it) }

    private fun collectTokens(out: MutableList<RazorToken>) {
        if (this is RazorToken) out += this else for (i in 0 until slotCount) slot(i)?.collectTokens(out)
    }

    val firstToken: RazorToken? get() = tokens().firstOrNull()
    val lastToken: RazorToken? get() = tokens().lastOrNull()

    override fun toString(): String = text()
}

/** A token: [content] is its text (empty for a [isMissing] one). */
class RazorToken(kind: RazorSyntaxKind, val content: String, diagnostics: List<RazorDiagnostic> = emptyList(), override val isMissing: Boolean = false) : RazorNode(kind) {
    init {
        this.diagnostics = diagnostics
    }

    override val width: Int get() = content.length
    override val slotCount: Int get() = 0
    override fun slot(index: Int): RazorNode? = throw IndexOutOfBoundsException("tokens have no slots")
    override val isToken: Boolean get() = true
    override fun write(out: StringBuilder) {
        out.append(content)
    }

    fun withDiagnostics(diagnostics: List<RazorDiagnostic>): RazorToken = RazorToken(kind, content, diagnostics, isMissing)

    /** `GreenNode.IsEquivalentTo` of two tokens: the same kind and content. */
    fun isEquivalentTo(other: RazorToken): Boolean = kind == other.kind && content == other.content

    companion object {
        fun missing(kind: RazorSyntaxKind, vararg diagnostics: RazorDiagnostic): RazorToken = RazorToken(kind, "", diagnostics.toList(), isMissing = true)
    }
}

/** `SyntaxList<T>`: the children of a block, the tokens of a literal. Never holds nulls; [replace] is for the syntax tree passes. */
class RazorList(nodes: List<RazorNode>) : RazorNode(RazorSyntaxKind.List) {
    private val items: MutableList<RazorNode> = ArrayList(nodes)
    val nodes: List<RazorNode> get() = items
    override val width: Int get() = items.sumOf { it.width }

    fun replace(index: Int, replacement: List<RazorNode>) {
        items.removeAt(index)
        items.addAll(index, replacement)
    }
    override val slotCount: Int get() = nodes.size
    override fun slot(index: Int): RazorNode = nodes[index]
    override val isList: Boolean get() = true
    val size: Int get() = nodes.size
    val isEmpty: Boolean get() = nodes.isEmpty()
    operator fun get(index: Int): RazorNode = nodes[index]

    /** The tokens of a `SyntaxList<SyntaxToken>`. */
    val tokens: List<RazorToken> get() = nodes.map { it as RazorToken }

    companion object {
        val EMPTY = RazorList(emptyList())
    }
}

/** A node with named slots: the generated classes. The width is summed on demand: the syntax tree passes rewrite lists after the parse. */
abstract class RazorSyntaxNode(kind: RazorSyntaxKind) : RazorNode(kind) {
    override val width: Int get() = (0 until slotCount).sumOf { slot(it)?.width ?: 0 }

    /** `GreenNodeExtensions.WithDiagnosticsGreen`: this node with the diagnostics attached (the tree is built once, so it is mutated). */
    fun <T : RazorSyntaxNode> withDiagnostics(diagnostics: List<RazorDiagnostic>): T {
        this.diagnostics = diagnostics
        @Suppress("UNCHECKED_CAST")
        return this as T
    }
}

/** `SyntaxListBuilder<T>`: nulls are skipped on [add], as the compiler's builder does; [consume] hands the nodes over and starts afresh. */
class RazorListBuilder {
    private var items: ArrayList<RazorNode> = ArrayList()

    val count: Int get() = items.size
    operator fun get(index: Int): RazorNode = items[index]

    fun add(node: RazorNode?) {
        if (node != null) items.add(node)
    }

    fun addRange(nodes: RazorList) {
        items.addAll(nodes.nodes)
    }

    fun addRange(nodes: List<RazorNode>) {
        items.addAll(nodes)
    }

    fun toList(): RazorList = if (items.isEmpty()) RazorList.EMPTY else RazorList(ArrayList(items))

    fun consume(): RazorList {
        val list = toList()
        items = ArrayList()
        return list
    }

    fun clear() {
        items.clear()
    }
}

/** `TagHelperInfo`: the tag helper binding of an element of the rewritten tree (`TagHelperParseTreeRewriter`). */
data class TagHelperInfo(val tagName: String, val tagMode: TagMode, val bindingResult: TagHelperBinding)

/** `TagHelperAttributeInfo`: the binding of an attribute of a tag helper element of the rewritten tree (`TagHelperBlockRewriter`). */
class TagHelperAttributeInfo(val name: String, val parameterName: String?, val attributeStructure: AttributeStructure, val bound: Boolean, val isDirectiveAttribute: Boolean)
