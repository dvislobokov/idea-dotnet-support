package io.github.dotnetsupport.razor.lang.discovery

import io.github.dotnetsupport.razor.RazorDump
import io.github.dotnetsupport.razor.RazorMetrics
import io.github.dotnetsupport.razor.RazorTestUtil
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptorJson
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The gate of the tag helper discovery (RAZOR_PLAN.md, R5a): the compiler's producers ported over the symbol model, fed with the symbols of a
 * Roslyn compilation (`razordump symbols`), against what the compiler discovers in that compilation (`razordump tag-helpers`) — descriptor
 * by descriptor, as razordump's JSON. Cases: the reference packs alone (ASP.NET Core's components, `@bind`, event handlers, MVC's tag
 * helpers), and the projects of the corpus (their components from `.razor` declarations and `.cs`, their view components and tag helpers).
 * Also checked: the three spellings of every type reference the producers read ([RazorTypeDisplay] against Roslyn's `ToDisplayString`).
 *
 * Metrics: `testData/metrics/<name>-taghelpers.json` (`same` only grows); differences: `build/razor-gate/<name>-taghelpers.txt`, both JSON
 * files beside it. Skipped without razordump (or without the corpus, for the projects).
 */
class TagHelperDiscoveryCorpusTest : TestCase() {
    fun testReferences() = gate("references", null)
    fun testEshop() = gate("eshop", "razor/eshop/src/WebApp")
    fun testEshopComponents() = gate("eshop-components", "razor/eshop/src/WebAppComponents")
    fun testMudblazor() = gate("mudblazor", "razor/mudblazor/src/MudBlazor")
    fun testNopcommerce() = gate("nopcommerce", "razor/nopcommerce/src/Presentation/Nop.Web")

    // As the build compiles them (tools/razor/README.md, "Build"): ImplicitUsings' GlobalUsings.g.cs and the dlls of the output beside the project's
    // own, so the types resolve and the referenced projects (Nop.Web.Framework's tag helpers, view components) are searched too.
    fun testEshopBuilt() = gateBuilt("eshop-built", "razor/eshop/src/WebApp", "razor/eshop/artifacts/obj/WebApp/debug/WebApp.GlobalUsings.g.cs", "razor/eshop/artifacts/bin/WebApp/debug", "WebApp.dll")
    fun testMudblazorBuilt() = gateBuilt("mudblazor-built", "razor/mudblazor/src/MudBlazor", "razor/mudblazor/src/MudBlazor/obj/Debug/net10.0/MudBlazor.GlobalUsings.g.cs", null, null)
    fun testNopcommerceBuilt() = gateBuilt(
        "nopcommerce-built", "razor/nopcommerce/src/Presentation/Nop.Web", "razor/nopcommerce/src/Presentation/Nop.Web/obj/Debug/net10.0/Nop.Web.GlobalUsings.g.cs",
        "razor/nopcommerce/src/Presentation/Nop.Web/bin/Debug/net10.0", "Nop.Web.dll",
    )

    private fun gateBuilt(name: String, relative: String, globalUsings: String, bin: String?, own: String?) {
        val corpus = RazorTestUtil.corpusRoot()
        val usings = globalUsings?.let { corpus.resolve(it) }
        if (usings != null && !Files.isRegularFile(usings)) return println("TagHelperDiscoveryCorpusTest: $usings is missing (build the corpus, tools/razor/README.md), $name skipped")
        val options = ArrayList<String>()
        if (usings != null) options += listOf("--csharp", usings.toString())
        if (bin != null) {
            val dlls = Files.list(corpus.resolve(bin)).use { s -> s.filter { it.fileName.toString().endsWith(".dll") && it.fileName.toString() != own }.map { it.toString() }.sorted().toList() }
            val list = RazorTestUtil.buildDir("razor-gate").resolve("$name.references.txt")
            Files.write(list, dlls)
            options += listOf("--references", "@$list")
        }
        gate(name, relative, *options.toTypedArray())
    }

    private fun gate(name: String, relative: String?, vararg options: String) {
        if (RazorDump.dll() == null) return println("TagHelperDiscoveryCorpusTest: razordump is not built (dotnet build tools/razor/razordump -c Release), $name skipped")
        val root = relative?.let { RazorTestUtil.corpusRoot().resolve(it) }
        if (root != null && !Files.isDirectory(root)) return println("TagHelperDiscoveryCorpusTest: $root is missing (tools/razor/fetch-corpus.sh), $name skipped")
        val work = RazorTestUtil.buildDir("razor-gate")
        val symbolsFile = work.resolve("$name.symbols.json")
        val oracleFile = work.resolve("$name.taghelpers.oracle.json")
        println("  razordump symbols: " + RazorDump.symbols(root, symbolsFile, *options))
        val summary = RazorDump.tagHelpers(root, oracleFile, *options)
        println("  razordump tag-helpers: $summary")
        // metadata properties the installed compiler lacks are written as defaults by razordump: ours set so too (the port follows main)
        val absent = TagHelperCompatibility.absentOf(summary)

        val started = System.nanoTime()
        val symbols = SymbolsJson.read(Files.readString(symbolsFile))
        val discovered = TagHelperCompatibility.withDefaults(TagHelperDiscovery.forCompilation(symbols).discover(symbols), absent)
        val millis = (System.nanoTime() - started) / 1_000_000
        val ours = TagHelperDescriptorJson.write(discovered)
        Files.writeString(work.resolve("$name.taghelpers.ours.json"), ours)

        // the spellings of every type reference the walked types hold (the discovery reads only some)
        for (assembly in symbols.assemblies) for (type in assembly.types) {
            if (type.kind == RTypeKind.Error) continue
            type.properties
            type.typeParameters
            type.methods
        }

        val expected = TagHelperDescriptorJson.read(Files.readString(oracleFile))
        val report = TagHelperCompatibility.compare(expected, discovered)
        val same = expected.size - report.size.coerceAtMost(expected.size)
        val lines = ArrayList<String>()
        lines += "# $name: ${discovered.size} discovered, ${expected.size} expected, $same same, ${millis} ms; type spellings: ${symbols.displaysChecked} checked, " +
            "${symbols.displayMismatches.size} differ"
        lines += symbols.displayMismatches.take(50).map { "spelling: $it" }
        lines += report
        Files.write(work.resolve("$name-taghelpers.txt"), lines)
        println(lines.take(60).joinToString("\n"))
        RazorMetrics.check(Path.of(RazorTestUtil.testDataPath("metrics/$name-taghelpers.json")), mapOf("same" to same.toLong()), setOf("same"), emptySet())
        assertTrue("type spellings differ from Roslyn's: ${symbols.displayMismatches.take(5)}", symbols.displayMismatches.isEmpty())
        assertTrue("descriptors differ from the compiler's (build/razor-gate/$name-taghelpers.txt):\n" + report.take(20).joinToString("\n"), report.isEmpty())
    }
}
