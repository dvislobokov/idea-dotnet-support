package io.github.dotnetsupport.build

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.nuget.NuGetSettings
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** `-verbosity` of MSBuild, with the names Rider shows. */
enum class MsBuildVerbosity(val title: String, val argument: String) {
    QUIET("Quiet", "quiet"), MINIMAL("Minimal", "minimal"), NORMAL("Normal", "normal"), DETAILED("Detailed", "detailed"), DIAGNOSTIC("Diagnostic", "diagnostic");

    override fun toString(): String = title
}

/**
 * Settings | Tools | .NET | Toolset and Build: what is added to the `dotnet build` of the project. In the workspace file:
 * global properties and a log folder belong to a checkout, not to the repository.
 */
@Service(Service.Level.PROJECT)
@State(name = "DotNetBuildOptions", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DotNetBuildOptions(private val project: Project) : SimplePersistentStateComponent<DotNetBuildOptions.Settings>(Settings()) {
    class Settings : BaseState() {
        /** `Name=Value;Other=Value`, as in the field of Rider. */
        var globalProperties by string("")
        var buildAfterSolutionIsLoaded by property(false)

        /** Off: `--no-restore`, the packages are restored by hand or by the automatic restore of the NuGet page. */
        var restoreBeforeBuild by property(true)

        /** 0: MSBuild decides by the number of cores. */
        var parallelProcesses by property(0)
        var outputVerbosity by enum(MsBuildVerbosity.MINIMAL)
        var logToFile by property(false)
        var fileVerbosity by enum(MsBuildVerbosity.NORMAL)
        var logFolder by string("")
    }

    val logFolder: File get() = state.logFolder?.takeIf { it.isNotBlank() }?.let(::File) ?: defaultLogFolder()

    /** What the settings add to [command] of [target]; [now] names the log file. */
    fun arguments(command: DotNetBuildCommand, target: VirtualFile, now: LocalDateTime = LocalDateTime.now()): List<String> {
        val builds = command == DotNetBuildCommand.BUILD || command == DotNetBuildCommand.REBUILD
        val skipRestore = builds && (!state.restoreBeforeBuild || NuGetSettings.getInstance().smartRestore && SmartRestore.isUpToDate(project, target))
        if (state.logToFile) logFolder.mkdirs() // the file logger does not create the folder
        return arguments(state, command, skipRestore, logFolder, now)
    }

    companion object {
        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss")

        fun getInstance(project: Project): DotNetBuildOptions = project.service()

        fun defaultLogFolder(): File = io.github.dotnetsupport.cli.DotNetLogs.directory("DotNetBuild").toFile()

        /** `Configuration=Release; Platform = x64` -> `-p:Configuration=Release`, `-p:Platform=x64`; entries without a name are dropped. */
        fun propertyArguments(globalProperties: String?): List<String> = globalProperties.orEmpty().split(';', '\n')
            .map { it.trim() }.filter { '=' in it && it.substringBefore('=').isNotBlank() }
            .map { "-p:" + it.substringBefore('=').trim() + "=" + it.substringAfter('=').trim() }

        fun arguments(settings: Settings, command: DotNetBuildCommand, skipRestore: Boolean, logFolder: File, now: LocalDateTime): List<String> = buildList {
            addAll(propertyArguments(settings.globalProperties))
            if (command == DotNetBuildCommand.RESTORE) {
                addAll(NuGetSettings.getInstance().restoreArguments())
                return@buildList
            }
            if (skipRestore) add("--no-restore")
            if (settings.parallelProcesses > 0) add("-m:${settings.parallelProcesses}")
            // minimal is what `dotnet build` prints anyway
            if (settings.outputVerbosity != MsBuildVerbosity.MINIMAL) add("-v:${settings.outputVerbosity.argument}")
            if (settings.logToFile && command != DotNetBuildCommand.CLEAN) {
                val file = File(logFolder, "${command.title}_${FILE_STAMP.format(now)}.log")
                add("-fl")
                add("-flp:logfile=${file.path};verbosity=${settings.fileVerbosity.argument};encoding=UTF-8")
            }
        }
    }
}

/**
 * "Smart Restore on Build": the restore inside `dotnet build` is skipped when nothing it depends on has changed since
 * the last one, i.e. `obj/project.assets.json` of every project is newer than the files that decide what gets restored.
 */
object SmartRestore {
    private val INPUTS_UP_THE_TREE = listOf("Directory.Packages.props", "Directory.Build.props", "Directory.Build.targets", "nuget.config", "NuGet.Config", "global.json")

    /** [target] is a solution or a project; a project is checked with the projects it references. */
    fun isUpToDate(project: Project, target: VirtualFile): Boolean {
        val solutions = SolutionService.getInstance(project)
        val projects = if (DotNetProjects.isProjectFile(target)) {
            val referenced = solutions.assets(target).targets.flatMap { it.projects }.mapNotNull { it.path }.mapNotNull { target.parent?.findFileByRelativePath(it) }
            listOf(target) + referenced
        } else solutions.solution(target).allProjects.mapNotNull { it.resolveFile(target) }
        return projects.isNotEmpty() && projects.distinct().all { isUpToDate(File(it.path)) }
    }

    fun isUpToDate(projectFile: File): Boolean {
        val directory = projectFile.parentFile ?: return false
        val assets = File(directory, "obj/project.assets.json")
        if (!assets.isFile) return false
        val inputs = listOf(projectFile, File(directory, "packages.lock.json")) +
            generateSequence(directory) { it.parentFile }.flatMap { dir -> INPUTS_UP_THE_TREE.map { File(dir, it) } }
        return isUpToDate(assets.lastModified(), inputs.filter { it.isFile }.map { it.lastModified() })
    }

    fun isUpToDate(assetsStamp: Long, inputStamps: List<Long>): Boolean = inputStamps.all { it <= assetsStamp }
}

/** "Run build after solution is loaded". */
class BuildAfterSolutionIsLoaded : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!DotNetBuildOptions.getInstance(project).state.buildAfterSolutionIsLoaded) return
        val solution = SolutionService.getInstance(project).solutionFiles().firstOrNull() ?: return
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({ DotNetBuildService.getInstance(project).run(solution, DotNetBuildCommand.BUILD) }, project.disposed)
    }
}
