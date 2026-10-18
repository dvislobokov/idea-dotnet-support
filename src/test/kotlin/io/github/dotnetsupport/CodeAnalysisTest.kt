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
import io.github.dotnetsupport.codeanalysis.CodeAnalysisWarmUp
import io.github.dotnetsupport.codeanalysis.TextPositions
import io.github.dotnetsupport.lang.CSharpSyntaxTrees

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

    /**
     * The plugin carries every source file of the helper, the disk cache of its design-time builds among them (its rule is checked by
     * `tools/codeanalysis-tests`, which needs `dotnet`): a file missing from [CodeAnalysisService.HELPER] would not compile on the user's machine.
     */
    fun testTheHelperIsInThePluginWithItsCache() {
        val names = CodeAnalysisService.HELPER.sources
        assertTrue(names.toString(), "DesignTimeCache.cs" in names && "Program.cs" in names && "Protocol.cs" in names)
        val sources = io.github.dotnetsupport.cli.DotNetHelper.Sources.read("codeanalysis", names + "CodeAnalysisHelper.csproj")
        assertNotNull("every source is in the resources of the plugin", sources)
        assertTrue(sources!!.files.getValue("DesignTimeCache.cs").contains("static string? Stale("))
        assertTrue("the design-time build asks the cache first", sources.files.getValue("Program.cs").contains("DesignTimeCache.Stale("))
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
        // another file saved by the IDE: a `.razor` the generators read, if the helper says it is one of theirs
        assertEquals(CodeAnalysisEvents.Kind.SAVED_FILE, CodeAnalysisEvents.kind("C:/s/A/Pages/Counter.razor", contentChange = true, fromSave = true))
        assertEquals(CodeAnalysisEvents.Kind.NONE, CodeAnalysisEvents.kind("C:/s/A/Pages/Counter.razor", contentChange = true, fromSave = false))
        assertEquals(CodeAnalysisEvents.Kind.NONE, CodeAnalysisEvents.kind("C:/s/A/obj/Debug/x.razor", contentChange = true, fromSave = true))
        // a `.razor` made, deleted, renamed or moved: the set of additional files, if the generators of its project take such files
        assertEquals(CodeAnalysisEvents.Kind.FILE_SET, CodeAnalysisEvents.kind("C:/s/A/Pages/Tally.razor", contentChange = false, fromSave = false))
        assertEquals(CodeAnalysisEvents.Kind.NONE, CodeAnalysisEvents.kind("C:/s/A/obj/Debug/Tally.razor", contentChange = false, fromSave = false))
    }

    /** The projects warmed up when the solution opens: the ones worked in last time, most recent first, a few, still in the solution. */
    fun testWhichProjectsWarmUpAtTheOpening() {
        var recent = emptyList<String>()
        for (path in listOf("C:/s/A/A.csproj", "C:/s/B/B.csproj", "C:/s/A/A.csproj", "C:/s/C/C.csproj", "C:/s/D/D.csproj")) recent = CodeAnalysisWarmUp.pushed(recent, path)
        assertEquals(listOf("C:/s/D/D.csproj", "C:/s/C/C.csproj", "C:/s/A/A.csproj"), recent)
        assertEquals("the same project in another case is the same", listOf("c:/s/c/c.csproj", "C:/s/D/D.csproj", "C:/s/A/A.csproj"), CodeAnalysisWarmUp.pushed(recent, "c:/s/c/c.csproj"))
        assertEquals(CodeAnalysisWarmUp.LIMIT, recent.size)
        // a project gone from the solution (removed, renamed) is not loaded; the path of the solution is the one used
        assertEquals(listOf("C:/s/A/A.csproj"), CodeAnalysisWarmUp.toWarmUp(listOf("C:/s/D/D.csproj", "c:/s/a/a.csproj"), listOf("C:/s/A/A.csproj", "C:/s/B/B.csproj")))
        assertEmpty(CodeAnalysisWarmUp.toWarmUp(emptyList(), listOf("C:/s/A/A.csproj")))
    }

    /** A helper on pipes that answers every request (`generate` with an empty run) and remembers the methods it was asked. */
    private fun recordingHelper(methods: MutableList<String>): io.github.dotnetsupport.cli.HelperConnection = io.github.dotnetsupport.cli.HelperConnection("test") {
        val toHelper = java.io.PipedOutputStream()
        val helperInput = java.io.PipedInputStream(toHelper, 65536)
        val helperOutput = java.io.PipedOutputStream()
        val fromHelper = java.io.PipedInputStream(helperOutput, 65536)
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        Thread {
            val writer = helperOutput.bufferedWriter()
            try {
                helperInput.bufferedReader().forEachLine { line ->
                    val request = JsonParser.parseString(line).asJsonObject
                    val id = request.get("id")?.asLong ?: return@forEachLine
                    val method = request.get("method").asString
                    synchronized(methods) { methods += method }
                    val result = if (method == "generate") """{"project":"p","framework":"net10.0","files":[],"errors":[],"milliseconds":1,"workingSet":1}""" else "{}"
                    writer.write("""{"id":$id,"result":$result}""" + "\n")
                    writer.flush()
                }
            } catch (_: java.io.IOException) {
            }
            alive.set(false)
        }.apply { isDaemon = true }.start()
        io.github.dotnetsupport.cli.HelperConnection.Transport(fromHelper, toHelper, null, alive::get) { alive.set(false); runCatching { helperOutput.close() } }
    }

    /** After the idle stop the first edit of a C# file of a project with generated files loads it again, once; without the stop, an edit asks nothing. */
    fun testTheFirstEditAfterTheIdleStopLoadsTheProjectAgain() {
        val folder = com.intellij.openapi.util.io.FileUtil.createTempDirectory("rewarm", null)
        val projectIo = java.io.File(folder, "App.csproj").apply { writeText("<Project Sdk=\"Microsoft.NET.Sdk\"/>") }
        val sourceIo = java.io.File(folder, "Program.cs").apply { writeText("class Program { }") }
        val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        val projectFile = lfs.refreshAndFindFileByIoFile(projectIo)!!
        val source = lfs.refreshAndFindFileByIoFile(sourceIo)!!
        val service = CodeAnalysisService.getInstance(project)
        val methods = java.util.Collections.synchronizedList(ArrayList<String>())
        val connection = recordingHelper(methods)
        service.setConnectionForTests(connection)
        try {
            service.putGeneratedForTests(projectFile.path, CodeAnalysisAnswers.generated(JsonParser.parseString("""{"project":"p","files":[],"errors":[],"generators":["G"]}""")))
            service.documentChanged(source)
            Thread.sleep(1_500)
            assertEmpty("an edit while the helper runs (or never ran) asks nothing", methods)
            service.markStoppedIdleForTests()
            service.documentChanged(source)
            service.documentChanged(source)
            val deadline = System.currentTimeMillis() + 15_000
            while (synchronized(methods) { "generate" !in methods } && System.currentTimeMillis() < deadline) {
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(50)
            }
            Thread.sleep(1_500)
            assertEquals("the project is loaded again, once", listOf("generate"), synchronized(methods) { methods.toList() })
        } finally {
            service.setConnectionForTests(null)
            service.putGeneratedForTests(projectFile.path, null)
            connection.dispose()
        }
    }

    /** RAZOR_PLAN.md, R0: the helper names the additional files of the generators; editing one of them makes what they made stale. */
    fun testAdditionalFilesOfTheGenerators() {
        val answer = JsonParser.parseString("""{"project":"C:\\s\\A\\A.csproj","framework":"net10.0","files":[],"buildFiles":[],
            "additionalFiles":["C:\\s\\A\\Pages\\Counter.razor"],"generators":["Microsoft.NET.Sdk.Razor.SourceGenerators.RazorSourceGenerator"],"errors":[]}""")
        val run = CodeAnalysisAnswers.generated(answer)
        assertEquals(listOf("C:/s/A/Pages/Counter.razor"), run.additionalFiles)
        assertEquals(listOf("Microsoft.NET.Sdk.Razor.SourceGenerators.RazorSourceGenerator"), run.generators)
        assertEmpty("an older helper says neither", CodeAnalysisAnswers.generated(JsonParser.parseString("""{"project":"p","files":[],"errors":[]}""")).additionalFiles)

        val folder = com.intellij.openapi.util.io.FileUtil.createTempDirectory("razorInputs", null)
        val projectIo = java.io.File(folder, "App.csproj").apply { writeText("<Project Sdk=\"Microsoft.NET.Sdk.Razor\"/>") }
        val razorIo = java.io.File(folder, "Pages/Counter.razor").apply { parentFile.mkdirs(); writeText("<p>@count</p>") }
        val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        val projectFile = lfs.refreshAndFindFileByIoFile(projectIo)!!
        val razor = lfs.refreshAndFindFileByIoFile(razorIo)!!
        val service = CodeAnalysisService.getInstance(project)
        try {
            val made = io.github.dotnetsupport.codeanalysis.GeneratedFile("${folder.path}/out/Counter_razor.g.cs", "Microsoft.CodeAnalysis.Razor.Compiler",
                "Microsoft.NET.Sdk.Razor.SourceGenerators.RazorSourceGenerator", "Pages/Counter_razor.g.cs")
            service.putGeneratedForTests(projectFile.path, run.copy(files = listOf(made), additionalFiles = listOf(razor.path.uppercase())))
            assertEquals("the path, whatever its case", projectFile, service.projectOfGeneratorInput(razor.path))
            assertNull(service.projectOfGeneratorInput(projectFile.path))
            // found live (0.1.268): a renamed `.razor` left the generators reading the old list — the class of the new name was never made;
            // a file of their kind in the project's folder made, deleted or moved loads the project again in the helper
            assertTrue(service.takesFilesLike("${projectFile.parent.path}/Pages/Tally.razor"))
            assertTrue("in any folder of the project", service.takesFilesLike("${projectFile.parent.path}/Shared/Nav.RAZOR"))
            assertFalse("a kind the generators do not take", service.takesFilesLike("${projectFile.parent.path}/Pages/notes.txt"))
            assertFalse("outside the project", service.takesFilesLike("${folder.parentFile.path}/Other/Tally.razor"))

            assertEquals(setOf("Microsoft.NET.Sdk.Razor.SourceGenerators.RazorSourceGenerator"), service.generators(projectFile))
            assertFalse(service.generatedState(projectFile)!!.stale)
            // typing in a .cs of the project: Razor's generator reads no C#, what it made stays fresh and the errors go on (0.1.198)
            val codeBehindIo = java.io.File(folder, "Pages/Counter.razor.cs").apply { writeText("partial class Counter { }") }
            val codeBehind = lfs.refreshAndFindFileByIoFile(codeBehindIo)!!
            service.documentChanged(codeBehind)
            assertFalse("a C# edit with Razor's generator only", service.generatedState(projectFile)!!.stale)
            assertTrue(service.isGeneratedFresh(projectFile))
            service.documentChanged(razor)
            assertTrue("typing in the .razor: what the generators made of it is stale until the next save", service.generatedState(projectFile)!!.stale)
            assertFalse(service.isGeneratedFresh(projectFile))
            // a generator that reads the C# (the JSON one) beside Razor's: a C# edit makes what they made stale
            service.putGeneratedForTests(projectFile.path, run.copy(files = listOf(made), additionalFiles = listOf(razor.path),
                generators = run.generators + "System.Text.Json.SourceGeneration.JsonSourceGenerator"))
            assertFalse(service.generatedState(projectFile)!!.stale)
            service.documentChanged(codeBehind)
            assertTrue("a C# edit with a generator reading the C#", service.generatedState(projectFile)!!.stale)
        } finally {
            service.putGeneratedForTests(projectFile.path, null)
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
        val info = AnalyzedFile(file.projectPath, listOf(diagnostic.copy(severity = AnalyzerSeverity.INFO, fixes = emptyList())), file.lineTexts, file.target)
        assertEmpty("suggestions off, no fixes to offer",AnalyzerAnnotator.locate(info, "x\nint Load();\n", suggestions = false))
    }

    /** The annotations in the editor: the id first, the fixes of the analyzer on Alt+Enter; nothing while the language server is enabled. */
    fun testAnnotationsWithFixes() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        try {
            val file = myFixture.configureByText("AnalyzerAnnotations.cs", "class A\n{\n    public async System.Threading.Tasks.Task Load() { await System.Threading.Tasks.Task.Yield(); }\n}\n")
            val diagnostic = CodeAnalysisAnswers.analysis(fixture("analyze.json")).diagnostics.first().copy(path = file.virtualFile.path, startLine = 2, endLine = 2, startColumn = 45, endColumn = 49)
            val service = CodeAnalysisService.getInstance(project)
            service.putAnalyzedForTests(file.virtualFile.path, AnalyzedFile("C:/s/A/A.csproj", listOf(diagnostic), listOf("public async System.Threading.Tasks.Task Load() { await System.Threading.Tasks.Task.Yield(); }"),
                CodeAnalysisAnswers.target("C:/s/A/A.csproj", "Debug", null, "r")))
            val warning = myFixture.doHighlighting(HighlightSeverity.WARNING).single { it.description?.startsWith("VSTHRD200") == true }
            assertEquals("Load", myFixture.editor.document.text.substring(warning.startOffset, warning.endOffset))
            myFixture.editor.caretModel.moveToOffset(warning.startOffset + 1)
            assertTrue(myFixture.availableIntentions.map { it.text }.toString(), myFixture.availableIntentions.any { it.text == "Rename to LoadAsync" })
            service.putAnalyzedForTests(file.virtualFile.path, null)
        } finally {
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        }
    }
}
