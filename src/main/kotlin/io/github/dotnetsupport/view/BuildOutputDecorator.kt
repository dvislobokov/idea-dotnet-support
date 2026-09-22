package io.github.dotnetsupport.view

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.msbuild.DotNetProjects

/**
 * What "Show All Files" brings into the Solution view is not a part of the project: `bin`, `obj` with everything in them, and
 * the project file the node already stands for. Painted the way the IDE paints files ignored by the VCS, so that they read as
 * secondary, as in Rider; it does not depend on a `.gitignore` or on having a VCS at all.
 */
class BuildOutputDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project ?: return
        // the nodes of a project and of a solution are those files too, and they are the project
        if (node is SolutionFileNode<*> || ProjectView.getInstance(project).currentViewId != SolutionViewPane.ID) return
        val file = node.virtualFile ?: return
        if (isOutsideOfProject(file)) data.forcedTextForeground = FileStatus.IGNORED.color ?: UIUtil.getInactiveTextColor()
    }

    companion object {
        private val OUTPUT_DIRECTORIES = setOf("bin", "obj")

        /** `bin` or `obj` next to a project file, anything inside them, or a project file itself. */
        fun isOutsideOfProject(file: VirtualFile): Boolean {
            if (DotNetProjects.isProjectFile(file)) return true
            return generateSequence(file) { it.parent }.any { directory ->
                directory.isDirectory && directory.name.lowercase() in OUTPUT_DIRECTORIES && directory.parent?.children?.any(DotNetProjects::isProjectFile) == true
            }
        }
    }
}
