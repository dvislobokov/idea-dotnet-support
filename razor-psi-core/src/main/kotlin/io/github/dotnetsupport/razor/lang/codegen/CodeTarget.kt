package io.github.dotnetsupport.razor.lang.codegen

/**
 * `CodeTarget` (`CodeTarget.cs`): what renders a document — the node writer and the target extensions (sections, templates, tag helpers,
 * ...) the extension nodes look up by type.
 */
abstract class CodeTarget(val codeDocument: RazorCodeDocument, val extensions: List<ICodeTargetExtension>) {
    val options: RazorCodeGenerationOptions get() = codeDocument.codeGenerationOptions

    abstract fun createNodeWriter(): IntermediateNodeWriter

    /** `GetExtension<TExtension>`: the first extension of the type. */
    fun <T : ICodeTargetExtension> getExtension(type: Class<T>): T? {
        for (extension in extensions) if (type.isInstance(extension)) return type.cast(extension)
        return null
    }

    fun <T : ICodeTargetExtension> hasExtension(type: Class<T>): Boolean = extensions.any { type.isInstance(it) }

    inline fun <reified T : ICodeTargetExtension> getExtension(): T? = getExtension(T::class.java)

    inline fun <reified T : ICodeTargetExtension> hasExtension(): Boolean = hasExtension(T::class.java)

    companion object {
        /** `CreateDefault`: the default target with the extensions [configure] adds. */
        fun createDefault(codeDocument: RazorCodeDocument, configure: ((CodeTargetBuilder) -> Unit)? = null): CodeTarget {
            val builder = DefaultCodeTargetBuilder(codeDocument)
            configure?.invoke(builder)
            return builder.build()
        }
    }
}

/** `CodeTargetBuilder` (`CodeTargetBuilder.cs`). */
abstract class CodeTargetBuilder(val codeDocument: RazorCodeDocument) {
    val options: RazorCodeGenerationOptions get() = codeDocument.codeGenerationOptions
    val targetExtensions: MutableList<ICodeTargetExtension> = ArrayList()

    abstract fun build(): CodeTarget
}

/** `DefaultCodeTarget` (`DefaultCodeTarget.cs`): the runtime node writer. */
class DefaultCodeTarget(codeDocument: RazorCodeDocument, extensions: List<ICodeTargetExtension>) : CodeTarget(codeDocument, extensions) {
    override fun createNodeWriter(): IntermediateNodeWriter = IntermediateNodeWriter.INSTANCE
}

/** `DefaultCodeTargetBuilder` (`DefaultCodeTargetBuilder.cs`). */
class DefaultCodeTargetBuilder(codeDocument: RazorCodeDocument) : CodeTargetBuilder(codeDocument) {
    override fun build(): CodeTarget = DefaultCodeTarget(codeDocument, targetExtensions.toList())
}

/** `ICodeTargetExtension` (`ICodeTargetExtension.cs`): a marker of the target extensions. */
interface ICodeTargetExtension

/** `TagHelperRenderingContext` (`TagHelperRenderingContext.cs`): the bound attributes rendered (case-insensitive) and the dictionaries checked. */
class TagHelperRenderingContext {
    private var renderedBoundAttributesMap: MutableMap<String, String>? = null
    private var verifiedPropertyDictionariesSet: MutableSet<String>? = null

    /** `RenderedBoundAttributes`: keyed by `StringComparer.OrdinalIgnoreCase`. */
    val renderedBoundAttributes: MutableMap<String, String>
        get() = renderedBoundAttributesMap ?: java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).also { renderedBoundAttributesMap = it }

    val verifiedPropertyDictionaries: MutableSet<String>
        get() = verifiedPropertyDictionariesSet ?: HashSet<String>().also { verifiedPropertyDictionariesSet = it }
}
