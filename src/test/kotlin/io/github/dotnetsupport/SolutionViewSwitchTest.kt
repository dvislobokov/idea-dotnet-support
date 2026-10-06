package io.github.dotnetsupport

import io.github.dotnetsupport.view.SolutionViewPane
import io.github.dotnetsupport.view.SolutionViewSwitch
import io.github.dotnetsupport.view.SolutionViewSwitch.Decision
import io.github.dotnetsupport.view.SolutionViewSwitch.State
import org.junit.Assert.assertEquals
import org.junit.Test

/** The decision of the switch to the Solution view on opening a project (`SolutionViewActivator`). */
class SolutionViewSwitchTest {
    private val ready = State(switchEnabled = true, activatedBefore = false, hasSolutions = true, paneRegistered = true, currentViewId = "ProjectPane")

    @Test
    fun `first open of a solution switches once the view shows a pane`() {
        assertEquals(Decision.SWITCH, SolutionViewSwitch.decide(ready))
        assertEquals(Decision.SWITCH, SolutionViewSwitch.decide(ready.copy(currentViewId = "ProjectFilesPane")))
    }

    @Test
    fun `the view not yet built waits instead of switching and marking`() {
        // the bug: changeView before the tool window content exists selects nothing, and the old activator never tried again
        assertEquals(Decision.WAIT, SolutionViewSwitch.decide(ready.copy(currentViewId = null)))
        assertEquals(Decision.WAIT, SolutionViewSwitch.decide(ready.copy(currentViewId = null, paneRegistered = false)))
    }

    @Test
    fun `already on the solution view still counts as switched`() {
        assertEquals(Decision.SWITCH, SolutionViewSwitch.decide(ready.copy(currentViewId = SolutionViewPane.ID)))
    }

    @Test
    fun `a reopen after the switch keeps the pane of the user`() {
        assertEquals(Decision.SKIP, SolutionViewSwitch.decide(ready.copy(activatedBefore = true)))
        assertEquals(Decision.SKIP, SolutionViewSwitch.decide(ready.copy(activatedBefore = true, currentViewId = null)))
    }

    @Test
    fun `nothing to switch to`() {
        assertEquals(Decision.SKIP, SolutionViewSwitch.decide(ready.copy(switchEnabled = false)))
        assertEquals(Decision.SKIP, SolutionViewSwitch.decide(ready.copy(hasSolutions = false)))
        assertEquals(Decision.SKIP, SolutionViewSwitch.decide(ready.copy(paneRegistered = false)))
    }
}
