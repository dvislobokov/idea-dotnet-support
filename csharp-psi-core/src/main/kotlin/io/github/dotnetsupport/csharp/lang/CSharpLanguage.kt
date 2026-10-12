package io.github.dotnetsupport.csharp.lang

import com.intellij.lang.Language

/**
 * Language id "C#", the only C# language of the plugin: the host's `io.github.dotnetsupport.lang.CSharpLanguage` is an alias of it, so
 * the heuristic tree and this module's tree are of one language. The file type is the host's (`io.github.dotnetsupport.lang.CSharpFileType`);
 * this module's tests register their own (src/test).
 */
object CSharpLanguage : Language("C#") {
    private fun readResolve(): Any = CSharpLanguage
    override fun getDisplayName(): String = "C#"
}
