package io.github.dotnetsupport.lang

import com.intellij.codeInsight.folding.CodeFoldingSettings
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType

enum class FoldKind { BODY, USINGS, REGION, DOC_COMMENT, COMMENT }

class CSharpFoldRegion(val kind: FoldKind, val range: TextRange, val placeholder: String)

/** What folds in a C# file: bodies of declarations, the using block, `#region`, runs of comments. Pure: see [CSharpFoldingBuilder]. */
object CSharpFolding {
    private const val USING_KEYWORD = "using "
    private const val MAX_PLACEHOLDER = 80
    private val TAG = Regex("""<[^>]+>""")

    fun regions(text: CharSequence): List<CSharpFoldRegion> {
        val structure = CSharpDeclarations.scan(text)
        val result = ArrayList<CSharpFoldRegion>()
        structure.all().mapNotNull { it.body }.mapTo(result) { CSharpFoldRegion(FoldKind.BODY, it, "{...}") }
        // `using ...`: the keyword of the first directive stays
        structure.usings?.takeIf { it.length > USING_KEYWORD.length }?.let {
            val start = it.startOffset + if (text.startsWith(USING_KEYWORD, it.startOffset)) USING_KEYWORD.length else 0
            result += CSharpFoldRegion(FoldKind.USINGS, TextRange(start, it.endOffset), "...")
        }
        result += directivesAndComments(text)
        return result.filter { multiline(text, it.range) }.sortedBy { it.range.startOffset }
    }

    private fun multiline(text: CharSequence, range: TextRange): Boolean = (range.startOffset until range.endOffset).any { text[it] == '\n' }

    private fun directivesAndComments(text: CharSequence): List<CSharpFoldRegion> {
        val result = ArrayList<CSharpFoldRegion>()
        val regions = ArrayDeque<Pair<Int, String>>()
        // a run of line comments of one kind: (type, start, end, first line)
        var run: Run? = null
        fun flush() {
            run?.let { result += CSharpFoldRegion(if (it.isDoc) FoldKind.DOC_COMMENT else FoldKind.COMMENT, TextRange(it.start, it.end), placeholder(it)) }
            run = null
        }

        val lexer = CSharpLexer()
        lexer.start(text)
        while (true) {
            val type = lexer.tokenType ?: break
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd)
            when (type) {
                TokenType.WHITE_SPACE -> if (tokenText.count { it == '\n' } > 1) flush() // a blank line ends the run
                CSharpTokenTypes.LINE_COMMENT, CSharpTokenTypes.DOC_COMMENT -> {
                    val isDoc = type == CSharpTokenTypes.DOC_COMMENT
                    val current = run
                    if (current != null && current.isDoc == isDoc) current.extend(lexer.tokenEnd, tokenText.toString())
                    else {
                        flush()
                        run = Run(isDoc, lexer.tokenStart, lexer.tokenEnd, tokenText.toString())
                    }
                }
                else -> {
                    flush()
                    when (type) {
                        CSharpTokenTypes.BLOCK_COMMENT -> result += CSharpFoldRegion(FoldKind.COMMENT, TextRange(lexer.tokenStart, lexer.tokenEnd), "/*...*/")
                        CSharpTokenTypes.PREPROCESSOR -> {
                            val directive = tokenText.toString().trim()
                            if (directive.startsWith("#region")) regions.addLast(lexer.tokenStart to directive.removePrefix("#region").trim())
                            else if (directive.startsWith("#endregion")) regions.removeLastOrNull()?.let { (start, name) ->
                                result += CSharpFoldRegion(FoldKind.REGION, TextRange(start, lexer.tokenStart + tokenText.trimEnd().length), name.ifEmpty { "#region" })
                            }
                        }
                    }
                }
            }
            lexer.advance()
        }
        flush()
        return result
    }

    private class Run(val isDoc: Boolean, val start: Int, var end: Int, first: String) {
        val lines = arrayListOf(first)
        fun extend(newEnd: Int, line: String) {
            end = newEnd
            lines += line
        }
    }

    /** `/// Orders of the shop...`: the first text of a doc comment without its tags; `// first line...` for the others. */
    private fun placeholder(run: Run): String {
        val prefix = if (run.isDoc) "///" else "//"
        val first = run.lines.map { TAG.replace(it.trim().removePrefix(prefix), "").trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return "$prefix ${first.take(MAX_PLACEHOLDER)}...".replace("  ", " ")
    }

    fun collapsedByDefault(kind: FoldKind): Boolean = when (kind) {
        FoldKind.USINGS -> CodeFoldingSettings.getInstance().COLLAPSE_IMPORTS
        FoldKind.DOC_COMMENT -> CodeFoldingSettings.getInstance().COLLAPSE_DOC_COMMENTS
        else -> false
    }
}

class CSharpFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> =
        CSharpFolding.regions(document.immutableCharSequence)
            .filter { it.range.endOffset <= document.textLength }
            .map { FoldingDescriptor(root.node, it.range, null, it.placeholder, CSharpFolding.collapsedByDefault(it.kind), emptySet()) }
            .toTypedArray()

    override fun getPlaceholderText(node: ASTNode): String = "..."
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
