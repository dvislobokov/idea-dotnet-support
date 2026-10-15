package io.github.dotnetsupport.frameworks.razor

import io.github.dotnetsupport.lang.semantic.CSharpCodeGenerators
import io.github.dotnetsupport.lang.semantic.CSharpProjectFacts

/**
 * Razor components and pages: the Razor source generator of the SDK makes their classes. The helper runs it (RAZOR_PLAN.md, R0): its
 * output is in the index, so completion and navigation see the components. Once it ran and what it made is fresh a missing name would be
 * an error again — with [errorsAfterGeneration], off while the corpus unveiled false errors of the core the silence hid (method groups as
 * delegates, `@Icons`, `using static` of nested types...; an extra error is worse than a missing one), on since R0b fixed them (0.1.196).
 */
class RazorCodeGenerators : CSharpCodeGenerators {
    override fun mayGenerateTypes(project: CSharpProjectFacts): Boolean =
        !(errorsAfterGeneration && project.ranGenerator(GENERATOR)) &&
            (project.sdkContains("Razor") || project.sdkContains("Blazor") || project.hasFiles("razor") || project.hasFiles("cshtml"))

    companion object {
        /** In `Sdks/Microsoft.NET.Sdk.Razor/source-generators/Microsoft.CodeAnalysis.Razor.Compiler.dll` of every SDK since 6. */
        const val GENERATOR = "Microsoft.NET.Sdk.Razor.SourceGenerators.RazorSourceGenerator"

        /**
         * On since 0.1.196: R0b brought the harness on `.corpus/razor` to one false error of the core in four solutions (MudBlazor's
         * `INumber<T>`, the index of assemblies), so a project with Razor reports errors once the generator ran, as any other project.
         */
        @Volatile var errorsAfterGeneration = true
    }
}
