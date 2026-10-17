package io.github.dotnetsupport.razor.lang.discovery

import io.github.dotnetsupport.razor.lang.codegen.components.ComponentMetadata
import io.github.dotnetsupport.razor.lang.syntax.AllowedChildTagDescriptor
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeDescriptor
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeParameterDescriptor
import io.github.dotnetsupport.razor.lang.syntax.MetadataObject
import io.github.dotnetsupport.razor.lang.syntax.PropertyMetadata
import io.github.dotnetsupport.razor.lang.syntax.RequiredAttributeDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperCollection
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagMatchingRuleDescriptor

/**
 * The descriptors as a compiler without some metadata properties of main has them (razordump writes such a property as its default and
 * names it in `absent=`; SDK 10.0.401: `Component.AcceptsUnmatchedAttributes`, `Property.AcceptsAssetPath`, `Property.AcceptsStringLiteral`):
 * the port follows main, the gates compare with the installed compiler. Without [documentation]: the documentation of every descriptor dropped
 * (the compiler's of the sources has its `cref`s resolved — a gate of the symbols of the sources leaves it out).
 */
object TagHelperCompatibility {
    fun withDefaults(collection: TagHelperCollection, absent: Set<String>, documentation: Boolean = true): TagHelperCollection {
        if (absent.isEmpty() && documentation) return collection
        fun metadata(m: MetadataObject): MetadataObject = when (m) {
            is ComponentMetadata -> if ("Component.AcceptsUnmatchedAttributes" in absent) m.copy(acceptsUnmatchedAttributes = false) else m
            is PropertyMetadata -> m.copy(
                acceptsStringLiteral = m.acceptsStringLiteral && "Property.AcceptsStringLiteral" !in absent,
                acceptsAssetPath = m.acceptsAssetPath && "Property.AcceptsAssetPath" !in absent,
            )
            else -> m
        }
        return TagHelperCollection(collection.map { d ->
            TagHelperDescriptor(
                d.flags, d.kind, d.runtimeKind, d.name, d.assemblyName, d.displayName, d.typeNameObject, d.documentation.takeIf { documentation }, d.tagOutputHint,
                d.tagMatchingRules.map { r ->
                    TagMatchingRuleDescriptor(r.tagName, r.parentTag, r.tagStructure, r.caseSensitive, r.attributes.map {
                        RequiredAttributeDescriptor(it.flags, it.name, it.nameComparison, it.value, it.valueComparison, it.diagnostics)
                    }, r.diagnostics)
                },
                d.boundAttributes.map { a ->
                    BoundAttributeDescriptor(
                        a.flags, a.name, a.propertyName, a.typeNameObject, a.indexerNamePrefix, a.indexerTypeNameObject, a.documentation.takeIf { documentation }, a.displayName,
                        a.containingType,
                        a.parameters.map { BoundAttributeParameterDescriptor(it.flags, it.name, it.propertyName, it.typeNameObject, it.documentation.takeIf { documentation }, it.diagnostics) },
                        metadata(a.metadata), a.diagnostics,
                    )
                },
                d.allowedChildTags.map { AllowedChildTagDescriptor(it.name, it.displayName, it.diagnostics) }, metadata(d.metadata), d.diagnostics,
            )
        })
    }

    /** The `absent=` list of razordump's summary line. */
    fun absentOf(summary: String): Set<String> =
        Regex("absent=(\\S*)").find(summary)?.groupValues?.get(1)?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** One line per descriptor of [expected] that [actual] lacks, has otherwise (the first differing line of its JSON) or has elsewhere; extras last. */
    fun compare(expected: TagHelperCollection, actual: TagHelperCollection): List<String> {
        fun key(d: TagHelperDescriptor) = "${d.kind} ${d.name} ${d.tagMatchingRules.firstOrNull()?.tagName} ${d.isFullyQualifiedNameMatch}"
        fun json(d: TagHelperDescriptor) = io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptorJson.write(TagHelperCollection(listOf(d))).lines()
        val result = ArrayList<String>()
        val actualByKey = LinkedHashMap<String, MutableList<Int>>()
        for (i in actual.indices) actualByKey.getOrPut(key(actual[i])) { ArrayList() } += i
        val used = HashSet<Int>()
        for (i in expected.indices) {
            val k = key(expected[i])
            val j = actualByKey[k]?.firstOrNull { it !in used }
            if (j == null) {
                result += "missing: $k"
                continue
            }
            used += j
            val e = json(expected[i])
            val a = json(actual[j])
            val at = e.indices.firstOrNull { it >= a.size || e[it] != a[it] }
            if (at != null || e.size != a.size) {
                val line = at ?: e.size
                result += "differs: $k at line $line: expected ${e.getOrNull(line)?.trim()} got ${a.getOrNull(line)?.trim()}"
            } else if (i != j) {
                result += "order: $k at $j, expected at $i"
            }
        }
        for (j in actual.indices) if (j !in used) result += "extra: ${key(actual[j])}"
        return result
    }
}
