package io.github.dotnetsupport.run

import com.intellij.execution.RunManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.dotnetsupport.settings.DotNetSettings
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile

/**
 * Creates run configurations for the runnable projects of the solution, the way Rider does: one per launch
 * profile (`Web: http`, `Web: https`), or a single one named after the project when there are no profiles.
 */
@Service(Service.Level.PROJECT)
class DotNetRunConfigurationGenerator(private val project: Project) {
    data class Target(val name: String, val projectPath: String, val launchProfile: String?, val openBrowser: Boolean = false, val preferred: Boolean = false) {
        val key: String get() = "$projectPath|${launchProfile.orEmpty()}"
    }

    /** Collects targets in the background and registers the missing configurations on EDT. */
    fun schedule() {
        if (!DotNetSettings.getInstance().createRunConfigurations) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val targets = ReadAction.computeBlocking<List<Target>, RuntimeException> { if (project.isDisposed) emptyList() else collectTargets() }
            if (targets.isNotEmpty()) ApplicationManager.getApplication().invokeLater({ register(targets) }, project.disposed)
        }
    }

    fun collectTargets(): List<Target> {
        val solutions = SolutionService.getInstance(project)
        val runnable = solutions.solutionFiles()
            .flatMap { solutionFile -> solutions.solution(solutionFile).allProjects.mapNotNull { it.resolveFile(solutionFile)?.let { file -> it.name to file } } }
            .distinctBy { it.second }
            .filter { (_, file) -> solutions.msBuildProject(file).let { it.isRunnable && !it.isTestProject } }
        // the startup project, as in Rider: the first executable project in the order of the solution that is not an AppHost
        val startup = runnable.firstOrNull { (_, file) -> !solutions.msBuildProject(file).isAspireHost }?.second
        return runnable
            // an Aspire AppHost starts the whole solution: its configuration goes first in the list, but is not the one selected by default
            .sortedByDescending { (_, file) -> solutions.msBuildProject(file).isAspireHost }
            .flatMap { (name, file) ->
                val profiles = LaunchSettings.profiles(file)
                val isStartup = file == startup
                if (profiles.isEmpty()) listOf(Target(name, file.path, null, preferred = isStartup))
                else profiles.mapIndexed { i, it -> Target("$name: ${it.name}", file.path, it.name, openBrowser = it.launchBrowser, preferred = isStartup && i == 0) } // as the profile asks
            }
    }

    fun register(targets: List<Target>) {
        val runManager = RunManager.getInstance(project)
        val properties = PropertiesComponent.getInstance(project)
        // Every target is generated once: a configuration the user has deleted or reworked does not come back.
        val generated = properties.getList(GENERATED_KEY).orEmpty().toMutableSet()
        val existing = runManager.allConfigurationsList.filterIsInstance<DotNetRunConfiguration>()
            .mapTo(HashSet()) { Target("", it.options.projectPath.orEmpty(), it.options.launchProfile).key }

        val created = ArrayList<Pair<Target, com.intellij.execution.RunnerAndConfigurationSettings>>()
        val previouslyGenerated = generated.toSet()
        for (target in targets) {
            if (!generated.add(target.key) || target.key in existing) continue
            val settings = runManager.createConfiguration(target.name, DotNetConfigurationType.instance.factory)
            (settings.configuration as DotNetRunConfiguration).options.apply {
                projectPath = target.projectPath
                launchProfile = target.launchProfile
                openBrowser = target.openBrowser
            }
            settings.storeInLocalWorkspace()
            runManager.addConfiguration(settings)
            created += target to settings
        }
        // the user's own choice stays: only an empty selection or a configuration this generator made earlier is replaced
        val selected = runManager.selectedConfiguration?.configuration as? DotNetRunConfiguration
        val selectedKey = selected?.let { Target("", it.options.projectPath.orEmpty(), it.options.launchProfile).key }
        if (created.isNotEmpty() && (runManager.selectedConfiguration == null || selectedKey in previouslyGenerated || created.any { it.second.configuration === selected }))
            runManager.selectedConfiguration = (created.firstOrNull { it.first.preferred } ?: created.first()).second
        properties.setList(GENERATED_KEY, generated.toList())
    }

    companion object {
        private const val GENERATED_KEY = "dotnet.generated.run.configurations"

        fun getInstance(project: Project): DotNetRunConfigurationGenerator = project.service()
    }
}

class DotNetRunConfigurationStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) = DotNetRunConfigurationGenerator.getInstance(project).schedule()
}
