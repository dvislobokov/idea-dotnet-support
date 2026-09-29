package io.github.dotnetsupport

import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.analysis.problemsView.ProblemsListener
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.roslyn.RoslynSolutionProblems
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** The errors of the whole solution reach the Problems tool window, and only the differences are announced. */
class SolutionProblemsTest : BasePlatformTestCase() {
    fun testDiagnosticsBecomeProblemsAndOnlyChangesArePublished() {
        val file = myFixture.addFileToProject("Sol/Program.cs", "class Program { }").virtualFile
        val problems = project.service<RoslynSolutionProblems>()
        val collector = ProblemsCollector.getInstance(project)
        val appeared = ArrayList<Problem>()
        val disappeared = ArrayList<Problem>()
        project.messageBus.connect(testRootDisposable).subscribe(ProblemsListener.TOPIC, object : ProblemsListener {
            override fun problemAppeared(problem: Problem) { appeared += problem }
            override fun problemDisappeared(problem: Problem) { disappeared += problem }
            override fun problemUpdated(problem: Problem) = Unit
        })

        fun diagnostic(line: Int, code: String, severity: DiagnosticSeverity, message: String) =
            Diagnostic(Range(Position(line, 2), Position(line, 9)), message, severity, "csharp").also { it.setCode(code) }

        val error = problems.toProblem(file, diagnostic(0, "CS0103", DiagnosticSeverity.Error, "The name 'x' does not exist"))!!
        val warning = problems.toProblem(file, diagnostic(3, "CS8600", DiagnosticSeverity.Warning, "Converting null literal"))!!
        assertNull("hints stay in the editor", problems.toProblem(file, diagnostic(1, "IDE0300", DiagnosticSeverity.Hint, "simplify")))
        assertEquals("The name 'x' does not exist", error.text)
        assertEquals("CS0103", error.group)
        assertEquals(0, error.line)
        assertEquals(2, error.column)

        val uri = "file:///sol/Program.cs"
        assertTrue(problems.publish(uri, listOf(error, warning)))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(2, appeared.size)
        assertEquals(2, collector.getFileProblemCount(file))

        // the same answer again: nothing is announced; one gone: one disappearance
        assertFalse(problems.publish(uri, listOf(problems.toProblem(file, diagnostic(0, "CS0103", DiagnosticSeverity.Error, "The name 'x' does not exist"))!!, warning)))
        assertTrue(problems.publish(uri, listOf(warning)))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, disappeared.size)
        assertEquals(1, collector.getFileProblemCount(file))

        // the server is gone: so are its problems
        problems.stop()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(0, collector.getFileProblemCount(file))
    }
}
