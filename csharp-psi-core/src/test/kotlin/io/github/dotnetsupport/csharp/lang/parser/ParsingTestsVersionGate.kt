package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import java.nio.file.Path

/**
 * The language version a snippet of `.corpus/parsing-tests` was written for, from the `options` and `classDefault`
 * columns of `index.tsv` (the options argument as written in Roslyn's test, and the options of the test class's
 * `ParseTree`/`ParseNode` override). [versions] is null when the options are a variable the extractor cannot see
 * (a theory parameter, a field), [script] when they are `TestOptions.Script` (or a test passes `Script` through).
 */
data class SnippetOptions(val script: Boolean, val versions: List<CSharpLanguageVersion>?)

object ParsingTestsOptions {
    /**
     * Every effective version: an input whose version is not known statically is diffed at each of them (both sides
     * parse at the same version, so any version is a valid check).
     */
    val grid: List<CSharpLanguageVersion> get() = CSharpLanguageVersion.effectiveVersions

    /** Resolves [options] (`TestOptions.Regular9`, `options`, ...) through [classDefault] (`options ?? TestOptions.Script`). */
    fun resolve(options: String, classDefault: String): SnippetOptions {
        val o = options.trim()
        val d = classDefault.trim()
        // The class default wraps the argument (`options ?? X`, `(options ?? X).WithLanguageVersion(V)`).
        val expr = if (d.isNotEmpty() && Regex("\\boptions\\b").containsMatchIn(d)) {
            val inner = if (o.isEmpty() || o == "null") "null" else o
            d.replace(Regex("\\boptions\\b"), Regex.escapeReplacement("($inner)"))
        } else o
        return evaluate(expr)
    }

    private fun evaluate(raw: String): SnippetOptions {
        // `cond ? A : B` (a theory over a bool): both branches.
        val masked = raw.replace("??", "\u0000\u0000")
        val q = masked.indexOf('?')
        val c = masked.lastIndexOf(" : ")
        if (q > 0 && c > q) {
            val a = evaluate(raw.substring(q + 1, c))
            val b = evaluate(raw.substring(c + 3))
            val versions = if (a.versions == null || b.versions == null) null else (a.versions + b.versions).distinct()
            return SnippetOptions(a.script || b.script, versions)
        }
        val resolved = evaluateSimple(simplifyCoalesce(raw.trim()))
        // Options passed through a variable to a helper defaulting to `TestOptions.Script` may be script options.
        return if (resolved.versions == null && raw.contains("Script")) resolved.copy(script = true) else resolved
    }

    private fun evaluateSimple(expr: String): SnippetOptions {
        val script = expr.contains("Script")
        // The last `WithLanguageVersion` wins.
        val with = Regex("WithLanguageVersion\\(([^()]*)\\)").findAll(expr).lastOrNull()
        if (with != null) {
            val arg = with.groupValues[1].trim()
            val version = Regex("^LanguageVersion\\.(\\w+)$").find(arg)?.groupValues?.get(1)?.let(::byName)
            return SnippetOptions(script, version?.let { listOf(it) })
        }
        if (expr.isEmpty() || expr == "null" || expr == "(null)") return SnippetOptions(script, listOf(CSharpLanguageVersion.Default))
        if (expr.startsWith("CSharpParseOptions.Default")) return SnippetOptions(script, listOf(CSharpLanguageVersion.Default))
        val base = Regex("^\\(?TestOptions\\.(\\w+)").find(expr)?.groupValues?.get(1) ?: return SnippetOptions(script, null)
        return SnippetOptions(script, testOptions(base)?.let { listOf(it) })
    }

    /** `(null) ?? X` → `X`, `(Y) ?? X` → `Y`. */
    private fun simplifyCoalesce(expr: String): String {
        var e = expr
        while (true) {
            val m = Regex("\\(\\(([^()]*)\\)\\s*\\?\\?\\s*([^()]*)\\)").find(e) ?: Regex("^\\(([^()]*)\\)\\s*\\?\\?\\s*(.*)$").find(e) ?: return e
            val left = m.groupValues[1].trim()
            val chosen = if (left == "null") m.groupValues[2].trim() else left
            val wrapped = m.value.startsWith("((")
            e = e.replaceRange(m.range, if (wrapped) "($chosen)" else chosen)
        }
    }

    /** `TestOptions.<name>` at `roslynCommit` (Test/Utilities/CSharp/TestOptions.cs); null when unknown. */
    private fun testOptions(name: String): CSharpLanguageVersion? = when (name) {
        "Regular", "Script", "RegularDefault", "RegularWithDocumentationComments", "RegularWithLegacyStrongName" -> CSharpLanguageVersion.Default
        "RegularPreview", "RegularNext", "RegularPreviewWithDocumentationComments", "RegularWithPatternCombinators",
        "RegularWithExtendedPropertyPatterns", "RegularWithListPatterns", "RegularWithExtendedPartialMethods",
        -> CSharpLanguageVersion.Preview
        "RegularWithoutRecursivePatterns" -> CSharpLanguageVersion.CSharp7_3
        "RegularWithRecursivePatterns", "RegularWithoutPatternCombinators", "WithoutCovariantReturns" -> CSharpLanguageVersion.CSharp8
        "WithCovariantReturns" -> CSharpLanguageVersion.CSharp9
        "RegularWithFileScopedNamespaces" -> CSharpLanguageVersion.CSharp10
        "WithoutImprovedOverloadCandidates" -> CSharpLanguageVersion.CSharp7_2
        else -> Regex("^Regular(\\d+(?:_\\d+)?)$").find(name)?.groupValues?.get(1)?.let { byName("CSharp$it") }
    }

    private fun byName(name: String): CSharpLanguageVersion? = CSharpLanguageVersion.entries.firstOrNull { it.name == name }
}

/**
 * The inputs of `.corpus/parsing-tests` written for a script or an explicit language version (the old
 * `scriptOrOldLangVersion` bucket, [ParsingTestsIndex.isScriptOrOldVersion]), each diffed at its own version: the
 * oracle runs with `--langversion` and our parser with the same [CSharpLanguageVersion]; an input whose version is a
 * variable is diffed at every version of [ParsingTestsOptions.grid], a `cond ? A : B` input at both. Script inputs
 * (`SourceCodeKind.Script`, Roslyn's `IsScript` paths, not ported) are a bucket of their own, diffed as regular code at
 * their version.
 */
class ParsingTestsVersionGate(name: String) {
    val versioned = SliceGate("$name-old-version")
    val script = SliceGate("$name-script")
    var versionedFiles = 0L
    var unresolvedFiles = 0L
    var scriptFiles = 0L

    /** Runs [mode] over the [special] inputs (relative path → options) with the extension of [mode]. */
    fun run(root: Path, mode: SliceParseHarness.Mode, special: Map<String, SnippetOptions>) {
        val extension = if (mode == SliceParseHarness.Mode.File) ".cs" else "." + mode.dumpMode
        val inputs = special.filterKeys { it.endsWith(extension) }
        val byVersion = LinkedHashMap<CSharpLanguageVersion, MutableList<String>>()
        val scriptPaths = HashSet<String>()
        for ((path, options) in inputs) {
            if (options.script) {
                scriptFiles++
                scriptPaths += path
            } else {
                versionedFiles++
                if (options.versions == null) unresolvedFiles++
            }
            for (v in options.versions ?: ParsingTestsOptions.grid) byVersion.getOrPut(v) { ArrayList() } += path
        }
        for ((version, paths) in byVersion.entries.sortedBy { it.key.effective().value }) {
            versioned.run(
                root, mode,
                filters = listOf({ d -> ParsingTestsIndex.normalize(d.path) in scriptPaths }, { true }),
                gates = listOf(script, versioned),
                version = version,
                includes = paths,
            )
        }
    }

    /** Metrics of both buckets under [prefix] (`oldLangVersion*`, `script*`). */
    fun metrics(into: MutableMap<String, Long>) {
        bucket(into, "oldLangVersion", versioned, versionedFiles)
        into["oldLangVersionUnresolvedFiles"] = unresolvedFiles
        bucket(into, "script", script, scriptFiles)
    }

    private fun bucket(into: MutableMap<String, Long>, prefix: String, gate: SliceGate, files: Long) {
        val s = gate.stats
        into["${prefix}Files"] = files
        into["${prefix}Diffs"] = s.validFiles + s.invalidFiles
        into["${prefix}ValidDiffs"] = s.validFiles
        into["${prefix}ValidMismatchedDiffs"] = s.validMismatchedFiles
        into["${prefix}SpuriousErrorDiffs"] = s.validSpuriousErrorFiles
        into["${prefix}InvalidMismatchedDiffs"] = s.invalidMismatchedFiles
        into["${prefix}Exceptions"] = s.validExceptions + s.invalidExceptions
    }

    companion object {
        /** Metric keys of [metrics] that are counts, not results. */
        val informational = setOf(
            "oldLangVersionFiles", "oldLangVersionDiffs", "oldLangVersionValidDiffs", "oldLangVersionUnresolvedFiles",
            "scriptFiles", "scriptDiffs", "scriptValidDiffs",
        )
    }
}
