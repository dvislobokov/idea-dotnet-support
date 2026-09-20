package io.github.dotnetsupport.run

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.unscramble.AnalyzeStacktraceUtil
import com.intellij.util.ui.JBUI
import io.github.dotnetsupport.monitor.ThreadDumpFilter
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * .NET stack frames and MSBuild diagnostics become links in every console of the IDE (Run, terminal-like tool
 * windows, the stack trace analyzer), not only in the ones the plugin starts itself.
 */
class DotNetConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(DotNetStackTraceFilter(project), MsBuildConsoleFilter(project), ThreadDumpFilter(project))
}

/** Paste a stack trace from a log or a ticket and get it with clickable frames. */
class AnalyzeDotNetStackTraceAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = StackTraceDialog(project)
        if (!dialog.showAndGet()) return
        // the console gets the filters of every ConsoleFilterProvider, ours included
        AnalyzeStacktraceUtil.addConsole(project, null, ".NET Stack Trace", dialog.stackTrace.trimEnd() + "\n")
    }

    private class StackTraceDialog(project: Project) : DialogWrapper(project) {
        private val text = JBTextArea(clipboardStackTrace().orEmpty()).apply { font = JBUI.Fonts.create("Monospaced", JBUI.Fonts.label().size) }

        val stackTrace: String get() = text.text

        init {
            title = "Analyze .NET Stack Trace"
            setOKButtonText("Analyze")
            init()
        }

        override fun getPreferredFocusedComponent(): JComponent = text

        override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JBLabel("Paste the stack trace; frames with source locations of this machine become links:"), BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(text), BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(420))
        }

        /** The clipboard is offered only when it looks like a stack trace. */
        private fun clipboardStackTrace(): String? =
            CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)?.takeIf { content -> content.lineSequence().any { DotNetStackTraceFilter.parse(it) != null } }
    }
}
