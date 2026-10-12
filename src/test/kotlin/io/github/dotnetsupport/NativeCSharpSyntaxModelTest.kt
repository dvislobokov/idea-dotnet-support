package io.github.dotnetsupport

import com.intellij.navigation.LocationPresentation
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.psi.CSharpVariableDeclarator
import io.github.dotnetsupport.lang.CSharpAttributedMethods
import io.github.dotnetsupport.lang.CSharpDeclarationInfo
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpFileStructure
import io.github.dotnetsupport.lang.CSharpIcons
import io.github.dotnetsupport.lang.CSharpSyntaxModel
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.DeclarationKind
import io.github.dotnetsupport.lang.HeuristicCSharpSyntaxModel
import io.github.dotnetsupport.lang.NativeCSharpSyntaxModel
import java.io.File

/** [NativeCSharpSyntaxModel]: the declarations of csharp-psi's tree as the facade gives them, and the PSI elements that stand for them. */
class NativeCSharpSyntaxModelTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } finally {
            super.tearDown()
        }
    }

    private val model = NativeCSharpSyntaxModel

    /** `kind presentation [modifiers] «range» body` per declaration, indented by nesting; a range as its text, its first and last line when longer. */
    private fun render(text: String, structure: CSharpFileStructure = model.declarations(text)): String {
        fun shown(range: TextRange): String = range.substring(text).lines().let { if (it.size == 1) it[0] else it.first() + "…" + it.last().trim() }
        fun line(info: CSharpDeclarationInfo, indent: String): List<String> {
            val modifiers = info.modifiers.takeIf { it.isNotEmpty() }?.joinToString(" ", " [", "]").orEmpty()
            val body = info.body?.let { " " + shown(it) }.orEmpty()
            val nameText = info.nameRange.substring(text)
            assertTrue("${info.name} at $nameText", if (info.kind == DeclarationKind.OPERATOR) nameText == "operator" else info.name.removePrefix("~") == nameText)
            return listOf("$indent${info.kind.title} ${info.presentation}$modifiers «${shown(info.range)}»$body") + info.children.flatMap { line(it, "$indent  ") }
        }
        return (listOfNotNull(structure.usings?.let { "usings «${shown(it)}»" }) + structure.declarations.flatMap { line(it, "") }).joinToString("\n")
    }

    fun testEveryKindOfDeclaration() {
        val text = """
            namespace Shop
            {
                /// <summary>Doc comments are not a part of the range.</summary>
                [Serializable]
                public abstract class Order<T> : IComparable where T : class
                {
                    public Order(int id) { }
                    ~Order() { }
                    [Obsolete] protected abstract decimal Total(decimal discount, string code);
                    public static Order<T> operator +(Order<T> a, Order<T> b) => a;
                    public static implicit operator int(Order<T> o) => 0;
                    public string Name { get; set; } = "";
                    public int this[int index, string key] => index;
                    private const int Max = 10;
                    public event EventHandler? Changed;
                    public event EventHandler Removed { add { } remove { } }
                    int IComparable.CompareTo(object? other) => 0;
                    void Local() { void Inner() { } int x = 1; }
                }
                internal enum Color { Red = 1, [Obsolete] Green }
                public delegate void Handler<TArg>(object sender, TArg e);
                interface IShape { double Area(); }
                struct Point { public int X; }
            }
        """.trimIndent()
        assertEquals("""
            namespace Shop «namespace Shop…}» {…}
              class Order [public abstract] «[Serializable]…}» {…}
                constructor Order(int id) [public] «public Order(int id) { }» { }
                constructor ~Order() «~Order() { }» { }
                method Total(decimal discount, string code): decimal [protected abstract] «[Obsolete] protected abstract decimal Total(decimal discount, string code);»
                operator operator +(Order<T> a, Order<T> b): Order<T> [public static] «public static Order<T> operator +(Order<T> a, Order<T> b) => a;»
                operator operator int(Order<T> o) [public static] «public static implicit operator int(Order<T> o) => 0;»
                property Name: string [public] «public string Name { get; set; } = "";» { get; set; }
                indexer this[int index, string key]: int [public] «public int this[int index, string key] => index;»
                field Max: int [private const] «private const int Max = 10;»
                event Changed: EventHandler? [public] «public event EventHandler? Changed;»
                event Removed: EventHandler [public] «public event EventHandler Removed { add { } remove { } }» { add { } remove { } }
                method CompareTo(object? other): int «int IComparable.CompareTo(object? other) => 0;»
                method Local(): void «void Local() { void Inner() { } int x = 1; }» { void Inner() { } int x = 1; }
              enum Color [internal] «internal enum Color { Red = 1, [Obsolete] Green }» { Red = 1, [Obsolete] Green }
                enum member Red «Red = 1»
                enum member Green «[Obsolete] Green»
              delegate Handler(object sender, TArg e): void [public] «public delegate void Handler<TArg>(object sender, TArg e);»
              interface IShape «interface IShape { double Area(); }» { double Area(); }
                method Area(): double «double Area();»
              struct Point «struct Point { public int X; }» { public int X; }
                field X: int [public] «public int X;»
        """.trimIndent(), render(text))
    }

    fun testFieldsWithSeveralDeclaratorsAreOneDeclarationEach() {
        val text = "class C\n{\n    [Obsolete] private int _a = 1, _b, _c = 3;\n    int _single;\n}\n"
        assertEquals("""
            class C «class C…}» {…}
              field _a: int [private] «[Obsolete] private int _a = 1»
              field _b: int [private] «_b»
              field _c: int [private] «_c = 3;»
              field _single: int «int _single;»
        """.trimIndent(), render(text))

        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val file = myFixture.configureByText("NativeModelFields.cs", text)
        val type = model.childDeclarations(file).single()
        val fields = model.childDeclarations(type)
        assertEquals(listOf("_a", "_b", "_c", "_single"), fields.map { model.declarationOf(it)!!.name })
        assertTrue(fields.take(3).all { it is CSharpVariableDeclarator })
        assertFalse(fields[3] is CSharpVariableDeclarator)
        assertEquals("_b", model.declarationElementAt(file, text.indexOf("_b"))?.let { model.declarationOf(it)?.name })
        assertEquals("_a", model.declarationElementAt(file, text.indexOf("private"))?.let { model.declarationOf(it)?.name })
        assertEquals("C", model.declarationElementAt(file, text.indexOf(", _c") + 1)?.let { model.declarationOf(it)?.name })
        assertEquals("_single", model.declarationElementAt(file, text.indexOf("int _single"))?.let { model.declarationOf(it)?.name })
        // Go to Symbol finds each declarator by its name, at its name
        assertEquals(text.indexOf("_c"), fields[2].textOffset)
        assertEquals("_c", fields[2].name)
        assertEquals(text.indexOf("_single"), fields[3].textOffset)
        assertEquals("_single", fields[3].name)
    }

    fun testFileScopedNamespaceRecordsAndNestedTypes() {
        val text = """
            using System;
            global using static System.Math;

            namespace App.Models;

            public record Person(string Name, int Age);
            public readonly record struct Money(decimal Amount);
            public sealed record class Box<T>(T Value) where T : notnull { }
            public class Outer { public class Inner { void M() { } } }
        """.trimIndent()
        assertEquals("""
            usings «using System;…global using static System.Math;»
            namespace App.Models «namespace App.Models;…public class Outer { public class Inner { void M() { } } }»
              record Person(string Name, int Age) [public] «public record Person(string Name, int Age);»
              record Money(decimal Amount) [public readonly] «public readonly record struct Money(decimal Amount);»
              record Box(T Value) [public sealed] «public sealed record class Box<T>(T Value) where T : notnull { }» { }
              class Outer [public] «public class Outer { public class Inner { void M() { } } }» { public class Inner { void M() { } } }
                class Inner [public] «public class Inner { void M() { } }» { void M() { } }
                  method M(): void «void M() { }» { }
        """.trimIndent(), render(text))
        val structure = model.declarations(text)
        val m = structure.all().single { it.name == "M" }
        assertEquals("App.Models.Outer.Inner.M", structure.qualifiedName(m))
        assertEquals(listOf("App.Models", "Outer", "Inner"), structure.containersOf(m).map { it.name })
    }

    /** `#if` is decided with the IDE's default symbols (`DEBUG` among them): the other branch is not code. */
    fun testIfBranchesFollowTheDefaultSymbols() {
        val text = "class C\n{\n#if DEBUG\n    void Debug(int x)\n#else\n    void Release()\n#endif\n    {\n    }\n    void After() { }\n}\n"
        assertEquals(listOf("C", "Debug(int x)", "After()"), model.declarations(text).all().map { it.presentation.substringBefore(':') }.toList())
    }

    fun testTopLevelStatementsAreNoDeclarationsButTheTypesAfterThemAre() {
        val text = "using System;\nusing (var s = Open()) { Console.WriteLine(s); }\nvoid Local() { }\nConsole.WriteLine(1);\nrecord Settings(string Name);\n"
        assertEquals("usings «using System;»\nrecord Settings(string Name) «record Settings(string Name);»", render(text))
    }

    fun testAssemblyAttributesAreNotAPartOfTheNamespace() {
        val text = "using System;\n\n[assembly: CLSCompliant(true)]\n\nnamespace N\n{\n    class C { }\n}\n"
        assertEquals("namespace N\n{\n    class C { }\n}", model.declarations(text).declarations.single().range.substring(text))
    }

    /** Code being typed: no exception, the declarations around and before the unfinished one are still there. */
    fun testUnfinishedCode() {
        val texts = listOf(
            "class C\n{\n    public void Done() { }\n    public int\n}\n",
            "class C\n{\n    public void Done() { }\n    public void Typing(int a,\n    void After() { }\n}\n",
            "namespace N {\n class C {\n  void M() {\n",
            "class C { public int X { get; ",
            "class { void M() { } }",
            "class C { int a, ; }",
            "public static Order operator",
            "class C { public static C operator }",
            "[Fact",
            "",
        )
        for (text in texts) {
            val structure = model.declarations(text)
            structure.all().forEach { assertTrue("$text: ${it.name}", it.range.endOffset <= text.length && it.nameRange.startOffset >= it.range.startOffset) }
            model.attributedMethods(text, setOf("Fact"))
        }
        assertEquals(listOf("C", "Done"), model.declarations(texts[0]).all().map { it.name }.take(2).toList())
        assertTrue(model.declarations(texts[1]).all().any { it.name == "Typing" })
        assertEquals(listOf("N", "C", "M"), model.declarations(texts[2]).all().map { it.name }.toList())
        assertEquals(listOf("C", "X"), model.declarations(texts[3]).all().map { it.name }.toList())
    }

    fun testElementsOfTheNativeTree() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val text = "namespace Shop.Orders\n{\n    public class OrderService\n    {\n        public decimal Total(int count) => 0;\n        int _x;\n    }\n}\n"
        val file = myFixture.configureByText("NativeModelElements.cs", text) as CSharpFile
        assertNotNull(file.compilationUnit)
        assertSame(NativeCSharpSyntaxModel, CSharpSyntaxModel.current)
        val namespace = model.childDeclarations(file).single()
        val type = model.childDeclarations(namespace).single()
        val (method, field) = model.childDeclarations(type)
        assertEquals(text.indexOf("OrderService"), type.textOffset)
        assertEquals(text.indexOf("Total"), method.textOffset)
        assertEquals("OrderService", type.name)
        assertEquals("_x", field.name)

        val typeRow = type.presentation!!
        assertEquals("OrderService", typeRow.presentableText)
        assertEquals("Shop.Orders", typeRow.locationString)
        val methodRow = method.presentation!!
        assertEquals("Total(int count)", methodRow.presentableText)
        assertEquals("OrderService", methodRow.locationString)
        assertEquals(" ", (methodRow as LocationPresentation).locationPrefix)

        // ElementBase.getIcon asks the icon providers: the plugin's one answers for declarations
        val info = model.declarationOf(method)!!
        assertEquals(CSharpIcons.of(DeclarationKind.METHOD, info.modifiers, DeclarationKind.CLASS).toString(), method.getIcon(0).toString())
        assertEquals(CSharpIcons.of(DeclarationKind.CLASS, setOf("public"), DeclarationKind.NAMESPACE).toString(), type.getIcon(0).toString())

        assertEquals("Total", model.declarationElementAt(file, text.indexOf("=> 0"))?.let { model.declarationOf(it)?.name })
        assertNull(model.declarationOf(file.findElementAt(text.indexOf("count"))!!.parent))
        assertEquals(listOf("Shop.Orders", "OrderService", "Total", "_x"), model.declarations(file).all().map { it.name }.toList())
        assertSame(model.declarations(file), model.declarations(file))
    }

    /** A file parsed before the switch keeps its tree: the native model answers for it as the heuristic one does, and the other way round. */
    fun testEitherModelAnswersOnEitherTree() {
        val text = "namespace N { class A { void M() { } int _f; } }"
        CSharpSyntaxTrees.forceNativeTreeForTests(false)
        val heuristic = myFixture.configureByText("NativeModelHeuristicFile.cs", text)
        assertNull((heuristic as CSharpFile).compilationUnit)
        assertEquals(render(text, HeuristicCSharpSyntaxModel.declarations(heuristic)), render(text, model.declarations(heuristic)))
        assertEquals("N", model.declarationOf(model.childDeclarations(heuristic).single())?.name)
        assertEquals("M", model.declarationElementAt(heuristic, text.indexOf("void"))?.let { model.declarationOf(it)?.name })

        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val native = myFixture.configureByText("NativeModelNativeFile.cs", text)
        assertNotNull((native as CSharpFile).compilationUnit)
        assertEquals(render(text, model.declarations(native)), render(text, HeuristicCSharpSyntaxModel.declarations(native)))
        assertEquals("N", HeuristicCSharpSyntaxModel.declarationOf(HeuristicCSharpSyntaxModel.childDeclarations(native).single())?.name)
        assertEquals("M", HeuristicCSharpSyntaxModel.declarationElementAt(native, text.indexOf("void"))?.let { HeuristicCSharpSyntaxModel.declarationOf(it)?.name })
    }

    fun testAttributedMethods() {
        val text = """
            namespace Shop.Tests
            {
                public class OrderTests
                {
                    [Fact] public void Plain() { }
                    [Theory, InlineData(1)] public void WithData(int x) { }
                    [Xunit.Fact] public void Qualified() { }
                    [global::Xunit.FactAttribute] public void Alias() { }
                    [Obsolete] public void NotATest() { }
                    public class Nested { [Fact] public void InNested() { } }
                }
                interface IContract { [Fact] void InInterface(); }
                public struct InStruct { [Fact] public void M() { } }
            }
        """.trimIndent()
        val found = model.attributedMethods(text, setOf("Fact", "FactAttribute", "Theory"))
        assertEquals(
            listOf("Shop.Tests.OrderTests Plain", "Shop.Tests.OrderTests WithData", "Shop.Tests.OrderTests Qualified", "Shop.Tests.OrderTests Alias",
                "Shop.Tests.OrderTests+Nested InNested", "Shop.Tests.InStruct M"),
            found.methods.map { "${it.typeName} ${it.name}" },
        )
        assertTrue(found.methods.all { text.substring(it.nameRange.startOffset, it.nameRange.endOffset) == it.name })
        assertEquals(listOf("Shop.Tests.OrderTests", "Shop.Tests.OrderTests+Nested", "Shop.Tests.InStruct"), found.types.map { it.first })
    }

    /** On the snapshot inputs the tests found are the heuristics' ones (the run gutter of the native goldens waits for its consumer). */
    fun testAttributedMethodsAgreeWithTheHeuristicsOnTheSnapshots() {
        val attributes = setOf("Fact", "Theory", "Test", "TestCase", "TestMethod", "FactAttribute")
        val inputs = File("src/test/resources/syntaxSnapshots").walkTopDown().filter { it.extension == "cs" }.toList()
        assertTrue(inputs.isNotEmpty())
        fun describe(found: CSharpAttributedMethods): List<String> {
            val types = found.types.toMap()
            return found.methods.map { "${it.typeName}.${it.name} ${it.nameRange} type ${types[it.typeName]}" }
        }
        for (input in inputs) {
            val text = input.readText().replace("\r\n", "\n")
            assertEquals(input.name, describe(HeuristicCSharpSyntaxModel.attributedMethods(text, attributes)), describe(model.attributedMethods(text, attributes)))
        }
    }
}
