package io.github.dotnetsupport.il

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.Alarm
import com.intellij.util.DocumentUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.build.DotNetBuildCommand
import io.github.dotnetsupport.build.DotNetBuildListener
import io.github.dotnetsupport.build.DotNetBuildService
import io.github.dotnetsupport.cli.PluginLogsToolWindowFactory
import io.github.dotnetsupport.solution.SolutionService
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JList
import javax.swing.JPanel

class IlViewerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = IlViewerPanel(project, toolWindow.disposable) { toolWindow.isVisible }
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false))
    }

    /** A stripe button only with a .NET solution; .NET | IL Viewer brings the window up anyway. */
    override suspend fun isApplicableAsync(project: Project): Boolean = readAction { SolutionService.getInstance(project).solutionFiles().isNotEmpty() }

    companion object {
        const val ID = "IL Viewer"

        fun show(project: Project) {
            val manager = ToolWindowManager.getInstance(project)
            val window = manager.getToolWindow(ID) ?: manager.registerToolWindow(ID) {
                anchor = ToolWindowAnchor.RIGHT
                icon = AllIcons.FileTypes.BinaryData
                contentFactory = IlViewerToolWindowFactory()
            }
            if (!window.isAvailable) window.setAvailable(true, null)
            window.activate(null)
        }
    }
}

/** .NET | IL Viewer: the IL of the code at the caret, as in Rider (Tools | IL Code). */
class ShowIlViewerAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        IlViewerToolWindowFactory.show(e.project ?: return)
    }
}

/**
 * The IL of the member at the caret of the C# editor, read-only and colored. Follows the caret with a pause ([DELAY_MS]) and only while the
 * window is visible; an answer to an older caret is dropped. The lines of the caret are matched both ways through the sequence points:
 * the C# line lights up its instructions, the caret in the IL lights up the source it was compiled from.
 */
class IlViewerPanel(private val project: Project, parent: Disposable, private val isShowing: () -> Boolean) : SimpleToolWindowPanel(true, true), Disposable {
    private val service = IlViewerService.getInstance(project)
    private val document = EditorFactory.getInstance().createDocument("")
    private val editor = EditorFactory.getInstance().createViewer(document, project) as EditorEx
    private val bodies = ComboBox<IlBody>()
    private val stale = EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
        text = IlViewState.STALE
        createActionLabel("Build") { build() }
    }
    private val warning = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, 6)
    }
    private val header = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val empty = JBPanelWithEmptyText()
    private val cards = CardLayout()
    private val center = JPanel(cards)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("IlViewer", 1)

    @Volatile private var generation = 0
    private var dirty = true
    private var updating = false
    private var state: IlViewState? = null
    private var body: IlBody? = null
    /** The C# editor the shown IL is of, and its caret line (1-based). */
    private var sourceEditor: Editor? = null
    private var sourceLine = 0
    private var ilLines: List<RangeHighlighter> = emptyList()
    private var sourceHighlight: RangeHighlighter? = null

    init {
        Disposer.register(parent, this)
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(IlHighlighter(), EditorColorsManager.getInstance().globalScheme)
        editor.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = false
            isLineMarkerAreaShown = false
            additionalLinesCount = 0
            isCaretRowShown = true
        }
        editor.setCaretVisible(true)
        bodies.renderer = object : ColoredListCellRenderer<IlBody>() {
            override fun customizeCellRenderer(list: JList<out IlBody>, value: IlBody?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                append(value.name)
                append("  " + IlViewerLogic.kindTitle(value.kind), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        bodies.addActionListener { if (!updating) (bodies.selectedItem as? IlBody)?.let(::show) }
        header.add(stale)
        header.add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2)
            add(bodies, BorderLayout.CENTER)
        })
        header.add(warning)
        center.add(editor.component, EDITOR_CARD)
        center.add(empty, EMPTY_CARD)
        setContent(JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(center, BorderLayout.CENTER)
        })
        render(IlViewState.Empty(IlViewState.NOT_CSHARP))

        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.editor === editor) ilCaretMoved() else if (isSourceEditor(event.editor)) sourceCaretMoved(event.editor)
            }
        }, this)
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = schedule()
        })
        connection.subscribe(DotNetBuildListener.TOPIC, DotNetBuildListener { _, _ -> ApplicationManager.getApplication().invokeLater({ schedule() }, ModalityState.any(), project.disposed) })
        connection.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) {
                if (dirty && isShowing()) schedule()
            }
        })
        schedule()
    }

    private fun isSourceEditor(other: Editor): Boolean = other.project === project && other !== editor && FileEditorManager.getInstance(project).selectedTextEditor === other

    /** The C# caret has moved: its instructions light up now, the IL of the new place is asked for after the pause. */
    private fun sourceCaretMoved(source: Editor) {
        clearSourceHighlight()
        if (source === sourceEditor) {
            sourceLine = source.caretModel.logicalPosition.line + 1
            highlightIl(scroll = true)
        }
        schedule()
    }

    fun schedule() {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, DELAY_MS)
    }

    /** Takes the caret on the EDT and asks for its IL in the background; an answer to an older caret is dropped. */
    private fun refresh() {
        if (project.isDisposed) return
        if (!isShowing()) {
            dirty = true
            return
        }
        dirty = false
        val source = FileEditorManager.getInstance(project).selectedTextEditor
        val file = source?.let { FileDocumentManager.getInstance().getFile(it.document) }
        if (source == null || file == null) return render(IlViewState.Empty(IlViewState.NOT_CSHARP))
        val offset = source.caretModel.offset
        val caret = IlCaret(file, source.document.immutableCharSequence, offset, source.document.getLineNumber(offset) + 1,
            FileDocumentManager.getInstance().isDocumentUnsaved(source.document))
        val asked = ++generation
        executor.execute {
            if (asked != generation || project.isDisposed) return@execute
            val result = service.compute(caret)
            ApplicationManager.getApplication().invokeLater({
                if (asked == generation && !project.isDisposed) {
                    sourceEditor = source
                    sourceLine = caret.line
                    render(result)
                }
            }, ModalityState.any(), project.disposed)
        }
    }

    /** Shows [next]; the body chosen in the list stays while the caret is in the same member. */
    fun render(next: IlViewState) {
        val previous = state as? IlViewState.Shown
        state = next
        clearSourceHighlight()
        stale.isVisible = next is IlViewState.Shown && next.stale
        warning.isVisible = false
        bodies.isVisible = false
        empty.emptyText.clear()
        when (next) {
            is IlViewState.Empty -> showEmpty(next.hint)
            is IlViewState.NotBuilt -> {
                showEmpty(IlViewState.NOT_BUILT)
                empty.emptyText.appendLine("Build", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { build() }
            }
            is IlViewState.Failed -> {
                showEmpty(next.message)
                empty.emptyText.appendLine("Plugin Logs", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { PluginLogsToolWindowFactory.show(project) }
            }
            is IlViewState.Shown -> {
                next.answer.warning?.let {
                    warning.text = it
                    warning.isVisible = true
                }
                val ordered = IlViewerLogic.ordered(next.answer.bodies)
                if (ordered.isEmpty()) {
                    showEmpty(IlViewState.NOTHING_HERE)
                } else {
                    val sameMember = previous != null && previous.request.file == next.request.file && previous.request.typeName == next.request.typeName &&
                        previous.request.memberName == next.request.memberName
                    val chosen = ordered[IlViewerLogic.choose(ordered, body?.name, sameMember)]
                    updating = true
                    try {
                        bodies.model = DefaultComboBoxModel(ordered.toTypedArray())
                        bodies.selectedItem = chosen
                    } finally {
                        updating = false
                    }
                    bodies.isVisible = true
                    show(chosen)
                }
            }
        }
        header.revalidate()
        header.repaint()
    }

    private fun showEmpty(text: String) {
        body = null
        empty.emptyText.text = text
        cards.show(center, EMPTY_CARD)
    }

    private fun show(next: IlBody) {
        body = next
        cards.show(center, EDITOR_CARD)
        if (document.text != next.text) {
            updating = true
            try {
                DocumentUtil.writeInRunUndoTransparentAction { document.setText(next.text) }
                editor.caretModel.moveToOffset(0)
            } finally {
                updating = false
            }
        }
        highlightIl(scroll = true)
    }

    /** Lights up the instructions of the C# caret line and scrolls to the first of them. */
    private fun highlightIl(scroll: Boolean) {
        ilLines.forEach { editor.markupModel.removeHighlighter(it) }
        val shown = body ?: return run { ilLines = emptyList() }
        val lines = IlViewerLogic.ilLines(shown, sourceLine).filter { it < document.lineCount }
        ilLines = lines.map { editor.markupModel.addLineHighlighter(MATCHED_IL, it, HighlighterLayer.SELECTION - 1) }
        if (scroll && lines.isNotEmpty()) editor.scrollingModel.scrollTo(LogicalPosition(lines.first(), 0), ScrollType.MAKE_VISIBLE)
    }

    /** The caret (or a click) in the IL: the source it was compiled from lights up, the caret of the C# editor stays where it is. */
    private fun ilCaretMoved() {
        if (updating) return
        clearSourceHighlight()
        val shown = body ?: return
        val source = sourceEditor?.takeIf { !it.isDisposed } ?: return
        val point = IlViewerLogic.sourceRange(shown, editor.caretModel.logicalPosition.line) ?: return
        val range = IlViewerLogic.sourceTextRange(source.document.immutableCharSequence, point) ?: return
        sourceHighlight = source.markupModel.addRangeHighlighter(MATCHED_SOURCE, range.startOffset, range.endOffset, HighlighterLayer.SELECTION - 1, HighlighterTargetArea.EXACT_RANGE)
        source.scrollingModel.scrollTo(source.offsetToLogicalPosition(range.startOffset), ScrollType.MAKE_VISIBLE)
    }

    private fun clearSourceHighlight() {
        val highlight = sourceHighlight ?: return
        sourceHighlight = null
        sourceEditor?.takeIf { !it.isDisposed }?.markupModel?.removeHighlighter(highlight)
    }

    private fun build() {
        val path = when (val current = state) {
            is IlViewState.NotBuilt -> current.projectPath
            is IlViewState.Shown -> current.projectPath
            is IlViewState.Failed -> current.projectPath
            else -> null
        } ?: return
        val projectFile = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        DotNetBuildService.getInstance(project).run(projectFile, DotNetBuildCommand.BUILD)
    }

    @get:TestOnly val shownText: String get() = if (body == null) "" else document.text
    @get:TestOnly val statusText: String get() = empty.emptyText.text
    @get:TestOnly val isStaleBannerShown: Boolean get() = stale.isVisible
    @get:TestOnly val warningText: String? get() = warning.text.takeIf { warning.isVisible }
    @get:TestOnly val bodyNames: List<String> get() = (0 until bodies.itemCount).map { bodies.getItemAt(it).name }.takeIf { bodies.isVisible }.orEmpty()

    @TestOnly
    fun select(name: String) {
        (0 until bodies.itemCount).map { bodies.getItemAt(it) }.firstOrNull { it.name == name }?.let { bodies.selectedItem = it }
    }

    override fun dispose() {
        generation++
        clearSourceHighlight()
        EditorFactory.getInstance().releaseEditor(editor)
    }

    companion object {
        const val DELAY_MS = 300
        private const val EDITOR_CARD = "editor"
        private const val EMPTY_CARD = "empty"

        val MATCHED_IL: TextAttributesKey = TextAttributesKey.createTextAttributesKey("DOTNET_IL_MATCHED_INSTRUCTIONS", EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES)
        val MATCHED_SOURCE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("DOTNET_IL_MATCHED_SOURCE", EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES)
    }
}
