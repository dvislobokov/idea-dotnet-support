package io.github.dotnetsupport.codeanalysis

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile

/** What a change of files on disk asks of [CodeAnalysisService]: pure, so it is tested without the VFS. */
object CodeAnalysisEvents {
    /**
     * [SAVED_FILE]: another file saved by the IDE — an additional file of the generators of a project, if the helper said so. [FILE_SET]:
     * another file appeared, went or moved — the set of additional files of a project, if its generators take files like it.
     */
    enum class Kind { SAVED_SOURCE, PROJECT_INPUT, SOURCE_SET, GENERATOR_INPUT, SAVED_FILE, FILE_SET, NONE }

    private val PROJECT_INPUTS = setOf("csproj", "props", "targets", "editorconfig", "globalconfig", "json")

    /** [contentChange]: the text of a file changed ([fromSave]: a document of the IDE was saved); otherwise a file appeared, went or moved. */
    fun kind(path: String, contentChange: Boolean, fromSave: Boolean): Kind {
        val name = path.substringAfterLast('/')
        val extension = name.substringAfterLast('.', "").lowercase()
        // what builds write (AssemblyInfo.cs, the editorconfig of MSBuild, outputs): no change of the project — the design-time build of the
        // helper writes some of them itself, and must not set off another load; the assets of a restore are the exception
        val output = Regex("(?i)/(obj|bin)/").containsMatchIn(path)
        if (output && !name.equals("project.assets.json", ignoreCase = true)) return Kind.NONE
        return when {
            extension == "cs" && contentChange -> if (fromSave) Kind.SAVED_SOURCE else Kind.NONE
            extension == "cs" -> Kind.SOURCE_SET
            // project.assets.json is what a restore changes; another JSON (appsettings) is no input of the compiler
            extension == "json" -> if (name.equals("project.assets.json", ignoreCase = true)) Kind.PROJECT_INPUT else Kind.NONE
            name.equals(".editorconfig", ignoreCase = true) || extension in PROJECT_INPUTS -> Kind.PROJECT_INPUT
            // what the targets of the build make C# of (XAML, protobuf, resources): their outputs are stale until made again
            extension in BuildGeneratedSources.INPUT_EXTENSIONS -> Kind.GENERATOR_INPUT
            contentChange && fromSave -> Kind.SAVED_FILE
            // a `.razor` renamed (or made, deleted, moved): the generators would go on reading the old list of files — the class of the
            // renamed component never made (the WSL sandbox, 0.1.268)
            !contentChange -> Kind.FILE_SET
            else -> Kind.NONE
        }
    }
}

/** Saves of C# files, changes of project files and of the set of sources of [project]. */
class CodeAnalysisFileListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) return
        val service = project.getServiceIfCreated(CodeAnalysisService::class.java) ?: return
        val saved = ArrayList<VirtualFile>()
        val inputs = ArrayList<String>()
        val generatorInputs = ArrayList<String>()
        val savedFiles = ArrayList<String>()
        for (event in events) {
            when (CodeAnalysisEvents.kind(event.path, event is VFileContentChangeEvent, event.isFromSave)) {
                CodeAnalysisEvents.Kind.SAVED_SOURCE -> event.file?.takeIf { !service.isGenerated(it) }?.let { saved += it }
                CodeAnalysisEvents.Kind.PROJECT_INPUT, CodeAnalysisEvents.Kind.SOURCE_SET -> {
                    if (service.isGeneratedPath(event.path)) continue
                    inputs += event.path
                    if (event is VFileMoveEvent) inputs += event.oldPath
                    if (event is VFilePropertyChangeEvent && event.isRename) inputs += event.oldPath
                }
                CodeAnalysisEvents.Kind.FILE_SET -> {
                    val old = (event as? VFileMoveEvent)?.oldPath ?: (event as? VFilePropertyChangeEvent)?.takeIf { it.isRename }?.oldPath
                    if (event is VFilePropertyChangeEvent && !event.isRename) continue
                    if (service.takesFilesLike(event.path) || old != null && service.takesFilesLike(old)) inputs += listOfNotNull(event.path, old)
                }
                CodeAnalysisEvents.Kind.GENERATOR_INPUT -> generatorInputs += event.path

                CodeAnalysisEvents.Kind.SAVED_FILE -> savedFiles += event.path
                CodeAnalysisEvents.Kind.NONE -> Unit
            }
        }
        if (inputs.isNotEmpty()) service.projectFilesChanged(inputs)
        if (generatorInputs.isNotEmpty()) service.generatorInputsChanged(generatorInputs)
        savedFiles.forEach(service::savedGeneratorInput)
        val own = saved.filter { ProjectLocator.getInstance().guessProjectForFile(it) == project }
        if (own.isNotEmpty()) ApplicationManager.getApplication().executeOnPooledThread { own.forEach(service::saved) }
    }
}

/**
 * Typing in a C# file or in an additional file of the generators (a `.razor`): the generated files of its project may not fit it any more
 * until the next save (they stay, the semantics listens).
 */
class CodeAnalysisDocumentListener : DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
        val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
        if (!file.isInLocalFileSystem) return
        for (project in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            project.getServiceIfCreated(CodeAnalysisService::class.java)?.documentChanged(file)
        }
    }
}

/**
 * Which projects load in the background when the solution opens, before any file is ([CodeAnalysisService.warmUp]): the ones the user
 * opened or saved C# files of last time, most recent first, at most [LIMIT]. Not every project of the solution: each one costs the
 * helper its compilation, its references' and what its generators make (MudBlazor.Docs with its references: ~1 GB, 20 s of
 * generators) — a solution of a hundred projects would hold the helper busy and big for projects nobody looks at. Pure, tested.
 */
object CodeAnalysisWarmUp {
    const val LIMIT = 3

    /** [recent] after [path] was worked in: in front, once, the oldest dropped past [limit]. Paths compare as on Windows, without case. */
    fun pushed(recent: List<String>, path: String, limit: Int = LIMIT): List<String> =
        (listOf(path) + recent.filterNot { it.equals(path, ignoreCase = true) }).take(limit)

    /** The [recent] projects that are still C# projects of the solution ([solutionProjects]), in the order of [recent]. */
    fun toWarmUp(recent: List<String>, solutionProjects: Collection<String>, limit: Int = LIMIT): List<String> =
        recent.mapNotNull { path -> solutionProjects.firstOrNull { it.equals(path, ignoreCase = true) } }.distinct().take(limit)
}

/** The solution is open: the projects worked in last time load and generate in the background ([CodeAnalysisService.warmUp]). */
class CodeAnalysisWarmUpActivity : com.intellij.openapi.startup.ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode || project.isDisposed) return
        CodeAnalysisService.getInstance(project).warmUp()
    }
}

/** A C# file opened in the editor: its project generates once and the file is analyzed. */
class CodeAnalysisEditorListener(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (file.fileType != CSharpFileType || project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread { if (!project.isDisposed) CodeAnalysisService.getInstance(project).opened(file) }
    }
}

/** The C# projects an action is about: the selected project or solution of the Solution view, else the project of the file in the editor. */
private fun targetProjects(e: AnActionEvent): List<VirtualFile> {
    val project = e.project ?: return emptyList()
    val target = SolutionContext.buildTarget(e)
    if (target != null && target.extension.equals("csproj", ignoreCase = true)) return listOf(target)
    if (target != null && !target.isDirectory && target.extension?.lowercase() in setOf("sln", "slnx", "slnf")) {
        return SolutionService.getInstance(project).solution(target).allProjects.mapNotNull { it.resolveFile(target) }.filter { it.extension.equals("csproj", ignoreCase = true) }
    }
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.fileType == CSharpFileType } ?: return emptyList()
    return listOfNotNull(CompilationModel.getInstance(project).projectOf(file))
}

/** .NET → Code Analysis → Run Code Analysis: the analyzers of the project (solution), the list in the Build tool window. */
class RunCodeAnalysisAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val projects = targetProjects(e)
        val active = e.project?.let { CodeAnalysisService.getInstance(it).analyzersActive } ?: false
        e.presentation.isVisible = projects.isNotEmpty() || !e.isFromContextMenu
        e.presentation.isEnabled = projects.isNotEmpty() && active
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CodeAnalysisService.getInstance(project).runCodeAnalysis(targetProjects(e).ifEmpty { return })
    }
}

/** .NET → Code Analysis → Refresh Generated Files: the source generators of the project (solution) run now. */
class RefreshGeneratedFilesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val projects = targetProjects(e)
        e.presentation.isVisible = projects.isNotEmpty() || !e.isFromContextMenu
        e.presentation.isEnabled = projects.isNotEmpty() && io.github.dotnetsupport.settings.DotNetSettings.getInstance().runSourceGenerators
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        CodeAnalysisService.getInstance(project).refreshGenerated(targetProjects(e))
    }
}
