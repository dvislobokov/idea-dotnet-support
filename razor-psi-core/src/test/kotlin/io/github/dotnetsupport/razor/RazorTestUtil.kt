package io.github.dotnetsupport.razor

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Paths of the razor-psi tests: the test data, the corpus (`.corpus/razor`), the build directory; as csharp-psi's CSharpTestUtil. */
object RazorTestUtil {
    private const val TEST_DATA_PATH = "razorpsi.testDataPath"
    private const val UPDATE_GOLDENS = "razorpsi.updateGoldens"
    private const val CORPUS = "razorpsi.corpus"
    private const val REPO_ROOT = "razorpsi.repoRoot"

    fun testDataPath(): String {
        val fromProperty = System.getProperty(TEST_DATA_PATH)?.takeIf { it.isNotBlank() }
        val dir = fromProperty?.let(::File) ?: findUpwards("razor-psi-core/testData")
        return dir.absolutePath
    }

    fun testDataPath(relative: String): String = testDataPath() + "/" + relative.trim('/')

    fun repoRoot(): Path = System.getProperty(REPO_ROOT)?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: findUpwards("gradle.properties").parentFile.toPath()

    val updateGoldens: Boolean get() = System.getProperty(UPDATE_GOLDENS)?.toBoolean() ?: false

    fun corpusRoot(): Path = System.getProperty(CORPUS)?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: repoRoot().resolve(".corpus")

    /** The Razor compiler of the corpus (tools/razor/fetch-corpus.sh), or null when it is not checked out. */
    fun razorCompiler(): Path? = corpusRoot().resolve("razor/razor-compiler/src/Razor/src/Compiler").takeIf { Files.isDirectory(it) }

    fun buildDir(sub: String): Path {
        val workDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val base = if (Files.exists(workDir.resolve("build.gradle.kts"))) workDir else repoRoot().resolve("razor-psi-core")
        return base.resolve("build").resolve(sub).also { Files.createDirectories(it) }
    }

    /** A file's text as .NET's `File.ReadAllText` reads it (what razordump parses): the BOM picks UTF-8 or UTF-16, malformed bytes are replaced. */
    fun readText(file: Path): String {
        val bytes = Files.readAllBytes(file)
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    private fun findUpwards(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw IllegalStateException("$relative not found above ${System.getProperty("user.dir")}")
    }
}

/** Metrics that may only improve (`testData/metrics/<name>.json`), as csharp-psi's CorpusMetrics: missing file — written; `updateGoldens` — rewritten. */
object RazorMetrics {
    fun check(file: Path, values: Map<String, Long>, higherIsBetter: Set<String>, lowerIsBetter: Set<String>) {
        val current = values.entries.joinToString(",\n", "{\n", "\n}\n") { "  \"${it.key}\": ${it.value}" }
        if (!Files.exists(file) || RazorTestUtil.updateGoldens) {
            Files.createDirectories(file.parent)
            Files.writeString(file, current)
            println("  metrics: written to $file")
            return
        }
        val recorded = Regex("\"(\\w+)\":\\s*(-?\\d+)").findAll(Files.readString(file)).associate { it.groupValues[1] to it.groupValues[2].toLong() }
        val failures = ArrayList<String>()
        for ((key, value) in values) {
            val old = recorded[key] ?: continue
            if (key in higherIsBetter && value < old) failures += "$key fell from $old to $value"
            if (key in lowerIsBetter && value > old) failures += "$key rose from $old to $value"
        }
        val improved = values.any { (k, v) -> recorded[k]?.let { (k in higherIsBetter && v > it) || (k in lowerIsBetter && v < it) } == true }
        if (failures.isNotEmpty()) throw AssertionError("metrics of $file worsened: ${failures.joinToString("; ")}")
        if (improved) {
            Files.writeString(file, current)
            println("  metrics: improved, $file rewritten")
        }
    }
}
