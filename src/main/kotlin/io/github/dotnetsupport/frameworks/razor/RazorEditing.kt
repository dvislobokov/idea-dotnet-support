package io.github.dotnetsupport.frameworks.razor

import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.templateLanguages.MultipleLangCommentProvider
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.lang.CSharpCommenter
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind

/** `@{ }`, `@( )`, `@code { }`, `@section { }`: the braces of Razor; those of C# are the C# matcher's, the tags HTML's. */
class RazorBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(BracePair(RazorTokenTypes.LBRACE, RazorTokenTypes.RBRACE, true), BracePair(RazorTokenTypes.LPAREN, RazorTokenTypes.RPAREN, false))
    }
}

/** `@* … *@`, for a line too (Ctrl+/ wraps it, as Rider's). */
class RazorCommenter : Commenter {
    override fun getLineCommentPrefix(): String? = null
    override fun getBlockCommentPrefix(): String = "@*"
    override fun getBlockCommentSuffix(): String = "*@"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

/**
 * Which comment a line of a Razor file gets: `//` in C# statements and members (`@{ }`, `@code { }` — their tokens are under a
 * [RazorCodeLanguage] node), `@* *@` in the markup and the directives, where the HTML file would say `<!-- -->`.
 */
class RazorCommentProvider : MultipleLangCommentProvider {
    override fun canProcess(file: PsiFile, viewProvider: FileViewProvider): Boolean = viewProvider is RazorFileViewProvider

    override fun getLineCommenter(file: PsiFile, editor: Editor, lineStartLanguage: Language, lineEndLanguage: Language): Commenter =
        if (lineStartLanguage == RazorCodeLanguage) CSharpCommenter() else RazorCommenter()
}

/**
 * Folds `@code { }` / `@functions { }`, `@section Name { }`, `@{ }`, `@* *@` over lines, and the C# blocks of several lines in them (a
 * method of `@code`, the body of `@if`); the tags are HTML's own folding.
 */
class RazorFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root as? RazorFile ?: return FoldingDescriptor.EMPTY_ARRAY
        val result = ArrayList<FoldingDescriptor>()
        fun multiline(range: TextRange) = range.length > 1 && document.getLineNumber(range.startOffset) != document.getLineNumber(range.endOffset)
        // the blocks of the directives (`@code { }`, `@section Name { }`) and `@{ }`: the braces are Razor's meta code
        for (block in PsiTreeUtil.findChildrenOfType(file, RazorDirectivePsi::class.java)) {
            val open = block.openBrace ?: continue
            val close = block.closeBrace ?: continue
            val range = TextRange(open.textRange.startOffset, close.textRange.endOffset)
            if (multiline(range)) result += FoldingDescriptor(block.node, range, null, "{...}")
        }
        for (statement in PsiTreeUtil.findChildrenOfType(file, RazorElement::class.java).filter { it.kind == RazorSyntaxKind.CSharpStatementBody }) {
            val braces = statement.children.filterIsInstance<RazorElement>().filter { it.kind == RazorSyntaxKind.RazorMetaCode }
            val open = braces.firstOrNull { it.text == "{" } ?: continue
            val close = braces.lastOrNull { it.text == "}" } ?: continue
            val range = TextRange(open.textRange.startOffset, close.textRange.endOffset)
            if (multiline(range)) result += FoldingDescriptor(statement.node, range, null, "{...}")
        }
        // the comments and the C# braces, from the tokens of the tree
        val braces = ArrayList<Int>()
        fun fold(range: TextRange, placeholder: String) {
            val node = file.node.findLeafElementAt(range.startOffset) ?: return
            result += FoldingDescriptor(node, range, null, placeholder)
        }
        for (comment in PsiTreeUtil.findChildrenOfType(file, RazorElement::class.java).filter { it.kind == RazorSyntaxKind.RazorComment }) {
            comment.textRange.takeIf(::multiline)?.let { fold(it, "@*...*@") }
        }
        var leaf = file.node.firstChildNode?.let { com.intellij.psi.impl.source.tree.TreeUtil.findFirstLeaf(it) }
        while (leaf != null) {
            val type = leaf.elementType as? RazorTreeTokenType
            if (type?.span == RazorSpan.CODE) when (type.kind) {
                RazorSyntaxKind.LeftBrace -> braces += leaf.startOffset
                RazorSyntaxKind.RightBrace -> if (braces.isNotEmpty()) {
                    val range = TextRange(braces.removeAt(braces.size - 1), leaf.textRange.endOffset)
                    if (multiline(range)) fold(range, "{...}")
                }
                else -> {}
            }
            leaf = com.intellij.psi.impl.source.tree.TreeUtil.nextLeaf(leaf)
        }
        return result.toTypedArray()
    }

    override fun getPlaceholderText(node: com.intellij.lang.ASTNode): String = "{...}"
    override fun isCollapsedByDefault(node: com.intellij.lang.ASTNode): Boolean = false
}
