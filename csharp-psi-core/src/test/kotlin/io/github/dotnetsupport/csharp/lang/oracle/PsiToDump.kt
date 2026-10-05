package io.github.dotnetsupport.csharp.lang.oracle

import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType

/**
 * Maps a PSI (AST) tree onto the oracle's [DumpNode] model so that [TreeDiff] can compare it with `roslyndump`.
 *
 * Element types are classified by two pluggable predicates; the defaults rely on the convention that the debug name
 * (`toString()`) of every element type of the language is the Roslyn `SyntaxKind` name:
 *  - [isTrivia]: `WHITE_SPACE` and kinds ending in `Trivia` (whitespace, comments, directives, disabled text). Dropped
 *    with their subtree: Roslyn's tree has trivia only on tokens, `roslyndump tree` lists it in a separate section.
 *  - [isNode]: composites whose kind does not end in `Token`, `Keyword` or `Trivia` and is not the file element type.
 *    They become nodes. Leaves become tokens; a composite with a token-like name (a lazily parsed token) becomes a token
 *    spanning its text; any other composite (the file element, wrappers) is unwrapped into its children.
 *    When the generated `CSharpElementType`/`CSharpTokenType` classes exist, pass `{ it is CSharpElementType }`.
 *
 * Further rules:
 *  - `PsiErrorElement`s are unwrapped (their children are mapped in place) and counted in [errorElements]. Tokens that
 *    Roslyn skipped (`SkippedTokensTrivia`) are removed by [TreeDiff], which knows the dump's trivia.
 *  - Missing tokens (`CSharpMissingTokenType`, debug name `<Kind> (missing)`: an empty composite, or one holding the
 *    zero-width error element of a reported token) are unwrapped too: a reported one is its error element, as before
 *    the gaps had a type of their own, an unreported one is nothing ([isMissingToken]).
 *  - Spans are Roslyn's `Span`, not `FullSpan`: from the first to the last significant position inside the element.
 *    Significant are non-trivia leaves and **zero-width** node composites and error elements, the counterparts of
 *    Roslyn's missing tokens (zero-width, positioned at the next token after trivia, which is also where an empty
 *    `PsiBuilder` marker lands with the default edge binders; custom binders that
 *    move it show up as range mismatches). An element without significant positions is zero-width at its start offset.
 *  - `CompilationUnit` ends at the end of the text: Roslyn's `EndOfFileToken` is its last token. Our PSI has no such
 *    leaf, and [TreeDiff] ignores zero-width tokens.
 *  - Shift operators: the parser merges `>` `>` with `remapCurrentToken`, so the PSI already has one `>>` leaf, as the
 *    Roslyn tree does; no rule needed. Contextual kinds are not represented (`contextualKind` is null).
 */
class PsiToDump(
    private val isNode: (IElementType) -> Boolean = ::defaultIsNode,
    private val isTrivia: (IElementType) -> Boolean = ::defaultIsTrivia,
) {
    /** Error elements unwrapped so far (over all [map] calls of this instance). */
    var errorElements = 0
        private set

    /** The top-level nodes and tokens of the tree under [root]; [root] itself is mapped like any other element. */
    fun map(root: ASTNode): List<DumpNode> {
        var top = root
        while (top.treeParent != null) top = top.treeParent
        val out = ArrayList<DumpNode>()
        convert(root, top.startOffset + top.textLength, out)
        return out
    }

    private fun convert(n: ASTNode, textEnd: Int, out: MutableList<DumpNode>) {
        val type = n.elementType
        when {
            isTrivia(type) -> Unit
            type == TokenType.ERROR_ELEMENT -> {
                errorElements++
                children(n, textEnd, out)
            }
            isMissingToken(type) -> children(n, textEnd, out)
            n.firstChildNode == null && !isNode(type) -> out += token(n)
            isNode(type) -> {
                val kids = ArrayList<DumpNode>()
                children(n, textEnd, kids)
                var start = first(n)
                var end = last(n)
                if (type.toString() == "CompilationUnit") {
                    // Without tokens (only trivia) the unit is Roslyn's lone `EndOfFileToken` at the end of the text.
                    start = if (n.textLength == 0) textEnd else start ?: textEnd
                    end = textEnd
                }
                val s = start ?: n.startOffset
                out += DumpNode(type.toString(), s, end ?: s, kids)
            }
            looksLikeToken(type) -> out += token(n)
            else -> children(n, textEnd, out)
        }
    }

    private fun children(n: ASTNode, textEnd: Int, out: MutableList<DumpNode>) {
        var c = n.firstChildNode
        while (c != null) {
            convert(c, textEnd, out)
            c = c.treeNext
        }
    }

    private fun token(n: ASTNode) = DumpNode(n.elementType.toString(), n.startOffset, n.startOffset + n.textLength, isToken = true)

    /** True for an element that is a position of its own: a non-trivia token or a zero-width node or error element. */
    private fun isAnchor(n: ASTNode): Boolean {
        val type = n.elementType
        if (isTrivia(type)) return false
        if (isMissingToken(type)) return false
        if (!hasMaterialChildren(n)) return n.textLength > 0 || isNode(type) || type == TokenType.ERROR_ELEMENT
        return !isNode(type) && type != TokenType.ERROR_ELEMENT && looksLikeToken(type)
    }

    /** Children other than unreported missing tokens (empty gaps, nothing in Roslyn's tree): an element without them is empty. */
    private fun hasMaterialChildren(n: ASTNode): Boolean {
        var c = n.firstChildNode
        while (c != null) {
            if (!isMissingToken(c.elementType) || c.firstChildNode != null) return true
            c = c.treeNext
        }
        return false
    }

    /** A non-empty error element holds skipped tokens: Roslyn's `SkippedTokensTrivia`, which never extends a `Span`. */
    private fun isSkippedTokens(n: ASTNode): Boolean = n.elementType == TokenType.ERROR_ELEMENT && n.firstChildNode != null

    /**
     * Roslyn's position of a zero-width token or node: after the trailing trivia of the previous token (whitespace and
     * comments up to and including the first line break), 0 when there is no previous token. An empty PsiBuilder
     * marker sits right after the previous token instead; the difference is only in the position of missing tokens.
     */
    private fun zeroWidthPosition(n: ASTNode): Int {
        var p = prevLeaf(n)
        while (p != null && (p.textLength == 0 || isTrivia(p.elementType))) p = prevLeaf(p)
        if (p == null) return 0
        var pos = p.startOffset + p.textLength
        // Inside an interpolated string Roslyn's nested parser sees no trivia: a missing part (the end token of an
        // unterminated `$"abc` + new line) sits right after the previous part, before the line break.
        var parent = n.treeParent
        while (parent != null && isMissingToken(parent.elementType)) parent = parent.treeParent
        val parentKind = parent?.elementType?.toString()
        if (parentKind == "InterpolatedStringExpression" || parentKind == "Interpolation") return pos
        var l = nextLeaf(p)
        // A misplaced directive in trailing trivia is one `BadToken` of skipped trivia that takes its line break: that
        // break does not end the trailing trivia, the next one does.
        var directiveBreak = false
        while (l != null && l !== n && (l.textLength == 0 || isTrivia(l.elementType))) {
            val name = l.elementType.toString()
            // Roslyn never puts a documentation comment into trailing trivia (`LexSyntaxTrivia`, isTrailing).
            if (name.endsWith("DocumentationCommentTrivia")) break
            if (l.textLength > 0) {
                if (name.endsWith("InDirectiveTrivia") || name == "PreprocessingMessageTrivia") directiveBreak = true
                val text = l.text
                var b = lineBreak(text, 0)
                // Any other trivia (a multi-line comment) is taken whole, as one trivia of Roslyn's.
                if (b >= 0 && l.elementType != TokenType.WHITE_SPACE && !name.endsWith("WhitespaceTrivia") && !name.endsWith("EndOfLineTrivia")) {
                    pos = l.startOffset + l.textLength
                    break
                }
                if (b >= 0 && directiveBreak) {
                    directiveBreak = false
                    b = lineBreak(text, afterLineBreak(text, b))
                }
                if (b >= 0) {
                    pos = l.startOffset + afterLineBreak(text, b)
                    break
                }
                pos = l.startOffset + l.textLength
            }
            l = nextLeaf(l)
        }
        return pos
    }

    // A doc comment is one trivia element here, as the lexer token it is: its lazily parsed XML is not a neighbour.
    private fun prevLeaf(n: ASTNode): ASTNode? = outsideDocComment(TreeUtil.prevLeaf(n))

    private fun nextLeaf(n: ASTNode): ASTNode? = outsideDocComment(TreeUtil.nextLeaf(n))

    private fun outsideDocComment(leaf: ASTNode?): ASTNode? {
        var result = leaf
        var p = leaf?.treeParent
        while (p != null) {
            if (p.elementType.toString().endsWith("DocumentationCommentTrivia")) result = p
            p = p.treeParent
        }
        return result
    }

    private fun anchorStart(n: ASTNode): Int = if (n.textLength == 0) zeroWidthPosition(n) else n.startOffset

    private fun first(n: ASTNode): Int? {
        if (isSkippedTokens(n)) return null
        if (isAnchor(n)) return anchorStart(n)
        if (isTrivia(n.elementType)) return null
        var c = n.firstChildNode
        while (c != null) {
            first(c)?.let { return it }
            c = c.treeNext
        }
        return null
    }

    private fun last(n: ASTNode): Int? {
        if (isSkippedTokens(n)) return null
        if (isAnchor(n)) return anchorStart(n) + n.textLength
        if (isTrivia(n.elementType)) return null
        var c = n.lastChildNode
        while (c != null) {
            last(c)?.let { return it }
            c = c.treePrev
        }
        return null
    }

    companion object {
        /** `WHITE_SPACE` and Roslyn trivia kinds (`*Trivia`: whitespace, end of line, comments, directives). */
        fun defaultIsTrivia(type: IElementType): Boolean =
            type == TokenType.WHITE_SPACE || type.toString().endsWith("Trivia")

        /** Roslyn token kinds end in `Token` or `Keyword`; `BAD_CHARACTER` is the platform's bad token. */
        fun looksLikeToken(type: IElementType): Boolean {
            val name = type.toString()
            return name.endsWith("Token") || name.endsWith("Keyword") || type == TokenType.BAD_CHARACTER
        }

        /** Any element type that is neither trivia nor a token nor the file element type nor the error element nor a gap. */
        fun defaultIsNode(type: IElementType): Boolean =
            type !is IFileElementType && type != TokenType.ERROR_ELEMENT && !defaultIsTrivia(type) && !looksLikeToken(type) &&
                !isMissingToken(type)

        /** The type of a missing token's gap (`CSharpMissingTokenType`): not a node, its error element (if any) counts. */
        fun isMissingToken(type: IElementType): Boolean = type.toString().endsWith(" (missing)")
    }
}

/** Index of the first `\n` or `\r` of [text] from [from], -1 when none. */
private fun lineBreak(text: String, from: Int): Int {
    for (i in from until text.length) if (text[i] == '\n' || text[i] == '\r') return i
    return -1
}

/** The index after the line break at [at] (`\r\n` is one). */
private fun afterLineBreak(text: String, at: Int): Int =
    if (text[at] == '\r' && at + 1 < text.length && text[at + 1] == '\n') at + 2 else at + 1
