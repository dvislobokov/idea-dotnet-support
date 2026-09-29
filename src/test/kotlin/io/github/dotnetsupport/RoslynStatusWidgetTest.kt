package io.github.dotnetsupport

import com.intellij.openapi.components.service
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lsp.RoslynPhase
import io.github.dotnetsupport.roslyn.RoslynServerUsage
import io.github.dotnetsupport.roslyn.RoslynStatusText
import io.github.dotnetsupport.roslyn.RoslynStatusWidget
import io.github.dotnetsupport.roslyn.RoslynStatusWidgetFactory
import io.github.dotnetsupport.roslyn.RoslynWorkspace
import java.time.Duration

/** The C# language server in the status bar: there while its process lives, with what it costs behind a click. */
class RoslynStatusWidgetTest : BasePlatformTestCase() {
    fun testTheWidgetIsThereOnlyWhileTheServerRuns() {
        val factory = StatusBarWidgetFactory.EP_NAME.extensionList.single { it.id == RoslynStatusWidgetFactory.ID }
        assertEquals("C# Language Server", factory.displayName)
        val workspace = project.service<RoslynWorkspace>()
        assertFalse("no server in a test", factory.isAvailable(project))

        workspace.serverStarted(ProcessHandle.current())
        try {
            assertTrue(workspace.isServerRunning)
            assertTrue(factory.isAvailable(project))
            val widget = factory.createWidget(project) as RoslynStatusWidget
            assertTrue(widget.getPresentation() is StatusBarWidget.MultipleTextValuesPresentation)
            assertSame(DotNetIcons.CSharp, widget.getIcon())
            assertTrue(widget.getSelectedValue().startsWith("Roslyn"))
            assertTrue(widget.getTooltipText().startsWith("C# Language Server: "))
        } finally {
            workspace.serverStopped(true)
        }
        assertFalse("stopped: the widget goes", factory.isAvailable(project))
    }

    fun testNoRowInTheWidgetOfLanguageServices() {
        val provider = io.github.dotnetsupport.roslyn.RoslynLspIntegrationProvider()
        assertEquals(emptyList<Any>(), provider.createWidgetItems(project, null))
        assertEquals(emptyList<Any>(), provider.createWidgetItems(project, myFixture.addFileToProject("widget/Program.cs", "class Program { }").virtualFile))
    }

    fun testWordsOfTheWidget() {
        assertEquals("Roslyn: starting...", RoslynStatusText.widget(RoslynPhase.STARTING, null))
        assertEquals("Roslyn: loading Shop.sln...", RoslynStatusText.widget(RoslynPhase.LOADING, "Shop.sln"))
        assertEquals("Roslyn: Shop.sln", RoslynStatusText.widget(RoslynPhase.READY, "Shop.sln"))
        assertEquals("Roslyn", RoslynStatusText.widget(RoslynPhase.READY, null))
        assertEquals("Loaded Shop.sln", RoslynStatusText.status(RoslynPhase.READY, "Shop.sln"))
        assertEquals("Loading the projects of the folder", RoslynStatusText.status(RoslynPhase.LOADING, null))
        assertEquals("Waiting for a solution to be selected", RoslynStatusText.status(RoslynPhase.CHOOSING_SOLUTION, null))
    }

    fun testNumbers() {
        assertEquals("measuring...", RoslynStatusText.cpu(null))
        assertEquals("0.0 %", RoslynStatusText.cpu(0.0))
        assertEquals("12.4 %", RoslynStatusText.cpu(12.44))
        assertEquals("812 MB", RoslynStatusText.memory(812L * 1024 * 1024))
        assertEquals("1.50 GB", RoslynStatusText.memory(1536L * 1024 * 1024))
        assertEquals("0 MB", RoslynStatusText.memory(0))
        assertEquals("45 s", RoslynStatusText.uptime(Duration.ofSeconds(45)))
        assertEquals("12 min", RoslynStatusText.uptime(Duration.ofSeconds(12 * 60 + 30)))
        assertEquals("1 h 5 min", RoslynStatusText.uptime(Duration.ofMinutes(65)))
        assertEquals("PID 4242, 3 processes, running 12 min", RoslynStatusText.process(4242, 3, Duration.ofMinutes(12)))
        assertEquals("PID 4242", RoslynStatusText.process(4242, 1, null))
    }

    fun testUsageOfAProcessTree() {
        val usage = RoslynServerUsage()
        assertNull(usage.sample(null))
        val first = usage.sample(ProcessHandle.current())!!
        assertEquals(ProcessHandle.current().pid(), first.pid)
        assertTrue("the memory of this very process", first.memoryBytes > 0)
        assertTrue(first.processes >= 1)
        assertNull("a share of the CPU needs two samples", first.cpuPercent)
        Thread.sleep(50)
        val second = usage.sample(ProcessHandle.current())!!
        assertNotNull(second.cpuPercent)
        assertTrue(second.cpuPercent!! in 0.0..100.0)
    }
}
