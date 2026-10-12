package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import io.github.dotnetsupport.newproject.TemplateOptionsLayout
import io.github.dotnetsupport.newproject.TemplateOptionsView
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/** The options of a template with many of them scroll, and the dialog stays on the screen. */
class TemplateOptionsScrollTest : BasePlatformTestCase() {
    private fun rows(width: Int, height: Int) = JPanel().apply { preferredSize = Dimension(width, height) }

    fun testTheViewportIsAsHighAsTheRowsUntilTheLimit() {
        val limit = 320
        assertEquals("a few rows: all of them", 120, TemplateOptionsLayout.viewportHeight(120, 1080, limit))
        assertEquals("many rows: the limit", 320, TemplateOptionsLayout.viewportHeight(2_000, 1080, limit))
        assertEquals("a small screen: 40% of it", 240, TemplateOptionsLayout.viewportHeight(2_000, 600, limit))
        assertEquals(0, TemplateOptionsLayout.viewportHeight(0, 1080, limit))
    }

    fun testTheWindowGrowsWithinTheScreen() {
        val screen = Dimension(1920, 1080)
        assertEquals(Dimension(700, 640), TemplateOptionsLayout.windowSize(Dimension(600, 400), Dimension(700, 640), screen))
        assertEquals("never beyond 90% of the screen", Dimension(1728, 972), TemplateOptionsLayout.windowSize(Dimension(600, 400), Dimension(3_000, 2_000), screen))
        assertEquals("it does not shrink", Dimension(900, 700), TemplateOptionsLayout.windowSize(Dimension(900, 700), Dimension(500, 300), screen))
        assertEquals("a window larger than the screen comes back", Dimension(1728, 972), TemplateOptionsLayout.windowSize(Dimension(2_500, 1_500), Dimension(500, 300), screen))
    }

    fun testManyRowsScroll() {
        val scroll = TemplateOptionsView.scrolled(rows(400, 2_000), 1080) as JScrollPane
        assertEquals(JBUI.scale(TemplateOptionsLayout.MAX_HEIGHT), scroll.preferredSize.height)
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.horizontalScrollBarPolicy)
        assertEquals(JBUI.scale(TemplateOptionsLayout.WHEEL_STEP), scroll.verticalScrollBar.unitIncrement)
        assertTrue("room for the scroll bar", scroll.preferredSize.width > 400)
        assertEquals("the rows themselves keep their height", 2_000, scroll.viewport.view.preferredSize.height)
    }

    fun testFewRowsTakeWhatTheyNeed() {
        val scroll = TemplateOptionsView.scrolled(rows(400, 90), 1080) as JScrollPane
        assertEquals(Dimension(400, 90), scroll.preferredSize)
    }

    fun testWideRowsDoNotWidenTheDialog() {
        val scroll = TemplateOptionsView.scrolled(rows(3_000, 90), 1080) as JScrollPane
        assertEquals(JBUI.scale(TemplateOptionsLayout.MAX_WIDTH), scroll.preferredSize.width)
    }
}
