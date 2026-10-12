package io.github.dotnetsupport.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.ParserDefinition.SpaceRequirements
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpPsiFactory
import org.jetbrains.annotations.TestOnly
import io.github.dotnetsupport.csharp.lang.CSharpParserDefinition as NativeCSharpParserDefinition
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes as NativeCSharpTokenTypes

/**
 * Which tree a C# file gets (CSHARP_PSI_MIGRATION.md, step 7): the heuristic one of the plugin ([HeuristicCSharpParserDefinition]) or
 * csharp-psi's port of Roslyn's parser (`io.github.dotnetsupport.csharp.lang.CSharpParserDefinition`). The switch is
 * [CSharpFeature.SYNTAX_TREE], application settings only (a parser definition has no project), by the rule of [CSharpFeatures.native]:
 * the heuristic tree by default, the native one when chosen or when the language server is off. A change of the answer rebuilds the
 * PSI of C# files ([CSharpSyntaxTreeSwitch]).
 */
object CSharpSyntaxTrees {
    @Volatile
    private var nativeForTests: Boolean? = null

    /** The last answer of [nativeTree] from the settings, null before the first: what the trees and the index were built with. */
    @Volatile
    var lastAnswer: Boolean? = null
        private set

    /** Asked on every parse and file creation: settings only, no project, no PSI. */
    fun nativeTree(): Boolean {
        nativeForTests?.let { return it }
        val native = CSharpFeatures.native(CSharpFeature.SYNTAX_TREE)
        if (lastAnswer != native) lastAnswer = native
        return native
    }

    /** Forces the tree of files parsed from now on: true the native one, false the heuristic one, null the settings again. */
    @TestOnly
    fun forceNativeTreeForTests(native: Boolean?) {
        nativeForTests = native
    }
}

/**
 * The registered parser definition of C#: a switch between the heuristic tree and csharp-psi's ([CSharpSyntaxTrees.nativeTree]).
 * Lexer, parser and file follow the choice. [createElement] does not: it goes by the element type, so the PSI of a tree of either kind
 * that already exists is right whatever the switch says now. The token sets are the union of both trees' (their token types are
 * distinct objects), for the same reason: whoever asks about a leaf of an older tree gets the right answer.
 */
class CSharpParserDefinition : ParserDefinition {
    private val heuristic: ParserDefinition get() = HeuristicCSharpParserDefinition.INSTANCE
    private val native: ParserDefinition get() = NativeCSharpParserDefinition.NATIVE
    private val current: ParserDefinition get() = if (CSharpSyntaxTrees.nativeTree()) native else heuristic

    override fun createLexer(project: Project?): Lexer = current.createLexer(project)
    override fun createParser(project: Project?): PsiParser = current.createParser(project)
    override fun getFileNodeType(): IFileElementType = current.fileNodeType
    override fun getWhitespaceTokens(): TokenSet = WHITESPACES
    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getStringLiteralElements(): TokenSet = STRINGS
    override fun createFile(viewProvider: FileViewProvider): PsiFile = current.createFile(viewProvider)
    override fun spaceExistenceTypeBetweenTokens(left: ASTNode, right: ASTNode): SpaceRequirements = current.spaceExistenceTypeBetweenTokens(left, right)

    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        is CSharpTokenType -> heuristic.createElement(node)
        else -> CSharpPsiFactory.createElement(node)
    }

    private companion object {
        val WHITESPACES: TokenSet = TokenSet.orSet(TokenSet.WHITE_SPACE, NativeCSharpTokenTypes.WHITESPACES)
        val COMMENTS: TokenSet = TokenSet.orSet(CSharpTokenTypes.COMMENTS, NativeCSharpTokenTypes.COMMENTS)
        // csharp-psi's own definition lists none; the native string tokens are [CSharpLeaves.STRINGS]
        val STRINGS: TokenSet = TokenSet.orSet(CSharpLeaves.STRINGS, NativeCSharpParserDefinition.NATIVE.stringLiteralElements)
    }
}
