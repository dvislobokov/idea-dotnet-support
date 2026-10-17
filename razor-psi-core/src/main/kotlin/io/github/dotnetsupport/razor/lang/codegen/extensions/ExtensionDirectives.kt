package io.github.dotnetsupport.razor.lang.codegen.extensions

import io.github.dotnetsupport.razor.lang.codegen.RazorProjectEngineBuilder
import io.github.dotnetsupport.razor.lang.syntax.DirectiveDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorDirectives
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind

/**
 * The directives of the engine's extensions (the `*Directive.cs` files of `Extensions`): the descriptors — the parser's instances of [RazorDirectives],
 * so that a directive node of the parsed tree is `===` the descriptor a pass looks for — and their registration. The passes are in their
 * own files.
 */

/** `AttributeDirective`: `@attribute [Attr]`. */
object AttributeDirective {
    val directive: DirectiveDescriptor = RazorDirectives.attribute

    fun register(builder: RazorProjectEngineBuilder) {
        builder.addDirective(directive, RazorFileKind.Legacy, RazorFileKind.Component, RazorFileKind.ComponentImport)
        builder.features.add(AttributeDirectivePass())
    }
}

/** `DocumentationDirective`: `@documentation { <summary>…</summary> }`. */
object DocumentationDirective {
    val directive: DirectiveDescriptor = RazorDirectives.documentation

    fun register(builder: RazorProjectEngineBuilder) {
        builder.addDirective(directive, RazorFileKind.Legacy, RazorFileKind.Component, RazorFileKind.ComponentImport)
        builder.features.add(DocumentationDirectivePass())
    }
}

/** `FunctionsDirective`: `@functions { }`. */
object FunctionsDirective {
    val directive: DirectiveDescriptor = RazorDirectives.functions

    fun register(builder: RazorProjectEngineBuilder) {
        builder.addDirective(directive, RazorFileKind.Legacy, RazorFileKind.Component)
        builder.features.add(FunctionsDirectivePass())
    }
}

/** `ImplementsDirective`: `@implements IInterface`. */
object ImplementsDirective {
    val directive: DirectiveDescriptor = RazorDirectives.implements

    fun register(builder: RazorProjectEngineBuilder) {
        builder.addDirective(directive, RazorFileKind.Legacy, RazorFileKind.Component, RazorFileKind.ComponentImport)
        builder.features.add(ImplementsDirectivePass())
    }
}

/** `InheritsDirective`: `@inherits BaseType`. */
object InheritsDirective {
    val directive: DirectiveDescriptor = RazorDirectives.inherits

    fun register(builder: RazorProjectEngineBuilder) {
        builder.addDirective(directive, RazorFileKind.Legacy, RazorFileKind.Component, RazorFileKind.ComponentImport)
        builder.features.add(InheritsDirectivePass())
    }
}

/** `NamespaceDirective`: `@namespace Name` (no pass of its own: the classifiers read it). */
object NamespaceDirective {
    val directive: DirectiveDescriptor = RazorDirectives.namespace

    fun register(builder: RazorProjectEngineBuilder): RazorProjectEngineBuilder {
        builder.addDirective(directive, RazorFileKind.Legacy, RazorFileKind.Component, RazorFileKind.ComponentImport)
        return builder
    }
}

/** `SectionDirective`: `@section Name { }` of MVC views. */
object SectionDirective {
    val directive: DirectiveDescriptor = RazorDirectives.section

    fun register(builder: RazorProjectEngineBuilder) {
        builder.addDirective(directive, RazorFileKind.Legacy)
        builder.features.add(SectionDirectivePass())
        builder.addTargetExtension(SectionTargetExtension())
    }
}
