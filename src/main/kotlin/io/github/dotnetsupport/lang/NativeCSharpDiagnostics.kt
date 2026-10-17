package io.github.dotnetsupport.lang

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.dotnetsupport.csharp.lang.CSharpPreprocessorSymbols
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpErrorCode
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpSyntaxDiagnostic
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpSyntaxDiagnostics
import io.github.dotnetsupport.settings.CSharpEditorSettings
import java.util.concurrent.atomic.AtomicReference

/**
 * `DIAGNOSTICS` on csharp-psi's tree, the syntactic part (CSHARP_PSI_MIGRATION.md, step 9, task A3): Roslyn's syntax errors of the parser,
 * the lexer and the directives with Roslyn's codes, messages and spans ([CSharpSyntaxDiagnostics]), shown as `CS1002: ; expected`. Since
 * 0.1.74 also the semantic part ([NativeCSharpSemanticDiagnostics], task C4c): the errors the resolver is sure of and the gray of unused
 * `using` directives.
 */
object NativeCSharpDiagnostics {
    /**
     * The native diagnostics answer for [file]: the switch, a file of the native tree, and «Compiler diagnostics for» not `none` — as
     * Roslyn's `CompilerDiagnosticsScope.None`, which reports no compiler diagnostic for any file, open ones included (the build is then
     * the only source of errors; the last build's errors still show, they are not background analysis).
     */
    fun serves(file: PsiFile): Boolean = file is CSharpFile && file.compilationUnit != null && CSharpFeatures.native(CSharpFeature.DIAGNOSTICS, file) && AnalysisScopes.compiler() != AnalysisScopes.NONE

    /** The syntax diagnostics of [file] (native tree), cached until the next change of PSI. */
    fun of(file: CSharpFile): List<CSharpSyntaxDiagnostic> = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(CSharpSyntaxDiagnostics.of(file.node, CSharpPreprocessorSymbols.forFile(file)), PsiModificationTracker.MODIFICATION_COUNT)
    }

    /**
     * The range to highlight: a zero-width diagnostic takes the character after it, or the end of the line at a line break and at the end of
     * the text, as the platform shows an empty error element.
     */
    fun highlightRange(d: CSharpSyntaxDiagnostic, document: Document): Pair<TextRange, Boolean> {
        val length = document.textLength
        val start = d.start.coerceIn(0, length)
        val end = d.end.coerceIn(start, length)
        if (end > start) return TextRange(start, end) to false
        val text = document.charsSequence
        if (start >= length || text[start] == '\n' || text[start] == '\r') return TextRange(start, start) to true
        return TextRange(start, start + 1) to false
    }
}

/** Registered for C#; acts on the file element only (one pass), and only while [NativeCSharpDiagnostics.serves]. Reads the file alone: dumb-aware. */
class NativeCSharpDiagnosticsAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is CSharpFile || !NativeCSharpDiagnostics.serves(element)) return
        // the output of a source generator (D4): read-only, compiled as it is, nothing to tell the user
        if (element.project.getServiceIfCreated(io.github.dotnetsupport.codeanalysis.CodeAnalysisService::class.java)?.isGenerated(element.viewProvider.virtualFile) == true) return
        val document = PsiDocumentManager.getInstance(element.project).getDocument(element) ?: return
        if (document.textLength != element.textLength) return
        for (d in NativeCSharpDiagnostics.of(element)) {
            val (range, afterEndOfLine) = NativeCSharpDiagnostics.highlightRange(d, document)
            val severity = if (d.isWarning) HighlightSeverity.WARNING else HighlightSeverity.ERROR
            val builder = holder.newAnnotation(severity, d.text).range(range).tooltip(StringUtil.escapeXmlEntities(d.text))
            (if (afterEndOfLine) builder.afterEndOfLine() else builder).create()
        }
        // the semantic ones of `using` (0.1.65): the types of C2, so not while the IDE indexes (of() answers nothing then)
        for (problem in NativeCSharpUsingChecks.of(element)) {
            if (problem.range.endOffset > document.textLength) continue
            holder.newAnnotation(HighlightSeverity.ERROR, problem.error.text).range(problem.range).tooltip(StringUtil.escapeXmlEntities(problem.error.text)).create()
        }
        // the semantic errors of C4c and the `using` directives nothing needs
        val semantic = NativeCSharpSemanticDiagnostics.of(element)
        val requiredFixed = HashSet<TextRange>()
        for (problem in semantic) {
            if (problem.range.endOffset > document.textLength || problem.range.isEmpty) continue
            if (problem.unnecessary) {
                // one gray per directive: CS8933 (a repeat of a global using) is the reason of the CS8019 around it
                if (problem.code != "CS8019") continue
                val reason = semantic.firstOrNull { it.code == "CS8933" && problem.range.contains(it.range) } ?: problem
                holder.newAnnotation(HighlightSeverity.WEAK_WARNING, reason.message).range(problem.range).highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL)
                    .tooltip(StringUtil.escapeXmlEntities(reason.text)).withFix(CSharpRemoveUnusedUsingsFix()).create()
                continue
            }
            if (problem.warning) {
                // as Rider: unreachable code and unused locals gray, the other warnings yellow
                val range = problem.gray?.takeIf { it.endOffset <= document.textLength } ?: problem.range
                var warning = holder.newAnnotation(HighlightSeverity.WARNING, problem.text).range(range).tooltip(StringUtil.escapeXmlEntities(problem.text))
                if (problem.code in GRAY_WARNINGS) warning = warning.highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL)
                for (fix in NativeCSharpWarningFixes.fixesFor(element, problem)) warning = warning.withFix(fix)
                warning.create()
                continue
            }
            var builder = holder.newAnnotation(HighlightSeverity.ERROR, problem.text).range(problem.range).tooltip(StringUtil.escapeXmlEntities(problem.text))
            // a name that is nowhere is painted as the platform paints an unresolved reference (red text), as Rider does: the squiggle alone is easy to miss
            if (problem.code in UNRESOLVED_NAME_CODES) builder = builder.textAttributes(CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES)
            if (problem.imports.isNotEmpty() && problem.name != null) builder = builder.withFix(CSharpImportTypeFix(problem.name, problem.imports, problem.extension, problem.range))
            // one fix for all the CS9035 of a creation: it fills every missing member
            if (problem.code == "CS9035" && requiredFixed.add(problem.range)) AddRequiredMembersFix.at(element, problem.range.startOffset)?.let { builder = builder.withFix(it) }
            // «Create class / field / method …» from the usage (0.1.100)
            for (fix in CSharpCreateFromUsage.fixes(element, problem.code, problem.range, imported = problem.imports.isNotEmpty())) builder = builder.withFix(fix)
            builder.create()
        }
    }

    private companion object {
        val GRAY_WARNINGS = setOf("CS0162", "CS0168", "CS0219")
        /** The errors of a name that is nowhere: CS0103 (no such name), CS0246 (no such type), CS0234 (not in the namespace), CS1061 / CS0117 (no such member). */
        val UNRESOLVED_NAME_CODES = setOf("CS0103", "CS0246", "CS0234", "CS1061", "CS0117")
    }
}

/**
 * The errors on screen follow the «Analysis» scopes of Settings | .NET | C# Editor at once: highlighting restarts on Apply and the
 * solution-wide pass ([NativeCSharpSolutionProblems]) starts or stops.
 */
class NativeCSharpDiagnosticsSwitch : CSharpEditorSettings.Listener {
    override fun settingsChanged() {
        val now = listOf(AnalysisScopes.compiler(), AnalysisScopes.analyzer())
        if (last.getAndSet(now) == now) return
        ApplicationManager.getApplication().invokeLater({
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                DaemonCodeAnalyzer.getInstance(project).restart()
                NativeCSharpSolutionProblems.getInstance(project).settingsChanged()
            }
        }, ModalityState.nonModal())
    }

    private companion object {
        val last = AtomicReference<List<String>?>(null)
    }
}
