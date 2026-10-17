package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.ComponentDocumentClassifierPass
import io.github.dotnetsupport.razor.lang.codegen.IntermediateNodePassBase
import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorOptimizationPass
import io.github.dotnetsupport.razor.lang.codegen.RazorProjectEngineBuilder
import io.github.dotnetsupport.razor.lang.codegen.extensions.TemplateIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.isNullOrDotNetWhiteSpace
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.ComponentAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentChildContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ExtensionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.FormNameIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeCollection
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeFactory
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeReference
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeWalker
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.MarkupElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MethodDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ReferenceCaptureIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.RenderModeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.SetKeyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.SplatIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperBodyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperDirectiveAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperDirectiveAttributeParameterIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperHtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperPropertyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantNodes
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantReferences
import io.github.dotnetsupport.razor.lang.syntax.AssetPathMetadata
import io.github.dotnetsupport.razor.lang.syntax.AttributeStructure
import io.github.dotnetsupport.razor.lang.syntax.PropertyMetadata
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.TagHelperCollection
import io.github.dotnetsupport.razor.lang.syntax.TagHelperKind
import java.util.IdentityHashMap
import java.util.TreeMap
import java.util.TreeSet

/**
 * The component passes (`Components/Component*Pass.cs`) and their registration. The larger ones are in their own files:
 * [ComponentLoweringPass], [ComponentBindLoweringPass], [ComponentGenericTypePass], the markup passes, the directive passes.
 */

/**
 * `RazorProjectEngine.AddComponentFeatures`, the passes after `ComponentDocumentClassifierPass` (which the caller adds): the directive
 * classifier, then the optimization passes in the compiler's order (each phase sorts its passes by `order`, stably).
 */
fun addComponentPasses(builder: RazorProjectEngineBuilder, razorLanguageVersion: RazorLanguageVersion) {
    // Directive Classifier
    builder.features.add(ComponentWhitespacePass())

    // Optimization
    builder.features.add(ComponentComplexAttributeContentPass())
    builder.features.add(ComponentLoweringPass())
    builder.features.add(ComponentEventHandlerLoweringPass())
    builder.features.add(ComponentKeyLoweringPass())
    builder.features.add(ComponentReferenceCaptureLoweringPass())
    builder.features.add(ComponentSplatLoweringPass())
    builder.features.add(ComponentFormNameLoweringPass())
    builder.features.add(ComponentBindLoweringPass())
    builder.features.add(ComponentRenderModeLoweringPass())
    builder.features.add(ComponentTildePathPass(razorLanguageVersion))
    builder.features.add(ComponentCssScopePass())
    builder.features.add(ComponentTemplateDiagnosticPass())
    builder.features.add(ComponentGenericTypePass())
    builder.features.add(ComponentChildContentDiagnosticPass())
    builder.features.add(ComponentMarkupDiagnosticPass())
    builder.features.add(ComponentMarkupBlockPass(razorLanguageVersion))
    builder.features.add(ComponentMarkupEncodingPass(razorLanguageVersion))
}

/** `ComponentIntermediateNodePassBase` (`ComponentIntermediateNodePassBase.cs`). */
abstract class ComponentIntermediateNodePassBase : IntermediateNodePassBase() {
    protected fun isComponentDocument(document: DocumentIntermediateNode): Boolean =
        document.documentKind == ComponentDocumentClassifierPass.COMPONENT_DOCUMENT_KIND
}

/** A set by reference that iterates in insertion order, as the compiler's reference-equality `HashSet` does when nothing is removed. */
internal class ReferenceOrderedSet<T : Any> : Iterable<T> {
    private val seen = IdentityHashMap<T, Boolean>()
    private val items = ArrayList<T>()

    fun add(item: T): Boolean {
        if (seen.put(item, true) != null) return false
        items.add(item)
        return true
    }

    override fun iterator(): Iterator<T> = items.iterator()
}

/** `string.IsNullOrWhiteSpace` of an intermediate token's content. */
private fun isNullOrWhiteSpace(value: String?): Boolean = value.isNullOrDotNetWhiteSpace()

/**
 * `ComponentWhitespacePass` (`ComponentWhitespacePass.cs`): trims the leading and trailing whitespace of the methods, elements and child
 * component bodies (before 5.0 only of `BuildRenderTree`, at its top level); `@preservewhitespace true` turns it off. Ordered into the
 * optimization phase (early, before markup lowering): a classifier pass would run ahead of the markup split and trim the markup method
 * bodies the split routes to the impl half.
 */
class ComponentWhitespacePass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = -500

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        // There's no benefit running the whitespace trimmer if we're not emitting the method bodies.
        if ((documentNode.options ?: codeDocument.codeGenerationOptions).suppressPrimaryMethodBody) return

        val razorLanguageVersion = codeDocument.parserOptions.languageVersion
        val useLegacyBehavior = razorLanguageVersion < RazorLanguageVersion.VERSION_5_0
        if (useLegacyBehavior) {
            // Prior to 5.0, the whitespace pass only applied to the BuildRenderTree method, and only removed the top-level leading and trailing whitespace
            val method = documentNode.findPrimaryMethod()
            if (method != null) {
                removeContiguousWhitespace(method.children, TraversalDirection.Forwards)
                removeContiguousWhitespace(method.children, TraversalDirection.Backwards)
            }
        } else {
            // From 5.0 onwards the pass removes leading and trailing whitespace from all methods, elements and child component blocks,
            // unless a directive disables it.

            // Respect @preservewhitespace directives
            if (preserveWhitespaceIsEnabled(documentNode)) return

            val clazz = documentNode.findPrimaryClass()
            if (clazz != null) Visitor().visit(clazz)
        }
    }

    private fun preserveWhitespaceIsEnabled(documentNode: DocumentIntermediateNode): Boolean {
        // If there's no @preservewhitespace attribute, the default is that we *don't* preserve whitespace
        var shouldPreserveWhitespace = false

        for (preserveWhitespaceDirective in documentNode.findDirectiveReferences(ComponentPreserveWhitespaceDirective.directive)) {
            val token = preserveWhitespaceDirective.node.tokens.firstOrNull()
            val shouldPreserveWhitespaceContent = token?.content
            if (shouldPreserveWhitespaceContent != null) shouldPreserveWhitespace = shouldPreserveWhitespaceContent == "true"
        }

        return shouldPreserveWhitespace
    }

    private enum class TraversalDirection { Forwards, Backwards }

    private class Visitor : IntermediateNodeWalker() {
        override fun visitMethodDeclaration(node: MethodDeclarationIntermediateNode) {
            removeContiguousWhitespace(node.children, TraversalDirection.Forwards)
            removeContiguousWhitespace(node.children, TraversalDirection.Backwards)
            visitDefault(node)
        }

        override fun visitMarkupElement(node: MarkupElementIntermediateNode) {
            removeContiguousWhitespace(node.children, TraversalDirection.Forwards)
            removeContiguousWhitespace(node.children, TraversalDirection.Backwards)
            visitDefault(node)
        }

        override fun visitTagHelperBody(node: TagHelperBodyIntermediateNode) {
            // The leading/trailing whitespace inside component child content: at the time this pass runs, ComponentChildContent is still
            // TagHelperBody in the tree.
            removeContiguousWhitespace(node.children, TraversalDirection.Forwards)
            removeContiguousWhitespace(node.children, TraversalDirection.Backwards)
            visitDefault(node)
        }

        override fun visitDefault(node: IntermediateNode) {
            // For any CSharpCodeIntermediateNode children, remove their preceding and trailing whitespace
            var childIndex = 0
            while (childIndex < node.children.size) {
                if (node.children[childIndex] is CSharpCodeIntermediateNode) {
                    childIndex -= removeContiguousWhitespace(node.children, TraversalDirection.Backwards, childIndex - 1)
                    removeContiguousWhitespace(node.children, TraversalDirection.Forwards, childIndex + 1)
                }
                childIndex++
            }

            super.visitDefault(node)
        }
    }

    private companion object {
        fun removeContiguousWhitespace(nodes: IntermediateNodeCollection, direction: TraversalDirection, startIndex: Int? = null): Int {
            var position = startIndex ?: if (direction == TraversalDirection.Forwards) 0 else nodes.size - 1
            var countRemoved = 0
            while (position >= 0 && position < nodes.size) {
                val node = nodes[position]
                val shouldRemoveNode: Boolean
                val shouldContinueIteration: Boolean

                when (node) {
                    is IntermediateToken -> {
                        shouldRemoveNode = isNullOrWhiteSpace(node.content)
                        shouldContinueIteration = shouldRemoveNode
                    }

                    is HtmlContentIntermediateNode -> {
                        removeContiguousWhitespace(node.children, direction)
                        shouldRemoveNode = node.children.size == 0
                        shouldContinueIteration = shouldRemoveNode
                    }

                    is MarkupElementIntermediateNode, is CSharpExpressionIntermediateNode, is TagHelperIntermediateNode -> {
                        // These node types may produce non-whitespace output at runtime
                        shouldRemoveNode = false
                        shouldContinueIteration = false
                    }

                    is CSharpCodeIntermediateNode -> {
                        shouldRemoveNode = false
                        shouldContinueIteration = false
                    }

                    else -> {
                        shouldRemoveNode = false
                        shouldContinueIteration = true // Because other types of nodes don't produce output
                    }
                }

                if (shouldRemoveNode) {
                    nodes.removeAt(position)
                    countRemoved++
                    if (direction == TraversalDirection.Forwards) position--
                }

                position += if (direction == TraversalDirection.Forwards) 1 else -1

                if (!shouldContinueIteration) break
            }

            return countRemoved
        }
    }
}

/**
 * `ComponentComplexAttributeContentPass` (`ComponentComplexAttributeContentPass.cs`): complex content (mixed C# and markup) in a
 * component attribute is an error (RZ9986); an explicit expression `@(...)` loses its parentheses (aspnet/Razor#2203, for lambdas).
 */
class ComponentComplexAttributeContentPass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    // Run before other Component passes
    override val order: Int get() = -1000

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        for (node in documentNode.findDescendantNodes<TagHelperIntermediateNode>()) processAttributes(node)
    }

    private fun processAttributes(node: TagHelperIntermediateNode) {
        for (i in node.children.indices.reversed()) {
            val child = node.children[i]
            if (child is TagHelperPropertyIntermediateNode && node.tagHelpers.any { it.kind == TagHelperKind.Component }) {
                processAttribute(node, child, child.attributeName)
            } else if (child is TagHelperHtmlAttributeIntermediateNode && node.tagHelpers.any { it.kind == TagHelperKind.Component }) {
                processAttribute(node, child, child.attributeName)
            } else if (child is TagHelperDirectiveAttributeIntermediateNode) {
                processAttribute(node, child, child.originalAttributeName)
            }
        }
    }

    private fun processAttribute(parent: IntermediateNode, node: IntermediateNode, attributeName: String) {
        var issueDiagnostic = false
        val children = node.children
        val only = if (children.size == 1) children[0] else null

        if (only is HtmlAttributeIntermediateNode && only.children.size > 1) {
            // This case can be hit for a 'string' attribute
            issueDiagnostic = true
        } else if (only is CSharpExpressionIntermediateNode && only.children.size > 1) {
            // An explicit @ inside the attribute 'escapes' the sugar of the codegen. The explicit expression is special-cased
            // (https://github.com/aspnet/Razor/issues/2203), as a tactical matter since it's important for lambdas.
            val c = only.children
            if (c.size == 3 && (c[0] as? IntermediateToken)?.content == "(" && (c[2] as? IntermediateToken)?.content == ")") {
                c.removeAt(2)
                c.removeAt(0)
            } else {
                issueDiagnostic = true
            }
        } else if (only is CSharpCodeIntermediateNode) {
            // This is the case when an attribute contains a code block @{ ... }. We don't support this.
            issueDiagnostic = true
        } else if (children.size > 1) {
            // This is the common case for 'mixed' content
            issueDiagnostic = true
        }

        if (issueDiagnostic) node.addDiagnostic(ComponentDiagnosticFactory.create_UnsupportedComplexContent(node, attributeName))
    }
}

/**
 * `ComponentEventHandlerLoweringPass` (`ComponentEventHandlerLoweringPass.cs`): `@onclick="..."` becomes an attribute whose value is
 * `EventCallback.Factory.Create<TEventArgs>(this, ...)`, `@onclick:preventDefault` / `:stopPropagation` the builder's calls; duplicates
 * fall back to the component's own parameter or are reported.
 */
class ComponentEventHandlerLoweringPass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = 50

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        // Nothing to do without the standard structure.
        documentNode.findPrimaryNamespace() ?: return
        documentNode.findPrimaryClass() ?: return

        // Each event handler *usage* is a tag helper property that is a descendant of a component or an element.
        val parents = ReferenceOrderedSet<IntermediateNode>()
        val references = documentNode.findDescendantReferences<TagHelperDirectiveAttributeIntermediateNode>()

        for (reference in references) parents.add(reference.parent)

        // We need to do something similar for directive attribute parameters like @onclick:preventDefault.
        val parameterReferences = documentNode.findDescendantReferences<TagHelperDirectiveAttributeParameterIntermediateNode>()

        for (parameterReference in parameterReferences) parents.add(parameterReference.parent)

        for (parent in parents) processDuplicates(parent)

        for (reference in references) {
            val node = reference.node

            // This node was removed as a duplicate, skip it.
            if (!reference.parent.children.contains(node)) continue

            if (node.tagHelper.kind == TagHelperKind.EventHandler) reference.replace(rewriteUsage(reference.parent, node))
        }

        for (parameterReference in parameterReferences) {
            val node = parameterReference.node

            // This node was removed as a duplicate, skip it.
            if (!parameterReference.parent.children.contains(node)) continue

            if (node.tagHelper.kind == TagHelperKind.EventHandler) parameterReference.replace(rewriteParameterUsage(node))
        }
    }

    private companion object {
        fun processDuplicates(parent: IntermediateNode) {
            // Reverse order because we will remove nodes. A 'property' node is duplicated when several tag helpers match the attribute, as
            // when a component also defines something like OnClick: the 'onclick' is removed and falls back to the component.
            for (i in parent.children.indices.reversed()) {
                val eventHandler = parent.children[i]
                if (eventHandler is TagHelperPropertyIntermediateNode && eventHandler.tagHelper.kind == TagHelperKind.EventHandler) {
                    for (j in parent.children.indices) {
                        val componentAttribute = parent.children[j]
                        if (componentAttribute is ComponentAttributeIntermediateNode &&
                            componentAttribute.tagHelper != null &&
                            componentAttribute.tagHelper!!.kind == TagHelperKind.Component &&
                            componentAttribute.attributeName == eventHandler.attributeName
                        ) {
                            // Found a duplicate - remove the 'fallback' in favor of the component's own handling.
                            parent.children.removeAt(i)
                            break
                        }
                    }
                }
            }

            // If we still have duplicates at this point then they are genuine conflicts.
            val duplicates = parent.children
                .filterIsInstance<TagHelperDirectiveAttributeIntermediateNode>()
                .filter { it.tagHelper.kind == TagHelperKind.EventHandler }
                .groupBy { it.attributeName }
                .filter { it.value.size > 1 }

            for ((key, duplicate) in duplicates) {
                parent.addDiagnostic(ComponentDiagnosticFactory.createEventHandler_Duplicates(parent.source, key, duplicate))

                for (property in duplicate) parent.children.remove(property)
            }

            val parameterDuplicates = parent.children
                .filterIsInstance<TagHelperDirectiveAttributeParameterIntermediateNode>()
                .filter { it.tagHelper.kind == TagHelperKind.EventHandler }
                .groupBy { it.attributeName }
                .filter { it.value.size > 1 }

            for ((key, duplicate) in parameterDuplicates) {
                parent.addDiagnostic(ComponentDiagnosticFactory.createEventHandlerParameter_Duplicates(parent.source, key, duplicate))

                for (property in duplicate) parent.children.remove(property)
            }
        }

        fun rewriteUsage(parent: IntermediateNode, node: TagHelperDirectiveAttributeIntermediateNode): IntermediateNode {
            val original = getAttributeContent(node)
            if (original.isEmpty()) {
                // This can happen in error cases, the parser will already have flagged this as an error, so ignore it.
                return node
            }

            // The content of the value node becomes `EventCallback.Factory.Create<T>(this, <code>)`: overloaded on string and T, so that the
            // code is in the right context for IntelliSense when typing in the attribute.
            val eventArgsType = node.tagHelper.getEventArgsType()!!

            val tokens = ArrayList<IntermediateToken>(original.size + 2)

            tokens.add(
                IntermediateNodeFactory.csharpToken(
                    "global::${ComponentsApi.EventCallback.factoryAccessor}.${ComponentsApi.EventCallbackFactory.createMethod}" +
                        "<${TypeNameHelper.getGloballyQualifiedNameIfNeeded(eventArgsType)}>(this, ",
                ),
            )

            tokens.addAll(original)

            tokens.add(IntermediateNodeFactory.csharpToken(")"))

            val attributeName = node.attributeName

            if (parent is MarkupElementIntermediateNode) {
                val result = HtmlAttributeIntermediateNode().also {
                    it.originalAttributeName = node.originalAttributeName
                    it.attributeName = attributeName
                    it.source = node.source
                    it.prefix = "$attributeName=\""
                    it.suffix = "\""
                }

                result.addDiagnosticsFromNode(node)

                val attributeValueNode = CSharpExpressionAttributeValueIntermediateNode()
                result.children.add(attributeValueNode)

                for (token in tokens) attributeValueNode.children.add(token)

                return result
            } else {
                val result = ComponentAttributeIntermediateNode.from(node, addChildren = false)
                result.originalAttributeName = node.originalAttributeName

                val expressionNode = CSharpExpressionIntermediateNode()

                for (token in tokens) expressionNode.children.add(token)

                result.children.add(expressionNode)

                return result
            }
        }

        fun getAttributeContent(node: IntermediateNode): List<IntermediateToken> {
            val template = node.findDescendantNodes<TemplateIntermediateNode>().firstOrNull()
            if (template != null) {
                // See comments in TemplateDiagnosticPass
                node.addDiagnostic(ComponentDiagnosticFactory.create_TemplateInvalidLocation(template.source))
                return listOf(IntermediateNodeFactory.csharpToken(""))
            }

            val only = if (node.children.size == 1) node.children[0] else null
            if (only is HtmlContentIntermediateNode) {
                // This case can be hit for a 'string' attribute. We want to turn it into an expression.
                val tokens = only.findDescendantNodes<IntermediateToken>()

                val content = "\"" + tokens.joinToString("") { it.content.replace("\"", "\\\"") } + "\""
                return listOf(IntermediateNodeFactory.csharpToken(content))
            }

            return node.findDescendantNodes<IntermediateToken>()
        }

        fun rewriteParameterUsage(node: TagHelperDirectiveAttributeParameterIntermediateNode): IntermediateNode {
            // builder.AddEventPreventDefaultAttribute(2, "onclick", true); // If minimized.
            // or
            // builder.AddEventPreventDefaultAttribute(2, "onclick", someBoolExpression); // If a bool expression is provided in the value.
            val eventHandlerMethod = when (node.boundAttributeParameter.name) {
                "preventDefault" -> ComponentsApi.RenderTreeBuilder.addEventPreventDefaultAttribute
                "stopPropagation" -> ComponentsApi.RenderTreeBuilder.addEventStopPropagationAttribute
                // Only a descriptor configured to expect another parameter gets here.
                else -> return node
            }

            val result = ComponentAttributeIntermediateNode.from(node, addChildren = false)
            result.originalAttributeName = node.originalAttributeName
            result.addAttributeMethodName = eventHandlerMethod

            if (node.attributeStructure != AttributeStructure.Minimized) {
                val tokens = getAttributeContent(node)
                val expressionNode = CSharpExpressionIntermediateNode()
                result.children.add(expressionNode)
                expressionNode.children.addAll(tokens)
            }

            return result
        }
    }
}

/** The token of `@key` / `@ref`: a single token, or a single token in an expression, that is not empty or white space. */
private fun determineSingleValueToken(attributeNode: TagHelperDirectiveAttributeIntermediateNode): IntermediateToken? {
    val children = attributeNode.children
    val only = if (children.size == 1) children[0] else null
    val foundToken = when {
        only is IntermediateToken -> only
        only is CSharpExpressionIntermediateNode && only.children.size == 1 && only.children[0] is IntermediateToken -> only.children[0] as IntermediateToken
        else -> null
    } ?: return null

    return if (!isNullOrWhiteSpace(foundToken.content)) foundToken else null
}

/** `ComponentKeyLoweringPass` (`ComponentKeyLoweringPass.cs`): `@key="value"` becomes a [SetKeyIntermediateNode]. */
class ComponentKeyLoweringPass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    // Run after component lowering pass
    override val order: Int get() = 50

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        // Nothing to do without the standard structure.
        documentNode.findPrimaryNamespace() ?: return
        documentNode.findPrimaryClass() ?: return

        val references = documentNode.findDescendantReferences<TagHelperDirectiveAttributeIntermediateNode>()

        for (reference in references) {
            if (reference.node.tagHelper.kind == TagHelperKind.Key) rewriteUsage(reference)
        }
    }

    private fun rewriteUsage(reference: IntermediateNodeReference<TagHelperDirectiveAttributeIntermediateNode>) {
        // Without a nonempty attribute value do nothing: there is already a diagnostic for empty values.
        val keyValueToken = determineSingleValueToken(reference.node) ?: return

        reference.replace(SetKeyIntermediateNode(keyValueToken))
    }
}

/** `ComponentReferenceCaptureLoweringPass` (`ComponentReferenceCaptureLoweringPass.cs`): `@ref="field"` becomes a [ReferenceCaptureIntermediateNode]. */
class ComponentReferenceCaptureLoweringPass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    // Run after component lowering pass
    override val order: Int get() = 50

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        // Nothing to do without the standard structure.
        documentNode.findPrimaryNamespace() ?: return
        documentNode.findPrimaryClass() ?: return

        val references = documentNode.findDescendantReferences<TagHelperDirectiveAttributeIntermediateNode>()

        for (reference in references) {
            if (reference.node.tagHelper.kind == TagHelperKind.Ref) rewriteUsage(reference)
        }
    }

    private fun rewriteUsage(reference: IntermediateNodeReference<TagHelperDirectiveAttributeIntermediateNode>) {
        val (node, parent) = reference

        // Without a non-empty attribute name do nothing: there is already a diagnostic for empty values.
        val identifierToken = determineSingleValueToken(node) ?: return

        // An element capture or a component capture, with the type name of the capture code when applicable.
        val componentTagHelper = (parent as? ComponentIntermediateNode)?.component
        val referenceCapture = if (componentTagHelper != null) ReferenceCaptureIntermediateNode(identifierToken, componentTagHelper.typeName)
        else ReferenceCaptureIntermediateNode(identifierToken)

        reference.replace(referenceCapture)
    }
}

/** `ComponentSplatLoweringPass` (`ComponentSplatLoweringPass.cs`): `@attributes="dictionary"` becomes a [SplatIntermediateNode]. */
class ComponentSplatLoweringPass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    // Run after component lowering pass
    override val order: Int get() = 50

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        for (reference in documentNode.findDescendantReferences<TagHelperDirectiveAttributeIntermediateNode>()) {
            if (reference.node.tagHelper.kind == TagHelperKind.Splat) rewriteUsage(reference)
        }
    }

    private fun rewriteUsage(reference: IntermediateNodeReference<TagHelperDirectiveAttributeIntermediateNode>) {
        val node = reference.node

        val result = SplatIntermediateNode().also { it.source = node.source }

        result.children.addAll(node.findDescendantNodes<CSharpIntermediateToken>())
        result.addDiagnosticsFromNode(node)

        reference.replace(result)
    }
}

/**
 * `ComponentFormNameLoweringPass` (`ComponentFormNameLoweringPass.cs`): `@formname` of a `form` becomes a [FormNameIntermediateNode]; on
 * anything else it is a warning (RZ10022) and an ordinary attribute.
 */
class ComponentFormNameLoweringPass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    // Run after component lowering pass
    override val order: Int get() = 50

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        val references = documentNode.findDescendantReferences<TagHelperDirectiveAttributeIntermediateNode>()
        for (reference in references) {
            val node = reference.node
            if (node.tagHelper.kind == TagHelperKind.FormName) {
                val parent = reference.parent

                if (!(parent is MarkupElementIntermediateNode && parent.tagName == "form")) {
                    node.addDiagnostic(ComponentDiagnosticFactory.createFormName_NotAForm(node.source))
                    reference.replace(rewriteForErrorRecovery(node, parent))
                    continue
                }

                reference.replace(rewrite(node))
            }
        }
    }

    private fun rewrite(node: TagHelperDirectiveAttributeIntermediateNode): IntermediateNode =
        rewriteCore(node, FormNameIntermediateNode().also { it.source = node.source })

    private fun rewriteForErrorRecovery(node: TagHelperDirectiveAttributeIntermediateNode, parent: IntermediateNode): IntermediateNode {
        if (parent is ComponentIntermediateNode) {
            return rewriteCore(
                node,
                ComponentAttributeIntermediateNode().also {
                    it.source = node.source
                    it.attributeName = node.originalAttributeName
                },
            )
        }

        val replacement = HtmlAttributeIntermediateNode().also {
            it.source = node.source
            it.attributeName = node.originalAttributeName
            it.prefix = node.originalAttributeName + "=\""
            it.suffix = "\""
        }
        replacement.children.addAll(
            node.children.map { child ->
                val result: IntermediateNode = if (child is CSharpExpressionIntermediateNode) CSharpExpressionAttributeValueIntermediateNode() else HtmlAttributeValueIntermediateNode()
                result.source = child.source
                result.children.addAll(child.children)
                result.addDiagnosticsFromNode(child)
                result
            },
        )
        replacement.addDiagnosticsFromNode(node)
        return replacement
    }

    private fun rewriteCore(node: TagHelperDirectiveAttributeIntermediateNode, replacement: IntermediateNode): IntermediateNode {
        replacement.children.addAll(node.children)
        replacement.addDiagnosticsFromNode(node)
        return replacement
    }
}

/**
 * `ComponentRenderModeLoweringPass` (`ComponentRenderModeLoweringPass.cs`): `@rendermode="..."` of a component becomes a
 * [RenderModeIntermediateNode] (an error on an element, RZ10023, and on a component that declares its own, RZ10024).
 */
class ComponentRenderModeLoweringPass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    // Run after component lowering pass
    override val order: Int get() = 50

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        val references = documentNode.findDescendantReferences<TagHelperDirectiveAttributeIntermediateNode>()
        for (reference in references) {
            val (node, parent) = reference

            if (node.tagHelper.kind == TagHelperKind.RenderMode) {
                if (parent !is ComponentIntermediateNode) {
                    node.addDiagnostic(ComponentDiagnosticFactory.createAttribute_ValidOnlyOnComponent(node.source, node.originalAttributeName))
                    continue
                }

                // Malformed or incomplete directive attributes can have no value nodes. Leave them unchanged so existing diagnostics and
                // error recovery are preserved.
                if (node.children.size == 0) continue

                val first = node.children[0]
                val expression = if (first is CSharpExpressionIntermediateNode) first.children[0] else first

                val renderModeNode = RenderModeIntermediateNode().also {
                    it.source = node.source
                    it.children.add(expression)
                }
                renderModeNode.addDiagnosticsFromNode(node)

                val component = parent.component!!
                if ((component.metadata as? ComponentMetadata)?.hasRenderModeDirective == true) {
                    renderModeNode.addDiagnostic(ComponentDiagnosticFactory.createRenderModeAttribute_ComponentDeclaredRenderMode(node.source, component.name))
                }

                reference.replace(renderModeNode)
            }
        }
    }
}

/**
 * `ComponentTildePathPass` (`ComponentTildePathPass.cs`): rewrites `~/`-prefixed string literal attribute values into
 * `Assets[@"path"]` expressions where the target opted into asset-path expansion — HTML element attributes the runtime declared with
 * `[AcceptsAssetPath(element, attribute)]` (the [AssetPathMetadata] tag helpers) and the component parameters with the AssetPath attribute
 * ([PropertyMetadata.acceptsAssetPath]). Language version 11 and later; between the order-50 passes and the bind pass.
 */
class ComponentTildePathPass(private val version: RazorLanguageVersion) : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = 75

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        if (version < RazorLanguageVersion.VERSION_11_0) return

        // `TryGetTagHelpers`: the full set of discovered tag helpers.
        val tagHelpers = codeDocument.items[RazorCodeDocument.TAG_HELPERS] as? TagHelperCollection ?: return

        val rewriter = Rewriter(buildAllowedElementAttributes(tagHelpers))
        rewriter.visit(documentNode)
    }

    /**
     * An opted-in HTML element name to the set of its attributes that accept asset paths, both case-insensitive (HTML semantics). The
     * allowlist is global: it comes from all the discovered tag helpers, whichever are in scope for this document.
     */
    private fun buildAllowedElementAttributes(tagHelpers: TagHelperCollection): Map<String, MutableSet<String>> {
        val result = TreeMap<String, MutableSet<String>>(String.CASE_INSENSITIVE_ORDER)

        for (tagHelper in tagHelpers) {
            val metadata = tagHelper.metadata as? AssetPathMetadata ?: continue
            result.getOrPut(metadata.element) { TreeSet(String.CASE_INSENSITIVE_ORDER) }.add(metadata.attribute)
        }

        return result
    }

    private class Rewriter(private val allowedElementAttributes: Map<String, MutableSet<String>>) : IntermediateNodeWalker() {
        private var currentElementName: String? = null

        override fun visitMarkupElement(node: MarkupElementIntermediateNode) {
            val previous = currentElementName
            currentElementName = node.tagName

            super.visitMarkupElement(node)

            currentElementName = previous
        }

        // HTML element attributes: HtmlAttribute -> HtmlAttributeValue (exactly one child) -> IntermediateToken starting with "~/".
        override fun visitHtmlAttribute(node: HtmlAttributeIntermediateNode) {
            val elementName = currentElementName ?: return
            val attributes = allowedElementAttributes[elementName] ?: return
            val attributeName = node.attributeName
            if (attributeName == null || !attributes.contains(attributeName)) return

            val valueNode = if (node.children.size == 1) node.children[0] as? HtmlAttributeValueIntermediateNode else null
            val expression = tryExpandAssetPath(node, valueNode, attributeName) ?: return

            val replacement = CSharpExpressionAttributeValueIntermediateNode().also { it.prefix = valueNode!!.prefix }
            replacement.children.add(expression)
            node.children[0] = replacement
        }

        // Component parameter attributes: ComponentAttribute -> HtmlContent (exactly one child) -> IntermediateToken starting with "~/".
        override fun visitComponentAttribute(node: ComponentAttributeIntermediateNode) {
            if ((node.boundAttribute?.metadata as? PropertyMetadata)?.acceptsAssetPath != true || node.attributeStructure == AttributeStructure.Minimized) return

            val contentNode = if (node.children.size == 1) node.children[0] as? HtmlContentIntermediateNode else null
            val expression = tryExpandAssetPath(node, contentNode, node.attributeName) ?: return

            val replacement = CSharpExpressionIntermediateNode()
            replacement.children.add(expression)
            node.children[0] = replacement
        }

        private companion object {
            const val TILDE_PREFIX = "~/"

            /**
             * An opted-in attribute whose value is a single `~/` literal becomes a synthetic `Assets[@"path"]` C# token; a value that
             * mixes a `~/` literal with dynamic content is RZ10029 instead. Null (no change) when the value is not an expandable asset path.
             */
            fun tryExpandAssetPath(ownerNode: IntermediateNode, valueContainer: IntermediateNode?, attributeName: String?): CSharpIntermediateToken? {
                val token = valueContainer?.let { getSingleLiteralToken(it) }
                if (token == null) {
                    reportMixedContentDiagnostic(ownerNode, attributeName)
                    return null
                }

                val content = token.content
                if (!content.startsWith(TILDE_PREFIX)) return null

                val path = content.substring(TILDE_PREFIX.length)
                if (path.isEmpty()) return null

                // A verbatim string literal: backslashes (common in paths) stay literal and only embedded quotes are doubled. No source:
                // synthetic code with no .razor mapping.
                val literal = path.replace("\"", "\"\"")
                return CSharpIntermediateToken("${ComponentsApi.ComponentBase.assets}[@\"$literal\"]", null)
            }

            /** The single literal token that makes up the entire value, or null for mixed/dynamic content (which never expands). */
            fun getSingleLiteralToken(valueContainer: IntermediateNode): IntermediateToken? =
                if (valueContainer.children.size == 1) valueContainer.children[0] as? IntermediateToken else null

            /** Only the literal HTML value portions are inspected: a `~/` purely inside a C# expression doesn't trigger the diagnostic. */
            fun reportMixedContentDiagnostic(node: IntermediateNode, attributeName: String?) {
                if (node.children.size <= 1) return

                for (child in node.children) {
                    if (child is HtmlAttributeValueIntermediateNode || child is HtmlContentIntermediateNode) {
                        val token = getSingleLiteralToken(child)
                        if (token != null && token.content.startsWith(TILDE_PREFIX)) {
                            node.addDiagnostic(ComponentDiagnosticFactory.createTildePath_MixedContent(token.source, attributeName))
                            return
                        }
                    }
                }
            }
        }
    }
}

/**
 * `ComponentCssScopePass` (`ComponentCssScopePass.cs`): every element gets a minimized attribute named by the CSS scope; after the
 * components and bind passes, so that the generated attribute comes later in the DOM than the developer's.
 */
class ComponentCssScopePass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = 110

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        val cssScope = codeDocument.codeGenerationOptions.cssScope
        if (cssScope.isNullOrEmpty()) return

        for (node in documentNode.findDescendantNodes<MarkupElementIntermediateNode>()) {
            // Add a minimized attribute whose name is simply the CSS scope
            node.children.add(
                HtmlAttributeIntermediateNode().also {
                    it.attributeName = cssScope
                    it.prefix = cssScope
                    it.suffix = ""
                },
            )
        }
    }
}

/**
 * `ComponentTemplateDiagnosticPass` (`ComponentTemplateDiagnosticPass.cs`): a template inside an attribute (markup, component, a malformed
 * `@ref`, a directive attribute) is RZ9994 and is removed, since it cannot be rendered there. After the components, event handler, ref
 * and bind passes.
 */
class ComponentTemplateDiagnosticPass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = 150

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        val candidates = ArrayList<IntermediateNodeReference<IntermediateNode>>()

        val visitor = Visitor(candidates)
        visitor.visit(documentNode)

        for (candidate in candidates) {
            val (node, parent) = candidate

            parent.addDiagnostic(ComponentDiagnosticFactory.create_TemplateInvalidLocation(node.source))

            // Remove the offending node since we don't know how to render it: no C# completion there, which is fine inside an HTML attribute.
            candidate.remove()
        }
    }

    /**
     * The compiler's visitor is an `IExtensionIntermediateNodeVisitor<TemplateIntermediateNode>`: a template goes to its own
     * `VisitExtension` (which does not descend), other extension nodes to the walker's. Here the walker's `visitExtension` dispatches.
     */
    private class Visitor(private val candidates: MutableList<IntermediateNodeReference<IntermediateNode>>) : IntermediateNodeWalker() {
        override fun visitExtension(node: ExtensionIntermediateNode) {
            if (node !is TemplateIntermediateNode) {
                super.visitExtension(node)
                return
            }

            // We found a template, let's check where it's located.
            for (ancestor in ancestors) {
                if (ancestor is HtmlAttributeIntermediateNode || // Inside markup attribute
                    ancestor is ComponentAttributeIntermediateNode || // Inside component attribute
                    ancestor is TagHelperPropertyIntermediateNode || // Inside malformed ref attribute
                    ancestor is TagHelperDirectiveAttributeIntermediateNode // Inside a directive attribute
                ) {
                    candidates.add(IntermediateNodeReference(node, parent!!))

                    // We found a candidate and can stop looking. There's no need to report multiple diagnostics for the same node.
                    break
                }
            }
        }
    }
}

/**
 * `ComponentChildContentDiagnosticPass` (`ComponentChildContentDiagnosticPass.cs`): a child content set by both an attribute and the body
 * (RZ9995), and a parameterized child content whose parameter name repeats an enclosing one's (RZ9999).
 */
class ComponentChildContentDiagnosticPass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    // Runs after components/eventhandlers/ref/bind/templates. We want to validate every component and it's usage of ChildContent.
    override val order: Int get() = 160

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        Visitor().visit(documentNode)
    }

    private class Visitor : IntermediateNodeWalker() {
        override fun visitComponent(node: ComponentIntermediateNode) {
            // Check for properties that are set by both element contents (body) and the attribute itself.
            for (childContent in node.childContents) {
                for (attribute in node.attributes) {
                    if (attribute.attributeName == childContent.attributeName) {
                        node.addDiagnostic(ComponentDiagnosticFactory.create_ChildContentSetByAttributeAndBody(attribute.source, attribute.attributeName))
                    }
                }
            }

            visitDefault(node)
        }

        override fun visitComponentChildContent(node: ComponentChildContentIntermediateNode) {
            // Each child content needs a unique parameter name within its scope: the name can be implicit, which works badly when nested.
            if (node.isParameterized) {
                val ancestors = this.ancestors
                val parentComponent = ancestors[0] as ComponentIntermediateNode

                // Skip the immediate parent component as we've already validated against it. Loop to ancestors.size - 1 because we're
                // always checking pairs.
                for (i in 1 until ancestors.size - 1) {
                    val ancestor = ancestors[i]
                    val ancestorParentComponent = ancestors[i + 1]
                    if (ancestor is ComponentChildContentIntermediateNode && ancestor.isParameterized &&
                        ancestor.parameterName == node.parameterName &&
                        ancestorParentComponent is ComponentIntermediateNode
                    ) {
                        // Duplicate name: an error, since it would almost certainly also be a far less clear C# error.
                        node.addDiagnostic(
                            ComponentDiagnosticFactory.create_ChildContentRepeatedParameterName(
                                node.source,
                                childContent1 = node,
                                component1 = parentComponent,
                                childContent2 = ancestor,
                                component2 = ancestorParentComponent,
                            ),
                        )
                    }
                }
            }

            visitDefault(node)
        }
    }
}
