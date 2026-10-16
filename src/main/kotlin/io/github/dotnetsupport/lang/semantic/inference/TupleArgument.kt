package io.github.dotnetsupport.lang.semantic.inference

import io.github.dotnetsupport.csharp.lang.psi.CSharpTupleExpression
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.roslyn.InferenceArgument

/** A tuple literal as Roslyn's inference sees it (`BoundTupleLiteral`): its natural type when every element has one, and its elements. */
internal class TupleArgument(private val r: CSharpNameResolver, private val arguments: InferenceArguments) {

    fun of(value: CSharpTupleExpression, lenient: Boolean): InferenceArgument.Tuple =
        InferenceArgument.Tuple(r.typeOf(value), value.arguments.map { a -> a.expression?.let { arguments.of(it, lenient) } ?: InferenceArgument.Opaque })
}
