package io.github.dotnetsupport.frameworks.razor

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lang.html.HTMLLanguage
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.FileViewProviderFactory
import com.intellij.psi.MultiplePsiFilesPerDocumentFileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.source.tree.SharedImplUtil
import com.intellij.psi.templateLanguages.TemplateDataElementType
import com.intellij.psi.templateLanguages.TemplateLanguageFileViewProvider
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.github.dotnetsupport.razor.lang.parser.RazorSyntaxTree
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind

/** The file of the Razor tree; its markup is in the HTML file of the same view ([RazorFileViewProvider]). */
class RazorFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, RazorLanguage) {
    override fun getFileType(): FileType = RazorFileType
    override fun toString(): String = "Razor File"
    val kind: RazorFileKind get() = RazorFileKind.of(name)

    /** The root of the compiler's tree. */
    val document: RazorDocumentPsi? get() = findChildByClass(RazorDocumentPsi::class.java)

    /** The compiler's tree of the last parse, with its diagnostics; null before the first. */
    val syntaxTree: RazorSyntaxTree?
        get() {
            node // loads the AST, which parses
            return getUserData(RazorAstBuilder.TREE)
        }

    /** The directives of the file in order: `@page`, `@using`, `@inject`, `@code`... */
    val directives: List<RazorDirectivePsi> get() = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(this, RazorDirectivePsi::class.java).toList()
}

/** The element type of a Razor file: the compiler's parser (razor-psi-core, R2) with the options of the kind the name of the file gives. */
object RazorFileElementType : IFileElementType("RAZOR_FILE", RazorLanguage) {
    private fun readResolve(): Any = RazorFileElementType

    override fun doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode {
        val name = psi.containingFile?.viewProvider?.virtualFile?.name ?: psi.containingFile?.name ?: "file.razor"
        val tree = RazorAstBuilder.parse(chameleon.chars, name)
        psi.containingFile?.putUserData(RazorAstBuilder.TREE, tree)
        return RazorAstBuilder.build(tree, SharedImplUtil.findCharTableByTree(chameleon))
    }
}

class RazorParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = RazorLexer(RazorFileKind.COMPONENT)
    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder -> unsupported(root, builder) }
    override fun getFileNodeType(): IFileElementType = RazorFileElementType
    override fun getWhitespaceTokens(): TokenSet = TokenSet.EMPTY
    override fun getCommentTokens(): TokenSet = RazorTreeTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createFile(viewProvider: FileViewProvider): PsiFile = RazorFile(viewProvider)

    override fun createElement(node: ASTNode): PsiElement = when ((node.elementType as? RazorTreeNodeType)?.kind) {
        RazorSyntaxKind.RazorDocument -> RazorDocumentPsi(node)
        RazorSyntaxKind.RazorDirective, RazorSyntaxKind.RazorUsingDirective, RazorSyntaxKind.RazorDocumentationDirective -> RazorDirectivePsi(node)
        RazorSyntaxKind.CSharpStatementLiteral, RazorSyntaxKind.CSharpExpressionLiteral -> RazorCodeLiteral(node)
        else -> RazorElement(node)
    }

    /** The file is parsed by [RazorFileElementType] from its text; a builder-driven parse of a fragment is not a thing of this language. */
    private fun unsupported(root: IElementType, builder: PsiBuilder): ASTNode {
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        return builder.treeBuilt
    }
}

/** The HTML file of a Razor view: the markup of the compiler's tree, with the Razor in it as outer elements. */
object RazorTemplateDataElementType : TemplateDataElementType("RAZOR_HTML", RazorLanguage, RazorTokenTypes.MARKUP, RazorTokenTypes.OUTER) {
    private fun readResolve(): Any = RazorTemplateDataElementType

    override fun createBaseLexer(viewProvider: TemplateLanguageFileViewProvider): Lexer = RazorTemplateLexer(viewProvider.virtualFile.name)
}

/** Razor as the base language, HTML as the template data (its tags, colours, Emmet, folding), the platform's way of JSP and others. */
class RazorFileViewProvider(manager: PsiManager, file: VirtualFile, eventSystemEnabled: Boolean) :
    MultiplePsiFilesPerDocumentFileViewProvider(manager, file, eventSystemEnabled), TemplateLanguageFileViewProvider {

    override fun getBaseLanguage(): Language = RazorLanguage
    override fun getTemplateDataLanguage(): Language = HTMLLanguage.INSTANCE
    override fun getLanguages(): Set<Language> = setOf(RazorLanguage, HTMLLanguage.INSTANCE)

    override fun cloneInner(fileCopy: VirtualFile): MultiplePsiFilesPerDocumentFileViewProvider = RazorFileViewProvider(manager, fileCopy, false)

    override fun createFile(lang: Language): PsiFile? {
        val definition = LanguageParserDefinitions.INSTANCE.forLanguage(lang) ?: return null
        return when (lang) {
            HTMLLanguage.INSTANCE -> (definition.createFile(this) as? PsiFileImpl)?.also { it.contentElementType = RazorTemplateDataElementType }
            RazorLanguage -> definition.createFile(this)
            else -> null
        }
    }
}

class RazorFileViewProviderFactory : FileViewProviderFactory {
    override fun createFileViewProvider(file: VirtualFile, language: Language?, manager: PsiManager, eventSystemEnabled: Boolean): FileViewProvider =
        RazorFileViewProvider(manager, file, eventSystemEnabled)
}
