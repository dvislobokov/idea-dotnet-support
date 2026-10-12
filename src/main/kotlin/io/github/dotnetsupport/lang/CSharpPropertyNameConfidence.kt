package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState

/**
 * No auto-popup while the name of a property is typed (`public RankedOrder Ord`), so that the gray ` { get; set; }` is seen and Tab takes
 * it: the server's name suggestions popped up after the type and stayed open over the whole name, and the gray text gives way to an
 * open list ([CSharpGhostTextProvider.isEnabled]). The suggestions are still there on Ctrl+Space, and for a field or a variable (a small
 * first letter: `private RankedOrder o`). Chosen over gray text next to the open list: Tab would be the list's or the gray text's
 * depending on what is in the list, and a name list that pops up over a name already being typed helps little.
 */
class CSharpPropertyNameConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState =
        if (psiFile is CSharpFile && CSharpGhostText.awaitsPropertyName(editor.document.immutableCharSequence, offset)) ThreeState.YES else ThreeState.UNSURE
}
