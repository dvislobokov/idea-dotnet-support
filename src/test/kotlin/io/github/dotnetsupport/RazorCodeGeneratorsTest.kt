package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.razor.RazorCodeGenerators
import io.github.dotnetsupport.lang.semantic.CSharpCodeGenerators
import io.github.dotnetsupport.lang.semantic.CSharpProjectFacts
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * RAZOR_PLAN.md, R0: a project with Razor keeps the semantics silent until the helper has run Razor's generator and what it made is fresh —
 * then its components and pages are in the index like any other type, and a missing name may be an error again. The last part waits for
 * R0b ([RazorCodeGenerators.errorsAfterGeneration]): until then the project stays silent whatever ran.
 */
class RazorCodeGeneratorsTest : BasePlatformTestCase() {
    private fun facts(sdk: String = "Microsoft.NET.Sdk.Razor", ran: Boolean, generators: Set<String>, files: Set<String> = setOf("razor")) =
        CSharpProjectFacts(sdk, { emptyList() }, { false }, { ran }, { generators }) { it in files }

    override fun tearDown() {
        try {
            RazorCodeGenerators.errorsAfterGeneration = false
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testSilentUntilR0b() {
        val razor = RazorCodeGenerators()
        assertTrue(razor.mayGenerateTypes(facts(ran = true, generators = setOf(RazorCodeGenerators.GENERATOR))))
        assertFalse("a project without Razor", razor.mayGenerateTypes(facts(sdk = "Microsoft.NET.Sdk", ran = false, generators = emptySet(), files = setOf("cs"))))
    }

    fun testSilentUntilItsGeneratorRan() {
        RazorCodeGenerators.errorsAfterGeneration = true
        val razor = RazorCodeGenerators()
        assertTrue("no run yet", razor.mayGenerateTypes(facts(ran = false, generators = emptySet())))
        assertTrue("a run that is stale (an edited .razor)", razor.mayGenerateTypes(facts(ran = false, generators = setOf(RazorCodeGenerators.GENERATOR))))
        assertTrue("a run without Razor's generator (it did not load)", razor.mayGenerateTypes(facts(ran = true, generators = setOf("System.Text.Json.SourceGeneration.JsonSourceGenerator"))))
        assertFalse("fresh, with Razor's generator", razor.mayGenerateTypes(facts(ran = true, generators = setOf(RazorCodeGenerators.GENERATOR))))
        assertFalse("views of MVC alike", razor.mayGenerateTypes(facts(sdk = "Microsoft.NET.Sdk.Web", ran = true, generators = setOf(RazorCodeGenerators.GENERATOR), files = setOf("cshtml"))))
    }

    fun testTheWholeProjectIsKnownOnceRazorRan() {
        RazorCodeGenerators.errorsAfterGeneration = true
        val generators = CSharpCodeGenerators.EP_NAME.extensionList
        val known = facts(sdk = "Microsoft.NET.Sdk.Web", ran = true, generators = setOf(RazorCodeGenerators.GENERATOR), files = setOf("razor", "cshtml", "cs"))
        assertFalse(CSharpSemanticEnvironment.mayGenerateTypes(known, generators))
        val withXaml = facts(sdk = "Microsoft.NET.Sdk.Web", ran = true, generators = setOf(RazorCodeGenerators.GENERATOR), files = setOf("razor", "xaml"))
        assertTrue("another framework still unknown keeps it silent", CSharpSemanticEnvironment.mayGenerateTypes(withXaml, generators))
    }
}
