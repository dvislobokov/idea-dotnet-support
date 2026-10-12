package io.github.dotnetsupport.csharp.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.impl.source.tree.LazyParseablePsiElement
import com.intellij.psi.impl.source.tree.FileElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IReparseableElementType
import com.intellij.util.CharTable
import io.github.dotnetsupport.csharp.lang.doc.DocCommentTreeBuilder
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexer
import io.github.dotnetsupport.csharp.lang.psi.CSharpDocumentationCommentTrivia
import io.github.dotnetsupport.csharp.lang.psi.CSharpVisitor
import io.github.dotnetsupport.csharp.lang.psi.CSharpXmlNode
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpDocumentationCommentTriviaImpl

/**
 * `SingleLineDocumentationCommentTrivia` (a run of `///` lines, without the new line of the last one) and
 * `MultiLineDocumentationCommentTrivia` (`/** */`): one token of the lexer, a comment for PsiBuilder and the editor,
 * parsed on first access into Roslyn's structured trivia (`XmlElement`, `XmlCrefAttribute`, crefs, ...) by the port of
 * `DocumentationCommentParser` ([DocCommentTreeBuilder]). The file parse does not pay for it; an edit inside the
 * comment reparses only the comment when the new text is still one such token ([isReparseable]).
 * docs/csharp-psi/GRAMMAR.md, "Doc comments".
 */
class CSharpDocCommentElementType(debugName: String) : IReparseableElementType(debugName, CSharpLanguage) {
    private val delimited = debugName.startsWith("MultiLine")

    override fun createNode(text: CharSequence?): ASTNode = CSharpDocCommentImpl(this, text)

    override fun parseContents(chameleon: ASTNode): ASTNode? {
        val text = chameleon.chars
        return try {
            DocCommentTreeBuilder.build(text, delimited, charTable(chameleon))
        } catch (e: RuntimeException) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            LOG.error("doc comment parse failed (${text.length} chars)", e)
            flat(text)
        } catch (_: StackOverflowError) {
            // The parser's depth guard keeps real input far from this; a caller already deep in the stack may not.
            // Not an error of the parser: the comment stays flat (docs/csharp-psi/GRAMMAR.md, "Doc comments").
            flat(text)
        }
    }

    /** A flat comment: one exterior-less text leaf keeps the tree consistent with the text. */
    private fun flat(text: CharSequence): ASTNode = com.intellij.lang.ASTFactory.leaf(SyntaxKind.XmlTextLiteralToken, text)

    /** The file's char table; none in a tree without a file element (PsiBuilder output in tests). */
    private fun charTable(node: ASTNode): CharTable? {
        var t: ASTNode? = node
        while (t != null) {
            if (t is FileElement) return t.charTable
            t = t.treeParent
        }
        return null
    }

    override fun isReparseable(currentNode: ASTNode, newText: CharSequence, fileLanguage: Language, project: Project): Boolean {
        if (delimited && (newText.length < 5 || !newText.endsWith("*/"))) return false
        val lexer = CSharpLexer()
        lexer.start(newText)
        if (lexer.tokenType !== this || lexer.tokenStart != 0 || lexer.tokenEnd != newText.length) return false
        lexer.advance()
        return lexer.tokenType == null
    }

    override fun toString(): String = debugName

    private companion object {
        val LOG = logger<CSharpDocCommentElementType>()
    }
}

/**
 * The PSI of a doc comment: a comment whose children are Roslyn's doc comment structure, and Roslyn's
 * `DocumentationCommentTriviaSyntax` ([CSharpDocumentationCommentTrivia]: [content], [endOfComment]), its fields
 * matched as in the generated [CSharpDocumentationCommentTriviaImpl] (whose shape it uses; that class itself is never
 * instantiated: the element is created by [CSharpDocCommentElementType.createNode]). Accessors parse the comment.
 */
class CSharpDocCommentImpl(type: IElementType, text: CharSequence?) :
    LazyParseablePsiElement(type, text), PsiComment, CSharpDocumentationCommentTrivia {
    override fun getTokenType(): IElementType = elementType

    @Suppress("UNCHECKED_CAST")
    override val content: List<CSharpXmlNode>
        get() = (CSharpDocumentationCommentTriviaImpl.SHAPE.match(this)[0] as List<ASTNode>).map { it.psi as CSharpXmlNode }

    override val endOfComment: PsiElement?
        get() = (CSharpDocumentationCommentTriviaImpl.SHAPE.match(this)[1] as ASTNode?)?.psi

    override fun accept(visitor: CSharpVisitor) = visitor.visitDocumentationCommentTrivia(this)

    /** A [CSharpVisitor] gets `visitDocumentationCommentTrivia`, any other visitor `visitComment`. */
    override fun accept(visitor: PsiElementVisitor) = if (visitor is CSharpVisitor) accept(visitor) else visitor.visitComment(this)

    override fun toString(): String = "PsiComment($elementType)"
}
