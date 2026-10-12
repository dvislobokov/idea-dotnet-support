package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpCompletionContributor
import io.github.dotnetsupport.lang.NativeCSharpExpectedCompletion
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * COMPLETION by the expected type (0.1.88, [NativeCSharpExpectedCompletion]): members of initializers and property patterns, the members of an
 * expected enum, `await` rows, `new` with a target type and the filtered type places, smart completion — on the assemblies of
 * src/test/resources/index.
 */
class CSharpExpectedTypeCompletionTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true
    private var autocompleteSmart = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        val code = CodeInsightSettings.getInstance()
        autocomplete = code.AUTOCOMPLETE_ON_CODE_COMPLETION
        autocompleteSmart = code.AUTOCOMPLETE_ON_SMART_TYPE_COMPLETION
        code.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        code.AUTOCOMPLETE_ON_SMART_TYPE_COMPLETION = false
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport"))!!
        ApplicationManager.getApplication().extensionArea.getExtensionPoint(CompletionContributor.EP)
            .registerExtension(CompletionContributorEP("C#", CSharpCompletionNativeTest.FakeServer::class.java.name, plugin), testRootDisposable)
    }

    override fun tearDown() {
        try {
            CSharpCompletionNativeTest.FakeServer.items = emptyList()
            val code = CodeInsightSettings.getInstance()
            code.AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            code.AUTOCOMPLETE_ON_SMART_TYPE_COMPLETION = autocompleteSmart
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun lookup(text: String, type: CompletionType = CompletionType.BASIC): List<LookupElement> {
        myFixture.configureByText("Expected${counter++}.cs", text)
        myFixture.complete(type)
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun native(text: String, type: CompletionType = CompletionType.BASIC): List<String> =
        lookup(text, type).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map { it.lookupString }

    /** The native items as the list shows them (`OrderStatus.Paid`). */
    private fun texts(text: String, type: CompletionType = CompletionType.BASIC): List<String> =
        lookup(text, type).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map { LookupElementPresentation.renderElement(it).itemText.orEmpty() }

    private fun shown(element: LookupElement): String {
        val presentation = LookupElementPresentation.renderElement(element)
        return presentation.itemText + (presentation.typeText?.let { " : $it" } ?: "")
    }

    private fun choose(text: String, item: String, type: CompletionType = CompletionType.BASIC): String {
        val elements = lookup(text, type)
        val element = elements.firstOrNull { it.lookupString == item } ?: error("no $item in ${elements.map { it.lookupString }}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return myFixture.editor.document.text
    }

    private fun code(body: String, members: String = ""): String = """
        using System;
        using System.Collections.Generic;
        using System.Threading.Tasks;

        public enum OrderStatus { New, Paid = 5, Shipped, Cancelled }
        public class Customer { public string Name { get; set; } = ""; public int Age { get; set; } }
        public class Order
        {
            public int Id { get; set; }
            public string Customer { get; set; } = "";
            public OrderStatus Status { get; init; }
            public decimal Total { get; private set; }
            public List<string> Lines { get; } = new();
            public readonly int Code;
            public string Summary => "";
            public Customer Buyer { get; set; } = new();
        }
        public class SpecialOrder : Order { }
        public abstract class Shape { }
        public class Circle : Shape { }
        public sealed class Square : Shape { }
        public interface IShape { }
        public class OrderException : Exception { }
        public delegate void Changed(int value);
        public record Point(int X, int Y);
        class Sample
        {
            private int _created;
            private string _mutable = "";
            Task<string> Highlights() => Task.FromResult("");
            Task<int> Count() => Task.FromResult(1);
            void Take(OrderStatus status) { }
            $members
            async Task Run(OrderStatus status, Order order, string name, int number, Point point)
            {
                $body
            }
        }
    """.trimIndent()

    // ---- initializers and property patterns

    fun testObjectInitializerListsTheMembersToAssign() {
        // without the mapping rows of 0.1.134 (`Buyer = order.Buyer`, CSharpMappingCompletionTest)
        val names = native(code("var o = new Order { <caret> };")).filterNot { " = " in it || it.startsWith("Map all") }
        assertEquals(names.toString(), listOf("Buyer", "Customer", "Fill all members", "Id", "Lines", "Status").sorted(), names.sorted())
    }

    fun testObjectInitializerLeavesOutTheAssignedOnes() {
        val names = native(code("var o = new Order { Id = 1, Status = OrderStatus.New, <caret> };")).filterNot { " = " in it || it.startsWith("Map all") }
        assertEquals(names.toString(), listOf("Buyer", "Customer", "Fill all members", "Lines"), names.sorted())
    }

    fun testWithInitializerOfARecord() {
        val names = native(code("var p = point with { X = 1, <caret> };"))
        assertEquals(names.toString(), listOf("Y"), names)
    }

    fun testACollectionInitializerStaysAList() {
        val names = native(code("var l = new List<int> { <caret> };"))
        assertTrue(names.toString(), "number" in names)
        assertFalse(names.toString(), "Capacity" in names)
    }

    fun testPropertyPatternListsTheMembers() {
        val names = native(code("if (order is { <caret> }) { }"))
        assertTrue(names.toString(), names.containsAll(listOf("Id", "Customer", "Status", "Total", "Lines", "Summary", "Buyer")))
        assertFalse(names.toString(), "number" in names || "if" in names)
        val rest = native(code("if (order is { Id: 1, <caret> }) { }"))
        assertFalse(rest.toString(), "Id" in rest)
    }

    fun testNestedPropertyPattern() {
        val names = native(code("if (order is { Buyer: { <caret> } }) { }"))
        assertEquals(names.toString(), listOf("Age", "Name"), names.sorted())
    }

    fun testValueOfASubpatternIsTheEnum() {
        val names = texts(code("if (order is { Status: <caret> }) { }"))
        assertEquals(names.toString(), ENUM_ROWS, names.take(4).sorted())
    }

    // ---- enum members

    fun testEnumMembersAfterEquality() {
        val elements = lookup(code("if (status == <caret>) { }"))
        val shown = elements.map(::shown)
        assertEquals(shown.toString(), listOf("OrderStatus.Cancelled : 7", "OrderStatus.New : 0", "OrderStatus.Paid : 5", "OrderStatus.Shipped : 6"), shown.take(4).sorted())
    }

    fun testEnumMembersInCaseSwitchArmIsArgumentAndAssignment() {
        for (body in listOf(
            "switch (status) { case <caret> }",
            "var label = status switch { <caret> };",
            "if (order.Status is <caret>) { }",
            "Take(<caret>);",
            "status = <caret>;",
            "OrderStatus next = <caret>;",
        )) {
            val names = texts(code(body))
            assertEquals("$body: $names", ENUM_ROWS, names.take(4).sorted())
        }
    }

    fun testATypedPrefixFindsTheMember() {
        val text = choose(code("if (status == Pa<caret>) { }"), "Paid")
        assertTrue(text, text.contains("if (status == OrderStatus.Paid) { }"))
    }

    fun testAnEnumMemberChosenAtTheEndOfALineClosesTheStatement() {
        // `Console.BackgroundColor = ConsoleColor.Black;`: the `;` (and the `)` left open) come with the member, as typing on would
        assertTrue(choose(code("status = <caret>"), "Paid").contains("status = OrderStatus.Paid;\n"))
        assertTrue(choose(code("Console.BackgroundColor = <caret>"), "Black").contains("Console.BackgroundColor = ConsoleColor.Black;\n"))
        assertTrue(choose(code("Take(<caret>"), "Paid").contains("Take(OrderStatus.Paid);\n"))
        assertTrue(choose(code("if (status == <caret>"), "Paid").contains("if (status == OrderStatus.Paid)\n"))
        assertTrue("after `ConsoleColor.`", choose(code("Console.ForegroundColor = ConsoleColor.<caret>"), "Black").contains("Console.ForegroundColor = ConsoleColor.Black;\n"))
        assertTrue("something follows on the line", choose(code("status = <caret> // later"), "Paid").contains("status = OrderStatus.Paid // later"))
        assertTrue("an initializer", choose(code("var o = new Order\n{\n    Status = <caret>\n};"), "Paid").contains("Status = OrderStatus.Paid\n"))
    }

    fun testTheListOpensAfterAnAssignmentOfAnEnum() {
        myFixture.configureByText("Popup${counter++}.cs", code("Console.BackgroundColor = IntellijIdeaRulezzz "))
        assertTrue(NativeCSharpExpectedCompletion.opensAfterSpace(myFixture.file.findElementAt(myFixture.file.text.indexOf("IntellijIdeaRulezzz"))!!))
        myFixture.configureByText("Popup${counter++}.cs", code("number = IntellijIdeaRulezzz "))
        assertFalse("no enum expected", NativeCSharpExpectedCompletion.opensAfterSpace(myFixture.file.findElementAt(myFixture.file.text.indexOf("IntellijIdeaRulezzz"))!!))
    }

    fun testAGenericExtensionNeedsItsConstraint() {
        // `AddEndpointFilter<TBuilder>(this TBuilder) where TBuilder : IEndpointConventionBuilder` was a method of `DayOfWeek`
        fun members(receiver: String) = native("using System;\nusing Fixture;\nclass C { void M(Circle circle, DayOfWeek day, string text) { $receiver.<caret> } }")
        assertTrue("`where T : Shape` of a circle", "Twice" in members("circle"))
        assertFalse("not of an enum", "Twice" in members("day"))
        assertFalse("not of a string", "Twice" in members("text"))
    }

    fun testNoEnumRowsWhereNothingIsExpected() {
        val names = texts(code("<caret>"))
        assertFalse(names.toString(), names.any { it.startsWith("OrderStatus.") })
    }

    fun testTheListOpensAfterEqualityOfAnEnum() {
        val contributor = NativeCSharpCompletionContributor()
        myFixture.configureByText("Popup${counter++}.cs", code("if (status == <caret>"))
        val equals = myFixture.file.findElementAt(myFixture.caretOffset - 2)!!
        assertEquals("==", equals.text)
        assertTrue(contributor.invokeAutoPopup(equals, ' '))
        assertFalse(contributor.invokeAutoPopup(equals, 'x'))
        // the place in the copy the platform completes in: an enum is expected after `status == `, not after `number == `
        myFixture.configureByText("Popup${counter++}.cs", code("if (status == IntellijIdeaRulezzz ) { }"))
        assertTrue(NativeCSharpExpectedCompletion.opensAfterSpace(myFixture.file.findElementAt(myFixture.file.text.indexOf("IntellijIdeaRulezzz"))!!))
        myFixture.configureByText("Popup${counter++}.cs", code("if (number == IntellijIdeaRulezzz ) { }"))
        assertFalse(NativeCSharpExpectedCompletion.opensAfterSpace(myFixture.file.findElementAt(myFixture.file.text.indexOf("IntellijIdeaRulezzz"))!!))
        myFixture.configureByText("Popup${counter++}.cs", code("switch (status) { case IntellijIdeaRulezzz }"))
        assertTrue(NativeCSharpExpectedCompletion.opensAfterSpace(myFixture.file.findElementAt(myFixture.file.text.indexOf("IntellijIdeaRulezzz"))!!))
    }

    // ---- await

    fun testAwaitRowForATaskOfTheExpectedType() {
        val elements = lookup(code("string s = <caret>"))
        val await = elements.firstOrNull { it.lookupString == "await Highlights" }
        assertNotNull(elements.map { it.lookupString }.toString(), await)
        assertEquals("await Highlights : Task<string>", shown(await!!))
        assertNull("Count gives an int", elements.firstOrNull { it.lookupString == "await Count" })
    }

    fun testAwaitRowMakesTheMethodAsync() {
        val text = choose(code("", members = "string Plain()\n    {\n        string s = <caret>\n    }"), "await Highlights")
        assertTrue(text, text.contains("string s = await Highlights()"))
        assertTrue(text, text.contains("async Task<string> Plain()"))
    }

    // ---- new with a target type

    fun testNewWithATargetTypeOffersItFirst() {
        val elements = lookup(code("Order o = new <caret>"))
        assertEquals(elements.map { it.lookupString }.toString(), "Order", elements.first().lookupString)
        assertEquals("Order()", LookupElementPresentation.renderElement(elements.first()).itemText)
        val text = choose(code("Order o = new <caret>"), "Order")
        assertTrue(text, text.contains("Order o = new Order()"))
    }

    fun testNewOfABaseTypeRanksTheDerivedOnes() {
        val names = native(code("Shape s = new <caret>"))
        assertTrue(names.toString(), names.indexOf("Circle") in 0 until names.indexOf("Order"))
        assertFalse("an abstract type is not created: $names", names.firstOrNull() == "Shape")
    }

    fun testThrowNewOffersOnlyExceptions() {
        val names = native(code("throw new <caret>"))
        assertTrue(names.toString(), names.containsAll(listOf("OrderException", "InvalidOperationException", "ArgumentException")))
        assertFalse(names.toString(), "Order" in names || "Circle" in names || "int" in names)
    }

    fun testCatchPutsExceptionsFirst() {
        val names = native(code("try { } catch (<caret>"))
        assertTrue(names.toString(), names.indexOf("OrderException") in 0 until names.indexOf("Order"))
        assertTrue(names.toString(), "ArgumentException" in names)
    }

    fun testBaseListEventAndConstraint() {
        val base = native("public interface IShape { }\npublic sealed class Square { }\npublic class Shape { }\npublic delegate void Changed();\npublic enum Kind { A }\nclass Circle : <caret>\n{\n}\n")
        assertTrue(base.toString(), base.containsAll(listOf("IShape", "Shape")))
        assertFalse(base.toString(), base.any { it in setOf("Square", "Changed", "Kind", "int", "Circle") })
        val struct = native("public interface IShape { }\npublic class Shape { }\nstruct Point : <caret>\n{\n}\n")
        assertTrue(struct.toString(), "IShape" in struct && "Shape" !in struct)
        val event = native("public interface IShape { }\npublic class Shape { }\npublic delegate void Changed();\nclass Sample\n{\n    public event <caret>\n}\n")
        assertTrue(event.toString(), "Changed" in event)
        assertFalse(event.toString(), "Shape" in event || "IShape" in event || "int" in event)
        val constraint = native("public interface IShape { }\npublic sealed class Square { }\npublic class Shape { }\nclass Box<T> where T : <caret>\n{\n}\n")
        assertTrue(constraint.toString(), constraint.containsAll(listOf("IShape", "Shape", "class", "struct")))
        assertFalse(constraint.toString(), "Square" in constraint || "int" in constraint)
    }

    // ---- smart completion

    fun testSmartCompletionKeepsWhatFits() {
        val names = native(code("int n = <caret>"), CompletionType.SMART)
        assertTrue(names.toString(), names.containsAll(listOf("number", "_created", "default")))
        assertFalse(names.toString(), names.any { it in setOf("name", "status", "_mutable", "if", "null", "order") })
        assertTrue(names.toString(), "await Count" in names)
    }

    fun testSmartCompletionOfAnEnumAndABool() {
        val status = texts(code("Take(<caret>);"), CompletionType.SMART)
        assertEquals(status.toString(), (ENUM_ROWS + "status").sorted(), status.filter { it != "default" }.sorted())
        val bool = native(code("bool b = <caret>"), CompletionType.SMART)
        assertTrue(bool.toString(), bool.containsAll(listOf("true", "false")))
    }

    fun testSmartCompletionOfStringHasItsStatics() {
        val names = native(code("string s = <caret>"), CompletionType.SMART)
        assertTrue(names.toString(), names.containsAll(listOf("name", "_mutable", "String.Empty", "await Highlights", "null")))
        assertFalse(names.toString(), "number" in names)
    }

    fun testSmartNewOffersTheTypesThatFit() {
        val names = native(code("Shape s = new <caret>"), CompletionType.SMART)
        assertTrue(names.toString(), "Circle" in names && "Square" in names)
        assertFalse(names.toString(), "Order" in names || "Shape" in names)
        val thrown = native(code("throw new <caret>"), CompletionType.SMART)
        assertTrue(thrown.toString(), "OrderException" in thrown && "ArgumentException" in thrown && "Order" !in thrown)
    }

    fun testSmartWithoutAnExpectedTypeIsTheUsualList() {
        val names = native(code("<caret>"), CompletionType.SMART)
        assertTrue(names.toString(), names.containsAll(listOf("status", "order", "if")))
    }

    private companion object {
        var counter = 0
        val ENUM_ROWS = listOf("OrderStatus.Cancelled", "OrderStatus.New", "OrderStatus.Paid", "OrderStatus.Shipped")

        private fun bytes(name: String): ByteArray? = CSharpExpectedTypeCompletionTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
