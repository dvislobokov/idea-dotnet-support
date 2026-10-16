package io.github.dotnetsupport.lang.semantic.inference

import io.github.dotnetsupport.csharp.lang.psi.CSharpCollectionExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpExpressionElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpSpreadElement
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.SemanticTrace
import io.github.dotnetsupport.lang.semantic.roslyn.InferenceArgument
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodTypeInferrer

/**
 * A collection expression `[a, ..b]` as Roslyn's inference sees it (`BoundUnconvertedCollectionExpression`, C# 12): each element as the
 * argument it is, each spread by the element type of its value; the port bounds them against the iteration type of the target
 * (`MakeCollectionExpressionTypeInferences`). bUnit's `Add(p => p.Items, [..])` in MudBlazor's tests was a refusal (D6e3).
 */
internal class CollectionArgument(private val r: CSharpNameResolver, private val arguments: InferenceArguments) {

    fun of(value: CSharpCollectionExpression, lenient: Boolean): InferenceArgument.Collection = InferenceArgument.Collection(value.elements.map { element ->
        when (element) {
            is CSharpExpressionElement -> InferenceArgument.CollectionElement.Expression(element.expression?.let { arguments.of(it, lenient) } ?: InferenceArgument.Opaque)
            is CSharpSpreadElement -> InferenceArgument.CollectionElement.Spread(spreadElementType(element, lenient))
            else -> throw RoslynMethodTypeInferrer.Unknown("an element `${SemanticTrace.text(element)}` of a collection expression is not modeled")
        }
    })

    /** What `foreach` over the spread value gives; an unknown one is no bound when [lenient] (as an error type), else a refusal. */
    private fun spreadElementType(element: CSharpSpreadElement, lenient: Boolean) =
        element.expression?.let(r::typeOf)?.let(r::elementType)
            ?: if (lenient) null else throw RoslynMethodTypeInferrer.Unknown("the element type of the spread `${SemanticTrace.text(element)}` is not known")
}
