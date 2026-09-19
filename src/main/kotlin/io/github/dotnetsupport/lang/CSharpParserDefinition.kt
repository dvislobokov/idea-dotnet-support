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
 * There is no real C# parser yet: the file is a flat list of tokens.
 * That is enough for highlighting, commenting, brace matching and word selection.
 */
class CSharpParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = CSharpLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val file = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        file.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = CSharpTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = CSharpTokenTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = CSharpFile(viewProvider)

    companion object {
        val FILE = IFileElementType(CSharpLanguage)
    }
}
