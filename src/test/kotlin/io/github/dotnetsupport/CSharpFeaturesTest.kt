package io.github.dotnetsupport

import com.intellij.platform.lsp.api.customization.LspRenameSupport
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFeatures
import io.github.dotnetsupport.lsp.RoslynLanguageServerConfigurable
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.roslyn.RoslynClientDescriptor
import io.github.dotnetsupport.roslyn.RoslynFeatures
import javax.swing.JComboBox
import javax.swing.JLabel

/** The switches ROSLYN | NATIVE of CSHARP_PSI_MIGRATION.md, step 2, and the csharp-psi modules of step 1. */
class CSharpFeaturesTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            settings.state.enabled = true
            DotNetBundle.forced = null
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testEverythingIsTodaysPathByDefault() {
        assertEquals("no native implementation yet: nothing to switch to", emptyList<CSharpFeature>(), CSharpFeatures.offered())
        for (feature in CSharpFeature.entries) {
            assertEquals(feature.name, CSharpFeatureSource.ROSLYN, settings.source(feature))
            assertFalse(feature.name, CSharpFeatures.native(feature, project))
            assertTrue(feature.name, RoslynFeatures.serves(feature, project))
        }
        // a NATIVE written by another version of the plugin, for a feature that has no native code here, changes nothing
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        assertFalse(CSharpFeatures.native(CSharpFeature.COMPLETION, project))
        // and without the server the heuristics stay what they are: nothing is native that does not exist
        settings.state.enabled = false
        assertFalse(CSharpFeatures.native(CSharpFeature.COMPLETION, project))
    }

    fun testTheRule() {
        fun native(hasNative: Boolean = true, source: CSharpFeatureSource = CSharpFeatureSource.NATIVE, server: Boolean = true, needsIndexes: Boolean = true, dumb: Boolean = false) =
            CSharpFeatures.native(hasNative, source, server, needsIndexes, dumb)
        assertTrue(native())
        assertFalse("not implemented", native(hasNative = false))
        assertFalse("not implemented, even without the server", native(hasNative = false, server = false))
        assertFalse("the switch", native(source = CSharpFeatureSource.ROSLYN))
        assertTrue("no server: what is built in works", native(source = CSharpFeatureSource.ROSLYN, server = false))
        assertFalse("indexing: today's path answers", native(dumb = true))
        assertTrue("a feature without indexes does not wait for them", native(needsIndexes = false, dumb = true))
    }

    fun testTheChoiceIsStoredForNonDefaultsOnly() {
        settings.setSource(CSharpFeature.RENAME, CSharpFeatureSource.NATIVE)
        assertEquals(mapOf("RENAME" to "NATIVE"), settings.state.features.toMap())

        val stored = XmlSerializer.serialize(settings.state)
        val loaded = RoslynLanguageServerSettings()
        loaded.loadState(XmlSerializer.deserialize(stored, RoslynLanguageServerSettings.Settings::class.java))
        assertEquals(CSharpFeatureSource.NATIVE, loaded.source(CSharpFeature.RENAME))
        assertEquals(CSharpFeatureSource.ROSLYN, loaded.source(CSharpFeature.COMPLETION))

        settings.setSource(CSharpFeature.RENAME, CSharpFeatureSource.ROSLYN)
        assertEquals("the default is not written", emptyMap<String, String>(), settings.state.features.toMap())
        settings.state.features = mutableMapOf("RENAME" to "SOMETHING_ELSE", "NO_SUCH_FEATURE" to "NATIVE")
        assertEquals("an unknown value is the default", CSharpFeatureSource.ROSLYN, settings.source(CSharpFeature.RENAME))
    }

    /** The module of the server reads the switch per request: rename of the server stands down once the feature is native. */
    fun testTheServerStandsDownForANativeFeature() {
        CSharpFeatures.implementForTests(setOf(CSharpFeature.RENAME), testRootDisposable)
        val file = myFixture.addFileToProject("CSharpFeatures/Program.cs", "class Program { }")
        val rename = RoslynClientDescriptor(project, file.virtualFile.parent, java.io.File("/tools/roslyn-language-server")).lspCustomization.renameCustomizer as LspRenameSupport

        assertTrue("ROSLYN by default", rename.shouldRunRename(file))
        assertTrue(RoslynFeatures.serves(CSharpFeature.RENAME, project))
        settings.setSource(CSharpFeature.RENAME, CSharpFeatureSource.NATIVE)
        assertFalse(rename.shouldRunRename(file))
        assertFalse(RoslynFeatures.serves(CSharpFeature.RENAME, project))
        assertTrue("another feature is not affected", RoslynFeatures.serves(CSharpFeature.COMPLETION, project))
        settings.setSource(CSharpFeature.RENAME, CSharpFeatureSource.ROSLYN)
        assertTrue("no restart needed", rename.shouldRunRename(file))
        settings.state.enabled = false
        assertFalse("no server: the native one", RoslynFeatures.serves(CSharpFeature.RENAME, project))
    }

    /** Settings | Tools | .NET | Language Server: a switch per feature that has a native implementation, none today. */
    fun testThePageOffersTheImplementedFeaturesOnly() {
        fun labels(page: RoslynLanguageServerConfigurable) = UIUtil.findComponentsOfType(page.createComponent()!!.also { page.reset() }, JLabel::class.java).map { it.text }
        fun sources(page: RoslynLanguageServerConfigurable) = UIUtil.findComponentsOfType(page.createComponent()!!, JComboBox::class.java)
            .filter { box -> (0 until box.itemCount).map { box.getItemAt(it) } == CSharpFeatureSource.entries }

        val today = RoslynLanguageServerConfigurable(project)
        assertFalse(labels(today).any { it.startsWith("Source of Features") || it == "Rename:" })
        assertEquals(emptyList<Any>(), sources(today))
        today.disposeUIResources()

        CSharpFeatures.implementForTests(setOf(CSharpFeature.RENAME), testRootDisposable)
        val page = RoslynLanguageServerConfigurable(project)
        assertTrue(labels(page).contains("Rename:"))
        val box = sources(page).single()
        assertEquals(CSharpFeatureSource.ROSLYN, box.selectedItem)
        box.selectedItem = CSharpFeatureSource.NATIVE
        assertTrue(page.isModified)
        page.apply()
        assertEquals(CSharpFeatureSource.NATIVE, settings.source(CSharpFeature.RENAME))
        page.disposeUIResources()

        DotNetBundle.forced = PluginLanguage.RUSSIAN
        assertEquals("Переименование", CSharpFeature.RENAME.label)
        assertEquals("Встроенный", CSharpFeatureSource.NATIVE.label)
        DotNetBundle.forced = null
        for (constant in CSharpFeature.entries + CSharpFeatureSource.entries) assertEquals("stored by name", constant.name, constant.toString())
        for (feature in CSharpFeature.entries) assertEquals("the English text is the one of the code", feature.title, feature.label)
    }

    /** Step 1: the three modules are composed into the plugin, their descriptors are included and still empty. */
    fun testTheCSharpPsiModulesArePartOfThePlugin() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        for (module in listOf("csharp-psi-core", "csharp-psi-semantic", "csharp-psi-ide")) {
            // the build rewrites plugin.xml (patchPluginXml), so the exact layout of the tag is not compared
            assertTrue(module, Regex("""<xi:include href="/META-INF/$module\.xml" xpointer="xpointer\(/idea-plugin/\*\)"\s*/>""").containsMatchIn(pluginXml))
            val descriptor = javaClass.getResource("/META-INF/$module.xml")
            assertNotNull(module, descriptor)
            assertFalse("$module contributes nothing before step 7", "<extensions" in descriptor!!.readText())
        }
    }
}
