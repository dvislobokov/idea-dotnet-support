package io.github.dotnetsupport.lang

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseNamespaceDeclaration
import io.github.dotnetsupport.lsp.RoslynOptions

/**
 * `formatting.dotnet_organize_imports_on_format` of the page of the server for the native formatter: Reformat Code of a whole file
 * (not of a selection, as the server's range formatting) also removes the `using` directives nothing needs (the gray ones of
 * [NativeCSharpSemanticDiagnostics], so only with the indexes) and sorts the rest of every place as "Sort 'using' directives" does,
 * `System` first unless `dotnet_sort_system_directives_first = false` in `.editorconfig`. Off by default, as the server's.
 */
class NativeCSharpOrganizeImports : PostFormatProcessor {
    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement {
        if (source is PsiFile) organize(source)
        return source
    }

    override fun processText(source: PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
        if (rangeToReformat.startOffset > 0 || rangeToReformat.endOffset < source.textLength) return rangeToReformat
        val before = source.textLength
        organize(source)
        return TextRange(0, (rangeToReformat.endOffset + source.textLength - before).coerceIn(0, source.textLength))
    }

    private fun organize(file: PsiFile) {
        if (file !is CSharpFile || !RoslynOptions.isOn(OPTION) || !NativeCSharpFormatting.engaged(file)) return
        val project = file.project
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return
        if (!DumbService.isDumb(project)) {
            for (range in CSharpRemoveUnusedUsingsFix.unusedLines(file, document.charsSequence)) document.deleteString(range.startOffset, range.endOffset)
            documents.commitDocument(document)
        }
        val systemFirst = CSharpEditorConfig.of(file.originalFile.virtualFile)["dotnet_sort_system_directives_first"]?.substringBefore(':')?.trim() != "false"
        val places = listOfNotNull(file.compilationUnit?.usings?.firstOrNull()) +
            PsiTreeUtil.findChildrenOfType(file, CSharpBaseNamespaceDeclaration::class.java).mapNotNull { it.usings.firstOrNull() }
        val edits = places.mapNotNull { NativeCSharpUsingEdits.sort(it, document.charsSequence, systemFirst) }.sortedByDescending { it.range.startOffset }
        if (edits.isEmpty()) return
        for (edit in edits) document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
        documents.commitDocument(document)
    }

    companion object {
        const val OPTION = "formatting.dotnet_organize_imports_on_format"
    }
}
