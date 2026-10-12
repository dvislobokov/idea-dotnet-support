package io.github.dotnetsupport.build

import java.io.File

data class MsBuildMessage(
    val isError: Boolean,
    val code: String?,
    val text: String,
    /** Absolute or project-relative path; null for messages without a location (`MSBUILD : error MSB1009: ...`). */
    val file: String?,
    /** 1-based, 0 when unknown. */
    val line: Int,
    val column: Int,
    /** Offsets of [file] inside the parsed line, for console hyperlinks. */
    val fileRange: IntRange?,
    val projectFile: String?,
) {
    /** [file] resolved against the directory of the project that reported the message. */
    fun resolveFile(): File? {
        val path = file ?: return null
        val direct = File(path)
        if (direct.isAbsolute) return direct
        return projectFile?.let { File(File(it).parentFile, path) } ?: direct
    }
}

/**
 * Canonical MSBuild diagnostic format:
 * `[N>]origin[(line[,col[,endLine,endCol]])]: [subcategory] error|warning CODE: text [project]`
 */
object MsBuildOutputParser {
    private val MESSAGE = Regex(
        """^\s*(?:\d+>)?(?<origin>.*?)(?:\((?<line>\d+)(?:,(?<col>\d+))?(?:,\d+,\d+)?\))?\s*:\s+(?:[\w .]+? )?(?<kind>error|warning)(?:\s+(?<code>[A-Za-z]+\d+))?\s*:\s*(?<text>.*?)(?:\s+\[(?<project>[^\[\]]+)])?\s*$"""
    )

    fun parseLine(line: String): MsBuildMessage? {
        val match = MESSAGE.matchEntire(line) ?: return null
        val origin = match.groups["origin"]!!
        val hasLocation = match.groups["line"] != null
        // The origin is either a file or a tool name ("MSBUILD", "CSC", "NETSDK").
        val isFile = hasLocation || origin.value.contains('/') || origin.value.contains('\\') || origin.value.contains('.')
        return MsBuildMessage(
            isError = match.groups["kind"]!!.value == "error",
            code = match.groups["code"]?.value,
            text = match.groups["text"]!!.value,
            file = origin.value.trim().takeIf { isFile && it.isNotEmpty() },
            line = match.groups["line"]?.value?.toIntOrNull() ?: 0,
            column = match.groups["col"]?.value?.toIntOrNull() ?: 0,
            fileRange = origin.range.takeIf { isFile && !it.isEmpty() },
            projectFile = match.groups["project"]?.value,
        )
    }

    /** The first [max] errors as journal lines (`Program.cs(3,5): error CS1002: ; expected`), so a failed build says why without opening the Build window. */
    fun errorSummary(messages: Collection<MsBuildMessage>, max: Int = 3): String {
        val errors = messages.filter { it.isError }
        val lines = errors.take(max).map { m ->
            val where = m.file?.let { f -> f + if (m.line > 0) "(${m.line}${if (m.column > 0) ",${m.column}" else ""})" else "" } ?: "MSBuild"
            "$where: error ${listOfNotNull(m.code, m.text).joinToString(": ")}"
        }
        return (lines + listOfNotNull("... and ${errors.size - max} more".takeIf { errors.size > max })).joinToString("\n")
    }
}
