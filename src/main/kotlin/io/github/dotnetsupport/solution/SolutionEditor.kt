package io.github.dotnetsupport.solution

import java.util.UUID

/**
 * Text edits of solution files for what `dotnet sln` cannot do. Works on the text rather than on a model
 * so that everything the plugin does not understand stays untouched.
 */
object SolutionEditor {
    private const val SOLUTION_FOLDER_TYPE = "{2150E333-8FDC-42A3-9474-1A3956D46DE8}"

    /** [parentId] is the id of the parent folder as the parser reports it; null for a top-level folder. */
    fun addFolder(text: String, extension: String?, name: String, parentId: String?): String =
        if (extension.equals("slnx", ignoreCase = true)) addSlnxFolder(text, name, parentId)
        else addSlnFolder(text, name, parentId, UUID.randomUUID().toString().uppercase())

    internal fun addSlnFolder(text: String, name: String, parentId: String?, id: String): String {
        val eol = if ("\r\n" in text) "\r\n" else "\n"
        val lines = text.split(eol).toMutableList()

        val declaration = listOf("Project(\"$SOLUTION_FOLDER_TYPE\") = \"$name\", \"$name\", \"{$id}\"", "EndProject")
        val global = lines.indexOfFirst { it.trim() == "Global" }
        if (global < 0) {
            // No Global section at all (an empty or hand-written file): there is nowhere to record nesting either.
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            return (lines + declaration + "").joinToString(eol)
        }
        lines.addAll(global, declaration)

        if (parentId != null) {
            val nesting = "\t\t{$id} = {$parentId}"
            val section = lines.indexOfFirst { it.trim().startsWith("GlobalSection(NestedProjects)") }
            if (section >= 0) {
                val sectionEnd = (section until lines.size).first { lines[it].trim() == "EndGlobalSection" }
                lines.add(sectionEnd, nesting)
            } else {
                val globalEnd = lines.indexOfLast { it.trim() == "EndGlobal" }.takeIf { it >= 0 } ?: lines.size
                lines.addAll(globalEnd, listOf("\tGlobalSection(NestedProjects) = preSolution", nesting, "\tEndGlobalSection"))
            }
        }
        return lines.joinToString(eol)
    }

    /** Folder ids of slnx are their full names: `/src/libs/`. */
    internal fun addSlnxFolder(text: String, name: String, parentId: String?): String {
        val eol = if ("\r\n" in text) "\r\n" else "\n"
        val folder = "  <Folder Name=\"${parentId ?: "/"}${escapeXml(name)}/\" />$eol"
        val end = text.lastIndexOf("</Solution>")
        if (end >= 0) {
            val lineStart = text.lastIndexOf('\n', end).let { if (it >= 0 && text.substring(it + 1, end).isBlank()) it + 1 else end }
            return text.substring(0, lineStart) + folder + text.substring(lineStart)
        }
        // <Solution /> without content
        val selfClosed = Regex("<Solution\\s*/>").find(text) ?: return text
        return text.replaceRange(selfClosed.range, "<Solution>$eol$folder</Solution>")
    }

    /**
     * A project renamed: [oldPath] -> [newPath] (relative to the solution, any separators) and its display name -> [newName].
     * `.sln`: the `Project(...) = "Name", "path", "{id}"` line; `.slnx`: `Path` and `DisplayName` of the `<Project>`.
     */
    fun renameProject(text: String, extension: String?, oldPath: String, newPath: String, newName: String): String {
        val old = oldPath.replace('\\', '/').split('/').map(Regex::escape).joinToString("[\\\\/]")
        if (extension.equals("slnx", ignoreCase = true)) {
            val project = Regex("""<Project(\s[^>]*?)Path\s*=\s*"$old"([^>]*)>""", RegexOption.IGNORE_CASE)
            return project.replace(text) { match ->
                val rest = match.groupValues[2].replace(Regex("""\sDisplayName\s*=\s*"[^"]*""""), "")
                "<Project${match.groupValues[1]}Path=\"${escapeXml(newPath.replace('\\', '/'))}\"$rest>"
            }
        }
        val line = Regex("""^(\s*Project\("\{[^}]+}"\)\s*=\s*)"[^"]*"(\s*,\s*)"$old"(\s*,\s*"\{[^}]+}".*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        return line.replace(text) { match ->
            "${match.groupValues[1]}\"$newName\"${match.groupValues[2]}\"${newPath.replace('/', '\\')}\"${match.groupValues[3]}"
        }
    }

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
