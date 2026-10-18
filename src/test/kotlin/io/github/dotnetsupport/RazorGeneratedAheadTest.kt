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
