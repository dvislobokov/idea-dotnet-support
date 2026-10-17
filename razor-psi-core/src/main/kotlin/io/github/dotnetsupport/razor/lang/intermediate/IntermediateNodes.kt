package io.github.dotnetsupport.razor.lang.intermediate

import io.github.dotnetsupport.razor.lang.codegen.CodeRenderingContext
import io.github.dotnetsupport.razor.lang.codegen.CodeTarget
import io.github.dotnetsupport.razor.lang.codegen.RazorCodeGenerationOptions
import io.github.dotnetsupport.razor.lang.syntax.DirectiveDescriptor
import io.github.dotnetsupport.razor.lang.syntax.DirectiveTokenDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticFactory
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/** The nodes of the document structure and of the content; the tag helper, component and unresolved nodes are in their own files. */

class DocumentIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var documentKind: String? = null
    var declDocumentNode: DocumentIntermediateNode? = null
    var fallbackDiscoveryDeclDocumentNode: DocumentIntermediateNode? = null
    var fallbackComponentTypeName: String? = null
    var options: RazorCodeGenerationOptions? = null
    var target: CodeTarget? = null

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitDocument(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(documentKind)
        formatter.writeProperty("DocumentKind", documentKind)
    }

    override fun cloneNode(): IntermediateNode = DocumentIntermediateNode().also {
        it.documentKind = documentKind
        it.declDocumentNode = declDocumentNode
        it.fallbackDiscoveryDeclDocumentNode = fallbackDiscoveryDeclDocumentNode
        it.fallbackComponentTypeName = fallbackComponentTypeName
        it.options = options
        it.target = target
    }

    fun findPrimaryClass(): ClassDeclarationIntermediateNode? = findNode<ClassDeclarationIntermediateNode> { it.isPrimaryClass }
    fun findPrimaryMethod(): MethodDeclarationIntermediateNode? = findNode<MethodDeclarationIntermediateNode> { it.isPrimaryMethod }
    fun findPrimaryNamespace(): NamespaceDeclarationIntermediateNode? = findNode<NamespaceDeclarationIntermediateNode> { it.isPrimaryNamespace }

    /** `FindDirectiveReferences`: pre-order, with the parent of each. */
    fun findDirectiveReferences(directive: DirectiveDescriptor): List<IntermediateNodeReference<DirectiveIntermediateNode>> {
        val results = ArrayList<IntermediateNodeReference<DirectiveIntermediateNode>>()
        val stack = ArrayList<Pair<IntermediateNode, IntermediateNode?>>()
        stack.add(this to null)
        while (stack.isNotEmpty()) {
            val (node, parent) = stack.removeAt(stack.size - 1)
            if (node is DirectiveIntermediateNode && node.directive === directive && parent != null) results.add(IntermediateNodeReference(node, parent))
            for (i in node.children.indices.reversed()) stack.add(node.children[i] to node)
        }
        return results
    }
}

abstract class MemberDeclarationIntermediateNode : IntermediateNode()

/** `BaseTypeWithModel`: `RazorPage<TModel>` split into the base type, `<`, `TModel`, `>` so that `@model` can replace the model type. */
class BaseTypeWithModel(baseType: String, location: SourceSpan? = null) {
    val baseType: IntermediateToken
    val greaterThan: IntermediateToken?
    var modelType: IntermediateToken?
    val lessThan: IntermediateToken?

    init {
        if (baseType.endsWith(MODEL_GENERIC_PARAMETER)) {
            this.baseType = IntermediateNodeFactory.csharpToken(baseType.substring(0, baseType.length - MODEL_GENERIC_PARAMETER.length))
            greaterThan = IntermediateNodeFactory.csharpToken("<")
            modelType = IntermediateNodeFactory.csharpToken("TModel")
            lessThan = IntermediateNodeFactory.csharpToken(">")
            if (location != null) {
                val greaterThanIndex = baseType.length - MODEL_GENERIC_PARAMETER.length
                this.baseType.source = location.slice(0, greaterThanIndex)
                greaterThan.source = location.slice(greaterThanIndex, 1)
                modelType!!.source = location.slice(greaterThanIndex + 1, baseType.length - 1 - (greaterThanIndex + 1))
                lessThan.source = location.slice(baseType.length - 1, 1)
            }
        } else {
            this.baseType = IntermediateNodeFactory.csharpToken(baseType, location)
            greaterThan = null
            modelType = null
            lessThan = null
        }
    }

    companion object {
        private const val MODEL_GENERIC_PARAMETER = "<TModel>"
    }
}

class TypeParameter(name: String, nameSource: SourceSpan? = null, constraints: String? = null, constraintsSource: SourceSpan? = null) {
    val name: IntermediateToken = IntermediateNodeFactory.csharpToken(name, nameSource)
    val constraints: IntermediateToken? = constraints?.let { IntermediateNodeFactory.csharpToken(it, constraintsSource) }
}

class MethodParameter(val name: String, val type: String, val modifiers: List<String> = emptyList())

class ClassDeclarationIntermediateNode : MemberDeclarationIntermediateNode() {
    var name: String? = null
    var baseType: BaseTypeWithModel? = null
    var modifiers: List<String> = emptyList()
    var interfaces: List<IntermediateToken> = emptyList()
    var typeParameters: List<TypeParameter> = emptyList()
    var isPrimaryClass: Boolean = false
    var nullableContext: Boolean = false
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitClassDeclaration(this)

    override fun cloneNode(): IntermediateNode = ClassDeclarationIntermediateNode().also {
        it.name = name
        it.baseType = baseType
        it.modifiers = modifiers
        it.interfaces = interfaces
        it.typeParameters = typeParameters
        it.isPrimaryClass = isPrimaryClass
        it.nullableContext = nullableContext
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(name)
        formatter.writeProperty("Name", name)
        formatter.writeProperty("Interfaces", interfaces.joinToString(", ") { it.content })
        formatter.writeProperty("Modifiers", modifiers.joinToString(", "))
        formatter.writeProperty("TypeParameters", typeParameters.joinToString(", ") { it.name.content })
    }
}

class MethodDeclarationIntermediateNode : MemberDeclarationIntermediateNode() {
    var name: String? = null
    var returnType: String? = null
    var modifiers: List<String> = emptyList()
    var parameters: List<MethodParameter> = emptyList()
    var isPrimaryMethod: Boolean = false
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitMethodDeclaration(this)

    override fun cloneNode(): IntermediateNode = MethodDeclarationIntermediateNode().also {
        it.name = name
        it.returnType = returnType
        it.modifiers = modifiers
        it.parameters = parameters
        it.isPrimaryMethod = isPrimaryMethod
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(name)
        formatter.writeProperty("Name", name)
        formatter.writeProperty("Modifiers", modifiers.joinToString(", "))
        formatter.writeProperty("Parameters", parameters.joinToString(", ") { p -> p.modifiers.joinToString("") { "$it " } + p.type + " " + p.name })
        formatter.writeProperty("ReturnType", returnType)
    }
}

class FieldDeclarationIntermediateNode(val name: String, val type: String) : MemberDeclarationIntermediateNode() {
    var modifiers: List<String> = emptyList()
    var suppressWarnings: List<String> = emptyList()
    var isTagHelperField: Boolean = false
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitFieldDeclaration(this)

    override fun cloneNode(): IntermediateNode = FieldDeclarationIntermediateNode(name, type).also {
        it.modifiers = modifiers
        it.suppressWarnings = suppressWarnings
        it.isTagHelperField = isTagHelperField
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(name)
        formatter.writeProperty("Name", name)
        formatter.writeProperty("Type", type)
        formatter.writeProperty("Modifiers", modifiers.joinToString(" "))
    }
}

class PropertyDeclarationIntermediateNode(val name: String, val type: IntermediateToken, val expressionBody: String) : MemberDeclarationIntermediateNode() {
    var modifiers: List<String> = emptyList()
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitPropertyDeclaration(this)

    override fun cloneNode(): IntermediateNode = PropertyDeclarationIntermediateNode(name, type.clone() as IntermediateToken, expressionBody).also {
        it.modifiers = modifiers
        it.isSynthesizedHelper = isSynthesizedHelper
    }
}

class NamespaceDeclarationIntermediateNode : IntermediateNode() {
    var name: String? = null
    var isPrimaryNamespace: Boolean = false
    var isGenericTyped: Boolean = false
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitNamespaceDeclaration(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(name)
        formatter.writeProperty("Name", name)
    }

    override fun cloneNode(): IntermediateNode = NamespaceDeclarationIntermediateNode().also {
        it.name = name
        it.isPrimaryNamespace = isPrimaryNamespace
        it.isGenericTyped = isGenericTyped
        it.isSynthesizedHelper = isSynthesizedHelper
    }
}

class UsingDirectiveIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY
    var appendLineDefaultAndHidden: Boolean = false
    var content: String? = null
    var hasExplicitSemicolon: Boolean = false

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitUsingDirective(this)

    override fun cloneNode(): IntermediateNode = UsingDirectiveIntermediateNode().also {
        it.appendLineDefaultAndHidden = appendLineDefaultAndHidden
        it.content = content
        it.hasExplicitSemicolon = hasExplicitSemicolon
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(content)
        formatter.writeProperty("Content", content)
    }
}

class DirectiveIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var directiveName: String? = null
    var directive: DirectiveDescriptor? = null
    val tokens: List<DirectiveTokenIntermediateNode> get() = children.filterIsInstance<DirectiveTokenIntermediateNode>()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitDirective(this)

    override fun cloneNode(): IntermediateNode = DirectiveIntermediateNode().also {
        it.directiveName = directiveName
        it.directive = directive
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(directiveName)
        formatter.writeProperty("Directive", directive?.displayName)
        formatter.writeProperty("DirectiveName", directiveName)
    }
}

class MalformedDirectiveIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var directiveName: String? = null
    var directive: DirectiveDescriptor? = null
    val tokens: List<DirectiveTokenIntermediateNode> get() = children.filterIsInstance<DirectiveTokenIntermediateNode>()

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitMalformedDirective(this)

    override fun cloneNode(): IntermediateNode = MalformedDirectiveIntermediateNode().also {
        it.directiveName = directiveName
        it.directive = directive
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(directiveName)
        formatter.writeProperty("Directive", directive?.displayName)
        formatter.writeProperty("DirectiveName", directiveName)
    }
}

class DirectiveTokenIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY
    var content: String? = null
    var directiveToken: DirectiveTokenDescriptor? = null

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitDirectiveToken(this)

    override fun cloneNode(): IntermediateNode = DirectiveTokenIntermediateNode().also {
        it.content = content
        it.directiveToken = directiveToken
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(content)
        formatter.writeProperty("Content", content)
    }
}

class CSharpCodeIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitCSharpCode(this)
    override fun cloneNode(): IntermediateNode = CSharpCodeIntermediateNode().also { it.isSynthesizedHelper = isSynthesizedHelper }
    override fun formatNode(formatter: IntermediateNodeFormatter) = formatter.writeChildren(children)
}

class CSharpExpressionIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitCSharpExpression(this)
    override fun cloneNode(): IntermediateNode = CSharpExpressionIntermediateNode().also { it.isSynthesizedHelper = isSynthesizedHelper }
    override fun formatNode(formatter: IntermediateNodeFormatter) = formatter.writeChildren(children)
}

class HtmlContentIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var hasEncodedContent: Boolean = false
    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitHtml(this)
    override fun cloneNode(): IntermediateNode = HtmlContentIntermediateNode().also {
        it.hasEncodedContent = hasEncodedContent
        it.isSynthesizedHelper = isSynthesizedHelper
    }
    override fun formatNode(formatter: IntermediateNodeFormatter) = formatter.writeChildren(children)
}

class HtmlAttributeIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var attributeNameExpression: CSharpExpressionIntermediateNode? = null
    var attributeName: String? = null
    var prefix: String? = null
    var suffix: String? = null
    var eventUpdatesAttributeName: String? = null
    var originalAttributeName: String? = null

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitHtmlAttribute(this)

    override fun cloneNode(): IntermediateNode = HtmlAttributeIntermediateNode().also {
        it.attributeName = attributeName
        it.attributeNameExpression = attributeNameExpression?.clone() as CSharpExpressionIntermediateNode?
        it.prefix = prefix
        it.suffix = suffix
        it.eventUpdatesAttributeName = eventUpdatesAttributeName
        it.originalAttributeName = originalAttributeName
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(attributeName)
        formatter.writeProperty("AttributeName", attributeName)
        formatter.writeProperty("AttributeNameExpression", attributeNameExpression?.findDescendantNodes<IntermediateToken>()?.joinToString("") { it.content })
        formatter.writeProperty("Prefix", prefix)
        formatter.writeProperty("Suffix", suffix)
        formatter.writeProperty("EventUpdatesAttributeName", eventUpdatesAttributeName)
    }
}

class HtmlAttributeValueIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var prefix: String? = null
    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitHtmlAttributeValue(this)
    override fun cloneNode(): IntermediateNode = HtmlAttributeValueIntermediateNode().also {
        it.prefix = prefix
        it.isSynthesizedHelper = isSynthesizedHelper
    }
    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeChildren(children)
        formatter.writeProperty("Prefix", prefix)
    }
}

class CSharpExpressionAttributeValueIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var prefix: String? = null
    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitCSharpExpressionAttributeValue(this)
    override fun cloneNode(): IntermediateNode = CSharpExpressionAttributeValueIntermediateNode().also {
        it.prefix = prefix
        it.isSynthesizedHelper = isSynthesizedHelper
    }
    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeChildren(children)
        formatter.writeProperty("Prefix", prefix)
    }
}

class CSharpCodeAttributeValueIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var prefix: String? = null
    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitCSharpCodeAttributeValue(this)
    override fun cloneNode(): IntermediateNode = CSharpCodeAttributeValueIntermediateNode().also {
        it.prefix = prefix
        it.isSynthesizedHelper = isSynthesizedHelper
    }
    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeChildren(children)
        formatter.writeProperty("Prefix", prefix)
    }
}

class MarkupElementIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var tagName: String? = null

    val attributes: List<HtmlAttributeIntermediateNode> get() = children.filterIsInstance<HtmlAttributeIntermediateNode>()
    val captures: List<ReferenceCaptureIntermediateNode> get() = children.filterIsInstance<ReferenceCaptureIntermediateNode>()
    val setKeys: List<SetKeyIntermediateNode> get() = children.filterIsInstance<SetKeyIntermediateNode>()
    val body: List<IntermediateNode>
        get() = children.filter {
            it !is ComponentAttributeIntermediateNode && it !is HtmlAttributeIntermediateNode && it !is SplatIntermediateNode &&
                it !is SetKeyIntermediateNode && it !is ReferenceCaptureIntermediateNode && it !is FormNameIntermediateNode
        }

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitMarkupElement(this)
    override fun cloneNode(): IntermediateNode = MarkupElementIntermediateNode().also {
        it.tagName = tagName
        it.isSynthesizedHelper = isSynthesizedHelper
    }
    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(tagName)
        formatter.writeProperty("TagName", tagName)
    }
}

class MarkupBlockIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var content: String? = null
    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitMarkupBlock(this)
    override fun formatNode(formatter: IntermediateNodeFormatter) = formatter.writeContent(content)
}

/** `ExtensionIntermediateNode`: a node of an extension (a section, a template, a tag helper runtime node...) that writes itself. */
abstract class ExtensionIntermediateNode : IntermediateNode() {
    abstract fun writeNode(target: CodeTarget, context: CodeRenderingContext)

    protected fun <T : ExtensionIntermediateNode> acceptExtensionNode(node: T, visitor: IntermediateNodeVisitor) {
        @Suppress("UNCHECKED_CAST")
        val typed = visitor as? ExtensionIntermediateNodeVisitor<T>
        if (typed == null || !typed.extensionNodeClass.isInstance(node)) visitor.visitExtension(node) else typed.visitExtension(node)
    }

    protected fun reportMissingCodeTargetExtension(context: CodeRenderingContext, dependency: Class<*>) {
        context.addDiagnostic(RazorDiagnosticFactory.codeTarget_UnsupportedExtension(context.documentKind ?: "", dependency))
    }
}
