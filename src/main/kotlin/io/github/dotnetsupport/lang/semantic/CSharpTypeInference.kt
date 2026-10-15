package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
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
    private val overloads get() = r.overloads

    sealed class Inferred {
        class Known(val types: List<SemanticType>) : Inferred()
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
        val owner = (symbol as? CSharpSymbol.SourceMember)?.element
        val names = r.typeParameterNames(owner)
        val arity = r.expressions.methodArity(symbol)
        if (arity == 0) return Inferred.Known(emptyList())
        val ordinalOf = { p: SemanticType.Parameter ->
            when {
                !p.ofMethod -> null
                symbol is CSharpSymbol.LibraryMember && p.owner == null -> p.index.takeIf { it in 0 until arity }
                owner != null && p.owner == owner -> names.indexOf(p.name).takeIf { it >= 0 }
                else -> null
            }
        }
        fun mentions(type: SemanticType?): Boolean = when (type) {
            null -> false
            is SemanticType.Parameter -> ordinalOf(type) != null
            is SemanticType.ArrayOf -> mentions(type.element)
            is SemanticType.Library -> type.arguments.any(::mentions)
            is SemanticType.Source -> type.arguments.any(::mentions) || mentions(type.outer)
        }
        return try {
            val formal = ArrayList<SemanticType?>()
            val byReference = ArrayList<Boolean>()
            val inferenceArguments = ArrayList<InferenceArgument>()
            // the receiver of a reduced extension method is its first argument
            if (receiver != null) {
                formal += receiver.second ?: return Inferred.Unknown
                byReference += false
                inferenceArguments += InferenceArgument.Typed(receiver.first)
            }
            for ((i, argument) in arguments.withIndex()) {
                val target = targets.getOrNull(i) ?: return Inferred.Unknown
                val kind = argument.refKindKeyword?.text
                formal += target
                byReference += kind == "ref" || kind == "out" || kind == "in"
                // an argument whose parameter mentions no type parameter of the method gives no inference: its type need not be known
                inferenceArguments += if (!mentions(target)) InferenceArgument.Opaque else argument(argument.expression ?: return Inferred.Unknown, lenient)
            }
            when (val result = RoslynMethodTypeInferrer(RoslynTypes(r), arity, ordinalOf, formal, byReference, inferenceArguments, onFixed = onFixed).infer()) {
                is RoslynMethodTypeInferrer.Result.Known -> Inferred.Known(result.types)
                RoslynMethodTypeInferrer.Result.Failed -> Inferred.Failed
                RoslynMethodTypeInferrer.Result.Unknown -> Inferred.Unknown
            }
        } catch (_: RoslynMethodTypeInferrer.Unknown) {
            Inferred.Unknown
        }
    }

    /** An argument as Roslyn's inference sees it (the kind of its `BoundExpression`). */
    private fun argument(expression: CSharpExpression, lenient: Boolean): InferenceArgument {
        val value = overloads.unparenthesized(expression)
        val unknownType = { if (lenient) InferenceArgument.Typed(null) else throw RoslynMethodTypeInferrer.Unknown() }
        return when {
            value is CSharpAnonymousFunctionExpression -> lambda(value)
            isMethodGroup(value) -> InferenceArgument.MethodGroup { parameters -> methodGroupReturnType(value, parameters) }
            value.elementType == SyntaxKind.NullLiteralExpression || value.elementType == SyntaxKind.DefaultLiteralExpression -> InferenceArgument.Typed(null)
            // `out T x` has its type; `out var x` has none yet
            value is CSharpDeclarationExpression -> InferenceArgument.Typed(value.type?.takeIf { !r.isVar(it) }?.let { r.resolveType(it) ?: throw RoslynMethodTypeInferrer.Unknown() })
            value is CSharpTupleExpression -> InferenceArgument.Tuple(r.typeOf(value), value.arguments.map { a -> a.expression?.let { argument(it, lenient) } ?: InferenceArgument.Opaque })
            value is CSharpCollectionExpression || value is CSharpImplicitObjectCreationExpression || value is CSharpThrowExpression -> InferenceArgument.Opaque
            // a conditional without a natural type is target-typed: not modeled
            value is CSharpConditionalExpression -> r.typeOf(value)?.let { InferenceArgument.Typed(it) } ?: InferenceArgument.Opaque
            else -> r.typeOf(value)?.let { InferenceArgument.Typed(it) } ?: unknownType()
        }
    }

    private fun lambda(lambda: CSharpAnonymousFunctionExpression): InferenceArgument.Lambda {
        val parameters = when (lambda) {
            is CSharpSimpleLambdaExpression -> listOfNotNull(lambda.parameter)
            is CSharpParenthesizedLambdaExpression -> lambda.parameterList?.parameters.orEmpty()
            is CSharpAnonymousMethodExpression -> lambda.parameterList?.parameters.orEmpty()
            else -> throw RoslynMethodTypeInferrer.Unknown()
        }
        val hasSignature = lambda !is CSharpAnonymousMethodExpression || lambda.parameterList != null
        val explicit = if (parameters.isNotEmpty() && parameters.all { it.type != null }) {
            parameters.map { p -> p.type?.let(r::resolveType) ?: throw RoslynMethodTypeInferrer.Unknown() }
        } else null
        val explicitReturnType = (lambda as? CSharpParenthesizedLambdaExpression)?.returnType?.let { r.resolveType(it) ?: throw RoslynMethodTypeInferrer.Unknown() }
        return InferenceArgument.Lambda(parameters.size, hasSignature, explicit, explicitReturnType) { delegateParameters ->
            // the body is typed by the resolver, which types implicit parameters by its own inference: only where that agrees with this one
            if (explicit == null) for ((i, p) in parameters.withIndex()) {
                if (overloads.same(r.lambdaParameterType(p), delegateParameters.getOrNull(i)) != true) throw RoslynMethodTypeInferrer.Unknown()
            }
            val returned = returnedType(lambda)
            // an async one gives `Task<X>`, or `Task` without a value (§12.6.3.13)
            if (!r.expressions.isAsync(lambda.modifiers)) returned
            else {
                val task = r.libraryType(if (returned == null) "System.Threading.Tasks.Task" else "System.Threading.Tasks.Task`1") ?: throw RoslynMethodTypeInferrer.Unknown()
                if (returned == null) task else SemanticType.Library(task.type, listOf(returned))
            }
        }
    }

    /** What the body of [lambda] returns: its expression, the one type of the `return`s of its block; null when it returns no value. */
    private fun returnedType(lambda: CSharpAnonymousFunctionExpression): SemanticType? {
        val body = lambda.expressionBody
        if (body != null) {
            val type = r.typeOf(body) ?: throw RoslynMethodTypeInferrer.Unknown()
            return type.takeIf { r.definitionName(it) != VOID }
        }
        val block = lambda.block ?: throw RoslynMethodTypeInferrer.Unknown()
        val returned = PsiTreeUtil.findChildrenOfType(block, CSharpReturnStatement::class.java).filter { inLambda(it, lambda) }.mapNotNull { it.expression }
        if (returned.isEmpty()) return null
        val types = returned.map { r.typeOf(it) ?: throw RoslynMethodTypeInferrer.Unknown() }
        if (types.any { overloads.same(it, types.first()) != true }) throw RoslynMethodTypeInferrer.Unknown()
        return types.first()
    }

    /** The return type of the method of the group [value] a delegate with [parameters] takes: one method, not generic, of that arity. */
    private fun methodGroupReturnType(value: CSharpExpression, parameters: List<SemanticType>): SemanticType? {
        val methods = methodGroup(value) ?: throw RoslynMethodTypeInferrer.Unknown()
        val single = methods.singleOrNull() ?: throw RoslynMethodTypeInferrer.Unknown()
        if (r.isGeneric(single)) throw RoslynMethodTypeInferrer.Unknown()
        val signature = r.signature(single, false) ?: throw RoslynMethodTypeInferrer.Unknown()
        if (signature.size != parameters.size) throw RoslynMethodTypeInferrer.Unknown()
        return r.returnType(single, emptyList())?.takeIf { r.definitionName(it) != VOID } ?: throw RoslynMethodTypeInferrer.Unknown()
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
        return r.resolveName(name)?.symbols?.takeIf { s -> s.isNotEmpty() && s.all(r::isInMethodGroup) }
    }

    companion object {
        private const val VOID = "System.Void"
    }
}
