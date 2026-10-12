package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.LeafElement
import io.github.dotnetsupport.csharp.lang.CSharpDocCommentElementType
import io.github.dotnetsupport.csharp.lang.CSharpTokenType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.CSharpMissingTokenType
import io.github.dotnetsupport.csharp.lang.oracle.DumpFile
import io.github.dotnetsupport.csharp.lang.oracle.DumpNode
import io.github.dotnetsupport.csharp.lang.oracle.Mismatch
import io.github.dotnetsupport.csharp.lang.oracle.TreeDiff

/**
 * The doc comment comparison of the tree gates (docs/csharp-psi/TESTING.md, "Doc comments"): every doc comment of the oracle's
 * dump (a `V *DocumentationCommentTrivia` record with its structure) against our doc comment element at the same place,
 * expanded (lazily parsed) and mapped onto [DumpNode]s:
 *  - nodes and non-empty tokens by kind and span, as in the main tree; trivia (exterior, white space) is left out;
 *  - zero-width tokens are compared too, unlike the main tree: on both sides they become childless nodes
 *    `<Kind>!missing` (Roslyn's missing tokens, our [CSharpMissingTokenType]) and `<Kind>!zero`
 *    (`EndOfDocumentationCommentToken`, `OmittedArraySizeExpressionToken`), so [TreeDiff] checks kind and position;
 *  - the spans of `SkippedTokensTrivia` inside the comment (the dump's nested `V` records, our composites) must be
 *    equal; tokens inside them are not part of the structure on either side.
 *
 * Our `///` comment ends before the new line of its last line, Roslyn's after it (docs/csharp-psi/GRAMMAR.md, "Doc comments"):
 * the oracle's structure is cut at our end E first: non-empty tokens from E on are dropped (that new line), zero-width
 * ones beyond E move to E, nodes left without children are dropped and node spans are recomputed from their children.
 */
object DocCommentDiff {
    class Outcome(val comments: Int, val mismatches: List<Mismatch>)

    /** Compares the doc comments of [dump] with those under [root] (our file or snippet tree). */
    fun compare(path: String, dump: DumpFile, root: ASTNode, diff: TreeDiff = TreeDiff()): Outcome {
        val docs = dump.trivia.filter { it.kind.endsWith("DocumentationCommentTrivia") && it.structure != null }
        if (docs.isEmpty()) return Outcome(0, emptyList())
        val ours = ArrayList<ASTNode>()
        collect(root, ours)
        val mismatches = ArrayList<Mismatch>()
        var i = 0
        for (v in docs) {
            while (i + 1 < ours.size && ours[i + 1].startOffset <= v.start) i++
            val element = ours.getOrNull(i)?.takeIf { it.startOffset <= v.start && v.start <= it.startOffset + it.textLength }
            if (element == null || element.elementType.toString() != v.kind) {
                mismatches += Mismatch(path, v.start, "doc comment ${v.kind} not found", v.kind, element?.elementType?.toString() ?: "none")
                continue
            }
            val end = element.startOffset + element.textLength
            val expected = normaliseExpected(v.structure!!, end, emptyLastLineAtEof(element, root))
            val actual = map(element)
            mismatches += diff.diff(path, listOfNotNull(expected), listOfNotNull(actual))
            val expectedSkipped = v.inner.filter { it.start < end }.map { it.start to it.end }
            val actualSkipped = ArrayList<Pair<Int, Int>>().also { skipped(element, it) }
            if (expectedSkipped != actualSkipped) {
                mismatches += Mismatch(path, v.start, "doc SkippedTokensTrivia", expectedSkipped.toString(), actualSkipped.toString())
            }
        }
        return Outcome(docs.size, mismatches)
    }

    /** Our doc comment elements in text order; the tree outside them is walked without expanding anything else. */
    private fun collect(n: ASTNode, out: MutableList<ASTNode>) {
        if (n.elementType is CSharpDocCommentElementType) {
            out += n
            return
        }
        var c = n.firstChildNode
        while (c != null) {
            collect(c, out)
            c = c.treeNext
        }
    }

    /** Our doc comment element (expanded) as a [DumpNode] tree; the element itself is the root node. */
    fun map(n: ASTNode): DumpNode? {
        val type = n.elementType
        val start = n.startOffset
        return when {
            type is CSharpMissingTokenType -> DumpNode("${type.tokenKind}!missing", start, start)
            type == SyntaxKind.SkippedTokensTrivia || type == TokenType.WHITE_SPACE ||
                type == SyntaxKind.DocumentationCommentExteriorTrivia -> null
            n is LeafElement -> DumpNode(type.toString(), start, start + n.textLength, isToken = true)
            type is CSharpTokenType && n.textLength == 0 -> DumpNode("$type!zero", start, start)
            else -> {
                val kids = ArrayList<DumpNode>()
                var c = n.firstChildNode
                while (c != null) {
                    map(c)?.let { kids += it }
                    c = c.treeNext
                }
                // The comment element itself starts with the exterior trivia (`///`), Roslyn's span after it.
                if (type is CSharpDocCommentElementType && kids.isNotEmpty()) DumpNode(type.toString(), kids.first().start, kids.last().end, kids)
                else DumpNode(type.toString(), start, start + n.textLength, kids)
            }
        }
    }

    private fun skipped(n: ASTNode, out: MutableList<Pair<Int, Int>>) {
        if (n.elementType == SyntaxKind.SkippedTokensTrivia) {
            out += n.startOffset to n.startOffset + n.textLength
            return
        }
        var c = n.firstChildNode
        while (c != null) {
            skipped(c, out)
            c = c.treeNext
        }
    }

    /**
     * Start of the last line of a `///` comment that ends the file with an empty line (`/// <summary>` + `///` + EOF),
     * -1 otherwise. Roslyn has no new line token after that `///` (none follows), so its missing tokens at the end sit
     * before the line, after the previous new line token; ours go after the `///`, as when a new line follows
     * (`DocCommentTreeBuilder.eodTriviaFirst`: the comment's text alone cannot tell the two apart).
     */
    private fun emptyLastLineAtEof(element: ASTNode, root: ASTNode): Int {
        if (element.elementType.toString() != "SingleLineDocumentationCommentTrivia") return -1
        if (element.startOffset + element.textLength != root.startOffset + root.textLength) return -1
        val text = element.chars
        val lineStart = text.lastIndexOf('\n') + 1
        if (lineStart == 0 || text.subSequence(lineStart, text.length).trim().toString() != "///") return -1
        return element.startOffset + lineStart
    }

    /**
     * The oracle's structure cut at our end [end] (see the class comment), zero-width tokens as `!` nodes; zero-width
     * tokens at [emptyLastLine] ([emptyLastLineAtEof]) move to [end].
     */
    fun normaliseExpected(n: DumpNode, end: Int, emptyLastLine: Int = -1): DumpNode? {
        if (n.isToken) {
            val zero = n.start == n.end
            if (!zero && n.start >= end) return null
            val s = if (zero && n.start == emptyLastLine) end else minOf(n.start, end)
            return when {
                n.missing -> DumpNode("${n.kind}!missing", s, s)
                zero -> DumpNode("${n.kind}!zero", s, s)
                else -> n
            }
        }
        val kids = n.children.mapNotNull { normaliseExpected(it, end, emptyLastLine) }
        if (kids.isEmpty()) return null
        return n.copy(start = kids.first().start, end = kids.last().end, children = kids)
    }
}
