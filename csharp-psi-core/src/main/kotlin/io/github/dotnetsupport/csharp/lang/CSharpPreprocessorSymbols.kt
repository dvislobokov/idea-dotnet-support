package io.github.dotnetsupport.csharp.lang

import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightVirtualFile

/**
 * Preprocessor symbols a C# file is lexed with (`#if` evaluation, Roslyn's `CSharpParseOptions.PreprocessorSymbols`).
 * Until the project model supplies `DefineConstants` per project and target framework, a file takes [KEY] from its
 * `PsiFile`, its `VirtualFile` or the files it is a copy of, else [IDE_DEFAULT] ([forFile]). Gates and tests that
 * compare with `roslyndump` (no `--define`) put an empty set under [KEY] or lex with `CSharpLexer()`, whose default is
 * empty. Changing the symbols of an open file does not reparse it by itself. There is no syntax highlighter yet: one
 * must take the same symbols (`EditorHighlighterProvider` with the file at hand), as [CSharpParserDefinition.createLexer]
 * cannot.
 */
object CSharpPreprocessorSymbols {
    @JvmField val KEY: Key<Set<String>> = Key.create("csharp.preprocessorSymbols")

    /**
     * What the .NET SDK defines for a `Debug` build of `net10.0` (`DEBUG;TRACE` from the configuration, the rest from
     * the target framework: `NET`, `NETCOREAPP`, `NET10_0` and every `NETx_OR_GREATER` / `NETCOREAPPx_OR_GREATER`).
     */
    @JvmField val IDE_DEFAULT: Set<String> = linkedSetOf(
        "DEBUG", "TRACE",
        "NET", "NETCOREAPP", "NET10_0",
        "NET10_0_OR_GREATER", "NET9_0_OR_GREATER", "NET8_0_OR_GREATER", "NET7_0_OR_GREATER", "NET6_0_OR_GREATER",
        "NET5_0_OR_GREATER",
        "NETCOREAPP3_1_OR_GREATER", "NETCOREAPP3_0_OR_GREATER", "NETCOREAPP2_2_OR_GREATER", "NETCOREAPP2_1_OR_GREATER",
        "NETCOREAPP2_0_OR_GREATER", "NETCOREAPP1_1_OR_GREATER", "NETCOREAPP1_0_OR_GREATER",
    )

    /**
     * The symbols of [file]: [KEY] on the file or its virtual file, then on the file it is a copy of, else
     * [IDE_DEFAULT]. Copies matter: a reparse after an edit (`BlockSupportImpl.makeFullParse`) parses a copy whose
     * virtual file is a new `LightVirtualFile` (its `originalFile` is the original virtual file) and whose
     * [PsiFile.getOriginalFile] is the original `PsiFile`; `PsiFile.copy()` (completion, refactoring previews) also
     * sets [PsiFile.getOriginalFile].
     */
    @JvmStatic
    fun forFile(file: PsiFile?): Set<String> {
        var psi = file
        val seen = HashSet<Any>()
        while (psi != null && seen.add(psi)) {
            psi.getUserData(KEY)?.let { return it }
            // the file being indexed: its PSI is over a light copy of the content, `getVirtualFile` is the indexed file (stubs, step 8)
            psi.virtualFile?.getUserData(KEY)?.let { return it }
            var virtualFile: VirtualFile? = psi.viewProvider.virtualFile
            while (virtualFile != null && seen.add(virtualFile)) {
                virtualFile.getUserData(KEY)?.let { return it }
                virtualFile = (virtualFile as? LightVirtualFile)?.originalFile
            }
            psi = psi.originalFile
        }
        return IDE_DEFAULT
    }
}
