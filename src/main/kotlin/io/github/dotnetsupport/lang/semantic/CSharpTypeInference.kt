package io.github.dotnetsupport.lang.semantic

import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.inference.InferenceArguments
import io.github.dotnetsupport.lang.semantic.roslyn.InferenceArgument
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodTypeInferrer
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynTypes

/**
 * The type inference of C# §12.6.3 for the compiler errors of overload resolution ([CSharpOverloadChecks]), strict where
 * [CSharpExpressionTypes.typeArguments] is lenient: Roslyn's `MethodTypeInferrer`, ported ([RoslynMethodTypeInferrer], CSHARP_PSI_MIGRATION.md
 * D6b), given the arguments as it sees them. [Inferred.Failed] only when the failure is sure — that is CS0411; anything not known is
 * [Inferred.Unknown].
 */
internal class CSharpTypeInference(private val r: CSharpNameResolver) {
    sealed class Inferred {
        class Known(val types: List<SemanticType>, val fromFunctionType: Boolean = false) : Inferred()
        object Failed : Inferred()
        object Unknown : Inferred()
    }

    /**
     * The type arguments of the generic method [symbol] for a call whose [i]-th argument goes to a parameter of the declared type
     * [targets]`[i]` (the element type for the expanded `params`), [receiver] the receiver and the `this` parameter of a reduced call.
     * [lenient] (the types of expressions, [CSharpExpressionTypes.typeArguments]): an argument of no known type gives no inference, as an
     * error type in Roslyn, instead of making the whole answer unknown; [onFixed] hears each type parameter as it is fixed.
     */
    fun infer(
        symbol: CSharpSymbol, targets: List<SemanticType?>, arguments: List<CSharpArgument>, receiver: Pair<SemanticType, SemanticType?>?, call: CSharpInvocationExpression?,
        site: CSharpSimpleName?, lenient: Boolean = false, onFixed: ((Int, SemanticType) -> Unit)? = null,
    ): Inferred {
        // the same question comes from the type of the call, the overload checks and the applicability of each candidate (up to ten times a
        // call, the log showed): answered once per resolver, unless the answer met a cycle (D6e4)
        val key = Key(symbol, call, site, lenient, targets.map(::fingerprint), receiver?.let { fingerprint(it.first) to fingerprint(it.second) })
        // a call inside a lambda being bound for a candidate is answered for that binding only (the types of its arguments depend on it)
        val answers = r.cacheFor(call ?: site, this.answers)
        answers[key]?.let { cached ->
            // what the run fixed before it ended, told again: the asker's partial answer (a lambda's parameter typed through the call) is the same
            if (onFixed != null) cached.fixed.forEachIndexed { i, type -> if (type != null) onFixed(i, type) }
            return cached.result
        }
        val before = r.cycleCount
        val where = { "inference ${site?.text ?: SemanticTrace.show(symbol)}${if (lenient) " (lenient)" else ""} at ${SemanticTrace.at(call ?: site)}" }
        val partial = arrayOfNulls<SemanticType>(r.expressions.methodArity(symbol))
        val hear: (Int, SemanticType) -> Unit = { i, type ->
            if (i in partial.indices) partial[i] = type
            onFixed?.invoke(i, type)
        }
        // Failed is a decision (the candidate does not apply, as in Roslyn), Unknown is a refusal: only the latter goes to the log
        val result = try {
            inferOrThrow(symbol, targets, arguments, receiver, call, site, lenient, hear)
        } catch (e: RoslynMethodTypeInferrer.Unknown) {
            SemanticTrace.trace { "${where()}: Unknown — ${e.describe()}" }
            Inferred.Unknown
        }
        if (r.cycleCount == before) answers[key] = Answer(result, partial.toList())
        return result
    }

    /** An answer kept: the result, and what the run had fixed when it ended (all of it for a [Inferred.Known]). */
    private class Answer(val result: Inferred, val fixed: List<SemanticType?>)

    /** A question of [infer], by what it is asked with: the types by their [fingerprint] (they compare by identity, and are made anew per ask). */
    private data class Key(
        val symbol: CSharpSymbol, val call: CSharpInvocationExpression?, val site: CSharpSimpleName?, val lenient: Boolean, val targets: List<Any?>,
        val receiver: Pair<Any?, Any?>?,
    )

    /** The parts of [type] that make it the type it is, as nested lists (hashed structurally, no text built). */
    internal fun fingerprint(type: SemanticType?): Any? = when (type) {
        null -> null
        is SemanticType.Library -> listOf(type.type, type.arguments.map(::fingerprint), type.tupleNames)
        is SemanticType.Source -> listOf(type.info.key, type.arguments.map(::fingerprint), fingerprint(type.outer))
        is SemanticType.ArrayOf -> listOf(type.rank, fingerprint(type.element))
        is SemanticType.Parameter -> listOf(type.name, type.owner, type.index, type.ofMethod)
    }

    private val answers = HashMap<Key, Answer>()

    private fun inferOrThrow(
        symbol: CSharpSymbol, targets: List<SemanticType?>, arguments: List<CSharpArgument>, receiver: Pair<SemanticType, SemanticType?>?, call: CSharpInvocationExpression?,
        site: CSharpSimpleName?, lenient: Boolean, onFixed: ((Int, SemanticType) -> Unit)?,
    ): Inferred {
        val arity = r.expressions.methodArity(symbol)
        if (arity == 0) return Inferred.Known(emptyList())
        val ordinalOf = ordinalOf(symbol, arity)
        fun mentions(type: SemanticType?): Boolean = when (type) {
            null -> false
            is SemanticType.Parameter -> ordinalOf(type) != null
            is SemanticType.ArrayOf -> mentions(type.element)
            is SemanticType.Library -> type.arguments.any(::mentions)
            is SemanticType.Source -> type.arguments.any(::mentions) || mentions(type.outer)
        }
        val formal = ArrayList<SemanticType?>()
        val byReference = ArrayList<Boolean>()
        val inferenceArguments = ArrayList<InferenceArgument>()
        // the receiver of a reduced extension method is its first argument
        if (receiver != null) {
            formal += receiver.second ?: throw RoslynMethodTypeInferrer.Unknown("the type of the `this` parameter is not known")
            byReference += false
            inferenceArguments += InferenceArgument.Typed(receiver.first)
        }
        for ((i, argument) in arguments.withIndex()) {
            val target = targets.getOrNull(i) ?: throw RoslynMethodTypeInferrer.Unknown("the type of parameter ${i + 1} is not known")
            val kind = argument.refKindKeyword?.text
            formal += target
            byReference += kind == "ref" || kind == "out" || kind == "in"
            // an argument whose parameter mentions no type parameter of the method gives no inference: its type need not be known
            inferenceArguments += if (!mentions(target)) InferenceArgument.Opaque
            else readers.of(argument.expression ?: throw RoslynMethodTypeInferrer.Unknown("argument ${i + 1} has no expression"), lenient)
        }
        return when (val result = RoslynMethodTypeInferrer(RoslynTypes(r), arity, ordinalOf, formal, byReference, inferenceArguments, onFixed = onFixed).infer()) {
            is RoslynMethodTypeInferrer.Result.Known -> Inferred.Known(result.types, result.fromFunctionType)
            RoslynMethodTypeInferrer.Result.Failed -> Inferred.Failed
            is RoslynMethodTypeInferrer.Result.Unknown -> throw RoslynMethodTypeInferrer.Unknown(result.reason)
        }
    }

    /** `GetOrdinal`: the index of a type parameter of [symbol] among the [arity] being inferred; null for any other type parameter. */
    private fun ordinalOf(symbol: CSharpSymbol, arity: Int): (SemanticType.Parameter) -> Int? {
        val owner = (symbol as? CSharpSymbol.SourceMember)?.element
        val names = r.typeParameterNames(owner)
        return { p ->
            when {
                !p.ofMethod -> null
                symbol is CSharpSymbol.LibraryMember && p.owner == null -> p.index.takeIf { it in 0 until arity }
                owner != null && p.owner == owner -> names.indexOf(p.name).takeIf { it >= 0 }
                else -> null
            }
        }
    }

    /**
     * The type arguments of [symbol] for arguments that are values of [argumentTypes] (a method group converted to a delegate: the delegate's
     * parameter types, the receiver of a reduced extension method first), [formal] the declared parameter types they go to. Not cached:
     * asked once per candidate of a conversion.
     */
    fun inferFromTypes(symbol: CSharpSymbol, formal: List<SemanticType?>, argumentTypes: List<SemanticType>, onFixed: ((Int, SemanticType) -> Unit)? = null): Inferred {
        val arity = r.expressions.methodArity(symbol)
        if (arity == 0) return Inferred.Known(emptyList())
        val arguments = argumentTypes.map { InferenceArgument.Typed(it) }
        return try {
            when (val result = RoslynMethodTypeInferrer(RoslynTypes(r), arity, ordinalOf(symbol, arity), formal, formal.map { false }, arguments, onFixed = onFixed).infer()) {
                is RoslynMethodTypeInferrer.Result.Known -> Inferred.Known(result.types, result.fromFunctionType)
                RoslynMethodTypeInferrer.Result.Failed -> Inferred.Failed
                is RoslynMethodTypeInferrer.Result.Unknown -> throw RoslynMethodTypeInferrer.Unknown(result.reason)
            }
        } catch (e: RoslynMethodTypeInferrer.Unknown) {
            SemanticTrace.trace { "inference ${SemanticTrace.show(symbol)} for a delegate's parameters [${argumentTypes.joinToString { SemanticTrace.show(it) }}]: Unknown — ${e.describe()}" }
            Inferred.Unknown
        }
    }

    /** The arguments as the inference sees them: one file per construct (package `inference`). */
    private val readers = InferenceArguments(r)

    fun isMethodGroup(e: CSharpExpression): Boolean = readers.methodGroups.isMethodGroup(e)

    /** The natural type of a lambda (C# 10): the delegate of its function type; null when it has none or that is not known. */
    fun lambdaFunctionType(lambda: CSharpAnonymousFunctionExpression): SemanticType? = try {
        readers.lambdas.of(lambda).functionType()
    } catch (e: RoslynMethodTypeInferrer.Unknown) {
        SemanticTrace.trace { "typeOf the lambda at ${SemanticTrace.at(lambda)}: its function type is not known — ${e.describe()}" }
        null
    }
}
