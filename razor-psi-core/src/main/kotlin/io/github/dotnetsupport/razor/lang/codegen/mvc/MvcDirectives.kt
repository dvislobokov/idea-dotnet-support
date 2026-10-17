package io.github.dotnetsupport.razor.lang.codegen.mvc

import io.github.dotnetsupport.razor.lang.codegen.RazorProjectEngineBuilder
import io.github.dotnetsupport.razor.lang.intermediate.*
import io.github.dotnetsupport.razor.lang.syntax.DirectiveDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorDirectives
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/**
 * The directives of MVC views and pages (`InjectDirective.cs`, `ModelDirective.cs`, `PageDirective.cs`): the descriptors — the parser's
 * instances of [RazorDirectives], so that a directive node of the parsed tree is `===` the descriptor — their registration and the
 * lookups other passes use. The nested `Pass` classes of `@inject` and `@model` are [InjectDirectivePass] and [ModelDirectivePass].
 */

/** `InjectDirective`: `@inject Type Name`. */
object InjectDirective {
    val directive: DirectiveDescriptor = RazorDirectives.mvcInject

    fun register(builder: RazorProjectEngineBuilder, considerNullabilityEnforcement: Boolean): RazorProjectEngineBuilder {
        builder.addDirective(directive)
        builder.features.add(InjectDirectivePass())
        builder.addTargetExtension(InjectTargetExtension(considerNullabilityEnforcement))
        return builder
    }

    /** `InjectDirective.Visitor`: the first class and the `@inject` directives, malformed ones included. */
    internal class Visitor : IntermediateNodeWalker() {
        var classNode: ClassDeclarationIntermediateNode? = null
            private set
        val directives: MutableList<IntermediateNode> = ArrayList()

        override fun visitClassDeclaration(node: ClassDeclarationIntermediateNode) {
            if (classNode == null) classNode = node
            super.visitClassDeclaration(node)
        }

        override fun visitDirective(node: DirectiveIntermediateNode) {
            if (node.directive === directive) directives.add(node)
        }

        override fun visitMalformedDirective(node: MalformedDirectiveIntermediateNode) {
            if (node.directive === directive) directives.add(node)
        }
    }
}

/** `ModelDirective`: `@model Type`. */
object ModelDirective {
    val directive: DirectiveDescriptor = RazorDirectives.model

    /** `RazorPageDocumentClassifierPass.RazorPageDocumentKind`. */
    private const val RAZOR_PAGE_DOCUMENT_KIND = "mvc.1.0.razor-page"

    fun register(builder: RazorProjectEngineBuilder): RazorProjectEngineBuilder {
        builder.addDirective(directive)
        builder.features.add(ModelDirectivePass())
        return builder
    }

    /** The model type: the last `@model`, else the page's own class for a Razor Page, else `dynamic`. */
    fun getModelType(document: DocumentIntermediateNode): IntermediateToken = getModelType(document, Visitor())

    internal fun getModelType(document: DocumentIntermediateNode, visitor: Visitor): IntermediateToken {
        visitor.visit(document)

        for (i in visitor.modelDirectives.indices.reversed()) {
            val directive = visitor.modelDirectives[i]

            val tokens = directive.tokens
            if (tokens.isNotEmpty()) return IntermediateNodeFactory.csharpToken(tokens[0].content!!, tokens[0].source)
        }

        return if (document.documentKind == RAZOR_PAGE_DOCUMENT_KIND) IntermediateNodeFactory.csharpToken(visitor.classNode!!.name!!)
        else IntermediateNodeFactory.csharpToken("dynamic")
    }

    /** `ModelDirective.Visitor`: the first namespace and class and the `@model` directives. */
    internal class Visitor : IntermediateNodeWalker() {
        var namespaceNode: NamespaceDeclarationIntermediateNode? = null
            private set
        var classNode: ClassDeclarationIntermediateNode? = null
            private set
        val modelDirectives: MutableList<DirectiveIntermediateNode> = ArrayList()

        override fun visitNamespaceDeclaration(node: NamespaceDeclarationIntermediateNode) {
            if (namespaceNode == null) namespaceNode = node
            super.visitNamespaceDeclaration(node)
        }

        override fun visitClassDeclaration(node: ClassDeclarationIntermediateNode) {
            if (classNode == null) classNode = node
            super.visitClassDeclaration(node)
        }

        override fun visitDirective(node: DirectiveIntermediateNode) {
            if (node.directive === directive) modelDirectives.add(node)
        }
    }
}

/** `PageDirective`: `@page "route"` of Razor Pages; [tryGetPageDirective] finds it in a document. */
class PageDirective private constructor(val routeTemplate: String?, val directiveNode: IntermediateNode, val source: SourceSpan?) {
    companion object {
        val directive: DirectiveDescriptor = RazorDirectives.mvcPage

        fun register(builder: RazorProjectEngineBuilder): RazorProjectEngineBuilder {
            builder.addDirective(directive)
            return builder
        }

        /** `TryGetPageDirective`: the directive (a malformed one only if there is no well-formed one), null when there is none. */
        fun tryGetPageDirective(documentNode: DocumentIntermediateNode): PageDirective? {
            val visitor = Visitor()
            for (i in 0 until documentNode.children.size) visitor.visit(documentNode.children[i])

            val directiveTokens = visitor.directiveTokens ?: return null

            val tokens = directiveTokens.toList()
            var routeTemplate: String? = null
            var sourceSpan: SourceSpan? = null
            if (tokens.isNotEmpty()) {
                routeTemplate = trimQuotes(tokens[0].content!!)
                sourceSpan = tokens[0].source
            }

            return PageDirective(routeTemplate, visitor.directiveNode!!, sourceSpan)
        }

        /** Malformed tokens are not captured: the content is always a quoted string here. */
        private fun trimQuotes(content: String): String {
            assert(content.length >= 2) // Debug.Assert
            assert(content.startsWith("\"")) // Debug.Assert
            assert(content.endsWith("\"")) // Debug.Assert

            return content.substring(1, content.length - 1)
        }
    }

    private class Visitor : IntermediateNodeWalker() {
        var directiveNode: IntermediateNode? = null
            private set
        var directiveTokens: List<DirectiveTokenIntermediateNode>? = null
            private set

        override fun visitDirective(node: DirectiveIntermediateNode) {
            if (node.directive === directive) {
                directiveNode = node
                directiveTokens = node.tokens
            }
        }

        override fun visitMalformedDirective(node: MalformedDirectiveIntermediateNode) {
            if (directiveTokens == null && node.directive === directive) {
                directiveNode = node
                directiveTokens = node.tokens
            }
        }
    }
}
