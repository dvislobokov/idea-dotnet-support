package io.github.dotnetsupport.debugger.mono

import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import io.github.dotnetsupport.debugger.CSharpLineBreakpointType
import io.github.dotnetsupport.debugger.DotNetDebugProcess
import io.github.dotnetsupport.debugger.DotNetLineBreakpoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Where a line breakpoint goes in the code of a runtime: pure, for the tests. */
object MonoBreakpointLocations {
    /** The paths of the runtime are the ones the code was compiled with (Windows: `C:\…\Assets\X.cs`); any slashes, any case. */
    fun samePath(a: String?, b: String?): Boolean = a != null && b != null && a.replace('\\', '/').equals(b.replace('\\', '/'), ignoreCase = true)

    fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    /**
     * The places of a breakpoint at the 1-based [line] among [methods] (the points of each in the file of the breakpoint): only methods whose
     * lines span it; in each, the first statement at or after it; of those, the nearest line. When several methods have a statement there
     * (a statement holding a lambda: the lambda is a method of its own on the same line) the outermost one — the widest — takes it, as the
     * debuggers of Rider and Visual Studio stop on the statement. The place is the lowest IL offset of that line.
     */
    fun choose(line: Int, methods: List<Pair<Int, List<MonoSequencePoint>>>): List<Pair<Int, Long>> {
        val candidates = methods.mapNotNull { (method, points) ->
            if (points.isEmpty()) return@mapNotNull null
            val first = points.minOf { it.line }
            val last = points.maxOf { maxOf(it.line, it.endLine) }
            if (line !in first..last) return@mapNotNull null
            val next = points.filter { it.line >= line }.minOfOrNull { it.line } ?: return@mapNotNull null
            Triple(method, next, points)
        }
        val nearest = candidates.minOfOrNull { it.second } ?: return emptyList()
        val outer = candidates.filter { it.second == nearest }.maxBy { (_, _, points) -> points.maxOf { it.line } - points.minOf { it.line } }
        return listOf(outer.first to outer.third.filter { it.line == nearest }.minOf { it.ilOffset }.toLong())
    }
}

/**
 * The line breakpoints of a Mono session (the breakpoints of `.cs` files the IDE has, [CSharpLineBreakpointType]). A breakpoint is placed
 * in the types loaded now (`VM.GET_TYPES_FOR_SOURCE_FILE`, then the line tables of their methods), and again whenever a type of its file
 * loads: one `TYPE_LOAD` request filtered by the names of the files (it stops the runtime, so the breakpoint is there before the code
 * runs) — the way into code not loaded yet and into the new domain after every compilation of Unity (seen: the types come again with new
 * ids, `TYPE_LOAD` with policy ALL). One breakpoint of the IDE may have many requests (one per domain); all of them lead back to it.
 */
class MonoLineBreakpointHandler(private val process: MonoDebugProcess) : XBreakpointHandler<DotNetLineBreakpoint>(CSharpLineBreakpointType::class.java) {
    private class Placed(val breakpoint: DotNetLineBreakpoint, val path: String) {
        /** `method:il` → the request there. */
        val requests = ConcurrentHashMap<String, Int>()
    }

    private val placed = ConcurrentHashMap<DotNetLineBreakpoint, Placed>()
    private val byRequest = ConcurrentHashMap<Int, DotNetLineBreakpoint>()
    private var typeLoadRequest: Int? = null
    private var typeLoadFiles: Set<String> = emptySet()

    override fun registerBreakpoint(breakpoint: DotNetLineBreakpoint) {
        val entry = Placed(breakpoint, DotNetDebugProcess.pathOf(breakpoint.fileUrl))
        placed[breakpoint] = entry
        if (breakpoint.conditionExpression?.expression?.isNotBlank() == true || breakpoint.properties?.hitCondition?.isNotBlank() == true ||
            breakpoint.logExpressionObject?.expression?.isNotBlank() == true) {
            process.print("The Unity debugger does not evaluate conditions, hit counts and log expressions yet: the breakpoint at " +
                "${MonoBreakpointLocations.fileName(entry.path)}:${breakpoint.line + 1} stops every time\n", com.intellij.execution.process.ProcessOutputTypes.SYSTEM)
        }
        if (process.connected) process.onWorker { place(entry); updateTypeLoad() }
    }

    override fun unregisterBreakpoint(breakpoint: DotNetLineBreakpoint, temporary: Boolean) {
        val entry = placed.remove(breakpoint) ?: return
        if (!process.connected) return
        process.onWorker {
            for (request in entry.requests.values) {
                byRequest.remove(request)
                runCatching { process.connection.clearRequest(MonoProtocol.EventKind.BREAKPOINT, request).get(MonoDebugProcess.TIMEOUT_S, TimeUnit.SECONDS) }
            }
            updateTypeLoad()
        }
    }

    fun find(request: Int): DotNetLineBreakpoint? = byRequest[request]?.takeIf { placed.containsKey(it) }

    /** On the worker, once negotiated: the breakpoints registered before. */
    fun connected() {
        updateTypeLoad()
        placed.values.forEach(::place)
    }

    /** A type of a breakpoint's file has loaded (the first time, or in a new domain): its breakpoints are placed again. */
    fun typeLoaded(type: Int) {
        if (placed.isEmpty()) return
        val files = process.connection.typeSourceFiles(type).get(MonoDebugProcess.TIMEOUT_S, TimeUnit.SECONDS)
        placed.values.filter { entry -> files.any { MonoBreakpointLocations.samePath(it, entry.path) } }.forEach(::place)
    }

    /** The places of [path]:[line] (1-based) in the code loaded now. */
    fun locations(path: String, line: Int): List<Pair<Int, Long>> {
        val connection = process.connection
        val methods = connection.typesForSourceFile(path).get(MonoDebugProcess.TIMEOUT_S, TimeUnit.SECONDS).flatMap { type ->
            val files = connection.typeSourceFiles(type).get(MonoDebugProcess.TIMEOUT_S, TimeUnit.SECONDS)
            if (files.none { MonoBreakpointLocations.samePath(it, path) }) return@flatMap emptyList()
            connection.methods(type).get(MonoDebugProcess.TIMEOUT_S, TimeUnit.SECONDS).map { method ->
                method to process.debugInfo(method)?.lines.orEmpty().filter { MonoBreakpointLocations.samePath(it.file, path) }
            }
        }
        return MonoBreakpointLocations.choose(line, methods)
    }

    private fun place(entry: Placed) {
        if (placed[entry.breakpoint] !== entry) return
        val places = locations(entry.path, entry.breakpoint.line + 1)
        for ((method, il) in places) {
            val key = "$method:$il"
            if (entry.requests.containsKey(key)) continue
            val request = process.connection.request(MonoProtocol.EventKind.BREAKPOINT, MonoProtocol.SuspendPolicy.ALL, MonoModifier.LocationOnly(method, il))
                .get(MonoDebugProcess.TIMEOUT_S, TimeUnit.SECONDS)
            entry.requests[key] = request
            byRequest[request] = entry.breakpoint
        }
        // not loaded yet: no mark, the TYPE_LOAD request places it later
        if (entry.requests.isNotEmpty()) process.session.setBreakpointVerified(entry.breakpoint)
        else if (process.connection.typesForSourceFile(entry.path).get(MonoDebugProcess.TIMEOUT_S, TimeUnit.SECONDS).isNotEmpty()) {
            process.session.setBreakpointInvalid(entry.breakpoint, "No code of the loaded assemblies at this line")
        }
    }

    /** One `TYPE_LOAD` request for the names of all the files with breakpoints; made again when they change. */
    private fun updateTypeLoad() {
        val files = placed.values.map { MonoBreakpointLocations.fileName(it.path) }.toSortedSet()
        if (files == typeLoadFiles) return
        val connection = process.connection
        typeLoadRequest?.let { request -> runCatching { connection.clearRequest(MonoProtocol.EventKind.TYPE_LOAD, request).get(MonoDebugProcess.TIMEOUT_S, TimeUnit.SECONDS) } }
        typeLoadRequest = null
        typeLoadFiles = files
        if (files.isEmpty()) return
        typeLoadRequest = connection.request(MonoProtocol.EventKind.TYPE_LOAD, MonoProtocol.SuspendPolicy.ALL, MonoModifier.SourceFiles(files))
            .get(MonoDebugProcess.TIMEOUT_S, TimeUnit.SECONDS)
    }
}
