package io.github.dotnetsupport.lang

import io.github.dotnetsupport.lsp.RoslynOptions

/**
 * The "Code Generation" options of the page of the server (Settings | .NET | Language Server), obeyed by every native generator that
 * writes a member into a type, as the server obeys them (rule of the user 2026-10-06: one page, both sides).
 */
object CSharpGenerationOptions {
    const val INSERTION_LOCATION = "type_members.dotnet_member_insertion_location"
    const val PROPERTY_BEHAVIOR = "type_members.dotnet_property_generation_behavior"

    /**
     * `at_the_end`: a generated member goes after the last member of the type, whatever the caret and the kind. The default,
     * `with_other_members_of_the_same_kind`, is what each generator does on its own: a field after the fields, a property after the
     * properties, Generate (Alt+Insert) and Implement at the caret, as in Rider.
     */
    val atEnd: Boolean get() = RoslynOptions.value(INSERTION_LOCATION) == "at_the_end"

    /** `prefer_throwing_properties` (the default of Roslyn): an implemented property throws `NotImplementedException` instead of `{ get; set; }`. */
    val throwingProperties: Boolean get() = RoslynOptions.value(PROPERTY_BEHAVIOR) != "prefer_auto_properties"
}
