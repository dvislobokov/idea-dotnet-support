package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.syntax.MetadataKind
import io.github.dotnetsupport.razor.lang.syntax.MetadataObject

/** `ComponentMetadata`: what the tag helper descriptor of a component knows about it (a record: equality by value). */
data class ComponentMetadata(
    val isGeneric: Boolean = false,
    val hasRenderModeDirective: Boolean = false,
    internal val acceptsUnmatchedAttributes: Boolean = false,
) : MetadataObject(MetadataKind.Component) {
    override val hasDefaultValue: Boolean get() = this == DEFAULT

    /** `ComponentMetadata.Builder`. */
    class Builder {
        var isGeneric: Boolean = false
        var hasRenderModeDirective: Boolean = false
        internal var acceptsUnmatchedAttributes: Boolean = false

        fun build(): ComponentMetadata = ComponentMetadata(isGeneric, hasRenderModeDirective, acceptsUnmatchedAttributes)
    }

    companion object {
        val DEFAULT = ComponentMetadata()
    }
}
