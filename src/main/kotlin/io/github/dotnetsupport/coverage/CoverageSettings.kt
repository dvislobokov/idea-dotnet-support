package io.github.dotnetsupport.coverage

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.msbuild.DotNetProjects

/** What happens to the coverage that is already shown when a new run brings its own; the choices of the platform page. */
enum class NewCoverageAction { ASK, DO_NOT_APPLY, REPLACE, ADD }

@Service(Service.Level.APP)
@State(name = "DotNetCoverageSettings", storages = [Storage("dotnet-support.xml")])
class CoverageSettings : SimplePersistentStateComponent<CoverageSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        /** Asks only when some coverage is shown already: there is nothing to choose for the first run. */
        var onNewCoverage by enum(NewCoverageAction.ASK)
        var activateView by property(true)
        var showInProjectView by property(true)
    }

    var onNewCoverage: NewCoverageAction
        get() = state.onNewCoverage
        set(value) { state.onNewCoverage = value }

    var activateView: Boolean
        get() = state.activateView
        set(value) { state.activateView = value }

    var showInProjectView: Boolean
        get() = state.showInProjectView
        set(value) { state.showInProjectView = value }

    companion object {
        fun getInstance(): CoverageSettings = service()
    }
}

/** Settings | Tools | .NET | Coverage, the same options as Build, Execution, Deployment | Coverage has for the languages of the IDE. */
class CoverageSettingsConfigurable : BoundConfigurable("Coverage") {
    private val settings get() = CoverageSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        buttonsGroup("When new coverage is gathered") {
            row { radioButton("Show options before applying coverage to the editor", NewCoverageAction.ASK) }
            row { radioButton("Do not apply collected coverage", NewCoverageAction.DO_NOT_APPLY) }
            row { radioButton("Replace active suites with the new one", NewCoverageAction.REPLACE) }
            row { radioButton("Add to the active suites", NewCoverageAction.ADD).comment("Hits of the runs are summed: a line is covered when any of them has executed it") }
        }.bind({ settings.onNewCoverage }, { settings.onNewCoverage = it })
        row { checkBox("Activate Coverage View").bindSelected(settings::activateView) }
        row { checkBox("Show coverage in the project view").bindSelected(settings::showInProjectView).comment("Percent of covered lines next to files and folders, in the Solution view too") }
    }
}

/** `87%` next to the files and folders the shown coverage knows about. */
class CoverageProjectViewDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        if (!CoverageSettings.getInstance().showInProjectView) return
        val project = node.project ?: return
        val report = DotNetCoverageService.getInstance(project).report
        if (report.files.isEmpty()) return
        // the node of a project stands for its directory
        val file = node.virtualFile?.let { if (DotNetProjects.isProjectFile(it)) it.parent else it } ?: return
        val (covered, total) = linesUnder(report, file.path, file.isDirectory) ?: return
        val text = CoverageToolWindowFactory.percent(covered, total) + " lines covered"
        data.locationString = listOfNotNull(data.locationString?.takeIf { it.isNotBlank() }, text).joinToString(", ")
    }

    companion object {
        /** Covered and total lines of the file, or of everything under the directory; null when the report has nothing there. */
        fun linesUnder(report: CoverageReport, path: String, isDirectory: Boolean): Pair<Int, Int>? {
            val normalized = FileUtil.toSystemIndependentName(path).trimEnd('/')
            val files = report.files.filter {
                val covered = FileUtil.toSystemIndependentName(it.path)
                if (isDirectory) FileUtil.startsWith(covered, normalized) && covered.length > normalized.length else FileUtil.pathsEqual(covered, normalized)
            }
            return if (files.isEmpty()) null else files.sumOf { it.coveredLines } to files.sumOf { it.totalLines }
        }
    }
}
