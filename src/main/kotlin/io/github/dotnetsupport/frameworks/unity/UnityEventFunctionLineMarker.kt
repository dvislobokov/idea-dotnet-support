package io.github.dotnetsupport.frameworks.unity

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.lang.CSharpFile

/**
 * The Unity icon in the gutter of an event function (Rider's "Unity event function" marker): Unity calls it by name; the frame methods
 * (`Update`, `FixedUpdate`, `LateUpdate`, `OnGUI`) say they run every frame — the code in them is performance-critical.
 */
class UnityEventFunctionLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val method = element.parent as? CSharpMethodDeclaration ?: return null
        if (method.identifier != element) return null
        val file = element.containingFile as? CSharpFile ?: return null
        if (!UnityInspectionSupport.isUnityFile(file)) return null
        if (method.modifiers.any { it.text == "override" }) return null
        val type = UnityInspectionSupport.typeOf(method) ?: return null
        val base = UnityTypes.unityBase(type) ?: return null
        val name = element.text
        if (name !in UnityMessages.of(base)) return null
        val frame = name in FRAME && base == "UnityEngine.MonoBehaviour"
        val tooltip = if (frame) "Unity event function '$name': called every frame (performance-critical)" else "Unity event function '$name': called by Unity"
        return LineMarkerInfo(element, element.textRange, UnityIcons.Unity, { tooltip }, null, GutterIconRenderer.Alignment.LEFT) { tooltip }
    }

    private companion object {
        val FRAME = setOf("Update", "FixedUpdate", "LateUpdate", "OnGUI")
    }
}
