package io.github.dotnetsupport.codeanalysis

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.security.MessageDigest

/** A file a source generator has made, in the caches of the IDE: `<output of the project>/<generator assembly>/<generator type>/<hint name>`. */
data class GeneratedFile(val path: String, val generatorAssembly: String, val generatorType: String, val hintName: String)

/**
 * `generate` of CodeAnalysisHelper: what the generators of [project] made for [framework], what failed; [buildFiles]: the C# the targets of
 * its design-time build made in `obj/` (XAML, Grpc.Tools, resources — [BuildGeneratedSources]).
 */
data class GeneratedRun(
    val project: String, val framework: String?, val files: List<GeneratedFile>, val errors: List<String>, val milliseconds: Long, val workingSet: Long,
    val buildFiles: List<String> = emptyList(),
)

enum class AnalyzerSeverity { ERROR, WARNING, INFO }

/**
 * A diagnostic of a Roslyn analyzer (or of a source generator, [source] `generator`): lines and columns 0-based, columns in UTF-16 units of
 * the line, so they fit the document whatever line ends the file has on disk. [fixes]: the titles of its code fixes, when asked for.
 */
data class AnalyzerDiagnostic(
    val id: String, val severity: AnalyzerSeverity, val message: String, val path: String, val startLine: Int, val startColumn: Int, val endLine: Int,
    val endColumn: Int, val category: String?, val helpLink: String?, val source: String, val fixable: Boolean, val fixes: List<String>,
)

/** `analyze` of CodeAnalysisHelper. */
data class AnalysisRun(val project: String, val framework: String?, val diagnostics: List<AnalyzerDiagnostic>, val analyzers: Int, val milliseconds: Long, val workingSet: Long)

/** A replacement of the text from (startLine, startColumn) to (endLine, endColumn) of [path] by [text]. */
data class FixEdit(val path: String, val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int, val text: String)

/** `fix` of CodeAnalysisHelper: the edits of the code fix [title] and the files it creates. */
data class FixResult(val title: String, val edits: List<FixEdit>, val created: List<Pair<String, String>>)

/**
 * The protocol of CodeAnalysisHelper (`helpers/codeanalysis/Program.cs`) on the side of the plugin: the parameters of the requests and the
 * answers from JSON. Pure, tested on answers captured from the helper.
 */
object CodeAnalysisAnswers {
    /** The parameters every request about a project has: which project, compiled how, where the generated files of the solution go. */
    fun target(projectPath: String, configuration: String, framework: String?, outputRoot: String): JsonObject = JsonObject().apply {
        addProperty("projectPath", projectPath)
        addProperty("configuration", configuration)
        framework?.let { addProperty("targetFramework", it) }
        addProperty("outputRoot", outputRoot)
    }

    fun analyzeParams(target: JsonObject, paths: Collection<String>, excludedIds: Collection<String>, fixes: Boolean): JsonObject = target.deepCopy().apply {
        if (paths.isNotEmpty()) add("paths", strings(paths))
        if (excludedIds.isNotEmpty()) add("excludedIds", strings(excludedIds))
        addProperty("fixes", fixes)
    }

    fun fixParams(target: JsonObject, diagnostic: AnalyzerDiagnostic, title: String): JsonObject = target.deepCopy().apply {
        addProperty("path", diagnostic.path)
        addProperty("id", diagnostic.id)
        addProperty("startLine", diagnostic.startLine)
        addProperty("startColumn", diagnostic.startColumn)
        addProperty("title", title)
    }

    fun invalidateParams(paths: Collection<String>): JsonObject = JsonObject().apply { add("paths", strings(paths)) }

    fun generated(answer: JsonElement): GeneratedRun = answer.asJsonObject.let { json ->
        GeneratedRun(
            json.text("project").orEmpty(), json.text("framework"),
            json.array("files").map { it.asJsonObject }.map {
                GeneratedFile(normalize(it.text("path").orEmpty()), it.text("generatorAssembly").orEmpty(), it.text("generatorType").orEmpty(), it.text("hintName").orEmpty())
            },
            json.array("errors").mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }, json.number("milliseconds") ?: 0, json.number("workingSet") ?: 0,
            json.array("buildFiles").mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.let(::normalize) },
        )
    }

    fun analysis(answer: JsonElement): AnalysisRun = answer.asJsonObject.let { json ->
        AnalysisRun(
            json.text("project").orEmpty(), json.text("framework"), json.array("diagnostics").map { diagnostic(it.asJsonObject) },
            json.number("analyzers")?.toInt() ?: 0, json.number("milliseconds") ?: 0, json.number("workingSet") ?: 0,
        )
    }

    fun fix(answer: JsonElement): FixResult = answer.asJsonObject.let { json ->
        FixResult(
            json.text("title").orEmpty(),
            json.array("edits").map { it.asJsonObject }.map {
                FixEdit(normalize(it.text("path").orEmpty()), it.int("startLine"), it.int("startColumn"), it.int("endLine"), it.int("endColumn"), it.text("text").orEmpty())
            },
            json.array("created").map { it.asJsonObject }.map { normalize(it.text("path").orEmpty()) to it.text("text").orEmpty() },
        )
    }

    private fun diagnostic(json: JsonObject) = AnalyzerDiagnostic(
        json.text("id").orEmpty(),
        when (json.text("severity")) { "error" -> AnalyzerSeverity.ERROR; "warning" -> AnalyzerSeverity.WARNING; else -> AnalyzerSeverity.INFO },
        json.text("message").orEmpty(), normalize(json.text("path").orEmpty()), json.int("startLine"), json.int("startColumn"), json.int("endLine"), json.int("endColumn"),
        json.text("category"), json.text("helpLink"), json.text("source") ?: "analyzer", json.get("fixable")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
        json.array("fixes").mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString },
    )

    /**
     * The folder of the generated files of [projectPath] under [root]: the name of the project and 8 hex digits of SHA-256 of its path in
     * lower case with `/` — as the helper names it (`Target.OutputOf`), so that the plugin finds the folder of a referenced project too.
     */
    fun outputFolder(root: String, projectPath: String): String {
        val path = projectPath.replace('\\', '/')
        val digest = MessageDigest.getInstance("SHA-256").digest(path.lowercase().toByteArray(Charsets.UTF_8))
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        return root.trimEnd('/', '\\').replace('\\', '/') + "/" + name + "-" + digest.take(4).joinToString("") { String.format("%02x", it) }
    }

    /** `C:\a\b.cs` -> `C:/a/b.cs`, the form of the VFS. */
    fun normalize(path: String): String = path.replace('\\', '/')

    private fun strings(values: Collection<String>) = JsonArray().apply { values.forEach(::add) }
    private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.number(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    private fun JsonObject.int(name: String): Int = number(name)?.toInt() ?: 0
    private fun JsonObject.array(name: String): List<JsonElement> = get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
}

/**
 * The list of Run Code Analysis in the Build tool window: a node per severity (Errors, Warnings, Suggestions — the Info ones, whatever
 * «Show suggestions» says: there they are asked for), empty ones left out, each sorted by file and place. Pure.
 */
object CodeAnalysisReport {
    class Group(val severity: AnalyzerSeverity, val diagnostics: List<AnalyzerDiagnostic>) {
        val kind: com.intellij.build.events.MessageEvent.Kind get() = when (severity) {
            AnalyzerSeverity.ERROR -> com.intellij.build.events.MessageEvent.Kind.ERROR
            AnalyzerSeverity.WARNING -> com.intellij.build.events.MessageEvent.Kind.WARNING
            AnalyzerSeverity.INFO -> com.intellij.build.events.MessageEvent.Kind.INFO
        }
        val title: String get() = "${label(severity)} (${diagnostics.size})"
    }

    fun groups(diagnostics: List<AnalyzerDiagnostic>): List<Group> = AnalyzerSeverity.entries.mapNotNull { severity ->
        diagnostics.filter { it.severity == severity }.sortedWith(compareBy({ it.path.lowercase() }, { it.startLine }, { it.startColumn }))
            .takeIf { it.isNotEmpty() }?.let { Group(severity, it) }
    }

    fun summary(groups: List<Group>): String = AnalyzerSeverity.entries.joinToString(", ") { severity ->
        "${groups.firstOrNull { it.severity == severity }?.diagnostics?.size ?: 0} ${label(severity).lowercase()}"
    }

    private fun label(severity: AnalyzerSeverity) = when (severity) {
        AnalyzerSeverity.ERROR -> "Errors"
        AnalyzerSeverity.WARNING -> "Warnings"
        AnalyzerSeverity.INFO -> "Suggestions"
    }
}

/** Lines and columns of the helper in a text with `\n` line ends (a document of the IDE): pure, for the annotator and the fixes. */
object TextPositions {
    /** The offset of ([line], [column]) in [text]; null when the text has no such line. A column past the end of the line stops at its end. */
    fun offset(text: CharSequence, line: Int, column: Int): Int? {
        var start = 0
        repeat(line) {
            val end = text.indexOf('\n', start)
            if (end < 0) return null
            start = end + 1
        }
        var end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        if (end > start && text[end - 1] == '\r') end--
        return (start + column).coerceAtMost(end)
    }

    /** The text of [line] (0-based) of [text] without its line end, trimmed; null when there is no such line. */
    fun lineText(text: CharSequence, line: Int): String? {
        val start = offset(text, line, 0) ?: return null
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        return text.subSequence(start, end).toString().trim()
    }

    /**
     * [edits] of one file applied to [text]: from the last to the first, so the earlier positions stay valid; `\r\n` of the new text made
     * `\n`. Null when an edit points past the text (the file has changed since the helper read it).
     */
    fun apply(text: String, edits: List<FixEdit>): String? {
        val located = edits.map { edit ->
            val start = offset(text, edit.startLine, edit.startColumn) ?: return null
            val end = offset(text, edit.endLine, edit.endColumn) ?: return null
            Triple(start, maxOf(start, end), edit.text.replace("\r\n", "\n"))
        }.sortedByDescending { it.first }
        val result = StringBuilder(text)
        for ((start, end, replacement) in located) result.replace(start, end, replacement)
        return result.toString()
    }
}
