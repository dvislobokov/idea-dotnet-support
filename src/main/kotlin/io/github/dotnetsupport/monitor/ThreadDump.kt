package io.github.dotnetsupport.monitor

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/** `System.Private.CoreLib.il!System.Threading.Monitor.Wait(class System.Object,int32)` */
class ThreadFrame(val module: String, val member: String, val text: String) {
    /** Code of the application, as opposed to the framework: decides which threads are shown first. */
    val isUserCode: Boolean
        get() = module.isNotEmpty() && FRAMEWORK_PREFIXES.none { module.startsWith(it) }

    /**
     * Where to look for the source: the simple name of the outermost type and the method as it is written in the code.
     * The compiler hides async methods and lambdas in nested types: `Orders+<LoadAsync>d__5.MoveNext` is `Orders.LoadAsync`,
     * `Orders+<>c.<Load>b__0_0` is a lambda inside `Orders.Load`, `Program.<Main>$` is the top-level statements.
     */
    fun source(): Pair<String, String?>? {
        if (member.isEmpty()) return null
        val withoutArguments = member.substringBefore('(')
        // "Shop.Orders..ctor": the name of a constructor starts with a dot itself
        val isConstructor = withoutArguments.endsWith("..ctor")
        val typePath = (if (isConstructor) withoutArguments.removeSuffix("..ctor") else withoutArguments.substringBeforeLast('.', ""))
        val method = if (isConstructor) "" else withoutArguments.substringAfterLast('.')
        if (typePath.isEmpty()) return null
        val outerType = typePath.substringBefore('+').substringAfterLast('.').substringBefore('`')
        if (outerType.isEmpty()) return null
        if (isConstructor) return outerType to outerType
        // "<>c" and "<>c__DisplayClass3_0" name nothing: then the method part does ("<Load>b__0_0")
        val hidden = listOf(typePath.substringAfter('+', ""), method).firstNotNullOfOrNull { part -> GENERATED.find(part)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } }
        val name = hidden ?: method
        return outerType to name.takeIf { it.isNotEmpty() && it != "Main" && it != "MoveNext" && '<' !in it }
    }

    companion object {
        private val FRAMEWORK_PREFIXES = listOf("System.", "Microsoft.", "netstandard", "mscorlib", "[")
        private val GENERATED = Regex("""<(\w*)>""")
    }
}

class ThreadStack(val id: String, val frames: List<ThreadFrame>) {
    val hasUserCode: Boolean get() = frames.any { it.isUserCode }
}

object ThreadDump {
    /**
     * Output of `dotnet-stack report`:
     * ```
     * Thread (0x4430):
     *   [Native Frames]
     *   System.Private.CoreLib.il!System.Threading.Monitor.Wait(class System.Object,int32)
     *   app!Program.<Main>$(class System.String[])
     * ```
     */
    fun parse(output: String): List<ThreadStack> {
        val threads = ArrayList<ThreadStack>()
        var id: String? = null
        var frames = ArrayList<ThreadFrame>()
        fun flush() {
            id?.let { threads += ThreadStack(it, frames) }
            frames = ArrayList()
        }
        for (line in output.lineSequence()) {
            val header = HEADER.find(line)
            when {
                header != null -> { flush(); id = header.groupValues[1] }
                id != null && line.isNotBlank() -> {
                    val text = line.trim()
                    val separator = text.indexOf('!')
                    frames += if (separator > 0) ThreadFrame(text.substring(0, separator).removeSuffix(".il"), text.substring(separator + 1), text) else ThreadFrame("", "", text)
                }
            }
        }
        flush()
        return threads
    }

    /**
     * The dump as it is shown: the threads that run the code of the application first, identical stacks (the idle
     * workers of the thread pool) folded into one entry.
     */
    fun render(threads: List<ThreadStack>, processTitle: String): String {
        val groups = threads.groupBy { stack -> stack.frames.joinToString("\n") { it.text } }.values
            .sortedWith(compareByDescending<List<ThreadStack>> { it.first().hasUserCode }.thenByDescending { it.size })
        val withUserCode = threads.count { it.hasUserCode }
        return buildString {
            append("Thread dump of $processTitle: ${threads.size} managed threads, $withUserCode in the code of the application\n\n")
            for (group in groups) {
                val ids = group.joinToString(", ") { it.id }
                append(if (group.size == 1) "Thread $ids" else "${group.size} threads with the same stack: $ids")
                if (group.first().hasUserCode) append("  [application code]")
                append('\n')
                group.first().frames.forEach { append("    at ").append(it.text).append('\n') }
                append('\n')
            }
        }
    }

    private val HEADER = Regex("""^Thread \((0x[0-9A-Fa-f]+)\):""")
}

/**
 * Frames of a thread dump have no file names, so the type is looked up by the name of its file (`Orders` in `Orders.cs`,
 * preferably of the project named like the module) and the method by its name inside the file.
 */
class ThreadDumpFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val match = FRAME.find(line) ?: return null
        val frame = ThreadFrame(match.groupValues[1].removeSuffix(".il"), match.groupValues[2], "")
        if (!frame.isUserCode) return null
        val (type, method) = frame.source() ?: return null
        val (file, lineNumber) = ReadAction.compute<Pair<VirtualFile, Int>?, RuntimeException> { locate(frame.module, type, method) } ?: return null

        val member = match.groups[2]!!.range
        val lineStart = entireLength - line.length
        return Filter.Result(lineStart + member.first, lineStart + member.last + 1, OpenFileHyperlinkInfo(project, file, lineNumber))
    }

    private fun locate(module: String, type: String, method: String?): Pair<VirtualFile, Int>? {
        if (project.isDisposed) return null
        val candidates = FilenameIndex.getVirtualFilesByName("$type.cs", GlobalSearchScope.projectScope(project))
        val file = candidates.firstOrNull { "/$module/" in it.path } ?: candidates.firstOrNull() ?: return null
        return file to (method?.let { lineOf(VfsUtilCore.loadText(file), it) } ?: 0)
    }

    companion object {
        private val FRAME = Regex("""^\s+at ([^\s!]+)!(\S[^(]*)\(""")

        /** The first place where [method] is followed by `(` or by generic arguments: its declaration, in most files. */
        fun lineOf(text: String, method: String): Int {
            val match = Regex("""\b${Regex.escape(method)}\s*(<[^>()]*>)?\s*\(""").find(text) ?: return 0
            return text.substring(0, match.range.first).count { it == '\n' }
        }
    }
}
