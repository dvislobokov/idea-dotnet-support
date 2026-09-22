package io.github.dotnetsupport.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.FileViewProvider
import io.github.dotnetsupport.DotNetIcons
import javax.swing.Icon

object CSharpLanguage : Language("C#")

object CSharpFileType : LanguageFileType(CSharpLanguage) {
    override fun getName(): String = "C#"
    override fun getDisplayName(): String = "C#"
    override fun getDescription(): String = "C# source file"
    override fun getDefaultExtension(): String = "cs"
    override fun getIcon(): Icon = DotNetIcons.CSharp
}

class CSharpFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, CSharpLanguage) {
    override fun getFileType(): FileType = CSharpFileType
    override fun toString(): String = "C# File"
}
