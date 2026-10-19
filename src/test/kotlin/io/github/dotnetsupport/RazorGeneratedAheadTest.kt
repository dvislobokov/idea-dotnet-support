package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.codeanalysis.CodeAnalysisAnswers
import io.github.dotnetsupport.codeanalysis.CodeAnalysisService
import io.github.dotnetsupport.codeanalysis.GeneratedFile
import io.github.dotnetsupport.frameworks.razor.RazorCodeGenerators
import io.github.dotnetsupport.frameworks.razor.RazorGeneratedAhead
import java.io.File

/**
 * A renamed, made or deleted `.razor`: the plugin writes the class of the new name where the helper puts the Razor generator's output and
 * removes the old one at once, as a renamed Unity script shows at once — not when the helper has run the generators again (0.1.280).
 */
class RazorGeneratedAheadTest : BasePlatformTestCase() {
    fun testTheHintNameOfTheGenerator() {
        assertEquals("Components/Pages/Counter_razor.g.cs", RazorGeneratedAhead.hintName("Components/Pages/Counter.razor"))
        assertEquals("Views/Home/Index_cshtml.g.cs", RazorGeneratedAhead.hintName("Views\\Home\\Index.cshtml"))
        assertEquals("a character that is no letter or digit", "My_Page_2_razor.g.cs", RazorGeneratedAhead.hintName("My-Page 2.razor"))
        assertEquals("a leading or doubled separator", "_a_/b_razor.g.cs", RazorGeneratedAhead.hintName("/a//b.razor"))
    }

    /** The shape of a class: the render method's body and `#line` are no part of it; a member of `@code` is. */
    fun testTheShapeOfAGeneratedClass() {
        val a = "namespace App {\n#line (1,1)-(1,5) \"x\"\n#pragma warning disable 1998\nprotected override void BuildRenderTree(B b)\n{\n b.AddContent(0, \"one\");\n}\n#pragma warning restore 1998\nint count;\n}\n"
        val markup = a.replace("\"one\"", "\"two\"").replace("(1,1)-(1,5)", "(2,1)-(2,5)")
        assertEquals("the markup and the lines changed only", RazorGeneratedAhead.shape(a), RazorGeneratedAhead.shape(markup))
        assertFalse("a member added", RazorGeneratedAhead.shape(a) == RazorGeneratedAhead.shape(a.replace("int count;", "int count;\nint step;")))
    }

    /** RAZOR_PLAN.md, R4 tails: an edit of `@code` writes the class again after a pause, an edit of the markup alone does not. */
    fun testAnEditOfTheCodeWritesTheClassAgain() {
        val folder = FileUtil.createTempDirectory("razorEdit", null)
        val projectIo = File(folder, "App.csproj").apply { writeText("<Project Sdk=\"Microsoft.NET.Sdk.Razor\"/>") }
        File(folder, "Pages/Counter.razor").apply { parentFile.mkdirs(); writeText("<p>@count</p>\n@code {\n    int count;\n}\n") }
        val lfs = LocalFileSystem.getInstance()
        val projectFile = lfs.refreshAndFindFileByIoFile(projectIo)!!
        val razor = lfs.refreshAndFindFileByIoFile(File(folder, "Pages/Counter.razor"))!!
        val service = CodeAnalysisService.getInstance(project)
        val run = CodeAnalysisAnswers.generated(JsonParser.parseString("""{"project":"p","files":[],"errors":[],"generators":["${RazorCodeGenerators.GENERATOR}"]}"""))
        val output = File(CodeAnalysisAnswers.outputFolder(service.outputRoot(), projectFile.path), "Microsoft.CodeAnalysis.Razor.Compiler/${RazorCodeGenerators.GENERATOR}")
        val generated = File(output, "Pages/Counter_razor.g.cs").apply { parentFile.mkdirs(); writeText("namespace App.Pages { public partial class Counter { } }") }
        val made = GeneratedFile(generated.path, "Microsoft.CodeAnalysis.Razor.Compiler", RazorCodeGenerators.GENERATOR, "Pages/Counter_razor.g.cs")
        try {
            service.putGeneratedForTests(projectFile.path, run.copy(files = listOf(made), additionalFiles = listOf(razor.path)))
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(razor)!!
            val edits = project.getService(io.github.dotnetsupport.frameworks.razor.RazorGeneratedAheadEdits::class.java)
            fun edit(at: String, insert: String) = com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(document.text.indexOf(at) + at.length, insert)
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            edit("int count;", "\n    [Parameter] public int Step { get; set; }")
            PlatformTestUtil.waitWithEventsDispatching("the class written again with the parameter", { generated.readText().contains("public int Step") }, 10)
            // the IDE sees the new text, not only the disk (the WSL sandbox: a refresh of the folder alone left Ctrl+B to the new parameter nowhere)
            val virtual = lfs.findFileByIoFile(generated)!!
            PlatformTestUtil.waitWithEventsDispatching("the VFS has the new text", { com.intellij.openapi.vfs.VfsUtilCore.loadText(virtual).contains("public int Step") }, 10)
            val stamp = generated.lastModified()
            Thread.sleep(20)
            edit("<p>", "Count: ")
            PlatformTestUtil.waitWithEventsDispatching("the edit of the markup handled", { edits.isIdleForTests() }, 10)
            assertEquals("the markup alone does not write the class", stamp, generated.lastModified())
        } finally {
            service.putGeneratedForTests(projectFile.path, null)
            output.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    fun testARenamedComponentHasItsClassAtOnce() {
        val folder = FileUtil.createTempDirectory("razorAhead", null)
        val projectIo = File(folder, "App.csproj").apply { writeText("<Project Sdk=\"Microsoft.NET.Sdk.Razor\"/>") }
        File(folder, "Pages/Counter.razor").apply { parentFile.mkdirs(); writeText("<p>@count</p>\n@code {\n    int count;\n}\n") }
        val lfs = LocalFileSystem.getInstance()
        val projectFile = lfs.refreshAndFindFileByIoFile(projectIo)!!
        val razor = lfs.refreshAndFindFileByIoFile(File(folder, "Pages/Counter.razor"))!!
        val service = CodeAnalysisService.getInstance(project)
        val run = CodeAnalysisAnswers.generated(JsonParser.parseString("""{"project":"p","files":[],"errors":[],"generators":["${RazorCodeGenerators.GENERATOR}"]}"""))
        val output = File(CodeAnalysisAnswers.outputFolder(service.outputRoot(), projectFile.path), "Microsoft.CodeAnalysis.Razor.Compiler/${RazorCodeGenerators.GENERATOR}")
        val old = File(output, "Pages/Counter_razor.g.cs").apply { parentFile.mkdirs(); writeText("namespace App.Pages { public partial class Counter { } }") }
        val made = GeneratedFile(old.path, "Microsoft.CodeAnalysis.Razor.Compiler", RazorCodeGenerators.GENERATOR, "Pages/Counter_razor.g.cs")
        try {
            service.putGeneratedForTests(projectFile.path, run.copy(files = listOf(made), additionalFiles = listOf(razor.path)))
            assertEquals(File(output, "Pages/Counter_razor.g.cs"), RazorGeneratedAhead.outputOf(service, razor.path))
            assertNull("a file of a project whose Razor generator did not run", RazorGeneratedAhead.outputOf(service, "${folder.parentFile.path}/Other/Tally.razor"))

            WriteAction.runAndWait<Throwable> { razor.rename(this, "Tally.razor") }
            val renamed = File(output, "Pages/Tally_razor.g.cs")
            PlatformTestUtil.waitWithEventsDispatching("the class of the new name written ahead", { renamed.exists() && !old.exists() }, 10)
            assertTrue("the class of the generator, named after the file: " + renamed.readText(), renamed.readText().contains("partial class Tally"))
            assertFalse("read-only, as the helper leaves them", renamed.canWrite())

            WriteAction.runAndWait<Throwable> { razor.delete(this) }
            PlatformTestUtil.waitWithEventsDispatching("the output of a deleted file removed", { !renamed.exists() }, 10)
        } finally {
            service.putGeneratedForTests(projectFile.path, null)
            output.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
