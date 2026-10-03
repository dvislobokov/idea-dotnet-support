package io.github.dotnetsupport.run

import com.intellij.execution.dashboard.RunDashboardManager
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Key

/*
 * Hot Reload status of a `dotnet watch` session, as Rider shows it: the state in the row of the Services tool window, the state lines of
 * `dotnet watch` colored in the console and a Restart that works. `dotnet watch` reads keys only from a real console: under an IDE its stdin is
 * a pipe, and Ctrl+R or the y/n answer to "Do you want to restart your app?" written there are ignored (checked on SDK 9.0.301 and 10.0.401),
 * so Restart reruns the configuration.
 */

/** What a `dotnet watch` session is doing; [level] colors it in the console and in the Services tool window. */
enum class HotReloadState(val text: String, val level: LogLevel?) {
    BUILDING("Building", null),
    RUNNING("Watching for changes", LogLevel.INFO),
    APPLIED("Changes applied", LogLevel.INFO),
    RESTART_NEEDED("Restart needed", LogLevel.WARNING),
    BUILD_FAILED("Build failed", LogLevel.ERROR),
    RESTARTING("Restarting", null),
    EXITED("Exited, waiting for a file change", LogLevel.WARNING),
}

/** The state of a session and the message of `dotnet watch` that set it. */
data class HotReloadStatus(val state: HotReloadState, val message: String)

/**
 * Reads the state lines of `dotnet watch` (started with `DOTNET_CLI_UI_LANGUAGE=en`: the messages are localized otherwise).
 * The lines are `dotnet watch <emoji> <message>`, sometimes with the project in brackets: `dotnet watch 🔥 [App (net10.0)] Restarting application ...`.
 * The texts, SDK 10.0.401 / 9.0.301 checked on the real CLI, SDK 8 from its sources:
 * - start: `🔥 Hot reload enabled.` (all), `⌚ Building <project> ...` (8, 9), `🚀 Started` (8), `🔨 Build succeeded: <project>` (9), `⌚ Waiting for changes` (10);
 * - applied: `🔥 Hot reload of changes succeeded.` (8), `🔥 [App (net9.0)] Hot reload succeeded.` (9), `🔥 C# and Razor changes applied in 82ms.` (10);
 * - rude edit: `⌚ Unable to apply hot reload because of a rude edit.` (8), `⌚ Unable to apply hot reload, restart is needed to apply the changes.` (9),
 *   `🔥 Restart is needed to apply the changes.` (10), then the prompt `  ❔ Do you want to restart your app? Yes (y) / No (n) / Always (a) / Never (v)`;
 * - compilation errors: `🔥 Unable to apply changes due to compilation errors.` (10), `🔨 Build failed: <project>` (9), `❌ Unable to apply hot reload due to compilation errors.` (8);
 * - restart: `🔥 [App (net10.0)] Restarting application ...` (10), `🔄 Restart requested.` (Ctrl+R in a terminal);
 * - the application ended: `❌ Exited with error code 1`, `⌚ Exited`, `⏳ Waiting for a file to change before restarting ...`.
 * Diagnostics (`❌ Program.cs(9,51): error CS0103: ...`) and messages of the application (`⚠ ...`) say nothing about the state and may contain any words.
 */
object HotReloadOutput {
    private val PREFIX = Regex("""^dotnet watch\s*[^\p{L}\p{N}\[]*""")
    // the prompt and the hint have no "dotnet watch" in front: "  ❔ Do you want to restart your app? ..."
    private val PROMPT = Regex("""^\s*\S{1,2}\s+(?=Do you want to restart)""")
    private val PROJECT = Regex("""^\[[^\]]*]\s*""")
    private val DIAGNOSTIC = Regex("""\(\d+,\d+\)\s*:\s*(error|warning)\s""")

    // first match wins: "Unable to apply hot reload due to compilation errors" before "Unable to apply hot reload"
    private val RULES: List<Pair<Regex, HotReloadState>> = listOf(
        "Hot reload enabled" to HotReloadState.BUILDING,
        "Building\\b" to HotReloadState.BUILDING,
        "Build succeeded" to HotReloadState.RUNNING,
        "Started\\b" to HotReloadState.RUNNING,
        "Waiting for changes" to HotReloadState.RUNNING,
        "Hot reload succeeded" to HotReloadState.APPLIED,
        "Hot reload of changes succeeded" to HotReloadState.APPLIED,
        "[\\w#, ]*changes applied" to HotReloadState.APPLIED,
        "Unable to apply (hot reload|changes) due to compilation errors" to HotReloadState.BUILD_FAILED,
        "Build failed" to HotReloadState.BUILD_FAILED,
        "Restart is needed" to HotReloadState.RESTART_NEEDED,
        "Unable to apply hot reload" to HotReloadState.RESTART_NEEDED,
        "Do you want to restart" to HotReloadState.RESTART_NEEDED,
        "Restart requested" to HotReloadState.RESTARTING,
        "Restarting\\b" to HotReloadState.RESTARTING,
        "Exited\\b" to HotReloadState.EXITED,
        "Waiting for a file to change" to HotReloadState.EXITED,
    ).map { (pattern, state) -> Regex("^(?:$pattern)", RegexOption.IGNORE_CASE) to state }

    /** The message of a `dotnet watch` line without the prefix, the emoji and the project; null for other lines (the build, the application). */
    fun message(line: String): String? {
        val text = line.trimEnd()
        val start = PREFIX.find(text) ?: PROMPT.find(text) ?: return null
        return text.substring(start.range.last + 1).replace(PROJECT, "").trim().takeIf { it.isNotEmpty() }
    }

    /** The state a line reports, null when it reports none. */
    fun parse(line: String): HotReloadState? {
        val message = message(line) ?: return null
        if (DIAGNOSTIC.containsMatchIn(message)) return null
        return RULES.firstOrNull { it.first.containsMatchIn(message) }?.second
    }

    /** The state after [line]: SDK 10 restarts the application after a rude edit and reports "Exited" on the way, which is part of the restart. */
    fun next(current: HotReloadStatus?, line: String): HotReloadStatus? {
        val state = parse(line) ?: return current
        if (current?.state == HotReloadState.RESTARTING && state == HotReloadState.EXITED) return current
        // SDK 9 waits for a file change after a failed build: the failure is what the user needs to see
        if (current?.state == HotReloadState.BUILD_FAILED && state == HotReloadState.EXITED) return current
        // the prompt follows "Restart is needed to apply the changes.", which explains more
        if (current?.state == HotReloadState.RESTART_NEEDED && state == HotReloadState.RESTART_NEEDED) return current
        return HotReloadStatus(state, message(line)!!)
    }
}

/** Follows the output of a `dotnet watch` process and keeps its [HotReloadStatus] on the handler for the Services tool window. */
class HotReloadTracker(private val handler: ProcessHandler, private val environment: ExecutionEnvironment?) : ProcessListener {
    private val pending = StringBuilder()

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        // a colored handler hands over pieces of lines
        val lines = synchronized(pending) {
            pending.append(event.text)
            val end = pending.lastIndexOf("\n")
            if (end < 0) return
            val complete = pending.substring(0, end)
            pending.delete(0, end + 1)
            complete.lines()
        }
        lines.forEach(::line)
    }

    private fun line(line: String) {
        val current = handler.getUserData(KEY)
        val next = HotReloadOutput.next(current, line)
        if (next == current) return
        handler.putUserData(KEY, next)
        val project = environment?.project ?: return
        ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) RunDashboardManager.getInstance(project).updateDashboard(false) }
    }

    companion object {
        val KEY: Key<HotReloadStatus> = Key.create("dotnet.hot.reload.status")
        val ENVIRONMENT: Key<ExecutionEnvironment> = Key.create("dotnet.hot.reload.environment")

        fun attach(handler: ProcessHandler, environment: ExecutionEnvironment?) {
            environment?.let { handler.putUserData(ENVIRONMENT, it) }
            handler.addProcessListener(HotReloadTracker(handler, environment))
        }

        /** Restarts the session: `dotnet watch` does not read Ctrl+R from a pipe, so the configuration is run again in the same tab. */
        fun restart(environment: ExecutionEnvironment) = ExecutionUtil.restart(environment)
    }
}

/**
 * Colors the state lines of `dotnet watch` in the console (the colors of the Log console scheme) and makes the restart prompt a link:
 * the keys it asks for do not reach `dotnet watch` under an IDE.
 */
class HotReloadConsoleFilter(private val environment: ExecutionEnvironment?) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val state = HotReloadOutput.parse(line) ?: return null
        val message = HotReloadOutput.message(line) ?: return null
        val lineStart = entireLength - line.length
        val start = lineStart + line.indexOf(message).coerceAtLeast(0)
        val end = lineStart + line.trimEnd().length
        val attributes = state.level?.let { EditorColorsManager.getInstance().globalScheme.getAttributes(it.attributes) }
        val link = environment?.takeIf { state == HotReloadState.RESTART_NEEDED }?.let { env -> HyperlinkInfo { HotReloadTracker.restart(env) } }
        if (attributes == null && link == null) return null
        return Filter.Result(start, end, link, attributes)
    }
}

/** "Restart dotnet watch" in the toolbar of a watch session; the same as Rerun, named for what the user wants after a rude edit. */
class RestartDotNetWatchAction(private val environment: ExecutionEnvironment, private val handler: ProcessHandler) :
    DumbAwareAction("Restart dotnet watch", "Restart the application to apply changes Hot Reload cannot apply", AllIcons.Actions.Restart) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !handler.isProcessTerminated
    }

    override fun actionPerformed(e: AnActionEvent) = HotReloadTracker.restart(environment)
}
