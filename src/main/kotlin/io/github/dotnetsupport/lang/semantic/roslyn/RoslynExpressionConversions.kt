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

        /** The `null` literal. */
        data object NullLiteral : Source()

        /** The `default` literal (no type written). */
        data object DefaultLiteral : Source()

        /** `throw e` as an expression. */
        data object ThrowExpression : Source()

        /** `new(…)` with no type written: `BoundUnconvertedObjectCreationExpression`. */
        data object ImplicitObjectCreation : Source()

        /** `new { … }`: its type is anonymous, a class with `object` for its only base and no interface. */
        data object AnonymousObjectCreation : Source()

        /** `$"…"`, its natural type `string`. */
        data object InterpolatedString : Source()

        /** A lambda or an anonymous method (`UnboundLambda`); [hasFunctionType]: whether it has a natural type (C# 10). */
        class AnonymousFunction(val hasFunctionType: Boolean) : Source()

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

        /** No conversion from the expression itself: the conversion from its type decides (`ClassifyImplicitConversionFromType`). */
        data object ByType : Result()

        /** To a delegate or `Expression<D>`: the anonymous function conversion, decided by the body (`HasAnonymousFunctionConversion`, the caller's still). */
        data object AnonymousFunctionConversion : Result()
    }

    /** `ClassifyImplicitBuiltInConversionFromExpression`, the kinds ported, then [Result.ByType] for the conversion from the expression's type. */
    fun classifyImplicitConversionFromExpression(source: Source, destination: SemanticType): Result = when (source) {
        is Source.MethodGroup -> classifyMethodGroup(source, destination)
        Source.NullLiteral -> classifyNullLiteralConversion(destination)
        Source.DefaultLiteral -> Result.Exists("DefaultLiteral")
        Source.ThrowExpression -> Result.Exists("ImplicitThrow")
        // the conversion always exists; whether the type can be created so is the binding's error (CS8752 / CS1729), not the conversion's
        Source.ImplicitObjectCreation -> Result.Exists("ObjectCreation")
        Source.AnonymousObjectCreation -> classifyAnonymousType(destination)
        Source.InterpolatedString -> when {
            // `IsValidInterpolatedStringConversionTarget`; a handler takes a constant string too — the constness decides the better conversion only
            types.definitionName(destination) in FORMATTABLE -> Result.Exists("InterpolatedString")
            types.isInterpolatedStringHandlerType(destination) -> Result.Exists("InterpolatedStringHandler")
            else -> Result.ByType
        }
        is Source.AnonymousFunction -> classifyAnonymousFunction(source, destination)
        Source.NotPorted -> Result.Unknown("not ported")
    }

    /**
     * A lambda converts to a delegate type or `Expression<D>` (the anonymous function conversion), else only by its function type to the
     * function type targets and the non-generic expression types; it has no type of its own, so no other conversion — to an interface
     * (`IEqualityComparer<T>`, `IOpenApiDocumentTransformer`), a struct, a class it is none, whatever operators they declare.
     */
    private fun classifyAnonymousFunction(lambda: Source.AnonymousFunction, destination: SemanticType): Result {
        // a type parameter is no delegate nor expression type: no anonymous function conversion to it (`HasAnonymousFunctionConversion`)
        if (destination is SemanticType.Parameter) return Result.None
        val delegate = types.delegateOfExpression(destination) ?: return Result.Unknown("the delegate of ${destination} is not known")
        if (types.delegateInvoke(delegate) != null) return Result.AnonymousFunctionConversion
        if (isValidFunctionTypeConversionTarget(destination) || types.definitionName(destination) in EXPRESSION_TYPES) {
            return if (lambda.hasFunctionType) Result.Exists("FunctionType") else Result.None
        }
        return when (types.typeKind(destination)) {
            null -> Result.Unknown("the kind of ${destination} is not known")
            RoslynTypes.TypeKind.DELEGATE -> Result.Unknown("the signature of the delegate ${destination} is not known")
            else -> Result.None
        }
    }

    /** `ClassifyNullLiteralConversion`: to a nullable type, to a reference type; a pointer is not modeled (the caller's applicability decides it). */
    private fun classifyNullLiteralConversion(destination: SemanticType): Result = when {
        destination !is SemanticType.Parameter && types.isNullableType(destination) -> Result.Exists("NullLiteral")
        else -> when (types.isReferenceType(destination)) {
            true -> Result.Exists("ImplicitReference")
            false -> Result.None
            null -> Result.Unknown(if (destination is SemanticType.Parameter) "the constraints of ${destination} do not say whether it is a reference type"
                else "whether ${destination} is a reference type is not known")
        }
    }

    /**
     * The conversions from an anonymous type: identity to itself (never a written target) and the implicit reference conversion to its only
     * base `object`; to any other closed type none.
     */
    private fun classifyAnonymousType(destination: SemanticType): Result = when {
        destination is SemanticType.Parameter -> Result.Unknown("an anonymous type to the type parameter ${destination}")
        types.definitionName(destination) == "System.Object" -> Result.Exists("ImplicitReference")
        types.isClosed(destination) -> Result.None
        else -> Result.Unknown("the type ${destination} is not fully known")
    }

    /**
     * A method group: to a delegate type the method group conversion (`GetMethodGroupDelegateConversion`, the caller's still); to any other
     * type only by the function type of the group (`HasImplicitFunctionTypeConversion`) — there is no conversion from a method group's type,
     * it has none, and no user-defined one.
     */
    private fun classifyMethodGroup(group: Source.MethodGroup, destination: SemanticType): Result {
        if (destination is SemanticType.Parameter) return Result.Unknown("a method group to a type parameter")
        // a delegate type: the method group conversion (RoslynMethodGroupConversion), which the caller asks of the group's methods
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

    /** The function type of a method group (C# 10): its unique signature; null when it has none; [Unknown] when that is not known. */
    fun functionType(group: Source.MethodGroup): Method? = when (val signature = getUniqueSignatureFromMethodGroup(group)) {
        is Signature.Unique -> signature.method
        Signature.None -> null
        is Signature.Unknown -> throw RoslynMethodTypeInferrer.Unknown(signature.reason)
    }

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
        val EXPRESSION_TYPES = setOf("System.Linq.Expressions.Expression", "System.Linq.Expressions.LambdaExpression")
        val FORMATTABLE = setOf("System.FormattableString", "System.IFormattable")
        val FUNCTION_TYPE_TARGETS =setOf("System.MulticastDelegate", "System.Delegate", "System.Object", "System.ICloneable", "System.Runtime.Serialization.ISerializable")
    }
}
