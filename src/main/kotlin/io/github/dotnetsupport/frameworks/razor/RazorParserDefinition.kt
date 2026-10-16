package io.github.dotnetsupport.frameworks.razor

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderFactory
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
import com.intellij.psi.templateLanguages.TemplateDataElementType
import com.intellij.psi.templateLanguages.TemplateLanguageFileViewProvider
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/** The file of the Razor tree; its markup is in the HTML file of the same view ([RazorFileViewProvider]). */
class RazorFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, RazorLanguage) {
    override fun getFileType(): FileType = RazorFileType
    override fun toString(): String = "Razor File"
    val kind: RazorFileKind get() = RazorFileKind.of(name)
}

/** A `@directive` with its argument or block ([RazorElementTypes.DIRECTIVE]) or a `@{ }` ([RazorElementTypes.CODE_BLOCK]). */
class RazorBlock(node: ASTNode) : ASTWrapperPsiElement(node) {
    /** `code`, `section`, `page`...; null for `@{ }`. */
    val directiveName: String? get() = node.findChildByType(RazorTokenTypes.DIRECTIVE)?.text

    /** The argument of the directive as written: `Scripts` of `@section Scripts`, `"/counter"` of `@page`. */
    val argument: String?
        get() = generateSequence(node.firstChildNode) { it.treeNext }.takeWhile { it.elementType != RazorTokenTypes.LBRACE }
            .firstOrNull { it.elementType in ARGUMENT }?.text?.trim()?.takeIf { it.isNotEmpty() }

    val openBrace: ASTNode? get() = node.findChildByType(RazorTokenTypes.LBRACE)
    val closeBrace: ASTNode? get() = node.getChildren(BRACES).lastOrNull()?.takeIf { it.elementType == RazorTokenTypes.RBRACE }

    private companion object {
        val ARGUMENT = TokenSet.create(RazorTokenTypes.CSHARP, RazorTokenTypes.DIRECTIVE_VALUE)
        val BRACES = TokenSet.create(RazorTokenTypes.LBRACE, RazorTokenTypes.RBRACE)
    }
}

/** A run of C# statements or members ([RazorElementTypes.CODE]). */
class RazorCode(node: ASTNode) : ASTWrapperPsiElement(node)

/** The element type of a Razor file: its lexer is of the kind the name of the file gives. */
object RazorFileElementType : IFileElementType("RAZOR_FILE", RazorLanguage) {
    private fun readResolve(): Any = RazorFileElementType

    override fun doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode {
        val kind = RazorFileKind.of(psi.containingFile?.viewProvider?.virtualFile?.name ?: psi.containingFile?.name)
        val builder = PsiBuilderFactory.getInstance().createBuilder(psi.project, chameleon, RazorLexer(kind), RazorLanguage, chameleon.chars)
        return RazorParser().parse(this, builder).firstChildNode
    }
}

class RazorParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = RazorLexer(RazorFileKind.COMPONENT)
    override fun createParser(project: Project?): PsiParser = RazorParser()
    override fun getFileNodeType(): IFileElementType = RazorFileElementType
    override fun getWhitespaceTokens(): TokenSet = TokenSet.WHITE_SPACE
    override fun getCommentTokens(): TokenSet = RazorTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createFile(viewProvider: FileViewProvider): PsiFile = RazorFile(viewProvider)

    override fun createElement(node: ASTNode): PsiElement =
        if (node.elementType == RazorElementTypes.CODE) RazorCode(node) else RazorBlock(node)
}

/**
 * Flat, but for the directives and `@{ }` (their blocks, for folding and the structure view) and the runs of statements: what is C# in
 * them gets a parent of [RazorCodeLanguage]. The tree of the compiler is R2.
 */
class RazorParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        while (!builder.eof()) {
            if (builder.tokenType == RazorTokenTypes.TRANSITION) block(builder) else token(builder)
        }
        file.done(root)
        return builder.treeBuilt
    }

    private fun token(builder: PsiBuilder) {
        if (builder.tokenType == RazorTokenTypes.CSHARP_CODE) {
            val code = builder.mark()
            builder.advanceLexer()
            code.done(RazorElementTypes.CODE)
        } else builder.advanceLexer()
    }

    private fun block(builder: PsiBuilder) {
        val start = builder.mark()
        builder.advanceLexer()
        val type = when (builder.tokenType) {
            RazorTokenTypes.DIRECTIVE -> RazorElementTypes.DIRECTIVE
            RazorTokenTypes.LBRACE -> RazorElementTypes.CODE_BLOCK
            else -> null
        }
        if (type == null) {
            start.drop()
            return
        }
        if (type == RazorElementTypes.DIRECTIVE) {
            builder.advanceLexer()
            while (builder.tokenType == RazorTokenTypes.CSHARP || builder.tokenType == RazorTokenTypes.DIRECTIVE_VALUE) builder.advanceLexer()
        }
        if (builder.tokenType == RazorTokenTypes.LBRACE) {
            builder.advanceLexer()
            var depth = 1
            while (!builder.eof()) {
                val current = builder.tokenType
                if (current == RazorTokenTypes.LBRACE) depth++
                if (current == RazorTokenTypes.RBRACE && --depth == 0) {
                    builder.advanceLexer()
                    break
                }
                if (current == RazorTokenTypes.TRANSITION) block(builder) else token(builder)
            }
        }
        start.done(type)
    }
}

/** The HTML file of a Razor view: the markup, with the Razor in it as outer elements. */
object RazorTemplateDataElementType : TemplateDataElementType("RAZOR_HTML", RazorLanguage, RazorTokenTypes.MARKUP, RazorTokenTypes.OUTER) {
    private fun readResolve(): Any = RazorTemplateDataElementType

    override fun createBaseLexer(viewProvider: TemplateLanguageFileViewProvider): Lexer = RazorLexer(RazorFileKind.of(viewProvider.virtualFile.name))
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
