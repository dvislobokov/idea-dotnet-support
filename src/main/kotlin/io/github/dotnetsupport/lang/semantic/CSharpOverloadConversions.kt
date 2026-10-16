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
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.OBJECT
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.PRIMITIVES
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.STRING
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.TASKS_OF
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.VALUE_TUPLE
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.VOID
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynConversions

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

private fun CSharpOverloads.isCandidateParameter(type: SemanticType.Parameter): Boolean = type.owner in openOwners

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
private fun CSharpOverloads.constraintsKnown(parameter: SemanticType.Parameter): Boolean {
    val owner = parameter.owner ?: return false
    val clauses = when (owner) {
        is CSharpTypeDeclaration -> owner.constraintClauses
        is CSharpMethodDeclaration -> owner.constraintClauses
        is CSharpLocalFunctionStatement -> owner.constraintClauses
        is CSharpDelegateDeclaration -> owner.constraintClauses
        else -> return false
    }
    val clause = clauses.firstOrNull { it.nameElement?.identifier?.text == parameter.name } ?: return true
    return clause.constraints.filterIsInstance<CSharpTypeConstraint>().size == r.constraintsOf(parameter).size
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
                val declaration = part.element() as? CSharpBaseTypeDeclaration ?: return@all part.element() is CSharpDelegateDeclaration
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
    when (value.elementType) {
        SyntaxKind.NullLiteralExpression -> return when {
            to is SemanticType.Parameter -> if (isReference(to) == true) Conversion.IMPLICIT else Conversion.UNKNOWN
            r.isNullable(to) -> Conversion.IMPLICIT
            isReference(to) == true -> Conversion.IMPLICIT
            r.isValueType(to) -> Conversion.NONE
            else -> Conversion.UNKNOWN
        }
        SyntaxKind.DefaultLiteralExpression -> return Conversion.IMPLICIT
    }
    when (value) {
        is CSharpImplicitObjectCreationExpression, is CSharpThrowExpression -> return Conversion.IMPLICIT
        is CSharpAnonymousFunctionExpression -> return if (strict) strictLambda(value, to) else lambda(value, to)
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
        is CSharpInterpolatedStringExpression -> {
            val tn = r.definitionName(to)
            if (tn == STRING) return Conversion.IDENTITY
            if (tn == "System.FormattableString" || tn == "System.IFormattable" || tn == OBJECT) return Conversion.IMPLICIT
            // every interpolated string converts to a handler type, a constant one too (Roslyn's `GetInterpolatedStringConversion`); being
            // constant matters to the better conversion only (§12.6.4.5: `string` beats the handler then) — asking it here left 222 calls of
            // `StringBuilder.AppendLine($"…")` not known (D6c2)
            if (isHandler(to)) return Conversion.IMPLICIT
        }
    }
    methodGroup(value)?.let { group ->
        // `compilation.GetBestTypeByMetadataName` of an extension method: its receiver is bound, the delegate takes the rest (MudBlazor.Analyzers)
        val name = (value as? CSharpMemberAccessExpression)?.nameElement
        val reduced = name != null && group.any { r.expressions.isReduced(it, name) }
        return if (strict) (if (reduced) Conversion.UNKNOWN else strictGroup(group, to)) else groupConversion(group, to, reduced)
    }
    val source = type ?: return Conversion.UNKNOWN
    val conversion = classify(source, to)
    if (conversion == Conversion.NONE && constantFits(value, to)) return Conversion.IMPLICIT
    return conversion
}

private fun CSharpOverloads.lambda(lambda: CSharpAnonymousFunctionExpression, to: SemanticType): Conversion {
    if (to is SemanticType.Parameter) return Conversion.UNKNOWN
    val target = r.expressions.unwrapExpression(to) ?: return Conversion.UNKNOWN
    if (r.expressions.delegateSignature(target) == null) {
        val tn = r.definitionName(target)
        // the natural type of a lambda (C# 10) goes to `Delegate`, `object` and `Expression`; a lambda without one has no conversion
        // there (CS8917): `MapGet("/", context => …)` takes the `RequestDelegate` overload, never the `Delegate` one
        if (tn in NATURAL_TARGETS) return if (hasNaturalType(lambda)) Conversion.IMPLICIT else Conversion.NONE
        return if (closed(target)) Conversion.NONE else Conversion.UNKNOWN
    }
    // a body that does not use untyped parameters is typed before the call is resolved: `() => 1` is no `Action`, `() => Log()` no `Func<int>`
    if (!r.expressions.lambdaFits(lambda, to, typeBody = hasNaturalType(lambda) || r.expressions.lambdaParameterCount(lambda) == 0)) return Conversion.NONE
    return Conversion.IMPLICIT
}

/**
 * [lambda] to [to] where only what is sure counts ([strict]): the number of parameters, their `ref` kinds and written types, a value
 * for a delegate that returns none; a body is taken as valid (its own errors are reported in it), but its type must be known where
 * the delegate returns a value. Async lambdas and blocks whose end is reachable are not judged.
 */
private fun CSharpOverloads.strictLambda(lambda: CSharpAnonymousFunctionExpression, to: SemanticType): Conversion {
    if (to is SemanticType.Parameter) return Conversion.UNKNOWN
    val target = r.expressions.unwrapExpression(to) ?: return Conversion.UNKNOWN
    val delegate = r.expressions.delegateSignature(target)
    if (delegate == null) {
        val tn = r.definitionName(target)
        if (tn in NATURAL_TARGETS) return if (hasNaturalType(lambda)) Conversion.IMPLICIT else Conversion.NONE
        return if (noDelegate(target)) Conversion.NONE else Conversion.UNKNOWN
    }
    val count = r.expressions.lambdaParameterCount(lambda)
    val parameters = when (lambda) {
        is CSharpSimpleLambdaExpression -> listOfNotNull(lambda.parameter)
        is CSharpParenthesizedLambdaExpression -> lambda.parameterList?.parameters.orEmpty()
        else -> (lambda as? CSharpAnonymousMethodExpression)?.parameterList?.parameters
    }
    if (count != null && count != delegate.first.size) return Conversion.NONE
    val kinds = delegateRefKinds(target) ?: return Conversion.UNKNOWN
    var unknown = false
    if (parameters != null) for ((i, p) in parameters.withIndex()) {
        val written = lambdaRefKind(p) ?: return Conversion.UNKNOWN
        val expected = kinds.getOrNull(i) ?: return Conversion.UNKNOWN
        if (written != expected) return if (written == "in" && expected == "ref") Conversion.UNKNOWN else Conversion.NONE
        val syntax = p.type ?: continue
        val type = r.resolveType(syntax) ?: return Conversion.UNKNOWN
        val wanted = delegate.first.getOrNull(i) ?: return Conversion.UNKNOWN
        if (mentionsCandidateParameter(wanted)) return Conversion.UNKNOWN
        when (same(type, wanted)) {
            false -> return Conversion.NONE
            null -> unknown = true
            true -> {}
        }
    }
    if (unknown) return Conversion.UNKNOWN
    if (delegate.first.any { it == null || mentionsCandidateParameter(it) }) return Conversion.UNKNOWN
    val written = delegate.second ?: return Conversion.UNKNOWN
    if (mentionsCandidateParameter(written)) return Conversion.UNKNOWN
    // an async lambda to a delegate returning `Task<Y>` / `ValueTask<Y>`: what it returns converts to `Y` (§10.7.1) — nopCommerce's
    // `GetAsync<T>(key, Func<Task<T>>)` and `GetAsync<T>(key, Func<T>)` are then both applicable and the first one is more specific
    val returns = if (!r.expressions.isAsync(lambda.modifiers)) written
        else if (r.definitionName(written) in TASKS_OF) (written as? SemanticType.Library)?.arguments?.singleOrNull() ?: return Conversion.UNKNOWN
        else return Conversion.UNKNOWN
    val void = r.definitionName(returns) == VOID
    // the body bound for this delegate, as Roslyn's `HasAnonymousFunctionConversion` binds it: implicit parameters take the delegate's types
    // (D6c2) — typed through the call being checked they were a cycle, and `Add(x => x.Severity, …)` of bUnit was not known (3541 lines)
    val implicit = parameters?.takeIf { ps -> ps.isNotEmpty() && ps.all { it.type == null } }
    fun body(compute: () -> Conversion): Conversion = if (implicit == null) compute() else try {
        r.bindLambda(lambda, implicit, delegate.first.map { it ?: return Conversion.UNKNOWN }, compute)
    } catch (e: io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodTypeInferrer.Unknown) {
        SemanticTrace.trace { "lambda at ${SemanticTrace.at(lambda)} to ${SemanticTrace.show(to)}: not known — ${e.describe()}" }
        Conversion.UNKNOWN
    }
    val body = lambda.expressionBody
    if (body != null) {
        if (void) return if (r.expressions.isStatementExpression(unparenthesized(body))) Conversion.IMPLICIT else Conversion.NONE
        return body {
            when (argument(body, r.typeOf(unparenthesized(body)), returns)) {
                Conversion.IDENTITY, Conversion.IMPLICIT -> Conversion.IMPLICIT
                Conversion.NONE -> Conversion.NONE
                Conversion.UNKNOWN -> Conversion.UNKNOWN
            }
        }
    }
    val block = lambda.block ?: return Conversion.UNKNOWN
    val returned = returnsOf(block, lambda)
    if (void) return if (returned.all { it.expression == null }) Conversion.IMPLICIT else Conversion.UNKNOWN
    if (returned.isEmpty() || returned.any { it.expression == null }) return Conversion.UNKNOWN
    if (CSharpReachability(r).endOf(block) != CSharpReachability.Reach.NO) return Conversion.UNKNOWN
    return body {
        var result = Conversion.IMPLICIT
        for (statement in returned) {
            val value = statement.expression ?: return@body Conversion.UNKNOWN
            when (argument(value, r.typeOf(unparenthesized(value)), returns)) {
                Conversion.NONE -> return@body Conversion.NONE
                Conversion.UNKNOWN -> result = Conversion.UNKNOWN
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
private fun CSharpOverloads.returnsOf(block: CSharpBlock, lambda: CSharpAnonymousFunctionExpression): List<CSharpReturnStatement> =
    PsiTreeUtil.findChildrenOfType(block, CSharpReturnStatement::class.java).filter {
        PsiTreeUtil.getParentOfType(it, CSharpAnonymousFunctionExpression::class.java, CSharpLocalFunctionStatement::class.java) == lambda
    }

/** The type the body of [lambda] gives: its expression, or what every `return` gives, the same type; null when not known. */
internal fun CSharpOverloads.strictReturnType(lambda: CSharpAnonymousFunctionExpression): SemanticType? {
    if (r.expressions.isAsync(lambda.modifiers)) return null
    lambda.expressionBody?.let { return r.typeOf(it) }
    val block = lambda.block ?: return null
    val types = returnsOf(block, lambda).map { s -> s.expression?.let(r::typeOf) ?: return null }
    val first = types.firstOrNull() ?: return null
    return first.takeIf { types.all { same(it, first) == true } }
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

/**
 * A method group to [to], strictly: methods of the group that are not generic and have neither optional, `params` nor by-reference
 * parameters, compatible by their return type (C# 7.3) and applicable to the delegate's parameter types; NONE when none is, IMPLICIT
 * when exactly one is and its parameters take the delegate's by identity or reference conversion.
 */
private fun CSharpOverloads.strictGroup(methods: List<CSharpSymbol>, to: SemanticType): Conversion {
    if (to is SemanticType.Parameter) return Conversion.UNKNOWN
    val target = r.expressions.unwrapExpression(to) ?: return Conversion.UNKNOWN
    val delegate = r.expressions.delegateSignature(target)
    if (delegate == null) {
        val tn = r.definitionName(target)
        if (tn == "System.Delegate" || tn == "System.MulticastDelegate" || tn == OBJECT) return Conversion.UNKNOWN
        return if (noDelegate(target)) Conversion.NONE else Conversion.UNKNOWN
    }
    if (delegateRefKinds(target)?.all { it.isEmpty() } != true) return Conversion.UNKNOWN
    val inputs = delegate.first.map { it ?: return Conversion.UNKNOWN }
    val returns = delegate.second ?: return Conversion.UNKNOWN
    if (inputs.any(::mentionsCandidateParameter) || mentionsCandidateParameter(returns)) return Conversion.UNKNOWN
    val compatible = ArrayList<List<CSharpNameResolver.Parameter>>()
    for (method in methods) {
        if (r.isGeneric(method)) return Conversion.UNKNOWN
        val parameters = r.signature(method, false) ?: return Conversion.UNKNOWN
        if (parameters.any { it.optional || it.isParams || it.byRef } || hasRefParameters(method)) return Conversion.UNKNOWN
        if (parameters.size != inputs.size) continue
        val result = r.returnType(method, emptyList()) ?: return Conversion.UNKNOWN
        val returnFits = when {
            r.definitionName(returns) == VOID -> r.definitionName(result) == VOID
            r.definitionName(result) == VOID -> false
            else -> when (same(result, returns)) {
                true -> true
                null -> return Conversion.UNKNOWN
                false -> if (isReference(result) == false || isReference(returns) == false) false
                else when (classify(result, returns, userDefined = false)) {
                    Conversion.IMPLICIT, Conversion.IDENTITY -> if (isReference(result) == true && isReference(returns) == true) true else return Conversion.UNKNOWN
                    Conversion.NONE -> false
                    Conversion.UNKNOWN -> return Conversion.UNKNOWN
                }
            }
        }
        if (!returnFits) continue
        var applicable = true
        for ((p, input) in parameters.zip(inputs)) {
            val type = p.type() ?: return Conversion.UNKNOWN
            when (classify(input, type)) {
                Conversion.NONE -> { applicable = false; break }
                Conversion.UNKNOWN -> return Conversion.UNKNOWN
                else -> {}
            }
        }
        if (applicable) compatible += parameters
    }
    if (compatible.isEmpty()) return Conversion.NONE
    val single = compatible.singleOrNull() ?: return Conversion.UNKNOWN
    for ((p, input) in single.zip(inputs)) {
        val type = p.type() ?: return Conversion.UNKNOWN
        if (same(input, type) == true) continue
        if (isReference(input) == true && isReference(type) == true) continue
        return Conversion.UNKNOWN
    }
    return Conversion.IMPLICIT
}

private fun CSharpOverloads.hasRefParameters(method: CSharpSymbol): Boolean = when (method) {
    is CSharpSymbol.LibraryMember -> r.session.parameters(method.member).any { it.isByReference }
    is CSharpSymbol.SourceMember -> parameterList(method.element)?.parameters?.any { p -> p.modifiers.any { it.text != "this" } } ?: true
    else -> true
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
    return Conversion.UNKNOWN
}

/** The methods a name not called stands for (a method group); null when it is no such name. */
private fun CSharpOverloads.methodGroup(e: CSharpExpression): List<CSharpSymbol>? {
    val name = when (e) {
        is CSharpSimpleName -> e
        is CSharpMemberAccessExpression -> e.nameElement
        else -> null
    } ?: return null
    if (r.invocationOf(name) != null) return null
    val symbols = r.resolveName(name)?.symbols ?: return null
    return symbols.takeIf { s -> s.isNotEmpty() && s.all(r::isInMethodGroup) }
}

private fun CSharpOverloads.groupConversion(methods: List<CSharpSymbol>, to: SemanticType, reduced: Boolean = false): Conversion {
    if (to is SemanticType.Parameter) return Conversion.UNKNOWN
    val target = r.expressions.unwrapExpression(to) ?: return Conversion.UNKNOWN
    val delegate = r.expressions.delegateSignature(target)
    if (delegate == null) {
        val tn = r.definitionName(target)
        if (tn == "System.Delegate" || tn == "System.MulticastDelegate" || tn == OBJECT) return Conversion.UNKNOWN
        return if (closed(target)) Conversion.NONE else Conversion.UNKNOWN
    }
    val count = delegate.first.size
    val fitting = methods.filter { m -> r.signature(m, reduced && r.isExtension(m))?.let { s -> s.size >= count && s.count { !it.optional && !it.isParams } <= count } ?: true }
    return if (fitting.isEmpty()) Conversion.NONE else Conversion.IMPLICIT
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
