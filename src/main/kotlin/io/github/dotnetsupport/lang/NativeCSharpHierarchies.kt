package io.github.dotnetsupport.lang

import com.intellij.lang.CodeInsightActions
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.hint.HintManager
import com.intellij.icons.AllIcons
import com.intellij.ide.hierarchy.CallHierarchyBrowserBase
import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.TypeHierarchyBrowserBase
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.AssemblyNavigation
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.SemanticType
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ListCellRenderer

/**
 * Go to Super (Ctrl+U), Type Hierarchy (Ctrl+H), Call Hierarchy (Ctrl+Alt+H) and the gutter of overrides and implementations without the
 * language server (CSHARP_PSI_MIGRATION.md, task C4b; feature `NAVIGATION`): the hierarchy of [CSharpSolutionSearch] on the declarations of
 * csharp-psi's tree. A type or member of an assembly is shown by its metadata view (B4). With ROSLYN the providers of the module `roslyn`
 * answer as before (each of these stands back first).
 */
object NativeCSharpHierarchies {
    /** The type or member the caret is on: a declaration's name, a usage resolved to one; else the member, then the type, around the caret. */
    fun declarationAt(file: PsiFile, offset: Int, members: Boolean = true): PsiElement? {
        val leaf = NativeCSharpFindUsages.identifierAt(file, offset) ?: file.findElementAt(offset)
        if (leaf != null && CSharpLeaves.isIdentifier(leaf)) {
            CSharpSolutionSearch.declarationNamedBy(leaf)?.let { return it }
            CSharpSolutionSearch.targetAt(leaf, CSharpSemanticSession(file.project))?.primary?.let { return it }
        }
        var current = leaf?.parent
        while (current != null && current !is PsiFile) {
            if (members && (current is CSharpBaseMethodDeclaration || current is CSharpBasePropertyDeclaration) && current.parent is CSharpBaseTypeDeclaration) return current
            if (current is CSharpBaseTypeDeclaration) return current
            current = current.parent
        }
        return null
    }

    fun typeAt(file: PsiFile, offset: Int): PsiElement? = declarationAt(file, offset).let { it as? CSharpBaseTypeDeclaration ?: it?.let(CSharpSolutionSearch::ownerType) }

    /** A supertype as an element to show and go to: its declaration in the sources or in the metadata view of its assembly. */
    fun elementOf(project: Project, type: SemanticType): PsiElement? = when (type) {
        is SemanticType.Source -> type.info.targets().firstOrNull()
        is SemanticType.Library -> AssemblyNavigation.target(project, type.type)
        else -> null
    }

    /** A base member as an element: a declaration, or the member of an assembly in its metadata view. */
    fun elementOf(project: Project, base: Any): PsiElement? = when (base) {
        is PsiElement -> base
        is CSharpSymbol.LibraryMember -> AssemblyNavigation.target(project, base.member)
        else -> null
    }

    fun isMetadata(file: PsiFile): Boolean = AssemblyNavigation.isMetadata(file.viewProvider.virtualFile)

    fun text(element: PsiElement): String = (element as? NavigationItem)?.presentation?.presentableText ?: CSharpDeclarationNames.name(element) ?: element.text.take(40)

    fun location(element: PsiElement): String? {
        val presentation: ItemPresentation? = (element as? NavigationItem)?.presentation
        val inType = CSharpSolutionSearch.ownerType(element)?.let(CSharpDeclarationNames::name)
        return presentation?.locationString?.takeIf { it.isNotEmpty() } ?: inType ?: element.containingFile?.name
    }

    /**
     * A row of the list of targets: what the renderer shows and where a choice goes, read from the PSI up front. The renderer and the
     * callback run on the EDT, which has no read access of its own: a row reads nothing.
     */
    class Row(val text: String, val location: String?, val icon: Icon?, private val target: Navigatable?) {
        val label: String get() = text + location?.let { "  ($it)" }.orEmpty()
        fun navigate() = target?.navigate(true) ?: Unit
    }

    /** The rows of [elements], in a read action: texts, icons and the places to go (a descriptor of the file and offset, no PSI). */
    fun rows(elements: List<PsiElement>): List<Row> = ReadAction.compute<List<Row>, RuntimeException> {
        elements.map { Row(text(it), location(it), it.getIcon(0), PsiNavigationSupport.getInstance().getDescriptor(it) ?: it as? Navigatable) }
    }

    fun renderer(): ListCellRenderer<Row> = SimpleListCellRenderer.create { label, row, _ ->
        label.text = row.label
        label.icon = row.icon
    }

    /** The list to choose a target from, as the platform lists targets; a choice goes there. */
    fun chooser(rows: List<Row>, title: String): JBPopup = JBPopupFactory.getInstance().createPopupChooserBuilder(rows)
        .setTitle(title)
        .setRenderer(renderer())
        .setNamerForFiltering { it.text }
        .setItemChosenCallback { it.navigate() }
        .createPopup()

    /** One element: go; several: a list to choose from. */
    fun choose(editor: Editor, elements: List<PsiElement>, title: String) {
        val rows = rows(elements)
        if (rows.size == 1) return rows.single().navigate()
        chooser(rows, title).showInBestPositionFor(editor)
    }

    fun <T> underProgress(project: Project, title: String, compute: () -> T): T =
        ProgressManager.getInstance().runProcessWithProgressSynchronously<T, RuntimeException>({ ReadAction.compute<T, RuntimeException>(compute) }, title, true, project)
}

/**
 * Ctrl+U: the base types of a type, the members a member overrides or implements ([CSharpSolutionSearch.baseMembers]). First for C#:
 * with ROSLYN it hands the action to the handler of the module `roslyn`.
 */
class NativeCSharpGotoSuperHandler : LanguageCodeInsightActionHandler {
    override fun startInWriteAction(): Boolean = false
    override fun isValidFor(editor: Editor?, file: PsiFile?): Boolean = file is CSharpFile

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (!NativeCSharpFindUsages.serves(file)) {
            val other = CodeInsightActions.GOTO_SUPER.allForLanguage(file.language).firstOrNull { it !is NativeCSharpGotoSuperHandler && (it as? LanguageCodeInsightActionHandler)?.isValidFor(editor, file) != false }
            return other?.invoke(project, editor, file) ?: HintManager.getInstance().showErrorHint(editor, "No base symbols found")
        }
        val offset = editor.caretModel.offset
        val (name, elements) = NativeCSharpHierarchies.underProgress(project, "Searching for Base Symbols") {
            val session = CSharpSemanticSession(project)
            val declaration = NativeCSharpHierarchies.declarationAt(file, offset) ?: return@underProgress "" to emptyList()
            val found = if (declaration is CSharpBaseTypeDeclaration) CSharpSolutionSearch.supertypes(declaration, session).mapNotNull { NativeCSharpHierarchies.elementOf(project, it) }
            else CSharpSolutionSearch.baseMembers(declaration, session).mapNotNull { NativeCSharpHierarchies.elementOf(project, it) }
            (CSharpDeclarationNames.name(declaration) ?: "") to found
        }
        if (elements.isEmpty()) return HintManager.getInstance().showErrorHint(editor, if (name.isEmpty()) "Put the caret on a type or on a member" else "No base symbols of $name found")
        NativeCSharpHierarchies.choose(editor, elements, "Choose Base Symbol of $name")
    }
}

/** Ctrl+H on the native tree: the type at the caret; null for ROSLYN and outside a type, so the next provider (the server's) answers. */
class NativeCSharpTypeHierarchyProvider : HierarchyProvider {
    override fun getTarget(dataContext: DataContext): PsiElement? {
        val file = dataContext.getData(CommonDataKeys.PSI_FILE) as? CSharpFile ?: return null
        val editor = dataContext.getData(CommonDataKeys.EDITOR)
        if (!NativeCSharpFindUsages.serves(file)) return null
        if (editor == null) return dataContext.getData(CommonDataKeys.PSI_ELEMENT)?.takeIf { it is CSharpBaseTypeDeclaration }
        return NativeCSharpHierarchies.typeAt(file, editor.caretModel.offset)
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = NativeCSharpTypeHierarchyBrowser(target.project, target)

    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        (hierarchyBrowser as NativeCSharpTypeHierarchyBrowser).changeView(TypeHierarchyBrowserBase.getTypeHierarchyType())
    }
}

class NativeCSharpTypeHierarchyBrowser(project: Project, element: PsiElement) : TypeHierarchyBrowserBase(project, element) {
    override fun isInterface(psiElement: PsiElement): Boolean = (psiElement as? CSharpTypeDeclaration)?.keyword?.text == "interface"
    override fun isApplicableElement(element: PsiElement): Boolean = element is CSharpBaseTypeDeclaration
    override fun getQualifiedName(psiElement: PsiElement?): String = psiElement?.let { NativeCSharpHierarchies.text(it) }.orEmpty()
    override fun canBeDeleted(psiElement: PsiElement?): Boolean = false
    override fun createLegendPanel(): JPanel? = null
    override fun getComparator(): Comparator<NodeDescriptor<*>>? = null
    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        trees[getTypeHierarchyType()] = createTree(true)
        trees[getSupertypesHierarchyType()] = createTree(true)
        trees[getSubtypesHierarchyType()] = createTree(true)
    }

    override fun createHierarchyTreeStructure(typeName: String, psiElement: PsiElement): HierarchyTreeStructure = when (typeName) {
        getSupertypesHierarchyType() -> NativeCSharpTypeHierarchyStructure(myProject, psiElement, Direction.SUPERTYPES)
        getSubtypesHierarchyType() -> NativeCSharpTypeHierarchyStructure(myProject, psiElement, Direction.SUBTYPES)
        else -> NativeCSharpTypeHierarchyStructure(myProject, psiElement, Direction.BOTH)
    }

    enum class Direction { SUPERTYPES, SUBTYPES, BOTH }
}

/** Levels of supertypes or subtypes, asked when a node opens; the "type hierarchy" view puts the base classes above the base. */
class NativeCSharpTypeHierarchyStructure(project: Project, base: PsiElement, private val direction: NativeCSharpTypeHierarchyBrowser.Direction) :
    HierarchyTreeStructure(project, baseDescriptor(project, base, direction)) {
    init {
        // the root: the top of the chain of base classes
        setBaseElement(baseDescriptor)
    }

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        // a base class above the base in the "type hierarchy" view: the next one down the chain
        (descriptor as? NativeCSharpHierarchyDescriptor)?.chainChild?.let { return arrayOf(it) }
        val element = descriptor.psiElement ?: return emptyArray()
        val session = CSharpSemanticSession(myProject)
        val items = if (direction == NativeCSharpTypeHierarchyBrowser.Direction.SUPERTYPES) {
            CSharpSolutionSearch.supertypes(element, session).mapNotNull { NativeCSharpHierarchies.elementOf(myProject, it) }
        } else CSharpSolutionSearch.targetOf(element)?.let { CSharpSolutionSearch.directSubtypes(myProject, it, session) }.orEmpty()
        return items.mapNotNull { item ->
            // a cycle (a type its own ancestor through an error) is not walked again
            if (generateSequence(descriptor as NodeDescriptor<*>?) { it.parentDescriptor }.any { (it as? HierarchyNodeDescriptor)?.psiElement == item }) null
            else NativeCSharpHierarchyDescriptor(myProject, descriptor, item, false)
        }.toTypedArray()
    }

    private companion object {
        /**
         * The "type hierarchy" view as the platform draws it for classes: the chain of base classes from the top down to the base (bold), its
         * subtypes below it; the other views start at the base.
         */
        fun baseDescriptor(project: Project, base: PsiElement, direction: NativeCSharpTypeHierarchyBrowser.Direction): HierarchyNodeDescriptor {
            if (direction != NativeCSharpTypeHierarchyBrowser.Direction.BOTH) return NativeCSharpHierarchyDescriptor(project, null, base, true)
            val session = CSharpSemanticSession(project)
            val chain = ArrayList<PsiElement>()
            var current: PsiElement = base
            while (chain.size < 32) {
                val next = CSharpSolutionSearch.supertypes(current, session).firstOrNull { !isInterfaceType(it) }?.let { NativeCSharpHierarchies.elementOf(project, it) } ?: break
                if (next == base || next in chain || next.containingFile?.let(NativeCSharpHierarchies::isMetadata) == true && chain.any { it.containingFile == next.containingFile }) break
                chain += next
                if (next.containingFile?.let(NativeCSharpHierarchies::isMetadata) == true) break
                current = next
            }
            var parent: NativeCSharpHierarchyDescriptor? = null
            for (element in chain.asReversed()) {
                val descriptor = NativeCSharpHierarchyDescriptor(project, parent, element, false)
                parent?.chainChild = descriptor
                parent = descriptor
            }
            return NativeCSharpHierarchyDescriptor(project, parent, base, true).also { parent?.chainChild = it }
        }

        private fun isInterfaceType(type: io.github.dotnetsupport.lang.semantic.SemanticType): Boolean = when (type) {
            is io.github.dotnetsupport.lang.semantic.SemanticType.Source -> type.info.targets().firstOrNull().let { (it as? CSharpTypeDeclaration)?.keyword?.text == "interface" }
            is io.github.dotnetsupport.lang.semantic.SemanticType.Library -> type.type.kind == io.github.dotnetsupport.index.IndexedTypeKind.INTERFACE
            else -> true
        }
    }
}

/** A row: the name, its container in gray; the base in bold. */
class NativeCSharpHierarchyDescriptor(project: Project, parent: HierarchyNodeDescriptor?, element: PsiElement, isBase: Boolean) :
    HierarchyNodeDescriptor(project, parent, element, isBase) {
    /** A base class above the base in the "type hierarchy" view: its only child, the next class down the chain. */
    var chainChild: NativeCSharpHierarchyDescriptor? = null

    override fun update(): Boolean {
        val changed = super.update()
        val element = psiElement ?: return changed
        val text = com.intellij.openapi.roots.ui.util.CompositeAppearance()
        myHighlightedText = text
        val name = NativeCSharpHierarchies.text(element)
        text.ending.addText(name, if (myIsBase) TextAttributes().apply { fontType = java.awt.Font.BOLD } else null)
        NativeCSharpHierarchies.location(element)?.let { text.ending.addText("  ($it)", SimpleTextAttributes.GRAYED_ATTRIBUTES.toTextAttributes()) }
        icon = element.getIcon(0)
        myName = name
        return true
    }
}

/** Ctrl+Alt+H on the native tree: the method, constructor or property at the caret. */
class NativeCSharpCallHierarchyProvider : HierarchyProvider {
    override fun getTarget(dataContext: DataContext): PsiElement? {
        val file = dataContext.getData(CommonDataKeys.PSI_FILE) as? CSharpFile ?: return null
        val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return null
        if (!NativeCSharpFindUsages.serves(file)) return null
        return NativeCSharpHierarchies.declarationAt(file, editor.caretModel.offset)?.takeIf { NativeCSharpCallGraph.isCallable(it) }
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = NativeCSharpCallHierarchyBrowser(target.project, target)

    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        (hierarchyBrowser as NativeCSharpCallHierarchyBrowser).changeView(CallHierarchyBrowserBase.getCallerType())
    }
}

class NativeCSharpCallHierarchyBrowser(project: Project, element: PsiElement) : CallHierarchyBrowserBase(project, element) {
    override fun isApplicableElement(element: PsiElement): Boolean = NativeCSharpCallGraph.isCallable(element)
    override fun createLegendPanel(): JPanel? = null
    override fun getComparator(): Comparator<NodeDescriptor<*>>? = null
    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        trees[getCallerType()] = createTree(false)
        trees[getCalleeType()] = createTree(false)
    }

    override fun createHierarchyTreeStructure(typeName: String, psiElement: PsiElement): HierarchyTreeStructure =
        NativeCSharpCallHierarchyStructure(myProject, psiElement, callers = typeName == getCallerType())
}

class NativeCSharpCallHierarchyStructure(project: Project, base: PsiElement, private val callers: Boolean) :
    HierarchyTreeStructure(project, NativeCSharpHierarchyDescriptor(project, null, base, true)) {

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val element = descriptor.psiElement ?: return emptyArray()
        val items = if (callers) NativeCSharpCallGraph.callers(myProject, element) else NativeCSharpCallGraph.callees(myProject, element)
        return items.mapNotNull { item ->
            // recursion is shown once: a member already on the path up is not expanded again
            if (generateSequence(descriptor as NodeDescriptor<*>?) { it.parentDescriptor }.any { (it as? HierarchyNodeDescriptor)?.psiElement == item }) null
            else NativeCSharpHierarchyDescriptor(myProject, descriptor, item, false)
        }.toTypedArray()
    }
}

/** Who calls a member and what it calls, by the search of usages and the resolver. */
object NativeCSharpCallGraph {
    fun isCallable(element: PsiElement): Boolean = element is CSharpMethodDeclaration || element is CSharpConstructorDeclaration || element is CSharpPropertyDeclaration ||
        element is CSharpIndexerDeclaration || element is CSharpEventDeclaration || element is CSharpLocalFunctionStatement

    /** The member a usage stands in: a method, constructor, property, accessor's property, field initializer's field, local function. */
    fun container(leaf: PsiElement): PsiElement? {
        var current = leaf.parent
        while (current != null && current !is PsiFile) {
            when (current) {
                is CSharpLocalFunctionStatement, is CSharpBaseMethodDeclaration, is CSharpBasePropertyDeclaration -> return current
                is CSharpBaseFieldDeclaration, is CSharpEnumMemberDeclaration -> return current
                is CSharpBaseTypeDeclaration -> return current
            }
            current = current.parent
        }
        return null
    }

    fun callers(project: Project, element: PsiElement): List<PsiElement> {
        val target = CSharpSolutionSearch.targetOf(element) ?: return emptyList()
        val found = LinkedHashSet<PsiElement>()
        CSharpSolutionSearch.processUsages(project, target, com.intellij.psi.search.GlobalSearchScope.projectScope(project)) { usage ->
            if (PsiTreeUtil.getParentOfType(usage.leaf, CSharpDocumentationCommentTrivia::class.java) == null && !inNameof(usage.leaf)) container(usage.leaf)?.let(found::add)
            true
        }
        return found.toList()
    }

    private fun inNameof(leaf: PsiElement): Boolean = NativeCSharpUsageKinds.kindOfLeaf(leaf) == CSharpUsageKind.NAMEOF

    /** The members of the solution the body of [element] calls, reads or creates, in the order of the text. */
    fun callees(project: Project, element: PsiElement): List<PsiElement> {
        val file = element.containingFile as? CSharpFile ?: return emptyList()
        val resolver = CSharpSemanticSession(project).resolver(file)
        val found = LinkedHashSet<PsiElement>()
        for (leaf in PsiTreeUtil.collectElements(element) { it.firstChild == null && CSharpLeaves.isIdentifier(it) }) {
            ProgressManager.checkCanceled()
            if (CSharpSolutionSearch.declarationNamedBy(leaf) != null || resolver.syntax.symbolAt(leaf)?.let { it.kind != LocalSymbolKind.LOCAL_FUNCTION } == true) continue
            val symbols = resolver.resolve(leaf)?.symbols ?: continue
            val symbol = symbols.firstOrNull() ?: continue
            val declaration = when (symbol) {
                is CSharpSymbol.SourceMember -> symbol.element.takeIf(::isCallable)
                is CSharpSymbol.Local -> PsiTreeUtil.getParentOfType(symbol.symbol.declaration, CSharpLocalFunctionStatement::class.java)
                is CSharpSymbol.SourceType -> if (isCreation(leaf)) symbol.declarations.firstOrNull() else null
                else -> null
            } ?: continue
            if (declaration != element) found += declaration
        }
        return found.toList()
    }

    private fun isCreation(leaf: PsiElement): Boolean {
        var top: PsiElement = leaf.parent ?: return false
        while (top.parent is CSharpQualifiedName || top.parent is CSharpAliasQualifiedName) top = top.parent
        return (top.parent as? CSharpObjectCreationExpression)?.type == top
    }
}

/**
 * The gutter of a C# file of the native tree, as in Rider: a member that overrides or implements another (up), one that is overridden or
 * implemented, a type that has subtypes (down); a click lists them. Computed in the slow pass, one session per pass.
 */
class NativeCSharpInheritanceLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile ?: return
        if (!NativeCSharpFindUsages.serves(file)) return
        val project = file.project
        val session = CSharpSemanticSession(project)
        for (leaf in elements) {
            if (leaf.firstChild != null || !CSharpLeaves.isIdentifier(leaf)) continue
            ProgressManager.checkCanceled()
            val declaration = CSharpSolutionSearch.declarationNamedBy(leaf) ?: continue
            if (declaration is CSharpBaseTypeDeclaration) {
                val target = CSharpSolutionSearch.targetOf(declaration) ?: continue
                if (target.primary != declaration) continue
                if (CSharpSolutionSearch.directSubtypes(project, target, session).isNotEmpty()) {
                    val isInterface = (declaration as? CSharpTypeDeclaration)?.keyword?.text == "interface"
                    result += marker(leaf, if (isInterface) AllIcons.Gutter.ImplementedMethod else AllIcons.Gutter.OverridenMethod,
                        if (isInterface) "Has implementations" else "Has subclasses") { CSharpSolutionSearch.allSubtypes(project, target, CSharpSemanticSession(project)) }
                }
                continue
            }
            if (declaration is CSharpConstructorDeclaration || CSharpSolutionSearch.ownerType(declaration) == null) continue
            val bases = CSharpSolutionSearch.baseMembers(declaration, session)
            if (bases.isNotEmpty()) {
                val implements = bases.all { base -> base !is PsiElement || (CSharpSolutionSearch.ownerType(base) as? CSharpTypeDeclaration)?.keyword?.text == "interface" } &&
                    (declaration as? CSharpMemberDeclaration)?.modifiers?.none { it.text == "override" } != false
                result += marker(leaf, if (implements) AllIcons.Gutter.ImplementingMethod else AllIcons.Gutter.OverridingMethod, if (implements) "Implements member" else "Overrides member") {
                    CSharpSolutionSearch.baseMembers(declaration, CSharpSemanticSession(project)).mapNotNull { NativeCSharpHierarchies.elementOf(project, it) }
                }
            }
            if (CSharpSolutionSearch.isOverridable(declaration) && CSharpSolutionSearch.overridingMembers(project, declaration, session).isNotEmpty()) {
                val inInterface = (CSharpSolutionSearch.ownerType(declaration) as? CSharpTypeDeclaration)?.keyword?.text == "interface"
                result += marker(leaf, if (inInterface) AllIcons.Gutter.ImplementedMethod else AllIcons.Gutter.OverridenMethod, if (inInterface) "Has implementations" else "Is overridden") {
                    CSharpSolutionSearch.overridingMembers(project, declaration, CSharpSemanticSession(project))
                }
            }
        }
    }

    private fun marker(leaf: PsiElement, icon: Icon, tooltip: String, targets: () -> List<PsiElement>): LineMarkerInfo<PsiElement> {
        val handler = GutterIconNavigationHandler<PsiElement> { event: MouseEvent, element: PsiElement ->
            val project = element.project
            val rows = NativeCSharpHierarchies.underProgress(project, tooltip) { NativeCSharpHierarchies.rows(targets()) }
            when (rows.size) {
                0 -> {}
                1 -> rows.single().navigate()
                else -> NativeCSharpHierarchies.chooser(rows, tooltip).show(RelativePoint(event))
            }
        }
        return LineMarkerInfo(leaf, leaf.textRange, icon, { tooltip }, handler, GutterIconRenderer.Alignment.RIGHT) { tooltip }
    }
}
