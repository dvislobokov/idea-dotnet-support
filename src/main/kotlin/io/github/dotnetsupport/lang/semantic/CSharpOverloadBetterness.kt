package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver.Conversion
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.GENERIC_TASKS
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.NATURAL_TARGETS
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.SIGNED_OVER
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.VOID
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Form

/*
 * Which candidate is better (C# §12.6.4.3 – §12.6.4.7), as extensions of [CSharpOverloads] (D6e5): the better conversion from an
 * expression ([better]) and from a type ([betterFromType]), the better conversion target, lambdas by what they return, collection
 * targets, and the better function member with its tie-breaks ([betterMember]: non-generic, normal form, no defaults, more specific).
 * Roslyn's `OverloadResolution.cs` replaces it in D6c.
 */

// ---- better conversion

/** §12.6.4.5: 1 when the conversion of [e] to [t1] is better than to [t2], -1 the other way round, 0 neither, null not known. */
internal fun CSharpOverloads.better(e: CSharpExpression, type: SemanticType?, t1: SemanticType, t2: SemanticType): Int? {
    when (same(t1, t2)) {
        true -> return 0
        null -> return null
        false -> {}
    }
    val value = unparenthesized(e)
    if (value is CSharpAnonymousFunctionExpression) return betterLambda(value, t1, t2)
    if (value is CSharpCollectionExpression) return betterCollection(t1, t2)
    if (type == null) {
        val targetTyped = value.elementType == SyntaxKind.NullLiteralExpression || value.elementType == SyntaxKind.DefaultLiteralExpression ||
            value is CSharpImplicitObjectCreationExpression || value is CSharpThrowExpression
        return if (targetTyped) betterTarget(t1, t2) else null
    }
    if (value is CSharpInterpolatedStringExpression) {
        // C# 10: a handler conversion is better than any other — for a non-constant interpolated string only (§12.6.4.5); a constant one
        // is a `string` and goes on to the exact match, where `string` wins
        val h1 = isHandler(t1)
        val h2 = isHandler(t2)
        if (h1 != h2) when (constantInterpolation(value)) {
            false -> return if (h1) 1 else -1
            null -> return null
            true -> {}
        }
    }
    val exact1 = same(type, t1)
    val exact2 = same(type, t2)
    if (exact1 == true && exact2 == false) return 1
    if (exact2 == true && exact1 == false) return -1
    if (exact1 == null || exact2 == null) return null
    return betterTarget(t1, t2)
}

/** §12.6.4.5 for an argument known by its type alone (the receiver of an extension method): the exact match, then the better target. */
internal fun CSharpOverloads.betterFromType(type: SemanticType, t1: SemanticType, t2: SemanticType): Int? {
    when (same(t1, t2)) {
        true -> return 0
        null -> return null
        false -> {}
    }
    val exact1 = same(type, t1)
    val exact2 = same(type, t2)
    if (exact1 == true && exact2 == false) return 1
    if (exact2 == true && exact1 == false) return -1
    if (exact1 == null || exact2 == null) return null
    return betterTarget(t1, t2)
}

internal fun CSharpOverloads.isHandler(type: SemanticType): Boolean = type is SemanticType.Library && type.type.attributes.any { it.endsWith("InterpolatedStringHandlerAttribute") }

/** §12.6.4.7, the better conversion target. */
internal fun CSharpOverloads.betterTarget(t1: SemanticType, t2: SemanticType): Int? {
    when (same(t1, t2)) {
        true -> return 0
        null -> return null
        false -> {}
    }
    val n1 = r.definitionName(t1)
    val n2 = r.definitionName(t2)
    if (n1 != null && n2 != null) {
        if (n2 in SIGNED_OVER[n1].orEmpty()) return 1
        if (n1 in SIGNED_OVER[n2].orEmpty()) return -1
    }
    val c12 = classify(t1, t2)
    val c21 = classify(t2, t1)
    if (ok(c12) && c21 == Conversion.NONE) return 1
    if (ok(c21) && c12 == Conversion.NONE) return -1
    if (c12 == Conversion.NONE && c21 == Conversion.NONE) return betterUnrelatedTarget(t1, t2)
    return null
}

/**
 * Roslyn's `BetterConversionTargetCore` for two types with no conversion either way: `Task<S1>` against `Task<S2>` by `S1` and `S2`; a
 * delegate (or `Expression<D>`) returning a value against one returning `void`, else by their return types — for any argument, not only
 * a lambda: `KeyUp(null)` takes the `Func<KeyboardEventArgs, Task>` overload over the `Action<KeyboardEventArgs>` one (MudBlazor, CS0121).
 */
private fun CSharpOverloads.betterUnrelatedTarget(t1: SemanticType, t2: SemanticType): Int? {
    val task1 = r.definitionName(t1) in GENERIC_TASKS
    val task2 = r.definitionName(t2) in GENERIC_TASKS
    if (task1 || task2) {
        if (!task1 || !task2) return 0
        val s1 = (t1 as? SemanticType.Library)?.arguments?.singleOrNull() ?: return null
        val s2 = (t2 as? SemanticType.Library)?.arguments?.singleOrNull() ?: return null
        return betterTarget(s1, s2)
    }
    val d1 = r.expressions.unwrapExpression(t1)?.let(r.expressions::delegateSignature)
    val d2 = r.expressions.unwrapExpression(t2)?.let(r.expressions::delegateSignature)
    if (d1 == null || d2 == null) return 0
    val r1 = d1.second ?: return null
    val r2 = d2.second ?: return null
    val void1 = r.definitionName(r1) == VOID
    val void2 = r.definitionName(r2) == VOID
    if (void1 != void2) return if (void2) 1 else -1
    return if (void1) 0 else betterTarget(r1, r2)
}

/** A lambda against two delegates taking the same parameters: by what the body returns (§12.6.4.5). */
private fun CSharpOverloads.betterLambda(lambda: CSharpAnonymousFunctionExpression, t1: SemanticType, t2: SemanticType): Int? {
    val d1 = r.expressions.unwrapExpression(t1)?.let(r.expressions::delegateSignature)
    val d2 = r.expressions.unwrapExpression(t2)?.let(r.expressions::delegateSignature)
    // C# 10: a conversion to a delegate type is better than the function type conversion to `Delegate`, `object`, `Expression`
    if (d1 != null && d2 == null && r.definitionName(t2) in NATURAL_TARGETS) return 1
    if (d2 != null && d1 == null && r.definitionName(t1) in NATURAL_TARGETS) return -1
    if (d1 == null || d2 == null) return null
    if (d1.first.size != d2.first.size) return 0
    for ((a, b) in d1.first.zip(d2.first)) if (same(a, b) != true) return if (same(a, b) == false) 0 else null
    val r1 = d1.second ?: return null
    val r2 = d2.second ?: return null
    val void1 = r.definitionName(r1) == VOID
    val void2 = r.definitionName(r2) == VOID
    val body = if (strict) strictReturnType(lambda) else r.expressions.lambdaReturnType(lambda)
    if (void1 != void2) {
        if (body == null || r.definitionName(body) == VOID) return null
        return if (void1) -1 else 1
    }
    if (void1) return 0
    body ?: return null
    val async = r.expressions.isAsync(lambda.modifiers)
    val y = if (async) (body as? SemanticType.Library)?.arguments?.firstOrNull() ?: return null else body
    val y1 = if (async) (r1 as? SemanticType.Library)?.arguments?.firstOrNull() ?: return null else r1
    val y2 = if (async) (r2 as? SemanticType.Library)?.arguments?.firstOrNull() ?: return null else r2
    val e1 = same(y, y1)
    val e2 = same(y, y2)
    if (e1 == true && e2 == false) return 1
    if (e2 == true && e1 == false) return -1
    if (e1 == null || e2 == null) return null
    return betterTarget(y1, y2)
}

/** C# 12: a collection expression goes better to `ReadOnlySpan<E>` than to `Span<E>`, to spans than to arrays and other collections. */
private fun CSharpOverloads.betterCollection(t1: SemanticType, t2: SemanticType): Int? {
    fun rank(t: SemanticType): Int = when (r.definitionName(t)) {
        "System.ReadOnlySpan`1" -> 2
        "System.Span`1" -> 1
        else -> 0
    }
    val a = rank(t1)
    val b = rank(t2)
    if (a != b) return if (a > b) 1 else -1
    if (a > 0) return 0
    return betterTarget(t1, t2)
}

/** §12.6.4.3: whether [m] is a better function member than [n] for the call; null when not known. */
internal fun CSharpOverloads.betterMember(m: Form, n: Form, arguments: List<CSharpArgument>, types: List<SemanticType?>, receiver: SemanticType? = null): Boolean? {
    var mBetter = false
    var nBetter = false
    var identical = true
    // the receiver of two extension methods is their first argument: `Queryable.Where` over `Enumerable.Where` for an `IQueryable<T>`
    val p = m.receiverTarget
    val q = n.receiverTarget
    if (p != null && q != null) {
        val sameType = same(p, q) ?: return null
        if (!sameType) {
            identical = false
            when (betterFromType(receiver ?: return null, p, q) ?: return null) {
                1 -> mBetter = true
                -1 -> nBetter = true
            }
        }
    }
    for ((i, argument) in arguments.withIndex()) {
        val p = m.targets[i] ?: return null
        val q = n.targets[i] ?: return null
        val sameType = same(p, q) ?: return null
        if (sameType) continue
        identical = false
        val expression = argument.expression ?: return null
        when (better(expression, types[i], p, q) ?: return null) {
            1 -> mBetter = true
            -1 -> nBetter = true
        }
    }
    if (mBetter != nBetter) return mBetter
    if (mBetter) return false
    if (!identical) return false
    // C# 7.2: an argument without `in` goes better to a parameter by value than to an `in` one
    if (m.refKinds.size == arguments.size && n.refKinds.size == arguments.size) {
        var mValue = false
        var nValue = false
        for ((i, argument) in arguments.withIndex()) {
            if (argument.refKindKeyword != null) continue
            if (m.refKinds[i] == null && n.refKinds[i] == "in") mValue = true
            if (n.refKinds[i] == null && m.refKinds[i] == "in") nValue = true
        }
        if (mValue != nValue) return mValue
        if (mValue) return false
    }
    // the tie-breaks, when the parameter types of the arguments are the same
    if (!m.generic && n.generic) return true
    if (m.generic && !n.generic) return false
    if (!m.expanded && n.expanded) return true
    if (m.expanded && !n.expanded) return false
    if (m.expanded && n.expanded) {
        if (m.declared.size != n.declared.size) return m.declared.size > n.declared.size
        // C# 13 `params` collections: `ReadOnlySpan<T>` over `Span<T>` over the others
        val a = m.paramsType
        val b = n.paramsType
        if (a != null && b != null && same(a, b) == false) return betterCollection(a, b)?.let { it > 0 }
    }
    if (!m.defaults && n.defaults) return true
    if (m.defaults && !n.defaults) return false
    // the receiver of two extension methods is their first parameter (`this IEnumerable<T>` against `this IEnumerable<KeyValuePair<TKey, TValue>>`
    // of AwesomeAssertions' `Should`: the second is more specific — CS0121 was ours alone)
    val mReceiver = if (m.receiverTarget != null && n.receiverTarget != null) listOf(m.declaredReceiver ?: return null) else emptyList()
    val nReceiver = if (m.receiverTarget != null && n.receiverTarget != null) listOf(n.declaredReceiver ?: return null) else emptyList()
    return moreSpecific(mReceiver + m.declaredTargets, nReceiver + n.declaredTargets)
}

/** §12.6.4.3, the more specific parameter types: a type parameter is less specific than any other type, recursively. */
private fun CSharpOverloads.moreSpecific(a: List<SemanticType?>, b: List<SemanticType?>): Boolean? {
    var more = false
    var less = false
    for ((x, y) in a.zip(b)) {
        when (specific(x ?: return null, y ?: return null)) {
            1 -> more = true
            -1 -> less = true
            null -> return null
        }
    }
    return more && !less
}

private fun CSharpOverloads.specific(x: SemanticType, y: SemanticType): Int? {
    val xp = x is SemanticType.Parameter
    val yp = y is SemanticType.Parameter
    if (xp && yp) return 0
    if (yp) return 1
    if (xp) return -1
    val xa = arguments(x)
    val ya = arguments(y)
    if (r.definitionName(x) != r.definitionName(y) || xa.size != ya.size) return 0
    var more = false
    var less = false
    for ((p, q) in xa.zip(ya)) when (specific(p ?: return null, q ?: return null)) {
        1 -> more = true
        -1 -> less = true
        null -> return null
    }
    return if (more && !less) 1 else if (less && !more) -1 else 0
}

private fun CSharpOverloads.arguments(type: SemanticType): List<SemanticType?> = when (type) {
    is SemanticType.Library -> type.arguments
    is SemanticType.Source -> type.arguments
    is SemanticType.ArrayOf -> listOf(type.element)
    is SemanticType.Parameter -> emptyList()
}
