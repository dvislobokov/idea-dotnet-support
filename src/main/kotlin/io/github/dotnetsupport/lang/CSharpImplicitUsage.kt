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

    companion object {
        val EP_NAME: ExtensionPointName<CSharpImplicitUsage> = ExtensionPointName.create("io.github.dotnetsupport.csharpImplicitUsage")

        fun of(declaration: PsiElement, session: CSharpSemanticSession): String? = EP_NAME.extensionList.firstNotNullOfOrNull { extension ->
            try {
                extension.implicitUsage(declaration, session)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: RuntimeException) {
                null // a framework's guess never takes the lens down
            }
        }
    }
}
