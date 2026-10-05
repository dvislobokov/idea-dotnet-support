package io.github.dotnetsupport

import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.CSharpFile
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpColors
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpNavigation
import io.github.dotnetsupport.lang.NativeCSharpSemanticColors
import io.github.dotnetsupport.lang.semantic.CSharpGlobalUsingIndex
import io.github.dotnetsupport.lang.semantic.CSharpResolution
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Name resolution of layer 11a (CSHARP_PSI_MIGRATION.md, task C1) over a fake reference set: the fixtures of src/test/resources/index
 * (IndexFixture of tools/index-fixture/Fixture.cs, System.Console, System.Linq, System.Collections). A name to resolve is marked by
 * `/*^*/` right before it; answers are documentation comment ids for symbols of the assemblies and declarations for the solution's.
 */
class CSharpNameResolutionTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
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

    private fun file(path: String, text: String): CSharpFile = myFixture.addFileToProject(path, text) as CSharpFile

    /** What each `/*^*/` of [file] resolves to, in the order of the text. */
    private fun resolveMarks(file: CSharpFile): List<CSharpResolution?> {
        val resolver = CSharpSemanticSession(project).resolver(file)
        val text = file.text
        val found = ArrayList<CSharpResolution?>()
        var at = text.indexOf(MARK)
        while (at >= 0) {
            found += resolver.resolve(file.findElementAt(at + MARK.length)!!)
            at = text.indexOf(MARK, at + 1)
        }
        assertFalse("no marks", found.isEmpty())
        return found
    }

    private fun ids(file: CSharpFile): List<String?> = resolveMarks(file).map { it?.single?.id }

    private fun delete(file: PsiFile) = WriteAction.runAndWait<Throwable> { file.virtualFile.delete(this) }

    fun testNamespacesOfUsingsAndDeclarations() {
        val f = file("resolve/Namespaces.cs", """
            using /*^*/System./*^*/Collections./*^*/Generic;
            using /*^*/Fixture;

            namespace /*^*/Fixture.Usage;

            class A
            {
                /*^*/global::/*^*/Fixture./*^*/Shape? s;
            }
        """.trimIndent())
        assertEquals(listOf("N:System", "N:System.Collections", "N:System.Collections.Generic", "N:Fixture", "N:Fixture", null, "N:Fixture", "T:Fixture.Shape"), ids(f))
    }

    fun testTypesOfAssembliesByImportArityAndNesting() {
        val f = file("resolve/Types.cs", """
            using Fixture;
            using System.Collections.Generic;

            class A
            {
                /*^*/Shape s;
                /*^*/Box</*^*/StringBuilderLike> box;
                /*^*/List<int> list;
                /*^*/Dictionary<string, int> map;
                /*^*/Box<StringBuilderLike>./*^*/Inner<int> inner;
                /*^*/Unknown u;
            }
        """.trimIndent())
        assertEquals(
            listOf("T:Fixture.Shape", "T:Fixture.Box`1", "T:Fixture.StringBuilderLike", "T:System.Collections.Generic.List`1",
                "T:System.Collections.Generic.Dictionary`2", "T:Fixture.Box`1", "T:Fixture.Box`1.Inner`1", null),
            ids(f),
        )
    }

    fun testSourceTypesWinOverAssemblyTypesAndTheAttributeSuffix() {
        val f = file("resolve/Mine.cs", """
            namespace Fixture
            {
                class Circle { }
                class MarkAttribute : System.Attribute { }

                [/*^*/Mark]
                class A
                {
                    /*^*/Circle c;
                    /*^*/Shape s;
                }
            }
        """.trimIndent())
        val found = resolveMarks(f)
        assertEquals("MarkAttribute", (found[0]?.single as CSharpSymbol.SourceType).info.qualifiedName.substringAfterLast("."))
        assertEquals("a type of the solution hides the type of the same name of a reference", f, found[1]?.single?.declarations?.single()?.containingFile)
        assertEquals("T:Fixture.Shape", found[2]?.single?.id)
    }

    fun testAliases() {
        val f = file("resolve/Aliases.cs", """
            using F = Fixture;
            using Strings = Fixture.Box<Fixture.StringBuilderLike>;

            class A
            {
                /*^*/F./*^*/Circle c;
                /*^*/Strings box;
                void M() { var v = box./*^*/Value; }
            }
        """.trimIndent())
        assertEquals(listOf("N:Fixture", "T:Fixture.Circle", "T:Fixture.Box`1", "P:Fixture.Box`1.Value"), ids(f))
    }

    fun testGlobalUsingsOfOtherFiles() {
        val usings = file("resolve/global/Usings.cs", "global using Fixture;\nglobal using static System.Console;\nglobal using Coll = System.Collections.Generic;\n")
        try {
            assertEquals(listOf("Fixture", "static System.Console", "Coll=System.Collections.Generic"), CSharpGlobalUsingIndex.directives(usings.text))
            val f = file("resolve/global/Use.cs", """
                class A
                {
                    /*^*/Circle c;
                    /*^*/Coll./*^*/List<int> list;
                    void M() { /*^*/Beep(); }
                }
            """.trimIndent())
            assertEquals(listOf("T:Fixture.Circle", "N:System.Collections.Generic", "T:System.Collections.Generic.List`1", "M:System.Console.Beep"), ids(f))
        } finally {
            delete(usings)
        }
    }

    fun testUsingStatic() {
        val f = file("resolve/Static.cs", """
            using static System.Console;
            using static Fixture.Color;

            class A
            {
                void M()
                {
                    /*^*/Beep();
                    var color = /*^*/Red;
                    /*^*/WriteLine();
                }
            }
        """.trimIndent())
        assertEquals(listOf("M:System.Console.Beep", "F:Fixture.Color.Red", "M:System.Console.WriteLine"), ids(f))
    }

    fun testMemberAccessOnTypesAndValuesOfKnownType() {
        val f = file("resolve/Members.cs", """
            using Fixture;

            class A
            {
                private Circle field;
                private Box<StringBuilderLike> Box { get; set; }

                void M(Circle parameter)
                {
                    Circle local = parameter;
                    var a = local./*^*/Radius;
                    var b = parameter./*^*/Label;
                    var c = field./*^*/Area;
                    var d = this./*^*/Box./*^*/Items;
                    var e = new Circle(1)./*^*/Name;
                    var f = /*^*/Shape./*^*/Count;
                    var g = /*^*/Color./*^*/Green;
                    var h = local./*^*/Describe();
                    var i = d./*^*/Count;
                }
            }
        """.trimIndent())
        assertEquals(
            listOf(
                "P:Fixture.Circle.Radius", "P:Fixture.Shape.Label", "P:Fixture.Circle.Area", null, "F:Fixture.Box`1.Items", "P:Fixture.Circle.Name",
                "T:Fixture.Shape", "F:Fixture.Shape.Count", "T:Fixture.Color", "F:Fixture.Color.Green",
                "M:Fixture.Circle.Describe(System.String,System.Int32)~System.String", "P:System.Collections.Generic.List`1.Count",
            ),
            ids(f),
        )
        assertTrue("the property of the own type is declared in the file", resolveMarks(f)[3]?.single is CSharpSymbol.SourceMember)
    }

    fun testExtensionMethodsWhenTheReceiverIsKnown() {
        val f = file("resolve/Extensions.cs", """
            using Fixture;
            using System.Linq;
            using System.Collections.Generic;

            class A
            {
                void M(Circle circle, Shape shape)
                {
                    circle./*^*/Twice();
                    shape./*^*/Name();
                    /*^*/Enumerable./*^*/Empty<int>();
                    circle./*^*/Nothing();
                }
            }
        """.trimIndent())
        assertEquals(
            listOf(
                "M:Fixture.ShapeExtensions.Twice``1(``0)~``0",
                "M:Fixture.ShapeExtensions.Name(Fixture.Shape,System.String,System.Char,Fixture.Color,System.Nullable{System.Double})~System.String",
                "T:System.Linq.Enumerable", "M:System.Linq.Enumerable.Empty``1~System.Collections.Generic.IEnumerable{``0}",
                null,
            ),
            ids(f),
        )
    }

    fun testExtensionMethodsNeedTheirNamespace() {
        val f = file("resolve/NoImport.cs", """
            class A
            {
                void M(Fixture.Circle circle) => circle./*^*/Twice();
            }
        """.trimIndent())
        assertEquals(listOf<String?>(null), ids(f))
    }

    fun testOverloadsByArgumentsAndAmbiguity() {
        val f = file("resolve/Overloads.cs", """
            class A
            {
                void M(int a) { }
                void M(int a, int b) { }
                void N(string s) { }
                void N(object o) { }

                void Use(Unknown x)
                {
                    /*^*/M(1, 2);
                    /*^*/M(1);
                    /*^*/N(x);
                    /*^*/N("text");
                }
            }
        """.trimIndent())
        val found = resolveMarks(f)
        val text = f.text
        fun declared(resolution: CSharpResolution?): String = resolution?.single?.declarations?.single()?.text.orEmpty()
        assertTrue(declared(found[0]), declared(found[0]).startsWith("void M(int a, int b)"))
        assertTrue(declared(found[1]), declared(found[1]).startsWith("void M(int a)"))
        assertEquals("an argument of no known type: both are candidates", 2, found[2]?.symbols?.size)
        assertTrue(text, found[3] != null)
    }

    fun testBrokenCodeDoesNotThrow() {
        val f = file("resolve/Broken.cs", """
            using Fixture
            class A
            {
                void M(Circle c)
                {
                    c./*^*/
                    /*^*/Undefined./*^*/Foo(;
                    var x = c./*^*/Radius.;
                    new /*^*/Shape(
                }
        """.trimIndent())
        val found = resolveMarks(f)
        assertNull(found[1])
        assertNull(found[2])
        assertEquals("P:Fixture.Circle.Radius", found[3]?.single?.id)
    }

    fun testColorsOfAssemblyTypesAndMembers() {
        settings.setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.NATIVE)
        val f = file("resolve/Colors.cs", """
            using Fixture;
            using System.Linq;

            class A
            {
                void M(Circle circle)
                {
                    var r = circle.Radius;
                    var n = Shape.Count;
                    var t = circle.Twice();
                    System.Console.WriteLine(Missing());
                    var query = from c in new[] { circle } group c by c.Radius into g select g.Key;
                }
            }
        """.trimIndent())
        val colors = NativeCSharpSemanticColors.colors(f).associate { f.text.substring(it.first.startOffset, it.first.endOffset) + "@" + it.first.startOffset to it.second }
        fun keyOf(name: String, occurrence: Int = 0): Any? {
            var at = -1
            repeat(occurrence + 1) { at = f.text.indexOf(name, at + 1) }
            return colors["$name@$at"]
        }
        assertEquals(CSharpColors.CLASS, keyOf("Circle"))
        assertEquals(CSharpColors.PROPERTY, keyOf("Radius"))
        assertEquals(CSharpColors.CLASS, keyOf("Shape"))
        assertEquals(CSharpColors.STATIC_FIELD, keyOf("Count"))
        assertEquals(CSharpColors.EXTENSION_METHOD_CALL, keyOf("Twice"))
        // robot 0.1.60: the overload is not picked (an argument of no known type), but every candidate is a static method, as the server colors it
        assertEquals(CSharpColors.STATIC_METHOD_CALL, keyOf("WriteLine"))
        // a range variable of `into`: the `IGrouping<K, T>` of LINQ (robot 0.1.60; `query.Any()` needs `IEnumerable<T>` of System.Runtime,
        // which the fixtures lack: checked by the robot)
        assertEquals(CSharpColors.PROPERTY, keyOf("Key"))
    }

    fun testNavigationGoesToSourceMembersOfValuesOfKnownType() {
        val model = file("resolve/nav/Model.cs", "namespace Nav;\npublic class Order\n{\n    public int Total { get; set; }\n}\n")
        val f = file("resolve/nav/Use.cs", """
            using Nav;

            class A
            {
                Order Make() => new Order();
                void M() => System.Console.WriteLine(Make()./*^*/Total);
            }
        """.trimIndent())
        val at = f.text.indexOf(MARK) + MARK.length
        val targets = NativeCSharpNavigation.targets(f.findElementAt(at)!!)
        assertEquals(model, targets?.single()?.containingFile)
        assertNull("an assembly member has no place to go to", NativeCSharpNavigation.targets(f.findElementAt(f.text.indexOf("WriteLine"))!!))
    }

    fun testSpeedOfColorsOnALargeFile() {
        settings.setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.NATIVE)
        val body = (0 until 150).joinToString("\n") { i ->
            """
                public double M$i(Circle circle, List<int> numbers)
                {
                    var area = circle.Area + circle.Radius;
                    var list = numbers.ToList();
                    Console.WriteLine(circle.Describe());
                    return area + Shape.Count + list.Count;
                }
            """.trimIndent().prependIndent("    ")
        }
        val f = file("resolve/Large.cs", "using System;\nusing System.Linq;\nusing System.Collections.Generic;\nusing Fixture;\n\nclass Large\n{\n$body\n}\n")
        assertTrue(f.text.lines().size > 1000)
        NativeCSharpSemanticColors.colors(f)
        val runs = (0 until 5).map {
            val start = System.nanoTime()
            NativeCSharpSemanticColors.colors(f)
            (System.nanoTime() - start) / 1_000_000
        }
        println("colors of ${f.text.lines().size} lines: $runs ms")
        // the budget is 50 ms on a developer machine; the assertion leaves room for a loaded build agent
        assertTrue("colors took $runs ms", runs.min() < 500)
    }

    private companion object {
        const val MARK = "/*^*/"

        private fun bytes(name: String): ByteArray = CSharpNameResolutionTest::class.java.getResourceAsStream("/index/$name")!!.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix"), AssemblyDocs.read(bytes("$name.dnxd")))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
