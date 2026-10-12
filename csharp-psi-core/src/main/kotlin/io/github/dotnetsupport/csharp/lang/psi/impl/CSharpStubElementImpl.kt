package io.github.dotnetsupport.csharp.lang.psi.impl

import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.ItemPresentationProviders
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpVisitor
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStub

/**
 * Base of the stub-based generated implementations (`GenPsi.Stubbed`: declarations, the compilation unit, an extension block, the variable
 * declaration and declarators of a field; docs/csharp-psi/GRAMMAR.md, "Stubs"). The accessors are [CSharpElementImpl]'s: slots over the
 * node's children, so any of them loads the AST of a file that only has stubs. What the stub knows is answered without it: [getName]
 * (and the parent, by [StubBasedPsiElementBase]). An element of a node that got no stub (a local's declarator, a member outside a type)
 * is plain AST PSI of this class.
 */
abstract class CSharpStubElementImpl : StubBasedPsiElementBase<CSharpStub>, StubBasedPsiElement<CSharpStub>, CSharpElement {
    constructor(node: ASTNode) : super(node)
    constructor(stub: CSharpStub, type: IElementType) : super(stub, type)

    /** The element type is a `SyntaxKind` constant, not an `IStubElementType` (the stub registry knows it); the deprecated `getElementType` throws. */
    override fun getIElementType(): IElementType = elementTypeImpl

    /** The fields of the Roslyn class in slot order. */
    abstract val shape: CSharpSyntaxShape

    @Volatile
    private var cachedSlots: Array<Any?>? = null

    /** Every field's value, as [CSharpElementImpl.slots]. Do not modify the array. */
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

    /** [CSharpDeclarationNames.name], from the stub when there is one (it was computed by the same function). */
    override fun getName(): String? {
        val stub = greenStub
        return if (stub != null) stub.name else CSharpDeclarationNames.name(this)
    }

    /** A declaration's offset is its name's, as navigation expects; other elements keep their start. */
    override fun getTextOffset(): Int = CSharpDeclarationNames.nameElement(this)?.textRange?.startOffset ?: super.getTextOffset()

    /** Texts and icons are the host's: it registers an `itemPresentationProvider` for this class (principle 7, no UI in the core). */
    override fun getPresentation(): ItemPresentation? = ItemPresentationProviders.getItemPresentation(this)

    /** As `ASTWrapperPsiElement` has it, without loading the AST. */
    override fun toString(): String = javaClass.simpleName + "(" + elementTypeImpl + ")"
}
