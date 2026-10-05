package io.github.dotnetsupport.codeanalysis

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.DotNetBundle
import io.github.dotnetsupport.settings.DotNetSettings

/**
 * Settings | .NET | Analyzers and Generators: what CodeAnalysisHelper does without the language server (D3, D4), as Rider's Roslyn
 * Analyzers page. Only what works: generators, analyzers on save, suggestions, the idle time of the helper.
 */
class CodeAnalysisConfigurable(private val project: Project) : BoundConfigurable(DotNetBundle.message("page.codeAnalysis")) {
    private val settings get() = DotNetSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group(DotNetBundle.message("codeAnalysis.generators")) {
            row {
                checkBox(DotNetBundle.message("codeAnalysis.runGenerators")).bindSelected(settings::runSourceGenerators)
                    .comment(DotNetBundle.message("codeAnalysis.runGenerators.comment"), maxLineLength = COMMENT_WIDTH)
            }
        }
        group(DotNetBundle.message("codeAnalysis.analyzers")) {
            row {
                checkBox(DotNetBundle.message("codeAnalysis.onSave")).bindSelected(settings::runAnalyzersOnSave)
                    .comment(DotNetBundle.message("codeAnalysis.onSave.comment"), maxLineLength = COMMENT_WIDTH)
            }
            row {
                checkBox(DotNetBundle.message("codeAnalysis.suggestions")).bindSelected(settings::showAnalyzerSuggestions)
                    .comment(DotNetBundle.message("codeAnalysis.suggestions.comment"), maxLineLength = COMMENT_WIDTH)
            }
        }
        row(DotNetBundle.message("codeAnalysis.idle")) {
            spinner(1..240).bindIntValue(settings::codeAnalysisIdleMinutes)
        }.rowComment(DotNetBundle.message("codeAnalysis.idle.comment"), maxLineLength = COMMENT_WIDTH)
    }

    private companion object {
        /** As on Settings | .NET: wider comments made the page wider than the dialog and cut the checkboxes (seen live). */
        const val COMMENT_WIDTH = 56
    }

    override fun apply() {
        super.apply()
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart("settings of the analyzers")
    }
}
