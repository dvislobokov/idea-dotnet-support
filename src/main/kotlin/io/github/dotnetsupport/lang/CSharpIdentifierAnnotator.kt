package io.github.dotnetsupport.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.lsp.RoslynServerStatus

/**
 * Colors types, methods and members by the token stream ([CSharpIdentifierClassifier]), in the three coarse keys of [CSharpColors]. Runs
 * once per file. Steps aside for the semantic tokens of the server once they color the file, and for the plugin's own tree when
 * `SEMANTIC_COLORS` is NATIVE ([NativeCSharpSemanticColors.serves]).
 */
class CSharpIdentifierAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is CSharpFile) return
        if (NativeCSharpSemanticColors.serves(element)) return
        // the semantic tokens of the language server say the same exactly, in the same colors (from its cache too), once they are on screen
        if (RoslynServerStatus.colorsIdentifiers(element.project, element.virtualFile)) return
        for ((range, kind) in CSharpIdentifierClassifier.classify(element.viewProvider.contents)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(keyFor(kind)).create()
        }
    }

    companion object {
        val TYPE: TextAttributesKey get() = CSharpColors.TYPE
        val METHOD: TextAttributesKey get() = CSharpColors.METHOD
        val MEMBER: TextAttributesKey get() = CSharpColors.MEMBER

        fun keyFor(kind: IdentifierKind): TextAttributesKey = when (kind) {
            IdentifierKind.TYPE -> TYPE
            IdentifierKind.METHOD -> METHOD
            IdentifierKind.MEMBER -> MEMBER
        }
    }
}
