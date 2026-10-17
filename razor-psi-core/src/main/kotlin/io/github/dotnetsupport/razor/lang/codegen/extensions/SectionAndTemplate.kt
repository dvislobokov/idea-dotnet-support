package io.github.dotnetsupport.razor.lang.codegen.extensions

import io.github.dotnetsupport.razor.lang.codegen.*
import io.github.dotnetsupport.razor.lang.intermediate.ExtensionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeCollection
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeFormatter
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeVisitor

/** Sections and templates (`SectionIntermediateNode.cs`, `ISectionTargetExtension.cs`, `SectionTargetExtension.cs`, and the same for templates). */

/** `SectionIntermediateNode`: `@section Name { … }`. */
class SectionIntermediateNode : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    var sectionName: String? = null

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun cloneNode(): IntermediateNode = SectionIntermediateNode().also {
        it.sectionName = sectionName
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<ISectionTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, ISectionTargetExtension::class.java)
            return
        }
        extension.writeSection(context, this)
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(sectionName)
        formatter.writeProperty("SectionName", sectionName)
    }
}

interface ISectionTargetExtension : ICodeTargetExtension {
    fun writeSection(context: CodeRenderingContext, node: SectionIntermediateNode)
}

/** `SectionTargetExtension`: `DefineSection("Name", async() => { … });`. */
class SectionTargetExtension : ISectionTargetExtension {
    var sectionMethodName: String = DEFAULT_SECTION_METHOD_NAME

    override fun writeSection(context: CodeRenderingContext, node: SectionIntermediateNode) {
        context.codeWriter
            .writeStartMethodInvocation(sectionMethodName)
            .write("\"")
            .write(node.sectionName!!)
            .write("\", ")

        context.codeWriter.buildAsyncLambda().use {
            context.renderChildren(node)
        }

        context.codeWriter.writeEndMethodInvocation(true)
    }

    companion object {
        const val DEFAULT_SECTION_METHOD_NAME = "DefineSection"
    }
}

/** `TemplateIntermediateNode`: an inline template, `@<p>…</p>`. */
class TemplateIntermediateNode : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun cloneNode(): IntermediateNode = TemplateIntermediateNode().also { it.isSynthesizedHelper = isSynthesizedHelper }

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<ITemplateTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, ITemplateTargetExtension::class.java)
            return
        }
        extension.writeTemplate(context, this)
    }
}

interface ITemplateTargetExtension : ICodeTargetExtension {
    fun writeTemplate(context: CodeRenderingContext, node: TemplateIntermediateNode)
}

/** `TemplateTargetExtension`: `item => new Template(async(__razor_template_writer) => { … })`. */
class TemplateTargetExtension : ITemplateTargetExtension {
    var templateTypeName: String = DEFAULT_TEMPLATE_TYPE_NAME

    override fun writeTemplate(context: CodeRenderingContext, node: TemplateIntermediateNode) {
        val itemParameterName = "item"
        val templateWriterName = "__razor_template_writer"

        context.codeWriter
            .write(itemParameterName).write(" => ")
            .writeStartNewObject(templateTypeName)

        context.codeWriter.buildAsyncLambda(templateWriterName).use {
            context.nodeWriter.beginWriterScope(context, templateWriterName)
            context.renderChildren(node)
            context.nodeWriter.endWriterScope(context)
        }

        context.codeWriter.writeEndMethodInvocation(false)
    }

    companion object {
        const val DEFAULT_TEMPLATE_TYPE_NAME = "Template"
    }
}
