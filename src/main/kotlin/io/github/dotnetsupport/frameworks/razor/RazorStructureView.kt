package io.github.dotnetsupport.frameworks.razor

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.lang.CSharpIcons
import io.github.dotnetsupport.lang.CSharpSyntaxModel
import io.github.dotnetsupport.lang.DeclarationKind
import javax.swing.Icon

/**
 * Structure of a Razor file: its directives that say what the component or the view is (`@page`, `@inject`, `@inherits`, `@model`...),
 * the sections, and the members of `@code` / `@functions` — the C# of those blocks parsed as the body of a class by the plugin's C#
 * ([CSharpSyntaxModel]), the markup in them blanked out.
 */
class RazorStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        val file = psiFile.viewProvider.getPsi(RazorLanguage) as? RazorFile ?: return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                object : StructureViewModelBase(file, editor, Item(file, file.name, 0, null, items(file))), StructureViewModel.ElementInfoProvider {
                    // a member or a line directive has nothing under it: no arrow to open
                    override fun isAlwaysShowsPlus(element: StructureViewTreeElement?): Boolean = false
                    override fun isAlwaysLeaf(element: StructureViewTreeElement?): Boolean = (element as? Item)?.items?.isEmpty() == true
                }
            override fun isRootNodeShown(): Boolean = false
        }
    }

    internal data class Item(val file: RazorFile, val text: String, val offset: Int, val icon: Icon?, val items: List<Item>) : StructureViewTreeElement, ItemPresentation {
        override fun getValue(): Any = this
        override fun getPresentation(): ItemPresentation = this
        override fun getChildren(): Array<StructureViewTreeElement> = items.toTypedArray()
        override fun getPresentableText(): String = text
        override fun getIcon(unused: Boolean): Icon? = icon
        override fun canNavigate(): Boolean = file.virtualFile != null
        override fun canNavigateToSource(): Boolean = canNavigate()
        override fun navigate(requestFocus: Boolean) {
            val virtualFile = file.virtualFile ?: return
            OpenFileDescriptor(file.project, virtualFile, offset).navigate(requestFocus)
        }

        override fun equals(other: Any?): Boolean = other is Item && other.text == text && other.offset == offset && other.file == file
        override fun hashCode(): Int = text.hashCode() * 31 + offset
    }

    companion object {
        private val SHOWN = setOf("page", "inject", "inherits", "layout", "model", "typeparam", "implements", "attribute", "rendermode", "section", "code", "functions")
        private const val CLASS = "class __RazorCode__ {"

        internal fun items(file: RazorFile): List<Item> {
            val blocks = file.directives.filter { it.directiveName in SHOWN }
            val members = members(file, blocks.filter { it.directiveName == "code" || it.directiveName == "functions" })
            return blocks.map { block ->
                val name = block.directiveName
                val text = "@$name" + block.argument?.let { " $it" }.orEmpty()
                val icon = when (name) {
                    "code", "functions" -> AllIcons.Nodes.Class
                    "section" -> AllIcons.Nodes.Tag
                    else -> AllIcons.Nodes.Annotationtype
                }
                Item(file, text, block.textRange.startOffset, icon, members.filter { it.offset in block.textRange })
            }
        }

        /** The members of the code blocks, from their C# alone: the text of the file with all else blanked, inside one class. */
        private fun members(file: RazorFile, blocks: List<RazorDirectivePsi>): List<Item> {
            if (blocks.isEmpty()) return emptyList()
            val text = file.text
            val masked = StringBuilder(text.length)
            for (i in text.indices) masked.append(if (text[i] == '\n' || text[i] == '\r') text[i] else ' ')
            for (block in blocks) {
                PsiTreeUtil.findChildrenOfType(block, RazorCodeLiteral::class.java).filter { it.isValidHost }.forEach { code ->
                    for (i in code.textRange.startOffset until code.textRange.endOffset) masked.setCharAt(i, text[i])
                }
            }
            val structure = CSharpSyntaxModel.current.declarations(CLASS + masked + "\n}")
            val type = structure.declarations.firstOrNull() ?: return emptyList()
            return type.children.map { member ->
                Item(file, member.presentation, member.nameRange.startOffset - CLASS.length, CSharpIcons.of(member.kind, member.modifiers, DeclarationKind.CLASS), emptyList())
            }
        }
    }
}
