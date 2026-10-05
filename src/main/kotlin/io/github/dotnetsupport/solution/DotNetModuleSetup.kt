package io.github.dotnetsupport.solution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
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
        withContext(Dispatchers.EDT) {
            if (project.isDisposed || ModuleManager.getInstance(project).modules.isNotEmpty()) return@withContext
            ApplicationManager.getApplication().runWriteAction { createModule(project, root) }
        }
    }

    private fun createModule(project: Project, root: VirtualFile) {
        val base = project.basePath ?: root.path
        val file = Path.of(base, ".idea", moduleName(root.name) + ".iml")
        val module = ModuleManager.getInstance(project).newModule(file, ModuleTypeManager.getInstance().defaultModuleType.id)
        ModuleRootModificationUtil.updateModel(module) { it.addContentEntry(root) }
        PluginLog.info("Solution", "The folder ${root.path} had no module: made module '${module.name}' with the folder as its content root, so its C# files are indexed")
    }

    companion object {
        /** A module is made only for a project without modules that has a solution or a project file of .NET in its folder. */
        fun needsModule(moduleCount: Int, solutions: Int, projects: Int): Boolean = moduleCount == 0 && (solutions > 0 || projects > 0)

        /** The name of the folder as the name of the module, as the other IDEs do; a name the file system would not take falls back to `dotnet`. */
        fun moduleName(folder: String): String = folder.trim().takeIf { it.isNotEmpty() && it.none { c -> c in "/\\:*?\"<>|" } } ?: "dotnet"
    }
}
