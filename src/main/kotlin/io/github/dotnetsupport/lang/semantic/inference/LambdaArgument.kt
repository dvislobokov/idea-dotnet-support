package io.github.dotnetsupport.lang.semantic.inference

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.SemanticTrace
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.lang.semantic.lambdaRefKind
import io.github.dotnetsupport.lang.semantic.unparenthesized
import io.github.dotnetsupport.lang.semantic.roslyn.InferenceArgument
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodTypeInferrer
import io.github.dotnetsupport.lang.semantic.same
import io.github.dotnetsupport.lang.semantic.targetTyped

/**
 * A lambda or an anonymous method as Roslyn's inference sees it (`UnboundLambda`): its parameters, written or not, and what its body
 * returns for a delegate — the body bound with the parameter types of that candidate ([CSharpNameResolver.bindLambda], D6e3), `async`
 * wrapping it into the delegate's task-like type.
 */
internal class LambdaArgument(private val r: CSharpNameResolver) {

    fun of(lambda: CSharpAnonymousFunctionExpression): InferenceArgument.Lambda {
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
        val inferReturnType = { delegateParameters: List<SemanticType>, delegateReturnType: SemanticType? ->
            // as Roslyn: the body is bound with the implicit parameters typed by the delegate of this candidate (its input types are fixed)
            val returned = if (explicit != null || parameters.isEmpty()) returnedType(lambda) else {
                if (delegateParameters.size != parameters.size) throw RoslynMethodTypeInferrer.Unknown("the delegate takes ${delegateParameters.size} parameters, the lambda ${parameters.size}")
                r.bindLambda(lambda, parameters, delegateParameters) { returnedType(lambda) }
            }
            if (!r.expressions.isAsync(lambda.modifiers)) returned else asyncReturnType(returned, delegateReturnType)
        }
        // C# 10 (`InferDelegateType`): a lambda whose parameters are all typed (or none) and whose return type the body gives has a function
        // type, the delegate `Action<…>` / `Func<…>` of them; `delegate { }` and implicitly typed parameters have none
        val functionType = {
            if (!hasSignature || (explicit == null && parameters.isNotEmpty())) null
            else {
                val kinds = parameters.map { p -> r.overloads.lambdaRefKind(p) ?: throw RoslynMethodTypeInferrer.Unknown("the modifier of a parameter of the lambda at ${SemanticTrace.at(lambda)} is not modeled") }
                val returned = explicitReturnType ?: returnedType(lambda)
                val returns = if (!r.expressions.isAsync(lambda.modifiers)) returned else asyncReturnType(returned, null)
                r.overloads.roslynTypes.functionTypeDelegate(explicit.orEmpty(), kinds, returns)
                    ?: throw RoslynMethodTypeInferrer.Unknown("the function type of the lambda at ${SemanticTrace.at(lambda)} is a synthesized delegate (not modeled)")
            }
        }
        return InferenceArgument.Lambda(parameters.size, hasSignature, explicit, explicitReturnType, inferReturnType, functionType)
    }

    /**
     * What an async lambda returning [returned] (null: no value) gives (§12.6.3.13): the task-like type the delegate returns, built over it —
     * `ValueTask<X>` for a `Func<…, ValueTask<TResult>>` (nopCommerce's `AsyncEnumerable.Select(async (x, _) => …)` was CS0411) — else
     * `Task<X>`, or `Task` without a value.
     */
    private fun asyncReturnType(returned: SemanticType?, delegateReturnType: SemanticType?): SemanticType {
        val taskLike = (delegateReturnType as? SemanticType.Library)?.type?.takeIf { it.fullName in TASK_LIKE }
        if (taskLike != null) {
            if (returned != null && taskLike.typeParameters.size == 1) return SemanticType.Library(taskLike, listOf(returned))
            if (returned == null && taskLike.typeParameters.isEmpty()) return SemanticType.Library(taskLike, emptyList())
        }
        val task = r.libraryType(if (returned == null) "System.Threading.Tasks.Task" else "System.Threading.Tasks.Task`1") ?: throw RoslynMethodTypeInferrer.Unknown()
        return if (returned == null) task else SemanticType.Library(task.type, listOf(returned))
    }

    /** What the body of [lambda] returns: its expression, the one type of the `return`s of its block; null when it returns no value. */
    private fun returnedType(lambda: CSharpAnonymousFunctionExpression): SemanticType? {
        val body = lambda.expressionBody
        if (body != null) {
            // `() => throw …`, `() => null`, `() => default`: no type of their own, so no inferred return type (Roslyn's `InferReturnType`
            // finds no type among the returns and makes no inference), not a refusal
            if (typeless(body)) return null
            val type = r.typeOf(body) ?: throw RoslynMethodTypeInferrer.Unknown("the type of the lambda body `${SemanticTrace.text(body)}` is not known")
            return type.takeIf { r.definitionName(it) != VOID }
        }
        val block = lambda.block ?: throw RoslynMethodTypeInferrer.Unknown("the lambda has no body")
        // `return null;` / `return default;` / `return throw…` beside the others take their type; alone they give no type
        val returned = PsiTreeUtil.findChildrenOfType(block, CSharpReturnStatement::class.java).filter { inLambda(it, lambda) }.mapNotNull { it.expression }
            .map { r.overloads.unparenthesized(it) }.filter { !typeless(it) }
        if (returned.isEmpty()) return null
        val types = returned.map { r.typeOf(it) ?: throw RoslynMethodTypeInferrer.Unknown("the type of `return ${SemanticTrace.text(it)}` in the lambda is not known") }
        return bestCommonType(types) ?: throw RoslynMethodTypeInferrer.Unknown("the returns of the lambda have no best common type: [${types.joinToString { SemanticTrace.show(it) }}]")
    }

    /**
     * The best common type of [types] (§12.6.3.15, as Roslyn's `InferReturnType` takes it): the one among them every other converts to
     * implicitly — `IEnumerable<X>` over `List<X>` of nopCommerce's `SelectManyAwait(x => { … return Enumerable.Empty<X>(); … return new List<X>(); })`.
     */
    private fun bestCommonType(types: List<SemanticType>): SemanticType? {
        val candidates = types.filter { to -> types.all { from -> r.overloads.same(from, to) == true || r.conversion(from, to).let { it == CSharpNameResolver.Conversion.IMPLICIT || it == CSharpNameResolver.Conversion.IDENTITY } } }
        val first = candidates.firstOrNull() ?: return null
        return if (candidates.all { r.overloads.same(it, first) == true }) first else null
    }

    /** No type of its own: `null`, `default`, `throw`, `new()`, `[…]`, a lambda, a method group — and a tuple with such an element (`return (false, false, null)`). */
    private fun typeless(e: CSharpExpression): Boolean {
        val value = r.overloads.unparenthesized(e)
        return r.overloads.targetTyped(value) || value is CSharpTupleExpression && value.arguments.any { a -> a.expression?.let(::typeless) != false }
    }

    private fun inLambda(statement: CSharpReturnStatement, lambda: CSharpAnonymousFunctionExpression): Boolean =
        PsiTreeUtil.getParentOfType(statement, CSharpAnonymousFunctionExpression::class.java, CSharpLocalFunctionStatement::class.java) == lambda

    private companion object {
        const val VOID = "System.Void"
        val TASK_LIKE = setOf("System.Threading.Tasks.Task", "System.Threading.Tasks.Task`1", "System.Threading.Tasks.ValueTask", "System.Threading.Tasks.ValueTask`1")
    }
}
