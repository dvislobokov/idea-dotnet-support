package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpGhostText
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.CSharpVariableNames
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpCompletionPlace
import io.github.dotnetsupport.lang.isInNumericLiteral
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.suggest.SuggestionRules

/**
 * COMPLETION on the native tree (CSHARP_PSI_MIGRATION.md, step 9, task A6): keywords by place, names in scope and their order, members
 * of the own type, its parts and bases, types of the solution, `override` / `partial`, names after a type, the common calls of a task
 * method (`Task.FromResult`, `Task.CompletedTask`, `async` by `await`), the merge with the server's list, broken code, the switch.
 */
class CSharpCompletionNativeTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport"))!!
        ApplicationManager.getApplication().extensionArea.getExtensionPoint(CompletionContributor.EP)
            .registerExtension(CompletionContributorEP("C#", FakeServer::class.java.name, plugin), testRootDisposable)
    }

    override fun tearDown() {
        try {
            FakeServer.items = emptyList()
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            settings.state.features = mutableMapOf()
            settings.state.enabled = true
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The lookup at `<caret>` of [text]: all its items in their order. */
    private fun lookup(text: String, name: String = "Complete${files++}.cs"): List<LookupElement> {
        myFixture.configureByText(name, if (text.startsWith("\n")) text.trimIndent() else text)
        myFixture.completeBasic()
        return myFixture.lookupElements?.toList().orEmpty()
    }

    /** The native items of the lookup, in order. */
    private fun native(text: String, name: String = "Complete${files++}.cs"): List<String> =
        lookup(text, name).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map { it.lookupString }

    private fun choose(text: String, item: String): String {
        val elements = lookup(text)
        val element = elements.firstOrNull { it.lookupString == item } ?: error("no $item in ${elements.map { it.lookupString }}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return myFixture.editor.document.text
    }

    private fun assertOrder(list: List<String>, vararg names: String) {
        val positions = names.map { name -> list.indexOf(name).also { assertTrue("$name in $list", it >= 0) } }
        assertEquals("the order of ${names.toList()} in $list", positions.sorted(), positions)
    }

    // ---- keywords by place

    fun testStatementKeywords() {
        val list = native("class A { void M() { <caret> } }")
        for (keyword in listOf("if", "for", "foreach", "while", "switch", "try", "return", "var", "new", "null", "this", "nameof", "int", "string")) assertTrue(keyword, keyword in list)
        for (keyword in listOf("break", "continue", "case", "else", "catch", "public", "class", "override", "yield")) assertFalse(keyword, keyword in list)
        assertTrue("a void method can be made async", "await" in list)
    }

    fun testLoopSwitchElseCatchYield() {
        assertTrue(native("class A { void M(int[] xs) { foreach (var x in xs) { <caret> } } }").containsAll(listOf("break", "continue")))
        val inSwitch = native("class A { void M(int x) { switch (x) { case 1: <caret> } } }")
        assertTrue(inSwitch.containsAll(listOf("break", "case", "default")))
        assertFalse(inSwitch.contains("continue"))
        assertTrue(native("class A { void M(bool a) { if (a) { }\n <caret>\n } }").contains("else"))
        assertTrue(native("class A { void M() { try { }\n <caret>\n } }").containsAll(listOf("catch", "finally")))
        assertTrue(native("using System.Collections.Generic; class A { IEnumerable<int> M() { <caret> } }").contains("yield"))
        assertEquals(listOf("break", "return"), native("using System.Collections.Generic; class A { IEnumerable<int> M() { yield <caret> } }").sorted())
    }

    fun testStaticMemberHasNoThis() {
        val list = native("class A { static void M() { <caret> } }")
        assertFalse(list.contains("this"))
        assertFalse(list.contains("base"))
    }

    fun testMemberStartModifiers() {
        val list = native("class A { <caret> }")
        for (keyword in listOf("public", "private", "static", "override", "void", "class", "async", "readonly", "int")) assertTrue(keyword, keyword in list)
        for (keyword in listOf("if", "return", "var", "this")) assertFalse(keyword, keyword in list)
        val afterPublic = native("class A { public <caret> }")
        assertFalse(afterPublic.contains("public"))
        assertFalse(afterPublic.contains("private"))
        assertTrue(afterPublic.containsAll(listOf("static", "override", "void", "async")))
        val afterStatic = native("class A { public static <caret> }")
        assertFalse(afterStatic.contains("override"))
        assertFalse(afterStatic.contains("virtual"))
        assertTrue(native("class A { protected <caret> }").contains("internal"))
    }

    fun testTopLevel() {
        val list = native("namespace N;\n<caret>")
        assertTrue(list.containsAll(listOf("class", "interface", "record", "public", "internal", "static")))
        assertFalse(list.contains("if"))
    }

    fun testTypePositions() {
        val created = native("class Order { } class A { void M() { var o = new <caret> } }")
        assertTrue(created.containsAll(listOf("Order", "int", "string")))
        assertFalse("no statements after new: $created", created.contains("if"))
        assertFalse(created.contains("var"))
        val parameter = native("class Order { } class A { void M(<caret>) { } }")
        assertTrue(parameter.containsAll(listOf("Order", "int", "ref", "out", "params", "this")))
    }

    fun testWhenWhereAndQueryKeywords() {
        assertEquals(listOf("when"), native("using System; class A { void M() { try { } catch (Exception e) <caret>\n } }"))
        assertEquals(listOf("where"), native("class A<T> <caret>\n{ }"))
        val query = native("using System.Linq; class A { void M(int[] xs) { var q = from x in xs <caret>\n } }")
        assertTrue(query.containsAll(listOf("where", "select", "orderby", "join", "let", "group")))
        assertTrue(native("using System.Linq; class A { void M(int[] xs) { var q = from x in xs orderby x <caret>\n } }").containsAll(listOf("ascending", "descending")))
    }

    fun testLabelsAfterGoto() {
        assertEquals(listOf("again"), native("class A { void M() { again: M(); goto <caret>; } }"))
    }

    // ---- names in scope

    fun testLocalsParametersMembersTypesKeywordsInThisOrder() {
        val list = native(
            """
            class Helper { }
            class A
            {
                private int _count;
                public string Title { get; set; }
                void Run() { }
                void M(int limit)
                {
                    var total = 1;
                    <caret>
                }
            }
            """,
        )
        assertOrder(list, "total", "limit", "_count", "Run", "Helper", "if")
        assertTrue(list.contains("Title"))
        assertFalse("a local declared below is not visible", native("class A { void M() { <caret>\n var later = 1; } }").contains("later"))
    }

    fun testShadowingAndLambdaParameters() {
        val list = native("using System; class A { int x; void M() { Func<int, int> f = x => <caret>; } }")
        assertEquals("the lambda's parameter, once", 1, list.count { it == "x" })
    }

    fun testTheVariableBeingDeclaredIsNotOffered() {
        val list = native("class A { void M(int limit) { int amount = <caret>\n } }")
        assertFalse(list.contains("amount"))
        assertTrue(list.contains("limit"))
    }

    fun testExpectedTypeAndNameGoUp() {
        val declared = native("class Order { } class Customer { } class A { void M(Order a, Customer b) { Customer c = <caret>\n } }")
        assertOrder(declared, "b", "a")
        val argument = native(
            """
            class Order { }
            class A
            {
                void Save(Order order) { }
                void M(Order other, int count)
                {
                    var order = new Order();
                    Save(<caret>);
                }
            }
            """,
        )
        assertOrder(argument, "order", "other", "count")
        val returned = native("class Order { } class A { Order M(int count, Order found) { return <caret>\n } }")
        assertOrder(returned, "found", "count")
    }

    fun testMembersOfPartsAndBasesOfTheSolution() {
        myFixture.addFileToProject("Shop/BaseService.cs", "namespace Shop; public class BaseService { protected int Retries; public void Log(string m) { } }")
        myFixture.addFileToProject("Shop/OrderService.Part.cs", "namespace Shop; public partial class OrderService { private int _pending; }")
        val list = native("namespace Shop; public partial class OrderService : BaseService { void M() { <caret> } }")
        assertTrue(list.containsAll(listOf("Retries", "Log", "_pending", "BaseService", "OrderService")))
    }

    fun testThisAndBase() {
        myFixture.addFileToProject("Shop/Animal.cs", "namespace Shop; public class Animal { public string Name { get; set; } }")
        val own = native("namespace Shop; class Dog : Animal { int _legs; void M() { this.<caret> } }")
        assertTrue(own.containsAll(listOf("_legs", "M", "Name")))
        assertFalse("no keywords after a dot", own.contains("if"))
        val base = native("namespace Shop; class Dog : Animal { int _legs; void M() { base.<caret> } }")
        assertTrue(base.contains("Name"))
        assertFalse(base.contains("_legs"))
    }

    fun testUsingStaticAndImportedTypes() {
        myFixture.addFileToProject("Util/Maths.cs", "namespace Util; public static class Maths { public static int Twice(int x) => x * 2; public const int Max = 3; }")
        myFixture.addFileToProject("Other/Hidden.cs", "namespace Other; public class Hidden { }")
        val list = native("using static Util.Maths;\nusing Util;\nclass A { void M() { <caret> } }")
        assertTrue(list.containsAll(listOf("Twice", "Max", "Maths")))
        assertFalse("a type of a namespace the file does not import: $list", list.contains("Hidden"))
    }

    fun testStaticContextHidesInstanceMembers() {
        val list = native("class A { int _x; static int Count; static void M() { <caret> } }")
        assertTrue(list.contains("Count"))
        assertFalse(list.contains("_x"))
    }

    fun testMethodItemGetsItsCall() {
        assertEquals("class A { void Run() { } void M() { Run();\n } }", choose("class A { void Run() { } void M() { Ru<caret>\n } }", "Run"))
        assertEquals(myFixture.editor.document.text.indexOf("Run();") + "Run();".length, myFixture.caretOffset)
        assertEquals("class A { int Twice(int x) => x; void M() { var y = Twice() } }", choose("class A { int Twice(int x) => x; void M() { var y = Tw<caret> } }", "Twice"))
        assertEquals(myFixture.editor.document.text.indexOf("Twice()") + "Twice(".length, myFixture.caretOffset)
    }

    // ---- override and partial

    fun testOverrideCompletion() {
        myFixture.addFileToProject(
            "Shapes/Shape.cs",
            """
            namespace Shapes;
            public abstract class Shape
            {
                public abstract double Area();
                public virtual string Describe(int digits) => "";
                protected virtual int Sides { get; set; }
                public sealed override string ToString() => "";
                public void NotVirtual() { }
            }
            """.trimIndent(),
        )
        val text = "namespace Shapes;\npublic class Square : Shape\n{\n    public override string Describe(int digits) => \"\";\n    override <caret>\n}\n"
        val list = native(text)
        assertTrue(list.containsAll(listOf("Area", "Sides", "Equals", "GetHashCode")))
        assertFalse("already overridden: $list", list.contains("Describe"))
        assertFalse("not virtual: $list", list.contains("NotVirtual"))
        assertFalse("no keywords after override: $list", list.contains("static"))
        assertEquals(
            "namespace Shapes;\npublic class Square : Shape\n{\n    public override string Describe(int digits) => \"\";\n    public override double Area()\n    {\n        throw new NotImplementedException();\n    }\n}\n",
            choose(text, "Area"),
        )
        assertEquals(
            "namespace Shapes;\npublic class Square : Shape\n{\n    public override string Describe(int digits) => \"\";\n    protected override int Sides\n    {\n        get => base.Sides;\n        set => base.Sides = value;\n    }\n}\n",
            choose(text, "Sides"),
        )
        val virtualOne = "namespace Shapes;\npublic class Circle : Shape\n{\n    public override <caret>\n}\n"
        assertEquals(
            "namespace Shapes;\npublic class Circle : Shape\n{\n    public override string Describe(int digits)\n    {\n        return base.Describe(digits);\n    }\n}\n",
            choose(virtualOne, "Describe"),
        )
    }

    fun testPartialMethods() {
        myFixture.addFileToProject("Parts/Order.Defining.cs", "namespace Parts; public partial class Order { partial void OnSaved(int id); partial void OnLoaded(); partial void OnLoaded() { } }")
        val text = "namespace Parts;\npublic partial class Order\n{\n    partial <caret>\n}\n"
        val list = native(text)
        assertTrue(list.contains("OnSaved"))
        assertFalse("implemented already: $list", list.contains("OnLoaded"))
        assertEquals("namespace Parts;\npublic partial class Order\n{\n    partial void OnSaved(int id)\n    {\n        \n    }\n}\n", choose(text, "OnSaved"))
    }

    // ---- names after a type

    fun testNamesAfterAType() {
        assertEquals(listOf("builder", "stringBuilder"), native("using System.Text; class A { void M() { StringBuilder <caret>\n } }"))
        assertEquals(listOf("orders"), native("using System.Collections.Generic; class Order { } class A { void M() { List<Order> <caret>\n } }"))
        assertEquals(listOf("_logger"), native("interface ILogger { } class A { private readonly ILogger <caret>\n }"))
        assertEquals(listOf("client", "httpClient"), native("class HttpClient { } class A { void M(HttpClient <caret>) { } }"))
        assertTrue(native("class Order { } class A { void M(object o) { if (o is Order <caret>) { } } }").contains("order"))
    }

    fun testVariableNamesOfTypes() {
        assertEquals(listOf("builder", "stringBuilder"), CSharpVariableNames.forType("StringBuilder"))
        assertEquals(listOf("service", "orderService"), CSharpVariableNames.forType("IOrderService"))
        assertEquals(listOf("lines", "orderLines"), CSharpVariableNames.forType("IReadOnlyList<OrderLine>"))
        assertEquals(listOf("categories"), CSharpVariableNames.forType("Category[]"))
        assertEquals(listOf("boxes"), CSharpVariableNames.forType("List<Box>"))
        assertEquals(listOf("people"), CSharpVariableNames.forType("List<Person>"))
        assertEquals(listOf("client", "httpClient"), CSharpVariableNames.forType("System.Net.Http.HttpClient?"))
        assertEquals(listOf("@event"), CSharpVariableNames.forType("Event"))
        assertEquals(listOf("Logger"), CSharpVariableNames.forType("ILogger<Program>", NativeCSharpCompletionPlace.NameStyle.PUBLIC_MEMBER))
        assertEquals(emptyList<String>(), CSharpVariableNames.forType("int"))
        assertEquals("builder2", CSharpVariableNames.unique("builder", setOf("builder", "builder1")))
    }

    // ---- common calls of a task method

    fun testTaskFromResultFirstAfterReturn() {
        val text = "using System.Threading.Tasks; class A { Task<int> Count(int x) { return <caret>\n } }"
        assertEquals("Task.FromResult", native(text).first())
        assertEquals("using System.Threading.Tasks; class A { Task<int> Count(int x) { return Task.FromResult(x);\n } }", choose(text, "Task.FromResult").let {
            myFixture.type("x")
            myFixture.editor.document.text
        })
        assertEquals("Task.CompletedTask", native("using System.Threading.Tasks; class A { Task Save() { return <caret>\n } }").first())
        assertEquals("ValueTask.FromResult", native("using System.Threading.Tasks; class A { ValueTask<string> Name() { return <caret>\n } }").first())
        assertFalse("async: a value is returned", native("using System.Threading.Tasks; class A { async Task<int> Count() { return <caret>\n } }").contains("Task.FromResult"))
        assertFalse(native("class A { int Count() { return <caret>\n } }").contains("Task.FromResult"))
    }

    fun testTaskReturnGhost() {
        val context = object : CSharpGhostText.Context() {}
        fun ghost(text: String): CSharpGhostText.Ghost? = CSharpGhostText.ghost(text.replace("|", ""), text.indexOf('|'), context)
        val fromResult = ghost("using System.Threading.Tasks;\nclass A\n{\n    Task<int> Count()\n    {\n        return |\n    }\n}\n")
        assertEquals(SuggestionRules.TASK_RETURN, fromResult?.rule)
        assertEquals("Task.FromResult();", fromResult?.text)
        assertEquals("Task.CompletedTask;", ghost("class A\n{\n    System.Threading.Tasks.Task Save()\n    {\n        return |\n    }\n}\n")?.text)
        assertNull(ghost("using System.Threading.Tasks;\nclass A\n{\n    async Task<int> Count()\n    {\n        return |\n    }\n}\n")?.takeIf { it.rule == SuggestionRules.TASK_RETURN })
        assertNull(ghost("class A\n{\n    int Count()\n    {\n        return |\n    }\n}\n")?.takeIf { it.rule == SuggestionRules.TASK_RETURN })
        // a lambda's return is the lambda's
        assertNull(ghost("using System; using System.Threading.Tasks;\nclass A\n{\n    Task<int> Count()\n    {\n        Func<int> f = () =>\n        {\n            return |\n        };\n    }\n}\n")?.takeIf { it.rule == SuggestionRules.TASK_RETURN })
    }

    fun testAwaitMakesTheMethodAsync() {
        val list = native("using System.Threading.Tasks; class A { int Count() { <caret>\n return 1; } }")
        assertTrue(list.contains("await"))
        assertEquals(
            "using System.Threading.Tasks; class A { async Task<int> Count() { await \n return 1; } }",
            choose("using System.Threading.Tasks; class A { int Count() { aw<caret>\n return 1; } }", "await"),
        )
        assertEquals(
            "using System.Threading.Tasks; class A { async Task Run() { await \n } }",
            choose("using System.Threading.Tasks; class A { void Run() { aw<caret>\n } }", "await"),
        )
        assertFalse("not in a property", native("class A { int P { get { <caret>\n return 1; } } }").contains("await"))
        assertFalse("not in lock", native("class A { void M(object o) { lock (o) { <caret>\n } } }").contains("await"))
    }

    fun testMakeAsyncIntention() {
        myFixture.configureByText("Async${files++}.cs", "using System.Threading.Tasks; class A { int Count() { <caret>await Task.Delay(1); return 1; } }")
        val intention = myFixture.findSingleIntention("Make method async")
        myFixture.launchAction(intention)
        assertEquals("using System.Threading.Tasks; class A { async Task<int> Count() { await Task.Delay(1); return 1; } }", myFixture.editor.document.text)
        myFixture.configureByText("Async${files++}.cs", "class A { async void Count() { <caret>await System.Threading.Tasks.Task.Delay(1); } }")
        assertTrue(myFixture.filterAvailableIntentions("Make method async").isEmpty())
        myFixture.configureByText("Async${files++}.cs", "using System; class A { void OnClick(object sender, EventArgs e) { <caret>await System.Threading.Tasks.Task.Delay(1); } }")
        myFixture.launchAction(myFixture.findSingleIntention("Make method async"))
        assertTrue("an event handler stays void", myFixture.editor.document.text.contains("async void OnClick"))
    }

    // ---- the merge with the server

    fun testServerDuplicatesAreDropped() {
        FakeServer.items = listOf(
            FakeServer.Item("total", 40.0), FakeServer.Item("if", 0.0), FakeServer.Item("break", 0.0), FakeServer.Item("Console", 20.0),
            FakeServer.Item("Equals(object? obj)", 30.0),
        )
        val all = lookup("class A { void M() { var total = 1; <caret> } }")
        val strings = all.map { it.lookupString }
        assertEquals("one total, the native one", 1, strings.count { it == "total" })
        assertEquals(true, all.first { it.lookupString == "total" }.getUserData(NativeCSharpCompletion.NATIVE))
        assertEquals("one if", 1, strings.count { it == "if" })
        assertFalse("the tree says no `break` outside a loop: $strings", strings.contains("break"))
        assertTrue("what the native list has not is kept: $strings", strings.contains("Console"))
        assertTrue(strings.contains("Equals(object? obj)"))
    }

    /** Robot 0.1.60: the server's `override` members (empty lookup string, `Describe(int digits)` shown) stood beside the native ones. */
    fun testServerOverridesWithoutALookupStringAreDropped() {
        myFixture.addFileToProject("Shapes2/Shape.cs", "namespace Shapes2; public abstract class Shape { public virtual string Describe(int digits) => \"\"; }")
        FakeServer.items = listOf(FakeServer.Item("", 30.0, "Describe(int digits)"), FakeServer.Item("", 30.0, "Clone()"))
        val all = lookup("namespace Shapes2;\npublic class Circle : Shape\n{\n    public override <caret>\n}\n")
        val shown = all.map { LookupElementPresentation.renderElement(it).itemText }
        assertTrue(all.any { it.lookupString == "Describe" && it.getUserData(NativeCSharpCompletion.NATIVE) == true })
        assertFalse("the server's Describe is a duplicate: $shown", "Describe(int digits)" in shown)
        assertTrue("what the native list has not is kept: $shown", "Clone()" in shown)
    }

    /** Robot 0.1.60: the server's named argument `amount:` (lookup string `amount`) is not the native local `amount`. */
    fun testANamedArgumentIsNotADuplicate() {
        FakeServer.items = listOf(FakeServer.Item("amount", 40.0, "amount:"))
        val all = lookup("class A { void Resize(int amount) { } void M() { var amount = 1; Resize(<caret>); } }")
        val shown = all.filter { it.lookupString == "amount" }.map { LookupElementPresentation.renderElement(it).itemText }
        val everything = all.map { "${it.lookupString}/${LookupElementPresentation.renderElement(it).itemText}" }
        assertEquals("the native local and the server's named argument: $everything", 2, shown.size)
        assertTrue(shown.contains("amount:"))
    }

    /** Robot 0.1.60: keywords the server offers where the tree decides the keywords (its keyword items are dropped there). */
    fun testKeywordsTheServerAlsoOffers() {
        val statement = native("class A { void M() { <caret> } }")
        for (keyword in listOf("global", "ref", "stackalloc", "static", "scoped", "void", "dynamic", "extern")) assertTrue(keyword, keyword in statement)
        val expression = native("class A { void M() { int x = <caret> } }")
        for (keyword in listOf("global", "ref", "stackalloc", "static")) assertTrue(keyword, keyword in expression)
        val type = native("class A { void M() { var o = new <caret> } }")
        for (keyword in listOf("dynamic", "global")) assertTrue(keyword, keyword in type)
        val member = native("class A { <caret> }")
        for (keyword in listOf("dynamic", "global", "ref", "sealed")) assertTrue(keyword, keyword in member)
    }

    fun testAfterADotEverythingIsTheServers() {
        FakeServer.items = listOf(FakeServer.Item("Length", 40.0), FakeServer.Item("if", 0.0))
        val all = lookup("class A { void M(string s) { s.<caret> } }")
        assertTrue(all.none { it.getUserData(NativeCSharpCompletion.NATIVE) == true })
        assertTrue(all.map { it.lookupString }.containsAll(listOf("Length", "if")))
    }

    fun testRoslynSwitchAddsNothing() {
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.ROSLYN)
        FakeServer.items = listOf(FakeServer.Item("total", 40.0), FakeServer.Item("break", 0.0))
        val all = lookup("class A { void M() { var total = 1; <caret> } }")
        assertTrue(all.none { it.getUserData(NativeCSharpCompletion.NATIVE) == true })
        assertTrue(all.map { it.lookupString }.containsAll(listOf("total", "break")))
    }

    // ---- broken code

    fun testBrokenCodeAtEveryOffset() {
        val text = """
            using System; namespace N { class A : B { public override ( int x, { void M(int a { var q = from x in ; if (a is ) goto ; switch (a) { case : } }
            partial async Task<int> N() => await ; [Obs] int this[ ] { get { return } } } enum E { X, } record R(int A) : I<
        """.trimIndent()
        for (offset in 0..text.length step 3) {
            val withCaret = text.substring(0, offset) + "<caret>" + text.substring(offset)
            myFixture.configureByText("Broken${files++}.cs", withCaret)
            myFixture.completeBasic()
            myFixture.lookup?.hideLookup(true)
        }
    }

    /**
     * A digit typed into the open list of the expected type (`int x = ` → `1`) closes it, as in Rider: a number is no name, and Enter
     * after it is a line break, not the first item (robot 0.1.63: `int x = 1_resized`). Nor is a list made at or after a number.
     */
    /** The items of a list that opens by itself after [typed] is typed at `<caret>` (the auto-popup of a trigger character of the server). */
    private fun autoPopup(text: String, typed: String): List<String> {
        myFixture.configureByText("Auto${files++}.cs", text)
        myFixture.type(typed)
        val editor = myFixture.editor
        com.intellij.codeInsight.completion.CodeCompletionHandlerBase(com.intellij.codeInsight.completion.CompletionType.BASIC, false, true, true)
            .invokeCompletion(project, editor, 0)
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        return myFixture.lookup?.items?.filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }?.map { it.lookupString }.orEmpty()
    }

    fun testNoListOpensByItselfAfterABraceOrAParenthesis() {
        // the screenshot of 2026-10-05: `GetStringAsync(CancellationToken cancellationToken = default){` opened the list of the body
        val header = "using System.Threading; using System.Threading.Tasks; class R { private string _title = \"\";\n" +
            "public async Task<string> GetStringAsync(CancellationToken cancellationToken = default)<caret>\n}"
        assertEquals(emptyList<String>(), autoPopup(header, "{"))
        assertEquals("after `(`", emptyList<String>(), autoPopup("class R { void M(int a) { Use<caret> } void Use(int x) { } }", "("))
        assertTrue("after `.`", autoPopup("class R { int Count; void M() { this<caret> } }", ".").contains("Count"))
        assertTrue("after `new `", autoPopup("class R { void M() { var r = new<caret> } }", " ").contains("R"))
        // an explicit call still lists the body
        assertTrue(native(header.replace("<caret>", "{<caret>")).contains("cancellationToken"))
    }

    fun testNoListOnANumber() {
        for (number in listOf("1", "12", "0x1F", "1.5", "1_000", "2L", "1e")) {
            myFixture.configureByText("Lit${files++}.cs", "class Lit { int _resized; void M() { int x = $number<caret> } }")
            myFixture.completeBasic()
            assertNull("a list after $number: ${myFixture.lookupElementStrings}", myFixture.lookup)
        }
        myFixture.configureByText("Lit${files++}.cs", "class Lit { int _resized; int Count => 0; void M() { int x = <caret> } }")
        myFixture.completeBasic()
        assertNotNull("the list of the expected type", myFixture.lookup)
        myFixture.type('1')
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        assertTrue("the list after a digit: ${myFixture.lookupElementStrings}", myFixture.lookupElementStrings.isNullOrEmpty())
        myFixture.lookup?.hideLookup(true)
        myFixture.type('\n')
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("int x = 1\n"))
        // names with digits still complete
        assertFalse(isInNumericLiteral("x1", 2))
        assertTrue(isInNumericLiteral("= 0x1F", 6))
    }

    /** Items as the client of the server makes them: marked [NativeCSharpCompletion.SERVER]. */
    class FakeServer : CompletionContributor() {
        /** [shown]: the text of the list, when it is not [label] (the server's `override` members have an empty label, named arguments `name:`). */
        class Item(val label: String, val priority: Double, val shown: String? = null)

        override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
            for (item in items) {
                val builder = LookupElementBuilder.create(item, item.label).let { if (item.shown != null) it.withPresentableText(item.shown) else it }
                val element = PrioritizedLookupElement.withPriority(builder, item.priority)
                element.putUserData(NativeCSharpCompletion.SERVER, true)
                result.addElement(element)
            }
        }

        companion object {
            @Volatile
            var items: List<Item> = emptyList()
        }
    }
}
