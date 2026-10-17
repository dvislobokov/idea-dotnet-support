package io.github.dotnetsupport.razor.lang.discovery

import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptorJson

/**
 * The symbol model read from `razordump symbols` (tools/razor/razordump/Symbols.cs, README there): the compilation the compiler's producers
 * searched, for the discovery gate. [displayMismatches] collects the type references whose three spellings ([RazorTypeDisplay]) differ
 * from Roslyn's (`"s"` of the dump).
 */
class SymbolsJson private constructor(root: Map<String, Any?>) : RCompilation {
    private val types = obj(root["types"])
    private val definitions = HashMap<String, Definition>()
    val displayMismatches = ArrayList<String>()
    var displaysChecked = 0
        private set

    override val assemblyName: String = root["assembly"] as String

    override val assemblies: List<RAssembly> = list(root["assemblies"]).map { item ->
        val a = obj(item)
        val keys = list(a["types"]).map { it as String }
        RAssembly(a["name"] as String, list(a["references"]).map { it as String }) { keys.map(::definition) }
    }

    private val byMetadataName = obj(root["byMetadataName"])
    private val wellKnown = obj(root["wellKnown"])

    override fun typeByMetadataName(metadataName: String): RTypeDefinition? {
        require(metadataName in byMetadataName) { "razordump symbols does not record $metadataName (SymbolsJson.WellKnown)" }
        return (byMetadataName[metadataName] as String?)?.let(::definition)
    }

    override fun typesByMetadataName(metadataName: String): List<RTypeDefinition> {
        require(metadataName in wellKnown) { "razordump symbols does not record $metadataName (SymbolsJson.WellKnown)" }
        return list(wellKnown[metadataName]).map { definition(it as String) }
    }

    fun definition(key: String): Definition = definitions.getOrPut(key) {
        Definition(key, obj(types[key] ?: throw IllegalArgumentException("no definition $key in the symbols dump")))
    }

    inner class Definition(override val key: String, private val o: Map<String, Any?>) : RTypeDefinition {
        override val assemblyName: String get() = key.substringBefore('|')
        override val name: String get() = o["name"] as String
        override val metadataName: String get() = o["metadataName"] as String
        override val namespace: String get() = o["namespace"] as String
        override val containingType: RTypeDefinition? by lazy { (o["containing"] as String?)?.let(::definition) }
        override val kind: RTypeKind get() = RTypeKind.valueOf(o["kind"] as String)
        override val accessibility: RAccessibility get() = RAccessibility.valueOf(o["accessibility"] as String)
        override val isAbstract: Boolean get() = o["abstract"] == true
        override val isStatic: Boolean get() = o["static"] == true
        override val specialType: String? get() = o["special"] as String?
        override val arity: Int get() = (o["arity"] as Number).toInt()
        private fun full(): Map<String, Any?> = if (o["full"] == true) o else throw IllegalStateException("$key is only a stub in the symbols dump: its members were not written")
        override val typeParameters: List<RTypeParameter> by lazy {
            if (arity == 0) emptyList() else list(full()["typeParameters"]).map { item ->
                val p = obj(item)
                RTypeParameter(
                    p["name"] as String, p["class"] == true, p["struct"] == true, p["unmanaged"] == true, p["notnull"] == true, p["new"] == true,
                    list(p["constraintTypes"]).map { type(it) }, attributes(p),
                )
            }
        }
        override val baseType: RNamed? by lazy { full()["base"]?.let { type(it) as? RNamed } }
        override val allInterfaces: List<RNamed> by lazy { list(full()["interfaces"]).map { type(it) }.filterIsInstance<RNamed>() }
        override val attributes: List<RAttribute> by lazy { attributes(full()) }
        override val documentation: String? get() = full()["doc"] as String?
        override val properties: List<RProperty> by lazy { list(full()["properties"]).map { property(obj(it)) } }
        override val methods: List<RMethod> by lazy {
            list(full()["methods"]).map { item ->
                val m = obj(item)
                RMethod(
                    m["name"] as String, RAccessibility.valueOf(m["accessibility"] as String), m["static"] == true, m["void"] == true, type(m["returnType"]),
                    list(m["parameters"]).map { p ->
                        val q = obj(p)
                        RParameter(q["name"] as String, type(q["type"]), q["default"] == true, q["dictionary"]?.let { list(it).map(::type) })
                    },
                )
            }
        }

        override fun equals(other: Any?): Boolean = other is RTypeDefinition && other.key == key
        override fun hashCode(): Int = key.hashCode()
        override fun toString(): String = key
    }

    private fun property(p: Map<String, Any?>): RProperty {
        val overridden = obj(p["overridden"] ?: emptyMap<String, Any?>())
        return RProperty(
            p["name"] as String, type(p["type"]), RAccessibility.valueOf(p["accessibility"] as String), p["static"] == true, p["override"] == true,
            if (overridden.isEmpty()) null else ({ definition(overridden["type"] as String).properties.firstOrNull { it.name == overridden["name"] } }),
            (p["parameters"] as Number?)?.toInt() ?: 0, (p["getter"] as String?)?.let(RAccessibility::valueOf), (p["setter"] as String?)?.let(RAccessibility::valueOf),
            p["initOnly"] == true, attributes(p), p["doc"] as String?, p["stringLiteral"] == true, p["dictionaryAssignable"] == true,
            p["dictionary"]?.let { list(it).map(::type) }, p["awaitable"] == true,
        )
    }

    private fun attributes(o: Map<String, Any?>): List<RAttribute> = list(o["attributes"]).map { item ->
        val a = obj(item)
        RAttribute(definition(a["class"] as String), list(a["arguments"]).map { constant(it) }, list(a["named"]).map { n ->
            val m = obj(n)
            (m["name"] as String) to constant(m["value"])
        })
    }

    private fun constant(item: Any?): RConstant {
        val c = obj(item)
        val kind = RConstantKind.valueOf(c["kind"] as String)
        val type = c["type"]?.let { type(it) }
        val value = c["value"]
        return RConstant(
            kind, type,
            when {
                kind == RConstantKind.Array -> value?.let { list(it).map(::constant) }
                kind == RConstantKind.Type && value != null -> type(value)
                else -> value
            },
        )
    }

    /** A type reference; with `"s"`, its spellings checked against [RazorTypeDisplay]. */
    fun type(item: Any?): RType {
        val t = obj(item)
        val nullable = t["q"] == true
        val type = when (t["t"]) {
            "n" -> RNamed(definition(t["d"] as String), list(t["a"]).map(::type), t["o"]?.let { type(it) as RNamed }, t["tn"]?.let { list(it).map { n -> n as String } }, nullable)
            "p" -> RTypeParameterRef(t["n"] as String, t["m"] == true, nullable)
            "a" -> RArray(type(t["e"]), (t["r"] as Number?)?.toInt() ?: 1, nullable)
            "ptr" -> RPointer(type(t["e"]), nullable)
            "dyn" -> RDynamic(nullable)
            else -> RError(t["n"] as String? ?: "?", list(t["a"]).map(::type), t["ns"] as String?, nullable)
        }
        val spellings = t["s"]
        if (spellings != null) {
            val expected = list(spellings).map { it as String }
            val actual = listOf(RazorTypeDisplay.defaultDisplay(type), RazorTypeDisplay.fullName(type), RazorTypeDisplay.globallyQualified(type))
            displaysChecked++
            if (expected != actual && displayMismatches.size < 200) displayMismatches += "$expected != $actual"
        }
        return type
    }

    companion object {
        fun read(text: String): SymbolsJson = SymbolsJson(obj(TagHelperDescriptorJson.Parser(text).parseDocument()))

        @Suppress("UNCHECKED_CAST")
        private fun obj(item: Any?): Map<String, Any?> = item as? Map<String, Any?> ?: throw IllegalArgumentException("symbols JSON: an object expected, got $item")

        @Suppress("UNCHECKED_CAST")
        private fun list(item: Any?): List<Any?> = item as List<Any?>? ?: emptyList()
    }
}
