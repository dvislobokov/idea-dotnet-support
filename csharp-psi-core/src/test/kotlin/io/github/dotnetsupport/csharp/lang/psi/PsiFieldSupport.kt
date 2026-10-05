package io.github.dotnetsupport.csharp.lang.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement

/** Helpers of the generated [CSharpPsiFieldTable]. */
object PsiFieldSupport {
    /** The elements and separators of a separated list in the order of [parent]'s children (Roslyn's slot order). */
    fun inTreeOrder(parent: PsiElement, elements: List<PsiElement>, separators: List<PsiElement>): List<PsiElement> {
        if (separators.isEmpty()) return elements
        val index = HashMap<ASTNode, Int>()
        var c = parent.node.firstChildNode
        while (c != null) {
            index[c] = index.size
            c = c.treeNext
        }
        return (elements + separators).sortedBy { index[it.node] ?: -1 }
    }
}
