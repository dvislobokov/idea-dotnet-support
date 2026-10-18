package io.github.dotnetsupport.lang.semantic

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.SyntaxTraverser
import io.github.dotnetsupport.lang.CSharpFile

/**
 * C# injected into the files of other languages (the document of C# of a Razor file's markup, RAZOR_PLAN.md R3): the search of usages reads
 * such a document as a file of its own. Only what the host writes is a usage there: the glue between the pieces (the class around them, the
 * `nameof(…)` of a component's parameter) is text of nobody's.
 */
object CSharpInjections {
    /** The C# documents injected into [file] (every file of its view: the markup of a template language hosts them too). */
    fun injectedFiles(file: PsiFile): List<CSharpFile> {
        val manager = InjectedLanguageManager.getInstance(file.project)
        val found = LinkedHashSet<CSharpFile>()
        for (root in file.viewProvider.allFiles) {
            for (host in SyntaxTraverser.psiTraverser(root).filter(PsiLanguageInjectionHost::class.java)) {
                manager.enumerate(host) { injected, _ -> (injected as? CSharpFile)?.takeIf { it.compilationUnit != null }?.let(found::add) }
            }
        }
        return found.toList()
    }

    /** The file of the editor [file] is shown in: the host of an injected one, else itself. */
    fun hostFile(file: PsiFile?): PsiFile? {
        file ?: return null
        val manager = InjectedLanguageManager.getInstance(file.project)
        return if (manager.isInjectedFragment(file)) manager.getTopLevelFile(file) else file
    }

    /** [range] of [file] is text its host writes: always in a file of its own, in an injected one inside a piece. */
    fun isWritten(file: PsiFile?, range: TextRange): Boolean {
        file ?: return false
        val manager = InjectedLanguageManager.getInstance(file.project)
        if (!manager.isInjectedFragment(file)) return true
        return manager.intersectWithAllEditableFragments(file, range).singleOrNull() == range
    }

    /**
     * The use scope of a type or member of injected C#: the project, not its document — the platform gives an element of an injected file
     * that file alone, and Find Usages of a parameter of a component's `@code` would miss the markup of the other files.
     */
    class UseScopeEnlarger : com.intellij.psi.search.UseScopeEnlarger() {
        override fun getAdditionalUseScope(element: PsiElement): com.intellij.psi.search.SearchScope? {
            val file = element.containingFile as? CSharpFile ?: return null
            if (element !is io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration && element !is io.github.dotnetsupport.csharp.lang.psi.CSharpVariableDeclarator) return null
            if (!InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)) return null
            return com.intellij.psi.search.GlobalSearchScope.projectScope(file.project)
        }
    }

    /** [range] of [file] in the text of its host file ([hostFile]). */
    fun toHost(file: PsiFile, range: TextRange): TextRange {
        val manager = InjectedLanguageManager.getInstance(file.project)
        return if (manager.isInjectedFragment(file)) manager.injectedToHost(file, range) else range
    }
}
