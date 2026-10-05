package io.github.dotnetsupport

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The usages Find Usages finds without the name written (0.1.82), as Roslyn's Find References answers them on the same code
 * (`debug-playground/Console/Editor/ImplicitUsages.cs`, checked with the server): `Deconstruct` of deconstructions (the left side) and of
 * `foreach (var (a, b) …)` (the variable), `Add` of collection initializers (each element), `GetEnumerator` of `foreach`, `Dispose` of
 * `using`, `GetAwaiter` of `await` (the keyword), and the constructor of every target-typed `new(…)` — dictionary and collection
 * initializers included. Positional patterns are no usage of `Deconstruct` for the server either.
 */
class CSharpImplicitUsagesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
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

    /** The declaration named by the [n]-th whole-word [name] of [file]. */
    private fun declaration(file: CSharpFile, name: String, n: Int = 0): PsiElement {
        val at = Regex("\\b$name\\b").findAll(file.text).elementAt(n).range.first
        return CSharpSolutionSearch.declarationNamedBy(file.findElementAt(at)!!) ?: error("no declaration named $name")
    }

    /** `line:text of the usage leaf` of each usage, sorted by line. */
    private fun usages(declaration: PsiElement): List<String> = ReferencesSearch.search(declaration, GlobalSearchScope.projectScope(project)).findAll()
        .map { it.element }.sortedBy { it.textRange.startOffset }.map { leaf ->
            val document = leaf.containingFile.viewProvider.document!!
            "${leaf.containingFile.name}:${document.getLineNumber(leaf.textRange.startOffset) + 1}:${leaf.text}"
        }

    private fun scenario(): CSharpFile = file("implicit/Implicit.cs", SCENARIO)

    fun testDeconstruct() {
        val f = scenario()
        // lines as in the scenario; the server: 3 places, the positional patterns are not among them
        assertEquals(listOf("Implicit.cs:44:var", "Implicit.cs:45:(", "Implicit.cs:47:var"), usages(declaration(f, "Deconstruct")))
    }

    fun testCollectionInitializerAdd() {
        val f = scenario()
        assertEquals(listOf("Implicit.cs:50:1", "Implicit.cs:50:2", "Implicit.cs:52:Add"), usages(declaration(f, "Add")))
    }

    fun testForEachUsingAndAwait() {
        val f = scenario()
        assertEquals(listOf("Implicit.cs:20:GetEnumerator", "Implicit.cs:53:foreach"), usages(declaration(f, "GetEnumerator")))
        assertEquals(listOf("Implicit.cs:50:using", "Implicit.cs:57:using"), usages(declaration(f, "Dispose")))
        assertEquals(listOf("Implicit.cs:61:await"), usages(declaration(f, "GetAwaiter")))
    }

    /** Every target-typed `new(…)` of the scenario is a usage of the type, each on `new`, as the server answers. */
    fun testTargetTypedNewEverywhere() {
        val f = scenario()
        val news = usages(declaration(f, "Point")).filter { it.endsWith(":new") }
        assertEquals(listOf(31, 33, 35, 39, 40, 41, 46).map { "Implicit.cs:$it:new" }, news)
    }

    fun testRenameLeavesImplicitUsagesAlone() {
        val f = scenario()
        val session = CSharpSemanticSession(project)
        val target = CSharpSolutionSearch.targetOf(declaration(f, "Add"))!!
        val renamed = CSharpSolutionSearch.usages(project, target, GlobalSearchScope.projectScope(project), session).filter { !it.implicit }.map { it.leaf.text }
        assertEquals(listOf("Add"), renamed)
    }

    private companion object {
        private fun bytes(name: String): ByteArray? = CSharpImplicitUsagesTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }

        val SCENARIO = """
            using System;
            using System.Collections;
            using System.Collections.Generic;
            using System.Runtime.CompilerServices;
            using System.Threading.Tasks;
            namespace Implicit;
            public sealed class Point
            {
                public Point(int x, int y) { X = x; Y = y; }
                public int X { get; }
                public int Y { get; }
                public void Deconstruct(out int x, out int y) { x = X; y = Y; }
            }
            public sealed class Bag : IEnumerable<int>, IDisposable
            {
                private readonly List<int> items = new List<int>();
                public void Add(int item) => items.Add(item);
                public IEnumerator<int> GetEnumerator() => items.GetEnumerator();
                // the explicit implementation calls the public one by its name
                IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
                public void Dispose() => items.Clear();
            }
            public readonly struct Delay
            {
                public TaskAwaiter GetAwaiter() => Task.CompletedTask.GetAwaiter();
            }
            public static class Scenarios
            {
                // line 29
                private static readonly Point Origin
                    = new(0, 0);
                public static Point Corner { get; }
                    = new(9, 9);
                public static Point Make()
                    => new(3, 4);
                private static int Sum(Point point) => point.X + point.Y;
                public static int Run()
                {
                    var byName = new Dictionary<string, Point> { ["a"] = new(1, 2) };
                    List<Point> listed = [new(5, 6)];
                    var initialized = new List<Point> { new(7, 8) };
                    // line 42
                    int total = 0;
                    var (a, b) = Make();
                    (var c, var d) = Origin;
                    total += a + b + c + d + Sum(new(1, 1));
                    foreach (var (px, py) in listed) total += px * py;
                    if (Corner is (0, _)) total++;
                    total += byName["a"] switch { (var sx, _) => sx };
                    using (var bag = new Bag { 1, 2 })
                    {
                        bag.Add(3);
                        foreach (var item in bag) total += item;
                    }
                    return total + initialized.Count;
                }
                public static void Dispose2() { using var other = new Bag(); }
                // line 58
                public static async Task RunAsync()
                {
                    await new Delay();
                }
            }
        """.trimIndent()
    }
}
