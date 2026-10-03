package io.github.dotnetsupport.aspire

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.dotnetsupport.solution.SolutionService

/** Aspire AppHost projects, see [io.github.dotnetsupport.msbuild.MsBuildProject.isAspireHost]. */
object AspireHosts {
    /** Whether the project file at [projectPath] is an AppHost; any thread. */
    fun isAppHost(project: Project, projectPath: String?): Boolean {
        val file = projectPath?.takeIf { it.isNotBlank() }?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return false
        return !project.isDisposed && SolutionService.getInstance(project).msBuildProject(file).isAspireHost
    }
}

/**
 * The login link of the Aspire dashboard, with the browser token in it. The AppHost logs it in English (`Login to the dashboard at
 * https://localhost:17149/login?t=…`, and again under `Login URL:`); the Aspire CLI that `dotnet run` of an Aspire 13 AppHost goes through
 * prints a localized label and the link as an OSC 8 hyperlink (the address twice, between escape sequences). Only the shape of the
 * link is matched. The token opens the dashboard without a login: it is kept on the process handler only, never logged.
 */
object AspireDashboard {
    /** The login link of a run, on its process handler: for "Open Dashboard" in the Services tool window. */
    val KEY: Key<String> = Key.create("dotnet.aspire.dashboard.url")

    private val LOGIN_URL = Regex("""https?://[^\s"'<>\\\u001b\u0007]+/login\?t=[A-Za-z0-9]+""")

    /** The link itself is clickable in the console without a filter of the plugin: the URL filter of the platform finds it in both outputs (seen live). */
    fun loginUrl(text: String): String? = LOGIN_URL.find(text)?.value
}
