// Ported from Roslyn: src/Compilers/CSharp/Portable/Binder/Semantics/Conversions/Conversions.cs (GetMethodGroupDelegateConversion,
// ResolveDelegateOrFunctionPointerMethodGroup, lines 44-130 and 264-284), Binder/Binder_Conversions.cs (MethodIsCompatibleWithDelegateOrFunctionPointer,
// 3516-3620), Binder/Semantics/OverloadResolution/OverloadResolution.cs (RemoveDelegateConversionsWithWrongReturnType, 849-900, and the
// Options.IsMethodGroupConversion rules: the normal form only, every parameter with an argument), MethodTypeInference.cs (MethodGroupReturnType,
// 1504-1540), roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c. Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License
// (NOTICE.md). Kotlin names are Roslyn's with a lower-case first letter; what is left out — CSHARP_PSI_MIGRATION.md, D6c2.
package io.github.dotnetsupport.lang.semantic.roslyn

import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynBetterFunctionMember.Argument
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynBetterFunctionMember.BetterResult
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynBetterFunctionMember.Member
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodTypeInferrer.Unknown

/**
 * C# §10.8, the method group conversion, as Roslyn decides it: overload resolution of the group with the delegate's parameter types as
 * the arguments (the normal form only, every parameter with an argument, the type arguments of a generic method inferred from those
 * types), the candidates returning the wrong type dropped, the best member by [RoslynBetterFunctionMember], then the stricter delegate
 * compatibility of that method (identity or implicit *reference* conversions of the parameters: `void M(long)` is applicable for
 * `Action<int>` but not compatible). A fact that is missing throws [Unknown] with the reason. Not ported: function pointers and calling
 * conventions, `dynamic`, the SPEC VIOLATION for an extension method of a value type receiver.
 */
internal class RoslynMethodGroupConversion(private val types: RoslynTypes, private val better: RoslynBetterFunctionMember) {

    sealed class Inferred {
        class Known(val types: List<SemanticType>) : Inferred()
        data object Failed : Inferred()
        class Unknown(val reason: String) : Inferred()
    }

    /**
     * A method of the group: [parameterTypes] as declared (the `this` parameter of a reduced extension method dropped; null not known),
     * [refKinds] how each is passed (null by value), [returnType] as declared; [infer] its type arguments from the delegate's parameter
     * types (the receiver first for a reduced extension method), the constraints checked; [substitute] a declared type with them;
     * [receiverFits] `ConvertExtensionMethodThisArg` for a reduced extension method with the type arguments (true for any other method;
     * null not known).
     */
    class Candidate(
        val arity: Int, val parameterTypes: List<SemanticType?>, val refKinds: List<String?>, val returnType: SemanticType?,
        val infer: (List<SemanticType>) -> Inferred, val substitute: (SemanticType?, List<SemanticType>) -> SemanticType?,
        val receiverFits: (List<SemanticType>) -> Boolean?,
    )

    sealed class Resolution {
        /** `OverloadResolutionResult.Succeeded`: [candidate] with [typeArguments], its parameters and return type substituted. */
        class Best(val candidate: Candidate, val typeArguments: List<SemanticType>, val parameterTypes: List<SemanticType>, val returnType: SemanticType) : Resolution()
        data object None : Resolution()
        data object Ambiguous : Resolution()
    }

    private class Applicable(val candidate: Candidate, val typeArguments: List<SemanticType>, val targets: List<SemanticType>, val returns: SemanticType)

    /**
     * `ResolveMethodGroup` with `IsMethodGroupConversion` for a delegate whose parameters are of [delegateParameterTypes] passed
     * [delegateRefKinds] (null by value); [returnType] when given drops the candidates whose return type does not match
     * (`RemoveDelegateConversionsWithWrongReturnType`; `MethodGroupReturnType` of the inference asks without it).
     */
    fun resolve(candidates: List<Candidate>, delegateParameterTypes: List<SemanticType>, delegateRefKinds: List<String?>, returnType: SemanticType?): Resolution {
        val applicable = ArrayList<Applicable>()
        for (c in candidates) {
            // the normal form only, every parameter with an argument: as many parameters as the delegate has
            if (c.parameterTypes.size != delegateParameterTypes.size) continue
            // `AreRefsCompatibleForMethodConversion`
            if (!c.refKinds.indices.all { refsCompatible(c.refKinds[it], delegateRefKinds[it]) }) continue
            var typeArguments = emptyList<SemanticType>()
            if (c.arity > 0) when (val inferred = c.infer(delegateParameterTypes)) {
                is Inferred.Known -> typeArguments = inferred.types
                Inferred.Failed -> continue
                is Inferred.Unknown -> throw Unknown("the type arguments of the method group's method are not known: ${inferred.reason}")
            }
            when (c.receiverFits(typeArguments)) {
                false -> continue
                null -> throw Unknown("whether the receiver converts to the `this` parameter is not known")
                true -> {}
            }
            val targets = c.parameterTypes.map { c.substitute(it ?: throw Unknown("a parameter type of the method is not known"), typeArguments) ?: throw Unknown("a parameter type of the method is not known") }
            // applicable: each delegate parameter converts implicitly to the method's (by identity when passed by reference)
            var fits = true
            for (i in targets.indices) {
                val ok = if (delegateRefKinds[i] != null) types.same(delegateParameterTypes[i], targets[i]) ?: throw Unknown("${delegateParameterTypes[i]} and ${targets[i]} cannot be compared")
                    else types.implicitConversionExists(delegateParameterTypes[i], targets[i]) ?: throw Unknown("whether ${delegateParameterTypes[i]} converts to ${targets[i]} is not known")
                if (!ok) { fits = false; break }
            }
            if (!fits) continue
            val returns = c.substitute(c.returnType ?: throw Unknown("the return type of the method is not known"), typeArguments) ?: throw Unknown("the return type of the method is not known")
            if (returnType != null && !returnsMatch(returns, returnType)) continue
            applicable += Applicable(c, typeArguments, targets, returns)
        }
        if (applicable.isEmpty()) return Resolution.None
        val best = if (applicable.size == 1) applicable.single() else {
            // `RemoveWorseMembers`: the better function member with the delegate's parameter types as the arguments
            val arguments = delegateParameterTypes.map { Argument.Receiver(it) }
            val members = applicable.map { a ->
                Member(
                    arity = a.candidate.arity, expanded = false, targets = a.targets, refKinds = a.candidate.refKinds,
                    definitionTargets = a.candidate.parameterTypes.map { it ?: throw Unknown("a parameter type of the method is not known") },
                    declaredParameterCount = a.targets.size, parametersUsed = a.targets.size, paramsType = null, toLastParameter = List(a.targets.size) { false },
                )
            }
            val beaten = HashSet<Int>()
            for (i in members.indices) for (j in members.indices) {
                if (i == j) continue
                when (better.betterFunctionMember(members[i], members[j], arguments)) {
                    BetterResult.LEFT -> beaten += j
                    BetterResult.RIGHT -> beaten += i
                    BetterResult.NEITHER -> {}
                }
            }
            val unbeaten = members.indices.filter { it !in beaten }
            if (unbeaten.size != 1) return Resolution.Ambiguous
            applicable[unbeaten.single()]
        }
        return Resolution.Best(best.candidate, best.typeArguments, best.targets, best.returns)
    }

    /**
     * `GetMethodGroupDelegateConversion`: whether the group converts to the delegate type [delegate] (a bona fide delegate, not
     * `Expression<D>`) — the resolution for its `Invoke`, then `MethodIsCompatibleWithDelegateOrFunctionPointer` of the method found.
     */
    fun conversion(candidates: List<Candidate>, delegate: SemanticType, delegateRefKinds: List<String?>): Boolean {
        val invoke = types.delegateInvoke(delegate) ?: return false
        val parameterTypes = invoke.first.map { it ?: throw Unknown("a parameter type of $delegate is not known") }
        val returnType = invoke.second ?: throw Unknown("the return type of $delegate is not known")
        val best = when (val resolution = resolve(candidates, parameterTypes, delegateRefKinds, returnType)) {
            Resolution.None, Resolution.Ambiguous -> return false
            is Resolution.Best -> resolution
        }
        // the delegate compatibility is stricter than applicability: a value parameter needs an identity or implicit reference conversion
        for (i in parameterTypes.indices) {
            val ok = if (delegateRefKinds[i] != null) types.same(parameterTypes[i], best.parameterTypes[i]) ?: throw Unknown("${parameterTypes[i]} and ${best.parameterTypes[i]} cannot be compared")
                else types.hasIdentityOrImplicitReferenceConversion(parameterTypes[i], best.parameterTypes[i]) ?: throw Unknown("whether ${parameterTypes[i]} converts to ${best.parameterTypes[i]} by reference is not known")
            if (!ok) return false
        }
        return returnsMatch(best.returnType, returnType)
    }

    /** `RemoveDelegateConversionsWithWrongReturnType`: `void` for `void`, else the identity or an implicit reference conversion. */
    private fun returnsMatch(methodReturns: SemanticType, delegateReturns: SemanticType): Boolean {
        if (types.isVoid(delegateReturns)) return types.isVoid(methodReturns)
        if (types.isVoid(methodReturns)) return false
        return types.hasIdentityOrImplicitReferenceConversion(methodReturns, delegateReturns) ?: throw Unknown("whether $methodReturns converts to $delegateReturns by reference is not known")
    }

    /** `AreRefsCompatibleForMethodConversion`: the same kind; `in` and `ref readonly` are one kind here. */
    private fun refsCompatible(method: String?, delegate: String?): Boolean = (method ?: "") == (delegate ?: "")
}
