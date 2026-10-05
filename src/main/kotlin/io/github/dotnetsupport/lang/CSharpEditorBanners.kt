package io.github.dotnetsupport.lang

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import io.github.dotnetsupport.build.DotNetBuildCommand
import io.github.dotnetsupport.build.DotNetBuildService
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.msbuild.MsBuildEvaluation
import io.github.dotnetsupport.solution.SolutionService
import java.util.function.Function
import javax.swing.JComponent

/**
 * What a C# file in the editor should say about itself, as Rider does above a file that is not what the developer thinks it is:
 * the language server is not installed, the file belongs to no project of the folder, the project file excludes it, the packages
 * of its project are not restored. One banner at a time, the most important first; each kind can be hidden for the project.
 */
class CSharpEditorBanners : EditorNotificationProvider {
    enum class Kind(val key: String) {
        NO_SERVER("dotnet.banner.noServer"), NO_PROJECT("dotnet.banner.noProject"), EXCLUDED("dotnet.banner.excluded"), NOT_RESTORED("dotnet.banner.notRestored")
    }

    class Banner(val kind: Kind, val text: String, val projectFile: VirtualFile? = null)

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val banner = bannerFor(project, file) ?: return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text = banner.text
                when (banner.kind) {
                    Kind.NO_SERVER -> {
                        createActionLabel("Install") { DotNetTool.ROSLYN_LANGUAGE_SERVER.install(project) { EditorNotifications.getInstance(project).updateAllNotifications() } }
                        createActionLabel("Settings...") { ShowSettingsUtil.getInstance().showSettingsDialog(project, "Language Server") }
                    }
                    Kind.NOT_RESTORED -> banner.projectFile?.let { projectFile ->
                        createActionLabel("Restore") { DotNetBuildService.getInstance(project).run(projectFile, DotNetBuildCommand.RESTORE) { EditorNotifications.getInstance(project).updateAllNotifications() } }
                    }
                    else -> Unit
                }
                createActionLabel("Don't Show Again") {
                    PropertiesComponent.getInstance(project).setValue(banner.kind.key, true)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
            }
        }
    }

    companion object {
        /** The banner of the file, or null. Read access; nothing is asked of a process. */
        fun bannerFor(project: Project, file: VirtualFile): Banner? {
            if (!isCSharpSourceFile(file)) return null
            val root = project.guessProjectDir() ?: return null
            if (!VfsUtilCore.isAncestor(root, file, true)) return null
            val hidden = PropertiesComponent.getInstance(project)
            fun shown(kind: Kind) = !hidden.getBoolean(kind.key)

            if (shown(Kind.NO_SERVER) && RoslynLanguageServerSettings.getInstance().state.enabled && DotNetTool.ROSLYN_LANGUAGE_SERVER.find() == null) {
                return Banner(Kind.NO_SERVER, "The C# language server (roslyn-language-server) is not installed: no errors, completion or navigation from the compiler.")
            }
            val projectFile = DotNetProjects.findOwningProject(file)
            if (projectFile == null) {
                return if (shown(Kind.NO_PROJECT)) Banner(Kind.NO_PROJECT, "This file belongs to no project of the folder: it is not built, and the language server does not analyze it.") else null
            }
            val relative = projectFile.parent?.let { VfsUtilCore.getRelativePath(file, it, '/') }
            val content = if (relative != null && shown(Kind.EXCLUDED)) MsBuildEvaluation.getInstance(project).content(projectFile) else null
            if (relative != null && content != null && content.isExcluded(relative)) {
                return Banner(Kind.EXCLUDED, if (content.isEvaluated) "Not a part of ${projectFile.name}: the project file does not list it, it is not compiled."
                    else "Excluded from ${projectFile.name} by the project file (Compile Remove or DefaultItemExcludes): not compiled.", projectFile)
            }
            // a project of the old format has no project.assets.json (packages.config goes to ..\packages): the banner was there for good
            if (shown(Kind.NOT_RESTORED) && projectFile.parent?.findFileByRelativePath("obj/project.assets.json") == null &&
                !SolutionService.getInstance(project).msBuildProject(projectFile).isLegacy) {
                return Banner(Kind.NOT_RESTORED, "The packages of ${projectFile.name} are not restored: references are unresolved until they are.", projectFile)
            }
            return null
        }

        private fun isCSharpSourceFile(file: VirtualFile): Boolean = !file.isDirectory && file.extension.equals("cs", ignoreCase = true)
    }
}
