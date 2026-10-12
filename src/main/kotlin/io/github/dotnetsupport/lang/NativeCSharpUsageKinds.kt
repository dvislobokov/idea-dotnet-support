package io.github.dotnetsupport.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * [CSharpUsageKind] of a usage on csharp-psi's tree (CSHARP_PSI_MIGRATION.md, step 9, feature `USAGE_KINDS`): the leaf at the start of
 * the usage and the nodes above it decide, where [CSharpUsageKinds] guesses from the neighbouring tokens. The kinds and their meaning are
 * the heuristic's; the tree answers better where tokens cannot tell (deconstruction, a member set by a nested initializer, the text of an
 * interpolated string, query variables, `is` patterns), the differences are listed in CSHARP_PSI_MIGRATION.md. Syntax only: `nameof` is
 * the invocation of an identifier `nameof`, `x is A.B` is a type check whatever `A.B` is.
 */
object NativeCSharpUsageKinds {
    /** [text] parsed as a native file of its own: for tests and for texts that are no file of a project. */
    fun classify(text: CharSequence, range: TextRange): CSharpUsageKind = read { Analysis(NativeCSharpSyntaxModel.parse(text)).kindOf(range) }

    /** One per file and change of it, as [CSharpUsageKinds.Analysis]; the tree is the file's own, so nothing is computed up front. */
    class Analysis(private val file: CSharpFile) : CSharpUsageKindAnalysis {
        override fun kindOf(range: TextRange): CSharpUsageKind = read {
            val leaf = file.findElementAt(range.startOffset)
            if (leaf == null) CSharpUsageKind.READ else kindOfLeaf(leaf)
        }
    }

    private val STRING_TOKENS = setOf(
        SyntaxKind.StringLiteralToken, SyntaxKind.CharacterLiteralToken, SyntaxKind.InterpolatedStringTextToken, SyntaxKind.SingleLineRawStringLiteralToken,
        SyntaxKind.MultiLineRawStringLiteralToken, SyntaxKind.Utf8StringLiteralToken, SyntaxKind.Utf8SingleLineRawStringLiteralToken, SyntaxKind.Utf8MultiLineRawStringLiteralToken,
    )

    private val COMMENT_TOKENS = setOf(
        SyntaxKind.SingleLineCommentTrivia, SyntaxKind.MultiLineCommentTrivia, SyntaxKind.SingleLineDocumentationCommentTrivia, SyntaxKind.MultiLineDocumentationCommentTrivia,
    )

    internal fun kindOfLeaf(leaf: PsiElement): CSharpUsageKind {
        val type = leaf.node.elementType
        if (type in COMMENT_TOKENS) return CSharpUsageKind.COMMENT
        // inside a doc comment the leaf is one of its parsed tree (a cref, XML text); directives and disabled text are no comments to Find Usages
        val comment = PsiTreeUtil.getParentOfType(leaf, PsiComment::class.java, false)
        if (comment != null) return if (comment.node.elementType in COMMENT_TOKENS) CSharpUsageKind.COMMENT else CSharpUsageKind.READ
        if (type in STRING_TOKENS) return CSharpUsageKind.STRING
        val parent = leaf.parent ?: return CSharpUsageKind.READ
        if (parent is CSharpSimpleName) return kindOfName(parent)
        return if (namesDeclaration(parent, leaf)) CSharpUsageKind.DECLARATION else CSharpUsageKind.READ
    }

    /** [leaf] is the identifier (`this` of an indexer, `operator`) that declares [parent]: members, locals, parameters, query and pattern variables. */
    private fun namesDeclaration(parent: PsiElement, leaf: PsiElement): Boolean = leaf == when (parent) {
        is CSharpParameter -> parent.identifier
        is CSharpSingleVariableDesignation -> parent.identifier
        is CSharpForEachStatement -> parent.identifier
        is CSharpLocalFunctionStatement -> parent.identifier
        is CSharpTypeParameter -> parent.identifier
        is CSharpCatchDeclaration -> parent.identifier
        is CSharpFromClause -> parent.identifier
        is CSharpLetClause -> parent.identifier
        is CSharpJoinClause -> parent.identifier
        is CSharpJoinIntoClause -> parent.identifier
        is CSharpQueryContinuation -> parent.identifier
        is CSharpTupleElement -> parent.identifier
        is CSharpLabeledStatement -> parent.identifier
        is CSharpExternAliasDirective -> parent.identifier
        else -> CSharpDeclarationNames.nameElement(parent)
    }

    /** The role of the name [name] (`X`, `X<T>`) in what is around it; qualified names and member accesses take the role of the whole. */
    private fun kindOfName(name: CSharpSimpleName): CSharpUsageKind {
        var current: PsiElement = name
        while (true) {
            val parent = current.parent ?: return CSharpUsageKind.READ
            when (parent) {
                // a part of a name or a type stands for the whole: `new Shop.Order()`, `Order[] a`, `Order? a`, `(Order a, int b) t`
                is CSharpQualifiedName, is CSharpAliasQualifiedName, is CSharpArrayType, is CSharpNullableType, is CSharpPointerType, is CSharpRefType,
                is CSharpScopedType, is CSharpTupleElement, is CSharpTupleType, is CSharpParenthesizedExpression, is CSharpMemberBindingExpression -> {}
                is CSharpTypeArgumentList -> return CSharpUsageKind.TYPE_ARGUMENT
                is CSharpBaseNamespaceDeclaration -> return if (current == parent.nameElement) CSharpUsageKind.DECLARATION else CSharpUsageKind.READ
                // `a.X` is the role of `X`; `X.a` reads `X` (or names it in `nameof(X.a)`)
                is CSharpMemberAccessExpression -> if (current != parent.nameElement) return qualifier(parent)
                is CSharpConditionalAccessExpression -> if (current != parent.whenNotNull) return qualifier(parent)
                is CSharpPostfixUnaryExpression -> when (parent.operatorToken?.text) {
                    "++", "--" -> return CSharpUsageKind.WRITE
                    "!" -> {} // `x!` is `x`
                    else -> return CSharpUsageKind.READ
                }
                is CSharpPrefixUnaryExpression -> return if (parent.operatorToken?.text in setOf("++", "--")) CSharpUsageKind.WRITE else CSharpUsageKind.READ
                is CSharpInvocationExpression -> return if (current == parent.expression) CSharpUsageKind.INVOCATION else CSharpUsageKind.READ
                is CSharpTupleExpression -> {}
                is CSharpArgument -> when {
                    current != parent.expression -> return CSharpUsageKind.READ // `M(name: x)`
                    parent.parent is CSharpTupleExpression -> {} // `(a, b) = ...`: the tuple decides
                    inNameof(parent) -> return CSharpUsageKind.NAMEOF
                    else -> return if (parent.refKindKeyword?.text in setOf("ref", "out")) CSharpUsageKind.WRITE else CSharpUsageKind.READ
                }
                // a member given a nested initializer is read, its members are set: `new Order { Lines = { a }, Customer = { Name = n } }`
                is CSharpAssignmentExpression -> return if (current == parent.left && parent.right !is CSharpInitializerExpression) CSharpUsageKind.WRITE else CSharpUsageKind.READ
                is CSharpRefExpression -> return CSharpUsageKind.WRITE // `ref x`, as `ref x` of an argument
                is CSharpTypeOfExpression -> return CSharpUsageKind.TYPEOF
                is CSharpObjectCreationExpression -> return if (current == parent.type) CSharpUsageKind.NEW else CSharpUsageKind.READ
                is CSharpArrayCreationExpression -> return if (current == parent.type) CSharpUsageKind.NEW else CSharpUsageKind.READ
                is CSharpCastExpression -> return if (current == parent.type) CSharpUsageKind.CAST else CSharpUsageKind.READ
                is CSharpBinaryExpression -> return if (current == parent.right && parent.operatorToken?.text in setOf("is", "as")) CSharpUsageKind.TYPE_CHECK else CSharpUsageKind.READ
                is CSharpTypePattern -> return CSharpUsageKind.TYPE_CHECK
                is CSharpDeclarationPattern -> return if (current == parent.type) CSharpUsageKind.TYPE_CHECK else CSharpUsageKind.READ
                is CSharpRecursivePattern -> return if (current == parent.type) CSharpUsageKind.TYPE_CHECK else CSharpUsageKind.READ
                // `x is not Order` (a name after `is` may be a type or a constant: syntax alone cannot tell, as the heuristic it is a type check)
                is CSharpConstantPattern -> return if (isNameLike(current) && inIsPattern(parent)) CSharpUsageKind.TYPE_CHECK else CSharpUsageKind.READ
                is CSharpBaseType -> return if (current == parent.type) CSharpUsageKind.BASE_TYPE else CSharpUsageKind.READ
                is CSharpUsingDirective -> return CSharpUsageKind.USING
                is CSharpNameEquals -> return when (parent.parent) {
                    is CSharpUsingDirective -> CSharpUsageKind.USING
                    is CSharpAnonymousObjectMemberDeclarator -> CSharpUsageKind.DECLARATION // `new { Total = 1 }` declares `Total`
                    else -> CSharpUsageKind.WRITE // `[Note(Text = "a")]`
                }
                is CSharpAttribute -> return if (current == parent.nameElement) CSharpUsageKind.ATTRIBUTE else CSharpUsageKind.READ
                else -> return if (isDeclarationType(parent, current)) CSharpUsageKind.DECLARATION_TYPE else CSharpUsageKind.READ
            }
            current = parent
        }
    }

    /** [type] is the type of the declaration [owner]: of a variable, parameter, field, property, event, method, operator, local function, lambda. */
    private fun isDeclarationType(owner: PsiElement, type: PsiElement): Boolean = type == when (owner) {
        is CSharpVariableDeclaration -> owner.type
        is CSharpBaseParameter -> owner.type
        is CSharpBasePropertyDeclaration -> owner.type
        is CSharpMethodDeclaration -> owner.returnType
        is CSharpOperatorDeclaration -> owner.returnType
        is CSharpConversionOperatorDeclaration -> owner.type
        is CSharpDelegateDeclaration -> owner.returnType
        is CSharpLocalFunctionStatement -> owner.returnType
        is CSharpParenthesizedLambdaExpression -> owner.returnType
        is CSharpForEachStatement -> owner.type
        is CSharpDeclarationExpression -> owner.type
        is CSharpCatchDeclaration -> owner.type
        is CSharpFromClause -> owner.type
        is CSharpJoinClause -> owner.type
        else -> null
    }

    /** A usage that qualifies [access] (`X.a`, `X?.a`): a read, or a name in `nameof(X.a)`. */
    private fun qualifier(access: PsiElement): CSharpUsageKind {
        var top = access
        while (top.parent.let { it is CSharpMemberAccessExpression || it is CSharpConditionalAccessExpression || it is CSharpMemberBindingExpression }) top = top.parent
        val argument = top.parent as? CSharpArgument
        return if (argument != null && argument.expression == top && inNameof(argument)) CSharpUsageKind.NAMEOF else CSharpUsageKind.READ
    }

    /** [argument] is the argument of `nameof(...)`. */
    private fun inNameof(argument: CSharpArgument): Boolean {
        val invocation = argument.parent?.parent as? CSharpInvocationExpression ?: return false
        return (invocation.expression as? CSharpIdentifierName)?.identifier?.text == "nameof"
    }

    private fun isNameLike(element: PsiElement): Boolean = element is CSharpName || element is CSharpMemberAccessExpression

    /** [pattern] is the pattern of `x is ...`, maybe under `not`, `and`, `or` or parentheses. */
    private fun inIsPattern(pattern: CSharpPattern): Boolean {
        var current: PsiElement = pattern
        while (current.parent.let { it is CSharpUnaryPattern || it is CSharpBinaryPattern || it is CSharpParenthesizedPattern }) current = current.parent
        return current.parent is CSharpIsPatternExpression
    }

    private fun <T> read(compute: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) compute() else ReadAction.compute<T, RuntimeException>(compute)
}
