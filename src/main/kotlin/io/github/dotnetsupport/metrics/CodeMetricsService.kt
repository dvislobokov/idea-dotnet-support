package io.github.dotnetsupport.metrics

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetHelper
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.solution.isSolutionOrFilterFile
import io.github.dotnetsupport.view.resolveFile
import java.io.File

/**
 * Analyze | Calculate Code Metrics of Visual Studio: MSBuild tells the sources and references of every C# project, the helper
 * (metrics/Program.cs, built on the machine like the other helpers) compiles them with Roslyn and measures; the results open as a tab
 * of the "Code Metrics" tool window. Nothing is built: the references of other projects are their last build, if any.
 */
@Service(Service.Level.PROJECT)
class CodeMetricsService(private val project: Project) {
    fun calculate(target: VirtualFile) {
        val projects = projectsOf(target)
        if (projects.isEmpty()) return DotNetCli.notifyInfo(project, TITLE, "${target.name} has no C# projects.")
        object : Task.Backgroundable(project, "Calculating code metrics of ${target.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "Preparing the metrics helper"
                val dll = HELPER.ensureBuilt() ?: run {
                    PluginLog.error(LOG_CATEGORY, "the metrics helper could not be built: ${HELPER.failure.orEmpty().lines().firstOrNull().orEmpty()}")
                    return DotNetCli.notifyError(project, TITLE, "The metrics helper could not be built: " + HELPER.failure.orEmpty().takeLast(600))
                }
                val inputs = JsonArray()
                val failed = mutableListOf<MetricsNode>()
                projects.forEachIndexed { index, file ->
                    indicator.checkCanceled()
                    indicator.fraction = index.toDouble() / (projects.size + 1)
                    indicator.text = "Asking MSBuild about ${file.name}"
                    when (val answer = query(file)) {
                        is JsonObject -> inputs.add(answer)
                        else -> failed += MetricsNode.container(MetricsLevel.PROJECT, file.nameWithoutExtension, emptyList(), file.path, answer.toString())
                    }
                }
                indicator.fraction = projects.size.toDouble() / (projects.size + 1)
                indicator.text = "Measuring ${inputs.size()} project${if (inputs.size() == 1) "" else "s"}"
                val measured = if (inputs.isEmpty) emptyList() else measure(dll, inputs) ?: return
                val byPath = (measured + failed).associateBy { FileUtil.toSystemIndependentName(it.file.orEmpty()) }
                val ordered = projects.mapNotNull { byPath[FileUtil.toSystemIndependentName(it.path)] }
                val root = CodeMetricsReport.root(target.nameWithoutExtension, ordered)
                ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) show(target, root) }, ModalityState.any())
            }
        }.queue()
    }

    /** The JSON of the project for the helper, or the text of why MSBuild gave none. */
    private fun query(file: VirtualFile): Any {
        val frameworks = ReadAction.compute<List<String>, RuntimeException> { SolutionService.getInstance(project).msBuildProject(file).targetFrameworks }
        val command = DotNetCli.commandLine(file.parent.path, *MetricsInput.msBuildArguments(File(file.path), frameworks.takeIf { it.size > 1 }?.first()).toTypedArray())
            .withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en")
        val result = try {
            DotNetCli.execute(command, MSBUILD_TIMEOUT_MS)
        } catch (e: Exception) {
            return "MSBuild could not be started: ${e.message}"
        }
        if (result.exitCode == 0) MetricsInput.fromMsBuild(result.stdout, File(file.path))?.let { return it }
        val errors = result.stdout.lines().filter { ": error " in it }.ifEmpty { (result.stdout + "\n" + result.stderr).trim().lines().takeLast(5) }
        return "MSBuild could not tell the sources and references (exit code ${result.exitCode}): " + errors.joinToString("\n").take(800)
    }

    private fun measure(dll: File, inputs: JsonArray): List<MetricsNode>? {
        val input = FileUtil.createTempFile("code-metrics", ".json", true)
        try {
            input.writeText(JsonObject().apply { add("projects", inputs) }.toString())
            val result = DotNetCli.execute(DotNetCli.commandLine(input.parent, dll.path, "--input", input.path), HELPER_TIMEOUT_MS)
            val parsed = CodeMetricsReport.parse(result.stdout)
            if (result.exitCode != 0 || parsed.isEmpty()) {
                PluginLog.warn(LOG_CATEGORY, "the metrics helper failed (exit code ${result.exitCode}): ${DotNetCli.lastLines(result, 3)}")
                DotNetCli.notifyError(project, TITLE, "The metrics helper failed (exit code ${result.exitCode}): " + result.stderr.ifBlank { result.stdout }.trim().takeLast(600))
                return null
            }
            return parsed
        } catch (e: Exception) {
            PluginLog.warn(LOG_CATEGORY, "the metrics helper could not be run", e)
            DotNetCli.notifyError(project, TITLE, "The metrics helper could not be run: ${e.message}")
            return null
        } finally {
            FileUtil.delete(input)
        }
    }

    private fun projectsOf(target: VirtualFile): List<VirtualFile> {
        val files = if (isSolutionOrFilterFile(target)) {
            val solutions = SolutionService.getInstance(project)
            val solutionFile = with(solutions) { solutions.solutionFilter(target)?.solutionFile(target) } ?: target
            solutions.solution(target).allProjects.mapNotNull { it.resolveFile(solutionFile) }
        } else listOf(target)
        return files.filter { it.extension.equals("csproj", ignoreCase = true) }.distinct()
    }

    private fun show(target: VirtualFile, root: MetricsNode) {
        val manager = ToolWindowManager.getInstance(project)
        val window = manager.getToolWindow(TOOL_WINDOW_ID) ?: manager.registerToolWindow(TOOL_WINDOW_ID) {
            anchor = ToolWindowAnchor.BOTTOM
            icon = AllIcons.Toolwindows.ToolWindowAnalyzeDataflow
            canCloseContent = true
            stripeTitle = { TOOL_WINDOW_ID }
        }
        val contents = window.contentManager
        // the same target again replaces its tab: Recalculate keeps one tab per solution or project
        contents.contents.filter { it.getUserData(TARGET) == target }.forEach { contents.removeContent(it, true) }
        val panel = CodeMetricsPanel(project, root) { calculate(target) }
        val content = contents.factory.createContent(panel, target.name, false).apply {
            putUserData(TARGET, target)
            setDisposer(panel)
        }
        contents.addContent(content)
        contents.setSelectedContent(content)
        window.activate(null)
    }

    companion object {
        /** The category of the journal of the plugin for the code metrics. */
        const val LOG_CATEGORY = "metrics"

        const val TITLE = "Code Metrics"
        const val TOOL_WINDOW_ID = "Code Metrics"
        val HELPER = DotNetHelper("metrics", "CodeMetrics", "HelperFramework")
        private val TARGET = com.intellij.openapi.util.Key.create<VirtualFile>("io.github.dotnetsupport.metrics.target")
        private const val MSBUILD_TIMEOUT_MS = 180_000
        private const val HELPER_TIMEOUT_MS = 600_000

        fun getInstance(project: Project): CodeMetricsService = project.service()
    }
}

/** "Calculate Code Metrics": for the project or solution selected, or the solution when nothing is. */
class CalculateCodeMetricsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = target(e)
        e.presentation.isVisible = target != null || !e.isFromContextMenu
        e.presentation.isEnabled = target != null
    }

    private fun target(e: AnActionEvent): VirtualFile? {
        val project = e.project ?: return null
        val target = SolutionContext.buildTarget(e) ?: SolutionService.getInstance(project).solutionFiles().firstOrNull() ?: return null
        return target.takeIf { isSolutionOrFilterFile(it) || it.extension.equals("csproj", ignoreCase = true) }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CodeMetricsService.getInstance(project).calculate(target(e) ?: return)
    }
}
