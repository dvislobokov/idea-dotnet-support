package io.github.dotnetsupport.razor.lang.intermediate

import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/**
 * The intermediate representation of the Razor compiler (`Microsoft.AspNetCore.Razor.Language.Intermediate`; RAZOR_PLAN.md, R3b): the
 * tree the lowering phase builds from the syntax tree, the passes rewrite and the code writers render. Ported class for class, member
 * for member (PascalCase to camelCase); the node classes are in [IntermediateNodes.kt] and the files next to it.
 */
abstract class IntermediateNode {
    private var diagnosticsList: ArrayList<RazorDiagnostic>? = null

    val diagnostics: List<RazorDiagnostic> get() = diagnosticsList ?: emptyList()
    val hasDiagnostics: Boolean get() = !diagnosticsList.isNullOrEmpty()

    var source: SourceSpan? = null
    var isImported: Boolean = false

    /** `IsSynthesizedHelper` (internal init): a node the passes add for the tooling, left out of the decl document. */
    var isSynthesizedHelper: Boolean = false

    abstract val children: IntermediateNodeCollection

    abstract fun accept(visitor: IntermediateNodeVisitor)

    fun addDiagnostic(diagnostic: RazorDiagnostic) {
        (diagnosticsList ?: ArrayList<RazorDiagnostic>().also { diagnosticsList = it }).add(diagnostic)
    }

    fun addDiagnosticsFromNode(node: IntermediateNode) {
        if (node.hasDiagnostics) (diagnosticsList ?: ArrayList<RazorDiagnostic>().also { diagnosticsList = it }).addAll(node.diagnostics)
    }

    open fun formatNode(formatter: IntermediateNodeFormatter) {}

    /** `Clone`: the node, its source, imported flag, diagnostics and the clones of the children. */
    open fun clone(): IntermediateNode {
        val clone = cloneNode()
        clone.source = source
        clone.isImported = isImported
        clone.addDiagnosticsFromNode(this)
        for (child in children) clone.children.add(child.clone())
        return clone
    }

    protected open fun cloneNode(): IntermediateNode = throw UnsupportedOperationException("${javaClass.simpleName} does not support cloning.")

    /** `GetDebuggerDisplay`: the node as the formatter prints it. */
    fun debuggerDisplay(): String = StringBuilder().also { IntermediateNodeFormatter(it).formatNode(this) }.toString()

    /** The whole tree as the formatter prints it. */
    fun tree(): String = StringBuilder().also { IntermediateNodeFormatter(it).formatTree(this) }.toString()

    override fun toString(): String = debuggerDisplay()
}

/** `IntermediateNodeCollection`: the children of a node; [READ_ONLY] is the empty one of the leaves (`IntermediateNodeCollection.ReadOnly`). */
class IntermediateNodeCollection private constructor(val isReadOnly: Boolean) : AbstractMutableList<IntermediateNode>() {
    constructor() : this(false)

    private val items = ArrayList<IntermediateNode>()

    override val size: Int get() = items.size
    override fun get(index: Int): IntermediateNode = items[index]

    override fun set(index: Int, element: IntermediateNode): IntermediateNode {
        throwIfReadOnly()
        return items.set(index, element)
    }

    override fun add(index: Int, element: IntermediateNode) {
        throwIfReadOnly()
        items.add(index, element)
    }

    override fun removeAt(index: Int): IntermediateNode {
        throwIfReadOnly()
        return items.removeAt(index)
    }

    /** `IndexOf` by reference, as the compiler's (`ReferenceEquals` in `InlineList.IndexOf`). */
    override fun indexOf(element: IntermediateNode): Int {
        for (i in items.indices) if (items[i] === element) return i
        return -1
    }

    override fun contains(element: IntermediateNode): Boolean = indexOf(element) >= 0

    override fun remove(element: IntermediateNode): Boolean {
        val index = indexOf(element)
        if (index < 0) return false
        removeAt(index)
        return true
    }

    private fun throwIfReadOnly() {
        if (isReadOnly) throw UnsupportedOperationException("Collection is read-only.")
    }

    companion object {
        val READ_ONLY = IntermediateNodeCollection(true)
    }
}

/** `LazyContent`: the content of a token computed from the syntax node on first use. */
class LazyContent(factory: () -> String) {
    val value: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED, factory)
}

/** `IntermediateToken`: a run of C# or HTML text with its source. */
abstract class IntermediateToken : IntermediateNode {
    private var contentHolder: Any
    val isLazy: Boolean

    val content: String get() = (contentHolder as? LazyContent)?.value ?: contentHolder as String

    protected constructor(content: String, source: SourceSpan?) {
        contentHolder = content
        isLazy = false
        if (source != null) this.source = source
    }

    protected constructor(content: LazyContent, source: SourceSpan?) {
        contentHolder = content
        isLazy = true
        if (source != null) this.source = source
    }

    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    fun updateContent(content: String) {
        contentHolder = content
    }

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitToken(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(content)
        formatter.writeProperty("Content", content)
    }
}

class CSharpIntermediateToken : IntermediateToken {
    constructor(content: String, source: SourceSpan?) : super(content, source)
    constructor(content: LazyContent, source: SourceSpan?) : super(content, source)

    override fun cloneNode(): IntermediateNode = CSharpIntermediateToken(content, source).also { it.isSynthesizedHelper = isSynthesizedHelper }
}

class HtmlIntermediateToken : IntermediateToken {
    constructor(content: String, source: SourceSpan?) : super(content, source)
    constructor(content: LazyContent, source: SourceSpan?) : super(content, source)

    override fun cloneNode(): IntermediateNode = HtmlIntermediateToken(content, source).also { it.isSynthesizedHelper = isSynthesizedHelper }
}

/** `IntermediateNodeFactory`. */
object IntermediateNodeFactory {
    fun csharpToken(content: String, source: SourceSpan? = null): CSharpIntermediateToken = CSharpIntermediateToken(content, source)
    fun <T> csharpToken(arg: T, contentFactory: (T) -> String, source: SourceSpan? = null): CSharpIntermediateToken = CSharpIntermediateToken(LazyContent { contentFactory(arg) }, source)
    fun htmlToken(content: String, source: SourceSpan? = null): HtmlIntermediateToken = HtmlIntermediateToken(content, source)
    fun <T> htmlToken(arg: T, contentFactory: (T) -> String, source: SourceSpan? = null): HtmlIntermediateToken = HtmlIntermediateToken(LazyContent { contentFactory(arg) }, source)
}

/** `IntermediateNodeReference`: a node with its parent, for the passes that replace, insert next to or remove it. */
class IntermediateNodeReference<T : IntermediateNode>(val node: T, val parent: IntermediateNode) {
    operator fun component1(): T = node
    operator fun component2(): IntermediateNode = parent

    private fun indexForMutation(): Int {
        if (parent.children.isReadOnly) throw IllegalStateException("The children of $parent are read-only.")
        val index = parent.children.indexOf(node)
        if (index < 0) throw IllegalStateException("The node $node is not a child of $parent.")
        return index
    }

    fun <N : IntermediateNode> insertAfter(node: N): IntermediateNodeReference<N> {
        parent.children.add(indexForMutation() + 1, node)
        return IntermediateNodeReference(node, parent)
    }

    fun insertAfter(nodes: Iterable<IntermediateNode>) {
        var index = indexForMutation()
        for (n in nodes) parent.children.add(++index, n)
    }

    fun <N : IntermediateNode> insertBefore(node: N): IntermediateNodeReference<N> {
        parent.children.add(indexForMutation(), node)
        return IntermediateNodeReference(node, parent)
    }

    fun insertBefore(nodes: Iterable<IntermediateNode>) {
        var index = indexForMutation()
        for (n in nodes) parent.children.add(index++, n)
    }

    fun remove() {
        parent.children.removeAt(indexForMutation())
    }

    fun <N : IntermediateNode> replace(node: N): IntermediateNodeReference<N> {
        parent.children[indexForMutation()] = node
        return IntermediateNodeReference(node, parent)
    }

    override fun toString(): String = "ref: ${parent.debuggerDisplay()} - ${node.debuggerDisplay()}"
}

/** `IntermediateNodeBuilder`: the stack the lowering visitor and the classifiers build the tree with. */
abstract class IntermediateNodeBuilder {
    abstract val current: IntermediateNode?
    abstract fun add(node: IntermediateNode)
    abstract fun insert(index: Int, node: IntermediateNode)
    abstract fun build(): IntermediateNode?
    abstract fun push(node: IntermediateNode)
    abstract fun pop(): IntermediateNode

    companion object {
        fun create(root: IntermediateNode): IntermediateNodeBuilder = DefaultRazorIntermediateNodeBuilder().also { it.push(root) }
    }
}

class DefaultRazorIntermediateNodeBuilder : IntermediateNodeBuilder() {
    private val stack = ArrayList<IntermediateNode>()
    private var depth = 0

    override val current: IntermediateNode? get() = if (depth > 0) stack[depth - 1] else null

    override fun add(node: IntermediateNode) {
        current!!.children.add(node)
    }

    override fun insert(index: Int, node: IntermediateNode) {
        val children = current!!.children
        if (index < 0 || index > children.size) throw IndexOutOfBoundsException("index $index of ${children.size}")
        if (index == children.size) children.add(node) else children.add(index, node)
    }

    override fun build(): IntermediateNode? {
        var node: IntermediateNode? = null
        while (depth > 0) node = pop()
        return node
    }

    override fun pop(): IntermediateNode {
        if (depth == 0) throw IllegalStateException("The builder has nothing to pop.")
        return stack[--depth]
    }

    override fun push(node: IntermediateNode) {
        if (depth >= stack.size) stack.add(node) else stack[depth] = node
        if (depth > 0) stack[depth - 1].children.add(node)
        depth++
    }
}

/**
 * `IntermediateNodeFormatter`: one node as `Name "content" { Key: "value" }` (the debugger display) or the tree with two spaces per
 * level. The short name is the class name without `IntermediateNode`.
 */
class IntermediateNodeFormatter(private val builder: StringBuilder, private val mode: Mode = Mode.PREFER_CONTENT, private val includeSource: Boolean = false) {
    enum class Mode { PREFER_CONTENT, PREFER_PROPERTIES }

    private val properties = LinkedHashMap<String, String>()
    private var content: String? = null

    fun writeChildren(children: IntermediateNodeCollection) {
        builder.append(" \"")
        for (child in children) if (child is IntermediateToken) writeEscaped(child.content)
        builder.append('"')
    }

    fun writeContent(content: String?) {
        if (content != null) this.content = content
    }

    fun writeProperty(key: String, value: String?) {
        if (value != null) properties[key] = value
    }

    fun formatNode(node: IntermediateNode) {
        builder.append(shortName(node))
        if (includeSource) builder.append(' ').append(node.source?.toString() ?: "(n/a)")
        node.formatNode(this)
        val content = content
        if (content != null && (properties.isEmpty() || mode == Mode.PREFER_CONTENT)) {
            builder.append(" \"")
            writeEscaped(content)
            builder.append('"')
        }
        if (properties.isNotEmpty() && (content == null || mode == Mode.PREFER_CONTENT)) {
            builder.append(" { ")
            var first = true
            for ((key, value) in properties) {
                if (first) first = false else builder.append(", ")
                builder.append(key).append(": \"")
                writeEscaped(value)
                builder.append('"')
            }
            builder.append(" }")
        }
        this.content = null
        properties.clear()
    }

    fun formatTree(node: IntermediateNode) {
        object : IntermediateNodeWalker() {
            var indent = 0
            override fun visitDefault(node: IntermediateNode) {
                repeat(indent) { builder.append(' ') }
                formatNode(node)
                builder.append(System.lineSeparator())
                indent += 2
                super.visitDefault(node)
                indent -= 2
            }
        }.visit(node)
    }

    private fun writeEscaped(content: String) {
        for (c in content) when (c) {
            '\r' -> builder.append("\\r")
            '\n' -> builder.append("\\n")
            '\t' -> builder.append("\\t")
            else -> builder.append(c)
        }
    }

    companion object {
        fun shortName(node: IntermediateNode): String = node.javaClass.simpleName.removeSuffix("IntermediateNode")
    }
}

/** `IntermediateNodeExtensions` and `DocumentIntermediateNodeExtensions`. */
fun IntermediateNode.getAllDiagnostics(): List<RazorDiagnostic> {
    val result = LinkedHashSet<RazorDiagnostic>()
    fun collect(node: IntermediateNode) {
        if (node.hasDiagnostics) result.addAll(node.diagnostics)
        for (child in node.children) collect(child)
    }
    collect(this)
    return result.sortedBy { it.span.absoluteIndex }
}

inline fun <reified T : IntermediateNode> IntermediateNode.findDescendantNodes(): List<T> {
    val results = ArrayList<T>()
    val stack = ArrayList<IntermediateNode>()
    for (i in children.indices.reversed()) stack.add(children[i])
    while (stack.isNotEmpty()) {
        val node = stack.removeAt(stack.size - 1)
        if (node is T) results.add(node)
        for (i in node.children.indices.reversed()) stack.add(node.children[i])
    }
    return results
}

/** `FindDescendantReferences`: post-order (the children before the node), with the parent of each. */
inline fun <reified T : IntermediateNode> IntermediateNode.findDescendantReferences(): List<IntermediateNodeReference<T>> {
    val results = ArrayList<IntermediateNodeReference<T>>()
    val stack = ArrayList<Triple<IntermediateNode, IntermediateNode?, Boolean>>()
    stack.add(Triple(this, null, false))
    while (stack.isNotEmpty()) {
        val (node, parent, visited) = stack.removeAt(stack.size - 1)
        if (visited) {
            if (node is T && parent != null) results.add(IntermediateNodeReference(node, parent))
        } else {
            stack.add(Triple(node, parent, true))
            for (i in node.children.indices.reversed()) stack.add(Triple(node.children[i], node, false))
        }
    }
    return results
}

inline fun <reified T : IntermediateNode> IntermediateNode.findNode(predicate: (T) -> Boolean): T? {
    val stack = ArrayList<IntermediateNode>()
    stack.add(this)
    while (stack.isNotEmpty()) {
        val node = stack.removeAt(stack.size - 1)
        if (node is T && predicate(node)) return node
        for (i in node.children.indices.reversed()) stack.add(node.children[i])
    }
    return null
}
