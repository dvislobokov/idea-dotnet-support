package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * 0.1.94: explicit interface implementation (`void IFoo.|`), `[]` after the dot of a value with an indexer, names of tuple elements and
 * of what a deconstruction takes apart, `partial class |` — the places of NativeCSharpLanguageCompletion.
 */
class CSharpLanguageCompletionTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            settings.state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun lookup(text: String, name: String = "Lang${files++}.cs"): List<LookupElement> {
        myFixture.configureByText(name, text)
        myFixture.completeBasic()
        return myFixture.lookupElements?.filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.orEmpty()
    }

    private fun names(text: String): List<String> = lookup(text).map { it.lookupString }

    private fun choose(text: String, item: String, name: String = "Lang${files++}.cs"): String {
        val elements = lookup(text, name)
        val element = elements.firstOrNull { it.lookupString == item } ?: error("no $item in ${elements.map { it.lookupString }}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return myFixture.editor.document.text
    }

    private val FACE = "namespace Shop;\npublic interface IPriced\n{\n    decimal Price { get; }\n    string Describe(int digits);\n    void Reset();\n}\n"

    // ---- 3.2 explicit implementation

    fun testExplicitMembersAfterTheTypeAndTheInterface() {
        val text = "$FACE\npublic class Item : IPriced\n{\n    string IPriced.Describe(int digits) => \"\";\n    void IPriced.<caret>\n}\n"
        val list = names(text)
        assertEquals(listOf("Price", "Reset"), list.sorted())
        assertEquals(
            "$FACE\npublic class Item : IPriced\n{\n    string IPriced.Describe(int digits) => \"\";\n    void IPriced.Reset()\n    {\n        throw new NotImplementedException();\n    }\n}\n"
                .replaceFirst("namespace Shop;", "using System;\n\nnamespace Shop;"),
            choose(text, "Reset"),
        )
    }

    fun testExplicitPropertyIsWrittenWithItsAccessors() {
        val text = "$FACE\npublic class Item : IPriced\n{\n    int IPriced.<caret>\n}\n"
        val written = choose(text, "Price")
        assertTrue(written, written.contains("decimal IPriced.Price\n    {\n        get => throw new NotImplementedException();\n    }"))
        assertFalse(written, written.contains("int IPriced"))
        assertTrue(written, written.startsWith("using System;"))
    }

    fun testTheInterfaceAloneBeforeTheDot() {
        val text = "$FACE\npublic class Item : IPriced\n{\n    IPriced.<caret>\n}\n"
        assertEquals(listOf("Describe", "Price", "Reset"), names(text).sorted())
        val written = choose(text, "Describe")
        assertTrue(written, written.contains("    string IPriced.Describe(int digits)\n    {\n        throw new NotImplementedException();\n    }\n}"))
    }

    fun testAnInterfaceTheTypeDoesNotImplementOffersNothing() {
        val text = "$FACE\npublic class Item\n{\n    void IPriced.<caret>\n}\n"
        assertEquals(emptyList<String>(), names(text))
    }

    fun testExplicitMembersOfAnInterfaceOfTheAssemblies() {
        val text = "using System;\n\npublic class Res : IDisposable\n{\n    void IDisposable.<caret>\n}\n"
        assertEquals(listOf("Dispose"), names(text))
        assertTrue(choose(text, "Dispose").contains("    void IDisposable.Dispose()\n    {\n        throw new NotImplementedException();\n    }"))
    }

    fun testTheImplementedInterfacesAfterTheReturnType() {
        val text = "$FACE\npublic interface IOther { void Go(); }\npublic class Item : IPriced, IOther\n{\n    void <caret>\n}\n"
        val list = names(text)
        assertTrue(list.toString(), list.containsAll(listOf("IPriced", "IOther")))
        val presentation = LookupElementPresentation.renderElement(lookup(text).first { it.lookupString == "IPriced" })
        assertEquals(" (explicit implementation)", presentation.tailText)
        assertTrue(choose(text, "IOther").contains("    void IOther.\n"))
    }

    fun testNoInterfaceNamesWhereTheTypeImplementsNothing() {
        assertEquals(emptyList<String>(), names("public interface IA { void M(); }\npublic class Item\n{\n    void <caret>\n}\n"))
    }

    fun testNoInterfaceNamesAfterAnAccessModifier() {
        assertEquals(emptyList<String>(), names("$FACE\npublic class Item : IPriced\n{\n    public void <caret>\n}\n"))
    }

    // ---- 3.3 indexer

    fun testBracketsAfterTheDotOfAString() {
        val text = "class C { void M(string s) { s.<caret> } }\n"
        val item = lookup(text).firstOrNull { it.lookupString == "[]" } ?: error("no [] in ${names(text)}")
        assertTrue(LookupElementPresentation.renderElement(item).tailText.orEmpty(), LookupElementPresentation.renderElement(item).tailText!!.trim().startsWith("this["))
        assertEquals("class C { void M(string s) { s[] } }\n", choose(text, "[]"))
        assertEquals("the caret is between the brackets", "class C { void M(string s) { s[".length, myFixture.caretOffset)
    }

    fun testBracketsForAnArrayAListAndATypeOfTheSolution() {
        assertTrue(names("class C { void M(int[] a) { a.<caret> } }\n").contains("[]"))
        assertTrue(names("using System.Collections.Generic;\nclass C { void M(List<int> a) { a.<caret> } }\n").contains("[]"))
        assertTrue(names("class Grid { public int this[int row, int column] => 0; }\nclass C { void M(Grid g) { g.<caret> } }\n").contains("[]"))
        val derived = names("class Grid { public int this[int row] => 0; }\nclass Sub : Grid { }\nclass C { void M(Sub g) { g.<caret> } }\n")
        assertTrue(derived.toString(), derived.contains("[]"))
    }

    fun testNoBracketsWithoutAnIndexerOrWithAPrefix() {
        assertFalse(names("class C { void M(object o) { o.<caret> } }\n").contains("[]"))
        assertFalse(names("class Plain { public int X; }\nclass C { void M(Plain p) { p.<caret> } }\n").contains("[]"))
        assertFalse(names("class C { void M(string s) { s.Le<caret> } }\n").contains("[]"))
    }

    fun testBracketsAfterAConditionalDot() {
        assertEquals("class C { void M(string s) { var c = s?[]; } }\n", choose("class C { void M(string s) { var c = s?.<caret>; } }\n", "[]"))
    }

    // ---- 3.5 tuples

    fun testNamesOfTheElementsOfATuple() {
        val text = "class C { void M() { (string title, int count) t = default; t.<caret> } }\n"
        val list = names(text)
        assertTrue(list.toString(), list.containsAll(listOf("title", "count")))
        val item = lookup(text).first { it.lookupString == "count" }
        assertEquals("int", LookupElementPresentation.renderElement(item).typeText)
    }

    fun testNamesOfAnInferredTuple() {
        val list = names("class C { void M(int total) { var t = (total, name: \"x\"); t.<caret> } }\n")
        assertTrue(list.toString(), list.containsAll(listOf("total", "name")))
    }

    fun testNamesOfDeconstructedTupleElements() {
        val declared = "class C { (string title, int count) Pair() => default; void M() { var (<caret>, count) = Pair(); } }\n"
        assertEquals(listOf("title"), names(declared))
        val tupleOfLocals = "class C { void M() { (string title, int count) pair = default; (var x, var <caret>) = pair; } }\n"
        assertEquals(listOf("count"), names(tupleOfLocals))
    }

    fun testNamesOfDeconstructInForeach() {
        val text = "using System.Collections.Generic;\nclass C { void M(List<(string title, int count)> pairs) { foreach (var (title, <caret>) in pairs) { } } }\n"
        assertEquals(listOf("count"), names(text))
    }

    fun testNamesOfTheParametersOfDeconstruct() {
        val text = "class Money { public void Deconstruct(out int units, out string currency) { units = 0; currency = \"\"; } }\n" +
            "class C { void M(Money m) { var (<caret>, c) = m; } }\n"
        assertEquals(listOf("units"), names(text))
        val record = "record Point(int X, int Y);\nclass C { void M(Point p) { var (a, <caret>) = p; } }\n"
        assertEquals(listOf("y"), names(record))
    }

    fun testNoNamesWithoutKnownElements() {
        assertEquals(emptyList<String>(), names("class C { void M(object o) { var (<caret>, b) = o; } }\n"))
        assertEquals(emptyList<String>(), names("class C { void M() { (string, int) pair = default; var (<caret>, b) = pair; } }\n"))
    }

    // ---- 3.6 partial types

    fun testPartialTypesOfTheNamespaceInOtherFiles() {
        myFixture.addFileToProject("Order.Part1.cs", "namespace Shop;\npublic partial class Order { }\npublic partial class Cart { }\npublic partial struct Money { }\npublic class Plain { }\n")
        myFixture.addFileToProject("Other.cs", "namespace Other;\npublic partial class Elsewhere { }\n")
        val list = names("namespace Shop;\npublic partial class <caret>\n")
        assertTrue(list.toString(), list.containsAll(listOf("Order", "Cart")))
        assertFalse("a struct is not a class: $list", "Money" in list)
        assertFalse("not partial: $list", "Plain" in list)
        assertFalse("another namespace: $list", "Elsewhere" in list)
        assertEquals(listOf("Money"), names("namespace Shop;\npublic partial struct <caret>\n"))
        val item = lookup("namespace Shop;\npublic partial class <caret>\n").first { it.lookupString == "Order" }
        assertEquals(" (Order.Part1.cs)", LookupElementPresentation.renderElement(item).tailText)
    }

    fun testAPartialTypeOfThisFileAloneIsNotOffered() {
        myFixture.addFileToProject("Mine.Other.cs", "namespace Shop;\npublic partial class Mine { }\n")
        val list = names("namespace Shop;\npublic partial class Own { }\npublic partial class <caret>\n")
        assertEquals(listOf("Mine"), list)
    }

    fun testPartialGenericAndInterface() {
        myFixture.addFileToProject("Box.cs", "namespace Shop;\npublic partial class Box<T> { }\npublic partial interface IShape { }\n")
        val list = names("namespace Shop;\npublic partial class <caret>\n")
        assertTrue(list.toString(), "Box<T>" in list)
        assertEquals(listOf("IShape"), names("namespace Shop;\npublic partial interface <caret>\n"))
    }

    fun testChoosingAPartialTypeWritesItsName() {
        myFixture.addFileToProject("Order.Part1.cs", "namespace Shop;\npublic partial class Order { }\n")
        assertEquals("namespace Shop;\npublic partial class Order\n", choose("namespace Shop;\npublic partial class <caret>\n", "Order"))
    }

    private companion object {
        fun bytes(name: String): ByteArray? = CSharpLanguageCompletionTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
