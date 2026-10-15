// Ported from Roslyn: src/Compilers/CSharp/Portable/Binder/Semantics/OverloadResolution/MethodTypeInference.cs (MethodTypeInferrer,
// lines 91-3532), roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c. Roslyn: Copyright (c) .NET Foundation and Contributors,
// MIT License (NOTICE.md). Kotlin names are Roslyn's with a lower-case first letter; what is left out and why — CSHARP_PSI_MIGRATION.md, D6b.
package io.github.dotnetsupport.lang.semantic.roslyn

import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * An argument of a call as the inference sees it: Roslyn's `BoundExpression` kinds that matter to it. Built from the syntax by the caller
 * ([io.github.dotnetsupport.lang.semantic.CSharpTypeInference]); a callback throws [RoslynMethodTypeInferrer.Unknown] where it cannot answer.
 */
internal sealed class InferenceArgument {
    /** An expression with a type, or none ([type] null: `null`, `default`) — `GetTypeWithAnnotations`. */
    class Typed(val type: SemanticType?) : InferenceArgument()

    /**
     * `UnboundLambda`. [hasSignature]: a parameter list is written (`x =>`, `(a, b) =>`; not `delegate { }`); [explicitParameterTypes] when
     * all are typed; [inferReturnType]: `InferReturnType` given the parameter types of the fixed delegate, null for none (`void`, no value).
     */
    class Lambda(
        val parameterCount: Int, val hasSignature: Boolean, val explicitParameterTypes: List<SemanticType>?, val explicitReturnType: SemanticType?,
        val inferReturnType: (List<SemanticType>) -> SemanticType?,
    ) : InferenceArgument()

    /** `BoundMethodGroup`: [returnType] is the return type of the one method overload resolution picks for those parameter types, else null. */
    class MethodGroup(val returnType: (List<SemanticType>) -> SemanticType?) : InferenceArgument()

    /** `BoundTupleLiteral`: its elements, [type] its natural type when every element has one. */
    class Tuple(val type: SemanticType?, val elements: List<InferenceArgument>) : InferenceArgument()

    /** An argument the inference does not model (a collection expression, a target-typed `new()`): an inference that needs it is unknown. */
    object Opaque : InferenceArgument()
}

/**
 * C# §12.6.3, method type inference, as Roslyn implements it: two phases, bounds exact / lower / upper, dependencies between type
 * parameters, fixing. Left out: nullability (overload resolution runs without it), function types of lambdas and method groups (C# 10 —
 * an argument that would need one makes the result [Result.Unknown]), `dynamic`, pointers and function pointers, collection expressions.
 * Where Roslyn knows a fact about a type and we may not ([RoslynTypes] answers null), the whole inference is [Result.Unknown]: a failure is
 * reported only when Roslyn would surely report it (CS0411).
 *
 * [ordinalOf] tells the method type parameters being inferred (`_methodTypeParameters`) from any other type parameter; [formalParameterTypes]
 * are the declared types of the parameters the arguments go to, the containing type's parameters already substituted.
 */
internal class RoslynMethodTypeInferrer(
    private val types: RoslynTypes,
    private val arity: Int,
    private val ordinalOf: (SemanticType.Parameter) -> Int?,
    private val formalParameterTypes: List<SemanticType?>,
    /** Per argument: passed by `ref` / `out` / `in` (an exact inference), `GetRefKind(...).IsManagedReference()`. */
    private val formalParameterByReference: List<Boolean>,
    private val arguments: List<InferenceArgument>,
    /** `IsFeatureFirstClassSpanEnabled`: C# 14 — the inferences between arrays and spans. */
    private val firstClassSpan: Boolean = true,
) {
    sealed class Result {
        class Known(val types: List<SemanticType>) : Result()
        object Failed : Result()
        object Unknown : Result()
    }

    /** Thrown where a fact the inference needs is not known: the result is [Result.Unknown]. */
    class Unknown : RuntimeException(null, null, false, false)

    private enum class InferenceResult { INFERENCE_FAILED, MADE_PROGRESS, NO_PROGRESS, SUCCESS }

    private enum class Dependency(val bits: Int) {
        UNKNOWN(0x00), NOT_DEPENDENT(0x01), DIRECT(0x11), INDIRECT(0x12);

        val depends: Boolean get() = bits and 0x10 != 0
    }

    private enum class ExactOrBoundsKind { EXACT, LOWER_BOUND, UPPER_BOUND }

    private val fixedResults = arrayOfNulls<SemanticType>(arity)
    private val exactBounds = arrayOfNulls<MutableList<SemanticType>>(arity)
    private val upperBounds = arrayOfNulls<MutableList<SemanticType>>(arity)
    private val lowerBounds = arrayOfNulls<MutableList<SemanticType>>(arity)
    private var dependencies: Array<Array<Dependency>>? = null
    private var dependenciesDirty = false

    private val numberArgumentsToProcess get() = minOf(arguments.size, formalParameterTypes.size)

    /** `Infer`: the inferred type arguments, [Result.Failed] when inference surely fails, [Result.Unknown] when not known. */
    fun infer(): Result {
        if (arity == 0) return Result.Known(emptyList())
        // Early out: if the method has no formal parameters then we know that inference will fail.
        if (formalParameterTypes.isEmpty()) return Result.Failed
        return try {
            inferTypeArgsFirstPhase()
            if (!inferTypeArgsSecondPhase()) Result.Failed else Result.Known(fixedResults.map { it ?: throw Unknown() })
        } catch (_: Unknown) {
            Result.Unknown
        }
    }

    private fun formal(index: Int): SemanticType = formalParameterTypes[index] ?: throw Unknown()

    // ---- fixed, unfixed and bounded type parameters

    private fun isUnfixed(index: Int): Boolean = fixedResults[index] == null

    private fun isUnfixedTypeParameter(type: SemanticType): Boolean {
        if (type !is SemanticType.Parameter) return false
        val ordinal = ordinalOf(type) ?: return false
        return ordinal in 0 until arity && isUnfixed(ordinal)
    }

    private fun allFixed(): Boolean = (0 until arity).none(::isUnfixed)

    private fun addBound(addedBound: SemanticType, collectedBounds: Array<MutableList<SemanticType>?>, methodTypeParameter: SemanticType.Parameter) {
        val index = ordinalOf(methodTypeParameter) ?: throw Unknown()
        val bounds = collectedBounds[index] ?: ArrayList<SemanticType>().also { collectedBounds[index] = it }
        // a set: the same type once (`TypeWithAnnotations.EqualsComparer`)
        if (bounds.none { types.same(it, addedBound) == true }) bounds += addedBound
    }

    private fun hasBound(index: Int): Boolean = lowerBounds[index] != null || upperBounds[index] != null || exactBounds[index] != null

    /** `GetFixedDelegateOrFunctionPointer`: [delegateType] with the fixed type parameters replaced, the unfixed ones kept. */
    private fun getFixedDelegate(delegateType: SemanticType): SemanticType =
        types.substitute(delegateType) { p -> ordinalOf(p)?.let { fixedResults[it] } ?: p } ?: throw Unknown()

    /** `GetDelegateType`: [type] itself when a delegate, the delegate of `Expression<TDelegate>`, else null. */
    private fun delegateType(type: SemanticType): SemanticType? {
        val delegate = types.delegateOfExpression(type) ?: return null
        return delegate.takeIf { types.isDelegateType(it) }
    }

    private fun containsTypeParameter(type: SemanticType?, ordinal: Int): Boolean = when (type) {
        null -> false
        is SemanticType.Parameter -> ordinalOf(type) == ordinal
        is SemanticType.ArrayOf -> containsTypeParameter(type.element, ordinal)
        is SemanticType.Library -> type.arguments.any { containsTypeParameter(it, ordinal) }
        is SemanticType.Source -> type.arguments.any { containsTypeParameter(it, ordinal) } || containsTypeParameter(type.outer, ordinal)
    }

    private fun containsAnyMethodTypeParameter(type: SemanticType?): Boolean = (0 until arity).any { containsTypeParameter(type, it) }

    // ---- phases

    private fun inferTypeArgsFirstPhase() {
        for (arg in 0 until numberArgumentsToProcess) {
            val target = formal(arg)
            val kind = if (formalParameterByReference.getOrElse(arg) { false }) ExactOrBoundsKind.EXACT else ExactOrBoundsKind.LOWER_BOUND
            makeExplicitParameterTypeInferences(arguments[arg], target, kind)
        }
    }

    private fun makeExplicitParameterTypeInferences(argument: InferenceArgument, target: SemanticType, kind: ExactOrBoundsKind) {
        // SPEC: * If Ei is an anonymous function, and Ti is a delegate type or expression tree type,
        // SPEC:   an explicit type parameter inference is made from Ei to Ti and
        // SPEC:   an explicit return type inference is made from Ei to Ti.
        // SPEC: * Otherwise, if Ei has a type U then a lower-bound inference or exact inference is made from U to Ti.
        // SPEC: * Otherwise, no inference is made for this argument
        when (argument) {
            is InferenceArgument.Lambda -> if (delegateType(target) != null) {
                explicitParameterTypeInference(argument, target)
                explicitReturnTypeInference(argument, target)
            } else if (containsAnyMethodTypeParameter(target)) {
                // the function type of the lambda (C# 10) would be the bound: not modeled
                throw Unknown()
            }
            // a method group to a delegate makes no inference here (no fixed types yet); its function type to a type parameter is not modeled
            is InferenceArgument.MethodGroup -> if (delegateType(target) == null && containsAnyMethodTypeParameter(target)) throw Unknown()
            is InferenceArgument.Tuple -> if (!makeExplicitParameterTypeInferences(argument, target, kind)) {
                argument.type?.let { exactOrBoundsInference(kind, it, target) } ?: run { if (containsAnyMethodTypeParameter(target)) throw Unknown() }
            }
            is InferenceArgument.Typed -> argument.type?.let { if (isReallyAType(it)) exactOrBoundsInference(kind, it, target) }
            InferenceArgument.Opaque -> if (containsAnyMethodTypeParameter(target)) throw Unknown()
        }
    }

    /** A tuple literal to a tuple type of its cardinality: element by element; false when the target is no such tuple. */
    private fun makeExplicitParameterTypeInferences(argument: InferenceArgument.Tuple, target: SemanticType, kind: ExactOrBoundsKind): Boolean {
        val destTypes = types.tupleElementTypes(target)?.takeIf { it.size == argument.elements.size } ?: return false
        for (i in argument.elements.indices) makeExplicitParameterTypeInferences(argument.elements[i], destTypes[i] ?: throw Unknown(), kind)
        return true
    }

    private fun inferTypeArgsSecondPhase(): Boolean {
        initializeDependencies()
        while (true) {
            when (doSecondPhase()) {
                InferenceResult.INFERENCE_FAILED -> return false
                InferenceResult.SUCCESS -> return true
                else -> {}
            }
        }
    }

    private fun doSecondPhase(): InferenceResult {
        // SPEC: * If no unfixed type parameters exist then type inference succeeds.
        if (allFixed()) return InferenceResult.SUCCESS
        // SPEC: * Otherwise, ... an output type inference is made from all such Ei to Ti.
        makeOutputTypeInferences()
        // SPEC: * ... each Xi that is unfixed, has bounds and does not depend on any Xj is fixed.
        var res = fixNondependentParameters()
        if (res != InferenceResult.NO_PROGRESS) return res
        // SPEC: * Otherwise, each Xi that is unfixed, has bounds and that some Xj depends on is fixed.
        res = fixDependentParameters()
        if (res != InferenceResult.NO_PROGRESS) return res
        // SPEC: * Otherwise, we are unable to make progress and there are unfixed parameters. Type inference fails.
        return InferenceResult.INFERENCE_FAILED
    }

    private fun makeOutputTypeInferences() {
        for (arg in 0 until numberArgumentsToProcess) makeOutputTypeInferences(arguments[arg], formal(arg))
    }

    private fun makeOutputTypeInferences(argument: InferenceArgument, formalType: SemanticType) {
        if (argument is InferenceArgument.Tuple && argument.type == null) {
            val destTypes = types.tupleElementTypes(formalType)?.takeIf { it.size == argument.elements.size } ?: return
            for (i in argument.elements.indices) makeOutputTypeInferences(argument.elements[i], destTypes[i] ?: throw Unknown())
        } else if (hasUnfixedParamInOutputType(argument, formalType) && !hasUnfixedParamInInputType(argument, formalType)) {
            outputTypeInference(argument, formalType)
        }
    }

    private fun fixNondependentParameters(): InferenceResult = fixParameters { !dependsOnAny(it) }

    private fun fixDependentParameters(): InferenceResult = fixParameters { anyDependsOn(it) }

    private fun fixParameters(predicate: (Int) -> Boolean): InferenceResult {
        // fixing a parameter may make others no longer dependent: first decide which to fix, then fix them all
        val needsFixing = BooleanArray(arity)
        var result = InferenceResult.NO_PROGRESS
        for (param in 0 until arity) {
            if (isUnfixed(param) && hasBound(param) && predicate(param)) {
                needsFixing[param] = true
                result = InferenceResult.MADE_PROGRESS
            }
        }
        for (param in 0 until arity) if (needsFixing[param] && !fix(param)) result = InferenceResult.INFERENCE_FAILED
        return result
    }

    // ---- input and output types

    /** `DoesInputTypeContain`: [argument] a lambda or a method group to a delegate one of whose parameter types contains the type parameter. */
    private fun doesInputTypeContain(argument: InferenceArgument, formalParameterType: SemanticType, ordinal: Int): Boolean {
        if (argument !is InferenceArgument.Lambda && argument !is InferenceArgument.MethodGroup) return false
        val delegate = delegateType(formalParameterType) ?: return false
        val parameters = types.delegateInvoke(delegate)?.first ?: return false
        return parameters.any { containsTypeParameter(it ?: throw Unknown(), ordinal) }
    }

    private fun hasUnfixedParamInInputType(source: InferenceArgument, dest: SemanticType): Boolean =
        (0 until arity).any { isUnfixed(it) && doesInputTypeContain(source, dest, it) }

    /** `DoesOutputTypeContain`: [argument] a lambda or a method group to a delegate whose return type contains the type parameter. */
    private fun doesOutputTypeContain(argument: InferenceArgument, formalParameterType: SemanticType, ordinal: Int): Boolean {
        if (argument !is InferenceArgument.Lambda && argument !is InferenceArgument.MethodGroup) return false
        val delegate = delegateType(formalParameterType) ?: return false
        val invoke = types.delegateInvoke(delegate) ?: return false
        return containsTypeParameter(invoke.second ?: throw Unknown(), ordinal)
    }

    private fun hasUnfixedParamInOutputType(argument: InferenceArgument, formalParameterType: SemanticType): Boolean =
        (0 until arity).any { isUnfixed(it) && doesOutputTypeContain(argument, formalParameterType, it) }

    // ---- dependence

    private fun dependsDirectlyOn(iParam: Int, jParam: Int): Boolean {
        // SPEC: An unfixed type parameter Xi depends directly on an unfixed type parameter Xj if for some argument Ek with type Tk,
        // SPEC: Xj occurs in an input type of Ek and Xi occurs in an output type of Ek with type Tk.
        for (iArg in 0 until numberArgumentsToProcess) {
            val formalParameterType = formal(iArg)
            val argument = arguments[iArg]
            if (doesInputTypeContain(argument, formalParameterType, jParam) && doesOutputTypeContain(argument, formalParameterType, iParam)) return true
        }
        return false
    }

    private fun initializeDependencies() {
        val table = Array(arity) { Array(arity) { Dependency.UNKNOWN } }
        dependencies = table
        for (i in 0 until arity) for (j in 0 until arity) if (dependsDirectlyOn(i, j)) table[i][j] = Dependency.DIRECT
        deduceAllDependencies()
    }

    private fun dependsOn(iParam: Int, jParam: Int): Boolean {
        // SPEC: Xj depends on Xi if Xj depends directly on Xi, or if Xi depends directly on Xk and Xk depends on Xj.
        if (dependenciesDirty) {
            setIndirectsToUnknown()
            deduceAllDependencies()
        }
        return dependencies!![iParam][jParam].depends
    }

    private fun dependsTransitivelyOn(iParam: Int, jParam: Int): Boolean {
        val table = dependencies!!
        return (0 until arity).any { k -> table[iParam][k].depends && table[k][jParam].depends }
    }

    private fun deduceAllDependencies() {
        while (deduceDependencies()) { }
        setUnknownsToNotDependent()
        dependenciesDirty = false
    }

    private fun deduceDependencies(): Boolean {
        val table = dependencies!!
        var madeProgress = false
        for (i in 0 until arity) for (j in 0 until arity) {
            if (table[i][j] == Dependency.UNKNOWN && dependsTransitivelyOn(i, j)) {
                table[i][j] = Dependency.INDIRECT
                madeProgress = true
            }
        }
        return madeProgress
    }

    private fun setUnknownsToNotDependent() {
        val table = dependencies!!
        for (i in 0 until arity) for (j in 0 until arity) if (table[i][j] == Dependency.UNKNOWN) table[i][j] = Dependency.NOT_DEPENDENT
    }

    private fun setIndirectsToUnknown() {
        val table = dependencies!!
        for (i in 0 until arity) for (j in 0 until arity) if (table[i][j] == Dependency.INDIRECT) table[i][j] = Dependency.UNKNOWN
    }

    /** A fixed parameter never depends on anything, nor is depended upon by anything. */
    private fun updateDependenciesAfterFix(iParam: Int) {
        val table = dependencies ?: return
        for (j in 0 until arity) {
            table[iParam][j] = Dependency.NOT_DEPENDENT
            table[j][iParam] = Dependency.NOT_DEPENDENT
        }
        dependenciesDirty = true
    }

    private fun dependsOnAny(iParam: Int): Boolean = (0 until arity).any { dependsOn(iParam, it) }

    private fun anyDependsOn(iParam: Int): Boolean = (0 until arity).any { dependsOn(it, iParam) }

    // ---- output type inferences

    private fun outputTypeInference(expression: InferenceArgument, target: SemanticType) {
        // SPEC: * If E is an anonymous function with inferred return type U and T is a delegate type or expression tree with return
        // SPEC:   type Tb then a lower bound inference is made from U to Tb.
        if (inferredReturnTypeInference(expression, target)) return
        // SPEC: * Otherwise, if E is a method group and T is a delegate type or expression tree type with parameter types T1...Tk and
        // SPEC:   return type Tb and overload resolution of E with the types T1...Tk yields a single method with return type U then a
        // SPEC:   lower-bound inference is made from U to Tb.
        if (methodGroupReturnTypeInference(expression, target)) return
        // SPEC: * Otherwise, if E is an expression with type U then a lower-bound inference is made from U to T. (A lambda or a method
        // SPEC:   group here has a function type only, which is not modeled.)
    }

    private fun inferredReturnTypeInference(source: InferenceArgument, target: SemanticType): Boolean {
        val delegate = delegateType(target) ?: return false
        val returnType = types.delegateInvoke(delegate)?.second ?: throw Unknown()
        if (types.isVoid(returnType)) return false
        val inferredReturnType = inferReturnType(source, delegate) ?: return false
        lowerBoundInference(inferredReturnType, returnType)
        return true
    }

    private fun methodGroupReturnTypeInference(source: InferenceArgument, target: SemanticType): Boolean {
        if (source !is InferenceArgument.MethodGroup) return false
        val delegate = delegateType(target) ?: return false
        val sourceReturnType = types.delegateInvoke(delegate)?.second ?: throw Unknown()
        if (types.isVoid(sourceReturnType)) return false
        // in the second phase all the input types are fixed
        val fixedParameters = types.delegateInvoke(getFixedDelegate(delegate))?.first ?: return false
        val returnType = source.returnType(fixedParameters.map { it ?: throw Unknown() })
        if (returnType == null || types.isVoid(returnType)) return false
        lowerBoundInference(returnType, sourceReturnType)
        return true
    }

    // ---- explicit parameter type inferences

    private fun explicitParameterTypeInference(source: InferenceArgument.Lambda, target: SemanticType) {
        // SPEC: If E is an explicitly typed anonymous function with parameter types U1...Uk and T is a delegate type or expression tree
        // SPEC: type with parameter types V1...Vk then for each Ui an exact inference is made from Ui to the corresponding Vi.
        val written = source.explicitParameterTypes ?: return
        val delegateParameters = delegateType(target)?.let(types::delegateInvoke)?.first ?: return
        val size = minOf(delegateParameters.size, source.parameterCount)
        for (i in 0 until size) exactInference(written[i], delegateParameters[i] ?: throw Unknown())
    }

    private fun explicitReturnTypeInference(source: InferenceArgument.Lambda, target: SemanticType) {
        // SPEC: If E is an anonymous function with explicit return type Ur and T is a delegate type or expression tree type with return
        // SPEC: type Vr then an exact inference is made from Ur to Vr.
        val written = source.explicitReturnType ?: return
        val invoke = delegateType(target)?.let(types::delegateInvoke) ?: return
        exactInference(written, invoke.second ?: throw Unknown())
    }

    // ---- exact inferences

    private fun exactInference(source: SemanticType, target: SemanticType) {
        // SPEC: * Otherwise, if U is the type U1? and V is the type V1? then an exact inference is made from U to V.
        if (exactNullableInference(source, target)) return
        // SPEC: * If V is one of the unfixed Xi then U is added to the set of exact bounds for Xi.
        if (exactTypeParameterInference(source, target)) return
        // SPEC: * Otherwise, if U is an array type UE[...] and V is an array type VE[...] of the same rank then an exact inference from
        // SPEC:   UE to VE is made.
        if (exactArrayInference(source, target)) return
        // SPEC: * V is a Span<V1> and U is an array type U1[] or a Span<U1>; V is a ReadOnlySpan<V1> and U is U1[], Span<U1> or ReadOnlySpan<U1>
        if (exactSpanInference(source, target)) return
        // SPEC: * Otherwise, if V is a constructed type C<V1...Vk> and U is a constructed type C<U1...Uk> then an exact inference is made
        // SPEC:   from each Ui to the corresponding Vi.
        if (exactConstructedInference(source, target)) return
        // SPEC: * Otherwise no inferences are made.
    }

    private fun exactTypeParameterInference(source: SemanticType, target: SemanticType): Boolean {
        if (!isUnfixedTypeParameter(target)) return false
        addBound(source, exactBounds, target as SemanticType.Parameter)
        return true
    }

    private fun exactArrayInference(source: SemanticType, target: SemanticType): Boolean {
        if (source !is SemanticType.ArrayOf || target !is SemanticType.ArrayOf || source.rank != target.rank) return false
        exactInference(source.element ?: throw Unknown(), target.element ?: throw Unknown())
        return true
    }

    private fun exactSpanInference(source: SemanticType, target: SemanticType): Boolean {
        if (!spanInferenceApplies(source, target)) return false
        exactInference(spanOrSZArrayElementType(source), types.spanElementType(target) ?: throw Unknown())
        return true
    }

    /** V a `Span<V1>` and U an `U1[]` or a `Span<U1>`; V a `ReadOnlySpan<V1>` and U also a `ReadOnlySpan<U1>` (C# 14, first-class spans). */
    private fun spanInferenceApplies(source: SemanticType, target: SemanticType): Boolean {
        if (!firstClassSpan || types.spanElementType(target) == null) return false
        val sourceIsSpan = types.spanElementType(source) != null && !types.isReadOnlySpan(source)
        return if (types.isReadOnlySpan(target)) types.isSZArray(source) || types.spanElementType(source) != null
        else types.isSZArray(source) || sourceIsSpan
    }

    private fun spanOrSZArrayElementType(type: SemanticType): SemanticType =
        (if (type is SemanticType.ArrayOf) type.element else types.spanElementType(type)) ?: throw Unknown()

    private fun exactOrBoundsInference(kind: ExactOrBoundsKind, source: SemanticType, target: SemanticType) = when (kind) {
        ExactOrBoundsKind.EXACT -> exactInference(source, target)
        ExactOrBoundsKind.LOWER_BOUND -> lowerBoundInference(source, target)
        ExactOrBoundsKind.UPPER_BOUND -> upperBoundInference(source, target)
    }

    /** `ExactOrBoundsNullableInference`: `U1?` to `V1?` — the same kind of inference from U1 to V1 (nullable reference types left out). */
    private fun exactOrBoundsNullableInference(kind: ExactOrBoundsKind, source: SemanticType, target: SemanticType): Boolean {
        if (!types.isNullableType(source) || !types.isNullableType(target)) return false
        exactOrBoundsInference(kind, types.nullableUnderlyingType(source) ?: throw Unknown(), types.nullableUnderlyingType(target) ?: throw Unknown())
        return true
    }

    private fun exactNullableInference(source: SemanticType, target: SemanticType): Boolean = exactOrBoundsNullableInference(ExactOrBoundsKind.EXACT, source, target)

    private fun lowerBoundTupleInference(source: SemanticType, target: SemanticType): Boolean {
        val sourceTypes = types.tupleElementTypes(source) ?: return false
        val targetTypes = types.tupleElementTypes(target) ?: return false
        if (sourceTypes.size != targetTypes.size) return false
        for (i in sourceTypes.indices) lowerBoundInference(sourceTypes[i] ?: throw Unknown(), targetTypes[i] ?: throw Unknown())
        return true
    }

    private fun exactConstructedInference(source: SemanticType, target: SemanticType): Boolean {
        if (!isNamed(source) || !isNamed(target) || !sameDefinition(source, target)) return false
        exactTypeArgumentInference(source, target)
        return true
    }

    private fun exactTypeArgumentInference(source: SemanticType, target: SemanticType) {
        val sourceTypeArguments = allTypeArguments(source)
        val targetTypeArguments = allTypeArguments(target)
        if (sourceTypeArguments.size != targetTypeArguments.size) throw Unknown()
        for (arg in sourceTypeArguments.indices) exactInference(sourceTypeArguments[arg] ?: throw Unknown(), targetTypeArguments[arg] ?: throw Unknown())
    }

    // ---- lower-bound inferences

    private fun lowerBoundInference(source: SemanticType, target: SemanticType) {
        // SPEC: * Otherwise, if V is nullable type V1? and U is nullable type U1? then an exact inference is made from U1 to V1.
        // SPEC ERROR: the spec should say "lower" here (Roslyn: M<T>(T?, T) with (char?, int) infers int).
        if (lowerBoundNullableInference(source, target)) return
        // SPEC: * If V is one of the unfixed Xi then U is added to the set of lower bounds for Xi.
        if (lowerBoundTypeParameterInference(source, target)) return
        // SPEC: * Otherwise, if U is an array type Ue[...] and V is either an array type Ve[...] of the same rank, or if U is a
        // SPEC:   one-dimensional array type Ue[] and V is one of IEnumerable<Ve>, ICollection<Ve> or IList<Ve> then ...
        if (lowerBoundArrayInference(source, target)) return
        if (lowerBoundSpanInference(source, target)) return
        if (lowerBoundTupleInference(source, target)) return
        // SPEC: Otherwise... many cases for constructed generic types.
        if (lowerBoundConstructedInference(source, target)) return
        // SPEC: * Otherwise, no inferences are made.
    }

    private fun lowerBoundTypeParameterInference(source: SemanticType, target: SemanticType): Boolean {
        if (!isUnfixedTypeParameter(target)) return false
        addBound(source, lowerBounds, target as SemanticType.Parameter)
        return true
    }

    /** `GetMatchingElementType`: of an array target of the same rank, or of `IEnumerable<T>` and the other array interfaces for `T[]`. */
    private fun getMatchingElementType(source: SemanticType.ArrayOf, target: SemanticType): SemanticType? {
        if (target is SemanticType.ArrayOf) return if (target.rank == source.rank) target.element ?: throw Unknown() else null
        if (source.rank != 1) return null
        if (types.definitionName(target) !in POSSIBLE_ARRAY_GENERIC_INTERFACES) return null
        return types.typeArguments(target).singleOrNull() ?: throw Unknown()
    }

    private fun lowerBoundArrayInference(source: SemanticType, target: SemanticType): Boolean {
        if (source !is SemanticType.ArrayOf) return false
        val elementSource = source.element ?: throw Unknown()
        val elementTarget = getMatchingElementType(source, target) ?: return false
        // SPEC: * if Ue is known to be a reference type then a lower-bound inference from Ue to Ve is made; otherwise an exact one.
        if (types.isReferenceType(elementSource) ?: throw Unknown()) lowerBoundInference(elementSource, elementTarget) else exactInference(elementSource, elementTarget)
        return true
    }

    private fun lowerBoundSpanInference(source: SemanticType, target: SemanticType): Boolean {
        if (!spanInferenceApplies(source, target)) return false
        val sourceElementType = spanOrSZArrayElementType(source)
        val targetElementType = types.spanElementType(target) ?: throw Unknown()
        // SPEC: * If U1 is not known to be a reference type, or V is a Span<V1>, then an exact inference is made
        val reference = types.isReferenceType(sourceElementType) ?: throw Unknown()
        if (!reference || !types.isReadOnlySpan(target)) exactInference(sourceElementType, targetElementType) else lowerBoundInference(sourceElementType, targetElementType)
        return true
    }

    private fun lowerBoundNullableInference(source: SemanticType, target: SemanticType): Boolean = exactOrBoundsNullableInference(ExactOrBoundsKind.LOWER_BOUND, source, target)

    private fun lowerBoundConstructedInference(source: SemanticType, target: SemanticType): Boolean {
        if (!isNamed(target) || allTypeArguments(target).isEmpty()) return false
        // SPEC: * Otherwise, if V is a constructed class or struct type C<V1...Vk> and U is C<U1...Uk> then an exact inference is made
        // SPEC:   from each Ui to the corresponding Vi; for an interface or delegate type C<...> an exact, lower or upper one by variance.
        if (isNamed(source) && sameDefinition(source, target)) {
            if (types.isInterfaceType(source) || types.isDelegateType(source)) lowerBoundTypeArgumentInference(source, target) else exactTypeArgumentInference(source, target)
            return true
        }
        // SPEC: * Otherwise, if V is a class type C<V1...Vk> and U is a class type which inherits directly or indirectly from C<U1...Uk>
        // SPEC:   (or a type parameter with such an effective base class) then an exact inference is made from each Ui to the corresponding Vi.
        if (lowerBoundClassInference(source, target)) return true
        // SPEC: * Otherwise, if V is an interface type C<V1...Vk> and U is a class, struct, interface or type parameter type and there is a
        // SPEC:   unique set U1...Uk such that U directly or indirectly implements C<U1...Uk> then an inference by variance is made.
        if (lowerBoundInterfaceInference(source, target)) return true
        return false
    }

    private fun lowerBoundClassInference(source: SemanticType, target: SemanticType): Boolean {
        if (types.typeKind(target) != RoslynTypes.TypeKind.CLASS) return false
        var sourceBase: SemanticType? = when (types.typeKind(source)) {
            RoslynTypes.TypeKind.CLASS, RoslynTypes.TypeKind.TYPE_PARAMETER -> types.baseType(source)
            else -> null
        }
        var depth = 0
        while (sourceBase != null) {
            if (sameDefinition(sourceBase, target)) {
                exactTypeArgumentInference(sourceBase, target)
                return true
            }
            if (++depth > MAX_DEPTH) throw Unknown()
            sourceBase = types.baseType(sourceBase)
        }
        // what we cannot see the whole of, we cannot say it does not derive from
        if (types.typeKind(source) == RoslynTypes.TypeKind.CLASS && !types.isClosed(source)) throw Unknown()
        return false
    }

    private fun lowerBoundInterfaceInference(source: SemanticType, target: SemanticType): Boolean {
        if (!types.isInterfaceType(target)) return false
        val allInterfaces = when (types.typeKind(source)) {
            RoslynTypes.TypeKind.STRUCT, RoslynTypes.TypeKind.CLASS, RoslynTypes.TypeKind.INTERFACE -> types.allInterfaces(source)
            // the effective base class's interfaces and the effective interfaces of a type parameter
            RoslynTypes.TypeKind.TYPE_PARAMETER -> (types.baseType(source)?.let(types::allInterfaces) ?: throw Unknown()) + types.allInterfaces(source)
            else -> return false
        }
        val matchingInterface = getInterfaceInferenceBound(allInterfaces, target)
        if (matchingInterface == null) {
            if (types.typeKind(source) != RoslynTypes.TypeKind.TYPE_PARAMETER && !types.isClosed(source)) throw Unknown()
            return false
        }
        lowerBoundTypeArgumentInference(matchingInterface, target)
        return true
    }

    private fun lowerBoundTypeArgumentInference(source: SemanticType, target: SemanticType) {
        // SPEC: * if Ui is known to be of reference type and the i-th type parameter was declared as covariant then a lower bound
        // SPEC:   inference is made; as contravariant, an upper bound inference; otherwise, an exact inference.
        val sourceTypeArguments = allTypeArguments(source)
        val targetTypeArguments = allTypeArguments(target)
        if (sourceTypeArguments.size != targetTypeArguments.size) throw Unknown()
        for (arg in sourceTypeArguments.indices) {
            val sourceTypeArgument = sourceTypeArguments[arg] ?: throw Unknown()
            val targetTypeArgument = targetTypeArguments[arg] ?: throw Unknown()
            val variance = allVariance(source, arg)
            val reference = if (variance == RoslynTypes.VarianceKind.NONE) false else types.isReferenceType(sourceTypeArgument) ?: throw Unknown()
            when {
                reference && variance == RoslynTypes.VarianceKind.OUT -> lowerBoundInference(sourceTypeArgument, targetTypeArgument)
                reference && variance == RoslynTypes.VarianceKind.IN -> upperBoundInference(sourceTypeArgument, targetTypeArgument)
                else -> exactInference(sourceTypeArgument, targetTypeArgument)
            }
        }
    }

    // ---- upper-bound inferences

    private fun upperBoundInference(source: SemanticType, target: SemanticType) {
        // SPEC: * Otherwise, if V is nullable type V1? and U is nullable type U1? then an exact inference is made from U1 to V1.
        if (upperBoundNullableInference(source, target)) return
        // SPEC: * If V is one of the unfixed Xi then U is added to the set of upper bounds for Xi.
        if (upperBoundTypeParameterInference(source, target)) return
        // SPEC: * Otherwise, if V is an array type Ve[...] and U is an array type Ue[...] of the same rank, or if V is a one-dimensional
        // SPEC:   array type Ve[] and U is one of IEnumerable<Ue>, ICollection<Ue> or IList<Ue> then ...
        if (upperBoundArrayInference(source, target)) return
        // SPEC: * Otherwise... cases for constructed types
        if (upperBoundConstructedInference(source, target)) return
        // SPEC: * Otherwise, no inferences are made.
    }

    private fun upperBoundTypeParameterInference(source: SemanticType, target: SemanticType): Boolean {
        if (!isUnfixedTypeParameter(target)) return false
        addBound(source, upperBounds, target as SemanticType.Parameter)
        return true
    }

    private fun upperBoundArrayInference(source: SemanticType, target: SemanticType): Boolean {
        if (target !is SemanticType.ArrayOf) return false
        val elementTarget = target.element ?: throw Unknown()
        val elementSource = getMatchingElementType(target, source) ?: return false
        if (types.isReferenceType(elementSource) ?: throw Unknown()) upperBoundInference(elementSource, elementTarget) else exactInference(elementSource, elementTarget)
        return true
    }

    private fun upperBoundNullableInference(source: SemanticType, target: SemanticType): Boolean = exactOrBoundsNullableInference(ExactOrBoundsKind.UPPER_BOUND, source, target)

    private fun upperBoundConstructedInference(source: SemanticType, target: SemanticType): Boolean {
        if (!isNamed(source) || allTypeArguments(source).isEmpty()) return false
        // SPEC: * Otherwise, if V is a constructed type C<V1...Vk> and U is C<U1...Uk> then an exact inference, lower bound inference or
        // SPEC:   upper bound inference is made from each Ui to the corresponding Vi.
        if (isNamed(target) && sameDefinition(source, target)) {
            if (types.isInterfaceType(target) || types.isDelegateType(target)) upperBoundTypeArgumentInference(source, target) else exactTypeArgumentInference(source, target)
            return true
        }
        // SPEC: * Otherwise, if U is a class type C<U1...Uk> and V is a class type which inherits directly or indirectly from C<V1...Vk>
        // SPEC:   then an exact inference is made from each Ui to the corresponding Vi.
        if (upperBoundClassInference(source, target)) return true
        // SPEC: * Otherwise, if U is an interface type C<U1...Uk> and V is a class, struct or interface type and there is a unique set
        // SPEC:   V1...Vk such that V directly or indirectly implements C<V1...Vk> then ...
        if (upperBoundInterfaceInference(source, target)) return true
        return false
    }

    private fun upperBoundClassInference(source: SemanticType, target: SemanticType): Boolean {
        if (types.typeKind(source) != RoslynTypes.TypeKind.CLASS || types.typeKind(target) != RoslynTypes.TypeKind.CLASS) return false
        var targetBase = types.baseType(target)
        var depth = 0
        while (targetBase != null) {
            if (sameDefinition(targetBase, source)) {
                exactTypeArgumentInference(source, targetBase)
                return true
            }
            if (++depth > MAX_DEPTH) throw Unknown()
            targetBase = types.baseType(targetBase)
        }
        if (!types.isClosed(target)) throw Unknown()
        return false
    }

    private fun upperBoundInterfaceInference(source: SemanticType, target: SemanticType): Boolean {
        if (!types.isInterfaceType(source)) return false
        when (types.typeKind(target)) {
            RoslynTypes.TypeKind.STRUCT, RoslynTypes.TypeKind.CLASS, RoslynTypes.TypeKind.INTERFACE -> {}
            else -> return false
        }
        val bestInterface = getInterfaceInferenceBound(types.allInterfaces(target), source)
        if (bestInterface == null) {
            if (!types.isClosed(target)) throw Unknown()
            return false
        }
        upperBoundTypeArgumentInference(source, bestInterface)
        return true
    }

    private fun upperBoundTypeArgumentInference(source: SemanticType, target: SemanticType) {
        // SPEC: * if Ui is known to be of reference type and the i-th type parameter was declared as covariant then an upper-bound
        // SPEC:   inference is made; as contravariant, a lower-bound inference; otherwise, an exact inference.
        val sourceTypeArguments = allTypeArguments(source)
        val targetTypeArguments = allTypeArguments(target)
        if (sourceTypeArguments.size != targetTypeArguments.size) throw Unknown()
        for (arg in sourceTypeArguments.indices) {
            val sourceTypeArgument = sourceTypeArguments[arg] ?: throw Unknown()
            val targetTypeArgument = targetTypeArguments[arg] ?: throw Unknown()
            val variance = allVariance(source, arg)
            val reference = if (variance == RoslynTypes.VarianceKind.NONE) false else types.isReferenceType(sourceTypeArgument) ?: throw Unknown()
            when {
                reference && variance == RoslynTypes.VarianceKind.OUT -> upperBoundInference(sourceTypeArgument, targetTypeArgument)
                reference && variance == RoslynTypes.VarianceKind.IN -> lowerBoundInference(sourceTypeArgument, targetTypeArgument)
                else -> exactInference(sourceTypeArgument, targetTypeArgument)
            }
        }
    }

    // ---- fixing

    private fun fix(iParam: Int): Boolean {
        val best = fix(exactBounds[iParam], lowerBounds[iParam], upperBounds[iParam]) ?: return false
        fixedResults[iParam] = best
        updateDependenciesAfterFix(iParam)
        return true
    }

    /** `Fix` (§12.6.3.12): the candidates of the bounds each bound allows, the unique one all others convert to; null when there is none. */
    private fun fix(exact: List<SemanticType>?, lower: List<SemanticType>?, upper: List<SemanticType>?): SemanticType? {
        // SPEC: * The set of candidate types starts out as the set of all types in the bounds.
        // SPEC: * For each exact bound U of Xi all types which are not identical to U are removed from the candidate set.
        val candidates = ArrayList<SemanticType>()
        fun addCandidate(type: SemanticType) {
            if (candidates.none { types.same(it, type) == true }) candidates += type
        }
        if (exact == null) {
            lower?.forEach(::addCandidate)
            upper?.forEach(::addCandidate)
        } else {
            exact.forEach(::addCandidate)
            // two exact bounds: fixing is impossible — when they surely differ
            if (candidates.size >= 2) {
                if (candidates.any { a -> candidates.any { b -> a !== b && types.same(a, b) == null } }) throw Unknown()
                return null
            }
        }
        if (candidates.isEmpty()) return null
        val initialCandidates = ArrayList(candidates)
        // SPEC:   For each lower bound U of Xi all types to which there is not an implicit conversion from U are removed.
        lower?.let { mergeOrRemoveCandidates(candidates, it, initialCandidates, RoslynTypes.VarianceKind.OUT) }
        // SPEC:   For each upper bound U of Xi all types from which there is not an implicit conversion to U are removed.
        upper?.let { mergeOrRemoveCandidates(candidates, it, initialCandidates, RoslynTypes.VarianceKind.IN) }
        // SPEC: * If among the remaining candidate types there is a unique type V to which there is an implicit conversion from all the
        // SPEC:   other candidate types, then the parameter is fixed to V.
        var best: SemanticType? = null
        for (candidate in ArrayList(candidates)) {
            val fromAll = candidates.all { candidate2 ->
                candidate2 === candidate || types.same(candidate, candidate2) == true || implicitConversionExists(candidate2, candidate)
            }
            if (!fromAll) continue
            if (best == null) best = candidate else return null // best candidate is not unique
        }
        return best
    }

    private fun mergeOrRemoveCandidates(candidates: MutableList<SemanticType>, bounds: List<SemanticType>, initialCandidates: List<SemanticType>, variance: RoslynTypes.VarianceKind) {
        for (bound in bounds) {
            for (candidate in initialCandidates) {
                if (types.same(bound, candidate) == true) continue
                val exists = if (variance == RoslynTypes.VarianceKind.OUT) implicitConversionExists(bound, candidate) else implicitConversionExists(candidate, bound)
                if (!exists) candidates.removeAll { it === candidate }
            }
        }
    }

    private fun implicitConversionExists(source: SemanticType, destination: SemanticType): Boolean = types.implicitConversionExists(source, destination) ?: throw Unknown()

    // ---- inferred return type

    /** `InferReturnType` (§12.6.3.13): of a lambda to a delegate whose input types are fixed; null when none is inferred. */
    private fun inferReturnType(source: InferenceArgument, target: SemanticType): SemanticType? {
        if (source !is InferenceArgument.Lambda) return null
        if (source.hasSignature) {
            // another number of parameters: not applicable anyway, no inference
            val originalDelegateParameters = types.delegateInvoke(target)?.first ?: return null
            if (originalDelegateParameters.size != source.parameterCount) return null
        }
        val fixedDelegateParameters = types.delegateInvoke(getFixedDelegate(target))?.first ?: return null
        // an explicitly typed lambda whose parameter types differ from the fixed delegate's is not applicable: no inference
        source.explicitParameterTypes?.let { written ->
            for (p in 0 until source.parameterCount) {
                if (!(types.same(written[p], fixedDelegateParameters.getOrNull(p) ?: throw Unknown()) ?: throw Unknown())) return null
            }
        }
        return source.inferReturnType(fixedDelegateParameters.map { it ?: throw Unknown() })
    }

    /** `GetInterfaceInferenceBound`: the one interface of [interfaces] with the definition of [target]; null for none or several. */
    private fun getInterfaceInferenceBound(interfaces: List<SemanticType>, target: SemanticType): SemanticType? {
        var matchingInterface: SemanticType? = null
        for (currentInterface in interfaces) {
            if (!sameDefinition(currentInterface, target)) continue
            if (matchingInterface == null) matchingInterface = currentInterface
            else if (!(types.same(matchingInterface, currentInterface) ?: throw Unknown())) return null // not unique
        }
        return matchingInterface
    }

    // ---- helpers

    /** `IsReallyAType`: not an error type, not `void`. */
    private fun isReallyAType(type: SemanticType): Boolean = !types.isVoid(type)

    private fun isNamed(type: SemanticType): Boolean = type is SemanticType.Library || type is SemanticType.Source

    /** `OriginalDefinition` equality; a built project is its sources. */
    private fun sameDefinition(a: SemanticType, b: SemanticType): Boolean {
        val x = types.definitionName(a) ?: return false
        val y = types.definitionName(b) ?: return false
        return x == y
    }

    /** `GetAllTypeArguments`: the arguments of the types around a nested type first, then its own. */
    private fun allTypeArguments(type: SemanticType): List<SemanticType?> =
        (types.outerType(type)?.let(::allTypeArguments) ?: emptyList()) + types.typeArguments(type)

    /** The variance of the [index]-th of [allTypeArguments] of [type]: the outer types' parameters are invariant. */
    private fun allVariance(type: SemanticType, index: Int): RoslynTypes.VarianceKind {
        val outer = types.outerType(type)?.let(::allTypeArguments)?.size ?: 0
        if (index < outer) return RoslynTypes.VarianceKind.NONE
        return types.variance(type, index - outer) ?: throw Unknown()
    }

    private companion object {
        const val MAX_DEPTH = 32
        val POSSIBLE_ARRAY_GENERIC_INTERFACES = setOf(
            "System.Collections.Generic.IEnumerable`1", "System.Collections.Generic.ICollection`1", "System.Collections.Generic.IList`1",
            "System.Collections.Generic.IReadOnlyCollection`1", "System.Collections.Generic.IReadOnlyList`1",
        )
    }
}
