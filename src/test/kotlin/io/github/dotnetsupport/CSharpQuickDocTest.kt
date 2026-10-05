package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpDocumentation
import io.github.dotnetsupport.lang.NativeCSharpDocumentationTargetProvider
import io.github.dotnetsupport.lang.NativeCSharpParameterInfo
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Quick documentation and parameter info on the plugin's semantics (CSHARP_PSI_MIGRATION.md, task C3, [CSharpFeature.DOCUMENTATION]): the
 * Quick Info line as Roslyn writes it, the `///` documentation of the solution, the documentation files of the assemblies of
 * src/test/resources/index (System.Console has one, System.Runtime has none), the overloads of a call, the switch.
 */
class CSharpQuickDocTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.DOCUMENTATION, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The documentation at the first `|` of [text] (removed). */
    private fun doc(text: String): NativeCSharpDocumentation.Doc? {
        val source = text.trimIndent()
        val offset = source.indexOf('|')
        val file = myFixture.configureByText("Doc${counter++}.cs", source.removeRange(offset, offset + 1)) as CSharpFile
        return NativeCSharpDocumentation.at(file, offset)
    }

    private fun definition(text: String): String? = doc(text)?.definition

    fun testLibraryMethodWithItsDocumentation() {
        val doc = doc("using System;\nclass A { void M() { Console.Write|Line(\"x\"); } }")!!
        assertEquals("void Console.WriteLine(string value) (+ 17 overloads)", doc.definition.replace(Regex("""\(\+ \d+ overloads\)"""), "(+ 17 overloads)"))
        assertTrue(doc.definition, doc.definition.startsWith("void Console.WriteLine(string"))
        assertNotNull("System.Console has its XML documentation", doc.xml)
        assertTrue(doc.html, doc.html.contains("Writes the specified string value"))
        assertTrue("the parameters as a section: ${doc.html}", doc.html.contains("Params:"))
    }

    fun testLibraryTypeAndProperty() {
        assertEquals("class System.Console", definition("using System;\nclass A { void M() { Con|sole.ReadLine(); } }"))
        assertEquals("int List<int>.Count { get; }", definition("using System.Collections.Generic;\nclass A { void M(List<int> xs) { var n = xs.Cou|nt; } }"))
    }

    fun testSourceMembersWithTheirComments() {
        val source = """
            namespace Shop;
            /// <summary>An order of the <see cref="T:Shop.Shop"/>.</summary>
            public class Order
            {
                /// <summary>Adds <paramref name="count"/> items.</summary>
                /// <param name="item">What is added.</param>
                /// <param name="count">How many.</param>
                /// <returns><see langword="true"/> when added.</returns>
                public bool Add(string item, int count = 1) => true;
                public bool Add(string item) => true;
                private const int Max = 3;
                public decimal Total { get; set; }
            }
            class Client { void M(Order order) { order.A|dd("x", 2); } }
        """
        val doc = doc(source)!!
        assertEquals("bool Order.Add(string item, int count = 1) (+ 1 overload)", doc.definition)
        assertTrue(doc.html, doc.html.contains("Adds <code>count</code> items."))
        assertTrue(doc.html, doc.html.contains("<code>item</code> – What is added."))
        assertTrue(doc.html, doc.html.contains("<code>true</code> when added."))
        assertEquals("class Shop.Order", definition(source.replace("order.A|dd", "order.Add").replace("void M(Order order)", "void M(Ord|er order)")))
        assertEquals("(constant) int Order.Max = 3", definition(source.replace("order.A|dd", "order.Add").replace("const int Max", "const int Ma|x")))
        assertEquals("decimal Order.Total { get; set; }", definition(source.replace("order.A|dd", "order.Add").replace("decimal Total", "decimal To|tal")))
        val type = doc(source.replace("order.A|dd", "order.Add").replace("public class Order", "public class Or|der"))!!
        assertTrue(type.html, type.html.contains("An order of the <code>Shop</code>."))
    }

    fun testLocalsAndParameters() {
        val source = """
            using System.Collections.Generic;
            class A
            {
                /// <param name="limit">The most to take.</param>
                void M(int limit)
                {
                    var numbers = new List<int>();
                    var n = numbers.Count + lim|it;
                }
            }
        """
        val parameter = doc(source)!!
        assertEquals("(parameter) int limit", parameter.definition)
        assertTrue(parameter.html, parameter.html.contains("The most to take."))
        assertEquals("(local variable) List<int> numbers", definition(source.replace("lim|it", "limit").replace("numbers.Count", "num|bers.Count")))
    }

    fun testTheSwitch() {
        val file = myFixture.configureByText("Switch.cs", "using System;\nclass A { void M() { Console.ReadLine(); } }") as CSharpFile
        val offset = file.text.indexOf("ReadLine")
        assertEquals(1, NativeCSharpDocumentationTargetProvider().documentationTargets(file, offset).size)
        settings.setSource(CSharpFeature.DOCUMENTATION, CSharpFeatureSource.ROSLYN)
        assertTrue("the server's hover answers", NativeCSharpDocumentationTargetProvider().documentationTargets(file, offset).isEmpty())
    }

    fun testRenderingOfTheXml() {
        assertEquals("<code>String</code> or <code>null</code>; see <code>Console.WriteLine</code>",
            NativeCSharpDocumentation.inline("""<see cref="T:System.String" /> or <see langword="null" />; see <see cref="M:System.Console.WriteLine(System.String)"/>"""))
        assertEquals("a<p>b", NativeCSharpDocumentation.inline("a<para>b</para>"))
        assertEquals("List", NativeCSharpDocumentation.crefName("T:System.Collections.Generic.List`1"))
        assertEquals("<ul><li>a – b</li><li>c</li></ul>",
            NativeCSharpDocumentation.inline("<list type=\"bullet\"><item><term>a</term><description>b</description></item><item><description>c</description></item></list>"))
        assertEquals("<a href=\"https://example.org/?a=1&amp;b=2\">docs</a> and <code>Dictionary&lt;K, V&gt;</code>",
            NativeCSharpDocumentation.inline("""<see href="https://example.org/?a=1&amp;b=2">docs</see> and <see cref="T:System.Collections.Generic.Dictionary{K, V}"/>"""))
    }

    fun testTheXmlOfAPackageCannotInjectHtml() {
        // documentation comes from any `///` and from the .xml files of any package: only a fixed set of tags is produced, links are http(s)
        val html = NativeCSharpDocumentation.inline(
            """<a href="file:///etc/passwd">x</a> <see href="javascript:alert(1)">y</see> <code onclick="z">c</code> <img src="q" onerror="z"/> """ +
                """<see langword="&lt;b&gt;bold&lt;/b&gt;"/> <paramref name="&quot;&gt;&lt;a href=&quot;x"/> &lt;script&gt;"""
        )
        assertEquals("x y <code>c</code> <code>&lt;b&gt;bold&lt;/b&gt;</code> <code>&quot;&gt;&lt;a href=&quot;x</code> &lt;script&gt;", html)
    }

    // ---- parameter info

    private fun rows(text: String): List<String> {
        val source = text.trimIndent()
        val offset = source.indexOf('|')
        val file = myFixture.configureByText("Info${counter++}.cs", source.removeRange(offset, offset + 1)) as CSharpFile
        val list = NativeCSharpParameterInfo.listAt(file, offset) ?: return emptyList()
        return NativeCSharpParameterInfo.rows(file, list).map { it.toString() }
    }

    fun testParameterInfoOfOverloads() {
        val rows = rows("""
            class Order
            {
                public void Add(string item) { }
                public void Add(string item, int count) { }
                void M() { Add("x", |); }
            }
        """)
        assertEquals(listOf("(string item)", "(string item, int count) *"), rows)
        val library = rows("using System;\nclass A { void M() { Console.WriteLine(|); } }")
        assertTrue(library.toString(), library.size > 5)
        assertTrue(library.toString(), library.any { it.startsWith("(string value)") })
        val linq = rows("using System.Collections.Generic;\nusing System.Linq;\nclass A { void M(List<int> xs) { xs.Take(|); } }")
        assertTrue("the receiver is not a parameter: $linq", linq.none { it.contains("source") })
        val creation = rows("class Point { public Point(int x, int y) { } void M() { var p = new Point(1, |); } }")
        assertEquals(listOf("(int x, int y) *"), creation)
    }

    fun testArgumentIndex() {
        val file = myFixture.configureByText("Index.cs", "class A { void M(int a, int b, int c) { M(1, 2, 3); } }") as CSharpFile
        val offset = file.text.indexOf("3)")
        val list = NativeCSharpParameterInfo.listAt(file, offset)!!
        assertEquals(2, NativeCSharpParameterInfo.argumentIndex(list, offset))
        assertEquals(14 until 19, NativeCSharpParameterInfo.rangeOf(listOf("int a", "int b", "int c"), 2))
    }

    private companion object {
        var counter = 0

        private fun bytes(name: String): ByteArray? = CSharpQuickDocTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
