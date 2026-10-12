package io.github.dotnetsupport

import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.codeanalysis.AnalyzedFile
import io.github.dotnetsupport.codeanalysis.AnalyzerDiagnostic
import io.github.dotnetsupport.codeanalysis.AnalyzerSeverity
import io.github.dotnetsupport.codeanalysis.CodeAnalysisAnswers
import io.github.dotnetsupport.codeanalysis.CodeAnalysisService
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpSolutionProblems
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.AnalysisScopes
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import java.io.File

/**
 * The «Analysis» scopes of Settings | .NET | Language Server without the server ([NativeCSharpSolutionProblems]): `fullSolution` lists the
 * errors of files nobody opened in the Problems view and follows edits, `openFiles` lists the open ones only, `none` shows nothing — in the
 * editor either. The analyzer scope puts the helper's warnings into the same tab under `fullSolution`.
 */
class CSharpSolutionProblemsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private val service get() = NativeCSharpSolutionProblems.getInstance(project)
    private var enabled = false
    private var options: Map<String, String> = emptyMap()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        enabled = settings.state.enabled
        options = settings.state.options.toMap()
        settings.state.enabled = false
        service.includeLooseFilesForTests(testRootDisposable)
    }

    override fun tearDown() {
        try {
            settings.state.enabled = false
            AnalysisScopes.set(AnalysisScopes.COMPILER, AnalysisScopes.NONE)
            AnalysisScopes.set(AnalysisScopes.ANALYZER, AnalysisScopes.NONE)
            pass() // the rows of this test leave the shared collector
            settings.state.enabled = enabled
            settings.state.options = options.toMutableMap()
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun pass() {
        service.settingsChanged()
        service.waitForTests()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun count(file: VirtualFile): Int = ProblemsCollector.getInstance(project).getFileProblemCount(file)

    private fun broken(name: String = "Unopened${files++}") = myFixture.addFileToProject("SolutionProblems/$name.cs", "class $name { void M() { int x = ; } }").virtualFile

    fun testFullSolutionListsTheErrorsOfFilesNobodyOpened() {
        val file = broken()
        AnalysisScopes.set(AnalysisScopes.COMPILER, AnalysisScopes.FULL_SOLUTION)
        pass()
        assertEquals("CS1525 of the unopened file", 1, count(file))

        // openFiles: the file is not open, its row goes; none: nothing
        AnalysisScopes.set(AnalysisScopes.COMPILER, AnalysisScopes.OPEN_FILES)
        pass()
        assertEquals(0, count(file))
        AnalysisScopes.set(AnalysisScopes.COMPILER, AnalysisScopes.FULL_SOLUTION)
        pass()
        assertEquals(1, count(file))
        AnalysisScopes.set(AnalysisScopes.COMPILER, AnalysisScopes.NONE)
        pass()
        assertEquals(0, count(file))
    }

    fun testOpenFilesListsTheOpenOnes() {
        val file = broken()
        AnalysisScopes.set(AnalysisScopes.COMPILER, AnalysisScopes.OPEN_FILES)
        pass()
        assertEquals(0, count(file))
        myFixture.openFileInEditor(file)
        service.waitForTests()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("open: in the tab, as the server's textDocument/diagnostic put it", 1, count(file))
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(file)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("closed: gone", 0, count(file))
    }

    fun testTheRowsFollowAnEdit() {
        val file = broken()
        AnalysisScopes.set(AnalysisScopes.COMPILER, AnalysisScopes.FULL_SOLUTION)
        pass()
        assertEquals(1, count(file))
        myFixture.openFileInEditor(file)
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(0, document.textLength, "class Fixed { void M() { int x = 1; } }")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        service.waitForTests()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("fixed: the row goes", 0, count(file))
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.textLength, "\nclass Broken { void N() { int y = ; } }")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        service.waitForTests()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("broken again: back", 1, count(file))
    }

    fun testNoneHidesTheErrorsInTheEditorToo() {
        myFixture.configureByText("NoneScope.cs", "class NoneScope { void M() { int x = ; } }")
        AnalysisScopes.set(AnalysisScopes.COMPILER, AnalysisScopes.NONE)
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.startsWith("CS") == true })
        AnalysisScopes.set(AnalysisScopes.COMPILER, AnalysisScopes.OPEN_FILES)
        // a change of the text: the daemon highlights the unchanged file again only on a restart (the settings page restarts it on Apply)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(myFixture.editor.document.textLength, " ") }
        assertEquals(1, myFixture.doHighlighting(HighlightSeverity.ERROR).count { it.description?.startsWith("CS1525") == true })
    }

    fun testAnalyzerWarningsUnderFullSolution() {
        val file = myFixture.addFileToProject("SolutionProblems/Analyzed.cs", "class Analyzed { }").virtualFile
        val diagnostic = AnalyzerDiagnostic("CA1822", AnalyzerSeverity.WARNING, "Member can be static", file.path, 0, 6, 0, 14, "Performance", null, "analyzer", false, emptyList())
        val info = diagnostic.copy(id = "IDE0290", severity = AnalyzerSeverity.INFO, message = "Use primary constructor")
        val analysis = CodeAnalysisService.getInstance(project)
        val projectPath = "C:/s/SolutionProblems/SolutionProblems.csproj"
        analysis.putAnalyzedForTests(file.path, AnalyzedFile(projectPath, listOf(diagnostic, info), listOf("class Analyzed { }", "class Analyzed { }"), CodeAnalysisAnswers.target(projectPath, "Debug", null, "r"), file.path))
        try {
            AnalysisScopes.set(AnalysisScopes.ANALYZER, AnalysisScopes.FULL_SOLUTION)
            pass()
            assertEquals("the warning, not the suggestion", 1, count(file))
            AnalysisScopes.set(AnalysisScopes.ANALYZER, AnalysisScopes.OPEN_FILES)
            pass()
            assertEquals(0, count(file))
        } finally {
            analysis.putAnalyzedForTests(file.path, null)
        }
    }

    /** The full pass over the C# files of `debug-playground` (syntax, `using` and semantic checks against the fixture assemblies): the time goes to stdout. */
    fun testMeasureThePassOnThePlayground() {
        val root = File("debug-playground").takeIf { it.isDirectory } ?: return
        val sources = root.walkTopDown().onEnter { it.name !in setOf("bin", "obj", ".git") }.filter { it.isFile && it.extension == "cs" }.toList()
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        val added = sources.map { myFixture.addFileToProject("Playground/" + it.relativeTo(root).path.replace('\\', '/'), it.readText()).virtualFile }
        try {
            AnalysisScopes.set(AnalysisScopes.COMPILER, AnalysisScopes.FULL_SOLUTION)
            pass()
            val (checked, millis) = service.lastPass!!
            println("solution problems: ${added.size} playground files, $checked checked in $millis ms")
            assertTrue(checked >= added.size)
        } finally {
            AnalysisScopes.set(AnalysisScopes.COMPILER, AnalysisScopes.NONE)
            pass()
            WriteAction.run<RuntimeException> { added.forEach { if (it.isValid) it.delete(this) } }
        }
    }
}
