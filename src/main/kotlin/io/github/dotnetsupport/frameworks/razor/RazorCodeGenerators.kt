package io.github.dotnetsupport.frameworks.razor

import io.github.dotnetsupport.lang.semantic.CSharpCodeGenerators
import io.github.dotnetsupport.lang.semantic.CSharpProjectFacts

/**
 * Razor components and pages: the Razor source generator of the SDK makes their classes. The helper runs it (RAZOR_PLAN.md, R0): its
 * output is in the index, so completion and navigation see the components. Once it ran and what it made is fresh a missing name would be
 * an error again — but only with [errorsAfterGeneration]: on the corpus that unveils false errors of the core the silence hid (method
 * groups as delegates, `@Icons`, `using static` of nested types...), and an extra error is worse than a missing one. Until they are fixed
 * (R0b) a project with Razor stays silent.
 */
class RazorCodeGenerators : CSharpCodeGenerators {
    override fun mayGenerateTypes(project: CSharpProjectFacts): Boolean =
        !(errorsAfterGeneration && project.ranGenerator(GENERATOR)) &&
            (project.sdkContains("Razor") || project.sdkContains("Blazor") || project.hasFiles("razor") || project.hasFiles("cshtml"))

    companion object {
        /** In `Sdks/Microsoft.NET.Sdk.Razor/source-generators/Microsoft.CodeAnalysis.Razor.Compiler.dll` of every SDK since 6. */
        const val GENERATOR = "Microsoft.NET.Sdk.Razor.SourceGenerators.RazorSourceGenerator"

        /** Off until R0b: the harness on `.corpus/razor` shows no false errors of the core in the projects with Razor. */
        @Volatile var errorsAfterGeneration = false
    }
}
