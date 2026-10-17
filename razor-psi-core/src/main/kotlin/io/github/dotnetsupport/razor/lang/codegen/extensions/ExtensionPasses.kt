package io.github.dotnetsupport.razor.lang.codegen.extensions

import io.github.dotnetsupport.razor.lang.intermediate.CommonModifiers
import io.github.dotnetsupport.razor.lang.codegen.ComponentDocumentClassifierPass
import io.github.dotnetsupport.razor.lang.codegen.IntermediateNodePassBase
import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorDirectiveClassifierPass
import io.github.dotnetsupport.razor.lang.codegen.RazorOptimizationPass
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentCodeDirective
import io.github.dotnetsupport.razor.lang.intermediate.BaseTypeWithModel
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DirectiveTokenIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeFactory
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeReference
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantNodes
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantReferences

/** The passes of `Language/Extensions` that need no tag helper descriptors; the tag helper ones are in `TagHelperPasses.kt`. */

/** `FunctionsDirectivePass`: the members of `@functions` (and `@code` of a component) move into the class, in source order. */
class FunctionsDirectivePass : IntermediateNodePassBase(), RazorDirectiveClassifierPass {
    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val clazz = documentNode.findPrimaryClass() ?: return
        val directiveNodes = ArrayList<IntermediateNodeReference<DirectiveIntermediateNode>>()
        directiveNodes.addAll(documentNode.findDirectiveReferences(FunctionsDirective.directive))
        if (codeDocument.fileKind.isComponent) directiveNodes.addAll(documentNode.findDirectiveReferences(ComponentCodeDirective.directive))
        directiveNodes.sortWith(compareBy(nullsFirst()) { it.node.source?.absoluteIndex })
        for (directiveReference in directiveNodes) {
            val node = directiveReference.node
            clazz.children.addAll(node.children)
            directiveReference.remove()
        }
    }
}

class ImplementsDirectivePass : IntermediateNodePassBase(), RazorDirectiveClassifierPass {
    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val clazz = documentNode.findPrimaryClass() ?: return
        val interfaces = ArrayList<IntermediateToken>(clazz.interfaces)
        for (implements in documentNode.findDirectiveReferences(ImplementsDirective.directive)) {
            val token = implements.node.tokens.firstOrNull()
            if (token != null) interfaces.add(IntermediateNodeFactory.csharpToken(token.content!!, token.source))
        }
        if (interfaces.size > clazz.interfaces.size) clazz.interfaces = interfaces
    }
}

class InheritsDirectivePass : IntermediateNodePassBase(), RazorDirectiveClassifierPass {
    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val clazz = documentNode.findPrimaryClass() ?: return
        for (inherits in documentNode.findDirectiveReferences(InheritsDirective.directive)) {
            val token = inherits.node.tokens.firstOrNull()
            if (token != null) {
                clazz.baseType = BaseTypeWithModel(token.content!!, token.source)
                break
            }
        }
    }
}

class AttributeDirectivePass : IntermediateNodePassBase(), RazorDirectiveClassifierPass {
    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val namespace = documentNode.findPrimaryNamespace() ?: return
        val clazz = documentNode.findPrimaryClass() ?: return
        var classIndex = namespace.children.indexOf(clazz)
        for (attribute in documentNode.findDirectiveReferences(AttributeDirective.directive)) {
            val token = attribute.node.tokens.firstOrNull()
            if (token != null) {
                val node = CSharpCodeIntermediateNode().also { it.source = token.source }
                node.children.add(IntermediateNodeFactory.csharpToken(token.content!!, token.source))
                namespace.children.add(classIndex++, node)
            }
        }
    }
}

class DocumentationDirectivePass : IntermediateNodePassBase(), RazorDirectiveClassifierPass {
    override val order: Int get() = -1

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val namespace = documentNode.findPrimaryNamespace() ?: return
        val clazz = documentNode.findPrimaryClass() ?: return
        val references = documentNode.findDescendantReferences<DocumentationIntermediateNode>()
        var hasLocalDocumentation = false
        for (reference in references) {
            reference.remove()
            if (!hasLocalDocumentation && !reference.node.isImported) {
                hasLocalDocumentation = true
                namespace.children.add(namespace.children.indexOf(clazz), reference.node)
            }
        }
    }
}

class SectionDirectivePass : IntermediateNodePassBase(), RazorDirectiveClassifierPass {
    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        documentNode.findPrimaryClass() ?: return
        for (directive in documentNode.findDirectiveReferences(SectionDirective.directive)) {
            val sectionName = directive.node.tokens.firstOrNull()?.content
            val section = SectionIntermediateNode().also { it.sectionName = sectionName }
            var i = 0
            while (i < directive.node.children.size) {
                if (directive.node.children[i] !is DirectiveTokenIntermediateNode) break
                i++
            }
            while (i != directive.node.children.size) {
                section.children.add(directive.node.children[i])
                directive.node.children.removeAt(i)
            }
            directive.insertAfter(section)
        }
    }
}

/** `MetadataAttributePass`: `RazorCompiledItemAttribute` on the document and `RazorSourceChecksumAttribute` per source before the class (not for components). */
class MetadataAttributePass : IntermediateNodePassBase(), RazorOptimizationPass {
    private var identifierFeature: IMetadataIdentifierFeature? = null

    override fun onInitialized() {
        identifierFeature = engine.getFeatures<IMetadataIdentifierFeature>().firstOrNull()
    }

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val options = documentNode.options
        if (options == null || options.suppressMetadataAttributes) return
        if (documentNode.documentKind == ComponentDocumentClassifierPass.COMPONENT_DOCUMENT_KIND) return
        val namespace = documentNode.findPrimaryNamespace() ?: return
        val clazz = documentNode.findPrimaryClass() ?: return
        val className = clazz.name
        if (className.isNullOrEmpty()) return
        val documentKind = documentNode.documentKind ?: return
        val identifier = identifierFeature?.getIdentifier(codeDocument, codeDocument.source) ?: return

        documentNode.children.add(0, RazorCompiledItemAttributeIntermediateNode().also {
            it.typeName = if (namespace.name.isNullOrEmpty()) className else namespace.name + "." + className
            it.kind = documentKind
            it.identifier = identifier
        })

        var insert = -1
        for (j in namespace.children.indices) {
            if (namespace.children[j] === clazz) {
                insert = j
                break
            }
        }
        if (insert < 0) return
        if (options.suppressMetadataSourceChecksumAttributes) return

        val checksum = codeDocument.source.checksumBytes
        if (checksum.isEmpty()) return
        namespace.children.add(insert++, RazorSourceChecksumAttributeIntermediateNode().also {
            it.checksum = checksum
            it.checksumAlgorithm = SourceHashAlgorithm.Sha256
            it.identifier = identifier
        })

        val feature = identifierFeature!!
        for (import in codeDocument.imports) {
            val importChecksum = import.checksumBytes
            val importIdentifier = feature.getIdentifier(codeDocument, import)
            if (importChecksum.isEmpty() || importIdentifier == null) continue
            namespace.children.add(insert++, RazorSourceChecksumAttributeIntermediateNode().also {
                it.checksum = importChecksum
                it.checksumAlgorithm = SourceHashAlgorithm.Sha256
                it.identifier = importIdentifier
            })
        }
    }
}

/** `DirectiveRemovalOptimizationPass`: the directive nodes go, their diagnostics move to the document. */
class DirectiveRemovalOptimizationPass : IntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = DEFAULT_FEATURE_ORDER + 50

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        for (reference in documentNode.findDescendantReferences<DirectiveIntermediateNode>()) {
            documentNode.addDiagnosticsFromNode(reference.node)
            reference.remove()
        }
    }
}

/** `EliminateMethodBodyPass`: with `SuppressPrimaryMethodBody`, an empty render method and the warnings of unused fields silenced. */
class EliminateMethodBodyPass : IntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = Int.MAX_VALUE

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!codeDocument.codeGenerationOptions.suppressPrimaryMethodBody) return
        val method = documentNode.findPrimaryMethod() ?: return
        method.children.clear()
        val newLine = System.lineSeparator()
        documentNode.children.add(documentNode.children.indexOf(documentNode.findPrimaryNamespace()!!), CSharpCodeIntermediateNode().also {
            it.children.add(IntermediateNodeFactory.csharpToken("#pragma warning disable 0414$newLine"))
            it.children.add(IntermediateNodeFactory.csharpToken("#pragma warning disable 0649$newLine"))
            it.children.add(IntermediateNodeFactory.csharpToken("#pragma warning disable 0169$newLine"))
        })
    }
}

/** `ViewCssScopePass`: the CSS scope attribute appended to the start tags of a view or page. */
class ViewCssScopePass : IntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = 110

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val cssScope = codeDocument.codeGenerationOptions.cssScope
        if (cssScope.isNullOrEmpty()) return
        if (documentNode.documentKind != "mvc.1.0.view" && documentNode.documentKind != "mvc.1.0.razor-page") return
        val scopeWithSeparator = " $cssScope"
        var previousToken: IntermediateToken? = null
        for (node in documentNode.findDescendantNodes<HtmlContentIntermediateNode>()) previousToken = processElement(node, scopeWithSeparator, previousToken)
    }

    private fun processElement(node: HtmlContentIntermediateNode, cssScope: String, previous: IntermediateToken?): IntermediateToken? {
        var previousToken = previous
        var i = 0
        while (i < node.children.size) {
            val child = node.children[i]
            if (child is HtmlIntermediateToken) {
                if (isValidElement(child, previousToken)) {
                    node.children.add(i + 1, IntermediateNodeFactory.htmlToken(cssScope))
                    i++
                }
                previousToken = child
            }
            i++
        }
        return previousToken
    }

    private fun isValidElement(token: IntermediateToken, previousToken: IntermediateToken?): Boolean {
        var content = token.content
        if (previousToken?.content == "<" && content.isNotEmpty() && content[0] != '<') {
            // the tag name follows a lone `<`
        } else if (content.length >= 2 && content[0] == '<') {
            content = content.substring(1)
        } else return false
        return !content.startsWith("/") && !content.startsWith("!") &&
            !content.equals("head", ignoreCase = true) && !content.equals("meta", ignoreCase = true) && !content.equals("title", ignoreCase = true) &&
            !content.equals("link", ignoreCase = true) && !content.equals("base", ignoreCase = true) && !content.equals("script", ignoreCase = true) &&
            !content.equals("style", ignoreCase = true) && !content.equals("html", ignoreCase = true)
    }
}
