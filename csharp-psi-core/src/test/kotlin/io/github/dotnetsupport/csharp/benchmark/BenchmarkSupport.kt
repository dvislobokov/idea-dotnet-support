package io.github.dotnetsupport.csharp.benchmark

import io.github.dotnetsupport.csharp.CSharpTestUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/**
 * Benchmark harness (adapted from go-psi's `tools/benchmark/BenchmarkSupport.kt`). A benchmark runs [WARMUPS] untimed
 * and [ITERATIONS] timed iterations, takes the median and derives a unit value (`median / quantity`, e.g. ms/MB).
 * Thresholds live in `testData/metrics/benchmark-parser.json` (`{"<name>": {"medianMs": .., "unit": .., "value": ..}}`):
 *  - a run fails when `median > stored medianMs * BASE_TOLERANCE * tolerance` (`-Dcsharppsi.benchmark.tolerance=2.0`
 *    for a slower machine);
 *  - `-Dcsharppsi.benchmark.update=true` stores the entry when it is missing or the run is faster (thresholds only
 *    improve); without the flag nothing is written, so a machine-dependent number is never recorded by accident;
 *  - `recordable = false` (a measurement of the placeholder parser) never stores nor compares: it is reported only.
 * Every benchmark prints `BENCH <name>: median=<ms> <unit>=<value> (threshold <value|none>)`.
 */
object BenchmarkSupport {
    const val WARMUPS = 3
    const val ITERATIONS = 7
    /** Shared machines: medians flap by ~30% between runs (go-psi). */
    const val BASE_TOLERANCE = 1.50

    data class Entry(val medianMs: Double, val unit: String, val value: Double)

    private val lock = Any()

    fun thresholdsFile(): Path = Paths.get(CSharpTestUtil.testDataPath("metrics")).resolve("benchmark-parser.json")

    private val update: Boolean
        get() = System.getProperty("csharppsi.benchmark.update")?.let { it.isEmpty() || it.toBoolean() } ?: false
    private val tolerance: Double get() = System.getProperty("csharppsi.benchmark.tolerance")?.toDoubleOrNull() ?: 1.0

    /** Runs [body] [WARMUPS] + [ITERATIONS] times ([prepare] untimed before each, with a GC) and checks the median. */
    fun run(name: String, unit: String, quantity: Double, recordable: Boolean = true, prepare: (Int) -> Unit = {}, body: () -> Unit): Entry {
        val times = ArrayList<Double>()
        for (i in 0 until WARMUPS + ITERATIONS) {
            prepare(i)
            System.gc()
            val ms = timed(body)
            if (i >= WARMUPS) times += ms
        }
        val median = median(times)
        val measured = Entry(median, unit, if (quantity > 0) median / quantity else median)
        check(name, measured, recordable)
        return measured
    }

    inline fun timed(block: () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000.0
    }

    fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    /** `BENCH <name>: <text> (report only)`. */
    fun report(name: String, text: String) = println("BENCH $name: $text (report only)")

    fun format(v: Double): String = String.format(Locale.ROOT, if (Math.abs(v) < 1.0) "%.3f" else "%.2f", v)

    private fun check(name: String, measured: Entry, recordable: Boolean) {
        val line = "BENCH $name: median=${format(measured.medianMs)} ${measured.unit}=${format(measured.value)}"
        if (!recordable) {
            println("$line (report only: placeholder parser)")
            return
        }
        synchronized(lock) {
            val file = thresholdsFile()
            val stored = read(file)
            val threshold = stored[name]
            println("$line (threshold ${threshold?.let { format(it.value) } ?: "none"})")
            if (update && (threshold == null || measured.medianMs < threshold.medianMs)) {
                stored[name] = measured
                write(file, stored)
                println("  thresholds: stored $name in $file")
            }
            if (threshold != null) {
                val allowed = threshold.medianMs * BASE_TOLERANCE * tolerance
                if (measured.medianMs > allowed) {
                    throw AssertionError(
                        "Benchmark $name regressed: median ${format(measured.medianMs)} ms > allowed ${format(allowed)} ms " +
                            "(stored ${format(threshold.medianMs)} ms, +${((BASE_TOLERANCE - 1) * 100).toInt()}%, tolerance x$tolerance)",
                    )
                }
            }
        }
    }

    private val entry = Regex(
        "\"([^\"]+)\"\\s*:\\s*\\{\\s*\"medianMs\"\\s*:\\s*([-0-9.eE]+)\\s*,\\s*\"unit\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"value\"\\s*:\\s*([-0-9.eE]+)\\s*\\}",
    )

    private fun read(file: Path): MutableMap<String, Entry> {
        val result = sortedMapOf<String, Entry>()
        if (!Files.exists(file)) return result
        for (m in entry.findAll(Files.readString(file))) {
            result[m.groupValues[1]] = Entry(m.groupValues[2].toDouble(), m.groupValues[3], m.groupValues[4].toDouble())
        }
        return result
    }

    private fun write(file: Path, entries: Map<String, Entry>) {
        Files.createDirectories(file.parent)
        val text = entries.entries.joinToString(",\n", "{\n", "\n}\n") { (k, e) ->
            "  \"$k\": {\"medianMs\": ${format(e.medianMs)}, \"unit\": \"${e.unit}\", \"value\": ${format(e.value)}}"
        }
        Files.writeString(file, text)
    }
}
