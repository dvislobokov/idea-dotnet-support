package io.github.dotnetsupport.diagnostics

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetLogs
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.CopyCompilationReferencesAction
import io.github.dotnetsupport.lang.AnalysisScopes
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatures
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lang.NativeCSharpSolutionProblems
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Everything the plugin knows about the solution, in one file to send or to read (the false errors of a live solution took six rounds of
 * questions, 0.1.184): the IDE, `dotnet`, the settings that decide who reports errors, what every project is compiled against
 * ([CopyCompilationReferencesAction.projectReport]), every error and warning of every C# file as the Problems window has them — with the
 * text under each — and the journal of the plugin. The file is in `~/idea-dotnet-logs/diagnostics` and opens in the editor.
 */
class FullDiagnosticsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Collecting .NET diagnostics", true) {
            override fun run(indicator: ProgressIndicator) {
                val text = runCatching { report(project, indicator) }.getOrElse { "the report failed: $it\n" + it.stackTraceToString() }
                val directory = DotNetLogs.directory("diagnostics").also { Files.createDirectories(it) }
                val file = directory.resolve("diagnostics-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt").toFile()
                file.writeText(text)
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) return@invokeLater
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, it), true) }
                    DotNetCli.notifyInfo(project, "Diagnostics collected", "${file.path} (${text.lines().size} lines)")
                }, ModalityState.any())
            }
        })
    }

    private fun report(project: Project, indicator: ProgressIndicator): String = buildString {
        val now = LocalDateTime.now()
        appendLine("C# Project Support ${PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport"))?.version} — full diagnostics of ${project.name}, $now")
        val app = ApplicationInfo.getInstance()
        appendLine("IDE: ${app.fullApplicationName} (${app.build}); OS: ${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION} ${SystemInfo.OS_ARCH}; Java ${SystemInfo.JAVA_RUNTIME_VERSION}")
        appendLine("project base: ${project.basePath}")
        val dotnet = DotNetCli.findExecutable()
        appendLine("dotnet: $dotnet -> ${dotnet?.let { runCatching { File(it).canonicalPath }.getOrNull() }}")
        if (dotnet != null) for (arguments in listOf(arrayOf("--list-sdks"), arrayOf("--list-runtimes"))) {
            val out = runCatching { DotNetCli.execute(DotNetCli.commandLine(project.basePath, *arguments), 30_000) }.getOrNull()
            appendLine("dotnet ${arguments.joinToString(" ")}:")
            out?.stdout?.lines()?.filter { it.isNotBlank() }?.forEach { appendLine("  $it") } ?: appendLine("  (failed)")
        }
        appendLine("who reports: " + CSharpFeature.entries.joinToString { "${it.name}=${if (CSharpFeatures.native(it, project)) "plugin" else "heuristics (indexing)"}" })
        appendLine("analysis scopes: compiler ${AnalysisScopes.compiler()}, analyzers ${AnalysisScopes.analyzer()}")

        // the projects of the solutions, each with what it is compiled against
        indicator.text = "Projects"
        val solutions = SolutionService.getInstance(project)
        val projectFiles = solutions.solutionFiles().flatMap { solution ->
            appendLine("solution: ${solution.path}")
            solutions.solution(solution).allProjects.mapNotNull { it.resolveFile(solution) }
        }.distinctBy { it.path }
        val service = AssemblyIndexService.getInstance(project)
        appendLine("projects: ${projectFiles.size}")
        for (projectFile in projectFiles) {
            indicator.checkCanceled()
            appendLine()
            appendLine("================ project ${projectFile.path}")
            append(runCatching { CopyCompilationReferencesAction.projectReport(project, projectFile) }.getOrElse { "  the report failed: $it\n" })
            val references = service.references(projectFile)
            if (references == null) appendLine("  (no references computed: not restored, or the index has not run yet)")
        }

        // every error and warning of every C# file, as the Problems window has them, and the text under each
        indicator.text = "Errors and warnings"
        val model = CompilationModel.getInstance(project)
        val files = ReadAction.nonBlocking<List<VirtualFile>> { FileTypeIndex.getFiles(CSharpFileType, GlobalSearchScope.projectScope(project)).toList() }
            .inSmartMode(project).executeSynchronously().sortedBy { it.path }
        val problems = NativeCSharpSolutionProblems.getInstance(project)
        val lines = ArrayList<String>()
        val byCode = java.util.TreeMap<String, Int>()
        var checked = 0
        for ((i, file) in files.withIndex()) {
            indicator.checkCanceled()
            indicator.fraction = i.toDouble() / files.size.coerceAtLeast(1)
            indicator.text2 = file.name
            // a non-blocking read is run again after a write action: nothing is counted inside it
            val found = ReadAction.nonBlocking<List<Pair<String, String>>?> {
                val owner = model.compiledIn(file) ?: return@nonBlocking null
                val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)?.charsSequence ?: return@nonBlocking null
                problems.check(file).map { p ->
                    val line = text.lineSequence().drop(p.line).firstOrNull()?.trim()?.take(160).orEmpty()
                    "${file.path}:${p.line + 1}:${p.column + 1} [${if (p.error) "ERROR" else "WARNING"}] ${p.code} ${p.text.replace('\n', ' ')}\n      | $line   (project ${owner.nameWithoutExtension})" to (p.code ?: "?")
                }
            }.inSmartMode(project).executeSynchronously() ?: continue
            checked++
            for ((line, code) in found) {
                lines += line
                byCode.merge(code, 1, Int::plus)
            }
        }
        appendLine()
        appendLine("================ errors and warnings: ${lines.size} in $checked compiled files of ${files.size}")
        appendLine("by code: " + byCode.entries.joinToString { "${it.key} ${it.value}" })
        lines.forEach(::appendLine)

        // the analyzers of the projects (CA, IDE, the packages' own), as far as they have run: Info too, which the Problems window leaves out
        appendLine()
        val analysis = io.github.dotnetsupport.codeanalysis.CodeAnalysisService.getInstance(project)
        val analyzed = projectFiles.flatMap { projectFile -> analysis.analyzedOf(projectFile.path).flatMap { it.diagnostics } }
        appendLine("================ analyzer diagnostics: ${analyzed.size} (of the projects analyzed so far; analyzers active: ${analysis.analyzersActive})")
        appendLine("by id: " + analyzed.groupingBy { it.id }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        for (d in analyzed.sortedWith(compareBy({ it.path }, { it.startLine }))) {
            appendLine("${d.path}:${d.startLine + 1}:${d.startColumn + 1} [${d.severity}] ${d.id} ${d.message.trim().replace('\n', ' ')}")
        }

        appendLine()
        appendLine("================ journal of the plugin (the last ${JOURNAL_LINES} entries)")
        PluginLog.entries.takeLast(JOURNAL_LINES).forEach { appendLine(it.format()) }
    }

    private companion object {
        const val JOURNAL_LINES = 400
    }
}
