package io.github.dotnetsupport.csharp.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.dotnetsupport.csharp.lang.CSharpMissingTokenType
import io.github.dotnetsupport.csharp.lang.CSharpTokenType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes
import io.github.dotnetsupport.csharp.lang.parser.CSharpBodyBlockType

/**
 * The fields of a Roslyn syntax class in slot order (generated from `Syntax.xml`) and the assignment of a node's
 * children to them. Roslyn's lists are not nodes in the PSI: the children of a node are the concatenation of its
 * slots, so they are assigned left to right, each field taking what it accepts. The rules (docs/csharp-psi/GRAMMAR.md, "PSI"):
 *
 *  - *Children*: tokens (leaves) and nodes (composites) in order. Whitespace, comments, directive tokens, disabled text
 *    and non-empty error elements (skipped tokens) are not children. A *gap* is either a missing token (an empty
 *    [CSharpMissingTokenType] composite of its kind, `SyntaxParser.createMissingToken`; reported or not) or a zero-width
 *    error element, a diagnostic on the next token without a kind (passed over).
 *  - *Token field*: a gap of a kind it accepts is its missing token (null); a gap of another kind belongs to a later
 *    field and ends the search; otherwise the next token, when the field accepts it. A field without kinds in
 *    `Syntax.xml` accepts any token not in [Field.stop] (the tokens the following fields may start with).
 *  - *Node field*: the next node of its kinds (gaps without a kind passed over).
 *  - *Lists*: nodes of their kinds while they come; a token list takes tokens of its kinds (or, without kinds, tokens
 *    not in [Field.stop]: `Modifiers`).
 *  - *Separated list*: element, separator, element ...; the separator is `,`, or `;` after a `','` gap (Roslyn's
 *    `EatTokenEvenWithIncorrectKind(CommaToken)` of `ParseCommaSeparatedSyntaxList(allowSemicolonAsSeparator)`: `for`
 *    incrementors, initializers, switch expression arms); a `','` gap or nothing between two elements is a missing
 *    separator, a `','` gap after the last element a missing trailing one; a gap of another kind ends the list.
 *  - `SyntaxNodeOrTokenList` (`BadNamespaceMemberDeclaration.Nodes`) takes every remaining child.
 *  - Inside a doc comment (`DocCommentTreeBuilder`): `DocumentationCommentExteriorTrivia` and `SkippedTokensTrivia`
 *    are trivia, not children; missing tokens are gaps as in the file's tree; a zero-width
 *    token (`EndOfDocumentationCommentToken`, `OmittedArraySizeExpressionToken`) is an empty composite of a token
 *    kind and counts as a token.
 *
 * A body block ([CSharpBodyBlockType]) is matched as [SyntaxKind.Block].
 */
class CSharpSyntaxShape(val roslynClass: String, val fields: Array<Field>) {
    enum class Cat { TOKEN, NODE, TOKEN_LIST, NODE_LIST, SEPARATED_LIST, ANY_LIST }

    /** [accepts]: kinds of the field (of elements for lists); null: any token not in [stop]. */
    class Field(val name: String, val cat: Cat, val accepts: TokenSet?, val optional: Boolean, val stop: TokenSet) {
        fun takesKind(kind: IElementType): Boolean = if (accepts != null) accepts.contains(kind) else !stop.contains(kind)
    }

    /** Value of a separated list slot. */
    class Separated(val elements: List<ASTNode>, val separators: List<ASTNode>)

    fun fieldIndex(name: String): Int = fields.indexOfFirst { it.name == name }

    /** The value of every field for [node], an instance of this shape: an [ASTNode] or null, a list, or [Separated]. */
    fun match(node: ASTNode): Array<Any?> {
        val m = Matcher(children(node))
        val out = arrayOfNulls<Any>(fields.size)
        for ((fi, f) in fields.withIndex()) {
            out[fi] = when (f.cat) {
                Cat.TOKEN -> m.token(f)
                Cat.NODE -> m.node(f)
                Cat.NODE_LIST -> generateSequence { m.node(f) }.toList()
                Cat.TOKEN_LIST -> generateSequence { m.listToken(f) }.toList()
                Cat.SEPARATED_LIST -> m.separated(f)
                Cat.ANY_LIST -> m.rest()
            }
        }
        return out
    }

    private class Matcher(val children: List<ASTNode>) {
        var i = 0

        /** Index of the first child from [from] that is not a gap without a kind. */
        fun skipDiagnostics(from: Int): Int {
            var j = from
            while (j < children.size && isGap(children[j]) && gapKind(children[j]) == null) j++
            return j
        }

        fun token(f: Field): ASTNode? {
            val j = skipDiagnostics(i)
            val c = children.getOrNull(j) ?: return null
            if (isGap(c)) {
                // A missing token: this field's when it takes the kind, a later field's otherwise.
                if (f.takesKind(gapKind(c)!!)) i = j + 1
                return null
            }
            if (isToken(c) && f.takesKind(c.elementType)) {
                i = j + 1
                return c
            }
            return null
        }

        fun node(f: Field): ASTNode? {
            val j = skipDiagnostics(i)
            val c = children.getOrNull(j) ?: return null
            if (isNode(c) && f.accepts!!.contains(kind(c))) {
                i = j + 1
                return c
            }
            return null
        }

        fun listToken(f: Field): ASTNode? {
            val c = children.getOrNull(i) ?: return null
            if (isToken(c) && f.takesKind(c.elementType)) {
                i++
                return c
            }
            return null
        }

        fun separated(f: Field): Separated {
            val elements = ArrayList<ASTNode>()
            val separators = ArrayList<ASTNode>()
            while (true) {
                elements += node(f) ?: break
                // After an element: gaps without a kind are diagnostics; a `','` gap is a missing separator (or the
                // diagnostic of a `;` separator); a gap of another kind belongs to a later field.
                var j = i
                var commaGap = false
                while (j < children.size && isGap(children[j])) {
                    when (gapKind(children[j])) {
                        null -> {}
                        SyntaxKind.CommaToken -> commaGap = true
                        else -> return Separated(elements, separators)
                    }
                    j++
                }
                // A `','` gap after the last element is the list's missing (trailing) separator.
                if (commaGap) i = j
                val c = children.getOrNull(j) ?: break
                when {
                    isToken(c) && c.elementType === SyntaxKind.CommaToken -> { separators += c; i = j + 1 }
                    isToken(c) && c.elementType === SyntaxKind.SemicolonToken && commaGap -> { separators += c; i = j + 1 }
                    isNode(c) && f.accepts!!.contains(kind(c)) -> i = j
                    else -> break
                }
            }
            return Separated(elements, separators)
        }

        fun rest(): List<ASTNode> {
            val list = children.subList(i, children.size).filter { !isGap(it) }
            i = children.size
            return list
        }
    }

    companion object {
        fun token(name: String, accepts: TokenSet?, optional: Boolean = false, stop: TokenSet = TokenSet.EMPTY) =
            Field(name, Cat.TOKEN, accepts, optional, stop)

        fun node(name: String, accepts: TokenSet, optional: Boolean = false) = Field(name, Cat.NODE, accepts, optional, TokenSet.EMPTY)

        fun nodeList(name: String, accepts: TokenSet) = Field(name, Cat.NODE_LIST, accepts, true, TokenSet.EMPTY)

        fun separatedList(name: String, accepts: TokenSet) = Field(name, Cat.SEPARATED_LIST, accepts, true, TokenSet.EMPTY)

        fun tokenList(name: String, accepts: TokenSet?, stop: TokenSet = TokenSet.EMPTY) = Field(name, Cat.TOKEN_LIST, accepts, true, stop)

        fun anyList(name: String) = Field(name, Cat.ANY_LIST, null, true, TokenSet.EMPTY)

        /** Not children of a node: trivia the parser skips. */
        private val TRIVIA: TokenSet = TokenSet.orSet(CSharpTokenTypes.WHITESPACES, CSharpTokenTypes.COMMENTS, TokenSet.WHITE_SPACE)

        /** The children of [node] that fields are made of, gaps (zero-width error elements) included. */
        fun children(node: ASTNode): List<ASTNode> {
            val list = ArrayList<ASTNode>()
            var c = node.firstChildNode
            while (c != null) {
                val t = c.elementType
                if (t === TokenType.ERROR_ELEMENT) {
                    if (c.textLength == 0) list += c
                } else if (t is CSharpMissingTokenType) {
                    list += c
                } else if (!TRIVIA.contains(t) && !DOC_TRIVIA.contains(t)) {
                    list += c
                }
                c = c.treeNext
            }
            return list
        }

        /** Trivia inside a doc comment: Roslyn hangs them on the XML tokens. */
        private val DOC_TRIVIA: TokenSet = TokenSet.create(SyntaxKind.DocumentationCommentExteriorTrivia, SyntaxKind.SkippedTokensTrivia)

        fun isGap(c: ASTNode): Boolean = c.elementType === TokenType.ERROR_ELEMENT || c.elementType is CSharpMissingTokenType

        /** The kind of the missing token a gap stands for; null for a diagnostic (a zero-width error element). */
        fun gapKind(c: ASTNode): IElementType? = (c.elementType as? CSharpMissingTokenType)?.tokenKind

        fun isNode(c: ASTNode): Boolean = c is CompositeElement && !isGap(c) && c.elementType !is CSharpTokenType

        /** A leaf, or a zero-width token of a doc comment (an empty composite of a token kind). */
        fun isToken(c: ASTNode): Boolean = c !is CompositeElement || c.elementType is CSharpTokenType

        /** The Roslyn kind of a child: a body block is a `Block`. */
        fun kind(c: ASTNode): IElementType {
            val t = c.elementType
            return if (CSharpBodyBlockType.isBlock(t)) SyntaxKind.Block else t
        }
    }
}
