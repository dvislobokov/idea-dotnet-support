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
    internal val roslynTypes by lazy { RoslynTypes(r) }
    internal val expressionConversions by lazy { io.github.dotnetsupport.lang.semantic.roslyn.RoslynExpressionConversions(roslynTypes) }
    internal val betterFunctionMember by lazy { io.github.dotnetsupport.lang.semantic.roslyn.RoslynBetterFunctionMember(roslynTypes, BetternessHost(this)) }
    internal val methodGroupConversion by lazy { io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodGroupConversion(roslynTypes, betterFunctionMember) }
    internal val roslynConversions by lazy { RoslynConversions(RoslynTypes(r)) { operators(it, withBases = false) } }

    // the method type parameters of the candidates being resolved: a parameter of the caller's own method is a type like any other
    internal val openOwners = HashSet<PsiElement>()
    // the method (or local function) the call is written in: its own type parameters are concrete types there, never open — even when
    // it is a candidate itself (an overload calling its sibling, `GetAsync<T>(key, Func<T>)` calling `GetAsync(key, () => Task.FromResult(acquire()))`)
    internal var siteOwner: PsiElement? = null

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
        /** A type argument was inferred from the function type of a lambda or a method group (`HasTypeArgumentInferredFromFunctionType`). */
        val inferredFromFunctionType: Boolean = false,
    )

    /** How a candidate fares with the arguments of a call ([evaluate]). */
    internal sealed class Outcome {
        class Applicable(val form: Form) : Outcome()
        /** No form takes that many arguments. */
        object Count : Outcome()
        /** A form takes them and an argument surely does not convert: [form] the one Roslyn names, [bad] the indices of those arguments. */
        class Bad(val form: Form, val bad: List<Int>, val refMismatch: Boolean) : Outcome()
        object InferenceFailed : Outcome()
        object ConstraintFailed : Outcome()
        /** The receiver of an extension method has no identity, reference or boxing conversion to its `this` parameter. */
        object ReceiverFailed : Outcome()
        object Unknown : Outcome()
    }

    /**
     * [symbol] against a call, strictly (§12.6.4.2): the normal form, then the expanded one; the type arguments of a generic method [written]
     * or by [infer] (the declared type each argument goes to, the receiver and the `this` parameter of a reduced call). [receiver]: the
     * type of the receiver of a reduced call of an extension method.
     */
    internal fun evaluate(
        symbol: CSharpSymbol, reduced: Boolean, receiver: SemanticType?, arguments: List<CSharpArgument>, all: List<CSharpSymbol>, written: List<SemanticType?>?,
        site: CSharpSimpleName? = null, infer: (List<SemanticType?>, Pair<SemanticType, SemanticType?>?) -> CSharpTypeInference.Inferred,
    ): Outcome {
        // type arguments written close every candidate's type parameters: a type parameter left in a target is the caller's own (eShop's
        // `AddMigration<TContext>(services)` calling `services.AddMigration<TContext>((_, _) => …)` — the caller is a candidate too)
        val owners = if (written != null) emptyList() else all.mapNotNull { (it as? CSharpSymbol.SourceMember)?.element }
        openOwners += owners
        twins = paramsTwins(all)
        val previousSiteOwner = siteOwner
        siteOwner = site?.let { enclosingFunction(it) }
        try {
            why = ""
            val outcome = evaluateStrict(symbol, reduced, receiver, arguments, written, infer)
            // a reason another line already gives (the inference's) is not repeated
            if (outcome === Outcome.Unknown && why != ECHO) SemanticTrace.trace { "applicable ${site?.text} at ${SemanticTrace.at(site ?: arguments.firstOrNull())}: ${SemanticTrace.show(symbol)} not known — ${why.ifEmpty { "no reason given" }}" }
            return outcome
        } finally {
            openOwners -= owners.toSet()
            siteOwner = previousSiteOwner
        }
    }

    // why the candidate [evaluateStrict] judges last is not known, for the trace
    private var why = ""

    private fun unknown(reason: String): Outcome { why = reason; return Outcome.Unknown }

    private val ECHO = "(see the `inference` line)"

    private fun evaluateStrict(
        symbol: CSharpSymbol, reduced: Boolean, receiver: SemanticType?, arguments: List<CSharpArgument>, written: List<SemanticType?>?,
        infer: (List<SemanticType?>, Pair<SemanticType, SemanticType?>?) -> CSharpTypeInference.Inferred,
    ): Outcome {
        val all = r.signature(symbol, false) ?: return unknown("the signature is not known")
        if (reduced && (all.isEmpty() || receiver == null)) return unknown("the receiver of the extension method is not known")
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
            var fromFunctionType = false
            if (generic) {
                methodArguments = written ?: when (val inferred = infer(declaredTargets, if (reduced) receiver!! to all.first().type() else null)) {
                    is CSharpTypeInference.Inferred.Known -> { fromFunctionType = inferred.fromFunctionType; inferred.types }
                    CSharpTypeInference.Inferred.Failed -> { inferenceFailed = true; continue }
                    CSharpTypeInference.Inferred.Unknown -> { unknown = true; why = ECHO; continue }
                }
                when (constraintsHoldStrict(symbol, methodArguments)) {
                    false -> { constraintFailed = true; continue }
                    null -> { unknown = true; why = "whether the constraints hold for [${methodArguments.joinToString { SemanticTrace.show(it) }}] is not known"; continue }
                    true -> {}
                }
            }
            fun substituted(type: SemanticType?): SemanticType? = if (methodArguments.isEmpty()) type else substitute(type, symbol, methodArguments)
            var receiverTarget: SemanticType? = null
            if (reduced) {
                receiverTarget = substituted(all.first().type()) ?: return unknown("the type of the `this` parameter is not known")
                // the receiver of an extension method: identity, reference or boxing conversions only
                when (classify(receiver!!, receiverTarget, userDefined = false)) {
                    Conversion.NONE -> return Outcome.ReceiverFailed
                    Conversion.UNKNOWN -> return unknown("the conversion of the receiver ${SemanticTrace.show(receiver)} to ${SemanticTrace.show(receiverTarget)} is not known")
                    else -> {}
                }
            }
            val targets = declaredTargets.map(::substituted)
            val refKinds = arguments.indices.map { refKind(symbol, map[it], reduced) }
            val wrong = ArrayList<Int>()
            var refMismatch = false
            var formUnknown = false
            for ((i, argument) in arguments.withIndex()) {
                val expression = argument.expression ?: return unknown("argument ${i + 1} is missing")
                val target = targets[i] ?: if (pointerParameter(symbol, map[i], reduced) && !mayBePointer(expression)) { wrong += i; continue }
                    else return unknown("the type of parameter ${map[i] + 1} is not known")
                val writtenKind = argument.refKindKeyword?.text
                val kind = refKinds[i]
                val handlerByRef = kind == "ref" && writtenKind == null && unparenthesized(expression) is CSharpInterpolatedStringExpression && isHandler(target)
                if (!(writtenKind == kind || handlerByRef || kind == "in" && (writtenKind == null || writtenKind == "ref"))) {
                    wrong += i; refMismatch = true; continue
                }
                val byRef = writtenKind == "ref" || writtenKind == "out"
                // `out var x` and the discard `out _` take the parameter's type, whatever it is
                val conversion = if (expression is CSharpDeclarationExpression && expression.type?.let(r::isVar) != false || writtenKind == "out" && isDiscard(expression)) Conversion.IDENTITY
                    else argument(expression, types[i], target)
                when (conversion) {
                    Conversion.NONE -> wrong += i
                    // an argument whose own type is not known is told once, not once for each parameter type it is tried against
                    Conversion.UNKNOWN -> { formUnknown = true; why = if (types[i] == null && !targetTyped(expression)) "the type of argument ${i + 1} `${SemanticTrace.text(expression)}` is not known"
                        else "argument ${i + 1} `${SemanticTrace.text(expression)}` of ${SemanticTrace.show(types[i])} to ${SemanticTrace.show(target)}: conversion not known" }
                    Conversion.IMPLICIT -> if (byRef) { wrong += i; refMismatch = true }
                    Conversion.IDENTITY -> {}
                }
            }
            val defaults = parameters.indices.any { p -> p !in map && !(expanded && p == parameters.size - 1) }
            val form = Form(
                symbol, parameters, targets, declaredTargets, expanded, defaults, generic, if (expanded) parameters.last().type() else null, receiverTarget, refKinds, methodArguments,
                if (reduced) all.first().type() else null, fromFunctionType,
            )
            if (wrong.isNotEmpty()) bad += Outcome.Bad(form, wrong, refMismatch)
            else if (formUnknown) unknown = true
            else return Outcome.Applicable(form)
        }
        if (unknown) return Outcome.Unknown
        if (bad.isNotEmpty()) {
            // one form does not convert an argument, the other failed its inference or constraints: the candidate is not applicable either way
            // (nopCommerce's `e.Id.In(entities.Select(x => x.Id))` against `In<T>(this T, params T[])`)
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
    internal fun unbeaten(forms: List<Form>, arguments: List<CSharpArgument>, receiver: SemanticType?, site: CSharpSimpleName?): List<Form>? {
        val owners = forms.mapNotNull { (it.symbol as? CSharpSymbol.SourceMember)?.element }
        openOwners += owners
        try {
            val beaten = HashSet<Form>()
            for (m in forms) for (n in forms) {
                if (m !== n && (betterMember(m, n, arguments, receiver, site) ?: return null)) beaten += n
            }
            return forms.filter { it !in beaten }
        } finally {
            openOwners -= owners.toSet()
        }
    }

    /**
     * The one candidate of [candidates] a call with [arguments] calls, or null when that cannot be told surely. [reduced]: extension methods
     * called on a receiver; [site]: the name called (null for a constructor).
     */
    fun resolve(all: List<CSharpSymbol>, arguments: List<CSharpArgument>, reduced: Boolean, site: CSharpSimpleName?): CSharpSymbol? {
        if (all.size < 2) return null
        val candidates = withoutBuiltTwins(all)
        // a member and its built twin: the source one is the call
        if (candidates.size < 2) return candidates.singleOrNull()
        val call = site?.let(r::invocationOf)
        val types = arguments.map { a -> a.expression?.let(::argumentType) }
        val receiver = if (reduced) site?.let(r.expressions::receiver) else null
        val written = (site as? CSharpGenericName)?.typeArgumentList?.arguments?.map { r.resolveType(it) ?: return null }
        val owners = if (written != null) emptyList() else candidates.mapNotNull { (it as? CSharpSymbol.SourceMember)?.element }
        openOwners += owners
        val previousSiteOwner = siteOwner
        siteOwner = site?.let { enclosingFunction(it) }
        try {
            // the applicability of each candidate as the compiler errors judge it (D6c3): Roslyn's inference, the port's conversions
            val applicable = ArrayList<Form>()
            val bad = ArrayList<Outcome.Bad>()
            val unknown = ArrayList<CSharpSymbol>()
            for (symbol in candidates) {
                val isReduced = reduced && r.isExtension(symbol)
                val outcome = evaluate(symbol, isReduced, receiver, arguments, candidates, written, site) { targets, self ->
                    if (call == null || site == null) CSharpTypeInference.Inferred.Unknown else inference.infer(symbol, targets, arguments, self, call, site)
                }
                when (outcome) {
                    is Outcome.Applicable -> applicable += outcome.form
                    // the `applicable` line of evaluate says why: no echo here
                    Outcome.Unknown -> unknown += symbol
                    is Outcome.Bad -> bad += outcome
                    else -> {}
                }
            }
            // a candidate not known beside applicable ones: no telling; alone among candidates that surely do not apply, it is the call
            // (`Select(x => x.Nope)`: the body of the lambda is not known, the overload with the index never takes a one-parameter lambda)
            if (unknown.isNotEmpty()) return if (applicable.isEmpty() && unknown.size == 1) unknown.single() else null
            if (applicable.size == 1) return applicable.single().symbol
            // no candidate applies: the call is the one whose lambdas bind to its delegates with their errors (`Select(x => { if (…) return 1; })`
            // is the `Func<T, R>` overload with CS1643, never the `Func<T, int, R>` one) — the failed candidate Roslyn reports and navigates to
            if (applicable.isEmpty()) return bad.filter { b -> b.bad.all { i -> lambdaBindsTo(arguments[i], b.form.targets[i]) } }.singleOrNull()?.form?.symbol
            // §12.8.10.3: the extension methods are candidates only when no instance method applies (Roslyn looks them up only then)
            if (applicable.any { it.receiverTarget == null } && applicable.any { it.receiverTarget != null }) applicable.removeAll { it.receiverTarget != null }
            if (applicable.size == 1) return applicable.single().symbol
            val pool = if (applicable.any { hasPriority(it.symbol) } && applicable.any { !hasPriority(it.symbol) }) applicable.filter { !hasPriority(it.symbol) } else applicable
            if (pool.size == 1) return pool.single().symbol
            var best: Form? = null
            for (m in pool) {
                if (pool.all { n -> n === m || betterMember(m, n, arguments, receiver, site) == true }) {
                    if (best != null) {
                        val first = best
                        SemanticTrace.trace { "resolve ${site?.text} at ${SemanticTrace.at(site)}: gave up, ${SemanticTrace.show(first.symbol)} and ${SemanticTrace.show(m.symbol)} both unbeaten" }
                        return null
                    }
                    best = m
                }
            }
            // a comparison not known wrote its `better` line; every comparison known and still no best is an ambiguity Roslyn would report
            if (best == null && pool.all { m -> pool.all { n -> n === m || betterMember(m, n, arguments, receiver, site) != null } }) SemanticTrace.trace {
                "resolve ${site?.text} at ${SemanticTrace.at(site)}: no best of [${pool.joinToString { m -> "${SemanticTrace.show(m.symbol)}(${m.targets.joinToString { p -> SemanticTrace.show(p) }})" }}]"
            }
            return best?.symbol
        } finally {
            openOwners -= owners.toSet()
            siteOwner = previousSiteOwner
        }
    }

    /**
     * Whether some of [all] is applicable to the call at [site] (§12.6.4.2): true, false, or null when none surely is but one is not known.
     * For the extension method lookup level by level (§12.8.10.3): the innermost namespace with an applicable candidate wins —
     * nopCommerce's `ProductExtensions.OrderBy(this IQueryable<Product>, …)` in the file's own namespace is no match for `query.OrderBy(x => …)`
     * over `IQueryable<ReviewType>`, System.Linq's is.
     */
    fun anyApplicable(all: List<CSharpSymbol>, arguments: List<CSharpArgument>, reduced: Boolean, site: CSharpSimpleName?): Boolean? {
        val candidates = withoutBuiltTwins(all)
        val call = site?.let(r::invocationOf)
        val receiver = if (reduced) site?.let(r.expressions::receiver) else null
        val written = (site as? CSharpGenericName)?.typeArgumentList?.arguments?.map { r.resolveType(it) ?: return null }
        val owners = if (written != null) emptyList() else candidates.mapNotNull { (it as? CSharpSymbol.SourceMember)?.element }
        openOwners += owners
        val previousSiteOwner = siteOwner
        siteOwner = site?.let { enclosingFunction(it) }
        try {
            var unknown = false
            for (symbol in candidates) {
                val isReduced = reduced && r.isExtension(symbol)
                val outcome = evaluate(symbol, isReduced, receiver, arguments, candidates, written, site) { targets, self ->
                    if (call == null || site == null) CSharpTypeInference.Inferred.Unknown else inference.infer(symbol, targets, arguments, self, call, site)
                }
                when (outcome) {
                    is Outcome.Applicable -> return true
                    Outcome.Unknown -> unknown = true
                    else -> {}
                }
            }
            return if (unknown) null else false
        } finally {
            openOwners -= owners.toSet()
            siteOwner = previousSiteOwner
        }
    }

    /** `_` where no variable of that name is in scope: a discard (`TryCreate(x, out _)`). */
    internal fun isDiscard(expression: CSharpExpression): Boolean {
        val leaf = (unparenthesized(expression) as? CSharpIdentifierName)?.identifier ?: return false
        return leaf.text == "_" && r.syntax.symbolAt(leaf) == null && r.resolve(leaf) == null
    }

    /** [argument] is a lambda and [target] a delegate type with as many parameters: the lambda binds to it, with the errors of its body. */
    private fun lambdaBindsTo(argument: CSharpArgument, target: SemanticType?): Boolean {
        val lambda = argument.expression as? CSharpAnonymousFunctionExpression ?: return false
        val delegate = target?.let(r.expressions::unwrapExpression)?.let(r.expressions::delegateSignature) ?: return false
        val count = r.expressions.lambdaParameterCount(lambda) ?: return true
        return count == delegate.first.size
    }

    /**
     * The candidates without the members of a built project that stand beside their own source (the IDE indexes the outputs of the referenced
     * projects too, `ProjectAssemblies.References.forIndex`): Roslyn sees a project reference as its compilation, so the source member is the
     * one. Two such twins are never better than each other — MudBlazor's `Trimmed`, `ToPascalCase`, nopCommerce's `ToPagedList` ended "no
     * best" with every comparison known (D6c). A member only in the built output (a generated part not seen yet) stays.
     */
    private fun withoutBuiltTwins(candidates: List<CSharpSymbol>): List<CSharpSymbol> {
        if (candidates.none { it is CSharpSymbol.SourceMember } || candidates.none { it is CSharpSymbol.LibraryMember }) return candidates
        val sources = candidates.filterIsInstance<CSharpSymbol.SourceMember>().mapNotNullTo(HashSet(), ::twinKey)
        return candidates.filter { it !is CSharpSymbol.LibraryMember || twinKey(it) !in sources }
    }

    /** What a member and its built twin share: the type it is declared in, its name, its type parameters, the names of its parameters. */
    private fun twinKey(symbol: CSharpSymbol): List<Any>? = when (symbol) {
        is CSharpSymbol.LibraryMember -> listOf(symbol.member.type.qualifiedName, symbol.member.name, symbol.member.arity, r.session.parameters(symbol.member).map { it.name })
        is CSharpSymbol.SourceMember -> {
            val element = symbol.element
            val owner = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, CSharpBaseTypeDeclaration::class.java)
            val ownerName = owner?.let(r.syntax::qualifiedName)
            val name = CSharpDeclarationNames.nameElement(element)?.text
            if (ownerName == null || name == null) null
            else listOf(ownerName, name, (element as? CSharpMethodDeclaration)?.typeParameterList?.parameters?.size ?: 0,
                parameterList(element)?.parameters?.map { it.identifier?.text.orEmpty() }.orEmpty())
        }
        else -> null
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
            // a `const string` (a field, a local, a constant of an assembly) keeps it constant; any other constant (an int) does not
            val constant = r.expressions.constants.of(value)
            if (constant != null) { if (constant.kind == CSharpConstantValues.Kind.STRING || constant.kind == CSharpConstantValues.Kind.NULL) continue else return false }
            // a call, a creation, a variable, a property is never a constant, whatever it returns (`$"M {ToS(x)}"` of MudBlazor's charts)
            if (r.expressions.constants.isNotConstant(value)) return false
            val type = r.typeOf(value)
            if (type != null && r.definitionName(type) != STRING) return false
            known = false
        }
        return if (known) true else null
    }

    private fun hasPriority(symbol: CSharpSymbol): Boolean =
        symbol is CSharpSymbol.LibraryMember && symbol.member.attributes.any { it == "System.Runtime.CompilerServices.OverloadResolutionPriorityAttribute" }

    private fun enclosingFunction(site: PsiElement): PsiElement? =
        com.intellij.psi.util.PsiTreeUtil.getParentOfType(site, CSharpMethodDeclaration::class.java, CSharpLocalFunctionStatement::class.java)

    private fun argumentType(e: CSharpExpression): SemanticType? {
        val value = unparenthesized(e)
        return when {
            value is CSharpAnonymousFunctionExpression -> null
            value is CSharpDeclarationExpression && value.type?.let(r::isVar) != false -> null
            // `default`, `null`, `new()`, `[…]`: typed by the parameter chosen — asking now is the call asking itself (`Should().Be(default)` was a cycle)
            targetTyped(value) -> null
            else -> r.typeOf(value)
        }
    }

    private val inference get() = r.inference

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
    internal fun refKind(symbol: CSharpSymbol, index: Int, reduced: Boolean): String? {
        val at = index + if (reduced) 1 else 0
        return when (symbol) {
            is CSharpSymbol.LibraryMember -> r.session.parameters(symbol.member).getOrNull(at)?.let { p -> if (p.isOut) "out" else if (p.isIn) "in" else if (p.isRef) "ref" else null }
            is CSharpSymbol.SourceMember, is CSharpSymbol.Local -> (if (symbol is CSharpSymbol.Local) r.localFunctionOf(symbol)?.parameterList else parameterList((symbol as CSharpSymbol.SourceMember).element))
                ?.parameters?.getOrNull(at)?.modifiers?.map { it.text }?.let { m ->
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

    internal fun substitute(type: SemanticType?, symbol: CSharpSymbol, arguments: List<SemanticType?>): SemanticType? {
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
    /** A primitive or an enum is an unmanaged type for sure; a struct of fields may be, not checked. */
    private fun surelyUnmanaged(argument: SemanticType): Boolean =
        r.definitionName(argument) in PRIMITIVES || (argument as? SemanticType.Library)?.type?.kind == io.github.dotnetsupport.index.IndexedTypeKind.ENUM ||
            (argument as? SemanticType.Source)?.info?.kind == io.github.dotnetsupport.lang.TypeKind.ENUM

    internal fun constraintsHoldStrict(symbol: CSharpSymbol, arguments: List<SemanticType?>): Boolean? {
        if (!constraintsHold(symbol, arguments)) return false
        fun converts(argument: SemanticType, type: SemanticType): Boolean = classify(argument, type).let { it == Conversion.IDENTITY || it == Conversion.IMPLICIT }
        when (symbol) {
            is CSharpSymbol.LibraryMember -> for ((i, parameter) in symbol.member.typeParameters.withIndex()) {
                val argument = arguments.getOrNull(i) ?: return null
                val reference = roslynTypes.isReferenceType(argument)
                if (parameter.isStruct && reference != false || parameter.isClass && reference != true) return if (reference == null) null else false
                // `allows ref struct` is an anti-constraint: every type satisfies it (ConcurrentDictionary.GetOrAdd<TArg> of .NET 9)
                if (parameter.isUnmanaged && !surelyUnmanaged(argument)) return null
                if (parameter.hasNew && !parameter.isStruct) when (newable(argument)) {
                    false -> return false
                    null -> return null
                    true -> {}
                }
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
                                val reference = roslynTypes.isReferenceType(argument)
                                if (struct && reference != false || !struct && reference != true) return if (reference == null) null else false
                                if (constraint.text.endsWith("?") || struct && r.isNullable(argument)) return null
                            }
                            is CSharpTypeConstraint -> {
                                // `notnull` not met is a warning (CS8714), never an error: it holds here; `unmanaged` — a primitive or an enum surely is
                                // (neither names a type: checked before the type is resolved — nopCommerce's `ToDictionaryAsync … where TKey : notnull`)
                                if (constraint.text == "notnull") continue
                                if (constraint.text == "unmanaged") { if (surelyUnmanaged(argument)) continue else return null }
                                val type = constraint.type?.let(resolver::resolveType)?.let { substitute(it, symbol, arguments) } ?: return null
                                if (!converts(argument, type)) return null
                            }
                            is CSharpConstructorConstraint -> when (newable(argument)) {
                                false -> return false
                                null -> return null
                                true -> {}
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

    /**
     * `new()` for the applicability of a candidate (Roslyn's `CheckConstraints`): a type parameter by its own `new()` or `struct`; a
     * partial type of the sources may have a constructor in a part not seen (generated), so it is not known then.
     */
    private fun newable(argument: SemanticType): Boolean? = when (argument) {
        is SemanticType.Parameter -> if (r.isValueType(argument) || hasConstructorConstraint(argument)) true else if (constraintsKnown(argument)) false else null
        else -> r.newable(argument, ::mayHaveUnseenParts)
    }

    /**
     * A partial type with a part the IDE may not see: its project may generate code ([CSharpSemanticEnvironment.mayGenerateTypes]) and the
     * generators have not run — nopCommerce's `public partial class ReminderSettings : ISettings` of a plain library is whole.
     */
    private fun mayHaveUnseenParts(info: io.github.dotnetsupport.lang.TypeInfo): Boolean = info.parts.any { "partial" in it.modifiers } && info.parts.any { part ->
        val file = part.element()?.containingFile ?: return@any true
        !CSharpSemanticEnvironment.generatedKnown(file) && CSharpSemanticEnvironment.mayGenerateTypes(file)
    }

    private fun hasConstructorConstraint(parameter: SemanticType.Parameter): Boolean {
        val owner = parameter.owner as? CSharpMethodDeclaration ?: (parameter.owner as? CSharpTypeDeclaration) ?: return false
        val clauses = if (owner is CSharpMethodDeclaration) owner.constraintClauses else (owner as CSharpTypeDeclaration).constraintClauses
        return clauses.any { c -> c.nameElement?.identifier?.text == parameter.name && c.constraints.any { it is CSharpConstructorConstraint } }
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
        internal val NO_VALUE_TASKS = setOf("System.Threading.Tasks.Task", "System.Threading.Tasks.ValueTask")
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
    }
}
