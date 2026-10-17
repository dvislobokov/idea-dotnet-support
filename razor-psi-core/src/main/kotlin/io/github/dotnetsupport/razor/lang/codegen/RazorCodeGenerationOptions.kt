package io.github.dotnetsupport.razor.lang.codegen

/**
 * `RazorCodeGenerationOptions` (RAZOR_PLAN.md, R3b): what the code generator takes — the indent, the line break, the root namespace,
 * the CSS scope, and the flags. The defaults are the engine's (`Flags.DefaultFlags` is the enhanced line pragma alone); the SDK's
 * source generator configures the rest (`RazorSourceGenerator.Helpers.cs`).
 */
class RazorCodeGenerationOptions(
    val indentSize: Int = 4,
    val newLine: String = System.lineSeparator(),
    val rootNamespace: String? = null,
    val cssScope: String? = null,
    val suppressUniqueIds: String? = null,
    val razorWarningLevel: Int = 0,
    val indentWithTabs: Boolean = false,
    val suppressChecksum: Boolean = false,
    val suppressMetadataAttributes: Boolean = false,
    val suppressMetadataSourceChecksumAttributes: Boolean = false,
    val suppressPrimaryMethodBody: Boolean = false,
    val suppressNullabilityEnforcement: Boolean = false,
    val omitMinimizedComponentAttributeValues: Boolean = false,
    val supportLocalizedComponentNames: Boolean = false,
    val useEnhancedLinePragma: Boolean = true,
    val suppressAddComponentParameter: Boolean = false,
    val remapLinePragmaPathsOnWindows: Boolean = false,
    val writeHtmlUtf8StringLiterals: Boolean = false,
    val enableMarkupSplit: Boolean = false,
) {
    fun copy(
        indentSize: Int = this.indentSize, newLine: String = this.newLine, rootNamespace: String? = this.rootNamespace, cssScope: String? = this.cssScope,
        suppressUniqueIds: String? = this.suppressUniqueIds, razorWarningLevel: Int = this.razorWarningLevel, indentWithTabs: Boolean = this.indentWithTabs,
        suppressChecksum: Boolean = this.suppressChecksum, suppressMetadataAttributes: Boolean = this.suppressMetadataAttributes,
        suppressMetadataSourceChecksumAttributes: Boolean = this.suppressMetadataSourceChecksumAttributes, suppressPrimaryMethodBody: Boolean = this.suppressPrimaryMethodBody,
        suppressNullabilityEnforcement: Boolean = this.suppressNullabilityEnforcement, omitMinimizedComponentAttributeValues: Boolean = this.omitMinimizedComponentAttributeValues,
        supportLocalizedComponentNames: Boolean = this.supportLocalizedComponentNames, useEnhancedLinePragma: Boolean = this.useEnhancedLinePragma,
        suppressAddComponentParameter: Boolean = this.suppressAddComponentParameter, remapLinePragmaPathsOnWindows: Boolean = this.remapLinePragmaPathsOnWindows,
        writeHtmlUtf8StringLiterals: Boolean = this.writeHtmlUtf8StringLiterals, enableMarkupSplit: Boolean = this.enableMarkupSplit,
    ) = RazorCodeGenerationOptions(
        indentSize, newLine, rootNamespace, cssScope, suppressUniqueIds, razorWarningLevel, indentWithTabs, suppressChecksum, suppressMetadataAttributes,
        suppressMetadataSourceChecksumAttributes, suppressPrimaryMethodBody, suppressNullabilityEnforcement, omitMinimizedComponentAttributeValues,
        supportLocalizedComponentNames, useEnhancedLinePragma, suppressAddComponentParameter, remapLinePragmaPathsOnWindows, writeHtmlUtf8StringLiterals, enableMarkupSplit,
    )

    companion object {
        val DEFAULT = RazorCodeGenerationOptions()
    }
}
