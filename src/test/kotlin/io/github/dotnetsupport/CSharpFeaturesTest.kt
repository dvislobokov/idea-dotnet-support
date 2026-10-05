package io.github.dotnetsupport

import com.intellij.platform.lsp.api.customization.LspRenameSupport
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFeatures
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
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
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
            DotNetBundle.forced = null
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Every feature starts at its default: the tree of step 7 is native (0.1.45), the rest is today's path (the kinds of usages too, until the robot). */
    fun testEveryFeatureStartsAtItsDefault() {
        assertEquals(
            "the tree of step 7, the formatting (0.1.49), the typing assistance (0.1.48), the kinds of usages (0.1.46), navigation (0.1.50), completion (0.1.55), " +
                "the syntax errors (0.1.54), the colors (0.1.51), rename (0.1.53) and the context actions (0.1.64) of step 9, the documentation (0.1.66) of step 11",
            listOf(
                CSharpFeature.SYNTAX_TREE, CSharpFeature.FORMATTING, CSharpFeature.EDITING, CSharpFeature.USAGE_KINDS, CSharpFeature.NAVIGATION,
                CSharpFeature.COMPLETION, CSharpFeature.DOCUMENTATION, CSharpFeature.DIAGNOSTICS, CSharpFeature.SEMANTIC_COLORS, CSharpFeature.RENAME,
                CSharpFeature.CONTEXT_ACTIONS,
            ),
            CSharpFeatures.offered(),
        )
        assertEquals("navigation is native after the robot (0.1.60)", CSharpFeatureSource.NATIVE, CSharpFeature.NAVIGATION.defaultSource)
        assertEquals("the colors are native after the robot (0.1.60)", CSharpFeatureSource.NATIVE, CSharpFeature.SEMANTIC_COLORS.defaultSource)
        assertEquals("the syntax errors are native after the robot (0.1.56)", CSharpFeatureSource.NATIVE, CSharpFeature.DIAGNOSTICS.defaultSource)
        assertEquals("rename is native after the robot (0.1.56)", CSharpFeatureSource.NATIVE, CSharpFeature.RENAME.defaultSource)
        assertEquals("completion is native after the robot (0.1.60)", CSharpFeatureSource.NATIVE, CSharpFeature.COMPLETION.defaultSource)
        assertEquals("the documentation is native after the robot (0.1.72)", CSharpFeatureSource.NATIVE, CSharpFeature.DOCUMENTATION.defaultSource)
        assertEquals("the context actions are native after the robot (0.1.72)", CSharpFeatureSource.NATIVE, CSharpFeature.CONTEXT_ACTIONS.defaultSource)
        assertTrue("completion reads the types of the solution from the stubs", CSharpFeature.COMPLETION.needsIndexes)
        assertFalse("the syntax errors read the file alone", CSharpFeature.DIAGNOSTICS.needsIndexes)
        assertTrue("the colors read the stubs of the solution", CSharpFeature.SEMANTIC_COLORS.needsIndexes)
        assertTrue("types of the solution come from the stub index", CSharpFeature.NAVIGATION.needsIndexes)
        assertEquals(CSharpFeatureSource.NATIVE, CSharpFeature.SYNTAX_TREE.defaultSource)
        assertEquals(CSharpFeatureSource.NATIVE, CSharpFeature.USAGE_KINDS.defaultSource)
        assertFalse("the kinds read the file's own tree, no index", CSharpFeature.USAGE_KINDS.needsIndexes)
        assertFalse("so does the typing assistance", CSharpFeature.EDITING.needsIndexes)
        assertEquals("the typing assistance is native after the robot (0.1.48)", CSharpFeatureSource.NATIVE, CSharpFeature.EDITING.defaultSource)
        assertEquals("the formatter matched the server on the playground (robot, 0.1.49)", CSharpFeatureSource.NATIVE, CSharpFeature.FORMATTING.defaultSource)
        assertFalse("the formatter reads the file's own tree", CSharpFeature.FORMATTING.needsIndexes)
        for (feature in CSharpFeature.entries) {
            // every offered feature: since 0.1.60 navigation, completion and the colors, since 0.1.72 the documentation and the context actions (robot)
            val native = true
            assertEquals(feature.name, if (native) CSharpFeatureSource.NATIVE else CSharpFeatureSource.ROSLYN, settings.source(feature))
            assertEquals(feature.name, native, CSharpFeatures.native(feature, project))
            assertEquals(feature.name, !native, RoslynFeatures.serves(feature, project))
        }
        assertEquals("defaults are not stored", emptyMap<String, String>(), settings.state.features.toMap())
        assertTrue("the documentation reads the stubs and the index of assemblies", CSharpFeature.DOCUMENTATION.needsIndexes)
        // a NATIVE written by another version of the plugin, for a feature that has no native code here, changes nothing
        CSharpFeatures.implementForTests(CSharpFeatures.offered().toSet() - CSharpFeature.DOCUMENTATION, testRootDisposable)
        settings.setSource(CSharpFeature.DOCUMENTATION, CSharpFeatureSource.NATIVE)
        assertFalse(CSharpFeatures.native(CSharpFeature.DOCUMENTATION, project))
        // and without the server the heuristics stay what they are: nothing is native that does not exist
        settings.state.enabled = false
        assertFalse(CSharpFeatures.native(CSharpFeature.DOCUMENTATION, project))
    }

    /** The tree of C# files: application settings only, the rule of [CSharpFeatures.native] with the server off included. */
    fun testTheSyntaxTreeSwitch() {
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        assertTrue("the native tree by default", CSharpSyntaxTrees.nativeTree())
        assertEquals(true, CSharpSyntaxTrees.lastAnswer)
        assertEquals("the project-level answer is the same", true, CSharpFeatures.native(CSharpFeature.SYNTAX_TREE, project))
        settings.setSource(CSharpFeature.SYNTAX_TREE, CSharpFeatureSource.ROSLYN)
        assertEquals("a choice away from the default is stored", mapOf("SYNTAX_TREE" to "ROSLYN"), settings.state.features.toMap())
        assertFalse("a stored ROSLYN: the heuristic tree", CSharpSyntaxTrees.nativeTree())
        assertEquals(false, CSharpSyntaxTrees.lastAnswer)
        assertFalse(CSharpFeatures.native(CSharpFeature.SYNTAX_TREE, project))
        settings.state.enabled = false
        assertTrue("no server: the native tree, by design of the rule", CSharpSyntaxTrees.nativeTree())
        settings.state.enabled = true
        assertFalse(CSharpSyntaxTrees.nativeTree())
        settings.setSource(CSharpFeature.SYNTAX_TREE, CSharpFeatureSource.NATIVE)
        assertEquals("back to the default: nothing stored", emptyMap<String, String>(), settings.state.features.toMap())
        assertTrue(CSharpSyntaxTrees.nativeTree())
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
        settings.setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.ROSLYN)
        assertEquals(mapOf("SEMANTIC_COLORS" to "ROSLYN"), settings.state.features.toMap())

        val stored = XmlSerializer.serialize(settings.state)
        val loaded = RoslynLanguageServerSettings()
        loaded.loadState(XmlSerializer.deserialize(stored, RoslynLanguageServerSettings.Settings::class.java))
        assertEquals(CSharpFeatureSource.ROSLYN, loaded.source(CSharpFeature.SEMANTIC_COLORS))
        assertEquals(CSharpFeatureSource.NATIVE, loaded.source(CSharpFeature.COMPLETION))
        assertEquals(CSharpFeatureSource.NATIVE, loaded.source(CSharpFeature.DOCUMENTATION))

        settings.setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.NATIVE)
        assertEquals("the default is not written", emptyMap<String, String>(), settings.state.features.toMap())
        settings.state.features = mutableMapOf("SEMANTIC_COLORS" to "SOMETHING_ELSE", "NO_SUCH_FEATURE" to "NATIVE")
        assertEquals("an unknown value is the default", CSharpFeatureSource.NATIVE, settings.source(CSharpFeature.SEMANTIC_COLORS))
        settings.state.features = mutableMapOf()
    }

    /** The module of the server reads the switch per request: rename of the server stands down once the feature is native. */
    fun testTheServerStandsDownForANativeFeature() {
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        CSharpFeatures.implementForTests(setOf(CSharpFeature.RENAME), testRootDisposable)
        val file = myFixture.addFileToProject("CSharpFeatures/Program.cs", "class Program { }")
        val rename = RoslynClientDescriptor(project, file.virtualFile.parent, java.io.File("/tools/roslyn-language-server")).lspCustomization.renameCustomizer as LspRenameSupport

        settings.setSource(CSharpFeature.RENAME, CSharpFeatureSource.ROSLYN)
        assertTrue("ROSLYN chosen", rename.shouldRunRename(file))
        assertTrue(RoslynFeatures.serves(CSharpFeature.RENAME, project))
        settings.setSource(CSharpFeature.RENAME, CSharpFeatureSource.NATIVE)
        // a file of the heuristic tree keeps the server's rename; one of the native tree is the plugin's handler's (NativeCSharpRename)
        CSharpSyntaxTrees.forceNativeTreeForTests(false)
        assertTrue(rename.shouldRunRename(myFixture.addFileToProject("CSharpFeatures/Heuristic.cs", "class Heuristic { }")))
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        assertFalse(rename.shouldRunRename(myFixture.addFileToProject("CSharpFeatures/Native.cs", "class Native { }")))
        CSharpSyntaxTrees.forceNativeTreeForTests(null)
        assertFalse(RoslynFeatures.serves(CSharpFeature.RENAME, project))
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.ROSLYN)
        assertTrue("another feature is not affected", RoslynFeatures.serves(CSharpFeature.COMPLETION, project))
        settings.setSource(CSharpFeature.RENAME, CSharpFeatureSource.ROSLYN)
        assertTrue("no restart needed", rename.shouldRunRename(file))
        settings.state.enabled = false
        assertFalse("no server: the native one", RoslynFeatures.serves(CSharpFeature.RENAME, project))
    }

    /** The platform shows every provider's documentation as pages of one popup: the server's hover is off while Built-in answers (robot, E-83). */
    fun testTheServersHoverFollowsTheDocumentationSwitch() {
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        val file = myFixture.addFileToProject("CSharpFeatures/Hover.cs", "class Hover { }")
        val customization = RoslynClientDescriptor(project, file.virtualFile.parent, java.io.File("/tools/roslyn-language-server")).lspCustomization
        assertSame(com.intellij.platform.lsp.api.customization.LspHoverDisabled, customization.hoverCustomizer)
        settings.setSource(CSharpFeature.DOCUMENTATION, CSharpFeatureSource.ROSLYN)
        assertTrue(customization.hoverCustomizer is com.intellij.platform.lsp.api.customization.LspHoverSupport)
        settings.setSource(CSharpFeature.DOCUMENTATION, CSharpFeatureSource.NATIVE)
        assertSame("read per request", com.intellij.platform.lsp.api.customization.LspHoverDisabled, customization.hoverCustomizer)
    }

    /** Settings | .NET | Language Server: a switch per feature that has a native implementation: the tree, the formatting, the typing assistance, the kinds of usages. */
    fun testThePageOffersTheImplementedFeaturesOnly() {
        fun labels(page: RoslynLanguageServerConfigurable) = UIUtil.findComponentsOfType(page.createComponent()!!.also { page.reset() }, JLabel::class.java).map { it.text }
        fun sources(page: RoslynLanguageServerConfigurable) = UIUtil.findComponentsOfType(page.createComponent()!!, JComboBox::class.java)
            .filter { box -> (0 until box.itemCount).map { box.getItemAt(it) } == CSharpFeatureSource.entries }

        val today = RoslynLanguageServerConfigurable(project)
        assertTrue(labels(today).contains("Structure, folding and breadcrumbs:"))
        assertTrue(labels(today).contains("Kinds of usages:"))
        assertTrue(labels(today).contains("Typing assistance:"))
        assertTrue(labels(today).contains("Formatting:"))
        assertTrue(labels(today).contains("Navigation and usages:"))
        assertTrue(labels(today).contains("Rename:"))
        assertTrue(labels(today).contains("Errors and warnings:"))
        assertTrue(labels(today).contains("Completion:"))
        assertTrue(labels(today).contains("Documentation and parameter info:"))
        assertEquals(
            "every offered feature at its default: native since the robot of 0.1.60, the documentation and the context actions since 0.1.72",
            CSharpFeatures.offered().map { it.defaultSource },
            sources(today).map { it.selectedItem },
        )
        today.disposeUIResources()

        CSharpFeatures.implementForTests(setOf(CSharpFeature.DOCUMENTATION), testRootDisposable)
        val page = RoslynLanguageServerConfigurable(project)
        assertTrue(labels(page).contains("Documentation and parameter info:"))
        val box = sources(page).single()
        assertEquals(CSharpFeatureSource.NATIVE, box.selectedItem)
        box.selectedItem = CSharpFeatureSource.ROSLYN
        assertTrue(page.isModified)
        page.apply()
        assertEquals(CSharpFeatureSource.ROSLYN, settings.source(CSharpFeature.DOCUMENTATION))
        page.disposeUIResources()

        DotNetBundle.forced = PluginLanguage.RUSSIAN
        assertEquals("Переименование", CSharpFeature.RENAME.label)
        assertEquals("Встроенный", CSharpFeatureSource.NATIVE.label)
        DotNetBundle.forced = null
        for (constant in CSharpFeature.entries + CSharpFeatureSource.entries) assertEquals("stored by name", constant.name, constant.toString())
        for (feature in CSharpFeature.entries) assertEquals("the English text is the one of the code", feature.title, feature.label)
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
}
