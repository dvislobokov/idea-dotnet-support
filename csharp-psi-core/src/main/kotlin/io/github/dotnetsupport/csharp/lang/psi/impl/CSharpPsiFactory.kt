package io.github.dotnetsupport.csharp.lang.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.parser.CSharpBodyBlockType

/**
 * PSI of a composite node (`CSharpParserDefinition.createElement`): the generated implementation of its Roslyn kind
 * ([CSharpPsiImplTable]). The one place where element types that are not [SyntaxKind] constants map onto a kind is
 * [roslynKind]: body blocks. The composites inside a parsed doc comment (XML and cref nodes, `SkippedTokensTrivia`)
 * come here too and get their generated classes; the doc comment element itself is created by
 * `CSharpDocCommentElementType.createNode` (`CSharpDocCommentImpl`, a `PsiComment` implementing
 * `CSharpDocumentationCommentTrivia`). Composites that stand for tokens get a plain [ASTWrapperPsiElement]: the missing
 * tokens of a doc comment (`CSharpMissingTokenType`, never returned by an accessor) and its zero-width tokens
 * (`EndOfDocumentationCommentToken`, `OmittedArraySizeExpressionToken`). No other composite lacks a class (gated).
 */
object CSharpPsiFactory {
    fun createElement(node: ASTNode): PsiElement {
        val create = CSharpPsiImplTable.constructors[roslynKind(node.elementType)] ?: return ASTWrapperPsiElement(node)
        return create(node)
    }

    /** The Roslyn kind whose class implements elements of [type]. */
    fun roslynKind(type: IElementType): IElementType = if (CSharpBodyBlockType.isBlock(type)) SyntaxKind.Block else type
}
