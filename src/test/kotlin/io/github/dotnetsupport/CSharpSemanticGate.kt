package io.github.dotnetsupport

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpParseOptions
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.index.IndexerTool
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The semantic gate (CSHARP_PSI_MIGRATION.md, step 11, task C0): a resolver of C# names and types against Roslyn on real code, per layer
 * (11a names, 11b types, 11e diagnostics) and per category ([SemanticComparison]). The oracle is `roslyndump semantics` (it runs `dotnet`,
 * so this is not a test of `test`): `./gradlew semanticGate`. Today the resolver is the syntactic baseline [SyntacticSemanticModel]; the
 * native semantic model of csharp-psi-semantic takes its place as layers land.
 *
 * Inputs: projects of debug-playground (project mode: the compilation MSBuild would make, project references from source) and libraries
 * of the corpus (`.corpus/runtime/src/libraries/<Library>/src` compiled together against the newest reference pack of the SDK). Each
 * input's sources are put into the light project with the parse options of the dump (`#if` symbols, language version), every dumped
 * file is compared, the sources are removed again.
 *
 * Options (`-PsemanticGate.<name>=`): `projects` (`Console/Console.csproj,Web/Web.csproj` of the playground), `libraries`
 * (`System.Linq,System.Threading.Channels,Microsoft.Extensions.Primitives`; `none` for none), `corpus` (`.corpus` of the repository or
 * `~/csharp-psi/.corpus`), `out` (`build/semantic-gate`: the dumps, `report.txt` with examples), `examples` per category (10), `reuse=true`
 * (dumps already in `out` are not made again), `roslyndump` (a built `RoslynDump.dll`), `dotnet`.
 *
 * Baseline: `src/test/resources/semanticGate/baseline.txt`, correct answers per input and category; may only improve (a regression fails
 * the gate, an improvement rewrites the file, committed with the change).
 */
class CSharpSemanticGate : BasePlatformTestCase() {
    private fun option(name: String): String? = System.getProperty("semanticGate.$name")?.takeIf { it.isNotBlank() }

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
    }

    override fun tearDown() {
        try {
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private class Input(val name: String, val args: List<String>)

    fun testAgainstRoslyn() {
        val repo = File(option("repoRoot") ?: ".").absoluteFile
        val out = File(option("out") ?: File(repo, "build/semantic-gate").path).absoluteFile.also { it.mkdirs() }
        val examples = option("examples")?.toInt() ?: 10
        val corpus = option("corpus")?.let(::File)
            ?: listOf(File(repo, ".corpus"), File(repo.parentFile, "csharp-psi/.corpus"), File(System.getProperty("user.home"), "csharp-psi/.corpus")).firstOrNull { it.isDirectory }
        val dll = roslynDump(repo)

        val inputs = ArrayList<Input>()
        val playground = File(repo, "debug-playground")
        for (project in (option("projects") ?: "Console/Console.csproj,Web/Web.csproj").split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "none" }) {
            val file = File(playground, project)
            if (file.isFile) inputs += Input("playground-" + file.nameWithoutExtension, listOf(file.path, "--root", playground.path))
            else println("semantic gate: no project $file")
        }
        val libraries = File(corpus ?: File("-"), "runtime/src/libraries")
        for (library in (option("libraries") ?: "System.Linq,System.Threading.Channels,Microsoft.Extensions.Primitives").split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "none" }) {
            val src = File(libraries, "$library/src")
            if (src.isDirectory) inputs += Input("runtime-$library", listOf(src.path, "--assembly", library, "--root", libraries.path, "--define", RUNTIME_DEFINES))
            else println("semantic gate: no corpus library $src (tools/csharp-psi/fetch-corpus.sh)")
        }
        check(inputs.isNotEmpty()) { "semantic gate: no inputs" }

        val report = StringBuilder()
        val details = StringBuilder()
        val metrics = LinkedHashMap<String, Int>()
        val total = SemanticComparison(examples)
        for ((index, input) in inputs.withIndex()) {
            val dumpFile = File(out, "${input.name}.txt")
            if (option("reuse") != "true" || !dumpFile.isFile) runDump(dll, input.args + listOf("--out", dumpFile.path))
            val dump = SemanticDump.read(dumpFile)
            val comparison = SemanticComparison(examples)
            val assemblies = indexAssemblies(repo, out, dump.header.referencePaths)
            CSharpSemanticEnvironment.setAssembliesForTests { assemblies }
            val started = System.currentTimeMillis()
            try {
                compare(dump, File(input.args[input.args.indexOf("--root") + 1]), "g$index", listOf(comparison, total))
            } finally {
                CSharpSemanticEnvironment.setAssembliesForTests(null)
            }
            val text = comparison.report("${input.name} (${dump.header.assembly}, C# ${dump.header.languageVersion}, ${(System.currentTimeMillis() - started) / 1000} s)")
            println(text)
            report.append(text).append('\n')
            details.append("==== ${input.name}\n").append(comparison.examples())
            comparison.metrics().forEach { (key, value) -> metrics["${input.name}.$key"] = value }
        }
        val summary = total.report("all inputs")
        println(summary)
        report.append(summary)
        File(out, "report.txt").writeText(report.toString() + "\n\nExamples (wrong and unresolved, first $examples per category)\n" + details)
        println("report: ${File(out, "report.txt")}")
        checkBaseline(File(repo, "src/test/resources/semanticGate/baseline.txt"), metrics)
    }

    /** Puts the dump's sources under [dir] of the light project, compares every dumped file, removes them. */
    private fun compare(dump: SemanticDump, root: File, dir: String, comparisons: List<SemanticComparison>) {
        val base = myFixture.tempDirFixture.findOrCreateDir(dir)
        try {
            val files = HashMap<String, VirtualFile>()
            for (source in dump.header.sources) {
                if (source.path.startsWith("../")) continue
                val disk = File(root, source.path)
                if (!disk.isFile) continue
                val text = disk.readText().removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
                val file = myFixture.tempDirFixture.createFile("$dir/${source.path}", text)
                CSharpParseOptions.put(file, source.defines.toSet(), source.languageVersion)
                files[source.path] = file
            }
            val manager = PsiManager.getInstance(project)
            // the files of the input do not change while they are compared: one session of the resolver for all of them
            val model = if (option("model") == "syntactic") SyntacticSemanticModel else ResolvingSemanticModel(project)
            for (record in dump.files) {
                val psi = files[record.path]?.let(manager::findFile)
                if (psi == null) {
                    println("semantic gate: ${record.path} is not among the sources")
                    continue
                }
                val answers = SemanticModelAnswers(model, psi, base)
                comparisons.forEach { it.add(record, answers) }
            }
        } finally {
            WriteAction.run<Throwable> { base.delete(this) }
        }
    }

    /**
     * The assemblies of an input as the plugin sees them: indexed by `indexer/` (built here for the newest SDK), opened like
     * `AssemblyIndexService` opens them; in the order of the dump (a type is taken from the first that has it).
     */
    private fun indexAssemblies(repo: File, out: File, paths: List<String>): AssemblyIndexSet {
        if (paths.isEmpty()) {
            println("semantic gate: the dump names no assembly paths (an old dump: run without reuse=true)")
            return AssemblyIndexSet(emptyList())
        }
        val tool = File(out, "indexer")
        val dll = File(tool, "${IndexerTool.ASSEMBLY}.dll")
        val sources = File(repo, "indexer").listFiles { f -> f.extension == "cs" || f.extension == "csproj" }.orEmpty()
        if (!dll.isFile || dll.lastModified() < (sources.maxOfOrNull { it.lastModified() } ?: 0)) {
            val process = ProcessBuilder(option("dotnet") ?: "dotnet", "build", File(repo, "indexer/${IndexerTool.ASSEMBLY}.csproj").path, "-c", "Release", "-nologo", "-v:q",
                "-o", tool.path, "-p:IndexerFramework=net10.0").redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            check(process.waitFor(10, TimeUnit.MINUTES) && process.exitValue() == 0) { "dotnet build indexer failed:\n$log" }
        }
        val list = File(out, "assemblies.txt").also { it.writeText(paths.joinToString("\n")) }
        val indexes = File(out, "index")
        val process = ProcessBuilder(option("dotnet") ?: "dotnet", dll.path, "--out", indexes.path, "--list", list.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val stdout = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.MINUTES) && process.exitValue() == 0) { "the indexer failed:\n$stdout" }
        val found = IndexerTool.parse(stdout).mapKeys { it.key.absoluteFile }
        val opened = paths.mapNotNull { found[File(it).absoluteFile]?.let { index -> AssemblyIndex.open(index.toPath()) } }
        println("semantic gate: ${opened.size} of ${paths.size} assemblies indexed")
        return AssemblyIndexSet(opened)
    }

    private fun runDump(dll: File, args: List<String>) {
        val command = listOf(option("dotnet") ?: "dotnet", dll.path, "semantics") + args
        val started = System.currentTimeMillis()
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.MINUTES)) { "roslyndump semantics did not finish" }
        check(process.exitValue() == 0) { "roslyndump semantics failed (${process.exitValue()}): $command\n$log" }
        println("roslyndump semantics ${args.first()}: ${(System.currentTimeMillis() - started) / 1000} s ${log.lines().filter { it.startsWith("# ") }.joinToString(" ")}")
    }

    /** The oracle, built with `dotnet build -c Release` when missing or older than a source of the tool. */
    private fun roslynDump(repo: File): File {
        option("roslyndump")?.let { return File(it) }
        val tool = File(repo, "tools/csharp-psi/roslyndump")
        val dll = File(tool, "bin/Release/net10.0/RoslynDump.dll")
        val newest = tool.listFiles { f -> f.extension == "cs" || f.extension == "csproj" }.orEmpty().maxOfOrNull { it.lastModified() } ?: 0
        if (!dll.isFile || dll.lastModified() < newest) {
            val process = ProcessBuilder(option("dotnet") ?: "dotnet", "build", tool.path, "-c", "Release", "-nologo", "-v:q").redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            check(process.waitFor(10, TimeUnit.MINUTES) && process.exitValue() == 0) { "dotnet build roslyndump failed:\n$log" }
        }
        return dll
    }

    /** Correct answers per input and category may only grow; new keys and improvements rewrite the file. */
    private fun checkBaseline(file: File, metrics: Map<String, Int>) {
        val old = if (file.isFile) file.readLines().filter { '=' in it && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=').toInt() } else emptyMap()
        val regressions = metrics.filter { (key, value) -> old[key]?.let { value < it } == true }.map { (key, value) -> "$key: ${old[key]} -> $value" }
        if (regressions.isNotEmpty()) fail("semantic gate regressed (${file.path}):\n" + regressions.joinToString("\n"))
        val merged = old + metrics
        if (merged != old) {
            file.parentFile.mkdirs()
            file.writeText("# Correct answers of the semantic gate per input and category (CSharpSemanticGate); may only improve.\n" +
                merged.toSortedMap().entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
            println("baseline updated: $file (${metrics.count { (k, v) -> old[k] != v }} values)")
        }
    }

    companion object {
        /** The `#if` symbols of a library of dotnet/runtime built for the newest .NET (its `src` projects target `$(NetCoreAppCurrent)`). */
        const val RUNTIME_DEFINES = "NET;NETCOREAPP;NET10_0;NET5_0_OR_GREATER;NET6_0_OR_GREATER;NET7_0_OR_GREATER;NET8_0_OR_GREATER;NET9_0_OR_GREATER;NET10_0_OR_GREATER;" +
            "NETCOREAPP3_0_OR_GREATER;NETCOREAPP3_1_OR_GREATER;NETCOREAPP2_0_OR_GREATER;NETCOREAPP2_1_OR_GREATER;NETCOREAPP1_0_OR_GREATER;TARGET_WINDOWS"
    }
}
