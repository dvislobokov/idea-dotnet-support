// Ported from Roslyn: src/Compilers/CSharp/Portable/Binder/Semantics/OverloadResolution/OverloadResolution.cs — BetterFunctionMember
// (lines 2136-2750), MoreSpecificType (2752-2869), BetterConversionFromExpression (2872-3047), BetterCollectionExpressionConversion and
// BetterParamsCollectionType (3049-3265), ExpressionMatchExactly (3267-3412), BetterConversionTarget (3453-3673),
// IsMethodGroupConversionIncompatibleWithDelegate and CanDowngradeConversionFromLambdaToNeither (3675-3776),
// roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c. Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
// Kotlin names are Roslyn's with a lower-case first letter; what is left out — CSHARP_PSI_MIGRATION.md, D6c4.
package io.github.dotnetsupport.lang.semantic.roslyn

import io.github.dotnetsupport.csharp.lang.psi.CSharpAnonymousFunctionExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpExpression
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodTypeInferrer.Unknown

/**
 * C# §12.6.4.3 – §12.6.4.7, the better function member, as Roslyn decides it: the better conversion from each argument, then the
 * tie-breaks. Roslyn has no "not known": where a fact this port needs is missing (a type that does not resolve, two types that cannot be
 * compared, the body of a lambda that does not type), it throws [Unknown] with the reason, and the caller writes it to the trace.
 *
 * The expressions are seen through [Host] (what Roslyn's bound nodes know: the kind of a conversion, a natural type, a lambda bound for
 * a delegate), the types through [RoslynTypes]. Not ported: `NormalizeTaskTypes` (custom task-like types), the COM `considerRefKinds`
 * algorithm, `dynamic`, pointers, interactive submissions, custom modifiers, the C# 12 collection expression rules (the C# 13 ones apply).
 */
internal class RoslynBetterFunctionMember(private val types: RoslynTypes, private val host: Host) {

    enum class BetterResult { LEFT, RIGHT, NEITHER }

    /** An argument of the call: an expression, or the receiver of an extension method (Roslyn's first argument, a value of its type). */
    sealed class Argument {
        class Expression(val expression: CSharpExpression) : Argument()
        class Receiver(val type: SemanticType) : Argument()
    }

    /** An element of a collection expression: an expression, or a spread `..e` (compared by the element types alone). */
    sealed class Element {
        class Expression(val expression: CSharpExpression) : Element()
        data object Spread : Element()
    }

    /**
     * A candidate applicable to the call (`MemberResolutionResult`), the parameters put in the order of the arguments: [targets] the
     * parameter type each argument goes to (substituted; the element type for the arguments of an expanded `params`), [refKinds] how it
     * takes it (`ref` / `out` / `in`, null by value), [definitionTargets] the same types of the original definition (uninstantiated, for
     * "more specific"); [arity] the type parameters of the method; [declaredParameterCount] all parameters declared, [parametersUsed]
     * `GetParameterCounts`; [paramsType] the declared type of the `params` parameter of an expanded form; [toLastParameter] per argument,
     * whether it goes to that parameter.
     */
    class Member(
        val arity: Int, val expanded: Boolean, val targets: List<SemanticType>, val refKinds: List<String?>, val definitionTargets: List<SemanticType>,
        val declaredParameterCount: Int, val parametersUsed: Int, val paramsType: SemanticType?, val toLastParameter: List<Boolean>,
        /** `HasTypeArgumentInferredFromFunctionType`: a type argument came from the function type of a lambda or a method group. */
        val inferredFromFunctionType: Boolean = false,
    )

    /** What the port asks about expressions; a question with no answer throws [Unknown]. */
    interface Host {
        /**
         * The kind of the implicit conversion from [e] to [to], as Roslyn names the ones that matter here: `FunctionType`,
         * `InterpolatedStringHandler`, `CollectionExpression`, `ConditionalExpression`, `ImplicitSpan`, `MethodGroup`, `AnonymousFunction`,
         * `Identity`, else `Other`.
         */
        fun conversionKind(e: CSharpExpression, to: SemanticType): String

        /** `BoundExpression.Type`: null for an expression without a type of its own (a lambda, `null`, a method group, a collection). */
        fun naturalType(e: CSharpExpression): SemanticType?

        fun isInterpolatedString(e: CSharpExpression): Boolean

        /** `ConstantValueOpt != null` of an interpolated string. */
        fun isConstantInterpolatedString(e: CSharpExpression): Boolean

        /** `OutVariablePendingInference` / `DiscardExpression` without a type: `out var x`, `out _`. */
        fun isImplicitlyTypedOutVariable(e: CSharpExpression): Boolean

        /** `UnboundLambda`. */
        fun lambdaOf(e: CSharpExpression): CSharpAnonymousFunctionExpression?

        fun isAsync(lambda: CSharpAnonymousFunctionExpression): Boolean

        /** `BindForReturnTypeInference(delegate)` + `GetInferredReturnType`: `Task<T>` for an async lambda; null when the body returns nothing. */
        fun inferredReturnType(lambda: CSharpAnonymousFunctionExpression, delegate: SemanticType): SemanticType?

        /** [body] with the lambda bound for [delegate] (its implicit parameters typed by it). */
        fun <T> boundFor(lambda: CSharpAnonymousFunctionExpression, delegate: SemanticType, body: () -> T): T

        /** The expressions the lambda returns: its expression body, or the `return e;` of its block (a `return;` left out). */
        fun returnExpressions(lambda: CSharpAnonymousFunctionExpression): List<CSharpExpression>

        /** The elements of a tuple literal; null for any other expression. */
        fun tupleElements(e: CSharpExpression): List<CSharpExpression>?

        fun isMethodGroup(e: CSharpExpression): Boolean

        /** `MethodIsCompatibleWithDelegate` for the method group [e] and [delegate]. */
        fun methodGroupConvertsTo(e: CSharpExpression, delegate: SemanticType): Boolean

        /** The elements of a collection expression; null for any other expression. */
        fun collectionElements(e: CSharpExpression): List<Element>?

        /** `IdenticalParameters` of the `Invoke` methods of two delegate types: the same types and `ref` kinds. */
        fun identicalDelegateParameters(d1: SemanticType, d2: SemanticType): Boolean
    }

    // ---- BetterFunctionMember

    fun betterFunctionMember(m1: Member, m2: Member, arguments: List<Argument>): BetterResult {
        // prefer the overload that did not use the function type of a lambda or a method group to infer its type arguments or to convert arguments
        val required1 = requiredFunctionType(m1, arguments)
        val required2 = requiredFunctionType(m2, arguments)
        if (!required1 && required2) return BetterResult.LEFT
        if (required1 && !required2) return BetterResult.RIGHT
        var result = BetterResult.NEITHER
        var okToDowngradeResultToNeither = false
        var ignoreDowngradableToNeither = false
        var allSame = true // are all parameter types equivalent by identity conversions?
        var i = 0
        while (i < arguments.size) {
            val type1 = m1.targets[i]
            val type2 = m2.targets[i]
            val (r, okToDowngradeToNeither) = betterConversionFromExpression(arguments[i], type1, type2)
            if (r == BetterResult.NEITHER) {
                if (allSame && !hasIdentityConversion(type1, type2)) allSame = false
                i++; continue
            }
            if (!hasIdentityConversion(type1, type2)) allSame = false
            // one of them was better: does that contradict a previous result or add a new fact?
            if (result == BetterResult.NEITHER) {
                if (!(ignoreDowngradableToNeither && okToDowngradeToNeither)) {
                    result = r
                    okToDowngradeResultToNeither = okToDowngradeToNeither
                }
            } else if (result != r) {
                // left better in one place, right in another: neither — unless the undocumented legacy behavior for lambdas lets one be ignored
                if (okToDowngradeResultToNeither) {
                    if (okToDowngradeToNeither) {
                        result = BetterResult.NEITHER; okToDowngradeResultToNeither = false; ignoreDowngradableToNeither = true
                    } else {
                        result = r; okToDowngradeResultToNeither = false
                    }
                    i++; continue
                } else if (okToDowngradeToNeither) {
                    i++; continue
                }
                result = BetterResult.NEITHER
                break
            } else {
                okToDowngradeResultToNeither = okToDowngradeResultToNeither && okToDowngradeToNeither
            }
            i++
        }
        if (result != BetterResult.NEITHER) return result

        // the loop above may have ended early: complete the comparison of the remaining parameter types
        if (allSame && m1.parametersUsed == m2.parametersUsed) {
            var j = i + 1
            while (j < arguments.size) {
                if (!hasIdentityConversion(m1.targets[j], m2.targets[j])) { allSame = false; break }
                j++
            }
        }

        // SPEC VIOLATION: the native compiler includes the optional parameters in the matching of the parameter type sequences, and has
        // tie-breaking rules even when the sequences are not equivalent
        if (!allSame || m1.parametersUsed != m2.parametersUsed) {
            if (m1.parametersUsed != m2.parametersUsed) {
                if (m1.expanded) {
                    if (!m2.expanded) return BetterResult.RIGHT
                } else if (m2.expanded) return BetterResult.LEFT
                // both needed optionals: ambiguous; otherwise the one that did not
                if (m1.parametersUsed == arguments.size) return BetterResult.LEFT
                if (m2.parametersUsed == arguments.size) return BetterResult.RIGHT
            }
            return preferValOverInOrRefInterpolatedHandlerParameters(arguments, m1, m2)
        }

        // a non-generic method is better than a generic one
        if (m1.arity == 0) {
            if (m2.arity > 0) return BetterResult.LEFT
        } else if (m2.arity == 0) return BetterResult.RIGHT

        // the normal form is better than the expanded form
        if (!m1.expanded && m2.expanded) return BetterResult.LEFT
        if (m1.expanded && !m2.expanded) return BetterResult.RIGHT

        // both expanded: the one with more declared parameters
        if (m1.expanded && m2.expanded) {
            if (m1.declaredParameterCount > m2.declaredParameterCount) return BetterResult.LEFT
            if (m1.declaredParameterCount < m2.declaredParameterCount) return BetterResult.RIGHT
        }

        // all parameters with an argument over defaults substituted
        val hasAll1 = m1.expanded || m1.declaredParameterCount == arguments.size
        val hasAll2 = m2.expanded || m2.declaredParameterCount == arguments.size
        if (hasAll1 && !hasAll2) return BetterResult.LEFT
        if (!hasAll1 && hasAll2) return BetterResult.RIGHT

        // the more specific parameter types, of the original definitions
        val moreSpecific = moreSpecificType(m1.definitionTargets, m2.definitionTargets)
        if (moreSpecific != BetterResult.NEITHER) return moreSpecific

        // interactive submissions and custom modifiers: not modeled

        val valOverIn = preferValOverInOrRefInterpolatedHandlerParameters(arguments, m1, m2)
        if (valOverIn != BetterResult.NEITHER) return valOverIn

        // params collection betterness, when every argument is in the params collection of both or of neither
        if (m1.expanded && m2.expanded && m1.toLastParameter == m2.toLastParameter) {
            val t1 = m1.paramsType
            val t2 = m2.paramsType
            if (t1 != null && t2 != null && !hasIdentityConversion(t1, t2)) {
                val better = betterParamsCollectionType(t1, t2)
                if (better != BetterResult.NEITHER) return better
            }
        }
        return BetterResult.NEITHER
    }

    /** `RequiredFunctionType`: a type argument inferred from a function type, or an argument converted by one. */
    private fun requiredFunctionType(m: Member, arguments: List<Argument>): Boolean = m.inferredFromFunctionType ||
        arguments.indices.any { i -> (arguments[i] as? Argument.Expression)?.let { host.conversionKind(it.expression, m.targets[i]) == FUNCTION_TYPE } == true }

    /** `val` parameters over `in` ones, and over `ref` ones when the argument is an interpolated string handler. */
    private fun preferValOverInOrRefInterpolatedHandlerParameters(arguments: List<Argument>, m1: Member, m2: Member): BetterResult {
        var preference = BetterResult.NEITHER
        for (i in arguments.indices) {
            val e = (arguments[i] as? Argument.Expression)?.expression
            val handler = e != null && host.conversionKind(e, m1.targets[i]) == INTERPOLATED_STRING_HANDLER && host.conversionKind(e, m2.targets[i]) == INTERPOLATED_STRING_HANDLER
            val refKind1 = m1.refKinds[i]
            val refKind2 = m2.refKinds[i]
            if (refKind1 == null && isAcceptableRefMismatch(refKind2, handler)) {
                if (preference == BetterResult.RIGHT) return BetterResult.NEITHER
                preference = BetterResult.LEFT
            } else if (refKind2 == null && isAcceptableRefMismatch(refKind1, handler)) {
                if (preference == BetterResult.LEFT) return BetterResult.NEITHER
                preference = BetterResult.RIGHT
            }
        }
        return preference
    }

    private fun isAcceptableRefMismatch(refKind: String?, interpolatedStringHandlerConversion: Boolean): Boolean =
        refKind == "in" || refKind == "ref" && interpolatedStringHandlerConversion

    // ---- MoreSpecificType

    private fun moreSpecificType(t1: List<SemanticType>, t2: List<SemanticType>): BetterResult {
        var result = BetterResult.NEITHER
        for (i in t1.indices) {
            val r = moreSpecificType(t1[i], t2[i])
            if (r == BetterResult.NEITHER) continue
            if (result == BetterResult.NEITHER) result = r
            else if (result != r) return BetterResult.NEITHER
        }
        return result
    }

    private fun moreSpecificType(t1: SemanticType, t2: SemanticType): BetterResult {
        // a type parameter is less specific than a non-type parameter
        val t1IsTypeParameter = t1 is SemanticType.Parameter
        val t2IsTypeParameter = t2 is SemanticType.Parameter
        if (t1IsTypeParameter && !t2IsTypeParameter) return BetterResult.RIGHT
        if (!t1IsTypeParameter && t2IsTypeParameter) return BetterResult.LEFT
        if (t1IsTypeParameter && t2IsTypeParameter) return BetterResult.NEITHER
        // an array type is more specific than another if its element type is
        if (t1 is SemanticType.ArrayOf) {
            if (t2 !is SemanticType.ArrayOf) return BetterResult.NEITHER
            return moreSpecificType(t1.element ?: throw Unknown("the element type of $t1 is not known"), t2.element ?: throw Unknown("the element type of $t2 is not known"))
        }
        // pointers and dynamic: not modeled
        // a constructed type is more specific than another if at least one type argument is more specific and none less
        val args1 = allTypeArguments(t1)
        val args2 = allTypeArguments(t2)
        if (args1.size != args2.size) return BetterResult.NEITHER
        return moreSpecificType(args1, args2)
    }

    /** `GetAllTypeArguments`: the type arguments of the containing types first, then the type's own. */
    private fun allTypeArguments(type: SemanticType): List<SemanticType> {
        val outer = types.outerType(type)?.let(::allTypeArguments).orEmpty()
        return outer + types.typeArguments(type).map { it ?: throw Unknown("a type argument of $type is not known") }
    }

    // ---- BetterConversionFromExpression

    /** Whether [t1] or [t2] is a better conversion target from [argument]; the second value: `okToDowngradeToNeither`. */
    fun betterConversionFromExpression(argument: Argument, t1: SemanticType, t2: SemanticType): Pair<BetterResult, Boolean> {
        if (hasIdentityConversion(t1, t2)) return BetterResult.NEITHER to false
        val e = (argument as? Argument.Expression)?.expression
        val lambda = e?.let(host::lambdaOf)
        // neither conversion is better when the argument is an implicitly typed out variable
        if (e != null && host.isImplicitlyTypedOutVariable(e)) return BetterResult.NEITHER to false
        val kind1 = e?.let { host.conversionKind(it, t1) } ?: OTHER
        val kind2 = e?.let { host.conversionKind(it, t2) } ?: OTHER
        // C# 10: for a non-constant interpolated string, an interpolated string handler conversion is better than one that is not
        if (e != null && host.isInterpolatedString(e) && (kind1 == INTERPOLATED_STRING_HANDLER || kind2 == INTERPOLATED_STRING_HANDLER) && !host.isConstantInterpolatedString(e)) {
            return when {
                kind1 == INTERPOLATED_STRING_HANDLER && kind2 == INTERPOLATED_STRING_HANDLER -> BetterResult.NEITHER
                kind1 == INTERPOLATED_STRING_HANDLER -> BetterResult.LEFT
                else -> BetterResult.RIGHT
            } to false
        }
        // a conversion to a delegate type is better than the function type conversion
        when {
            kind1 == FUNCTION_TYPE && kind2 == FUNCTION_TYPE -> {}
            kind2 == FUNCTION_TYPE -> return BetterResult.LEFT to false
            kind1 == FUNCTION_TYPE -> return BetterResult.RIGHT to false
        }
        // E exactly matches T1 and not T2
        val t1MatchesExactly = expressionMatchExactly(argument, t1)
        val t2MatchesExactly = expressionMatchExactly(argument, t2)
        if (t1MatchesExactly) {
            if (!t2MatchesExactly) return BetterResult.LEFT to (lambda != null && canDowngradeConversionFromLambdaToNeither(lambda, t1, t2))
        } else if (t2MatchesExactly) {
            return BetterResult.RIGHT to (lambda != null && canDowngradeConversionFromLambdaToNeither(lambda, t1, t2))
        }
        // C1 is not a conditional expression conversion and C2 is
        if (kind1 != CONDITIONAL_EXPRESSION && kind2 == CONDITIONAL_EXPRESSION) return BetterResult.LEFT to false
        if (kind2 != CONDITIONAL_EXPRESSION && kind1 == CONDITIONAL_EXPRESSION) return BetterResult.RIGHT to false
        // E is a collection expression
        if (kind1 == COLLECTION_EXPRESSION && kind2 == COLLECTION_EXPRESSION) {
            return betterCollectionExpressionConversion(host.collectionElements(e!!) ?: throw Unknown("the elements of the collection expression are not known"), t1, t2) to false
        }
        when {
            kind1 == IMPLICIT_SPAN && kind2 == IMPLICIT_SPAN -> {}
            kind2 == IMPLICIT_SPAN -> return BetterResult.RIGHT to false
            kind1 == IMPLICIT_SPAN -> return BetterResult.LEFT to false
        }
        // T1 is a better conversion target than T2
        return betterConversionTarget(argument, t1, t2, 0)
    }

    private fun betterCollectionExpressionConversion(elements: List<Element>, t1: SemanticType, t2: SemanticType): BetterResult {
        val kind1 = collectionKind(t1)
        val kind2 = collectionKind(t2)
        val t1IsSpanType = kind1.span
        val t2IsSpanType = kind2.span
        // both no span types, and T1 converts to T2 but not T2 to T1
        if (!t1IsSpanType && !t2IsSpanType) {
            val t1ToT2 = implicitConversionExists(t1, t2)
            val t2ToT1 = implicitConversionExists(t2, t1)
            if (t1ToT2 && !t2ToT1) return BetterResult.LEFT
            if (!t1ToT2 && t2ToT1) return BetterResult.RIGHT
        }
        val elementType1 = kind1.element ?: throw Unknown("the element type of $t1 is not known")
        val elementType2 = kind2.element ?: throw Unknown("the element type of $t2 is not known")
        // the element types differ: the element conversions to E1 are better than those to E2
        if (!hasIdentityConversion(elementType1, elementType2)) {
            var betterResult = BetterResult.NEITHER
            for (element in elements) {
                val elementBetterResult = when (element) {
                    Element.Spread -> betterConversionTarget(null, elementType1, elementType2, 0).first
                    is Element.Expression -> betterConversionFromExpression(Argument.Expression(element.expression), elementType1, elementType2).first
                }
                if (elementBetterResult == BetterResult.NEITHER) continue
                if (betterResult != BetterResult.NEITHER) {
                    if (betterResult != elementBetterResult) return BetterResult.NEITHER
                } else betterResult = elementBetterResult
            }
            return betterResult
        }
        // T1 is ReadOnlySpan<E1> and T2 is Span<E2>; T1 a span and T2 an array or an array interface
        if (t1IsSpanType || t2IsSpanType) {
            when {
                kind1.kind == CollectionKind.READ_ONLY_SPAN && kind2.kind == CollectionKind.SPAN -> return BetterResult.LEFT
                t1IsSpanType && kind2.arrayLike -> return BetterResult.LEFT
                kind1.kind == CollectionKind.SPAN && kind2.kind == CollectionKind.READ_ONLY_SPAN -> return BetterResult.RIGHT
                t2IsSpanType && kind1.arrayLike -> return BetterResult.RIGHT
            }
        }
        return BetterResult.NEITHER
    }

    private fun betterParamsCollectionType(t1: SemanticType, t2: SemanticType): BetterResult = betterCollectionExpressionConversion(emptyList(), t1, t2)

    private enum class CollectionKind { READ_ONLY_SPAN, SPAN, ARRAY, ARRAY_INTERFACE, OTHER }

    private class Collection(val kind: CollectionKind, val element: SemanticType?) {
        val span get() = kind == CollectionKind.READ_ONLY_SPAN || kind == CollectionKind.SPAN
        val arrayLike get() = kind == CollectionKind.ARRAY || kind == CollectionKind.ARRAY_INTERFACE
    }

    /** `GetCollectionExpressionTypeKind` with the element type (the iteration type for the other collections). */
    private fun collectionKind(type: SemanticType): Collection = when {
        types.isReadOnlySpan(type) -> Collection(CollectionKind.READ_ONLY_SPAN, types.spanElementType(type))
        types.definitionName(type) == SPAN -> Collection(CollectionKind.SPAN, types.spanElementType(type))
        types.isSZArray(type) -> Collection(CollectionKind.ARRAY, types.arrayElementType(type))
        types.definitionName(type) in ARRAY_INTERFACES -> Collection(CollectionKind.ARRAY_INTERFACE, types.typeArguments(type).firstOrNull())
        else -> Collection(CollectionKind.OTHER, types.collectionIterationType(type))
    }

    // ---- ExpressionMatchExactly

    private fun expressionMatchExactly(argument: Argument, t: SemanticType): Boolean {
        val e = when (argument) {
            is Argument.Receiver -> return hasIdentityConversion(argument.type, t)
            is Argument.Expression -> argument.expression
        }
        // E has a type S with an identity conversion to T
        val natural = host.naturalType(e)
        if (natural != null && hasIdentityConversion(natural, t)) return true
        // a tuple literal: each element against the element types of T, whatever the literal's own type
        host.tupleElements(e)?.let { elements ->
            val targets = types.tupleElementTypes(t) ?: return false
            if (targets.size != elements.size) return false
            return elements.indices.all { expressionMatchExactly(Argument.Expression(elements[it]), targets[it] ?: throw Unknown("an element type of $t is not known")) }
        }
        // E is an anonymous function, T a delegate type D or Expression<D>, D returns Y
        val lambda = host.lambdaOf(e) ?: return false
        val d = getDelegateType(t) ?: return false
        var y = types.delegateInvoke(d)?.second ?: throw Unknown("the return type of $d is not known")
        if (types.isVoid(y)) return false
        // an inferred return type X exists for E with the parameters of D, and X is identical to Y
        val x = host.inferredReturnType(lambda, d)
        if (x != null && hasIdentityConversion(x, y)) return true
        if (host.isAsync(lambda)) {
            y = if (types.definitionName(y) in GENERIC_TASKS) types.typeArguments(y).firstOrNull() ?: throw Unknown("the type argument of $y is not known") else return false
        }
        // the body is an expression that exactly matches Y, or every return statement has an expression that exactly matches Y
        val returns = host.returnExpressions(lambda)
        if (returns.isEmpty()) return false
        return host.boundFor(lambda, d) { returns.all { expressionMatchExactly(Argument.Expression(it), y) } }
    }

    // ---- BetterConversionTarget

    private fun betterConversionTarget(argument: Argument?, type1: SemanticType, type2: SemanticType, depth: Int): Pair<BetterResult, Boolean> {
        if (depth > BETTER_CONVERSION_TARGET_RECURSION_LIMIT) return BetterResult.NEITHER to false
        if (hasIdentityConversion(type1, type2)) return BetterResult.NEITHER to false
        // C# 14 first-class spans: ReadOnlySpan<E> over Span<E>; between two spans no other rule but the covariant ReadOnlySpan one
        if (isBetterSpanConversionTarget(type1, type2)) return BetterResult.LEFT to false
        if (isBetterSpanConversionTarget(type2, type1)) return BetterResult.RIGHT to false
        if (isSpanLike(type1) && isSpanLike(type2) && !(types.isReadOnlySpan(type1) && types.isReadOnlySpan(type2))) return BetterResult.NEITHER to false
        // T1 is better than T2 if no implicit conversion from T2 to T1 exists and one from T1 to T2 does
        val type1ToType2 = implicitConversionExists(type1, type2)
        val type2ToType1 = implicitConversionExists(type2, type1)
        val lambda = (argument as? Argument.Expression)?.expression?.let(host::lambdaOf)
        if (type1ToType2) {
            if (type2ToType1) return BetterResult.NEITHER to false
            return BetterResult.LEFT to (lambda != null && canDowngradeConversionFromLambdaToNeither(lambda, type1, type2))
        } else if (type2ToType1) {
            return BetterResult.RIGHT to (lambda != null && canDowngradeConversionFromLambdaToNeither(lambda, type1, type2))
        }
        // Task<S1> against Task<S2> by S1 and S2; a Task<T> satisfies no other rule
        val type1IsGenericTask = types.definitionName(type1) in GENERIC_TASKS
        val type2IsGenericTask = types.definitionName(type2) in GENERIC_TASKS
        if (type1IsGenericTask) {
            if (type2IsGenericTask) {
                return betterConversionTarget(null, types.typeArguments(type1).firstOrNull() ?: throw Unknown("the type argument of $type1 is not known"),
                    types.typeArguments(type2).firstOrNull() ?: throw Unknown("the type argument of $type2 is not known"), depth + 1)
            }
            return BetterResult.NEITHER to false
        } else if (type2IsGenericTask) return BetterResult.NEITHER to false
        // D1 and D2 (or Expression<D1> and Expression<D2>): D2 returns void, or S1 is a better target than S2
        val d1 = getDelegateType(type1)
        if (d1 != null) {
            val d2 = getDelegateType(type2) ?: return BetterResult.NEITHER to false
            val r1 = types.delegateInvoke(d1)?.second ?: throw Unknown("the return type of $d1 is not known")
            val r2 = types.delegateInvoke(d2)?.second ?: throw Unknown("the return type of $d2 is not known")
            var delegateResult = BetterResult.NEITHER
            if (!types.isVoid(r1)) {
                if (types.isVoid(r2)) delegateResult = BetterResult.LEFT
            } else if (!types.isVoid(r2)) delegateResult = BetterResult.RIGHT
            if (delegateResult == BetterResult.NEITHER) delegateResult = betterConversionTarget(null, r1, r2, depth + 1).first
            // the winner's conversion of a method group must be a valid one, else neither (compatibility)
            val e = (argument as? Argument.Expression)?.expression
            if (e != null && host.isMethodGroup(e)) {
                if (delegateResult == BetterResult.LEFT && !host.methodGroupConvertsTo(e, d1)) return BetterResult.NEITHER to false
                if (delegateResult == BetterResult.RIGHT && !host.methodGroupConvertsTo(e, d2)) return BetterResult.NEITHER to false
            }
            return delegateResult to false
        } else if (getDelegateType(type2) != null) return BetterResult.NEITHER to false
        // a signed integral type over an unsigned one
        if (types.definitionName(type1) in SIGNED_INTEGRAL) {
            if (types.definitionName(type2) in UNSIGNED_INTEGRAL) return BetterResult.LEFT to false
        } else if (types.definitionName(type1) in UNSIGNED_INTEGRAL && types.definitionName(type2) in SIGNED_INTEGRAL) return BetterResult.RIGHT to false
        return BetterResult.NEITHER to false
    }

    private fun isBetterSpanConversionTarget(type1: SemanticType, type2: SemanticType): Boolean {
        if (!types.isReadOnlySpan(type1) || types.definitionName(type2) != SPAN) return false
        val element1 = types.spanElementType(type1) ?: throw Unknown("the element type of $type1 is not known")
        val element2 = types.spanElementType(type2) ?: throw Unknown("the element type of $type2 is not known")
        return hasIdentityConversion(element1, element2)
    }

    private fun isSpanLike(type: SemanticType): Boolean = types.isReadOnlySpan(type) || types.definitionName(type) == SPAN

    /**
     * DELIBERATE SPEC VIOLATION (Roslyn's bug 11961): the native compiler skipped the type analysis for a lambda against two delegates with
     * identical parameter lists; a result from that analysis may be dropped to "neither" so that such code stays unambiguous.
     */
    private fun canDowngradeConversionFromLambdaToNeither(lambda: CSharpAnonymousFunctionExpression, type1: SemanticType, type2: SemanticType): Boolean {
        val d1 = getDelegateType(type1) ?: return false
        val d2 = getDelegateType(type2) ?: return false
        if (!host.identicalDelegateParameters(d1, d2)) return true
        val r1 = types.delegateInvoke(d1)?.second ?: throw Unknown("the return type of $d1 is not known")
        val r2 = types.delegateInvoke(d2)?.second ?: throw Unknown("the return type of $d2 is not known")
        if (types.isVoid(r1)) return types.isVoid(r2)
        if (types.isVoid(r2)) return false
        if (hasIdentityConversion(r1, r2)) return true
        return host.inferredReturnType(lambda, d1) == null
    }

    // ---- facts of types

    /** `GetDelegateType`: a delegate type, or the `D` of `Expression<D>`; null for any other type. */
    private fun getDelegateType(type: SemanticType): SemanticType? = types.delegateOfExpression(type)?.takeIf { types.delegateInvoke(it) != null }

    private fun hasIdentityConversion(a: SemanticType, b: SemanticType): Boolean = types.same(a, b) ?: throw Unknown("$a and $b cannot be compared")

    private fun implicitConversionExists(from: SemanticType, to: SemanticType): Boolean =
        types.implicitConversionExists(from, to) ?: throw Unknown("whether $from converts to $to is not known")

    companion object {
        const val FUNCTION_TYPE = "FunctionType"
        const val INTERPOLATED_STRING_HANDLER = "InterpolatedStringHandler"
        const val COLLECTION_EXPRESSION = "CollectionExpression"
        const val CONDITIONAL_EXPRESSION = "ConditionalExpression"
        const val IMPLICIT_SPAN = "ImplicitSpan"
        const val METHOD_GROUP = "MethodGroup"
        const val ANONYMOUS_FUNCTION = "AnonymousFunction"
        const val IDENTITY = "Identity"
        const val OTHER = "Other"

        private const val BETTER_CONVERSION_TARGET_RECURSION_LIMIT = 100
        private const val SPAN = "System.Span`1"
        private val GENERIC_TASKS = setOf("System.Threading.Tasks.Task`1", "System.Threading.Tasks.ValueTask`1")
        private val ARRAY_INTERFACES = setOf(
            "System.Collections.Generic.IEnumerable`1", "System.Collections.Generic.IReadOnlyCollection`1", "System.Collections.Generic.IReadOnlyList`1",
            "System.Collections.Generic.ICollection`1", "System.Collections.Generic.IList`1",
        )
        private val SIGNED_INTEGRAL = setOf("System.SByte", "System.Int16", "System.Int32", "System.Int64", "System.IntPtr")
        private val UNSIGNED_INTEGRAL = setOf("System.Byte", "System.UInt16", "System.UInt32", "System.UInt64", "System.UIntPtr")
    }
}
