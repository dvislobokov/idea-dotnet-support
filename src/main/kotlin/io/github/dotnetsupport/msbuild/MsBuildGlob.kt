package io.github.dotnetsupport.msbuild

/**
 * MSBuild wildcards over paths relative to the project directory: `**` is any number of directories, `*` anything within a
 * name, `?` one character; `\` and `/` are the same. Case does not matter, as on Windows where most project files are written.
 */
class MsBuildGlob(pattern: String) {
    /** Normalized: `/` separators, no leading `./`. */
    val pattern: String = normalize(pattern)
    private val regex: Regex = toRegex(this.pattern)

    fun matches(relativePath: String): Boolean = regex.matches(normalize(relativePath))

    /** Whether everything inside the directory is matched: the pattern is `dir\**` or `dir\**\*` (comments nest in Kotlin, hence the backslashes). */
    fun coversDirectory(relativePath: String): Boolean {
        val directory = normalize(relativePath).trimEnd('/')
        return pattern.equals("$directory/**", ignoreCase = true) || pattern.equals("$directory/**/*", ignoreCase = true)
    }

    /** The directory part before the first wildcard: where the files the pattern names live. */
    val fixedDirectory: String
        get() {
            val wildcard = pattern.indexOfFirst { it == '*' || it == '?' }
            return (if (wildcard < 0) pattern else pattern.substring(0, wildcard)).substringBeforeLast('/', "")
        }

    val hasWildcards: Boolean get() = pattern.any { it == '*' || it == '?' }

    companion object {
        fun normalize(path: String): String = path.trim().replace('\\', '/').removePrefix("./")

        private fun toRegex(pattern: String): Regex {
            val sb = StringBuilder("^")
            var i = 0
            while (i < pattern.length) {
                when {
                    pattern.startsWith("**/", i) -> { sb.append("(?:.*/)?"); i += 3 }
                    pattern.startsWith("**", i) -> { sb.append(".*"); i += 2 }
                    pattern[i] == '*' -> { sb.append("[^/]*"); i++ }
                    pattern[i] == '?' -> { sb.append("[^/]"); i++ }
                    else -> { sb.append(Regex.escape(pattern[i].toString())); i++ }
                }
            }
            return Regex(sb.append('$').toString(), RegexOption.IGNORE_CASE)
        }
    }
}
