package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointType
import io.github.dotnetsupport.debugger.DotNetFunctionBreakpointHandler
import io.github.dotnetsupport.debugger.DotNetFunctionBreakpointType

/** Function breakpoints (a method by its name, as "New Function Breakpoint" of Visual Studio): what goes to `setFunctionBreakpoints`. */
class DebugFunctionBreakpointsTest : BasePlatformTestCase() {
    fun testTheBreakpointGoesByNameWithItsConditionAndHitCount() {
        assertEquals(mapOf("name" to "Shop.Orders.Place"), DotNetFunctionBreakpointHandler.breakpointJson(" Shop.Orders.Place ", null, null))
        assertEquals(mapOf("name" to "Place", "condition" to "total > 10", "hitCondition" to ">=3"),
            DotNetFunctionBreakpointHandler.breakpointJson("Place", " total > 10 ", ">= 3"))
        assertEquals("an invalid hit count is left out", mapOf("name" to "Place"), DotNetFunctionBreakpointHandler.breakpointJson("Place", "", "often"))
    }

    fun testTheTypeShowsTheNameOfTheMethod() {
        val type = XBreakpointType.EXTENSION_POINT_NAME.findExtension(DotNetFunctionBreakpointType::class.java)!!
        val properties = type.createProperties().also { it.name = "Orders.Place" }
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val breakpoint = com.intellij.openapi.application.WriteAction.computeAndWait<com.intellij.xdebugger.breakpoints.XBreakpoint<*>, RuntimeException> {
            manager.addBreakpoint(type, properties)
        }
        try {
            assertEquals("Orders.Place", type.getDisplayText(breakpoint as com.intellij.xdebugger.breakpoints.XBreakpoint<io.github.dotnetsupport.debugger.DotNetFunctionBreakpointProperties>))
            assertTrue(type.isAddBreakpointButtonVisible)
        } finally {
            com.intellij.openapi.application.WriteAction.runAndWait<RuntimeException> { manager.removeBreakpoint(breakpoint) }
        }
    }
}
