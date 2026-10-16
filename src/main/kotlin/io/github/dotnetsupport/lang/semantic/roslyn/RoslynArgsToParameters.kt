package io.github.dotnetsupport.lang.semantic.roslyn

/**
 * Roslyn's `OverloadResolution_ArgsToParameters.cs` (CSHARP_PSI_MIGRATION.md, D6c, the first piece): which parameter each argument of a
 * call corresponds to, in the normal or the expanded (`params`) form, and why a candidate does not apply when no correspondence exists —
 * the reasons in Roslyn's order of priority, the material of CS1739 / CS1744 / CS1740 / CS7036 / CS8323. `__arglist` (vararg) is not
 * modeled. Pure: parameters by name and optionality, arguments by name.
 */
internal object RoslynArgsToParameters {

    class Parameter(val name: String, val optional: Boolean)

    sealed class Result {
        /** The parameter of each argument (`argsToParameters`); in the expanded form the elements of the array go to the last one. */
        class Applicable(val argsToParameters: List<Int>) : Result()

        /** Not applicable in this form; [position] the argument (or, for [Kind.REQUIRED_PARAMETER_MISSING], the parameter) at fault. */
        class Bad(val kind: Kind, val position: Int) : Result()
    }

    /** `ArgumentAnalysisResultKind`, the failures, in the order Roslyn reports them. */
    enum class Kind { BAD_NON_TRAILING_NAMED_ARGUMENT, NO_CORRESPONDING_PARAMETER, NO_CORRESPONDING_NAMED_PARAMETER, NAME_USED_FOR_POSITIONAL, REQUIRED_PARAMETER_MISSING, DUPLICATE_NAMED_ARGUMENT }

    /** `AnalyzeArguments`: the arguments named [names] (null for a positional one) against [parameters] in the [expanded] form or the normal. */
    fun analyze(parameters: List<Parameter>, names: List<String?>, expanded: Boolean, isMethodGroupConversion: Boolean = false): Result {
        // the easy out: no named arguments, normal form
        if (!expanded && names.all { it == null }) return analyzeForNormalFormNoNamedArguments(parameters, names.size, isMethodGroupConversion)
        val argumentCount = names.size
        val argsToParameters = IntArray(argumentCount)
        var unmatchedArgumentIndex: Int? = null
        var unmatchedArgumentIsNamed = false
        val seen = Seen()
        for (argumentPosition in 0 until argumentCount) {
            val named = names[argumentPosition] != null
            val parameterPosition = correspondsToAnyParameter(parameters, expanded, names, argumentPosition, seen) ?: -1
            if (parameterPosition == -1 && unmatchedArgumentIndex == null) {
                unmatchedArgumentIndex = argumentPosition
                unmatchedArgumentIsNamed = named
            }
            argsToParameters[argumentPosition] = parameterPosition
        }
        // (1) a named argument used out of position and followed by unnamed arguments
        checkForBadNonTrailingNamedArgument(names, argsToParameters)?.let { return Result.Bad(Kind.BAD_NON_TRAILING_NAMED_ARGUMENT, it) }
        // (2) an argument without a corresponding parameter
        unmatchedArgumentIndex?.let { return Result.Bad(if (unmatchedArgumentIsNamed) Kind.NO_CORRESPONDING_NAMED_PARAMETER else Kind.NO_CORRESPONDING_PARAMETER, it) }
        // (3) a named argument for a parameter a positional one supplied already
        nameUsedForPositional(names, argsToParameters)?.let { return Result.Bad(Kind.NAME_USED_FOR_POSITIONAL, it) }
        // (4) a non-optional parameter without an argument
        checkForMissingRequiredParameter(argsToParameters, parameters, isMethodGroupConversion, expanded)?.let { return Result.Bad(Kind.REQUIRED_PARAMETER_MISSING, it) }
        // (5) a name given twice
        checkForDuplicateNamedArgument(names)?.let { return Result.Bad(Kind.DUPLICATE_NAMED_ARGUMENT, it) }
        return Result.Applicable(argsToParameters.toList())
    }

    private class Seen {
        var namedParams = false
        var outOfPositionNamedArgument = false
    }

    private fun checkForBadNonTrailingNamedArgument(names: List<String?>, argsToParameters: IntArray): Int? {
        val found = names.indices.firstOrNull { i -> argsToParameters[i] != -1 && argsToParameters[i] != i && names[i] != null } ?: return null
        return found.takeIf { (found + 1 until names.size).any { names[it] == null } }
    }

    /** `CorrespondsToAnyParameter` (§12.6.2.2). */
    private fun correspondsToAnyParameter(parameters: List<Parameter>, expanded: Boolean, names: List<String?>, argumentPosition: Int, seen: Seen): Int? {
        val name = names[argumentPosition]
        if (name == null) {
            // unnamed arguments after a named one for the params parameter, or after an out-of-position named one, correspond to nothing
            if (seen.namedParams || seen.outOfPositionNamedArgument) return null
            if (argumentPosition >= parameters.size) return if (expanded) parameters.size - 1 else null
            return argumentPosition
        }
        // SPEC VIOLATION, kept by Roslyn since C# 4: a named argument may correspond to the parameter array in the expanded form
        // (`M(params int[] x)` called `M(x: 1234)`)
        for (p in parameters.indices) {
            if (parameters[p].name != name) continue
            if (expanded && p == parameters.size - 1) seen.namedParams = true
            if (p != argumentPosition) seen.outOfPositionNamedArgument = true
            return p
        }
        return null
    }

    private fun analyzeForNormalFormNoNamedArguments(parameters: List<Parameter>, argumentCount: Int, isMethodGroupConversion: Boolean): Result {
        if (argumentCount < parameters.size) {
            for (p in argumentCount until parameters.size) if (!canBeOptional(parameters[p], isMethodGroupConversion)) return Result.Bad(Kind.REQUIRED_PARAMETER_MISSING, p)
        } else if (parameters.size < argumentCount) {
            return Result.Bad(Kind.NO_CORRESPONDING_PARAMETER, parameters.size)
        }
        return Result.Applicable((0 until argumentCount).toList())
    }

    /** No parameter is optional for a method group conversion (§10.8: the candidates that omit none). */
    private fun canBeOptional(parameter: Parameter, isMethodGroupConversion: Boolean): Boolean = !isMethodGroupConversion && parameter.optional

    private fun nameUsedForPositional(names: List<String?>, argsToParameters: IntArray): Int? {
        for (argumentPosition in argsToParameters.indices) {
            if (names[argumentPosition] == null) continue
            for (i in 0 until argumentPosition) if (names[i] == null && argsToParameters[argumentPosition] == argsToParameters[i]) return argumentPosition
        }
        return null
    }

    private fun checkForMissingRequiredParameter(argsToParameters: IntArray, parameters: List<Parameter>, isMethodGroupConversion: Boolean, expanded: Boolean): Int? {
        // in the expanded form the last parameter is always optional
        val count = if (expanded) parameters.size - 1 else parameters.size
        for (p in 0 until count) {
            if (canBeOptional(parameters[p], isMethodGroupConversion)) continue
            if (argsToParameters.none { it == p }) return p
        }
        return null
    }

    private fun checkForDuplicateNamedArgument(names: List<String?>): Int? {
        val defined = HashSet<String>()
        for ((i, name) in names.withIndex()) if (name != null && !defined.add(name)) return i
        return null
    }
}
