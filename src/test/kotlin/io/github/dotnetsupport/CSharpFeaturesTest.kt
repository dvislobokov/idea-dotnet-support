package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatures

/**
 * The features of the plugin's own C# (CSHARP_PSI_MIGRATION.md): the plugin answers for every one of them; a feature that reads the indexes
 * stands back while the IDE builds them. The csharp-psi modules of step 1 are a part of the plugin.
 */
class CSharpFeaturesTest : BasePlatformTestCase() {
    fun testTheRule() {
        for (feature in CSharpFeature.entries) {
            assertTrue("${feature.name}: the plugin answers", CSharpFeatures.native(feature, dumb = false))
            assertEquals("${feature.name}: while the IDE indexes, only what needs no index", !feature.needsIndexes, CSharpFeatures.native(feature, dumb = true))
        }
        assertEquals(
            "what reads the stubs or the index of assemblies",
            setOf(
                CSharpFeature.NAVIGATION, CSharpFeature.COMPLETION, CSharpFeature.DOCUMENTATION, CSharpFeature.SEMANTIC_COLORS, CSharpFeature.RENAME,
                CSharpFeature.CONTEXT_ACTIONS, CSharpFeature.INLAY_HINTS, CSharpFeature.CODE_LENS,
            ),
            CSharpFeature.entries.filter { it.needsIndexes }.toSet(),
        )
        assertTrue("a file of a smart project", CSharpFeatures.native(CSharpFeature.COMPLETION, myFixture.configureByText("Rule.cs", "class Rule { }")))
        assertFalse("no file", CSharpFeatures.native(CSharpFeature.COMPLETION, null as com.intellij.psi.PsiFile?))
    }

    /** Step 1: the three modules are composed into the plugin, their descriptors are included (only the core contributes, step 8). */
    fun testTheCSharpPsiModulesArePartOfThePlugin() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        for (module in listOf("csharp-psi-core", "csharp-psi-semantic", "csharp-psi-ide")) {
            // the build rewrites plugin.xml (patchPluginXml), so the exact layout of the tag is not compared
            assertTrue(module, Regex("""<xi:include href="/META-INF/$module\.xml" xpointer="xpointer\(/idea-plugin/\*\)"\s*/>""").containsMatchIn(pluginXml))
            val descriptor = javaClass.getResource("/META-INF/$module.xml")
            assertNotNull(module, descriptor)
            // the core registers its stubs and stub indexes since step 8; the others nothing yet
            if (module != "csharp-psi-core") assertFalse("$module contributes nothing yet", "<extensions" in descriptor!!.readText())
        }
    }

    /** The language server is gone (0.1.262): no module of its client, no page of its settings. */
    fun testThePluginHasNoLanguageServer() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        assertFalse("io.github.dotnetsupport.roslyn" in pluginXml)
        assertNull(javaClass.getResource("/io.github.dotnetsupport.roslyn.xml"))
        assertFalse("roslyn-language-server" in io.github.dotnetsupport.cli.DotNetTool.entries.map { it.packageId })
    }
}
