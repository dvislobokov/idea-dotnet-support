package io.github.dotnetsupport.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.FileViewProvider
import io.github.dotnetsupport.DotNetIcons
import javax.swing.Icon

/**
 * The one C# language of the plugin is csharp-psi-core's (two `Language("C#")` cannot coexist): the heuristic tree and the native one are
 * of the same language, and [CSharpParserDefinition] switches between them.
 */
typealias CSharpLanguage = io.github.dotnetsupport.csharp.lang.CSharpLanguage

/** A C# file of either tree (the heuristic one is [HeuristicCSharpFile]); `compilationUnit` is null on the heuristic tree. */
typealias CSharpFile = io.github.dotnetsupport.csharp.lang.CSharpFile

object CSharpFileType : LanguageFileType(CSharpLanguage) {
    override fun getName(): String = "C#"
    override fun getDisplayName(): String = "C#"
    override fun getDescription(): String = "C# source file"
    override fun getDefaultExtension(): String = "cs"
    override fun getIcon(): Icon = DotNetIcons.CSharp
}

/** The file of the heuristic tree ([HeuristicCSharpParserDefinition]): declaration nodes over flat tokens. */
class HeuristicCSharpFile(viewProvider: FileViewProvider) : CSharpFile(viewProvider, HeuristicCSharpParserDefinition.FILE) {
    override fun toString(): String = "C# File"
}
