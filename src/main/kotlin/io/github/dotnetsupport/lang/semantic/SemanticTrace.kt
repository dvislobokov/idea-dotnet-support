package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.PsiElement
import io.github.dotnetsupport.cli.PluginLog
import org.jetbrains.annotations.TestOnly

/**
 * The debug log of the C# analysis (CSHARP_PSI_MIGRATION.md, D6e1; the rule — CLAUDE.md, "Отладочный журнал семантики"): every place where
 * the semantics gives up (an answer "not known", `Unknown` in a ported part of Roslyn's binder, a fallback from a port to the older rules, a
 * cycle guard that fired, a candidate overload resolution dropped) writes one line — what was asked, which path answered, why it could not.
 * Nothing is written for what went well. The lines go to the plugin log, category `semantic` (.NET | Plugin Logs, `~/idea-dotnet-logs/plugin/`),
 * behind the setting `DotNetSettings.semanticDebugLog` (on by default until the main problems are solved); [enabled] mirrors it so a check
 * costs one volatile read and the text is built only when it is wanted.
 */
object SemanticTrace {
    const val CATEGORY = "semantic"

    @Volatile var enabled: Boolean = true

    @Volatile private var sink: ((String) -> Unit)? = null

    // a line goes to the log once per session of the IDE: highlighting runs again on every edit and the strict inference is asked many
    // times for one call (MudBlazor: 7910 lines for one pass, the same line up to 10 times); a test collects every line
    private val seen = LinkedHashSet<String>()
    private const val SEEN_LIMIT = 50_000

    inline fun trace(message: () -> String) {
        if (enabled && !isQuiet()) write(message())
    }

    // a stretch of work whose refusals are no news (the lambdas of a form another argument already rules out): nothing is written in it
    private val quietDepth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }

    fun isQuiet(): Boolean = quietDepth.get() > 0

    fun enterQuiet() = quietDepth.set(quietDepth.get() + 1)

    fun leaveQuiet() = quietDepth.set(quietDepth.get() - 1)

    /** [block] with the trace off when [quiet]. */
    inline fun <T> quietly(quiet: Boolean, block: () -> T): T {
        if (!quiet) return block()
        enterQuiet()
        try { return block() } finally { leaveQuiet() }
    }

    fun write(line: String) {
        sink?.let { it(line); return }
        synchronized(seen) {
            if (seen.size >= SEEN_LIMIT) seen.clear()
            if (!seen.add(line)) return
        }
        // the plugin log only: a pass over a solution writes thousands of lines, idea.log is not for them
        PluginLog.infoQuiet(CATEGORY, line)
    }

    /** `Program.cs:12` of [element], for the start of a line; `?` where the file has no document. */
    fun at(element: PsiElement?): String {
        val file = element?.containingFile ?: return "?"
        val document = file.viewProvider.document ?: return file.name
        val offset = element.textRange?.startOffset ?: return file.name
        return "${file.name}:${document.getLineNumber(offset.coerceIn(0, document.textLength)) + 1}"
    }

    /** A type as the compiler messages show it, `?` for none. */
    fun show(type: SemanticType?): String = type?.let { CSharpTypeDisplay.display(it) ?: it.toString() } ?: "?"

    /** A member as a short name: `Select`, `Should<2>`; the owner is in the line's context already. */
    fun show(symbol: CSharpSymbol?): String = when (symbol) {
        null -> "?"
        is CSharpSymbol.SourceMember -> io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames.nameElement(symbol.element)?.text ?: symbol.element.javaClass.simpleName
        is CSharpSymbol.LibraryMember -> symbol.member.name + if (symbol.member.arity > 0) "<${symbol.member.arity}>" else ""
        else -> symbol.toString()
    }

    /** The text of [element] on one line, cut to [limit] characters. */
    fun text(element: PsiElement?, limit: Int = 60): String {
        val text = element?.text?.replace(Regex("\\s+"), " ") ?: return "?"
        return if (text.length <= limit) text else text.take(limit - 1) + "…"
    }

    /** The lines [block] writes, with its result; the log is left alone meanwhile. */
    @TestOnly
    fun <T> collectForTests(block: () -> T): Pair<T, List<String>> {
        val lines = ArrayList<String>()
        val before = sink
        val wasEnabled = enabled
        sink = { synchronized(lines) { lines += it } }
        enabled = true
        try {
            return block() to lines
        } finally {
            sink = before
            enabled = wasEnabled
        }
    }
}
