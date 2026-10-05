package io.github.dotnetsupport.lang

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
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
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import java.util.concurrent.atomic.AtomicReference

/**
 * `DIAGNOSTICS` on csharp-psi's tree, the syntactic part (CSHARP_PSI_MIGRATION.md, step 9, task A3): Roslyn's syntax errors of the parser,
 * the lexer and the directives with Roslyn's codes, messages and spans ([CSharpSyntaxDiagnostics]), shown as `CS1002: ; expected`. Since
 * 0.1.74 also the semantic part ([NativeCSharpSemanticDiagnostics], task C4c): the errors the resolver is sure of and the gray of unused
 * `using` directives. The server is still asked with NATIVE; its diagnostics the native pass reports too give way ([repeatsNative],
 * `RoslynLspIntegration`), so nothing is shown twice and nothing the plugin does not know yet is lost.
 */
object NativeCSharpDiagnostics {
    /** The native diagnostics answer for [file]: the switch, and a file of the native tree. */
    fun serves(file: PsiFile): Boolean = file is CSharpFile && file.compilationUnit != null && CSharpFeatures.native(CSharpFeature.DIAGNOSTICS, file.project)

    /** The syntax diagnostics of [file] (native tree), cached until the next change of PSI. */
    fun of(file: CSharpFile): List<CSharpSyntaxDiagnostic> = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(CSharpSyntaxDiagnostics.of(file.node, CSharpPreprocessorSymbols.forFile(file)), PsiModificationTracker.MODIFICATION_COUNT)
    }

    /**
     * Whether a highlight of another source (the server) with [description] starting at [offset] of [file] repeats a syntax error the
     * native pass shows: the same message of Roslyn on the same line. The server is the same Roslyn, so the texts and lines agree; the
     * native ones carry the code (`CS1002: ; expected`) and never match themselves.
     */
    fun repeatsNative(file: PsiFile, description: String?, offset: Int, code: String? = null): Boolean {
        if (description == null || !serves(file)) return false
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return false
        if (offset > document.textLength) return false
        val line = document.getLineNumber(offset)
        // the semantic errors of the native pass (C4c): the same code on the same line, the texts of two Roslyn versions may differ a little
        if (code != null) NativeCSharpSemanticDiagnostics.codesByLine(file as CSharpFile)[line]?.let { if (NativeCSharpSemanticDiagnostics.sameCode(it, code)) return true }
        if (of(file as CSharpFile).any { it.message == description && it.start <= document.textLength && document.getLineNumber(it.start) == line }) return true
        // the errors of `using` the native pass reports from the types of C2 (CS1674 and its kin)
        return NativeCSharpUsingChecks.of(file).any { it.error.message == description && it.range.startOffset <= document.textLength && document.getLineNumber(it.range.startOffset) == line }
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
    }
}

/** The errors on screen follow «Errors and warnings» of the Language Server page at once: highlighting restarts on Apply, as for the colors. */
class NativeCSharpDiagnosticsSwitch : RoslynLanguageServerSettings.Listener {
    override fun settingsChanged(restart: Boolean) {
        val native = CSharpFeatures.native(CSharpFeature.DIAGNOSTICS)
        if (last.getAndSet(native) == native) return
        ApplicationManager.getApplication().invokeLater({
            for (project in ProjectManager.getInstance().openProjects) if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
        }, ModalityState.nonModal())
    }

    private companion object {
        val last = AtomicReference<Boolean?>(null)
    }
}
