package io.github.dotnetsupport.solution

import com.intellij.ide.highlighter.XmlLikeFileType
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.DotNetIcons
import javax.swing.Icon

object SolutionFileType : LanguageFileType(PlainTextLanguage.INSTANCE, true) {
    override fun getName(): String = "Visual Studio Solution"
    override fun getDisplayName(): String = "Visual Studio Solution"
    override fun getDescription(): String = "Visual Studio solution"
    override fun getDefaultExtension(): String = "sln"
    override fun getIcon(): Icon = DotNetIcons.Solution
}

object SolutionXmlFileType : XmlLikeFileType(XMLLanguage.INSTANCE) {
    override fun getName(): String = "Visual Studio Solution (XML)"
    override fun getDisplayName(): String = "Visual Studio Solution (XML)"
    override fun getDescription(): String = "Visual Studio solution (XML format)"
    override fun getDefaultExtension(): String = "slnx"
    override fun getIcon(): Icon = DotNetIcons.Solution
}

/** Files `dotnet sln` edits. A filter (`.slnf`) is a solution for `dotnet build` / `test` and for the Solution view, but not for `dotnet sln`. */
val SOLUTION_EXTENSIONS: Set<String> = setOf("sln", "slnx")
const val SOLUTION_FILTER_EXTENSION: String = "slnf"

fun isSolutionFilterFile(file: VirtualFile): Boolean = !file.isDirectory && file.extension.equals(SOLUTION_FILTER_EXTENSION, ignoreCase = true)

/** A solution or a solution filter: what can be built, tested and shown as a solution. */
fun isSolutionOrFilterFile(file: VirtualFile): Boolean =
    !file.isDirectory && (file.extension?.lowercase() in SOLUTION_EXTENSIONS || isSolutionFilterFile(file))
