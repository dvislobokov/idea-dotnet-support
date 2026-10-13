package io.github.dotnetsupport.lang

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.CSharpCompilationUnit
import io.github.dotnetsupport.csharp.lang.psi.CSharpGlobalStatement

/**
 * The scopes of the C# live templates, as Rider's: a statement, an expression, a type member, a type declaration, a namespace. Under the
 * `CSHARP` context of [CSharpTemplateContext] (plugin.xml `baseContextId`), so a template of the whole file (`#region`, `nguid`) still goes
 * everywhere. `class X : wh|` is none of them: no `while` there (0.1.178).
 */
object CSharpTemplateScopes {
    enum class Scope { STATEMENT, EXPRESSION, MEMBER, TYPE, NAMESPACE }

    /** The scopes at [offset]; null where the tree says nothing (no word at the caret, the heuristic tree): every template goes there, as before. */
    fun at(file: PsiFile, offset: Int): Set<Scope>? {
        if (file !is CSharpFile) return null
        val leaf = word(file, offset) ?: return null
        // the heuristic tree has no places
        if (PsiTreeUtil.getParentOfType(leaf, CSharpCompilationUnit::class.java) == null) return null
        val place = NativeCSharpCompletionPlace.of(leaf) ?: return emptySet()
        return when (place.kind) {
            NativeCompletionKind.STATEMENT -> {
                val global = place.name?.let(NativeCSharpCompletionPlace::statementStart)?.parent is CSharpGlobalStatement
                if (global) setOf(Scope.STATEMENT, Scope.EXPRESSION, Scope.TYPE, Scope.NAMESPACE) else setOf(Scope.STATEMENT, Scope.EXPRESSION)
            }
            NativeCompletionKind.EXPRESSION -> setOf(Scope.EXPRESSION)
            NativeCompletionKind.MEMBER_START -> setOf(Scope.MEMBER, Scope.TYPE)
            NativeCompletionKind.TOP_LEVEL -> setOf(Scope.TYPE, Scope.NAMESPACE)
            else -> emptySet()
        }
    }

    /** The word being expanded: the identifier around [offset] or right before it (the platform asks with the offset of either end). */
    private fun word(file: PsiFile, offset: Int): PsiElement? = listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)
        .firstOrNull { CSharpLeaves.isIdentifier(it) && it.textRange.startOffset <= offset && offset <= it.textRange.endOffset }
}

/** A scope of [CSharpTemplateScopes]: in context where the tree puts it, and where it cannot tell. */
abstract class CSharpTemplateScopeContext(presentableName: String, private val scope: CSharpTemplateScopes.Scope) : TemplateContextType(presentableName) {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file as? CSharpFile ?: return false
        val scopes = CSharpTemplateScopes.at(file, templateActionContext.startOffset) ?: return true
        return scope in scopes
    }
}

class CSharpStatementTemplateContext : CSharpTemplateScopeContext("Statement", CSharpTemplateScopes.Scope.STATEMENT)
class CSharpExpressionTemplateContext : CSharpTemplateScopeContext("Expression", CSharpTemplateScopes.Scope.EXPRESSION)
class CSharpMemberTemplateContext : CSharpTemplateScopeContext("Type member", CSharpTemplateScopes.Scope.MEMBER)
class CSharpTypeTemplateContext : CSharpTemplateScopeContext("Type declaration", CSharpTemplateScopes.Scope.TYPE)
class CSharpNamespaceTemplateContext : CSharpTemplateScopeContext("Namespace", CSharpTemplateScopes.Scope.NAMESPACE)
