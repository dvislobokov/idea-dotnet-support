package io.github.dotnetsupport

import com.intellij.openapi.util.TextRange
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
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.NativeCSharpSemanticModel
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Types of expressions, layer 11b (CSHARP_PSI_MIGRATION.md, task C2), over the fixtures of src/test/resources/index with System.Runtime
 * (the special types, `Task`, `Nullable`, `ValueTuple`, `IEnumerable<T>`). An expression is marked `/*<*/expr/*>*/`; its type is what the
 * native semantic model answers for exactly that range, in Roslyn's display format (the semantic gate's).
 */
class CSharpExpressionTypesTest : BasePlatformTestCase() {
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

    /** The type of each marked expression of [body] (statements of a method of a class with the usings of the fixtures), in order. */
    private fun types(body: String, members: String = "", path: String = "types/T${counter++}.cs"): List<String?> {
        val f = file(path, """
            using System;
            using System.Collections.Generic;
            using System.Linq;
            using System.Threading.Tasks;
            using Fixture;

            class Sample
            {
            ${members.prependIndent("    ")}
                async Task Body(int number, string text, List<int> numbers, Dictionary<string, int> map, int[] array, int? maybe, byte small, uint unsigned,
                    Box<StringBuilderLike> box, Circle circle, object any, bool flag)
                {
            ${body.prependIndent("        ")}
                }
            }
        """.trimIndent())
        return marked(f)
    }

    private fun marked(f: CSharpFile): List<String?> {
        val model = NativeCSharpSemanticModel(CSharpSemanticSession(project))
        val text = f.text
        val found = ArrayList<String?>()
        var at = text.indexOf(OPEN)
        while (at >= 0) {
            val start = at + OPEN.length
            val end = text.indexOf(CLOSE, start)
            found += model.typeOf(f, TextRange(start, end))?.display
            at = text.indexOf(OPEN, end)
        }
        assertFalse("no marks", found.isEmpty())
        return found
    }

    fun testLiteralsNamesAndVar() {
        assertEquals(
            listOf("int", "long", "string", "char", "bool", "double", "decimal", "uint", "int", "string", "System.Collections.Generic.List<int>", "int?", "System.Collections.Generic.List<int>"),
            types("""
                var a = /*<*/1/*>*/ + /*<*/2L/*>*/;
                var s = /*<*/"x"/*>*/ + /*<*/'c'/*>*/ + /*<*/true/*>*/ + /*<*/1.5/*>*/ + /*<*/1m/*>*/ + /*<*/1u/*>*/;
                var copy = /*<*/number/*>*/;
                var t = /*<*/text/*>*/;
                var list = new List<int>();
                var again = /*<*/list/*>*/;
                var m = /*<*/maybe/*>*/;
                /*<*/var/*>*/ inferred = list;
            """.trimIndent()),
        )
    }

    fun testMembersAndGenericSubstitution() {
        assertEquals(
            listOf("double", "string", "System.Collections.Generic.List<Fixture.StringBuilderLike>", "Fixture.StringBuilderLike", "int", "string", "int", "Fixture.Box<Fixture.StringBuilderLike>.Inner<int>"),
            types("""
                var r = /*<*/circle.Radius/*>*/;
                var label = /*<*/circle.Label/*>*/;
                var items = /*<*/box.Items/*>*/;
                var held = /*<*/box.Held/*>*/;
                var count = /*<*/Shape.Count/*>*/;
                var kind = /*<*/Shape.Kind/*>*/;
                var length = /*<*/text.Length/*>*/;
                Box<StringBuilderLike>.Inner<int> inner = null;
                var i = /*<*/inner/*>*/;
            """.trimIndent()),
        )
    }

    fun testThisBaseNewAndTargetTypedNew() {
        val f = file("types/This.cs", """
            using System.Collections.Generic;
            class Base { public int Id; }
            class Derived : Base
            {
                List<int> numbers = /*<*/new()/*>*/;
                void M()
                {
                    var self = /*<*/this/*>*/;
                    var id = /*<*/base.Id/*>*/;
                    var created = /*<*/new Derived()/*>*/;
                    Derived other = /*<*/new()/*>*/;
                    other = /*<*/default/*>*/;
                    var array = /*<*/new[] { 1, 2 }/*>*/;
                    var grid = /*<*/new int[2, 3]/*>*/;
                }
            }
        """.trimIndent())
        assertEquals(listOf("System.Collections.Generic.List<int>", "Derived", "int", "Derived", "Derived", "Derived", "int[]", "int[,]"), marked(f))
    }

    fun testInvocationsWithInferredTypeArguments() {
        assertEquals(
            listOf(
                "System.Collections.Generic.List<int>", "System.Collections.Generic.IEnumerable<string>", "int", "int", "string", "bool",
                "System.Collections.Generic.IEnumerable<int>", "Fixture.Circle", "string", "Fixture.StringBuilderLike", "double",
                "System.Collections.Generic.IEnumerable<int>",
            ),
            types("""
                var a = /*<*/numbers.ToList()/*>*/;
                var b = /*<*/numbers.Select(n => n.ToString())/*>*/;
                var c = /*<*/numbers.Where(n => n > 1).First()/*>*/;
                var d = numbers.Select(n => /*<*/n/*>*/);
                var e = numbers.Select(n => /*<*/n.ToString()/*>*/);
                var f = /*<*/numbers.Any(n => n > 2)/*>*/;
                var g = /*<*/Enumerable.Empty<int>()/*>*/;
                var h = /*<*/circle.Twice()/*>*/;
                var i = /*<*/box.Map(x => "y")/*>*/;
                var j = box.Map(x => /*<*/x/*>*/);
                var k = /*<*/numbers.Sum(n => n * 2.5)/*>*/;
                var l = /*<*/numbers.Select((n, i) => i)/*>*/;
            """.trimIndent()),
        )
    }

    fun testElementAccessCastsAndOperatorsOfTypes() {
        assertEquals(
            listOf("int", "int", "int", "char", "int", "object", "string", "System.Type", "string", "int", "Fixture.Circle"),
            types("""
                var a = /*<*/numbers[0]/*>*/;
                var b = /*<*/map["k"]/*>*/;
                var c = /*<*/array[1]/*>*/;
                var d = /*<*/text[0]/*>*/;
                var e = /*<*/circle[1]/*>*/;
                var f = /*<*/(object)number/*>*/;
                var g = /*<*/any as string/*>*/;
                var h = /*<*/typeof(int)/*>*/;
                var i = /*<*/nameof(number)/*>*/;
                var j = /*<*/default(int)/*>*/;
                var k = /*<*/circle!/*>*/;
            """.trimIndent()),
        )
    }

    fun testAwait() {
        assertEquals(
            listOf("int", "void", "string"),
            types("""
                var a = /*<*/await Task.FromResult(1)/*>*/;
                /*<*/await Task.Delay(1)/*>*/;
                var c = /*<*/await Task.Run(() => "x")/*>*/;
            """.trimIndent()),
        )
    }

    fun testPredefinedOperatorsAndPromotions() {
        assertEquals(
            listOf("int", "double", "uint", "long", "long", "int", "bool", "bool", "string", "string", "int", "long", "int", "bool", "int", "int?", "decimal"),
            types("""
                var a = /*<*/small + small/*>*/;
                var b = /*<*/1 + 2.0/*>*/;
                var c = /*<*/unsigned + 1/*>*/;
                var d = /*<*/unsigned + number/*>*/;
                var e = /*<*/-unsigned/*>*/;
                var f = /*<*/-small/*>*/;
                var g = /*<*/number == 1/*>*/;
                var h = /*<*/any is string/*>*/;
                var i = /*<*/$"{number}"/*>*/;
                var j = /*<*/text + number/*>*/;
                var k = /*<*/maybe ?? 0/*>*/;
                var l = /*<*/flag ? 1 : 2L/*>*/;
                var m = /*<*/number++/*>*/;
                var n = /*<*/!flag/*>*/;
                var o = /*<*/number << 2/*>*/;
                var p = /*<*/maybe + 1/*>*/;
                var q = /*<*/1m * number/*>*/;
            """.trimIndent()),
        )
    }

    fun testForeachOutVarDeconstructionAndTuples() {
        assertEquals(
            listOf(
                "int", "System.Collections.Generic.KeyValuePair<string, int>", "string", "int", "int", "string", "(int, string)", "(int a, string b)",
                "(int number, string text)", "Fixture.StringBuilderLike", "bool", "string",
            ),
            types("""
                foreach (var n in numbers) { var x = /*<*/n/*>*/; }
                foreach (var pair in map) { var y = /*<*/pair/*>*/; var z = /*<*/pair.Key/*>*/; }
                int.TryParse("1", out var parsed);
                var p = /*<*/parsed/*>*/;
                var (first, second) = (1, "a");
                var f = /*<*/first/*>*/;
                var s = /*<*/second/*>*/;
                var t1 = /*<*/(1, "a")/*>*/;
                var t2 = /*<*/(a: 1, b: "a")/*>*/;
                var t3 = /*<*/(number, text)/*>*/;
                foreach (var item in box) { var w = /*<*/item/*>*/; }
                var has = /*<*/maybe.HasValue/*>*/;
                if (any is string str) { var u = /*<*/str/*>*/; }
            """.trimIndent()),
        )
    }

    fun testQueries() {
        assertEquals(
            listOf("System.Collections.Generic.IEnumerable<string>", "int", "System.Linq.IOrderedEnumerable<int>", "System.Collections.Generic.IEnumerable<System.Linq.IGrouping<bool, int>>", "bool"),
            types("""
                var a = /*<*/from n in numbers where n > 1 select n.ToString()/*>*/;
                var b = from n in numbers let m = n * 2 select /*<*/m/*>*/;
                var c = /*<*/from n in numbers orderby n select n/*>*/;
                var d = /*<*/from n in numbers group n by n > 2/*>*/;
                var e = from n in numbers group n by n > 2 into g select /*<*/g.Key/*>*/;
            """.trimIndent()),
        )
    }

    fun testBrokenCodeAndCyclesGiveNothing() {
        val f = file("types/Broken.cs", """
            class Broken
            {
                void M()
                {
                    var a = /*<*/a/*>*/;
                    var p = q;
                    var q = /*<*/p/*>*/;
                    var x = /*<*/Undefined.Foo().Bar/*>*/;
                    var y = /*<*/1 +/*>*/ ;
                    var z = /*<*/new Missing()/*>*/;
                }
        """.trimIndent())
        assertEquals(listOf<String?>(null, null, null, null, null), marked(f))
    }

    fun testMembersAfterExpressionsResolveToTheSolution() {
        file("types/nav/Model.cs", "namespace Nav;\npublic class Order\n{\n    public decimal Total { get; set; }\n    public string Name { get; set; } = \"\";\n}\n")
        val f = file("types/nav/Use.cs", """
            using System.Collections.Generic;
            using System.Linq;
            using Nav;

            class Use
            {
                void M(List<Order> orders)
                {
                    var first = orders.First().Name;
                    foreach (var o in orders) { var t = /*<*/o/*>*/.Total; }
                    var names = orders.Select(x => x.Name).ToList();
                    var sum = orders.Where(x => x.Total > 1).Sum(x => x.Total);
                }
            }
        """.trimIndent())
        assertEquals(listOf("Nav.Order"), marked(f))
        val text = f.text
        fun target(name: String, occurrence: Int): String? {
            var at = -1
            repeat(occurrence + 1) { at = text.indexOf(name, at + 1) }
            return NativeCSharpNavigation.targets(f.findElementAt(at)!!)?.singleOrNull()?.text?.substringBefore(" {")
        }
        assertEquals("public string Name", target("Name", 0))
        assertEquals("public decimal Total", target("Total", 0))
        assertEquals("public string Name", target("Name", 1))
        assertEquals("public decimal Total", target("Total", 1))
        assertEquals("public decimal Total", target("Total", 2))
        settings.setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.NATIVE)
        val colors = NativeCSharpSemanticColors.colors(f).associate { text.substring(it.first.startOffset, it.first.endOffset) + "@" + it.first.startOffset to it.second }
        assertEquals(CSharpColors.PROPERTY, colors["Total@${text.indexOf("Total")}"])
        assertEquals(CSharpColors.PROPERTY, colors["Name@${text.indexOf("x.Name") + 2}"])
    }

    /** The live scenario `debug-playground/Console/Editor/ExpressionTypes.cs`: every `.Total` / `.Name` in code goes to TypesOrder (implicit usings added). */
    fun testThePlaygroundScenarioResolvesEveryMemberAfterAnExpression() {
        val scenario = java.io.File("debug-playground/Console/Editor/ExpressionTypes.cs").readText().replace("\r\n", "\n")
        val f = file("types/playground/ExpressionTypes.cs", "using System;\nusing System.Linq;\nusing System.Collections.Generic;\nusing System.Threading.Tasks;\n$scenario")
        val text = f.text
        val wrong = ArrayList<String>()
        var count = 0
        for (match in Regex("""\.(Total|Name)\b""").findAll(text)) {
            val line = text.substring(text.lastIndexOf('\n', match.range.first) + 1, match.range.first).trimStart()
            if (line.startsWith("//")) continue
            count++
            val expected = if (match.groupValues[1] == "Total") "public decimal Total" else "public string Name"
            val target = NativeCSharpNavigation.targets(f.findElementAt(match.range.first + 1)!!)?.singleOrNull()?.text?.substringBefore(" {")
            if (target != expected) wrong += "${text.substring(text.lastIndexOf('\n', match.range.first) + 1, match.range.last + 1).trim()} -> $target"
        }
        assertTrue(count > 15)
        assertEquals(emptyList<String>(), wrong)
    }

    fun testSpeedOfColorsWithLambdasOnALargeFile() {
        settings.setSource(CSharpFeature.SEMANTIC_COLORS, CSharpFeatureSource.NATIVE)
        val body = (0 until 150).joinToString("\n") { i ->
            """
                public decimal M$i(List<Item> items, Dictionary<string, Item> map)
                {
                    var first = items.Where(x => x.Price > $i).Select(x => x.Name).FirstOrDefault();
                    foreach (var pair in map) { var key = pair.Key; var value = pair.Value.Price; }
                    var total = items.Sum(x => x.Price) + items.Count(x => x.Name.Length > 2);
                    return total + (first?.Length ?? 0);
                }
            """.trimIndent().prependIndent("    ")
        }
        val f = file("types/Large.cs", "using System.Linq;\nusing System.Collections.Generic;\n\nclass Item { public string Name = \"\"; public decimal Price; }\n\nclass Large\n{\n$body\n}\n")
        assertTrue(f.text.lines().size > 1000)
        NativeCSharpSemanticColors.colors(f)
        val runs = (0 until 5).map {
            val start = System.nanoTime()
            NativeCSharpSemanticColors.colors(f)
            (System.nanoTime() - start) / 1_000_000
        }
        println("colors with lambdas of ${f.text.lines().size} lines: $runs ms")
        // the budget is 100 ms warm on a developer machine; the assertion leaves room for a loaded build agent
        assertTrue("colors took $runs ms", runs.min() < 500)
    }

    private companion object {
        const val OPEN = "/*<*/"
        const val CLOSE = "/*>*/"
        var counter = 0

        private fun bytes(name: String): ByteArray? = CSharpExpressionTypesTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
