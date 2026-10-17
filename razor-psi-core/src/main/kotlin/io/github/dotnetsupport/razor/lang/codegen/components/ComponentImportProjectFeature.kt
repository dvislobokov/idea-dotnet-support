package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.DefaultImportProjectItem
import io.github.dotnetsupport.razor.lang.codegen.ImportProjectFeature
import io.github.dotnetsupport.razor.lang.codegen.RazorProjectEngineFeatureBase
import io.github.dotnetsupport.razor.lang.codegen.RazorProjectItem

/**
 * `ComponentImportProjectFeature` (`ComponentImportProjectFeature.cs`): the imports of a component — the default `@using`s, then the
 * `_Imports.razor` files up the folders (so that they can override the defaults); nothing for a non-component.
 */
class ComponentImportProjectFeature : RazorProjectEngineFeatureBase(), ImportProjectFeature {
    override fun collectImports(projectItem: RazorProjectItem, imports: MutableList<RazorProjectItem>) {
        // Don't add Component imports for a non-component.
        if (!projectItem.fileKind.isComponent) return

        imports.add(defaultImport)

        // We add hierarchical imports second so any default directive imports can be overridden.
        imports.addAll(getHierarchicalImports(projectItem))
    }

    /** `GetHierarchicalImports`: `FindHierarchicalItems` gives the items in the order the compiler adds them. */
    private fun getHierarchicalImports(projectItem: RazorProjectItem): List<RazorProjectItem> =
        projectEngine.fileSystem.findHierarchicalItems(projectItem.filePath!!, ComponentHelpers.IMPORTS_FILE_NAME)

    private companion object {
        // Using explicit newlines here to avoid fooling our baseline tests
        const val DEFAULT_USING_IMPORT_CONTENT =
            "\r\n" +
                "@using global::System\r\n" +
                "@using global::System.Collections.Generic\r\n" +
                "@using global::System.Linq\r\n" +
                "@using global::System.Threading.Tasks\r\n" +
                "@using global::" + ComponentsApi.RenderFragment.namespace + "\r\n" // Microsoft.AspNetCore.Components

        val defaultImport = DefaultImportProjectItem("Default component imports (${ComponentHelpers.IMPORTS_FILE_NAME})", DEFAULT_USING_IMPORT_CONTENT)
    }
}
