package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import java.io.File

/**
 * The latency of the completion list at the places of the Jellyfin comparison (docs/compare, rows 16, 17, 75â€“77): `var policy = new |`,
 * SMART `UserPolicy policy = new |`, an argument `ChangePassword(userId, |`, a member `newName.|`. A harness, not a check: it runs only
 * with `LATENCY_HARNESS=<dir of real .dnix indexes>` (the sandbox's `system_*\dotnet-support\index\v5`), prints the timings and the
 * methods of the plugin a sampler found the completion thread in. A bound of 3 s keeps it from silently getting much slower.
 */
class CompletionLatencyHarnessTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private val dir = System.getenv("LATENCY_HARNESS")?.let(::File)?.takeIf { it.isDirectory }

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        val set = assemblies()
        CSharpSemanticEnvironment.setAssembliesForTests { set }
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = true
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun assemblies(): AssemblyIndexSet? {
        val dir = dir ?: return null
        cached?.let { return it }
        val byName = LinkedHashMap<String, AssemblyIndex>()
        for (file in dir.listFiles { f -> f.name.endsWith(".dnix") }.orEmpty().sortedByDescending { it.length() }) {
            val index = runCatching { AssemblyIndex.read(file.readBytes()) }.getOrNull() ?: continue
            byName.putIfAbsent(index.assemblyName, index)
        }
        println("harness: ${byName.size} assemblies, ${byName.values.sumOf { it.typeCount }} types")
        return AssemblyIndexSet(byName.values.toList()).also { cached = it }
    }

    private fun solution() {
        for (f in 0 until (System.getenv("LATENCY_FILES")?.toIntOrNull() ?: 120)) {
            val types = (0 until 12).joinToString("\n") { t ->
                "public class Service${f}x$t { public int Id { get; set; } public string Name { get; set; } = \"\"; public void Run$t(int a) { } }\n" +
                    "public record Dto${f}x$t(int Id, string Name);\npublic enum Kind${f}x$t { A, B, C }"
            }
            myFixture.addFileToProject("Solution/File$f.cs", "using System;\nnamespace Shop.Part$f;\n\n$types\n")
        }
    }

    private val code = """
        using System;
        using System.Collections.Generic;
        using System.Linq;
        using System.Threading.Tasks;

        namespace Shop;

        public class UserPolicy { public bool IsAdministrator { get; set; } }
        public class User { public Guid Id { get; set; } public string Username { get; set; } = ""; public UserPolicy Policy { get; set; } = new(); }

        public class UserManager
        {
            private readonly List<User> _users = new();
            public void ChangePassword(Guid userId, string newPassword) { }
            public async Task RenameUser(User user, string newName)
            {
                __BODY__
                await Task.CompletedTask;
            }
        }
    """.trimIndent()

    private class Place(val name: String, val body: String, val type: CompletionType)

    private val places = listOf(
        Place("new BASIC", "var policy = new <caret>", CompletionType.BASIC),
        Place("new SMART", "UserPolicy policy = new <caret>", CompletionType.SMART),
        Place("argument BASIC", "ChangePassword(user.Id, <caret>", CompletionType.BASIC),
        Place("member BASIC", "var n = newName.<caret>", CompletionType.BASIC),
        Place("prefix Thro", "Thro<caret>", CompletionType.BASIC),
    )

    fun testLatency() {
        if (dir == null) return
        solution()
        val report = StringBuilder()
        var worst = 0L
        for (place in places) {
            val times = ArrayList<Long>()
            val sampler = Sampler()
            for (run in 0 until 8) {
                myFixture.configureByText("Latency${counter++}.cs", code.replace("__BODY__", place.body))
                if (run >= 3) sampler.start()
                val started = System.nanoTime()
                myFixture.complete(place.type)
                val ms = (System.nanoTime() - started) / 1_000_000
                if (run >= 3) sampler.stop()
                times += ms
                val count = myFixture.lookupElements?.size ?: -1
                if (run == 7) report.append("${place.name}: rows $count, ms ${times.joinToString(" / ")}\n")
                runCatching { myFixture.lookup?.hideLookup(true) }
            }
            worst = maxOf(worst, times.drop(3).sorted()[2])
            report.append(sampler.top(25)).append('\n')
        }
        println(report)
        File("build/latency-harness.txt").writeText(report.toString())
        assertTrue("median of the warm runs over 3 s:\n$report", worst < 3000)
    }

    /** Samples the stacks of all threads every millisecond and counts, per method of the plugin, the samples it is on the stack (inclusive). */
    private class Sampler {
        private val counts = HashMap<String, Int>()
        private val leaves = HashMap<String, Int>()
        private var samples = 0
        @Volatile private var running = false
        private var thread: Thread? = null

        fun start() {
            running = true
            thread = Thread {
                while (running) {
                    for ((t, stack) in Thread.getAllStackTraces()) {
                        if (t === Thread.currentThread()) continue
                        if (stack.none { it.className.startsWith("io.github.dotnetsupport") && it.methodName == "fillCompletionVariants" }) continue
                        samples++
                        val seen = HashSet<String>()
                        for (frame in stack) {
                            if (!frame.className.startsWith("io.github.dotnetsupport")) continue
                            val key = frame.className.substringAfterLast('.') + "." + frame.methodName
                            if (seen.add(key)) counts.merge(key, 1, Int::plus)
                        }
                        val leaf = stack.firstOrNull()?.let { it.className.substringAfterLast('.') + "." + it.methodName }
                        if (leaf != null) leaves.merge(leaf, 1, Int::plus)
                    }
                    Thread.sleep(1)
                }
            }.apply { isDaemon = true; start() }
        }

        fun stop() {
            running = false
            thread?.join()
        }

        fun top(n: Int): String {
            val out = StringBuilder("  samples $samples\n")
            counts.entries.sortedByDescending { it.value }.take(n).forEach { out.append("  ${it.value * 100 / maxOf(samples, 1)}% ${it.key}\n") }
            out.append("  leaves:\n")
            leaves.entries.sortedByDescending { it.value }.take(12).forEach { out.append("    ${it.value * 100 / maxOf(samples, 1)}% ${it.key}\n") }
            return out.toString()
        }
    }

    companion object {
        private var counter = 0
        private var cached: AssemblyIndexSet? = null
    }
}
