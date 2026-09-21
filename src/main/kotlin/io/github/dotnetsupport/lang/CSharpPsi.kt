package io.github.dotnetsupport.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.RowIcon
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.intellij.util.IncorrectOperationException
import com.intellij.util.PlatformIcons
import javax.swing.Icon

/** One element type per kind of declaration: the PSI knows what it is without looking at the text again. */
object CSharpElementTypes {
    private val BY_KIND: Map<DeclarationKind, IElementType> = DeclarationKind.entries.associateWith { CSharpTokenType("DECLARATION_" + it.name) }
    private val KINDS: Map<IElementType, DeclarationKind> = BY_KIND.entries.associate { it.value to it.key }

    fun of(kind: DeclarationKind): IElementType = BY_KIND.getValue(kind)
    fun kindOf(type: IElementType): DeclarationKind? = KINDS[type]
}

/**
 * The tree of a C# file: [CSharpDeclarations] finds the declarations, and the tokens are grouped into a node per
 * declaration. Inside a member the tokens stay flat: statements and expressions are not parsed.
 */
object CSharpTreeBuilder {
    fun build(root: IElementType, builder: PsiBuilder): ASTNode {
        val starts = CSharpDeclarations.scan(builder.originalText).all().groupBy { it.range.startOffset }
        val file = builder.mark()
        val open = ArrayDeque<Pair<PsiBuilder.Marker, CSharpDeclarationInfo>>()
        while (!builder.eof()) {
            val offset = builder.currentOffset
            while (open.isNotEmpty() && open.last().second.range.endOffset <= offset) open.removeLast().let { (marker, info) -> marker.done(CSharpElementTypes.of(info.kind)) }
            // an outer declaration first: a marker is closed after the ones opened inside of it
            starts[offset]?.sortedByDescending { it.range.length }?.forEach { open.addLast(builder.mark() to it) }
            builder.advanceLexer()
        }
        while (open.isNotEmpty()) open.removeLast().let { (marker, info) -> marker.done(CSharpElementTypes.of(info.kind)) }
        file.done(root)
        return builder.treeBuilt
    }
}

/** The declarations of a file as [CSharpDeclarations] sees its current text; scanned once per change. */
object CSharpStructure {
    fun of(file: PsiFile): CSharpFileStructure = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(CSharpDeclarations.scan(file.viewProvider.contents), file)
    }
}

/** A namespace, a type or a member. Everything about it beyond its kind comes from [CSharpStructure]. */
class CSharpDeclaration(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner, NavigatablePsiElement {
    val kind: DeclarationKind get() = CSharpElementTypes.kindOf(node.elementType) ?: DeclarationKind.CLASS

    val info: CSharpDeclarationInfo?
        get() {
            val start = textRange.startOffset
            return CSharpStructure.of(containingFile).all().firstOrNull { it.range.startOffset == start && it.kind == kind }
        }

    /** The namespace and the types around: `Shop.Orders.OrderService`. */
    val containerName: String
        get() = generateSequence(parent) { it.parent }.filterIsInstance<CSharpDeclaration>().mapNotNull { it.name }.toList().asReversed().joinToString(".")

    override fun getName(): String? = info?.name
    override fun getNameIdentifier(): PsiElement? = info?.let { containingFile.findElementAt(it.nameRange.startOffset) }
    override fun getTextOffset(): Int = info?.nameRange?.startOffset ?: super.getTextOffset()
    override fun setName(name: String): PsiElement = throw IncorrectOperationException("Renaming C# declarations needs a language server")

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = info?.presentation ?: text.take(MAX_TEXT)
        override fun getLocationString(): String = listOf(containerName, containingFile.name).filter { it.isNotEmpty() }.joinToString(" in ")
        override fun getIcon(unused: Boolean): Icon = this@CSharpDeclaration.getIcon(0)
    }

    override fun getIcon(flags: Int): Icon = CSharpIcons.of(kind, info?.modifiers.orEmpty(), (parent as? CSharpDeclaration)?.kind)
    override fun toString(): String = "CSharpDeclaration(${kind.title} ${name.orEmpty()})"

    private companion object {
        const val MAX_TEXT = 40
    }
}

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

/** Structure tool window and File Structure popup: the declarations of the file as a tree. */
class CSharpStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is CSharpFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                StructureViewModelBase(psiFile, editor, Element(psiFile)).withSuitableClasses(CSharpDeclaration::class.java).withSorters(Sorter.ALPHA_SORTER)

            override fun isRootNodeShown(): Boolean = false
        }
    }

    class Element(element: PsiElement) : PsiTreeElementBase<PsiElement>(element) {
        override fun getPresentableText(): String? = (element as? CSharpDeclaration)?.presentation?.presentableText ?: (element as? PsiFile)?.name

        override fun getChildrenBase(): Collection<StructureViewTreeElement> =
            PsiTreeUtil.getChildrenOfTypeAsList(element, CSharpDeclaration::class.java).map(::Element)
    }
}

/** `Shop.Orders › OrderService › Total()` above the editor, and the sticky lines that follow it. */
class CSharpBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(CSharpLanguage)
    override fun acceptElement(element: PsiElement): Boolean = element is CSharpDeclaration && element.info != null
    override fun getElementIcon(element: PsiElement): Icon? = (element as? CSharpDeclaration)?.getIcon(0)
    override fun getElementTooltip(element: PsiElement): String? = (element as? CSharpDeclaration)?.info?.presentation

    override fun getElementInfo(element: PsiElement): String {
        val info = (element as CSharpDeclaration).info ?: return ""
        return info.name + if (info.parameters != null && !info.kind.isType) "()" else ""
    }
}
