package io.github.dotnetsupport.testing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.msbuild.TestFramework
import java.io.File
import java.io.StringReader

/** How `dotnet test` reaches the tests of a project. */
enum class TestMode {
    /** VSTest: `--filter`, `--logger trx`, `--collect`. Also a Testing Platform project through its VSTest bridge. */
    VSTEST,
    /** `dotnet test` of SDK 8 / 9 with `TestingPlatformDotnetTestSupport`: the options of the platform go after `--`. */
    TESTING_PLATFORM_AFTER_SEPARATOR,
    /** The `dotnet test` of SDK 10 opted into Microsoft.Testing.Platform (`global.json`): `--project`, and the options of the platform directly. */
    TESTING_PLATFORM_RUNNER,
}

/**
 * Microsoft.Testing.Platform (MSTest runner, xunit.v3 runner, TUnit): the test project is an executable with its own options, and the
 * report, the filter and the coverage are asked for differently than from VSTest. Checked against `dotnet test --help` of SDK 10.0.401 in
 * both modes (2026-09-29) and the option names of the platform 1.5 (`Microsoft.Testing.Platform.dll`).
 */
object TestingPlatform {
    const val TRX_FILE_NAME = "results.trx"

    fun mode(project: MsBuildProject, runnerConfigured: Boolean): TestMode = when {
        runnerConfigured -> TestMode.TESTING_PLATFORM_RUNNER
        project.usesTestingPlatform && project.testingPlatformDotnetTestSupport -> TestMode.TESTING_PLATFORM_AFTER_SEPARATOR
        else -> TestMode.VSTEST
    }

    /**
     * The arguments of `dotnet test` for [projectPath]. [selected]: `-c` / `--framework` of the toolbar; [filter]: the VSTest expression
     * the gutter and the explorer build (`FullyQualifiedName~A.B.C|...`), translated for the platform of the framework; [extra]: the
     * arguments of the configuration, options of `dotnet test` in VSTest mode and of the platform otherwise.
     */
    fun arguments(mode: TestMode, framework: TestFramework?, projectPath: String, selected: List<String>, filter: String?, resultsDirectory: File?, coverage: Boolean, extra: List<String>): List<String> {
        val expression = filter?.takeIf { it.isNotBlank() }
        return when (mode) {
            TestMode.VSTEST -> buildList {
                add("test"); add(projectPath); addAll(selected)
                expression?.let { add("--filter"); add(it) }
                if (resultsDirectory != null) { add("--logger"); add("trx;LogFileName=$TRX_FILE_NAME"); add("--results-directory"); add(resultsDirectory.path) }
                if (coverage) add("--collect:XPlat Code Coverage")
                addAll(extra)
            }
            TestMode.TESTING_PLATFORM_AFTER_SEPARATOR -> buildList {
                add("test"); add(projectPath); addAll(selected)
                add("--")
                addAll(platformOptions(framework, expression, resultsDirectory, coverage))
                addAll(extra)
            }
            TestMode.TESTING_PLATFORM_RUNNER -> buildList {
                add("test"); add("--project"); add(projectPath); addAll(selected)
                if (resultsDirectory != null) { add("--results-directory"); add(resultsDirectory.path) }
                addAll(platformOptions(framework, expression, null, coverage))
                addAll(extra)
            }
        }
    }

    /** The options of the platform: the TRX report (the xunit.v3 runner names its own), the filter of the framework, the coverage extension. */
    fun platformOptions(framework: TestFramework?, expression: String?, resultsDirectory: File?, coverage: Boolean): List<String> = buildList {
        if (framework == TestFramework.XUNIT) { add("--report-xunit-trx"); add("--report-xunit-trx-filename"); add(TRX_FILE_NAME) }
        else { add("--report-trx"); add("--report-trx-filename"); add(TRX_FILE_NAME) }
        if (resultsDirectory != null) { add("--results-directory"); add(resultsDirectory.path) }
        expression?.let { addAll(filterOptions(framework, it)) }
        // Microsoft.Testing.Extensions.CodeCoverage; the file is looked for by the same name as the one of coverlet
        if (coverage) { add("--coverage"); add("--coverage-output-format"); add("cobertura"); add("--coverage-output"); add("coverage.cobertura.xml") }
    }

    /** `FullyQualifiedName~A.B.C|FullyQualifiedName~A.D.` -> the names: (`A.B.C`, method) and (`A.D`, class). */
    fun targets(expression: String): List<Pair<String, Boolean>> = expression.split('|').mapNotNull { part ->
        val value = part.trim().removePrefix("FullyQualifiedName~").takeIf { it.isNotEmpty() && !part.contains('=') } ?: return@mapNotNull null
        val name = value.replace(Regex("""\\(.)"""), "$1")
        if (name.endsWith(".")) name.removeSuffix(".") to true else name to false
    }

    /**
     * MSTest understands the VSTest expression (its bridge); xunit.v3 has `--filter-class` / `--filter-method`; TUnit has one tree-node
     * filter `/Assembly/Namespace/Class/Method` with `*` and `(a|b)` per segment — several classes at once are matched more broadly.
     */
    fun filterOptions(framework: TestFramework?, expression: String): List<String> {
        val targets = targets(expression)
        return when (framework) {
            TestFramework.XUNIT -> targets.flatMap { (name, isClass) -> listOf(if (isClass) "--filter-class" else "--filter-method", name) }
            TestFramework.TUNIT -> {
                if (targets.isEmpty()) return listOf("--filter", expression)
                val classes = targets.map { (name, isClass) -> (if (isClass) name else name.substringBeforeLast('.')).substringAfterLast('.') }.distinct()
                val methods = targets.filter { !it.second }.map { it.first.substringAfterLast('.') }.distinct()
                val classPart = if (classes.size == 1) classes.single() else "(${classes.joinToString("|")})"
                val methodPart = if (targets.any { it.second } || methods.isEmpty()) "*" else if (methods.size == 1) methods.single() else "(${methods.joinToString("|")})"
                listOf("--treenode-filter", "/*/*/$classPart/$methodPart")
            }
            else -> listOf("--filter", expression)
        }
    }

    /** `global.json` with `"test": { "runner": "Microsoft.Testing.Platform" }` (SDK 10), or the older `dotnet.config` with `[dotnet.test.runner]`, up from [directory]. */
    fun isRunnerConfigured(directory: VirtualFile?): Boolean {
        var current = directory
        while (current != null) {
            current.findChild("global.json")?.let { runCatching { VfsUtilCore.loadText(it) }.getOrNull() }?.let { if (isRunnerInGlobalJson(it)) return true }
            current.findChild("dotnet.config")?.let { runCatching { VfsUtilCore.loadText(it) }.getOrNull() }?.let { if (isRunnerInDotnetConfig(it)) return true }
            current = current.parent
        }
        return false
    }

    fun isRunnerInGlobalJson(text: String): Boolean {
        val root = try {
            JsonParser.parseReader(JsonReader(StringReader(text)).apply { strictness = Strictness.LENIENT }) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val runner = (root?.get("test") as? JsonObject)?.get("runner")?.takeIf { it.isJsonPrimitive }?.asString
        return runner.equals("Microsoft.Testing.Platform", ignoreCase = true)
    }

    fun isRunnerInDotnetConfig(text: String): Boolean {
        var inSection = false
        for (line in text.lineSequence().map { it.trim() }) {
            if (line.startsWith("[")) inSection = line.equals("[dotnet.test.runner]", ignoreCase = true)
            else if (inSection && line.substringBefore('=').trim().equals("name", ignoreCase = true) && line.substringAfter('=').trim().trim('"').equals("Microsoft.Testing.Platform", ignoreCase = true)) return true
        }
        return false
    }
}
