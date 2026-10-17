package io.github.dotnetsupport.frameworks.razor

import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.index.IndexedAccess
import io.github.dotnetsupport.index.IndexedAttribute
import io.github.dotnetsupport.index.IndexedConstant
import io.github.dotnetsupport.index.IndexedMember
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.razor.lang.discovery.RAccessibility
import io.github.dotnetsupport.razor.lang.discovery.RArray
import io.github.dotnetsupport.razor.lang.discovery.RAssembly
import io.github.dotnetsupport.razor.lang.discovery.RAttribute
import io.github.dotnetsupport.razor.lang.discovery.RCompilation
import io.github.dotnetsupport.razor.lang.discovery.RConstant
import io.github.dotnetsupport.razor.lang.discovery.RConstantKind
import io.github.dotnetsupport.razor.lang.discovery.RError
import io.github.dotnetsupport.razor.lang.discovery.RMethod
import io.github.dotnetsupport.razor.lang.discovery.RNamed
import io.github.dotnetsupport.razor.lang.discovery.RParameter
import io.github.dotnetsupport.razor.lang.discovery.RPointer
import io.github.dotnetsupport.razor.lang.discovery.RProperty
import io.github.dotnetsupport.razor.lang.discovery.RType
import io.github.dotnetsupport.razor.lang.discovery.RTypeDefinition
import io.github.dotnetsupport.razor.lang.discovery.RTypeKind
import io.github.dotnetsupport.razor.lang.discovery.RTypeParameter
import io.github.dotnetsupport.razor.lang.discovery.RTypeParameterRef
import io.github.dotnetsupport.razor.lang.discovery.RSubstitution
import io.github.dotnetsupport.razor.lang.discovery.RazorTypeDisplay
import java.util.concurrent.ConcurrentHashMap

/**
 * The symbols of referenced assemblies for the tag helper discovery (RAZOR_PLAN.md, R5b), read from the plugin's assembly index: the
 * compilation the producers of razor-psi search ([RCompilation]) over an [AssemblyIndexSet] — the types of each assembly in the order the
 * compiler walks them (namespaces depth first in ordinal order, the types of a namespace in the order of the metadata, the nested types of
 * public types after their type), their properties, attributes with their arguments (format 7), the type references converted to the model.
 * The questions a producer asks the compilation are answered from the index: `Dictionary<string, object>` assigned to a type (its base
 * classes and interfaces), the `IDictionary<,>` a type implements, an awaitable delegate (`GetAwaiter` of its `Invoke`'s result). The
 * documentation is not read: the compiler has none for metadata references either.
 *
 * [own] — the assembly being compiled, first (its types come from the sources: R5c); null for none.
 */
class IndexRazorCompilation(override val assemblyName: String, private val set: AssemblyIndexSet, own: RAssembly? = null) : RCompilation {
    private val definitions = ConcurrentHashMap<String, RTypeDefinition>()

    override val assemblies: List<RAssembly> = listOfNotNull(own) + set.indexes.map { index ->
        RAssembly(index.assemblyName, index.assemblyReferences) { walk(index).map(::definition) }
    }

    override fun typeByMetadataName(metadataName: String): RTypeDefinition? {
        val found = typesByMetadataName(metadataName)
        // the compilation's own type wins; among the references only one may have it
        return found.singleOrNull()
    }

    override fun typesByMetadataName(metadataName: String): List<RTypeDefinition> =
        set.indexes.mapNotNull { it.findType(metadataName)?.let(::definition) }

    fun definition(type: IndexedType): RTypeDefinition = definitions.getOrPut(key(type.index, type.fullName)) { IndexDefinition(type) }

    private fun key(index: AssemblyIndex, fullName: String) = index.assemblyName + "|" + fullName

    private fun resolve(fullName: String): IndexedType? = set.findType(fullName)

    /** A type of an attribute or a signature the index set does not have: a definition of its name alone, as Roslyn's error type. */
    private fun missing(fullName: String): RTypeDefinition = definitions.getOrPut("?|$fullName") { MissingDefinition(fullName) }

    /** The types of [index] as `TagHelperDiscoverer` walks an assembly. */
    private fun walk(index: AssemblyIndex): List<IndexedType> {
        val all = List(index.allTypeCount) { index.type(it) }
        val topLevel = all.filter { it.declaringType == null }.groupBy { it.namespace }
        val nested = all.filter { it.declaringType != null }.groupBy { it.declaringType!!.row }
        val result = ArrayList<IndexedType>()
        fun visitType(type: IndexedType) {
            result += type
            if (accessibility(type) == RAccessibility.Public) nested[type.row]?.sortedBy { it.metadataRow }?.forEach(::visitType)
        }
        // the namespace tree: a namespace's types first, then its namespaces by name
        val children = HashMap<String, MutableSet<String>>()
        for (namespace in topLevel.keys) {
            var current = namespace
            while (current.isNotEmpty()) {
                val parent = current.substringBeforeLast('.', "")
                children.getOrPut(parent) { sortedSetOf() } += current
                current = parent
            }
        }
        fun visitNamespace(namespace: String) {
            topLevel[namespace]?.sortedBy { it.metadataRow }?.forEach(::visitType)
            children[namespace]?.sortedBy { it.substringAfterLast('.') }?.forEach(::visitNamespace)
        }
        visitNamespace("")
        return result
    }

    private fun accessibility(type: IndexedType): RAccessibility = when {
        type.isPrivate -> RAccessibility.Private
        type.isInternal && type.isProtected -> RAccessibility.ProtectedAndInternal
        type.isInternal -> RAccessibility.Internal
        type.isProtected -> RAccessibility.Protected
        else -> RAccessibility.Public
    }

    private fun accessibility(member: IndexedMember): RAccessibility = when {
        member.isPrivateProtected -> RAccessibility.ProtectedAndInternal
        member.isProtectedInternal -> RAccessibility.ProtectedOrInternal
        member.isProtected -> RAccessibility.Protected
        member.isInternal -> RAccessibility.Internal
        else -> RAccessibility.Public
    }

    private fun accessibility(access: IndexedAccess): RAccessibility? = when (access) {
        IndexedAccess.NONE -> null
        IndexedAccess.PUBLIC -> RAccessibility.Public
        IndexedAccess.PROTECTED -> RAccessibility.Protected
        IndexedAccess.INTERNAL -> RAccessibility.Internal
        IndexedAccess.PROTECTED_INTERNAL -> RAccessibility.ProtectedOrInternal
        IndexedAccess.PRIVATE_PROTECTED -> RAccessibility.ProtectedAndInternal
    }

    // ---- type references

    /** [reference] in the model; [typeParameters] — the names of the type parameters of the type (outer ones first), [methodParameters] — of the method. */
    fun type(reference: IndexedTypeRef, typeParameters: List<String>, methodParameters: List<String> = emptyList()): RType = when (reference) {
        is IndexedTypeRef.Named -> named(reference.fullName, emptyList(), null, reference.annotated, typeParameters, methodParameters)
        is IndexedTypeRef.Generic -> named(reference.definition.fullName, reference.arguments, reference.tupleNames, reference.annotated, typeParameters, methodParameters)
        is IndexedTypeRef.TypeParameter -> RTypeParameterRef(
            (if (reference.ofMethod) methodParameters else typeParameters).getOrElse(reference.index) { "T${reference.index}" }, reference.ofMethod, reference.annotated,
        )
        is IndexedTypeRef.ArrayOf -> RArray(type(reference.element, typeParameters, methodParameters), reference.rank, reference.annotated)
        is IndexedTypeRef.PointerTo -> RPointer(type(reference.element, typeParameters, methodParameters))
        is IndexedTypeRef.ByRef -> type(reference.element, typeParameters, methodParameters)
        is IndexedTypeRef.FunctionPointer -> RError("delegate*")
    }

    private fun named(
        fullName: String, arguments: List<IndexedTypeRef>, tupleNames: List<String>?, annotated: Boolean, typeParameters: List<String>, methodParameters: List<String>,
    ): RType {
        val converted = arguments.map { type(it, typeParameters, methodParameters) }
        val resolved = resolve(fullName)
        if (resolved == null) {
            // Roslyn's type of a missing assembly: its name without the arity, its arguments, its namespace
            val top = fullName.substringBefore('+')
            val namespace = top.substringBeforeLast('.', "").takeIf { it.isNotEmpty() && !fullName.contains('+') }
            val simple = fullName.substringAfterLast('+').substringAfterLast('.').substringBefore('`')
            return RError(simple, converted, namespace, annotated)
        }
        return named(resolved, converted, tupleNames, annotated)
    }

    /** A type of the index with all its type arguments (the ones of the types around a nested type first, as the index and the semantics write them). */
    fun named(resolved: IndexedType, converted: List<RType>, tupleNames: List<String>?, annotated: Boolean): RNamed {
        val definition = definition(resolved)
        val own = definition.arity
        val outerArguments = converted.dropLast(own.coerceAtMost(converted.size))
        val ownArguments = converted.takeLast(own.coerceAtMost(converted.size))
        val outer = resolved.declaringType?.let { outerType ->
            val outerDefinition = definition(outerType)
            outerOf(outerDefinition, outerArguments)
        }
        val tuple = definition.namespace == "System" && definition.name == "ValueTuple" && own > 0 && resolved.declaringType == null
        val names = if (tuple) tupleElementNames(converted, tupleNames) else null
        // Roslyn marks every Nullable<T> annotated
        val nullable = annotated || definition.specialType == "System_Nullable_T"
        return RNamed(definition, ownArguments, outer, names, nullable)
    }

    private fun outerOf(definition: RTypeDefinition, arguments: List<RType>): RNamed {
        val own = definition.arity
        val outer = definition.containingType?.let { outerOf(it, arguments.dropLast(own.coerceAtMost(arguments.size))) }
        return RNamed(definition, arguments.takeLast(own.coerceAtMost(arguments.size)), outer)
    }

    /** The element names of a tuple (all of them, the rest of a long one too): ItemN where it has none. */
    private fun tupleElementNames(arguments: List<RType>, names: List<String>?): List<String> {
        val count = tupleSize(arguments)
        return List(count) { i -> names?.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "Item${i + 1}" }
    }

    private fun tupleSize(arguments: List<RType>): Int {
        val rest = arguments.getOrNull(7) as? RNamed
        return if (arguments.size == 8 && rest != null && rest.definition.name == "ValueTuple") 7 + tupleSize(rest.typeArguments) else arguments.size
    }

    private fun constant(constant: IndexedConstant, typeParameters: List<String>): RConstant = when (constant.kind) {
        IndexedConstant.Kind.PRIMITIVE -> RConstant(RConstantKind.Primitive, null, constant.value)
        IndexedConstant.Kind.ENUM -> RConstant(RConstantKind.Enum, constant.enumType?.let { type(it, typeParameters) }, constant.value)
        IndexedConstant.Kind.TYPE -> RConstant(RConstantKind.Type, null, (constant.value as IndexedTypeRef?)?.let { type(it, typeParameters) })
        IndexedConstant.Kind.ARRAY -> RConstant(RConstantKind.Array, null, (constant.value as List<*>?)?.map { constant(it as IndexedConstant, typeParameters) })
    }

    private fun attributes(data: List<IndexedAttribute>, typeParameters: List<String>): List<RAttribute> = data.map { attribute ->
        RAttribute(
            resolve(attribute.typeName)?.let(::definition) ?: missing(attribute.typeName),
            attribute.arguments.map { constant(it, typeParameters) },
            attribute.namedArguments.map { (name, value) -> name to constant(value, typeParameters) },
        )
    }

    // ---- what the producers ask the compilation

    /** `Dictionary<string, object>` converts to [type] by identity or an implicit reference conversion (by the spelling of its full name). */
    fun acceptsStringObjectDictionary(type: RType): Boolean = RazorTypeDisplay.fullName(type) in stringObjectDictionaryNames

    private val stringObjectDictionaryNames: Set<String> by lazy {
        val dictionary = resolve("System.Collections.Generic.Dictionary`2") ?: return@lazy emptySet()
        val arguments = listOf(IndexedTypeRef.Named("System.String", false), IndexedTypeRef.Named("System.Object", false))
        val self = IndexedTypeRef.Generic(IndexedTypeRef.Named(dictionary.fullName, false), arguments)
        val supertypes = set.baseTypes(dictionary).map { it.reference } + set.interfaces(dictionary).map { it.reference.substitute(arguments) }
        (listOf(self) + supertypes).mapTo(HashSet()) { RazorTypeDisplay.fullName(type(it, emptyList())) }
    }

    /** [type] is a delegate whose `Invoke` returns an awaitable (`ComponentTagHelperProducer.IsAwaitable`), over the model: a delegate of the sources too. */
    fun isAwaitableDelegate(type: RType): Boolean {
        val named = type as? RNamed ?: return false
        if (named.definition.kind != RTypeKind.Delegate) return false
        val invoke = named.definition.methods.firstOrNull { it.name == "Invoke" } ?: return false
        if (invoke.returnsVoid) return false
        val returns = RSubstitution.of(named).apply(invoke.returnType) as? RNamed ?: return false
        val indexed = (returns.definition as? IndexDefinition)?.indexed ?: return false
        return isAwaitable(indexed)
    }

    /** A `GetAwaiter()` whose result has `IsCompleted`, `OnCompleted(delegate)` and `GetResult()`. */
    private fun isAwaitable(returned: IndexedType): Boolean = set.members(returned).map { it.member }.any { getAwaiter ->
        if (getAwaiter.name != "GetAwaiter" || !getAwaiter.kind.isCallable) return@any false
        val awaiterRef = getAwaiter.typeRef
        val awaiter = resolve((awaiterRef as? IndexedTypeRef.Named)?.fullName ?: (awaiterRef as? IndexedTypeRef.Generic)?.definition?.fullName ?: return@any false) ?: return@any false
        val members = set.members(awaiter).map { it.member }
        members.any { it.name == "IsCompleted" && it.kind == IndexedMemberKind.PROPERTY && (it.typeRef as? IndexedTypeRef.Named)?.fullName == "System.Boolean" && it.hasGetter } &&
            members.any { it.name == "OnCompleted" && it.kind.isCallable && (it.typeRef as? IndexedTypeRef.Named)?.fullName == "System.Void" && it.parameters.size == 1 &&
                resolve(it.parameters[0].typeRef.let { p -> (p as? IndexedTypeRef.Named)?.fullName ?: (p as? IndexedTypeRef.Generic)?.definition?.fullName ?: "" })?.kind == IndexedTypeKind.DELEGATE } &&
            members.any { it.name == "GetResult" && it.kind.isCallable && it.parameters.isEmpty() }
    }

    /** The types `Dictionary<string, object>` converts to by identity or an implicit reference conversion (`CaptureUnmatchedValues`). */
    private val stringObjectDictionaryTargets: Set<String> by lazy {
        val dictionary = resolve("System.Collections.Generic.Dictionary`2") ?: return@lazy emptySet()
        val arguments = listOf(IndexedTypeRef.Named("System.String", false), IndexedTypeRef.Named("System.Object", false))
        val self = IndexedTypeRef.Generic(IndexedTypeRef.Named(dictionary.fullName, false), arguments)
        val supertypes = set.baseTypes(dictionary).map { it.reference } + set.interfaces(dictionary).map { it.reference.substitute(arguments) }
        (listOf(self) + supertypes).mapTo(HashSet()) { plain(it) }
    }

    /** A reference without its nullable annotations and tuple names: what a conversion between types looks at. */
    private fun plain(reference: IndexedTypeRef): String = when (reference) {
        is IndexedTypeRef.Named -> reference.fullName
        is IndexedTypeRef.Generic -> reference.definition.fullName + reference.arguments.joinToString(",", "<", ">") { plain(it) }
        is IndexedTypeRef.ArrayOf -> plain(reference.element) + "[" + ",".repeat(reference.rank - 1) + "]"
        else -> reference.toString()
    }

    private val dictionaryArgumentsCache = ConcurrentHashMap<String, List<IndexedTypeRef>>()

    /** The type arguments of `IDictionary<,>` when [reference] is one or implements one; null when not. */
    private fun dictionaryArguments(reference: IndexedTypeRef): List<IndexedTypeRef>? {
        val result = dictionaryArgumentsCache.getOrPut(plain(reference)) {
            val generic = reference as? IndexedTypeRef.Generic
            if (generic?.definition?.fullName == I_DICTIONARY) return@getOrPut generic.arguments
            val resolved = (reference as? IndexedTypeRef.Named)?.fullName?.let(::resolve) ?: generic?.definition?.fullName?.let(::resolve) ?: return@getOrPut emptyList()
            val found = set.interfaces(resolved).firstOrNull { it.type.fullName == I_DICTIONARY } ?: return@getOrPut emptyList()
            (found.reference as? IndexedTypeRef.Generic)?.arguments?.map { it.substitute(generic?.arguments.orEmpty()) }.orEmpty()
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private val awaitableCache = ConcurrentHashMap<String, Boolean>()

    /** A delegate whose `Invoke` returns an awaitable: a `GetAwaiter()` whose result has `IsCompleted`, `OnCompleted(delegate)` and `GetResult()`. */
    private fun isAwaitableDelegate(reference: IndexedTypeRef): Boolean = awaitableCache.getOrPut(plain(reference)) {
        val generic = reference as? IndexedTypeRef.Generic
        val delegate = resolve((reference as? IndexedTypeRef.Named)?.fullName ?: generic?.definition?.fullName ?: return@getOrPut false) ?: return@getOrPut false
        if (delegate.kind != IndexedTypeKind.DELEGATE) return@getOrPut false
        val invoke = delegate.members.firstOrNull { it.name == "Invoke" && it.kind == IndexedMemberKind.METHOD } ?: return@getOrPut false
        val returns = invoke.typeRef.substitute(generic?.arguments.orEmpty())
        if ((returns as? IndexedTypeRef.Named)?.fullName == "System.Void") return@getOrPut false
        val returned = resolve((returns as? IndexedTypeRef.Named)?.fullName ?: (returns as? IndexedTypeRef.Generic)?.definition?.fullName ?: return@getOrPut false) ?: return@getOrPut false
        set.members(returned).map { it.member }.any { getAwaiter ->
            if (getAwaiter.name != "GetAwaiter" || !getAwaiter.kind.isCallable) return@any false
            val awaiterRef = getAwaiter.typeRef
            val awaiter = resolve((awaiterRef as? IndexedTypeRef.Named)?.fullName ?: (awaiterRef as? IndexedTypeRef.Generic)?.definition?.fullName ?: return@any false) ?: return@any false
            val members = set.members(awaiter).map { it.member }
            members.any { it.name == "IsCompleted" && it.kind == IndexedMemberKind.PROPERTY && (it.typeRef as? IndexedTypeRef.Named)?.fullName == "System.Boolean" && it.hasGetter } &&
                members.any { it.name == "OnCompleted" && it.kind.isCallable && (it.typeRef as? IndexedTypeRef.Named)?.fullName == "System.Void" && it.parameters.size == 1 &&
                    resolve(it.parameters[0].typeRef.let { p -> (p as? IndexedTypeRef.Named)?.fullName ?: (p as? IndexedTypeRef.Generic)?.definition?.fullName ?: "" })?.kind == IndexedTypeKind.DELEGATE } &&
                members.any { it.name == "GetResult" && it.kind.isCallable && it.parameters.isEmpty() }
        }
    }

    // ---- definitions

    private inner class IndexDefinition(private val type: IndexedType) : RTypeDefinition {
        val indexed: IndexedType get() = type
        override val key: String = key(type.index, type.fullName)
        override val assemblyName: String get() = type.index.assemblyName
        override val name: String get() = type.simpleName
        override val metadataName: String get() = type.path.substringAfterLast('+')
        override val namespace: String get() = type.namespace
        override val containingType: RTypeDefinition? by lazy { type.declaringType?.let(::definition) }
        override val kind: RTypeKind
            get() = when (type.kind) {
                IndexedTypeKind.CLASS, IndexedTypeKind.STATIC_CLASS -> RTypeKind.Class
                IndexedTypeKind.STRUCT -> RTypeKind.Struct
                IndexedTypeKind.INTERFACE -> RTypeKind.Interface
                IndexedTypeKind.ENUM -> RTypeKind.Enum
                IndexedTypeKind.DELEGATE -> RTypeKind.Delegate
            }
        override val accessibility: RAccessibility get() = accessibility(type)
        // Roslyn: an interface is abstract, a static class is abstract and sealed
        override val isAbstract: Boolean get() = type.isAbstract || type.kind == IndexedTypeKind.INTERFACE
        override val isStatic: Boolean get() = type.isStatic
        override val specialType: String? get() = SPECIAL_TYPES[type.fullName]
        override val arity: Int get() = type.ownArity
        private val allTypeParameterNames: List<String> by lazy { type.typeParameters.map { it.name } }
        override val typeParameters: List<RTypeParameter> by lazy {
            type.typeParameters.takeLast(type.ownArity).map { p ->
                RTypeParameter(
                    p.name, p.isClass, p.isStruct, p.isUnmanaged, p.isNotNull, p.hasNew, p.constraints.map { type(it, allTypeParameterNames) },
                    attributes(p.attributes, allTypeParameterNames),
                )
            }
        }
        override val baseType: RNamed? by lazy {
            val reference = type.baseType ?: when (type.kind) {
                IndexedTypeKind.STRUCT -> IndexedTypeRef.Named("System.ValueType", false)
                IndexedTypeKind.ENUM -> IndexedTypeRef.Named("System.Enum", false)
                IndexedTypeKind.DELEGATE -> IndexedTypeRef.Named("System.MulticastDelegate", false)
                else -> null
            }
            reference?.let { type(it, allTypeParameterNames) as? RNamed }
        }
        override val allInterfaces: List<RNamed> by lazy { set.interfaces(type).mapNotNull { type(it.reference, allTypeParameterNames) as? RNamed } }
        override val attributes: List<RAttribute> by lazy { attributes(type.attributeData, allTypeParameterNames) }
        override val documentation: String? get() = null
        override val properties: List<RProperty> by lazy {
            set.ownMembers(type).filter { it.kind == IndexedMemberKind.PROPERTY || it.kind == IndexedMemberKind.INDEXER }.map(::property)
        }
        override val methods: List<RMethod> by lazy {
            set.ownMembers(type).filter { it.kind == IndexedMemberKind.METHOD }.map { method ->
                val methodParameters = method.typeParameters.map { it.name }
                RMethod(
                    method.name, accessibility(method), method.isStatic, (method.typeRef as? IndexedTypeRef.Named)?.fullName == "System.Void",
                    type(method.typeRef, allTypeParameterNames, methodParameters),
                    method.parameters.map { p ->
                        RParameter(p.name, type(p.typeRef, allTypeParameterNames, methodParameters), p.hasDefault, dictionaryArguments(p.typeRef)?.map { type(it, allTypeParameterNames, methodParameters) })
                    },
                )
            }
        }

        private fun property(member: IndexedMember): RProperty {
            val reference = member.typeRef
            return RProperty(
                member.name, type(reference, allTypeParameterNames), accessibility(member), member.isStatic, member.isOverride,
                if (member.isOverride) ({ overridden(member.name) }) else null, if (member.kind == IndexedMemberKind.INDEXER) member.parameters.size else 0,
                accessibility(member.getterAccess), accessibility(member.setterAccess), member.isInitOnly, attributes(member.attributeData, allTypeParameterNames), null,
                acceptsStringLiteral = false, dictionaryAssignable = plain(reference) in stringObjectDictionaryTargets,
                dictionaryArguments = dictionaryArguments(reference)?.map { type(it, allTypeParameterNames) }, isAwaitableDelegate = isAwaitableDelegate(reference),
            )
        }

        /** `OverriddenProperty`: the property of that name in the nearest base class that has one. */
        private fun overridden(name: String): RProperty? = set.baseTypes(type).firstNotNullOfOrNull { base -> definition(base.type).properties.firstOrNull { it.name == name } }

        override fun equals(other: Any?): Boolean = other is RTypeDefinition && other.key == key
        override fun hashCode(): Int = key.hashCode()
        override fun toString(): String = key
    }

    private class MissingDefinition(private val fullName: String) : RTypeDefinition {
        override val key: String = "?|$fullName"
        override val assemblyName: String get() = "?"
        override val name: String get() = fullName.substringAfterLast('+').substringAfterLast('.').substringBefore('`')
        override val metadataName: String get() = fullName.substringAfterLast('+').substringAfterLast('.')
        override val namespace: String get() = if (fullName.contains('+')) "" else fullName.substringBeforeLast('.', "")
        override val containingType: RTypeDefinition? get() = null
        override val kind: RTypeKind get() = RTypeKind.Error
        override val accessibility: RAccessibility get() = RAccessibility.NotApplicable
        override val isAbstract: Boolean get() = false
        override val isStatic: Boolean get() = false
        override val specialType: String? get() = null
        override val arity: Int get() = metadataName.substringAfter('`', "0").toIntOrNull() ?: 0
        override val typeParameters: List<RTypeParameter> get() = emptyList()
        override val baseType: RNamed? get() = null
        override val allInterfaces: List<RNamed> get() = emptyList()
        override val attributes: List<RAttribute> get() = emptyList()
        override val documentation: String? get() = null
        override val properties: List<RProperty> get() = emptyList()
        override val methods: List<RMethod> get() = emptyList()
        override fun equals(other: Any?): Boolean = other is RTypeDefinition && other.key == key
        override fun hashCode(): Int = key.hashCode()
    }

    companion object {
        private const val I_DICTIONARY = "System.Collections.Generic.IDictionary`2"

        /** Roslyn's `SpecialType` of the types the producers and the spellings ask about. */
        private val SPECIAL_TYPES = mapOf(
            "System.Object" to "System_Object", "System.Enum" to "System_Enum", "System.MulticastDelegate" to "System_MulticastDelegate",
            "System.Delegate" to "System_Delegate", "System.ValueType" to "System_ValueType", "System.Void" to "System_Void", "System.Boolean" to "System_Boolean",
            "System.Char" to "System_Char", "System.SByte" to "System_SByte", "System.Byte" to "System_Byte", "System.Int16" to "System_Int16",
            "System.UInt16" to "System_UInt16", "System.Int32" to "System_Int32", "System.UInt32" to "System_UInt32", "System.Int64" to "System_Int64",
            "System.UInt64" to "System_UInt64", "System.Decimal" to "System_Decimal", "System.Single" to "System_Single", "System.Double" to "System_Double",
            "System.String" to "System_String", "System.IntPtr" to "System_IntPtr", "System.UIntPtr" to "System_UIntPtr", "System.Array" to "System_Array",
            "System.Collections.IEnumerable" to "System_Collections_IEnumerable", "System.Collections.Generic.IEnumerable`1" to "System_Collections_Generic_IEnumerable_T",
            "System.Collections.Generic.IList`1" to "System_Collections_Generic_IList_T", "System.Collections.Generic.ICollection`1" to "System_Collections_Generic_ICollection_T",
            "System.Collections.IEnumerator" to "System_Collections_IEnumerator", "System.Collections.Generic.IEnumerator`1" to "System_Collections_Generic_IEnumerator_T",
            "System.Collections.Generic.IReadOnlyList`1" to "System_Collections_Generic_IReadOnlyList_T",
            "System.Collections.Generic.IReadOnlyCollection`1" to "System_Collections_Generic_IReadOnlyCollection_T", "System.Nullable`1" to "System_Nullable_T",
            "System.DateTime" to "System_DateTime", "System.Runtime.CompilerServices.IsVolatile" to "System_Runtime_CompilerServices_IsVolatile",
            "System.IDisposable" to "System_IDisposable", "System.TypedReference" to "System_TypedReference", "System.ArgIterator" to "System_ArgIterator",
            "System.RuntimeArgumentHandle" to "System_RuntimeArgumentHandle", "System.RuntimeFieldHandle" to "System_RuntimeFieldHandle",
            "System.RuntimeMethodHandle" to "System_RuntimeMethodHandle", "System.RuntimeTypeHandle" to "System_RuntimeTypeHandle", "System.IAsyncResult" to "System_IAsyncResult",
            "System.AsyncCallback" to "System_AsyncCallback", "System.Runtime.CompilerServices.RuntimeFeature" to "System_Runtime_CompilerServices_RuntimeFeature",
            "System.Runtime.CompilerServices.PreserveBaseOverridesAttribute" to "System_Runtime_CompilerServices_PreserveBaseOverridesAttribute",
            "System.Runtime.CompilerServices.InlineArrayAttribute" to "System_Runtime_CompilerServices_InlineArrayAttribute",
        )
    }
}
