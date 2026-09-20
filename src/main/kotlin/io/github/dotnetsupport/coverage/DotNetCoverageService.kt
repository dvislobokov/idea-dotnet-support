package io.github.dotnetsupport.coverage

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import io.github.dotnetsupport.cli.DotNetCli
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.io.File

/**
 * Coverage of the last `dotnet test` run with coverlet: colored stripes in the gutter of open editors and a summary
 * in the ".NET Coverage" tool window. Deliberately not the platform Coverage subsystem, which is built around
 * JVM-style class data and its own report format.
 */
@Service(Service.Level.PROJECT)
class DotNetCoverageService(private val project: Project) : Disposable {
    var report: CoverageReport = CoverageReport.EMPTY
        private set
    var title: String = ""
        private set
    private val listeners = ArrayList<() -> Unit>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) highlight(event.editor)
            }
        }, this)
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    /** Reads every `coverage.cobertura.xml` under [resultsDirectory]: a run over a solution has one per test project. */
    fun loadResults(resultsDirectory: File, runName: String) {
        // VSTest keeps a second copy of each attachment under <run>/In/<machine>
        val files = resultsDirectory.walkTopDown()
            .filter { it.isFile && it.name == "coverage.cobertura.xml" && "/In/" !in FileUtil.toSystemIndependentName(it.path) }
            .toList()
        if (files.isEmpty()) {
            DotNetCli.notifyError(
                project, "No coverage data",
                "The run produced no coverage.cobertura.xml. Coverage is collected by the coverlet.collector package: add it to the test project.",
            )
            return
        }
        val merged = files.map { CoberturaParser.parse(it.readText()) }.reduce(CoverageReport::merge)
        ApplicationManager.getApplication().invokeLater({ show(merged, runName) }, project.disposed)
    }

    fun show(newReport: CoverageReport, runName: String) {
        report = newReport
        title = runName
        refreshEditors()
        listeners.forEach { it() }
        // The window is always available. It used to appear with the first run, but then a layout that remembered it
        // as open made setAvailable(true) fail an assertion of the platform.
        ToolWindowManager.getInstance(project).getToolWindow(CoverageToolWindowFactory.ID)?.show()
    }

    fun clear() {
        report = CoverageReport.EMPTY
        title = ""
        refreshEditors()
        listeners.forEach { it() }
    }

    fun coverageOf(path: String): FileCoverage? {
        val normalized = FileUtil.toSystemIndependentName(path)
        return report.files.firstOrNull { FileUtil.pathsEqual(FileUtil.toSystemIndependentName(it.path), normalized) }
    }

    private fun refreshEditors() {
        EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::highlight)
    }

    private fun highlight(editor: Editor) {
        val markup = editor.markupModel
        editor.getUserData(HIGHLIGHTERS)?.forEach(markup::removeHighlighter)
        editor.putUserData(HIGHLIGHTERS, null)

        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val coverage = coverageOf(file.path) ?: return
        val lineCount = editor.document.lineCount
        editor.putUserData(HIGHLIGHTERS, coverage.lines.filterKeys { it in 1..lineCount }.map { (number, line) ->
            markup.addLineHighlighter(number - 1, HighlighterLayer.SELECTION - 1, null).apply {
                lineMarkerRenderer = Stripe(colorOf(line))
            }
        })
    }

    override fun dispose() {}

    private class Stripe(private val color: Color) : LineMarkerRenderer {
        override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
            g.color = color
            g.fillRect(r.x, r.y, STRIPE_WIDTH, r.height)
        }
    }

    companion object {
        private val HIGHLIGHTERS = Key.create<List<RangeHighlighter>>("dotnet.coverage.highlighters")
        private const val STRIPE_WIDTH = 4

        val COVERED: Color = JBColor(Color(0x59A869), Color(0x499C54))
        val PARTIAL: Color = JBColor(Color(0xE8A33D), Color(0xC9A227))
        val UNCOVERED: Color = JBColor(Color(0xDB5860), Color(0xC75450))

        fun colorOf(line: LineCoverage): Color = when {
            !line.isCovered -> UNCOVERED
            line.isPartial -> PARTIAL
            else -> COVERED
        }

        fun getInstance(project: Project): DotNetCoverageService = project.service()
    }
}
