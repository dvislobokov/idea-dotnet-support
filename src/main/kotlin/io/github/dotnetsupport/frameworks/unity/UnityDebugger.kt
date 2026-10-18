package io.github.dotnetsupport.frameworks.unity

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.debugger.mono.MonoDebugProcess

/**
 * The debugger of the scripts in the open Unity editor (UNITY_PLAN.md, "Отладчик"): the plugin's own Mono client at the editor's agent
 * ([UnityEditorLink.debuggerPort]). The editor must be in Debug mode first: attaching in Release stops it at a modal dialog, and Cancel
 * there leaves its agent deaf until a restart — so the IDE asks and switches it through the package, waits for the compilation, then attaches.
 */
object UnityDebugger {
    const val SESSION_NAME = "Unity Editor"

    fun isAttached(project: Project): Boolean = XDebuggerManager.getInstance(project).debugSessions.any { it.debugProcess is MonoDebugProcess }

    fun attach(project: Project) {
        val connection = UnityEditorConnection.getInstance(project)
        val link = connection.link ?: return
        val state = link.state
        if (link.status != UnityEditorLink.Status.CONNECTED || state == null || link.editorProcessId == null) return UnityEditorNotifications.notConnected(project)
        if (state.debugMode) return start(project, link.editorProcessId)
        if (state.codeOptimization == null) {
            return DotNetCli.notifyError(project, SESSION_NAME, "The package of the link in the project is too old to switch Unity to Debug mode: .NET → Connect to Unity Editor.")
        }
        val answer = Messages.showYesNoDialog(project,
            "Unity compiles the scripts in Release mode, where a debugger cannot attach. Switch the editor to Debug mode for this session? " +
                "It compiles and reloads the scripts; C# runs slower in Debug mode.",
            "Attach to Unity Editor", "Switch to Debug Mode", Messages.getCancelButton(), UnityIcons.Unity)
        if (answer != Messages.YES) return
        val sentAt = System.currentTimeMillis()
        if (!connection.send("debug-mode")) return UnityEditorNotifications.notConnected(project)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Switching Unity to Debug mode", true) {
            override fun run(indicator: ProgressIndicator) {
                val deadline = System.currentTimeMillis() + WAIT_MS
                while (System.currentTimeMillis() < deadline) {
                    indicator.checkCanceled()
                    connection.update()
                    val now = connection.link
                    if (now != null && ready(now, sentAt)) return start(project, now.editorProcessId!!)
                    Thread.sleep(500)
                }
                DotNetCli.notifyError(project, SESSION_NAME, "Unity has not finished compiling the scripts in Debug mode in ${WAIT_MS / 1000} s.")
            }
        })
    }

    /** Debug mode, the compilation it caused finished and the domain reloaded (the heartbeat is back). */
    fun ready(link: UnityEditorLink.Link, sentAt: Long): Boolean {
        val state = link.state ?: return false
        return link.status == UnityEditorLink.Status.CONNECTED && state.debugMode && !state.compiling && state.compiledAt >= sentAt
    }

    private fun start(project: Project, editorProcessId: Long) {
        val port = UnityEditorLink.debuggerPort(editorProcessId)
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || isAttached(project)) return@invokeLater
            XDebuggerManager.getInstance(project).newSessionBuilder(object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess = MonoDebugProcess(session, "127.0.0.1", port, SESSION_NAME)
            }).sessionName(SESSION_NAME).icon(UnityIcons.Unity).showTab(true).startSession()
        }, ModalityState.nonModal())
    }

    private const val WAIT_MS = 180_000L
}

/** Attach to Unity Editor, as Rider's: the debugger in the open editor; the IDE switches it to Debug mode first, with the user's consent. */
class UnityAttachDebuggerAction : UnityEditorAction("Attach to Unity Editor", "Debug the scripts in the open Unity editor", AllIcons.Actions.StartDebugger) {
    override fun isEnabled(link: UnityEditorLink.Link): Boolean = link.editorProcessId != null

    override fun update(e: AnActionEvent, link: UnityEditorLink.Link) {
        if (e.project?.let(UnityDebugger::isAttached) == true) e.presentation.isEnabled = false
    }

    override fun actionPerformed(e: AnActionEvent) {
        UnityDebugger.attach(e.project ?: return)
    }
}
