package io.github.dotnetsupport

import io.github.dotnetsupport.lang.CSharpExpectations
import io.github.dotnetsupport.lang.CSharpExpected
import io.github.dotnetsupport.lang.CSharpNameLikeness
import io.github.dotnetsupport.lang.CSharpNameLikeness.Likeness
import io.github.dotnetsupport.lang.CSharpScopeTypes
import io.github.dotnetsupport.lang.CSharpTypeNames
import io.github.dotnetsupport.roslyn.RoslynCompletionPolicy
import io.github.dotnetsupport.roslyn.RoslynCompletionRanking
import io.github.dotnetsupport.suggest.SuggestionRules
import org.eclipse.lsp4j.CompletionItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order of the completion list: what fits the place goes up. */
class CompletionRankingTest {
    private val source = """
        public class OrderService
        {
            private readonly IRepository _repository;
            private readonly List<Order> _orders = new();
            public string Name { get; set; }
            public int Count => _orders.Count;

            public decimal Total(Order order) => 0;

            public async Task<Order> Handle(Order order, string customerName, CancellationToken cancellationToken = default)
            {
                var total = Total(order);
                int count = 0;
                var text = "x";
                var made = new Dictionary<string, List<int>>();
                List<List<int>> nested = null;
                string? maybe = null;
                foreach (var line in order.Lines) { }
                if (order is Paid paid && count > 0) { }
                |
            }
        }
    """.trimIndent()

    private val offset = source.indexOf('|')
    private val text = source.removeRange(offset, offset + 1)

    /** [line] typed where the caret of the source is. */
    private fun typed(line: String): Pair<String, Int> = (text.substring(0, offset) + line + text.substring(offset)) to (offset + line.length)

    @Test
    fun `names of the scope with the types that are written`() {
        val symbols = CSharpScopeTypes.at(text, offset)
        fun type(name: String) = symbols[name]?.type
        assertEquals("Order", type("order"))
        assertEquals("string", type("customerName"))
        assertEquals("CancellationToken", type("cancellationToken"))
        assertEquals("IRepository", type("_repository"))
        assertEquals("List<Order>", type("_orders"))
        assertEquals("string", type("Name"))
        assertEquals("int", type("Count"))
        assertEquals("a method: what it returns", "decimal", type("Total"))
        assertEquals("int", type("count"))
        assertEquals("a string literal", "string", type("text"))
        assertEquals("Dictionary<string, List<int>>", type("made"))
        assertEquals("List<List<int>>", type("nested"))
        assertEquals("string?", type("maybe"))
        assertEquals("Paid", type("paid"))
        assertNull("var of a call: the text does not say", type("total"))
        assertTrue("total" in symbols)
        assertTrue(symbols.getValue("count").local)
        assertTrue(symbols.getValue("order").local)
        assertFalse(symbols.getValue("_repository").local)
        assertFalse("a member of something else", "Lines" in symbols)
        assertFalse("Paid" in symbols)
        assertEquals(listOf("name" to "string", "order" to "Order", "rest" to "int[]", "handler" to "Func<int, string>"),
            CSharpScopeTypes.parameters("(string name, [FromBody] Order order, params int[] rest, Func<int, string> handler = null)"))
    }

    @Test
    fun `what is wanted at the caret`() {
        fun expected(line: String): String? {
            val (changed, at) = typed(line)
            val start = RoslynCompletionRanking.nameStart(changed, at)
            return CSharpExpectations.at(changed, start, CSharpScopeTypes.at(changed, start))?.toString()
        }
        assertEquals("int amount", expected("int amount = "))
        assertEquals("the name is being typed", "int amount", expected("int amount = co"))
        assertEquals("List<Order> found", expected("List<Order> found = "))
        assertEquals("? amount", expected("var amount = "))
        assertEquals("an assignment to a local", "int count", expected("count = "))
        assertEquals("to a property of this class", "string Name", expected("Name = "))
        assertEquals("string Name", expected("this.Name += "))
        assertEquals("to a member of something else: the name only", "? Customer", expected("order.Customer = "))
        assertEquals("async: what is awaited", "Order ?", expected("return "))
        assertNull("a comparison", expected("if (count == "))
        assertNull(expected("if (count >= "))
        assertNull("a lambda", expected("Func<int, int> f = x => "))
        assertNull(expected("Handle("))
        assertNull(expected(""))
    }

    @Test
    fun `types that go for one another`() {
        assertTrue(CSharpTypeNames.matches("int", "int"))
        assertTrue(CSharpTypeNames.matches("string", "System.String"))
        assertTrue(CSharpTypeNames.matches("string", "string?"))
        assertTrue(CSharpTypeNames.matches("List<Order>", "System.Collections.Generic.List<Shop.Order>"))
        assertTrue(CSharpTypeNames.matches("IEnumerable<Order>", "List<Order>"))
        assertTrue(CSharpTypeNames.matches("IReadOnlyList<int>", "int[]"))
        assertFalse(CSharpTypeNames.matches("IEnumerable<Order>", "List<Line>"))
        assertFalse(CSharpTypeNames.matches("int", "string"))
        assertFalse("anything goes there: no sign", CSharpTypeNames.matches("object", "string"))
        assertFalse("a type parameter", CSharpTypeNames.matches("T", "string"))
        assertFalse(CSharpTypeNames.matches("TSource", "TSource"))
        assertFalse(CSharpTypeNames.matches(null, "int"))
        assertFalse(CSharpTypeNames.matches("int", null))
        assertEquals("Dictionary<string,List<int>>", CSharpTypeNames.normalize("System.Collections.Generic.Dictionary<System.String, List<System.Int32>>?"))
    }

    @Test
    fun `names that are alike`() {
        assertEquals(Likeness.EXACT, CSharpNameLikeness.of("order", "order"))
        assertEquals(Likeness.EXACT, CSharpNameLikeness.of("order", "_order"))
        assertEquals(Likeness.EXACT, CSharpNameLikeness.of("name", "Name"))
        assertEquals(Likeness.PARTIAL, CSharpNameLikeness.of("customerName", "Name"))
        assertEquals(Likeness.PARTIAL, CSharpNameLikeness.of("name", "customerName"))
        assertEquals(Likeness.PARTIAL, CSharpNameLikeness.of("token", "cancellationToken"))
        assertEquals(Likeness.NONE, CSharpNameLikeness.of("name", "nameless"))
        assertEquals(Likeness.NONE, CSharpNameLikeness.of("order", "count"))
        assertEquals("too short to be a part", Likeness.NONE, CSharpNameLikeness.of("id", "orderId"))
        assertEquals(Likeness.EXACT, CSharpNameLikeness.of("id", "Id"))
        assertEquals(Likeness.NONE, CSharpNameLikeness.of(null, "order"))
        assertEquals(listOf("http", "client", "factory"), CSharpNameLikeness.words("HTTPClientFactory"))
    }

    @Test
    fun `what fits the place goes up`() {
        fun order(line: String, vararg items: Pair<String, CompletionItemKind>, expected: CSharpExpected? = null, chosen: Map<String, Int> = emptyMap()): List<String> {
            val (changed, at) = typed(line)
            val found = RoslynCompletionRanking.contextOf(changed, at)
            val context = if (expected == null) found else RoslynCompletionRanking.Context(expected, found.symbols, found.line)
            return items.sortedByDescending { (name, kind) ->
                RoslynCompletionPolicy.priority(kind, false) + RoslynCompletionRanking.bonus(name, kind, context, chosen[name] ?: 0).value
            }.map { it.first }
        }
        val variable = CompletionItemKind.Variable
        val field = CompletionItemKind.Field
        val property = CompletionItemKind.Property
        val method = CompletionItemKind.Method
        val keyword = CompletionItemKind.Keyword

        assertEquals("an int is wanted: the int first, the method that returns one above a string", listOf("count", "Count", "customerName", "Total", "checked"),
            order("int amount = ", "checked" to keyword, "Total" to method, "customerName" to variable, "Count" to property, "count" to variable))
        assertEquals("a decimal is wanted: the method that gives it over the variables that do not", "Total",
            order("decimal sum = ", "customerName" to variable, "count" to variable, "Total" to method).first())
        assertEquals("named as the parameter", listOf("order", "count", "_orders"),
            order("Save(", "_orders" to field, "count" to variable, "order" to variable, expected = CSharpExpected("Order", "order")))
        assertEquals("the type of the parameter, whatever the name", listOf("customerName", "count"),
            order("Send(", "count" to variable, "customerName" to variable, expected = CSharpExpected("string", "message")))
        assertEquals("a part of the name", listOf("cancellationToken", "count"),
            order("Run(", "count" to variable, "cancellationToken" to variable, expected = CSharpExpected(null, "token")))
        assertEquals("nothing is wanted: locals over fields, as they came otherwise", listOf("count", "_orders"),
            order("", "_orders" to field, "count" to variable))
        assertEquals("chosen before", listOf("_orders", "_repository"),
            order("", "_repository" to field, "_orders" to field, chosen = mapOf("_orders" to 7)))
        assertEquals("chosen often, and still below what fits", listOf("count", "customerName"),
            order("int amount = ", "customerName" to variable, "count" to variable, chosen = mapOf("customerName" to 500)))
    }

    @Test
    fun `after a dot the names of the file say nothing`() {
        val (changed, at) = typed("int amount = order.")
        val context = RoslynCompletionRanking.contextOf(changed, at)
        assertTrue(context.symbols.isEmpty())
        assertEquals("int amount", context.expected.toString())
        // `order.Count` is not the `Count` of this class: no sign of the type, the name still counts
        assertEquals(emptySet<String>(), RoslynCompletionRanking.bonus("Count", CompletionItemKind.Property, context, 0).signals)
        assertEquals(setOf(SuggestionRules.SIGNAL_NAME), RoslynCompletionRanking.bonus("Amount", CompletionItemKind.Property, context, 0).signals)
    }

    @Test
    fun `the parameter of a signature`() {
        assertEquals("Order order", RoslynCompletionRanking.expectedOf("Order order").toString())
        assertEquals("CancellationToken cancellationToken", RoslynCompletionRanking.expectedOf("CancellationToken cancellationToken = default").toString())
        assertEquals("one of many", "object args", RoslynCompletionRanking.expectedOf("params object[] args").toString())
        assertEquals("Func<int, string> selector", RoslynCompletionRanking.expectedOf("Func<int, string> selector").toString())
        assertNull(RoslynCompletionRanking.expectedOf(""))
    }

    @Test
    fun `signs of an item`() {
        val (changed, at) = typed("int amount = ")
        val context = RoslynCompletionRanking.contextOf(changed, at)
        val count = RoslynCompletionRanking.bonus("count", CompletionItemKind.Variable, context, 3)
        assertEquals(setOf(SuggestionRules.SIGNAL_TYPE, SuggestionRules.SIGNAL_LOCAL, SuggestionRules.SIGNAL_USED), count.signals)
        val keyword = RoslynCompletionRanking.bonus("checked", CompletionItemKind.Keyword, context, 0)
        assertEquals(0.0, keyword.value, 0.0)
        assertTrue(keyword.signals.isEmpty())
        assertTrue("never more than the cap", RoslynCompletionRanking.bonus("x", CompletionItemKind.Class, context, 1_000_000).value <= RoslynCompletionRanking.USED_MAX)
    }
}
