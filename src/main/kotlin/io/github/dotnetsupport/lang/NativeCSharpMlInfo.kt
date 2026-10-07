package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.ml.CSharpMlCandidate
import io.github.dotnetsupport.ml.CSharpMlCandidateKind
import io.github.dotnetsupport.ml.CSharpMlScope

/**
 * What the native completion knows about each of its items, for the ML ranker (ML_RANKER_EXPORT_TASK.md, ADAPTER.md §3–4): a
 * [CSharpMlCandidate] on the lookup element. Metadata only — nothing of the list changes. The offline export (`CSharpMlDatasetExport`,
 * test scope) and the IDE weigher read it with [infoOf]; an item made by a path that attaches nothing (templates, the server, the
 * second Ctrl+Space) is OTHER at [CSharpMlScope.IMPORTED] with its priority ([fallback]).
 */
object NativeCSharpMlInfo {
    val KEY: Key<CSharpMlCandidate> = Key.create("dotnet.mlCandidate")

    /** The kind, scope and declaration an item maker knows before the priority is final. */
    class Shape(val kind: CSharpMlCandidateKind, val scope: Int, val isStatic: Boolean = false, val needsUsing: Boolean = false, val declaration: (() -> PsiElement?)? = null)

    val OTHER = Shape(CSharpMlCandidateKind.OTHER, CSharpMlScope.IMPORTED)

    /**
     * Attaches the candidate info to [element]. [file] is the file being completed (the completion copy): the declaration offset is taken
     * only when the declaration is in it — a declaration of another file is stub-backed and its offset would load that file's AST.
     */
    fun attach(element: LookupElement, shape: Shape, priority: Double, expectedTypeMatch: Int = 0, file: PsiFile? = null): LookupElement {
        val declaration = if (file == null) null else runCatching { shape.declaration?.invoke() }.getOrNull()
        val offset = if (file != null && declaration != null) declarationOffsetInFile(file, declaration) else null
        element.putUserData(KEY, CSharpMlCandidate(element.lookupString, shape.kind, shape.isStatic, shape.scope, shape.needsUsing, expectedTypeMatch, offset, priority))
        return element
    }

    /** The info of the item, through decorators (`PrioritizedLookupElement`, the grayed rows of the second Ctrl+Space). */
    fun infoOf(element: LookupElement): CSharpMlCandidate? {
        var e: LookupElement? = element
        while (e != null) {
            e.getUserData(KEY)?.let { return it }
            e = (e as? LookupElementDecorator<*>)?.delegate
        }
        return null
    }

    /** The priority of the item under the plugin's rules (0 for an item nobody prioritized). */
    fun priorityOf(element: LookupElement): Double = element.`as`(PrioritizedLookupElement::class.java)?.priority ?: 0.0

    /** The candidate of an item the plugin told nothing about. */
    fun fallback(element: LookupElement): CSharpMlCandidate =
        CSharpMlCandidate(element.lookupString, CSharpMlCandidateKind.OTHER, false, CSharpMlScope.IMPORTED, false, 0, null, priorityOf(element))

    /** [infoOf] or [fallback]. */
    fun candidateOf(element: LookupElement): CSharpMlCandidate = infoOf(element) ?: fallback(element)

    /** Offset of [declaration] when it is in [file] (the copy being completed or its original), else null; never loads another file. */
    fun declarationOffsetInFile(file: PsiFile, declaration: PsiElement): Int? {
        if (!declaration.isValid) return null
        val containing = declaration.containingFile ?: return null
        val same = containing === file || containing === file.originalFile || (containing.virtualFile == null && containing.name == file.name)
        if (!same) return null
        return runCatching { declaration.textOffset }.getOrNull()
    }
}
