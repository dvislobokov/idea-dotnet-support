package io.github.dotnetsupport.debugger

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import io.github.dotnetsupport.lang.CSharpBreakpointLines
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.run.DotNetExceptionBreakpoints
import io.github.dotnetsupport.run.DotNetExceptionFilter
import io.github.dotnetsupport.run.HitCondition
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.JComponent

// --- line breakpoints ---

/** What the adapter can do with a line breakpoint beyond a condition: stop on a hit count, log instead of stopping. */
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

typealias DotNetLineBreakpoint = XLineBreakpoint<DotNetLineBreakpointProperties>

/** Line breakpoints in `.cs` files. The id is the one of the breakpoints users have saved: it must not change. */
class CSharpLineBreakpointType : XLineBreakpointType<DotNetLineBreakpointProperties>("dotnet-line", ".NET Line Breakpoints") {
    override fun createBreakpointProperties(file: VirtualFile, line: Int): DotNetLineBreakpointProperties = DotNetLineBreakpointProperties()

    /** For a breakpoint read from the workspace file: without it the saved hit count and log message are dropped on load (seen live). */
    override fun createProperties(): DotNetLineBreakpointProperties = DotNetLineBreakpointProperties()

    /** With an editor for expressions the platform shows "Condition" for the breakpoint; the condition goes to the adapter with it. */
    override fun getEditorsProvider(breakpoint: DotNetLineBreakpoint, project: Project): XDebuggerEditorsProvider = DotNetEditorsProvider()

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean {
        if (file.fileType != CSharpFileType) return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        // asked for every line the mouse passes in the gutter: one scan per change of the text
        val cached = document.getUserData(LINES)?.takeIf { it.first == document.modificationStamp }
            ?: (document.modificationStamp to CSharpBreakpointLines.find(document.immutableCharSequence)).also { document.putUserData(LINES, it) }
        return line in cached.second
    }

    override fun createCustomPropertiesPanel(project: Project): XBreakpointCustomPropertiesPanel<DotNetLineBreakpoint> = PropertiesPanel()

    private class PropertiesPanel : XBreakpointCustomPropertiesPanel<DotNetLineBreakpoint>() {
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

        override fun loadFrom(breakpoint: DotNetLineBreakpoint) {
            hitCondition.text = breakpoint.properties?.hitCondition.orEmpty()
            logMessage.text = breakpoint.properties?.logMessage.orEmpty()
        }

        override fun saveTo(breakpoint: DotNetLineBreakpoint) {
            val properties = breakpoint.properties ?: return
            // an invalid hit count is not kept: the adapter would refuse the whole breakpoint
            val hits = hitCondition.text.trim().takeIf { HitCondition.isValid(it) }.orEmpty()
            val message = logMessage.text.trim()
            if (hits == properties.hitCondition && message == properties.logMessage) return
            properties.hitCondition = hits
            properties.logMessage = message
            // a breakpoint whose properties change is registered again: that is what sends it to the adapter again
            (breakpoint as? com.intellij.xdebugger.impl.breakpoints.XBreakpointBase<*, *, *>)?.fireBreakpointChanged()
        }
    }

    private companion object {
        val LINES: Key<Pair<Long, Set<Int>>> = Key.create("io.github.dotnetsupport.debugger.breakpointLines")
    }
}

/**
 * The line breakpoints of a session: `setBreakpoints` is per file and replaces the whole list of the file, so every change sends the file
 * again. The condition, the hit count and the log message go with each breakpoint. The adapter answers in the order it was sent: that is
 * how its ids are matched to the breakpoints of the IDE.
 */
class DotNetLineBreakpointHandler(private val process: DotNetDebugProcess) :
    XBreakpointHandler<DotNetLineBreakpoint>(CSharpLineBreakpointType::class.java) {
    private val byFile = ConcurrentHashMap<String, MutableSet<DotNetLineBreakpoint>>()
    private val byId = ConcurrentHashMap<Int, DotNetLineBreakpoint>()
    /** Run to Cursor: file -> the 0-based line of a breakpoint of one stop. */
    private val temporary = ConcurrentHashMap<String, Int>()

    override fun registerBreakpoint(breakpoint: DotNetLineBreakpoint) {
        val path = DotNetDebugProcess.pathOf(breakpoint.fileUrl)
        byFile.computeIfAbsent(path) { ConcurrentHashMap.newKeySet() }.add(breakpoint)
        if (process.configured) send(path)
    }

    override fun unregisterBreakpoint(breakpoint: DotNetLineBreakpoint, temporary: Boolean) {
        val path = DotNetDebugProcess.pathOf(breakpoint.fileUrl)
        byFile[path]?.remove(breakpoint)
        if (process.configured) send(path)
    }

    fun sendAll(): CompletableFuture<*> = CompletableFuture.allOf(*(byFile.keys + temporary.keys).distinct().map(::send).toTypedArray())

    fun find(id: Int): DotNetLineBreakpoint? = byId[id]

    fun runTo(path: String, line: Int): CompletableFuture<*> {
        temporary[path] = line
        return send(path)
    }

    fun clearTemporary() {
        val paths = temporary.keys.toList()
        temporary.clear()
        paths.forEach(::send)
    }

    /** `breakpoint` event: the adapter has verified (or moved, or refused) a breakpoint after the fact, e.g. when its module loads. */
    fun update(breakpoint: JsonObject) {
        val ide = breakpoint.int("id")?.let(byId::get) ?: return
        mark(ide, breakpoint)
    }

    private fun send(path: String): CompletableFuture<*> {
        val breakpoints = byFile[path].orEmpty().filter { it.isEnabled }.sortedBy { it.line }
        val extra = temporary[path]?.takeIf { line -> breakpoints.none { it.line == line } }
        val list = breakpoints.map { breakpointJson(it.line, it.conditionExpression?.expression, it.properties?.hitCondition, it.properties?.logMessage) } +
            listOfNotNull(extra?.let { breakpointJson(it, null, null, null) })
        val source = json("path" to path, "name" to path.substringAfterLast('/').substringAfterLast('\\'))
        return process.connection.request("setBreakpoints", json("source" to source, "breakpoints" to list), DotNetDebugProcess.REQUEST_TIMEOUT_MS)
            .thenAccept { answer ->
                answer.objects("breakpoints").zip(breakpoints).forEach { (dap, ide) ->
                    dap.int("id")?.let { byId[it] = ide }
                    mark(ide, dap)
                }
            }
            .exceptionally { error -> breakpoints.forEach { process.session.setBreakpointInvalid(it, DotNetDebugProcess.errorText(error)) }; null }
    }

    private fun mark(ide: DotNetLineBreakpoint, dap: JsonObject) {
        if (dap.bool("verified") == true) process.session.setBreakpointVerified(ide)
        else process.session.setBreakpointInvalid(ide, dap.string("message") ?: "The debugger has not bound the breakpoint (yet)")
    }

    companion object {
        /** One breakpoint of `setBreakpoints`: the line of the protocol is 1-based; the empty parts are left out. */
        fun breakpointJson(line: Int, condition: String?, hitCondition: String?, logMessage: String?): Map<String, Any> = buildMap {
            put("line", line + 1)
            condition?.trim()?.takeIf { it.isNotEmpty() }?.let { put("condition", it) }
            HitCondition.normalize(hitCondition)?.takeIf { HitCondition.isValid(it) }?.let { put("hitCondition", it) }
            logMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { put("logMessage", it) }
        }
    }
}

// --- exception breakpoints ---

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
 * Exception breakpoints. The default one is "any exception, when it is unhandled or user-unhandled": stopping at every thrown exception is
 * too noisy to be a default. The id is the one of the breakpoints users have saved: it must not change.
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
 * The exception breakpoints of a session as one `setExceptionBreakpoints`: a filter of the adapter per chosen "break when" of every
 * breakpoint, with the types as its condition. Sent even when empty: an adapter that has heard nothing applies the defaults of its filters
 * (`unhandled` and `user-unhandled` are on in `dotnet-debugger`) and would stop although every exception breakpoint is off.
 */
class DotNetExceptionBreakpointHandler(private val process: DotNetDebugProcess) :
    XBreakpointHandler<DotNetExceptionBreakpoint>(DotNetExceptionBreakpointType::class.java) {
    private val registered = ConcurrentHashMap.newKeySet<DotNetExceptionBreakpoint>()

    override fun registerBreakpoint(breakpoint: DotNetExceptionBreakpoint) {
        registered.add(breakpoint)
        if (process.configured) send()
    }

    override fun unregisterBreakpoint(breakpoint: DotNetExceptionBreakpoint, temporary: Boolean) {
        registered.remove(breakpoint)
        if (process.configured) send()
    }

    /** The breakpoint of the IDE to report a stop at an exception with. */
    fun first(): DotNetExceptionBreakpoint? = registered.firstOrNull()

    fun send(): CompletableFuture<*> {
        val filters = registered.map { it.properties?.types to it.properties?.filters.orEmpty() }
        return process.connection.request("setExceptionBreakpoints", arguments(filters, process.capabilities.bool("supportsExceptionFilterOptions") == true),
            DotNetDebugProcess.REQUEST_TIMEOUT_MS).exceptionally { null }
    }

    companion object {
        /** With conditions (`filterOptions`) where the adapter can take them, plain filter ids otherwise. */
        fun arguments(breakpoints: List<Pair<String?, Set<DotNetExceptionFilter>>>, filterOptions: Boolean): JsonObject {
            val options = breakpoints.flatMap { (types, filters) ->
                val condition = DotNetExceptionBreakpoints.typeCondition(types)
                filters.map { filter -> buildMap { put("filterId", filter.id); condition?.let { put("condition", it) } } }
            }
            return if (filterOptions) json("filters" to emptyList<String>(), "filterOptions" to options)
            else json("filters" to options.map { it["filterId"] }.distinct())
        }
    }
}
