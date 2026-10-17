package io.github.dotnetsupport.razor.lang.codegen.extensions

import io.github.dotnetsupport.razor.lang.codegen.IntermediateNodePassBase
import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorOptimizationPass
import io.github.dotnetsupport.razor.lang.intermediate.ClassDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CommonModifiers
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ExtensionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.FieldDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.HtmlIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeWalker
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperBodyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperHtmlAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperPropertyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantNodes
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.isDefaultKind
import io.github.dotnetsupport.razor.lang.syntax.kindUsesDefaultTagHelperRuntime

/** `DefaultTagHelperOptimizationPass`: the tag helper nodes of the ITagHelper runtime get their runtime, create, execute and property nodes and a field per tag helper. */
class DefaultTagHelperOptimizationPass : IntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = DEFAULT_FEATURE_ORDER + 1000

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        val clazz = documentNode.findPrimaryClass() ?: return
        val context = Context(clazz)
        val tagHelperNodes = documentNode.findDescendantNodes<TagHelperIntermediateNode>().filter(::isTagHelperRuntimeNode)
        if (tagHelperNodes.isEmpty()) return
        addDefaultRuntime(context)
        for (tagHelperNode in tagHelperNodes) {
            rewriteBody(tagHelperNode)
            rewriteHtmlAttributes(tagHelperNode)
            addExecute(tagHelperNode)
            for (tagHelper in tagHelperNode.tagHelpers) rewriteUsage(context, tagHelperNode, tagHelper)
        }
        for (tagHelper in context.tagHelpers) addField(context, tagHelper)
    }

    private fun addDefaultRuntime(context: Context) {
        var i = 0
        while (i < context.clazz.children.size && context.clazz.children[i] is FieldDeclarationIntermediateNode) i++
        context.clazz.children.add(i, DefaultTagHelperRuntimeIntermediateNode())
    }

    private fun rewriteBody(node: TagHelperIntermediateNode) {
        for (i in node.children.indices) {
            val bodyNode = node.children[i] as? TagHelperBodyIntermediateNode ?: continue
            node.children[i] = DefaultTagHelperBodyIntermediateNode(bodyNode).also {
                it.tagMode = node.tagMode
                it.tagName = node.tagName
            }
            break
        }
    }

    private fun addExecute(node: TagHelperIntermediateNode) {
        node.children.add(DefaultTagHelperExecuteIntermediateNode())
    }

    private fun rewriteHtmlAttributes(node: TagHelperIntermediateNode) {
        for (i in node.children.indices) {
            val htmlAttributeNode = node.children[i] as? TagHelperHtmlAttributeIntermediateNode ?: continue
            node.children[i] = DefaultTagHelperHtmlAttributeIntermediateNode(htmlAttributeNode)
        }
    }

    private fun rewriteUsage(context: Context, node: TagHelperIntermediateNode, tagHelper: TagHelperDescriptor) {
        if (!tagHelper.isDefaultKind()) return
        context.add(tagHelper)
        var i = 0
        while (i < node.children.size && node.children[i] is TagHelperBodyIntermediateNode) i++
        while (i < node.children.size && node.children[i] is DefaultTagHelperBodyIntermediateNode) i++
        while (i < node.children.size && node.children[i] is DefaultTagHelperCreateIntermediateNode) i++
        node.children.add(i, DefaultTagHelperCreateIntermediateNode().also {
            it.fieldName = context.getFieldName(tagHelper)
            it.tagHelper = tagHelper
            it.typeName = tagHelper.typeName
        })
        for (j in node.children.indices) {
            val propertyNode = node.children[j] as? TagHelperPropertyIntermediateNode ?: continue
            if (propertyNode.tagHelper == tagHelper) {
                node.children[j] = DefaultTagHelperPropertyIntermediateNode(propertyNode).also {
                    it.fieldName = context.getFieldName(tagHelper)
                    it.propertyName = propertyNode.boundAttribute.propertyName
                }
            }
        }
    }

    private fun addField(context: Context, tagHelper: TagHelperDescriptor) {
        var i = 0
        while (i < context.clazz.children.size && context.clazz.children[i] is DefaultTagHelperRuntimeIntermediateNode) i++
        while (i < context.clazz.children.size && context.clazz.children[i] is FieldDeclarationIntermediateNode) i++
        context.clazz.children.add(i, FieldDeclarationIntermediateNode(context.getFieldName(tagHelper), "global::" + tagHelper.typeName).also {
            it.isTagHelperField = true
            it.modifiers = CommonModifiers.private
        })
    }

    private fun isTagHelperRuntimeNode(node: TagHelperIntermediateNode): Boolean = node.tagHelpers.any { it.kindUsesDefaultTagHelperRuntime() }

    private class Context(val clazz: ClassDeclarationIntermediateNode) {
        private val fieldNames = LinkedHashMap<TagHelperDescriptor, String>()
        val tagHelpers: Collection<TagHelperDescriptor> get() = fieldNames.keys

        fun add(tagHelper: TagHelperDescriptor): Boolean {
            if (fieldNames.containsKey(tagHelper)) return false
            fieldNames[tagHelper] = "__" + tagHelper.typeName.replace('.', '_')
            return true
        }

        fun getFieldName(tagHelper: TagHelperDescriptor): String = fieldNames.getValue(tagHelper)
    }
}

/** `PreallocatedTagHelperAttributeOptimizationPass`: attributes with literal values become `static readonly TagHelperAttribute` fields. */
class PreallocatedTagHelperAttributeOptimizationPass : IntermediateNodePassBase(), RazorOptimizationPass {
    override val order: Int get() = DEFAULT_FEATURE_ORDER + 1010

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        PreallocatedTagHelperWalker().visitDocument(documentNode)
    }

    /** The two `IExtensionIntermediateNodeVisitor<T>` of the C# walker, dispatched in [visitExtension]. */
    internal class PreallocatedTagHelperWalker : IntermediateNodeWalker() {
        private var classDeclaration: ClassDeclarationIntermediateNode? = null
        private var variableCountOffset = 0
        private var preallocatedDeclarationCount = 0

        override fun visitClassDeclaration(node: ClassDeclarationIntermediateNode) {
            classDeclaration = node
            variableCountOffset = node.children.size
            visitDefault(node)
        }

        override fun visitExtension(node: ExtensionIntermediateNode) {
            when (node) {
                is DefaultTagHelperHtmlAttributeIntermediateNode -> visitHtmlAttribute(node)
                is DefaultTagHelperPropertyIntermediateNode -> visitProperty(node)
                else -> super.visitExtension(node)
            }
        }

        private fun visitHtmlAttribute(node: DefaultTagHelperHtmlAttributeIntermediateNode) {
            val htmlContentNode = node.children.singleOrNull() as? HtmlContentIntermediateNode ?: return
            val plainTextValue = getContent(htmlContentNode)
            val clazz = classDeclaration!!
            var declaration: PreallocatedTagHelperHtmlAttributeValueIntermediateNode? = null
            for (current in clazz.children) {
                if (current is PreallocatedTagHelperHtmlAttributeValueIntermediateNode &&
                    current.attributeName == node.attributeName && current.value == plainTextValue && current.attributeStructure == node.attributeStructure
                ) {
                    declaration = current
                    break
                }
            }
            if (declaration == null) {
                val variableCount = clazz.children.size - variableCountOffset
                declaration = PreallocatedTagHelperHtmlAttributeValueIntermediateNode().also {
                    it.variableName = PRE_ALLOCATED_ATTRIBUTE_VARIABLE_PREFIX + variableCount
                    it.attributeName = node.attributeName
                    it.value = plainTextValue
                    it.attributeStructure = node.attributeStructure
                }
                clazz.children.add(preallocatedDeclarationCount++, declaration)
            }
            val addPreAllocatedAttribute = PreallocatedTagHelperHtmlAttributeIntermediateNode().also { it.variableName = declaration.variableName }
            val parentNode = parent!!
            val nodeIndex = parentNode.children.indexOf(node)
            parentNode.children[nodeIndex] = addPreAllocatedAttribute
        }

        private fun visitProperty(node: DefaultTagHelperPropertyIntermediateNode) {
            val boundAttribute = node.boundAttribute!!
            if (!(boundAttribute.isStringProperty || (node.isIndexerNameMatch && boundAttribute.isIndexerStringProperty))) return
            val htmlContentNode = node.children.singleOrNull() as? HtmlContentIntermediateNode ?: return
            val plainTextValue = getContent(htmlContentNode)
            val clazz = classDeclaration!!
            var declaration: PreallocatedTagHelperPropertyValueIntermediateNode? = null
            for (current in clazz.children) {
                if (current is PreallocatedTagHelperPropertyValueIntermediateNode &&
                    current.attributeName == node.attributeName && current.value == plainTextValue && current.attributeStructure == node.attributeStructure
                ) {
                    declaration = current
                    break
                }
            }
            if (declaration == null) {
                val variableCount = clazz.children.size - variableCountOffset
                declaration = PreallocatedTagHelperPropertyValueIntermediateNode().also {
                    it.variableName = PRE_ALLOCATED_ATTRIBUTE_VARIABLE_PREFIX + variableCount
                    it.attributeName = node.attributeName
                    it.value = plainTextValue
                    it.attributeStructure = node.attributeStructure
                }
                clazz.children.add(preallocatedDeclarationCount++, declaration)
            }
            val setPreallocatedProperty = PreallocatedTagHelperPropertyIntermediateNode(node).also { it.variableName = declaration.variableName }
            val parentNode = parent!!
            val nodeIndex = parentNode.children.indexOf(node)
            parentNode.children[nodeIndex] = setPreallocatedProperty
        }

        private fun getContent(node: HtmlContentIntermediateNode): String = node.children.filterIsInstance<HtmlIntermediateToken>().joinToString("") { it.content }

        companion object {
            private const val PRE_ALLOCATED_ATTRIBUTE_VARIABLE_PREFIX = "__tagHelperAttribute_"
        }
    }
}
