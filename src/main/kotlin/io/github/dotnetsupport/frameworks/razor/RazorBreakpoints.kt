package io.github.dotnetsupport.frameworks.razor

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.debugger.BreakpointLinesSource
import io.github.dotnetsupport.lang.CSharpBreakpointLines

/**
 * Line breakpoints in `.razor` and `.cshtml` files (RAZOR_PLAN.md, R8). The pdb of the project maps the C# the generator made of the file
 * back to it (`#line`), so a breakpoint is sent by the file's path and line as one of a `.cs`; the lines it can be put on are those with C#
 * the compiler maps there: an expression of the markup (`@count`), a statement (`@if (…)`, `@foreach`, the code of `@{ }`), and in `@code`
 * / `@functions` the lines the rules of C# allow (a statement, not a field without initializer or a brace). Not a directive (`@page`,
 * `@using`, `@inject`: no code runs there).
 */
object RazorBreakpointLines {
    private val CODE_BLOCKS = setOf("code", "functions")

    fun find(file: RazorFile): Set<Int> {
        val generated = RazorCSharpDocument.generate(file) ?: return emptySet()
        val text = file.text
        val lineStarts = ArrayList<Int>().apply { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        fun lineOf(offset: Int): Int = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        // the lines of the code blocks: `@code {` … `}`
        val blocks = PsiTreeUtil.findChildrenOfType(file, RazorDirectivePsi::class.java).filter { it.directiveName in CODE_BLOCKS }.mapNotNull { directive ->
            val open = directive.openBrace?.textRange?.startOffset ?: return@mapNotNull null
            val close = directive.closeBrace?.textRange?.startOffset ?: text.length
            lineOf(open)..lineOf(close)
        }
        val inBlock = { line: Int -> blocks.any { line in it } }
        val result = HashSet<Int>()
        // the markup's C#: where a source mapping starts, not in a directive that runs nothing
        for (mapping in generated.mappings) {
            val span = mapping.originalSpan
            if (span.filePath != generated.filePath || span.length <= 0 || span.absoluteIndex !in text.indices) continue
            val line = lineOf(span.absoluteIndex)
            if (inBlock(line)) continue
            val leaf = file.node.findLeafElementAt(span.absoluteIndex)?.psi
            val directive = PsiTreeUtil.getParentOfType(leaf, RazorDirectivePsi::class.java)
            if (directive != null && directive.directiveName !in CODE_BLOCKS) continue
            result += line
        }
        // the code blocks: their C# as members of a class, by the rules of a `.cs`
        if (blocks.isNotEmpty()) {
            val shadow = RazorIndent.shadowLines(file)
            val lines = shadow.indices.map { line ->
                val block = blocks.firstOrNull { line in it }
                when {
                    block == null -> ""
                    line == block.first -> "class __Razor {"
                    else -> shadow[line]
                }
            }
            result += CSharpBreakpointLines.find(lines.joinToString("\n")).filter(inBlock)
        }
        return result
    }
}

class RazorBreakpointLinesSource : BreakpointLinesSource {
    override fun handles(file: VirtualFile): Boolean = file.fileType == RazorFileType

    override fun lines(project: Project, file: VirtualFile): Set<Int> {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return emptySet()
        document.getUserData(LINES)?.takeIf { it.first == document.modificationStamp }?.let { return it.second }
        val lines = ReadAction.compute<Set<Int>, RuntimeException> {
            val razor = PsiManager.getInstance(project).findFile(file)?.viewProvider?.getPsi(RazorLanguage) as? RazorFile
            razor?.let(RazorBreakpointLines::find).orEmpty()
        }
        document.putUserData(LINES, document.modificationStamp to lines)
        return lines
    }

    private companion object {
        val LINES = Key.create<Pair<Long, Set<Int>>>("razor.breakpointLines")
    }
}
