package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.psi.tree.IElementType

/** Structural dumps for comparing an incrementally reparsed tree with a fresh parse (docs/csharp-psi/GRAMMAR.md, "Reparseable bodies"). */
object BodyReparseSupport {

    /**
     * Every element under [root] in preorder: depth, element type (a [CSharpBodyBlockType] with its context key, so a
     * body parsed in another context differs), offsets relative to [base], and the text of leaves. Lazy elements are
     * expanded by the walk.
     */
    fun structure(root: ASTNode, base: Int = 0): List<String> = ArrayList<String>().also { dump(root, 0, base, it, null, null, 0) }

    /**
     * [structure] of [file] with the subtree of [oldNode] replaced by [newNode] (a reparsed body in a `DummyHolder`,
     * offsets from 0) and the offsets after it shifted by the text length difference: the tree a body reparse yields.
     */
    fun structureWithReplacement(file: ASTNode, oldNode: ASTNode, newNode: ASTNode): List<String> =
        ArrayList<String>().also { dump(file, 0, 0, it, oldNode, newNode, newNode.textLength - oldNode.textLength) }

    fun typeName(type: IElementType): String = if (type is CSharpBodyBlockType) "Block#${type.context.key.toString(16)}" else type.toString()

    private fun dump(n: ASTNode, depth: Int, base: Int, out: MutableList<String>, oldNode: ASTNode?, newNode: ASTNode?, delta: Int) {
        if (n === oldNode) {
            val start = n.startOffset
            dump(newNode!!, depth, -start, out, null, null, 0)
            return
        }
        val start = shifted(n.startOffset, oldNode, delta) - base
        val end = shifted(n.startOffset + n.textLength, oldNode, delta) - base
        val first = n.firstChildNode
        out += "$depth ${typeName(n.elementType)} $start-$end" + if (first == null) " '${n.text}'" else ""
        var c = first
        while (c != null) {
            dump(c, depth + 1, base, out, oldNode, newNode, delta)
            c = c.treeNext
        }
    }

    private fun shifted(offset: Int, oldNode: ASTNode?, delta: Int): Int =
        if (oldNode != null && offset >= oldNode.startOffset + oldNode.textLength) offset + delta else offset

    /**
     * The first difference between [fresh] (a full parse of the new text) and [file] with [oldNode] replaced by
     * [newNode] (a body reparse), compared element by element as in [structure] without materialising the lists; null
     * when equal.
     */
    fun firstMismatch(fresh: ASTNode, file: ASTNode, oldNode: ASTNode, newNode: ASTNode): String? {
        val expected = elements(fresh, 0, 0, null, null, 0).iterator()
        val actual = elements(file, 0, 0, oldNode, newNode, newNode.textLength - oldNode.textLength).iterator()
        var i = 0
        while (expected.hasNext() || actual.hasNext()) {
            val e = if (expected.hasNext()) expected.next() else null
            val a = if (actual.hasNext()) actual.next() else null
            if (e != a) return "at element $i: full parse `$e`, body reparse `$a`"
            i++
        }
        return null
    }

    private fun elements(n: ASTNode, depth: Int, base: Int, oldNode: ASTNode?, newNode: ASTNode?, delta: Int): Sequence<String> = sequence {
        if (n === oldNode) {
            yieldAll(elements(newNode!!, depth, -n.startOffset, null, null, 0))
            return@sequence
        }
        val start = shifted(n.startOffset, oldNode, delta) - base
        val end = shifted(n.startOffset + n.textLength, oldNode, delta) - base
        val first = n.firstChildNode
        yield("$depth ${typeName(n.elementType)} $start-$end" + if (first == null) " '${n.text}'" else "")
        var c = first
        while (c != null) {
            yieldAll(elements(c, depth + 1, base, oldNode, newNode, delta))
            c = c.treeNext
        }
    }

    /** The first differing line of two structures, or null. */
    fun firstDifference(expected: List<String>, actual: List<String>): String? {
        if (expected == actual) return null
        val i = expected.indices.firstOrNull { it >= actual.size || expected[it] != actual[it] } ?: expected.size
        return "at element $i: expected `${expected.getOrNull(i)}`, got `${actual.getOrNull(i)}`"
    }
}
