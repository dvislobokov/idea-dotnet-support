package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.codegen.mvc.RazorExtensions
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion

/**
 * The engine of the SDK's source generator (`RazorSourceGenerator.RazorProjectEngine` of `Microsoft.NET.Sdk.Razor.SourceGenerators`), as
 * razordump's `codegen` builds it: consolidated MVC views, `AddComponentParameter` available, no `RazorSourceChecksumAttribute`, the Roslyn
 * tokenizer, the MVC extension (`@inject`, `@model`, `@page`, `@section`, view components), [newLine]. The tag helper producers of the
 * generator (Roslyn symbols) are not here: the descriptors come as data (`TagHelperDescriptorJson`, the plugin's index — R5).
 */
object RazorGeneratorEngine {
    fun create(fileSystem: RazorProjectFileSystem, version: RazorLanguageVersion, rootNamespace: String?, newLine: String, configure: ((RazorProjectEngineBuilder) -> Unit)? = null): RazorProjectEngine {
        val configuration = RazorConfiguration(version, "Default", emptyList(), useConsolidatedMvcViews = true, suppressAddComponentParameter = false, useRoslynTokenizer = true)
        return RazorProjectEngine.create(configuration, fileSystem) { b ->
            configure?.invoke(b)
            b.setRootNamespace(rootNamespace)
            b.configureCodeGenerationOptions { o ->
                o.suppressMetadataSourceChecksumAttributes = true
                o.supportLocalizedComponentNames = false
                o.suppressAddComponentParameter = configuration.suppressAddComponentParameter
                o.newLine = newLine
            }
            b.configureParserOptions { o -> o.useRoslynTokenizer = true }
            RazorExtensions.register(b)
            b.setCSharpLanguageVersion(null)
        }
    }

    /** The Razor language version of a target framework (`net10.0` → 10.0), as the SDK's `RazorLangVersion` defaults; the latest when unknown. */
    fun versionOf(targetFramework: String?): RazorLanguageVersion {
        val major = Regex("""^net(\d+)\.\d+""").find(targetFramework.orEmpty().trim().lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: return RazorLanguageVersion.LATEST
        return if (major >= 5) RazorLanguageVersion(major, 0) else RazorLanguageVersion.VERSION_3_0
    }
}
