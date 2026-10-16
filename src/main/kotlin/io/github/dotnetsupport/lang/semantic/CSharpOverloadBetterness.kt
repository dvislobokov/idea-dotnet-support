package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver.Conversion
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Companion.STRING
import io.github.dotnetsupport.lang.semantic.CSharpOverloads.Form
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynBetterFunctionMember
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynBetterFunctionMember.Argument
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynBetterFunctionMember.BetterResult
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynBetterFunctionMember.Element
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynBetterFunctionMember.Member
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodTypeInferrer.Unknown

/*
 * Which candidate is better (C# §12.6.4.3 – §12.6.4.7): Roslyn's `BetterFunctionMember`, ported as [RoslynBetterFunctionMember] (D6c4).
 * This file is the adapter: a [Form] as the port's [Member], the arguments with the receiver of an extension method first, the resolver
 * as the port's [RoslynBetterFunctionMember.Host]. A fact the port lacks is one trace line and "not known" (null) here.
 */

/** Whether [m] is a better function member than [n] for the call at [site]; null when not known. [receiver]: of a reduced call. */
internal fun CSharpOverloads.betterMember(m: Form, n: Form, arguments: List<CSharpArgument>, receiver: SemanticType?, site: CSharpSimpleName?): Boolean? {
    val m1 = member(m, arguments.size) ?: return null
    val m2 = member(n, arguments.size) ?: return null
    if ((m.receiverTarget != null) != (n.receiverTarget != null)) {
        // Roslyn never compares the two: the extension methods are looked up only when no instance method applies (resolve drops them)
        SemanticTrace.trace { "better ${site?.text} at ${SemanticTrace.at(site)}: an instance method against an extension method" }
        return null
    }
    val args = ArrayList<Argument>()
    if (m.receiverTarget != null) args += Argument.Receiver(receiver ?: return null)
    for (argument in arguments) args += Argument.Expression(argument.expression ?: return null)
    return try {
        betterFunctionMember.betterFunctionMember(m1, m2, args) == BetterResult.LEFT
    } catch (e: Unknown) {
        SemanticTrace.trace { "better ${site?.text} at ${SemanticTrace.at(site)}: ${SemanticTrace.show(m.symbol)} vs ${SemanticTrace.show(n.symbol)}: not known — ${e.describe()}" }
        null
    }
}

/** §12.6.4.5 for an argument known by its type alone (the receiver in the query translation): 1 when [t1] is better, -1, 0; null not known. */
internal fun CSharpOverloads.betterFromType(type: SemanticType, t1: SemanticType, t2: SemanticType): Int? = try {
    when (betterFunctionMember.betterConversionFromExpression(Argument.Receiver(type), t1, t2).first) {
        BetterResult.LEFT -> 1
        BetterResult.RIGHT -> -1
        BetterResult.NEITHER -> 0
    }
} catch (e: Unknown) {
    null
}

internal fun CSharpOverloads.isHandler(type: SemanticType): Boolean = type is SemanticType.Library && type.type.attributes.any { it.endsWith("InterpolatedStringHandlerAttribute") }

/** [form] as the port sees it, the receiver of a reduced call its first argument; null when a parameter type is not known. */
private fun CSharpOverloads.member(form: Form, argumentCount: Int): Member? {
    val reduced = form.receiverTarget != null
    val targets = (listOfNotNull(form.receiverTarget) + form.targets).map { it ?: return null }
    val definitionTargets = (if (reduced) listOf(form.declaredReceiver ?: return null) else emptyList()) + form.definitionTargets.map { it ?: return null }
    val refKinds = (if (reduced) listOf(null) else emptyList<String?>()) + (if (form.refKinds.size == argumentCount) form.refKinds else List(argumentCount) { null })
    val declaredParameterCount = form.declared.size + (if (reduced) 1 else 0)
    val count = targets.size
    // `GetParameterCounts`: an expanded form uses as many parameters as there are arguments, or all but the unused `params` one
    val parametersUsed = if (form.expanded) (if (count < declaredParameterCount) declaredParameterCount - 1 else count) else declaredParameterCount
    return Member(
        arity = form.methodArguments.size, expanded = form.expanded, targets = targets, refKinds = refKinds, definitionTargets = definitionTargets,
        declaredParameterCount = declaredParameterCount, parametersUsed = parametersUsed, paramsType = form.paramsType,
        toLastParameter = List(count) { form.expanded && it >= declaredParameterCount - 1 }, inferredFromFunctionType = form.inferredFromFunctionType,
    )
}

/** The resolver as the port's host: the expressions as Roslyn's bound nodes know them. */
internal class BetternessHost(private val o: CSharpOverloads) : RoslynBetterFunctionMember.Host {
    private val r get() = o.r

    private fun delegateOf(to: SemanticType): SemanticType? = r.expressions.unwrapExpression(to)?.takeIf { r.expressions.delegateSignature(it) != null }

    override fun conversionKind(e: CSharpExpression, to: SemanticType): String {
        val value = o.unparenthesized(e)
        return when {
            value is CSharpInterpolatedStringExpression -> if (o.isHandler(to)) RoslynBetterFunctionMember.INTERPOLATED_STRING_HANDLER else RoslynBetterFunctionMember.OTHER
            value is CSharpAnonymousFunctionExpression -> if (delegateOf(to) != null) RoslynBetterFunctionMember.ANONYMOUS_FUNCTION else RoslynBetterFunctionMember.FUNCTION_TYPE
            value is CSharpCollectionExpression -> RoslynBetterFunctionMember.COLLECTION_EXPRESSION
            o.methodGroup(value) != null -> if (delegateOf(to) != null) RoslynBetterFunctionMember.METHOD_GROUP else RoslynBetterFunctionMember.FUNCTION_TYPE
            value is CSharpConditionalExpression && r.typeOf(value) == null -> RoslynBetterFunctionMember.CONDITIONAL_EXPRESSION
            // `out var x` / `out _`: typed by the parameter of the method chosen — asking now is the call asking itself (a cycle); no function type anyway
            // likewise `default`, `null`, `new()`, `[…]`: typed by the target, no function type (`Should().Be(default)` was a cycle too)
            value is CSharpDeclarationExpression || o.isDiscard(value) || o.targetTyped(value) -> RoslynBetterFunctionMember.OTHER
            else -> {
                val type = r.typeOf(value) ?: return RoslynBetterFunctionMember.OTHER
                when {
                    o.same(type, to) == true -> RoslynBetterFunctionMember.IDENTITY
                    isSpanConversion(type, to) -> RoslynBetterFunctionMember.IMPLICIT_SPAN
                    else -> RoslynBetterFunctionMember.OTHER
                }
            }
        }
    }

    /** C# 14: an array to `Span<T>` / `ReadOnlySpan<T>`, a string to `ReadOnlySpan<char>` — the implicit span conversion. */
    private fun isSpanConversion(from: SemanticType, to: SemanticType): Boolean {
        val target = r.definitionName(to)
        if (target != "System.Span`1" && target != "System.ReadOnlySpan`1") return false
        return from is SemanticType.ArrayOf || r.definitionName(from) == STRING && target == "System.ReadOnlySpan`1"
    }

    override fun naturalType(e: CSharpExpression): SemanticType? {
        val value = o.unparenthesized(e)
        if (o.targetTyped(value)) return null
        // a conditional expression or a tuple literal may have no natural type (target-typed, C# 9)
        if (value is CSharpConditionalExpression || value is CSharpTupleExpression) return r.typeOf(value)
        return r.typeOf(value) ?: throw Unknown("the type of `${SemanticTrace.text(value)}` is not known")
    }

    override fun isInterpolatedString(e: CSharpExpression): Boolean = o.unparenthesized(e) is CSharpInterpolatedStringExpression

    override fun isConstantInterpolatedString(e: CSharpExpression): Boolean =
        o.constantInterpolation(o.unparenthesized(e) as CSharpInterpolatedStringExpression) ?: throw Unknown("whether `${SemanticTrace.text(e)}` is constant is not known")

    override fun isImplicitlyTypedOutVariable(e: CSharpExpression): Boolean {
        val value = o.unparenthesized(e)
        return value is CSharpDeclarationExpression && value.type?.let(r::isVar) != false || value.elementType == SyntaxKind.IdentifierName && value.text == "_"
    }

    override fun lambdaOf(e: CSharpExpression): CSharpAnonymousFunctionExpression? = o.unparenthesized(e) as? CSharpAnonymousFunctionExpression

    override fun isAsync(lambda: CSharpAnonymousFunctionExpression): Boolean = r.expressions.isAsync(lambda.modifiers)

    override fun inferredReturnType(lambda: CSharpAnonymousFunctionExpression, delegate: SemanticType): SemanticType? {
        val returns = returnExpressions(lambda)
        // `() => throw …`, `() => null`: no inferred return type (Roslyn's `InferReturnType` finds none) — the targets decide, as for `GetAsync<object>(key, () => throw …)`
        if (returns.isEmpty() || returns.all { o.targetTyped(it) }) return null
        return boundFor(lambda, delegate) { r.expressions.lambdaReturnType(lambda) ?: throw Unknown("the type the lambda at ${SemanticTrace.at(lambda)} returns is not known") }
    }

    override fun <T> boundFor(lambda: CSharpAnonymousFunctionExpression, delegate: SemanticType, body: () -> T): T {
        val parameters = lambdaParameters(lambda)
        if (parameters == null || parameters.isEmpty() || parameters.any { it.type != null }) return body()
        val signature = r.expressions.delegateSignature(delegate) ?: throw Unknown("the signature of $delegate is not known")
        if (signature.first.size != parameters.size) throw Unknown("the lambda at ${SemanticTrace.at(lambda)} takes ${parameters.size} parameters, $delegate ${signature.first.size}")
        return r.bindLambda(lambda, parameters, signature.first.map { it ?: throw Unknown("a parameter type of $delegate is not known") }, body)
    }

    override fun returnExpressions(lambda: CSharpAnonymousFunctionExpression): List<CSharpExpression> {
        lambda.expressionBody?.let { return listOf(it) }
        val block = lambda.block ?: return emptyList()
        return o.returnsOf(block, lambda).mapNotNull { it.expression }
    }

    override fun tupleElements(e: CSharpExpression): List<CSharpExpression>? =
        (o.unparenthesized(e) as? CSharpTupleExpression)?.arguments?.map { it.expression ?: throw Unknown("an element of the tuple is missing") }

    override fun isMethodGroup(e: CSharpExpression): Boolean = o.methodGroup(o.unparenthesized(e)) != null

    override fun methodGroupConvertsTo(e: CSharpExpression, delegate: SemanticType): Boolean {
        val group = o.methodGroup(o.unparenthesized(e)) ?: return false
        return when (o.methodGroupConversion(o.unparenthesized(e), group, delegate)) {
            Conversion.NONE -> false
            Conversion.UNKNOWN -> throw Unknown("whether the method group `${SemanticTrace.text(e)}` converts to $delegate is not known")
            else -> true
        }
    }

    override fun collectionElements(e: CSharpExpression): List<Element>? = (o.unparenthesized(e) as? CSharpCollectionExpression)?.elements?.map { element ->
        when (element) {
            is CSharpSpreadElement -> Element.Spread
            is CSharpExpressionElement -> Element.Expression(element.expression ?: throw Unknown("an element of the collection expression is missing"))
            else -> throw Unknown("an element of the collection expression is not modeled")
        }
    }

    override fun identicalDelegateParameters(d1: SemanticType, d2: SemanticType): Boolean {
        val s1 = r.expressions.delegateSignature(d1) ?: throw Unknown("the signature of $d1 is not known")
        val s2 = r.expressions.delegateSignature(d2) ?: throw Unknown("the signature of $d2 is not known")
        if (s1.first.size != s2.first.size) return false
        val k1 = o.delegateRefKinds(d1) ?: throw Unknown("the ref kinds of $d1 are not known")
        val k2 = o.delegateRefKinds(d2) ?: throw Unknown("the ref kinds of $d2 are not known")
        if (k1 != k2) return false
        return s1.first.indices.all { o.same(s1.first[it], s2.first[it]) ?: throw Unknown("the parameters of $d1 and $d2 cannot be compared") }
    }
}
