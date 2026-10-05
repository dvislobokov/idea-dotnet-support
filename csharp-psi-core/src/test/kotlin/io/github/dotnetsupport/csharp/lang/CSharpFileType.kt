package io.github.dotnetsupport.csharp.lang

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/** The file type of this module's tests (src/test/resources/META-INF/plugin.xml); in the plugin `.cs` is the host's `CSharpFileType`. */
object CSharpFileType : LanguageFileType(CSharpLanguage) {
    override fun getName(): String = "C#"
    override fun getDescription(): String = "C# source file"
    override fun getDefaultExtension(): String = "cs"
    override fun getIcon(): Icon = AllIcons.FileTypes.Text
}
