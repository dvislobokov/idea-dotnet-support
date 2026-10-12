package io.github.dotnetsupport

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpExpressionNames
import io.github.dotnetsupport.lang.CSharpPostfixTemplateProvider
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * Postfix templates by the type of the expression (COMPLETION_GAPS 2.1) and Rider's templates the plugin lacked (2.2): `.await` of a task
 * only, `.foreach` of a collection, `.if` of a `bool`, `.for` up to `Count` / `Length`, names by expression and type; `.field`, `.prop`,
 * `.inject`, `.to`, `.arg`, `.sel`, `.parse`, `.tryparse`.
 */
class PostfixByTypeTest : BasePlatformTestCase() {
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    override fun tearDown() {
        try {
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val templates by lazy { CSharpPostfixTemplateProvider().templates.associateBy { it.key.removePrefix(".") } }

    private fun method(body: String) = """
        using System;
        using System.Collections.Generic;
        using System.Threading.Tasks;

        class Order
        {
            public decimal Total { get; set; }
        }

        class A
        {
            void M(bool ready, int count, string name, List<int> numbers, int[] array, Task<int> task, List<Order> orders, Order order, object box, IEnumerable<int> sequence, int? maybe)
            {
                $body
            }
        }
    """.trimIndent()

    /** The keys offered after the expression before `<caret>` (the key itself already removed, as the platform asks). */
    private fun offered(body: String): Set<String> {
        myFixture.configureByText("Offered${files++}.cs", method(body))
        val context = myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
        return templates.filterValues { it.isApplicable(context, myFixture.editor.document, myFixture.caretOffset) }.keys
    }

    private fun expand(key: String, text: String): String {
        myFixture.configureByText("Expand${files++}.cs", text)
        val context = myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
        WriteCommandAction.runWriteCommandAction(project) { templates.getValue(key).expand(context, myFixture.editor) }
        WriteCommandAction.runWriteCommandAction(project) { TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false) }
        val result = myFixture.editor.document.text
        return result.substring(0, myFixture.caretOffset) + "<caret>" + result.substring(myFixture.caretOffset)
    }

    private fun assertContains(text: String, part: String) = assertTrue(text, text.contains(part))

    private fun expandInBody(key: String, body: String): String = expand(key, method(body))

    /** DEV_JOURNEY 4.7 (0.1.100): after `OrderStatus.` the members of the enum only; a class name keeps `.new` and `.typeof`. */
    fun testATypeNameGetsNoTemplatesOfAValue() {
        assertEquals(emptySet<String>(), offered("DayOfWeek<caret>"))
        val type = offered("Order<caret>")
        assertTrue(type.toString(), "new" in type && "typeof" in type)
        assertFalse(type.toString(), "var" in type || "arg" in type || "await" in type || "nameof" in type)
        assertTrue("a value keeps them", "var" in offered("order<caret>"))
    }

    fun testABoolGetsTheConditionsAndNotTheLoops() {
        val keys = offered("ready<caret>")
        assertTrue(keys.toString(), keys.containsAll(listOf("if", "else", "while", "not", "var", "return")))
        assertFalse(keys.toString(), keys.any { it in setOf("foreach", "for", "forr", "await", "using", "null", "notnull", "lock", "parse") })
    }

    fun testACollectionGetsTheLoops() {
        val keys = offered("numbers<caret>")
        assertTrue(keys.toString(), keys.containsAll(listOf("foreach", "for", "forr", "notnull", "null", "lock")))
        assertFalse(keys.toString(), keys.any { it in setOf("if", "while", "not", "await", "using", "parse") })
        // a sequence has no count: `foreach` only
        val sequence = offered("sequence<caret>")
        assertTrue(sequence.toString(), "foreach" in sequence)
        assertFalse(sequence.toString(), "for" in sequence || "forr" in sequence)
    }

    fun testATaskIsAwaitedAndANumberCounted() {
        val task = offered("task<caret>")
        assertTrue(task.toString(), "await" in task)
        assertFalse(task.toString(), "foreach" in task || "if" in task)
        val count = offered("count<caret>")
        assertTrue(count.toString(), "for" in count)
        assertFalse(count.toString(), count.any { it in setOf("foreach", "null", "notnull", "lock", "await") })
        // `int?` may be null
        assertTrue("notnull" in offered("maybe<caret>"))
    }

    fun testAStringIsParsedAndAnUnknownExpressionGetsEverything() {
        val name = offered("name<caret>")
        assertTrue(name.toString(), name.containsAll(listOf("parse", "tryparse", "foreach", "for", "notnull")))
        assertFalse("parse" in offered("count<caret>"))
        val unknown = offered("missing<caret>")
        assertTrue(unknown.toString(), unknown.containsAll(listOf("if", "foreach", "await", "using", "lock", "null", "parse", "for")))
    }

    fun testStatementTemplatesAreNotOfferedBetweenMembersButInjectIs() {
        myFixture.configureByText("Members${files++}.cs", "class B\n{\n    IClock<caret>\n}\n")
        val context = myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
        val keys = templates.filterValues { it.isApplicable(context, myFixture.editor.document, myFixture.caretOffset) }.keys
        assertEquals(setOf("inject"), keys)
    }

    fun testForCountsToCountOrLength() {
        assertContains(expandInBody("for", "numbers<caret>"), "for (var i = 0; i < numbers.Count; i++)")
        assertContains(expandInBody("for", "array<caret>"), "for (var i = 0; i < array.Length; i++)")
        assertContains(expandInBody("forr", "array<caret>"), "for (var i = array.Length - 1; i >= 0; i--)")
        assertContains(expandInBody("for", "count<caret>"), "for (var i = 0; i < count; i++)")
    }

    fun testNamesComeFromTheExpressionAndTheType() {
        assertContains(expandInBody("var", "order.Total<caret>"), "var orderTotal = order.Total;<caret>")
        assertContains(expand("foreach", "using System.Collections.Generic;\nclass Order { }\nclass B { void M(List<Order> orders) {\n    orders<caret>\n} }"), "foreach (var order in orders)")
        // `order` is taken by a parameter there
        assertContains(expandInBody("foreach", "orders<caret>"), "foreach (var order1 in orders)")
        assertContains(expandInBody("var", "new List<Order>()<caret>"), "var orders1 = new List<Order>();")
        assertEquals(listOf("orders"), CSharpExpressionNames.forExpression("GetOrders()").take(1))
        assertEquals(listOf("orderTotal", "total"), CSharpExpressionNames.forExpression("order.Total").take(2))
        assertEquals("category", CSharpExpressionNames.singular("categories"))
        assertEquals("person", CSharpExpressionNames.singular("people"))
        assertEquals("box", CSharpExpressionNames.singular("boxes"))
        assertEquals("order", CSharpExpressionNames.forElement("GetOrders()").first())
    }

    fun testFieldAndPropertyAreDeclaredInTheType() {
        val field = expand("field", "class C\n{\n    private int _x;\n\n    public C(string name)\n    {\n        name<caret>\n    }\n}\n")
        assertEquals("class C\n{\n    private int _x;\n    private readonly string _name;\n\n    public C(string name)\n    {\n        _name = name;<caret>\n    }\n}\n", field)
        val property = expand("prop", "class C\n{\n    void M(int count)\n    {\n        count<caret>\n    }\n}\n")
        assertEquals("class C\n{\n    public int Count { get; set; }\n\n    void M(int count)\n    {\n        Count = count;<caret>\n    }\n}\n", property)
    }

    fun testInjectAddsAConstructorParameter() {
        // no constructor: the primary one
        assertEquals("class C(IOrderService orderService)\n{\n    <caret>\n}\n", expand("inject", "class C\n{\n    IOrderService<caret>\n}\n"))
        assertEquals("class C(int a, IClock clock)\n{\n    <caret>\n}\n", expand("inject", "class C(int a)\n{\n    IClock<caret>\n}\n"))
        // a constructor: its parameter and a field
        assertEquals(
            "class C\n{\n    private readonly IClock _clock;\n\n    <caret>\n\n    public C(int a, IClock clock)\n    {\n        A = a;\n        _clock = clock;\n    }\n}\n",
            expand("inject", "class C\n{\n    IClock<caret>\n\n    public C(int a)\n    {\n        A = a;\n    }\n}\n"),
        )
        assertContains(expand("inject", "class C\n{\n    IClock<caret>\n\n    public C() { }\n}\n"), "public C(IClock clock) {\n        _clock = clock;\n    }")
    }

    fun testToArgSelParse() {
        assertContains(expandInBody("to", "count<caret>"), " = count;<caret>")
        assertContains(expandInBody("arg", "var x = name<caret>;"), "var x = (name)<caret>;")
        myFixture.configureByText("Sel${files++}.cs", method("var x = name<caret>;"))
        val context = myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
        WriteCommandAction.runWriteCommandAction(project) { templates.getValue("sel").expand(context, myFixture.editor) }
        assertEquals("name", myFixture.editor.selectionModel.selectedText)
        assertContains(expandInBody("parse", "var x = name<caret>;"), "var x = int.Parse(name)<caret>;")
        assertContains(expandInBody("tryparse", "if (name<caret>)"), "if (int.TryParse(name, out var value)<caret>)")
    }

    fun testRiderDescriptions() {
        assertEquals("Awaits expressions of 'Task' type", templates.getValue("await").description)
        assertEquals("Introduces primary constructor parameter of type", templates.getValue("inject").description)
        assertTrue(templates.keys.containsAll(listOf("arg", "field", "prop", "to", "sel", "parse", "tryparse", "inject")))
    }

    private companion object {
        fun bytes(name: String): ByteArray? = PostfixByTypeTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
