package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.build.events.MessageEvent
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.codeanalysis.AnalyzedFile
import io.github.dotnetsupport.codeanalysis.AnalyzerAnnotator
import io.github.dotnetsupport.codeanalysis.AnalyzerSeverity
import io.github.dotnetsupport.codeanalysis.CodeAnalysisAnswers
import io.github.dotnetsupport.codeanalysis.CodeAnalysisReport
import io.github.dotnetsupport.codeanalysis.CodeAnalysisService
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.settings.DotNetSettings

/**
 * The analyzers in the editor as quiet as in VS and Rider (0.1.82): on the answer of the helper for four files of `debug-playground/Console`
 * (`codeanalysis/analyze-suggestions.json`, severities as the compiler computed them with `Console/.editorconfig`), only the warnings are
 * drawn by default; an Info is a silent carrier of its code fixes, or a weak warning with «Show suggestions»; Run Code Analysis groups by
 * severity.
 */
class AnalyzerNoiseTest : BasePlatformTestCase() {
    private val run by lazy { CodeAnalysisAnswers.analysis(JsonParser.parseString(javaClass.getResource("/codeanalysis/analyze-suggestions.json")!!.readText())) }

    fun testSuggestionsAreOffByDefault() {
        assertFalse(DotNetSettings.Settings().showAnalyzerSuggestions)
    }

    fun testHowEachSeverityShows() {
        val byFile = run.diagnostics.groupBy { it.path.substringAfterLast('/') }
        fun shown(file: String, suggestions: Boolean) = byFile.getValue(file).mapNotNull { AnalyzerAnnotator.presentation(it, suggestions) }
        // Analyzers.cs: its .editorconfig section makes three rules warnings — drawn either way
        assertEquals(List(3) { HighlightSeverity.WARNING }, shown("Analyzers.cs", false))
        // Overloads.cs: 18 × IDE0060 (Info, no fix) — 18 weak warnings before 0.1.82, nothing now
        assertEquals(18, byFile.getValue("Overloads.cs").size)
        assertEmpty(shown("Overloads.cs", false))
        assertEquals(List(18) { HighlightSeverity.WEAK_WARNING }, shown("Overloads.cs", true))
        // Navigation.cs: Info with fixes (CA1822 «Make static»…) — silent: not drawn, Alt+Enter at the caret still has the fix
        val navigation = shown("Navigation.cs", false)
        assertTrue(navigation.isNotEmpty() && navigation.all { it == HighlightSeverity.INFORMATION })
        val visibleBefore = run.diagnostics.count { AnalyzerAnnotator.presentation(it, true)!! >= HighlightSeverity.WEAK_WARNING }
        val visibleNow = run.diagnostics.count { (AnalyzerAnnotator.presentation(it, false) ?: HighlightSeverity.INFORMATION) > HighlightSeverity.INFORMATION }
        assertEquals(46 to 3, visibleBefore to visibleNow)
    }

    fun testRunCodeAnalysisGroupsBySeverity() {
        val groups = CodeAnalysisReport.groups(run.diagnostics)
        assertEquals(listOf("Warnings (3)", "Suggestions (43)"), groups.map { it.title })
        assertEquals(listOf(MessageEvent.Kind.WARNING, MessageEvent.Kind.INFO), groups.map { it.kind })
        val suggestions = groups.last().diagnostics
        assertEquals("sorted by file and line", suggestions.sortedWith(compareBy({ it.path.lowercase() }, { it.startLine })), suggestions)
        assertEquals("0 errors, 3 warnings, 43 suggestions", CodeAnalysisReport.summary(groups))
        assertEquals("0 errors, 0 warnings, 0 suggestions", CodeAnalysisReport.summary(CodeAnalysisReport.groups(emptyList())))
    }

    /** In the editor: the warning drawn with its tooltip, the Info not drawn and not described, its fix on Alt+Enter at the caret. */
    fun testSilentSuggestionKeepsItsFix() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val settings = RoslynLanguageServerSettings.getInstance()
        val enabled = settings.state.enabled
        val suggestions = DotNetSettings.getInstance().showAnalyzerSuggestions
        try {
            settings.state.enabled = false
            DotNetSettings.getInstance().showAnalyzerSuggestions = false
            val text = "class Noise\n{\n    public int Twice(int value) => value * 2;\n}\n"
            val file = myFixture.configureByText("AnalyzerNoise.cs", text)
            val make = run.diagnostics.first { it.id == "CA1822" && it.severity == AnalyzerSeverity.INFO && "Make static" in it.fixes }
                .copy(path = file.virtualFile.path, startLine = 2, endLine = 2, startColumn = 15, endColumn = 20, message = "Member 'Twice' does not access instance data")
            val service = CodeAnalysisService.getInstance(project)
            service.putAnalyzedForTests(file.virtualFile.path, AnalyzedFile("C:/s/A/A.csproj", listOf(make), listOf("public int Twice(int value) => value * 2;"),
                CodeAnalysisAnswers.target("C:/s/A/A.csproj", "Debug", null, "r")))
            val infos = myFixture.doHighlighting().filter { text.substring(it.startOffset, it.endOffset) == "Twice" && it.severity == HighlightSeverity.INFORMATION }
            assertTrue(infos.toString(), infos.isNotEmpty() && infos.all { it.description == null })
            assertTrue(myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).none { it.description?.startsWith("CA1822") == true })
            myFixture.editor.caretModel.moveToOffset(text.indexOf("Twice") + 1)
            assertTrue(myFixture.availableIntentions.map { it.text }.toString(), myFixture.availableIntentions.any { it.text == "Make static" })

            DotNetSettings.getInstance().showAnalyzerSuggestions = true
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(myFixture.editor.document.textLength, " ") }
            assertTrue(myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).any { it.description?.startsWith("CA1822") == true })
            service.putAnalyzedForTests(file.virtualFile.path, null)
        } finally {
            settings.state.enabled = enabled
            DotNetSettings.getInstance().showAnalyzerSuggestions = suggestions
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        }
    }
}
