package io.github.dotnetsupport.lang

import com.intellij.ide.util.ModuleRendererFactory
import com.intellij.psi.PsiElement
import com.intellij.util.TextWithIcon

/**
 * The right side of a row of Go to Class / Symbol for a C# declaration: its file, as the platform shows it for the symbols of the
 * language server, not the module (one module, the opened folder, for the whole solution: it told nothing).
 */
class CSharpLocationRenderer : ModuleRendererFactory() {
    override fun handles(element: Any?): Boolean = element is PsiElement && element.isValid && CSharpSyntaxModel.current.declarationOf(element) != null

    override fun getModuleTextWithIcon(element: Any?): TextWithIcon? {
        val file = (element as? PsiElement)?.takeIf { it.isValid }?.containingFile ?: return null
        return TextWithIcon(file.name, file.fileType.icon)
    }
}
