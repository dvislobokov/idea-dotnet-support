package io.github.dotnetsupport.solution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.PluginLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * A folder with a .NET solution or project opened in IntelliJ IDEA gets a module whose content root is the folder, as GoLand, PyCharm or
 * WebStorm make one for any folder they open. IDEA 2026.1 opens a folder without a build system it knows as a project without modules: its
 * files are content that is *not indexed* (`ProjectRootEntity`, `WorkspaceFileKind.CONTENT_NON_INDEXABLE`). Then the stub indexes hold no
 * C# file of the solution (types and members of other files, Go to Class), and the platform runs no annotator, intention or line marker
 * that is not `DumbAware` on those files at all (`DumbService.isUsableInCurrentContext(thing, file)`): the robot in WSL saw no Built-in
 * colors and no Alt+Enter rows of the plugin on a fresh copy of the playground (0.1.63), while the playground with its `.idea` of an older
 * IDEA, which has a module, showed them. A project that has any module is left as it is: its structure is the user's or an importer's.
 */
class DotNetModuleSetup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode || project.isDefault) return
        if (ModuleManager.getInstance(project).modules.isNotEmpty()) return
        val root = project.guessProjectDir() ?: return
        val found = readAction { if (root.isValid) SolutionFinder.find(root, includeProjects = true) else SolutionFinder.Found.EMPTY }
        if (!needsModule(moduleCount = 0, solutions = found.solutions.size, projects = found.projects.size)) return
        if (!makeModule(project, root, again = false)) return
        // The workspace model of a project opened from its cache is synced with `.idea` a few seconds after the start: a module made before
        // that and not saved yet is dropped by the sync (the WSL sandbox, 0.1.260: the module of a Unity project gone 4 s after it was made,
        // after an IDE that was killed, and every index of the plugin empty from then on). The module is saved at once, and made again if
        // the sync still takes it away.
        repeat(WATCH_STEPS) {
            delay(WATCH_STEP_MS)
            if (project.isDisposed) return
            if (ModuleManager.getInstance(project).modules.isEmpty()) makeModule(project, root, again = true)
        }
    }

    private suspend fun makeModule(project: Project, root: VirtualFile, again: Boolean): Boolean = withContext(Dispatchers.EDT) {
        if (project.isDisposed || ModuleManager.getInstance(project).modules.isNotEmpty()) return@withContext false
        ApplicationManager.getApplication().runWriteAction { createModule(project, root, again) }
        SaveAndSyncHandler.getInstance().scheduleProjectSave(project)
        true
    }

    private fun createModule(project: Project, root: VirtualFile, again: Boolean) {
        val base = project.basePath ?: root.path
        val file = Path.of(base, ".idea", moduleName(root.name) + ".iml")
        val module = ModuleManager.getInstance(project).newModule(file, ModuleTypeManager.getInstance().defaultModuleType.id)
        ModuleRootModificationUtil.updateModel(module) { it.addContentEntry(root) }
        if (again) PluginLog.info("Solution", "The project model dropped the module of ${root.path} (synced from .idea before it was saved): made module '${module.name}' again")
        else PluginLog.info("Solution", "The folder ${root.path} had no module: made module '${module.name}' with the folder as its content root, so its C# files are indexed")
    }

    companion object {
        /** How long the module is watched after it was made: the sync of a model from the cache comes within seconds of the start. */
        private const val WATCH_STEPS = 30
        private const val WATCH_STEP_MS = 2000L

        /** A module is made only for a project without modules that has a solution or a project file of .NET in its folder. */
        fun needsModule(moduleCount: Int, solutions: Int, projects: Int): Boolean = moduleCount == 0 && (solutions > 0 || projects > 0)

        /** The name of the folder as the name of the module, as the other IDEs do; a name the file system would not take falls back to `dotnet`. */
        fun moduleName(folder: String): String = folder.trim().takeIf { it.isNotEmpty() && it.none { c -> c in "/\\:*?\"<>|" } } ?: "dotnet"
    }
}
