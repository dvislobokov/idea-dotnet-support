package io.github.dotnetsupport

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.monitor.Diagnostics
import io.github.dotnetsupport.monitor.HeapSnapshot
import io.github.dotnetsupport.monitor.HeapSnapshotDialog
import io.github.dotnetsupport.monitor.HeapSnapshots
import io.github.dotnetsupport.monitor.ThreadDump
import io.github.dotnetsupport.monitor.ThreadDumpFilter
import io.github.dotnetsupport.monitor.ThreadFrame
import io.github.dotnetsupport.settings.DotNetSettings
import io.github.dotnetsupport.settings.DotNetSettingsConfigurable
import java.io.File
import java.time.LocalTime

/** The outputs are the real ones: dotnet-stack and dotnet-gcdump 10.0 against a .NET 10 web application. */
class DiagnosticsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            DotNetTool.entries.forEach { DotNetSettings.getInstance().setToolPath(it, "") }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val stacks = """
        Thread (0x4430):
          [Native Frames]
          System.Private.CoreLib.il!System.Threading.Monitor.Wait(class System.Object,int32)
          Microsoft.Extensions.Hosting.Abstractions.il!Microsoft.Extensions.Hosting.HostingAbstractionsHostExtensions.Run(class Microsoft.Extensions.Hosting.IHost)
          app!Program.<Main>$(class System.String[])

        Thread (0x6C3C):
          [Native Frames]
          System.Private.CoreLib.il!System.Threading.LowLevelLifoSemaphore.Wait(int32,bool)
          System.Private.CoreLib.il!System.Threading.PortableThreadPool+WorkerThread.WorkerThreadStart()

        Thread (0x6F70):
          [Native Frames]
          System.Private.CoreLib.il!System.Threading.LowLevelLifoSemaphore.Wait(int32,bool)
          System.Private.CoreLib.il!System.Threading.PortableThreadPool+WorkerThread.WorkerThreadStart()
    """.trimIndent()

    fun testThreadDump() {
        val threads = ThreadDump.parse(stacks)
        assertEquals(listOf("0x4430", "0x6C3C", "0x6F70"), threads.map { it.id })
        assertEquals(4, threads[0].frames.size)
        val main = threads[0].frames.last()
        assertEquals("app" to "Program.<Main>$(class System.String[])", main.module to main.member)
        // ".il" marks a precompiled image, it is not a part of the assembly name
        assertEquals("System.Private.CoreLib", threads[0].frames[1].module)
        assertTrue(threads[0].hasUserCode)
        assertFalse(threads[1].hasUserCode)

        val text = ThreadDump.render(threads, "app (42)")
        assertTrue(text, text.startsWith("Thread dump of app (42): 3 managed threads, 1 in the code of the application\n"))
        // the thread of the application goes first, the idle workers are folded
        assertTrue(text, text.indexOf("Thread 0x4430  [application code]") < text.indexOf("2 threads with the same stack: 0x6C3C, 0x6F70"))
        assertEquals(1, Regex("WorkerThreadStart").findAll(text).count())
        assertTrue(text, "    at app!Program.<Main>$(class System.String[])\n" in text)

        assertEquals(emptyList<Any>(), ThreadDump.parse("Unhandled exception: process 1 is not a .NET process"))
    }

    fun testWhereAFrameComesFrom() {
        fun source(member: String) = ThreadFrame("Shop", member, "").source()
        assertEquals("Orders" to "Load", source("Shop.Services.Orders.Load(int32)"))
        // async state machine, lambda, local function, constructor, generic type, top-level statements
        assertEquals("Orders" to "LoadAsync", source("Shop.Services.Orders+<LoadAsync>d__5.MoveNext()"))
        assertEquals("Orders" to "Load", source("Shop.Services.Orders+<>c.<Load>b__0_0(class System.String)"))
        assertEquals("Orders" to "Load", source("Shop.Services.Orders+<>c__DisplayClass3_0.<Load>b__1()"))
        assertEquals("Orders" to "Orders", source("Shop.Services.Orders..ctor(class Shop.Db)"))
        assertEquals("Cache" to "Get", source("Shop.Cache`1.Get(!0)"))
        assertEquals("Program" to null, source("Program.<Main>$(class System.String[])"))
        assertNull(ThreadFrame("", "", "[Native Frames]").source())

        assertTrue(ThreadFrame("Shop", "Shop.Orders.Load()", "").isUserCode)
        assertFalse(ThreadFrame("System.Private.CoreLib", "System.Threading.Monitor.Wait()", "").isUserCode)
        assertFalse(ThreadFrame("Microsoft.AspNetCore.Server.Kestrel.Core", "X.Y()", "").isUserCode)
    }

    fun testFramesOfTheProjectBecomeLinks() {
        myFixture.addFileToProject("Other/Orders.cs", "class Orders { }")
        val file = myFixture.addFileToProject(
            "Shop/Orders.cs",
            "namespace Shop;\n\nclass Orders\n{\n    // Load(1) is called below\n    public async Task LoadAsync<T>(int id)\n    {\n    }\n}\n",
        )
        assertEquals(5, ThreadDumpFilter.lineOf(file.text, "LoadAsync"))
        assertEquals(0, ThreadDumpFilter.lineOf(file.text, "Missing"))

        val filter = ThreadDumpFilter(project)
        val line = "    at Shop!Shop.Orders+<LoadAsync>d__5.MoveNext()\n"
        val result = filter.applyFilter(line, 100 + line.length)!!
        val item = result.resultItems.single()
        // the member is the link; offsets are relative to the whole console text
        assertEquals("Shop.Orders+<LoadAsync>d__5.MoveNext", line.substring(item.highlightStartOffset - 100, item.highlightEndOffset - 100))
        val link = item.hyperlinkInfo as com.intellij.execution.filters.OpenFileHyperlinkInfo
        // the file of the project named like the module wins over a namesake
        assertEquals(file.virtualFile, link.descriptor!!.file)

        assertNull(filter.applyFilter("    at System.Private.CoreLib.il!System.Threading.Monitor.Wait(class System.Object,int32)\n", 90))
        assertNull(filter.applyFilter("    at Shop!Shop.Unknown.Run()\n", 40))
        assertNull(filter.applyFilter("Thread 0x4430  [application code]\n", 40))
    }

    fun testHeapSnapshot() {
        val report = """
                 39,114,093  GC Heap bytes
                     13,633  GC Heap objects
                     23,236  Total references

               Object Bytes     Count  Type
                    200,024       190  System.Byte[] (Bytes > 100K)  [System.Private.CoreLib.dll]
                     61,868         1  System.String (Bytes > 10K)  [System.Private.CoreLib.dll]
                     16,408         6  System.Byte[] (Bytes > 10K)  [System.Private.CoreLib.dll]
                      7,904         1  Entry<Microsoft.Extensions.DependencyInjection.ServiceLookup.ServiceIdentifier,System.Object>[] (Bytes > 1K)  [System.Private.CoreLib.dll]
                         72         1  System.Net.Http.HttpRequestMessage  [System.Net.Http.dll]
                         22     2,094  System.String  [System.Private.CoreLib.dll]
        """.trimIndent()
        val snapshot = HeapSnapshot.parse(report, "app (42)", LocalTime.of(10, 15, 0))!!
        assertEquals(39_114_093L, snapshot.totalBytes)
        assertEquals(13_633L, snapshot.objects)
        // "Object Bytes" is the size of one object, and the size classes of a type are added up
        val bytes = snapshot.types.first()
        assertEquals("System.Byte[]", bytes.name)
        assertEquals("System.Private.CoreLib.dll", bytes.module)
        assertEquals(196L, bytes.count)
        assertEquals(200_024L * 190 + 16_408L * 6, bytes.bytes)
        val strings = snapshot.types.single { it.name == "System.String" }
        assertEquals(2_095L to 61_868L + 22L * 2_094, strings.count to strings.bytes)
        assertEquals(listOf("System.Byte[]", "System.String"), snapshot.types.take(2).map { it.name })
        assertTrue(snapshot.label, snapshot.label.startsWith("10:15:00  37"))

        // the thousands separator follows the culture of the machine
        val russian = HeapSnapshot.parse("     39 114 093  GC Heap bytes\n\n   Object Bytes     Count  Type\n        200 024       190  System.Byte[]  [System.Private.CoreLib.dll]\n", "p")!!
        assertEquals(39_114_093L to 200_024L * 190, russian.totalBytes to russian.types.single().bytes)
        assertNull(HeapSnapshot.parse("Unhandled exception: the process does not exist", "p"))
    }

    fun testHeapComparison() {
        fun snapshot(time: Int, vararg rows: String) = HeapSnapshot.parse(
            "   Object Bytes     Count  Type\n" + rows.joinToString("\n") { "   $it" } + "\n", "p", LocalTime.of(10, time, 0))!!

        val before = snapshot(0, "100     10  Shop.Order  [Shop.dll]", "50     4  Shop.Session  [Shop.dll]", "24     100  System.String  [System.Private.CoreLib.dll]")
        val after = snapshot(5, "100     510  Shop.Order  [Shop.dll]", "24     100  System.String  [System.Private.CoreLib.dll]", "32     7  Shop.Cart  [Shop.dll]")

        val differences = after.compareWith(before).associateBy { it.type.name }
        assertEquals(500L to 50_000L, differences.getValue("Shop.Order").let { it.countDelta to it.bytesDelta })
        assertEquals(0L to 0L, differences.getValue("System.String").let { it.countDelta to it.bytesDelta })
        // a new type, and one that is gone
        assertEquals(7L to 224L, differences.getValue("Shop.Cart").let { it.countDelta to it.bytesDelta })
        assertEquals(-4L to -200L, differences.getValue("Shop.Session").let { it.countDelta to it.bytesDelta })
        assertEquals(0L, differences.getValue("Shop.Session").type.count)

        val summary = HeapSnapshotDialog.summaryText(after, before)
        assertTrue(summary, "<b>+" in summary && "since 10:00:00" in summary)
        assertTrue("Take another snapshot" in HeapSnapshotDialog.summaryText(after, null))

        val store = HeapSnapshots.getInstance(project)
        repeat(10) { store.add(7, snapshot(it, "100     $it  Shop.Order  [Shop.dll]")) }
        assertEquals(8, store.of(7).size)
        assertEquals(LocalTime.of(10, 9, 0), store.of(7).last().takenAt)
        assertEquals(emptyList<Any>(), store.of(8))
    }

    fun testToolsAreConfigurable() {
        val settings = DotNetSettings.getInstance()
        val custom = FileUtil.createTempFile("dotnet-stack", ".exe", true)
        assertEquals("", settings.toolPath(DotNetTool.STACK))
        settings.setToolPath(DotNetTool.STACK, "  ${custom.path} ")
        assertEquals(custom.path, settings.toolPath(DotNetTool.STACK))
        assertEquals(custom, DotNetTool.STACK.find())
        // the other tools are not affected; a path that is gone falls back to the detection
        assertEquals("", settings.toolPath(DotNetTool.GCDUMP))
        settings.setToolPath(DotNetTool.STACK, custom.path + ".missing")
        assertEquals(DotNetTool.STACK.detect(), DotNetTool.STACK.find())
        settings.setToolPath(DotNetTool.STACK, "")
        assertTrue(settings.state.toolPaths.isEmpty())

        assertEquals(listOf("tool", "update", "--global", "dotnet-gcdump"), DotNetTool.GCDUMP.installCommand())
        assertEquals(listOf("report", "--process-id", "42"), Diagnostics.threadDumpCommand(File("dotnet-stack"), 42).parametersList.list)
        assertEquals(listOf("report", "--process-id", "42"), Diagnostics.heapReportCommand(File("dotnet-gcdump"), 42).parametersList.list)

        // the page shows a row per tool and notices an edited path
        val page = DotNetSettingsConfigurable(project)
        try {
            assertNotNull(page.createComponent())
            page.reset()
            assertFalse(page.isModified)
        } finally {
            page.disposeUIResources()
        }
    }
}
