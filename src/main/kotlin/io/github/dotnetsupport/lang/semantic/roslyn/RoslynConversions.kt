// Ported from Roslyn: src/Compilers/CSharp/Portable/Binder/Semantics/Conversions/ConversionsBase.cs (the implicit conversions from a type:
// ClassifyImplicitConversionFromType, ClassifyStandardImplicitConversion and what they call) and UserDefinedImplicitConversions.cs with
// UserDefinedConversions.cs (AnalyzeImplicitUserDefinedConversions), roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c. Roslyn:
// Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md). Kotlin names are Roslyn's with a lower-case first letter; what is
// left out and why — CSHARP_PSI_MIGRATION.md, D6d.
package io.github.dotnetsupport.lang.semantic.roslyn

import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * C# §10.2, the implicit conversions from a type to a type, as Roslyn classifies them: identity, numeric, nullable, reference (base classes,
 * interfaces, variance, arrays, delegates, type parameters), boxing, tuple, span, then the user-defined ones (§10.5.4: the set of types D,
 * the applicable and lifted operators, the most specific source and target, the most specific operator).
 *
 * Proving only: an answer is the kind of a conversion this code proves exists, or null — "none" and "not known" alike, because Roslyn knows
 * every type and we may not ([RoslynTypes] answers null). The caller keeps its own answer for null. So a fact we lack can only leave a
 * conversion unproved, never make a false error. Left out: nullability, `dynamic`, pointers, function types, `allows ref struct`, the union
 * conversions; conversions from expressions (constants, `null`, lambdas, method groups) are the caller's.
 */
internal class RoslynConversions(
    private val types: RoslynTypes,
    /** The implicit operators [type] itself declares (operand, result), substituted; null when they cannot be known. */
    private val implicitOperators: (SemanticType) -> List<Pair<SemanticType?, SemanticType?>>?,
) {
    enum class Kind { IDENTITY, IMPLICIT_NUMERIC, IMPLICIT_NULLABLE, IMPLICIT_REFERENCE, BOXING, IMPLICIT_TUPLE, IMPLICIT_SPAN, IMPLICIT_USER_DEFINED }

    private var currentRecursionDepth = 0

    private inline fun <T> nested(fallback: T, block: () -> T): T {
        if (currentRecursionDepth >= MAXIMUM_RECURSION_DEPTH) return fallback
        currentRecursionDepth++
        try {
            return block()
        } finally {
            currentRecursionDepth--
        }
    }

    fun classifyImplicitConversionFromType(source: SemanticType, destination: SemanticType): Kind? = nested(null) {
        if (hasIdentityConversion(source, destination)) return@nested Kind.IDENTITY
        classifyImplicitBuiltInConversionSlow(source, destination)?.let { return@nested it }
        if (analyzeImplicitUserDefinedConversions(source, destination)) Kind.IMPLICIT_USER_DEFINED else null
    }

    private fun classifyImplicitBuiltInConversionSlow(source: SemanticType, destination: SemanticType): Kind? {
        if (types.isVoid(source) || types.isVoid(destination)) return null
        return classifyStandardImplicitConversion(source, destination)
    }

    /** The standard implicit conversions (§10.4.2): what a user-defined conversion may have on either side of its operator. */
    fun classifyStandardImplicitConversion(source: SemanticType, destination: SemanticType): Kind? = nested(null) {
        when {
            hasIdentityConversion(source, destination) -> Kind.IDENTITY
            hasImplicitNumericConversion(source, destination) -> Kind.IMPLICIT_NUMERIC
            classifyImplicitNullableConversion(source, destination) -> Kind.IMPLICIT_NULLABLE
            hasImplicitReferenceConversion(source, destination) -> Kind.IMPLICIT_REFERENCE
            hasBoxingConversion(source, destination) -> Kind.BOXING
            classifyImplicitTupleConversion(source, destination) -> Kind.IMPLICIT_TUPLE
            hasImplicitSpanConversion(source, destination) -> Kind.IMPLICIT_SPAN
            else -> null
        }
    }

    // ---- identity, numeric, nullable, tuple

    private fun hasIdentityConversion(type1: SemanticType?, type2: SemanticType?): Boolean = type1 != null && type2 != null && types.same(type1, type2) == true

    private fun hasImplicitNumericConversion(source: SemanticType, destination: SemanticType): Boolean {
        val from = types.definitionName(source) ?: return false
        val to = types.definitionName(destination) ?: return false
        return implicitNumeric[from]?.contains(to) == true
    }

    private fun classifyImplicitNullableConversion(source: SemanticType, destination: SemanticType): Boolean {
        val unwrappedDestination = types.nullableUnderlyingType(destination) ?: return false
        val unwrappedSource = strippedType(source)
        if (types.isValueType(unwrappedSource) != true) return false
        return hasIdentityConversion(unwrappedSource, unwrappedDestination) || hasImplicitNumericConversion(unwrappedSource, unwrappedDestination) ||
            classifyImplicitTupleConversion(unwrappedSource, unwrappedDestination)
    }

    /** `StrippedType`: the underlying type of `Nullable<T>`, else the type. */
    private fun strippedType(type: SemanticType): SemanticType = types.nullableUnderlyingType(type) ?: type

    private fun classifyImplicitTupleConversion(source: SemanticType, destination: SemanticType): Boolean {
        val sourceTypes = types.tupleElementTypes(source) ?: return false
        val destTypes = types.tupleElementTypes(destination) ?: return false
        if (sourceTypes.size != destTypes.size) return false
        return sourceTypes.indices.all { i ->
            val s = sourceTypes[i] ?: return false
            val d = destTypes[i] ?: return false
            classifyImplicitConversionFromType(s, d) != null
        }
    }

    // ---- reference conversions

    private fun hasImplicitReferenceConversion(source: SemanticType, destination: SemanticType): Boolean {
        // a type parameter: the reference conversions of its constraints, or the boxing ones when it is not known to be a reference type
        if (source is SemanticType.Parameter) return types.isReferenceType(source) == true && hasImplicitTypeParameterConversion(source, destination)
        if (types.isReferenceType(source) != true) return false
        if (types.definitionName(destination) == OBJECT) return true
        return when (types.typeKind(source)) {
            RoslynTypes.TypeKind.CLASS -> types.isClassType(destination) && isBaseClass(source, destination) || hasImplicitConversionToInterface(source, destination)
            RoslynTypes.TypeKind.INTERFACE -> hasImplicitConversionToInterface(source, destination)
            RoslynTypes.TypeKind.DELEGATE -> hasImplicitConversionFromDelegate(source, destination)
            RoslynTypes.TypeKind.ARRAY -> hasImplicitConversionFromArray(source, destination)
            else -> false
        }
    }

    private fun hasImplicitConversionToInterface(source: SemanticType, destination: SemanticType): Boolean {
        if (!types.isInterfaceType(destination)) return false
        if (types.isClassType(source)) return hasAnyBaseInterfaceConversion(source, destination)
        if (types.isInterfaceType(source)) {
            if (hasAnyBaseInterfaceConversion(source, destination)) return true
            if (!hasIdentityConversion(source, destination) && hasInterfaceVarianceConversion(source, destination)) return true
        }
        return false
    }

    private fun hasImplicitConversionFromArray(source: SemanticType, destination: SemanticType): Boolean {
        if (source !is SemanticType.ArrayOf) return false
        if (hasCovariantArrayConversion(source, destination)) return true
        if (types.definitionName(destination) == RoslynTypes.ARRAY) return true
        if (types.special(RoslynTypes.ARRAY)?.let { isBaseInterface(destination, it) } == true) return true
        return hasArrayConversionToInterface(source, destination)
    }

    private fun hasCovariantArrayConversion(source: SemanticType, destination: SemanticType): Boolean {
        if (source !is SemanticType.ArrayOf || destination !is SemanticType.ArrayOf || source.rank != destination.rank) return false
        val s = source.element ?: return false
        val d = destination.element ?: return false
        return hasImplicitReferenceConversion(s, d)
    }

    private fun hasArrayConversionToInterface(source: SemanticType.ArrayOf, destination: SemanticType): Boolean {
        if (!types.isSZArray(source) || !types.isInterfaceType(destination)) return false
        if (types.definitionName(destination) == ENUMERABLE) return true
        val arguments = types.typeArguments(destination)
        if (arguments.size != 1 || types.definitionName(destination) !in POSSIBLE_ARRAY_GENERIC_INTERFACES) return false
        val elementType = source.element ?: return false
        val argument0 = arguments[0] ?: return false
        return hasIdentityConversion(elementType, argument0) || hasImplicitReferenceConversion(elementType, argument0)
    }

    private fun hasImplicitConversionFromDelegate(source: SemanticType, destination: SemanticType): Boolean {
        if (!types.isDelegateType(source)) return false
        val specialDestination = types.definitionName(destination)
        if (specialDestination == MULTICAST_DELEGATE || specialDestination == DELEGATE) return true
        if (types.special(MULTICAST_DELEGATE)?.let { isBaseInterface(destination, it) } == true) return true
        return hasDelegateVarianceConversion(source, destination)
    }

    /** `IsBaseInterface`: [baseType] is among all the interfaces of [derivedType]. */
    private fun isBaseInterface(baseType: SemanticType, derivedType: SemanticType): Boolean =
        types.isInterfaceType(baseType) && types.allInterfaces(derivedType).any { hasIdentityConversion(it, baseType) }

    /** `IsBaseClass`: [baseType] is a class on the chain of base types of [derivedType] (not the type itself). */
    private fun isBaseClass(derivedType: SemanticType, baseType: SemanticType): Boolean {
        if (!types.isClassType(baseType)) return false
        var b = baseTypeOf(derivedType)
        var steps = 0
        while (b != null && steps++ < MAXIMUM_BASE_CHAIN) {
            if (hasIdentityConversion(b, baseType)) return true
            b = baseTypeOf(b)
        }
        return false
    }

    /** `BaseTypeWithDefinitionUseSiteDiagnostics`; where the facade does not say, what every type of the kind has: `ValueType`, `Enum`, `object`. */
    private fun baseTypeOf(type: SemanticType): SemanticType? {
        if (types.definitionName(type) == OBJECT) return null
        types.baseType(type)?.let { return it }
        return when (types.typeKind(type)) {
            RoslynTypes.TypeKind.STRUCT -> types.special(RoslynTypes.VALUE_TYPE)
            RoslynTypes.TypeKind.ENUM -> types.special(ENUM)
            RoslynTypes.TypeKind.DELEGATE -> types.special(MULTICAST_DELEGATE)
            RoslynTypes.TypeKind.CLASS -> types.special(OBJECT)
            else -> null
        }
    }

    private fun hasAnyBaseInterfaceConversion(derivedType: SemanticType, baseType: SemanticType): Boolean = implementsVarianceCompatibleInterface(derivedType, baseType)

    private fun implementsVarianceCompatibleInterface(derivedType: SemanticType, baseType: SemanticType): Boolean {
        if (!types.isInterfaceType(baseType)) return false
        if (derivedType !is SemanticType.Library && derivedType !is SemanticType.Source) return false
        return types.allInterfaces(derivedType).any { hasInterfaceVarianceConversion(it, baseType) }
    }

    private fun hasInterfaceVarianceConversion(source: SemanticType, destination: SemanticType): Boolean =
        types.isInterfaceType(source) && types.isInterfaceType(destination) && hasVariantConversion(source, destination)

    private fun hasDelegateVarianceConversion(source: SemanticType, destination: SemanticType): Boolean =
        types.isDelegateType(source) && types.isDelegateType(destination) && hasVariantConversion(source, destination)

    private fun hasVariantConversion(source: SemanticType, destination: SemanticType): Boolean = nested(false) {
        // HasVariantConversionQuick
        if (hasIdentityConversion(source, destination)) return@nested true
        val definition = types.definitionName(source)
        if (definition == null || definition != types.definitionName(destination)) return@nested false
        hasVariantConversionNoCycleCheck(source, destination)
    }

    private fun hasVariantConversionNoCycleCheck(source: SemanticType, destination: SemanticType): Boolean {
        // GetAllTypeArguments: the arguments of an outer generic type take no variance — they must be the same
        val sourceOuter = types.outerType(source)
        val destinationOuter = types.outerType(destination)
        if ((sourceOuter != null || destinationOuter != null) && !hasIdentityConversion(sourceOuter, destinationOuter)) return false
        val sourceTypeArguments = types.typeArguments(source)
        val destinationTypeArguments = types.typeArguments(destination)
        if (sourceTypeArguments.size != destinationTypeArguments.size) return false
        for (paramIndex in sourceTypeArguments.indices) {
            val sourceTypeArgument = sourceTypeArguments[paramIndex] ?: return false
            val destinationTypeArgument = destinationTypeArguments[paramIndex] ?: return false
            if (hasIdentityConversion(sourceTypeArgument, destinationTypeArgument)) continue
            when (types.variance(source, paramIndex)) {
                RoslynTypes.VarianceKind.OUT -> if (!hasImplicitReferenceConversion(sourceTypeArgument, destinationTypeArgument)) return false
                RoslynTypes.VarianceKind.IN -> if (!hasImplicitReferenceConversion(destinationTypeArgument, sourceTypeArgument)) return false
                // the special case of IEquatable<T> is about nullability only, which is left out
                RoslynTypes.VarianceKind.NONE, null -> return false
            }
        }
        return true
    }

    // ---- type parameters and boxing

    /** §10.2.12, the conversions of a type parameter: to its effective base class and its bases, its interfaces, a type parameter it depends on. */
    private fun hasImplicitTypeParameterConversion(source: SemanticType.Parameter, destination: SemanticType): Boolean =
        hasImplicitEffectiveBaseConversion(source, destination) || hasImplicitEffectiveInterfaceSetConversion(source, destination) ||
            destination is SemanticType.Parameter && dependsOn(source, destination, 0)

    private fun hasImplicitEffectiveBaseConversion(source: SemanticType.Parameter, destination: SemanticType): Boolean {
        val effectiveBaseClass = types.baseType(source) ?: return false
        return hasIdentityConversion(effectiveBaseClass, destination) || isBaseClass(effectiveBaseClass, destination) ||
            hasAnyBaseInterfaceConversion(effectiveBaseClass, destination)
    }

    private fun hasImplicitEffectiveInterfaceSetConversion(source: SemanticType.Parameter, destination: SemanticType): Boolean =
        types.isInterfaceType(destination) && types.allInterfaces(source).any { hasInterfaceVarianceConversion(it, destination) }

    /** `DependsOn`: [other] is among the constraints of [source], directly or through another type parameter. */
    private fun dependsOn(source: SemanticType.Parameter, other: SemanticType.Parameter, depth: Int): Boolean {
        if (depth > MAXIMUM_RECURSION_DEPTH) return false
        return types.constraintTypes(source).any { constraint ->
            constraint is SemanticType.Parameter && (hasIdentityConversion(constraint, other) || dependsOn(constraint, other, depth + 1))
        }
    }

    private fun hasBoxingConversion(source: SemanticType, destination: SemanticType): Boolean {
        if (source is SemanticType.Parameter) return types.isReferenceType(source) != true && hasImplicitTypeParameterConversion(source, destination)
        if (types.isValueType(source) != true || types.isReferenceType(destination) != true) return false
        types.nullableUnderlyingType(source)?.let { return hasBoxingConversion(it, destination) }
        return isBaseClass(source, destination) || hasAnyBaseInterfaceConversion(source, destination)
    }

    // ---- spans (C# 14, first-class spans)

    private fun hasImplicitSpanConversion(source: SemanticType, destination: SemanticType): Boolean {
        val destinationElement = types.spanElementType(destination)
        if (source is SemanticType.ArrayOf && types.isSZArray(source)) {
            val elementType = source.element ?: return false
            destinationElement ?: return false
            return if (types.isReadOnlySpan(destination)) hasCovariantConversion(elementType, destinationElement) else hasIdentityConversion(elementType, destinationElement)
        }
        val sourceElement = types.spanElementType(source)
        if (sourceElement != null) return destinationElement != null && types.isReadOnlySpan(destination) && hasCovariantConversion(sourceElement, destinationElement)
        if (types.definitionName(source) == STRING) return destinationElement != null && types.isReadOnlySpan(destination) && types.definitionName(destinationElement) == CHAR
        return false
    }

    private fun hasCovariantConversion(source: SemanticType, destination: SemanticType): Boolean =
        hasIdentityConversion(source, destination) || hasImplicitReferenceConversion(source, destination)

    // ---- user-defined implicit conversions (UserDefinedImplicitConversions.cs)

    /** `UserDefinedConversionAnalysis`: an operator taking [fromType] to [toType], lifted where they are not its own ([operatorFrom], [operatorTo]). */
    private class UserDefinedConversionAnalysis(val fromType: SemanticType, val toType: SemanticType, val operatorFrom: SemanticType, val operatorTo: SemanticType)

    /** `AnalyzeImplicitUserDefinedConversions(...).Kind == Valid`: one most specific operator converts [source] to [target]. */
    private fun analyzeImplicitUserDefinedConversions(source: SemanticType, target: SemanticType): Boolean {
        val d = ArrayList<Pair<SemanticType, SemanticType.Parameter?>>()
        computeUserDefinedImplicitConversionTypeSet(source, target, d)
        val u = ArrayList<UserDefinedConversionAnalysis>()
        computeApplicableUserDefinedImplicitConversionSet(source, target, d, u)
        if (u.isEmpty()) return false
        val sx = mostSpecificSourceTypeForImplicitUserDefinedConversion(u, source) ?: return false
        val tx = mostSpecificTargetTypeForImplicitUserDefinedConversion(u, target) ?: return false
        return mostSpecificConversionOperator(sx, tx, u) != null
    }

    private fun computeUserDefinedImplicitConversionTypeSet(s: SemanticType, t: SemanticType, d: MutableList<Pair<SemanticType, SemanticType.Parameter?>>) {
        addTypesParticipatingInUserDefinedConversion(d, s, includeBaseTypes = true)
        addTypesParticipatingInUserDefinedConversion(d, t, includeBaseTypes = false)
    }

    /** `AddTypesParticipatingInUserDefinedConversion` (UserDefinedConversions.cs). */
    private fun addTypesParticipatingInUserDefinedConversion(result: MutableList<Pair<SemanticType, SemanticType.Parameter?>>, type: SemanticType, includeBaseTypes: Boolean) {
        val stripped = strippedType(type)
        val excludeExisting = result.isNotEmpty()
        fun add(participating: SemanticType, constrainedTo: SemanticType.Parameter?) {
            if (!excludeExisting || result.none { hasIdentityConversion(it.first, participating) }) result += participating to constrainedTo
        }
        fun addFromClassOrStruct(classOrStruct: SemanticType) {
            val kind = types.typeKind(classOrStruct)
            if (kind == RoslynTypes.TypeKind.CLASS || kind == RoslynTypes.TypeKind.STRUCT) add(classOrStruct, null)
            if (!includeBaseTypes) return
            var t = baseTypeOf(classOrStruct)
            var steps = 0
            while (t != null && steps++ < MAXIMUM_BASE_CHAIN) {
                add(t, null)
                t = baseTypeOf(t)
            }
        }
        if (stripped is SemanticType.Parameter) {
            types.baseType(stripped)?.let(::addFromClassOrStruct)
            for (iface in types.allInterfaces(stripped)) add(iface, stripped)
        } else {
            addFromClassOrStruct(stripped)
        }
    }

    private fun computeApplicableUserDefinedImplicitConversionSet(
        source: SemanticType, target: SemanticType, d: List<Pair<SemanticType, SemanticType.Parameter?>>, u: MutableList<UserDefinedConversionAnalysis>,
    ) {
        // DELIBERATE SPEC VIOLATION of Roslyn (bug 17021): only the source and the target themselves may not be interfaces
        if (types.isInterfaceType(source) || types.isInterfaceType(target)) return
        if (hasImplicitSpanConversion(source, target)) return
        var haveInterfaces = false
        for ((declaringType, _) in d) {
            if (types.isInterfaceType(declaringType)) haveInterfaces = true else addCandidatesFromType(declaringType, source, target, u)
        }
        if (u.isEmpty() && haveInterfaces) for ((declaringType, _) in d) if (types.isInterfaceType(declaringType)) addCandidatesFromType(declaringType, source, target, u)
    }

    private fun addCandidatesFromType(declaringType: SemanticType, source: SemanticType, target: SemanticType, u: MutableList<UserDefinedConversionAnalysis>) {
        for ((from, to) in implicitOperators(declaringType) ?: return) {
            val convertsFrom = from ?: continue
            var convertsTo = to ?: continue
            if (types.isVoid(convertsTo)) continue
            val fromConversion = encompassingImplicitConversion(source, convertsFrom)
            var toConversion = encompassingImplicitConversion(convertsTo, target)
            if (fromConversion && toConversion) {
                // the native compiler's fiction Roslyn keeps: X → Y used for `Y? y = x` is taken as X → Y?
                if (types.isNullableType(target) && isValidNullableTypeArgument(convertsTo)) {
                    convertsTo = makeNullableType(convertsTo) ?: continue
                    toConversion = encompassingImplicitConversion(convertsTo, target)
                }
                if (toConversion) u += UserDefinedConversionAnalysis(convertsFrom, convertsTo, from, to)
            } else if (types.isNullableType(source) && isValidNullableTypeArgument(convertsFrom) && canBeAssignedNull(target)) {
                val nullableFrom = makeNullableType(convertsFrom) ?: continue
                val nullableTo = if (isValidNullableTypeArgument(convertsTo)) makeNullableType(convertsTo) ?: continue else convertsTo
                if (encompassingImplicitConversion(source, nullableFrom) && encompassingImplicitConversion(nullableTo, target)) {
                    u += UserDefinedConversionAnalysis(nullableFrom, nullableTo, from, to)
                }
            }
        }
    }

    /** `IsValidNullableTypeArgument`: a value type that is not `Nullable<T>` itself. */
    private fun isValidNullableTypeArgument(type: SemanticType): Boolean = types.isValueType(type) == true && !types.isNullableType(type)

    /** `CanBeAssignedNull`: a reference type or `Nullable<T>`. */
    private fun canBeAssignedNull(type: SemanticType): Boolean = types.isReferenceType(type) == true || types.isNullableType(type)

    private fun makeNullableType(type: SemanticType): SemanticType? = types.special(NULLABLE)?.let { SemanticType.Library(it.type, listOf(type)) }

    /** `EncompassingImplicitConversion(...).Exists`: every standard implicit conversion encompasses. */
    private fun encompassingImplicitConversion(a: SemanticType, b: SemanticType): Boolean = classifyStandardImplicitConversion(a, b) != null

    private fun isEncompassedBy(a: SemanticType, b: SemanticType): Boolean = encompassingImplicitConversion(a, b)

    private fun mostSpecificSourceTypeForImplicitUserDefinedConversion(u: List<UserDefinedConversionAnalysis>, source: SemanticType): SemanticType? {
        if (u.any { hasIdentityConversion(it.fromType, source) }) return source
        return mostEncompassedType(u) { it.fromType }
    }

    private fun mostSpecificTargetTypeForImplicitUserDefinedConversion(u: List<UserDefinedConversionAnalysis>, target: SemanticType): SemanticType? {
        if (u.any { hasIdentityConversion(it.toType, target) }) return target
        return mostEncompassingType(u) { it.toType }
    }

    private fun liftingCount(conv: UserDefinedConversionAnalysis): Int =
        (if (!hasIdentityConversion(conv.fromType, conv.operatorFrom)) 1 else 0) + (if (!hasIdentityConversion(conv.toType, conv.operatorTo)) 1 else 0)

    private fun mostSpecificConversionOperator(sx: SemanticType, tx: SemanticType, u: List<UserDefinedConversionAnalysis>): Int? {
        val constraint = { conv: UserDefinedConversionAnalysis -> hasIdentityConversion(conv.fromType, sx) && hasIdentityConversion(conv.toType, tx) }
        for (lifting in 0..2) {
            when (val best = uniqueIndex(u) { constraint(it) && liftingCount(it) == lifting }) {
                is BestIndex.Best -> return best.index
                BestIndex.Ambiguous -> return null
                BestIndex.None -> {}
            }
        }
        return null
    }

    private sealed class BestIndex {
        class Best(val index: Int) : BestIndex()
        object Ambiguous : BestIndex()
        object None : BestIndex()
    }

    private fun <T> uniqueIndex(items: List<T>, predicate: (T) -> Boolean): BestIndex {
        var result: Int? = null
        for (i in items.indices) if (predicate(items[i])) {
            if (result == null) result = i else return BestIndex.Ambiguous
        }
        return result?.let { BestIndex.Best(it) } ?: BestIndex.None
    }

    private enum class BetterResult { LEFT, RIGHT, NEITHER, EQUAL }

    private fun <T> mostEncompassedType(items: List<T>, extract: (T) -> SemanticType): SemanticType? {
        val best = uniqueBestValidIndex(items) { left, right ->
            val leftType = extract(left)
            val rightType = extract(right)
            if (hasIdentityConversion(leftType, rightType)) return@uniqueBestValidIndex BetterResult.EQUAL
            val leftWins = isEncompassedBy(leftType, rightType)
            val rightWins = isEncompassedBy(rightType, leftType)
            if (leftWins == rightWins) BetterResult.NEITHER else if (leftWins) BetterResult.LEFT else BetterResult.RIGHT
        }
        return best?.let { extract(items[it]) }
    }

    private fun <T> mostEncompassingType(items: List<T>, extract: (T) -> SemanticType): SemanticType? {
        val best = uniqueBestValidIndex(items) { left, right ->
            val leftType = extract(left)
            val rightType = extract(right)
            if (hasIdentityConversion(leftType, rightType)) return@uniqueBestValidIndex BetterResult.EQUAL
            val leftWins = isEncompassedBy(rightType, leftType)
            val rightWins = isEncompassedBy(leftType, rightType)
            if (leftWins == rightWins) BetterResult.NEITHER else if (leftWins) BetterResult.LEFT else BetterResult.RIGHT
        }
        return best?.let { extract(items[it]) }
    }

    private fun <T> uniqueBestValidIndex(items: List<T>, better: (T, T) -> BetterResult): Int? {
        if (items.isEmpty()) return null
        var candidateIndex: Int? = null
        for (currentIndex in items.indices) {
            val candidate = candidateIndex
            if (candidate == null) {
                candidateIndex = currentIndex
                continue
            }
            when (better(items[candidate], items[currentIndex])) {
                BetterResult.EQUAL, BetterResult.LEFT -> {}
                BetterResult.NEITHER -> candidateIndex = null
                BetterResult.RIGHT -> candidateIndex = currentIndex
            }
        }
        val found = candidateIndex ?: return null
        for (currentIndex in 0 until found) {
            val result = better(items[found], items[currentIndex])
            if (result != BetterResult.LEFT && result != BetterResult.EQUAL) return null
        }
        return found
    }

    companion object {
        private const val OBJECT = RoslynTypes.OBJECT
        private const val STRING = "System.String"
        private const val CHAR = "System.Char"
        private const val ENUM = "System.Enum"
        private const val NULLABLE = "System.Nullable`1"
        private const val DELEGATE = "System.Delegate"
        private const val MULTICAST_DELEGATE = "System.MulticastDelegate"
        private const val ENUMERABLE = "System.Collections.IEnumerable"
        private val POSSIBLE_ARRAY_GENERIC_INTERFACES = setOf(
            "System.Collections.Generic.IList`1", "System.Collections.Generic.ICollection`1", "System.Collections.Generic.IEnumerable`1",
            "System.Collections.Generic.IReadOnlyList`1", "System.Collections.Generic.IReadOnlyCollection`1",
        )
        // ConversionEasyOut: the implicit numeric conversions (`nint` / `nuint` are IntPtr / UIntPtr)
        private val implicitNumeric = io.github.dotnetsupport.lang.semantic.CSharpOverloads.NUMERIC
        private const val MAXIMUM_RECURSION_DEPTH = 50
        private const val MAXIMUM_BASE_CHAIN = 64
    }
}
