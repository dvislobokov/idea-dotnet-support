package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver.Conversion

/**
 * Query expressions as the method calls they are (C# §12.20.3, task 0.1.80 of CSHARP_PSI_MIGRATION.md): `from x in e where p select v` is
 * `e.Where(x => p).Select(x => v)`, and each call is resolved against the actual type of what it is called on — instance methods first,
 * then the extension methods imported where the query is (`Queryable` for an `IQueryable<T>`, which beats `Enumerable` by its receiver,
 * an EF Core `DbSet<T>`, own types with `Select` / `Where`, `IAsyncEnumerable<T>` with System.Linq.Async). A range variable is the parameter
 * of the lambda its clause becomes, so its type is what that method's delegate takes; the type of the query is what the last call returns.
 *
 * The lambdas have no syntax: a lambda here is the range variables its parameters bind and the expression of the clause its body is.
 * Clauses after the first that introduces a second range variable work on a transparent identifier (an anonymous type `new { x, y }`):
 * an opaque type stands for it, its range variables keep the types they were bound with. Anything unknown on the way — a candidate whose
 * signature is not known, candidates that disagree on what a lambda takes, a type argument nothing fixes — and the rest of the query
 * stays unknown: never a guess.
 */
internal class CSharpQueryTranslation(private val r: CSharpNameResolver) {
    private class Translation {
        /** The type each range variable is bound with, by the clause that declares it. */
        val ranges = HashMap<PsiElement, SemanticType?>()
        var result: SemanticType? = null
        var done = false
    }

    private val translations = HashMap<CSharpQueryExpression, Translation>()
    private var opaqueCount = 0

    /** The type of the query: what its last method returns; null when not known. */
    fun typeOf(query: CSharpQueryExpression): SemanticType? = translate(query)?.result

    /** Whether the query of [element] (a query, a clause) is being translated right now: its answers so far are partial. */
    fun translating(element: PsiElement): Boolean {
        val query = element as? CSharpQueryExpression ?: queryOf(element) ?: return false
        return translations[query]?.done == false
    }

    /** The type of the range variable declared by [clause], as the translation of its query binds it; null when not known (yet). */
    fun rangeVariableType(clause: PsiElement): SemanticType? {
        val query = queryOf(clause) ?: return null
        val translation = translate(query) ?: return null
        translation.ranges[clause]?.let { return it }
        // asked before the lambda that binds it is resolved: what depends on it must not be kept as "nothing"
        if (!translation.done) r.noteCycle()
        return null
    }

    private fun queryOf(element: PsiElement): CSharpQueryExpression? = PsiTreeUtil.getParentOfType(element, CSharpQueryExpression::class.java, false)

    private fun translate(query: CSharpQueryExpression): Translation? {
        translations[query]?.let { return it }
        val translation = Translation()
        translations[query] = translation
        val cycles = r.cycleCount
        try {
            translation.result = compute(query, translation)?.takeIf { !mentionsOpaque(it) }
        } finally {
            translation.done = true
        }
        // an answer that met a question in progress elsewhere may be incomplete: asked again later, it is translated again
        if (r.cycleCount != cycles) translations.remove(query)
        return translation
    }

    // ---- the clauses (§12.20.3)

    /** What a lambda's parameter stands for: a range variable (its declaring clause) or the transparent identifier (null). */
    private sealed class Scope {
        class Single(val clause: PsiElement) : Scope()
        class Transparent(val type: SemanticType) : Scope()

        val binder: PsiElement? get() = (this as? Single)?.clause
    }

    private fun compute(query: CSharpQueryExpression, t: Translation): SemanticType? {
        val from = query.fromClause ?: return null
        var source = source(from.expression, from.type, from, t) ?: return null
        var scope: Scope = Scope.Single(from)
        var body = query.body ?: return null
        while (true) {
            val result = body(body, source, scope, t) ?: return null
            val continuation = body.continuation ?: return result
            source = result
            scope = Scope.Single(continuation)
            body = continuation.body ?: return null
        }
    }

    /**
     * What a `from` / `join` takes its elements from: the expression, through `Cast<T>()` when the range variable has a written type (the
     * collection of a second `from` is typed with the variables before it bound: it is a lambda's body).
     */
    private fun source(expression: CSharpExpression?, type: CSharpType?, clause: PsiElement, t: Translation): SemanticType? {
        val value = expression?.let(r::typeOf) ?: return null
        if (type == null) return value
        val declared = r.resolveType(type) ?: return null
        return call(clause, value, "Cast", emptyList(), t, listOf(declared))
    }

    private fun body(body: CSharpQueryBody, start: SemanticType, first: Scope, t: Translation): SemanticType? {
        var source = start
        var scope = first
        val clauses = body.clauses
        val last = body.selectOrGroup ?: return null
        for ((i, clause) in clauses.withIndex()) {
            // `from x2 in e2 select v` and `join … select v`: the select is the result selector of the call
            val selectFollows = i == clauses.size - 1 && last is CSharpSelectClause
            val selected = (last as? CSharpSelectClause)?.expression
            when (clause) {
                is CSharpFromClause -> {
                    val collection = Lambda(listOf(scope.binder)) { source(clause.expression, clause.type, clause, t) }
                    if (selectFollows) return call(last, source, "SelectMany", listOf(collection, Lambda(listOf(scope.binder, clause), value(selected))), t)
                    val transparent = opaque(clause)
                    source = call(clause, source, "SelectMany", listOf(collection, Lambda(listOf(scope.binder, clause)) { transparent }), t) ?: return null
                    scope = Scope.Transparent(transparent)
                }
                is CSharpLetClause -> {
                    val transparent = opaque(clause)
                    val value = clause.expression
                    source = call(clause, source, "Select", listOf(Lambda(listOf(scope.binder)) {
                        // `new { x, y = f }`: the let's variable is typed by its expression, with the variables before it bound
                        t.ranges[clause] = value?.let(r::typeOf)
                        transparent
                    }), t) ?: return null
                    scope = Scope.Transparent(transparent)
                }
                is CSharpWhereClause -> source = call(clause, source, "Where", listOf(Lambda(listOf(scope.binder), value(clause.condition))), t) ?: return null
                is CSharpJoinClause -> {
                    val inner = Value { source(clause.inExpression, clause.type, clause, t) }
                    val outerKey = Lambda(listOf(scope.binder), value(clause.leftExpression))
                    val innerKey = Lambda(listOf(clause), value(clause.rightExpression))
                    val into = clause.into
                    val method = if (into == null) "Join" else "GroupJoin"
                    val second: PsiElement = into ?: clause
                    if (selectFollows) return call(last, source, method, listOf(inner, outerKey, innerKey, Lambda(listOf(scope.binder, second), value(selected))), t)
                    val transparent = opaque(clause)
                    source = call(clause, source, method, listOf(inner, outerKey, innerKey, Lambda(listOf(scope.binder, second)) { transparent }), t) ?: return null
                    scope = Scope.Transparent(transparent)
                }
                is CSharpOrderByClause -> for ((k, ordering) in clause.orderings.withIndex()) {
                    val descending = ordering.ascendingOrDescendingKeyword?.text == "descending"
                    val method = (if (k == 0) "OrderBy" else "ThenBy") + if (descending) "Descending" else ""
                    source = call(ordering, source, method, listOf(Lambda(listOf(scope.binder), value(ordering.expression))), t) ?: return null
                }
                else -> return null
            }
        }
        return when (last) {
            is CSharpSelectClause -> {
                // a degenerate select (`… select x` of the range variable itself) is left out, but not from a query of nothing else
                if (clauses.isNotEmpty() && isRangeVariable(last.expression, scope)) return source
                call(last, source, "Select", listOf(Lambda(listOf(scope.binder), value(last.expression))), t)
            }
            is CSharpGroupClause -> {
                val key = Lambda(listOf(scope.binder), value(last.byExpression))
                if (isRangeVariable(last.groupExpression, scope)) call(last, source, "GroupBy", listOf(key), t)
                else call(last, source, "GroupBy", listOf(key, Lambda(listOf(scope.binder), value(last.groupExpression))), t)
            }
            else -> null
        }
    }

    private fun value(expression: CSharpExpression?): () -> SemanticType? = { expression?.let(r::typeOf) }

    /** `select x` / `group x by …` where `x` is the one range variable in scope. */
    private fun isRangeVariable(expression: CSharpExpression?, scope: Scope): Boolean {
        val clause = (scope as? Scope.Single)?.clause ?: return false
        var e = expression
        while (e is CSharpParenthesizedExpression) e = e.expression
        val name = e as? CSharpIdentifierName ?: return false
        val local = name.identifier?.let(r.syntax::symbolAt) ?: return false
        return local.declaration.parent == clause
    }

    /** A type no one can name, for the anonymous type of a transparent identifier: it goes through inference like any other. */
    private fun opaque(clause: PsiElement): SemanticType = SemanticType.Parameter("<>h__TransparentIdentifier${opaqueCount++}", clause, 0, false)

    private fun mentionsOpaque(type: SemanticType?): Boolean = when (type) {
        null -> false
        is SemanticType.Parameter -> type.name.startsWith("<>h__TransparentIdentifier")
        is SemanticType.Library -> type.arguments.any(::mentionsOpaque)
        is SemanticType.Source -> type.arguments.any(::mentionsOpaque)
        is SemanticType.ArrayOf -> mentionsOpaque(type.element)
    }

    // ---- the calls

    private sealed class Argument

    /** An argument that is a value of a type (the inner sequence of a `join`). */
    private class Value(val type: () -> SemanticType?) : Argument()

    /** A lambda whose parameters bind [binders] (null: the transparent identifier) and whose body has the type [body] gives. */
    private class Lambda(val binders: List<PsiElement?>, val body: () -> SemanticType?) : Argument()

    private class Candidate(val symbol: CSharpSymbol, val reduced: Boolean, val parameters: List<CSharpNameResolver.Parameter>, val fixed: Array<SemanticType?>) {
        val owner: PsiElement? = (symbol as? CSharpSymbol.SourceMember)?.element
        var receiverTarget: SemanticType? = null
        var applicable = true
    }

    /**
     * `receiver.name(arguments)` of a clause at [site] (where the extension methods are looked up): the return type of the one method it
     * calls, the lambdas' range variables bound on the way. [typeArguments]: written ones (`Cast<T>`).
     */
    private fun call(
        site: PsiElement, receiver: SemanticType, name: String, arguments: List<Argument>, t: Translation,
        typeArguments: List<SemanticType>? = null,
    ): SemanticType? {
        // the members of the receiver's type first; extension methods only when no instance method applies
        val instance = r.membersNamed(receiver, name, 0).filter { r.isMethod(it) && r.overloads.isStatic(it) != true && !r.isExtension(it) }
        val values = arguments.map { (it as? Value)?.type?.invoke() }
        for (reduced in listOf(false, true)) {
            val symbols = if (!reduced) instance else r.extensionMethods(receiver, name, 0, site).filter(r::isExtension)
            if (symbols.isEmpty()) continue
            val candidates = symbols.mapNotNull { symbol -> prepare(symbol, reduced, receiver, arguments, values, typeArguments) ?: return null }
            if (candidates.none { it.applicable }) continue
            // the lambdas in order: their parameters typed by what is fixed so far, their bodies fix the rest
            for ((i, argument) in arguments.withIndex()) {
                val lambda = argument as? Lambda ?: continue
                val alive = candidates.filter { it.applicable }
                if (alive.isEmpty()) break
                var parameterTypes: List<SemanticType>? = null
                for (candidate in alive) {
                    val delegate = candidate.parameters[i].type()?.let { substitute(it, candidate) }?.let(r.expressions::unwrapExpression) ?: return null
                    val signature = r.expressions.delegateSignature(delegate)
                    if (signature == null || signature.first.size != lambda.binders.size) { candidate.applicable = false; continue }
                    val types = signature.first.map { type -> type?.takeIf { !r.expressions.mentionsUnfixed(it, candidate.fixed) } ?: return null }
                    if (parameterTypes == null) parameterTypes = types
                    // the candidates must agree on what the lambda takes: the range variables are bound once
                    else if (types.zip(parameterTypes).any { (a, b) -> r.overloads.same(a, b) != true }) return null
                }
                val bound = parameterTypes ?: continue
                for ((binder, type) in lambda.binders.zip(bound)) if (binder != null && t.ranges[binder] == null) t.ranges[binder] = type
                var body: SemanticType? = null
                var typed = false
                for (candidate in candidates.filter { it.applicable }) {
                    val delegate = candidate.parameters[i].type()?.let(r.expressions::unwrapExpression) ?: return null
                    val returns = r.expressions.delegateSignature(delegate)?.second ?: return null
                    if (!typed) { body = lambda.body(); typed = true }
                    val type = body ?: continue
                    if (r.expressions.mentionsUnfixed(returns, candidate.fixed)) r.expressions.unify(returns, type, candidate.fixed, candidate.owner)
                    val target = substitute(returns, candidate)
                    if (target != null && !r.expressions.mentionsUnfixed(target, candidate.fixed) && r.definitionName(target) != "System.Void" &&
                        r.conversion(type, target) == Conversion.NONE) candidate.applicable = false
                }
            }
            val applicable = candidates.filter { it.applicable }
            if (applicable.isEmpty()) continue
            val best = best(applicable, receiver) ?: return null
            // every type argument inferred: one left unknown leaves what the call returns unknown
            if (best.fixed.any { it == null }) return null
            val returned = r.returnType(best.symbol, best.fixed.toList()) ?: return null
            if (r.expressions.mentionsUnfixed(returned, best.fixed) || mentionsOpenParameter(returned, best)) return null
            return returned
        }
        return null
    }

    /** [symbol] with what the receiver, the written type arguments and the values fix; not [Candidate.applicable] when surely not; null when not known. */
    private fun prepare(
        symbol: CSharpSymbol, reduced: Boolean, receiver: SemanticType, arguments: List<Argument>, values: List<SemanticType?>, typeArguments: List<SemanticType>?,
    ): Candidate? {
        val all = r.signature(symbol, false) ?: return null
        if (reduced && all.isEmpty()) return null
        val parameters = if (reduced) all.drop(1) else all
        val arity = r.expressions.methodArity(symbol)
        val candidate = Candidate(symbol, reduced, parameters, arrayOfNulls(arity))
        if (parameters.size < arguments.size || parameters.drop(arguments.size).any { !it.optional } || parameters.take(arguments.size).any { it.byRef || it.isParams }) {
            candidate.applicable = false
            return candidate
        }
        if (typeArguments != null) {
            if (typeArguments.size != arity) { candidate.applicable = false; return candidate }
            typeArguments.forEachIndexed { i, type -> candidate.fixed[i] = type }
        }
        if (reduced) {
            val self = all.first().type() ?: return null
            r.expressions.unify(self, receiver, candidate.fixed, candidate.owner)
            val target = substitute(self, candidate) ?: return null
            candidate.receiverTarget = target
            if (!r.expressions.mentionsUnfixed(target, candidate.fixed) && r.overloads.classify(receiver, target, userDefined = false) == Conversion.NONE) candidate.applicable = false
        }
        for ((i, argument) in arguments.withIndex()) {
            if (argument !is Value) continue
            val type = values[i] ?: return null
            val parameter = parameters[i].type() ?: return null
            r.expressions.unify(parameter, type, candidate.fixed, candidate.owner)
            val target = substitute(parameter, candidate) ?: return null
            if (!r.expressions.mentionsUnfixed(target, candidate.fixed) && r.conversion(type, target) == Conversion.NONE) candidate.applicable = false
        }
        return candidate
    }

    /** [type] with the method type parameters [candidate] has fixed so far in their places. */
    private fun substitute(type: SemanticType, candidate: Candidate): SemanticType? = r.replace(type) { p ->
        if (p.ofMethod && (p.owner == null || p.owner == candidate.owner)) candidate.fixed.getOrNull(p.index) ?: p else p
    }

    /** A type parameter of the candidate method left in what it returns (a source method's parameters are matched by owner). */
    private fun mentionsOpenParameter(type: SemanticType?, candidate: Candidate): Boolean = when (type) {
        null -> false
        is SemanticType.Parameter -> type.ofMethod && (type.owner == null || type.owner == candidate.owner)
        is SemanticType.Library -> type.arguments.any { mentionsOpenParameter(it, candidate) }
        is SemanticType.Source -> type.arguments.any { mentionsOpenParameter(it, candidate) }
        is SemanticType.ArrayOf -> mentionsOpenParameter(type.element, candidate)
    }

    /**
     * The better function member among applicable [candidates] (§12.6.4.3) as far as a query needs it: extension methods by their receiver
     * (`IQueryable<T>` over `IEnumerable<T>`); candidates no conversion tells apart that return the same type are as good as one.
     */
    private fun best(candidates: List<Candidate>, receiver: SemanticType): Candidate? {
        if (candidates.size == 1) return candidates.single()
        val winners = candidates.filter { m ->
            candidates.all { n ->
                if (n === m) return@all true
                val p = m.receiverTarget ?: return@all false
                val q = n.receiverTarget ?: return@all false
                r.overloads.betterFromType(receiver, p, q) == 1
            }
        }
        if (winners.size == 1) return winners.single()
        val returns = candidates.map { r.returnType(it.symbol, it.fixed.toList())?.display ?: return null }
        return if (returns.distinct().size == 1) candidates.first() else null
    }
}
