package io.github.dotnetsupport.lang.semantic.inference

import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.SemanticTrace
import io.github.dotnetsupport.lang.semantic.roslyn.InferenceArgument
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodTypeInferrer
import io.github.dotnetsupport.lang.semantic.unparenthesized

/**
 * An argument of a call as Roslyn's method type inference sees it — the kind of its `BoundExpression` ([InferenceArgument]). One file of
 * this package per construct, so a refusal the log of the harness shows is fixed where that construct is read: [LambdaArgument],
 * [MethodGroupArgument], [TupleArgument], [CollectionArgument]; here only which one an expression is. The port itself ([RoslynMethodTypeInferrer]) stays one
 * file, method for method with Roslyn's `MethodTypeInferrer.cs`.
 */
internal class InferenceArguments(private val r: CSharpNameResolver) {
    val lambdas = LambdaArgument(r)
    val methodGroups = MethodGroupArgument(r)
    private val tuples = TupleArgument(r, this)
    private val collections = CollectionArgument(r, this)

    /** [expression] as the inference sees it; [lenient]: an expression of no known type gives no inference instead of a refusal. */
    fun of(expression: CSharpExpression, lenient: Boolean): InferenceArgument {
        val value = r.overloads.unparenthesized(expression)
        val unknownType = { if (lenient) InferenceArgument.Typed(null) else throw RoslynMethodTypeInferrer.Unknown("the type of argument `${SemanticTrace.text(value)}` is not known") }
        return when {
            value is CSharpAnonymousFunctionExpression -> lambdas.of(value)
            methodGroups.isMethodGroup(value) -> methodGroups.of(value)
            value.elementType == SyntaxKind.NullLiteralExpression || value.elementType == SyntaxKind.DefaultLiteralExpression -> InferenceArgument.Typed(null)
            // the discard `out _` has no type of its own (Roslyn binds it to the parameter's type once chosen): no inference from it
            r.overloads.isDiscard(value) -> InferenceArgument.Typed(null)
            // `out T x` has its type; `out var x` has none yet
            value is CSharpDeclarationExpression -> InferenceArgument.Typed(value.type?.takeIf { !r.isVar(it) }?.let { r.resolveType(it) ?: throw RoslynMethodTypeInferrer.Unknown() })
            value is CSharpTupleExpression -> tuples.of(value, lenient)
            value is CSharpCollectionExpression -> collections.of(value, lenient)
            // a target-typed `new()`, a `throw`, a conditional without a natural type have no type: no inference from them, as Roslyn
            // (`MakeExplicitParameterTypeInferences`: not `IsReallyAType`) — the others infer the call (bUnit's `Add(p => p.Items, new())`)
            value is CSharpImplicitObjectCreationExpression || value is CSharpThrowExpression -> InferenceArgument.Typed(null)
            value is CSharpConditionalExpression -> InferenceArgument.Typed(r.typeOf(value))
            else -> r.typeOf(value)?.let { InferenceArgument.Typed(it) } ?: unknownType()
        }
    }
}
