package io.github.dotnetsupport.roslyn

import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.TypeHierarchyBrowserBase
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiElement
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JPanel
import javax.swing.JTree

/**
 * Type Hierarchy (Ctrl+H) of a C# type: supertypes, subtypes and the whole tree, asked from the server level by level. The platform
 * builds its tool window around PSI elements; the items of the server are [HierarchyElement]s.
 */
class RoslynTypeHierarchyProvider : HierarchyProvider {
    override fun getTarget(dataContext: DataContext): PsiElement? = RoslynHierarchies.element(dataContext, RoslynHierarchies::prepareType)

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = RoslynTypeHierarchyBrowser(target.project, target as HierarchyElement)

    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        (hierarchyBrowser as RoslynTypeHierarchyBrowser).changeView(TypeHierarchyBrowserBase.getTypeHierarchyType())
    }
}

class RoslynTypeHierarchyBrowser(project: Project, element: HierarchyElement) : TypeHierarchyBrowserBase(project, element) {
    private val client: LspClient = element.client

    override fun isInterface(psiElement: PsiElement): Boolean = (psiElement as? HierarchyElement)?.item?.isInterface == true
    override fun isApplicableElement(element: PsiElement): Boolean = element is HierarchyElement
    override fun getQualifiedName(psiElement: PsiElement?): String = (psiElement as? HierarchyElement)?.let { it.item.container?.let { c -> "$c.${it.item.name}" } ?: it.item.name }.orEmpty()
    override fun canBeDeleted(psiElement: PsiElement?): Boolean = false
    override fun createLegendPanel(): JPanel? = null
    override fun getComparator(): Comparator<NodeDescriptor<*>>? = null
    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        trees[getTypeHierarchyType()] = createTree(true)
        trees[getSupertypesHierarchyType()] = createTree(true)
        trees[getSubtypesHierarchyType()] = createTree(true)
    }

    override fun createHierarchyTreeStructure(typeName: String, psiElement: PsiElement): HierarchyTreeStructure? {
        val element = psiElement as? HierarchyElement ?: return null
        return when (typeName) {
            getSupertypesHierarchyType() -> RoslynTypeHierarchyStructure(myProject, client, element, Direction.SUPERTYPES)
            getSubtypesHierarchyType() -> RoslynTypeHierarchyStructure(myProject, client, element, Direction.SUBTYPES)
            else -> RoslynTypeHierarchyStructure(myProject, client, element, Direction.BOTH)
        }
    }

    enum class Direction { SUPERTYPES, SUBTYPES, BOTH }
}

/** The direction of a level: supertypes, subtypes, or (the "type hierarchy") the supertypes above the base and its subtypes below it. */
class RoslynTypeHierarchyStructure(project: Project, private val client: LspClient, base: HierarchyElement, private val direction: RoslynTypeHierarchyBrowser.Direction) :
    HierarchyTreeStructure(project, RoslynHierarchyDescriptor(project, null, base, true, direction == RoslynTypeHierarchyBrowser.Direction.BOTH)) {

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val element = descriptor.psiElement as? HierarchyElement ?: return emptyArray()
        val ours = descriptor as? RoslynHierarchyDescriptor
        val items = when {
            direction == RoslynTypeHierarchyBrowser.Direction.SUPERTYPES -> RoslynHierarchies.supertypes(client, element.item)
            direction == RoslynTypeHierarchyBrowser.Direction.SUBTYPES -> RoslynHierarchies.subtypes(client, element.item)
            // the whole hierarchy: the base shows its supertypes (marked) and its subtypes; a supertype goes on up, a subtype on down
            ours?.isBase == true -> RoslynHierarchies.supertypes(client, element.item).map { it to true } + RoslynHierarchies.subtypes(client, element.item).map { it to false }
            ours?.upwards == true -> RoslynHierarchies.supertypes(client, element.item)
            else -> RoslynHierarchies.subtypes(client, element.item)
        }
        return items.mapNotNull { entry ->
            val (item, upwards) = if (entry is Pair<*, *>) (entry.first as HierarchyItem) to (entry.second as Boolean) else (entry as HierarchyItem) to (ours?.upwards == true)
            // a cycle (a type that is its own ancestor through an error) is not walked again
            if (generateSequence(descriptor as NodeDescriptor<*>?) { it.parentDescriptor }.any { (it as? RoslynHierarchyDescriptor)?.element?.item?.sameAs(item) == true }) null
            else RoslynHierarchyDescriptor(myProject, descriptor, HierarchyElement(myProject, client, item), false, upwards)
        }.toTypedArray()
    }
}

/** A row: the name of the symbol, its container in gray; the base of the hierarchy in bold, as the platform does. */
class RoslynHierarchyDescriptor(project: Project, parent: HierarchyNodeDescriptor?, val element: HierarchyElement, val isBase: Boolean, val upwards: Boolean = false) :
    HierarchyNodeDescriptor(project, parent, element, isBase) {

    override fun update(): Boolean {
        val changed = super.update()
        val text = com.intellij.openapi.roots.ui.util.CompositeAppearance()
        myHighlightedText = text
        val name = element.rowText
        text.ending.addText(name, if (isBase) TextAttributes().apply { fontType = java.awt.Font.BOLD } else null)
        element.locationText?.let { text.ending.addText("  ($it)", SimpleTextAttributes.GRAYED_ATTRIBUTES.toTextAttributes()) }
        icon = element.item.icon
        myName = name
        return changed || true
    }
}
