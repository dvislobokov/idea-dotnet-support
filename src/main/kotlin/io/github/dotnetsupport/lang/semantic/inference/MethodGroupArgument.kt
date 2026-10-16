package io.github.dotnetsupport.lang.semantic.inference

import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.lang.semantic.roslyn.InferenceArgument
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynMethodTypeInferrer

/**
 * A method group as Roslyn's inference sees it (`BoundMethodGroup`): a name that is not called and stands for methods only; what it
 * returns for a delegate's parameter types is known for one method, not generic, of that arity (overload resolution of the group is not
 * modeled). Its function type (C# 10) is not modeled either: [RoslynMethodTypeInferrer] refuses a group to a non-delegate.
 */
internal class MethodGroupArgument(private val r: CSharpNameResolver) {

    fun isMethodGroup(e: CSharpExpression): Boolean = methods(e) != null

    fun of(value: CSharpExpression): InferenceArgument.MethodGroup = InferenceArgument.MethodGroup { parameters -> returnType(value, parameters) }

    /** The return type of the method of the group [value] a delegate with [parameters] takes: one method, not generic, of that arity. */
    private fun returnType(value: CSharpExpression, parameters: List<SemanticType>): SemanticType? {
        val methods = methods(value) ?: throw RoslynMethodTypeInferrer.Unknown()
        val single = methods.singleOrNull() ?: throw RoslynMethodTypeInferrer.Unknown()
        if (r.isGeneric(single)) throw RoslynMethodTypeInferrer.Unknown()
        val signature = r.signature(single, false) ?: throw RoslynMethodTypeInferrer.Unknown()
        if (signature.size != parameters.size) throw RoslynMethodTypeInferrer.Unknown()
        return r.returnType(single, emptyList())?.takeIf { r.definitionName(it) != "System.Void" } ?: throw RoslynMethodTypeInferrer.Unknown()
    }

    private fun methods(e: CSharpExpression): List<CSharpSymbol>? {
        val name = when (e) {
            is CSharpSimpleName -> e
            is CSharpMemberAccessExpression -> e.nameElement
            else -> null
        } ?: return null
        if (r.invocationOf(name) != null) return null
        return r.resolveName(name)?.symbols?.takeIf { s -> s.isNotEmpty() && s.all(r::isInMethodGroup) }
    }
}
