package io.github.dotnetsupport.razor.lang.codegen.mvc

import io.github.dotnetsupport.razor.lang.syntax.*
import io.github.dotnetsupport.razor.lang.codegen.*
import io.github.dotnetsupport.razor.lang.intermediate.*

/**
 * View component tag helpers (`ViewComponentTagHelperIntermediateNode.cs`, `IViewComponentTagHelperTargetExtension.cs`,
 * `ViewComponentTagHelperTargetExtensionBase.cs`, `ViewComponentTagHelperTargetExtension.cs`, `ViewComponentTypes.cs`, `ViewComponentsApi.cs`):
 * `<vc:name>` becomes a nested tag helper class that invokes the view component.
 */

/** `ViewComponentTagHelperIntermediateNode`: the generated tag helper class [className] of a view component. */
class ViewComponentTagHelperIntermediateNode(val className: String, val tagHelper: TagHelperDescriptor) : ExtensionIntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IViewComponentTagHelperTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IViewComponentTagHelperTargetExtension::class.java)
            return
        }
        extension.writeViewComponentTagHelper(context, this)
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(className)
        formatter.writeProperty("ClassName", className)
        formatter.writeProperty("TagHelper", tagHelper.displayName)
    }
}

interface IViewComponentTagHelperTargetExtension : ICodeTargetExtension {
    fun writeViewComponentTagHelper(context: CodeRenderingContext, node: ViewComponentTagHelperIntermediateNode)
}

/** `ViewComponentTagHelperTargetExtensionBase`: the class — target element, helper field, constructor, properties and `ProcessAsync`. */
abstract class ViewComponentTagHelperTargetExtensionBase : IViewComponentTagHelperTargetExtension {
    /** `ProcessMethodParameters`, initialized once on first use. */
    val processMethodParameters: List<MethodParameter> by lazy {
        listOf(
            MethodParameter(tagHelperContextVariableName, ViewComponentsApi.TagHelperContext.fullTypeName),
            MethodParameter(tagHelperOutputVariableName, ViewComponentsApi.TagHelperOutput.fullTypeName),
        )
    }

    protected abstract val tagHelperContentVariableName: String
    protected abstract val tagHelperContextVariableName: String
    protected abstract val tagHelperOutputVariableName: String
    protected abstract val viewComponentHelperVariableName: String

    override fun writeViewComponentTagHelper(context: CodeRenderingContext, node: ViewComponentTagHelperIntermediateNode) {
        // Add target element.
        writeTargetElementString(context.codeWriter, node.tagHelper)

        // Initialize declaration.
        context.codeWriter.buildClassDeclaration(
            CommonModifiers.public, node.className, BaseTypeWithModel(ViewComponentsApi.TagHelper.fullTypeName), null, null, context,
        ).use {
            // Add view component helper.
            context.codeWriter.writeFieldDeclaration(CommonModifiers.privateReadOnly, ViewComponentsApi.IViewComponentHelper.globallyQualifiedTypeName, viewComponentHelperVariableName)

            // Add constructor.
            writeConstructorString(context.codeWriter, node.className)

            // Add attributes.
            writeAttributeDeclarations(context.codeWriter, node.tagHelper)

            // Add process method.
            writeProcessMethodString(context.codeWriter, node.tagHelper)

            writeAdditionalMembers(context, node)
        }
    }

    protected open fun writeAdditionalMembers(context: CodeRenderingContext, node: ViewComponentTagHelperIntermediateNode) {}

    protected abstract fun getInvokeArguments(tagHelper: TagHelperDescriptor): List<String>

    private fun writeConstructorString(writer: CodeWriter, className: String) {
        writer.buildConstructorDeclaration(CommonModifiers.public, className, constructorParameters).use {
            writer.writeStartAssignment(viewComponentHelperVariableName).writeLine("helper;")
        }
    }

    private fun writeProcessMethodString(writer: CodeWriter, tagHelper: TagHelperDescriptor) {
        writer.buildMethodDeclaration(CommonModifiers.publicOverrideAsync, taskTypeName, ViewComponentsApi.processAsyncMethodName, processMethodParameters).use {
            writer.writeMethodInvocation(
                "($viewComponentHelperVariableName as ${ViewComponentsApi.IViewContextAware.globallyQualifiedTypeName})?.${ViewComponentsApi.IViewContextAware.contextualizeMethodName}",
                *contextualizeArguments.toTypedArray(),
            )

            val invokeArguments = getInvokeArguments(tagHelper)
            writer.write("var ")
                .writeStartAssignment(tagHelperContentVariableName)
                .write("await ")
                .writeMethodInvocation("$viewComponentHelperVariableName.${ViewComponentsApi.IViewComponentHelper.invokeMethodName}", *invokeArguments.toTypedArray())
            writer.writeStartAssignment("$tagHelperOutputVariableName.${ViewComponentsApi.TagHelperOutput.tagNamePropertyName}").writeLine("null;")
            writer.writeMethodInvocation(
                "$tagHelperOutputVariableName.${ViewComponentsApi.TagHelperOutput.contentPropertyName}.${ViewComponentsApi.TagHelperOutput.contentSetMethodName}",
                tagHelperContentVariableName,
            )
        }
    }

    private companion object {
        val constructorParameters: List<MethodParameter> = listOf(MethodParameter("helper", ViewComponentsApi.IViewComponentHelper.globallyQualifiedTypeName))

        /** `$"global::{typeof(Task).FullName}"`. */
        const val taskTypeName = "global::System.Threading.Tasks.Task"

        val contextualizeArguments: List<String> = listOf(ViewComponentsApi.viewContextPropertyName)

        fun writeTargetElementString(writer: CodeWriter, tagHelper: TagHelperDescriptor) {
            assert(tagHelper.tagMatchingRules.size == 1) // Debug.Assert

            val rule = tagHelper.tagMatchingRules[0]

            writer.write("[")
                .writeStartMethodInvocation(ViewComponentsApi.HtmlTargetElementAttribute.fullTypeName)
                .writeStringLiteral(rule.tagName)
                .writeLine(")]")
        }

        fun writeAttributeDeclarations(writer: CodeWriter, tagHelper: TagHelperDescriptor) {
            writer.write("[")
                .write(ViewComponentsApi.HtmlAttributeNotBoundAttribute.fullTypeName)
                .writeParameterSeparator()
                .write(ViewComponentsApi.ViewContextAttribute.globallyQualifiedTypeName)
                .writeLine("]")

            writer.writeAutoPropertyDeclaration(CommonModifiers.public, ViewComponentsApi.ViewContext.globallyQualifiedTypeName, ViewComponentsApi.viewContextPropertyName)

            for (attribute in tagHelper.boundAttributes) {
                writer.writeAutoPropertyDeclaration(CommonModifiers.public, attribute.typeName, attribute.propertyName)

                if (attribute.indexerTypeName != null) {
                    writer.write(" = ")
                        .writeStartNewObject(attribute.typeName)
                        .writeEndMethodInvocation()
                }
            }
        }
    }
}

/** `ViewComponentTagHelperTargetExtension`: the current form, with `ProcessInvokeAsyncArgs` matching the kebab-case attributes to the properties. */
class ViewComponentTagHelperTargetExtension : ViewComponentTagHelperTargetExtensionBase() {
    /** `ProcessInvokeAsyncArgsMethodParameters`, initialized once on first use. */
    private val processInvokeAsyncArgsMethodParameters: List<MethodParameter> by lazy {
        listOf(MethodParameter(tagHelperContextVariableName, ViewComponentsApi.TagHelperContext.fullTypeName))
    }

    override val tagHelperContentVariableName: String get() = "__helperContent"
    override val tagHelperContextVariableName: String get() = "__context"
    override val tagHelperOutputVariableName: String get() = "__output"
    override val viewComponentHelperVariableName: String get() = "__helper"

    override fun writeAdditionalMembers(context: CodeRenderingContext, node: ViewComponentTagHelperIntermediateNode) {
        // The arguments of `InvokeAsync` are pre-processed so that the markup attributes (kebab-case) match the properties of the class.
        writeProcessInvokeAsyncArgsMethodString(context.codeWriter, node.tagHelper)
    }

    private fun writeProcessInvokeAsyncArgsMethodString(writer: CodeWriter, tagHelper: TagHelperDescriptor) {
        val dictionaryType = "Dictionary<string, object>"

        writer.buildMethodDeclaration(CommonModifiers.private, dictionaryType, ViewComponentsApi.processInvokeAsyncArgsMethodName, processInvokeAsyncArgsMethodParameters).use {
            writer.writeStartAssignment("$dictionaryType args")
                .writeStartNewObject(dictionaryType)
                .writeEndMethodInvocation()

            for (attribute in tagHelper.boundAttributes) {
                val attributeName = attribute.name
                val parameterName = attribute.propertyName

                writer.writeLine("if (__context.AllAttributes.ContainsName(\"$attributeName\"))")
                writer.buildScope().use {
                    writer.writeLine("args[nameof($parameterName)] = $parameterName;")
                }
            }

            writer.writeLine("return args;")
        }
    }

    override fun getInvokeArguments(tagHelper: TagHelperDescriptor): List<String> {
        val viewComponentName = tagHelper.viewComponentName
        return listOf("\"$viewComponentName\"", "${ViewComponentsApi.processInvokeAsyncArgsMethodName}($tagHelperContextVariableName)")
    }
}

/** `ViewComponentTypes`: the names of the MVC view component types the tag helper discovery looks for. */
object ViewComponentTypes {
    const val assembly = "Microsoft.AspNetCore.Mvc.ViewFeatures"

    /** `AssemblyVersion` (`new Version(1, 1, 0, 0)`). */
    val assemblyVersion: List<Int> = listOf(1, 1, 0, 0)

    const val viewComponentSuffix = "ViewComponent"
    const val viewComponentAttribute = "Microsoft.AspNetCore.Mvc.ViewComponentAttribute"
    const val nonViewComponentAttribute = "Microsoft.AspNetCore.Mvc.NonViewComponentAttribute"
    const val genericTask = "System.Threading.Tasks.Task`1"
    const val task = "System.Threading.Tasks.Task"
    const val iDictionary = "System.Collections.Generic.IDictionary`2"
    const val asyncMethodName = "InvokeAsync"
    const val syncMethodName = "Invoke"

    object ViewComponent {
        const val name = "Name"
    }
}

/** `ViewComponentsApi`: the names of the MVC tag helper runtime a view component tag helper class uses. */
object ViewComponentsApi {
    object HtmlAttributeNotBoundAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Razor.TagHelpers.HtmlAttributeNotBoundAttribute"
    }

    object HtmlTargetElementAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Razor.TagHelpers.HtmlTargetElementAttribute"
    }

    object IViewComponentHelper {
        const val fullTypeName = "Microsoft.AspNetCore.Mvc.IViewComponentHelper"
        const val globallyQualifiedTypeName = "global::$fullTypeName"
        const val invokeMethodName = "InvokeAsync"
    }

    object IViewContextAware {
        const val fullTypeName = "Microsoft.AspNetCore.Mvc.ViewFeatures.IViewContextAware"
        const val globallyQualifiedTypeName = "global::$fullTypeName"
        const val contextualizeMethodName = "Contextualize"
    }

    object TagHelper {
        const val fullTypeName = "Microsoft.AspNetCore.Razor.TagHelpers.TagHelper"
    }

    object TagHelperContext {
        const val fullTypeName = "Microsoft.AspNetCore.Razor.TagHelpers.TagHelperContext"
    }

    object TagHelperOutput {
        const val fullTypeName = "Microsoft.AspNetCore.Razor.TagHelpers.TagHelperOutput"
        const val contentPropertyName = "Content"
        const val tagNamePropertyName = "TagName"
        const val contentSetMethodName = "SetHtmlContent"
    }

    object ViewContext {
        const val fullTypeName = "Microsoft.AspNetCore.Mvc.Rendering.ViewContext"
        const val globallyQualifiedTypeName = "global::$fullTypeName"
    }

    object ViewContextAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Mvc.ViewFeatures.ViewContextAttribute"
        const val globallyQualifiedTypeName = "global::$fullTypeName"
    }

    const val processAsyncMethodName = "ProcessAsync"
    const val processInvokeAsyncArgsMethodName = "ProcessInvokeAsyncArgs"
    const val viewContextPropertyName = "ViewContext"
}
