package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.TypeKind
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver.Conversion
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.COLLECTION_TARGETS
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.NATURAL_TARGETS
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.NULLABLE
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.NO_VALUE_TASKS
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.OBJECT
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.PRIMITIVES
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.STRING
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.TASKS_OF
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.VALUE_TUPLE
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.VOID
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynConversions
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynExpressionConversions
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodGroupConversion
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodTypeInferrer

/*
 * The conversions of overload resolution (C# §10.2), as extensions of [CSharpOverloads] (D6e5: one file per concern, so D6c replaces
 * them file by file): the identity of types ([same]), the implicit conversions between types ([classify]: Roslyn's port first, then
 * what it does not prove) and of argument expressions ([argument]: `null`, `default`, `new()`, lambdas, method groups, collection
 * expressions, interpolated strings, constants, tuple literals). The state they share (the candidates being resolved, the strict mode
 * of the compiler errors) stays in [CSharpOverloads].
 */

// ---- identity

/** Whether [a] and [b] are the same type (tuple element names aside); null when a part of either is not known. */
internal fun CSharpOverloads.same(a: SemanticType?, b: SemanticType?): Boolean? {
    if (a == null || b == null) return null
    if (a is SemanticType.Parameter || b is SemanticType.Parameter) {
        if (a is SemanticType.Parameter && b is SemanticType.Parameter && a.ofMethod == b.ofMethod && a.owner == b.owner && a.name == b.name && a.index == b.index) return true
        // a type parameter of a library method that inference left open may stand for anything
        if (isOpen(a) || isOpen(b)) return null
        return false
    }
    return when (a) {
        is SemanticType.ArrayOf -> if (b !is SemanticType.ArrayOf || a.rank != b.rank) false else same(a.element, b.element)
        is SemanticType.Library -> when (b) {
            is SemanticType.Library -> if (a.type.fullName != b.type.fullName) false else sameArguments(a.arguments, b.arguments)
            is SemanticType.Source -> if (builtFrom(a, b)) sameArguments(a.arguments, b.arguments) else false
            else -> false
        }
        is SemanticType.Source -> when (b) {
            is SemanticType.Source -> if (a.info.key != b.info.key) false else sameArguments(a.arguments, b.arguments)
            is SemanticType.Library -> if (builtFrom(b, a)) sameArguments(a.arguments, b.arguments) else false
            else -> false
        }
        is SemanticType.Parameter -> null
    }
}

/**
 * A built project is its sources: the IDE indexes the outputs of referenced projects too (`ProjectAssemblies.References.forIndex`), so a
 * type may come from the assembly and from the source alike — MudBlazor's `KeyInterceptorService` of an extension method of the built
 * `UnitTests.Shared` given to a parameter written in the sources (CS1503 "from 'KeyInterceptorService' to 'KeyInterceptorService'").
 */
private fun CSharpOverloads.builtFrom(library: SemanticType.Library, source: SemanticType.Source): Boolean =
    library.type.qualifiedName == source.info.qualifiedName && library.type.arity == source.info.arity

private fun CSharpOverloads.sameArguments(a: List<SemanticType?>, b: List<SemanticType?>): Boolean? {
    if (a.size != b.size) return false
    var unknown = false
    for ((x, y) in a.zip(b)) when (same(x, y)) {
        false -> return false
        null -> unknown = true
        true -> {}
    }
    return if (unknown) null else true
}

/** A type parameter of a method in a signature, not inferred: it may become any type. */
private fun CSharpOverloads.isOpen(type: SemanticType): Boolean = type is SemanticType.Parameter && type.ofMethod && (type.owner == null || type.owner is CSharpMethodDeclaration && isCandidateParameter(type))

private fun CSharpOverloads.isCandidateParameter(type: SemanticType.Parameter): Boolean = type.owner in openOwners && type.owner != siteOwner

// ---- conversions between types

/** The implicit conversion from [from] to [to] (§10.2); [userDefined] false: the standard ones only (the operand of a user-defined one). */
internal fun CSharpOverloads.classify(from: SemanticType, to: SemanticType, userDefined: Boolean = true, depth: Int = 0): Conversion {
    if (depth > 8) return Conversion.UNKNOWN
    // Roslyn's classification, ported (CSHARP_PSI_MIGRATION.md, D6d), proves a conversion; what it does not prove, the rules below decide
    val proved = if (userDefined) roslynConversions.classifyImplicitConversionFromType(from, to) else roslynConversions.classifyStandardImplicitConversion(from, to)
    if (proved != null) return if (proved == RoslynConversions.Kind.IDENTITY) Conversion.IDENTITY else Conversion.IMPLICIT
    val answer = classifyUnproved(from, to)
    // the log of the analysis: a conversion the port left to the old rules and they found (the port's gap, D6e3) or could not tell; NONE
    // is a decision both agree on (no conversion) — not logged
    if (depth == 0 && answer != Conversion.NONE && SemanticTrace.enabled && tracedConversions.add(Triple(from.toString(), to.toString(), userDefined))) {
        SemanticTrace.write("classify ${SemanticTrace.show(from)} -> ${SemanticTrace.show(to)}${if (userDefined) "" else " (standard)"}: port unproved, old rules $answer")
    }
    return answer
}

/**
 * What the port ([RoslynConversions]) does not prove (D6e3: the rules that proved conversions are gone, the port proves them — the log
 * showed them deciding only questions with an open type parameter, which Roslyn never asks). An open type parameter of a method being
 * resolved: not known (the old pickers ask before inference); two instances of one definition whose arguments are not all known:
 * identity, leniently; a type parameter of the enclosing code: by its constraints, NONE only where they all resolve; else NONE only
 * where both types are fully known.
 */
private fun CSharpOverloads.classifyUnproved(from: SemanticType, to: SemanticType): Conversion {
    when (same(from, to)) {
        true -> return Conversion.IDENTITY
        null -> if (from !is SemanticType.Parameter && to !is SemanticType.Parameter && r.definitionName(from) == r.definitionName(to)) return Conversion.IDENTITY
        false -> {}
    }
    if (to is SemanticType.Parameter) {
        if (to.ofMethod && (to.owner == null || isCandidateParameter(to))) return Conversion.UNKNOWN
        // nothing converts to a type parameter but itself and what depends on it (`where T : U`), which the port proves
        if (from is SemanticType.Parameter) return if (constraintsKnown(from)) Conversion.NONE else Conversion.UNKNOWN
        return if (to.owner != null && closed(from)) Conversion.NONE else Conversion.UNKNOWN
    }
    if (from is SemanticType.Parameter) {
        if (from.ofMethod && (from.owner == null || isCandidateParameter(from))) return Conversion.UNKNOWN
        return if (from.owner != null && constraintsKnown(from) && closed(to)) Conversion.NONE else Conversion.UNKNOWN
    }
    return if (closed(from) && closed(to)) Conversion.NONE else Conversion.UNKNOWN
}

internal fun CSharpOverloads.ok(c: Conversion) = c == Conversion.IDENTITY || c == Conversion.IMPLICIT

/** The conversion of a tuple from those of its elements: NONE if any is none, UNKNOWN if any is not known, IDENTITY if all are. */
private fun CSharpOverloads.combine(elements: List<Conversion>): Conversion = when {
    elements.any { it == Conversion.NONE } -> Conversion.NONE
    elements.any { it == Conversion.UNKNOWN } -> Conversion.UNKNOWN
    elements.all { it == Conversion.IDENTITY } -> Conversion.IDENTITY
    else -> Conversion.IMPLICIT
}

/** Whether every constraint of [parameter] written as a type resolves. */
internal fun CSharpOverloads.constraintsKnown(parameter: SemanticType.Parameter): Boolean {
    val owner = parameter.owner ?: return false
    val clauses = when (owner) {
        is CSharpTypeDeclaration -> owner.constraintClauses
        is CSharpMethodDeclaration -> owner.constraintClauses
        is CSharpLocalFunctionStatement -> owner.constraintClauses
        is CSharpDelegateDeclaration -> owner.constraintClauses
        else -> return false
    }
    val clause = clauses.firstOrNull { it.nameElement?.identifier?.text == parameter.name } ?: return true
    // `notnull` and `unmanaged` name no type: known, and not among [CSharpNameResolver.constraintsOf]
    return clause.constraints.filterIsInstance<CSharpTypeConstraint>().count { it.text != "notnull" && it.text != "unmanaged" } == r.constraintsOf(parameter).size
}

/**
 * The implicit operators of [type] and of its base classes (§10.5.4): `implicit operator TpmHandle(SessionBase s)` converts an `AuthSession :
 * SessionBase` too (CS1503 on `ContainsKey(session)` of a live project, 0.1.185); [depth] stops a cycle of bases the compiler reports.
 */
internal fun CSharpOverloads.operators(type: SemanticType, depth: Int = 0, withBases: Boolean = true): List<Pair<SemanticType?, SemanticType?>>? = if (depth > 16) null else when (type) {
    is SemanticType.Library -> r.membersNamed(type, "op_Implicit", 0).mapNotNull { symbol ->
        // the index marks operators OPERATOR, not METHOD: `isMethod` alone dropped every library conversion (`string` →
        // `ReadOnlySpan<char>`, `T[]` → `ReadOnlyMemory<T>`, `string` → `PathString`) and CS1503 followed (Jellyfin, 0.1.166)
        val operator = (symbol as? CSharpSymbol.LibraryMember)?.member?.kind == IndexedMemberKind.OPERATOR
        if (!operator && !r.isMethod(symbol)) return@mapNotNull null
        r.signature(symbol, false)?.singleOrNull()?.type?.invoke() to r.returnType(symbol, emptyList())
    }
    is SemanticType.Source -> {
        val found = ArrayList<Pair<SemanticType?, SemanticType?>>()
        for (part in type.info.parts) {
            val declaration = part.element() as? CSharpTypeDeclaration ?: continue
            val resolver = (declaration.containingFile as? CSharpFile)?.let(r.session::reachable) ?: return null
            for (op in declaration.members.filterIsInstance<CSharpConversionOperatorDeclaration>()) {
                if (op.implicitOrExplicitKeyword?.text != "implicit") continue
                val operand = op.parameterList?.parameters?.singleOrNull()?.type?.let(resolver::resolveType)
                found += r.substitute(operand, type) to r.substitute(op.type?.let(resolver::resolveType), type)
            }
        }
        if (withBases) for (base in r.libraryBases(type)) found += operators(base, depth + 1) ?: return null
        if (withBases) for (base in r.baseTypes(type)) if (base is SemanticType.Source && !isInterface(base) && base.info.key != type.info.key) found += operators(base, depth + 1) ?: return null
        found
    }
    is SemanticType.ArrayOf -> emptyList()
    is SemanticType.Parameter -> null
}

internal fun CSharpOverloads.isInterface(type: SemanticType): Boolean = when (type) {
    is SemanticType.Library -> type.type.kind == IndexedTypeKind.INTERFACE
    is SemanticType.Source -> type.info.kind == TypeKind.INTERFACE
    else -> false
}

/** Whether [type] is a reference type; null when that is not known (a type parameter without `class`). */
internal fun CSharpOverloads.isReference(type: SemanticType): Boolean? = when (type) {
    is SemanticType.ArrayOf -> true
    is SemanticType.Library -> type.type.kind != IndexedTypeKind.STRUCT && type.type.kind != IndexedTypeKind.ENUM
    is SemanticType.Source -> type.info.kind?.let { it != TypeKind.STRUCT && it != TypeKind.RECORD_STRUCT && it != TypeKind.ENUM }
    is SemanticType.Parameter -> if (r.isValueType(type)) false else null
}

/** A type whose base types and interfaces all resolve, all the way up: what it does not convert to, it surely does not. */
internal fun CSharpOverloads.closed(type: SemanticType, depth: Int = 0): Boolean {
    if (depth > 16) return false
    return when (type) {
        is SemanticType.ArrayOf -> type.element?.let { closed(it, depth + 1) } ?: false
        is SemanticType.Parameter -> type.owner != null && constraintsKnown(type) && r.constraintsOf(type).all { closed(it, depth + 1) }
        is SemanticType.Library -> libraryKnown(type.type, depth) && type.arguments.all { it != null }
        is SemanticType.Source -> type.arguments.all { it != null } && known.getOrPut(type.info.key) {
            known[type.info.key] = true
            type.info.kind != null && type.info.parts.all { part ->
                val declaration = part.element() as? CSharpBaseTypeDeclaration ?: return@all part.element() is CSharpDelegateDeclaration || part is io.github.dotnetsupport.lang.TypePart.Anonymous
                val owner = (declaration.containingFile as? CSharpFile)?.let(r.session::reachable) ?: return@all false
                declaration.baseList?.types.orEmpty().all { base -> base.type?.let(owner::resolveType)?.let { closed(it, depth + 1) } == true }
            }
        }
    }
}

private fun CSharpOverloads.libraryKnown(type: io.github.dotnetsupport.index.IndexedType, depth: Int): Boolean = known.getOrPut(type) {
    if (depth > 16) return@getOrPut false
    (type.interfaces + listOfNotNull(type.baseType)).all { reference -> r.assemblies.resolve(reference)?.let { libraryKnown(it, depth + 1) } == true }
}

// ---- conversions of argument expressions

internal fun CSharpOverloads.unparenthesized(e: CSharpExpression): CSharpExpression {
    // `(x)` and `x!` (the null-forgiving operator changes no conversion)
    var at = e
    while (true) {
        at = when {
            at is CSharpParenthesizedExpression -> at.expression ?: return at
            at is CSharpPostfixUnaryExpression && at.operatorToken?.text == "!" -> at.operand ?: return at
            else -> return at
        }
    }
}

/** The conversion of the expression [e] (its natural type [type], null when none or not known) to [to]. */
internal fun CSharpOverloads.argument(e: CSharpExpression, type: SemanticType?, to: SemanticType, depth: Int = 0): Conversion {
    val value = unparenthesized(e)
    if (depth > 4) return Conversion.UNKNOWN
    // the port (D6c2, roslyn/RoslynExpressionConversions) for the kinds it covers; it answers alone, the old rules for them are gone.
    // An anonymous object whose type is known (0.1.236) converts by that type: to itself by identity (`DeserializeAnonymousType(json,
    // new { … })` infers T as that type), to `object` by its base, to nothing else
    expressionSource(value)?.takeIf { !(value is CSharpAnonymousObjectCreationExpression && type != null) }?.let { source ->
        when (val ported = expressionConversions.classifyImplicitConversionFromExpression(source, to)) {
            is RoslynExpressionConversions.Result.Exists -> return Conversion.IMPLICIT
            RoslynExpressionConversions.Result.None -> return Conversion.NONE
            is RoslynExpressionConversions.Result.Unknown -> {
                SemanticTrace.trace { "conversion of `${SemanticTrace.text(value)}` at ${SemanticTrace.at(value)} to ${SemanticTrace.show(to)}: not known — ${ported.reason}" }
                return Conversion.UNKNOWN
            }
            RoslynExpressionConversions.Result.ByType, RoslynExpressionConversions.Result.AnonymousFunctionConversion -> {}
        }
    }
    when (value) {
        is CSharpAnonymousFunctionExpression -> return strictLambda(value, to)
        is CSharpCollectionExpression -> return collection(to)
        is CSharpConditionalExpression -> if (type == null) {
            val branches = listOfNotNull(value.whenTrue, value.whenFalse)
            val each = branches.map { argument(it, r.typeOf(it), to, depth + 1) }
            return when {
                each.any { it == Conversion.NONE } -> Conversion.NONE
                each.all(::ok) -> Conversion.IMPLICIT
                else -> Conversion.UNKNOWN
            }
        }
        is CSharpTupleExpression -> {
            // a tuple literal converts element by element, each element as the expression it is (`(null, 1)`, `(x, "a")`) — §10.2.13
            val targets = (to as? SemanticType.Library)?.takeIf { r.definitionName(it)?.startsWith(VALUE_TUPLE) == true }?.arguments
            if (targets != null && targets.size == value.arguments.size) {
                return combine(value.arguments.mapIndexed { i, element ->
                    val expression = element.expression ?: return Conversion.UNKNOWN
                    argument(expression, r.typeOf(unparenthesized(expression)), targets[i] ?: return Conversion.UNKNOWN, depth + 1)
                }).let { if (it == Conversion.IDENTITY) Conversion.IMPLICIT else it }
            }
            if (type == null) return Conversion.UNKNOWN
        }
    }
    methodGroup(value)?.let { group ->
        // a delegate type (not `Expression<D>`): the method group conversion; any other type: by the group's function type (C# 10 —
        // `MapGet(pattern, GetItems)` to `Delegate`, eShop's minimal APIs)
        if (r.expressions.delegateSignature(to) != null) return methodGroupConversion(value, group, to)
        val reduced = isReducedGroup(value, group)
        when (val ported = expressionConversions.classifyImplicitConversionFromExpression(methodGroupSource(group, value, reduced), to)) {
            is RoslynExpressionConversions.Result.Exists -> return Conversion.IMPLICIT
            RoslynExpressionConversions.Result.None -> return Conversion.NONE
            is RoslynExpressionConversions.Result.Unknown -> {
                SemanticTrace.trace { "conversion of the method group `${SemanticTrace.text(value)}` at ${SemanticTrace.at(value)} to ${SemanticTrace.show(to)}: not known — ${ported.reason}" }
                return Conversion.UNKNOWN
            }
            RoslynExpressionConversions.Result.ByType, RoslynExpressionConversions.Result.AnonymousFunctionConversion -> return Conversion.UNKNOWN
        }
    }
    val source = type ?: return Conversion.UNKNOWN
    val conversion = classify(source, to)
    if (conversion == Conversion.NONE && constantFits(value, to)) return Conversion.IMPLICIT
    return conversion
}

/**
 * [lambda] to [to] where only what is sure counts ([strict]): the number of parameters, their `ref` kinds and written types, a value
 * for a delegate that returns none; a body is taken as valid (its own errors are reported in it), but its type must be known where
 * the delegate returns a value. Async lambdas and blocks whose end is reachable are not judged.
 */
private fun CSharpOverloads.strictLambda(lambda: CSharpAnonymousFunctionExpression, to: SemanticType): Conversion {
    // the port decides what the target is (D6c3); the anonymous function conversion to a delegate is checked below
    when (val ported = expressionConversions.classifyImplicitConversionFromExpression(RoslynExpressionConversions.Source.AnonymousFunction(hasNaturalType(lambda)), to)) {
        is RoslynExpressionConversions.Result.Exists -> return Conversion.IMPLICIT
        RoslynExpressionConversions.Result.None -> return Conversion.NONE
        is RoslynExpressionConversions.Result.Unknown -> {
            SemanticTrace.trace { "conversion of the lambda at ${SemanticTrace.at(lambda)} to ${SemanticTrace.show(to)}: not known — ${ported.reason}" }
            return Conversion.UNKNOWN
        }
        RoslynExpressionConversions.Result.AnonymousFunctionConversion, RoslynExpressionConversions.Result.ByType -> {}
    }
    // every place this check gives up says why (the applicable line of the call says only `conversion not known`)
    fun unknown(reason: String): Conversion {
        SemanticTrace.trace { "conversion of the lambda at ${SemanticTrace.at(lambda)} to ${SemanticTrace.show(to)}: not known — $reason" }
        return Conversion.UNKNOWN
    }
    val target = r.expressions.unwrapExpression(to) ?: return unknown("the target is not a delegate or an expression tree")
    val delegate = r.expressions.delegateSignature(target) ?: return unknown("the signature of the delegate is not known")
    val count = r.expressions.lambdaParameterCount(lambda)
    val parameters = lambdaParameters(lambda)
    if (count != null && count != delegate.first.size) return Conversion.NONE
    val kinds = delegateRefKinds(target) ?: return unknown("the ref kinds of the delegate's parameters are not known")
    var unknown = false
    if (parameters != null) for ((i, p) in parameters.withIndex()) {
        val written = lambdaRefKind(p) ?: return unknown("the modifier of the parameter `${p.text}` is not modeled")
        val expected = kinds.getOrNull(i) ?: return unknown("the delegate has no parameter ${i + 1}")
        if (written != expected) return if (written == "in" && expected == "ref") unknown("an `in` parameter to a `ref` one of the delegate") else Conversion.NONE
        val syntax = p.type ?: continue
        val type = r.resolveType(syntax) ?: return unknown("the parameter type `${syntax.text}` does not resolve")
        val wanted = delegate.first.getOrNull(i) ?: return unknown("the delegate has no parameter ${i + 1}")
        if (mentionsCandidateParameter(wanted)) return unknown("parameter ${i + 1} of the delegate, ${SemanticTrace.show(wanted)}, mentions a type parameter of the candidate")
        when (same(type, wanted)) {
            false -> return Conversion.NONE
            null -> unknown = true
            true -> {}
        }
    }
    if (unknown) return unknown("a written parameter type is not surely the delegate's")
    if (delegate.first.any { it == null || mentionsCandidateParameter(it) }) return unknown("a parameter of the delegate is not known or mentions a type parameter of the candidate")
    val written = delegate.second ?: return unknown("the return type of the delegate is not known")
    if (mentionsCandidateParameter(written)) return unknown("the return type of the delegate, ${SemanticTrace.show(written)}, mentions a type parameter of the candidate")
    // an async lambda to a delegate returning `Task<Y>` / `ValueTask<Y>`: what it returns converts to `Y` (§10.7.1) — nopCommerce's
    // `GetAsync<T>(key, Func<Task<T>>)` and `GetAsync<T>(key, Func<T>)` are then both applicable and the first one is more specific
    // an async lambda to a delegate returning `void`, `Task` or `ValueTask` returns no value, as a lambda to a `void` one (§12.21.1) —
    // the `async () => { … }` of the tag helpers of every generated view to `Func<Task>` (5 800 lines of nopCommerce's trace)
    val async = r.expressions.isAsync(lambda.modifiers)
    val returns = if (!async || r.definitionName(written) == VOID || r.definitionName(written) in NO_VALUE_TASKS) written
        else if (r.definitionName(written) in TASKS_OF) (written as? SemanticType.Library)?.arguments?.singleOrNull() ?: return unknown("the task type ${SemanticTrace.show(written)} has no type argument")
        // CS4010 in the body (an async lambda returns void or a task-like type only) prevents the delegate conversion: nopCommerce's
        // `GetAsync(key, async () => query)` is applicable to `Func<Task<T>>` alone, not to `Func<T>`
        else return Conversion.NONE
    val void = r.definitionName(returns) == VOID || async && r.definitionName(returns) in NO_VALUE_TASKS
    // the body bound for this delegate, as Roslyn's `HasAnonymousFunctionConversion` binds it: implicit parameters take the delegate's types
    // (D6c2) — typed through the call being checked they were a cycle, and `Add(x => x.Severity, …)` of bUnit was not known (3541 lines)
    val implicit = parameters?.takeIf { ps -> ps.isNotEmpty() && ps.all { it.type == null } }
    fun body(compute: () -> Conversion): Conversion = if (implicit == null) compute() else try {
        r.bindLambda(lambda, implicit, delegate.first.map { it ?: return unknown("a parameter of the delegate is not known") }, compute)
    } catch (e: io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodTypeInferrer.Unknown) {
        SemanticTrace.trace { "lambda at ${SemanticTrace.at(lambda)} to ${SemanticTrace.show(to)}: not known — ${e.describe()}" }
        Conversion.UNKNOWN
    }
    val body = lambda.expressionBody
    if (body != null) {
        if (void) return if (r.expressions.isStatementExpression(unparenthesized(body))) Conversion.IMPLICIT else Conversion.NONE
        return body {
            val bodyType = r.typeOf(unparenthesized(body))
            when (argument(body, bodyType, returns)) {
                Conversion.IDENTITY, Conversion.IMPLICIT -> Conversion.IMPLICIT
                Conversion.NONE -> Conversion.NONE
                Conversion.UNKNOWN -> unknown("the body `${SemanticTrace.text(body)}` of ${SemanticTrace.show(bodyType)} to ${SemanticTrace.show(returns)}: conversion not known")
            }
        }
    }
    val block = lambda.block ?: return unknown("the lambda has no body")
    val returned = returnsOf(block, lambda)
    // the lambda bound for the delegate with an error of its body has no conversion (`PreventsSuccessfulDelegateConversion`): a `return e;`
    // in a `void` one (CS0127), a `return;` or a reachable end of the block in one returning a value (CS0126, CS1643 — `Create(this,
    // () => { called = true; })` takes the `Action` overload, not the `Func<Task>` one)
    if (void) return if (returned.all { it.expression == null }) Conversion.IMPLICIT else Conversion.NONE
    if (returned.any { it.expression == null }) return Conversion.NONE
    when (CSharpReachability(r).endOf(block)) {
        CSharpReachability.Reach.YES -> return Conversion.NONE
        CSharpReachability.Reach.UNKNOWN -> return unknown("whether the end of the block is reachable is not known")
        CSharpReachability.Reach.NO -> {}
    }
    if (returned.isEmpty()) return Conversion.IMPLICIT
    return body {
        var result = Conversion.IMPLICIT
        for (statement in returned) {
            val value = statement.expression ?: return@body unknown("a `return` without a value")
            val valueType = r.typeOf(unparenthesized(value))
            when (argument(value, valueType, returns)) {
                Conversion.NONE -> return@body Conversion.NONE
                Conversion.UNKNOWN -> result = unknown("`return ${SemanticTrace.text(value)}` of ${SemanticTrace.show(valueType)} to ${SemanticTrace.show(returns)}: conversion not known")
                else -> {}
            }
        }
        result
    }
}

/** A type no lambda or method group converts to: closed, no interface, without user-defined conversions. */
private fun CSharpOverloads.noDelegate(type: SemanticType): Boolean {
    if (!closed(type) || isInterface(type) || type is SemanticType.ArrayOf) return type is SemanticType.ArrayOf
    if (type is SemanticType.Library && type.type.kind == IndexedTypeKind.DELEGATE) return false
    val operators = operators(type) ?: return false
    return operators.isEmpty() || type is SemanticType.Library && type.type.fullName == STRING
}

private fun CSharpOverloads.mentionsCandidateParameter(type: SemanticType): Boolean = when (type) {
    is SemanticType.Parameter -> isOpen(type) || isCandidateParameter(type)
    is SemanticType.ArrayOf -> type.element?.let(::mentionsCandidateParameter) ?: true
    is SemanticType.Library -> type.arguments.any { it == null || mentionsCandidateParameter(it) }
    is SemanticType.Source -> type.arguments.any { it == null || mentionsCandidateParameter(it) }
}

/** The `return` statements of [lambda]'s own body (not of the lambdas and local functions in it). */
internal fun CSharpOverloads.returnsOf(block: CSharpBlock, lambda: CSharpAnonymousFunctionExpression): List<CSharpReturnStatement> =
    PsiTreeUtil.findChildrenOfType(block, CSharpReturnStatement::class.java).filter {
        PsiTreeUtil.getParentOfType(it, CSharpAnonymousFunctionExpression::class.java, CSharpLocalFunctionStatement::class.java) == lambda
    }

/** `ref`, `out`, `in` or "" (by value) of a parameter of a lambda; null for `scoped`, `ref readonly` and what else is not modeled. */
internal fun CSharpOverloads.lambdaRefKind(p: CSharpParameter): String? {
    val modifiers = p.modifiers.map { it.text }
    if (modifiers.any { it != "ref" && it != "out" && it != "in" }) return null
    return when {
        "out" in modifiers -> "out"
        "in" in modifiers -> "in"
        "ref" in modifiers -> "ref"
        else -> ""
    }
}

/** The `ref` kinds of the parameters of a delegate type ("" by value); null when not known (`ref readonly`, `scoped`, `params`). */
internal fun CSharpOverloads.delegateRefKinds(type: SemanticType): List<String>? = when (type) {
    is SemanticType.Library -> type.type.members.firstOrNull { it.name == "Invoke" }?.let { invoke ->
        r.session.parameters(invoke).map { p -> if (p.isOut) "out" else if (p.isIn) "in" else if (p.isRef) "ref" else "" }
    }
    is SemanticType.Source -> type.info.parts.firstNotNullOfOrNull { it.element() as? CSharpDelegateDeclaration }?.parameterList?.parameters?.map { p ->
        val modifiers = p.modifiers.map { it.text }
        if (modifiers.any { it != "ref" && it != "out" && it != "in" }) return null
        if ("out" in modifiers) "out" else if ("in" in modifiers) "in" else if ("ref" in modifiers) "ref" else ""
    }
    else -> null
}

/** C# 10: a lambda whose parameters are typed (or that takes none) has a function type, `Func<…>` / `Action<…>`. */
internal fun CSharpOverloads.hasNaturalType(lambda: CSharpAnonymousFunctionExpression): Boolean = when (lambda) {
    is CSharpParenthesizedLambdaExpression -> lambda.parameterList?.parameters.orEmpty().all { it.type != null }
    is CSharpAnonymousMethodExpression -> lambda.parameterList != null && lambda.parameterList?.parameters.orEmpty().all { it.type != null }
    else -> false
}

/** A collection expression converts to arrays, spans, the collection interfaces and the types with a collection initializer. */
private fun CSharpOverloads.collection(to: SemanticType): Conversion {
    if (to is SemanticType.ArrayOf) return Conversion.IMPLICIT
    val name = r.definitionName(to) ?: return Conversion.UNKNOWN
    if (name in COLLECTION_TARGETS) return Conversion.IMPLICIT
    if (name in PRIMITIVES || name == OBJECT) return Conversion.NONE
    if (to is SemanticType.Library && (to.type.kind == IndexedTypeKind.DELEGATE || to.type.kind == IndexedTypeKind.ENUM)) return Conversion.NONE
    // an enum of the solution is no collection either: `GetShoppingCartAsync(customer, [a, b])` beside its `ShoppingCartType?` overload
    if (to is SemanticType.Source && (to.info.kind == TypeKind.ENUM || to.info.kind == TypeKind.DELEGATE)) return Conversion.NONE
    return Conversion.UNKNOWN
}

/** The methods a name not called stands for (a method group); null when it is no such name. */
internal fun CSharpOverloads.methodGroup(e: CSharpExpression): List<CSharpSymbol>? {
    val name = when (e) {
        is CSharpSimpleName -> e
        is CSharpMemberAccessExpression -> e.nameElement
        else -> null
    } ?: return null
    if (r.invocationOf(name) != null) return null
    val symbols = r.resolveName(name)?.symbols ?: return null
    return symbols.takeIf { s -> s.isNotEmpty() && s.all(r::isInMethodGroup) }
}

/** Whether the method group [group] of [value] is an extension method group on a receiver (`comp.InvokeAsync`), whose receiver is bound. */
private fun CSharpOverloads.isReducedGroup(value: CSharpExpression, group: List<CSharpSymbol>): Boolean {
    val name = (value as? CSharpMemberAccessExpression)?.nameElement ?: return false
    return group.any { r.expressions.isReduced(it, name) }
}

/** The methods of the group [value] as [RoslynMethodGroupConversion] sees them (the receiver of a reduced group their first argument). */
private fun CSharpOverloads.groupCandidates(value: CSharpExpression, group: List<CSharpSymbol>): List<RoslynMethodGroupConversion.Candidate> {
    val name = (value as? CSharpMemberAccessExpression)?.nameElement
    val receiver by lazy { name?.let(r.expressions::receiver) }
    return group.map { m ->
        val isReduced = name != null && r.expressions.isReduced(m, name)
        val all = r.signature(m, false) ?: throw RoslynMethodTypeInferrer.Unknown("the signature of ${SemanticTrace.show(m)} is not known")
        val parameters = if (isReduced) all.drop(1) else all
        RoslynMethodGroupConversion.Candidate(
            arity = r.expressions.methodArity(m), parameterTypes = parameters.map { it.type() }, refKinds = parameters.indices.map { refKind(m, it, isReduced) },
            returnType = { typeArguments -> r.returnType(m, typeArguments) },
            infer = { delegateTypes ->
                val self = if (isReduced) listOf(receiver ?: throw RoslynMethodTypeInferrer.Unknown("the receiver of ${SemanticTrace.show(m)} is not known")) else emptyList()
                when (val inferred = r.inference.inferFromTypes(m, all.map { it.type() }, self + delegateTypes)) {
                    is CSharpTypeInference.Inferred.Known -> when (constraintsHoldStrict(m, inferred.types)) {
                        true -> RoslynMethodGroupConversion.Inferred.Known(inferred.types)
                        false -> RoslynMethodGroupConversion.Inferred.Failed
                        null -> RoslynMethodGroupConversion.Inferred.Unknown("whether the constraints of ${SemanticTrace.show(m)} hold is not known")
                    }
                    CSharpTypeInference.Inferred.Failed -> RoslynMethodGroupConversion.Inferred.Failed
                    CSharpTypeInference.Inferred.Unknown -> RoslynMethodGroupConversion.Inferred.Unknown("see the `inference` line")
                }
            },
            substitute = { type, typeArguments -> if (typeArguments.isEmpty()) type else substitute(type, m, typeArguments) },
            receiverFits = { typeArguments ->
                if (!isReduced) true else {
                    val self = (if (typeArguments.isEmpty()) all.first().type() else substitute(all.first().type(), m, typeArguments))
                    val from = receiver
                    if (self == null || from == null) null else when (classify(from, self, userDefined = false)) {
                        Conversion.IDENTITY, Conversion.IMPLICIT -> true
                        Conversion.NONE -> false
                        Conversion.UNKNOWN -> null
                    }
                }
            },
        )
    }
}

/** §10.8: whether the method group [group] of [value] converts to the delegate type [to]; one trace line when that is not known. */
internal fun CSharpOverloads.methodGroupConversion(value: CSharpExpression, group: List<CSharpSymbol>, to: SemanticType): Conversion = try {
    val kinds = delegateRefKinds(to)?.map { it.ifEmpty { null } } ?: throw RoslynMethodTypeInferrer.Unknown("the ref kinds of ${SemanticTrace.show(to)} are not known")
    if (methodGroupConversion.conversion(groupCandidates(value, group), to, kinds)) Conversion.IMPLICIT else Conversion.NONE
} catch (e: RoslynMethodTypeInferrer.Unknown) {
    SemanticTrace.trace { "conversion of the method group `${SemanticTrace.text(value)}` at ${SemanticTrace.at(value)} to ${SemanticTrace.show(to)}: not known — ${e.describe()}" }
    Conversion.UNKNOWN
}

/** `MethodGroupReturnType` of the inference: what the group [value] returns for a delegate of [parameterTypes]; null when no one method is picked. */
internal fun CSharpOverloads.methodGroupReturnType(value: CSharpExpression, parameterTypes: List<SemanticType>): SemanticType? {
    val group = methodGroup(unparenthesized(value)) ?: throw RoslynMethodTypeInferrer.Unknown("`${SemanticTrace.text(value)}` is no method group")
    return when (val resolution = methodGroupConversion.resolve(groupCandidates(value, group), parameterTypes, parameterTypes.map { null }, null)) {
        is RoslynMethodGroupConversion.Resolution.Best -> resolution.returnType.takeIf { r.definitionName(it) != VOID }
        RoslynMethodGroupConversion.Resolution.None, RoslynMethodGroupConversion.Resolution.Ambiguous -> null
    }
}

/** The delegate of the function type of the method group [value] (C# 10): its unique signature; null when it has none. */
internal fun CSharpOverloads.methodGroupFunctionType(value: CSharpExpression): SemanticType? {
    val group = methodGroup(unparenthesized(value)) ?: throw RoslynMethodTypeInferrer.Unknown("`${SemanticTrace.text(value)}` is no method group")
    val source = methodGroupSource(group, value, isReducedGroup(value, group)) as? RoslynExpressionConversions.Source.MethodGroup
        ?: throw RoslynMethodTypeInferrer.Unknown("the signatures of the method group `${SemanticTrace.text(value)}` are not known")
    val method = expressionConversions.functionType(source) ?: return null
    val parameters = method.parameters.map { it.first ?: throw RoslynMethodTypeInferrer.Unknown("a parameter type of the method group `${SemanticTrace.text(value)}` is not known") }
    return roslynTypes.functionTypeDelegate(parameters, method.parameters.map { it.second }, method.returns)
        ?: throw RoslynMethodTypeInferrer.Unknown("the function type of the method group `${SemanticTrace.text(value)}` is a synthesized delegate (not modeled)")
}

/** A method group as [RoslynExpressionConversions] sees it (`BoundMethodGroup`): its methods with their signatures, the type arguments written. */
/** Whether [value] has no type of its own and is typed by its target: a lambda, a method group, `null`, `default`, `new()`, a collection. */
internal fun CSharpOverloads.targetTyped(value: CSharpExpression): Boolean {
    val e = unparenthesized(value)
    return e is CSharpAnonymousFunctionExpression || e is CSharpCollectionExpression || e is CSharpImplicitObjectCreationExpression || e is CSharpThrowExpression ||
        e.elementType == SyntaxKind.NullLiteralExpression || e.elementType == SyntaxKind.DefaultLiteralExpression || methodGroup(e) != null
}

/** The parameters of a lambda; null for an anonymous method without a parameter list (`delegate { … }`, any parameters). */
internal fun lambdaParameters(lambda: CSharpAnonymousFunctionExpression): List<CSharpParameter>? = when (lambda) {
    is CSharpSimpleLambdaExpression -> listOfNotNull(lambda.parameter)
    is CSharpParenthesizedLambdaExpression -> lambda.parameterList?.parameters.orEmpty()
    else -> (lambda as? CSharpAnonymousMethodExpression)?.parameterList?.parameters
}

/** The kinds of expression the port classifies alone ([RoslynExpressionConversions.Source]); null for the rest. */
private fun expressionSource(value: CSharpExpression): RoslynExpressionConversions.Source? = when {
    value.elementType == SyntaxKind.NullLiteralExpression -> RoslynExpressionConversions.Source.NullLiteral
    value.elementType == SyntaxKind.DefaultLiteralExpression -> RoslynExpressionConversions.Source.DefaultLiteral
    value is CSharpThrowExpression -> RoslynExpressionConversions.Source.ThrowExpression
    value is CSharpImplicitObjectCreationExpression -> RoslynExpressionConversions.Source.ImplicitObjectCreation
    value is CSharpAnonymousObjectCreationExpression -> RoslynExpressionConversions.Source.AnonymousObjectCreation
    value is CSharpInterpolatedStringExpression -> RoslynExpressionConversions.Source.InterpolatedString
    else -> null
}

private fun CSharpOverloads.methodGroupSource(group: List<CSharpSymbol>, value: CSharpExpression, reduced: Boolean): RoslynExpressionConversions.Source {
    val name = (value as? CSharpMemberAccessExpression)?.nameElement ?: value as? CSharpSimpleName
    val arity = (name as? CSharpGenericName)?.typeArgumentList?.arguments?.size ?: 0
    val methods = group.map { method ->
        val parameters = r.signature(method, false) ?: return RoslynExpressionConversions.Source.NotPorted
        RoslynExpressionConversions.Method(r.expressions.methodArity(method), parameters.mapIndexed { i, p -> p.type() to (refKind(method, i, false) ?: "") },
            r.returnType(method, emptyList()))
    }
    return RoslynExpressionConversions.Source.MethodGroup(methods, arity, reduced)
}

/** §10.2.11: a constant `int` converts to `sbyte`, `byte`, `short`, `ushort`, `uint`, `ulong` (and `long` to `ulong`) when it fits. */
private fun CSharpOverloads.constantFits(e: CSharpExpression, to: SemanticType): Boolean {
    val value = constantValue(e) ?: return false
    // `Take(4)` for a `byte?`: the constant converts to `byte`, then to its nullable (§10.6.1)
    val target = if (r.definitionName(to) == NULLABLE) (to as? SemanticType.Library)?.arguments?.firstOrNull() ?: return false else to
    val range = when (r.definitionName(target)) {
        "System.SByte" -> -128L..127L
        "System.Byte" -> 0L..255L
        "System.Int16" -> -32768L..32767L
        "System.UInt16" -> 0L..65535L
        "System.UInt32" -> 0L..0xFFFFFFFFL
        "System.UInt64" -> 0L..Long.MAX_VALUE
        else -> return false
    }
    return value in range
}

private fun CSharpOverloads.constantValue(e: CSharpExpression): Long? {
    val value = unparenthesized(e)
    if (value is CSharpPrefixUnaryExpression && value.operatorToken?.text == "-") return value.operand?.let(::constantValue)?.let { -it }
    if (value.elementType != SyntaxKind.NumericLiteralExpression) return null
    val keyword = CSharpNameResolver.numericKeyword(value.text)
    if (keyword != "int" && keyword != "long") return null
    val t = value.text.replace("_", "").lowercase().trimEnd('l', 'u')
    return when {
        t.startsWith("0x") -> t.drop(2).toLongOrNull(16)
        t.startsWith("0b") -> t.drop(2).toLongOrNull(2)
        else -> t.toLongOrNull()
    }
}
