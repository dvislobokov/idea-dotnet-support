package io.github.dotnetsupport.roslyn

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * The server starts with the project, not with the first `.cs` opened in an editor: the errors of the whole solution (the Problems tool
 * window) and Go to Class through `workspace/symbol` are wanted before any file is opened, and a solution that is loaded once stays loaded.
 * The platform starts a client for a file that is opened ([RoslynLspIntegrationProvider.fileOpened]); here the same client is started for
 * a folder with a solution or projects in it.
 */
class RoslynStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode && !RoslynLspIntegrationProvider.startInTests) return
        val found = readAction { project.service<RoslynWorkspace>().scan() }
        if (found.solutions.isEmpty() && found.projects.isEmpty()) return
        RoslynLspIntegrationProvider.ensureStarted(project)
    }
}
