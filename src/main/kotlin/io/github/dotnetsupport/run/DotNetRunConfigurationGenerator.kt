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
    data class Target(val name: String, val projectPath: String, val launchProfile: String?, val openBrowser: Boolean = false) {
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
        return solutions.solutionFiles()
            .flatMap { solutionFile -> solutions.solution(solutionFile).allProjects.mapNotNull { it.resolveFile(solutionFile)?.let { file -> it.name to file } } }
            .distinctBy { it.second }
            .filter { (_, file) -> solutions.msBuildProject(file).let { it.isRunnable && !it.isTestProject } }
            .flatMap { (name, file) ->
                val profiles = LaunchSettings.profiles(file)
                if (profiles.isEmpty()) listOf(Target(name, file.path, null))
                else profiles.map { Target("$name: ${it.name}", file.path, it.name, openBrowser = it.launchBrowser) } // as the profile asks
            }
    }

    fun register(targets: List<Target>) {
        val runManager = RunManager.getInstance(project)
        val properties = PropertiesComponent.getInstance(project)
        // Every target is generated once: a configuration the user has deleted or reworked does not come back.
        val generated = properties.getList(GENERATED_KEY).orEmpty().toMutableSet()
        val existing = runManager.allConfigurationsList.filterIsInstance<DotNetRunConfiguration>()
            .mapTo(HashSet()) { Target("", it.options.projectPath.orEmpty(), it.options.launchProfile).key }

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
            if (runManager.selectedConfiguration == null) runManager.selectedConfiguration = settings
        }
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
