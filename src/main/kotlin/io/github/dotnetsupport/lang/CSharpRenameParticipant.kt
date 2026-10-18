package io.github.dotnetsupport.lang

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * What a framework writes beside a rename of a type or member of the solution (RAZOR_PLAN.md, "Модульность": a point of the core a
 * framework module fills; UNITY_PLAN.md, the asset index): Unity's scenes keep the value of a serialized field under its name, so the
 * rename of such a field adds `[FormerlySerializedAs("old")]`, as Rider does; the markup of Razor names a component by its tag and a
 * parameter by an attribute, and its `.razor` file is named after the component. Computed with the plan of the rename (a read action,
 * offsets of the text before it) and written in the same command.
 */
interface CSharpRenameParticipant {
    /** A text to insert at [offset] of [file] (in the text before the rename). */
    class Insertion(val file: CSharpFile, val offset: Int, val text: String)

    /** A place of [file] (of any language) that names the symbol: the new name is written over [range]. */
    class Occurrence(val file: PsiFile, val range: TextRange)

    /** What to insert when [declaration] (a declaration of the renamed symbol) is renamed from [oldName]. */
    fun insertions(declaration: PsiElement, oldName: String): List<Insertion> = emptyList()

    /** The places outside the C# that name [declaration], renamed from [oldName]: a tag, an attribute of markup. */
    fun occurrences(declaration: PsiElement, oldName: String): List<Occurrence> = emptyList()

    /** The files named after the type [declaration] declares besides its C# files (`Old.razor`, `Old.razor.css`): `Old` of their names is renamed. */
    fun namedFiles(declaration: PsiElement, oldName: String): List<PsiFile> = emptyList()

    companion object {
        val EP_NAME: ExtensionPointName<CSharpRenameParticipant> = ExtensionPointName.create("io.github.dotnetsupport.csharpRenameParticipant")

        fun insertions(declaration: PsiElement, oldName: String): List<Insertion> = collect { it.insertions(declaration, oldName) }

        fun occurrences(declaration: PsiElement, oldName: String): List<Occurrence> = collect { it.occurrences(declaration, oldName) }

        fun namedFiles(declaration: PsiElement, oldName: String): List<PsiFile> = collect { it.namedFiles(declaration, oldName) }

        private fun <T> collect(ask: (CSharpRenameParticipant) -> List<T>): List<T> = EP_NAME.extensionList.flatMap { participant ->
            try {
                ask(participant)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: RuntimeException) {
                emptyList() // a framework's addition never takes the rename down
            }
        }
    }
}
