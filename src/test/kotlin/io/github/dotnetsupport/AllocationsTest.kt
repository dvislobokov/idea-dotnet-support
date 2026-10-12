package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.allocations.AllocationLine
import io.github.dotnetsupport.allocations.AllocationMessage
import io.github.dotnetsupport.allocations.AllocationReports
import io.github.dotnetsupport.allocations.AllocationTargets
import io.github.dotnetsupport.allocations.AllocationText
import io.github.dotnetsupport.allocations.AllocationTotals
import io.github.dotnetsupport.allocations.AllocationsLinePainter
import io.github.dotnetsupport.allocations.AllocationsService
import io.github.dotnetsupport.cli.DotNetHelper

/**
 * What the lines of the sources allocate, written in the editor. The report is the real output of the watcher (allocwatch) on the
 * scenario `allocations` of debug-playground; the watcher itself is not run in tests.
 */
class AllocationsTest : BasePlatformTestCase() {
    private val report: List<String> = javaClass.getResourceAsStream("/allocations/watcher.jsonl")!!.use { it.readBytes().toString(Charsets.UTF_8) }.lines().filter { it.isNotBlank() }

    fun testReportOfTheWatcher() {
        val messages = report.map { AllocationReports.parse(it) }
        assertTrue(messages[0] is AllocationMessage.Started)
        assertTrue((messages[0] as AllocationMessage.Started).pid > 0)
        val snapshot = messages[1] as AllocationMessage.Snapshot
        assertEquals(3.0, snapshot.seconds)
        assertTrue(snapshot.bytesPerSecond > 15_000_000)
        val buffers = snapshot.lines.first()
        assertEquals("C:\\playground\\Console\\Allocations.cs", buffers.file)
        assertEquals("Playground.AllocationScenario.Buffer()", buffers.method)
        assertEquals(mapOf("System.Byte[]" to 100), buffers.types)
        assertTrue(buffers.share > 90)
        assertTrue(buffers.objectsPerSecond > 10_000)
        assertTrue("the lines come by their bytes", snapshot.lines.zipWithNext().all { (a, b) -> a.bytesPerSecond >= b.bytesPerSecond })
        assertEquals("stopped", (messages.last() as AllocationMessage.Stopped).reason)

        assertNull(AllocationReports.parse("not json"))
        assertNull(AllocationReports.parse("{\"something\":1}"))
        assertNull(AllocationReports.parse("{broken"))
    }

    fun testNumbersAsTheyAreWritten() {
        assertEquals("19.3 MB/s", AllocationText.rate(20_250_000))
        assertEquals("884 KB/s", AllocationText.rate(905_551))
        assertEquals("120 B/s", AllocationText.rate(120))
        assertEquals("11.5 GB/s", AllocationText.rate(12_294_861_171))
        assertEquals("18.4K obj/s", AllocationText.objects(18_401))
        assertEquals("21 obj/s", AllocationText.objects(21))
        assertEquals("1.2M obj/s", AllocationText.objects(1_200_000))
        assertEquals("95%", AllocationText.share(95.1))
        assertEquals("4.5%", AllocationText.share(4.5))
        assertEquals("0%", AllocationText.share(0.0))

        val line = AllocationLine("f", 40, "M()", 20_250_000, 18_401, 95.1, 1, mapOf("System.Byte[]" to 100))
        assertEquals("19.3 MB/s, 18.4K obj/s, byte[]  95%", AllocationText.line(line))
        val mixed = AllocationLine("f", 40, "M()", 1024, 10, 1.0, 1, linkedMapOf("System.String" to 60, "System.Int32[]" to 30, "System.Object" to 10))
        assertEquals("1 KB/s, 10 obj/s, string, int[], ...  1.0%", AllocationText.line(mixed))
        assertEquals("allocates 19.3 MB/s  99%", AllocationText.total(20_250_000, 99.4))
        assertTrue(AllocationText.tooltip(line).contains("byte[]: 100%"))
        assertTrue("the angle brackets of a generic are text", AllocationText.tooltip(mixed.let { AllocationLine("f", 1, "M<T>()", 1, 1, 1.0, 1, mapOf("System.Func`1[System.Int32]" to 100)) }).contains("Func&lt;int&gt;"))
    }

    fun testTypesAsCSharpWritesThem() {
        assertEquals("byte[]", AllocationText.type("System.Byte[]"))
        assertEquals("string", AllocationText.type("System.String"))
        assertEquals("int", AllocationText.type("System.Int32"))
        assertEquals("int[,]", AllocationText.type("System.Int32[,]"))
        assertEquals("List<int>", AllocationText.type("System.Collections.Generic.List`1[System.Int32]"))
        assertEquals("Dictionary<string, List<int>>", AllocationText.type("System.Collections.Generic.Dictionary`2[System.String,System.Collections.Generic.List`1[System.Int32]]"))
        assertEquals("Func<int>", AllocationText.type("System.Func`1[System.Int32]"))
        assertEquals("Order[]", AllocationText.type("Shop.Models.Order[]"))
        assertEquals("closure", AllocationText.type("<>c__DisplayClass8_0"))
        assertEquals("closure", AllocationText.type("Playground.AllocationScenario+<>c__DisplayClass8_0"))
        assertEquals("Enumerator", AllocationText.type("System.Collections.Generic.List`1+Enumerator[System.Int32]").substringBefore('<'))
        assertEquals("object", AllocationText.type("?"))
    }

    fun testWhichProcessIsTheProgram() {
        fun candidate(pid: Long, command: String, started: Long) = AllocationTargets.Candidate(pid, command, started)
        assertEquals("the program, not its launcher nor the helpers of the build", listOf(30L), AllocationTargets.order(listOf(
            candidate(10, "\"C:\\Program Files\\dotnet\\dotnet.exe\" run --project App.csproj", 100),
            candidate(20, "dotnet.exe \"C:\\Program Files\\dotnet\\sdk\\10.0.401\\MSBuild.dll\" /nodemode:1", 200),
            candidate(25, "dotnet.exe exec VBCSCompiler.dll", 250),
            candidate(30, "C:\\work\\App\\bin\\Debug\\net9.0\\App.exe", 300),
        )))
        assertEquals("the newest first", listOf(40L, 30L), AllocationTargets.order(listOf(candidate(30, "App.exe", 300), candidate(40, "dotnet App.dll --urls x", 400))))
        assertTrue("only the launcher so far: the program has not started", AllocationTargets.order(listOf(candidate(10, "dotnet run", 100))).isEmpty())

        // Windows: Java tells the path of the executable and nothing more, so the launcher and the nodes of the build look alike
        val host = "C:\\Program Files\\dotnet\\dotnet.exe"
        assertTrue("what runs is not known: not offered to the watcher", AllocationTargets.order(listOf(candidate(10, host, 100), candidate(20, "\"$host\"", 200), candidate(21, "", 210))).isEmpty())
        assertEquals("the program with its own executable is known by it", listOf(30L),
            AllocationTargets.order(listOf(candidate(10, host, 100), candidate(15, "C:\\Windows\\System32\\conhost.exe 0x4", 350), candidate(30, "C:\\work\\App\\bin\\Debug\\net9.0\\App.exe", 300))))
        assertEquals("the list of the system is what is believed", "\"$host\" run --project App.csproj", AllocationTargets.commandLine(host, "\"$host\" run --project App.csproj"))
        assertEquals(host, AllocationTargets.commandLine(host, ""))
        assertEquals(host, AllocationTargets.commandLine(host, null))
        assertFalse(AllocationTargets.isProgram(AllocationTargets.commandLine(host, "\"$host\" run --project App.csproj --launch-profile Allocations")))
        assertTrue(AllocationTargets.isProgram(AllocationTargets.commandLine(host, "\"$host\" C:\\work\\App\\bin\\Debug\\net9.0\\App.dll allocations")))

        // what the service relies on where Java is silent: the list of the platform knows the command line of this very process
        val own = com.intellij.execution.process.OSProcessUtil.getProcessList().firstOrNull { it.pid.toLong() == ProcessHandle.current().pid() }
        assertNotNull("the process of the tests is in the list of the platform", own)
        assertTrue("with its arguments: ${own!!.commandLine}", own.commandLine.trim().contains(' '))

        assertEquals("c:/work/app/a.cs", AllocationTargets.key("C:\\Work\\App\\A.cs", caseSensitive = false))
        assertEquals("/work/App/A.cs", AllocationTargets.key("/work/App/A.cs", caseSensitive = true))
    }

    private val source = "namespace Shop;\n\npublic class Orders\n{\n    public byte[] Buffer()\n    {\n        return new byte[1024];\n    }\n\n" +
        "    public List<int> Numbers()\n    {\n        var numbers = new List<int>();\n        for (var i = 0; i < 9; i++) numbers.Add(i);\n        return numbers;\n    }\n}\n"

    private fun line(path: String, number: Int, bytes: Long, share: Double, type: String = "System.Byte[]") =
        AllocationLine(path, number, "M()", bytes, bytes / 1024, share, bytes * 3, mapOf(type to 100))

    fun testTotalOfAMethodWithSeveralLines() {
        val lines = listOf(line("f", 7, 19_000_000, 95.0), line("f", 12, 40_000, 0.2), line("f", 13, 60_000, 0.3))
        assertEquals("the method of two lines that allocate, at its declaration; the one of one line has its own numbers",
            mapOf(10 to (100_000L to 0.5)), AllocationTotals.of(source, lines))
        assertTrue(AllocationTotals.of(source, lines.take(1)).isEmpty())
        assertTrue(AllocationTotals.of("", lines).isEmpty())
    }

    fun testNumbersAtTheLinesOfTheEditor() {
        val file = myFixture.addFileToProject("alloc/Orders.cs", source).virtualFile
        myFixture.configureFromExistingVirtualFile(file)
        val service = AllocationsService.getInstance(project)
        service.resolver = { path -> file.takeIf { path == file.path } }
        val painter = AllocationsLinePainter()
        fun textAt(line: Int) = painter.getLineExtensions(project, file, line)?.joinToString("") { it.text }?.trim()
        try {
            assertNull("nothing is watched", textAt(6))
            val lines = listOf(line(file.path, 7, 19_000_000, 95.0), line(file.path, 12, 40_000, 0.2, "System.Collections.Generic.List`1[System.Int32]"), line(file.path, 13, 60_000, 0.3, "System.Int32[]"))
            service.apply(AllocationMessage.Snapshot(3.0, 3.0, 19_100_000, 500, lines))

            assertEquals("the line 7 of the build is the line 6 from zero", "18.1 MB/s, 18.6K obj/s, byte[]  95%", textAt(6))
            assertEquals("39 KB/s, 39 obj/s, List<int>  0.2%", textAt(11))
            assertEquals("59 KB/s, 58 obj/s, int[]  0.3%", textAt(12))
            assertEquals("the total of the method at its declaration", "allocates 98 KB/s  0.5%", textAt(9))
            assertNull(textAt(4))
            assertNull(textAt(0))

            // two lines typed above: the numbers stay with their lines
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "using System;\n\n") }
            assertEquals("18.1 MB/s, 18.6K obj/s, byte[]  95%", textAt(8))
            assertNull(textAt(6))

            // the next report: the same lines of the build, other numbers; the list is no more among them
            service.apply(AllocationMessage.Snapshot(4.0, 4.0, 10_000_000, 600, listOf(line(file.path, 7, 9_000_000, 99.0), line(file.path, 13, 60_000, 1.0, "System.Int32[]"))))
            assertEquals("8.6 MB/s, 8.8K obj/s, byte[]  99%", textAt(8))
            assertNull("fell out of the window", textAt(13))
            assertEquals("59 KB/s, 58 obj/s, int[]  1.0%", textAt(14))
            assertNull("one line that allocates: no total", textAt(11))

            service.stop()
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertNull("the watcher has left: nothing is written", textAt(8))
        } finally {
            service.stop()
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            service.resolver = { null }
        }
    }

    fun testTheSwitchAndTheWatcher() {
        val actions = ActionManager.getInstance()
        val menu = actions.getAction("DotNet.MainMenu") as DefaultActionGroup
        val items = menu.childActionsOrStubs.map { actions.getId(it) }
        assertEquals("right after the monitor: not among the service items at the bottom of the menu", items.indexOf("DotNet.Monitor") + 1, items.indexOf("DotNet.Allocations"))
        val editorMenu = actions.getAction("EditorPopupMenu") as DefaultActionGroup
        assertTrue("and in the menu of the editor", editorMenu.childActionsOrStubs.any { actions.getId(it) == "DotNet.Allocations" })
        assertFalse("off until asked for", AllocationsService.getInstance(project).isEnabled)

        val sources = DotNetHelper.Sources.read("allocwatch", listOf("Program.cs", "AllocWatch.csproj"))
        assertNotNull("allocwatch/Program.cs and the project file are packed by the build", sources)
        assertTrue(sources!!.files.getValue("AllocWatch.csproj").contains("\$(HelperFramework)"))
        assertTrue(sources.files.getValue("Program.cs").contains("GCAllocationTick"))
    }
}
