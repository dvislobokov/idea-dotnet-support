package io.github.dotnetsupport.testing

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element

enum class TestOutcome { PASSED, FAILED, SKIPPED }

class TrxTestResult(
    /** `Calc.Tests.CalculatorTests`; nested classes are separated with `+`. */
    val className: String,
    val methodName: String,
    /** Name inside the class: `Divides(a: 4, b: 2, r: 2)` for a theory case, the method name otherwise. */
    val displayName: String,
    val outcome: TestOutcome,
    val durationMs: Long,
    /** Failure message, or the reason a test is skipped. */
    val message: String?,
    val stackTrace: String?,
    val stdOut: String?,
    /** The test assembly (`codeBase` of the definition): a run over several target frameworks has the same test once per assembly. */
    val source: String? = null,
) {
    /** What `dotnet test --filter FullyQualifiedName~...` matches against. */
    val fullyQualifiedName: String get() = "$className.$methodName"
}

/** Visual Studio Test Results file, written by `dotnet test --logger trx`. */
object TrxParser {
    private val FAILED = setOf("failed", "error", "timeout", "aborted")

    fun parse(text: CharSequence): List<TrxTestResult> {
        val root = try {
            JDOMUtil.load(text.toString().removePrefix("﻿"))
        } catch (_: Exception) {
            return emptyList()
        }
        // Elements are in the TeamTest namespace: they are matched by local name.
        val definitions = root.child("TestDefinitions")?.children.orEmpty().associateBy({ it.getAttributeValue("id") }) { it.child("TestMethod") }

        return root.child("Results")?.children.orEmpty().filter { it.name == "UnitTestResult" }.map { result ->
            val testName = result.getAttributeValue("testName").orEmpty()
            val method = definitions[result.getAttributeValue("testId")]
            val className = method?.getAttributeValue("className") ?: testName.substringBefore('(').substringBeforeLast('.', "")
            val methodName = method?.getAttributeValue("name") ?: testName.substringBefore('(').substringAfterLast('.')
            val output = result.child("Output")
            val error = output?.child("ErrorInfo")
            TrxTestResult(
                className, methodName,
                displayName = testName.removePrefix("$className.").ifEmpty { methodName },
                outcome = when (result.getAttributeValue("outcome").orEmpty().lowercase()) {
                    "passed" -> TestOutcome.PASSED
                    in FAILED -> TestOutcome.FAILED
                    else -> TestOutcome.SKIPPED // NotExecuted, Inconclusive, Pending, ...
                },
                durationMs = parseDuration(result.getAttributeValue("duration")),
                message = error?.child("Message")?.text?.normalizeLines()?.trim()?.ifEmpty { null },
                stackTrace = error?.child("StackTrace")?.text?.normalizeLines()?.trimEnd()?.ifEmpty { null },
                stdOut = output?.child("StdOut")?.text?.normalizeLines()?.ifEmpty { null },
                source = method?.getAttributeValue("codeBase"),
            )
        }
    }

    /** `00:00:01.2345678` */
    internal fun parseDuration(value: String?): Long {
        val parts = value?.split(':') ?: return 0
        if (parts.size != 3) return 0
        val seconds = parts[2].toDoubleOrNull() ?: return 0
        return ((parts[0].toLongOrNull() ?: 0) * 3_600_000 + (parts[1].toLongOrNull() ?: 0) * 60_000 + seconds * 1000).toLong()
    }

    private fun Element.child(name: String): Element? = children.firstOrNull { it.name == name }
    private fun String.normalizeLines(): String = replace("\r\n", "\n").replace('\r', '\n')
}

/** Counts of the last run of a test project, as the `.trx` has them: a theory case or a skipped test counts here, but not in the sources. */
data class TestRunSummary(val passed: Int, val failed: Int, val skipped: Int) {
    val total: Int get() = passed + failed + skipped

    companion object {
        fun of(results: List<TrxTestResult>) = TestRunSummary(
            results.count { it.outcome == TestOutcome.PASSED }, results.count { it.outcome == TestOutcome.FAILED }, results.count { it.outcome == TestOutcome.SKIPPED })

        private val byProject = java.util.concurrent.ConcurrentHashMap<String, TestRunSummary>()

        /** Keyed by the project directory; the explorer reads it when it is reloaded. */
        fun record(projectDirectory: String, summary: TestRunSummary) { byProject[normalize(projectDirectory)] = summary }
        fun last(projectDirectory: String): TestRunSummary? = byProject[normalize(projectDirectory)]
        private fun normalize(path: String) = path.replace('\\', '/').trimEnd('/').lowercase()

        /** The text of a project node: methods found in the sources (a theory is one), then what the last run reported (every case). */
        fun label(methods: Int, last: TestRunSummary?): String {
            val found = "$methods test methods"
            if (last == null) return found
            val parts = listOf(last.passed to "passed", last.failed to "failed", last.skipped to "skipped").filter { it.first > 0 }.joinToString(", ") { "${it.first} ${it.second}" }
            return "$found \u00b7 last run ${last.total}" + if (parts.isEmpty()) "" else ": $parts"
        }
    }
}
