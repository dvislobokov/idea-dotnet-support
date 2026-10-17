package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.*
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperTargetExtension
import io.github.dotnetsupport.razor.lang.codegen.extensions.ITemplateTargetExtension
import io.github.dotnetsupport.razor.lang.codegen.extensions.TemplateIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ExtensionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeCollection
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeVisitor
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/** The extension nodes and the target extension of components (`ComponentInjectIntermediateNode.cs`, `RouteAttributeExtensionNode.cs`, `ComponentTemplateTargetExtension.cs`). */

/** `ComponentInjectIntermediateNode`: `@inject Type Name` of a component, written as an `[Inject]` property. */
class ComponentInjectIntermediateNode(val typeName: String, val memberName: String?, val typeSpan: SourceSpan?, val memberSpan: SourceSpan?) :
    ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun cloneNode(): IntermediateNode =
        ComponentInjectIntermediateNode(typeName, memberName, typeSpan, memberSpan).also { it.isSynthesizedHelper = isSynthesizedHelper }

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        if (typeName == "" && typeSpan != null) {
            // No type name at all: an empty mapped region, so that IntelliSense still works.
            context.buildEnhancedLinePragma(typeSpan).use { }
        } else {
            val memberName = memberName ?: ("Member_" + DefaultTagHelperTargetExtension.getDeterministicId(context))
            context.codeWriter.writeAutoPropertyDeclaration(injectedPropertyModifiers, typeName, memberName, typeSpan, memberSpan, context, false, true)
        }
    }

    private companion object {
        val injectedPropertyModifiers: List<String> = listOf(
            "[global::${ComponentsApi.InjectAttribute.fullTypeName}]",
            "private", // Encapsulation is the default
        )
    }
}

/** `RouteAttributeExtensionNode`: the `[Route("template")]` attribute of a `@page` directive, the template mapped. */
class RouteAttributeExtensionNode(val template: String) : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun cloneNode(): IntermediateNode = RouteAttributeExtensionNode(template).also { it.isSynthesizedHelper = isSynthesizedHelper }

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        context.codeWriter.write("[global::")
        context.codeWriter.write(ComponentsApi.RouteAttribute.fullTypeName)
        context.codeWriter.writeLine("(")
        context.codeWriter.writeLine("// language=Route,Component")
        context.buildEnhancedLinePragma(source).use {
            context.codeWriter.writeLine(template)
        }
        context.codeWriter.writeLine(")]")
    }
}

/** `ComponentTemplateTargetExtension`: templates of a component are written by the component node writer (plugged in by the component code target only). */
class ComponentTemplateTargetExtension : ITemplateTargetExtension {
    override fun writeTemplate(context: CodeRenderingContext, node: TemplateIntermediateNode) {
        (context.nodeWriter as ComponentNodeWriter).writeTemplate(context, node)
    }
}
