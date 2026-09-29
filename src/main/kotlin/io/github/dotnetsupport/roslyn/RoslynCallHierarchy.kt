package io.github.dotnetsupport.roslyn

import com.intellij.ide.hierarchy.CallHierarchyBrowserBase
import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiElement
import javax.swing.JPanel
import javax.swing.JTree

/** Call Hierarchy (Ctrl+Alt+H) of a C# member: callers and callees from the server, level by level. */
class RoslynCallHierarchyProvider : HierarchyProvider {
    override fun getTarget(dataContext: DataContext): PsiElement? {
        val target = RoslynHierarchies.target(dataContext) ?: return null
        val item = RoslynHierarchies.prepareCall(target).firstOrNull() ?: return null
        return HierarchyElement(target.project, target.client, item)
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = RoslynCallHierarchyBrowser(target.project, target as HierarchyElement)

    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        (hierarchyBrowser as RoslynCallHierarchyBrowser).changeView(CallHierarchyBrowserBase.getCallerType())
    }
}

class RoslynCallHierarchyBrowser(project: Project, element: HierarchyElement) : CallHierarchyBrowserBase(project, element) {
    private val client: LspClient = element.client

    override fun isApplicableElement(element: PsiElement): Boolean = element is HierarchyElement
    override fun createLegendPanel(): JPanel? = null
    override fun getComparator(): Comparator<NodeDescriptor<*>>? = null
    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        trees[getCallerType()] = createTree(false)
        trees[getCalleeType()] = createTree(false)
    }

    override fun createHierarchyTreeStructure(typeName: String, psiElement: PsiElement): HierarchyTreeStructure? {
        val element = psiElement as? HierarchyElement ?: return null
        return RoslynCallHierarchyStructure(myProject, client, element, callers = typeName == getCallerType())
    }
}

class RoslynCallHierarchyStructure(project: Project, private val client: LspClient, base: HierarchyElement, private val callers: Boolean) :
    HierarchyTreeStructure(project, RoslynHierarchyDescriptor(project, null, base, true)) {

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val element = descriptor.psiElement as? HierarchyElement ?: return emptyArray()
        val items = if (callers) RoslynHierarchies.callers(client, element.item) else RoslynHierarchies.callees(client, element.item)
        return items.mapNotNull { item ->
            // recursion is shown once: a method already on the path up is not expanded again
            if (generateSequence(descriptor as NodeDescriptor<*>?) { it.parentDescriptor }.any { (it as? RoslynHierarchyDescriptor)?.element?.item?.sameAs(item) == true }) null
            else RoslynHierarchyDescriptor(myProject, descriptor, HierarchyElement(myProject, client, item), false)
        }.toTypedArray()
    }
}
