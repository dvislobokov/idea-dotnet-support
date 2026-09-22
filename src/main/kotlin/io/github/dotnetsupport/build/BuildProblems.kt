package io.github.dotnetsupport.build

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.lsp.RoslynServerStatus
import java.io.File

/** A diagnostic of the last build, with the text of its line then: the way to find the line again after edits. */
class BuildProblem(val line: Int, val column: Int, val code: String?, val text: String, val isError: Boolean, val lineText: String?) {
    val message: String get() = listOfNotNull(code, text).joinToString(": ")
}

/**
 * The compiler errors and warnings of the last build, for the editor: there is no C# analysis in the IDE, so the build is
 * the one source of real diagnostics. They stay until the next build; a problem whose line has been edited is dropped,
 * and one whose line has moved is followed (see [locate]).
 */
@Service(Service.Level.PROJECT)
class BuildProblems(private val project: Project) {
    @Volatile private var byFile: Map<String, List<BuildProblem>> = emptyMap()

    fun of(path: String): List<BuildProblem> = byFile[key(path)].orEmpty()

    /** The diagnostics of a finished build replace the ones of the build before. [readLines] is the text of a file at that moment. */
    fun replace(messages: Collection<MsBuildMessage>, readLines: (File) -> List<String>? = { file -> runCatching { file.readLines() }.getOrNull() }) {
        byFile = messages.filter { it.line > 0 }.mapNotNull { message -> message.resolveFile()?.let { it to message } }
            .groupBy({ key(it.first.path) }) { (file, message) -> file to message }
            .mapValues { (_, located) ->
                val lines = readLines(located.first().first)
                located.map { (_, message) -> BuildProblem(message.line, message.column, message.code, message.text, message.isError, lines?.getOrNull(message.line - 1)?.trim()) }.distinctBy { listOf(it.line, it.column, it.message) }
            }
        if (!project.isDisposed) ApplicationManager.getApplication().invokeLater({ DaemonCodeAnalyzer.getInstance(project).restart("dotnet build has finished") }, project.disposed)
    }

    fun clear() = replace(emptyList())

    private fun key(path: String): String = FileUtil.toSystemIndependentName(path).lowercase()

    companion object {
        private const val SEARCH_DISTANCE = 200

        fun getInstance(project: Project): BuildProblems = project.service()

        /**
         * The 0-based line of [problem] in the current [lines]: its own, when the text there is still what was compiled;
         * otherwise the nearest line with that text (code was added or removed above). Null: the line itself was edited.
         */
        fun locate(problem: BuildProblem, lines: List<String>): Int? {
            val expected = problem.lineText ?: return (problem.line - 1).takeIf { it in lines.indices }
            val original = problem.line - 1
            if (lines.getOrNull(original)?.trim() == expected) return original
            if (expected.isEmpty()) return null
            return (1..SEARCH_DISTANCE).firstNotNullOfOrNull { distance ->
                listOf(original - distance, original + distance).firstOrNull { lines.getOrNull(it)?.trim() == expected }
            }
        }
    }
}

/** Shows [BuildProblems] in C# files: underlines, the error stripe, the tooltip with the compiler message. */
class BuildProblemsAnnotator : ExternalAnnotator<BuildProblemsAnnotator.Input, List<BuildProblemsAnnotator.Located>>() {
    class Input(val problems: List<BuildProblem>, val document: Document)
    class Located(val problem: BuildProblem, val range: TextRange)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? = collectInformation(file)

    override fun collectInformation(file: PsiFile): Input? {
        // the language server reports the same problems live; the ones of the last build would double them and go stale
        if (RoslynServerStatus.isReady(file.project)) return null
        val path = file.virtualFile?.path ?: return null
        val problems = BuildProblems.getInstance(file.project).of(path).takeIf { it.isNotEmpty() } ?: return null
        return Input(problems, file.viewProvider.document ?: return null)
    }

    override fun doAnnotate(input: Input): List<Located> {
        val text = input.document.immutableCharSequence
        val lines = text.lines()
        return input.problems.mapNotNull { problem ->
            val line = BuildProblems.locate(problem, lines) ?: return@mapNotNull null
            if (line >= input.document.lineCount) return@mapNotNull null
            Located(problem, rangeOf(text, input.document.getLineStartOffset(line), input.document.getLineEndOffset(line), problem.column))
        }
    }

    override fun apply(file: PsiFile, located: List<Located>, holder: AnnotationHolder) {
        for (item in located) {
            if (item.range.endOffset > file.textLength || item.range.isEmpty) continue
            holder.newAnnotation(if (item.problem.isError) HighlightSeverity.ERROR else HighlightSeverity.WARNING, item.problem.message)
                .tooltip("<html>" + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(item.problem.message) + "<br><i>From the last build</i></html>")
                .range(item.range).create()
        }
    }

    companion object {
        /** The word at the column the compiler names; the rest of the line when there is none. */
        fun rangeOf(text: CharSequence, lineStart: Int, lineEnd: Int, column: Int): TextRange {
            val start = (lineStart + (column - 1).coerceAtLeast(0)).coerceIn(lineStart, lineEnd)
            var end = start
            while (end < lineEnd && (text[end].isLetterOrDigit() || text[end] == '_')) end++
            if (end > start) return TextRange(start, end)
            // punctuation, or no column at all: from there to the end of the code on the line
            val from = if (column > 0) start else (lineStart until lineEnd).firstOrNull { !text[it].isWhitespace() } ?: lineStart
            val to = (lineEnd - 1 downTo from).firstOrNull { !text[it].isWhitespace() }?.plus(1) ?: lineEnd
            return TextRange(from, maxOf(to, minOf(from + 1, lineEnd)))
        }
    }
}
