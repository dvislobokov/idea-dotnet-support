package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver.Conversion

/**
 * The type inference of C# §12.6.3 for the compiler errors of overload resolution ([CSharpOverloadChecks]), strict where
 * [CSharpExpressionTypes.typeArguments] is lenient: bounds are collected (exact, lower; upper ones are not modeled), each type parameter is
 * fixed by the spec's rule (the one candidate all other bounds convert to), lambdas and method groups give their output types once their
 * input types are fixed. [Inferred.Failed] only when the failure is sure — that is CS0411; anything not modeled is [Inferred.Unknown].
 */
internal class CSharpTypeInference(private val r: CSharpNameResolver) {
    private val overloads get() = r.overloads

    sealed class Inferred {
        class Known(val types: List<SemanticType>) : Inferred()
        object Failed : Inferred()
        object Unknown : Inferred()
    }

    private class Unknown : RuntimeException(null, null, false, false)

    private class Slot {
        val exact = ArrayList<SemanticType>()
        val lower = ArrayList<SemanticType>()
        var fixed: SemanticType? = null
        val bounded get() = exact.isNotEmpty() || lower.isNotEmpty()
    }

    /**
     * The type arguments of the generic method [symbol] for a call whose [i]-th argument goes to a parameter of the declared type
     * [targets]`[i]` (the element type for the expanded `params`), [receiver] the receiver and the `this` parameter of a reduced call.
     */
    fun infer(
        symbol: CSharpSymbol, targets: List<SemanticType?>, arguments: List<CSharpArgument>, receiver: Pair<SemanticType, SemanticType?>?, call: CSharpInvocationExpression?,
        site: CSharpSimpleName?,
    ): Inferred {
        val owner = (symbol as? CSharpSymbol.SourceMember)?.element
        val names = r.typeParameterNames(owner)
        val arity = r.expressions.methodArity(symbol)
        if (arity == 0) return Inferred.Known(emptyList())
        val slots = List(arity) { Slot() }
        fun slot(p: SemanticType.Parameter): Slot? = when {
            !p.ofMethod -> null
            symbol is CSharpSymbol.LibraryMember && p.owner == null -> slots.getOrNull(p.index)
            owner != null && p.owner == owner -> slots.getOrNull(names.indexOf(p.name))
            else -> null
        }
        val context = Context(slots, ::slot)
        return try {
            if (receiver != null) context.lower(receiver.first, receiver.second ?: throw Unknown())
            val pending = ArrayList<Pair<CSharpExpression, SemanticType>>()
            for ((i, argument) in arguments.withIndex()) {
                val target = targets.getOrNull(i) ?: throw Unknown()
                if (!context.mentions(target)) continue
                val expression = argument.expression ?: throw Unknown()
                val value = overloads.unparenthesized(expression)
                when {
                    value is CSharpAnonymousFunctionExpression || isMethodGroup(value) -> {
                        // C# 10: a lambda or a method group has a function type a bare type parameter would take
                        if (target is SemanticType.Parameter) throw Unknown()
                        val delegate = r.expressions.unwrapExpression(target)?.let(r.expressions::delegateSignature) ?: throw Unknown()
                        if (value is CSharpAnonymousFunctionExpression) explicitParameters(value, delegate.first, context)
                        pending += value to target
                    }
                    value.elementType == SyntaxKind.NullLiteralExpression || value.elementType == SyntaxKind.DefaultLiteralExpression -> {}
                    value is CSharpDeclarationExpression -> {
                        val written = value.type?.takeIf { !r.isVar(it) }?.let(r::resolveType) ?: throw Unknown()
                        context.exact(written, target)
                    }
                    value is CSharpTupleExpression || value is CSharpCollectionExpression || value is CSharpImplicitObjectCreationExpression ||
                        value is CSharpThrowExpression || value is CSharpConditionalExpression -> throw Unknown()
                    else -> {
                        val type = r.typeOf(value) ?: throw Unknown()
                        val kind = argument.refKindKeyword?.text
                        if (kind == "ref" || kind == "out") context.exact(type, target) else context.lower(type, target)
                    }
                }
            }
            // phase 2: fix what nothing pending gives more to, then the output types of the lambdas whose input types are fixed
            while (true) {
                var progress = false
                val blocked = HashSet<Slot>()
                for ((_, target) in pending) {
                    val delegate = r.expressions.unwrapExpression(target)?.let(r.expressions::delegateSignature) ?: throw Unknown()
                    if (delegate.first.any { it == null || context.mentionsUnfixed(it) }) context.collect(delegate.second, blocked)
                }
                for (s in slots) if (s.fixed == null && s.bounded && s !in blocked) {
                    if (!fix(s)) return Inferred.Failed
                    progress = true
                }
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val (value, target) = iterator.next()
                    val delegate = r.expressions.unwrapExpression(target)?.let(r.expressions::delegateSignature) ?: throw Unknown()
                    if (delegate.first.any { it == null || context.mentionsUnfixed(it) }) continue
                    iterator.remove()
                    progress = true
                    val returns = delegate.second ?: throw Unknown()
                    if (!context.mentionsUnfixed(returns)) continue
                    val output = outputType(value, delegate.first.map { context.substitute(it!!) }, symbol, call, site, context) ?: continue
                    context.lower(output, returns)
                }
                if (!progress) break
            }
            for (s in slots) if (s.fixed == null) {
                if (!s.bounded) return Inferred.Failed
                if (!fix(s)) return Inferred.Failed
            }
            Inferred.Known(slots.map { it.fixed!! })
        } catch (_: Unknown) {
            Inferred.Unknown
        }
    }

    /** An explicitly typed lambda gives exact bounds by its parameter types (§12.6.3.8). */
    private fun explicitParameters(lambda: CSharpAnonymousFunctionExpression, expected: List<SemanticType?>, context: Context) {
        val written = when (lambda) {
            is CSharpParenthesizedLambdaExpression -> lambda.parameterList?.parameters.orEmpty()
            is CSharpAnonymousMethodExpression -> lambda.parameterList?.parameters ?: return
            else -> return
        }
        if (written.isEmpty() || written.any { it.type == null } || written.size != expected.size) return
        for ((p, e) in written.zip(expected)) {
            val type = p.type?.let(r::resolveType) ?: throw Unknown()
            context.exact(type, e ?: throw Unknown())
        }
    }

    /** The output type of a lambda or a method group whose input types are [inputs] (§12.6.3.7); null when it gives none. */
    private fun outputType(
        value: CSharpExpression, inputs: List<SemanticType>, symbol: CSharpSymbol, call: CSharpInvocationExpression?, site: CSharpSimpleName?, context: Context,
    ): SemanticType? {
        if (value is CSharpAnonymousFunctionExpression) {
            if (r.expressions.isAsync(value.modifiers)) throw Unknown()
            val parameters = when (value) {
                is CSharpSimpleLambdaExpression -> listOfNotNull(value.parameter)
                is CSharpParenthesizedLambdaExpression -> value.parameterList?.parameters.orEmpty()
                is CSharpAnonymousMethodExpression -> value.parameterList?.parameters.orEmpty()
                else -> throw Unknown()
            }
            if (parameters.size != inputs.size && !(value is CSharpAnonymousMethodExpression && value.parameterList == null)) return null
            // the body is typed by the resolver, which types implicit parameters by its own inference: only where that agrees with this one
            if (parameters.any { it.type == null } && call != null && site != null) {
                val lenient = r.expressions.typeArguments(symbol, call, site)
                if (context.fixedTypes().withIndex().any { (i, t) -> t != null && overloads.same(t, lenient.getOrNull(i)) != true }) throw Unknown()
            }
            val body = value.expressionBody
            if (body != null) {
                val type = r.typeOf(body) ?: throw Unknown()
                return type.takeIf { r.definitionName(it) != VOID }
            }
            val block = value.block ?: throw Unknown()
            val returned = PsiTreeUtil.findChildrenOfType(block, CSharpReturnStatement::class.java).filter { inLambda(it, value) }.mapNotNull { it.expression }
            if (returned.isEmpty()) return null
            val types = returned.map { r.typeOf(it) ?: throw Unknown() }
            if (types.any { overloads.same(it, types.first()) != true }) throw Unknown()
            return types.first()
        }
        val methods = methodGroup(value) ?: throw Unknown()
        val single = methods.singleOrNull() ?: throw Unknown()
        if (r.isGeneric(single)) throw Unknown()
        val parameters = r.signature(single, false) ?: throw Unknown()
        if (parameters.size != inputs.size) throw Unknown()
        return r.returnType(single, emptyList())?.takeIf { r.definitionName(it) != VOID } ?: throw Unknown()
    }

    private fun inLambda(statement: CSharpReturnStatement, lambda: CSharpAnonymousFunctionExpression): Boolean =
        PsiTreeUtil.getParentOfType(statement, CSharpAnonymousFunctionExpression::class.java, CSharpLocalFunctionStatement::class.java) == lambda

    fun isMethodGroup(e: CSharpExpression): Boolean = methodGroup(e) != null

    private fun methodGroup(e: CSharpExpression): List<CSharpSymbol>? {
        val name = when (e) {
            is CSharpSimpleName -> e
            is CSharpMemberAccessExpression -> e.nameElement
            else -> null
        } ?: return null
        if (r.invocationOf(name) != null) return null
        return r.resolveName(name)?.symbols?.takeIf { s -> s.isNotEmpty() && s.all(r::isMethod) }
    }

    /** Fixes [s] (§12.6.3.12): the candidates that every bound allows, the one all others convert to; false when there is none. */
    private fun fix(s: Slot): Boolean {
        val candidates = ArrayList<SemanticType>()
        for (t in s.exact + s.lower) {
            if (candidates.none { overloads.same(it, t) ?: throw Unknown() }) candidates += t
        }
        for (e in s.exact) candidates.removeAll { !(overloads.same(it, e) ?: throw Unknown()) }
        for (l in s.lower) candidates.removeAll { converts(l, it) == false }
        if (candidates.isEmpty()) return false
        val best = candidates.filter { v -> candidates.all { u -> u === v || converts(u, v) == true } }
        if (best.size != 1) {
            if (candidates.any { u -> candidates.any { v -> u !== v && converts(u, v) == null } }) throw Unknown()
            if (best.isEmpty()) return false
            throw Unknown()
        }
        s.fixed = best.single()
        return true
    }

    private fun converts(from: SemanticType, to: SemanticType): Boolean? = when (overloads.classify(from, to)) {
        Conversion.IDENTITY, Conversion.IMPLICIT -> true
        Conversion.NONE -> false
        Conversion.UNKNOWN -> throw Unknown()
    }

    private inner class Context(private val slots: List<Slot>, val slotOf: (SemanticType.Parameter) -> Slot?) {

        fun mentions(type: SemanticType?): Boolean = when (type) {
            null -> false
            is SemanticType.Parameter -> slotOf(type) != null
            is SemanticType.ArrayOf -> mentions(type.element)
            is SemanticType.Library -> type.arguments.any(::mentions)
            is SemanticType.Source -> type.arguments.any(::mentions) || type.outer?.let(::mentions) == true
        }

        fun mentionsUnfixed(type: SemanticType?): Boolean = when (type) {
            null -> false
            is SemanticType.Parameter -> slotOf(type)?.let { it.fixed == null } == true
            is SemanticType.ArrayOf -> mentionsUnfixed(type.element)
            is SemanticType.Library -> type.arguments.any(::mentionsUnfixed)
            is SemanticType.Source -> type.arguments.any(::mentionsUnfixed) || type.outer?.let(::mentionsUnfixed) == true
        }

        fun collect(type: SemanticType?, into: MutableSet<Slot>) {
            when (type) {
                null -> {}
                is SemanticType.Parameter -> slotOf(type)?.let { into += it }
                is SemanticType.ArrayOf -> collect(type.element, into)
                is SemanticType.Library -> type.arguments.forEach { collect(it, into) }
                is SemanticType.Source -> type.arguments.forEach { collect(it, into) }
            }
        }

        fun substitute(type: SemanticType): SemanticType = r.replace(type) { p -> slotOf(p)?.let { it.fixed ?: throw Unknown() } ?: p } ?: throw Unknown()

        fun fixedTypes(): List<SemanticType?> = slots.map { it.fixed }

        /** §12.6.3.9, the exact inference from [u] to [v]. */
        fun exact(u: SemanticType, v: SemanticType, depth: Int = 0) {
            if (depth > 8) throw Unknown()
            if (!mentions(v)) return
            when (v) {
                is SemanticType.Parameter -> slotOf(v)?.let { it.exact += u }
                is SemanticType.ArrayOf -> if (u is SemanticType.ArrayOf && u.rank == v.rank) exact(u.element ?: throw Unknown(), v.element ?: throw Unknown(), depth + 1)
                is SemanticType.Library -> if (u is SemanticType.Library && u.type.fullName == v.type.fullName) pairs(u.arguments, v.arguments) { _, a, b -> exact(a, b, depth + 1) }
                is SemanticType.Source -> if (u is SemanticType.Source && u.info.key == v.info.key) pairs(u.arguments, v.arguments) { _, a, b -> exact(a, b, depth + 1) }
            }
        }

        /** §12.6.3.10, the lower-bound inference from [u] to [v] (upper bounds, through contravariance, are not modeled). */
        fun lower(u: SemanticType, v: SemanticType, depth: Int = 0) {
            if (depth > 8) throw Unknown()
            if (!mentions(v)) return
            // a type parameter of the caller is a type like any other as a bound, but what it derives from is not walked here
            if (u is SemanticType.Parameter && v !is SemanticType.Parameter) throw Unknown()
            when (v) {
                is SemanticType.Parameter -> slotOf(v)?.let { it.lower += u }
                is SemanticType.ArrayOf -> {
                    if (u !is SemanticType.ArrayOf) { if (overloads.closed(u)) return else throw Unknown() }
                    if (u.rank != v.rank) return
                    element(u.element ?: throw Unknown(), v.element ?: throw Unknown(), depth)
                }
                is SemanticType.Library -> {
                    if (v.type.fullName == NULLABLE) throw Unknown()
                    if (u is SemanticType.ArrayOf && u.rank == 1 && v.type.fullName in ARRAY_INTERFACES) return element(u.element ?: throw Unknown(), v.arguments.singleOrNull() ?: throw Unknown(), depth)
                    val found = overloads.instancesOf(u, v.type.fullName) ?: throw Unknown()
                    if (found.isEmpty()) { if (overloads.closed(u)) return else throw Unknown() }
                    val instance = found.singleOrNull() ?: throw Unknown()
                    val variance = v.type.typeParameters
                    if (variance.size != v.arguments.size) throw Unknown()
                    pairs(instance.arguments, v.arguments) { i, a, b ->
                        val p = variance[i]
                        when {
                            p.isCovariant -> if (overloads.isReference(a) == true) lower(a, b, depth + 1) else if (overloads.isReference(a) == false) exact(a, b, depth + 1) else throw Unknown()
                            p.isContravariant -> if (overloads.isReference(a) == false) exact(a, b, depth + 1) else throw Unknown()
                            else -> exact(a, b, depth + 1)
                        }
                    }
                }
                is SemanticType.Source -> {
                    val declaration = v.info.parts.firstOrNull()?.element() as? CSharpTypeDeclaration
                    if (declaration?.typeParameterList?.parameters.orEmpty().any { it.varianceKeyword != null }) throw Unknown()
                    val instance = r.expressions.sourceInstanceOf(u, v.info.key, 0)
                    if (instance == null) { if (overloads.closed(u)) return else throw Unknown() }
                    pairs(instance.arguments, v.arguments) { _, a, b -> exact(a, b, depth + 1) }
                }
            }
        }

        private fun element(u: SemanticType, v: SemanticType, depth: Int) = when (overloads.isReference(u)) {
            true -> lower(u, v, depth + 1)
            false -> exact(u, v, depth + 1)
            null -> throw Unknown()
        }

        private fun pairs(a: List<SemanticType?>, b: List<SemanticType?>, each: (Int, SemanticType, SemanticType) -> Unit) {
            if (a.size != b.size) throw Unknown()
            for (i in a.indices) each(i, a[i] ?: throw Unknown(), b[i] ?: throw Unknown())
        }
    }

    companion object {
        private const val VOID = "System.Void"
        private const val NULLABLE = "System.Nullable`1"
        private val ARRAY_INTERFACES = setOf(
            "System.Collections.Generic.IEnumerable`1", "System.Collections.Generic.ICollection`1", "System.Collections.Generic.IList`1",
            "System.Collections.Generic.IReadOnlyList`1", "System.Collections.Generic.IReadOnlyCollection`1",
        )
    }
}
