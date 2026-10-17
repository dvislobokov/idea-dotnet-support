package io.github.dotnetsupport.razor.lang.syntax

enum class DirectiveKind { SingleLine, RazorBlock, CodeBlock }

enum class DirectiveUsage { Unrestricted, FileScopedSinglyOccurring, FileScopedMultipleOccurring }

enum class DirectiveTokenKind { Type, Namespace, Member, String, Attribute, Boolean, GenericTypeConstraint, IdentifierOrExpression }

/** `DirectiveTokenDescriptor`: one argument of a directive; [name] is what the compiler shows (and prints in `Gen<DirectiveToken {…}>`). */
class DirectiveTokenDescriptor(val kind: DirectiveTokenKind, val optional: Boolean = false, val name: String? = null, val description: String? = null)

/** `DirectiveDescriptor`: a directive the parser knows — its keyword, its shape, how often it may occur and its tokens. */
class DirectiveDescriptor(
    val directive: String, val kind: DirectiveKind, val usage: DirectiveUsage = DirectiveUsage.Unrestricted,
    val tokens: List<DirectiveTokenDescriptor> = emptyList(), val displayName: String? = null, val description: String? = null,
) {
    override fun toString(): String = directive

    class Builder(val directive: String, val kind: DirectiveKind) {
        var usage: DirectiveUsage = DirectiveUsage.Unrestricted
        var description: String? = null
        var displayName: String? = null
        val tokens = ArrayList<DirectiveTokenDescriptor>()

        fun addTypeToken(name: String? = null, description: String? = null) = add(DirectiveTokenKind.Type, false, name, description)
        fun addNamespaceToken(name: String? = null, description: String? = null) = add(DirectiveTokenKind.Namespace, false, name, description)
        fun addMemberToken(name: String? = null, description: String? = null) = add(DirectiveTokenKind.Member, false, name, description)
        fun addStringToken(name: String? = null, description: String? = null) = add(DirectiveTokenKind.String, false, name, description)
        fun addAttributeToken(name: String? = null, description: String? = null) = add(DirectiveTokenKind.Attribute, false, name, description)
        fun addBooleanToken(name: String? = null, description: String? = null) = add(DirectiveTokenKind.Boolean, false, name, description)
        fun addIdentifierOrExpression(name: String? = null, description: String? = null) = add(DirectiveTokenKind.IdentifierOrExpression, false, name, description)
        fun addOptionalStringToken(name: String? = null, description: String? = null) = add(DirectiveTokenKind.String, true, name, description)
        fun addOptionalTypeToken(name: String? = null, description: String? = null) = add(DirectiveTokenKind.Type, true, name, description)
        fun addOptionalMemberToken(name: String? = null, description: String? = null) = add(DirectiveTokenKind.Member, true, name, description)
        fun addOptionalGenericTypeConstraintToken(name: String? = null, description: String? = null) = add(DirectiveTokenKind.GenericTypeConstraint, true, name, description)

        private fun add(kind: DirectiveTokenKind, optional: Boolean, name: String?, description: String?): Builder {
            tokens += DirectiveTokenDescriptor(kind, optional, name, description)
            return this
        }

        fun build(): DirectiveDescriptor {
            require(directive.isNotEmpty() && directive.all { it.isLetter() }) { "Invalid directive keyword '$directive'" }
            var optionalSeen = false
            for (token in tokens) {
                optionalSeen = optionalSeen or token.optional
                require(!optionalSeen || token.optional) { "A non-optional directive token cannot follow an optional directive token." }
            }
            return DirectiveDescriptor(directive, kind, usage, tokens.toList(), displayName, description)
        }
    }

    companion object {
        fun create(directive: String, kind: DirectiveKind, configure: (Builder.() -> Unit)? = null): DirectiveDescriptor =
            Builder(directive, kind).apply { configure?.invoke(this) }.build()
    }
}

/**
 * The directives the compiler registers (`RazorProjectEngine.Create`, `AddComponentFeatures`, the MVC `RazorExtensions.Register`), by
 * file kind and language version: what a `.razor` or `.cshtml` of an SDK project may use. Names and token names are the compiler's
 * (Resources.resx, ComponentResources.resx, RazorExtensionsResources.resx), since the serializer prints them.
 */
object RazorDirectives {
    val functions = DirectiveDescriptor.create("functions", DirectiveKind.CodeBlock)
    val implements = DirectiveDescriptor.create("implements", DirectiveKind.SingleLine) {
        addTypeToken("TypeName", "The interface to implement for the current page.")
        usage = DirectiveUsage.FileScopedMultipleOccurring
    }
    val inherits = DirectiveDescriptor.create("inherits", DirectiveKind.SingleLine) {
        addTypeToken("TypeName", "The base type for the current page.")
        usage = DirectiveUsage.FileScopedSinglyOccurring
    }
    val namespace = DirectiveDescriptor.create("namespace", DirectiveKind.SingleLine) {
        addNamespaceToken("Namespace", "The namespace of the current page.")
        usage = DirectiveUsage.FileScopedSinglyOccurring
    }
    val attribute = DirectiveDescriptor.create("attribute", DirectiveKind.SingleLine) {
        addAttributeToken("Attribute", "The C# attribute that will be applied to the current page.")
        usage = DirectiveUsage.FileScopedMultipleOccurring
    }
    val documentation = DirectiveDescriptor.create("documentation", DirectiveKind.CodeBlock) {
        usage = DirectiveUsage.FileScopedSinglyOccurring
    }

    // Components (AddComponentFeatures)
    val code = DirectiveDescriptor.create("code", DirectiveKind.CodeBlock)
    val componentInject = DirectiveDescriptor.create("inject", DirectiveKind.SingleLine) {
        addTypeToken("TypeName", "The type of the service to inject.")
        addMemberToken("PropertyName", "The name of the property.")
        usage = DirectiveUsage.FileScopedMultipleOccurring
    }
    val layout = DirectiveDescriptor.create("layout", DirectiveKind.SingleLine) {
        addTypeToken("TypeName", "The type name of the layout component.")
        usage = DirectiveUsage.FileScopedSinglyOccurring
    }
    val componentPage = DirectiveDescriptor.create("page", DirectiveKind.SingleLine) {
        addStringToken("route template", "The route template for the component.")
        usage = DirectiveUsage.FileScopedMultipleOccurring
    }
    val typeparam = DirectiveDescriptor.create("typeparam", DirectiveKind.SingleLine) {
        addMemberToken("type parameter", "The name of the type parameter.")
        usage = DirectiveUsage.FileScopedMultipleOccurring
    }
    val constrainedTypeparam = DirectiveDescriptor.create("typeparam", DirectiveKind.SingleLine) {
        addMemberToken("type parameter", "The name of the type parameter.")
        addOptionalGenericTypeConstraintToken("type parameter constraint", "The constraint of the type parameter.")
        usage = DirectiveUsage.FileScopedMultipleOccurring
    }
    val preservewhitespace = DirectiveDescriptor.create("preservewhitespace", DirectiveKind.SingleLine) {
        addBooleanToken("Preserve", "Boolean value that indicates whether to preserve whitespace.")
        usage = DirectiveUsage.FileScopedSinglyOccurring
    }
    val rendermode = DirectiveDescriptor.create("rendermode", DirectiveKind.SingleLine) {
        addIdentifierOrExpression("Render mode", "The render mode for the component.")
        usage = DirectiveUsage.FileScopedSinglyOccurring
    }

    // MVC views and pages (RazorExtensions.Register of Microsoft.AspNetCore.Mvc.Razor.Extensions)
    val mvcInject = DirectiveDescriptor.create("inject", DirectiveKind.SingleLine) {
        addTypeToken("TypeName", "The type of the service to inject.")
        addMemberToken("PropertyName", "The name of the property.")
        usage = DirectiveUsage.FileScopedMultipleOccurring
    }
    val model = DirectiveDescriptor.create("model", DirectiveKind.SingleLine) {
        addTypeToken("TypeName", "The model type.")
        usage = DirectiveUsage.FileScopedSinglyOccurring
    }
    val mvcPage = DirectiveDescriptor.create("page", DirectiveKind.SingleLine) {
        addOptionalStringToken("RouteTemplate", "The route template for the page.")
        usage = DirectiveUsage.FileScopedSinglyOccurring
    }
    val section = DirectiveDescriptor.create("section", DirectiveKind.RazorBlock) {
        addMemberToken("SectionName", "The name of the section.")
    }

    /** The directives of the file kind, in the order the engine registers them (the order only decides duplicates by name). */
    fun forFileKind(kind: RazorFileKind, version: RazorLanguageVersion = RazorLanguageVersion.LATEST): List<DirectiveDescriptor> {
        val list = ArrayList<DirectiveDescriptor>()
        if (version >= RazorLanguageVersion.VERSION_12_0) list += documentation
        if (version >= RazorLanguageVersion.VERSION_3_0) {
            if (kind != RazorFileKind.ComponentImport) list += functions
            list += implements
            list += inherits
            list += namespace
            list += attribute
            if (kind.isComponent) {
                if (kind == RazorFileKind.Component) list += code
                list += componentInject
                list += layout
                list += componentPage
                list += if (version >= RazorLanguageVersion.VERSION_6_0) constrainedTypeparam else typeparam
                if (version >= RazorLanguageVersion.VERSION_5_0) list += preservewhitespace
                if (version >= RazorLanguageVersion.VERSION_8_0 && kind == RazorFileKind.Component) list += rendermode
            }
        }
        if (kind == RazorFileKind.Legacy) {
            list += mvcInject
            list += model
            list += mvcPage
            list += section
        }
        return list
    }
}
