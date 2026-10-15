package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.semantic.CSharpCodeGenerators
import io.github.dotnetsupport.lang.semantic.CSharpProjectFacts
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * Whether a project may have types no file declares (RAZOR_PLAN.md, F0): the framework modules answer through [CSharpCodeGenerators], and
 * together with the core they answer as the lists of `CSharpSemanticEnvironment.mayGenerateTypes` did before them ([before], verbatim).
 */
class CSharpCodeGeneratorsTest : BasePlatformTestCase() {
    fun testTheFrameworkModulesAreLoaded() {
        val names = CSharpCodeGenerators.EP_NAME.extensionList.map { it.javaClass.name }.toSet()
        assertEquals(setOf(
            "io.github.dotnetsupport.frameworks.razor.RazorCodeGenerators",
            "io.github.dotnetsupport.frameworks.xaml.XamlCodeGenerators",
            "io.github.dotnetsupport.frameworks.avalonia.AvaloniaCodeGenerators",
            "io.github.dotnetsupport.frameworks.grpc.GrpcCodeGenerators",
        ), names)
    }

    fun testTheModulesAnswerAsTheListsOfTheCoreDid() {
        val generators = CSharpCodeGenerators.EP_NAME.extensionList
        val sdks = listOf("", "Microsoft.NET.Sdk", "Microsoft.NET.Sdk.Web", "Microsoft.NET.Sdk.Razor", "Microsoft.NET.Sdk.BlazorWebAssembly",
            "Microsoft.NET.Sdk.WindowsDesktop", "Microsoft.Maui.Sdk")
        val packageSets = listOf(emptyList(), listOf("Newtonsoft.Json"), listOf("Grpc.Tools"), listOf("Avalonia"), listOf("Uno.WinUI"),
            listOf("CommunityToolkit.Mvvm"), listOf("My.SourceGenerators"), listOf("Refit", "Grpc.Tools"))
        val extensions = listOf("razor", "cshtml", "xaml", "axaml", "proto", "cs")
        var cases = 0
        for (sdk in sdks) for (packages in packageSets) for (mask in 0 until (1 shl extensions.size)) for (buildKnown in listOf(false, true))
            for (generatorsRan in listOf(false, true)) for (dumb in listOf(false, true)) {
                val present = extensions.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
                val facts = CSharpProjectFacts(sdk, { packages }, { buildKnown }, { generatorsRan }) { dumb || it in present }
                val now = CSharpSemanticEnvironment.mayGenerateTypes(facts, generators) || dumb
                assertEquals("$sdk $packages $present build=$buildKnown generators=$generatorsRan dumb=$dumb",
                    before(sdk, packages, present, buildKnown, generatorsRan, dumb), now)
                cases++
            }
        assertEquals(7 * 8 * 64 * 8, cases)
    }

    /** `CSharpSemanticEnvironment.mayGenerateTypes` of 0.1.185, the project in a folder. */
    private fun before(sdk: String, packageNames: List<String>, files: Set<String>, buildKnown: Boolean, generatorsRan: Boolean, dumb: Boolean): Boolean {
        if (sdk.contains("Razor", ignoreCase = true) || sdk.contains("Blazor", ignoreCase = true) || sdk.contains("Maui", ignoreCase = true)) return true
        if (sdk.contains("WindowsDesktop", ignoreCase = true) && !buildKnown) return true
        val packages = (if (generatorsRan) listOf("Grpc.Tools", "Avalonia", "Uno.")
            else listOf("Generator", "Grpc.Tools", "CommunityToolkit.Mvvm", "Refit", "Mapperly", "StronglyTypedId", "Vogen", "Avalonia", "Uno."))
            .filter { !buildKnown || it !in setOf("Grpc.Tools") }
        if (packageNames.any { name -> packages.any { name.contains(it, ignoreCase = true) } }) return true
        if (dumb) return true
        return listOf("razor", "cshtml", "xaml", "axaml", "proto").filter { !buildKnown || it !in setOf("xaml", "proto") }.any { it in files }
    }
}
