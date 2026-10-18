package io.github.dotnetsupport.frameworks.razor

import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * Go to Related Symbol (Ctrl+Alt+Home) between the files of a component or a page (RAZOR_PLAN.md, R9), as in Rider: `Counter.razor` ↔
 * `Counter.razor.cs` (code-behind), `.razor.css` (scoped styles), `.razor.js` (collocated JavaScript); `Index.cshtml` ↔ `Index.cshtml.cs`
 * (the page model), `.cshtml.css`, `.cshtml.js`.
 */
object RazorRelatedFiles {
    private val MARKUP = listOf(".razor", ".cshtml")
    private val SUFFIXES = listOf(".cs", ".css", ".scss", ".js")

    /** The files of the same component / page as [file], in the order of [SUFFIXES], the markup first; [file] itself left out. */
    fun of(file: VirtualFile): List<VirtualFile> {
        val folder = file.parent ?: return emptyList()
        val name = file.name
        val markup = MARKUP.firstNotNullOfOrNull { extension ->
            when {
                name.endsWith(extension, ignoreCase = true) -> name
                else -> SUFFIXES.firstNotNullOfOrNull { suffix -> name.takeIf { it.endsWith(extension + suffix, ignoreCase = true) }?.dropLast(suffix.length) }
            }
        } ?: return emptyList()
        return (listOf(markup) + SUFFIXES.map { markup + it })
            .filter { !it.equals(name, ignoreCase = true) }
            .mapNotNull { folder.findChild(it) }
    }
}

class RazorGotoRelatedProvider : GotoRelatedProvider() {
    override fun getItems(context: PsiElement): List<GotoRelatedItem> {
        val file = context.containingFile?.originalFile?.viewProvider?.virtualFile ?: return emptyList()
        val psi = PsiManager.getInstance(context.project)
        return RazorRelatedFiles.of(file).mapNotNull { psi.findFile(it) }.map { GotoRelatedItem(it, "Razor") }
    }
}
