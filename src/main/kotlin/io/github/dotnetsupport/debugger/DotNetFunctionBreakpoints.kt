package io.github.dotnetsupport.debugger

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import io.github.dotnetsupport.run.HitCondition
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.JComponent

/** A breakpoint on a method by its name, wherever it is (a library, generated code): `Namespace.Class.Method`, `Class.Method` or `Method`. */
class DotNetFunctionBreakpointProperties : XBreakpointProperties<DotNetFunctionBreakpointProperties.State>() {
    class State {
        @JvmField var name: String = ""
        @JvmField var hitCondition: String = ""
    }

    private var state = State()

    var name: String
        get() = state.name
        set(value) { state.name = value }

    var hitCondition: String
        get() = state.hitCondition
        set(value) { state.hitCondition = value }

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }
}

typealias DotNetFunctionBreakpoint = XBreakpoint<DotNetFunctionBreakpointProperties>

/**
 * Function breakpoints, as "New Function Breakpoint" of Visual Studio: added with "+" in the Breakpoints dialog by the name of the method.
 * The adapter binds them by name in every loaded module (`setFunctionBreakpoints`; `dap-probe/FINDINGS.md`: in user code). The id is the
 * one of the breakpoints users save: it must not change.
 */
class DotNetFunctionBreakpointType :
    XBreakpointType<DotNetFunctionBreakpoint, DotNetFunctionBreakpointProperties>("dotnet-function", ".NET Function Breakpoints") {

    override fun getDisplayText(breakpoint: DotNetFunctionBreakpoint): String = breakpoint.properties?.name?.ifBlank { null } ?: "(no method)"

    override fun createProperties(): DotNetFunctionBreakpointProperties = DotNetFunctionBreakpointProperties()

    override fun getEnabledIcon(): Icon = AllIcons.Debugger.Db_method_breakpoint
    override fun getDisabledIcon(): Icon = AllIcons.Debugger.Db_disabled_method_breakpoint

    /** With an editor for expressions the platform shows "Condition"; it goes to the adapter with the breakpoint. */
    override fun getEditorsProvider(breakpoint: DotNetFunctionBreakpoint, project: Project): XDebuggerEditorsProvider = DotNetEditorsProvider()

    override fun isAddBreakpointButtonVisible(): Boolean = true

    override fun addBreakpoint(project: Project, parentComponent: JComponent?): DotNetFunctionBreakpoint? {
        val name = Messages.showInputDialog(project, "Method to stop in: Namespace.Class.Method, Class.Method or Method", "Add .NET Function Breakpoint", null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val properties = createProperties().also { it.name = name }
        return XDebuggerManager.getInstance(project).breakpointManager.addBreakpoint(this, properties)
    }

    override fun createCustomPropertiesPanel(project: Project): XBreakpointCustomPropertiesPanel<DotNetFunctionBreakpoint> = PropertiesPanel()

    private class PropertiesPanel : XBreakpointCustomPropertiesPanel<DotNetFunctionBreakpoint>() {
        private val name = JBTextField().apply { emptyText.text = "Namespace.Class.Method" }
        private val hitCondition = JBTextField().apply { emptyText.text = "Every hit" }

        override fun getComponent(): JComponent = panel {
            row("Method:") { cell(name).align(AlignX.FILL).comment("<code>Shop.Orders.Place</code>, <code>Orders.Place</code> or <code>Place</code> — every overload") }
            row("Hit count:") {
                cell(hitCondition).align(AlignX.FILL).comment("Stop on the 5th hit: <code>5</code>; from the 3rd on: <code>&gt;= 3</code>; every 10th: <code>% 10</code>")
                    .validationOnInput { if (HitCondition.isValid(it.text)) null else error("A positive number, optionally after ==, >=, >, <=, < or %") }
            }
        }

        override fun loadFrom(breakpoint: DotNetFunctionBreakpoint) {
            name.text = breakpoint.properties?.name.orEmpty()
            hitCondition.text = breakpoint.properties?.hitCondition.orEmpty()
        }

        override fun saveTo(breakpoint: DotNetFunctionBreakpoint) {
            val properties = breakpoint.properties ?: return
            val newName = name.text.trim().ifEmpty { properties.name }
            val hits = hitCondition.text.trim().takeIf { HitCondition.isValid(it) }.orEmpty()
            if (newName == properties.name && hits == properties.hitCondition) return
            properties.name = newName
            properties.hitCondition = hits
            (breakpoint as? com.intellij.xdebugger.impl.breakpoints.XBreakpointBase<*, *, *>)?.fireBreakpointChanged()
        }
    }
}

/**
 * The function breakpoints of a session as one `setFunctionBreakpoints`, which replaces the whole list; the adapter answers in the order it
 * was sent, which matches its ids to the breakpoints of the IDE. Nothing is sent to an adapter without `supportsFunctionBreakpoints`.
 */
class DotNetFunctionBreakpointHandler(private val process: DotNetDebugProcess) :
    XBreakpointHandler<DotNetFunctionBreakpoint>(DotNetFunctionBreakpointType::class.java) {
    private val registered = ConcurrentHashMap.newKeySet<DotNetFunctionBreakpoint>()
    private val byId = ConcurrentHashMap<Int, DotNetFunctionBreakpoint>()

    override fun registerBreakpoint(breakpoint: DotNetFunctionBreakpoint) {
        registered.add(breakpoint)
        if (process.configured) send()
    }

    override fun unregisterBreakpoint(breakpoint: DotNetFunctionBreakpoint, temporary: Boolean) {
        registered.remove(breakpoint)
        if (process.configured) send()
    }

    fun find(id: Int): DotNetFunctionBreakpoint? = byId[id]

    fun send(): CompletableFuture<*> {
        if (process.capabilities.bool("supportsFunctionBreakpoints") != true) return CompletableFuture.completedFuture(null)
        val breakpoints = registered.filter { it.isEnabled && !it.properties?.name.isNullOrBlank() }.sortedBy { it.properties?.name }
        val list = breakpoints.map { breakpointJson(it.properties!!.name, it.conditionExpression?.expression, it.properties?.hitCondition) }
        return process.connection.request("setFunctionBreakpoints", json("breakpoints" to list), DotNetDebugProcess.REQUEST_TIMEOUT_MS)
            .thenAccept { answer ->
                byId.clear()
                answer.objects("breakpoints").zip(breakpoints).forEach { (dap, ide) ->
                    dap.int("id")?.let { byId[it] = ide }
                    // the platform marks only line breakpoints verified: a refusal goes to the console, where the user looks after a run
                    if (dap.bool("verified") != true) dap.string("message")?.takeIf { it.isNotBlank() }?.let { process.print("Function breakpoint ${ide.properties?.name}: $it\n", com.intellij.execution.process.ProcessOutputTypes.SYSTEM) }
                }
            }
            .exceptionally { error ->
                process.print("Function breakpoints were not set: ${DotNetDebugProcess.errorText(error)}\n", com.intellij.execution.process.ProcessOutputTypes.STDERR)
                null
            }
    }

    companion object {
        fun breakpointJson(name: String, condition: String?, hitCondition: String?): Map<String, Any> = buildMap {
            put("name", name.trim())
            condition?.trim()?.takeIf { it.isNotEmpty() }?.let { put("condition", it) }
            HitCondition.normalize(hitCondition)?.takeIf { HitCondition.isValid(it) }?.let { put("hitCondition", it) }
        }
    }
}
