package io.github.dotnetsupport

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.CSharpUsageTypeProvider
import io.github.dotnetsupport.lang.NativeCSharpFindUsages
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Find Usages, Go to Implementation and the hierarchy of types and members across the solution without the language server (task C4b of
 * CSHARP_PSI_MIGRATION.md): candidates by word, each resolved by the native resolver. A place is marked by `/*^*/` right before a name.
 */
class CSharpSolutionUsagesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { null }
    }

    override fun tearDown() {
        try {
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun file(path: String, text: String): CSharpFile = myFixture.addFileToProject(path, text) as CSharpFile

    /** The leaf right after the [n]-th `/*^*/` of [file]. */
    private fun mark(file: CSharpFile, n: Int = 0): PsiElement {
        var at = -1
        repeat(n + 1) { at = file.text.indexOf(MARK, at + 1) }
        assertTrue("no mark $n", at >= 0)
        return file.findElementAt(at + MARK.length)!!
    }

    private fun declarationAt(file: CSharpFile, n: Int = 0): PsiElement = CSharpSolutionSearch.declarationNamedBy(mark(file, n)) ?: error("no declaration at mark $n")

    /** `file:line:text` of each usage, sorted. */
    private fun usages(declaration: PsiElement): List<String> = ReferencesSearch.search(declaration, GlobalSearchScope.projectScope(project)).findAll().map { describe(it.element) }.sortedWith(PLACES)

    private fun describe(leaf: PsiElement): String {
        val file = leaf.containingFile
        val line = file.viewProvider.document!!.getLineNumber(leaf.textRange.startOffset) + 1
        return "${file.name}:$line:${leaf.text}"
    }

    private fun shapes() {
        file("usages/IShape.cs", """
            namespace Shapes;
            /// <summary>A shape; see <see cref="Circle.Radius"/>.</summary>
            public interface /*^*/IShape
            {
                double /*^*/Area();
            }
        """.trimIndent())
        file("usages/Circle.cs", """
            namespace Shapes;
            public class /*^*/Circle : IShape
            {
                public /*^*/Circle(double radius) { Radius = radius; }
                public double /*^*/Radius { get; set; }
                public double /*^*/Area() => 3.14 * Radius * Radius;
                public virtual string /*^*/Describe() => nameof(Radius);
            }
            public class Ring : Circle
            {
                public Ring() : base(1) { }
                public override string Describe() => "ring";
            }
        """.trimIndent())
        file("usages/Use.cs", """
            using Shapes;
            namespace App;
            public static class Use
            {
                public static double Total(IShape[] shapes)
                {
                    var c = new Circle(2);
                    c.Radius = 3;
                    c.Radius++;
                    double sum = c.Area();
                    foreach (var s in shapes) sum += s.Area();
                    Circle other = c;
                    return sum + other.Radius;
                }
                // Radius in a comment and "Radius" in a string are no usages
                static string Text => "Radius";
            }
        """.trimIndent())
    }

    fun testUsagesOfAPropertyAcrossFiles() {
        shapes()
        val circle = myFixture.findFileInTempDir("usages/Circle.cs").let { psiManager.findFile(it) as CSharpFile }
        assertEquals(
            listOf("Circle.cs:4:Radius", "Circle.cs:6:Radius", "Circle.cs:6:Radius", "Circle.cs:7:Radius", "IShape.cs:2:Radius", "Use.cs:8:Radius", "Use.cs:9:Radius", "Use.cs:13:Radius"),
            usages(declarationAt(circle, 2)),
        )
    }

    fun testUsagesOfATypeAndOfAMethod() {
        shapes()
        val circle = psiManager.findFile(myFixture.findFileInTempDir("usages/Circle.cs")) as CSharpFile
        val shape = psiManager.findFile(myFixture.findFileInTempDir("usages/IShape.cs")) as CSharpFile
        // `base(1)` of Ring is a usage of the constructor, not of the type, as Roslyn and Rider count it
        assertEquals(listOf("Circle.cs:9:Circle", "IShape.cs:2:Circle", "Use.cs:7:Circle", "Use.cs:12:Circle"), usages(declarationAt(circle, 0)))
        assertEquals("the hierarchy, as the server: the call on Circle and the one on IShape", listOf("Use.cs:10:Area", "Use.cs:11:Area"), usages(declarationAt(circle, 3)))
        assertEquals(listOf("Use.cs:10:Area", "Use.cs:11:Area"), usages(declarationAt(shape, 1)))
        assertEquals("new Circle(2) and base(1) are the constructor's", listOf("Circle.cs:11:base", "Use.cs:7:Circle"), usages(declarationAt(circle, 1)))
    }

    fun testImplementationsAndOverrides() {
        shapes()
        val circle = psiManager.findFile(myFixture.findFileInTempDir("usages/Circle.cs")) as CSharpFile
        val shape = psiManager.findFile(myFixture.findFileInTempDir("usages/IShape.cs")) as CSharpFile
        fun names(element: PsiElement) = DefinitionsScopedSearch.search(element).findAll().map { describe(CSharpSolutionSearchTestNames.nameOf(it)) }.sortedWith(PLACES)
        assertEquals(listOf("Circle.cs:2:Circle", "Circle.cs:9:Ring"), names(declarationAt(shape, 0)))
        assertEquals(listOf("Circle.cs:6:Area"), names(declarationAt(shape, 1)))
        assertEquals(listOf("Circle.cs:12:Describe"), names(declarationAt(circle, 4)))
        val session = CSharpSemanticSession(project)
        val ringDescribe = CSharpSolutionSearch.declarationNamedBy(circle.findElementAt(circle.text.indexOf("Describe() => \"ring\""))!!)!!
        assertEquals(listOf("Circle.cs:7:Describe"), CSharpSolutionSearch.baseMembers(ringDescribe, session).map { describe(CSharpSolutionSearchTestNames.nameOf(it as PsiElement)) })
        assertEquals(listOf("IShape.cs:5:Area"), CSharpSolutionSearch.baseMembers(declarationAt(circle, 3), session).map { describe(CSharpSolutionSearchTestNames.nameOf(it as PsiElement)) })
    }

    fun testTheTargetAtAUsageAndTheKindsOfUsages() {
        shapes()
        val use = psiManager.findFile(myFixture.findFileInTempDir("usages/Use.cs")) as CSharpFile
        val leaf = use.findElementAt(use.text.indexOf("Radius = 3"))!!
        val target = NativeCSharpFindUsages.targetElement(leaf)!!
        assertEquals("Radius", io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames.name(target))
        val kinds = ReferencesSearch.search(target).findAll().associate { describe(it.element) to CSharpUsageTypeProvider().getUsageType(it.element).toString() }
        assertEquals("Write access", kinds["Use.cs:8:Radius"])
        assertEquals("Usage in nameof", kinds["Circle.cs:7:Radius"])
        assertEquals("Read access", kinds["Use.cs:13:Radius"])
    }

    /**
     * What the server's Find References counts beyond the names: target-typed `new()`, property patterns, the member's hierarchy; `base(…)`
     * of a subtype's constructor is a usage of the constructor alone.
     */
    fun testUsagesTheServerCountsToo() {
        val file = file("usages/More.cs", """
            public class /*^*/Tally
            {
                public Tally() { }
                public int /*^*/Total;
                public virtual int /*^*/Size() => 0;
                public static Tally Make() => new();
                public static int Kind(object item) => item switch { Tally { Total: > 0 } => 1, _ => 0 };
            }
            public class Big : Tally
            {
                public Big() : base() { }
                public override int Size() => 1;
                public int Both(Tally t) => t.Size() + base.Size();
            }
            public static class Lists { public static Tally[] All = [new()]; }
        """.trimIndent())
        assertEquals(
            listOf("More.cs:6:new", "More.cs:6:Tally", "More.cs:7:Tally", "More.cs:9:Tally", "More.cs:13:Tally", "More.cs:15:new", "More.cs:15:Tally").sorted(),
            usages(declarationAt(file, 0)).sorted(),
        )
        val constructor = CSharpSolutionSearch.declarationNamedBy(file.findElementAt(file.text.indexOf("Tally() { }"))!!)!!
        assertEquals("the constructor: the two `new()` and `base()`", listOf("More.cs:6:new", "More.cs:11:base", "More.cs:15:new"), usages(constructor))
        assertEquals(listOf("More.cs:7:Total"), usages(declarationAt(file, 1)))
        val bigSize = CSharpSolutionSearch.declarationNamedBy(file.findElementAt(file.text.indexOf("Size() => 1"))!!)!!
        assertEquals("the override: the calls of the virtual member too", listOf("More.cs:13:Size", "More.cs:13:Size"), usages(bigSize))
    }

    /** The EXPECT of `debug-playground/Console/Editor/SolutionUsages.cs` (`TYPE:solution-*`) on its two files. */
    fun testThePlaygroundScenario() {
        fun load(path: String, name: String) = file("playground/$name", java.io.File(path).readText().replace("\r\n", "\n"))
        val lib = load("debug-playground/Lib/SolutionShapes.cs", "SolutionShapes.cs")
        val console = load("debug-playground/Console/Editor/SolutionUsages.cs", "SolutionUsages.cs")
        val side = NativeCSharpFindUsages.targetElement(console.findElementAt(console.text.indexOf("Side = 3;"))!!)!!
        val found = usages(side)
        assertEquals(found.toString(), 7, found.size)
        assertEquals(found.toString(), 2, found.count { it.startsWith("SolutionUsages.cs") })
        val area = NativeCSharpFindUsages.targetElement(console.findElementAt(console.text.indexOf("Area() + square"))!!)!!
        fun names(element: PsiElement) = DefinitionsScopedSearch.search(element).findAll().map { describe(CSharpSolutionSearchTestNames.nameOf(it)) }.sortedWith(PLACES)
        assertEquals(listOf("SolutionShapes.cs:20:Area", "SolutionUsages.cs:20:Area"), names(area))
        assertNotNull(lib)
    }

    private companion object {
        const val MARK = "/*^*/"
        val PLACES: Comparator<String> = compareBy({ it.substringBefore(":") }, { it.split(":")[1].toInt() })
    }
}

/** The name leaf of a declaration, for the messages of the tests. */
internal object CSharpSolutionSearchTestNames {
    fun nameOf(element: PsiElement): PsiElement = io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames.nameElement(element)
        ?: (element as? io.github.dotnetsupport.csharp.lang.psi.CSharpVariableDeclarator)?.identifier ?: element
}
