package io.github.dotnetsupport

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCallGraph
import io.github.dotnetsupport.lang.NativeCSharpCallHierarchyProvider
import io.github.dotnetsupport.lang.NativeCSharpGotoSuperHandler
import io.github.dotnetsupport.lang.NativeCSharpHierarchies
import io.github.dotnetsupport.lang.NativeCSharpTypeHierarchyBrowser
import io.github.dotnetsupport.lang.NativeCSharpTypeHierarchyProvider
import io.github.dotnetsupport.lang.NativeCSharpTypeHierarchyStructure
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Go to Super, Type Hierarchy, Call Hierarchy and the gutter of overrides without the language server (task C4b of CSHARP_PSI_MIGRATION.md),
 * and their switch: with NAVIGATION on the server they stand back.
 */
class CSharpNativeHierarchiesTest : BasePlatformTestCase() {
    private var round = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { null }
    }

    override fun tearDown() {
        try {
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            RoslynLanguageServerSettings.getInstance().state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Shapes in two files of their own folder; the editor on [open] at the first [at]. */
    private fun shapes(open: String, at: String) {
        val dir = "hierarchy${round++}"
        myFixture.addFileToProject("$dir/IShape.cs", """
            namespace Shapes;
            public interface IShape { double Area(); }
            public interface ISolid : IShape { double Volume(); }
        """.trimIndent())
        myFixture.addFileToProject("$dir/Circle.cs", """
            namespace Shapes;
            public class Circle : IShape
            {
                public virtual double Area() => 3.14 * Square(2);
                static double Square(double x) => x * x;
            }
            public class Ring : Circle
            {
                public override double Area() => base.Area() - Inner();
                double Inner() => new Circle().Area() / 2;
            }
        """.trimIndent())
        myFixture.configureFromTempProjectFile("$dir/$open")
        val offset = myFixture.editor.document.text.indexOf(at)
        assertTrue(at, offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset)
    }

    private fun names(elements: Collection<PsiElement>): List<String> = elements.map { e ->
        val owner = io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch.ownerType(e)?.let(CSharpDeclarationNames::name)
        listOfNotNull(owner, CSharpDeclarationNames.name(e)).joinToString(".")
    }

    private fun children(structure: NativeCSharpTypeHierarchyStructure, descriptor: HierarchyNodeDescriptor = structure.baseDescriptor): List<String> =
        names(structure.getChildElements(descriptor).map { (it as HierarchyNodeDescriptor).psiElement!! })

    private val dataContext get() = (myFixture.editor as EditorEx).dataContext

    fun testGotoSuperOfAnOverrideGoesToTheOverriddenMember() {
        shapes("Circle.cs", "Area() => base")
        NativeCSharpGotoSuperHandler().invoke(project, myFixture.editor, myFixture.file)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        val line = editor.document.getLineNumber(editor.caretModel.offset)
        assertTrue(editor.document.text.lines()[line], editor.document.text.lines()[line].contains("public virtual double Area()"))
    }

    fun testGotoSuperOfATypeGoesToItsBase() {
        shapes("Circle.cs", "Ring : Circle")
        NativeCSharpGotoSuperHandler().invoke(project, myFixture.editor, myFixture.file)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        val line = editor.document.getLineNumber(editor.caretModel.offset)
        assertTrue(editor.document.text.lines()[line], editor.document.text.lines()[line].contains("public class Circle : IShape"))
    }

    fun testTypeHierarchy() {
        shapes("Circle.cs", "Circle : IShape")
        val target = NativeCSharpTypeHierarchyProvider().getTarget(dataContext)!!
        assertEquals(listOf("Circle"), names(listOf(target)))
        val up = NativeCSharpTypeHierarchyStructure(project, target, NativeCSharpTypeHierarchyBrowser.Direction.SUPERTYPES)
        assertEquals(listOf("IShape"), children(up))
        val down = NativeCSharpTypeHierarchyStructure(project, target, NativeCSharpTypeHierarchyBrowser.Direction.SUBTYPES)
        assertEquals(listOf("Ring"), children(down))
        val shape = (up.getChildElements(up.baseDescriptor).single() as HierarchyNodeDescriptor).psiElement!!
        val fromInterface = NativeCSharpTypeHierarchyStructure(project, shape, NativeCSharpTypeHierarchyBrowser.Direction.SUBTYPES)
        assertEquals(listOf("Circle", "ISolid"), children(fromInterface).sorted())
        // the "type hierarchy" view of Ring: Circle on top, Ring (the base) under it
        val ring = (down.getChildElements(down.baseDescriptor).single() as HierarchyNodeDescriptor).psiElement!!
        val both = NativeCSharpTypeHierarchyStructure(project, ring, NativeCSharpTypeHierarchyBrowser.Direction.BOTH)
        val root = both.rootElement as HierarchyNodeDescriptor
        assertEquals(listOf("Circle"), names(listOf(root.psiElement!!)))
        assertEquals(listOf("Ring"), children(both, root))
    }

    fun testCallHierarchy() {
        shapes("Circle.cs", "Area() => 3.14")
        val area = NativeCSharpCallHierarchyProvider().getTarget(dataContext)!!
        assertEquals(listOf("Circle.Area"), names(listOf(area)))
        assertEquals(listOf("Ring.Area", "Ring.Inner"), names(NativeCSharpCallGraph.callers(project, area)).sorted())
        assertEquals(listOf("Circle.Square"), names(NativeCSharpCallGraph.callees(project, area)))
        val inner = io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch.declarationNamedBy(myFixture.file.findElementAt(myFixture.file.text.indexOf("Inner() =>"))!!)!!
        assertEquals("a creation is a call of the type; then the method", listOf("Circle", "Circle.Area"), names(NativeCSharpCallGraph.callees(project, inner)))
    }

    fun testTheGutterOfOverridesAndImplementations() {
        shapes("Circle.cs", "Circle : IShape")
        val tooltips = myFixture.findAllGutters().mapNotNull { it.tooltipText }.sorted()
        assertEquals(listOf("Has subclasses", "Implements member", "Is overridden", "Overrides member"), tooltips)
    }

    /**
     * The list of implementations (a click on "N implementations", on the gutter, Go to Super) is drawn on the EDT, which has no read access:
     * its rows carry what the renderer shows, read in a read action beforehand — rendering reads no PSI.
     */
    fun testTheRowsOfTheChooserAreRenderedWithoutReadAccess() {
        shapes("Circle.cs", "Circle : IShape")
        val circle = NativeCSharpTypeHierarchyProvider().getTarget(dataContext)!!
        val session = io.github.dotnetsupport.lang.semantic.CSharpSemanticSession(project)
        val found = io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch.targetOf(circle)!!.let { io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch.allSubtypes(project, it, session) }
        val rows = NativeCSharpHierarchies.rows(found + circle)
        assertEquals(listOf("Ring  (Shapes)", "Circle  (Shapes)"), rows.map { it.label })
        // a pooled thread holds no read lock, as the EDT of the IDE holds none while it paints
        val labels = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread<List<String>> {
            assertFalse(com.intellij.openapi.application.ApplicationManager.getApplication().isReadAccessAllowed)
            val renderer = NativeCSharpHierarchies.renderer()
            rows.map { row -> (renderer.getListCellRendererComponent(javax.swing.JList(), row, 0, false, false) as javax.swing.JLabel).text }
        }.get()
        assertEquals(rows.map { it.label }, labels)
        assertTrue(rows.all { it.icon != null })
    }

    fun testTheServerAnswersWithNavigationOnIt() {
        RoslynLanguageServerSettings.getInstance().state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.NAVIGATION, CSharpFeatureSource.ROSLYN)
        shapes("Circle.cs", "Circle : IShape")
        assertNull(NativeCSharpTypeHierarchyProvider().getTarget(dataContext))
        assertNull(NativeCSharpCallHierarchyProvider().getTarget(dataContext))
        assertEquals(emptyList<String>(), myFixture.findAllGutters().mapNotNull { it.tooltipText })
    }
}
