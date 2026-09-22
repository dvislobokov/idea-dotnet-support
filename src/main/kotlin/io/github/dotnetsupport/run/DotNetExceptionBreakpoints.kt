package io.github.dotnetsupport.run

/**
 * When a .NET exception breakpoint stops the program, in the terms of the debug adapter (`exceptionBreakpointFilters` of `dotnet-debugger`).
 * Knows nothing about the DAP client: the breakpoint type of the debugger module turns its settings into these.
 */
enum class DotNetExceptionFilter(val id: String, val title: String) {
    THROWN("all", "Thrown"),
    USER_UNHANDLED("user-unhandled", "User-unhandled"),
    UNHANDLED("unhandled", "Unhandled"),
}

object DotNetExceptionBreakpoints {
    /**
     * `ShopException;  System.IO.*  !System.OperationCanceledException` -> `ShopException, System.IO.*, !System.OperationCanceledException`,
     * the form the adapter takes as the condition of a filter; null for "any exception".
     */
    fun typeCondition(text: String?): String? =
        text.orEmpty().split(',', ';', ' ', '\t', '\n').map { it.trim() }.filter { it.isNotEmpty() && it != "!" }.distinct().joinToString(", ").ifEmpty { null }

    /** "Any exception" or the types, then when: `ShopException, System.IO.* (thrown, unhandled)`. */
    fun displayText(types: String?, filters: Collection<DotNetExceptionFilter>): String {
        val what = typeCondition(types) ?: "Any exception"
        val whenText = DotNetExceptionFilter.entries.filter { it in filters }.joinToString(", ") { it.title.lowercase() }
        return if (whenText.isEmpty()) "$what (never)" else "$what ($whenText)"
    }
}
