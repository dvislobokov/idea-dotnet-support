package io.github.dotnetsupport

import io.github.dotnetsupport.roslyn.RoslynRequestStats
import io.github.dotnetsupport.roslyn.RoslynServerWrapper
import io.github.dotnetsupport.roslyn.RoslynTokenStore
import io.github.dotnetsupport.roslyn.RoslynWarmUp
import junit.framework.TestCase
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.io.File
import java.nio.file.Files

/** Phase 3 of LSP_PLAN.md: the cache of semantic tokens, the warm-up and the timings. Nothing is started here. */
class RoslynCacheTest : TestCase() {
    private lateinit var directory: File

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("roslyn-tokens").toFile()
    }

    override fun tearDown() {
        try {
            directory.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testKeyIsTheTextAndTheLegend() {
        val legend = RoslynTokenStore.legendKey(SemanticTokensLegend(listOf("class", "method"), listOf("static")))
        val other = RoslynTokenStore.legendKey(SemanticTokensLegend(listOf("class", "method", "field"), listOf("static")))
        assertEquals(RoslynTokenStore.key(legend, "class A { }"), RoslynTokenStore.key(legend, StringBuilder("class A { }")))
        assertFalse(RoslynTokenStore.key(legend, "class A { }") == RoslynTokenStore.key(legend, "class A {  }"))
        assertFalse("another server means other numbers", RoslynTokenStore.key(legend, "class A { }") == RoslynTokenStore.key(other, "class A { }"))
        assertEquals(40, RoslynTokenStore.key(legend, "").length)
        assertEquals(RoslynTokenStore.legendKey(null), RoslynTokenStore.legendKey(SemanticTokensLegend(emptyList(), emptyList())))
    }

    fun testStore() {
        val store = RoslynTokenStore(directory)
        assertNull(store.get("absent"))
        val tokens = listOf(0, 7, 5, 2, 0, 1, 4, 3, 13, 1, Int.MAX_VALUE)
        store.put("a", tokens)
        assertTrue(store.contains("a"))
        assertEquals(tokens, store.get("a"))
        store.put("a", listOf(1, 2, 3))
        assertEquals("replaced", listOf(1, 2, 3), store.get("a"))
        assertEquals(emptyList<Int>(), RoslynTokenStore.decode(RoslynTokenStore.encode(emptyList())))

        File(directory, "broken").writeBytes(byteArrayOf(0, 0, 0, 9, 1))
        assertNull("a damaged file is a miss, not an error", store.get("broken"))
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    /** The least recently used go first; a read counts as a use. */
    fun testEviction() {
        val store = RoslynTokenStore(directory, limitBytes = 3 * (4 + 100 * 4L))
        val hundred = List(100) { it }
        for ((index, name) in listOf("old", "used", "new").withIndex()) {
            store.put(name, hundred)
            File(directory, name).setLastModified(1_000_000L * (index + 1))
        }
        assertEquals(hundred, store.get("used")) // now the most recent
        File(directory, "old").setLastModified(1_000_000L)
        store.put("newest", hundred)
        assertEquals(setOf("used", "new", "newest"), directory.list()!!.toSet())
    }

    fun testWarmUpPoint() {
        val text = """
            using System.Text; // System.Console in a comment
            namespace Shop;
            class Order
            {
                string note = "a.b";
                void Print() { var total = this.Total; Console.WriteLine(total); }
            }
        """.trimIndent()
        val point = RoslynWarmUp.point(text)!!
        assertEquals("Console", text.substring(point.identifier, point.identifierEnd))
        assertEquals("WriteLine", text.substring(point.afterDot).substringBefore('('))
        assertNull("no body", RoslynWarmUp.point("using System.Text;"))
        assertNull("no member access", RoslynWarmUp.point("class A { int x; }"))
        assertEquals("a", RoslynWarmUp.point("class A { void M() { a . b(); } }")!!.let { "class A { void M() { a . b(); } }".substring(it.identifier, it.identifierEnd) })
    }

    fun testTimings() {
        val stats = RoslynRequestStats()
        stats.record("textDocument/completion", 153.0)
        stats.record("textDocument/completion", 4.0)
        stats.record("textDocument/completion", 9.0)
        stats.record("textDocument/completion", 6.0)
        stats.warmingUp = true
        stats.record("textDocument/hover", 39.0)
        stats.warmingUp = false
        stats.record("textDocument/semanticTokens/full", 0.0, fromCache = true)
        val lines = stats.report().lines()
        val completion = lines.first { it.startsWith("textDocument/completion") }.split(Regex("\\s+"))
        assertEquals(listOf("textDocument/completion", "4", "0", "153.0", "6.0", "9.0"), completion)
        assertTrue(lines.any { it.startsWith("(warm-up) textDocument/hover") })
        assertEquals("1", lines.first { it.startsWith("textDocument/semanticTokens/full") }.split(Regex("\\s+"))[2])
        stats.reset()
        assertEquals(1, stats.report().lines().count { it.isNotBlank() })
    }

    fun testNamesOfTheProtocol() {
        assertEquals("textDocument/completion", RoslynServerWrapper.lspName(TextDocumentService::class.java.getMethod("completion", org.eclipse.lsp4j.CompletionParams::class.java)))
        assertEquals("textDocument/semanticTokens/full", RoslynServerWrapper.lspName(TextDocumentService::class.java.getMethod("semanticTokensFull", org.eclipse.lsp4j.SemanticTokensParams::class.java)))
        assertEquals("workspace/symbol", RoslynServerWrapper.lspName(WorkspaceService::class.java.getMethod("symbol", org.eclipse.lsp4j.WorkspaceSymbolParams::class.java)))
        assertEquals("codeAction/resolveFixAll", RoslynServerWrapper.lspName(io.github.dotnetsupport.roslyn.RoslynServer::class.java.getMethod("resolveFixAll", io.github.dotnetsupport.roslyn.FixAllParams::class.java)))
    }
}
