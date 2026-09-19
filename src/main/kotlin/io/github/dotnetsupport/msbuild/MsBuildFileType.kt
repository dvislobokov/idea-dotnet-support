package io.github.dotnetsupport.msbuild

import com.intellij.ide.highlighter.XmlLikeFileType
import com.intellij.lang.xml.XMLLanguage
import io.github.dotnetsupport.DotNetIcons
import javax.swing.Icon

object MsBuildFileType : XmlLikeFileType(XMLLanguage.INSTANCE) {
    override fun getName(): String = "MSBuild"
    override fun getDescription(): String = "MSBuild project file"
    override fun getDefaultExtension(): String = "csproj"
    override fun getIcon(): Icon = DotNetIcons.Project
}

/** Other XML-based .NET files (XAML, resources, configs): XML editing support, icons come from the icon provider. */
object DotNetXmlFileType : XmlLikeFileType(XMLLanguage.INSTANCE) {
    override fun getName(): String = ".NET XML"
    override fun getDescription(): String = ".NET XML file (XAML, resx, config)"
    override fun getDefaultExtension(): String = "xaml"
    override fun getIcon(): Icon = DotNetIcons.Xaml
}

val MSBUILD_EXTENSIONS: Set<String> = setOf("csproj", "fsproj", "vbproj", "shproj", "proj", "props", "targets")
