package io.github.dotnetsupport.run

/** The hit conditions `dotnet-debugger` understands: a positive number, optionally after `==`, `>=`, `>`, `<=`, `<` or `%`. */
object HitCondition {
    private val SYNTAX = Regex("""(==?|>=|>|<=|<|%)?\s*([1-9]\d*)""")

    fun isValid(text: String?): Boolean = text.isNullOrBlank() || SYNTAX.matches(text.trim())

    /** `> = 3` is `>=3` to the adapter as well; blank is no condition. */
    fun normalize(text: String?): String? = text?.replace(" ", "")?.takeIf { it.isNotEmpty() }
}
