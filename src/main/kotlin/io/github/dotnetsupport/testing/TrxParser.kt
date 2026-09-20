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
