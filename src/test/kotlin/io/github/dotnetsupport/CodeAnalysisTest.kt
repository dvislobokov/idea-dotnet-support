package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.codeanalysis.AnalyzedFile
import io.github.dotnetsupport.codeanalysis.AnalyzerAnnotator
import io.github.dotnetsupport.codeanalysis.AnalyzerSeverity
import io.github.dotnetsupport.codeanalysis.CodeAnalysisAnswers
import io.github.dotnetsupport.codeanalysis.CodeAnalysisEvents
import io.github.dotnetsupport.codeanalysis.CodeAnalysisService
import io.github.dotnetsupport.codeanalysis.TextPositions
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Source generators and analyzers through CodeAnalysisHelper (D3 / D4): the protocol on answers captured from the helper on
 * `debug-playground/Console` (`src/test/resources/codeanalysis`, paths made `C:\repo` / `C:\cache`), the positions of its diagnostics
 * and edits in documents, the events that wake it, the annotations. The helper itself never runs in tests.
 */
class CodeAnalysisTest : BasePlatformTestCase() {
    private fun fixture(name: String) = JsonParser.parseString(javaClass.getResource("/codeanalysis/$name")!!.readText())

    fun testGeneratedFilesOfThePlayground() {
        val run = CodeAnalysisAnswers.generated(fixture("generate.json"))
        assertEquals("net9.0", run.framework)
        assertEmpty(run.errors)
        val assemblies = run.files.map { it.generatorAssembly }.toSet()
        assertEquals(setOf("Microsoft.Extensions.Logging.Generators", "System.Text.Json.SourceGeneration", "System.Text.RegularExpressions.Generator"), assemblies)
        val json = run.files.filter { it.generatorAssembly == "System.Text.Json.SourceGeneration" }
        assertTrue(json.all { it.generatorType == "System.Text.Json.SourceGeneration.JsonSourceGenerator" })
        assertTrue(json.map { it.hintName }.toString(), "PlaygroundJsonContext.GeneratedOrder.g.cs" in json.map { it.hintName })
        // the path is the one of the VFS, under the folder of the project the plugin names the same way as the helper
        val logging = run.files.single { it.generatorAssembly == "Microsoft.Extensions.Logging.Generators" }
        assertEquals("C:/cache/generated/Console-356acd77/Microsoft.Extensions.Logging.Generators/Microsoft.Extensions.Logging.Generators.LoggerMessageGenerator/LoggerMessage.g.cs", logging.path)
    }

    /** The folder of a project's generated files: the plugin finds it without asking, the helper writes there (`Target.OutputOf`). */
    fun testOutputFolderIsNamedAsByTheHelper() {
        // the hash the helper gave the playground of the worktree it was captured in (generate.json)
        val captured = "C:/Users/dvislobokov/idea-dotnet-support/.claude/worktrees/agent-a7ad4b093b1623d8c/debug-playground/Console/Console.csproj"
        assertEquals("C:/cache/generated/Console-356acd77", CodeAnalysisAnswers.outputFolder("C:/cache/generated", captured))
        assertEquals("C:/cache/generated/Console-356acd77", CodeAnalysisAnswers.outputFolder("C:\\cache\\generated\\", captured.replace('/', '\\')))
        assertEquals("the case of the path does not count", CodeAnalysisAnswers.outputFolder("r", captured), CodeAnalysisAnswers.outputFolder("r", captured.lowercase()).replace("console-", "Console-"))
    }

    fun testAnalyzerDiagnosticsOfThePlayground() {
        val run = CodeAnalysisAnswers.analysis(fixture("analyze.json"))
        assertEquals(listOf("VSTHRD200", "VSTHRD103", "CA1822"), run.diagnostics.map { it.id })
        assertTrue(run.diagnostics.all { it.severity == AnalyzerSeverity.WARNING && it.source == "analyzer" && it.fixable })
        val rename = run.diagnostics.first()
        assertEquals("C:/repo/debug-playground/Console/Editor/Analyzers.cs", rename.path)
        assertEquals(listOf(19, 30, 19, 34), listOf(rename.startLine, rename.startColumn, rename.endLine, rename.endColumn))
        assertEquals(listOf("Rename to LoadAsync"), rename.fixes)
        assertEquals("https://microsoft.github.io/vs-threading/analyzers/VSTHRD200.html", rename.helpLink)
        assertEquals(listOf("Make static"), run.diagnostics.last().fixes)
    }

    fun testFixEditsApplyToTheText() {
        val fix = CodeAnalysisAnswers.fix(fixture("fix.json"))
        assertEquals("Await ReadAllTextAsync instead", fix.title)
        assertEquals(2, fix.edits.size)
        assertEmpty(fix.created)
        // lines 22 / 23 of Analyzers.cs when it was captured (0-based 21, 22)
        val text = (List(21) { "" } + listOf("        await Task.Yield();", "        return File.ReadAllText(path) + counter;", "    }")).joinToString("\n")
        val fixed = TextPositions.apply(text, fix.edits)!!
        assertEquals("        return await File.ReadAllTextAsync(path) + counter;", fixed.lines()[22])
        assertNull("an edit past the end of the text: the file has changed", TextPositions.apply("short", fix.edits))
    }

    fun testPositions() {
        val text = "ab\r\ncd\nef"
        assertEquals(0, TextPositions.offset(text, 0, 0))
        assertEquals("a column past the end stops before the line end", 2, TextPositions.offset(text, 0, 10))
        assertEquals(4, TextPositions.offset(text, 1, 0))
        assertEquals(8, TextPositions.offset(text, 2, 1))
        assertNull(TextPositions.offset(text, 3, 0))
        assertEquals("cd", TextPositions.lineText(text, 1))
    }

    fun testParametersOfTheRequests() {
        val target = CodeAnalysisAnswers.target("C:/s/A/A.csproj", "Release", "net9.0", "C:/cache/generated/s-1")
        val analyze = CodeAnalysisAnswers.analyzeParams(target, listOf("C:/s/A/B.cs"), listOf("IDE0005"), fixes = true)
        assertEquals("""{"projectPath":"C:/s/A/A.csproj","configuration":"Release","targetFramework":"net9.0","outputRoot":"C:/cache/generated/s-1","paths":["C:/s/A/B.cs"],"excludedIds":["IDE0005"],"fixes":true}""", analyze.toString())
        assertFalse("the target itself is not changed", target.has("paths"))
        val whole = CodeAnalysisAnswers.analyzeParams(CodeAnalysisAnswers.target("C:/s/A/A.csproj", "Debug", null, "r"), emptyList(), emptyList(), fixes = false)
        assertEquals("""{"projectPath":"C:/s/A/A.csproj","configuration":"Debug","outputRoot":"r","fixes":false}""", whole.toString())
    }

    fun testWhatWakesTheHelper() {
        assertEquals(CodeAnalysisEvents.Kind.SAVED_SOURCE, CodeAnalysisEvents.kind("C:/s/A/B.cs", contentChange = true, fromSave = true))
        assertEquals("a change from outside is not a save of the IDE", CodeAnalysisEvents.Kind.NONE, CodeAnalysisEvents.kind("C:/s/A/B.cs", contentChange = true, fromSave = false))
        assertEquals(CodeAnalysisEvents.Kind.SOURCE_SET, CodeAnalysisEvents.kind("C:/s/A/New.cs", contentChange = false, fromSave = false))
        for (input in listOf("C:/s/A/A.csproj", "C:/s/Directory.Build.props", "C:/s/.editorconfig", "C:/s/A/obj/project.assets.json", "C:/s/x.globalconfig")) {
            assertEquals(input, CodeAnalysisEvents.Kind.PROJECT_INPUT, CodeAnalysisEvents.kind(input, contentChange = true, fromSave = false))
        }
        // what a build (or the design-time build of the helper) writes does not set off another load
        for (output in listOf("C:/s/A/obj/Debug/net9.0/A.AssemblyInfo.cs", "C:/s/A/obj/Debug/net9.0/A.GeneratedMSBuildEditorConfig.editorconfig", "C:/s/A/bin/Debug/x.json", "C:/s/A/appsettings.json")) {
            assertEquals(output, CodeAnalysisEvents.Kind.NONE, CodeAnalysisEvents.kind(output, contentChange = true, fromSave = false))
        }
    }

    /** A diagnostic stays on its line when lines are added above it, and goes when its own line is edited (until the next save). */
    fun testDiagnosticsFollowTheirLines() {
        val diagnostic = CodeAnalysisAnswers.analysis(fixture("analyze.json")).diagnostics.first().copy(startLine = 1, endLine = 1, startColumn = 4, endColumn = 8)
        val file = AnalyzedFile("C:/s/A/A.csproj", listOf(diagnostic), listOf("int Load();"), CodeAnalysisAnswers.target("C:/s/A/A.csproj", "Debug", null, "r"))
        val same = AnalyzerAnnotator.locate(file, "x\nint Load();\n", suggestions = true).single()
        assertEquals(6 to 10, same.range.startOffset to same.range.endOffset)
        val moved = AnalyzerAnnotator.locate(file, "x\n// new\n// lines\nint Load();\n", suggestions = true).single()
        assertEquals("Load", "x\n// new\n// lines\nint Load();\n".substring(moved.range.startOffset, moved.range.endOffset))
        assertEmpty(AnalyzerAnnotator.locate(file, "x\nint LoadAsync();\n", suggestions = true))
        val info = AnalyzedFile(file.projectPath, listOf(diagnostic.copy(severity = AnalyzerSeverity.INFO)), file.lineTexts, file.target)
        assertEmpty("suggestions off", AnalyzerAnnotator.locate(info, "x\nint Load();\n", suggestions = false))
    }

    /** The annotations in the editor: the id first, the fixes of the analyzer on Alt+Enter; nothing while the language server is enabled. */
    fun testAnnotationsWithFixes() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val settings = RoslynLanguageServerSettings.getInstance()
        val enabled = settings.state.enabled
        try {
            val file = myFixture.configureByText("AnalyzerAnnotations.cs", "class A\n{\n    public async System.Threading.Tasks.Task Load() { await System.Threading.Tasks.Task.Yield(); }\n}\n")
            val diagnostic = CodeAnalysisAnswers.analysis(fixture("analyze.json")).diagnostics.first().copy(path = file.virtualFile.path, startLine = 2, endLine = 2, startColumn = 45, endColumn = 49)
            val service = CodeAnalysisService.getInstance(project)
            service.putAnalyzedForTests(file.virtualFile.path, AnalyzedFile("C:/s/A/A.csproj", listOf(diagnostic), listOf("public async System.Threading.Tasks.Task Load() { await System.Threading.Tasks.Task.Yield(); }"),
                CodeAnalysisAnswers.target("C:/s/A/A.csproj", "Debug", null, "r")))
            settings.state.enabled = false
            val warning = myFixture.doHighlighting(HighlightSeverity.WARNING).single { it.description?.startsWith("VSTHRD200") == true }
            assertEquals("Load", myFixture.editor.document.text.substring(warning.startOffset, warning.endOffset))
            myFixture.editor.caretModel.moveToOffset(warning.startOffset + 1)
            assertTrue(myFixture.availableIntentions.map { it.text }.toString(), myFixture.availableIntentions.any { it.text == "Rename to LoadAsync" })
            settings.state.enabled = true
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(myFixture.editor.document.textLength, " ") }
            assertTrue("the server runs the analyzers itself", myFixture.doHighlighting(HighlightSeverity.WARNING).none { it.description?.startsWith("VSTHRD200") == true })
            service.putAnalyzedForTests(file.virtualFile.path, null)
        } finally {
            settings.state.enabled = enabled
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        }
    }
}
