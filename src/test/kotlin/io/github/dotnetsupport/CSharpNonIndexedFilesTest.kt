package io.github.dotnetsupport

import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.lang.LanguageAnnotators
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.roots.FileIndexFacade
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpColors
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpGotoDeclarationHandler
import io.github.dotnetsupport.lang.CSharpLanguage
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpInactiveCode
import io.github.dotnetsupport.lang.NativeCSharpQuickNavigateInfo
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynPolicy
import io.github.dotnetsupport.solution.DotNetModuleSetup
import java.nio.file.Files

/**
 * A C# file the IDE does not index (robot in WSL, 0.1.63: IDEA opened a fresh folder without a module) still gets the Built-in colors and
 * the plugin's Alt+Enter rows: the platform runs only DumbAware annotators and intentions on such a file (`isUsableInCurrentContext`).
 * Also: the module made for a folder of a solution, the gray of inactive `#if` text, the code in front of the server's messages, the line
 * of Ctrl + hover over a native target.
 */
class CSharpNonIndexedFilesTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.NATIVE)
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        settings.setSource(CSharpFeature.NAVIGATION, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** A file on disk outside every content root: in the local file system and not indexable, as the files of a project without modules. */
    private fun outside(name: String, text: String) {
        val directory = Files.createTempDirectory("dotnet-nonindexed")
        val path = directory.resolve(name)
        Files.writeString(path, text)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        assertFalse(FileIndexFacade.getInstance(project).isIndexable(file))
        myFixture.configureFromExistingVirtualFile(file)
    }

    fun testColorsAndInactiveCodeOfANonIndexedFile() {
        outside("Outside.cs", "class Outside { static void Main() { int n = 1; n++; }\n#if NEVER\n  int skipped = ;\n#endif\n}")
        val highlighted = myFixture.doHighlighting().mapNotNull { info -> info.forcedTextAttributesKey?.let { "${info.text}:${it.externalName}" } }
        assertTrue(highlighted.toString(), "Outside:CSHARP_CLASS_IDENTIFIER" in highlighted)
        assertTrue(highlighted.toString(), "n:CSHARP_MUTABLE_LOCAL_VARIABLE_IDENTIFIER" in highlighted)
        assertTrue(highlighted.toString(), "int skipped = ;:CSHARP_PREPROCESSOR_INACTIVE_BRANCH" in highlighted)
    }

    fun testIntentionsOfANonIndexedFile() {
        outside("OutsideUsing.cs", "using System.IO;\nclass OutsideUsing { void M() { using var <caret>s = new MemoryStream(); s.Flush(); } }")
        val texts = myFixture.availableIntentions.map { it.text }
        assertTrue(texts.toString(), "Convert to 'using' statement" in texts)
    }

    /** What the platform skips on a file it does not index, unless DumbAware: every annotator and intention of the plugin for C#. */
    fun testCSharpAnnotatorsAndIntentionsAreDumbAware() {
        val annotators = LanguageAnnotators.INSTANCE.allForLanguage(CSharpLanguage).filter { it.javaClass.name.startsWith("io.github.dotnetsupport") }
        assertTrue(annotators.isNotEmpty())
        assertEquals(emptyList<String>(), annotators.filter { it !is DumbAware }.map { it.javaClass.simpleName })
        val intentions = IntentionManager.getInstance().availableIntentions.map { IntentionActionDelegate.unwrap(it) }
            .filter { it.javaClass.name.startsWith("io.github.dotnetsupport.lang.") }
        assertTrue(intentions.size > 10)
        assertEquals(emptyList<String>(), intentions.filter { it !is DumbAware }.map { it.javaClass.simpleName })
    }

    fun testInactiveRanges() {
        myFixture.configureByText("Inactive.cs", "class A {\n#if NEVER\n  void B() { }\n#else\n  void C() { }\n#endif\n}")
        val file = myFixture.file as io.github.dotnetsupport.lang.CSharpFile
        assertEquals(listOf("void B() { }"), NativeCSharpInactiveCode.ranges(file).map { it.substring(file.text) })
        assertEquals(CSharpColors.INACTIVE_BRANCH.externalName, "CSHARP_PREPROCESSOR_INACTIVE_BRANCH")
    }

    fun testModuleOnlyForAFolderOfDotNetWithoutModules() {
        assertTrue(DotNetModuleSetup.needsModule(moduleCount = 0, solutions = 1, projects = 0))
        assertTrue(DotNetModuleSetup.needsModule(moduleCount = 0, solutions = 0, projects = 2))
        assertFalse(DotNetModuleSetup.needsModule(moduleCount = 1, solutions = 1, projects = 1))
        assertFalse(DotNetModuleSetup.needsModule(moduleCount = 0, solutions = 0, projects = 0))
        assertEquals("playground", DotNetModuleSetup.moduleName("playground"))
        assertEquals("dotnet", DotNetModuleSetup.moduleName("a:b"))
    }

    fun testServerDiagnosticsCarryTheirCode() {
        assertEquals("CS0230: Type and identifier are both required", RoslynPolicy.diagnosticText("CS0230", "Type and identifier are both required"))
        assertEquals("CS0230: x", RoslynPolicy.diagnosticText("CS0230", "CS0230: x"))
        assertEquals("x", RoslynPolicy.diagnosticText(null, "x"))
    }

    /** Ctrl + hover over a local: the Quick Info line, as the server gives for its targets. */
    fun testCtrlHoverLineOfANativeTarget() {
        myFixture.configureByText("Hover.cs", "class Hover { int M() { int total = 1; return to<caret>tal; } }")
        val source = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val target = CSharpGotoDeclarationHandler().getGotoDeclarationTargets(source, myFixture.caretOffset, myFixture.editor)!!.single()
        val line = NativeCSharpQuickNavigateInfo().getQuickNavigateInfo(target, source)
        assertEquals("(local variable) int total", line)
    }
}
