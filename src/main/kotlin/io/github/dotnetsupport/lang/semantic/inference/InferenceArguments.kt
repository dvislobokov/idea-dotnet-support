package io.github.dotnetsupport.lang.semantic.inference

import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.SemanticTrace
import io.github.dotnetsupport.lang.semantic.roslyn.InferenceArgument
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodTypeInferrer

/**
 * An argument of a call as Roslyn's method type inference sees it — the kind of its `BoundExpression` ([InferenceArgument]). One file of
 * this package per construct, so a refusal the log of the harness shows is fixed where that construct is read: [LambdaArgument],
 * [MethodGroupArgument], [TupleArgument]; here only which one an expression is. The port itself ([RoslynMethodTypeInferrer]) stays one
 * file, method for method with Roslyn's `MethodTypeInferrer.cs`.
 */
internal class InferenceArguments(private val r: CSharpNameResolver) {
    val lambdas = LambdaArgument(r)
    val methodGroups = MethodGroupArgument(r)
    private val tuples = TupleArgument(r, this)

    /** [expression] as the inference sees it; [lenient]: an expression of no known type gives no inference instead of a refusal. */
    fun of(expression: CSharpExpression, lenient: Boolean): InferenceArgument {
        val value = r.overloads.unparenthesized(expression)
        val unknownType = { if (lenient) InferenceArgument.Typed(null) else throw RoslynMethodTypeInferrer.Unknown("the type of argument `${SemanticTrace.text(value)}` is not known") }
        return when {
            value is CSharpAnonymousFunctionExpression -> lambdas.of(value)
            methodGroups.isMethodGroup(value) -> methodGroups.of(value)
            value.elementType == SyntaxKind.NullLiteralExpression || value.elementType == SyntaxKind.DefaultLiteralExpression -> InferenceArgument.Typed(null)
            // `out T x` has its type; `out var x` has none yet
            value is CSharpDeclarationExpression -> InferenceArgument.Typed(value.type?.takeIf { !r.isVar(it) }?.let { r.resolveType(it) ?: throw RoslynMethodTypeInferrer.Unknown() })
            value is CSharpTupleExpression -> tuples.of(value, lenient)
            value is CSharpCollectionExpression || value is CSharpImplicitObjectCreationExpression || value is CSharpThrowExpression -> InferenceArgument.Opaque
            // a conditional without a natural type is target-typed: not modeled
            value is CSharpConditionalExpression -> r.typeOf(value)?.let { InferenceArgument.Typed(it) } ?: InferenceArgument.Opaque
            else -> r.typeOf(value)?.let { InferenceArgument.Typed(it) } ?: unknownType()
        }
    }
}
