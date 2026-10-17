package io.github.dotnetsupport.lang

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement

/**
 * What a framework writes beside a rename of a type or member of the solution (RAZOR_PLAN.md, "Модульность": a point of the core a
 * framework module fills; UNITY_PLAN.md, the asset index): Unity's scenes keep the value of a serialized field under its name, so the
 * rename of such a field adds `[FormerlySerializedAs("old")]`, as Rider does. The insertions are computed with the plan of the rename
 * (a read action, offsets of the text before it) and written in the same command.
 */
interface CSharpRenameParticipant {
    /** A text to insert at [offset] of [file] (in the text before the rename). */
    class Insertion(val file: CSharpFile, val offset: Int, val text: String)

    /** What to insert when [declaration] (a declaration of the renamed symbol) is renamed from [oldName]. */
    fun insertions(declaration: PsiElement, oldName: String): List<Insertion>

    companion object {
        val EP_NAME: ExtensionPointName<CSharpRenameParticipant> = ExtensionPointName.create("io.github.dotnetsupport.csharpRenameParticipant")

        fun insertions(declaration: PsiElement, oldName: String): List<Insertion> = EP_NAME.extensionList.flatMap { participant ->
            try {
                participant.insertions(declaration, oldName)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: RuntimeException) {
                emptyList() // a framework's addition never takes the rename down
            }
        }
    }
}
