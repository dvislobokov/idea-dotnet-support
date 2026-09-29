package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.roslyn.RoslynSolutionProblems
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.WorkspaceDiagnosticReport
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import java.io.File

/** The answer of `workspace/diagnostic` of server 5.12 (captured 2026-09-29) through lsp4j, and what of it becomes a problem. */
class WorkspaceDiagnosticsTest : BasePlatformTestCase() {
    fun testTheCapturedAnswerParsesIntoFullReportsWithErrors() {
        val json = JsonParser.parseString(File("src/test/resources/roslyn/capture-5.12/61-workspace_diagnostic.json").readText()).asJsonObject["result"]
        val report = MessageJsonHandler(emptyMap()).gson.fromJson(json, WorkspaceDiagnosticReport::class.java)
        val entries = report.items
        assertEquals(57, entries.size)
        // every entry is a full report ("kind": "full"), none is taken for an unchanged one
        assertTrue(entries.all { it.isLeft })
        val program = entries.map { it.left }.first { it.uri.endsWith("/Web/Program.cs") }
        assertEquals(3, program.items.size)
        assertEquals(1, program.items.count { it.severity == DiagnosticSeverity.Error })

        val problems = project.service<RoslynSolutionProblems>()
        val file = myFixture.addFileToProject("Web/Program.cs", "").virtualFile
        val converted = program.items.mapNotNull { problems.toProblem(file, it) }
        assertEquals("errors and warnings only", 1, converted.size)
        assertTrue(converted.all { it.text.isNotBlank() })
    }
}
