// Ported from Roslyn: src/Compilers/CSharp/Portable/Binder/Semantics/Conversions/ConversionsBase.cs (ClassifyImplicitConversionFromExpression,
// HasImplicitFunctionTypeConversion, IsValidFunctionTypeConversionTarget) and Binder/Binder_Expressions.cs (GetUniqueSignatureFromMethodGroup),
// roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c. Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
// Kotlin names are Roslyn's with a lower-case first letter; what is ported so far and what the caller still decides — CSHARP_PSI_MIGRATION.md,
// D6c2 and its ledger of the patches of the old rules.
package io.github.dotnetsupport.lang.semantic.roslyn

import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * C# §10.2, the implicit conversions from an expression, as Roslyn classifies them — in the order of `ClassifyImplicitConversionFromExpression`.
 * The expression is given as Roslyn's binder sees it ([Source], the kind of its `BoundExpression`); the types come from [RoslynConversions].
 *
 * Three answers, unlike [RoslynConversions] (which only proves): a conversion that exists, one that surely does not (a candidate does not
 * apply), and not known with its reason — for a kind of expression not ported yet ([Source.NotPorted]) the caller's old rules decide.
 * Ported: a method group to a type that is no delegate, by its function type (C# 10, `MapGet(pattern, GetItems)` to `Delegate`). A method
 * group to a delegate type (`GetMethodGroupDelegateConversion`) is the caller's still.
 */
internal class RoslynExpressionConversions(private val types: RoslynTypes) {

    /** An expression as the conversions see it. */
    sealed class Source {
        /** `BoundMethodGroup`: its candidate [methods]; [arity] the number of type arguments written; [reduced] an extension method group on a receiver. */
        class MethodGroup(val methods: List<Method>, val arity: Int, val reduced: Boolean) : Source()

        /** A kind of expression whose conversions are not ported yet. */
        data object NotPorted : Source()
    }

    /** A method of a group: its type parameters, its parameters (type, `ref` / `out` / `in` or ""), what it returns; null types are not known. */
    class Method(val arity: Int, val parameters: List<Pair<SemanticType?, String>>, val returns: SemanticType?)

    sealed class Result {
        /** A conversion exists, of the kind Roslyn names. */
        data class Exists(val kind: String) : Result()
        data object None : Result()
        data class Unknown(val reason: String) : Result()
    }

    fun classifyImplicitConversionFromExpression(source: Source, destination: SemanticType): Result = when (source) {
        is Source.MethodGroup -> classifyMethodGroup(source, destination)
        Source.NotPorted -> Result.Unknown("not ported")
    }

    /**
     * A method group: to a delegate type the method group conversion (`GetMethodGroupDelegateConversion`, the caller's still); to any other
     * type only by the function type of the group (`HasImplicitFunctionTypeConversion`) — there is no conversion from a method group's type,
     * it has none, and no user-defined one.
     */
    private fun classifyMethodGroup(group: Source.MethodGroup, destination: SemanticType): Result {
        if (destination is SemanticType.Parameter) return Result.Unknown("a method group to a type parameter")
        if (types.delegateInvoke(destination) != null) return Result.Unknown("a method group to a delegate: not ported")
        if (!isValidFunctionTypeConversionTarget(destination)) {
            return if (types.isClosed(destination)) Result.None else Result.Unknown("the type ${destination} is not fully known")
        }
        return when (val signature = getUniqueSignatureFromMethodGroup(group)) {
            is Signature.Unique -> Result.Exists("FunctionType")
            Signature.None -> Result.None
            is Signature.Unknown -> Result.Unknown(signature.reason)
        }
    }

    /**
     * `IsValidFunctionTypeConversionTarget`: `System.MulticastDelegate`, its base classes (`System.Delegate`, `object`) and its interfaces
     * (`ICloneable`, `ISerializable`). The non-generic expression types are targets of a lambda's function type, not of a method group's —
     * Roslyn reports a method group to `Expression` as an error; not modeled, so left to the caller here.
     */
    private fun isValidFunctionTypeConversionTarget(destination: SemanticType): Boolean = types.definitionName(destination) in FUNCTION_TYPE_TARGETS

    private sealed class Signature {
        data class Unique(val method: Method) : Signature()
        data object None : Signature()
        data class Unknown(val reason: String) : Signature()
    }

    /**
     * `GetUniqueSignatureFromMethodGroup` (C# 13): the candidates of the arity written (no type arguments inferred), and one signature among
     * them all — the same parameter types, `ref` kinds and return type; none when they differ or there is no candidate. The receiver's
     * filter (static or instance by the receiver) and the extension methods of a reduced group are not modeled: a reduced group is not known.
     */
    private fun getUniqueSignatureFromMethodGroup(group: Source.MethodGroup): Signature {
        if (group.reduced) return Signature.Unknown("the function type of an extension method group is not modeled")
        val candidates = group.methods.filter { it.arity == group.arity }
        val first = candidates.firstOrNull() ?: return Signature.None
        if (first.parameters.any { it.first == null } || first.returns == null) return Signature.Unknown("a type of the signature is not known")
        for (other in candidates.drop(1)) {
            if (other.parameters.size != first.parameters.size) return Signature.None
            for ((a, b) in first.parameters.zip(other.parameters)) {
                if (a.second != b.second) return Signature.None
                when (types.same(a.first ?: return Signature.Unknown("a type of the signature is not known"),
                    b.first ?: return Signature.Unknown("a type of the signature is not known"))) {
                    false -> return Signature.None
                    null -> return Signature.Unknown("the parameters of two methods cannot be compared")
                    true -> {}
                }
            }
            when (types.same(first.returns, other.returns ?: return Signature.Unknown("a return type is not known"))) {
                false -> return Signature.None
                null -> return Signature.Unknown("the return types of two methods cannot be compared")
                true -> {}
            }
        }
        return Signature.Unique(first)
    }

    private companion object {
        val FUNCTION_TYPE_TARGETS = setOf("System.MulticastDelegate", "System.Delegate", "System.Object", "System.ICloneable", "System.Runtime.Serialization.ISerializable")
    }
}
