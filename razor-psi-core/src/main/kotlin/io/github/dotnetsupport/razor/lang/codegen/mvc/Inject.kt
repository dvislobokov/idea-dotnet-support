package io.github.dotnetsupport.razor.lang.codegen.mvc

import io.github.dotnetsupport.razor.lang.codegen.*
import io.github.dotnetsupport.razor.lang.codegen.extensions.DefaultTagHelperTargetExtension
import io.github.dotnetsupport.razor.lang.intermediate.*
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/** `@inject` of MVC views and pages (`InjectIntermediateNode.cs`, `IInjectTargetExtension.cs`, `InjectTargetExtension.cs`; `Microsoft.AspNetCore.Mvc.Razor.Extensions`). */

/** `InjectIntermediateNode`: `@inject Type Name`, written as a `[RazorInject]` property. */
class InjectIntermediateNode : ExtensionIntermediateNode() {
    var typeName: String? = null
    var typeSource: SourceSpan? = null
    var memberName: String? = null
    var memberSource: SourceSpan? = null
    var isMalformed: Boolean = false

    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)

    override fun cloneNode(): IntermediateNode = InjectIntermediateNode().also {
        it.typeName = typeName
        it.typeSource = typeSource
        it.memberName = memberName
        it.memberSource = memberSource
        it.isMalformed = isMalformed
        it.isSynthesizedHelper = isSynthesizedHelper
    }

    override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
        val extension = target.getExtension<IInjectTargetExtension>()
        if (extension == null) {
            reportMissingCodeTargetExtension(context, IInjectTargetExtension::class.java)
            return
        }
        extension.writeInjectProperty(context, this)
    }

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(memberName)
        formatter.writeProperty("MemberName", memberName)
        formatter.writeProperty("TypeName", typeName)
        formatter.writeProperty("IsMalformed", isMalformed.toCSharpString())
    }
}

interface IInjectTargetExtension : ICodeTargetExtension {
    fun writeInjectProperty(context: CodeRenderingContext, node: InjectIntermediateNode)
}

/** `InjectTargetExtension`: the property of `@inject`, with `= default!;` when nullability is enforced. */
class InjectTargetExtension(private val considerNullabilityEnforcement: Boolean) : IInjectTargetExtension {
    override fun writeInjectProperty(context: CodeRenderingContext, node: InjectIntermediateNode) {
        val typeSource = node.typeSource
        if (!typeSource?.filePath.isNullOrDotNetWhiteSpace()) {
            if (node.typeName == "") {
                // No type name at all: an empty mapped region, so that IntelliSense still works.
                context.buildEnhancedLinePragma(typeSource!!).use { }
            } else {
                context.codeWriter.writeLine(RAZOR_INJECT_ATTRIBUTE)
                val memberName = node.memberName ?: ("Member_" + DefaultTagHelperTargetExtension.getDeterministicId(context))
                context.codeWriter.writeAutoPropertyDeclaration(listOf("public"), node.typeName!!, memberName, node.typeSource, node.memberSource, context, true, true)
            }
        } else if (!node.isMalformed) {
            var property = "public ${node.typeName ?: ""} ${node.memberName ?: ""} { get; private set; }"
            if (considerNullabilityEnforcement && !context.options.suppressNullabilityEnforcement) property += " = default!;"

            fun writeProperty() {
                if (considerNullabilityEnforcement && !context.options.suppressNullabilityEnforcement) context.codeWriter.writeLine("#nullable restore")

                context.codeWriter
                    .writeLine(RAZOR_INJECT_ATTRIBUTE)
                    .writeLine(property)

                if (considerNullabilityEnforcement && !context.options.suppressNullabilityEnforcement) context.codeWriter.writeLine("#nullable disable")
            }

            val source = node.source
            if (source != null) {
                context.buildLinePragma(source).use {
                    writeProperty()
                }
            } else {
                writeProperty()
            }
        }
    }

    private companion object {
        const val RAZOR_INJECT_ATTRIBUTE = "[global::Microsoft.AspNetCore.Mvc.Razor.Internal.RazorInjectAttribute]"
    }
}
