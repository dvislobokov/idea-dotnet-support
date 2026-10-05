package io.github.dotnetsupport.lang

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind

/**
 * Questions about the leaves of a C# PSI tree of either kind (CSHARP_PSI_MIGRATION.md, step 7): the heuristic tree has the token types of
 * [CSharpTokenTypes], the native one Roslyn's kinds ([SyntaxKind]), so whoever walks the leaves asks here rather than compares types.
 */
object CSharpLeaves {
    private val HOST_PUNCTUATION = TokenSet.create(
        CSharpTokenTypes.LBRACE, CSharpTokenTypes.RBRACE, CSharpTokenTypes.LPAREN, CSharpTokenTypes.RPAREN, CSharpTokenTypes.LBRACKET,
        CSharpTokenTypes.RBRACKET, CSharpTokenTypes.SEMICOLON, CSharpTokenTypes.COMMA, CSharpTokenTypes.DOT, CSharpTokenTypes.OPERATOR,
    )

    /** String and character literals of both trees; of an interpolated string only its text, the holes are code. */
    val STRINGS: TokenSet = TokenSet.orSet(
        CSharpTokenTypes.STRINGS,
        TokenSet.create(
            SyntaxKind.StringLiteralToken, SyntaxKind.CharacterLiteralToken, SyntaxKind.InterpolatedStringTextToken,
            SyntaxKind.SingleLineRawStringLiteralToken, SyntaxKind.MultiLineRawStringLiteralToken, SyntaxKind.Utf8StringLiteralToken,
            SyntaxKind.Utf8SingleLineRawStringLiteralToken, SyntaxKind.Utf8MultiLineRawStringLiteralToken,
        ),
    )

    fun isIdentifier(type: IElementType?): Boolean = type === CSharpTokenTypes.IDENTIFIER || type === SyntaxKind.IdentifierToken
    fun isIdentifier(element: PsiElement): Boolean = isIdentifier(element.elementType)

    /** The keyword [text]: `KEYWORD` of the heuristic tree, a keyword kind (`StaticKeyword`) of the native one. */
    fun isKeyword(element: PsiElement, text: String): Boolean {
        val type = element.elementType ?: return false
        return (type === CSharpTokenTypes.KEYWORD || CSharpSyntaxFacts.KeywordKinds.contains(type)) && element.textMatches(text)
    }

    /** The punctuation [text] (`(`, `;`, `{`); never a zero-width token the native parser put in for a missing one. */
    fun isPunctuation(element: PsiElement, text: String): Boolean {
        val type = element.elementType ?: return false
        return (HOST_PUNCTUATION.contains(type) || CSharpSyntaxFacts.PunctuationKinds.contains(type)) && element.textMatches(text)
    }

    /** Whitespace, a comment or a leaf inside one: a native doc comment is a comment with a structure of its own under it. */
    fun isTrivia(element: PsiElement): Boolean = element is PsiWhiteSpace || commentAround(element) != null

    /** A string literal (its text) or a comment, a doc comment's insides included. */
    fun isInStringOrComment(element: PsiElement): Boolean = STRINGS.contains(element.elementType) || commentAround(element) != null

    /** The leaf next to [element] (before it, unless [forward]) that is code: whitespace and comments are skipped, a doc comment as a whole. */
    fun codeLeaf(element: PsiElement, forward: Boolean): PsiElement? {
        var leaf = step(element, forward)
        while (leaf != null) {
            leaf = when {
                leaf is PsiWhiteSpace -> step(leaf, forward)
                else -> step(commentAround(leaf) ?: return leaf, forward)
            }
        }
        return null
    }

    /**
     * A leaf a line marker belongs to: the platform asks every element, and on the native tree an empty node (no children) or a zero-width
     * token the parser put in for a missing one may start at the same offset as the real token, which would give the marker twice.
     */
    fun isMarkerLeaf(element: PsiElement): Boolean = element.node is LeafElement && element.textLength > 0

    private fun step(element: PsiElement, forward: Boolean): PsiElement? = if (forward) PsiTreeUtil.nextLeaf(element) else PsiTreeUtil.prevLeaf(element)

    private fun commentAround(element: PsiElement): PsiComment? =
        element as? PsiComment ?: PsiTreeUtil.getParentOfType(element, PsiComment::class.java, true, CSharpFile::class.java)
}

/**
 * Hides the error elements of the native parser in C# files, always: with `CSharpFeature.DIAGNOSTICS` ROSLYN the language server reports
 * the syntax errors, with NATIVE [NativeCSharpDiagnosticsAnnotator] does, at Roslyn's spans and with its codes. An element's own range is
 * not where Roslyn reports (a missing `;` is after the line break, a skipped token may carry no diagnostic), so the elements themselves
 * would show the errors twice and in the wrong places. The heuristic tree has no error elements.
 */
class CSharpHighlightErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean = element.containingFile !is CSharpFile
}
