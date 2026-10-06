package io.github.dotnetsupport.codeanalysis

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.build.BuildProblem
import io.github.dotnetsupport.build.BuildProblems
import io.github.dotnetsupport.lang.AnalysisScopes
import io.github.dotnetsupport.settings.DotNetSettings

/**
 * The diagnostics of the Roslyn analyzers ([CodeAnalysisService.analyze]) in a C# file, with their code fixes on Alt+Enter. They are of
 * the text the helper read at the last save: a diagnostic whose line has been edited since is dropped until the next save, one whose line
 * has moved is followed ([BuildProblems.locate]). Errors and warnings as such; Info (Rider's suggestions) as weak warnings only with «Show
 * suggestions», else as VS and Rider show them by default: nothing to see, the code fixes on Alt+Enter at the caret ([presentation]).
 */
class AnalyzerAnnotator : ExternalAnnotator<AnalyzerAnnotator.Input, List<AnalyzerAnnotator.Located>>() {
    class Input(val file: AnalyzedFile, val document: Document, val suggestions: Boolean)
    class Located(val diagnostic: AnalyzerDiagnostic, val range: TextRange)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? = collectInformation(file)

    override fun collectInformation(file: PsiFile): Input? {
        val service = file.project.getServiceIfCreated(CodeAnalysisService::class.java) ?: return null
        // «Analyzer diagnostics for» none: nothing in the editor, as with the server; Run Code Analysis still lists them in the Build window
        if (!service.analyzersActive || AnalysisScopes.analyzer() == AnalysisScopes.NONE) return null
        val path = file.viewProvider.virtualFile.path
        val analyzed = service.analyzedFile(path)?.takeIf { it.diagnostics.isNotEmpty() } ?: return null
        return Input(analyzed, file.viewProvider.document ?: return null, DotNetSettings.getInstance().showAnalyzerSuggestions)
    }

    override fun doAnnotate(input: Input): List<Located> = locate(input.file, input.document.immutableCharSequence, input.suggestions)

    override fun apply(file: PsiFile, located: List<Located>, holder: AnnotationHolder) {
        val analyzed = file.project.getServiceIfCreated(CodeAnalysisService::class.java)?.analyzedFile(file.viewProvider.virtualFile.path) ?: return
        val suggestions = DotNetSettings.getInstance().showAnalyzerSuggestions
        for (item in located) {
            if (item.range.endOffset > file.textLength) continue
            val d = item.diagnostic
            val severity = presentation(d, suggestions) ?: continue
            var builder = if (severity == HighlightSeverity.INFORMATION) holder.newSilentAnnotation(severity).range(item.range)
                else holder.newAnnotation(severity, "${d.id}: ${d.message}").range(item.range).tooltip(tooltip(d))
            for (title in d.fixes) builder = builder.withFix(AnalyzerFixIntention(analyzed, d, title))
            builder.create()
        }
    }

    companion object {
        /**
         * How [d] shows in the editor: errors and warnings as such; an Info as a weak warning with «Show suggestions», else (the default,
         * as VS and Rider) a silent annotation — not drawn, no tooltip, no mark on the scrollbar — only when it has code fixes to offer on
         * Alt+Enter at the caret; null: not at all.
         */
        fun presentation(d: AnalyzerDiagnostic, suggestions: Boolean): HighlightSeverity? = when (d.severity) {
            AnalyzerSeverity.ERROR -> HighlightSeverity.ERROR
            AnalyzerSeverity.WARNING -> HighlightSeverity.WARNING
            AnalyzerSeverity.INFO -> if (suggestions) HighlightSeverity.WEAK_WARNING else HighlightSeverity.INFORMATION.takeIf { d.fixes.isNotEmpty() }
        }

        /** The diagnostics of [file] in the current [text]: on their lines (followed when moved), the rest of the code fixes kept. */
        fun locate(file: AnalyzedFile, text: CharSequence, suggestions: Boolean): List<Located> {
            val lines = text.lines()
            return file.diagnostics.mapIndexedNotNull { index, d ->
                if (presentation(d, suggestions) == null) return@mapIndexedNotNull null
                val problem = BuildProblem(d.startLine + 1, d.startColumn + 1, d.id, d.message, d.severity == AnalyzerSeverity.ERROR, file.lineTexts.getOrNull(index))
                val line = BuildProblems.locate(problem, lines) ?: return@mapIndexedNotNull null
                val shift = line - d.startLine
                val start = TextPositions.offset(text, line, d.startColumn) ?: return@mapIndexedNotNull null
                val end = TextPositions.offset(text, d.endLine + shift, d.endColumn)?.coerceAtLeast(start) ?: start
                // a diagnostic of no width (an end of a line) still gets a character to hang on
                val range = if (end > start) TextRange(start, end) else TextRange(start, minOf(start + 1, text.length)).takeIf { !it.isEmpty } ?: return@mapIndexedNotNull null
                Located(d, range)
            }
        }

        fun tooltip(d: AnalyzerDiagnostic): String {
            val id = d.helpLink?.let { "<a href=\"${StringUtil.escapeXmlEntities(it)}\">${d.id}</a>" } ?: d.id
            val origin = if (d.source == "generator") "Source generator" else "Roslyn analyzer"
            return "<html>$id: ${StringUtil.escapeXmlEntities(d.message)}<br><i>$origin</i></html>"
        }
    }
}

/** A code fix of an analyzer: the helper works out its edits, the IDE applies them as one command. */
class AnalyzerFixIntention(private val file: AnalyzedFile, private val diagnostic: AnalyzerDiagnostic, private val title: String) : IntentionAction, PriorityAction {
    override fun getText(): String = title
    override fun getFamilyName(): String = "Roslyn analyzer fix"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true
    override fun startInWriteAction(): Boolean = false
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.NORMAL
    override fun invoke(project: Project, editor: Editor?, psiFile: PsiFile?) = CodeAnalysisService.getInstance(project).applyFix(file, diagnostic, title)
}
