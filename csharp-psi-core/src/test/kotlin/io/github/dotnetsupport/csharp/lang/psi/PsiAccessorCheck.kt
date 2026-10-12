package io.github.dotnetsupport.csharp.lang.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.tree.IFileElementType
import io.github.dotnetsupport.csharp.lang.CSharpDocCommentElementType
import io.github.dotnetsupport.csharp.lang.CSharpDocCommentImpl
import io.github.dotnetsupport.csharp.lang.CSharpTokenType
import io.github.dotnetsupport.csharp.lang.CSharpMissingTokenType
import io.github.dotnetsupport.csharp.lang.oracle.DumpNode
import io.github.dotnetsupport.csharp.lang.oracle.DumpTrivia
import io.github.dotnetsupport.csharp.lang.oracle.Mismatch
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpPsiFactory

/**
 * The check of the generated PSI against Roslyn (docs/csharp-psi/GRAMMAR.md, "PSI"; docs/csharp-psi/TESTING.md, "PSI accessor gate"): a
 * `roslyndump --fields` tree, whose children name the field of their parent, against our tree of the same text.
 *
 *  - *Classes*: every composite of our tree (error elements and the file element aside) is an instance of the
 *    implementation generated for its kind (`elementsWithWrongClass`); every node kind in Roslyn's tree has a generated
 *    class (`nodesWithoutPsiClass`).
 *  - *Alignment*: Roslyn's nodes and ours are paired in parallel, the node children of each pair in order (our error
 *    elements are transparent); a pair whose node children differ in number or kinds is an `alignment` mismatch and is
 *    not descended into (the tree gates guarantee none on their corpora).
 *  - *Accessors*: for every paired node and every field of its class ([CSharpPsiFieldTable], through the public
 *    accessor), the elements the accessor returns equal the children Roslyn assigns to that field: nodes the very
 *    paired element, tokens the same kind and range, lists the same elements in order (a separated list: elements and
 *    separators). Roslyn's missing tokens and zero-width tokens (`EndOfFileToken`, `OmittedArraySizeExpressionToken`,
 *    `OmittedTypeArgumentToken`) have no PSI leaf: expected absent (null). Each field of each node is one
 *    `accessorCheck`; a difference is one `accessorMismatch`, class `<RoslynClass>.<Field>`.
 *
 * *Doc comments* (the dump's `V *DocumentationCommentTrivia` records with their structure) are checked the same way
 * against our doc comment element at the same offset, expanded: its class is `CSharpDocCommentImpl`, the XML and cref
 * nodes inside are paired and their accessors checked; here zero-width tokens other than missing ones
 * (`EndOfDocumentationCommentToken`, `OmittedArraySizeExpressionToken`) exist on our side (empty composites) and must
 * be returned, missing tokens (`CSharpMissingTokenType`) must not. Our `///` comment ends before the new line of its
 * last line, Roslyn's after it (docs/csharp-psi/GRAMMAR.md, "Doc comments"): Roslyn's structure is cut at our end as the tree
 * gates do ([cut]). The other structured trivia (directives, skipped tokens outside doc comments) are not checked:
 * our tree has no nodes for them (directive tokens are leaves, skipped tokens error elements).
 */
class PsiAccessorCheck {
    var files = 0L
    var nodes = 0L
    var accessorChecks = 0L
    var accessorMismatches = 0L
    var nodesWithoutPsiClass = 0L
    var elementsWithWrongClass = 0L
    var alignmentMismatches = 0L
    var docComments = 0L
    val mismatches = ArrayList<Mismatch>()

    /**
     * Checks one file: [expected] are the dump's roots (with fields), [root] our root (the file element or a composite),
     * [trivia] the dump's trivia section (its doc comments are checked).
     */
    fun check(path: String, expected: List<DumpNode>, root: ASTNode, trivia: List<DumpTrivia> = emptyList()): List<Mismatch> {
        val before = mismatches.size
        files++
        checkClasses(path, root)
        val ours = nodeChildren(root)
        val roslyn = expected.filter { !it.isToken }
        if (!sameKinds(roslyn, ours)) {
            add(path, roslyn.firstOrNull()?.start ?: 0, "alignment", roslyn.joinToString { it.kind }, ours.joinToString { kindName(it) })
            alignmentMismatches++
        } else {
            for (k in roslyn.indices) pair(path, roslyn[k], ours[k])
        }
        checkDocComments(path, trivia, root)
        return mismatches.subList(before, mismatches.size).toList()
    }

    private fun checkDocComments(path: String, trivia: List<DumpTrivia>, root: ASTNode) {
        val docs = trivia.filter { it.kind.endsWith("DocumentationCommentTrivia") && it.structure != null }
        if (docs.isEmpty()) return
        // By start offset; Roslyn's trivia span starts after the leading exterior (`///`), ours with it.
        val ours = java.util.TreeMap<Int, ASTNode>()
        collectDocComments(root, ours)
        for (v in docs) {
            docComments++
            val element = ours.floorEntry(v.start)?.value?.takeIf { v.start <= it.startOffset + it.textLength }
            if (element == null || element.elementType.toString() != v.kind) {
                alignmentMismatches++
                add(path, v.start, "doc comment not found", v.kind, element?.elementType?.toString() ?: "none")
                continue
            }
            val expected = cut(v.structure!!, element.startOffset + element.textLength)
            if (expected == null) {
                alignmentMismatches++
                add(path, v.start, "doc comment empty after the cut", v.kind, kindName(element))
                continue
            }
            pair(path, expected, element, inDoc = true)
        }
    }

    private fun checkClasses(path: String, root: ASTNode) {
        var c = root.firstChildNode
        while (c != null) {
            if (c.elementType is CSharpDocCommentElementType) {
                if (c.psi !is CSharpDocCommentImpl) {
                    elementsWithWrongClass++
                    add(path, c.startOffset, "class of ${kindName(c)}", "CSharpDocCommentImpl", c.psi.javaClass.simpleName)
                }
            } else if (c is CompositeElement && c.elementType !== TokenType.ERROR_ELEMENT && c.elementType !is IFileElementType &&
                !isTokenComposite(c)
            ) {
                val expected = CSharpPsiFieldTable.classByKind[kindName(c)]
                val psi = c.psi
                if (expected == null || psi.javaClass != expected.third) {
                    elementsWithWrongClass++
                    add(path, c.startOffset, "class of ${kindName(c)}", expected?.third?.simpleName ?: "(no class)", psi.javaClass.simpleName)
                }
            }
            if (c is CompositeElement) checkClasses(path, c)
            c = c.treeNext
        }
    }

    private fun pair(path: String, r: DumpNode, p: ASTNode, inDoc: Boolean = false) {
        nodes++
        val roslynClass = CSharpPsiFieldTable.classByKind[r.kind]?.first
        if (roslynClass == null) {
            nodesWithoutPsiClass++
            add(path, r.start, "no PSI class", r.kind, "-")
            return
        }
        val rNodes = r.children.filter { !it.isToken }
        val pNodes = nodeChildren(p)
        if (!sameKinds(rNodes, pNodes)) {
            alignmentMismatches++
            add(path, r.start, "alignment in ${r.kind}", rNodes.joinToString { it.kind }, pNodes.joinToString { kindName(it) })
            return
        }
        val paired = java.util.IdentityHashMap<DumpNode, ASTNode>()
        for (k in rNodes.indices) paired[rNodes[k]] = pNodes[k]
        val psi = p.psi
        val fields = CSharpPsiFieldTable.fieldsByClass.getValue(roslynClass)
        for (child in r.children) {
            if (child.field == null) error("$path: ${r.kind} at ${r.start}: a child without a field (run roslyndump with --fields)")
            if (fields.none { it.name == child.field }) {
                accessorMismatches++
                add(path, child.start, "$roslynClass.${child.field} unknown field", child.toString(), "-")
            }
        }
        for (f in fields) {
            accessorChecks++
            // Zero-width tokens: never a PSI leaf in the file's tree; in a doc comment an empty composite, missing ones aside.
            val expected = r.children.filter { it.field == f.name && !(it.isToken && (it.missing || (it.start == it.end && !inDoc))) }
            val actual = try {
                f.get(psi)
            } catch (e: RuntimeException) {
                accessorMismatches++
                add(path, r.start, "$roslynClass.${f.name}", expected.joinToString(), e.toString())
                continue
            }
            val same = expected.size == actual.size && expected.indices.all { k ->
                val e = expected[k]
                val a = actual[k]
                if (e.isToken) (a.node !is CompositeElement || inDoc && isTokenComposite(a.node)) && a.node.elementType.toString() == e.kind &&
                    a.textRange.startOffset == e.start && a.textRange.endOffset == e.end
                else paired[e] === a.node
            }
            if (!same) {
                accessorMismatches++
                add(path, r.start, "$roslynClass.${f.name}", expected.joinToString(), actual.joinToString { describe(it) })
            }
        }
        for (k in rNodes.indices) pair(path, rNodes[k], pNodes[k], inDoc)
    }

    private fun describe(e: PsiElement): String = "(${kindName(e.node)} ${e.textRange.startOffset}-${e.textRange.endOffset})"

    private fun add(path: String, offset: Int, cls: String, expected: String, actual: String) {
        mismatches += Mismatch(path, offset, cls, expected, actual)
    }

    companion object {
        /** The Roslyn kind of our element (a body block is `Block`). */
        fun kindName(n: ASTNode): String = CSharpPsiFactory.roslynKind(n.elementType).toString()

        /** Composites that stand for tokens: missing and zero-width tokens of a doc comment. */
        fun isTokenComposite(n: ASTNode): Boolean =
            n.elementType is CSharpMissingTokenType || n.elementType is CSharpTokenType && n.textLength == 0

        private fun collectDocComments(n: ASTNode, out: MutableMap<Int, ASTNode>) {
            if (n.elementType is CSharpDocCommentElementType) {
                out[n.startOffset] = n
                return
            }
            var c = n.firstChildNode
            while (c != null) {
                collectDocComments(c, out)
                c = c.treeNext
            }
        }

        /**
         * Roslyn's doc comment structure cut at our end [end] (as `DocCommentDiff.normaliseExpected`, fields kept):
         * non-empty tokens from [end] on are dropped (the final new line of a `///` comment), zero-width ones beyond it
         * move to it, nodes left without children are dropped, node spans follow their children.
         */
        fun cut(n: DumpNode, end: Int): DumpNode? {
            if (n.isToken) {
                if (n.start != n.end && n.start >= end) return null
                return if (n.start > end) n.copy(start = end, end = end) else n
            }
            val kids = n.children.mapNotNull { cut(it, end) }
            if (kids.isEmpty()) return null
            return n.copy(start = kids.first().start, end = kids.last().end, children = kids)
        }

        private fun sameKinds(r: List<DumpNode>, p: List<ASTNode>): Boolean =
            r.size == p.size && r.indices.all { r[it].kind == kindName(p[it]) }

        /**
         * Node children of [n] in order: composites other than error elements (looked into), doc comments, and the
         * skipped tokens and token composites of a doc comment.
         */
        fun nodeChildren(n: ASTNode): List<ASTNode> {
            val out = ArrayList<ASTNode>()
            fun walk(x: ASTNode) {
                var c = x.firstChildNode
                while (c != null) {
                    if (c is CompositeElement) {
                        val t = c.elementType
                        when {
                            t === TokenType.ERROR_ELEMENT || t is IFileElementType -> walk(c)
                            // Not nodes: doc comments (trivia of the file's tree), skipped tokens and token composites inside them.
                            t is CSharpDocCommentElementType || t is CSharpTokenType || t is CSharpMissingTokenType -> {}
                            else -> out += c
                        }
                    }
                    c = c.treeNext
                }
            }
            walk(n)
            return out
        }
    }
}
