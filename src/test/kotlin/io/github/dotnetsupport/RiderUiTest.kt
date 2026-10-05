package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.build.BuildConfigurationChoices
import io.github.dotnetsupport.build.BuildSolutionBar
import io.github.dotnetsupport.lang.CSharpEditorMenuGroup
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpGenerateAction
import io.github.dotnetsupport.lang.CSharpNavigateToAction
import io.github.dotnetsupport.lang.CSharpPopupKind
import io.github.dotnetsupport.lang.CSharpRefactorThisAction
import io.github.dotnetsupport.lang.CSharpRiderPopups
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.RiderNamedAction
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import javax.swing.KeyStroke

/**
 * The places of the UI made like Rider's (0.1.70): the .NET node at the root of Settings, the Build Solution button of the main toolbar,
 * Refactor This / Navigate To / Generate in a C# editor with Rider's shortcuts of the default keymap, the rows of the editor's menu.
 */
class RiderUiTest : BasePlatformTestCase() {
    private val actions get() = ActionManager.getInstance()

    override fun tearDown() {
        try {
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun ids(group: String): List<String?> = (actions.getAction(group) as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }

    private fun event(place: String = ActionPlaces.UNKNOWN, withEditor: Boolean = true): AnActionEvent {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
            .apply { if (withEditor) add(CommonDataKeys.EDITOR, myFixture.editor).add(CommonDataKeys.PSI_FILE, myFixture.file) }.build()
        return AnActionEvent.createEvent(context, null, place, if (place == ActionPlaces.EDITOR_POPUP) ActionUiKind.POPUP else ActionUiKind.NONE, null)
    }

    // ---- Settings

    fun testDotNetSettingsAreAtTheRootOfSettings() {
        val pages = Configurable.PROJECT_CONFIGURABLE.getExtensions(project) + Configurable.APPLICATION_CONFIGURABLE.extensionList
        val parent = pages.single { it.id == "io.github.dotnetsupport.settings" }
        assertEquals("root", parent.parentId)
        assertEquals(
            setOf("settings.build", "settings.nuget", "settings.coverage", "settings.debugger", "settings.codeAnalysis", "settings.languageServer"),
            pages.filter { it.parentId == "io.github.dotnetsupport.settings" }.map { it.id.removePrefix("io.github.dotnetsupport.") }.toSet(),
        )
        // the tree the Settings dialog builds: .NET among the top-level nodes, not under Tools
        val root = ConfigurableExtensionPointUtil.getConfigurableGroup(project, true)
        assertTrue(root.configurables.any { (it as? SearchableConfigurable)?.id == "io.github.dotnetsupport.settings" })
        val tools = root.configurables.filterIsInstance<SearchableConfigurable>().firstOrNull { it.id == "tools" }
        if (tools is Configurable.Composite) assertFalse(tools.configurables.any { (it as? SearchableConfigurable)?.id == "io.github.dotnetsupport.settings" })
    }

    // ---- Toolbar

    fun testBuildSolutionButtonReplacesTheComboBoxes() {
        assertNull("the Debug | net9.0 combo box is gone", actions.getAction("DotNet.BuildConfiguration"))
        assertTrue(actions.getAction("DotNet.BuildSolutionBar") is BuildSolutionBar)
        val toolbar = ids("MainToolbarRight")
        assertTrue(toolbar.contains("DotNet.BuildSolutionBar"))
        val run = toolbar.indexOf("NewUiRunWidget")
        if (run >= 0) assertTrue("before the Run widget, as in Rider", toolbar.indexOf("DotNet.BuildSolutionBar") < run)

        assertEquals(
            listOf("DotNet.BuildSolution", "DotNet.RebuildSolution", "DotNet.CleanSolution", "DotNet.RestoreSolution", "DotNet.CancelBuild", "DotNet.BuildConfigurationChoices"),
            ids(BuildSolutionBar.POPUP_GROUP),
        )
        // no solution: the button hides itself
        val presentation = event(withEditor = false).also { actions.getAction("DotNet.BuildSolutionBar").update(it) }.presentation
        assertFalse(presentation.isVisible)
    }

    fun testConfigurationChoicesOfTheButton() {
        val children = BuildConfigurationChoices().getChildren(event(withEditor = false))
        assertEquals("Configuration", (children.first() as Separator).text)
        // a folder without a solution: Debug and Release, Debug chosen
        val choices = children.filterIsInstance<ToggleAction>()
        assertEquals(listOf("Debug", "Release"), choices.map { it.templatePresentation.text })
        assertTrue(choices.first().isSelected(event(withEditor = false)))
    }

    // ---- Popups of the editor

    fun testPlatformActionsAreOverriddenWithTheirTextsAndShortcuts() {
        val refactor = actions.getAction("Refactorings.QuickListPopupAction")
        val generate = actions.getAction("Generate")
        assertTrue(refactor is CSharpRefactorThisAction)
        assertTrue(generate is CSharpGenerateAction)
        assertTrue(actions.getAction("DotNet.NavigateTo") is CSharpNavigateToAction)
        assertFalse(refactor.templatePresentation.text.isNullOrBlank())
        assertFalse(generate.templatePresentation.text.isNullOrBlank())

        // Rider's shortcuts of its default keymap (the keymaps of Rider 2026.2): Refactor This Ctrl+Alt+Shift+T, Navigate To Ctrl+Shift+G, Generate Alt+Insert
        val keymap = KeymapManagerEx.getInstanceEx().getKeymap(KeymapManager.DEFAULT_IDEA_KEYMAP)!!
        fun keys(id: String) = keymap.getShortcuts(id).filterIsInstance<KeyboardShortcut>().map { it.firstKeyStroke }
        assertTrue(KeyStroke.getKeyStroke("control alt shift T") in keys("Refactorings.QuickListPopupAction"))
        assertTrue(KeyStroke.getKeyStroke("control shift G") in keys("DotNet.NavigateTo"))
        assertTrue(KeyStroke.getKeyStroke("alt INSERT") in keys("Generate"))
        assertTrue("in Navigate of the main menu", ids("GoToMenu").contains("DotNet.NavigateTo"))
    }

    fun testTheActionsTakeOverOnlyInCSharp() {
        myFixture.configureByText("Popups0.cs", "class A { void M() { } }")
        for (action in listOf(CSharpRefactorThisAction(), CSharpGenerateAction(), CSharpNavigateToAction())) {
            val e = event()
            action.update(e)
            assertTrue(action.javaClass.simpleName, e.presentation.isEnabledAndVisible)
        }
        val inMenu = event(ActionPlaces.EDITOR_POPUP)
        CSharpGenerateAction().update(inMenu)
        assertEquals("Rider's name in the menu of the editor", "Generate Code...", inMenu.presentation.text)

        myFixture.configureByText("notes.txt", "text")
        val e = event(ActionPlaces.EDITOR_POPUP)
        CSharpNavigateToAction().update(e)
        assertFalse(e.presentation.isVisible)
    }

    fun testNavigateToRowsInRiderOrder() {
        val rows = CSharpRiderPopups.navigateGroup().childActionsOrStubs.filterIsInstance<RiderNamedAction>().map { actions.getId(it.delegate) to it }
        assertEquals(listOf("GotoDeclaration", "GotoImplementation", "GotoSuperMethod", "FindUsages", "GotoRelated", "GotoTypeDeclaration"), rows.map { it.first }.take(6))
        // a row keeps the shortcut of its action
        val declaration = rows.first().second
        assertEquals(actions.getAction("GotoDeclaration").shortcutSet.shortcuts.toList(), declaration.shortcutSet.shortcuts.toList())
        myFixture.configureByText("Popups1.cs", "class A { void M() { } }")
        val e = event()
        declaration.update(e)
        assertEquals("Declaration", e.presentation.text)
    }

    fun testRefactorThisListsTheNativeRefactoringsAtTheCaret() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.CONTEXT_ACTIONS, CSharpFeatureSource.NATIVE)
        myFixture.configureByText("Popups2.cs", "class Sample\n{\n    int M(int a, int b)\n    {\n        var <caret>sum = a + b;\n        return sum * 2;\n    }\n}\n")
        val rows = CSharpRiderPopups.nativeActions(CSharpPopupKind.REFACTOR, project, myFixture.editor, myFixture.file).map { it.templatePresentation.text }
        assertTrue(rows.toString(), "Inline Variable" in rows)

        val group = CSharpRiderPopups.refactorGroup(emptyList()).childActionsOrStubs.filterIsInstance<RiderNamedAction>().map { actions.getId(it.delegate) }
        assertEquals("Rider's first four", listOf("RenameElement", "ChangeSignature", "Inline", "SafeDelete"), group.take(4))
        val extract = CSharpRiderPopups.refactorGroup(emptyList()).childActionsOrStubs.filterIsInstance<RiderNamedAction>().first { actions.getId(it.delegate) == "ExtractMethod" }
        assertTrue("the native Extract Method answers the row", extract.delegate is io.github.dotnetsupport.lang.CSharpExtractMethodAction)
    }

    fun testGeneratorsInRiderOrder() {
        val titles = listOf("Generate Equals and GetHashCode...", "Unit Test", "Generate overrides...", "Generate constructor 'Order(int)'", "Implement interface")
        assertEquals(
            listOf("Generate constructor 'Order(int)'", "Implement interface", "Generate overrides...", "Generate Equals and GetHashCode...", "Unit Test"),
            titles.sortedBy(CSharpRiderPopups::generateRank),
        )
        val group = CSharpRiderPopups.generateGroup(emptyList())
        assertTrue("the rest of the Generate group follows: Insert New GUID", group.childActionsOrStubs.any { it is io.github.dotnetsupport.lang.HideDisabledGroup })
        val rest = group.childActionsOrStubs.filterIsInstance<io.github.dotnetsupport.lang.HideDisabledGroup>().single().getChildren(null)
            .map { actions.getId((it as? com.intellij.openapi.actionSystem.AnActionWrapper)?.delegate ?: it) }
        assertTrue(rest.toString(), "ImplementMethods" !in rest && "OverrideMethods" !in rest)
        val native = listOf("Constructor", "Read-only properties", "Properties", "Missing members", "Overriding members", "Delegating members", "Partial members",
            "Deconstructor", "Equality members", "Equality comparer", "Relational members", "Relational comparer", "Formatting members", "Dispose pattern", "Unit Test")
        assertEquals("Rider's order of the native rows", native, native.shuffled(java.util.Random(7)).sortedBy(CSharpRiderPopups::generateRank))
    }

    fun testEditorMenuRowsOfRider() {
        assertTrue(ids("EditorPopupMenu1.FindRefactor").let { it.indexOf("DotNet.CSharpEditor.FindUsagesAdvanced") == it.indexOf("FindUsages") + 1 })
        assertTrue(ids("EditorPopupMenu1").contains("DotNet.CSharpEditor.Inspect"))
        assertEquals(listOf("CallHierarchy", "TypeHierarchy", "DotNet.IlViewer"), ids("DotNet.CSharpEditor.Inspect"))
        assertTrue(ids("EditorLangPopupMenu").contains("DotNet.CSharpEditor.QuickDefinition"))

        myFixture.configureByText("Popups3.cs", "class A { }")
        val e = event(ActionPlaces.EDITOR_POPUP)
        CSharpEditorMenuGroup().update(e)
        assertTrue(e.presentation.isVisible)
        myFixture.configureByText("notes2.txt", "text")
        val other = event(ActionPlaces.EDITOR_POPUP)
        CSharpEditorMenuGroup().update(other)
        assertFalse(other.presentation.isVisible)
    }
}
