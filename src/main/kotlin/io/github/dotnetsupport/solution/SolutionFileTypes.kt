package io.github.dotnetsupport.solution

import com.intellij.ide.highlighter.XmlLikeFileType
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import io.github.dotnetsupport.DotNetIcons
import javax.swing.Icon

object SolutionFileType : LanguageFileType(PlainTextLanguage.INSTANCE, true) {
    override fun getName(): String = "Visual Studio Solution"
    override fun getDescription(): String = "Visual Studio solution"
    override fun getDefaultExtension(): String = "sln"
    override fun getIcon(): Icon = DotNetIcons.Solution
}

object SolutionXmlFileType : XmlLikeFileType(XMLLanguage.INSTANCE) {
    override fun getName(): String = "Visual Studio Solution (XML)"
    override fun getDescription(): String = "Visual Studio solution (XML format)"
    override fun getDefaultExtension(): String = "slnx"
    override fun getIcon(): Icon = DotNetIcons.Solution
}

val SOLUTION_EXTENSIONS: Set<String> = setOf("sln", "slnx")
