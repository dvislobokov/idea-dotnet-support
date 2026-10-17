package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorOptimizationPass
import io.github.dotnetsupport.razor.lang.codegen.extensions.TemplateIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionAttributeValueIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeCollection
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeFactory
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeReference
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.MarkupElementIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperDirectiveAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperDirectiveAttributeParameterIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperPropertyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantNodes
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantReferences
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperKind

/**
 * `ComponentBindLoweringPass` (`ComponentBindLoweringPass.cs`, `.AttributeInfo.cs`, `.BindEntryKey.cs`): `@bind` / `@bind-Value` (with
 * `:event`, `:format`, `:culture`, `:get`, `:set`, `:after`) expands, like a macro, to the attributes the user could have written — the
 * value (through `BindConverter.FormatValue` on an element), the change handler (`EventCallback.Factory.CreateBinder` on an element,
 * `RuntimeHelpers.CreateInferredEventCallback` or a delegate on a component) and, on a component, the `Expression` attribute. The
 * fallback bind tag helpers give way to the more specific ones; genuine duplicates are reported. Runs after the event handler pass.
 */
class ComponentBindLoweringPass : ComponentIntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = 100

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (!isComponentDocument(documentNode)) return

        // Nothing to do without the standard structure.
        documentNode.findPrimaryNamespace() ?: return
        documentNode.findPrimaryClass() ?: return

        val bindGetSetSupported = codeDocument.parserOptions.languageVersion >= RazorLanguageVersion.VERSION_7_0

        // First, process duplicates so we don't have to worry about them later.
        processDuplicates(
            documentNode.findDescendantReferences<TagHelperDirectiveAttributeIntermediateNode>(),
            documentNode.findDescendantReferences<TagHelperDirectiveAttributeParameterIntermediateNode>(),
        )

        // Now that we've processed duplicates, re-collect all the references as some may have been removed.
        val references = documentNode.findDescendantReferences<TagHelperDirectiveAttributeIntermediateNode>()
        val parameterReferences = documentNode.findDescendantReferences<TagHelperDirectiveAttributeParameterIntermediateNode>()

        // The non-parameterized @bind or @bind-* attributes, by (parent, attribute name): the same name on two elements are two entries.
        // Duplicates on one element (<Foo @bind="bar" @bind="bar" />) are taken care of by the IR lowering. Insertion order, as the
        // compiler's dictionary enumerates.
        val bindEntries = LinkedHashMap<BindEntryKey, BindEntry>()

        for (reference in references) {
            val parent = reference.parent
            val node = reference.node

            if (node.tagHelper.kind == TagHelperKind.Bind) {
                val key = BindEntryKey(parent, node.attributeName)
                check(!bindEntries.containsKey(key)) { "An item with the same key has already been added. Key: $key" } // Dictionary.Add
                bindEntries[key] = BindEntry(reference, node, null)
            }
        }

        // Look for (@bind:get, @bind:set) pairs, the alternative form of the binding.
        for (parameterReference in parameterReferences) {
            val parent = parameterReference.parent
            val node = parameterReference.node

            if (!node.boundAttributeParameter.bindAttributeGetSet) continue

            if (!bindGetSetSupported) {
                node.addDiagnostic(ComponentDiagnosticFactory.createBindAttributeParameter_UnsupportedSyntaxBindGetSet(node.source, node.attributeName))
            }

            val key = BindEntryKey(parent, node.attributeNameWithoutParameter)

            val existingEntry = bindEntries[key]
            if (existingEntry == null) {
                bindEntries[key] = BindEntry(parameterReference, null, node)
            } else {
                val bindNode = existingEntry.bindNode!!

                bindNode.addDiagnostic(ComponentDiagnosticFactory.createBindAttributeParameter_InvalidSyntaxBindAndBindGet(node.source, bindNode.attributeName))
            }
        }

        // Now collect all the parameterized attributes and store them along with their corresponding @bind or @bind-* attributes.
        for (parameterReference in parameterReferences) {
            val parent = parameterReference.parent
            val node = parameterReference.node

            if (node.tagHelper.kind != TagHelperKind.Bind) continue

            // Check if this tag contains a corresponding non-parameterized bind node.
            val key = BindEntryKey(parent, node.attributeNameWithoutParameter)
            val name = node.boundAttributeParameter.name

            val entry = bindEntries[key]
            if (entry == null) {
                // There is no corresponding bind node. Add a diagnostic and move on.
                if (name != "set") {
                    parent.addDiagnostic(ComponentDiagnosticFactory.createBindAttributeParameter_MissingBind(node.source, node.attributeName))
                } else {
                    parent.addDiagnostic(ComponentDiagnosticFactory.createBindAttributeParameter_MissingBindGet(node.source, node.attributeNameWithoutParameter))
                }
            } else if (name == "event") {
                entry.bindEventNode = node
            } else if (name == "format") {
                entry.bindFormatNode = node
            } else if (name == "culture") {
                entry.bindCultureNode = node
            } else if (name == "after") {
                entry.bindAfterNode = node
            } else if (name == "get") {
                // Avoid removing the reference since it will be processed later on.
                continue
            } else if (name == "set") {
                if (entry.bindNode != null) {
                    parent.addDiagnostic(ComponentDiagnosticFactory.createBindAttributeParameter_UseBindGet(node.source, node.boundAttribute.name))
                }

                entry.bindSetNode = node
            } else {
                // Unsupported bind attribute parameter: only a descriptor configured to expect a parameter other than 'event' and 'format'.
            }

            // We've extracted what we need from the parameterized bind node. Remove it.
            parameterReference.remove()
        }

        // We now have all the info we need to rewrite the tag helper.
        for ((key, entry) in bindEntries) {
            val afterNode = entry.bindAfterNode
            if (entry.bindSetNode != null && afterNode != null) {
                key.parent.addDiagnostic(ComponentDiagnosticFactory.createBindAttributeParameter_InvalidSyntaxBindSetAfter(afterNode.source, afterNode.attributeNameWithoutParameter))
            }

            val reference = entry.bindNodeReference
            val newNodes = rewriteUsage(reference.parent, entry)
            reference.remove()

            for (newNode in newNodes) reference.parent.children.add(newNode)
        }
    }

    /** `AttributeInfo`: a bind-related attribute of a node, its index among the children and what its tag helper is. */
    private class AttributeInfo(val node: IntermediateNode, val index: Int, val tagHelper: TagHelperDescriptor, val attributeName: String, val originalAttributeName: String) {
        constructor(node: TagHelperDirectiveAttributeIntermediateNode, index: Int) : this(node, index, node.tagHelper, node.attributeName, node.originalAttributeName)
        constructor(node: TagHelperDirectiveAttributeParameterIntermediateNode, index: Int) : this(node, index, node.tagHelper, node.attributeName, node.originalAttributeName)

        val isFallbackBindTagHelper: Boolean by lazy { tagHelper.isFallbackBindTagHelper() }
        val isBindTagHelper: Boolean by lazy { tagHelper.kind == TagHelperKind.Bind }
        val isInputElementFallbackBindTagHelper: Boolean by lazy { tagHelper.isInputElementFallbackBindTagHelper() }
        val isInputElementBindTagHelper: Boolean by lazy { tagHelper.isInputElementBindTagHelper() }

        val isFallback: Boolean get() = isFallbackBindTagHelper || isInputElementFallbackBindTagHelper
    }

    /** `BindEntryKey`: the parent by reference and the attribute name (`AttributeNameWithoutParameter` for a parameter). */
    private class BindEntryKey(val parent: IntermediateNode, val attributeName: String) {
        override fun equals(other: Any?): Boolean = other is BindEntryKey && parent === other.parent && attributeName == other.attributeName
        override fun hashCode(): Int = System.identityHashCode(parent) * 31 + attributeName.hashCode()
        override fun toString(): String = "BindEntryKey { Parent = $parent, AttributeName = $attributeName }"
    }

    /** `BindEntry`: a `@bind` (or `@bind:get`) and its parameters; exactly one of [bindNode] and [bindGetNode] is set. */
    private class BindEntry(
        val bindNodeReference: IntermediateNodeReference<*>,
        val bindNode: TagHelperDirectiveAttributeIntermediateNode?,
        val bindGetNode: TagHelperDirectiveAttributeParameterIntermediateNode?,
    ) {
        var bindEventNode: TagHelperDirectiveAttributeParameterIntermediateNode? = null
        var bindFormatNode: TagHelperDirectiveAttributeParameterIntermediateNode? = null
        var bindCultureNode: TagHelperDirectiveAttributeParameterIntermediateNode? = null
        var bindSetNode: TagHelperDirectiveAttributeParameterIntermediateNode? = null
        var bindAfterNode: TagHelperDirectiveAttributeParameterIntermediateNode? = null

        val tagHelper: TagHelperDescriptor by lazy { bindNode?.tagHelper ?: bindGetNode?.tagHelper ?: cantBothBeNullOrNonNull() }

        /** For @bind the attribute, for @bind:get the attribute without the parameter part. */
        val attributeName: String by lazy { bindNode?.attributeName ?: bindGetNode?.attributeNameWithoutParameter ?: cantBothBeNullOrNonNull() }

        val originalAttributeName: String by lazy { bindNode?.originalAttributeName ?: bindGetNode?.originalAttributeName ?: cantBothBeNullOrNonNull() }

        fun getEffectiveBindNode(): IntermediateNode = bindNode ?: bindGetNode ?: cantBothBeNullOrNonNull()

        fun getChangeAttributeName(): String? = tagHelper.getChangeAttributeName()
        fun getExpressionAttributeName(): String? = tagHelper.getExpressionAttributeName()
        fun getValueAttributeName(): String? = tagHelper.getValueAttributeName()
        fun getFormat(): String? = tagHelper.getFormat()
        fun isInvariantCultureBindTagHelper(): Boolean = tagHelper.isInvariantCultureBindTagHelper()

        private fun cantBothBeNullOrNonNull(): Nothing = throw IllegalStateException("BindNode and BindGetNode can't both be null or non-null.")
    }

    /** The names (and descriptors) of the attributes a bind expands to (`TryComputeAttributeNames`' out parameters). */
    private class AttributeNames(
        val valueAttributeName: String?,
        val changeAttributeName: String?,
        val expressionAttributeName: String?,
        val changeAttributeNode: CSharpExpressionIntermediateNode?,
        val valueAttribute: BoundAttributeDescriptor?,
        val changeAttribute: BoundAttributeDescriptor?,
        val expressionAttribute: BoundAttributeDescriptor?,
    )

    private companion object {
        /** `$"global::{typeof(CultureInfo).FullName}.{nameof(CultureInfo.InvariantCulture)}"`. */
        const val cultureInvariantText = "global::System.Globalization.CultureInfo.InvariantCulture"

        fun processDuplicates(
            references: List<IntermediateNodeReference<TagHelperDirectiveAttributeIntermediateNode>>,
            parameterReferences: List<IntermediateNodeReference<TagHelperDirectiveAttributeParameterIntermediateNode>>,
        ) {
            val parents = ReferenceOrderedSet<IntermediateNode>()

            for (reference in references) parents.add(reference.parent)

            for (parameterReference in parameterReferences) parents.add(parameterReference.parent)

            for (parent in parents) processDuplicateAttributes(parent)
        }

        fun processDuplicateAttributes(node: IntermediateNode) {
            val children = node.children

            // First, collect all the bind-related attributes on this node.
            val attributes = ArrayList<AttributeInfo>()

            for (i in children.indices) {
                when (val child = children[i]) {
                    is TagHelperDirectiveAttributeIntermediateNode -> attributes.add(AttributeInfo(child, i))
                    is TagHelperDirectiveAttributeParameterIntermediateNode -> attributes.add(AttributeInfo(child, i))
                }
            }

            // Next, identify attributes for "fallback" bind tag helpers that are overridden by attributes with more specific bind tag helpers.
            val toRemove = ArrayList<AttributeInfo>()

            for (attribute in attributes) {
                // A usage of the general 'fallback' bind tag helper may duplicate a more specific one: the fallback is removed. The general
                // <input @bind="..." /> is a 'fallback' too: a tag helper cannot bind only when a specific attribute is *not* present.
                if (attribute.isFallback) {
                    for (candidate in attributes) {
                        // Same node, skip.
                        if (attribute.node === candidate.node) continue

                        // If the candidate isn't a more specific bind tag helper then skip it.
                        if ((attribute.isFallbackBindTagHelper && !candidate.isBindTagHelper) ||
                            (attribute.isInputElementFallbackBindTagHelper && !candidate.isInputElementBindTagHelper)
                        ) {
                            continue
                        }

                        if (attribute.attributeName == candidate.attributeName) {
                            // Found a duplicate - remove the 'fallback' in favor of the more specific tag helper.
                            toRemove.add(attribute)
                            break
                        }
                    }
                }
            }

            // Remove them in reverse order by the stored index (the attributes are already ordered by index).
            for (i in toRemove.indices.reversed()) children.removeAt(toRemove[i].index)

            // If we still have duplicates at this point then they are genuine conflicts: a hash set finds whether there are any, the more
            // expensive pass identifies and removes them.
            val duplicates = HashSet<String>()

            for (child in children) {
                // UNDONE (as in the compiler): duplicates among the parameterized attributes are not looked for, here or in
                // reportDiagnosticAndRemoveDuplicates — the LINQ this was unrolled from only looked at the directive attributes.
                if (child is TagHelperDirectiveAttributeIntermediateNode && !duplicates.add(child.attributeName)) {
                    reportDiagnosticAndRemoveDuplicates(node)
                    break
                }
            }
        }

        fun reportDiagnosticAndRemoveDuplicates(node: IntermediateNode) {
            val children = node.children

            val duplicates = LinkedHashMap<String, MutableList<AttributeInfo>>()

            for (i in children.indices) {
                val directiveAttribute = children[i]
                if (directiveAttribute is TagHelperDirectiveAttributeIntermediateNode) {
                    duplicates.getOrPut(directiveAttribute.attributeName) { ArrayList() }.add(AttributeInfo(directiveAttribute, i))
                }
            }

            val toRemove = ArrayList<AttributeInfo>()

            for ((_, builder) in duplicates) {
                if (builder.size > 1) {
                    node.addDiagnostic(
                        ComponentDiagnosticFactory.createBindAttribute_Duplicates(
                            node.source,
                            builder[0].originalAttributeName,
                            builder.map { it.node as TagHelperDirectiveAttributeIntermediateNode },
                        ),
                    )

                    toRemove.addAll(builder)
                }
            }

            // Do we have duplicates to remove? If so, remove them in reverse order of the index.
            if (toRemove.size > 0) {
                toRemove.sortWith { x, y -> x.index.compareTo(y.index) }

                for (i in toRemove.indices.reversed()) children.removeAt(toRemove[i].index)
            }
        }

        /**
         * `RewriteUsage`: bind works like a macro, it expands to code the user could have written:
         * `<MyComponent @bind-Value="@currentCount" />` is `<MyComponent Value="..." ValueChanged="..." ValueExpression="() => ..." />`.
         * The bound expression must be an lvalue (the C# errors otherwise are good enough). `EventCallback<>` is supported and encouraged:
         * `ValueChanged` may be an `Action<>` or an `EventCallback<>`; the overloads of the bind helpers give an `Action<object>` that
         * converts and sets an arbitrary value. The element is treated as a component for now: another pass turns a tag helper node
         * without a component back into a 'regular' element.
         */
        fun rewriteUsage(parent: IntermediateNode, bindEntry: BindEntry): List<IntermediateNode> {
            val names = tryComputeAttributeNames(parent, bindEntry)
            if (names == null) {
                // Skip anything we can't understand. It's important that we don't crash, that will bring down the build.
                val bindNode = bindEntry.bindNode
                if (bindNode != null) {
                    bindNode.addDiagnostic(ComponentDiagnosticFactory.createBindAttribute_InvalidSyntax(bindNode.source, bindNode.attributeName))

                    return listOf(bindNode)
                } else {
                    val bindGetNode = bindEntry.bindGetNode!!

                    bindGetNode.addDiagnostic(
                        ComponentDiagnosticFactory.createBindAttribute_MissingBindSet(
                            bindGetNode.source, bindGetNode.attributeName, "${bindGetNode.attributeNameWithoutParameter}:set",
                        ),
                    )

                    return listOf(bindGetNode)
                }
            }

            val valueAttributeName = names.valueAttributeName
            val changeAttributeName = names.changeAttributeName
            val expressionAttributeName = names.expressionAttributeName
            val changeAttributeNode = names.changeAttributeNode
            val valueAttribute = names.valueAttribute
            val changeAttribute = names.changeAttribute
            val expressionAttribute = names.expressionAttribute

            val targetNode = bindEntry.getEffectiveBindNode()

            val original = getAttributeContent(targetNode)
            if (original.content.isEmpty()) {
                // This can happen in error cases, the parser will already have flagged this as an error, so ignore it.
                return listOf(targetNode)
            }

            if (parent is ComponentIntermediateNode && !valueAttributeName.isNullOrEmpty() && !parent.component!!.acceptsUnmatchedAttributes()) {
                val source = getOriginalPropertySpan(targetNode) ?: targetNode.source

                if (valueAttribute == null) {
                    parent.addDiagnostic(ComponentDiagnosticFactory.createBindAttribute_MissingTarget(source, bindEntry.originalAttributeName, parent.tagName))
                } else if (changeAttributeNode == null && !changeAttributeName.isNullOrEmpty() && changeAttribute == null) {
                    parent.addDiagnostic(
                        ComponentDiagnosticFactory.createBindAttribute_MissingChangeAttribute(
                            bindEntry.bindEventNode?.source ?: source, bindEntry.originalAttributeName, changeAttributeName, parent.tagName,
                        ),
                    )
                }
            }

            // Look for a format: if we find one, the two nodes we generate get it.
            var format: IntermediateToken? = null
            val formatNode = bindEntry.bindFormatNode
            val formatContent = bindEntry.getFormat()
            if (formatNode != null) {
                format = getAttributeContent(formatNode)
            } else if (formatContent != null) {
                // We may have a default format if one is associated with the field type.
                format = IntermediateNodeFactory.csharpToken("\"$formatContent\"")
            }

            // Look for a culture: if we find one, the two nodes we generate get it.
            var culture: IntermediateToken? = null
            val cultureNode = bindEntry.bindCultureNode
            if (cultureNode != null) {
                culture = getAttributeContent(cultureNode)
            } else if (bindEntry.isInvariantCultureBindTagHelper()) {
                // We may have a default invariant culture if one is associated with the field type.
                culture = IntermediateNodeFactory.csharpToken(cultureInvariantText)
            }

            // Look for an after event: if we find one, the CreateBinder call we generate gets it.
            val after = bindEntry.bindAfterNode?.let { getAttributeContent(it) }

            val setter = bindEntry.bindSetNode?.let { getAttributeContent(it) }

            val valueExpressionTokens = ArrayList<IntermediateToken>()
            val changeExpressionTokens = ArrayList<IntermediateToken>()

            // There are a few cases to handle for @bind:
            // 1. This is a component using a delegate (int Value & Action<int> Value)
            // 2. This is a component using EventCallback (int value & EventCallback<int>)
            // 3. This is an element
            if (parent is ComponentIntermediateNode && changeAttribute != null && changeAttribute.isDelegateProperty()) {
                rewriteNodesForComponentDelegateBind(original, setter, after, changeAttribute.isDelegateWithAwaitableResult(), valueExpressionTokens, changeExpressionTokens)
            } else if (parent is ComponentIntermediateNode) {
                rewriteNodesForComponentEventCallbackBind(original, setter, after, valueExpressionTokens, changeExpressionTokens)
            } else {
                rewriteNodesForElementEventCallbackBind(original, format, culture, setter, after, valueExpressionTokens, changeExpressionTokens)
            }

            if (parent is MarkupElementIntermediateNode) {
                val valueNode = HtmlAttributeIntermediateNode().also {
                    it.originalAttributeName = bindEntry.originalAttributeName
                    it.attributeName = valueAttributeName
                    it.source = targetNode.source

                    it.prefix = (valueAttributeName ?: "") + "=\""
                    it.suffix = "\""
                }

                valueNode.addDiagnosticsFromNode(targetNode)

                val valueAttributeValue = CSharpExpressionAttributeValueIntermediateNode()

                for (token in valueExpressionTokens) valueAttributeValue.children.add(token)

                valueNode.children.add(valueAttributeValue)

                val changeNode = HtmlAttributeIntermediateNode().also {
                    it.originalAttributeName = bindEntry.originalAttributeName
                    it.attributeName = changeAttributeName
                    it.attributeNameExpression = changeAttributeNode
                    it.source = targetNode.source

                    it.prefix = (changeAttributeName ?: "") + "=\""
                    it.suffix = "\""

                    it.eventUpdatesAttributeName = valueAttributeName
                }

                val changeAttributeValue = CSharpExpressionAttributeValueIntermediateNode()

                for (token in changeExpressionTokens) changeAttributeValue.children.add(token)

                changeNode.children.add(changeAttributeValue)

                return listOf(valueNode, changeNode)
            } else {
                val node = bindEntry.bindNode
                val getNode = bindEntry.bindGetNode

                val builder = ArrayList<IntermediateNode>()

                val valueNode = if (node != null) ComponentAttributeIntermediateNode.from(node, addChildren = false)
                else ComponentAttributeIntermediateNode.from(getNode!!, addChildren = false)

                valueNode.originalAttributeName = bindEntry.originalAttributeName

                valueNode.propertySpan = getOriginalPropertySpan(valueNode)
                valueNode.attributeName = valueAttributeName
                valueNode.boundAttribute = valueAttribute // Might be null if it doesn't match a component attribute
                valueNode.propertyName = valueAttribute?.propertyName
                valueNode.typeName = if (valueAttribute?.isWeaklyTyped == false) valueAttribute.typeName else null

                val valueExpressionNode = CSharpExpressionIntermediateNode()

                for (token in valueExpressionTokens) valueExpressionNode.children.add(token)

                valueNode.children.add(valueExpressionNode)

                builder.add(valueNode)

                val changeNode = if (node != null) ComponentAttributeIntermediateNode.from(node, addChildren = false)
                else ComponentAttributeIntermediateNode.from(getNode!!, addChildren = false)

                changeNode.originalAttributeName = bindEntry.originalAttributeName
                changeNode.propertySpan = getOriginalPropertySpan(changeNode)
                changeNode.isSynthesized = true
                changeNode.attributeName = changeAttributeName
                changeNode.boundAttribute = changeAttribute // Might be null if it doesn't match a component attribute
                changeNode.propertyName = changeAttribute?.propertyName
                changeNode.typeName = if (changeAttribute?.isWeaklyTyped == false) changeAttribute.typeName else null

                val changeExpressionNode = CSharpExpressionIntermediateNode()

                for (token in changeExpressionTokens) changeExpressionNode.children.add(token)

                changeNode.children.add(changeExpressionNode)

                builder.add(changeNode)

                // Finally, also emit a node for the "Expression" attribute, but only if the target component is defined to accept one
                if (expressionAttribute != null) {
                    val expressionNode = if (node != null) ComponentAttributeIntermediateNode.from(node, addChildren = false)
                    else ComponentAttributeIntermediateNode.from(getNode!!, addChildren = false)

                    expressionNode.originalAttributeName = bindEntry.originalAttributeName
                    expressionNode.propertySpan = getOriginalPropertySpan(expressionNode)
                    expressionNode.isSynthesized = true
                    expressionNode.attributeName = expressionAttributeName
                    expressionNode.boundAttribute = expressionAttribute
                    expressionNode.propertyName = expressionAttribute.propertyName
                    expressionNode.typeName = if (expressionAttribute.isWeaklyTyped) null else expressionAttribute.typeName

                    expressionNode.children.add(CSharpExpressionIntermediateNode().also { it.children.add(IntermediateNodeFactory.csharpToken("() => ${original.content}")) })

                    builder.add(expressionNode)
                }

                // The bind set/event/after parameters need no runtime code of their own, but the IDE needs a C# reference to their
                // properties (Find All References, Rename): a minimal node the runtime writer emits as a no-op referencing the property.
                tryAddDesignTimePropertyAccessHelperNode(builder, bindEntry.bindSetNode, valueAttribute)
                tryAddDesignTimePropertyAccessHelperNode(builder, bindEntry.bindEventNode, valueAttribute)
                tryAddDesignTimePropertyAccessHelperNode(builder, bindEntry.bindAfterNode, valueAttribute)

                return builder
            }
        }

        fun tryAddDesignTimePropertyAccessHelperNode(
            builder: MutableList<IntermediateNode>, node: TagHelperDirectiveAttributeParameterIntermediateNode?, valueAttribute: BoundAttributeDescriptor?,
        ) {
            if (node == null || valueAttribute == null) return

            val helperNode = ComponentAttributeIntermediateNode.from(node, addChildren = true)
            helperNode.originalAttributeName = node.originalAttributeName
            helperNode.isDesignTimePropertyAccessHelper = true
            helperNode.propertySpan = getOriginalPropertySpan(node)
            helperNode.boundAttribute = valueAttribute
            helperNode.propertyName = valueAttribute.propertyName

            builder.add(helperNode)
        }

        /** `TryParseBindAttribute`: "bind" (no value attribute name) or "bind-Value"; null for anything else. */
        fun tryParseBindAttribute(bindEntry: BindEntry): Pair<Boolean, String?> {
            val attributeName = bindEntry.attributeName

            if (attributeName == "bind") return true to null

            if (!attributeName.startsWith("bind-")) return false to null

            return true to attributeName.substring("bind-".length)
        }

        /** `TryComputeAttributeNames`: the attribute names a bind expands to; null when they cannot be computed. */
        fun tryComputeAttributeNames(parent: IntermediateNode, bindEntry: BindEntry): AttributeNames? {
            var changeAttributeName: String? = null
            var expressionAttributeName: String?
            var changeAttributeNode: CSharpExpressionIntermediateNode? = null
            var valueAttribute: BoundAttributeDescriptor? = null
            var changeAttribute: BoundAttributeDescriptor? = null
            var expressionAttribute: BoundAttributeDescriptor? = null

            // The tag helper specifies attribute names, they should win (<input type="text" bind="@Foo" /> with a tag helper generated to
            // match a specific tag, with the attributes in its metadata). We expect 1 bind tag helper per-node. Even when a 'bind' tag
            // helper specifies the attribute names, the attribute should satisfy one of the valid syntaxes.
            val (parsed, parsedValueAttributeName) = tryParseBindAttribute(bindEntry)
            if (!parsed) return null

            val valueAttributeName = bindEntry.getValueAttributeName() ?: parsedValueAttributeName

            // An attribute that specifies the event like @bind:event="oninput" is preferred; otherwise the tag helper's.
            val bindEventNode = bindEntry.bindEventNode
            if (bindEventNode == null) {
                // @bind:event not specified
                changeAttributeName = bindEntry.getChangeAttributeName()
            } else {
                val text = tryExtractEventNodeStaticText(bindEventNode)
                if (text != null) {
                    // @bind:event="oninput" - change attribute is static
                    changeAttributeName = text
                } else {
                    // @bind:event="@someExpr" - we can't know the name of the change attribute, it's dynamic
                    changeAttributeNode = extractEventNodeExpression(bindEventNode)
                }
            }

            expressionAttributeName = bindEntry.getExpressionAttributeName()

            // We expect 0-1 components per-node.
            val componentTagHelper = (parent as? ComponentIntermediateNode)?.component
            if (componentTagHelper == null) {
                // If it's not a component node then there isn't too much else to figure out.
                if (changeAttributeName == null && changeAttributeNode == null) return null
                return AttributeNames(valueAttributeName, changeAttributeName, expressionAttributeName, changeAttributeNode, valueAttribute, changeAttribute, expressionAttribute)
            }

            // On a component '<PropertyName>Changed' is the name of the change event, '<PropertyName>Expression' of the expression.
            if (changeAttributeName == null) changeAttributeName = (valueAttributeName ?: "") + "Changed"
            if (expressionAttributeName == null) expressionAttributeName = (valueAttributeName ?: "") + "Expression"

            val boundAttributes = componentTagHelper.boundAttributes
            var i = boundAttributes.size - 1
            var set = 0
            while (i >= 0 && set != 3) {
                val attribute = boundAttributes[i]
                val ignoreCase = !attribute.caseSensitive // GetComparison

                if (valueAttribute == null && valueAttributeName != null && valueAttributeName.equals(attribute.name, ignoreCase)) {
                    valueAttribute = attribute
                    set++
                }

                if (changeAttribute == null && changeAttributeName.equals(attribute.name, ignoreCase)) {
                    changeAttribute = attribute
                    set++
                }

                if (expressionAttribute == null && expressionAttributeName.equals(attribute.name, ignoreCase)) {
                    expressionAttribute = attribute
                    set++
                }

                i--
            }

            return AttributeNames(valueAttributeName, changeAttributeName, expressionAttributeName, changeAttributeNode, valueAttribute, changeAttribute, expressionAttribute)
        }

        fun tryExtractEventNodeStaticText(node: TagHelperDirectiveAttributeParameterIntermediateNode): String? {
            val first = node.children.firstOrNull()
            return if (first is HtmlContentIntermediateNode) getAttributeContent(first).content else null
        }

        fun extractEventNodeExpression(node: TagHelperDirectiveAttributeParameterIntermediateNode): CSharpExpressionIntermediateNode? =
            node.children.firstOrNull() as? CSharpExpressionIntermediateNode

        /**
         * `RewriteNodesForComponentDelegateBind`: the value as-is, a delegate for the changes. A setter is cast to the change attribute's
         * type; an 'after' needs a callback that invokes it after the regular setter, synchronous or awaited by whether the attribute's
         * delegate has an awaitable result (EventCallbackFactory cannot normalize it: the target is a delegate, not an EventCallback).
         */
        fun rewriteNodesForComponentDelegateBind(
            original: IntermediateToken, setter: IntermediateToken?, after: IntermediateToken?, awaitable: Boolean,
            valueExpressionTokens: MutableList<IntermediateToken>, changeExpressionTokens: MutableList<IntermediateToken>,
        ) {
            valueExpressionTokens.add(original)

            // __value => <code> = __value
            if (setter == null && after == null) {
                changeExpressionTokens.add(IntermediateNodeFactory.csharpToken("__value => ${original.content} = __value"))
            } else if (setter != null && after == null) {
                changeExpressionTokens.add(setter)
            } else if (setter == null && after != null) {
                val startToken = if (awaitable) {
                    IntermediateNodeFactory.csharpToken("async  __value => { ${original.content} = __value; await ${ComponentsApi.RuntimeHelpers.invokeAsynchronousDelegate}(")
                } else {
                    IntermediateNodeFactory.csharpToken(" __value => { ${original.content} = __value; ${ComponentsApi.RuntimeHelpers.invokeSynchronousDelegate}(")
                }

                changeExpressionTokens.add(startToken)
                changeExpressionTokens.add(after)
                changeExpressionTokens.add(IntermediateNodeFactory.csharpToken("); }"))
            } else {
                // Treat this as the original case, since we don't support bind:set and bind:after simultaneously, we will produce an error.
                changeExpressionTokens.add(IntermediateNodeFactory.csharpToken("__value => ${original.content} = __value"))
            }
        }

        /** `RewriteNodesForComponentEventCallbackBind`: largely the element's: `CreateInferredEventCallback` all the way. */
        fun rewriteNodesForComponentEventCallbackBind(
            original: IntermediateToken, setter: IntermediateToken?, after: IntermediateToken?,
            valueExpressionTokens: MutableList<IntermediateToken>, changeExpressionTokens: MutableList<IntermediateToken>,
        ) {
            valueExpressionTokens.add(original)

            changeExpressionTokens.add(IntermediateNodeFactory.csharpToken("${ComponentsApi.RuntimeHelpers.createInferredEventCallback}(this, "))

            if (setter == null && after == null) {
                // no bind:set nor bind:after, assign to the bound expression
                changeExpressionTokens.add(IntermediateNodeFactory.csharpToken("__value => ${original.content} = __value"))
            } else if (setter != null && after == null) {
                // bind:set only
                changeExpressionTokens.add(setter)
            } else if (setter == null && after != null) {
                // bind:after only
                changeExpressionTokens.add(
                    IntermediateNodeFactory.csharpToken(
                        "${ComponentsApi.RuntimeHelpers.createInferredBindSetter}(callback: __value => { ${original.content} = __value; return " +
                            "${ComponentsApi.RuntimeHelpers.invokeAsynchronousDelegate}(callback: ",
                    ),
                )
                changeExpressionTokens.add(after)
                changeExpressionTokens.add(IntermediateNodeFactory.csharpToken("); }, value: ${original.content})"))
            } else {
                // bind:set and bind:after create the code even though we disallow this combination through a diagnostic
                changeExpressionTokens.add(
                    IntermediateNodeFactory.csharpToken(
                        "${ComponentsApi.RuntimeHelpers.createInferredEventCallback}(this, callback: async __value => { await " +
                            "${ComponentsApi.RuntimeHelpers.createInferredBindSetter}(callback: ",
                    ),
                )
                changeExpressionTokens.add(setter!!)
                changeExpressionTokens.add(
                    IntermediateNodeFactory.csharpToken(", value: ${original.content}); await ${ComponentsApi.RuntimeHelpers.invokeAsynchronousDelegate}(callback: "),
                )
                changeExpressionTokens.add(after!!)
                changeExpressionTokens.add(IntermediateNodeFactory.csharpToken("); }, value: ${original.content})"))
            }

            changeExpressionTokens.add(IntermediateNodeFactory.csharpToken(", ${original.content})"))
        }

        /**
         * `RewriteNodesForElementEventCallbackBind`: `BindConverter.FormatValue(<code>, format: <format>, culture: <culture>)` for the value;
         * `EventCallback.Factory.CreateBinder(this, __value => <code> = __value, <code>, format: <format>, culture: <culture>)` for the
         * change — the EventCallback's type name cannot be written (the generic type pass runs after this), the compiler resolves the
         * overloads. The line mappings are applied to the value attribute, not the change attribute.
         */
        fun rewriteNodesForElementEventCallbackBind(
            original: IntermediateToken, format: IntermediateToken?, culture: IntermediateToken?, setter: IntermediateToken?, after: IntermediateToken?,
            valueExpressionTokens: MutableList<IntermediateToken>, changeExpressionTokens: MutableList<IntermediateToken>,
        ) {
            valueExpressionTokens.add(IntermediateNodeFactory.csharpToken("global::${ComponentsApi.BindConverter.formatValue}("))
            valueExpressionTokens.add(original)

            if (format != null && format.content.isNotEmpty()) {
                valueExpressionTokens.add(IntermediateNodeFactory.csharpToken(", format: "))
                valueExpressionTokens.add(format)
            }

            if (culture != null && culture.content.isNotEmpty()) {
                valueExpressionTokens.add(IntermediateNodeFactory.csharpToken(", culture: "))
                valueExpressionTokens.add(culture)
            }

            valueExpressionTokens.add(IntermediateNodeFactory.csharpToken(")"))

            changeExpressionTokens.add(
                IntermediateNodeFactory.csharpToken("global::${ComponentsApi.EventCallback.factoryAccessor}.${ComponentsApi.EventCallbackFactory.createBinderMethod}(this, "),
            )

            if (setter == null && after == null) {
                // no bind:set nor bind:after, , assign to the bound expression
                changeExpressionTokens.add(IntermediateNodeFactory.csharpToken("__value => ${original.content} = __value"))
            } else if (setter != null && after == null) {
                // bind:set only
                changeExpressionTokens.add(IntermediateNodeFactory.csharpToken("${ComponentsApi.RuntimeHelpers.createInferredBindSetter}(callback: "))
                changeExpressionTokens.add(setter)
                changeExpressionTokens.add(IntermediateNodeFactory.csharpToken(", value: ${original.content})"))
            } else if (setter == null && after != null) {
                // bind:after only
                changeExpressionTokens.add(
                    IntermediateNodeFactory.csharpToken(
                        "${ComponentsApi.RuntimeHelpers.createInferredBindSetter}(callback: __value => { ${original.content} = __value; return " +
                            "${ComponentsApi.RuntimeHelpers.invokeAsynchronousDelegate}(callback: ",
                    ),
                )
                changeExpressionTokens.add(after)
                changeExpressionTokens.add(IntermediateNodeFactory.csharpToken("); }, value: ${original.content})"))
            } else {
                // bind:set and bind:after create the code even though we disallow this combination through a diagnostic
                changeExpressionTokens.add(
                    IntermediateNodeFactory.csharpToken(
                        "${ComponentsApi.RuntimeHelpers.createInferredEventCallback}(this, callback: async __value => { await " +
                            "${ComponentsApi.RuntimeHelpers.createInferredBindSetter}(callback: ",
                    ),
                )
                changeExpressionTokens.add(setter!!)
                changeExpressionTokens.add(
                    IntermediateNodeFactory.csharpToken(", value: ${original.content})(); await ${ComponentsApi.RuntimeHelpers.invokeAsynchronousDelegate}(callback: "),
                )
                changeExpressionTokens.add(after!!)
                changeExpressionTokens.add(IntermediateNodeFactory.csharpToken("); }, value: ${original.content})"))
            }

            changeExpressionTokens.add(IntermediateNodeFactory.csharpToken(", "))

            changeExpressionTokens.add(IntermediateNodeFactory.csharpToken(original.content))

            if (format != null && format.content.isNotEmpty()) changeExpressionTokens.add(IntermediateNodeFactory.csharpToken(", format: ${format.content}"))

            if (culture != null && culture.content.isNotEmpty()) changeExpressionTokens.add(IntermediateNodeFactory.csharpToken(", culture: ${culture.content}"))

            changeExpressionTokens.add(IntermediateNodeFactory.csharpToken(")"))
        }

        fun getAttributeContent(node: IntermediateNode): IntermediateToken {
            val template = node.findDescendantNodes<TemplateIntermediateNode>().firstOrNull()
            if (template != null) {
                // See comments in TemplateDiagnosticPass
                node.addDiagnostic(ComponentDiagnosticFactory.create_TemplateInvalidLocation(template.source))
                return IntermediateNodeFactory.csharpToken("")
            }

            if (node.children.size == 0) return IntermediateNodeFactory.csharpToken("")

            return when (val first = node.children[0]) {
                // This case can be hit for a 'string' attribute. We want to turn it into an expression.
                is HtmlContentIntermediateNode -> IntermediateNodeFactory.csharpToken(getTokenContent(first.children, addQuotes = true))

                // This case can be hit when the attribute has an explicit @ inside, which 'escapes' any special sugar we provide for codegen.
                is CSharpExpressionIntermediateNode -> getToken(first)

                // This is the common case for 'mixed' content.
                else -> getToken(node)
            }
        }

        private fun getToken(node: IntermediateNode): IntermediateToken {
            if (node.children.size == 1) {
                val token = node.children[0]
                if (token is IntermediateToken) return token
            }

            // In error cases we won't have a single token, but we still want to generate the code.
            return IntermediateNodeFactory.csharpToken(getTokenContent(node.children))
        }

        private fun getTokenContent(children: IntermediateNodeCollection, addQuotes: Boolean = false): String {
            val builder = StringBuilder()

            if (addQuotes) builder.append('"')

            for (child in children) if (child is IntermediateToken) builder.append(child.content)

            if (addQuotes) builder.append('"')

            return builder.toString()
        }

        /** `GetOriginalPropertySpan`: the original attribute span without its `bind-` (`@bind-Value:get` → `Value:get`). */
        fun getOriginalPropertySpan(node: IntermediateNode): SourceSpan? {
            val originalSpan = when (node) {
                is ComponentAttributeIntermediateNode -> node.originalAttributeSpan
                is TagHelperDirectiveAttributeIntermediateNode -> node.originalAttributeSpan
                is TagHelperDirectiveAttributeParameterIntermediateNode -> node.originalAttributeSpan
                is TagHelperPropertyIntermediateNode -> node.originalAttributeSpan
                else -> null
            }

            if (originalSpan != null) {
                val offset = "bind-".length

                return SourceSpan(
                    originalSpan.filePath,
                    originalSpan.absoluteIndex + offset,
                    originalSpan.lineIndex,
                    originalSpan.characterIndex + offset,
                    originalSpan.length - offset,
                    originalSpan.lineCount,
                    originalSpan.endCharacterIndex,
                )
            }

            return null
        }
    }
}
