package io.github.dotnetsupport.run

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes the frames of .NET stack traces clickable:
 * `   at Shop.Cart.Add(Item item) in C:\src\Shop\Cart.cs:line 42`.
 */
class DotNetStackTraceFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val frame = parse(line) ?: return null
        val file = LocalFileSystem.getInstance().findFileByPath(frame.path.replace('\\', '/')) ?: return null
        val lineStart = entireLength - line.length
        return Filter.Result(
            lineStart + frame.range.first, lineStart + frame.range.last + 1,
            OpenFileHyperlinkInfo(project, file, (frame.line - 1).coerceAtLeast(0)),
        )
    }

    /** [range] covers `path:line N` inside the parsed line. */
    class Frame(val path: String, val line: Int, val range: IntRange)

    companion object {
        // The words around the path are localized ("in … :line 42", "в … :строка 42"), so only the shape is matched:
        // whitespace, an absolute path, a colon, one word, the number at the end of the line.
        private val FRAME = Regex("""\s((?:[A-Za-z]:[\\/]|/)[^:*?"<>|\r\n]+):\S+ (\d+)\s*$""")

        fun parse(line: String): Frame? {
            val match = FRAME.find(line) ?: return null
            val path = match.groups[1]!!
            val number = match.groups[2]!!
            return Frame(path.value, number.value.toIntOrNull() ?: return null, path.range.first..number.range.last)
        }
    }
}

/** Opens the browser when an ASP.NET Core application reports the address it listens on. */
class ListeningUrlListener(private val launchUrl: String?) : ProcessListener {
    private val opened = AtomicBoolean()

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        val url = ListeningUrl.parse(event.text) ?: return
        // Kestrel reports every endpoint (http and https): the first one is enough
        if (opened.compareAndSet(false, true)) BrowserUtil.browse(ListeningUrl.browserUrl(url, launchUrl))
    }
}

object ListeningUrl {
    // The message of Microsoft.Hosting.Lifetime; log messages are not localized.
    private val LISTENING = Regex("""Now listening on:\s*(https?://\S+)""")
    private val ANY_ADDRESS = Regex("""^(https?://)(?:0\.0\.0\.0|\[::]|\*|\+)(?=[:/]|$)""")

    fun parse(text: String): String? = LISTENING.find(text)?.groupValues?.get(1)?.trimEnd('.', ',')

    /** `http://0.0.0.0:5000` is where the server listens, not something a browser can open; [launchUrl] comes from the launch profile. */
    fun browserUrl(listeningUrl: String, launchUrl: String?): String {
        val base = ANY_ADDRESS.replace(listeningUrl) { it.groupValues[1] + "localhost" }.trimEnd('/')
        return when {
            launchUrl.isNullOrBlank() -> base
            launchUrl.startsWith("http://") || launchUrl.startsWith("https://") -> launchUrl
            else -> base + "/" + launchUrl.trimStart('/')
        }
    }
}
