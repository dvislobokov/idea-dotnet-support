package io.github.dotnetsupport

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/** Icons for .NET files, including the ones that have no dedicated file type (F#, Razor, appsettings.json, ...). */
class DotNetFileIconProvider : FileIconProvider, DumbAware {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (file.isDirectory) null else DotNetIcons.forFile(file.name)
}

/** The `Properties` folder of a project has an icon of its own, as in Rider: it holds launchSettings.json and AssemblyInfo.cs, not code. */
class DotNetDirectoryIconProvider : com.intellij.ide.IconProvider(), DumbAware {
    override fun getIcon(element: com.intellij.psi.PsiElement, flags: Int): Icon? {
        val directory = (element as? com.intellij.psi.PsiDirectory)?.virtualFile ?: return null
        return if (isPropertiesFolder(directory)) DotNetIcons.PropertiesFolder else null
    }

    companion object {
        private val NAMES = setOf("Properties", "My Project")
        private val PROJECT_EXTENSIONS = setOf("csproj", "vbproj", "fsproj", "shproj")

        /** Named so and right in the folder of a project file. */
        fun isPropertiesFolder(directory: VirtualFile): Boolean =
            directory.isDirectory && directory.name in NAMES &&
                directory.parent?.children.orEmpty().any { !it.isDirectory && it.extension?.lowercase() in PROJECT_EXTENSIONS }
    }
}
