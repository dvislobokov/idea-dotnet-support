package io.github.dotnetsupport

import io.github.dotnetsupport.monitor.ChartFormats
import io.github.dotnetsupport.monitor.CounterSnapshot
import io.github.dotnetsupport.monitor.DotNetCounters
import io.github.dotnetsupport.monitor.ProcessSampler
import io.github.dotnetsupport.monitor.TimeSeriesChart
import junit.framework.TestCase
import java.awt.Color
import java.io.File
import java.time.Duration

/** The counter lines are the real output of dotnet-counters 10.0 attached to a .NET 10 web application. */
class MonitorTest : TestCase() {
    fun testCounterLine() {
        val cpu = DotNetCounters.parseLine("09/20/2026 16:08:59,System.Runtime,dotnet.process.cpu.time (s / 1 sec)[cpu.mode=user],Rate,0.109375")!!
        assertEquals("System.Runtime", cpu.provider)
        assertEquals("dotnet.process.cpu.time", cpu.name)
        assertEquals(mapOf("cpu.mode" to "user"), cpu.tags)
        assertEquals(0.109375, cpu.value)

        val duration = DotNetCounters.parseLine(
            "09/20/2026 16:08:59,Microsoft.AspNetCore.Hosting,http.server.request.duration (s)[http.request.method=GET;http.response.status_code=200;http.route=/;network.protocol.version=1.1;url.scheme=http;Percentile=95],Metric,9.822845458984375E-05"
        )!!
        assertEquals("http.server.request.duration", duration.name)
        assertEquals("95", duration.tags["Percentile"])
        assertEquals("/", duration.tags["http.route"])
        assertEquals(9.822845458984375E-05, duration.value)

        // no tags; a unit with braces; the display names of the event counters of older runtimes
        assertEquals("dotnet.assembly.count", DotNetCounters.parseLine("09/20/2026 16:08:35,System.Runtime,dotnet.assembly.count ({assembly}),Metric,142")!!.name)
        assertEquals("% Time in GC since last GC", DotNetCounters.parseLine("01/02/2025 10:00:00,System.Runtime,% Time in GC since last GC (%),Metric,3")!!.name)
        // a comma inside a custom counter name
        assertEquals("orders, pending", DotNetCounters.parseLine("01/02/2025 10:00:00,Shop,orders, pending (items),Metric,7")!!.name)

        assertNull(DotNetCounters.parseLine("Timestamp,Provider,Counter Name,Counter Type,Mean/Increment"))
        assertNull(DotNetCounters.parseLine("09/20/2026 16:08:59,System.Runtime,dotnet.process.cpu.ti"))
        assertNull(DotNetCounters.parseLine(""))
    }

    fun testRuntimeMetricsOfModernRuntime() {
        val snapshot = CounterSnapshot()
        listOf(
            "t,System.Runtime,dotnet.gc.last_collection.heap.size (By)[gc.heap.generation=gen0],Metric,1178720",
            "t,System.Runtime,dotnet.gc.last_collection.heap.size (By)[gc.heap.generation=loh],Metric,19605488",
            "t,System.Runtime,dotnet.gc.last_collection.heap.size (By)[gc.heap.generation=loh],Metric,20000000", // a newer value of the same series
            "t,System.Runtime,dotnet.gc.heap.total_allocated (By / 1 sec),Rate,3312520",
            "t,System.Runtime,dotnet.gc.pause.time (s / 1 sec),Rate,0.025",
            "t,System.Runtime,dotnet.gc.collections ({collection} / 1 sec)[gc.heap.generation=gen0],Rate,2",
            "t,System.Runtime,dotnet.gc.collections ({collection} / 1 sec)[gc.heap.generation=gen1],Rate,1",
            "t,Microsoft.AspNetCore.Hosting,http.server.active_requests ({request})[http.request.method=GET;url.scheme=http],Metric,3",
            "t,Microsoft.AspNetCore.Hosting,http.server.active_requests ({request})[http.request.method=POST;url.scheme=http],Metric,1",
            "t,Microsoft.AspNetCore.Hosting,http.server.request.duration (s)[http.route=/a;Percentile=95],Metric,0.010",
            "t,Microsoft.AspNetCore.Hosting,http.server.request.duration (s)[http.route=/b;Percentile=95],Metric,0.250",
            "t,Microsoft.AspNetCore.Hosting,http.server.request.duration (s)[http.route=/b;Percentile=99],Metric,0.900",
            "t,System.Net.Http,http.client.active_requests ({request})[server.address=localhost],Metric,2",
        ).forEach { snapshot.accept(DotNetCounters.parseLine(it)!!, now = 1_000) }

        val metrics = snapshot.metrics(now = 1_500)
        assertEquals(21_178_720.0, metrics.gcHeapBytes)
        assertEquals(3_312_520.0, metrics.allocatedBytesPerSecond)
        assertEquals(2.5, metrics.gcPausePercent!!, 1e-9)
        assertEquals(3.0, metrics.gcCollectionsPerSecond)
        assertEquals(4.0, metrics.activeRequests)
        // the slowest route, and only the 95th percentile
        assertEquals(250.0, metrics.requestDurationP95Ms!!, 1e-9)
        assertEquals(2.0, metrics.activeClientRequests)
        // nothing was thrown: the runtime has not published the instrument
        assertNull(metrics.exceptionsPerSecond)

        // series that stopped arriving are dropped, not drawn as a plateau
        val later = snapshot.metrics(now = 10_000)
        assertNull(later.requestDurationP95Ms)
        assertNull(later.gcHeapBytes)
    }

    fun testRuntimeMetricsOfOlderRuntime() {
        val snapshot = CounterSnapshot()
        listOf(
            "t,System.Runtime,GC Heap Size (MB),Metric,42",
            "t,System.Runtime,Allocation Rate (B / 1 sec),Rate,8192",
            "t,System.Runtime,% Time in GC since last GC (%),Metric,7",
            "t,System.Runtime,Exception Count (Count / 1 sec),Rate,5",
            "t,Microsoft.AspNetCore.Hosting,Current Requests,Metric,9",
            "t,System.Net.Http,Current Requests,Metric,4",
        ).forEach { snapshot.accept(DotNetCounters.parseLine(it)!!, now = 0) }
        val metrics = snapshot.metrics(now = 0)
        assertEquals(42_000_000.0, metrics.gcHeapBytes)
        assertEquals(8192.0, metrics.allocatedBytesPerSecond)
        assertEquals(7.0, metrics.gcPausePercent)
        assertEquals(5.0, metrics.exceptionsPerSecond)
        // the same display name of two providers
        assertEquals(9.0, metrics.activeRequests)
        assertEquals(4.0, metrics.activeClientRequests)
    }

    fun testProcessList() {
        val processes = DotNetCounters.parseProcessList(
            " 12788  Rider.Backend  C:\\Program Files\\JetBrains\\Rider\\Rider.Backend.exe      \"C:\\Program Files\\JetBrains\\Rider\\Rider.Backend.exe\" --Port=58815\n" +
                " 26672  app            C:\\work\\my app\\bin\\Debug\\net10.0\\app.exe                                        \n" +
                "\n"
        )
        assertEquals(listOf(12788L, 26672L), processes.map { it.pid })
        assertEquals("app (26672)", processes[1].toString())
        assertEquals("C:\\work\\my app\\bin\\Debug\\net10.0\\app.exe", processes[1].path)
    }

    fun testCollectCommand() {
        val command = DotNetCounters.collectCommand(File("dotnet-counters"), 4242, File("out.csv"))
        assertEquals(
            listOf("collect", "--process-id", "4242", "--format", "csv", "--output", "out.csv", "--refresh-interval", "1", "--counters", DotNetCounters.PROVIDERS),
            command.parametersList.list,
        )
    }

    fun testCpuPercent() {
        val second = 1_000_000_000L
        // two busy cores of eight for one second
        assertEquals(25.0, ProcessSampler.cpuPercent(Duration.ofSeconds(10), Duration.ofSeconds(12), second, processors = 8), 1e-9)
        // a child has exited: the sum of CPU times went down
        assertEquals(0.0, ProcessSampler.cpuPercent(Duration.ofSeconds(12), Duration.ofSeconds(3), second, processors = 8))
        assertEquals(100.0, ProcessSampler.cpuPercent(Duration.ZERO, Duration.ofSeconds(60), second, processors = 4))
        assertEquals(0.0, ProcessSampler.cpuPercent(Duration.ZERO, Duration.ofSeconds(1), 0, processors = 4))
    }

    fun testSystemFormats() {
        assertEquals(83_183_616L to 17, ProcessSampler.parseProcStatus("Name:\tapp\nVmPeak:\t  300000 kB\nVmRSS:\t   81234 kB\nThreads:\t17\n"))
        assertEquals(0L to null, ProcessSampler.parseProcStatus("Name:\tzombie\n"))
        assertEquals(12_345L * 1024, ProcessSampler.parsePsRss("  12345\n"))
        assertEquals(0L, ProcessSampler.parsePsRss(""))
    }

    /** The real thing: this JVM, through the same calls the monitor uses (on Windows: our own mapping of psapi.dll). */
    fun testSamplingOfALiveProcess() {
        val self = ProcessSampler.tree(ProcessHandle.current().pid())
        assertTrue(self.isNotEmpty())
        val usage = ProcessSampler.sample(self)
        assertTrue("working set: ${usage.workingSetBytes}", usage.workingSetBytes > 50L * 1024 * 1024)
        assertTrue(usage.cpuTime > Duration.ZERO)
        assertEquals(emptyList<ProcessHandle>(), ProcessSampler.tree(Long.MAX_VALUE))
    }

    fun testChart() {
        assertEquals(listOf(1.0, 1.0, 50.0, 100.0, 200.0, 5000.0), listOf(0.0, 0.7, 42.0, 100.0, 101.0, 4200.0).map(ChartFormats::niceMax))
        assertEquals(listOf("128.0 MB", "1 KB", "2.00 GB"), listOf(100e6, 600.0, 1.5e9).map { ChartFormats.bytes(ChartFormats.niceMaxBytes(it)).replace(',', '.') })
        assertEquals("78.4 MB", ChartFormats.bytes(78.4 * 1024 * 1024).replace(',', '.'))
        assertEquals("512 B", ChartFormats.bytes(512.0))
        assertEquals("42%", ChartFormats.percent(42.3))

        val chart = TimeSeriesChart("Memory", ChartFormats::bytes, null, "working set" to Color.GREEN, "GC heap" to Color.ORANGE)
        assertEquals("no data", chart.legend())
        chart.add(2048.0, null)
        assertEquals("working set 2 KB", chart.legend())
        chart.add(4096.0, 1024.0)
        assertEquals("working set 4 KB · GC heap 1 KB", chart.legend())
        repeat(TimeSeriesChart.CAPACITY + 5) { chart.add(1.0, 2.0) }
        assertEquals(2.0, chart.latest(1))
        chart.clear()
        assertNull(chart.latest(0))
    }
}
