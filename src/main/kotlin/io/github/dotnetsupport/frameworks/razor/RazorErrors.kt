package io.github.dotnetsupport.frameworks.razor

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticSeverity

/**
 * The errors and warnings of the Razor compiler in the editor (RAZOR_PLAN.md, R6): the diagnostics of the C# document of the file
 * ([RazorCSharpDocument.generate]) — the parser's (RZ1xxx, RZ2xxx) and the passes' (components RZ9xxx / RZ10xxx, tag helpers RZ3xxx,
 * directives) — at their spans, with their ids and messages, as the compiler reports them: the port's diagnostics are the compiler's on the
 * corpus (`RazorCodegenCorpusTest` with tag helpers, `RazorCodegenBaselineCorpusTest`). The C# errors of the markup are the C#'s (the
 * injection). A diagnostic of an imported file (`_Imports.razor`) is shown in that file.
 */
object RazorErrors {
    /** The diagnostics to show in [file] now: in its own text, and only the parser's while the project's tag helpers are not known yet. */
    fun diagnostics(file: RazorFile): List<RazorDiagnostic> {
        val generated = RazorCSharpDocument.generate(file) ?: return emptyList()
        // without the descriptors every component would be "an unexpected element" (RZ10012), every bound attribute plain markup
        val known = generated.tagHelperContext != null
        return generated.diagnostics.filter { d ->
            d.span.absoluteIndex >= 0 && (d.span.filePath == null || d.span.filePath == generated.filePath) && (known || isSyntax(d))
        }.distinctBy { Triple(it.id, it.span.absoluteIndex, it.span.length) }
    }

    /** RZ1000–RZ2999: what the parser says of the text alone, true whatever the project. */
    fun isSyntax(diagnostic: RazorDiagnostic): Boolean = diagnostic.id.removePrefix("RZ").toIntOrNull()?.let { it in 1000..2999 } == true

    /** The range of [diagnostic] in a text of [length]: an empty span (an unclosed block at the end) is one character, at the end the last. */
    fun range(diagnostic: RazorDiagnostic, length: Int): TextRange? {
        if (length == 0) return null
        val start = diagnostic.span.absoluteIndex.coerceIn(0, length)
        val end = (start + diagnostic.span.length.coerceAtLeast(0)).coerceAtMost(length)
        if (end > start) return TextRange(start, end)
        return if (start < length) TextRange(start, start + 1) else TextRange(length - 1, length)
    }
}

/** [RazorErrors] on the root of the Razor tree: one pass per file; RZ10012 with "Add @using" of the components of that name. */
class RazorErrorsAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is RazorDocumentPsi) return
        val file = element.containingFile as? RazorFile ?: return
        val length = file.textLength
        for (diagnostic in RazorErrors.diagnostics(file)) {
            val range = RazorErrors.range(diagnostic, length) ?: continue
            val severity = if (diagnostic.severity == RazorDiagnosticSeverity.ERROR) HighlightSeverity.ERROR else HighlightSeverity.WARNING
            var builder = holder.newAnnotation(severity, "${diagnostic.id}: ${diagnostic.message}").range(range)
            if (diagnostic.id == UNEXPECTED_ELEMENT) {
                val name = diagnostic.args.firstOrNull()?.toString()
                if (name != null) for (namespace in RazorAddUsing.namespacesOf(file, name)) builder = builder.withFix(RazorAddUsing(namespace))
            }
            builder.create()
        }
    }

    private companion object {
        /** `ComponentDiagnosticFactory.UnexpectedMarkupElement`: an element named like a component no `@using` brings in. */
        const val UNEXPECTED_ELEMENT = "RZ10012"
    }
}

/**
 * "Add @using X" for an element named like a component of namespace X that the file does not import (RZ10012), as in Rider: the line
 * goes after the file's `@using` lines, else after its leading directives (`@page`, `@layout`, `@inherits`…), else first.
 */
class RazorAddUsing(private val namespace: String) : com.intellij.codeInsight.intention.IntentionAction, com.intellij.codeInsight.intention.HighPriorityAction {
    override fun getText(): String = "Add @using $namespace"
    override fun getFamilyName(): String = "Add @using"
    override fun isAvailable(project: com.intellij.openapi.project.Project, editor: com.intellij.openapi.editor.Editor?, file: com.intellij.psi.PsiFile?): Boolean = true
    override fun startInWriteAction(): Boolean = true

    override fun invoke(project: com.intellij.openapi.project.Project, editor: com.intellij.openapi.editor.Editor?, file: com.intellij.psi.PsiFile?) {
        val document = file?.viewProvider?.document ?: return
        val text = document.immutableCharSequence
        document.insertString(insertionOffset(text), "@using $namespace\n")
    }

    companion object {
        private val USING = Regex("""(?m)^[ \t]*@using[ \t]+[^\r\n]*\r?\n?""")
        private val LEADING_DIRECTIVE = Regex("""^[ \t]*@(page|layout|inherits|attribute|rendermode|namespace|implements|typeparam|model|inject)\b[^\r\n]*\r?\n?""")

        /** Where the new `@using` line goes in [text]. */
        fun insertionOffset(text: CharSequence): Int {
            USING.findAll(text).lastOrNull()?.let { return it.range.last + 1 }
            var offset = 0
            while (true) {
                val directive = LEADING_DIRECTIVE.find(text.subSequence(offset, text.length)) ?: return offset
                if (directive.range.first != 0) return offset
                offset += directive.value.length
            }
        }

        /** The namespaces of the components of [file]'s project named [name] (the fully qualified name match of each, `Ns.Name`). */
        fun namespacesOf(file: RazorFile, name: String): List<String> {
            val virtualFile = file.originalFile.viewProvider.virtualFile
            val projectFile = io.github.dotnetsupport.msbuild.DotNetProjects.findOwningProject(virtualFile) ?: return emptyList()
            val descriptors = RazorTagHelperService.getInstance(file.project).tagHelpers(projectFile) ?: return emptyList()
            return descriptors.asSequence()
                .filter { it.kind == io.github.dotnetsupport.razor.lang.syntax.TagHelperKind.Component && it.isFullyQualifiedNameMatch }
                .mapNotNull { RazorTagHelperEditor.typeNameOf(it) }
                .filter { it.substringAfterLast('.') == name && '.' in it }
                .map { it.substringBeforeLast('.') }
                .distinct().sorted().toList()
        }
    }
}
