package io.github.dotnetsupport.razor.lang.intermediate

/** `IntermediateNodeVisitor`: one method per node class, each falling back to [visitDefault]. */
open class IntermediateNodeVisitor {
    open fun visit(node: IntermediateNode) = node.accept(this)
    open fun visitDefault(node: IntermediateNode) {}
    open fun visitToken(node: IntermediateToken) = visitDefault(node)
    open fun visitDirectiveToken(node: DirectiveTokenIntermediateNode) = visitDefault(node)
    open fun visitDirective(node: DirectiveIntermediateNode) = visitDefault(node)
    open fun visitMalformedDirective(node: MalformedDirectiveIntermediateNode) = visitDefault(node)
    open fun visitExtension(node: ExtensionIntermediateNode) = visitDefault(node)
    open fun visitCSharpCode(node: CSharpCodeIntermediateNode) = visitDefault(node)
    open fun visitCSharpExpression(node: CSharpExpressionIntermediateNode) = visitDefault(node)
    open fun visitHtmlAttributeValue(node: HtmlAttributeValueIntermediateNode) = visitDefault(node)
    open fun visitCSharpExpressionAttributeValue(node: CSharpExpressionAttributeValueIntermediateNode) = visitDefault(node)
    open fun visitCSharpCodeAttributeValue(node: CSharpCodeAttributeValueIntermediateNode) = visitDefault(node)
    open fun visitHtmlAttribute(node: HtmlAttributeIntermediateNode) = visitDefault(node)
    open fun visitClassDeclaration(node: ClassDeclarationIntermediateNode) = visitDefault(node)
    open fun visitMethodDeclaration(node: MethodDeclarationIntermediateNode) = visitDefault(node)
    open fun visitFieldDeclaration(node: FieldDeclarationIntermediateNode) = visitDefault(node)
    open fun visitPropertyDeclaration(node: PropertyDeclarationIntermediateNode) = visitDefault(node)
    open fun visitDocument(node: DocumentIntermediateNode) = visitDefault(node)
    open fun visitHtml(node: HtmlContentIntermediateNode) = visitDefault(node)
    open fun visitNamespaceDeclaration(node: NamespaceDeclarationIntermediateNode) = visitDefault(node)
    open fun visitUsingDirective(node: UsingDirectiveIntermediateNode) = visitDefault(node)
    open fun visitTagHelper(node: TagHelperIntermediateNode) = visitDefault(node)
    open fun visitTagHelperBody(node: TagHelperBodyIntermediateNode) = visitDefault(node)
    open fun visitTagHelperProperty(node: TagHelperPropertyIntermediateNode) = visitDefault(node)
    open fun visitTagHelperDirectiveAttribute(node: TagHelperDirectiveAttributeIntermediateNode) = visitDefault(node)
    open fun visitTagHelperHtmlAttribute(node: TagHelperHtmlAttributeIntermediateNode) = visitDefault(node)
    open fun visitTagHelperDirectiveAttributeParameter(node: TagHelperDirectiveAttributeParameterIntermediateNode) = visitDefault(node)
    open fun visitComponent(node: ComponentIntermediateNode) = visitDefault(node)
    open fun visitComponentAttribute(node: ComponentAttributeIntermediateNode) = visitDefault(node)
    open fun visitComponentChildContent(node: ComponentChildContentIntermediateNode) = visitDefault(node)
    open fun visitComponentTypeArgument(node: ComponentTypeArgumentIntermediateNode) = visitDefault(node)
    open fun visitComponentTypeInferenceMethod(node: ComponentTypeInferenceMethodIntermediateNode) = visitDefault(node)
    open fun visitMarkupElement(node: MarkupElementIntermediateNode) = visitDefault(node)
    open fun visitMarkupBlock(node: MarkupBlockIntermediateNode) = visitDefault(node)
    open fun visitReferenceCapture(node: ReferenceCaptureIntermediateNode) = visitDefault(node)
    open fun visitSetKey(node: SetKeyIntermediateNode) = visitDefault(node)
    open fun visitSplat(node: SplatIntermediateNode) = visitDefault(node)
    open fun visitRenderMode(node: RenderModeIntermediateNode) = visitDefault(node)
    open fun visitFormName(node: FormNameIntermediateNode) = visitDefault(node)
}

/** `IExtensionIntermediateNodeVisitor<TNode>`: a visitor that knows one extension node class; [ExtensionIntermediateNode.acceptExtensionNode] asks for it. */
interface ExtensionIntermediateNodeVisitor<T : ExtensionIntermediateNode> {
    val extensionNodeClass: Class<T>
    fun visitExtension(node: T)
}

/** `IntermediateNodeWalker`: visits the children by default, keeping the ancestors. */
abstract class IntermediateNodeWalker : IntermediateNodeVisitor() {
    private val ancestorStack = ArrayList<IntermediateNode>()

    /** `Ancestors`: the nearest first. */
    protected val ancestors: List<IntermediateNode> get() = ancestorStack.asReversed()
    protected val parent: IntermediateNode? get() = ancestorStack.lastOrNull()

    override fun visitDefault(node: IntermediateNode) {
        val children = node.children
        if (children.isEmpty()) return
        ancestorStack.add(node)
        try {
            var i = 0
            while (i < children.size) {
                visit(children[i])
                i++
            }
        } finally {
            ancestorStack.removeAt(ancestorStack.size - 1)
        }
    }
}
