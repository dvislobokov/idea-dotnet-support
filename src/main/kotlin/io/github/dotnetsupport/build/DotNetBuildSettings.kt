package io.github.dotnetsupport.build

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.msbuild.TargetFrameworks
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import javax.swing.JComponent

/**
 * The build configuration (Debug / Release / ...) and the target framework chosen in the toolbar; they apply to
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
        set(value) { state.configuration = value }

    var framework: String?
        get() = state.framework
        set(value) { state.framework = value }

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
    private fun frameworkArguments(projectFile: VirtualFile?): List<String> {
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

/** Toolbar selector `Debug | .NET 9.0 ▾`, shown for projects with a solution. */
class BuildConfigurationSelector : ComboBoxAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || SolutionService.getInstance(project).solutionFiles().isEmpty()) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val settings = DotNetBuildSettings.getInstance(project)
        e.presentation.isEnabledAndVisible = true
        e.presentation.text = listOfNotNull(settings.configuration, settings.framework?.let(TargetFrameworks::displayName)).joinToString(" | ")
        e.presentation.description = "Configuration and target framework for build, run and tests"
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val project = dataContext.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT) ?: return DefaultActionGroup()
        val settings = DotNetBuildSettings.getInstance(project)
        val group = DefaultActionGroup()
        group.add(Separator.create("Configuration"))
        settings.availableConfigurations().forEach { name -> group.add(choice(name, { settings.configuration == name }) { settings.configuration = name }) }

        val frameworks = settings.availableFrameworks()
        if (frameworks.isNotEmpty()) {
            group.add(Separator.create("Target Framework"))
            group.add(choice("Default", { settings.framework == null }) { settings.framework = null })
            frameworks.forEach { tfm -> group.add(choice(TargetFrameworks.displayName(tfm), { settings.framework == tfm }) { settings.framework = tfm }) }
        }
        return group
    }

    private fun choice(text: String, isSelected: () -> Boolean, select: () -> Unit): AnAction = object : ToggleAction(text), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent): Boolean = isSelected()
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) select()
        }
    }
}
