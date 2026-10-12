package io.github.dotnetsupport.csharp.lang.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.ItemPresentationProviders
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpVisitor

/**
 * Base of the generated implementations (`CSharpPsiImpl.kt`): accessors read the slot their field gets from
 * [CSharpSyntaxShape.match] over the node's children. The slots are matched once per state of the node and cached
 * until the node's subtree changes: `CompositeElement.subtreeChanged` (every change below the node, raw ones
 * included) calls [subtreeChanged] of the wrapper, which drops the cache. So an accessor is O(1) after the first (a
 * class with thousands of members asked for its name by each of them). The cache is an immutable array in a volatile
 * field: lock-free, a race between readers only matches twice (writes run under the write lock).
 */
abstract class CSharpElementImpl(node: ASTNode) : ASTWrapperPsiElement(node), CSharpElement {
    /** The fields of the Roslyn class in slot order. */
    abstract val shape: CSharpSyntaxShape

    @Volatile
    private var cachedSlots: Array<Any?>? = null

    /** Every field's value: [ASTNode]s, lists of them, or [CSharpSyntaxShape.Separated]. Do not modify the array. */
    fun slots(): Array<Any?> = cachedSlots ?: shape.match(node).also { cachedSlots = it }

    override fun subtreeChanged() {
        cachedSlots = null
        super.subtreeChanged()
    }

    protected fun tokenSlot(i: Int): PsiElement? = (slots()[i] as ASTNode?)?.psi

    @Suppress("UNCHECKED_CAST")
    protected fun <T> nodeSlot(i: Int): T? = (slots()[i] as ASTNode?)?.psi as T?

    @Suppress("UNCHECKED_CAST")
    protected fun <T> nodeListSlot(i: Int): List<T> = PsiListView(slots()[i] as List<ASTNode>)

    @Suppress("UNCHECKED_CAST")
    protected fun tokenListSlot(i: Int): List<PsiElement> = PsiListView(slots()[i] as List<ASTNode>)

    protected fun <T> separatedSlot(i: Int): List<T> = PsiListView((slots()[i] as CSharpSyntaxShape.Separated).elements)

    protected fun separatorsSlot(i: Int): List<PsiElement> = PsiListView((slots()[i] as CSharpSyntaxShape.Separated).separators)

    override fun accept(visitor: PsiElementVisitor) {
        if (visitor is CSharpVisitor) accept(visitor) else super.accept(visitor)
    }

    /** The name of a declaration ([CSharpDeclarationNames]), null for other elements: what Go to Class / Symbol matches and shows. */
    override fun getName(): String? = CSharpDeclarationNames.name(this)

    /** A declaration's offset is its name's, as navigation expects; other elements keep their start. */
    override fun getTextOffset(): Int = CSharpDeclarationNames.nameElement(this)?.textRange?.startOffset ?: super.getTextOffset()

    /** Texts and icons are the host's: it registers an `itemPresentationProvider` for this class (principle 7, no UI in the core). */
    override fun getPresentation(): ItemPresentation? = ItemPresentationProviders.getItemPresentation(this)
}

/** A read-only view of cached nodes as their PSI: `members[i]` is O(1), not a copy of the list. */
internal class PsiListView<T>(private val nodes: List<ASTNode>) : AbstractList<T>() {
    override val size: Int get() = nodes.size

    @Suppress("UNCHECKED_CAST")
    override fun get(index: Int): T = nodes[index].psi as T
}
