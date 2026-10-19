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

    /**
     * Razor's generator makes its classes of the `.razor` / `.cshtml` files: an edit of a `.cs` does not change them, so the project keeps
     * reporting errors while one is typed (the live check of 0.1.196 found it silent). What it takes from the C# — a `[Parameter]` of a
     * code-behind for the markup of another component — follows at the next save of a `.razor` or a build.
     */
    override fun readsOnlyAdditionalFiles(generatorTypeName: String): Boolean = generatorTypeName == GENERATOR

    /**
     * The C# document of a Razor file is the generator's output of that file — the helper's (or the one written ahead), or the SDK's in
     * `obj/…/generated/…/RazorSourceGenerator/<hint>` of the same project: not a second part of its class.
     */
    override fun isDocumentOutput(document: com.intellij.psi.PsiFile, other: com.intellij.openapi.vfs.VirtualFile): Boolean {
        if (!other.name.endsWith(".g.cs", ignoreCase = true)) return false
        val manager = com.intellij.lang.injection.InjectedLanguageManager.getInstance(document.project)
        if (!manager.isInjectedFragment(document)) return false
        val razor = RazorTagHelperEditor.razorFile(manager.getTopLevelFile(document))?.viewProvider?.virtualFile ?: return false
        val service = document.project.getServiceIfCreated(io.github.dotnetsupport.codeanalysis.CodeAnalysisService::class.java)
        if (service != null && RazorGeneratedAhead.outputOf(service, razor.path)?.let { com.intellij.openapi.util.io.FileUtil.pathsEqual(com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(it.path), other.path) } == true) return true
        val model = io.github.dotnetsupport.msbuild.CompilationModel.getInstance(document.project)
        val projectFile = model.projectOf(razor) ?: return false
        val folder = projectFile.parent?.path ?: return false
        if (!razor.path.startsWith("$folder/")) return false
        val hint = RazorGeneratedAhead.hintName(razor.path.substring(folder.length + 1))
        return other.path.endsWith("/$GENERATOR/$hint", ignoreCase = true) && other.path.startsWith("$folder/") && model.projectOf(other) == projectFile
    }

    /**
     * A Razor file its project leaves out (`<Content Remove="Plugins\**">` for a `.cshtml` of the Web SDK, `DefaultItemExcludes`) is not
     * compiled — the views nopCommerce's build copies into `Nop.Web/Plugins/…`; by the items of its project file, as the Solution view
     * marks excluded files (the evaluated `Compile` list does not name Razor files).
     */
    override fun isDocumentCompiled(document: com.intellij.psi.PsiFile): Boolean? {
        val manager = com.intellij.lang.injection.InjectedLanguageManager.getInstance(document.project)
        if (!manager.isInjectedFragment(document)) return null
        // a piece of the head of the class in an injection of its own: its class has none of the members, the document checks the piece
        if ((manager.getInjectionHost(document) as? RazorCodeLiteral)?.let(RazorCSharpDocument::isHeadHost) == true) return false
        val razor = RazorTagHelperEditor.razorFile(manager.getTopLevelFile(document))?.viewProvider?.virtualFile ?: return null
        val owning = io.github.dotnetsupport.msbuild.DotNetProjects.findOwningProject(razor)?.takeIf { it.extension.equals("csproj", ignoreCase = true) } ?: return null
        val relative = com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(razor, owning.parent, '/') ?: return null
        val msbuild = io.github.dotnetsupport.solution.SolutionService.getInstance(document.project).msBuildProject(owning)
        return !io.github.dotnetsupport.msbuild.ProjectContent(msbuild).isExcluded(relative)
    }

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
