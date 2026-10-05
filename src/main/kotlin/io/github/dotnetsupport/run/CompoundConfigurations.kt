package io.github.dotnetsupport.run

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.compound.CompoundRunConfiguration
import com.intellij.execution.compound.CompoundRunConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.dotnetsupport.cli.DotNetCli
import java.io.File

/**
 * Compound configurations of ".NET Project" ones, as Rider saves "Run Multiple Projects": the platform's compound type (there is one in
 * every IDE), named after the projects. Its configurations go to a folder of the same name when they are in none, so the Services tool
 * window shows the launches of the compound as one group (grouping by folder), where Stop and Rerun of the group act on all of them.
 */
object CompoundConfigurations {
    const val MULTIPLE_PROJECTS = "Multiple Projects"

    /** `Web + Worker` for up to three projects, else Rider's "Multiple Projects"; ` (2)` and on when the name is taken. */
    fun name(projectNames: List<String>, taken: Set<String>): String {
        val base = if (projectNames.size in 1..3) projectNames.joinToString(" + ") else MULTIPLE_PROJECTS
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base ($it)" }.first { it !in taken }
    }

    fun projectName(settings: RunnerAndConfigurationSettings): String =
        (settings.configuration as? DotNetRunConfiguration)?.options?.projectPath?.let { File(it).nameWithoutExtension }?.ifEmpty { null } ?: settings.name

    /** A saved compound of exactly these configurations. */
    fun find(project: Project, members: List<RunnerAndConfigurationSettings>): RunnerAndConfigurationSettings? {
        val runManager = RunManagerImpl.getInstanceImpl(project)
        val wanted = members.map { it.configuration }.toSet()
        return runManager.allSettings.firstOrNull { settings ->
            val compound = settings.configuration as? CompoundRunConfiguration ?: return@firstOrNull false
            !settings.isTemporary && compound.getConfigurationsWithTargets(runManager).keys == wanted
        }
    }

    /** Saves [members] (the ones not registered yet, or temporary, become permanent) and a compound of them; an existing one is reused. */
    fun save(project: Project, members: List<RunnerAndConfigurationSettings>): RunnerAndConfigurationSettings {
        val runManager = RunManagerImpl.getInstanceImpl(project)
        members.forEach { settings ->
            when {
                runManager.allSettings.none { it === settings } -> runManager.addConfiguration(settings)
                settings.isTemporary -> runManager.makeStable(settings)
            }
        }
        find(project, members)?.let { return it.also { runManager.selectedConfiguration = it } }

        val name = name(members.map(::projectName), runManager.allSettings.map { it.name }.toSet())
        val type = ConfigurationTypeUtil.findConfigurationType(CompoundRunConfigurationType::class.java)
        val settings = runManager.createConfiguration(name, type.configurationFactories.first())
        (settings.configuration as CompoundRunConfiguration).setConfigurationsWithoutTargets(members.map { it.configuration })
        runManager.addConfiguration(settings)
        members.filter { it.folderName.isNullOrEmpty() }.forEach {
            it.folderName = name
            runManager.fireRunConfigurationChanged(it)
        }
        runManager.selectedConfiguration = settings
        return settings
    }

    /** After Run / Debug N Projects: the same launch is one click away next time. Nothing when a compound of them is saved already. */
    fun offerToSave(project: Project, members: List<RunnerAndConfigurationSettings>) {
        if (members.size < 2 || find(project, members) != null) return
        val names = members.joinToString(", ") { projectName(it) }
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification("Started $names", "Save them as a compound run configuration to start them together from the toolbar.", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Save as Compound Configuration") { saved(project, save(project, members)) })
            .notify(project)
    }

    fun saved(project: Project, compound: RunnerAndConfigurationSettings) =
        DotNetCli.notifyInfo(project, "Compound Configuration '${compound.name}' Saved", "It is selected in the toolbar; Run | Edit Configurations shows what it starts.")
}

/**
 * Save as Compound Configuration: the projects selected in the Solution view, or else the ".NET Project" configurations running now
 * (started one by one, to be started together from now on). In the popup of the Solution view only for a selection of several projects.
 */
class SaveAsCompoundAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val selected = project?.let { RunProjectTarget.allSelected(it, e) }.orEmpty()
        if (e.isFromContextMenu) {
            e.presentation.isEnabledAndVisible = selected.size > 1
            return
        }
        e.presentation.isVisible = true
        e.presentation.isEnabled = project != null && (selected.size > 1 || RunningLaunches.getInstance(project).runningDotNet().size > 1)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = RunProjectTarget.allSelected(project, e)
        val members = if (selected.size > 1) selected.map { it.settings(project) } else RunningLaunches.getInstance(project).runningDotNet()
        if (members.size < 2) return
        CompoundConfigurations.saved(project, CompoundConfigurations.save(project, members.distinctBy { it.uniqueID }))
    }
}


/**
 * Debug of a compound configuration. The platform runs a compound with any runner that takes it, and for Debug there is none in an IDE
 * without a debugger of its own languages (IntelliJ IDEA Community has Java's, which takes only Java profiles): the compound of .NET
 * Project configurations was not debuggable at all. The state of the compound starts each of its configurations with the executor.
 */
class DotNetCompoundDebugRunner : com.intellij.execution.runners.ProgramRunner<com.intellij.execution.configurations.RunnerSettings> {
    override fun getRunnerId(): String = "DotNetCompoundDebugRunner"

    override fun canRun(executorId: String, profile: com.intellij.execution.configurations.RunProfile): Boolean {
        if (executorId != com.intellij.execution.executors.DefaultDebugExecutor.EXECUTOR_ID || profile !is CompoundRunConfiguration) return false
        val members = profile.getConfigurationsWithTargets(RunManagerImpl.getInstanceImpl(profile.project)).keys
        return members.any { it is DotNetRunConfiguration } && members.all { com.intellij.execution.runners.ProgramRunner.getRunner(executorId, it) != null }
    }

    override fun execute(environment: com.intellij.execution.runners.ExecutionEnvironment) {
        environment.state?.execute(environment.executor, this)
    }
}
