package io.github.dotnetsupport.frameworks.razor

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpPropertyDeclaration
import io.github.dotnetsupport.lang.CSharpImplicitUsage
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession

/**
 * The uses of a component, a tag helper and their parameters in the markup, for the usages lens of the C# (RAZOR_PLAN.md, R4: "Code Vision
 * does not count the markup" — `EmailTagHelper` said "no usages" over four `<email>` tags): "4 usages in markup", beside the count of the
 * C#. Only for a type the project's descriptors name and a property one of them binds — the search of the markup is not run for every
 * member of every class.
 */
class RazorMarkupUsages : CSharpImplicitUsage {
    override fun implicitUsage(declaration: PsiElement, session: CSharpSemanticSession): String? = null

    override fun usageNote(declaration: PsiElement, session: CSharpSemanticSession): String? {
        if (declaration !is CSharpBaseTypeDeclaration && declaration !is CSharpPropertyDeclaration) return null
        val known = RazorTagHelperService.getInstance(declaration.project).known()
        if (known.isEmpty()) return null
        val bound = when (declaration) {
            is CSharpBaseTypeDeclaration -> RazorTagHelperUsages.componentOf(declaration)?.let { name -> known.any { RazorTagHelperEditor.typeNameOf(it) == name } }
            else -> RazorTagHelperUsages.parameterOf(declaration)?.let { (type, property) ->
                known.any { RazorTagHelperEditor.typeNameOf(it) == type && it.boundAttributes.any { a -> a.propertyName == property } }
            }
        } ?: false
        if (!bound) return null
        val count = RazorTagHelperUsages.places(declaration, GlobalSearchScope.projectScope(declaration.project)).size
        return when (count) {
            0 -> null
            1 -> "1 usage in markup"
            else -> "$count usages in markup"
        }
    }
}
