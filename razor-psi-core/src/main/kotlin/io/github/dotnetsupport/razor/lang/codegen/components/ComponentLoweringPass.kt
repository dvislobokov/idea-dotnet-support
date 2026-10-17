package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorOptimizationPass
import io.github.dotnetsupport.razor.lang.codegen.isNullOrDotNetWhiteSpace
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentChildContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentTypeArgumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeCollection
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeWalker
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.MarkupElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperBodyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperDirectiveAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperDirectiveAttributeParameterIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperHtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperPropertyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.UsingDirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantNodes
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantReferences
import io.github.dotnetsupport.razor.lang.syntax.AttributeStructure
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticFactory
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperKind
import io.github.dotnetsupport.razor.lang.syntax.createComponent_EditorRequiredParameterNotSpecified
import java.util.TreeSet

/**
 * `ComponentLoweringPass` (`ComponentLoweringPass.cs`): each tag helper node of a component document becomes a [ComponentIntermediateNode]
 * (one component descriptor, or one picked by the `@using`s and the type parameters among several) or a [MarkupElementIntermediateNode];
 * the child contents become [ComponentChildContentIntermediateNode]s. Runs earlier than the passes that lower specific kinds of attributes.
 */
class ComponentLoweringPass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = 0

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        // Nothing to do without the standard structure.
        documentNode.findPrimaryNamespace() ?: return
        documentNode.findPrimaryClass() ?: return

        // For each component *usage* we need to rewrite the tag helper node to map to the relevant component APIs.
        val usings = documentNode.findDescendantNodes<UsingDirectiveIntermediateNode>()
        val references = documentNode.findDescendantReferences<TagHelperIntermediateNode>()

        for (reference in references) {
            val node = reference.node
            // A child content tag helper is rewritten when its parent is visited.
            if (node.tagHelpers.any { it.kind == TagHelperKind.ChildContent }) continue

            // The element didn't match any child content descriptors. Look for any matching component descriptors.
            var count = 0
            for (tagHelper in node.tagHelpers) {
                // Count component tag helpers
                if (tagHelper.kind == TagHelperKind.Component) count++
            }

            if (count == 1) {
                reference.replace(rewriteAsComponent(node, node.tagHelpers.single { it.kind == TagHelperKind.Component }))
            } else if (count > 1) {
                val tagHelper = getTagHelperOrAddDiagnostic(node, usings) ?: break

                reference.replace(rewriteAsComponent(node, tagHelper))
            } else {
                reference.replace(rewriteAsElement(node))
            }
        }
    }

    private companion object {
        /**
         * `GetTagHelperOrAddDiagnostic`: several component tag helpers are candidates for the same tag, and the emitted code qualifies the
         * one it picks, so the C# compiler cannot report the ambiguity: the candidate is the single component of a namespace in the
         * `@using`s, or, among several, the one the type parameters select; otherwise RZ9985 (once) and null.
         */
        fun getTagHelperOrAddDiagnostic(node: TagHelperIntermediateNode, usings: List<UsingDirectiveIntermediateNode>): TagHelperDescriptor? {
            var candidate: TagHelperDescriptor? = null
            var matched: MutableList<TagHelperDescriptor>? = null
            for (tagHelper in node.tagHelpers) {
                if (tagHelper.kind != TagHelperKind.Component) continue

                for (j in usings.indices) {
                    val usingNamespace = usings[j].content
                    if (tagHelper.typeNamespace == usingNamespace) {
                        if (candidate == null) {
                            candidate = tagHelper
                        } else {
                            if (matched == null) matched = ArrayList()
                            matched.add(tagHelper)
                        }
                    }
                }
            }

            if (matched != null) {
                // Insert candidate at the beginning to maintain the original order
                matched.add(0, candidate!!)

                // Before reporting an ambiguity, try to disambiguate by the type parameters: a generic and a non-generic version of a
                // component with the same name.
                val resolvedCandidate = tryDisambiguateByTypeParameters(node, matched)
                if (resolvedCandidate != null) return resolvedCandidate

                // Iterate over existing diagnostics to avoid adding multiple diagnostics when we find an ambiguous tag.
                for (diagnostic in node.diagnostics) {
                    if (diagnostic.id == ComponentDiagnosticFactory.MultipleComponents.id || diagnostic.id == ComponentDiagnosticFactory.AmbiguousComponentSelection.id) {
                        return null
                    }
                }

                node.addDiagnostic(ComponentDiagnosticFactory.create_MultipleComponents(node.source, node.tagName, matched))

                return null
            }

            return candidate
        }

        /**
         * `TryDisambiguateByTypeParameters`: one generic and one non-generic component, or generic components of different type
         * parameter counts, told apart by the type parameters the markup provides.
         */
        fun tryDisambiguateByTypeParameters(node: TagHelperIntermediateNode, candidates: List<TagHelperDescriptor>): TagHelperDescriptor? {
            // Separate candidates into generic and non-generic
            val genericCandidates = candidates.filter { it.isGenericTypedComponent() }
            val nonGenericCandidates = candidates.filter { !it.isGenericTypedComponent() }

            // Get all type parameter attributes provided in the markup
            val providedTypeParameters = getProvidedTypeParameters(node)

            // If no type parameters are provided
            if (providedTypeParameters.size == 0) {
                // If there's exactly one non-generic component, prefer it
                if (nonGenericCandidates.size == 1 && genericCandidates.size >= 1) {
                    val nonGenericComponent = nonGenericCandidates[0]

                    // Check for ambiguity with any generic component
                    for (genericComponent in genericCandidates) {
                        if (hasAmbiguousParameters(node, genericComponent, nonGenericComponent)) {
                            // Report an ambiguity error and return null
                            node.addDiagnostic(ComponentDiagnosticFactory.create_AmbiguousComponentSelection(node.source, node.tagName, genericComponent, nonGenericComponent))
                            return null
                        }
                    }

                    // No ambiguity with any generic variant, use the non-generic component
                    return nonGenericComponent
                }

                // Can't disambiguate - either no non-generic or multiple non-generic components
                return null
            }

            // Type parameters are provided - find the generic component that matches
            var bestMatch: TagHelperDescriptor? = null
            val providedTypeParametersArray = providedTypeParameters.toList()

            for (candidate in genericCandidates) {
                val candidateTypeParams = getTypeParameterNames(candidate)

                // Check if all provided type parameters exist in this candidate's type parameters
                var allProvidedMatch = true
                for (provided in providedTypeParametersArray) {
                    if (!candidateTypeParams.contains(provided)) {
                        allProvidedMatch = false
                        break
                    }
                }

                if (!allProvidedMatch) continue

                // All provided type parameters match: all of them provided is an exact match, the component to use.
                if (providedTypeParameters.size == candidateTypeParams.size) return candidate

                // Partial match - could be this component (type inference will handle the rest)
                if (bestMatch == null) {
                    bestMatch = candidate
                } else {
                    // Multiple components match the provided type parameters - ambiguous
                    return null
                }
            }

            // Return the best match if we found one, otherwise null (ambiguous or no match)
            return bestMatch
        }

        /** The type parameter names provided in the markup (properties that are a type parameter of a generic candidate). */
        fun getProvidedTypeParameters(node: TagHelperIntermediateNode): MutableSet<String> {
            val result = LinkedHashSet<String>()

            for (child in node.children) {
                if (child is TagHelperPropertyIntermediateNode) {
                    for (tagHelper in node.tagHelpers) {
                        if (tagHelper.isGenericTypedComponent()) {
                            for (typeParam in tagHelper.getTypeParameters()) {
                                if (child.attributeName == typeParam.name) {
                                    result.add(child.attributeName)
                                    break
                                }
                            }
                        }
                    }
                }
            }

            return result
        }

        /** The set of type parameter names of a component. */
        fun getTypeParameterNames(component: TagHelperDescriptor): MutableSet<String> {
            val result = LinkedHashSet<String>()
            for (typeParam in component.getTypeParameters()) result.add(typeParam.name)
            return result
        }

        /** Both components have parameters named as the markup's attributes: the selection is ambiguous without type parameters. */
        fun hasAmbiguousParameters(node: TagHelperIntermediateNode, genericComponent: TagHelperDescriptor, nonGenericComponent: TagHelperDescriptor): Boolean {
            // Get all the attribute names used in the markup (excluding type parameters)
            val typeParameterNames = getTypeParameterNames(genericComponent)

            val markupAttributeNames = TreeSet(String.CASE_INSENSITIVE_ORDER)
            for (child in node.children) {
                // Skip type parameters
                if (child is TagHelperPropertyIntermediateNode && !typeParameterNames.contains(child.attributeName)) markupAttributeNames.add(child.attributeName)
            }

            // If no non-type-parameter attributes are used, there's no ambiguity
            if (markupAttributeNames.size == 0) return false

            // Check if both components have bound attributes with the same names
            val genericParamNames = TreeSet(String.CASE_INSENSITIVE_ORDER)
            for (attr in genericComponent.boundAttributes) if (!attr.isTypeParameterProperty()) genericParamNames.add(attr.name)

            val nonGenericParamNames = TreeSet(String.CASE_INSENSITIVE_ORDER)
            for (attr in nonGenericComponent.boundAttributes) nonGenericParamNames.add(attr.name)

            // A parameter that exists in both components is ambiguous.
            for (attrName in markupAttributeNames) {
                if (genericParamNames.contains(attrName) && nonGenericParamNames.contains(attrName)) return true
            }

            return false
        }

        fun rewriteAsComponent(node: TagHelperIntermediateNode, tagHelper: TagHelperDescriptor): ComponentIntermediateNode {
            assert(node.startTagSpan != null) { "Component tags should always have a start tag span." }
            val component = ComponentIntermediateNode().also {
                it.component = tagHelper
                it.source = node.source
                it.tagName = node.tagName
                it.typeName = tagHelper.typeName
                it.startTagSpan = node.startTagSpan!!
            }

            component.addDiagnosticsFromNode(node)

            val visitor = ComponentRewriteVisitor(component)
            visitor.visit(node)

            // Fixup the parameter names of child content elements. We can't do this during the rewrite because we see the nodes in the wrong order.
            for (childContent in component.childContents) {
                if (childContent.parameterName == null) {
                    childContent.parameterName = component.childContentParameterName ?: ComponentHelpers.ChildContent.DEFAULT_PARAMETER_NAME
                }
            }

            validateRequiredAttributes(node, tagHelper, component)

            return component
        }

        fun validateRequiredAttributes(node: TagHelperIntermediateNode, tagHelper: TagHelperDescriptor, intermediateNode: ComponentIntermediateNode) {
            // With a splat the user may have provided all values. This pass runs earlier than ComponentSplatLoweringPass, so no
            // SplatIntermediateNode yet.
            if (intermediateNode.children.any { it is TagHelperDirectiveAttributeIntermediateNode && it.tagHelper.kind == TagHelperKind.Splat }) return

            for (requiredAttribute in tagHelper.editorRequiredAttributes) {
                if (!isPresentAsAttribute(requiredAttribute.name, intermediateNode)) {
                    intermediateNode.addDiagnostic(
                        RazorDiagnosticFactory.createComponent_EditorRequiredParameterNotSpecified(node.source, intermediateNode.tagName, requiredAttribute.name),
                    )
                }
            }
        }

        private const val BIND_PREFIX = "@bind-"

        fun isPresentAsAttribute(attributeName: String, intermediateNode: ComponentIntermediateNode): Boolean {
            for (child in intermediateNode.children) {
                if (child is ComponentAttributeIntermediateNode && attributeName == child.attributeName) return true
                if (child is ComponentChildContentIntermediateNode && attributeName == child.attributeName) return true
                if (child is TagHelperDirectiveAttributeIntermediateNode) {
                    val originalAttributeName = child.originalAttributeName
                    if (originalAttributeName.startsWith(BIND_PREFIX) &&
                        equalsWithOptionalChangedOrExpressionSuffix(originalAttributeName.substring(BIND_PREFIX.length), attributeName)
                    ) {
                        return true
                    }
                }
                if (child is TagHelperDirectiveAttributeParameterIntermediateNode) {
                    // `@bind-Value:get` or `@bind-Value:set` is specified.
                    if (child.originalAttributeName.startsWith(BIND_PREFIX) &&
                        equalsWithOptionalChangedOrExpressionSuffix(child.attributeNameWithoutParameter.substring(BIND_PREFIX.length - 1), attributeName)
                    ) {
                        return true
                    }
                }
            }

            return false
        }

        /** `requiredName` is `specifiedName`, or `specifiedName + "Changed"`, or `specifiedName + "Expression"`. */
        fun equalsWithOptionalChangedOrExpressionSuffix(specifiedName: String, requiredName: String): Boolean =
            equalsWithSuffix(specifiedName, requiredName, "Changed") || equalsWithSuffix(specifiedName, requiredName, "Expression") || specifiedName == requiredName

        /** `requiredName` is `specifiedName + suffix`. */
        fun equalsWithSuffix(specifiedName: String, requiredName: String, suffix: String): Boolean =
            requiredName.endsWith(suffix) && specifiedName == requiredName.substring(0, requiredName.length - suffix.length)

        fun rewriteAsElement(node: TagHelperIntermediateNode): MarkupElementIntermediateNode {
            val result = MarkupElementIntermediateNode().also {
                it.source = node.source
                it.tagName = node.tagName
            }

            result.addDiagnosticsFromNode(node)

            val visitor = ElementRewriteVisitor(result.children)
            visitor.visit(node)

            return result
        }
    }

    private class ComponentRewriteVisitor(private val component: ComponentIntermediateNode) : IntermediateNodeWalker() {
        private val children: IntermediateNodeCollection = component.children

        override fun visitTagHelper(node: TagHelperIntermediateNode) {
            // Visit children, we're replacing this node.
            super.visitDefault(node)
        }

        override fun visitTagHelperBody(node: TagHelperBodyIntermediateNode) {
            // Wrap the component's children in a ChildContent node if we have some significant content.
            if (node.children.size == 0) return

            // A single HTML content node of whitespace (`<MyComponent>  </MyComponent>`) is no child content: it could conflict with a
            // child content set by an attribute, and insignificant formatting should not get in the way.
            if (node.children.size == 1 && isIgnorableWhitespace(node.children[0])) return

            // Implicit child content (`<MyComponent><div></div></MyComponent>`): all the content is one child content lambda assigned to
            // 'ChildContent'. Explicit (`<MyComponent><ChildContent><div>...</div></ChildContent></MyComponent>`): every child content
            // element is a direct child and gets its own lambda, assigned to the property of its name; whitespace outside them is ignored,
            // anything else is an error.
            if (!node.children.filterIsInstance<TagHelperIntermediateNode>().any { t -> t.tagHelpers.any { it.kind == TagHelperKind.ChildContent } }) {
                // This node has implicit child content. It may or may not have an attribute that matches.
                val attribute = component.component!!.boundAttributes.firstOrNull { it.name == ComponentsApi.RenderTreeBuilder.childContent }
                children.add(rewriteChildContent(attribute, node.source, node.children))
                return
            }

            // Explicit child content: each child content element is rewritten, any significant content outside of one is an error.
            for (i in node.children.indices) {
                val child = node.children[i]
                if (isIgnorableWhitespace(child)) continue

                if (child is TagHelperIntermediateNode && child.tagHelpers.any { it.kind == TagHelperKind.ChildContent }) {
                    // This is a child content element
                    val attribute = component.component!!.boundAttributes.firstOrNull { it.name == child.tagName }
                    val rewrittenChildContent = rewriteChildContent(attribute, child.source, child.children)
                    // The diagnostics the resolution phase put on the tag helper node survive the rewriting into a child content node.
                    rewrittenChildContent.addDiagnosticsFromNode(child)
                    children.add(rewrittenChildContent)
                    continue
                }

                // If we get here then this is significant content inside a component with explicit child content.
                child.addDiagnostic(ComponentDiagnosticFactory.create_ChildContentMixedWithExplicitChildContent(child.source, component))
                children.add(child)
            }
        }

        private fun isIgnorableWhitespace(n: IntermediateNode): Boolean =
            n is HtmlContentIntermediateNode && n.children.size == 1 && (n.children[0] as? IntermediateToken)?.content.let { it != null && it.isNullOrDotNetWhiteSpace() }

        private fun rewriteChildContent(attribute: BoundAttributeDescriptor?, source: SourceSpan?, children: IntermediateNodeCollection): ComponentChildContentIntermediateNode {
            val childContent = ComponentChildContentIntermediateNode().also {
                it.boundAttribute = attribute
                it.source = source
                it.typeName = attribute?.typeNameObject?.fullName ?: ComponentsApi.RenderFragment.fullTypeName
            }

            // Implicit child content: the children are non-tag-helper nodes, accepted as they are. Explicit child content: the children
            // are tag helper nodes that need special processing.
            for (i in children.indices) {
                val child = children[i]
                if (child is TagHelperBodyIntermediateNode) {
                    // The body is all of the content we want to render, the rest of the children will be the attributes.
                    for (j in child.children.indices) childContent.children.add(child.children[j])
                } else if (child is TagHelperPropertyIntermediateNode) {
                    if (child.boundAttribute.isChildContentParameterNameProperty()) {
                        // The parameter name must be literal text: `<ChildContent Context="@Foo()">` is a diagnostic.
                        val parameterName = tryGetAttributeStringContent(child)
                        if (parameterName != null) {
                            childContent.parameterName = parameterName
                            continue
                        }

                        // The parameter name is invalid.
                        childContent.addDiagnostic(ComponentDiagnosticFactory.create_ChildContentHasInvalidParameter(child.source, child.attributeName, attribute?.name))
                        continue
                    }

                    // An unrecognized bound attribute: practically never, unless the child content descriptor was misconfigured.
                    childContent.addDiagnostic(ComponentDiagnosticFactory.create_ChildContentHasInvalidAttribute(child.source, child.attributeName, attribute?.name))
                } else if (child is TagHelperHtmlAttributeIntermediateNode) {
                    // This is an HTML attribute on a child content.
                    childContent.addDiagnostic(ComponentDiagnosticFactory.create_ChildContentHasInvalidAttribute(child.source, child.attributeName, attribute?.name))
                } else if (child is TagHelperDirectiveAttributeIntermediateNode) {
                    // Directive attributes are not supported inside child content ('@ref' on a child content, say).
                    childContent.addDiagnostic(ComponentDiagnosticFactory.create_ChildContentHasInvalidAttribute(child.source, child.originalAttributeName, attribute?.name))
                } else {
                    // This is some other kind of node (likely an implicit child content)
                    childContent.children.add(child)
                }
            }

            return childContent
        }

        /** `TryGetAttributeStringContent`: the success path is a single HTML content node of tokens; null otherwise. */
        private fun tryGetAttributeStringContent(property: TagHelperPropertyIntermediateNode): String? {
            val only = if (property.children.size == 1) property.children[0] else null
            if (only is HtmlContentIntermediateNode) return only.children.filterIsInstance<IntermediateToken>().joinToString("") { it.content }
            return null
        }

        override fun visitTagHelperHtmlAttribute(node: TagHelperHtmlAttributeIntermediateNode) {
            val attribute = ComponentAttributeIntermediateNode(node)

            if (!node.isDirectiveAttributeCandidate && !component.component!!.acceptsUnmatchedAttributes()) {
                attribute.addDiagnostic(ComponentDiagnosticFactory.create_UnknownComponentParameter(node.attributeNameSpan, component.tagName, node.attributeName))
            }

            children.add(attribute)

            // Since we don't support complex content, we can rewrite the inside of this node to the rather simpler form that property nodes usually have.
            for (i in attribute.children.indices) {
                val child = attribute.children[i]
                if (child is HtmlAttributeValueIntermediateNode) {
                    val newNode = HtmlContentIntermediateNode().also { it.source = child.source }
                    for (j in child.children.indices) newNode.children.add(child.children[j])

                    attribute.children[i] = newNode
                } else if (child is CSharpExpressionAttributeValueIntermediateNode) {
                    val newNode = CSharpExpressionIntermediateNode().also { it.source = child.source }
                    for (j in child.children.indices) newNode.children.add(child.children[j])

                    attribute.children[i] = newNode
                } else if (child is CSharpCodeAttributeValueIntermediateNode) {
                    val newNode = CSharpExpressionIntermediateNode().also { it.source = child.source }
                    for (j in child.children.indices) newNode.children.add(child.children[j])

                    attribute.children[i] = newNode
                }
            }
        }

        override fun visitTagHelperProperty(node: TagHelperPropertyIntermediateNode) {
            // Each 'tag helper property' belongs to a specific tag helper: the cases for components are handled here, the others are left
            // to the other passes.
            if (node.tagHelper.kind != TagHelperKind.Component) {
                children.add(node)
                return
            }

            // A type argument: no 'real' parameter passed to the component, it needs special code generation support.
            if (node.tagHelper.isGenericTypedComponent() && node.boundAttribute.isTypeParameterProperty()) {
                children.add(ComponentTypeArgumentIntermediateNode(node))
                return
            }

            // A 'Context' parameter: the name of the lambda parameter of a parameterized child content.
            if (node.boundAttribute.isChildContentParameterNameProperty()) {
                // The parameter name must be literal text: `<MyComponent Context="@Foo()">` is a diagnostic.
                val parameterName = tryGetAttributeStringContent(node)
                if (parameterName != null) {
                    component.childContentParameterName = parameterName
                    return
                }

                // The parameter name is invalid.
                component.addDiagnostic(ComponentDiagnosticFactory.create_ChildContentHasInvalidParameterOnComponent(node.source, node.attributeName, component.tagName))
                return
            }

            children.add(ComponentAttributeIntermediateNode(node))
        }

        override fun visitTagHelperDirectiveAttribute(node: TagHelperDirectiveAttributeIntermediateNode) {
            // Nothing special with directive attributes here: their own lowering passes process them.
            children.add(node)
        }

        override fun visitDefault(node: IntermediateNode) {
            children.add(node)
        }
    }

    private class ElementRewriteVisitor(private val children: IntermediateNodeCollection) : IntermediateNodeWalker() {
        override fun visitTagHelper(node: TagHelperIntermediateNode) {
            // Visit children, we're replacing this node.
            var i = 0
            while (i < node.children.size) {
                visit(node.children[i])
                i++
            }
        }

        override fun visitTagHelperBody(node: TagHelperBodyIntermediateNode) {
            for (i in node.children.indices) children.add(node.children[i])
        }

        override fun visitTagHelperHtmlAttribute(node: TagHelperHtmlAttributeIntermediateNode) {
            val attribute = HtmlAttributeIntermediateNode().also {
                it.attributeName = node.attributeName
                it.source = node.source
            }

            children.add(attribute)

            attribute.addDiagnosticsFromNode(node)

            when (node.attributeStructure) {
                AttributeStructure.Minimized -> {
                    attribute.prefix = node.attributeName
                    attribute.suffix = ""
                }

                AttributeStructure.NoQuotes, AttributeStructure.SingleQuotes, AttributeStructure.DoubleQuotes -> {
                    // We're ignoring attribute structure here for simplicity, it doesn't effect us.
                    attribute.prefix = node.attributeName + "=\""
                    attribute.suffix = "\""

                    for (i in node.children.indices) attribute.children.add(rewriteAttributeContent(node.children[i]))
                }
            }
        }

        private fun rewriteAttributeContent(content: IntermediateNode): IntermediateNode {
            if (content is HtmlContentIntermediateNode) {
                val value = HtmlAttributeValueIntermediateNode().also { it.source = content.source }

                for (i in content.children.indices) value.children.add(content.children[i])

                value.addDiagnosticsFromNode(content)

                return value
            }

            return content
        }

        override fun visitTagHelperProperty(node: TagHelperPropertyIntermediateNode) {
            // Component properties are rewritten here, the others are left to the other passes.
            children.add(if (node.tagHelper.kind == TagHelperKind.Component) ComponentAttributeIntermediateNode(node) else node)
        }

        override fun visitTagHelperDirectiveAttribute(node: TagHelperDirectiveAttributeIntermediateNode) {
            // Nothing special with directive attributes here: their own lowering passes process them.
            children.add(node)
        }

        override fun visitDefault(node: IntermediateNode) {
            children.add(node)
        }
    }
}
