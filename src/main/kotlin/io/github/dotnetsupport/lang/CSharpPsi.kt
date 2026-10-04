package io.github.dotnetsupport.lang

import com.intellij.openapi.util.Condition
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.LocationPresentation
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.IncorrectOperationException
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

/**
 * A declaration is a named PSI element, so the rename of the platform takes it for its own and ends in [CSharpDeclaration.setName]
 * ("needs a language server") even when a language server is there: the rename of the LSP client is registered last. Vetoed,
 * the rename of the platform steps aside and the one of the server is what Shift+F6 does.
 */
class CSharpRenameVeto : Condition<PsiElement> {
    override fun value(element: PsiElement): Boolean = element is CSharpDeclaration
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

    /** The types around, without the namespace: `BaseShape`, `Outer.Inner`. */
    val containingTypes: String
        get() = generateSequence(parent) { it.parent }.filterIsInstance<CSharpDeclaration>().filter { it.kind != DeclarationKind.NAMESPACE }
            .mapNotNull { it.name }.toList().asReversed().joinToString(".")

    /**
     * A row of Go to Class / Symbol as Java has it: `Area()` (the parameters as written, no type), then in gray the type it is in
     * (`BaseShape`, `Outer.Inner`) — for a type the namespace and the types around it — and the file on the right
     * ([CSharpLocationRenderer]). The four `Area` of an interface and its implementations were four identical rows before (reported).
     * The Structure view keeps the type after the name: it takes [CSharpDeclarationInfo.presentation].
     */
    override fun getPresentation(): ItemPresentation = object : ItemPresentation, LocationPresentation {
        override fun getPresentableText(): String = info?.let { it.name + it.parameters.orEmpty() } ?: text.take(MAX_TEXT)
        override fun getLocationString(): String = if (kind.isType) containerName else containingTypes
        override fun getIcon(unused: Boolean): Icon = this@CSharpDeclaration.getIcon(0)
        override fun getLocationPrefix(): String = " "
        override fun getLocationSuffix(): String = ""
    }

    override fun getIcon(flags: Int): Icon = CSharpIcons.of(kind, info?.modifiers.orEmpty(), (parent as? CSharpDeclaration)?.kind)
    override fun toString(): String = "CSharpDeclaration(${kind.title} ${name.orEmpty()})"

    private companion object {
        const val MAX_TEXT = 40
    }
}
