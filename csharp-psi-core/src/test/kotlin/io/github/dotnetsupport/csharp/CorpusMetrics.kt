package io.github.dotnetsupport.csharp

import junit.framework.TestCase.fail
import java.nio.file.Files
import java.nio.file.Path

/**
 * Corpus metrics file (`testData/metrics/<name>.json`): a flat object of integer values that may only improve.
 * Lower is better unless the key is in `higherIsBetter` (for example matched nodes on invalid code). [check] fails on
 * any regression and rewrites the file when nothing regressed and some value changed, so an improvement lands in the
 * same change as the code; a missing file is created. Keys in `informational` (file and node counts) are recorded but
 * never compared.
 */
object CorpusMetrics {
    private val entry = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*(-?\\d+)")

    fun read(file: Path): Map<String, Long> =
        entry.findAll(Files.readString(file)).associate { it.groupValues[1] to it.groupValues[2].toLong() }

    fun check(
        file: Path,
        actual: LinkedHashMap<String, Long>,
        informational: Set<String> = emptySet(),
        higherIsBetter: Set<String> = emptySet(),
    ) {
        if (!Files.exists(file)) {
            write(file, actual)
            println("  metrics: created $file")
            return
        }
        val accepted = read(file)
        val regressions = actual.filter { (key, value) ->
            val old = accepted[key]
            key !in informational && old != null && (if (key in higherIsBetter) value < old else value > old)
        }
        if (regressions.isNotEmpty()) {
            fail(
                "Corpus metrics regressed in $file: " +
                    regressions.entries.joinToString { (k, v) -> "$k ${accepted[k]} -> $v" },
            )
        }
        if (actual != accepted) {
            write(file, actual)
            println("  metrics: updated $file (was $accepted)")
        } else {
            println("  metrics: unchanged $file")
        }
    }

    private fun write(file: Path, values: Map<String, Long>) {
        Files.createDirectories(file.parent)
        Files.writeString(file, values.entries.joinToString(",\n  ", "{\n  ", "\n}\n") { (k, v) -> "\"$k\": $v" })
    }
}
