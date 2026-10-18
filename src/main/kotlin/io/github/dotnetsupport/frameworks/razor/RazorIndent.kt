package io.github.dotnetsupport.frameworks.razor

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.service.AbstractDocumentFormattingService
import com.intellij.formatting.service.FormattingService
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import io.github.dotnetsupport.lang.CSharpIndentOptions
import io.github.dotnetsupport.lang.CSharpIndentRules
import io.github.dotnetsupport.lang.IndentSizes
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind

/**
 * The indent of a line of a Razor file (RAZOR_PLAN.md, R7): the C# rules of the plugin ([CSharpIndentRules], what Enter in a `.cs` uses)
 * over a C# shadow of the file — the same lines and columns, with the code of the code blocks as it is (`@code { }`, `@{ }`, `@if (…) { }`,
 * their braces), a start tag of an element that has an end tag as `{` and its end tag as `}`, and everything else blank: the markup's text,
 * directives, the expressions in the markup (`@count`), comments. So a line is indented by the C# blocks and the elements around it, as
 * Rider's formatter does, and the C# of `@code` as C#.
 */
object RazorIndent {
    /** The elements of HTML without an end tag. */
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

    /** The elements whose content is kept as written by Reformat: its lines are no markup of the page. */
    private val VERBATIM = setOf("pre", "textarea", "script", "style")

    /** The directives with a block: their braces and the code between them are C# of the shadow; any other directive is blank. */
    private val BLOCK_DIRECTIVES = setOf("code", "functions", "section")

    /**
     * The masks of [file]'s text, one char per char (line breaks kept): code as it is, a start tag `x {` (an element with content: the
     * line after it goes one level deeper, as after the header of a C# block) or `x;` (`<br>`, `<Counter />`), an end tag `}`, the rest blank.
     */
    private fun masks(file: RazorFile): CharArray {
        val text = file.text
        val flat = notIndentingElements(file)
        val out = CharArray(text.length) { if (text[it] == '\n' || text[it] == '\r') text[it] else ' ' }
        fun copy(node: ASTNode) {
            val start = node.startOffset
            for (i in 0 until node.textLength) {
                val c = text[start + i]
                if (c != '\n' && c != '\r') out[start + i] = c
            }
        }
        fun visit(node: ASTNode, codeAllowed: Boolean) {
            when ((node.elementType as? RazorTreeNodeType)?.kind) {
                RazorSyntaxKind.MarkupStartTag, RazorSyntaxKind.MarkupTagHelperStartTag -> {
                    val tag = node.text
                    if (!tag.startsWith("<") || tag.startsWith("<!")) return
                    out[node.startOffset] = 'x'
                    val last = tag.indexOfLast { !it.isWhitespace() }.takeIf { it > 0 } ?: return
                    out[node.startOffset + last] = if (opensElement(tag) && tagName(tag) !in flat) '{' else ';'
                    return
                }
                RazorSyntaxKind.MarkupEndTag, RazorSyntaxKind.MarkupTagHelperEndTag -> {
                    if (tagName(node.text) !in flat) out[node.startOffset] = '}'
                    return
                }
                RazorSyntaxKind.RazorComment, RazorSyntaxKind.MarkupCommentBlock, RazorSyntaxKind.CSharpExplicitExpression,
                RazorSyntaxKind.CSharpImplicitExpression -> return
                RazorSyntaxKind.RazorDirective, RazorSyntaxKind.RazorUsingDirective, RazorSyntaxKind.RazorDocumentationDirective -> {
                    val directive = node.psi as? RazorDirectivePsi
                    if (directive == null || directive.directiveName !in BLOCK_DIRECTIVES) return
                    val open = directive.openBrace?.textRange?.startOffset ?: return
                    val close = directive.closeBrace?.textRange?.startOffset
                    out[open] = '{'
                    if (close != null) out[close] = '}'
                    // the block's content: code of `@code`, markup of `@section`
                    var child = node.firstChildNode
                    while (child != null) {
                        visitInside(child, open, close, ::visit)
                        child = child.treeNext
                    }
                    return
                }
                RazorSyntaxKind.CSharpStatementLiteral -> {
                    if (codeAllowed) copy(node)
                    return
                }
                RazorSyntaxKind.RazorMetaCode -> {
                    // the braces of `@{ }` (the directives' are taken above)
                    val t = node.text.trim()
                    if (t == "{" || t == "}") out[node.startOffset + node.text.indexOf(t)] = t[0]
                    return
                }
                else -> {}
            }
            var child = node.firstChildNode
            while (child != null) {
                visit(child, codeAllowed)
                child = child.treeNext
            }
        }
        visit(file.node, codeAllowed = true)
        return out
    }

    /**
     * The C# shadow of [file], by lines: a line's indent as written, then what its masks keep, from the first kept char — so the C# rules
     * see the indents of the lines as they are (`@code {` is a `{` at the indent of `@code`, not at the column of the brace).
     */
    fun shadowLines(file: RazorFile): MutableList<String> {
        val text = file.text
        val masks = String(masks(file))
        val lines = ArrayList<String>()
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val lineEnd = if (end > start && text[end - 1] == '\r') end - 1 else end
            val lead = text.substring(start, lineEnd).takeWhile { it == ' ' || it == '\t' }
            lines += lead + masks.substring(start + lead.length, lineEnd).trimStart()
            if (end >= text.length) break
            start = end + 1
        }
        return lines
    }

    /** The shadow of [file] as one text ([shadowLines] joined by line breaks). */
    fun shadow(file: RazorFile): String = shadowLines(file).joinToString("\n")

    /** Visits the parts of a directive's subtree that lie between its braces (exclusive). */
    private fun visitInside(node: ASTNode, open: Int, close: Int?, visit: (ASTNode, Boolean) -> Unit) {
        val start = node.startOffset
        val end = start + node.textLength
        if (end <= open || close != null && start >= close) return
        if (start > open && (close == null || end <= close)) return visit(node, true)
        var child = node.firstChildNode
        while (child != null) {
            visitInside(child, open, close, visit)
            child = child.treeNext
        }
    }

    /** A start tag whose element has content and an end tag: not `<br>`, not `<Counter />`, not `<!DOCTYPE>`. */
    fun opensElement(tag: String): Boolean {
        val trimmed = tag.trimEnd()
        if (trimmed.endsWith("/>") || !trimmed.startsWith("<") || trimmed.startsWith("<!")) return false
        val name = tagName(trimmed)
        return name.isNotEmpty() && name !in VOID
    }

    /** The name of the element of a start or end tag, lower case: `div` of `<div class="a">` and of `</div>`. */
    private fun tagName(tag: String): String =
        tag.trimStart().removePrefix("<").removePrefix("/").takeWhile { !it.isWhitespace() && it != '>' && it != '/' }.lowercase()

    /**
     * The elements whose children keep the indent of the element: Settings | Editor | Code Style | HTML, "Do not indent children of"
     * (`html`, `body`, `thead`, `tbody`, `tfoot` by default) — as the HTML of the IDE formats them.
     */
    private fun notIndentingElements(file: PsiFile): Set<String> {
        val names = runCatching { CodeStyle.getCustomSettings(file, com.intellij.psi.formatter.xml.HtmlCodeStyleSettings::class.java).HTML_DO_NOT_INDENT_CHILDREN_OF }
            .getOrNull() ?: "html,body,thead,tbody,tfoot"
        return names.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun sizes(file: PsiFile): IndentSizes {
        val options = CodeStyle.getIndentOptions(file)
        return IndentSizes(options.INDENT_SIZE, options.CONTINUATION_INDENT_SIZE, options.TAB_SIZE, options.USE_TAB_CHARACTER)
    }

    private fun options(file: PsiFile): Map<String, String> = CSharpIndentOptions.of(file.viewProvider.virtualFile)

    /** The indent of the line that starts at [lineStart] of [file] (committed), as text; null to leave it as it is. */
    fun indentOf(file: RazorFile, lineStart: Int): String? {
        val sizes = sizes(file)
        val line = file.text.subSequence(0, lineStart.coerceIn(0, file.textLength)).count { it == '\n' }
        val lines = shadowLines(file)
        val start = lines.take(line).sumOf { it.length + 1 }
        val columns = CSharpIndentRules.DEFAULT.indentOf(lines.joinToString("\n"), start, sizes, options(file))?.first ?: return null
        return sizes.text(columns)
    }

    /**
     * The lines of [range] of [document] (its committed [file]) indented as [indentOf] says, top down — a line's indent follows the lines
     * above it as they are after their own; blank lines lose their whitespace. Lines inside a comment, `<pre>`, `<textarea>`, `<script>`
     * and `<style>` are kept.
     */
    fun reindent(file: RazorFile, document: Document, range: TextRange) {
        val sizes = sizes(file)
        val options = options(file)
        val lines = shadowLines(file)
        val shadow = StringBuilder(lines.joinToString("\n"))
        val firstLine = document.getLineNumber(range.startOffset.coerceIn(0, document.textLength))
        val lastLine = document.getLineNumber(range.endOffset.coerceIn(0, document.textLength)).coerceAtMost(lines.size - 1)
        val kept = keptLines(file, document)
        val text = document.immutableCharSequence
        val edits = ArrayList<Triple<Int, Int, String>>()
        var shadowStart = 0
        for (line in 0..lastLine) {
            val shadowLine = lines[line]
            if (line >= firstLine && line !in kept) {
                val start = document.getLineStartOffset(line)
                val end = document.getLineEndOffset(line)
                var contentStart = start
                while (contentStart < end && (text[contentStart] == ' ' || text[contentStart] == '\t')) contentStart++
                val indent = if (contentStart == end) "" else CSharpIndentRules.DEFAULT.indentOf(shadow, shadowStart, sizes, options)?.first?.let(sizes::text)
                if (indent != null && text.subSequence(start, contentStart).toString() != indent) {
                    edits += Triple(start, contentStart, indent)
                    // the shadow follows: the lines below are indented after this one as it is now
                    val lead = shadowLine.takeWhile { it == ' ' || it == '\t' }
                    shadow.replace(shadowStart, shadowStart + lead.length, indent)
                    lines[line] = indent + shadowLine.substring(lead.length)
                }
            }
            shadowStart += lines[line].length + 1
        }
        for ((start, end, indent) in edits.asReversed()) document.replaceString(start, end, indent)
    }

    /** The lines (0-based) that start inside a comment or a verbatim element: Reformat leaves them as they are. */
    private fun keptLines(file: RazorFile, document: Document): Set<Int> {
        val kept = HashSet<Int>()
        fun keep(range: TextRange) {
            if (range.isEmpty) return
            val first = document.getLineNumber(range.startOffset) + 1
            val last = document.getLineNumber(range.endOffset.coerceAtMost(document.textLength))
            for (line in first..last) kept += line
        }
        fun visit(node: ASTNode) {
            when ((node.elementType as? RazorTreeNodeType)?.kind) {
                RazorSyntaxKind.RazorComment, RazorSyntaxKind.MarkupCommentBlock -> return keep(node.textRange)
                RazorSyntaxKind.MarkupElement -> {
                    val name = node.firstChildNode?.text?.removePrefix("<")?.takeWhile { it.isLetterOrDigit() }?.lowercase()
                    if (name in VERBATIM) {
                        // the content: from the line after the start tag to the end tag's line (that one is the element's)
                        val start = node.firstChildNode!!.textRange.endOffset
                        val end = node.lastChildNode?.takeIf { (it.elementType as? RazorTreeNodeType)?.kind == RazorSyntaxKind.MarkupEndTag }?.startOffset ?: node.textRange.endOffset
                        if (end > start) {
                            val first = document.getLineNumber(start) + 1
                            val last = document.getLineNumber(end) - 1
                            for (line in first..last) kept += line
                        }
                        return
                    }
                }
                else -> {}
            }
            var child = node.firstChildNode
            while (child != null) {
                visit(child)
                child = child.treeNext
            }
        }
        visit(file.node)
        return kept
    }
}

/** Enter in a Razor file: the new line's indent ([RazorIndent]), in the markup (HTML) and in the C# of the code blocks alike. */
class RazorLineIndentProvider : LineIndentProvider {
    override fun isSuitableFor(language: Language?): Boolean = language == RazorLanguage || language == RazorCodeLanguage || language == HTMLLanguage.INSTANCE

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        val documents = PsiDocumentManager.getInstance(project)
        val psi = documents.getPsiFile(editor.document) ?: return null
        if (psi.viewProvider !is RazorFileViewProvider) return null
        documents.commitDocument(editor.document)
        val razor = psi.viewProvider.getPsi(RazorLanguage) as? RazorFile ?: return null
        val document = editor.document
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset.coerceIn(0, document.textLength)))
        return RazorIndent.indentOf(razor, lineStart) ?: LineIndentProvider.DO_NOT_ADJUST
    }
}

/** Reformat Code on a Razor file: its lines indented as Enter indents them ([RazorIndent.reindent]); the text inside the lines stays. */
class RazorFormattingService : AbstractDocumentFormattingService() {
    override fun getFeatures(): Set<FormattingService.Feature> = setOf(FormattingService.Feature.FORMAT_FRAGMENTS)

    override fun canFormat(file: PsiFile): Boolean = file.viewProvider is RazorFileViewProvider

    override fun formatDocument(document: Document, formattingRanges: List<TextRange>, formattingContext: com.intellij.formatting.FormattingContext, canChangeWhiteSpaceOnly: Boolean, quickFormat: Boolean) {
        val project = formattingContext.project
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val razor = formattingContext.containingFile.viewProvider.getPsi(RazorLanguage) as? RazorFile ?: return
        // the ranges from the end: the indents of the lines above a range do not move with its edits
        for (range in formattingRanges.sortedByDescending { it.startOffset }) {
            RazorIndent.reindent(razor, document, range)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}
