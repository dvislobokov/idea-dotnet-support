package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// The whole-file tree gates of step 6, one class per corpus so that `--tests '*RuntimeTreeCorpusTest*'` runs one of
// them. Rules, buckets and properties: TreeCorpusGate and docs/csharp-psi/TESTING.md, "Tree gates".

/** Roslyn's own sources (`.corpus/roslyn/src`, the sparse checkout of tools/csharp-psi/fetch-roslyn.sh). */
class RoslynSrcTreeCorpusTest : TreeCorpusTestBase() {
    override val gateName = "roslyn-src"
    override fun corpusDir(): Path = CSharpTestUtil.corpusRoot().resolve("roslyn/src")
    fun testTrees() = runGate()
}

/** dotnet/runtime at `runtimeTag` (`.corpus/runtime/src`). */
class RuntimeTreeCorpusTest : TreeCorpusTestBase() {
    override val gateName = "runtime"
    override fun corpusDir(): Path = CSharpTestUtil.corpusRoot().resolve("runtime/src")
    fun testTrees() = runGate()
}

/** dotnet/aspnetcore at `aspnetcoreTag` (`.corpus/aspnetcore/src`). */
class AspnetcoreTreeCorpusTest : TreeCorpusTestBase() {
    override val gateName = "aspnetcore"
    override fun corpusDir(): Path = CSharpTestUtil.corpusRoot().resolve("aspnetcore/src")
    fun testTrees() = runGate()
}

/**
 * The playground of the plugin (`debug-playground` of the repository, or
 * `-Dcsharppsi.playground=<dir>`); skipped when missing. It changes with the user's experiments, so its metrics file
 * may need a deliberate reset when files are added (docs/csharp-psi/TESTING.md).
 */
class PlaygroundTreeCorpusTest : TreeCorpusTestBase() {
    override val gateName = "playground"
    override fun corpusDir(): Path =
        System.getProperty("csharppsi.playground")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: CSharpTestUtil.repoRoot().resolve("debug-playground")
    fun testTrees() = runGate()
}

/**
 * C# 7.3, the default language version of .NET Framework projects, on real code: the libraries of
 * `runtime/src/libraries` whose project file in `src` targets .NET Framework (`$(NetFrameworkMinimum)`, `net46x`..`net48x`),
 * both sides at `--langversion 7.3`. Those libraries are compiled with a newer `LangVersion`, so files that use newer
 * syntax have Roslyn errors at 7.3 and land in the invalid bucket (with its normalisation); files valid at 7.3 must
 * match exactly.
 */
class RuntimeNetFrameworkCSharp73TreeCorpusTest : TreeCorpusTestBase() {
    override val gateName = "runtime-netfx-cs7.3"
    override val langVersion = "7.3"
    override val languageVersion = CSharpLanguageVersion.CSharp7_3
    override fun corpusDir(): Path = CSharpTestUtil.corpusRoot().resolve("runtime/src/libraries")
    override fun includes(): List<String> = netFrameworkLibraries(corpusDir())
    fun testTrees() = runGate()

    companion object {
        private val netFramework = Regex("NetFrameworkMinimum|net4[6-8]")

        /** `<Library>/src` of every library with a project file in `src` targeting .NET Framework, sorted. */
        fun netFrameworkLibraries(libraries: Path): List<String> {
            if (!Files.isDirectory(libraries)) return emptyList()
            return Files.list(libraries).use { dirs ->
                dirs.filter { dir ->
                    val src = dir.resolve("src")
                    Files.isDirectory(src) && Files.list(src).use { files ->
                        files.anyMatch { it.fileName.toString().endsWith(".csproj") && netFramework.containsMatchIn(Files.readString(it)) }
                    }
                }.map { "${it.fileName}/src" }.sorted().toList()
            }
        }
    }
}
