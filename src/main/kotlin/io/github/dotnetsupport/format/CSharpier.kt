package io.github.dotnetsupport.format

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One way to run CSharpier. The command line was redone in 1.0, and calling one generation in the style of the other
 * fails (0.x looks for a file named `format`, 1.x prints its help), so the version decides the syntax:
 *
 * | | 1.x | 0.x |
 * |---|---|---|
 * | format | `csharpier format <paths>` | `dotnet-csharpier <paths>` |
 * | check | `csharpier check <paths>` | `--check <paths>` |
 * | server | `csharpier server` | `--server` |
 * | where a piped file lives | `--stdin-path <file>` | not available: the working directory decides |
 */
class CSharpierCli(
    /** `[csharpier.exe]`, or `[dotnet, csharpier]` for a tool of the repository's manifest. */
    val launcher: List<String>,
    val version: String,
    /** Where `dotnet csharpier` must be started from to find its manifest; null for a global tool. */
    val manifestDirectory: File?,
) {
    val isLegacy: Boolean get() = version.substringBefore('.').toIntOrNull() == 0
    val isLocal: Boolean get() = manifestDirectory != null

    /** "CSharpier 1.2.5 (dotnet-tools.json)" */
    val description: String get() = "CSharpier $version" + if (isLocal) " (tool manifest of the repository)" else " (global tool)"

    /** Formats the text piped to stdin; the configuration (`.csharpierrc`, `.editorconfig`) is looked up from [file]. */
    fun formatStdin(file: File): GeneralCommandLine =
        commandLine(if (isLegacy) emptyList() else listOf("format", "--stdin-path", file.path), file.parentFile)

    fun server(workDirectory: File?): GeneralCommandLine = commandLine(listOf(if (isLegacy) "--server" else "server"), workDirectory)

    fun format(target: File): GeneralCommandLine = commandLine((if (isLegacy) emptyList() else listOf("format")) + target.path, directoryOf(target))

    fun check(target: File): GeneralCommandLine = commandLine(listOf(if (isLegacy) "--check" else "check", target.path), directoryOf(target))

    private fun directoryOf(target: File) = if (target.isDirectory) target else target.parentFile

    private fun commandLine(arguments: List<String>, workDirectory: File?): GeneralCommandLine =
        GeneralCommandLine(launcher + arguments)
            // a local tool is resolved through the manifest above the working directory; files below it are all inside
            // a file that is not on disk (an in-memory or remote one) has no directory to start in
            .withWorkDirectory((workDirectory ?: manifestDirectory)?.takeIf { it.isDirectory })
            .withCharset(Charsets.UTF_8)
            .withEnvironment("DOTNET_NOLOGO", "1")

    override fun equals(other: Any?): Boolean = other is CSharpierCli && other.launcher == launcher && other.version == version && other.manifestDirectory == manifestDirectory
    override fun hashCode(): Int = launcher.hashCode() * 31 + version.hashCode()
}

/** Why CSharpier cannot be used right now, in words for the user. */
class CSharpierUnavailable(message: String, val canRestore: Boolean = false, val manifestDirectory: File? = null) : Exception(message)

object CSharpierLocator {
    private val MANIFESTS = listOf("dotnet-tools.json", ".config/dotnet-tools.json")
    private val CONFIGS = listOf(".csharpierrc", ".csharpierrc.json", ".csharpierrc.yaml", ".csharpierrc.yml")
    private val versions = ConcurrentHashMap<String, String>()

    /** The `csharpier` entry of the nearest tool manifest at or above [directory]: the manifest's directory and the version. */
    fun manifestEntry(directory: File?): Pair<File, String>? {
        var current = directory
        while (current != null) {
            for (name in MANIFESTS) {
                val manifest = File(current, name)
                if (!manifest.isFile) continue
                // SDK 10 writes the manifest next to the sources, older ones into .config: the tool is resolved from `current` either way
                parseManifest(runCatching { manifest.readText() }.getOrDefault(""))?.let { return current!! to it }
            }
            current = current.parentFile
        }
        return null
    }

    /** `{ "tools": { "csharpier": { "version": "1.2.5", ... } } }`; the package id is case-insensitive. */
    fun parseManifest(json: String): String? {
        val tools = runCatching { (JsonParser.parseString(json) as? JsonObject)?.get("tools") as? JsonObject }.getOrNull() ?: return null
        val entry = tools.entrySet().firstOrNull { it.key.equals("csharpier", ignoreCase = true) }?.value as? JsonObject ?: return null
        return entry.get("version")?.takeIf { it.isJsonPrimitive }?.asString
    }

    /** True when the repository is formatted with CSharpier: its own configuration file, or the tool in the manifest. */
    fun isUsedBy(directory: File?, projectFileText: String? = null): Boolean {
        if (projectFileText != null && "CSharpier.MsBuild" in projectFileText) return true
        if (manifestEntry(directory) != null) return true
        return generateSequence(directory) { it.parentFile }.any { dir -> CONFIGS.any { File(dir, it).isFile } }
    }

    /**
     * The tool of the repository when its manifest names one (the same version the CI uses), otherwise the global one.
     * Blocking: the version of a global tool is asked from the tool itself, once per executable.
     */
    @Throws(CSharpierUnavailable::class)
    fun find(directory: File?): CSharpierCli {
        manifestEntry(directory)?.let { (manifestDirectory, version) ->
            val dotnet = DotNetCli.findExecutable() ?: throw CSharpierUnavailable("The 'dotnet' executable is not found.")
            return CSharpierCli(listOf(dotnet, "csharpier"), version, manifestDirectory)
        }
        val executable = DotNetTool.CSHARPIER.find()
            ?: throw CSharpierUnavailable("CSharpier is not installed: neither in a tool manifest of the repository nor as a global tool.")
        return CSharpierCli(listOf(executable.path), versionOf(executable), null)
    }

    private fun versionOf(executable: File): String = versions.computeIfAbsent(executable.path + "@" + executable.lastModified()) {
        val output = runCatching { CapturingProcessHandler(GeneralCommandLine(executable.path, "--version").withCharset(Charsets.UTF_8)).runProcess(20_000).stdout }.getOrDefault("")
        parseVersion(output) ?: if (executable.nameWithoutExtension.startsWith("dotnet-")) "0.0" else "1.0"
    }

    /** `1.3.0`, and `0.30.6+abcdef` with a commit suffix. */
    fun parseVersion(output: String): String? = Regex("""\d+\.\d+(\.\d+)?""").find(output)?.value
}

/** What CSharpier said about a file. */
sealed class FormatResult {
    class Formatted(val text: String) : FormatResult()

    /** The file is fine as it is, is ignored (`.csharpierignore`) or is not of a supported type. */
    object Unchanged : FormatResult()

    class Failed(val message: String) : FormatResult()
}

object CSharpierOutput {
    /** `{"formattedFile": "...", "status": "Formatted" | "Failed" | "UnsupportedFile" | "Ignored", "errorMessage": null}` */
    fun parseServerResponse(json: String): FormatResult {
        val root = runCatching { JsonParser.parseString(json) as? JsonObject }.getOrNull() ?: return FormatResult.Failed("Unexpected answer of the CSharpier server.")
        fun string(name: String) = root.get(name)?.takeIf { it.isJsonPrimitive }?.asString
        return when (string("status")) {
            "Formatted" -> string("formattedFile")?.let { FormatResult.Formatted(it) } ?: FormatResult.Unchanged
            "Failed" -> FormatResult.Failed(string("errorMessage") ?: "CSharpier could not format the file.")
            else -> FormatResult.Unchanged
        }
    }

    /**
     * A one-shot run: formatted text on stdout, or exit code 1 and on stderr
     * ```
     * Error C:\src\A.cs - Was not formatted due to syntax errors.
     *   (1,19): error CS1026: ) expected
     * ```
     * Empty output with exit code 0 means "nothing to do": an ignored or unsupported file.
     */
    fun parseProcessOutput(exitCode: Int, stdout: String, stderr: String): FormatResult = when {
        exitCode == 0 && stdout.isNotEmpty() -> FormatResult.Formatted(stdout)
        exitCode == 0 -> FormatResult.Unchanged
        RESTORE_HINT in stderr || RESTORE_HINT in stdout -> FormatResult.Failed(RESTORE_NEEDED)
        else -> FormatResult.Failed(describeFailure(stderr.ifBlank { stdout }))
    }

    /** "Was not formatted due to syntax errors: (1,19) error CS1026: ) expected" — without the path, which the user knows. */
    fun describeFailure(output: String): String {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val headline = lines.firstOrNull()?.substringAfter(" - ", lines.first())?.trimEnd('.') ?: return "CSharpier failed."
        val details = lines.drop(1).filter { DIAGNOSTIC.containsMatchIn(it) }.take(3).joinToString("; ")
        return if (details.isEmpty()) headline else "$headline: $details"
    }

    /** `(1,19): error CS1026: ...` -> line and column, 1-based as printed. */
    fun firstErrorPosition(message: String): Pair<Int, Int>? =
        DIAGNOSTIC.find(message)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    const val RESTORE_HINT = "dotnet tool restore"
    const val RESTORE_NEEDED = "CSharpier is listed in the tool manifest of the repository but is not restored."
    private val DIAGNOSTIC = Regex("""\((\d+),(\d+)\):? """)
}
