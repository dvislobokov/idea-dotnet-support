package io.github.dotnetsupport.lang

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession

/**
 * A declaration a framework uses without any code that says so (RAZOR_PLAN.md, "Модульность": the point of the core a framework module
 * fills; UNITY_PLAN.md, step 2): the messages of Unity's `MonoBehaviour` (`Update`, `OnTriggerEnter`) are called by the engine, its
 * serialized fields are set by the inspector, a component class is created by a scene. Where the core would say "no usages" (the usages
 * code vision), it shows what [implicitUsage] answers instead ("Event function", as Rider does).
 */
interface CSharpImplicitUsage {
    /** What uses [declaration] (a type, a member or a declarator of a field) behind the code, for the user to read; null when nothing does. */
    fun implicitUsage(declaration: PsiElement, session: CSharpSemanticSession): String?

    /**
     * The uses of [declaration] the framework found outside the code (UNITY_PLAN.md, the asset index: "Used in Boot.unity" over a script
     * of a scene, "Set by Unity: 5" over a field the inspector set, "Event handler in Menu.prefab"), for the lens to show beside the
     * count of the usages in code; null for none.
     */
    fun usageNote(declaration: PsiElement, session: CSharpSemanticSession): String? = null

    companion object {
        val EP_NAME: ExtensionPointName<CSharpImplicitUsage> = ExtensionPointName.create("io.github.dotnetsupport.csharpImplicitUsage")

        fun of(declaration: PsiElement, session: CSharpSemanticSession): String? = first { it.implicitUsage(declaration, session) }

        fun noteOf(declaration: PsiElement, session: CSharpSemanticSession): String? = first { it.usageNote(declaration, session) }

        private fun first(ask: (CSharpImplicitUsage) -> String?): String? = EP_NAME.extensionList.firstNotNullOfOrNull { extension ->
            try {
                ask(extension)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: RuntimeException) {
                null // a framework's guess never takes the lens down
            }
        }

        /** The text of the usages lens: [count] usages in code, what a framework says when there are none, and its note beside both. */
        fun lensText(count: String, total: Int, implicit: () -> String?, note: String?): String = when {
            note == null -> (if (total == 0) implicit() else null) ?: count
            total == 0 -> note
            else -> "$count, " + note.replaceFirstChar { it.lowercaseChar() }
        }
    }
}
