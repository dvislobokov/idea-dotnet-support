package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.CodeTarget
import io.github.dotnetsupport.razor.lang.codegen.ICodeTargetExtension
import io.github.dotnetsupport.razor.lang.codegen.IntermediateNodeWriter
import io.github.dotnetsupport.razor.lang.codegen.RazorCodeDocument
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion

/**
 * `ComponentCodeTarget` (`ComponentCodeTarget.cs`): the target of component documents — the [ComponentNodeWriter] of the document's
 * language version, and the built-in [ComponentTemplateTargetExtension] (which does not apply to legacy documents) before the given ones.
 */
class ComponentCodeTarget(codeDocument: RazorCodeDocument, targetExtensions: List<ICodeTargetExtension>) :
    CodeTarget(codeDocument, getComponentTargetExtensions(targetExtensions)) {
    private val version: RazorLanguageVersion get() = codeDocument.parserOptions.languageVersion

    override fun createNodeWriter(): IntermediateNodeWriter = ComponentNodeWriter(version)

    private companion object {
        // Components provide some built-in target extensions that don't apply to legacy documents.
        fun getComponentTargetExtensions(targetExtensions: List<ICodeTargetExtension>): List<ICodeTargetExtension> =
            listOf<ICodeTargetExtension>(ComponentTemplateTargetExtension()) + targetExtensions
    }
}
