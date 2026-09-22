package io.github.dotnetsupport.monitor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import io.github.dotnetsupport.cli.DotNetTool
import java.io.File

/** A process `dotnet-counters ps` can attach to. */
class DotNetProcess(val pid: Long, val name: String, val path: String) {
    override fun toString(): String = "$name ($pid)"
}

/** One line of the CSV that `dotnet-counters collect` keeps appending to. */
class CounterValue(val provider: String, val name: String, val tags: Map<String, String>, val value: Double)

/** The numbers of the runtime the charts show; null: the application does not publish the counter (yet). */
class RuntimeMetrics(
    val gcHeapBytes: Double?,
    val gcCommittedBytes: Double?,
    val allocatedBytesPerSecond: Double?,
    /** Share of the last second spent in GC pauses, 0..100. */
    val gcPausePercent: Double?,
    val gcCollectionsPerSecond: Double?,
    val exceptionsPerSecond: Double?,
    val lockContentionsPerSecond: Double?,
    val threadPoolQueueLength: Double?,
    val activeRequests: Double?,
    /** 95th percentile of the server request duration, milliseconds. */
    val requestDurationP95Ms: Double?,
    val activeClientRequests: Double?,
)

object DotNetCounters {
    const val PACKAGE = "dotnet-counters"

    /** System.Runtime alone for a console application costs nothing; the web providers are silent when unused. */
    const val PROVIDERS = "System.Runtime,Microsoft.AspNetCore.Hosting,Microsoft.AspNetCore.Server.Kestrel,System.Net.Http"

    fun findExecutable(): File? = DotNetTool.COUNTERS.find()

    /** `monitor` needs a real console (it fails when redirected); `collect` appends to the file every interval, and the file is followed. */
    fun collectCommand(executable: File, pid: Long, output: File): GeneralCommandLine =
        GeneralCommandLine(executable.path, "collect", "--process-id", pid.toString(), "--format", "csv", "--output", output.path,
            "--refresh-interval", "1", "--counters", PROVIDERS)
            .withCharset(Charsets.UTF_8)

    /** Blocking. */
    fun processes(executable: File): List<DotNetProcess> = try {
        parseProcessList(CapturingProcessHandler(GeneralCommandLine(executable.path, "ps").withCharset(Charsets.UTF_8)).runProcess(15_000).stdout)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * ` 26672  app            C:\work\app\bin\Debug\net10.0\app.exe      "C:\...\app.exe" --urls ...`:
     * the columns are padded with spaces, and paths may contain single spaces.
     */
    fun parseProcessList(output: String): List<DotNetProcess> = output.lineSequence().mapNotNull { line ->
        val cells = line.trim().split(COLUMNS, limit = 4)
        val pid = cells.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        DotNetProcess(pid, cells.getOrNull(1).orEmpty(), cells.getOrNull(2).orEmpty())
    }.toList()

    /**
     * `09/20/2026 16:08:59,System.Runtime,dotnet.process.cpu.time (s / 1 sec)[cpu.mode=user],Rate,0.109375`.
     * The name is split into the instrument, the unit in parentheses and the tags in brackets; the value is the last
     * field (a custom counter name may contain commas). Null for the header and for torn lines.
     */
    fun parseLine(line: String): CounterValue? {
        val firstComma = line.indexOf(',')
        val secondComma = line.indexOf(',', firstComma + 1)
        val lastComma = line.lastIndexOf(',')
        val typeComma = line.lastIndexOf(',', lastComma - 1)
        if (firstComma < 0 || secondComma < 0 || typeComma <= secondComma) return null
        val value = line.substring(lastComma + 1).trim().toDoubleOrNull() ?: return null

        val fullName = line.substring(secondComma + 1, typeComma)
        val tagsStart = fullName.lastIndexOf('[').takeIf { it >= 0 && fullName.endsWith("]") }
        val tags = tagsStart?.let { fullName.substring(it + 1, fullName.length - 1) }.orEmpty()
            .split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val withoutTags = if (tagsStart != null) fullName.substring(0, tagsStart) else fullName
        // "dotnet.gc.pause.time (s / 1 sec)" -> "dotnet.gc.pause.time"; "GC Heap Size (MB)" -> "GC Heap Size"
        val name = withoutTags.substringBeforeLast(" (", withoutTags).trim()
        return CounterValue(line.substring(firstComma + 1, secondComma), name, tags, value)
    }

    private val COLUMNS = Regex("""\s{2,}""")
}

/**
 * The latest value of every time series. The runtime of .NET 9+ publishes OpenTelemetry-style instruments
 * (`dotnet.gc.pause.time`), older ones publish event counters with display names (`% Time in GC since last GC`);
 * both are understood.
 */
class CounterSnapshot {
    private class Entry(val counter: CounterValue, val updatedAt: Long)

    private val latest = HashMap<String, Entry>()

    @Synchronized fun accept(counter: CounterValue, now: Long = System.currentTimeMillis()) {
        latest[counter.provider + "/" + counter.name + counter.tags.toSortedMap()] = Entry(counter, now)
    }

    /** A series that stopped arriving (no requests: no percentiles) is forgotten instead of being drawn as a plateau. */
    @Synchronized private fun series(name: String, now: Long): List<CounterValue> =
        latest.values.filter { it.counter.name == name && now - it.updatedAt <= STALE_MS }.map { it.counter }

    fun metrics(now: Long = System.currentTimeMillis()): RuntimeMetrics {
        fun sum(name: String, filter: (CounterValue) -> Boolean = { true }): Double? =
            series(name, now).filter(filter).takeIf { it.isNotEmpty() }?.sumOf { it.value }
        // display names repeat between providers ("Current Requests" of the server and of HttpClient)
        fun legacy(name: String, scale: Double = 1.0, provider: String = "System.Runtime"): Double? = sum(name) { it.provider == provider }?.times(scale)

        return RuntimeMetrics(
            gcHeapBytes = sum("dotnet.gc.last_collection.heap.size") ?: legacy("GC Heap Size", MEGABYTE),
            gcCommittedBytes = sum("dotnet.gc.last_collection.memory.committed_size") ?: legacy("GC Committed Bytes", MEGABYTE),
            allocatedBytesPerSecond = sum("dotnet.gc.heap.total_allocated") ?: legacy("Allocation Rate"),
            // seconds of pause per second
            gcPausePercent = sum("dotnet.gc.pause.time")?.times(100)?.coerceIn(0.0, 100.0) ?: legacy("% Time in GC since last GC"),
            gcCollectionsPerSecond = sum("dotnet.gc.collections")
                ?: listOfNotNull(legacy("Gen 0 GC Count"), legacy("Gen 1 GC Count"), legacy("Gen 2 GC Count")).takeIf { it.isNotEmpty() }?.sum(),
            exceptionsPerSecond = sum("dotnet.exceptions") ?: legacy("Exception Count"),
            lockContentionsPerSecond = sum("dotnet.monitor.lock_contentions") ?: legacy("Monitor Lock Contention Count"),
            threadPoolQueueLength = sum("dotnet.thread_pool.queue.length") ?: legacy("ThreadPool Queue Length"),
            activeRequests = sum("http.server.active_requests") ?: legacy("Current Requests", provider = "Microsoft.AspNetCore.Hosting"),
            // the slowest route decides
            requestDurationP95Ms = series("http.server.request.duration", now).filter { it.tags["Percentile"] == "95" }.maxOfOrNull { it.value }?.times(1000),
            activeClientRequests = sum("http.client.active_requests") ?: legacy("Current Requests", provider = "System.Net.Http"),
        )
    }

    private companion object {
        const val STALE_MS = 3_500L
        const val MEGABYTE = 1_000_000.0
    }
}
