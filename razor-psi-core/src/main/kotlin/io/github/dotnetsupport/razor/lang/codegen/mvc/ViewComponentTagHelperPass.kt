package io.github.dotnetsupport.razor.lang.codegen.mvc

import io.github.dotnetsupport.razor.lang.codegen.IntermediateNodePassBase
import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorOptimizationPass
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperBodyIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperCreateIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperPropertyIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperRuntimeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ClassDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CommonModifiers
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.FieldDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeCollection
import io.github.dotnetsupport.razor.lang.intermediate.NamespaceDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperBodyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperPropertyIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantNodes
import io.github.dotnetsupport.razor.lang.syntax.MetadataKind
import io.github.dotnetsupport.razor.lang.syntax.MetadataObject
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperKind
import io.github.dotnetsupport.razor.lang.syntax.TypeNameObject

/** `ViewComponentMetadata`: the metadata of a view component tag helper — the view component's name and the original type. */
data class ViewComponentMetadata(val name: String, internal val originalTypeNameObject: TypeNameObject) : MetadataObject(MetadataKind.ViewComponent) {
    val originalTypeName: String? get() = originalTypeNameObject.fullName
    override val hasDefaultValue: Boolean get() = false
}

/** `Mvc/TagHelperDescriptorExtensions.cs`. */
val TagHelperDescriptor.isViewComponentKind: Boolean get() = kind == TagHelperKind.ViewComponent
val TagHelperDescriptor.viewComponentName: String? get() = (metadata as? ViewComponentMetadata)?.name

/** `ViewComponentTagHelperPass`: every view component tag helper used gets a generated nested `ITagHelper` class, a field and the create/property nodes of the default runtime. */
class ViewComponentTagHelperPass : IntermediateNodePassBase(), RazorOptimizationPass {
    // Run after the default tag helper pass
    override val order: Int get() = DEFAULT_FEATURE_ORDER + 2000

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (documentNode.documentKind != RazorPageDocumentClassifierPass.RAZOR_PAGE_DOCUMENT_KIND && documentNode.documentKind != MvcViewDocumentClassifierPass.MVC_VIEW_DOCUMENT_KIND) return
        val namespaceNode = documentNode.findPrimaryNamespace()
        val classNode = documentNode.findPrimaryClass()
        if (namespaceNode == null || classNode == null) return
        val context = Context(namespaceNode, classNode)
        for (node in documentNode.findDescendantNodes<TagHelperIntermediateNode>()) {
            for (tagHelper in node.tagHelpers) rewriteUsage(context, node, tagHelper)
        }
        for (tagHelper in context.tagHelpers) {
            addField(context, tagHelper)
            addTagHelperClass(context, tagHelper)
        }
    }

    private fun rewriteUsage(context: Context, node: TagHelperIntermediateNode, tagHelper: TagHelperDescriptor) {
        if (!tagHelper.isViewComponentKind) return
        context.add(tagHelper)
        val children = node.children
        var index = 0
        index = skipNodes<TagHelperBodyIntermediateNode>(children, index)
        index = skipNodes<DefaultTagHelperBodyIntermediateNode>(children, index)
        index = skipNodes<DefaultTagHelperCreateIntermediateNode>(children, index)
        children.add(index, DefaultTagHelperCreateIntermediateNode().also {
            it.fieldName = context.getFieldName(tagHelper)
            it.tagHelper = tagHelper
            it.typeName = context.getFullyQualifiedName(tagHelper)
        })
        for (i in children.indices) {
            val propertyNode = children[i] as? TagHelperPropertyIntermediateNode ?: continue
            if (propertyNode.tagHelper == tagHelper) {
                children[i] = DefaultTagHelperPropertyIntermediateNode(propertyNode).also {
                    it.fieldName = context.getFieldName(tagHelper)
                    it.propertyName = propertyNode.boundAttribute.propertyName
                }
            }
        }
    }

    private fun addField(context: Context, tagHelper: TagHelperDescriptor) {
        val children = context.clazz.children
        var index = 0
        index = skipNodes<DefaultTagHelperRuntimeIntermediateNode>(children, index)
        index = skipNodes<FieldDeclarationIntermediateNode>(children, index)
        context.clazz.children.add(index, FieldDeclarationIntermediateNode(context.getFieldName(tagHelper), "global::" + context.getFullyQualifiedName(tagHelper)).also {
            it.isTagHelperField = true
            it.modifiers = CommonModifiers.private
        })
    }

    private inline fun <reified T> skipNodes(children: IntermediateNodeCollection, start: Int): Int {
        var i = start
        while (i < children.size && children[i] is T) i++
        return i
    }

    private fun addTagHelperClass(context: Context, tagHelper: TagHelperDescriptor) {
        context.clazz.children.add(ViewComponentTagHelperIntermediateNode(context.getClassName(tagHelper), tagHelper))
    }

    private class Context(val namespace: NamespaceDeclarationIntermediateNode, val clazz: ClassDeclarationIntermediateNode) {
        private val names = LinkedHashMap<TagHelperDescriptor, Triple<String, String, String>>()
        val tagHelpers: Collection<TagHelperDescriptor> get() = names.keys

        fun add(tagHelper: TagHelperDescriptor): Boolean {
            if (names.containsKey(tagHelper)) return false
            val viewComponentName = tagHelper.viewComponentName
            val className = "__Generated__${viewComponentName}ViewComponentTagHelper"
            val fullyQualifiedName = if (!namespace.name.isNullOrEmpty()) "${namespace.name}.${clazz.name}.$className" else "${namespace.name ?: ""}${clazz.name}.$className"
            val fieldName = "__${viewComponentName}ViewComponentTagHelper"
            names[tagHelper] = Triple(className, fullyQualifiedName, fieldName)
            return true
        }

        fun getClassName(tagHelper: TagHelperDescriptor): String = names.getValue(tagHelper).first
        fun getFullyQualifiedName(tagHelper: TagHelperDescriptor): String = names.getValue(tagHelper).second
        fun getFieldName(tagHelper: TagHelperDescriptor): String = names.getValue(tagHelper).third
    }
}
