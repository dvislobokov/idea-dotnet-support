package io.github.dotnetsupport.csharp

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Shared test configuration. Values come from system properties set by the root build (`tasks.withType<Test>`). */
object CSharpTestUtil {
    private const val TEST_DATA_PATH = "csharppsi.testDataPath"
    private const val UPDATE_GOLDENS = "csharppsi.updateGoldens"
    private const val CORPUS = "csharppsi.corpus"
    private const val REPO_ROOT = "csharppsi.repoRoot"

    /** Absolute path of `<repo>/csharp-psi-core/testData`, with forward slashes (as the platform test framework expects). */
    @JvmStatic
    fun testDataPath(): String {
        val fromProperty = System.getProperty(TEST_DATA_PATH)?.takeIf { it.isNotBlank() }
        val dir = fromProperty?.let(::File) ?: findTestDataUpwards()
        return dir.absolutePath.replace(File.separatorChar, '/')
    }

    /** `<repo>/csharp-psi-core/testData/<relative>` with forward slashes. */
    @JvmStatic
    fun testDataPath(relative: String): String = testDataPath() + "/" + relative.trim('/')

    /** The repository root (idea-dotnet-support): `-Dcsharppsi.repoRoot`, set by the build, else the grandparent of `testData`. */
    @JvmStatic
    fun repoRoot(): Path =
        System.getProperty(REPO_ROOT)?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: Paths.get(testDataPath()).parent.parent

    /** True when goldens should be (re)written: `-Dcsharppsi.updateGoldens=true` (or `-P`). */
    @JvmStatic
    val updateGoldens: Boolean
        get() = System.getProperty(UPDATE_GOLDENS)?.let { it.isEmpty() || it.toBoolean() } ?: false

    /** Root of the fetched corpora (`<repo>/.corpus`: `roslyn`, `runtime`, `aspnetcore`), `-Dcsharppsi.corpus`. */
    @JvmStatic
    fun corpusRoot(): Path =
        System.getProperty(CORPUS)?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: repoRoot().resolve(".corpus")

    /**
     * `build/<sub>` of the module the tests run in (Gradle runs tests in the module directory), created if missing;
     * `<repo>/build/<sub>` when the working directory is not a module.
     */
    @JvmStatic
    fun buildDir(sub: String): Path {
        val workDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val base = if (Files.exists(workDir.resolve("build.gradle.kts"))) workDir else repoRoot()
        return Files.createDirectories(base.resolve("build").resolve(sub))
    }

    private fun findTestDataUpwards(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "csharp-psi-core/testData").takeIf { it.isDirectory } ?: File(dir, "testData")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("Cannot locate testData; set -D$TEST_DATA_PATH")
    }
}
