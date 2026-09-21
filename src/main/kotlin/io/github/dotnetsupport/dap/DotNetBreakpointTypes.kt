package io.github.dotnetsupport.dap

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import io.github.dotnetsupport.lang.CSharpBreakpointLines
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.run.BreakpointExtras
import io.github.dotnetsupport.run.HitCondition
import javax.swing.JComponent

/** What the debug adapter can do with a line breakpoint and the DAP client of the platform has no place for. */
class DotNetLineBreakpointProperties : XBreakpointProperties<DotNetLineBreakpointProperties.State>() {
    class State {
        @JvmField var hitCondition: String = ""
        @JvmField var logMessage: String = ""
    }

    private var state = State()

    var hitCondition: String
        get() = state.hitCondition
        set(value) { state.hitCondition = value }

    var logMessage: String
        get() = state.logMessage
        set(value) { state.logMessage = value }

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }
}

/** Line breakpoints in `.cs` files; registered with the debugger, because without one they would stop nothing. */
class CSharpLineBreakpointType : XLineBreakpointType<DotNetLineBreakpointProperties>("dotnet-line", ".NET Line Breakpoints") {
    override fun createBreakpointProperties(file: VirtualFile, line: Int): DotNetLineBreakpointProperties = DotNetLineBreakpointProperties()

    /** For a breakpoint read from the workspace file: without it the saved hit count and log message are dropped on load (seen live). */
    override fun createProperties(): DotNetLineBreakpointProperties = DotNetLineBreakpointProperties()

    /** With an editor for expressions the platform shows "Condition" for the breakpoint; the condition itself is sent by its DAP client. */
    override fun getEditorsProvider(breakpoint: XLineBreakpoint<DotNetLineBreakpointProperties>, project: Project): XDebuggerEditorsProvider = DotNetEditorsProvider()

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean {
        if (file.fileType != CSharpFileType) return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        // asked for every line the mouse passes in the gutter: one scan per change of the text
        val cached = document.getUserData(LINES)?.takeIf { it.first == document.modificationStamp }
            ?: (document.modificationStamp to CSharpBreakpointLines.find(document.immutableCharSequence)).also { document.putUserData(LINES, it) }
        return line in cached.second
    }

    override fun createCustomPropertiesPanel(project: Project): XBreakpointCustomPropertiesPanel<XLineBreakpoint<DotNetLineBreakpointProperties>> = PropertiesPanel()

    /** Hit count and log message: sent to the adapter with the breakpoint, see [extrasAt]. */
    private class PropertiesPanel : XBreakpointCustomPropertiesPanel<XLineBreakpoint<DotNetLineBreakpointProperties>>() {
        private val hitCondition = JBTextField().apply { emptyText.text = "Every hit" }
        private val logMessage = JBTextField().apply { emptyText.text = "Stop, do not log" }

        override fun getComponent(): JComponent = panel {
            row("Hit count:") {
                cell(hitCondition).align(AlignX.FILL).comment("Stop on the 5th hit: <code>5</code>; from the 3rd on: <code>&gt;= 3</code>; every 10th: <code>% 10</code>")
                    .validationOnInput { if (HitCondition.isValid(it.text)) null else error("A positive number, optionally after ==, >=, >, <=, < or %") }
            }
            row("Log message:") {
                cell(logMessage).align(AlignX.FILL).comment("Printed to the debug console instead of stopping; <code>{expression}</code> is replaced by its value: <code>total = {total}</code>")
            }
        }

        override fun loadFrom(breakpoint: XLineBreakpoint<DotNetLineBreakpointProperties>) {
            hitCondition.text = breakpoint.properties?.hitCondition.orEmpty()
            logMessage.text = breakpoint.properties?.logMessage.orEmpty()
        }

        override fun saveTo(breakpoint: XLineBreakpoint<DotNetLineBreakpointProperties>) {
            val properties = breakpoint.properties ?: return
            // an invalid hit count is not kept: the adapter would refuse the whole breakpoint
            val hits = hitCondition.text.trim().takeIf { HitCondition.isValid(it) }.orEmpty()
            val message = logMessage.text.trim()
            if (hits == properties.hitCondition && message == properties.logMessage) return
            properties.hitCondition = hits
            properties.logMessage = message
            // the platform re-registers a breakpoint whose properties have changed: that is what sends it to the adapter again
            (breakpoint as? com.intellij.xdebugger.impl.breakpoints.XBreakpointBase<*, *, *>)?.fireBreakpointChanged()
        }
    }

    companion object {
        private val LINES: Key<Pair<Long, Set<Int>>> = Key.create("io.github.dotnetsupport.dap.breakpointLines")

        /** The extras of the breakpoint of the IDE at [path] and the 1-based [line] of the protocol; null when there is none (Run to Cursor). */
        fun extrasAt(project: Project, path: String, line: Int): BreakpointExtras? = ReadAction.compute<BreakpointExtras?, RuntimeException> {
            if (project.isDisposed) return@compute null
            val type = EXTENSION_POINT_NAME.findExtension(CSharpLineBreakpointType::class.java) ?: return@compute null
            XDebuggerManager.getInstance(project).breakpointManager.getBreakpoints(type)
                // the URL, not presentableFilePath: that one is relative to the project
                .firstOrNull { it.line == line - 1 && FileUtil.pathsEqual(VfsUtilCore.urlToPath(it.fileUrl), path) }
                ?.properties?.let { BreakpointExtras(it.hitCondition, it.logMessage) }
        }
    }
}
