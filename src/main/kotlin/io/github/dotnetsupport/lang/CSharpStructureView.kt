package io.github.dotnetsupport.lang

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.Language
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.RowIcon
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.intellij.util.PlatformIcons
import javax.swing.Icon

object CSharpIcons {
    fun of(kind: DeclarationKind, modifiers: Set<String>, parent: DeclarationKind?): Icon {
        val base = when (kind) {
            DeclarationKind.NAMESPACE -> return AllIcons.Nodes.Package
            DeclarationKind.CLASS -> if ("abstract" in modifiers) AllIcons.Nodes.AbstractClass else AllIcons.Nodes.Class
            DeclarationKind.STRUCT -> AllIcons.Nodes.Class
            DeclarationKind.INTERFACE -> AllIcons.Nodes.Interface
            DeclarationKind.ENUM -> AllIcons.Nodes.Enum
            DeclarationKind.RECORD -> AllIcons.Nodes.Record
            DeclarationKind.DELEGATE -> AllIcons.Nodes.Lambda
            DeclarationKind.CONSTRUCTOR -> AllIcons.Nodes.ClassInitializer
            DeclarationKind.METHOD, DeclarationKind.OPERATOR -> if ("abstract" in modifiers) AllIcons.Nodes.AbstractMethod else AllIcons.Nodes.Method
            DeclarationKind.PROPERTY, DeclarationKind.INDEXER -> AllIcons.Nodes.Property
            DeclarationKind.FIELD -> if ("const" in modifiers) AllIcons.Nodes.Constant else AllIcons.Nodes.Field
            DeclarationKind.EVENT -> AllIcons.Nodes.Favorite
            DeclarationKind.ENUM_MEMBER -> return AllIcons.Nodes.Constant
        }
        return RowIcon(base, visibility(kind, modifiers, parent))
    }

    /** What C# assumes without a modifier: internal for a type, private for a member, public in an interface. */
    private fun visibility(kind: DeclarationKind, modifiers: Set<String>, parent: DeclarationKind?): Icon = when {
        "public" in modifiers -> PlatformIcons.PUBLIC_ICON
        "protected" in modifiers -> PlatformIcons.PROTECTED_ICON
        "private" in modifiers -> PlatformIcons.PRIVATE_ICON
        "internal" in modifiers || "file" in modifiers -> PlatformIcons.PACKAGE_LOCAL_ICON
        parent == DeclarationKind.INTERFACE -> PlatformIcons.PUBLIC_ICON
        kind.isType && (parent == null || parent == DeclarationKind.NAMESPACE) -> PlatformIcons.PACKAGE_LOCAL_ICON
        else -> PlatformIcons.PRIVATE_ICON
    }
}

/** Structure tool window and File Structure popup: the declarations of the file as a tree, from [CSharpSyntaxModel]. */
class CSharpStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is CSharpFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = object : StructureViewModelBase(psiFile, editor, Element(psiFile)) {
                // the element the caret is in, for Autoscroll from Source: the innermost declaration
                override fun isSuitable(element: PsiElement?): Boolean = element != null && CSharpSyntaxModel.current.declarationOf(element) != null
            }.withSorters(Sorter.ALPHA_SORTER)

            override fun isRootNodeShown(): Boolean = false
        }
    }

    class Element(element: PsiElement) : PsiTreeElementBase<PsiElement>(element) {
        override fun getPresentableText(): String? = element.let { if (it is PsiFile) it.name else (it as? NavigationItem)?.presentation?.presentableText }

        override fun getChildrenBase(): Collection<StructureViewTreeElement> = element?.let { CSharpSyntaxModel.current.childDeclarations(it).map(::Element) }.orEmpty()
    }
}

/** `Shop.Orders › OrderService › Total()` above the editor, and the sticky lines that follow it. */
class CSharpBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(CSharpLanguage)
    override fun acceptElement(element: PsiElement): Boolean = CSharpSyntaxModel.current.declarationOf(element) != null
    override fun getElementIcon(element: PsiElement): Icon? = element.getIcon(0)
    override fun getElementTooltip(element: PsiElement): String? = CSharpSyntaxModel.current.declarationOf(element)?.presentation

    override fun getElementInfo(element: PsiElement): String {
        val info = CSharpSyntaxModel.current.declarationOf(element) ?: return ""
        return info.name + if (info.parameters != null && !info.kind.isType) "()" else ""
    }
}
