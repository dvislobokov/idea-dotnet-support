package io.github.dotnetsupport.build

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile

/**
 * The build configuration (Debug / Release / ...) and the target framework chosen in the Build Solution button of the toolbar ([BuildSolutionBar]); they apply to
 * build, run and test. Kept per project in the workspace file: a personal choice, not something to commit.
 */
@Service(Service.Level.PROJECT)
@State(name = "DotNetBuildSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DotNetBuildSettings(private val project: Project) : SimplePersistentStateComponent<DotNetBuildSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        var configuration by string(DEFAULT_CONFIGURATION)

        /** Null: the default of the SDK (every framework for a build, the first one for a run). */
        var framework by string()
    }

    var configuration: String
        get() = state.configuration ?: DEFAULT_CONFIGURATION
        set(value) {
            if (value == configuration) return
            state.configuration = value
            optionsChanged()
        }

    var framework: String?
        get() = state.framework
        set(value) {
            if (value == state.framework) return
            state.framework = value
            optionsChanged()
        }

    /** Another configuration or framework: other `#if` symbols and language version for the C# files ([CompilationModel]). */
    private fun optionsChanged() {
        if (!project.isDisposed) project.messageBus.syncPublisher(CompilationModel.CHANGED).optionsChanged(null)
    }

    private fun solutionProjects(): List<VirtualFile> {
        val solutions = SolutionService.getInstance(project)
        return solutions.solutionFiles().flatMap { file -> solutions.solution(file).allProjects.mapNotNull { it.resolveFile(file) } }.distinct()
    }

    /** Configurations of the solution file; Debug and Release when it declares none (`.slnx` omits the defaults). */
    fun availableConfigurations(): List<String> {
        val solutions = SolutionService.getInstance(project)
        return solutions.solutionFiles().flatMap { solutions.solution(it).configurations }.distinct().ifEmpty { listOf("Debug", "Release") }
    }

    /** Frameworks worth choosing from: the ones of multi-targeted projects. */
    fun availableFrameworks(): List<String> {
        val solutions = SolutionService.getInstance(project)
        return solutionProjects().map { solutions.msBuildProject(it).targetFrameworks }.filter { it.size > 1 }.flatten().distinct()
    }

    /** `--framework` makes sense only for a project that targets several frameworks, the selected one among them. */
    fun frameworkArguments(projectFile: VirtualFile?): List<String> {
        val selected = framework ?: return emptyList()
        if (projectFile == null || !DotNetProjects.isProjectFile(projectFile)) return emptyList() // a solution: the CLI rejects --framework
        val frameworks = SolutionService.getInstance(project).msBuildProject(projectFile).targetFrameworks
        return if (frameworks.size > 1 && selected in frameworks) listOf("--framework", selected) else emptyList()
    }

    /** The framework whose output a debugger starts: the selected one, else the first of a multi-targeted project; null for a single target. */
    fun launchFramework(projectFile: VirtualFile): String? {
        val frameworks = SolutionService.getInstance(project).msBuildProject(projectFile).targetFrameworks
        return if (frameworks.size > 1) framework?.takeIf { it in frameworks } ?: frameworks.first() else null
    }

    fun buildArguments(command: DotNetBuildCommand, target: VirtualFile): Array<String> =
        if (command == DotNetBuildCommand.RESTORE) emptyArray()
        else (listOf("-c", configuration) + frameworkArguments(target)).toTypedArray()

    fun runArguments(projectPath: String): List<String> =
        listOf("-c", configuration) + frameworkArguments(LocalFileSystem.getInstance().findFileByPath(projectPath)) +
            // `dotnet run -p` used to mean --project: the long form is unambiguous
            DotNetBuildOptions.propertyArguments(DotNetBuildOptions.getInstance(project).state.globalProperties).map { "--property:" + it.removePrefix("-p:") }

    companion object {
        const val DEFAULT_CONFIGURATION = "Debug"

        fun getInstance(project: Project): DotNetBuildSettings = project.service()
    }
}
