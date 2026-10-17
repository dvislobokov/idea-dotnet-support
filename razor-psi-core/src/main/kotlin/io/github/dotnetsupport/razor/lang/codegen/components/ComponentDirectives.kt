package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.RazorProjectEngineBuilder
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.syntax.DirectiveDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorDirectives
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind

/**
 * The directives of components (`Component*Directive.cs`): the descriptors — the parser's instances of [RazorDirectives], so that a
 * directive node of the parsed tree is `===` the descriptor a pass looks for — and their registration. The passes are in their own files.
 */

/** `ComponentCodeDirective`: `@code { }`. */
object ComponentCodeDirective {
    val directive: DirectiveDescriptor = RazorDirectives.code

    fun register(builder: RazorProjectEngineBuilder) {
        builder.addDirective(directive, RazorFileKind.Component)
    }
}

/** `ComponentConstrainedTypeParamDirective`: `@typeparam TItem where TItem : …` (language version 6.0 and later). */
object ComponentConstrainedTypeParamDirective {
    val directive: DirectiveDescriptor = RazorDirectives.constrainedTypeparam

    fun register(builder: RazorProjectEngineBuilder): RazorProjectEngineBuilder {
        builder.addDirective(directive, RazorFileKind.Component, RazorFileKind.ComponentImport)
        return builder
    }
}

/** `ComponentInjectDirective`: `@inject Type Name` of components (properties for the components' property injector). */
object ComponentInjectDirective {
    val directive: DirectiveDescriptor = RazorDirectives.componentInject

    fun register(builder: RazorProjectEngineBuilder) {
        builder.addDirective(directive, RazorFileKind.Component, RazorFileKind.ComponentImport)
        builder.features.add(ComponentInjectDirectivePass())
    }
}

/** `ComponentLayoutDirective`: `@layout Type`. */
object ComponentLayoutDirective {
    val directive: DirectiveDescriptor = RazorDirectives.layout

    fun register(builder: RazorProjectEngineBuilder) {
        builder.addDirective(directive, RazorFileKind.Component, RazorFileKind.ComponentImport)
        builder.features.add(ComponentLayoutDirectivePass())
    }
}

/** `ComponentPageDirective`: `@page "/route"` of components. */
class ComponentPageDirective private constructor() {
    /** Never set in the compiler either (it has no constructor that sets them). */
    val routeTemplate: String? = null
    val directiveNode: IntermediateNode? = null

    companion object {
        val directive: DirectiveDescriptor = RazorDirectives.componentPage

        fun register(builder: RazorProjectEngineBuilder): RazorProjectEngineBuilder {
            builder.addDirective(directive, RazorFileKind.Component, RazorFileKind.ComponentImport)
            builder.features.add(ComponentPageDirectivePass())
            return builder
        }
    }
}

/** `ComponentPreserveWhitespaceDirective`: `@preservewhitespace true`. */
object ComponentPreserveWhitespaceDirective {
    val directive: DirectiveDescriptor = RazorDirectives.preservewhitespace

    fun register(builder: RazorProjectEngineBuilder) {
        builder.addDirective(directive, RazorFileKind.Component, RazorFileKind.ComponentImport)
    }
}

/** `ComponentRenderModeDirective`: `@rendermode Mode`. */
object ComponentRenderModeDirective {
    val directive: DirectiveDescriptor = RazorDirectives.rendermode

    fun register(builder: RazorProjectEngineBuilder) {
        builder.addDirective(directive, RazorFileKind.Component)
        builder.features.add(ComponentRenderModeDirectivePass())
    }
}

/** `ComponentTypeParamDirective`: `@typeparam TItem` (before language version 6.0). */
object ComponentTypeParamDirective {
    val directive: DirectiveDescriptor = RazorDirectives.typeparam

    fun register(builder: RazorProjectEngineBuilder): RazorProjectEngineBuilder {
        builder.addDirective(directive, RazorFileKind.Component, RazorFileKind.ComponentImport)
        return builder
    }
}
