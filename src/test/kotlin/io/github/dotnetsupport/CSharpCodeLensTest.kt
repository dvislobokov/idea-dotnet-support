package io.github.dotnetsupport

import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.codeInsight.codeVision.CodeVisionInitializer
import com.intellij.codeInsight.codeVision.ui.model.CodeVisionListData
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.rd.createLifetime
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.TestModeFlags
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.CSharpUsageCounts
import io.github.dotnetsupport.lang.NativeCSharpCodeLens
import io.github.dotnetsupport.lang.NativeCSharpCodeVisionProvider
import io.github.dotnetsupport.lang.NativeCSharpDebugTestCodeVisionProvider
import io.github.dotnetsupport.lang.NativeCSharpInheritorsCodeVisionProvider
import io.github.dotnetsupport.lang.NativeCSharpReferencesCodeVisionProvider
import io.github.dotnetsupport.lang.NativeCSharpRunTestCodeVisionProvider
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynOptions
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetRunConfiguration

/**
 * Code Vision without the language server (feature CODE_LENS): "N usages" by the native Find Usages, "N implementations" by the hierarchy,
 * "Run" / "Debug" over tests by the producer of the gutter; the two Code Lens options of the Language Server page switch them off.
 */
class CSharpCodeLensTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        // the Code Vision host computes synchronously and draws the inlays, as the platform's CodeVisionTestCase arranges it
        Registry.get("editor.codeVision.new").setValue(true, testRootDisposable)
        TestModeFlags.set(CodeVisionHost.isCodeVisionTestKey, true, testRootDisposable)
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { null }
        CSharpUsageCounts.getInstance(project).clear()
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            settings.state.options = mutableMapOf()
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun file(path: String, text: String): CSharpFile = myFixture.addFileToProject(path, text.trimIndent()) as CSharpFile

    /** `line: text` of each lens, by the line the lens stands above. */
    private fun lines(file: CSharpFile, lenses: List<NativeCSharpCodeLens.Lens>): List<String> {
        val document = file.viewProvider.document!!
        return lenses.map { "${document.getLineNumber(it.range.startOffset) + 1}: ${it.text}" }
    }

    private fun references(file: CSharpFile): List<String> = lines(file, NativeCSharpCodeLens.referenceLenses(file))

    /**
     * `line: a | b` of the plugin's lenses as the Code Vision host draws them in the editor of the fixture: computed by the host (its test
     * mode, synchronously), merged per line, the entries of one line in a row.
     */
    private fun vision(): List<String> {
        val editor = myFixture.editor
        project.putUserData(CodeVisionHost.isCodeVisionTestKey, true)
        myFixture.doHighlighting()
        CodeVisionInitializer.getInstance(project).getCodeVisionHost().calculateCodeVisionSync(editor, testRootDisposable)
        // above the line or after its end, as the Code Vision settings place them (the test's default is after the line)
        val inlays = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength) + editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength)
        return inlays.sortedBy { it.offset }.mapNotNull { inlay ->
            val ours = inlay.getUserData(CodeVisionListData.KEY)?.visibleLens?.filter { it.providerId in NativeCSharpCodeLens.PROVIDER_IDS }.orEmpty()
            if (ours.isEmpty()) null else "${editor.document.getLineNumber(inlay.offset) + 1}: " + ours.joinToString(" | ") { it.longPresentation }
        }
    }

    fun testUsagesOfTypesAndMembersAcrossTheSolution() {
        val shapes = file("lens1/Shapes.cs", """
            namespace Lens1;
            public interface IShape
            {
                double Area();
            }
            public class Circle : IShape
            {
                public Circle(double radius) { Radius = radius; }
                public double Radius { get; set; }
                public double Area() => 3.14 * Radius * Radius;
                public static int Count;
                private int _unused;
            }
            public enum Kind { Round, Square }
        """)
        file("lens1/Program.cs", """
            using Lens1;
            namespace Lens1.App;
            public static class Program
            {
                public static void Main()
                {
                    IShape shape = new Circle(2);
                    System.Console.WriteLine(shape.Area() + new Circle(1).Radius);
                    Circle.Count++;
                    var kind = Kind.Round;
                }
            }
        """)
        assertEquals(
            listOf(
                "2: 2 usages",        // IShape: the base list of Circle and the type of `shape`
                "4: 1 usage",         // Area(): `shape.Area()` through the interface; the implementation in Circle is a declaration, not a usage
                "6: 3 usages",        // Circle: two `new Circle`, `Circle.Count`
                "8: 2 usages",        // the constructor: the two `new`
                "9: 4 usages",        // Radius: the setter in the constructor, twice in Area, `.Radius` in Program
                "10: 1 usage",        // Area() of Circle: the call through the interface, as Find Usages cascades over the hierarchy
                "11: 1 usage",        // Count
                "12: no usages",      // _unused
                "14: 1 usage",        // Kind
                "14: 1 usage",        // Round
                "14: no usages",      // Square
            ),
            references(shapes),
        )
        val program = (myFixture.findFileInTempDir("lens1/Program.cs")!!.let { com.intellij.psi.PsiManager.getInstance(project).findFile(it) }) as CSharpFile
        assertEquals(listOf("3: no usages", "5: no usages"), references(program))
    }

    /** The native Find Usages resolves a name to every overload (no overload resolution by arguments): the lens says what a click lists. */
    fun testOverloadsCountWhatFindUsagesLists() {
        val file = file("lens2/Overloads.cs", """
            namespace Lens2;
            public class Printer
            {
                public void Print(int value) { }
                public void Print(string value) { }
                public void Run()
                {
                    Print(1);
                    Print(2);
                    Print("three");
                }
            }
        """)
        assertEquals(listOf("2: no usages", "4: 3 usages", "5: 3 usages", "6: no usages"), references(file))
    }

    fun testFieldsDeclaredTogetherShareTheLine() {
        val file = file("lens3/Fields.cs", """
            namespace Lens3;
            public class Pair
            {
                private int _a, _b;
                public int Sum() => _a + _a + _b;
            }
        """)
        assertEquals(listOf("2: no usages", "4: 2 usages", "4: 1 usage", "5: no usages"), references(file))
    }

    /**
     * What the editor shows: the Code Vision host keeps one entry per line of a provider unless the provider says otherwise — `int _a, _b;`
     * showed the count of `_b` alone, a one-line enum the count of its last member above the enum's name.
     */
    fun testEveryDeclarationOfALineKeepsItsEntryInTheEditor() {
        val file = file("lens9/Line.cs", """
            namespace Lens9;
            public class Pair
            {
                private int _a, _b;
                public int Sum() => _a + (int)Kind.Round;
            }
            public enum Kind { Round, Square }
        """)
        myFixture.openFileInEditor(file.virtualFile)
        assertEquals(
            listOf("2: no usages", "4: 1 usage | no usages", "5: no usages", "7: 1 usage | 1 usage | no usages"),
            vision(),
        )
    }

    /**
     * An edit above a declaration moves it; the target of the cached count must find it at its new place — the counts of the members below
     * the edit went to "no usages" until the file was reopened.
     */
    fun testTheCountsSurviveAnEditAboveTheDeclarations() {
        val file = file("lens10/Edit.cs", """
            namespace Lens10;
            // a comment to type in
            public class Square
            {
                public double Side { get; set; }
                public double Area() => Side * Side;
            }
        """)
        val before = listOf("3: no usages", "5: 2 usages", "6: no usages")
        assertEquals(before, references(file))
        myFixture.openFileInEditor(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf("type in") + "type in".length)
        myFixture.type(" and more")
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertTrue(file.text.contains("and more"))
        assertEquals(before, references(file))
    }

    fun testTheCountOutsideTheFileSurvivesTypingInTheFile() {
        val lib = file("lens4/Lib.cs", """
            namespace Lens4;
            public class Lib
            {
                public static void Hello() { }
            }
        """)
        file("lens4/Use.cs", """
            namespace Lens4;
            public class Use
            {
                public void Go() { Lib.Hello(); Lib.Hello(); }
            }
        """)
        assertEquals(listOf("2: 2 usages", "4: 2 usages"), references(lib))
        // an edit in Lib itself: its external counts stay cached, the usage inside the file is new
        myFixture.openFileInEditor(lib.virtualFile)
        myFixture.editor.caretModel.moveToOffset(lib.text.indexOf("{ }") + 1)
        myFixture.type(" Hello();")
        assertEquals(listOf("2: 2 usages", "4: 3 usages"), references(lib))
        // an edit elsewhere drops the cache: a new usage in another file is counted
        val more = file("lens4/More.cs", """
            namespace Lens4;
            public class More { public void Go() => Lib.Hello(); }
        """)
        assertEquals(listOf("2: 3 usages", "4: 4 usages"), references(lib))
        assertEquals(listOf("2: no usages", "2: no usages"), references(more))
    }

    fun testImplementationsOverridesAndInheritors() {
        val file = file("lens5/Hierarchy.cs", """
            namespace Lens5;
            public interface IRunner { void Run(); }
            public abstract class Base : IRunner
            {
                public abstract void Run();
                public virtual void Stop() { }
                public void Plain() { }
            }
            public class Fast : Base
            {
                public override void Run() { }
                public override void Stop() { }
            }
            public class Faster : Fast { }
            public class Lone { }
        """)
        assertEquals(
            // implementations and inheritors all the way down, as Go to Implementation lists them
            listOf("2: 3 implementations", "2: 2 implementations", "3: 2 inheritors", "5: 1 implementation", "6: 1 override", "9: 1 inheritor"),
            lines(file, NativeCSharpCodeLens.inheritorLenses(file)),
        )
    }

    fun testRunAndDebugAboveTestsOfEveryFramework() {
        myFixture.addFileToProject("Lens6.Tests/Lens6.Tests.csproj", """<Project Sdk="Microsoft.NET.Sdk"><ItemGroup><PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.0.0"/></ItemGroup></Project>""")
        val file = file("Lens6.Tests/AllTests.cs", """
            using Xunit;
            using NUnit.Framework;
            using Microsoft.VisualStudio.TestTools.UnitTesting;
            namespace Lens6.Tests;
            public class XunitTests
            {
                [Fact] public void Adds() { }
                [Theory, InlineData(1)] public void Multiplies(int x) { }
                public void Helper() { }
            }
            [TestFixture]
            public class NunitTests
            {
                [Test] public void Divides() { }
                [TestCase(1)] public void Subtracts(int x) { }
            }
            [TestClass]
            public class MsTests
            {
                [TestMethod] public void Rounds() { }
            }
            public class NotTests { public void Nothing() { } }
        """)
        val lenses = NativeCSharpCodeLens.testLenses(file)
        assertEquals(
            listOf("7: XunitTests.Adds", "8: XunitTests.Multiplies", "14: NunitTests.Divides", "15: NunitTests.Subtracts", "20: MsTests.Rounds", "5: XunitTests", "11: NunitTests", "17: MsTests"), // a class lens above its attributes
            lines(file, lenses),
        )
        // the lens runs what the gutter runs: `dotnet test --filter` of the producer
        val method = ConfigurationContext(lenses.first().declaration).configuration!!.configuration as DotNetRunConfiguration
        assertEquals(DotNetCommand.TEST, method.options.command)
        assertEquals("FullyQualifiedName~Lens6.Tests.XunitTests.Adds", method.options.testFilter)
        val type = ConfigurationContext(lenses[5].declaration).configuration!!.configuration as DotNetRunConfiguration
        assertEquals("FullyQualifiedName~Lens6.Tests.XunitTests.", type.options.testFilter)
    }

    fun testTheProvidersFollowTheOptionsAndTheSwitch() {
        myFixture.addFileToProject("Lens7.Tests/Lens7.Tests.csproj", """<Project Sdk="Microsoft.NET.Sdk"><ItemGroup><PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.0.0"/></ItemGroup></Project>""")
        val file = file("Lens7.Tests/Tests.cs", """
            using Xunit;
            namespace Lens7.Tests;
            public class Tests
            {
                [Fact] public void Adds() { Helper(); }
                private void Helper() { }
            }
        """)
        myFixture.openFileInEditor(file.virtualFile)
        val editor = myFixture.editor
        fun texts(provider: NativeCSharpCodeVisionProvider): List<String> =
            provider.computeLenses(editor, file).map { (range, entry) -> "${editor.document.getLineNumber(range.startOffset) + 1}: ${(entry as com.intellij.codeInsight.codeVision.ui.model.TextCodeVisionEntry).text}" }
        val references = NativeCSharpReferencesCodeVisionProvider()
        val run = NativeCSharpRunTestCodeVisionProvider()
        val debug = NativeCSharpDebugTestCodeVisionProvider()
        assertTrue(NativeCSharpCodeLens.referencesEnabled() && NativeCSharpCodeLens.testsEnabled())
        assertEquals(listOf("3: no usages", "5: no usages", "6: 1 usage"), texts(references))
        assertEquals(listOf("5: Run", "3: Run"), texts(run))
        assertEquals(listOf("5: Debug", "3: Debug"), texts(debug))
        assertEquals(NativeCSharpCodeLens.REFERENCES_ID, references.computeLenses(editor, file).first().second.providerId)
        assertEquals(emptyList<String>(), texts(NativeCSharpInheritorsCodeVisionProvider()))

        settings.setValue(RoslynOptions.option("code_lens.dotnet_enable_references_code_lens"), "false")
        assertEquals("the References option is read natively", emptyList<String>(), texts(references))
        assertEquals(listOf("5: Run", "3: Run"), texts(run))
        settings.setValue(RoslynOptions.option("code_lens.dotnet_enable_tests_code_lens"), "false")
        assertEquals("the Run and debug tests option is read natively", emptyList<String>(), texts(run))
        assertEquals(emptyList<String>(), texts(debug))
        settings.setValue(RoslynOptions.option("code_lens.dotnet_enable_references_code_lens"), "true")
        settings.setValue(RoslynOptions.option("code_lens.dotnet_enable_tests_code_lens"), "true")

        // with the server on and the feature given to it, the native lenses stand down; without the server they answer whatever the switch
        settings.state.enabled = true
        settings.setSource(CSharpFeature.CODE_LENS, CSharpFeatureSource.ROSLYN)
        assertFalse(NativeCSharpCodeLens.serves(file))
        assertEquals(emptyList<String>(), texts(references))
        assertEquals(emptyList<String>(), texts(run))
        settings.state.enabled = false
        assertTrue(NativeCSharpCodeLens.serves(file))
        assertEquals(listOf("5: Run", "3: Run"), texts(run))

        // silent while the IDE indexes: the counts need the indexes
        val token = com.intellij.testFramework.DumbModeTestUtils.startEternalDumbModeTask(project)
        try {
            assertFalse(NativeCSharpCodeLens.serves(file))
            assertEquals(emptyList<String>(), texts(references))
        } finally {
            com.intellij.testFramework.DumbModeTestUtils.endEternalDumbModeTaskAndWaitForSmartMode(project, token)
        }
    }

    /**
     * The Code Vision host computes again only when a document changed: Apply asks it in the open editors
     * ([io.github.dotnetsupport.lang.NativeCSharpCodeLensSwitch]), so a toggled option or a switched source is seen without an edit.
     */
    fun testApplyingThePageRedrawsTheLensesOfOpenEditors() {
        val file = file("lens8/Refresh.cs", """
            namespace Lens8;
            public class R
            {
                public void A() { B(); }
                public void B() { }
            }
        """)
        myFixture.openFileInEditor(file.virtualFile)
        // the invalidations Apply sends the host: one per change of the answer, for every provider (an empty list)
        val invalidated = ArrayList<CodeVisionHost.LensInvalidateSignal>()
        val host = CodeVisionInitializer.getInstance(project).getCodeVisionHost()
        host.invalidateProviderSignal.advise(testRootDisposable.createLifetime()) { invalidated += it }
        // a lambda, not a local fun: a local fun that captures nothing compiles to a method `test…$applied`, which JUnit 3 runs as a test
        val applied = {
            com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.syncPublisher(RoslynLanguageServerSettings.CHANGED).settingsChanged(false)
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
        val lenses = listOf("2: no usages", "4: no usages", "5: 1 usage")
        assertEquals(lenses, vision())
        settings.setValue(RoslynOptions.option("code_lens.dotnet_enable_references_code_lens"), "false")
        applied()
        assertEquals("References off: the host is asked, the lenses go without an edit", 1, invalidated.count { it.editor == null && it.providerIds.isEmpty() })
        assertEquals(emptyList<String>(), vision())
        settings.setValue(RoslynOptions.option("code_lens.dotnet_enable_references_code_lens"), "true")
        applied()
        assertEquals("and come back", lenses, vision())
        // the source given to the running server: the plugin's lenses stand down at once
        settings.state.enabled = true
        settings.setSource(CSharpFeature.CODE_LENS, CSharpFeatureSource.ROSLYN)
        applied()
        assertEquals("the server answers", emptyList<String>(), vision())
        settings.setSource(CSharpFeature.CODE_LENS, CSharpFeatureSource.NATIVE)
        applied()
        assertEquals("the plugin again", lenses, vision())
        assertEquals(4, invalidated.count { it.editor == null && it.providerIds.isEmpty() })
    }

    /** Rider shows no lenses over a library source (Source Link), a decompiled type or the metadata view: the sources of the solution alone. */
    fun testNoLensesOverLibrarySourcesAndDecompiledTypes() {
        val text = """
            namespace Lib;
            public static class Host
            {
                public static void Create() { Build(); }
                public static void Build() { }
            }
        """.trimIndent()
        val own = file("lens11/Own.cs", text)
        assertTrue(NativeCSharpCodeLens.serves(own))
        assertEquals(listOf("2: no usages", "4: no usages", "5: 1 usage"), references(own))

        val library = io.github.dotnetsupport.sourcelink.LibrarySourceFile("sha256-lens11", io.github.dotnetsupport.sourcelink.LibrarySourceOrigin(null, "C:/x/Lib.dll", "Lib", "src/Host.cs"), text)
        val decompiled = io.github.dotnetsupport.decompiler.DecompiledFile(
            io.github.dotnetsupport.decompiler.DecompiledKey("C:/x/Lib.dll", "Lib.Host"),
            io.github.dotnetsupport.decompiler.DecompiledType("C:/x/Lib.dll", "Lib", "1.0.0.0", 0, "Lib.Host", text, emptyList()), 0,
        )
        val references = NativeCSharpReferencesCodeVisionProvider()
        for (virtualFile in listOf(library, decompiled)) {
            val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile) as CSharpFile
            assertFalse(virtualFile.toString(), NativeCSharpCodeLens.serves(psi))
            myFixture.openFileInEditor(virtualFile)
            assertEquals(virtualFile.toString(), emptyList<Pair<Any, Any>>(), references.computeLenses(myFixture.editor, psi))
        }
    }

    fun testTexts() {
        assertEquals("no usages", NativeCSharpCodeLens.usagesText(0))
        assertEquals("1 usage", NativeCSharpCodeLens.usagesText(1))
        assertEquals("12 usages", NativeCSharpCodeLens.usagesText(12))
        assertEquals("500+ usages", NativeCSharpCodeLens.usagesText(500))
    }
}
