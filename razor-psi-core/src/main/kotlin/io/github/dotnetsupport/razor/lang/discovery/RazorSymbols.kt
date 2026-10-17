package io.github.dotnetsupport.razor.lang.discovery

/**
 * The symbols the compiler's tag helper producers read (RAZOR_PLAN.md, R5): what of Roslyn's `INamedTypeSymbol`, `IPropertySymbol`,
 * `AttributeData` and `Compilation` the discovery of components, tag helpers and the directive attributes looks at — a model without
 * Roslyn. The gate fills it from `razordump symbols` (the symbols of a real compilation), the plugin from its assembly index and its own
 * semantics of the project's sources; the producers ([TagHelperDiscovery]) see only this.
 *
 * Types are references ([RType]: a named type with its arguments, a type parameter, an array…) over definitions ([RTypeDefinition]:
 * the declared type with its members, read lazily by the implementations). Where a producer asks the compilation a question of C# —
 * a conversion, an awaitable delegate, the `IDictionary<,>` a type implements — the answer is a property of the member it is asked about.
 */
enum class RTypeKind { Unknown, Array, Class, Delegate, Dynamic, Enum, Error, Interface, Module, Pointer, Struct, Structure, TypeParameter, Submission, FunctionPointer, Extension }

enum class RAccessibility { NotApplicable, Private, ProtectedAndInternal, Protected, Internal, ProtectedOrInternal, Public }

/** A type as a member's signature uses it. */
sealed class RType {
    /** Annotated nullable (`string?`, and every `Nullable<T>` as Roslyn marks it). */
    abstract val nullable: Boolean
}

/** A named type: [definition] with its own [typeArguments]; [containingType] — the constructed outer type of a nested one. */
class RNamed(
    val definition: RTypeDefinition, val typeArguments: List<RType> = emptyList(), val containingType: RNamed? = null,
    val tupleElementNames: List<String>? = null, override val nullable: Boolean = false,
) : RType() {
    /** Roslyn's `IsGenericType`: the type or a type containing it has type parameters. */
    val isGenericType: Boolean get() = definition.isGenericType
    val name: String get() = definition.name
    val typeKind: RTypeKind get() = definition.kind
    val specialType: String? get() = definition.specialType
    val isTuple: Boolean get() = definition.namespace == "System" && definition.name == "ValueTuple" && definition.arity >= 1 && definition.containingType == null &&
        tupleElementNames != null
    override fun toString(): String = RazorTypeDisplay.fullName(this)
}

class RTypeParameterRef(val name: String, val isMethodTypeParameter: Boolean = false, override val nullable: Boolean = false) : RType() {
    override fun toString(): String = name
}

class RArray(val elementType: RType, val rank: Int = 1, override val nullable: Boolean = false) : RType()

class RPointer(val pointedAtType: RType, override val nullable: Boolean = false) : RType()

class RDynamic(override val nullable: Boolean = false) : RType()

/** A type the compilation could not bind (`IErrorTypeSymbol`), or a kind the model does not spell (a function pointer): shown by [name]. */
class RError(val name: String, val typeArguments: List<RType> = emptyList(), val namespace: String? = null, override val nullable: Boolean = false) : RType()

val RType.typeKind: RTypeKind
    get() = when (this) {
        is RNamed -> definition.kind
        is RTypeParameterRef -> RTypeKind.TypeParameter
        is RArray -> RTypeKind.Array
        is RPointer -> RTypeKind.Pointer
        is RDynamic -> RTypeKind.Dynamic
        is RError -> RTypeKind.Error
    }

/** `SpecialType` of a named type (`System_String`, `System_Boolean`, `System_Void`…); null for the others. */
val RType.specialType: String? get() = (this as? RNamed)?.definition?.specialType

/** A declared type: the same object for every reference to it (identity by [key]). */
interface RTypeDefinition {
    /** `assembly|metadata name` (nested types with `+`): the identity of the definition, `SymbolEqualityComparer` on definitions. */
    val key: String
    val assemblyName: String
    val name: String
    val metadataName: String
    /** The full name of the containing namespace; "" for the global one. */
    val namespace: String
    val containingType: RTypeDefinition?
    val kind: RTypeKind
    val accessibility: RAccessibility
    val isAbstract: Boolean
    val isStatic: Boolean
    val specialType: String?
    val arity: Int
    val typeParameters: List<RTypeParameter>
    /** The base class; null for none and for one the compilation could not bind (an error type has no members and no base). */
    val baseType: RNamed?
    /** Every interface, inherited ones too (`AllInterfaces`); the ones the compilation could not bind left out. */
    val allInterfaces: List<RNamed>
    val attributes: List<RAttribute>
    /** `GetDocumentationCommentXml()`: the `<member>` element of the XML documentation; null when there is none. */
    val documentation: String?
    /** Every property declared by the type (not inherited), in declaration order. */
    val properties: List<RProperty>
    /** The methods the producers look for (view components' `Invoke` / `InvokeAsync`, `RenderTreeBuilder.AddNamedEvent`); others may be left out. */
    val methods: List<RMethod>
}

/** `IsGenericType` of the declared type: it or a type containing it has type parameters. */
val RTypeDefinition.isGenericType: Boolean get() = arity > 0 || containingType?.isGenericType == true

/** The definition seen as the type it declares (the type parameters as its arguments). */
fun RTypeDefinition.asType(): RNamed =
    RNamed(this, typeParameters.map { RTypeParameterRef(it.name) }, containingType?.asType())

class RTypeParameter(
    val name: String, val hasReferenceTypeConstraint: Boolean = false, val hasValueTypeConstraint: Boolean = false, val hasUnmanagedTypeConstraint: Boolean = false,
    val hasNotNullConstraint: Boolean = false, val hasConstructorConstraint: Boolean = false, val constraintTypes: List<RType> = emptyList(),
    val attributes: List<RAttribute> = emptyList(),
)

class RAttribute(val attributeClass: RTypeDefinition, val constructorArguments: List<RConstant> = emptyList(), val namedArguments: List<Pair<String, RConstant>> = emptyList())

enum class RConstantKind { Error, Primitive, Enum, Type, Array }

/** `TypedConstant`: [value] is a String, Boolean, Long (integers, chars as their string), Double, an [RType] (a `typeof`), a list of constants (an array) or null. */
class RConstant(val kind: RConstantKind, val type: RType?, val value: Any?) {
    @Suppress("UNCHECKED_CAST")
    val values: List<RConstant> get() = value as? List<RConstant> ?: emptyList()
}

class RProperty(
    val name: String,
    val type: RType,
    val accessibility: RAccessibility,
    val isStatic: Boolean = false,
    val isOverride: Boolean = false,
    /** The property it overrides (`OverriddenProperty`), asked lazily: it lives in a base class. */
    overridden: (() -> RProperty?)? = null,
    val parameterCount: Int = 0,
    /** The accessibility of the getter / the setter; null when there is none. */
    val getter: RAccessibility? = null,
    val setter: RAccessibility? = null,
    val isInitOnly: Boolean = false,
    val attributes: List<RAttribute> = emptyList(),
    val documentation: String? = null,
    /** `CompilationExtensions.CanConvertStringLiteral(type)`: an implicit union conversion from `string`. */
    val acceptsStringLiteral: Boolean = false,
    /** `Dictionary<string, object>` converts to the type by identity or an implicit reference conversion (`CaptureUnmatchedValues`). */
    val dictionaryAssignable: Boolean = false,
    /** The type arguments of `IDictionary<,>` when the type is one or implements one; null when not. */
    val dictionaryArguments: List<RType>? = null,
    /** The type is a delegate whose `Invoke` returns an awaitable (`ComponentTagHelperProducer.IsAwaitable`; of the constructed delegate). */
    val isAwaitableDelegate: Boolean = false,
) {
    private val overriddenLazy = lazy { overridden?.invoke() }
    val overriddenProperty: RProperty? get() = overriddenLazy.value
}

class RMethod(val name: String, val accessibility: RAccessibility, val isStatic: Boolean, val returnsVoid: Boolean, val returnType: RType, val parameters: List<RParameter>)

class RParameter(val name: String, val type: RType, val hasExplicitDefaultValue: Boolean = false, val dictionaryArguments: List<RType>? = null)

/** An assembly of the compilation: [name], the names of the assemblies it references, its types in the order the discovery walks them. */
class RAssembly(val name: String, val referencedAssemblyNames: List<String>, types: () -> List<RTypeDefinition>) {
    /** Every top-level type and the nested types of public types, namespaces depth first (`TagHelperDiscoverer`). */
    val types: List<RTypeDefinition> by lazy(types)
}

/** The compilation the discovery searches: its own assembly first, then the references in their order. */
interface RCompilation {
    val assemblyName: String
    val assemblies: List<RAssembly>
    /** `GetTypeByMetadataName`: the one type of that name (the compilation's own wins; null when none or ambiguous among references). */
    fun typeByMetadataName(metadataName: String): RTypeDefinition?
    /** `GetTypesByMetadataName`: every type of that name. */
    fun typesByMetadataName(metadataName: String): List<RTypeDefinition>
}

/** `SymbolExtensions.HasFullName`: the type's name and the names of its containers end the dotted [fullName] (`<…>` of it ignored). */
fun RTypeDefinition.hasFullName(fullName: String): Boolean {
    if (name.length > fullName.length) return false
    var rest = if (fullName.endsWith('>')) fullName.substring(0, fullName.lastIndexOf('<')) else fullName
    var current: RTypeDefinition? = this
    while (current != null) {
        if (!rest.endsWith(current.name)) return false
        rest = rest.substring(0, rest.length - current.name.length)
        val outer = current.containingType
        if (rest.isEmpty()) return outer == null && current.namespace.isEmpty()
        if (rest.last() != '.') return false
        rest = rest.substring(0, rest.length - 1)
        if (outer == null) return rest == current.namespace
        current = outer
    }
    return false
}

fun RType.hasFullName(fullName: String): Boolean = (this as? RNamed)?.definition?.hasFullName(fullName) == true

fun RAttribute.hasFullName(fullName: String): Boolean = attributeClass.hasFullName(fullName)

fun List<RAttribute>.attribute(fullName: String): RAttribute? = firstOrNull { it.hasFullName(fullName) }
