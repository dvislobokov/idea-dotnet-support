package io.github.dotnetsupport.razor.lang.codegen.components

/** `ComponentHelpers` (`ComponentHelpers.cs`): the imports file name, the mangled class names of the declaration pass, the child content names. */
object ComponentHelpers {
    const val IMPORTS_FILE_NAME = "_Imports.razor"

    private const val MANGLED_CLASS_NAME_PREFIX = "__generated__"

    fun mangleClassName(className: String?): String {
        if (className.isNullOrEmpty()) return ""
        return MANGLED_CLASS_NAME_PREFIX + className
    }

    fun isMangledClass(className: String?): Boolean = className?.startsWith(MANGLED_CLASS_NAME_PREFIX) == true

    /** `ComponentHelpers.ChildContent`. */
    object ChildContent {
        /** The name of the synthesized attribute used to set a child content parameter. */
        const val PARAMETER_ATTRIBUTE_NAME = "Context"

        /** The default name of the child content parameter (unless set by a Context attribute). */
        const val DEFAULT_PARAMETER_NAME = "context"
    }
}
