package io.github.dotnetsupport.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * The heuristic tree: [CSharpTreeBuilder] groups the tokens into a node per declaration (namespace, type, member),
 * which is what Structure view, breadcrumbs, folding and Go to Class need. Inside a member the tokens are a flat list:
 * enough for highlighting, commenting, brace matching and word selection.
 *
 * Not registered: [CSharpParserDefinition] is, and it delegates here unless [CSharpSyntaxTrees.nativeTree] chooses csharp-psi's tree.
 */
class HeuristicCSharpParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = CSharpLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser(CSharpTreeBuilder::build)

    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = CSharpTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = CSharpTokenTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement =
        if (CSharpElementTypes.kindOf(node.elementType) != null) CSharpDeclaration(node) else ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = HeuristicCSharpFile(viewProvider)

    companion object {
        @JvmField val INSTANCE = HeuristicCSharpParserDefinition()
        @JvmField val FILE = IFileElementType(CSharpLanguage)
    }
}
