package io.github.dotnetsupport.lang.semantic.roslyn

import io.github.dotnetsupport.csharp.lang.psi.CSharpDelegateDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeDeclaration
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.lang.semantic.classify
import io.github.dotnetsupport.lang.semantic.closed
import io.github.dotnetsupport.lang.semantic.isReference
import io.github.dotnetsupport.lang.semantic.same

/**
 * What Roslyn's binder asks of a `TypeSymbol`, answered over [SemanticType] (CSHARP_PSI_MIGRATION.md, D6a): the facade the ported parts of
 * the binder ([RoslynMethodTypeInferrer], later overload resolution and conversions) are written against, so their code reads as Roslyn's.
 * Roslyn knows every type; we may not (an unresolved base, an assembly not indexed): every answer is null where a part is unknown, never a
 * guess — the ported code turns that into "not known", not into an error.
 */
internal class RoslynTypes(private val r: CSharpNameResolver) {
    /** `TypeKind` of Roslyn, the kinds the binder tells apart. */
    enum class TypeKind { ARRAY, CLASS, STRUCT, INTERFACE, DELEGATE, ENUM, TYPE_PARAMETER }

    /** `VarianceKind` of a type parameter. */
    enum class VarianceKind { NONE, OUT, IN }

    fun typeKind(type: SemanticType): TypeKind? = when (type) {
        is SemanticType.ArrayOf -> TypeKind.ARRAY
        is SemanticType.Parameter -> TypeKind.TYPE_PARAMETER
        is SemanticType.Library -> when (type.type.kind) {
            IndexedTypeKind.CLASS, IndexedTypeKind.STATIC_CLASS -> TypeKind.CLASS
            IndexedTypeKind.STRUCT -> TypeKind.STRUCT
            IndexedTypeKind.INTERFACE -> TypeKind.INTERFACE
            IndexedTypeKind.ENUM -> TypeKind.ENUM
            IndexedTypeKind.DELEGATE -> TypeKind.DELEGATE
        }
        is SemanticType.Source -> when (type.info.kind) {
            io.github.dotnetsupport.lang.TypeKind.CLASS, io.github.dotnetsupport.lang.TypeKind.STATIC_CLASS, io.github.dotnetsupport.lang.TypeKind.RECORD -> TypeKind.CLASS
            io.github.dotnetsupport.lang.TypeKind.STRUCT, io.github.dotnetsupport.lang.TypeKind.RECORD_STRUCT -> TypeKind.STRUCT
            io.github.dotnetsupport.lang.TypeKind.INTERFACE -> TypeKind.INTERFACE
            io.github.dotnetsupport.lang.TypeKind.ENUM -> TypeKind.ENUM
            io.github.dotnetsupport.lang.TypeKind.DELEGATE -> TypeKind.DELEGATE
            null -> null
        }
    }

    /** `IsReferenceType`: a type parameter by `class` or a class among its constraints; null for one whose constraints do not say. */
    fun isReferenceType(type: SemanticType): Boolean? = when (type) {
        is SemanticType.Parameter -> when {
            r.isValueType(type) -> false
            r.hasClassConstraint(type) -> true
            // `ValueType` and `Enum` are classes a struct or an enum satisfies: they say nothing
            r.constraintsOf(type).any { isClassType(it) && definitionName(it) != VALUE_TYPE && definitionName(it) != ENUM } -> true
            else -> null
        }
        else -> r.overloads.isReference(type)
    }

    /** `IsValueType`; null for a type parameter whose constraints do not say. */
    fun isValueType(type: SemanticType): Boolean? = when (type) {
        is SemanticType.Parameter -> if (r.isValueType(type)) true else if (r.hasClassConstraint(type)) false else null
        else -> isReferenceType(type)?.not()
    }

    fun isInterfaceType(type: SemanticType): Boolean = typeKind(type) == TypeKind.INTERFACE

    fun isClassType(type: SemanticType): Boolean = typeKind(type) == TypeKind.CLASS

    fun isDelegateType(type: SemanticType): Boolean = typeKind(type) == TypeKind.DELEGATE

    // ---- arrays, nullable, spans, tuples

    fun isArray(type: SemanticType): Boolean = type is SemanticType.ArrayOf

    /** `ArrayTypeSymbol.ElementType`. */
    fun arrayElementType(type: SemanticType): SemanticType? = (type as? SemanticType.ArrayOf)?.element

    fun arrayRank(type: SemanticType): Int = (type as? SemanticType.ArrayOf)?.rank ?: 0

    /** `IsSZArray`: a one-dimensional array. */
    fun isSZArray(type: SemanticType): Boolean = type is SemanticType.ArrayOf && type.rank == 1

    /** `IsNullableType()`: `Nullable<T>`. */
    fun isNullableType(type: SemanticType): Boolean = r.isNullable(type)

    /** `GetNullableUnderlyingType()`. */
    fun nullableUnderlyingType(type: SemanticType): SemanticType? = if (isNullableType(type)) (type as SemanticType.Library).arguments.singleOrNull() else null

    /** `Span<T>` / `ReadOnlySpan<T>` of the binder's span inference; the element, or null for any other type. */
    fun spanElementType(type: SemanticType): SemanticType? =
        if (definitionName(type) in SPANS) (type as? SemanticType.Library)?.arguments?.singleOrNull() else null

    fun isReadOnlySpan(type: SemanticType): Boolean = definitionName(type) == READ_ONLY_SPAN

    /** `IsTupleType`. */
    fun isTupleType(type: SemanticType): Boolean = definitionName(type)?.startsWith(VALUE_TUPLE) == true

    /** `TupleElementTypesWithAnnotations`: the elements of a `ValueTuple`, the eighth argument (`TRest`) unfolded; null for no tuple. */
    fun tupleElementTypes(type: SemanticType): List<SemanticType?>? {
        if (!isTupleType(type)) return null
        val arguments = (type as? SemanticType.Library)?.arguments ?: return null
        if (arguments.size < 8) return arguments
        val rest = arguments[7]?.let(::tupleElementTypes) ?: return null
        return arguments.take(7) + rest
    }

    /** `TryGetCollectionIterationType` of a target type: what `foreach` over it gives (arrays, spans, `IEnumerable<T>`, the pattern); null when not known. */
    fun collectionIterationType(type: SemanticType): SemanticType? = r.elementType(type)

    // ---- named types: definition, arguments, variance

    /** The definition a constructed type comes from (`OriginalDefinition`), as a name: `System.Collections.Generic.List\`1`. */
    fun definitionName(type: SemanticType): String? = r.definitionName(type)

    /** `TypeArgumentsWithAnnotations` of a named type (its own; a nested type's outer ones are in [outerType]). */
    fun typeArguments(type: SemanticType): List<SemanticType?> = when (type) {
        is SemanticType.Library -> type.arguments
        is SemanticType.Source -> type.arguments
        else -> emptyList()
    }

    /** `ContainingType` of a type nested in a generic one, with its arguments (`Outer<int>` of `Outer<int>.Inner`). */
    fun outerType(type: SemanticType): SemanticType? = (type as? SemanticType.Source)?.outer

    /** `TypeParameters[i].Variance` of the definition of [type]; null when not known. */
    fun variance(type: SemanticType, index: Int): VarianceKind? = when (type) {
        is SemanticType.Library -> type.type.typeParameters.getOrNull(index)?.let {
            if (it.isCovariant) VarianceKind.OUT else if (it.isContravariant) VarianceKind.IN else VarianceKind.NONE
        }
        is SemanticType.Source -> {
            val parameters = type.info.parts.firstNotNullOfOrNull { part ->
                when (val element = part.element()) {
                    is CSharpTypeDeclaration -> element.typeParameterList?.parameters
                    is CSharpDelegateDeclaration -> element.typeParameterList?.parameters
                    else -> null
                }
            }
            parameters?.getOrNull(index)?.let {
                when (it.varianceKeyword?.text) { "out" -> VarianceKind.OUT; "in" -> VarianceKind.IN; else -> VarianceKind.NONE }
            }
        }
        else -> null
    }

    /** Two types are one (`Equals` ignoring tuple names and nullability): a built project is its sources; null when not known. */
    fun same(a: SemanticType?, b: SemanticType?): Boolean? = r.overloads.same(a, b)

    // ---- supertypes

    /**
     * `BaseTypeNoUseSiteDiagnostics`, substituted: a class's base class (`object` at the end), a struct's `ValueType`, an enum's `Enum`,
     * a delegate's `MulticastDelegate`; none for an interface; the effective base class of a type parameter (its class constraint, else
     * `ValueType` for `struct`, else `object`). Null where it is not known.
     */
    fun baseType(type: SemanticType): SemanticType? = when (type) {
        is SemanticType.Library -> {
            if (type.type.kind == IndexedTypeKind.INTERFACE) null
            else r.session.baseTypes(r.assemblies, type.type).firstOrNull()?.let { base ->
                SemanticType.Library(base.type, base.arguments.map { r.fromRef(it, type.arguments) })
            }
        }
        is SemanticType.Source -> if (isInterfaceType(type)) null else r.baseTypes(type).firstOrNull(r::isBaseClass)
        is SemanticType.ArrayOf -> r.libraryType(ARRAY)
        is SemanticType.Parameter -> r.constraintsOf(type).firstOrNull { isClassType(it) }
            ?: r.libraryType(if (r.isValueType(type)) VALUE_TYPE else OBJECT)
    }

    /**
     * `AllInterfacesNoUseSiteDiagnostics`, substituted, each once: the interfaces of [type], of its bases and of those interfaces; of a type
     * parameter, those of its constraints (`AllEffectiveInterfaces`); of an array, those of `T[]` (`IList<T>` and its own).
     */
    fun allInterfaces(type: SemanticType, depth: Int = 0): List<SemanticType> {
        if (depth > MAX_DEPTH) return emptyList()
        val found = ArrayList<SemanticType>()
        fun add(t: SemanticType) {
            if (found.none { same(it, t) == true }) found += t
        }
        when (type) {
            is SemanticType.Library -> for (supertype in r.session.interfaces(r.assemblies, type.type)) {
                add(SemanticType.Library(supertype.type, supertype.arguments.map { r.fromRef(it, type.arguments) }))
            }
            is SemanticType.Source -> for (base in r.baseTypes(type)) {
                if (isInterfaceType(base)) add(base)
                allInterfaces(base, depth + 1).forEach(::add)
            }
            is SemanticType.ArrayOf -> for (name in ARRAY_INTERFACES) {
                r.libraryType(name)?.let { generic -> add(SemanticType.Library(generic.type, listOf(type.element))) }
            }
            is SemanticType.Parameter -> for (constraint in r.constraintsOf(type)) {
                if (isInterfaceType(constraint)) add(constraint)
                allInterfaces(constraint, depth + 1).forEach(::add)
            }
        }
        return found
    }

    /** The type constraints of a type parameter (`ConstraintTypes`), as resolved; may miss one that does not resolve. */
    fun constraintTypes(type: SemanticType.Parameter): List<SemanticType> = r.constraintsOf(type)

    /** Whether every base type and interface of [type] resolves, all the way up: what it does not derive from, it surely does not. */
    fun isClosed(type: SemanticType): Boolean = r.overloads.closed(type)

    // ---- delegates and conversions

    /** `DelegateInvokeMethod`: the parameter types and the return type of a delegate type, substituted; null for any other type. */
    fun delegateInvoke(type: SemanticType): Pair<List<SemanticType?>, SemanticType?>? = r.expressions.delegateSignature(type)

    /** `Expression<TDelegate>` to `TDelegate` (`GetDelegateType` of a lambda's target), else [type] itself. */
    fun delegateOfExpression(type: SemanticType): SemanticType? = r.expressions.unwrapExpression(type)

    fun isVoid(type: SemanticType?): Boolean = type != null && definitionName(type) == VOID

    /** `ClassifyImplicitConversionFromType(...).Exists`; null when not known. */
    fun implicitConversionExists(from: SemanticType, to: SemanticType): Boolean? = when (r.overloads.classify(from, to)) {
        CSharpNameResolver.Conversion.IDENTITY, CSharpNameResolver.Conversion.IMPLICIT -> true
        CSharpNameResolver.Conversion.NONE -> false
        CSharpNameResolver.Conversion.UNKNOWN -> null
    }

    /** A special type by its metadata name (`System.Object`, `System.Threading.Tasks.Task\`1`); null when no assembly has it. */
    fun special(fullName: String): SemanticType.Library? = r.libraryType(fullName)

    /** [type] with the type parameters [map] gives replaced (`Substitute` of a `TypeMap`). */
    fun substitute(type: SemanticType?, map: (SemanticType.Parameter) -> SemanticType?): SemanticType? = r.replace(type, map)

    companion object {
        const val OBJECT = "System.Object"
        const val VALUE_TYPE = "System.ValueType"
        const val ENUM = "System.Enum"
        const val ARRAY = "System.Array"
        const val VOID = "System.Void"
        const val VALUE_TUPLE = "System.ValueTuple`"
        const val READ_ONLY_SPAN = "System.ReadOnlySpan`1"
        private val SPANS = setOf("System.Span`1", READ_ONLY_SPAN)
        private val ARRAY_INTERFACES = listOf(
            "System.Collections.Generic.IList`1", "System.Collections.Generic.ICollection`1", "System.Collections.Generic.IEnumerable`1",
            "System.Collections.Generic.IReadOnlyList`1", "System.Collections.Generic.IReadOnlyCollection`1",
        )
        private const val MAX_DEPTH = 16
    }
}
