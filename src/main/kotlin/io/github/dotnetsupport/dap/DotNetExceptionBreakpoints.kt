package io.github.dotnetsupport.dap

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapEventConsumer
import com.intellij.platform.dap.DapExceptionBreakpoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel
import io.github.dotnetsupport.run.DotNetExceptionBreakpoints
import io.github.dotnetsupport.run.DotNetExceptionFilter
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments
import javax.swing.Icon
import javax.swing.JComponent

/** Which exceptions (empty: any) and when, as the "Break when" of Rider: thrown, user-unhandled, unhandled. */
class DotNetExceptionBreakpointProperties : XBreakpointProperties<DotNetExceptionBreakpointProperties.State>() {
    class State {
        @JvmField var types: String = ""
        @JvmField var thrown: Boolean = false
        @JvmField var userUnhandled: Boolean = true
        @JvmField var unhandled: Boolean = true
    }

    private var state = State()

    var types: String
        get() = state.types
        set(value) { state.types = value }

    var filters: Set<DotNetExceptionFilter>
        get() = buildSet {
            if (state.thrown) add(DotNetExceptionFilter.THROWN)
            if (state.userUnhandled) add(DotNetExceptionFilter.USER_UNHANDLED)
            if (state.unhandled) add(DotNetExceptionFilter.UNHANDLED)
        }
        set(value) {
            state.thrown = DotNetExceptionFilter.THROWN in value
            state.userUnhandled = DotNetExceptionFilter.USER_UNHANDLED in value
            state.unhandled = DotNetExceptionFilter.UNHANDLED in value
        }

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }
}

typealias DotNetExceptionBreakpoint = XBreakpoint<DotNetExceptionBreakpointProperties>

/**
 * Exception breakpoints of the .NET debugger. The default one is "any exception, when it is unhandled or user-unhandled": stopping at
 * every thrown exception is too noisy to be a default. The platform DAP client needs the type, and has no handler for it:
 * see [DotNetExceptionBreakpointHandler].
 */
class DotNetExceptionBreakpointType :
    XBreakpointType<DotNetExceptionBreakpoint, DotNetExceptionBreakpointProperties>("dotnet-exception", ".NET Exception Breakpoints") {

    override fun getDisplayText(breakpoint: DotNetExceptionBreakpoint): String =
        DotNetExceptionBreakpoints.displayText(breakpoint.properties?.types, breakpoint.properties?.filters.orEmpty())

    override fun createProperties(): DotNetExceptionBreakpointProperties = DotNetExceptionBreakpointProperties()

    override fun getEnabledIcon(): Icon = AllIcons.Debugger.Db_exception_breakpoint
    override fun getDisabledIcon(): Icon = AllIcons.Debugger.Db_disabled_exception_breakpoint

    /** The platform makes default breakpoints disabled; this one is what stops at a crash, so it is on from the start. */
    override fun createDefaultBreakpoint(creator: XBreakpointCreator<DotNetExceptionBreakpointProperties>): DotNetExceptionBreakpoint =
        creator.createBreakpoint(createProperties()).apply { isEnabled = true }

    override fun isAddBreakpointButtonVisible(): Boolean = true

    /** A new breakpoint is for particular exceptions, so it stops when they are thrown; the types are typed in the panel of the dialog. */
    override fun addBreakpoint(project: Project, parentComponent: JComponent?): DotNetExceptionBreakpoint {
        val properties = createProperties().apply { filters = setOf(DotNetExceptionFilter.THROWN) }
        return XDebuggerManager.getInstance(project).breakpointManager.addBreakpoint(this, properties)
    }

    override fun createCustomPropertiesPanel(project: Project): XBreakpointCustomPropertiesPanel<DotNetExceptionBreakpoint> = PropertiesPanel()

    private class PropertiesPanel : XBreakpointCustomPropertiesPanel<DotNetExceptionBreakpoint>() {
        private val types = JBTextField().apply { emptyText.text = "Any exception" }
        private val boxes = DotNetExceptionFilter.entries.associateWith { JBCheckBox(it.title) }

        override fun getComponent(): JComponent = panel {
            row("Exception types:") {
                cell(types).align(AlignX.FILL).comment("Full names, <code>*</code> for any part, <code>!</code> to exclude: <code>System.IO.*, !System.OperationCanceledException</code>")
            }
            row("Break when:") { boxes.values.forEach { cell(it) } }
        }

        override fun loadFrom(breakpoint: DotNetExceptionBreakpoint) {
            val properties = breakpoint.properties ?: return
            types.text = properties.types
            boxes.forEach { (filter, box) -> box.isSelected = filter in properties.filters }
        }

        override fun saveTo(breakpoint: DotNetExceptionBreakpoint) {
            val properties = breakpoint.properties ?: return
            properties.types = DotNetExceptionBreakpoints.typeCondition(types.text).orEmpty()
            properties.filters = boxes.filterValues { it.isSelected }.keys
        }
    }
}

/**
 * Hands exception breakpoints to the breakpoint manager of the DAP session, as the Jupyter debugger of the platform does: the process
 * of the platform registers line breakpoints only. One breakpoint of the IDE is a filter of the adapter per chosen "break when",
 * each with the types as its condition.
 */
class DotNetExceptionBreakpointHandler(private val session: DapDebugSession) :
    XBreakpointHandler<DotNetExceptionBreakpoint>(DotNetExceptionBreakpointType::class.java) {
    /** What was registered, to take away exactly that: the properties may have changed since. */
    private val registered = HashMap<DotNetExceptionBreakpoint, List<DapExceptionBreakpoint>>()

    override fun registerBreakpoint(breakpoint: DotNetExceptionBreakpoint) {
        val properties = breakpoint.properties ?: return
        val condition = DotNetExceptionBreakpoints.typeCondition(properties.types)
        val filters = properties.filters.map { DapExceptionBreakpoint.create(it.id, condition, breakpoint) }
        synchronized(registered) { registered[breakpoint] = filters }
        session.commandProcessor.submitCommand { with(session.breakpointManager) { filters.forEach { addExceptionBreakpoint(it) } } }
    }

    override fun unregisterBreakpoint(breakpoint: DotNetExceptionBreakpoint, temporary: Boolean) {
        val filters = synchronized(registered) { registered.remove(breakpoint) } ?: return
        session.commandProcessor.submitCommand { with(session.breakpointManager) { filters.forEach { removeExceptionBreakpoint(it) } } }
    }
}

/**
 * "No exception breakpoints" has to be said aloud. The platform sends `setExceptionBreakpoints` only when there is an active exception
 * breakpoint; an adapter that has heard nothing applies the defaults of its filters (`unhandled` and `user-unhandled` are on in
 * `dotnet-debugger`), so a program stops at an exception although every exception breakpoint is disabled - and the platform, meeting a
 * stop it has no breakpoint for, switches the default breakpoint of the type back on. Sent on `initialized`, before the configuration is
 * done; with an active breakpoint nothing is sent here, the list of the platform follows anyway.
 */
class NoExceptionBreakpoints(private val project: Project, private val commandProcessor: DapCommandProcessor) {
    fun recording(consumer: DapEventConsumer): DapEventConsumer = object : DapEventConsumer by consumer {
        override fun initialized() {
            if (!hasActiveBreakpoints()) commandProcessor.submitCommand { server.setExceptionBreakpoints(emptyFilters()).await() }
            consumer.initialized()
        }
    }

    private fun hasActiveBreakpoints(): Boolean {
        val type = XBreakpointType.EXTENSION_POINT_NAME.findExtension(DotNetExceptionBreakpointType::class.java) ?: return false
        return XDebuggerManager.getInstance(project).breakpointManager.getBreakpoints(type).any { isActive(it.isEnabled, it.properties?.filters.orEmpty()) }
    }

    companion object {
        /** An enabled breakpoint with nothing chosen in "Break when" sends no filter either. */
        fun isActive(enabled: Boolean, filters: Set<DotNetExceptionFilter>): Boolean = enabled && filters.isNotEmpty()

        fun emptyFilters(): SetExceptionBreakpointsArguments = SetExceptionBreakpointsArguments().also {
            it.filters = emptyArray()
            it.filterOptions = emptyArray()
        }
    }
}
