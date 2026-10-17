package io.github.dotnetsupport.razor.lang.discovery

/**
 * Type parameters replaced by type arguments: the members of a definition seen through a constructed type of it (`MudBaseInput<T>`'s
 * `T Value` from `MudTextField<string>` is a `string`). Keyed by the names of the type parameters of the definition and the types around it.
 */
class RSubstitution(val map: Map<String, RType>) {
    fun apply(type: RType): RType = when (type) {
        is RTypeParameterRef -> if (type.isMethodTypeParameter) type else map[type.name]?.let { withNullable(it, type.nullable) } ?: type
        is RNamed -> RNamed(type.definition, type.typeArguments.map(::apply), type.containingType?.let { apply(it) as RNamed }, type.tupleElementNames, type.nullable)
        is RArray -> RArray(apply(type.elementType), type.rank, type.nullable)
        is RPointer -> RPointer(apply(type.pointedAtType), type.nullable)
        is RError -> RError(type.name, type.typeArguments.map(::apply), type.namespace, type.nullable)
        else -> type
    }

    private fun withNullable(type: RType, nullable: Boolean): RType = if (!nullable || type.nullable) type else when (type) {
        is RNamed -> RNamed(type.definition, type.typeArguments, type.containingType, type.tupleElementNames, true)
        is RTypeParameterRef -> RTypeParameterRef(type.name, type.isMethodTypeParameter, true)
        is RArray -> RArray(type.elementType, type.rank, true)
        else -> type
    }

    fun apply(property: RProperty): RProperty = if (map.isEmpty()) property else RProperty(
        property.name, apply(property.type), property.accessibility, property.isStatic, property.isOverride, { property.overriddenProperty }, property.parameterCount,
        property.getter, property.setter, property.isInitOnly, property.attributes, property.documentation, property.acceptsStringLiteral, property.dictionaryAssignable,
        property.dictionaryArguments?.map(::apply), property.isAwaitableDelegate,
    )

    companion object {
        val NONE = RSubstitution(emptyMap())

        /** The substitution of [type] (a constructed type) seen from where [outer] applies. */
        fun of(type: RNamed, outer: RSubstitution = NONE): RSubstitution {
            val map = HashMap<String, RType>()
            var current: RNamed? = type
            while (current != null) {
                val parameters = current.definition.typeParameters
                for ((i, parameter) in parameters.withIndex()) current.typeArguments.getOrNull(i)?.let { map[parameter.name] = outer.apply(it) }
                current = current.containingType
            }
            return RSubstitution(map)
        }
    }
}

/** Questions about types the producers' answers are computed from, over the model alone. */
object RTypes {
    private const val I_DICTIONARY = "System.Collections.Generic.IDictionary`2"

    /** `AllInterfaces` of [type] with its arguments put in. */
    fun allInterfaces(type: RNamed): List<RNamed> {
        val substitution = RSubstitution.of(type)
        return type.definition.allInterfaces.map { substitution.apply(it) as RNamed }
    }

    /** The type arguments of `IDictionary<,>` when [type] is one or implements one (the first such interface); null when not. */
    fun dictionaryArguments(type: RType): List<RType>? {
        val named = type as? RNamed ?: return null
        if (isDictionaryInterface(named.definition)) return named.typeArguments
        return allInterfaces(named).firstOrNull { isDictionaryInterface(it.definition) }?.typeArguments
    }

    private fun isDictionaryInterface(definition: RTypeDefinition) = definition.namespace == "System.Collections.Generic" && definition.metadataName == "IDictionary`2" &&
        definition.containingType == null
}
