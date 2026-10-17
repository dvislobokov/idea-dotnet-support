package io.github.dotnetsupport.razor

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs tools/razor/razordump (the oracle, README.md there): `-Drazorpsi.razordump=<RazorDump.dll>` when given, else the Release build
 * under tools/razor/razordump; null when neither exists (the gates then skip). The dump is split into its files (`### <path>` headers).
 */
object RazorDump {
    private const val DLL_PROPERTY = "razorpsi.razordump"

    fun dll(): Path? {
        val fromProperty = System.getProperty(DLL_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val candidate = fromProperty ?: RazorTestUtil.repoRoot().resolve("tools/razor/razordump/bin/Release/net10.0/RazorDump.dll")
        return candidate.takeIf { Files.isRegularFile(it) }
    }

    /** `razordump tree <dir> --out <file>`; returns the summary line razordump prints, or throws. */
    fun tree(dir: Path, out: Path, vararg options: String): String = run("tree", dir, out, *options)

    /** `razordump codegen <dir> --out <file>` (README.md there, "Code generation"); returns the summary line, or throws. */
    fun codegen(dir: Path, out: Path, vararg options: String): String = run("codegen", dir, out, *options)

    /** `razordump tag-helpers [<dir>] --out <file>`: the descriptors the compiler discovers, as JSON. */
    fun tagHelpers(dir: Path?, out: Path, vararg options: String): String = run("tag-helpers", dir, out, *options)

    /** `razordump symbols [<dir>] --out <file>`: the symbols its producers read (the input of razor-psi's discovery). */
    fun symbols(dir: Path?, out: Path, vararg options: String): String = run("symbols", dir, out, *options)

    private fun run(mode: String, dir: Path?, out: Path, vararg options: String): String {
        val dll = dll() ?: throw IllegalStateException("razordump is not built: dotnet build tools/razor/razordump -c Release")
        val command = listOf("dotnet", dll.toString(), mode) + listOfNotNull(dir?.toString()) + listOf("--out", out.toString(), "--langversion", "latest") + options
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw IllegalStateException("razordump timed out: $command")
        }
        check(process.exitValue() == 0) { "razordump failed (${process.exitValue()}): $command\n$output" }
        return output.trim().lines().lastOrNull() ?: ""
    }

    /** The trees of a dump by file path (the text after each `### path` line, `# diagnostic` lines left out). */
    fun parse(dump: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var name: String? = null
        val body = StringBuilder()
        for (line in dump.lineSequence()) {
            if (line.startsWith("### ")) {
                if (name != null) result[name] = body.toString().trimEnd('\n')
                name = line.substring(4)
                body.setLength(0)
            } else if (!line.startsWith("# diagnostic ")) {
                body.append(line).append('\n')
            }
        }
        if (name != null) result[name] = body.toString().trimEnd('\n')
        return result
    }

    /**
     * The files of a `codegen` dump by path: everything after a `### <path>` line up to the next one — the generated code, then the
     * `### mappings` and `### diagnostics` sections, which stay in the block (they are not file headers).
     */
    fun parseCodegen(dump: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var name: String? = null
        val body = StringBuilder()
        for (line in dump.lineSequence()) {
            if (line.startsWith("### ") && line != CODEGEN_MAPPINGS && line != CODEGEN_DIAGNOSTICS) {
                if (name != null) result[name] = body.toString().trimEnd('\n')
                name = line.substring(4)
                body.setLength(0)
            } else {
                body.append(line).append('\n')
            }
        }
        if (name != null) result[name] = body.toString().trimEnd('\n')
        return result
    }

    const val CODEGEN_MAPPINGS = "### mappings"
    const val CODEGEN_DIAGNOSTICS = "### diagnostics"

    /** The diagnostics of a dump by file path: `RZ1006(10:0,10 [1] path) message` lines. */
    fun diagnostics(dump: String): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        var name: String? = null
        for (line in dump.lineSequence()) {
            if (line.startsWith("### ")) name = line.substring(4)
            else if (line.startsWith("# diagnostic ") && name != null) result.getOrPut(name) { ArrayList() }.add(line.substring("# diagnostic ".length))
        }
        return result
    }
}
