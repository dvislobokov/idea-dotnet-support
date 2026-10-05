package io.github.dotnetsupport.csharp.lang.doc

import com.intellij.lang.ASTFactory
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.TreeElement
import com.intellij.psi.tree.IElementType
import com.intellij.util.CharTable
import io.github.dotnetsupport.csharp.lang.CSharpElementType
import io.github.dotnetsupport.csharp.lang.CSharpMissingTokenType
import io.github.dotnetsupport.csharp.lang.SyntaxKind

/**
 * Builds the children of a doc comment element from the parser's green tree, PsiBuilder-like:
 *  - every token is a leaf, preceded by its leading trivia: `DocumentationCommentExteriorTrivia` leaves (`///`, the
 *    delimiters of a delimited comment, leading `*`) and white space (Roslyn's WhitespaceTrivia and EndOfLineTrivia). The trivia before the first
 *    token of a node goes before the node, so a node starts at its first token as Roslyn's `Span` does;
 *  - zero-width tokens are empty composites: missing tokens of [CSharpMissingTokenType], the
 *    `EndOfDocumentationCommentToken` (after its trivia) and `OmittedArraySizeExpressionToken` of their own kind;
 *  - each skipped token (Roslyn's trailing `SkippedTokensTrivia`, one per token) is a `SkippedTokensTrivia` composite
 *    holding the token's leaf, after all nodes that end with the token it trails, so their ranges exclude it;
 *  - missing tokens at the end of a `///` comment whose last line is empty (`/// <summary>` + Enter) go after that
 *    line's `///`: Roslyn's go after the new line token we drop ([eodTriviaFirst]).
 * The green tree is walked without recursion: crefs like `A.A.A...` or `int***...` nest as deep as they are long.
 */
internal class DocCommentTreeBuilder private constructor(
    private val text: CharSequence,
    private val charTable: CharTable?,
    private val eod: DocGreenToken?,
) {
    private val holder: CompositeElement = ASTFactory.composite(HOLDER)
    private val stack = ArrayList<CompositeElement>().apply { add(holder) }
    private val pending = ArrayList<IElementType>()
    private var pendingSkipped: List<DocGreenToken>? = null
    private var pos = 0

    /**
     * The final new line of a `///` comment is a token in Roslyn when the comment's last line is empty and the mode
     * makes new lines tokens (text, attribute text): then `EndOfDocumentationCommentToken`'s leading trivia (ours) is
     * just that line's exterior, and Roslyn's missing tokens at the end follow the dropped new line token, i.e. that
     * exterior. Tag, cref and name modes make the new line trivia, which starts with white space then.
     */
    private val eodTriviaFirst: Boolean = eod != null && eod.trivia != null && eod.trivia[0] == DocToken.EXTERIOR
    private var eodTriviaEmitted = false

    private fun top(): CompositeElement = stack[stack.size - 1]

    private fun add(child: TreeElement) = top().rawAddChildrenWithoutNotifications(child)

    private fun materialize() {
        for (kind in pending) {
            val c = ASTFactory.composite(kind)
            add(c)
            stack += c
        }
        pending.clear()
    }

    private fun leaf(kind: IElementType, start: Int, end: Int): TreeElement {
        val chars = charTable?.intern(text, start, end) ?: text.subSequence(start, end)
        return ASTFactory.leaf(kind, chars)
    }

    private fun trivia(fullStart: Int, start: Int, trivia: IntArray?) {
        check(fullStart == pos) { "token at $fullStart, text emitted up to $pos" }
        if (trivia != null) {
            var i = 0
            while (i < trivia.size) {
                val s = trivia[i + 1]
                val e = trivia[i + 2]
                check(s == pos) { "trivia at $s, text emitted up to $pos" }
                add(
                    if (trivia[i] == DocToken.EXTERIOR) leaf(SyntaxKind.DocumentationCommentExteriorTrivia, s, e)
                    else ASTFactory.whitespace(charTable?.intern(text, s, e) ?: text.subSequence(s, e)),
                )
                pos = e
                i += 3
            }
        }
        check(pos == start) { "leading trivia ends at $pos, token starts at $start" }
    }

    private fun flushSkipped() {
        val skipped = pendingSkipped ?: return
        pendingSkipped = null
        for (t in skipped) {
            trivia(t.fullStart, t.start, t.trivia)
            // Roslyn drops zero-width skipped tokens (SyntaxParser.AddSkippedSyntax).
            if (t.end > t.start) {
                val c = ASTFactory.composite(SyntaxKind.SkippedTokensTrivia)
                add(c)
                c.rawAddChildrenWithoutNotifications(leaf(t.kind, t.start, t.end))
                pos = t.end
            }
        }
    }

    /** Emits [root]'s subtree; an explicit stack instead of recursion (see the class comment). */
    private fun emit(root: DocGreen) {
        val work = ArrayList<Any>()
        work += root
        while (work.isNotEmpty()) {
            when (val g = work.removeAt(work.size - 1)) {
                CLOSE -> {
                    if (pending.isNotEmpty()) materialize() // an empty node
                    stack.removeAt(stack.size - 1)
                }
                is DocGreenNode -> {
                    flushSkipped()
                    pending += g.kind
                    work += CLOSE
                    for (i in g.children.indices.reversed()) work += g.children[i]
                }
                is DocGreenToken -> emitToken(g)
            }
        }
    }

    private fun emitToken(g: DocGreenToken) {
        flushSkipped()
        when {
            g.missing -> {
                if (eodTriviaFirst && !eodTriviaEmitted && pos == eod!!.fullStart) {
                    trivia(eod.fullStart, eod.start, eod.trivia)
                    eodTriviaEmitted = true
                }
                materialize()
                add(ASTFactory.composite(CSharpMissingTokenType.of(g.kind)))
            }
            g.fullStart < 0 -> { // OmittedArraySizeExpressionToken
                materialize()
                add(ASTFactory.composite(g.kind))
            }
            else -> {
                if (g === eod && eodTriviaEmitted) check(pos == g.start) else trivia(g.fullStart, g.start, g.trivia)
                materialize()
                add(if (g.end == g.start) ASTFactory.composite(g.kind) else leaf(g.kind, g.start, g.end))
                pos = g.end
            }
        }
        pendingSkipped = g.skipped
    }

    companion object {
        private val HOLDER = CSharpElementType("DocCommentHolder")
        private val CLOSE = Any()

        /**
         * Parses [text], a doc comment ([delimited]: `/** */`), and returns the first of the sibling chain of its
         * children (what `ILazyParseableElementType.parseContents` returns); the leaves spell [text] exactly.
         */
        fun build(text: CharSequence, delimited: Boolean, charTable: CharTable?): TreeElement? {
            val root = DocumentationCommentParser(text, delimited).parseDocumentationComment()
            val eod = (root.children.lastOrNull() as? DocGreenToken)
                ?.takeIf { !delimited && it.kind == SyntaxKind.EndOfDocumentationCommentToken && !it.missing }
            val b = DocCommentTreeBuilder(text, charTable, eod)
            root.children.forEach(b::emit)
            b.flushSkipped()
            check(b.pos == text.length) { "doc comment tree covers ${b.pos} of ${text.length} characters" }
            return b.holder.firstChildNode as TreeElement?
        }
    }
}
