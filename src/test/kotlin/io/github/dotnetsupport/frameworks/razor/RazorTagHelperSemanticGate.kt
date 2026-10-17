package io.github.dotnetsupport.frameworks.razor

import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.index.IndexerTool
import io.github.dotnetsupport.razor.lang.discovery.RAssembly
import io.github.dotnetsupport.razor.lang.discovery.TagHelperCompatibility
import io.github.dotnetsupport.razor.lang.discovery.TagHelperDiscovery
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptorJson
import junit.framework.TestCase
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The gate of the symbols of referenced assemblies for the tag helper discovery (RAZOR_PLAN.md, R5b): the reference packs of .NET and ASP.NET
 * Core indexed by the plugin's indexer (format 7: attributes with their arguments), read as [IndexRazorCompilation], searched by razor-psi's
 * producers — against what the Razor compiler of the SDK discovers in a compilation of the same references (`razordump tag-helpers`), descriptor by
 * descriptor. Runs `dotnet` (the indexer, razordump): part of `./gradlew semanticGate`, not of `test`. Differences:
 * `build/razor-gate-index/references-taghelpers.txt`, both JSON files beside it.
 */
class RazorTagHelperSemanticGate : TestCase() {
    private fun option(name: String): String? = System.getProperty("semanticGate.$name")?.takeIf { it.isNotBlank() }

    fun testReferencePacks() = gate("references", emptyList())

    /** MudBlazor's library as a package reference: its components, generic and cascading, read from the metadata. */
    fun testMudblazorLibrary() = corpus("mudblazor", "razor/mudblazor/src/MudBlazor/bin/Debug/net10.0") { it.name == "MudBlazor.dll" }

    /** The output of Nop.Web but its own dll: the tag helpers and view components of Nop.Web.Framework and the packages, read from the metadata. */
    fun testNopcommerceOutput() = corpus("nopcommerce", "razor/nopcommerce/src/Presentation/Nop.Web/bin/Debug/net10.0") { it.name != "Nop.Web.dll" }

    private fun corpus(name: String, bin: String, take: (File) -> Boolean) {
        val repo = File(option("repoRoot") ?: ".").absoluteFile
        val folder = File(repo, ".corpus/$bin")
        if (!folder.isDirectory) return println("razor tag helper gate: $folder is missing (build the corpus, tools/razor/README.md), $name skipped")
        gate(name, folder.listFiles { f -> f.extension == "dll" && take(f) }.orEmpty().sortedBy { it.path })
    }

    private fun gate(name: String, extra: List<File>) {
        val repo = File(option("repoRoot") ?: ".").absoluteFile
        val razordump = File(repo, "tools/razor/razordump/bin/Release/net10.0/RazorDump.dll")
        if (!razordump.isFile) return println("razor tag helper gate: razordump is not built (dotnet build tools/razor/razordump -c Release), skipped")
        val out = File(repo, "build/razor-gate-index").also { it.mkdirs() }
        val dotnet = option("dotnet") ?: "dotnet"

        // the references razordump compiles against (TagHelperDiscovery.References of Codegen.cs): the newest patch of the runtime's major version of
        // each reference pack, its dlls in the order of the directory listing
        val dotnetRoot = dotnetRoot(dotnet)
        val major = Regex("""^(\d+)\.""").find(run(listOf(dotnet, "--version"), out).trim())?.groupValues?.get(1) ?: "10"
        val assemblies = listOf("Microsoft.NETCore.App.Ref", "Microsoft.AspNetCore.App.Ref").flatMap { pack ->
            val versions = File(dotnetRoot, "packs/$pack").listFiles().orEmpty().filter { it.name.startsWith("$major.") }
            val version = versions.maxByOrNull { v -> v.name.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }.fold(0L) { a, b -> a * 10000 + b } } ?: return@flatMap emptyList()
            val ref = File(version, "ref").listFiles().orEmpty().filter { it.isDirectory }.maxByOrNull { it.name } ?: return@flatMap emptyList()
            ref.listFiles { f -> f.extension == "dll" }.orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        check(assemblies.isNotEmpty()) { "no reference packs under $dotnetRoot/packs" }

        // razordump puts --references after the packs, in the order given
        val set = index(repo, out, dotnet, assemblies + extra)
        val oracleFile = File(out, "$name.taghelpers.oracle.json")
        val references = File(out, "$name.references.txt").also { it.writeText(extra.joinToString("\n") { f -> f.path }) }
        val summary = run(listOf(dotnet, razordump.path, "tag-helpers", "--out", oracleFile.path) + if (extra.isEmpty()) emptyList() else listOf("--references", "@$references"), out)
            .trim().lines().last()
        println("razordump tag-helpers: $summary")

        val started = System.nanoTime()
        // the compilation razordump makes has an assembly of its own (ASP), without types, before the references
        val compilation = IndexRazorCompilation("ASP", set, RAssembly("ASP", set.indexes.map { it.assemblyName }) { emptyList() })
        val discovered = TagHelperCompatibility.withDefaults(TagHelperDiscovery.forCompilation(compilation).discover(compilation), TagHelperCompatibility.absentOf(summary))
        val millis = (System.nanoTime() - started) / 1_000_000
        File(out, "$name.taghelpers.ours.json").writeText(TagHelperDescriptorJson.write(discovered))
        val expected = TagHelperDescriptorJson.read(oracleFile.readText())
        val report = TagHelperCompatibility.compare(expected, discovered)
        val lines = listOf("# $name from the index: ${discovered.size} discovered, ${expected.size} expected, ${report.size} differ, $millis ms") + report
        File(out, "$name-taghelpers.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        println(lines.take(40).joinToString("\n"))
        assertTrue("descriptors from the index differ from the compiler's (build/razor-gate-index/$name-taghelpers.txt):\n" + report.take(20).joinToString("\n"), report.isEmpty())
    }

    /** The assemblies indexed by the indexer of the repository (built here), opened as the plugin opens them, in their order. */
    private fun index(repo: File, out: File, dotnet: String, assemblies: List<File>): AssemblyIndexSet {
        val tool = File(out, "indexer")
        val dll = File(tool, "${IndexerTool.ASSEMBLY}.dll")
        val sources = File(repo, "indexer").listFiles { f -> f.extension == "cs" || f.extension == "csproj" }.orEmpty()
        if (!dll.isFile || dll.lastModified() < (sources.maxOfOrNull { it.lastModified() } ?: 0)) {
            run(listOf(dotnet, "build", File(repo, "indexer/${IndexerTool.ASSEMBLY}.csproj").path, "-c", "Release", "-nologo", "-v:q", "-o", tool.path, "-p:IndexerFramework=net10.0"), out)
        }
        val list = File(out, "assemblies.txt").also { it.writeText(assemblies.joinToString("\n") { it.path }) }
        val indexes = File(out, "index/v${AssemblyIndex.FORMAT_VERSION}")
        val found = IndexerTool.parse(run(listOf(dotnet, dll.path, "--out", indexes.path, "--list", list.path), out, stderr = false)).mapKeys { it.key.absoluteFile }
        return AssemblyIndexSet(assemblies.mapNotNull { found[it.absoluteFile]?.let { index -> AssemblyIndex.open(index.toPath()) } })
    }

    private fun dotnetRoot(dotnet: String): File {
        val sdks = run(listOf(dotnet, "--list-sdks"), File(".")).lines().firstOrNull { it.isNotBlank() } ?: error("no SDK")
        // `10.0.401 [C:\Program Files\dotnet\sdk]`
        return File(sdks.substringAfter('[').substringBefore(']')).parentFile
    }

    private fun run(command: List<String>, directory: File, stderr: Boolean = true): String {
        val builder = ProcessBuilder(command).directory(directory)
        if (stderr) builder.redirectErrorStream(true) else builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.MINUTES) && process.exitValue() == 0) { "failed: $command\n$output" }
        return output
    }
}
