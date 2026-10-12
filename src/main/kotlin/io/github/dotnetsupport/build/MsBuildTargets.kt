package io.github.dotnetsupport.build

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.msbuild.DotNetProjects
import javax.swing.JList

/** A target of a project. [isOwn]: declared in the project file or in a `Directory.Build.*` of the repository, not in the SDK. */
class MsBuildTarget(val name: String, val isOwn: Boolean) {
    /** `_CheckForInvalidConfiguration`: an implementation detail of the SDK by convention. */
    val isInternal: Boolean get() = name.startsWith("_")
}

object MsBuildTargets {
    private val TARGET_DECLARATION = Regex("""<Target\s+[^>]*\bName\s*=\s*"([^"]+)"""")

    fun declaredIn(msBuildFile: CharSequence): List<String> = TARGET_DECLARATION.findAll(msBuildFile).map { it.groupValues[1] }.toList()

    /**
     * [cliOutput] is what `dotnet msbuild -targets` prints: one name per line, about five hundred for an SDK project.
     * Own targets come first, SDK internals last; the order inside each group is the one of MSBuild.
     */
    fun arrange(cliOutput: String, ownTargets: Collection<String>): List<MsBuildTarget> {
        val own = ownTargets.map { it.lowercase() }.toSet()
        return cliOutput.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '.' || c == '-' } }
            .distinct()
            .map { MsBuildTarget(it, it.lowercase() in own) }
            .sortedWith(compareBy({ !it.isOwn }, { it.isInternal }))
            .toList()
    }

    /** Targets written in the project itself and in the `Directory.Build.props` / `.targets` files above it. */
    fun ownTargets(projectFile: VirtualFile): List<String> {
        val files = ArrayList<VirtualFile>()
        files += projectFile
        var directory = projectFile.parent
        while (directory != null) {
            listOf("Directory.Build.props", "Directory.Build.targets").mapNotNullTo(files) { directory!!.findChild(it) }
            directory = directory.parent
        }
        return files.flatMap { file -> runCatching { declaredIn(VfsUtilCore.loadText(file)) }.getOrDefault(emptyList()) }.distinct()
    }
}

/** "Run MSBuild Target...": a searchable list of the targets of the selected project, like the Gradle tasks of IDEA. */
class RunMsBuildTargetAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = projectFile(e) != null
    }

    private fun projectFile(e: AnActionEvent): VirtualFile? = SolutionContext.buildTarget(e)?.takeIf(DotNetProjects::isProjectFile)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectFile = projectFile(e) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val targets = try {
                val output = DotNetCli.execute(DotNetCli.commandLine(projectFile.parent.path, "msbuild", projectFile.path, "-targets", "-nologo"), 120_000)
                MsBuildTargets.arrange(output.stdout, MsBuildTargets.ownTargets(projectFile))
            } catch (e: Exception) {
                DotNetCli.notifyError(project, "MSBuild targets of ${projectFile.name}", e.message.orEmpty())
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater({ choose(project, projectFile, targets) }, ModalityState.any())
        }
    }

    private fun choose(project: Project, projectFile: VirtualFile, targets: List<MsBuildTarget>) {
        if (project.isDisposed) return
        JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
            .setTitle("Run MSBuild Target of ${projectFile.nameWithoutExtension}")
            .setNamerForFiltering { it.name }
            .setRenderer(object : ColoredListCellRenderer<MsBuildTarget>() {
                override fun customizeCellRenderer(list: JList<out MsBuildTarget>, target: MsBuildTarget, index: Int, selected: Boolean, hasFocus: Boolean) {
                    icon = if (target.isOwn) AllIcons.Nodes.Target else AllIcons.Nodes.EmptyNode
                    append(target.name, if (target.isInternal) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (target.isOwn) append("  declared in the project", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            })
            .setItemChosenCallback { run(project, projectFile, it.name) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun run(project: Project, projectFile: VirtualFile, target: String) {
        val configuration = DotNetBuildSettings.getInstance(project).configuration
        val arguments = arrayOf("msbuild", projectFile.path, "-t:$target", "-p:Configuration=$configuration", "-nologo", "-clp:NoSummary")
        DotNetBuildService.getInstance(project).run(projectFile, "$target of ${projectFile.name}", arguments) { run(project, projectFile, target) }
    }
}
