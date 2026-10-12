package io.github.dotnetsupport.lang

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.Consumer
import com.intellij.openapi.project.DumbService
import com.intellij.psi.search.LocalSearchScope
import io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames
import io.github.dotnetsupport.csharp.lang.psi.CSharpVariableDeclarator
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import io.github.dotnetsupport.lsp.RoslynServerStatus

/**
 * Highlights the other occurrences of the identifier under the caret. With NAVIGATION NATIVE on the native tree a local, parameter, label,
 * range variable or type parameter gets its usages from the tree, read and written apart ([NativeCSharpNavigation.localUsages]). Anything else
 * goes on as with ROSLYN: to the server's `documentHighlight` (the platform's factory, registered last) once it covers the file, before
 * that to the occurrences of the identifier token's text in the file.
 */
class CSharpHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactoryBase() {
    override fun createHighlightUsagesHandler(editor: Editor, psiFile: PsiFile, target: PsiElement): HighlightUsagesHandlerBase<*>? {
        if (psiFile !is CSharpFile) return null
        if (NativeCSharpNavigation.serves(psiFile)) {
            val usages = NativeCSharpNavigation.localUsages(target)
            if (usages != null) return Handler(editor, psiFile, usages.read, usages.write)
            memberUsages(psiFile, editor.caretModel.offset)?.let { (read, write) -> return Handler(editor, psiFile, read, write) }
        }
        val file = psiFile.virtualFile
        if (file != null && RoslynServerStatus.covers(psiFile.project, file)) return null // the server answers with documentHighlight
        val ranges = CSharpUsageHighlights.occurrences(editor.document.immutableCharSequence, editor.caretModel.offset)
        return if (ranges.isEmpty()) null else Handler(editor, psiFile, ranges, emptyList())
    }

    /**
     * The usages in [file] of the type or member at [offset] (task C4b), by the resolver of the native tree: the declarations in the file and
     * the names resolved to them, written ones apart; null when the caret is on no such name.
     */
    private fun memberUsages(file: CSharpFile, offset: Int): Pair<List<TextRange>, List<TextRange>>? {
        if (DumbService.isDumb(file.project)) return null
        val leaf = NativeCSharpFindUsages.identifierAt(file, offset) ?: return null
        val session = CSharpSemanticSession(file.project)
        val target = CSharpSolutionSearch.targetAt(leaf, session) ?: return null
        val read = ArrayList<TextRange>()
        val write = ArrayList<TextRange>()
        val scope = LocalSearchScope(file)
        for (word in CSharpSolutionSearch.words(target)) CSharpSolutionSearch.processFile(file, word, target, scope, session) { usage ->
            if (NativeCSharpUsageKinds.kindOfLeaf(usage.leaf) == CSharpUsageKind.WRITE) write += usage.leaf.textRange else read += usage.leaf.textRange
            true
        }
        for (declaration in target.declarations) {
            if (declaration.containingFile != file) continue
            val name = CSharpDeclarationNames.nameElement(declaration) ?: (declaration as? CSharpVariableDeclarator)?.identifier
                ?: (declaration as? io.github.dotnetsupport.csharp.lang.psi.CSharpBaseFieldDeclaration)?.declaration?.variables?.singleOrNull()?.identifier ?: continue
            write += name.textRange
        }
        if (read.isEmpty() && write.isEmpty()) return null
        return read.sortedBy { it.startOffset } to write.sortedBy { it.startOffset }
    }

    private class Handler(editor: Editor, private val psiFile: PsiFile, private val read: List<TextRange>, private val write: List<TextRange>) :
        HighlightUsagesHandlerBase<PsiElement>(editor, psiFile) {
        override fun getTargets(): List<PsiElement> = listOf(psiFile)
        override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) = selectionConsumer.consume(targets)
        override fun computeUsages(targets: List<PsiElement>) {
            read.forEach(myReadUsages::add)
            write.forEach(myWriteUsages::add)
        }
    }
}

object CSharpUsageHighlights {
    /** Ranges of every identifier token with the same text as the one the caret is on; empty when the caret is not on an identifier. */
    fun occurrences(text: CharSequence, caret: Int): List<TextRange> {
        val tokens = CSharpExpressions.tokenize(text)
        val at = tokens.firstOrNull { it.isIdentifier && caret >= it.start && caret <= it.end } ?: return emptyList()
        return tokens.filter { it.isIdentifier && it.text == at.text }.map { TextRange(it.start, it.end) }
    }
}
