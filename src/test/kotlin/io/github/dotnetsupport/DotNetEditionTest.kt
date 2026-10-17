package io.github.dotnetsupport

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.allocations.AllocationLine
import io.github.dotnetsupport.allocations.AllocationMessage
import io.github.dotnetsupport.allocations.AllocationsLinePainter
import io.github.dotnetsupport.allocations.AllocationsService
import io.github.dotnetsupport.edition.DotNetEdition
import io.github.dotnetsupport.edition.EditionMode
import io.github.dotnetsupport.monitor.MonitorPanel

/**
 * The editions (edition/DotNetEdition.kt): GIGA IDE's work mode read by its name, nothing off outside GIGA IDE, and in GIGA IDE without Pro
 * the profiler of allocations off and the monitor down to CPU, memory and GC.
 */
class DotNetEditionTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            DotNetEdition.setModeForTests(null)
            PropertiesComponent.getInstance(project).unsetValue(AllocationsService.ENABLED_KEY)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testTheWorkModesOfGigaIde() {
        assertEquals(EditionMode.PRO, DotNetEdition.modeOf("PRO_PERSON"))
        assertEquals(EditionMode.PRO, DotNetEdition.modeOf("PRO_COMPANY"))
        assertEquals(EditionMode.TRIAL, DotNetEdition.modeOf("TRIAL"))
        assertEquals(EditionMode.FREE, DotNetEdition.modeOf("COMMUNITY"))
        assertEquals("a mode this plugin does not know is not Pro", EditionMode.FREE, DotNetEdition.modeOf("ENTERPRISE"))
        assertEquals(EditionMode.FREE, DotNetEdition.modeOf(null))
    }

    fun testOutsideGigaIdeNothingIsOff() {
        assertFalse("the build of the tests has the checks on", DotNetEdition.isUnlockedBuild)
        assertEquals("IntelliJ IDEA has no plugin com.gigaide.pro", EditionMode.OTHER_IDE, DotNetEdition.mode())
        assertFalse(DotNetEdition.isPro())
        assertFalse(DotNetEdition.isFree())
        DotNetEdition.setModeForTests(EditionMode.TRIAL)
        assertTrue("the trial works as Pro", DotNetEdition.isPro())
        assertFalse(DotNetEdition.isFree())
    }

    fun testWithoutProTheAllocationsAreOff() {
        val service = AllocationsService.getInstance(project)
        val action = ActionManager.getInstance().getAction("DotNet.Allocations")
        fun update() = TestActionEvent.createTestEvent(action, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()).also { action.update(it) }.presentation

        DotNetEdition.setModeForTests(EditionMode.FREE)
        PropertiesComponent.getInstance(project).setValue(AllocationsService.ENABLED_KEY, true, false)
        assertFalse("switched on before, off without Pro", service.isEnabled)
        val free = update()
        assertTrue("in the main menu it stays", free.isVisible)
        assertFalse(free.isEnabled)
        assertTrue(free.description, free.description.startsWith(DotNetEdition.PRO_ONLY))

        // the numbers of a watcher still running when Pro ends are not written
        val file = myFixture.addFileToProject("edition/Orders.cs", "class Orders\n{\n    void M() { var a = new byte[100]; }\n}\n").virtualFile
        myFixture.configureFromExistingVirtualFile(file)
        service.resolver = { path -> file.takeIf { path == file.path } }
        try {
            DotNetEdition.setModeForTests(EditionMode.PRO)
            service.apply(AllocationMessage.Snapshot(1.0, 1.0, 1000, 10, listOf(AllocationLine(file.path, 3, "M()", 1000, 1, 100.0, 3000, mapOf("System.Byte[]" to 100)))))
            val painter = AllocationsLinePainter()
            assertNotNull("Pro: written", painter.getLineExtensions(project, file, 2))
            DotNetEdition.setModeForTests(EditionMode.FREE)
            assertNull("without Pro: not written", painter.getLineExtensions(project, file, 2))
        } finally {
            service.stop()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            service.resolver = { null }
        }

        DotNetEdition.setModeForTests(EditionMode.PRO)
        assertTrue("the switch as it was", service.isEnabled)
        assertTrue(update().isEnabled)
        DotNetEdition.setModeForTests(null)
        assertTrue("outside GIGA IDE: as before", update().isEnabled)
    }

    fun testWithoutProTheMonitorShowsCpuMemoryAndGc() {
        DotNetEdition.setModeForTests(EditionMode.FREE)
        val panel = MonitorPanel(project, testRootDisposable)
        assertEquals(listOf("CPU", "Memory", "Time in GC", "GC collections"), panel.shownCharts())
        assertTrue("the rest is named, with the way to Pro", panel.isProNoteShown)

        // Pro activated while the window is open: the next sample brings the charts back
        DotNetEdition.setModeForTests(EditionMode.PRO)
        panel.applyEdition()
        assertEquals(
            listOf("CPU", "Memory", "Allocation rate", "Time in GC", "GC collections", "Active requests", "Request duration", "Exceptions and contention", "Thread pool queue"),
            panel.shownCharts(),
        )
        assertFalse(panel.isProNoteShown)

        DotNetEdition.setModeForTests(null)
        panel.applyEdition()
        assertEquals("outside GIGA IDE: every chart", 9, panel.shownCharts().size)
    }
}
