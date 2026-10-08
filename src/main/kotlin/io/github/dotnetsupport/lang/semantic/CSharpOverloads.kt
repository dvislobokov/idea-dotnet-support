package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.TypeKind
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver.Conversion
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
 */
internal class CSharpOverloads(private val r: CSharpNameResolver) {
    private val known = IdentityHashMap<Any, Boolean>()

    // ---- identity

    /** Whether [a] and [b] are the same type (tuple element names aside); null when a part of either is not known. */
    fun same(a: SemanticType?, b: SemanticType?): Boolean? {
        if (a == null || b == null) return null
        if (a is SemanticType.Parameter || b is SemanticType.Parameter) {
            if (a is SemanticType.Parameter && b is SemanticType.Parameter && a.ofMethod == b.ofMethod && a.owner == b.owner && a.name == b.name && a.index == b.index) return true
            // a type parameter of a library method that inference left open may stand for anything
            if (isOpen(a) || isOpen(b)) return null
            return false
        }
        return when (a) {
            is SemanticType.ArrayOf -> if (b !is SemanticType.ArrayOf || a.rank != b.rank) false else same(a.element, b.element)
            is SemanticType.Library -> if (b !is SemanticType.Library || a.type.fullName != b.type.fullName) false else sameArguments(a.arguments, b.arguments)
            is SemanticType.Source -> if (b !is SemanticType.Source || a.info.key != b.info.key) false else sameArguments(a.arguments, b.arguments)
            is SemanticType.Parameter -> null
        }
    }

    private fun sameArguments(a: List<SemanticType?>, b: List<SemanticType?>): Boolean? {
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
    private fun isOpen(type: SemanticType): Boolean = type is SemanticType.Parameter && type.ofMethod && (type.owner == null || type.owner is CSharpMethodDeclaration && isCandidateParameter(type))

    // the method type parameters of the candidates being resolved: a parameter of the caller's own method is a type like any other
    private val openOwners = HashSet<PsiElement>()

    private fun isCandidateParameter(type: SemanticType.Parameter): Boolean = type.owner in openOwners

    // ---- conversions between types

    /** The implicit conversion from [from] to [to] (§10.2); [userDefined] false: the standard ones only (the operand of a user-defined one). */
    fun classify(from: SemanticType, to: SemanticType, userDefined: Boolean = true, depth: Int = 0): Conversion {
        if (depth > 8) return Conversion.UNKNOWN
        when (same(from, to)) {
            true -> return Conversion.IDENTITY
            null -> if (from !is SemanticType.Parameter && to !is SemanticType.Parameter && r.definitionName(from) == r.definitionName(to)) return Conversion.IDENTITY
            false -> {}
        }
        if (from is SemanticType.Parameter || to is SemanticType.Parameter) return parameter(from, to, depth)
        val fn = r.definitionName(from)
        val tn = r.definitionName(to)
        if (tn == OBJECT) return Conversion.IMPLICIT
        if (fn != null && fn == tn) return sameDefinition(from, to, depth)
        if (tn == NULLABLE) {
            val inner = (to as SemanticType.Library).arguments.firstOrNull() ?: return Conversion.UNKNOWN
            val core = if (fn == NULLABLE) (from as SemanticType.Library).arguments.firstOrNull() ?: return Conversion.UNKNOWN else from
            return when (classify(core, inner, userDefined, depth + 1)) {
                Conversion.IDENTITY, Conversion.IMPLICIT -> Conversion.IMPLICIT
                Conversion.NONE -> Conversion.NONE
                Conversion.UNKNOWN -> Conversion.UNKNOWN
            }
        }
        if (fn == NULLABLE) {
            val core = (from as SemanticType.Library).arguments.firstOrNull() ?: return Conversion.UNKNOWN
            if (tn == VALUE_TYPE) return Conversion.IMPLICIT
            if (isInterface(to)) return classify(core, to, false, depth + 1).let { if (it == Conversion.IDENTITY) Conversion.IMPLICIT else it }
            return if (closed(core) && closed(to)) Conversion.NONE else Conversion.UNKNOWN
        }
        if (fn != null && tn != null) NUMERIC[fn]?.let { if (tn in it) return Conversion.IMPLICIT }
        supertype(from, to, depth)?.let { return it }
        if (fn in PRIMITIVES && tn in PRIMITIVES) return Conversion.NONE
        if (userDefined) when (userConversion(from, to, depth)) {
            true -> return Conversion.IMPLICIT
            null -> return Conversion.UNKNOWN
            false -> {}
        }
        return if (closed(from) && closed(to)) Conversion.NONE else Conversion.UNKNOWN
    }

    private fun ok(c: Conversion) = c == Conversion.IDENTITY || c == Conversion.IMPLICIT

    /** Two instances of one generic definition (or two arrays): identity, variance, array covariance. */
    private fun sameDefinition(from: SemanticType, to: SemanticType, depth: Int): Conversion {
        if (from is SemanticType.ArrayOf && to is SemanticType.ArrayOf) {
            if (from.rank != to.rank) return Conversion.NONE
            val a = from.element ?: return Conversion.IDENTITY
            val b = to.element ?: return Conversion.IDENTITY
            if (same(a, b) == true) return Conversion.IDENTITY
            return when (isReference(a)) {
                true -> classify(a, b, false, depth + 1).let { if (ok(it)) Conversion.IMPLICIT else it }
                false -> if (closed(a) && closed(b)) Conversion.NONE else Conversion.UNKNOWN
                null -> Conversion.UNKNOWN
            }
        }
        if (same(from, to) == null) return Conversion.IDENTITY
        return variance(from, to, depth) ?: Conversion.NONE
    }

    /** `IEnumerable<string>` to `IEnumerable<object>`: the variant type parameters of a library interface or delegate. */
    private fun variance(from: SemanticType, to: SemanticType, depth: Int): Conversion? {
        if (from !is SemanticType.Library || to !is SemanticType.Library || from.type.fullName != to.type.fullName) return null
        val parameters = to.type.typeParameters
        if (parameters.size != to.arguments.size || from.arguments.size != to.arguments.size) return null
        for ((i, p) in parameters.withIndex()) {
            val a = from.arguments[i] ?: return Conversion.UNKNOWN
            val b = to.arguments[i] ?: return Conversion.UNKNOWN
            when (same(a, b)) {
                true -> continue
                null -> return Conversion.UNKNOWN
                false -> {}
            }
            val fits = when {
                p.isCovariant -> isReference(a) == true && ok(classify(a, b, false, depth + 1))
                p.isContravariant -> isReference(b) == true && ok(classify(b, a, false, depth + 1))
                else -> false
            }
            if (!fits) return null
        }
        return Conversion.IMPLICIT
    }

    /** A reference or boxing conversion: [to] among the base types and interfaces of [from]; null when it is not there. */
    private fun supertype(from: SemanticType, to: SemanticType, depth: Int): Conversion? {
        if (to is SemanticType.Library && to.arguments.isNotEmpty()) {
            // every instance of the definition among the supertypes: `string` is `IEnumerable<char>`, not `IEnumerable<string>`
            val instances = instances(from, to.type.fullName, 0) ?: return null
            if (instances.isEmpty()) return null
            var unknown = false
            for (instance in instances) {
                when (same(instance, to)) {
                    true -> return Conversion.IMPLICIT
                    null -> unknown = true
                    false -> when (variance(instance, to, depth)) {
                        Conversion.IMPLICIT -> return Conversion.IMPLICIT
                        Conversion.UNKNOWN -> unknown = true
                        else -> {}
                    }
                }
            }
            return if (unknown || !closed(from)) Conversion.UNKNOWN else Conversion.NONE
        }
        val instance: SemanticType = when (to) {
            is SemanticType.Library -> r.expressions.instanceOf(from, to.type.fullName)
            is SemanticType.Source -> sourceInstance(from, to.info.key, 0)
            else -> null
        } ?: return null
        val arguments = (to as? SemanticType.Library)?.arguments ?: (to as SemanticType.Source).arguments
        if (arguments.isEmpty()) return Conversion.IMPLICIT
        return when (same(instance, to)) {
            true, null -> Conversion.IMPLICIT
            // a type may implement an interface twice, with other arguments: only variance says yes here
            false -> variance(instance, to, depth)?.takeIf { it == Conversion.IMPLICIT } ?: Conversion.UNKNOWN
        }
    }

    /** The instances of the generic library type [definition] that [type] is or derives from; null when they cannot be listed. */
    private fun instances(type: SemanticType, definition: String, depth: Int): List<SemanticType.Library>? {
        if (depth > 16) return null
        return when (type) {
            is SemanticType.Library -> {
                if (type.type.fullName == definition) return listOf(type)
                val assemblies = r.assemblies
                (r.session.baseTypes(assemblies, type.type) + r.session.interfaces(assemblies, type.type)).filter { it.type.fullName == definition }
                    .map { SemanticType.Library(it.type, it.arguments.map { a -> r.fromRef(a, type.arguments) }) }
            }
            is SemanticType.Source -> r.baseTypes(type).flatMap { instances(it, definition, depth + 1) ?: return null }.distinctBy { it.toString() }
            is SemanticType.ArrayOf -> listOfNotNull(r.expressions.instanceOf(type, definition))
            is SemanticType.Parameter -> r.constraintsOf(type).flatMap { instances(it, definition, depth + 1) ?: return null }
        }
    }

    private fun sourceInstance(type: SemanticType, key: String, depth: Int): SemanticType.Source? {
        if (depth > 16) return null
        return when (type) {
            is SemanticType.Source -> if (type.info.key == key) type else r.baseTypes(type).firstNotNullOfOrNull { sourceInstance(it, key, depth + 1) }
            is SemanticType.Parameter -> r.constraintsOf(type).firstNotNullOfOrNull { sourceInstance(it, key, depth + 1) }
            else -> null
        }
    }

    /** Conversions where a type parameter is involved: by its constraints; NONE only when they are all known. */
    private fun parameter(from: SemanticType, to: SemanticType, depth: Int): Conversion {
        if (to is SemanticType.Parameter) {
            if (to.ofMethod && (to.owner == null || isCandidateParameter(to))) return Conversion.UNKNOWN
            if (from is SemanticType.Parameter) {
                // `where T : U`: T converts to U
                for (constraint in r.constraintsOf(from)) if (ok(classify(constraint, to, false, depth + 1))) return Conversion.IMPLICIT
                return if (constraintsKnown(from)) Conversion.NONE else Conversion.UNKNOWN
            }
            // nothing converts to a type parameter but itself (and `null` / `default`, which are expressions)
            return if (to.owner != null && closed(from)) Conversion.NONE else Conversion.UNKNOWN
        }
        val p = from as SemanticType.Parameter
        if (p.ofMethod && (p.owner == null || isCandidateParameter(p))) return Conversion.UNKNOWN
        val tn = r.definitionName(to)
        if (tn == OBJECT) return Conversion.IMPLICIT
        if (tn == VALUE_TYPE && r.isValueType(p)) return Conversion.IMPLICIT
        for (constraint in r.constraintsOf(p)) if (ok(classify(constraint, to, false, depth + 1))) return Conversion.IMPLICIT
        return if (p.owner != null && constraintsKnown(p) && closed(to)) Conversion.NONE else Conversion.UNKNOWN
    }

    /** Whether every constraint of [parameter] written as a type resolves. */
    private fun constraintsKnown(parameter: SemanticType.Parameter): Boolean {
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

    /** A user-defined implicit operator of [from] or [to] whose operand takes [from] and whose result goes to [to]; null when not known. */
    private fun userConversion(from: SemanticType, to: SemanticType, depth: Int): Boolean? {
        val operators = (operators(from) ?: return null) + (operators(to) ?: return null) +
            ((to as? SemanticType.Library)?.takeIf { it.type.fullName == NULLABLE }?.arguments?.firstOrNull()?.let { operators(it) ?: return null }.orEmpty())
        var unknown = false
        for ((operand, result) in operators) {
            if (operand == null || result == null) { unknown = true; continue }
            val into = classify(from, operand, false, depth + 1)
            val out = classify(result, to, false, depth + 1)
            if (ok(into) && ok(out)) return true
            if (into == Conversion.UNKNOWN && out != Conversion.NONE || out == Conversion.UNKNOWN && into != Conversion.NONE) unknown = true
        }
        return if (unknown) null else false
    }

    /** The implicit operators declared by [type] and its base types (operand, result); null when they cannot be known. */
    private fun operators(type: SemanticType): List<Pair<SemanticType?, SemanticType?>>? = when (type) {
        is SemanticType.Library -> r.membersNamed(type, "op_Implicit", 0).mapNotNull { symbol ->
            if (!r.isMethod(symbol)) return@mapNotNull null
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
            for (base in r.libraryBases(type)) found += operators(base) ?: return null
            found
        }
        is SemanticType.ArrayOf -> emptyList()
        is SemanticType.Parameter -> null
    }

    internal fun isInterface(type: SemanticType): Boolean = when (type) {
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.INTERFACE
        is SemanticType.Source -> type.info.kind == TypeKind.INTERFACE
        else -> false
    }

    /** Whether [type] is a reference type; null when that is not known (a type parameter without `class`). */
    fun isReference(type: SemanticType): Boolean? = when (type) {
        is SemanticType.ArrayOf -> true
        is SemanticType.Library -> type.type.kind != IndexedTypeKind.STRUCT && type.type.kind != IndexedTypeKind.ENUM
        is SemanticType.Source -> type.info.kind?.let { it != TypeKind.STRUCT && it != TypeKind.RECORD_STRUCT && it != TypeKind.ENUM }
        is SemanticType.Parameter -> if (r.isValueType(type)) false else null
    }

    /** A type whose base types and interfaces all resolve, all the way up: what it does not convert to, it surely does not. */
    fun closed(type: SemanticType, depth: Int = 0): Boolean {
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

    private fun libraryKnown(type: io.github.dotnetsupport.index.IndexedType, depth: Int): Boolean = known.getOrPut(type) {
        if (depth > 16) return@getOrPut false
        (type.interfaces + listOfNotNull(type.baseType)).all { reference -> r.assemblies.resolve(reference)?.let { libraryKnown(it, depth + 1) } == true }
    }

    // ---- conversions of argument expressions

    fun unparenthesized(e: CSharpExpression): CSharpExpression {
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
    fun argument(e: CSharpExpression, type: SemanticType?, to: SemanticType, depth: Int = 0): Conversion {
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
            is CSharpTupleExpression -> if (type == null) return Conversion.UNKNOWN
            is CSharpInterpolatedStringExpression -> {
                val tn = r.definitionName(to)
                if (tn == STRING) return Conversion.IDENTITY
                if (tn == "System.FormattableString" || tn == "System.IFormattable" || tn == OBJECT) return Conversion.IMPLICIT
                // a constant interpolated string (`$"{nameof(x)} is null"`) is a string and converts to no handler
                if (isHandler(to)) return when (constantInterpolation(value)) { false -> Conversion.IMPLICIT; true -> Conversion.NONE; null -> Conversion.UNKNOWN }
            }
        }
        methodGroup(value)?.let { return if (strict) strictGroup(it, to) else groupConversion(it, to) }
        val source = type ?: return Conversion.UNKNOWN
        val conversion = classify(source, to)
        if (conversion == Conversion.NONE && constantFits(value, to)) return Conversion.IMPLICIT
        return conversion
    }

    private fun lambda(lambda: CSharpAnonymousFunctionExpression, to: SemanticType): Conversion {
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
    private fun strictLambda(lambda: CSharpAnonymousFunctionExpression, to: SemanticType): Conversion {
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
        if (unknown || r.expressions.isAsync(lambda.modifiers)) return Conversion.UNKNOWN
        if (delegate.first.any { it == null || mentionsCandidateParameter(it) }) return Conversion.UNKNOWN
        val returns = delegate.second ?: return Conversion.UNKNOWN
        if (mentionsCandidateParameter(returns)) return Conversion.UNKNOWN
        val void = r.definitionName(returns) == VOID
        val body = lambda.expressionBody
        if (body != null) {
            if (void) return if (isStatementExpression(unparenthesized(body))) Conversion.IMPLICIT else Conversion.NONE
            return when (argument(body, r.typeOf(unparenthesized(body)), returns)) {
                Conversion.IDENTITY, Conversion.IMPLICIT -> Conversion.IMPLICIT
                Conversion.NONE -> Conversion.NONE
                Conversion.UNKNOWN -> Conversion.UNKNOWN
            }
        }
        val block = lambda.block ?: return Conversion.UNKNOWN
        val returned = returnsOf(block, lambda)
        if (void) return if (returned.all { it.expression == null }) Conversion.IMPLICIT else Conversion.UNKNOWN
        if (returned.isEmpty() || returned.any { it.expression == null }) return Conversion.UNKNOWN
        if (CSharpReachability(r).endOf(block) != CSharpReachability.Reach.NO) return Conversion.UNKNOWN
        var result = Conversion.IMPLICIT
        for (statement in returned) {
            val value = statement.expression ?: return Conversion.UNKNOWN
            when (argument(value, r.typeOf(unparenthesized(value)), returns)) {
                Conversion.NONE -> return Conversion.NONE
                Conversion.UNKNOWN -> result = Conversion.UNKNOWN
                else -> {}
            }
        }
        return result
    }

    /** A type no lambda or method group converts to: closed, no interface, without user-defined conversions. */
    private fun noDelegate(type: SemanticType): Boolean {
        if (!closed(type) || isInterface(type) || type is SemanticType.ArrayOf) return type is SemanticType.ArrayOf
        if (type is SemanticType.Library && type.type.kind == IndexedTypeKind.DELEGATE) return false
        val operators = operators(type) ?: return false
        return operators.isEmpty() || type is SemanticType.Library && type.type.fullName == STRING
    }

    private fun mentionsCandidateParameter(type: SemanticType): Boolean = when (type) {
        is SemanticType.Parameter -> isOpen(type) || isCandidateParameter(type)
        is SemanticType.ArrayOf -> type.element?.let(::mentionsCandidateParameter) ?: true
        is SemanticType.Library -> type.arguments.any { it == null || mentionsCandidateParameter(it) }
        is SemanticType.Source -> type.arguments.any { it == null || mentionsCandidateParameter(it) }
    }

    /** The `return` statements of [lambda]'s own body (not of the lambdas and local functions in it). */
    private fun returnsOf(block: CSharpBlock, lambda: CSharpAnonymousFunctionExpression): List<CSharpReturnStatement> =
        PsiTreeUtil.findChildrenOfType(block, CSharpReturnStatement::class.java).filter {
            PsiTreeUtil.getParentOfType(it, CSharpAnonymousFunctionExpression::class.java, CSharpLocalFunctionStatement::class.java) == lambda
        }

    /** The type the body of [lambda] gives: its expression, or what every `return` gives, the same type; null when not known. */
    private fun strictReturnType(lambda: CSharpAnonymousFunctionExpression): SemanticType? {
        if (r.expressions.isAsync(lambda.modifiers)) return null
        lambda.expressionBody?.let { return r.typeOf(it) }
        val block = lambda.block ?: return null
        val types = returnsOf(block, lambda).map { s -> s.expression?.let(r::typeOf) ?: return null }
        val first = types.firstOrNull() ?: return null
        return first.takeIf { types.all { same(it, first) == true } }
    }

    private fun isStatementExpression(e: CSharpExpression): Boolean = e is CSharpInvocationExpression || e is CSharpAssignmentExpression ||
        e is CSharpAwaitExpression || e is CSharpBaseObjectCreationExpression || e is CSharpPostfixUnaryExpression ||
        (e is CSharpPrefixUnaryExpression && (e.operatorToken?.text == "++" || e.operatorToken?.text == "--")) || e is CSharpThrowExpression

    /** `ref`, `out`, `in` or "" (by value) of a parameter of a lambda; null for `scoped`, `ref readonly` and what else is not modeled. */
    internal fun lambdaRefKind(p: CSharpParameter): String? {
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
    internal fun delegateRefKinds(type: SemanticType): List<String>? = when (type) {
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
    private fun strictGroup(methods: List<CSharpSymbol>, to: SemanticType): Conversion {
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

    private fun hasRefParameters(method: CSharpSymbol): Boolean = when (method) {
        is CSharpSymbol.LibraryMember -> r.session.parameters(method.member).any { it.isByReference }
        is CSharpSymbol.SourceMember -> parameterList(method.element)?.parameters?.any { p -> p.modifiers.any { it.text != "this" } } ?: true
        else -> true
    }

    /** C# 10: a lambda whose parameters are typed (or that takes none) has a function type, `Func<…>` / `Action<…>`. */
    fun hasNaturalType(lambda: CSharpAnonymousFunctionExpression): Boolean = when (lambda) {
        is CSharpParenthesizedLambdaExpression -> lambda.parameterList?.parameters.orEmpty().all { it.type != null }
        is CSharpAnonymousMethodExpression -> lambda.parameterList != null && lambda.parameterList?.parameters.orEmpty().all { it.type != null }
        else -> false
    }

    /** A collection expression converts to arrays, spans, the collection interfaces and the types with a collection initializer. */
    private fun collection(to: SemanticType): Conversion {
        if (to is SemanticType.ArrayOf) return Conversion.IMPLICIT
        val name = r.definitionName(to) ?: return Conversion.UNKNOWN
        if (name in COLLECTION_TARGETS) return Conversion.IMPLICIT
        if (name in PRIMITIVES || name == OBJECT) return Conversion.NONE
        if (to is SemanticType.Library && (to.type.kind == IndexedTypeKind.DELEGATE || to.type.kind == IndexedTypeKind.ENUM)) return Conversion.NONE
        return Conversion.UNKNOWN
    }

    /** The methods a name not called stands for (a method group); null when it is no such name. */
    private fun methodGroup(e: CSharpExpression): List<CSharpSymbol>? {
        val name = when (e) {
            is CSharpSimpleName -> e
            is CSharpMemberAccessExpression -> e.nameElement
            else -> null
        } ?: return null
        if (r.invocationOf(name) != null) return null
        val symbols = r.resolveName(name)?.symbols ?: return null
        return symbols.takeIf { s -> s.isNotEmpty() && s.all(r::isMethod) }
    }

    private fun groupConversion(methods: List<CSharpSymbol>, to: SemanticType): Conversion {
        if (to is SemanticType.Parameter) return Conversion.UNKNOWN
        val target = r.expressions.unwrapExpression(to) ?: return Conversion.UNKNOWN
        val delegate = r.expressions.delegateSignature(target)
        if (delegate == null) {
            val tn = r.definitionName(target)
            if (tn == "System.Delegate" || tn == "System.MulticastDelegate" || tn == OBJECT) return Conversion.UNKNOWN
            return if (closed(target)) Conversion.NONE else Conversion.UNKNOWN
        }
        val count = delegate.first.size
        val fitting = methods.filter { m -> r.signature(m, false)?.let { s -> s.size >= count && s.count { !it.optional && !it.isParams } <= count } ?: true }
        return if (fitting.isEmpty()) Conversion.NONE else Conversion.IMPLICIT
    }

    /** §10.2.11: a constant `int` converts to `sbyte`, `byte`, `short`, `ushort`, `uint`, `ulong` (and `long` to `ulong`) when it fits. */
    private fun constantFits(e: CSharpExpression, to: SemanticType): Boolean {
        val value = constantValue(e) ?: return false
        val range = when (r.definitionName(to)) {
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

    private fun constantValue(e: CSharpExpression): Long? {
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

    // ---- better conversion

    /** §12.6.4.5: 1 when the conversion of [e] to [t1] is better than to [t2], -1 the other way round, 0 neither, null not known. */
    fun better(e: CSharpExpression, type: SemanticType?, t1: SemanticType, t2: SemanticType): Int? {
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
            // C# 10: a handler conversion is better than any other
            val h1 = isHandler(t1)
            val h2 = isHandler(t2)
            if (h1 != h2) return if (h1) 1 else -1
        }
        val exact1 = same(type, t1)
        val exact2 = same(type, t2)
        if (exact1 == true && exact2 == false) return 1
        if (exact2 == true && exact1 == false) return -1
        if (exact1 == null || exact2 == null) return null
        return betterTarget(t1, t2)
    }

    /** §12.6.4.5 for an argument known by its type alone (the receiver of an extension method): the exact match, then the better target. */
    fun betterFromType(type: SemanticType, t1: SemanticType, t2: SemanticType): Int? {
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

    private fun isHandler(type: SemanticType): Boolean = type is SemanticType.Library && type.type.attributes.any { it.endsWith("InterpolatedStringHandlerAttribute") }

    /** §12.6.4.7, the better conversion target. */
    fun betterTarget(t1: SemanticType, t2: SemanticType): Int? {
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
        if (c12 == Conversion.NONE && c21 == Conversion.NONE) return 0
        return null
    }

    /** A lambda against two delegates taking the same parameters: by what the body returns (§12.6.4.5). */
    private fun betterLambda(lambda: CSharpAnonymousFunctionExpression, t1: SemanticType, t2: SemanticType): Int? {
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
    private fun betterCollection(t1: SemanticType, t2: SemanticType): Int? {
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
    )

    /**
     * For the compiler errors of [CSharpOverloadChecks]: conversions of lambdas and method groups are NONE or IMPLICIT only where that is
     * sure (the lenient answers of navigation would make an inapplicable candidate applicable there), anything else UNKNOWN.
     */
    private var strict = false

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
                val target = targets[i] ?: return Outcome.Unknown
                val writtenKind = argument.refKindKeyword?.text
                val kind = refKinds[i]
                val handlerByRef = kind == "ref" && writtenKind == null && unparenthesized(expression) is CSharpInterpolatedStringExpression && isHandler(target)
                if (!(writtenKind == kind || handlerByRef || kind == "in" && (writtenKind == null || writtenKind == "ref") || kind == null && writtenKind == null)) {
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
            val form = Form(symbol, parameters, targets, declaredTargets, expanded, defaults, generic, if (expanded) parameters.last().type() else null, receiverTarget, refKinds, methodArguments)
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

    /** The conversion of the argument [e] to [to], strictly ([strict]). */
    internal fun strictArgument(e: CSharpExpression, to: SemanticType): Conversion {
        val wasStrict = strict
        strict = true
        try {
            return argument(e, argumentType(e), to)
        } finally {
            strict = wasStrict
        }
    }

    internal fun instancesOf(type: SemanticType, definition: String): List<SemanticType.Library>? = instances(type, definition, 0)

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
                if (form === UNKNOWN_FORM) return null
                applicable += form
            }
            if (applicable.size == 1) return applicable.single().symbol
            if (applicable.isEmpty()) return null
            val pool = if (applicable.any { hasPriority(it.symbol) } && applicable.any { !hasPriority(it.symbol) }) applicable.filter { !hasPriority(it.symbol) } else applicable
            if (pool.size == 1) return pool.single().symbol
            var best: Form? = null
            for (m in pool) {
                if (pool.all { n -> n === m || betterMember(m, n, arguments, types, receiver) == true }) {
                    if (best != null) return null
                    best = m
                }
            }
            return best?.symbol
        } finally {
            openOwners -= owners.toSet()
        }
    }

    /**
     * CS0121: the applicable candidates of a call that no other applicable one beats, when there are two or more of them (none is the best,
     * §12.6.4.1). Null unless the applicability of every candidate and every comparison between the applicable ones is known.
     */
    fun ambiguity(candidates: List<CSharpSymbol>, arguments: List<CSharpArgument>, site: CSharpSimpleName): List<CSharpSymbol>? {
        if (candidates.size < 2 || candidates.any(::hasPriority)) return null
        val call = r.invocationOf(site) ?: return null
        val types = arguments.map { a -> a.expression?.let(::argumentType) }
        val owners = candidates.mapNotNull { (it as? CSharpSymbol.SourceMember)?.element }
        openOwners += owners
        try {
            twins = paramsTwins(candidates)
            val applicable = candidates.mapNotNull { symbol -> applicable(symbol, false, arguments, types, call, site, false)?.also { if (it === UNKNOWN_FORM) return null } }
            if (applicable.size < 2) return null
            val beaten = HashSet<CSharpSymbol>()
            for (m in applicable) for (n in applicable) {
                if (m !== n && (betterMember(m, n, arguments, types) ?: return null)) beaten += n.symbol
            }
            return applicable.map { it.symbol }.filter { it !in beaten }.takeIf { it.size >= 2 }
        } finally {
            openOwners -= owners.toSet()
        }
    }

    /** Whether every hole of [e] is a constant string (a literal, `nameof`); false when one surely is not (not a string); null when not known. */
    private fun constantInterpolation(e: CSharpInterpolatedStringExpression): Boolean? {
        var known = true
        for (part in e.contents) {
            val hole = part as? CSharpInterpolation ?: continue
            if (hole.alignmentClause != null || hole.formatClause != null) return false
            val value = hole.expression?.let(::unparenthesized) ?: return null
            if (value is CSharpLiteralExpression && value.text.let { it.startsWith("\"") || it.startsWith("@\"") }) continue
            if (value is CSharpInvocationExpression && value.expression?.text == "nameof") continue
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
        if (generic && !constraintsHold(symbol, methodArguments)) return null
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
                if (target == null) { unknown = true; continue }
                val written = argument.refKindKeyword?.text
                val kind = refKind(symbol, index, reduced)
                // `in` parameters take an argument with `in`, `ref` or nothing; `ref` / `out` only their own keyword
                // a `ref` interpolated string handler (`Debug.Assert(bool, ref AssertInterpolatedStringHandler)`) takes `$"..."` as it is
                val handlerByRef = kind == "ref" && written == null && unparenthesized(expression) is CSharpInterpolatedStringExpression && target != null && isHandler(target)
                if (!(written == kind || handlerByRef || kind == "in" && (written == null || written == "ref") || kind == null && written == null)) { fits = false; break }
                val byRef = written == "ref" || written == "out"
                val conversion = if (expression is CSharpDeclarationExpression && expression.type?.let(r::isVar) != false) Conversion.IDENTITY else argument(expression, types[i], target)
                when (conversion) {
                    Conversion.NONE -> { fits = false; break }
                    Conversion.UNKNOWN -> unknown = true
                    Conversion.IMPLICIT -> if (byRef) { fits = false; break }
                    Conversion.IDENTITY -> {}
                }
            }
            if (!fits) continue
            if (unknown) return UNKNOWN_FORM
            val defaults = parameters.indices.any { p -> p !in map && !(expanded && p == parameters.size - 1) }
            return Form(symbol, parameters, targets, declaredTargets, expanded, defaults, generic, if (expanded) parameters.last().type() else null, receiverTarget)
        }
        return if (unknown) UNKNOWN_FORM else null
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

    private fun parameterList(element: PsiElement): CSharpParameterList? = when (element) {
        is CSharpMethodDeclaration -> element.parameterList
        is CSharpLocalFunctionStatement -> element.parameterList
        is CSharpConstructorDeclaration -> element.parameterList
        is CSharpTypeDeclaration -> element.parameterList
        else -> null
    }

    /** The parameter each argument goes to; null when the arguments do not fit (§12.6.4.2, the corresponding parameters). */
    private fun map(parameters: List<CSharpNameResolver.Parameter>, arguments: List<CSharpArgument>, expanded: Boolean): List<Int>? {
        val result = ArrayList<Int>()
        val used = HashSet<Int>()
        var positional = true
        for ((i, argument) in arguments.withIndex()) {
            val name = argument.nameColon?.nameElement?.identifier?.text
            val index: Int
            if (name != null) {
                index = parameters.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return null
                // a named argument in its own position lets positional ones follow (C# 7.2); out of position, it must not
                if (index != i) positional = false
                if (expanded && index == parameters.size - 1) return null
            } else {
                if (!positional) return null
                index = if (expanded && i >= parameters.size - 1) parameters.size - 1 else i
                if (index >= parameters.size) return null
            }
            if (!(expanded && index == parameters.size - 1) && !used.add(index)) return null
            result += index
        }
        for ((p, parameter) in parameters.withIndex()) {
            if (p in used) continue
            if (expanded && p == parameters.size - 1) continue
            if (!parameter.optional) return null
        }
        return result
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

    /** §12.6.4.3: whether [m] is a better function member than [n] for the call; null when not known. */
    private fun betterMember(m: Form, n: Form, arguments: List<CSharpArgument>, types: List<SemanticType?>, receiver: SemanticType? = null): Boolean? {
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
        return moreSpecific(m.declaredTargets, n.declaredTargets)
    }

    /** §12.6.4.3, the more specific parameter types: a type parameter is less specific than any other type, recursively. */
    private fun moreSpecific(a: List<SemanticType?>, b: List<SemanticType?>): Boolean? {
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

    private fun specific(x: SemanticType, y: SemanticType): Int? {
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

    private fun arguments(type: SemanticType): List<SemanticType?> = when (type) {
        is SemanticType.Library -> type.arguments
        is SemanticType.Source -> type.arguments
        is SemanticType.ArrayOf -> listOf(type.element)
        is SemanticType.Parameter -> emptyList()
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
        private const val OBJECT = "System.Object"
        private const val STRING = "System.String"
        private const val VOID = "System.Void"
        private const val NULLABLE = "System.Nullable`1"
        private const val VALUE_TYPE = "System.ValueType"

        /** The non-delegate types a lambda converts to by its natural type (C# 10) and by nothing else. */
        val NATURAL_TARGETS = setOf(
            "System.Delegate", "System.MulticastDelegate", OBJECT, "System.Linq.Expressions.Expression", "System.Linq.Expressions.LambdaExpression",
        )

        private val PRIMITIVES = setOf(
            "System.Boolean", "System.Byte", "System.SByte", "System.Char", "System.Int16", "System.UInt16", "System.Int32", "System.UInt32", "System.Int64",
            "System.UInt64", "System.Single", "System.Double", "System.Decimal", "System.String",
        )

        val COLLECTION_TARGETS = setOf(
            "System.Span`1", "System.ReadOnlySpan`1", "System.Collections.Generic.IEnumerable`1", "System.Collections.Generic.IReadOnlyCollection`1",
            "System.Collections.Generic.IReadOnlyList`1", "System.Collections.Generic.ICollection`1", "System.Collections.Generic.IList`1",
            "System.Collections.Generic.List`1",
        )

        /** C# §10.2.3, the implicit numeric conversions (`nint` / `nuint` included). */
        private val NUMERIC: Map<String, Set<String>> = run {
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
        private val SIGNED_OVER: Map<String, Set<String>> = run {
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
