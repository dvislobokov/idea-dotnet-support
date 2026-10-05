package io.github.dotnetsupport.csharp.lang.psi

import com.intellij.psi.PsiElement

/**
 * Roslyn's `CSharpSyntaxNode`: the root of the PSI interfaces generated from `Syntax.xml` (`CSharpPsi.kt`, rules in
 * docs/csharp-psi/GRAMMAR.md, "PSI"). Every composite of a parsed C# file is one; tokens are plain leaves.
 */
interface CSharpElement : PsiElement {
    /** Calls the [CSharpVisitor] method of this element's class (`visitBinaryExpression`, ...). */
    fun accept(visitor: CSharpVisitor)
}
