package io.github.dotnetsupport.csharp.lang.oracle

import io.github.dotnetsupport.csharp.CSharpTestUtil
import java.nio.file.Files
import java.nio.file.Path

/** One difference between the oracle's tree and ours. [cls] groups mismatches for the report and the allowlist. */
class Mismatch(val file: String, val offset: Int, val cls: String, val expected: String, val actual: String) {
    val key get() = "$file:$offset"
    override fun toString() = "$key  [$cls]  expected $expected got $actual"
}

/**
 * Aligned diff of two [DumpNode] trees: Roslyn's (`expected`) against ours (`actual`, usually from [PsiToDump]).
 * Nodes and tokens are compared by kind and span; `contextualKind` is not compared. Mismatch classes:
 * `A -> B kind`, `K range`, `K missing` (in Roslyn's tree only), `K extra` (in ours only).
 *
 * Siblings are aligned by start offset, so one missing or extra element does not cascade: a missing or extra wrapper
 * node is reported once and its children are compared in place; a kind mismatch is reported once without descending.
 *
 * Normalisation of the expected tree, each counted:
 *  - missing tokens ([missingTokens]) have no PSI counterpart (an error element at most) and are skipped;
 *  - other zero-width tokens ([zeroWidthTokens]: `EndOfFileToken`, `OmittedArraySizeExpressionToken`,
 *    `OmittedTypeArgumentToken`) cannot be PSI leaves and are skipped;
 * and of the actual tree (symmetrically, so that a dump can be diffed with itself): zero-width tokens are skipped
 * without counting; tokens inside a `SkippedTokensTrivia` span of the dump are removed ([skippedTokens]): Roslyn
 * keeps them as trivia, our parser inside error elements.
 *
 * One instance accumulates over many files.
 */
class TreeDiff {
    val mismatches = ArrayList<Mismatch>()
    var expectedNodes = 0L
        private set
    var expectedTokens = 0L
        private set
    var missingTokens = 0L
        private set
    var zeroWidthTokens = 0L
        private set
    var skippedTokens = 0L
        private set

    /** Compares the main tree of [expected] with [actual] (the roots of our tree). */
    fun diff(expected: DumpFile, actual: List<DumpNode>): List<Mismatch> {
        val skipped = expected.trivia.filter { it.kind == "SkippedTokensTrivia" }.map { it.start until it.end }
        return diff(expected.path, expected.roots, actual, skipped)
    }

    /** Compares two root lists; returns the mismatches of this call (also appended to [mismatches]). */
    fun diff(file: String, expected: List<DumpNode>, actual: List<DumpNode>, skipped: List<IntRange> = emptyList()): List<Mismatch> {
        val before = mismatches.size
        val e = expected.mapNotNull { normaliseExpected(it) }
        val a = actual.mapNotNull { normaliseActual(it, skipped) }
        children(file, e, a)
        return mismatches.subList(before, mismatches.size).toList()
    }

    private fun normaliseExpected(n: DumpNode): DumpNode? {
        if (n.isToken) {
            if (n.missing) {
                missingTokens++
                return null
            }
            if (n.start == n.end) {
                zeroWidthTokens++
                return null
            }
            expectedTokens++
            return n
        }
        expectedNodes++
        return n.copy(children = n.children.mapNotNull { normaliseExpected(it) })
    }

    private fun normaliseActual(n: DumpNode, skipped: List<IntRange>): DumpNode? {
        if (n.isToken) {
            if (n.start == n.end) return null
            if (skipped.any { n.start in it && n.end - 1 in it }) {
                skippedTokens++
                return null
            }
            return n
        }
        return n.copy(children = n.children.mapNotNull { normaliseActual(it, skipped) })
    }

    private fun pair(file: String, e: DumpNode, a: DumpNode) {
        if (e.kind != a.kind || e.isToken != a.isToken) {
            mismatches += Mismatch(file, e.start, "${e.kind} -> ${a.kind} kind", e.toString(), a.toString())
            return
        }
        if (e.start != a.start || e.end != a.end) {
            mismatches += Mismatch(file, e.start, "${e.kind} range", e.toString(), a.toString())
        }
        children(file, e.children, a.children)
    }

    private fun same(x: DumpNode?, y: DumpNode) = x != null && x.kind == y.kind && x.start == y.start

    private fun children(file: String, expected: List<DumpNode>, actual: List<DumpNode>) {
        val exp = expected.toMutableList()
        val act = actual.toMutableList()
        var i = 0
        var j = 0
        while (i < exp.size || j < act.size) {
            val e = exp.getOrNull(i)
            val a = act.getOrNull(j)
            when {
                e != null && a != null && e.start == a.start -> when {
                    e.kind == a.kind -> { pair(file, e, a); i++; j++ }
                    // A zero-width or extra sibling before the one that matches.
                    same(exp.getOrNull(i + 1), a) -> { missing(file, e); i++ }
                    same(act.getOrNull(j + 1), e) -> { extra(file, a); j++ }
                    // A wrapper on one side only: report it once, compare its children in place.
                    !e.isToken && same(e.children.firstOrNull(), a) -> {
                        missing(file, e)
                        exp.removeAt(i)
                        exp.addAll(i, e.children)
                    }
                    !a.isToken && same(a.children.firstOrNull(), e) -> {
                        extra(file, a)
                        act.removeAt(j)
                        act.addAll(j, a.children)
                    }
                    else -> { pair(file, e, a); i++; j++ }
                }
                // The same element with a different start.
                e != null && a != null && e.kind == a.kind && e.isToken == a.isToken && e.start < a.end && a.start < e.end -> {
                    pair(file, e, a); i++; j++
                }
                e != null && (a == null || e.start < a.start) -> { missing(file, e); i++ }
                else -> { extra(file, a!!); j++ }
            }
        }
    }

    private fun missing(file: String, e: DumpNode) {
        mismatches += Mismatch(file, e.start, "${e.kind} missing", e.toString(), "(none)")
    }

    private fun extra(file: String, a: DumpNode) {
        mismatches += Mismatch(file, a.start, "${a.kind} extra", "(none)", a.toString())
    }
}

/**
 * Deliberate differences: `testData/<area>/<name>-allowlist.txt`, one entry per line, `#` starts a comment.
 * `class <mismatch class>` allows a whole class, `<file>:<offset>` a single mismatch. Empty by default (CLAUDE.md);
 * entries that matched nothing are reported as stale.
 */
class Allowlist(val classes: Set<String>, val keys: Set<String>) {
    class Partition(val real: List<Mismatch>, val allowed: List<Mismatch>, val stale: List<String>)

    fun partition(mismatches: List<Mismatch>): Partition {
        val (allowed, real) = mismatches.partition { it.cls in classes || it.key in keys }
        val usedClasses = allowed.map { it.cls }.toSet()
        val usedKeys = allowed.map { it.key }.toSet()
        val stale = classes.filter { it !in usedClasses }.map { "class $it" } + keys.filter { it !in usedKeys }
        return Partition(real, allowed, stale.sorted())
    }

    val size get() = classes.size + keys.size

    companion object {
        val EMPTY = Allowlist(emptySet(), emptySet())

        fun parse(lines: List<String>): Allowlist {
            val entries = lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            val (classes, keys) = entries.partition { it.startsWith("class ") }
            return Allowlist(classes.map { it.removePrefix("class ").trim() }.toSet(), keys.toSet())
        }

        /** Reads `testData/<area>/<name>-allowlist.txt`; a missing file is an empty allowlist. */
        fun read(area: String, name: String): Allowlist = read(Path.of(CSharpTestUtil.testDataPath("$area/$name-allowlist.txt")))

        fun read(file: Path): Allowlist = if (Files.exists(file)) parse(Files.readAllLines(file)) else EMPTY
    }
}

/** Console report of a corpus diff plus the full list in `build/roslyndump/<name>-mismatches.txt`. */
object DiffReport {
    /**
     * Prints the summary ([facts] first, then the diff counters), mismatch classes by count, up to [examples] examples
     * round-robin over the classes and stale allowlist entries; writes every mismatch (`MISMATCH`/`ALLOWED`) to the
     * report file. Returns the partition for metrics and assertions.
     */
    fun report(
        name: String,
        diff: TreeDiff,
        allowlist: Allowlist = Allowlist.EMPTY,
        facts: Map<String, Any> = emptyMap(),
        examples: Int = 40,
    ): Allowlist.Partition {
        val partition = allowlist.partition(diff.mismatches)
        val real = partition.real
        val file = CSharpTestUtil.buildDir("roslyndump").resolve("$name-mismatches.txt")
        Files.write(file, real.map { "MISMATCH $it" } + partition.allowed.map { "ALLOWED $it" })

        val rows = LinkedHashMap<String, Any>()
        rows.putAll(facts)
        rows["Roslyn nodes / tokens"] = "${diff.expectedNodes} / ${diff.expectedTokens}"
        rows["ignored tokens"] = "missing ${diff.missingTokens}, zero-width ${diff.zeroWidthTokens}, skipped ${diff.skippedTokens}"
        rows["mismatches"] = "${real.size} (in ${real.map { it.file }.distinct().size} files)"
        rows["allowlisted"] = "${partition.allowed.size} (allowlist entries: ${allowlist.size}, stale: ${partition.stale.size})"
        rows["full list"] = file
        val width = rows.keys.maxOf { it.length } + 1
        println("$name summary")
        rows.forEach { (k, v) -> println("  ${"$k:".padEnd(width)} $v") }
        printClasses("mismatch classes", real)
        printClasses("allowlisted classes", partition.allowed)
        printExamples(real, examples)
        partition.stale.take(20).forEach { println("  STALE ALLOWLIST $it") }
        return partition
    }

    private fun printClasses(title: String, list: List<Mismatch>) {
        if (list.isEmpty()) return
        println("  $title:")
        list.groupingBy { it.cls }.eachCount().entries.sortedByDescending { it.value }
            .forEach { (cls, count) -> println("    %7d  %s".format(count, cls)) }
    }

    /** First [limit] examples, spread over the classes (up to 3 per class, most frequent classes first). */
    private fun printExamples(list: List<Mismatch>, limit: Int) {
        if (list.isEmpty()) return
        println("  first examples:")
        val byClass = list.groupBy { it.cls }.entries.sortedByDescending { it.value.size }
        var printed = 0
        for (round in 0 until 3) {
            for ((_, items) in byClass) {
                if (printed >= limit) return
                items.getOrNull(round)?.let { println("    $it"); printed++ }
            }
        }
    }
}
