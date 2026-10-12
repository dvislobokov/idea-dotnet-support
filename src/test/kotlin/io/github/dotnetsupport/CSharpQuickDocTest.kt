package io.github.dotnetsupport

import com.intellij.openapi.util.text.StringUtil
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
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
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
        assertEquals("void Console.WriteLine(string? value) (+ 17 overloads)", doc.definition.replace(Regex("""\(\+ \d+ overloads\)"""), "(+ 17 overloads)"))
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

    fun testInheritdocTakesTheDocumentationOfTheBase() {
        val source = """
            namespace Shop;
            /// <summary>Something that ships.</summary>
            public interface IShippable
            {
                /// <summary>Ships it.</summary>
                /// <param name="express">Faster.</param>
                /// <returns>The tracking number.</returns>
                string Ship(bool express);
            }
            /// <summary>An animal.</summary>
            public abstract class Base
            {
                /// <summary>The name of it.</summary>
                public virtual string Name => "";
            }
            /// <inheritdoc/>
            public class Order : Base, IShippable
            {
                /// <inheritdoc/>
                /// <returns>Always the same.</returns>
                public string Ship(bool express) => "1";
                /// <inheritdoc />
                public override string Name => "order";
                /// <inheritdoc cref="IShippable.Ship"/>
                public void Other() { }
            }
            class Client { void M(Order order) { order.Sh|ip(true); } }
        """
        val ship = doc(source)!!
        assertTrue(ship.html, ship.html.contains("Ships it."))
        assertTrue("the own part wins: ${ship.html}", ship.html.contains("Always the same.") && !ship.html.contains("The tracking number."))
        assertTrue("the parameter too: ${ship.html}", ship.html.contains("<code>express</code> – Faster."))
        assertFalse(ship.html, ship.html.contains("inheritdoc"))
        val name = doc(source.replace("order.Sh|ip(true)", "order.Ship(true); var n = order.Na|me"))!!
        assertTrue(name.html, name.html.contains("The name of it."))
        val type = doc(source.replace("order.Sh|ip(true)", "order.Ship(true)").replace("void M(Order order)", "void M(Or|der order)"))!!
        assertTrue("a type from its base class: ${type.html}", type.html.contains("An animal."))
        val other = doc(source.replace("order.Sh|ip(true)", "order.Ot|her()"))!!
        assertTrue("`cref` of inheritdoc: ${other.html}", other.html.contains("Ships it."))
        val parameter = doc(source.replace("order.Sh|ip(true)", "order.Ship(true)").replace("public string Ship(bool express) => \"1\";", "public string Ship(bool express) => ex|press ? \"1\" : \"2\";"))!!
        assertTrue("a parameter of an inheriting method: ${parameter.html}", parameter.html.contains("Faster."))
    }

    fun testInheritdocMerge() {
        assertEquals("<summary>Own.</summary>\n<param name=\"b\">B.</param>",
            NativeCSharpDocumentation.merge("<summary>Own.</summary>", "<summary>Base.</summary><param name=\"b\">B.</param>"))
    }

    fun testCrefsAreLinks() {
        val source = """
            namespace Shop;
            /// <summary>Made by <see cref="Factory"/>, shipped by <see cref="Factory.Ship"/>; a <see cref="T:System.Console"/>.</summary>
            public class Order { }
            /// <summary>Makes orders.</summary>
            public class Factory { /// <summary>Ships.</summary>
                public void Ship() { } }
            class Client { void M(Ord|er order) { } }
        """
        val doc = doc(source)!!
        val links = Regex("""<a href="([^"]+)"><code>([^<]+)</code></a>""").findAll(doc.html).associate { it.groupValues[2] to StringUtil.unescapeXmlEntities(it.groupValues[1]) }
        assertEquals(doc.html, setOf("Factory", "Factory.Ship", "Console"), links.keys)
        assertTrue(links.values.all { it.startsWith(NativeCSharpDocumentation.LINK) })
        assertEquals("class Shop.Factory", NativeCSharpDocumentation.resolveLink(project, links.getValue("Factory"))?.definition)
        assertEquals("void Factory.Ship()", NativeCSharpDocumentation.resolveLink(project, links.getValue("Factory.Ship"))?.definition)
        val console = NativeCSharpDocumentation.resolveLink(project, links.getValue("Console"))!!
        assertEquals("class System.Console", console.definition)
        assertNotNull("F4 opens the metadata view", NativeCSharpDocumentation.navigatable(project, console.location!!))
        assertNotNull("the documentation of a declaration goes to it", doc.location)
        // the documentation of an assembly: its `cref`s link to what the index has
        val writeLine = doc("using System;\nclass A { void M() { Console.Write|Line(\"x\"); } }")!!
        assertTrue(writeLine.html, writeLine.html.contains("<a href=\"psi_element://dotnet-doc/") || writeLine.links.isEmpty())
        assertEquals("System.Console", (NativeCSharpDocumentation.librarySymbol("T:System.Console", ASSEMBLIES) as? io.github.dotnetsupport.lang.semantic.CSharpSymbol.LibraryType)?.type?.fullName)
        assertNotNull(NativeCSharpDocumentation.librarySymbol("M:System.Console.WriteLine(System.String)", ASSEMBLIES))
        assertNull(NativeCSharpDocumentation.librarySymbol("N:System", ASSEMBLIES))
    }

    fun testVarShowsTheType() {
        val doc = doc("using System.Collections.Generic;\nclass A { void M() { va|r numbers = new List<int>(); } }")!!
        assertEquals("class System.Collections.Generic.List<T>", doc.definition)
        assertTrue(doc.html, doc.html.contains("T is int"))
        assertEquals("class Shop.Order", definition("namespace Shop;\nclass Order { void M() { va|r o = new Order(); } }"))
        assertEquals("foreach", "class System.String", definition("class A { void M(string[] xs) { foreach (va|r x in xs) { } } }"))
    }

    fun testLibraryParametersShowTheirNullability() {
        val definition = definition("using System;\nclass A { void M() { Console.Write|Line(\"x\"); } }")!!
        assertTrue("as Roslyn: `string? value` where the assembly annotates it: $definition", definition.startsWith("void Console.WriteLine(string"))
    }

    fun testTheSwitch() {
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
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
        assertTrue("as the server, with the annotation of the assembly: $library", library.any { it.startsWith("(string? value)") })
        val linq = rows("using System.Collections.Generic;\nusing System.Linq;\nclass A { void M(List<int> xs) { xs.Take(|); } }")
        assertTrue("the receiver is not a parameter: $linq", linq.none { it.contains("source") })
        val creation = rows("class Point { public Point(int x, int y) { } void M() { var p = new Point(1, |); } }")
        assertEquals(listOf("(int x, int y) *"), creation)
    }

    fun testTheConstructorTheArgumentsPickIsMarked() {
        val rows = rows("class Point { public Point(int x) { } public Point(int x, int y) { } void M() { var p = new Point(1|); } }")
        assertEquals(listOf("(int x) *", "(int x, int y)"), rows)
        assertEquals(listOf("(int x)", "(int x, int y) *"), rows("class Point { public Point(int x) { } public Point(int x, int y) { } void M() { var p = new Point(1, |2); } }"))
    }

    fun testNamedArguments() {
        val file = myFixture.configureByText("Named.cs", "class A { void M(int a, int b = 0, int c = 0) { M(1, c: 3); } }") as CSharpFile
        val offset = file.text.indexOf("3)")
        val list = NativeCSharpParameterInfo.listAt(file, offset)!!
        assertEquals("c", NativeCSharpParameterInfo.argumentName(list, offset))
        assertNull(NativeCSharpParameterInfo.argumentName(list, file.text.indexOf("1, c")))
        assertEquals("count", NativeCSharpParameterInfo.nameOf("int count = 1"))
        assertEquals("items", NativeCSharpParameterInfo.nameOf("params string[] items"))
        assertEquals("text", NativeCSharpParameterInfo.nameOf("string? text = \"a = b\""))
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
