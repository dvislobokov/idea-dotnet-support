package io.github.dotnetsupport.run

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile

/**
 * When a solution with an ASP.NET Core web project is opened, checks the HTTPS development certificate and offers to trust it, so that the
 * first `https://` run does not greet the browser with a certificate warning (`dotnet dev-certs https`, as Visual Studio prompts on Windows).
 */
class DevCertCheckActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(DISMISS_KEY) || DotNetCli.findExecutable() == null) return

        val solutions = SolutionService.getInstance(project)
        if (solutions.solutionFiles().isEmpty()) return
        val hasWebProject = ReadAction.compute<Boolean, RuntimeException> {
            solutions.solutionFiles().any { sln ->
                solutions.solution(sln).allProjects.mapNotNull { it.resolveFile(sln) }.any { solutions.msBuildProject(it).isWebSdk }
            }
        }
        if (!hasWebProject) return

        // `--check --trust`: exit code 0 means a valid certificate exists and is trusted; anything else means it is missing or not trusted
        val workDirectory = project.guessProjectDir()?.path
        val check = runCatching { DotNetCli.execute(DotNetCli.commandLine(workDirectory, "dev-certs", "https", "--check", "--trust"), 30_000) }.getOrNull() ?: return
        if (check.exitCode == 0) return

        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification(
                "ASP.NET Core development certificate is not trusted",
                "HTTPS endpoints will warn in the browser until the certificate is trusted.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple("Trust") { trust(project, workDirectory) })
            .addAction(NotificationAction.createSimple("Don't ask again") { properties.setValue(DISMISS_KEY, true) })
            .notify(project)
    }

    /** `dotnet dev-certs https --trust`: on Windows and macOS this asks the OS to trust the certificate (a system prompt may appear). */
    private fun trust(project: Project, workDirectory: String?) {
        val title = "Trusting the ASP.NET Core development certificate"
        val commands = DotNetCli.commandLinesOrNotify(project, title) { listOf(DotNetCli.commandLine(workDirectory, "dev-certs", "https", "--trust")) } ?: return
        DotNetCli.runInBackground(project, title, commands)
    }

    private companion object {
        const val DISMISS_KEY = "io.github.dotnetsupport.devcert.dismissed"
    }
}
