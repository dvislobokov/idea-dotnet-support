package io.github.dotnetsupport.lang

import io.github.dotnetsupport.lsp.RoslynLanguageServer
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynOptions

/**
 * The «Analysis» options of Settings | .NET | Language Server, read the way the server gets them (`additionalOptions` included): what the
 * server does with them when it runs, the plugin's own pass does without it (`NativeCSharpSolutionProblems`, `CodeAnalysisService`).
 * As Roslyn's `CompilerDiagnosticsScope` / `BackgroundAnalysisScope`: [OPEN_FILES] reports on the open documents, [FULL_SOLUTION] on every
 * file of the solution in the background (the Problems tool window), [NONE] reports nothing at all — not even on open files; a build is
 * then the only source of errors.
 */
object AnalysisScopes {
    const val COMPILER = "background_analysis.dotnet_compiler_diagnostics_scope"
    const val ANALYZER = "background_analysis.dotnet_analyzer_diagnostics_scope"
    const val OPEN_FILES = "openFiles"
    const val FULL_SOLUTION = "fullSolution"
    const val NONE = "none"

    fun compiler(): String = of(COMPILER)
    fun analyzer(): String = of(ANALYZER)

    private fun of(section: String): String =
        RoslynLanguageServer.configuration(listOf(section), RoslynLanguageServerSettings.getInstance(), null).single()?.toString() ?: OPEN_FILES

    /** Sets [section] of the page to [value], for tests and actions; the default value clears the entry. */
    fun set(section: String, value: String) {
        val option = RoslynOptions.ALL.first { it.section == section }
        RoslynLanguageServerSettings.getInstance().setValue(option, value)
    }
}
