package io.github.dotnetsupport

import com.google.gson.JsonParser
import io.github.dotnetsupport.cli.DiagnosticsAnswers
import io.github.dotnetsupport.cli.DiagnosticsHelperService
import io.github.dotnetsupport.cli.DotNetHelper
import io.github.dotnetsupport.cli.ProcessRuntime
import io.github.dotnetsupport.cli.ProcessRuntimes
import io.github.dotnetsupport.cli.normalAddress
import junit.framework.TestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The answers of DiagnosticsHelper (`helpers/diagnostics`), saved from real runs: `runtimes` of the `leak` scenario of debug-playground,
 * of PowerShell 5.1 of 64 and 32 bits, of explorer.exe, of the System process and of a pid that is not there; `retained` and
 * `dominators` of a dump of `leak` taken by `dotnet-dump collect --type Heap` (lists cut short).
 */
class DiagnosticsHelperTest : TestCase() {
    private fun answer(name: String) = JsonParser.parseString(javaClass.getResource("/diagnostics/$name")!!.readText())

    fun testRuntimesOfProcesses() {
        val runtimes = DiagnosticsAnswers.runtimes(answer("runtimes.json"))
        assertEquals(6, runtimes.size)
        assertEquals(ProcessRuntime("core", "9.0.6", 64), runtimes[75844])
        assertEquals("PowerShell 5.1 has the desktop CLR loaded", ProcessRuntime("desktop", "4.8.9345.0", 64), runtimes[31936])
        assertEquals("the helper of 64 bits sees the CLR of a process of 32 bits", 32, runtimes[73936]!!.bitness)
        assertTrue(runtimes[73936]!!.isDesktop)
        assertFalse(runtimes[75844]!!.isDesktop)
        assertEquals("explorer.exe: open, but no CLR", ProcessRuntime(null, null, 64), runtimes[37532])
        assertTrue("System and a pid that is gone: not accessible", 4L in runtimes && runtimes[4] == null && runtimes[999999] == null)
    }

    fun testRetainedSizesOfADump() {
        val heap = DiagnosticsAnswers.retained(answer("retained.json"))
        assertEquals(184415, heap.objects)
        assertEquals(102, heap.unreachableObjects)
        assertEquals("the largest retainers first", heap.types.sortedByDescending { it.retained }, heap.types)
        // the leak: the cached orders keep their byte arrays alive, so the type retains far more than its own bytes
        val orders = heap.type("00007FFDFDEEF7E0")!!
        assertEquals("Playground.CachedOrder", orders.name)
        assertEquals(61000, orders.count)
        assertEquals(2440000, orders.shallow)
        assertEquals(69184900, orders.retained)
        assertNull(heap.type("7ffdfdd00000"))

        val top = heap.dominators.first()
        assertEquals("System.Object[]", top.type)
        assertEquals("StrongHandle", top.root)
        assertEquals(58, top.children)
        assertTrue(top.retained > top.size)
        assertEquals(16, heap.omittedDominators)
        assertEquals(142938112, heap.peakWorkingSet)
    }

    fun testDominatedChildren() {
        val dominated = DiagnosticsAnswers.dominated(answer("dominators.json"))
        assertEquals("29ee0400028", dominated.parent!!.address)
        assertEquals(listOf(71297096L, 56424L, 33128L), dominated.children.map { it.retained })
        assertEquals("System.Collections.Generic.Dictionary<System.Int32, Playground.CachedOrder>", dominated.children[0].type)
        assertNull(dominated.children[0].root)
        assertEquals(55, dominated.omitted)
        assertNull("the top of the tree has no parent", DiagnosticsAnswers.dominated(JsonParser.parseString("""{"parent":null,"children":[],"omitted":0}""")).parent)
    }

    fun testAddressesAreComparedWithoutPaddingAndCase() {
        assertEquals("7ffdfdeef7e0", normalAddress("00007FFDFDEEF7E0"))
        assertEquals("7ffdfdeef7e0", normalAddress("0x7ffdfdeef7e0"))
        assertEquals("0", normalAddress("0000"))
    }

    /** Attach to Process asks for one process after another: one batch for all, then the cache; a slow helper is waited for once, briefly. */
    fun testRuntimesAreAskedInOneBatchAndCached() {
        var time = 0L
        val queries = AtomicInteger()
        val cache = ProcessRuntimes(
            query = { pids -> queries.incrementAndGet(); pids.associateWith { if (it == 2L) ProcessRuntime("desktop", "4.8", 32) else null } },
            processIds = { listOf(1L, 2L, 3L) }, ttlMs = 5_000, waitMs = 1_000, now = { time }, runAsync = { it() },
        )
        assertNull(cache.runtime(1))
        assertTrue(cache.runtime(2)!!.isDesktop)
        assertNull(cache.runtime(3))
        assertEquals(1, queries.get())
        time += 6_000
        assertTrue(cache.runtime(2)!!.isDesktop)
        assertEquals("an old snapshot is asked again", 2, queries.get())
    }

    fun testASlowHelperHoldsTheListUpOnlyOnce() {
        val release = CountDownLatch(1)
        val queries = AtomicInteger()
        val cache = ProcessRuntimes(
            query = { pids -> queries.incrementAndGet(); release.await(10, TimeUnit.SECONDS); pids.associateWith { ProcessRuntime("desktop", "4.8", 64) } },
            processIds = { listOf(1L, 2L) }, waitMs = 300, runAsync = { Thread(it).start() },
        )
        val started = System.nanoTime()
        assertNull("not known within the wait", cache.runtime(1))
        val first = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("waited $first ms", first in 250..2_000)
        val again = System.nanoTime()
        assertNull(cache.runtime(2))
        assertTrue("the second process is not waited for again", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - again) < 100)
        release.countDown()
        var known: ProcessRuntime? = null
        repeat(100) { if (known == null) { known = cache.runtime(2); Thread.sleep(20) } }
        assertTrue(known!!.isDesktop)
        assertEquals(1, queries.get())
    }

    fun testAFailingHelperIsNotAskedForEveryProcess() {
        val queries = AtomicInteger()
        val cache = ProcessRuntimes(query = { queries.incrementAndGet(); throw IllegalStateException("not built") }, processIds = { listOf(1L, 2L) }, runAsync = { it() })
        assertNull(cache.runtime(1))
        assertNull(cache.runtime(2))
        assertEquals(1, queries.get())
    }

    /** The build of the plugin carries the helper as its source, with the protocol next to it. */
    fun testTheHelperIsInThePlugin() {
        val sources = DotNetHelper.Sources.read("diagnostics", listOf("Program.cs", "Protocol.cs", "DiagnosticsHelper.csproj"))
        assertNotNull(sources)
        assertTrue(sources!!.files.getValue("DiagnosticsHelper.csproj").contains("Microsoft.Diagnostics.Runtime"))
        assertTrue(sources.files.getValue("Program.cs").contains("HelperProtocol.ServeAsync"))
        assertEquals("DiagnosticsHelper", DiagnosticsHelperService.HELPER.assembly)
    }
}
