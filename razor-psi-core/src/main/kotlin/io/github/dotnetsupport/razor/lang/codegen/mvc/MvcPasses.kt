package io.github.dotnetsupport.razor.lang.codegen.mvc

import io.github.dotnetsupport.razor.lang.codegen.CodeRenderingContext
import io.github.dotnetsupport.razor.lang.codegen.CodeTarget
import io.github.dotnetsupport.razor.lang.intermediate.CommonModifiers
import io.github.dotnetsupport.razor.lang.codegen.IntermediateNodePassBase
import io.github.dotnetsupport.razor.lang.codegen.extensions.IMetadataIdentifierFeature
import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorDirectiveClassifierPass
import io.github.dotnetsupport.razor.lang.codegen.RazorOptimizationPass
import io.github.dotnetsupport.razor.lang.codegen.extensions.RazorCompiledItemMetadataAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpCodeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpExpressionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CSharpIntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.ClassDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DirectiveTokenIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ExtensionIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeCollection
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeFactory
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeVisitor
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNodeWalker
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.MalformedDirectiveIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.NamespaceDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.PropertyDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperPropertyIntermediateNode
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticSeverity
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.SourceLocation
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/** The passes of the MVC extension (`Microsoft.AspNetCore.Mvc.Razor.Extensions`); the directive descriptors are in their own files. */

/** `RazorExtensionsDiagnosticFactory` (RZ39xx). */
object RazorExtensionsDiagnosticFactory {
    private fun descriptor(code: Int, message: String, severity: RazorDiagnosticSeverity = RazorDiagnosticSeverity.ERROR, level: Int = 0) = RazorDiagnosticDescriptor("RZ$code", message, severity, level)
    private val undefined = SourceSpan(SourceLocation.UNDEFINED, 0)

    val ViewComponent_CannotFindMethod = descriptor(3900, "Could not find an '{0}' or '{1}' method for the view component '{2}'.")
    fun createViewComponent_CannotFindMethod(tagHelperType: String) = RazorDiagnostic(ViewComponent_CannotFindMethod, undefined, listOf(ViewComponentTypes.syncMethodName, ViewComponentTypes.asyncMethodName, tagHelperType))

    val ViewComponent_AmbiguousMethods = descriptor(3901, "View component '{0}' must contain exactly one public method named '{1}' or '{2}'.")
    fun createViewComponent_AmbiguousMethods(tagHelperType: String) = RazorDiagnostic(ViewComponent_AmbiguousMethods, undefined, listOf(tagHelperType, ViewComponentTypes.syncMethodName, ViewComponentTypes.asyncMethodName))

    val ViewComponent_AsyncMethod_ShouldReturnTask = descriptor(3902, "Method '{0}' of view component '{1}' should be declared to return {2}&lt;T&gt;.")
    fun createViewComponent_AsyncMethod_ShouldReturnTask(tagHelperType: String) = RazorDiagnostic(ViewComponent_AsyncMethod_ShouldReturnTask, undefined, listOf(ViewComponentTypes.asyncMethodName, tagHelperType, "Task"))

    val ViewComponent_SyncMethod_ShouldReturnValue = descriptor(3903, "Method '{0}' of view component '{1}' should be declared to return a value.")
    fun createViewComponent_SyncMethod_ShouldReturnValue(tagHelperType: String) = RazorDiagnostic(ViewComponent_SyncMethod_ShouldReturnValue, undefined, listOf(ViewComponentTypes.syncMethodName, tagHelperType))

    val ViewComponent_SyncMethod_CannotReturnTask = descriptor(3904, "Method '{0}' of view component '{1}' cannot return a {2}.")
    fun createViewComponent_SyncMethod_CannotReturnTask(tagHelperType: String) = RazorDiagnostic(ViewComponent_SyncMethod_CannotReturnTask, undefined, listOf(ViewComponentTypes.syncMethodName, tagHelperType, "Task"))

    val PageDirective_CannotBeImported = descriptor(3905, "The '@{0}' directive specified in {1} file will not be imported. The directive must appear at the top of each Razor cshtml file.")
    fun createPageDirective_CannotBeImported(source: SourceSpan): RazorDiagnostic {
        val fileName = source.filePath?.substringAfterLast('/')?.substringAfterLast('\\') ?: ""
        return RazorDiagnostic(PageDirective_CannotBeImported, source, listOf(PageDirective.directive.directive, fileName))
    }

    val PageDirective_MustExistAtTheTopOfFile = descriptor(3906, "The '@{0}' directive must precede all other elements defined in a Razor file.")
    fun createPageDirective_MustExistAtTheTopOfFile(source: SourceSpan) = RazorDiagnostic(PageDirective_MustExistAtTheTopOfFile, source, listOf(PageDirective.directive.directive))

    val ModelDirective_ModelTypeNotApplied = descriptor(3907, "The '@model' directive is not applied to the generated base class because the '@inherits' directive does not contain '<TModel>'.", RazorDiagnosticSeverity.WARNING, 11)
    fun createModelDirective_ModelTypeNotApplied(source: SourceSpan) = RazorDiagnostic(ModelDirective_ModelTypeNotApplied, source)
}

/** `ModelDirective.Pass`: `@model T` into `RazorPage<T>` (`dynamic` without one, the page class for a Razor Page). */
class ModelDirectivePass : IntermediateNodePassBase(), RazorDirectiveClassifierPass {
    override val order: Int get() = 5

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (documentNode.documentKind != RazorPageDocumentClassifierPass.RAZOR_PAGE_DOCUMENT_KIND && documentNode.documentKind != MvcViewDocumentClassifierPass.MVC_VIEW_DOCUMENT_KIND) return
        val visitor = ModelDirective.Visitor()
        val modelType = ModelDirective.getModelType(documentNode, visitor)
        val existingBaseType = visitor.classNode?.baseType ?: return
        if (existingBaseType.modelType != null) {
            existingBaseType.modelType = modelType
            return
        }
        val modelSource = modelType.source
        if (documentNode.documentKind == MvcViewDocumentClassifierPass.MVC_VIEW_DOCUMENT_KIND && modelSource != null && existingBaseType.baseType.source != null) {
            documentNode.addDiagnostic(RazorExtensionsDiagnosticFactory.createModelDirective_ModelTypeNotApplied(modelSource))
        }
    }
}

/** `InjectDirective.Pass`: `@inject T Name` into an [InjectIntermediateNode] of the class, the last of a name winning, `<TModel>` replaced by the model type. */
class InjectDirectivePass : IntermediateNodePassBase(), RazorDirectiveClassifierPass {
    override val order: Int get() = 10

    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (documentNode.documentKind != RazorPageDocumentClassifierPass.RAZOR_PAGE_DOCUMENT_KIND && documentNode.documentKind != MvcViewDocumentClassifierPass.MVC_VIEW_DOCUMENT_KIND) return
        val visitor = InjectDirective.Visitor()
        visitor.visit(documentNode)
        val modelType = ModelDirective.getModelType(documentNode).content
        val properties = HashSet<String>()
        for (i in visitor.directives.indices.reversed()) {
            val directive = visitor.directives[i]
            val tokens = directive.children.filterIsInstance<DirectiveTokenIntermediateNode>()
            val isMalformed = directive is MalformedDirectiveIntermediateNode
            val hasType = tokens.isNotEmpty() && !tokens[0].content.isNullOrBlank()
            var typeName = if (hasType) tokens[0].content!! else ""
            var typeSpan = if (hasType) tokens[0].source else directive.source?.zeroWidthEndSpan()
            val hasMemberName = tokens.size > 1 && !tokens[1].content.isNullOrBlank()
            val memberName = if (hasMemberName) tokens[1].content else null
            val memberSpan = if (hasMemberName) tokens[1].source else null
            if (hasMemberName && !properties.add(memberName!!)) continue

            val tModel = "<TModel>"
            if (typeName.endsWith(tModel)) {
                typeName = typeName.substring(0, typeName.length - tModel.length) + "<" + modelType + ">"
                val span = typeSpan
                if (span != null) typeSpan = SourceSpan(span.filePath, span.absoluteIndex, span.lineIndex, span.characterIndex, span.length - tModel.length, span.lineCount, span.endCharacterIndex - tModel.length)
            }

            val injectNode = InjectIntermediateNode().also {
                it.typeName = typeName
                it.memberName = memberName
                it.typeSource = typeSpan
                it.memberSource = memberSpan
                it.isMalformed = isMalformed
            }
            visitor.classNode!!.children.add(injectNode)
        }
    }
}

/** `PagesPropertyInjectionPass`: `ViewData` and `Model` of a Razor Page. */
class PagesPropertyInjectionPass : IntermediateNodePassBase(), RazorOptimizationPass {
    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (documentNode.documentKind != RazorPageDocumentClassifierPass.RAZOR_PAGE_DOCUMENT_KIND) return
        val razor9OrHigher = codeDocument.parserOptions.languageVersion >= RazorLanguageVersion.VERSION_9_0
        val nullableEnabled = razor9OrHigher && !codeDocument.codeGenerationOptions.suppressNullabilityEnforcement
        val modelType = ModelDirective.getModelType(documentNode)
        val visitor = ClassVisitor()
        visitor.visit(documentNode)
        val clazz = visitor.clazz!!
        val viewDataType = "global::Microsoft.AspNetCore.Mvc.ViewFeatures.ViewDataDictionary<${modelType.content}>"
        fun nullableEnable(code: String): String = if (!nullableEnabled) "$code;" else "#nullable restore\r\n$code!;\r\n#nullable disable"
        val vddProperty = CSharpCodeIntermediateNode()
        vddProperty.children.add(IntermediateNodeFactory.csharpToken(nullableEnable("public $viewDataType ViewData => ($viewDataType)PageContext?.ViewData")))
        clazz.children.add(vddProperty)
        if (!razor9OrHigher) {
            val modelProperty = CSharpCodeIntermediateNode()
            modelProperty.children.add(IntermediateNodeFactory.csharpToken(nullableEnable("public ${modelType.content} Model => ViewData.Model")))
            clazz.children.add(modelProperty)
        } else {
            clazz.children.add(PropertyDeclarationIntermediateNode("Model", modelType, "ViewData.Model").also { it.modifiers = CommonModifiers.public })
        }
    }

    private class ClassVisitor : IntermediateNodeWalker() {
        var clazz: ClassDeclarationIntermediateNode? = null
        override fun visitClassDeclaration(node: ClassDeclarationIntermediateNode) {
            if (clazz == null) clazz = node
            super.visitClassDeclaration(node)
        }
    }
}

/** `ModelExpressionPass`: a tag helper property of type `ModelExpression` takes `ModelExpressionProvider.CreateModelExpression(ViewData, __model => …)`. */
class ModelExpressionPass : IntermediateNodePassBase(), RazorOptimizationPass {
    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (documentNode.documentKind != RazorPageDocumentClassifierPass.RAZOR_PAGE_DOCUMENT_KIND && documentNode.documentKind != MvcViewDocumentClassifierPass.MVC_VIEW_DOCUMENT_KIND) return
        Visitor().visit(documentNode)
    }

    private class Visitor : IntermediateNodeWalker() {
        override fun visitTagHelperProperty(node: TagHelperPropertyIntermediateNode) {
            if (node.boundAttribute.typeName == MODEL_EXPRESSION_TYPE_NAME || (node.isIndexerNameMatch && node.boundAttribute.indexerTypeName == MODEL_EXPRESSION_TYPE_NAME)) {
                val expression = CSharpExpressionIntermediateNode()
                expression.children.add(IntermediateNodeFactory.csharpToken("ModelExpressionProvider.CreateModelExpression(ViewData, __model => "))
                val only = node.children.singleOrNull()
                if (only is CSharpIntermediateToken) {
                    expression.children.add(IntermediateNodeFactory.csharpToken("__model."))
                    expression.children.add(only)
                } else {
                    for (child in node.children) {
                        if (child is CSharpExpressionIntermediateNode) {
                            for (nested in child.children) if (nested is CSharpIntermediateToken) expression.children.add(nested)
                        }
                    }
                }
                expression.children.add(IntermediateNodeFactory.csharpToken(")"))
                node.children.clear()
                node.children.add(expression)
            }
        }
    }

    companion object {
        private const val MODEL_EXPRESSION_TYPE_NAME = "Microsoft.AspNetCore.Mvc.ViewFeatures.ModelExpression"
    }
}

/** `CreateNewOnMetadataUpdateAttributePass`: `[CreateNewOnMetadataUpdate]` and the `Identifier` metadata attribute before the class of a view or page. */
class CreateNewOnMetadataUpdateAttributePass : IntermediateNodePassBase(), RazorOptimizationPass {
    override fun executeCore(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode) {
        if (documentNode.documentKind != RazorPageDocumentClassifierPass.RAZOR_PAGE_DOCUMENT_KIND && documentNode.documentKind != MvcViewDocumentClassifierPass.MVC_VIEW_DOCUMENT_KIND) return
        val namespace = documentNode.findPrimaryNamespace() ?: return
        val clazz = documentNode.findPrimaryClass() ?: return
        val classIndex = namespace.children.indexOf(clazz)
        if (classIndex == -1) return
        val identifierFeature = engine.getFeatures<IMetadataIdentifierFeature>().first()
        val identifier = identifierFeature.getIdentifier(codeDocument, codeDocument.source)
        namespace.children.add(classIndex, CreateNewOnMetadataUpdateAttributeIntermediateNode())
        namespace.children.add(classIndex, RazorCompiledItemMetadataAttributeIntermediateNode().also {
            it.key = "Identifier"
            it.value = identifier
        })
    }

    class CreateNewOnMetadataUpdateAttributeIntermediateNode : ExtensionIntermediateNode() {
        override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY
        override fun accept(visitor: IntermediateNodeVisitor) = acceptExtensionNode(this, visitor)
        override fun writeNode(target: CodeTarget, context: CodeRenderingContext) {
            context.codeWriter.write("[").write("global::System.Runtime.CompilerServices.CreateNewOnMetadataUpdateAttribute").writeLine("]")
        }
    }
}
