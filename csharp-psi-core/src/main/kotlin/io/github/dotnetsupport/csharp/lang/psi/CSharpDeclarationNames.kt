package io.github.dotnetsupport.csharp.lang.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes

/**
 * What names a declaration, by syntax only (the host turns it into presentation, icons and its declaration model): the identifier of a
 * type, member, enum member or variable declarator (of a field with one declarator too), `this` of an indexer, the `operator` keyword
 * of an operator, the name of a namespace. The texts follow C#: `~Finalizer`, `operator ==`, `operator int`, `A.B` (a namespace name
 * without the whitespace or comments in it).
 */
object CSharpDeclarationNames {
    /** The element that names [element] when it is a declaration, null for any other element. */
    fun nameElement(element: PsiElement): PsiElement? = when (element) {
        is CSharpBaseNamespaceDeclaration -> element.nameElement
        is CSharpBaseTypeDeclaration -> element.identifier
        is CSharpDelegateDeclaration -> element.identifier
        is CSharpMethodDeclaration -> element.identifier
        is CSharpConstructorDeclaration -> element.identifier
        is CSharpDestructorDeclaration -> element.identifier
        is CSharpOperatorDeclaration -> element.operatorKeyword
        is CSharpConversionOperatorDeclaration -> element.operatorKeyword
        is CSharpPropertyDeclaration -> element.identifier
        is CSharpEventDeclaration -> element.identifier
        is CSharpIndexerDeclaration -> element.thisKeyword
        is CSharpEnumMemberDeclaration -> element.identifier
        is CSharpVariableDeclarator -> element.identifier
        // `int _count;` is named by its declarator; `int a, b;` by none (each declarator is a declaration of its own)
        is CSharpBaseFieldDeclaration -> element.declaration?.variables?.singleOrNull()?.identifier
        else -> null
    }

    /** The name of the declaration [element], null for any other element or a declaration whose name is missing. */
    fun name(element: PsiElement): String? {
        val name = nameElement(element) ?: return null
        return when (element) {
            is CSharpBaseNamespaceDeclaration -> compact(name.node)
            is CSharpDestructorDeclaration -> "~" + name.text
            is CSharpOperatorDeclaration, is CSharpConversionOperatorDeclaration -> {
                // the tokens between `operator` and the parameter list: `==`, `>>`, `checked +`, the type of a conversion
                val symbol = StringBuilder()
                var next = name.node.treeNext
                while (next != null && next.psi.let { it !is CSharpParameterList && it !is CSharpBlock && it !is CSharpArrowExpressionClause } && next.text != ";") {
                    symbol.append(compact(next))
                    next = next.treeNext
                }
                "operator $symbol"
            }
            else -> name.text
        }
    }

    /** The text of [node] without whitespace and comments. */
    private fun compact(node: ASTNode): String {
        if (node.elementType in CSharpTokenTypes.WHITESPACES || node.elementType in CSharpTokenTypes.COMMENTS || node.psi.let { it is PsiWhiteSpace || it is PsiComment }) return ""
        val first = node.firstChildNode ?: return node.text
        val text = StringBuilder()
        var child: ASTNode? = first
        while (child != null) {
            text.append(compact(child))
            child = child.treeNext
        }
        return text.toString()
    }
}
