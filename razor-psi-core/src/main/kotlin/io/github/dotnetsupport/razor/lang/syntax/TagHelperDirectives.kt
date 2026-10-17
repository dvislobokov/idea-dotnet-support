package io.github.dotnetsupport.razor.lang.syntax

/** The descriptors of `@addTagHelper`, `@removeTagHelper` and `@tagHelperPrefix` (`CSharpCodeParser.*DirectiveDescriptor`): the lowering phase hangs their directive nodes on them. */
object TagHelperDirectives {
    val addTagHelper: DirectiveDescriptor = DirectiveDescriptor.create(SyntaxConstants.CSharp.ADD_TAG_HELPER_KEYWORD, DirectiveKind.SingleLine) {
        addStringToken("TypeName, AssemblyName", "Add tag helpers from the specified type name and assembly name. Specify '*' for the type name to include all tag helper types from the specified assembly.")
        description = "Register Tag Helpers for use in the current document."
    }

    val removeTagHelper: DirectiveDescriptor = DirectiveDescriptor.create(SyntaxConstants.CSharp.REMOVE_TAG_HELPER_KEYWORD, DirectiveKind.SingleLine) {
        addStringToken("TypeName, AssemblyName", "Remove tag helpers from the specified type name and assembly name. Specify '*' for the type name to remove all tag helper types from the specified assembly.")
        description = "Remove Tag Helpers for use in the current document."
    }

    val tagHelperPrefix: DirectiveDescriptor = DirectiveDescriptor.create(SyntaxConstants.CSharp.TAG_HELPER_PREFIX_KEYWORD, DirectiveKind.SingleLine) {
        addStringToken("Prefix", "The tag prefix to apply to tag helpers.")
        description = "Specify a prefix that is required in an element name for it to be included in Tag Helper processing."
    }
}
