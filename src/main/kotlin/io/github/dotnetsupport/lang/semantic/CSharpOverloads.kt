package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver.Conversion
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynArgsToParameters
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynConversions
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynTypes
import java.util.IdentityHashMap

/**
 * Conversions (C# §10.2) and overload resolution (§12.6.4) of layer 11d (CSHARP_PSI_MIGRATION.md, task D1) for a [CSharpNameResolver]:
 *
 * - [classify]: identity (tuple names aside), implicit numeric, nullable, reference (base types, interfaces, variance, arrays), boxing,
 *   type parameters by their constraints, user-defined implicit operators; NONE only where every type involved is fully known;
 * - [argument]: the conversion of an argument expression — `null`, `default`, `new()`, `throw`, lambdas, method groups, collection
 *   expressions, interpolated strings, constants of `int` that fit, tuple literals, `?:` without a natural type;
 * - [resolve]: the applicable candidates (normal and expanded `params` form, named and optional arguments, type arguments written or
 *   inferred, constraints, the receiver of an extension method) and the better function member (§12.6.4.3: better conversion from
 *   expression and target, exact match, signed over unsigned, lambdas by their return type, then non-generic, normal form, no defaults,
 *   more specific). It answers only when one candidate surely beats all others: anything unknown on the way and it says nothing.
 *
 * One file per concern (D6e5, so D6c replaces them file by file): the conversions — [same], [classify], [argument] — are extensions in
 * `CSharpOverloadConversions.kt`, the better conversion and the better function member in `CSharpOverloadBetterness.kt`; this file keeps
 * the forms, the applicability, [resolve] / [evaluate], and the state they share.
 */
internal class CSharpOverloads(internal val r: CSharpNameResolver) {
    internal val known = IdentityHashMap<Any, Boolean>()
    internal val roslynConversions by lazy { RoslynConversions(RoslynTypes(r)) { operators(it, withBases = false) } }

    // the method type parameters of the candidates being resolved: a parameter of the caller's own method is a type like any other
    internal val openOwners = HashSet<PsiElement>()

    internal val tracedConversions = HashSet<Triple<String, String, Boolean>>()

    // ---- overload resolution

    /** A candidate in the form it is applicable in: the parameter type each argument goes to (method type arguments substituted). */
    internal class Form(
        val symbol: CSharpSymbol, val declared: List<CSharpNameResolver.Parameter>, val targets: List<SemanticType?>, val declaredTargets: List<SemanticType?>,
        val expanded: Boolean, val defaults: Boolean, val generic: Boolean, val paramsType: SemanticType?,
        /** The `this` parameter of an extension method called on a receiver, substituted: the receiver is its first argument (§12.8.10.3). */
        val receiverTarget: SemanticType? = null,
        /** `ref` / `out` / `in` or null: how the parameter each argument goes to takes it. */
        val refKinds: List<String?> = emptyList(),
        /** The type arguments of a generic method, inferred or written. */
        val methodArguments: List<SemanticType?> = emptyList(),
        /** The `this` parameter as declared, not substituted: the receiver takes part in "more specific" with the other parameters. */
        val declaredReceiver: SemanticType? = null,
    )

    /**
     * For the compiler errors of [CSharpOverloadChecks]: conversions of lambdas and method groups are NONE or IMPLICIT only where that is
     * sure (the lenient answers of navigation would make an inapplicable candidate applicable there), anything else UNKNOWN.
     */
    internal var strict = false

    /** How a candidate fares with the arguments of a call ([evaluate]). */
    internal sealed class Outcome {
        class Applicable(val form: Form) : Outcome()
        /** No form takes that many arguments. */
        object Count : Outcome()
        /** A form takes them and an argument surely does not convert: [form] the one Roslyn names, [bad] the indices of those arguments. */
        class Bad(val form: Form, val bad: List<Int>, val refMismatch: Boolean) : Outcome()
        object InferenceFailed : Outcome()
        object ConstraintFailed : Outcome()
        object Unknown : Outcome()
    }

    /**
     * [symbol] against a call, strictly (§12.6.4.2): the normal form, then the expanded one; the type arguments of a generic method [written]
     * or by [infer] (the declared type each argument goes to, the receiver and the `this` parameter of a reduced call). [receiver]: the
     * type of the receiver of a reduced call of an extension method.
     */
    internal fun evaluate(
        symbol: CSharpSymbol, reduced: Boolean, receiver: SemanticType?, arguments: List<CSharpArgument>, all: List<CSharpSymbol>, written: List<SemanticType?>?,
        infer: (List<SemanticType?>, Pair<SemanticType, SemanticType?>?) -> CSharpTypeInference.Inferred,
    ): Outcome {
        val owners = all.mapNotNull { (it as? CSharpSymbol.SourceMember)?.element }
        val wasStrict = strict
        strict = true
        openOwners += owners
        twins = paramsTwins(all)
        try {
            return evaluateStrict(symbol, reduced, receiver, arguments, written, infer)
        } finally {
            strict = wasStrict
            openOwners -= owners.toSet()
        }
    }

    private fun evaluateStrict(
        symbol: CSharpSymbol, reduced: Boolean, receiver: SemanticType?, arguments: List<CSharpArgument>, written: List<SemanticType?>?,
        infer: (List<SemanticType?>, Pair<SemanticType, SemanticType?>?) -> CSharpTypeInference.Inferred,
    ): Outcome {
        val all = r.signature(symbol, false) ?: return Outcome.Unknown
        if (reduced && (all.isEmpty() || receiver == null)) return Outcome.Unknown
        val parameters = (if (reduced) all.drop(1) else all).let(::withParamsSpan)
        val generic = r.isGeneric(symbol)
        val types = arguments.map { a -> a.expression?.let(::argumentType) }
        var unknown = false
        var inferenceFailed = false
        var constraintFailed = false
        val bad = ArrayList<Outcome.Bad>()
        for (expanded in listOf(false, true)) {
            if (expanded && parameters.lastOrNull()?.isParams != true) break
            val map = map(parameters, arguments, expanded) ?: continue
            val declaredTargets = arguments.indices.map { i ->
                val declared = parameters[map[i]].type()
                if (expanded && map[i] == parameters.size - 1) declared?.let(::elementOf) else declared
            }
            var methodArguments: List<SemanticType?> = emptyList()
            if (generic) {
                methodArguments = written ?: when (val inferred = infer(declaredTargets, if (reduced) receiver!! to all.first().type() else null)) {
                    is CSharpTypeInference.Inferred.Known -> inferred.types
                    CSharpTypeInference.Inferred.Failed -> { inferenceFailed = true; continue }
                    CSharpTypeInference.Inferred.Unknown -> { unknown = true; continue }
                }
                when (constraintsHoldStrict(symbol, methodArguments)) {
                    false -> { constraintFailed = true; continue }
                    null -> { unknown = true; continue }
                    true -> {}
                }
            }
            fun substituted(type: SemanticType?): SemanticType? = if (methodArguments.isEmpty()) type else substitute(type, symbol, methodArguments)
            var receiverTarget: SemanticType? = null
            if (reduced) {
                receiverTarget = substituted(all.first().type()) ?: return Outcome.Unknown
                if (classify(receiver!!, receiverTarget, userDefined = false) !in listOf(Conversion.IDENTITY, Conversion.IMPLICIT)) return Outcome.Unknown
            }
            val targets = declaredTargets.map(::substituted)
            val refKinds = arguments.indices.map { refKind(symbol, map[it], reduced) }
            val wrong = ArrayList<Int>()
            var refMismatch = false
            var formUnknown = false
            for ((i, argument) in arguments.withIndex()) {
                val expression = argument.expression ?: return Outcome.Unknown
                val target = targets[i] ?: if (pointerParameter(symbol, map[i], reduced) && !mayBePointer(expression)) { wrong += i; continue } else return Outcome.Unknown
                val writtenKind = argument.refKindKeyword?.text
                val kind = refKinds[i]
                val handlerByRef = kind == "ref" && writtenKind == null && unparenthesized(expression) is CSharpInterpolatedStringExpression && isHandler(target)
                if (!(writtenKind == kind || handlerByRef || kind == "in" && (writtenKind == null || writtenKind == "ref"))) {
                    wrong += i; refMismatch = true; continue
                }
                val byRef = writtenKind == "ref" || writtenKind == "out"
                val conversion = if (expression is CSharpDeclarationExpression && expression.type?.let(r::isVar) != false) Conversion.IDENTITY else argument(expression, types[i], target)
                when (conversion) {
                    Conversion.NONE -> wrong += i
                    Conversion.UNKNOWN -> formUnknown = true
                    Conversion.IMPLICIT -> if (byRef) { wrong += i; refMismatch = true }
                    Conversion.IDENTITY -> {}
                }
            }
            val defaults = parameters.indices.any { p -> p !in map && !(expanded && p == parameters.size - 1) }
            val form = Form(
                symbol, parameters, targets, declaredTargets, expanded, defaults, generic, if (expanded) parameters.last().type() else null, receiverTarget, refKinds, methodArguments,
                if (reduced) all.first().type() else null,
            )
            if (wrong.isNotEmpty()) bad += Outcome.Bad(form, wrong, refMismatch)
            else if (formUnknown) unknown = true
            else return Outcome.Applicable(form)
        }
        if (unknown) return Outcome.Unknown
        if (bad.isNotEmpty()) {
            if (inferenceFailed || constraintFailed) return Outcome.Unknown
            if (bad.size == 1) return bad.single()
            // both forms of a `params` method fail: the expanded one when the last argument is no array of it (as `checkArgumentTypes`)
            val (normal, expanded) = bad
            return if ((arguments.size - 1) in normal.bad) expanded else normal
        }
        if (constraintFailed) return Outcome.ConstraintFailed
        if (inferenceFailed) return Outcome.InferenceFailed
        return Outcome.Count
    }

    /**
     * Of the applicable [forms], those no other one is better than (§12.6.4.3): one is the best member, two or more an ambiguity; null when
     * a comparison is not known. [receiver]: of a reduced call of extension methods.
     */
    internal fun unbeaten(forms: List<Form>, arguments: List<CSharpArgument>, receiver: SemanticType?): List<Form>? {
        val owners = forms.mapNotNull { (it.symbol as? CSharpSymbol.SourceMember)?.element }
        val wasStrict = strict
        strict = true
        openOwners += owners
        try {
            val types = arguments.map { a -> a.expression?.let(::argumentType) }
            val beaten = HashSet<Form>()
            for (m in forms) for (n in forms) {
                if (m !== n && (betterMember(m, n, arguments, types, receiver) ?: return null)) beaten += n
            }
            return forms.filter { it !in beaten }
        } finally {
            strict = wasStrict
            openOwners -= owners.toSet()
        }
    }

    /**
     * The one candidate of [candidates] a call with [arguments] calls, or null when that cannot be told surely. [reduced]: extension methods
     * called on a receiver; [site]: the name called (null for a constructor).
     */
    fun resolve(candidates: List<CSharpSymbol>, arguments: List<CSharpArgument>, reduced: Boolean, site: CSharpSimpleName?, lambdas: Boolean = false): CSharpSymbol? {
        if (candidates.size < 2) return null
        val call = site?.let(r::invocationOf)
        val types = arguments.map { a -> a.expression?.let(::argumentType) }
        val receiver = if (reduced) site?.let(r.expressions::receiver) else null
        val owners = candidates.mapNotNull { (it as? CSharpSymbol.SourceMember)?.element }
        openOwners += owners
        try {
            val applicable = ArrayList<Form>()
            val ownTwins = paramsTwins(candidates)
            for (symbol in candidates) {
                val isReduced = reduced && r.isExtension(symbol)
                twins = ownTwins
                val form = applicable(symbol, isReduced, arguments, types, call, site, lambdas) ?: continue
                if (form === UNKNOWN_FORM) {
                    SemanticTrace.trace { "resolve ${site?.text} at ${SemanticTrace.at(site)}: gave up, the applicability of ${SemanticTrace.show(symbol)} is not known" }
                    return null
                }
                applicable += form
            }
            if (applicable.size == 1) return applicable.single().symbol
            if (applicable.isEmpty()) return null
            val pool = if (applicable.any { hasPriority(it.symbol) } && applicable.any { !hasPriority(it.symbol) }) applicable.filter { !hasPriority(it.symbol) } else applicable
            if (pool.size == 1) return pool.single().symbol
            var best: Form? = null
            for (m in pool) {
                if (pool.all { n -> n === m || betterMember(m, n, arguments, types, receiver) == true }) {
                    if (best != null) {
                        val first = best
                        SemanticTrace.trace { "resolve ${site?.text} at ${SemanticTrace.at(site)}: gave up, ${SemanticTrace.show(first.symbol)} and ${SemanticTrace.show(m.symbol)} both unbeaten" }
                        return null
                    }
                    best = m
                }
            }
            if (best == null) SemanticTrace.trace {
                // which comparisons did not answer: the case is told by them (betterMember is asked again only when the log is on)
                val unknown = pool.flatMap { m -> pool.filter { n -> n !== m && betterMember(m, n, arguments, types, receiver) == null }.map { n -> m to n } }
                    .joinToString { (m, n) -> "${SemanticTrace.show(m.symbol)}(${m.targets.joinToString { p -> SemanticTrace.show(p) }})<${m.methodArguments.joinToString { p -> SemanticTrace.show(p) }}> vs ${SemanticTrace.show(n.symbol)}(${n.targets.joinToString { p -> SemanticTrace.show(p) }})<${n.methodArguments.joinToString { p -> SemanticTrace.show(p) }}>" }
                "resolve ${site?.text} at ${SemanticTrace.at(site)}: gave up, no best of [${pool.joinToString { SemanticTrace.show(it.symbol) }}]; not known: [$unknown]"
            }
            return best?.symbol
        } finally {
            openOwners -= owners.toSet()
        }
    }

    /** Whether every hole of [e] is a constant string (a literal, `nameof`); false when one surely is not (not a string); null when not known. */
    internal fun constantInterpolation(e: CSharpInterpolatedStringExpression): Boolean? {
        var known = true
        for (part in e.contents) {
            val hole = part as? CSharpInterpolation ?: continue
            if (hole.alignmentClause != null || hole.formatClause != null) return false
            val value = hole.expression?.let(::unparenthesized) ?: return null
            if (value is CSharpLiteralExpression && value.text.let { it.startsWith("\"") || it.startsWith("@\"") }) continue
            if (value is CSharpInvocationExpression && value.expression?.text == "nameof") continue
            // a call, an element access, a creation is never a constant, whatever it returns (`$"M {ToS(x)}"` of MudBlazor's charts)
            if (value is CSharpInvocationExpression || value is CSharpElementAccessExpression || value is CSharpBaseObjectCreationExpression) return false
            val type = r.typeOf(value)
            if (type == null) known = false else if (r.definitionName(type) != STRING) return false else known = false
        }
        return if (known) true else null
    }

    private fun hasPriority(symbol: CSharpSymbol): Boolean =
        symbol is CSharpSymbol.LibraryMember && symbol.member.attributes.any { it == "System.Runtime.CompilerServices.OverloadResolutionPriorityAttribute" }

    private fun argumentType(e: CSharpExpression): SemanticType? {
        val value = unparenthesized(e)
        return when {
            value is CSharpAnonymousFunctionExpression -> null
            value is CSharpDeclarationExpression && value.type?.let(r::isVar) != false -> null
            else -> r.typeOf(value)
        }
    }

    private val UNKNOWN_FORM = Form(CSharpSymbol.Namespace(""), emptyList(), emptyList(), emptyList(), false, false, false, null)

    /** [symbol] as applicable to the call (normal form first, then expanded); null when surely not; [UNKNOWN_FORM] when that is not known. */
    private fun applicable(
        symbol: CSharpSymbol, reduced: Boolean, arguments: List<CSharpArgument>, types: List<SemanticType?>, call: CSharpInvocationExpression?,
        site: CSharpSimpleName?, lambdas: Boolean,
    ): Form? {
        val all = r.signature(symbol, false) ?: return UNKNOWN_FORM
        if (reduced && all.isEmpty()) return null
        val parameters = (if (reduced) all.drop(1) else all).let(::withParamsSpan)
        val generic = r.isGeneric(symbol)
        val methodArguments: List<SemanticType?> = if (generic && call != null && site != null) r.expressions.typeArguments(symbol, call, site, withLambdas = lambdas) else emptyList()
        // a candidate that does not apply is a decision, not a refusal: not logged
        if (generic && !constraintsHold(symbol, methodArguments)) return null
        // a type parameter the lenient inference left open: when Roslyn's inference surely fails, the candidate is not applicable
        // (`Should<TCollection, TKey, TValue>(this TCollection)` of an `IDictionary` infers no TKey — AwesomeAssertions in MudBlazor's tests)
        if (generic && call != null && site != null && methodArguments.any { it == null } && inferenceFails(symbol, all, parameters, arguments, reduced, call, site)) return null
        fun substituted(type: SemanticType?): SemanticType? = if (methodArguments.isEmpty()) type else substitute(type, symbol, methodArguments)
        var receiverTarget: SemanticType? = null
        if (reduced) {
            val receiver = site?.let(r.expressions::receiver) ?: return UNKNOWN_FORM
            val self = substituted(all.first().type()) ?: return UNKNOWN_FORM
            receiverTarget = self
            // the receiver of an extension method: identity, reference or boxing conversions only
            when (classify(receiver, self, userDefined = false)) {
                Conversion.NONE -> return null
                Conversion.UNKNOWN -> {}
                else -> {}
            }
        }
        var unknown = false
        var why = ""
        for (expanded in listOf(false, true)) {
            if (expanded && parameters.lastOrNull()?.isParams != true) break
            val map = map(parameters, arguments, expanded) ?: continue
            val declaredTargets = ArrayList<SemanticType?>()
            val targets = ArrayList<SemanticType?>()
            var fits = true
            for ((i, argument) in arguments.withIndex()) {
                val index = map[i]
                val parameter = parameters[index]
                var declared = parameter.type()
                if (expanded && index == parameters.size - 1) declared = declared?.let(::elementOf)
                val target = substituted(declared)
                declaredTargets += declared
                targets += target
                val expression = argument.expression ?: continue
                if (target == null && pointerParameter(symbol, index, reduced) && !mayBePointer(expression)) { fits = false; break }
                if (target == null) { unknown = true; why = "the type of parameter ${index + 1} is not known"; continue }
                val written = argument.refKindKeyword?.text
                val kind = refKind(symbol, index, reduced)
                // `in` parameters take an argument with `in`, `ref` or nothing; `ref` / `out` only their own keyword
                // a `ref` interpolated string handler (`Debug.Assert(bool, ref AssertInterpolatedStringHandler)`) takes `$"..."` as it is
                val handlerByRef = kind == "ref" && written == null && unparenthesized(expression) is CSharpInterpolatedStringExpression && isHandler(target)
                if (!(written == kind || handlerByRef || kind == "in" && (written == null || written == "ref"))) { fits = false; break }
                val byRef = written == "ref" || written == "out"
                val conversion = if (expression is CSharpDeclarationExpression && expression.type?.let(r::isVar) != false) Conversion.IDENTITY else argument(expression, types[i], target)
                when (conversion) {
                    Conversion.NONE -> { fits = false; break }
                    Conversion.UNKNOWN -> { unknown = true; why = "argument ${i + 1} `${SemanticTrace.text(expression)}` of ${SemanticTrace.show(types[i])} to ${SemanticTrace.show(target)}: conversion not known" }
                    Conversion.IMPLICIT -> if (byRef) { fits = false; break }
                    Conversion.IDENTITY -> {}
                }
            }
            if (!fits) continue
            if (unknown) {
                SemanticTrace.trace { "applicable ${site?.text} at ${SemanticTrace.at(site)}: ${SemanticTrace.show(symbol)} not known — $why" }
                return UNKNOWN_FORM
            }
            val defaults = parameters.indices.any { p -> p !in map && !(expanded && p == parameters.size - 1) }
            return Form(symbol, parameters, targets, declaredTargets, expanded, defaults, generic, if (expanded) parameters.last().type() else null, receiverTarget,
                declaredReceiver = if (reduced) all.first().type() else null)
        }
        if (unknown) SemanticTrace.trace { "applicable ${site?.text} at ${SemanticTrace.at(site)}: ${SemanticTrace.show(symbol)} not known — $why" }
        return if (unknown) UNKNOWN_FORM else null
    }

    private val inference get() = r.inference

    /**
     * Whether the strict inference ([CSharpTypeInference], Roslyn's) surely fails for [symbol] in its normal form — a form with `params`
     * may infer from the expanded one, so it is not asked then. [all]: the declared parameters with the receiver of a reduced call.
     */
    private fun inferenceFails(
        symbol: CSharpSymbol, all: List<CSharpNameResolver.Parameter>, parameters: List<CSharpNameResolver.Parameter>, arguments: List<CSharpArgument>,
        reduced: Boolean, call: CSharpInvocationExpression, site: CSharpSimpleName,
    ): Boolean {
        if (parameters.lastOrNull()?.isParams == true) return false
        val map = map(parameters, arguments, false) ?: return false
        val targets = arguments.indices.map { i -> parameters[map[i]].type() }
        val self = if (reduced) (r.expressions.receiver(site) ?: return false) to all.first().type() else null
        return inference.infer(symbol, targets, arguments, self, call, site) == CSharpTypeInference.Inferred.Failed
    }

    // the `params` arrays of the candidates by (parameter count, name of the last one)
    private var twins: Set<Pair<Int, String>> = emptySet()

    private fun paramsTwins(candidates: List<CSharpSymbol>): Set<Pair<Int, String>> = candidates.mapNotNullTo(HashSet()) { symbol ->
        if (symbol !is CSharpSymbol.LibraryMember) return@mapNotNullTo null
        val last = r.session.parameters(symbol.member).lastOrNull()?.takeIf { it.isParams } ?: return@mapNotNullTo null
        r.session.parameters(symbol.member).size to last.name
    }

    /**
     * C# 13 `params` collections: the index marks `params` arrays only (`ParamArrayAttribute`), not `ParamCollectionAttribute`. The BCL
     * gives every `params T[]` overload a `params ReadOnlySpan<T>` twin of the same shape: a last `ReadOnlySpan<T>` / `Span<T>` parameter
     * named as the `params` array of another candidate with as many parameters is one too.
     */
    private fun withParamsSpan(parameters: List<CSharpNameResolver.Parameter>): List<CSharpNameResolver.Parameter> {
        val last = parameters.lastOrNull() ?: return parameters
        if (last.isParams || twins.isEmpty() || (parameters.size to last.name) !in twins) return parameters
        val type = last.type() as? SemanticType.Library ?: return parameters
        if (type.type.fullName != "System.ReadOnlySpan`1" && type.type.fullName != "System.Span`1") return parameters
        return parameters.dropLast(1) + CSharpNameResolver.Parameter(last.name, last.optional, true, last.byRef, last.type)
    }

    /** `ref`, `out`, `in` (`ref readonly` too) or null: how the [index]-th parameter takes its argument. */
    private fun refKind(symbol: CSharpSymbol, index: Int, reduced: Boolean): String? {
        val at = index + if (reduced) 1 else 0
        return when (symbol) {
            is CSharpSymbol.LibraryMember -> r.session.parameters(symbol.member).getOrNull(at)?.let { p -> if (p.isOut) "out" else if (p.isIn) "in" else if (p.isRef) "ref" else null }
            is CSharpSymbol.SourceMember -> parameterList(symbol.element)?.parameters?.getOrNull(at)?.modifiers?.map { it.text }?.let { m ->
                when {
                    "out" in m -> "out"
                    "in" in m || "readonly" in m -> "in"
                    "ref" in m && "this" !in m -> "ref"
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * Whether the parameter at [index] is a pointer or a function pointer: the types the semantics does not model. Only `null`, `default` and
     * another pointer convert to one (§23.5.1), so for any other argument the candidate does not apply — `ArgumentNullException.ThrowIfNull`
     * has a `void*` overload beside the `object?` one, and "the type of parameter 1 is not known" was 1354 calls of nopCommerce (D6c2).
     */
    private fun pointerParameter(symbol: CSharpSymbol, index: Int, reduced: Boolean): Boolean {
        val at = index + if (reduced) 1 else 0
        return when (symbol) {
            is CSharpSymbol.LibraryMember -> r.session.parameters(symbol.member).getOrNull(at)?.typeRef
                .let { it is io.github.dotnetsupport.index.IndexedTypeRef.PointerTo || it is io.github.dotnetsupport.index.IndexedTypeRef.FunctionPointer }
            is CSharpSymbol.SourceMember -> parameterList(symbol.element)?.parameters?.getOrNull(at)?.type
                .let { it is CSharpPointerType || it is CSharpFunctionPointerType }
            else -> false
        }
    }

    /** An argument that may go to a pointer: `null`, `default` (and a pointer, which no expression of the modeled types is). */
    private fun mayBePointer(expression: CSharpExpression): Boolean = unparenthesized(expression).elementType.let {
        it == SyntaxKind.NullLiteralExpression || it == SyntaxKind.DefaultLiteralExpression || it == SyntaxKind.DefaultExpression
    } || unparenthesized(expression).let { it is CSharpPrefixUnaryExpression && it.operatorToken?.text == "&" }

    internal fun parameterList(element: PsiElement): CSharpParameterList? = when (element) {
        is CSharpMethodDeclaration -> element.parameterList
        is CSharpLocalFunctionStatement -> element.parameterList
        is CSharpConstructorDeclaration -> element.parameterList
        is CSharpTypeDeclaration -> element.parameterList
        else -> null
    }

    /**
     * The parameter each argument goes to; null when the arguments do not fit (§12.6.2.2, the corresponding parameters): Roslyn's
     * `AnalyzeArguments` ([RoslynArgsToParameters], D6c) — a named argument may stand for the parameter array in the expanded form, as Roslyn
     * has allowed since C# 4; why a candidate does not fit is there too, for the errors of D6c.
     */
    private fun map(parameters: List<CSharpNameResolver.Parameter>, arguments: List<CSharpArgument>, expanded: Boolean): List<Int>? {
        val names = arguments.map { it.nameColon?.nameElement?.identifier?.text }
        val analyzed = RoslynArgsToParameters.analyze(parameters.map { RoslynArgsToParameters.Parameter(it.name, it.optional) }, names, expanded)
        return (analyzed as? RoslynArgsToParameters.Result.Applicable)?.argsToParameters
    }

    /** The element type of a `params` collection: an array's element, `Span<T>` / `ReadOnlySpan<T>` / the collection interfaces' `T`. */
    private fun elementOf(type: SemanticType): SemanticType? = when (type) {
        is SemanticType.ArrayOf -> type.element
        is SemanticType.Library -> if (type.type.fullName in COLLECTION_TARGETS) type.arguments.firstOrNull() else r.elementType(type)
        else -> null
    }

    private fun substitute(type: SemanticType?, symbol: CSharpSymbol, arguments: List<SemanticType?>): SemanticType? {
        val owner = (symbol as? CSharpSymbol.SourceMember)?.element
        val names = r.typeParameterNames(owner)
        return r.replace(type) { p ->
            when {
                !p.ofMethod -> p
                symbol is CSharpSymbol.LibraryMember && p.owner == null -> arguments.getOrNull(p.index) ?: p
                owner != null && p.owner == owner -> arguments.getOrNull(names.indexOf(p.name)) ?: p
                else -> p
            }
        }
    }

    /** Whether the type arguments [arguments] of [symbol] satisfy its constraints; null when that is not known (`new()`, `unmanaged`, ...). */
    private fun constraintsHoldStrict(symbol: CSharpSymbol, arguments: List<SemanticType?>): Boolean? {
        if (!constraintsHold(symbol, arguments)) return false
        fun converts(argument: SemanticType, type: SemanticType): Boolean = classify(argument, type).let { it == Conversion.IDENTITY || it == Conversion.IMPLICIT }
        when (symbol) {
            is CSharpSymbol.LibraryMember -> for ((i, parameter) in symbol.member.typeParameters.withIndex()) {
                val argument = arguments.getOrNull(i) ?: return null
                if (parameter.isStruct && isReference(argument) != false || parameter.isClass && isReference(argument) != true) return null
                if (parameter.hasNew || parameter.isUnmanaged || parameter.allowsRefStruct) return null
                for (constraint in parameter.constraints) {
                    val type = r.fromRef(constraint, symbol.declaringArguments, arguments) ?: return null
                    if (!converts(argument, type)) return null
                }
            }
            is CSharpSymbol.SourceMember -> {
                val method = symbol.element as? CSharpMethodDeclaration ?: return null
                val resolver = (method.containingFile as? CSharpFile)?.let(r.session::reachable) ?: return null
                val names = r.typeParameterNames(method)
                for (clause in method.constraintClauses) {
                    val argument = arguments.getOrNull(names.indexOf(clause.nameElement?.identifier?.text)) ?: return null
                    for (constraint in clause.constraints) {
                        when (constraint) {
                            is CSharpClassOrStructConstraint -> {
                                val struct = constraint.text.startsWith("struct")
                                if (struct && isReference(argument) != false || !struct && isReference(argument) != true) return null
                                if (constraint.text.endsWith("?") || struct && r.isNullable(argument)) return null
                            }
                            is CSharpTypeConstraint -> {
                                val type = constraint.type?.let(resolver::resolveType)?.let { substitute(it, symbol, arguments) } ?: return null
                                if (constraint.text == "notnull" || constraint.text == "unmanaged" || !converts(argument, type)) return null
                            }
                            else -> return null
                        }
                    }
                }
            }
            else -> return null
        }
        return true
    }

    /** Whether the type arguments inferred for [symbol] may satisfy its constraints: false only when one surely does not. */
    private fun constraintsHold(symbol: CSharpSymbol, arguments: List<SemanticType?>): Boolean {
        when (symbol) {
            is CSharpSymbol.LibraryMember -> {
                for ((i, parameter) in symbol.member.typeParameters.withIndex()) {
                    val argument = arguments.getOrNull(i) ?: continue
                    if (parameter.isStruct && (isReference(argument) == true || r.isNullable(argument))) return false
                    if (parameter.isClass && isReference(argument) == false) return false
                    for (constraint in parameter.constraints) {
                        val type = r.fromRef(constraint, symbol.declaringArguments, arguments) ?: continue
                        if (classify(argument, type) == Conversion.NONE) return false
                    }
                }
            }
            is CSharpSymbol.SourceMember -> {
                val method = symbol.element as? CSharpMethodDeclaration ?: return true
                val resolver = (method.containingFile as? CSharpFile)?.let(r.session::reachable) ?: return true
                val names = r.typeParameterNames(method)
                for (clause in method.constraintClauses) {
                    val argument = arguments.getOrNull(names.indexOf(clause.nameElement?.identifier?.text)) ?: continue
                    for (constraint in clause.constraints) {
                        when (constraint) {
                            is CSharpClassOrStructConstraint -> {
                                if (constraint.text.startsWith("struct") && (isReference(argument) == true || r.isNullable(argument))) return false
                                if (constraint.text.startsWith("class") && isReference(argument) == false) return false
                            }
                            is CSharpTypeConstraint -> {
                                val type = constraint.type?.let(resolver::resolveType)?.let { substitute(it, symbol, arguments) } ?: continue
                                if (classify(argument, type) == Conversion.NONE) return false
                            }
                        }
                    }
                }
            }
            else -> {}
        }
        return true
    }

    /** Whether [symbol] is static: what a type, not a value, reaches. */
    fun isStatic(symbol: CSharpSymbol): Boolean? = when (symbol) {
        is CSharpSymbol.LibraryMember -> symbol.member.isStatic || symbol.member.kind == IndexedMemberKind.CONSTANT || symbol.member.kind == IndexedMemberKind.ENUM_MEMBER
        is CSharpSymbol.SourceMember -> when (val element = symbol.element) {
            is CSharpEnumMemberDeclaration -> true
            is CSharpMemberDeclaration -> element.modifiers.any { it.text == "static" || it.text == "const" }
            is CSharpVariableDeclarator -> (element.parent?.parent as? CSharpBaseFieldDeclaration)?.modifiers?.any { it.text == "static" || it.text == "const" }
            else -> null
        }
        else -> null
    }

    companion object {
        internal const val OBJECT = "System.Object"
        internal const val STRING = "System.String"
        internal const val VOID = "System.Void"
        internal const val NULLABLE = "System.Nullable`1"
        internal val TASKS_OF = setOf("System.Threading.Tasks.Task`1", "System.Threading.Tasks.ValueTask`1")
        internal const val VALUE_TUPLE = "System.ValueTuple`"

        /** The non-delegate types a lambda converts to by its natural type (C# 10) and by nothing else. */
        val NATURAL_TARGETS = setOf(
            "System.Delegate", "System.MulticastDelegate", OBJECT, "System.Linq.Expressions.Expression", "System.Linq.Expressions.LambdaExpression",
        )

        internal val PRIMITIVES = setOf(
            "System.Boolean", "System.Byte", "System.SByte", "System.Char", "System.Int16", "System.UInt16", "System.Int32", "System.UInt32", "System.Int64",
            "System.UInt64", "System.Single", "System.Double", "System.Decimal", "System.String",
        )

        val COLLECTION_TARGETS = setOf(
            "System.Span`1", "System.ReadOnlySpan`1", "System.Collections.Generic.IEnumerable`1", "System.Collections.Generic.IReadOnlyCollection`1",
            "System.Collections.Generic.IReadOnlyList`1", "System.Collections.Generic.ICollection`1", "System.Collections.Generic.IList`1",
            "System.Collections.Generic.List`1",
        )

        /** C# §10.2.3, the implicit numeric conversions (`nint` / `nuint` included). */
        internal val GENERIC_TASKS = setOf("System.Threading.Tasks.Task`1", "System.Threading.Tasks.ValueTask`1")

        internal val NUMERIC: Map<String, Set<String>> = run {
            fun s(vararg names: String) = names.map { "System.$it" }.toSet()
            mapOf(
                "System.SByte" to s("Int16", "Int32", "Int64", "Single", "Double", "Decimal", "IntPtr"),
                "System.Byte" to s("Int16", "UInt16", "Int32", "UInt32", "Int64", "UInt64", "Single", "Double", "Decimal", "IntPtr", "UIntPtr"),
                "System.Int16" to s("Int32", "Int64", "Single", "Double", "Decimal", "IntPtr"),
                "System.UInt16" to s("Int32", "UInt32", "Int64", "UInt64", "Single", "Double", "Decimal", "IntPtr", "UIntPtr"),
                "System.Int32" to s("Int64", "Single", "Double", "Decimal", "IntPtr"),
                "System.UInt32" to s("Int64", "UInt64", "Single", "Double", "Decimal", "UIntPtr"),
                "System.Int64" to s("Single", "Double", "Decimal"),
                "System.UInt64" to s("Single", "Double", "Decimal"),
                "System.Char" to s("UInt16", "Int32", "UInt32", "Int64", "UInt64", "Single", "Double", "Decimal", "IntPtr", "UIntPtr"),
                "System.Single" to s("Double"),
                "System.IntPtr" to s("Int64", "Single", "Double", "Decimal"),
                "System.UIntPtr" to s("UInt64", "Single", "Double", "Decimal"),
            )
        }

        /** §12.6.4.7: a signed integral type is a better target than the unsigned ones it does not convert to. */
        internal val SIGNED_OVER: Map<String, Set<String>> = run {
            fun s(vararg names: String) = names.map { "System.$it" }.toSet()
            mapOf(
                "System.SByte" to s("Byte", "UInt16", "UInt32", "UInt64"),
                "System.Int16" to s("UInt16", "UInt32", "UInt64"),
                "System.Int32" to s("UInt32", "UInt64"),
                "System.Int64" to s("UInt64"),
            )
        }
    }
}
