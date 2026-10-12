package io.github.dotnetsupport.publish

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.build.DotNetBuildService
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile

/** Runs `dotnet publish` the way a build runs: the Build tool window, its parser of MSBuild diagnostics, Rerun and Stop. */
object DotNetPublisher {
    /** Called on the EDT (the dialog, a run configuration): the documents are saved here, the process starts on a pooled thread. */
    fun publish(project: Project, options: PublishOptions, onFinished: (Boolean) -> Unit = {}) {
        val target = LocalFileSystem.getInstance().findFileByPath(options.projectPath)
        if (target == null) {
            DotNetCli.notifyError(project, "Publish", "Project file not found: ${options.projectPath}")
            onFinished(false)
            return
        }
        WriteIntentReadAction.run { FileDocumentManager.getInstance().saveAllDocuments() }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            DotNetBuildService.getInstance(project).run(
                target, "Publish ${options.projectName}", PublishCommand.arguments(options).toTypedArray(), saveDocuments = false,
                onFinished = { succeeded ->
                    notifyFinished(project, options, succeeded)
                    onFinished(succeeded)
                },
            ) { publish(project, options) }
        }
    }

    private fun notifyFinished(project: Project, options: PublishOptions, succeeded: Boolean) {
        if (project.isDisposed) return
        if (!succeeded) {
            DotNetCli.notifyError(project, "Publish of '${options.projectName}' failed", "The errors are in the Build tool window")
            return
        }
        val folder = PublishCommand.outputDirectory(options)
        val image = if (options.container) "The container image is in the local registry. " else ""
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification("Published '${options.projectName}'", "$image${folder.path}", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(RevealFileAction.getActionName()) { RevealFileAction.openDirectory(folder) })
            .notify(project)
    }

    /** The projects worth publishing: applications of the solutions, test projects aside. */
    fun publishableProjects(project: Project): List<VirtualFile> {
        val solutions = SolutionService.getInstance(project)
        return solutions.solutionFiles().flatMap { file -> solutions.solution(file).allProjects.mapNotNull { it.resolveFile(file) } }.distinct()
            .filter { isPublishable(solutions.msBuildProject(it)) }
    }

    fun isPublishable(msBuildProject: MsBuildProject): Boolean = msBuildProject.isRunnable && !msBuildProject.isTestProject
}

/** The last choices of the Publish dialog, per project. In the workspace file: folders and runtimes are personal. */
@Service(Service.Level.PROJECT)
@State(name = "DotNetPublishSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PublishSettings : SimplePersistentStateComponent<PublishSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        /** Project path -> the properties of [PublishOptions.toProperties] as a JSON object. */
        var projects by map<String, String>()
        var saveAsRunConfiguration by property(false)
    }

    fun last(projectPath: String): PublishOptions? {
        val json = state.projects[projectPath] ?: return null
        val properties = try {
            JsonParser.parseString(json).asJsonObject.entrySet().associate { it.key to it.value.asString }
        } catch (_: Exception) {
            return null
        }
        return PublishOptions.fromProperties(projectPath, properties)
    }

    fun remember(options: PublishOptions) {
        state.projects = state.projects.toMutableMap().apply { put(options.projectPath, Gson().toJson(options.toProperties())) }
    }

    companion object {
        fun getInstance(project: Project): PublishSettings = project.service()
    }
}
