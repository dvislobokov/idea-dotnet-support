package io.github.dotnetsupport.csharp.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.impl.PsiBuilderImpl
import com.intellij.lang.PsiParser
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexer
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes
import io.github.dotnetsupport.csharp.lang.parser.LanguageParser
import io.github.dotnetsupport.csharp.lang.parser.SyntaxParser
import io.github.dotnetsupport.csharp.lang.parser.parseCompilationUnitCore
import io.github.dotnetsupport.csharp.lang.psi.CSharpCompilationUnit
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpPsiFactory
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * Lexer of step 4 and the parser of step 5 (a port of Roslyn's `LanguageParser`): the file element holds one
 * `CompilationUnit` node, Roslyn's tree root (docs/csharp-psi/GRAMMAR.md, "Declarations: the file element"). A file is lexed
 * with its own preprocessor symbols ([CSharpFileElementType]); [createLexer] (no file at hand) uses the IDE default.
 * Directive tokens and disabled text are in [getCommentTokens]: the parser never sees them.
 *
 * In the plugin this definition is not registered: the host's `io.github.dotnetsupport.lang.CSharpParserDefinition` is, and it switches
 * between its heuristic tree and this one. So nothing here looks the definition up by language: the builders of this module take
 * [NATIVE] explicitly ([createBuilder]). This module's tests register it directly (src/test/resources/META-INF/plugin.xml).
 */
class CSharpParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = CSharpLexer(CSharpPreprocessorSymbols.IDE_DEFAULT)
    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val file = builder.mark()
        parseCompilationUnit(builder)
        file.done(root)
        builder.treeBuilt
    }
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getWhitespaceTokens(): TokenSet = CSharpTokenTypes.WHITESPACES
    override fun getCommentTokens(): TokenSet = CSharpTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    /** The class generated from Syntax.xml for the node's kind (docs/csharp-psi/GRAMMAR.md, "PSI"). */
    override fun createElement(node: ASTNode): PsiElement = CSharpPsiFactory.createElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = CSharpFile(viewProvider)

    companion object {
        @JvmField val FILE: IFileElementType = CSharpFileElementType()

        /** The definition of this module's tree, whatever definition the language has registered. */
        @JvmField val NATIVE: CSharpParserDefinition = CSharpParserDefinition()

        /**
         * A builder over [chameleon] with [NATIVE]'s whitespace and comment tokens. `PsiBuilderFactory.createBuilder(.., language, ..)` would
         * take them from the registered definition, which in the plugin is the host's switch.
         */
        fun createBuilder(project: Project, chameleon: ASTNode, lexer: Lexer): PsiBuilder = PsiBuilderImpl(project, NATIVE, lexer, chameleon, chameleon.chars)

        /** `ParseCompilationUnit` (LP 168): the `CompilationUnit` node of the whole text, under the stack guard. */
        fun parseCompilationUnit(builder: PsiBuilder): SyntaxParser.Companion.StackGuardResult<LanguageParser> {
            val guarded = SyntaxParser.parseWithStackGuard(builder, builder.mark(), ::LanguageParser) { it.parseCompilationUnitCore() }
            guarded.root.done(SyntaxKind.CompilationUnit)
            return guarded
        }
    }
}

/**
 * The file element type: parses with a lexer over the file's preprocessor symbols ([CSharpPreprocessorSymbols.forFile])
 * and a parser at the file's language version ([CSharpLanguageLevel.forFile], put on the builder).
 */
class CSharpFileElementType : IFileElementType(CSharpLanguage) {
    override fun doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode? {
        val project = psi.project
        val lexer = CSharpLexer(CSharpPreprocessorSymbols.forFile(psi.containingFile))
        val builder = CSharpParserDefinition.createBuilder(project, chameleon, lexer)
        builder.putUserData(CSharpLanguageLevel.KEY, CSharpLanguageLevel.forFile(psi.containingFile))
        // the native parser itself, not the registered definition's (the host's switch in the plugin)
        return CSharpParserDefinition.NATIVE.createParser(project).parse(this, builder).firstChildNode
    }
}

/**
 * A C# file of either tree: [fileElementType] is [CSharpParserDefinition.FILE] for this module's tree; the host passes its heuristic one
 * (`HeuristicCSharpFile`), so that `is CSharpFile` holds for both. The file type is the view provider's: the host's in the plugin.
 */
open class CSharpFile(viewProvider: FileViewProvider, fileElementType: IFileElementType = CSharpParserDefinition.FILE) :
    PsiFileBase(viewProvider, CSharpLanguage) {
    init {
        // PsiFileBase took the element type of the registered definition, which in the plugin depends on the switch
        this.init(fileElementType, fileElementType)
    }

    override fun getFileType(): FileType = viewProvider.fileType
    override fun toString(): String = "C# file"

    /** Roslyn's root node; null only while the file is empty of any element (never after a parse), and on the host's heuristic tree. */
    val compilationUnit: CSharpCompilationUnit? get() = findChildByClass(CSharpCompilationUnit::class.java)
}
