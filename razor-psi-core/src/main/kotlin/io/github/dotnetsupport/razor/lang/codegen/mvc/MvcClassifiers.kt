package io.github.dotnetsupport.razor.lang.codegen.mvc

import io.github.dotnetsupport.razor.lang.codegen.DefaultImportProjectItem
import io.github.dotnetsupport.razor.lang.codegen.DocumentClassifierPassBase
import io.github.dotnetsupport.razor.lang.codegen.ImportProjectFeature
import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.codegen.RazorConfiguration
import io.github.dotnetsupport.razor.lang.codegen.RazorDocumentClassifierPass
import io.github.dotnetsupport.razor.lang.codegen.RazorProjectEngine
import io.github.dotnetsupport.razor.lang.codegen.RazorProjectEngineBuilder
import io.github.dotnetsupport.razor.lang.codegen.RazorProjectEngineFeatureBase
import io.github.dotnetsupport.razor.lang.codegen.RazorProjectFileSystem
import io.github.dotnetsupport.razor.lang.codegen.RazorProjectItem
import io.github.dotnetsupport.razor.lang.codegen.extensions.RazorCompiledItemMetadataAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.codegen.extensions.SectionDirective
import io.github.dotnetsupport.razor.lang.codegen.extensions.TemplateTargetExtension
import io.github.dotnetsupport.razor.lang.codegen.tryComputeClassName
import io.github.dotnetsupport.razor.lang.codegen.tryGetNamespace
import io.github.dotnetsupport.razor.lang.intermediate.BaseTypeWithModel
import io.github.dotnetsupport.razor.lang.intermediate.ClassDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.CommonModifiers
import io.github.dotnetsupport.razor.lang.intermediate.DocumentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.MethodDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.NamespaceDeclarationIntermediateNode
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion

/** The document classifiers and the registration of the MVC extension (`Microsoft.AspNetCore.Mvc.Razor.Extensions`). */

/** `MvcViewDocumentClassifierPass`: every legacy document is a view — `RazorPage<TModel>` with `ExecuteAsync`. */
class MvcViewDocumentClassifierPass(private val useConsolidatedMvcViews: Boolean = false) : DocumentClassifierPassBase() {
    override val documentKind: String get() = MVC_VIEW_DOCUMENT_KIND
    override fun isMatch(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode): Boolean = true

    override fun onDocumentStructureCreated(codeDocument: RazorCodeDocument, namespace: NamespaceDeclarationIntermediateNode, clazz: ClassDeclarationIntermediateNode, method: MethodDeclarationIntermediateNode) {
        super.onDocumentStructureCreated(codeDocument, namespace, clazz, method)
        namespace.name = codeDocument.tryGetNamespace(fallbackToRootNamespace = false)?.first ?: (if (useConsolidatedMvcViews) "AspNetCoreGeneratedDocument" else "AspNetCore")
        clazz.name = codeDocument.tryComputeClassName() ?: ("AspNetCore_" + codeDocument.source.checksum)
        clazz.baseType = BaseTypeWithModel("global::Microsoft.AspNetCore.Mvc.Razor.RazorPage<TModel>", null)
        clazz.modifiers = if (useConsolidatedMvcViews) CommonModifiers.internalSealed else CommonModifiers.public
        clazz.nullableContext = true
        method.name = "ExecuteAsync"
        method.modifiers = CommonModifiers.publicAsyncOverride
        method.returnType = "global::System.Threading.Tasks.Task"
    }

    companion object {
        const val MVC_VIEW_DOCUMENT_KIND = "mvc.1.0.view"
    }
}

/** `RazorPageDocumentClassifierPass`: a legacy document with `@page` is a Razor Page — `Page` with the route template as metadata. */
class RazorPageDocumentClassifierPass(private val useConsolidatedMvcViews: Boolean = false) : DocumentClassifierPassBase() {
    override val documentKind: String get() = RAZOR_PAGE_DOCUMENT_KIND
    override fun isMatch(codeDocument: RazorCodeDocument, documentNode: DocumentIntermediateNode): Boolean = PageDirective.tryGetPageDirective(documentNode) != null

    override fun onDocumentStructureCreated(codeDocument: RazorCodeDocument, namespace: NamespaceDeclarationIntermediateNode, clazz: ClassDeclarationIntermediateNode, method: MethodDeclarationIntermediateNode) {
        super.onDocumentStructureCreated(codeDocument, namespace, clazz, method)
        namespace.name = codeDocument.tryGetNamespace(fallbackToRootNamespace = false)?.first ?: (if (useConsolidatedMvcViews) "AspNetCoreGeneratedDocument" else "AspNetCore")
        clazz.name = codeDocument.tryComputeClassName() ?: ("AspNetCore_" + codeDocument.source.checksum)
        clazz.baseType = BaseTypeWithModel("global::Microsoft.AspNetCore.Mvc.RazorPages.Page")
        clazz.modifiers = if (useConsolidatedMvcViews) CommonModifiers.internalSealed else CommonModifiers.public
        clazz.nullableContext = true
        method.name = "ExecuteAsync"
        method.modifiers = CommonModifiers.publicAsyncOverride
        method.returnType = "global::System.Threading.Tasks.Task"

        val document = codeDocument.getRequiredDocumentNode()
        val pageDirective = PageDirective.tryGetPageDirective(document)!!
        ensureValidPageDirective(codeDocument, pageDirective)
        addRouteTemplateMetadataAttribute(namespace, clazz, pageDirective)
    }

    private fun addRouteTemplateMetadataAttribute(namespace: NamespaceDeclarationIntermediateNode, clazz: ClassDeclarationIntermediateNode, pageDirective: PageDirective) {
        if (pageDirective.routeTemplate.isNullOrEmpty()) return
        val classIndex = namespace.children.indexOf(clazz)
        if (classIndex == -1) return
        namespace.children.add(classIndex, RazorCompiledItemMetadataAttributeIntermediateNode().also {
            it.key = ROUTE_TEMPLATE_KEY
            it.value = pageDirective.routeTemplate
            it.source = pageDirective.source
            it.valueStringSyntax = "Route"
        })
    }

    /** The `@page` of an import is an error; one that is not the first thing of the file too (the file parsed with the leading directives only). */
    private fun ensureValidPageDirective(codeDocument: RazorCodeDocument, pageDirective: PageDirective) {
        if (pageDirective.directiveNode.isImported) {
            pageDirective.directiveNode.addDiagnostic(RazorExtensionsDiagnosticFactory.createPageDirective_CannotBeImported(pageDirective.directiveNode.source!!))
        } else {
            val leadingDirectiveCodeDocument = leadingDirectiveParsingEngine.createCodeDocument(codeDocument.source, codeDocument.fileKind, emptyList(), null, null)
            leadingDirectiveParsingEngine.engine.process(leadingDirectiveCodeDocument)
            val leadingDirectiveDocumentNode = leadingDirectiveCodeDocument.getRequiredDocumentNode()
            if (PageDirective.tryGetPageDirective(leadingDirectiveDocumentNode) == null) {
                pageDirective.directiveNode.addDiagnostic(RazorExtensionsDiagnosticFactory.createPageDirective_MustExistAtTheTopOfFile(pageDirective.directiveNode.source!!))
            }
        }
    }

    companion object {
        const val RAZOR_PAGE_DOCUMENT_KIND = "mvc.1.0.razor-page"
        const val ROUTE_TEMPLATE_KEY = "RouteTemplate"

        /** `LeadingDirectiveParsingEngine`: the phases up to the document classifier, parsing the leading directives only. */
        private val leadingDirectiveParsingEngine: RazorProjectEngine by lazy {
            RazorProjectEngine.create(RazorConfiguration(RazorLanguageVersion.VERSION_3_0, "leading-directive-parser"), RazorProjectFileSystem.create("/")) { builder ->
                for (i in builder.phases.indices.reversed()) {
                    val phase = builder.phases[i]
                    builder.phases.removeAt(i)
                    if (phase is io.github.dotnetsupport.razor.lang.codegen.DefaultRazorDocumentClassifierPhase) break
                }
                RazorExtensions.register(builder)
                builder.configureParserOptions { it.parseLeadingDirectives = true }
            }
        }
    }
}

/** `MvcImportProjectFeature`: the default MVC directives and the `_ViewImports.cshtml` files up the folders of a legacy file. */
class MvcImportProjectFeature : RazorProjectEngineFeatureBase(), ImportProjectFeature {
    override fun collectImports(projectItem: RazorProjectItem, imports: MutableList<RazorProjectItem>) {
        if (projectItem.fileKind.isComponent) return
        addDefaultDirectivesImport(imports)
        addHierarchicalImports(projectItem, imports)
    }

    fun addHierarchicalImports(projectItem: RazorProjectItem, imports: MutableList<RazorProjectItem>) {
        imports.addAll(projectEngine.fileSystem.findHierarchicalItems(projectItem.filePath!!, IMPORTS_FILE_NAME))
    }

    companion object {
        const val IMPORTS_FILE_NAME = "_ViewImports.cshtml"

        private val defaultImport = DefaultImportProjectItem(
            "Default MVC 3.0 imports ($IMPORTS_FILE_NAME)",
            "\r\n@using global::System\r\n@using global::System.Collections.Generic\r\n@using global::System.Linq\r\n@using global::System.Threading.Tasks\r\n" +
                "@using global::Microsoft.AspNetCore.Mvc\r\n@using global::Microsoft.AspNetCore.Mvc.Rendering\r\n@using global::Microsoft.AspNetCore.Mvc.ViewFeatures\r\n" +
                "@inject global::Microsoft.AspNetCore.Mvc.Rendering.IHtmlHelper<TModel> Html\r\n@inject global::Microsoft.AspNetCore.Mvc.Rendering.IJsonHelper Json\r\n" +
                "@inject global::Microsoft.AspNetCore.Mvc.IViewComponentHelper Component\r\n@inject global::Microsoft.AspNetCore.Mvc.IUrlHelper Url\r\n" +
                "@inject global::Microsoft.AspNetCore.Mvc.ViewFeatures.IModelExpressionProvider ModelExpressionProvider\r\n" +
                "@addTagHelper global::Microsoft.AspNetCore.Mvc.Razor.TagHelpers.UrlResolutionTagHelper, Microsoft.AspNetCore.Mvc.Razor\r\n" +
                "@addTagHelper global::Microsoft.AspNetCore.Mvc.Razor.TagHelpers.HeadTagHelper, Microsoft.AspNetCore.Mvc.Razor\r\n" +
                "@addTagHelper global::Microsoft.AspNetCore.Mvc.Razor.TagHelpers.BodyTagHelper, Microsoft.AspNetCore.Mvc.Razor\r\n",
        )

        fun addDefaultDirectivesImport(imports: MutableList<RazorProjectItem>) {
            imports.add(defaultImport)
        }
    }
}

/**
 * `RazorExtensions.Register`: the MVC extension — `@inject`, `@model`, `@page`, `@section`, the view component tag helpers, `HelperResult`
 * templates, the passes and the classifiers, C# 8. The tag helper producers (Roslyn symbols) are not here: the descriptors come from the
 * plugin's index (R5).
 */
object RazorExtensions {
    fun register(builder: RazorProjectEngineBuilder) {
        InjectDirective.register(builder, considerNullabilityEnforcement = true)
        ModelDirective.register(builder)
        PageDirective.register(builder)
        SectionDirective.register(builder)
        builder.addTargetExtension(ViewComponentTagHelperTargetExtension())
        builder.addTargetExtension(TemplateTargetExtension().also { it.templateTypeName = "global::Microsoft.AspNetCore.Mvc.Razor.HelperResult" })
        builder.features.add(ModelExpressionPass())
        builder.features.add(PagesPropertyInjectionPass())
        builder.features.add(ViewComponentTagHelperPass())
        builder.features.add(RazorPageDocumentClassifierPass(builder.configuration.useConsolidatedMvcViews))
        builder.features.add(MvcViewDocumentClassifierPass(builder.configuration.useConsolidatedMvcViews))
        builder.features.add(MvcImportProjectFeature())
        builder.setCSharpLanguageVersion(8)
        if (builder.configuration.languageVersion >= RazorLanguageVersion.VERSION_6_0) builder.features.add(CreateNewOnMetadataUpdateAttributePass())
    }
}
