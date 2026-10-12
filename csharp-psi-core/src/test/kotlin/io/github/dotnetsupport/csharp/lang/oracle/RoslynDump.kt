package io.github.dotnetsupport.csharp.lang.oracle

import io.github.dotnetsupport.csharp.CSharpTestUtil
import java.io.BufferedReader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

/**
 * A node or a token of a syntax tree in the oracle's model: Roslyn's `Kind()` name and its `Span` (half-open, without
 * trivia, UTF-16 offsets in the LF-normalised text). [missing]: a zero-width token inserted by Roslyn's error recovery.
 * [contextualKind]: for identifiers spelling a contextual keyword (`var`, `async`, ...), not compared by [TreeDiff].
 * [field]: the `Syntax.xml` field of the parent this child belongs to (`roslyndump --fields`), null without the option.
 * The same model is produced from PSI by [PsiToDump].
 */
data class DumpNode(
    val kind: String,
    val start: Int,
    val end: Int,
    val children: List<DumpNode> = emptyList(),
    val isToken: Boolean = false,
    val missing: Boolean = false,
    val contextualKind: String? = null,
    val field: String? = null,
) {
    /** Number of nodes and tokens in this subtree. */
    fun count(): Int = 1 + children.sumOf { it.count() }

    /** The subtree in the `roslyndump` line format (`N`/`T` records), for messages and goldens. */
    fun format(depth: Int = 0): String = buildString { appendTo(this, depth) }

    private fun appendTo(sb: StringBuilder, depth: Int) {
        sb.append(if (isToken) "T " else "N ").append(depth).append(' ').append(kind).append(' ').append(start)
            .append(' ').append(end)
        if (missing) sb.append(" missing")
        contextualKind?.let { sb.append(" ck=").append(it) }
        field?.let { sb.append(" f=").append(it) }
        sb.append('\n')
        children.forEach { it.appendTo(sb, depth + 1) }
    }

    override fun toString() = "($kind $start-$end${if (missing) " missing" else ""})"
}

/**
 * A `V` record: trivia. In `tree` dumps structured trivia (directives, skipped tokens, doc comments) has a [structure].
 * [inner]: the `SkippedTokensTrivia` inside a doc comment (trivia of its XML tokens), written after its structure.
 */
data class DumpTrivia(
    val kind: String,
    val start: Int,
    val end: Int,
    val structure: DumpNode? = null,
    val inner: List<DumpTrivia> = emptyList(),
)

/** A `D` record: a parser error of Roslyn. */
data class DumpDiagnostic(val id: String, val start: Int, val end: Int, val message: String)

/**
 * One file of a dump. [roots]: for `tree`/`expr`/`stmt` the depth-0 nodes (one `CompilationUnit` for `tree`); for
 * `tokens` the flat token list. [trivia] in text order, [diagnostics] in Roslyn's order.
 */
data class DumpFile(
    val path: String,
    val roots: List<DumpNode>,
    val trivia: List<DumpTrivia>,
    val diagnostics: List<DumpDiagnostic>,
) {
    /** The single root of a `tree` dump. */
    val root: DumpNode get() = roots.single()

    fun nodeCount(): Long = roots.sumOf { it.count().toLong() }
}

/**
 * The oracle `tools/csharp-psi/roslyndump` (format: `tools/csharp-psi/roslyndump/README.md`): builds it when needed, runs a command over a
 * file or a directory and parses its output.
 *
 * The dll is `-Dcsharppsi.roslyndump=<path to RoslynDump.dll>` when given (used as is), otherwise
 * `tools/csharp-psi/roslyndump/bin/Release/net10.0/RoslynDump.dll`, rebuilt with `dotnet build -c Release` when it is missing or
 * older than any source of the tool. `dotnet` must be on `PATH`.
 */
object RoslynDump {
    private const val DLL_PROPERTY = "csharppsi.roslyndump"

    /**
     * Timeouts of one `roslyndump` run in minutes, over a directory and over a single file or snippet
     * (`-Pcsharppsi.roslyndump.timeoutMinutes=`, `-Pcsharppsi.roslyndump.fileTimeoutMinutes=`). Roslyn itself can
     * hang (docs/csharp-psi/GRAMMAR.md, "Doc comments": `/// <see cref="A\uFFFFB"/>`): the process is killed and the run fails.
     */
    private val dirTimeoutMinutes = System.getProperty("csharppsi.roslyndump.timeoutMinutes")?.toLongOrNull() ?: 10
    private val fileTimeoutMinutes = System.getProperty("csharppsi.roslyndump.fileTimeoutMinutes")?.toLongOrNull() ?: 2
    private val lock = Any()
    @Volatile private var checkedDll: Path? = null

    /** Output of one run: the summary line `# files= nodes= ...` (or the whole stderr when it is missing). */
    class Run(val summary: String, val files: List<DumpFile>)

    /** The path of `RoslynDump.dll`, built if it is missing or stale; checked once per JVM. */
    fun dll(): Path {
        checkedDll?.let { return it }
        synchronized(lock) {
            checkedDll?.let { return it }
            System.getProperty(DLL_PROPERTY)?.takeIf { it.isNotBlank() }?.let { custom ->
                val path = Paths.get(custom)
                check(Files.isRegularFile(path)) { "-D$DLL_PROPERTY=$custom: no such file" }
                checkedDll = path
                return path
            }
            val project = CSharpTestUtil.repoRoot().resolve("tools/csharp-psi/roslyndump")
            val dll = project.resolve("bin/Release/net10.0/RoslynDump.dll")
            if (isStale(project, dll)) {
                val (code, output) = runProcess(
                    listOf("dotnet", "build", project.toString(), "-c", "Release", "-nologo", "-v", "q"),
                    CSharpTestUtil.repoRoot(),
                    timeoutSeconds = 10 * 60,
                )
                check(code == 0 && Files.isRegularFile(dll)) { "dotnet build tools/csharp-psi/roslyndump failed ($code):\n$output" }
            }
            checkedDll = dll
            return dll
        }
    }

    private fun isStale(project: Path, dll: Path): Boolean {
        if (!Files.isRegularFile(dll)) return true
        val built = dll.getLastModifiedTime()
        return Files.walk(project).use { paths ->
            paths.filter { p ->
                p.isRegularFile() && p.extension in setOf("cs", "csproj", "props", "targets") &&
                    project.relativize(p).none { it.toString() == "bin" || it.toString() == "obj" }
            }.anyMatch { it.getLastModifiedTime() > built }
        }
    }

    /**
     * Runs `roslyndump <command> <target> [--define ...] [--langversion v] [--include @list] --out <out>`; returns the
     * summary line from stderr. Fails with the tool's output on a non-zero exit code. [langVersion] is a `LangVersion`
     * string (`7.3`, `preview`; null: the tool's default, `preview`); [includes] restricts a directory to these relative
     * paths (files or directories; empty: everything).
     */
    fun runToFile(
        command: String,
        target: Path,
        out: Path,
        defines: List<String> = emptyList(),
        langVersion: String? = null,
        includes: List<String> = emptyList(),
        fields: Boolean = false,
    ): String {
        val list = if (includes.isEmpty()) null else Files.createTempFile("roslyndump-include", ".txt").also { Files.write(it, includes) }
        try {
            val args = options(command, target, defines, langVersion, list, fields)
            args += listOf("--out", out.toAbsolutePath().toString())
            val timeout = if (Files.isDirectory(target)) dirTimeoutMinutes else fileTimeoutMinutes
            val (code, output) = runProcess(args, CSharpTestUtil.repoRoot(), timeout * 60)
            check(code == 0) { "roslyndump $command $target failed ($code):\n$output" }
            return output.lines().lastOrNull { it.startsWith("# files=") } ?: output.trim()
        } finally {
            list?.let { Files.deleteIfExists(it) }
        }
    }

    /** The command line of `roslyndump` without `--out` (the dump goes to stdout). */
    fun options(
        command: String,
        target: Path,
        defines: List<String> = emptyList(),
        langVersion: String? = null,
        includeList: Path? = null,
        fields: Boolean = false,
    ): MutableList<String> {
        val args = mutableListOf("dotnet", dll().toString(), command, target.toAbsolutePath().toString())
        if (fields) args += "--fields"
        if (defines.isNotEmpty()) args += listOf("--define", defines.joinToString(";"))
        if (langVersion != null) args += listOf("--langversion", langVersion)
        if (includeList != null) args += listOf("--include", "@" + includeList.toAbsolutePath())
        return args
    }

    /** Runs [command] over [target] into a temporary file and parses all of it (small inputs; see [forEachFile]). */
    fun run(
        command: String,
        target: Path,
        defines: List<String> = emptyList(),
        langVersion: String? = null,
        includes: List<String> = emptyList(),
        fields: Boolean = false,
    ): Run {
        val out = Files.createTempFile("roslyndump", ".txt")
        try {
            val summary = runToFile(command, target, out, defines, langVersion, includes, fields)
            return Run(summary, read(out))
        } finally {
            Files.deleteIfExists(out)
        }
    }

    /** Parses a dump file into memory. */
    fun read(dump: Path): List<DumpFile> = ArrayList<DumpFile>().also { list -> forEachFile(dump) { list += it } }

    /** Streams the files of a dump one by one: corpus dumps have millions of nodes. */
    fun forEachFile(dump: Path, action: (DumpFile) -> Unit) {
        Files.newBufferedReader(dump, StandardCharsets.UTF_8).use { parse(it, action) }
    }

    /** Parses dump text (tests). */
    fun parse(text: String): List<DumpFile> =
        ArrayList<DumpFile>().also { list -> parse(text.reader().buffered()) { list += it } }

    fun parse(reader: BufferedReader, action: (DumpFile) -> Unit) {
        var current: FileBuilder? = null
        var lineNo = 0
        while (true) {
            val line = reader.readLine() ?: break
            lineNo++
            if (line.isEmpty() || line.startsWith("#")) continue
            try {
                if (line.startsWith("file ")) {
                    current?.let { action(it.build()) }
                    current = FileBuilder(line.substring(5))
                } else {
                    (current ?: error("record before the first `file` line")).record(line)
                }
            } catch (e: RuntimeException) {
                throw IllegalStateException("roslyndump output, line $lineNo: ${e.message}: $line", e)
            }
        }
        current?.let { action(it.build()) }
    }

    /** Assembles one file from its records; `children` lists are the builder's [ArrayList]s, filled in place. */
    private class FileBuilder(val path: String) {
        private val roots = ArrayList<DumpNode>()
        private val trivia = ArrayList<DumpTrivia>()
        private val diagnostics = ArrayList<DumpDiagnostic>()
        private val stack = ArrayList<DumpNode>()

        // A `V` record whose structure (`N`/`T` records from depth 1) may follow.
        private var pendingTrivia: DumpTrivia? = null
        private var pendingStructure: DumpNode? = null
        private val pendingInner = ArrayList<DumpTrivia>()

        fun record(line: String) {
            val f = line.split(' ')
            when (f[0]) {
                "N" -> {
                    var field: String? = null
                    for (extra in f.drop(5)) {
                        if (extra.startsWith("f=")) field = extra.substring(2) else error("unknown node field `$extra`")
                    }
                    add(DumpNode(f[2], f[3].toInt(), f[4].toInt(), ArrayList(), field = field), f[1].toInt())
                }
                "T" -> {
                    // `tree` writes the depth, `tokens` does not.
                    val depth = f[1].toIntOrNull()
                    val k = if (depth == null) 1 else 2
                    var missing = false
                    var ck: String? = null
                    var field: String? = null
                    for (extra in f.drop(k + 3)) {
                        when {
                            extra == "missing" -> missing = true
                            extra.startsWith("ck=") -> ck = extra.substring(3)
                            extra.startsWith("f=") -> field = extra.substring(2)
                            else -> error("unknown token field `$extra`")
                        }
                    }
                    val token = DumpNode(f[k], f[k + 1].toInt(), f[k + 2].toInt(), emptyList(), true, missing, ck, field)
                    if (depth == null) {
                        flushTrivia()
                        roots += token
                    } else {
                        add(token, depth)
                    }
                }
                "V" -> {
                    val v = DumpTrivia(f[1], f[2].toInt(), f[3].toInt())
                    val doc = pendingTrivia?.takeIf { it.kind.endsWith("DocumentationCommentTrivia") }
                    if (doc != null && v.kind == "SkippedTokensTrivia" && v.start >= doc.start && v.end <= doc.end) {
                        pendingInner += v
                    } else {
                        flushTrivia()
                        pendingTrivia = v
                    }
                }
                "D" -> {
                    flushTrivia()
                    val d = line.split(' ', limit = 5)
                    diagnostics += DumpDiagnostic(d[1], d[2].toInt(), d[3].toInt(), d.getOrElse(4) { "" })
                }
                else -> error("unknown record")
            }
        }

        private fun add(node: DumpNode, depth: Int) {
            // Structure of trivia starts at depth 1, the main tree at depth 0.
            val inTrivia = pendingTrivia != null
            val level = depth - (if (inTrivia) 1 else 0)
            require(level >= 0 && level <= stack.size) { "depth $depth does not follow the previous record" }
            while (stack.size > level) stack.removeAt(stack.size - 1)
            if (level == 0) {
                if (inTrivia) {
                    require(pendingStructure == null) { "second structure root for one trivia" }
                    pendingStructure = node
                } else {
                    roots += node
                }
            } else {
                val parent = stack[level - 1]
                require(!parent.isToken) { "a token has no children" }
                (parent.children as MutableList<DumpNode>) += node
            }
            stack += node
        }

        private fun flushTrivia() {
            pendingTrivia?.let { trivia += it.copy(structure = pendingStructure, inner = pendingInner.toList()) }
            pendingTrivia = null
            pendingStructure = null
            pendingInner.clear()
            stack.clear()
        }

        fun build(): DumpFile {
            flushTrivia()
            return DumpFile(path, roots, trivia, diagnostics)
        }
    }

    /**
     * Runs a process with stdout and stderr merged; fails clearly when the binary is not on `PATH`, and when it runs
     * longer than [timeoutSeconds]: the process and its children are killed (the output goes to a temporary file, so
     * waiting does not depend on reading it).
     */
    fun runProcess(command: List<String>, workDir: Path, timeoutSeconds: Long = 30 * 60): Pair<Int, String> {
        val log = Files.createTempFile("roslyndump-process", ".log")
        try {
            val process = try {
                ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start()
            } catch (e: IOException) {
                throw IllegalStateException("Cannot run ${command.first()} (is it on PATH?): $e", e)
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(30, TimeUnit.SECONDS)
                val tail = Files.readString(log).takeLast(4000)
                throw IllegalStateException("Timed out after $timeoutSeconds s, killed: $command\n$tail")
            }
            return process.exitValue() to Files.readString(log)
        } finally {
            Files.deleteIfExists(log)
        }
    }
}
