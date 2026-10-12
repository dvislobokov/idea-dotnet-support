package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.actions.ConvertSolutionToSlnxAction
import io.github.dotnetsupport.run.DotNetStackTraceFilter
import io.github.dotnetsupport.run.LaunchSettings
import io.github.dotnetsupport.run.ListeningUrl

class ToolsTest : BasePlatformTestCase() {
    fun testStackTraceFrames() {
        val windows = """   at Program.<<Main>$>g__Boom|0_0() in C:\src\My App\Program.cs:line 12"""
        val frame = DotNetStackTraceFilter.parse(windows)!!
        assertEquals("""C:\src\My App\Program.cs""", frame.path)
        assertEquals(12, frame.line)
        assertEquals("""C:\src\My App\Program.cs:line 12""", windows.substring(frame.range))

        val unix = DotNetStackTraceFilter.parse("   at Shop.Cart.Add(Item item) in /home/me/shop/Cart.cs:line 7\n")!!
        assertEquals("/home/me/shop/Cart.cs" to 7, unix.path to unix.line)

        // localized runtimes translate the words, not the shape
        val russian = DotNetStackTraceFilter.parse("""   в Shop.Cart.Add(Item item) в D:\work\Cart.cs:строка 42""")!!
        assertEquals("""D:\work\Cart.cs""" to 42, russian.path to russian.line)

        // frames without sources, MSBuild diagnostics and ordinary output are not frames
        assertNull(DotNetStackTraceFilter.parse("   at System.Linq.Enumerable.First[TSource](IEnumerable`1 source)"))
        assertNull(DotNetStackTraceFilter.parse("""C:\src\App\Program.cs(12,5): error CS1002: ; expected [C:\src\App\App.csproj]"""))
        assertNull(DotNetStackTraceFilter.parse("info: Microsoft.Hosting.Lifetime[14] Now listening on: http://localhost:5000"))
    }

    fun testStackTraceFilterLinksExistingFiles() {
        // the light fixture keeps files in memory, a stack trace points to the real disk
        val source = FileUtil.createTempFile("Frame", ".cs", true)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(source)
        val filter = DotNetStackTraceFilter(project)

        val line = "   at A.B() in ${source.path}:line 3\n"
        val textBefore = 100 // the console passes the length of everything printed so far
        val link = filter.applyFilter(line, textBefore + line.length)!!.resultItems.single()
        assertEquals(textBefore + line.indexOf(source.path), link.highlightStartOffset)
        assertEquals(textBefore + line.trimEnd().length, link.highlightEndOffset)

        // no link to a file that does not exist on this machine (a trace from a build server)
        assertNull(filter.applyFilter("   at A.B() in C:\\no\\such\\File.cs:line 3\n", 200))
    }

    fun testListeningUrl() {
        assertEquals("http://localhost:5000", ListeningUrl.parse("      Now listening on: http://localhost:5000\n"))
        assertEquals("https://[::]:7001", ListeningUrl.parse("info: Microsoft.Hosting.Lifetime[14]\n      Now listening on: https://[::]:7001"))
        assertNull(ListeningUrl.parse("Application started. Press Ctrl+C to shut down."))

        assertEquals("http://localhost:5000", ListeningUrl.browserUrl("http://0.0.0.0:5000", null))
        assertEquals("https://localhost:7001/swagger", ListeningUrl.browserUrl("https://[::]:7001", "swagger"))
        assertEquals("http://localhost:8080/api/health", ListeningUrl.browserUrl("http://*:8080/", "/api/health"))
        assertEquals("https://example.test/app", ListeningUrl.browserUrl("http://localhost:5000", "https://example.test/app"))
        assertEquals("http://10.0.0.5:5000", ListeningUrl.browserUrl("http://10.0.0.5:5000", ""))
    }

    fun testLaunchProfileDetails() {
        val profiles = LaunchSettings.profiles(
            """
            {
              "profiles": {
                "http": { "commandName": "Project", "launchBrowser": true, "launchUrl": "swagger", "applicationUrl": "http://localhost:5000" },
                "quiet": { "commandName": "Project", "launchBrowser": false },
                "IIS Express": { "commandName": "IISExpress", "launchBrowser": true }
              }
            }
            """.trimIndent()
        )
        assertEquals(listOf("http", "quiet"), profiles.map { it.name })
        assertEquals(listOf(true, false), profiles.map { it.launchBrowser })
        assertEquals(listOf("swagger", null), profiles.map { it.launchUrl })
    }

    fun testConvertToSlnxAvailability() {
        val old = myFixture.addFileToProject("a/Old.sln", "").virtualFile
        val modern = myFixture.addFileToProject("b/New.slnx", "<Solution/>").virtualFile
        val converted = myFixture.addFileToProject("c/Both.sln", "").virtualFile
        myFixture.addFileToProject("c/Both.slnx", "<Solution/>")

        assertTrue(ConvertSolutionToSlnxAction.isConvertible(old))
        assertFalse(ConvertSolutionToSlnxAction.isConvertible(modern))
        assertFalse("already converted", ConvertSolutionToSlnxAction.isConvertible(converted))
    }

    fun testInsertGuid() {
        val actionManager = ActionManager.getInstance()
        val generate = actionManager.getAction("GenerateGroup") as DefaultActionGroup
        assertTrue(generate.getChildActionsOrStubs().any { actionManager.getId(it) == "DotNet.InsertGuid" })

        val guid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        // two carets, the second one with a selection to replace
        myFixture.configureByText("A.cs", "var a = \"<caret>\";\nvar b = \"<selection>old<caret></selection>\";")
        myFixture.performEditorAction("DotNet.InsertGuid")
        val values = Regex("\"([^\"]*)\"").findAll(myFixture.editor.document.text).map { it.groupValues[1] }.toList()
        assertEquals(2, values.size)
        assertTrue(values.toString(), values.all(guid::matches))
        assertTrue("each caret gets its own GUID", values[0] != values[1])

        // solution files keep their GUIDs in upper case
        myFixture.configureByText("Demo.sln", "Project(\"{2150E333-8FDC-42A3-9474-1A3956D46DE8}\") = \"src\", \"src\", \"{<caret>}\"")
        myFixture.performEditorAction("DotNet.InsertGuid")
        val inserted = myFixture.editor.document.text.substringAfterLast('{').substringBefore('}')
        assertTrue(inserted, guid.matches(inserted.lowercase()) && inserted == inserted.uppercase())
    }
}
