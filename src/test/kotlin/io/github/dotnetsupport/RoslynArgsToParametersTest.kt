package io.github.dotnetsupport

import io.github.dotnetsupport.lang.semantic.roslyn.RoslynArgsToParameters
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynArgsToParameters.Kind
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynArgsToParameters.Parameter
import io.github.dotnetsupport.lang.semantic.roslyn.RoslynArgsToParameters.Result
import junit.framework.TestCase

/** Roslyn's correspondence of arguments to parameters (`OverloadResolution_ArgsToParameters.cs`, D6c): the map and the reason of a failure. */
class RoslynArgsToParametersTest : TestCase() {
    private fun p(spec: String): List<Parameter> = spec.split(' ').filter { it.isNotEmpty() }.map { Parameter(it.removeSuffix("?"), it.endsWith("?")) }

    private fun analyze(parameters: String, names: List<String?>, expanded: Boolean = false): String = when (val r = RoslynArgsToParameters.analyze(p(parameters), names, expanded)) {
        is Result.Applicable -> r.argsToParameters.joinToString(",")
        is Result.Bad -> "${r.kind}@${r.position}"
    }

    fun testPositional() {
        assertEquals("0,1", analyze("a b", listOf(null, null)))
        assertEquals("0", analyze("a b?", listOf(null)))
        assertEquals("${Kind.REQUIRED_PARAMETER_MISSING}@1", analyze("a b", listOf(null)))
        assertEquals("${Kind.NO_CORRESPONDING_PARAMETER}@2", analyze("a b", listOf(null, null, null)))
    }

    fun testNamed() {
        assertEquals("1,0", analyze("a b", listOf("b", "a")))
        // a named argument in its own position lets positional ones follow (C# 7.2)
        assertEquals("0,1", analyze("a b", listOf("a", null)))
        assertEquals("${Kind.BAD_NON_TRAILING_NAMED_ARGUMENT}@0", analyze("a b", listOf("b", null)))
        assertEquals("${Kind.NO_CORRESPONDING_NAMED_PARAMETER}@1", analyze("a b", listOf(null, "c")))
        assertEquals("${Kind.NAME_USED_FOR_POSITIONAL}@1", analyze("a b?", listOf(null, "a")))
        assertEquals("${Kind.REQUIRED_PARAMETER_MISSING}@0", analyze("a b", listOf("b")))
        assertEquals("${Kind.DUPLICATE_NAMED_ARGUMENT}@2", analyze("a b c?", listOf("a", "b", "b")))
    }

    fun testExpandedForm() {
        assertEquals("0,1,1,1", analyze("a xs", listOf(null, null, null, null), expanded = true))
        assertEquals("0", analyze("a xs", listOf(null), expanded = true))
        // SPEC VIOLATION kept by Roslyn: `M(params int[] x)` called `M(x: 1234)` applies in the expanded form
        assertEquals("0", analyze("x", listOf("x"), expanded = true))
        // a positional argument after a named one for the array corresponds to nothing
        assertEquals("${Kind.BAD_NON_TRAILING_NAMED_ARGUMENT}@0", analyze("a xs", listOf("xs", null), expanded = true))
    }

    fun testMethodGroupConversionHasNoOptionalParameters() {
        assertEquals("${Kind.REQUIRED_PARAMETER_MISSING}@1", RoslynArgsToParameters.analyze(p("a b?"), listOf(null), expanded = false, isMethodGroupConversion = true)
            .let { (it as Result.Bad).kind.toString() + "@" + it.position })
    }
}
