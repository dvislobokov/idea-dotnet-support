package io.github.dotnetsupport.msbuild

/**
 * Text edits of project files for items the CLI has no command for. On the text, not on a model: what the plugin does
 * not understand stays untouched, and the indentation of the file is kept.
 */
object MsBuildItemEditor {
    /** `<Reference Include="Name"><HintPath>..\libs\Name.dll</HintPath></Reference>` for every dll, in a new `ItemGroup` before `</Project>`. */
    fun addAssemblyReferences(text: String, hintPaths: List<String>): String {
        val items = hintPaths.map { path ->
            val windowsPath = path.replace('/', '\\')
            val name = windowsPath.substringAfterLast('\\').removeSuffix(".dll").removeSuffix(".DLL")
            "<Reference Include=\"${escape(name)}\">" to "<HintPath>${escape(windowsPath)}</HintPath>"
        }
        return addItemGroup(text, items.flatMap { (open, hint) -> listOf(open, INDENT + hint, "</Reference>") })
    }

    /** `Include="old\path.csproj"` of a `ProjectReference` becomes [newInclude]; either separator is matched, case is ignored. */
    fun renameProjectReference(text: String, oldInclude: String, newInclude: String): String {
        val old = oldInclude.replace('\\', '/').split('/').map(Regex::escape).joinToString("[\\\\/]")
        val pattern = Regex("""(<ProjectReference\s[^>]*?Include\s*=\s*")$old(")""", RegexOption.IGNORE_CASE)
        return pattern.replace(text) { it.groupValues[1] + newInclude.replace('/', '\\') + it.groupValues[2] }
    }

    /** [lines] as a new `<ItemGroup>` before `</Project>`, indented as the file is. */
    private fun addItemGroup(text: String, lines: List<String>): String {
        val eol = if ("\r\n" in text) "\r\n" else "\n"
        val end = text.lastIndexOf("</Project>")
        if (end < 0) return text
        val unit = indentUnit(text)
        val group = buildList {
            add("$unit<ItemGroup>")
            lines.mapTo(this) { "$unit$unit$it" }
            add("$unit</ItemGroup>")
        }.joinToString(eol)
        val lineStart = text.lastIndexOf('\n', end).let { if (it >= 0 && text.substring(it + 1, end).isBlank()) it + 1 else end }
        val before = text.substring(0, lineStart)
        val separator = if (before.endsWith("$eol$eol") || before.isBlank()) "" else eol
        return before + separator + group + eol + eol + text.substring(lineStart)
    }

    /** The indentation of the first indented line of the file, or two spaces. */
    private fun indentUnit(text: String): String =
        text.lineSequence().map { line -> line.takeWhile { it == ' ' || it == '\t' } }.firstOrNull { it.isNotEmpty() } ?: INDENT

    private const val INDENT = "  "

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
