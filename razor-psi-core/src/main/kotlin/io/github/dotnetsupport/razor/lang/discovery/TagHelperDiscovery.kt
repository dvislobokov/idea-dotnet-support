package io.github.dotnetsupport.razor.lang.discovery

import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.TagHelperCollection
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor

/**
 * The tag helper discovery of the compiler (`TagHelperDiscoveryService`, `TagHelperDiscoverer`) over the symbol model: the producers the
 * source generator's engine registers (`CompilerFeatures.Register`, then MVC's `RazorExtensions.Register`) created from the compilation,
 * each assembly searched — its own first, then the references — and the collections merged without repeats.
 *
 * An assembly is searched when its name or the name of an assembly it references starts with `Microsoft.AspNetCore.`; there the producers
 * add their static descriptors, then every type (namespaces depth first, the nested types of public types) goes to the producers that take it.
 * [discoverAssembly] is the part a host caches per assembly (the descriptors of a referenced assembly depend only on it and the producers).
 */
class TagHelperDiscovery(private val producers: List<TagHelperProducer>) {
    private val typeProducers = producers.filter { it.supportsTypes }

    fun discover(compilation: RCompilation): TagHelperCollection {
        val all = ArrayList<TagHelperDescriptor>()
        for (assembly in compilation.assemblies) all += discoverAssembly(assembly)
        return TagHelperCollection(distinct(all))
    }

    fun discoverAssembly(assembly: RAssembly): List<TagHelperDescriptor> {
        if (producers.isEmpty() || !mightContainTagHelpers(assembly)) return emptyList()
        val results = ArrayList<TagHelperDescriptor>()
        for (producer in producers) if (producer.supportsStaticTagHelpers) producer.addStaticTagHelpers(assembly, results)
        for (type in assembly.types) {
            for (producer in typeProducers) if (producer.isCandidateType(type)) producer.addTagHelpersForType(type, results)
        }
        return distinct(results)
    }

    companion object {
        private const val ASP_NET_CORE = "Microsoft.AspNetCore."

        fun mightContainTagHelpers(assembly: RAssembly): Boolean =
            assembly.name.startsWith(ASP_NET_CORE) || assembly.referencedAssemblyNames.any { it.startsWith(ASP_NET_CORE) }

        /**
         * The producers of the source generator's engine for [compilation] in their order: the components' (`@bind`, components, event
         * handlers, `@ref`, `@key`, `@attributes`, asset paths; `@rendermode` and `@formname` from Razor 8.0), then MVC's tag helpers and
         * view components ([mvc]).
         */
        fun forCompilation(
            compilation: RCompilation, version: RazorLanguageVersion = RazorLanguageVersion.LATEST, mvc: Boolean = true, includeDocumentation: Boolean = false,
            excludeHidden: Boolean = false,
        ): TagHelperDiscovery {
            val producers = ArrayList<TagHelperProducer>()
            if (version >= RazorLanguageVersion.VERSION_3_0) {
                val bind = BindTagHelperProducer.create(compilation)
                bind?.let { producers += it }
                producers += ComponentTagHelperProducer.create(compilation, bind)
                EventHandlerTagHelperProducer.create(compilation)?.let { producers += it }
                StaticTagHelperProducer.ref(compilation)?.let { producers += it }
                StaticTagHelperProducer.key(compilation)?.let { producers += it }
                StaticTagHelperProducer.splat(compilation)?.let { producers += it }
                AcceptsAssetPathTagHelperProducer.create(compilation)?.let { producers += it }
            }
            if (version >= RazorLanguageVersion.VERSION_8_0) {
                StaticTagHelperProducer.renderMode(compilation)?.let { producers += it }
                StaticTagHelperProducer.formName(compilation)?.let { producers += it }
            }
            if (mvc) {
                DefaultTagHelperProducer.create(compilation, includeDocumentation, excludeHidden)?.let { producers += it }
                ViewComponentTagHelperProducer.create(compilation)?.let { producers += it }
            }
            return TagHelperDiscovery(producers)
        }

        private fun distinct(descriptors: List<TagHelperDescriptor>): List<TagHelperDescriptor> {
            val seen = HashSet<String>()
            return descriptors.filter { seen.add(tagHelperKey(it)) }
        }
    }
}
