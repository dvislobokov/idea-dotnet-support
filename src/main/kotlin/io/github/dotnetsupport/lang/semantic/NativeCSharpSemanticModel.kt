package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.csharp.lang.psi.CSharpExpression
import io.github.dotnetsupport.csharp.semantic.CSharpDiagnosticRef
import io.github.dotnetsupport.csharp.semantic.CSharpSemanticModel
import io.github.dotnetsupport.csharp.semantic.CSharpSymbolRef
import io.github.dotnetsupport.csharp.semantic.CSharpTypeRef
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpLeaves

/**
 * The plugin's own [CSharpSemanticModel] (CSHARP_PSI_MIGRATION.md, step 11): names by [CSharpNameResolver] (layer 11a), types of expressions
 * by [CSharpNameResolver.expressionType] (11b), the semantic errors C4c answers ([CSharpSemanticChecks], part of 11e). Its answers are of one
 * [session]: a model per question, or per unchanged set of files.
 */
class NativeCSharpSemanticModel(val session: CSharpSemanticSession) : CSharpSemanticModel {
    override fun symbolAt(file: PsiFile, offset: Int): CSharpSymbolRef? {
        val csharp = file as? CSharpFile ?: return null
        if (csharp.compilationUnit == null || DumbService.isDumb(file.project)) return null
        val leaf = file.findElementAt(offset)?.takeIf { it.textRange.startOffset == offset && CSharpLeaves.isIdentifier(it) } ?: return null
        val resolution = session.resolver(csharp).resolve(leaf) ?: return null
        resolution.single?.let { return ref(it) }
        return CSharpSymbolRef(candidates = resolution.symbols.map(::ref))
    }

    /** The type of the expression node (type syntax included) that spans exactly [range]; the innermost one when several do. */
    override fun typeOf(file: PsiFile, range: TextRange): CSharpTypeRef? {
        val csharp = file as? CSharpFile ?: return null
        if (csharp.compilationUnit == null || DumbService.isDumb(file.project)) return null
        var element: PsiElement? = file.findElementAt(range.startOffset)
        while (element != null && element !is PsiFile && element.textRange.startOffset == range.startOffset) {
            if (element.textRange == range && element is CSharpExpression) {
                return session.resolver(csharp).expressionType(element)?.display?.let(::CSharpTypeRef)
            }
            if (element.textRange.endOffset > range.endOffset) break
            element = element.parent
        }
        return null
    }

    override fun diagnostics(file: PsiFile): List<CSharpDiagnosticRef> {
        val csharp = file as? CSharpFile ?: return emptyList()
        if (csharp.compilationUnit == null || DumbService.isDumb(file.project)) return emptyList()
        return CSharpSemanticChecks(session.resolver(csharp)).run().map { CSharpDiagnosticRef(it.code, it.range, it.isError) }
    }

    companion object {
        fun ref(symbol: CSharpSymbol): CSharpSymbolRef = CSharpSymbolRef(symbol.declarations, symbol.id)
    }
}
