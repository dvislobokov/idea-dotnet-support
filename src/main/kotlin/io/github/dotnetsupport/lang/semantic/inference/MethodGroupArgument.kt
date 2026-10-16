package io.github.dotnetsupport.lang.semantic.inference

import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.methodGroupFunctionType
import io.github.dotnetsupport.lang.semantic.methodGroupReturnType
import io.github.dotnetsupport.lang.semantic.roslyn.InferenceArgument

/**
 * A method group as Roslyn's inference sees it (`BoundMethodGroup`): a name that is not called and stands for methods only; what it
 * returns for a delegate's parameter types is what the method group resolution picks (`MethodGroupReturnType`, the port
 * `RoslynMethodGroupConversion` through [io.github.dotnetsupport.lang.semantic.CSharpOverloads]); its function type (C# 10) is the
 * delegate of its unique signature.
 */
internal class MethodGroupArgument(private val r: CSharpNameResolver) {

    fun isMethodGroup(e: CSharpExpression): Boolean = methods(e) != null

    fun of(value: CSharpExpression): InferenceArgument.MethodGroup =
        InferenceArgument.MethodGroup({ parameters -> r.overloads.methodGroupReturnType(value, parameters) }, { r.overloads.methodGroupFunctionType(value) })

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
