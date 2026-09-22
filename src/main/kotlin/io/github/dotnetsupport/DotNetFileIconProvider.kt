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
