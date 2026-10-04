package io.github.dotnetsupport

import io.github.dotnetsupport.lang.CSharpGhostText
import io.github.dotnetsupport.lang.CSharpGhostTextProvider
import io.github.dotnetsupport.lang.CSharpScopeNames
import io.github.dotnetsupport.roslyn.ArgumentSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gray text of declarations and arguments: where it is offered, and where it keeps silent. */
class GhostTextTest {
    /** The suggestion at `|` of the text. */
    private fun at(text: String, targetTypedNew: Boolean = true): String? {
        val offset = text.indexOf('|')
        return CSharpGhostText.suggest(text.removeRange(offset, offset + 1), offset, CSharpGhostText.Context(targetTypedNew))
    }

    private fun inClass(member: String) = "namespace Shop;\n\npublic class Order\n{\n$member\n}\n"

    @Test
    fun `auto property after the name of a public member`() {
        assertEquals(" { get; set; }", at(inClass("    public string Name|")))
        assertEquals("{ get; set; }", at(inClass("    public string Name |")))
        assertEquals(" { get; set; }", at(inClass("    public required List<OrderLine> Lines|")))
        assertEquals(" { get; set; }", at(inClass("    internal static int? Count|")))
        assertEquals(" { get; set; }", at(inClass("    public Dictionary<string, int[]> Totals|")))
        assertEquals(" { get; set; }", at(inClass("    protected virtual bool IsReady|")))
        assertEquals(" { get; set; }", at(inClass("    public Shop.Models.Address Address|")))
        assertEquals(" { get; set; }", at(inClass("    public Settings Settings|")))
    }

    /** Where the auto-popup of name suggestions stays away, so that the list does not hide the gray ` { get; set; }` (0.1.44). */
    @Test
    fun `the name of a property is being typed`() {
        fun awaits(member: String): Boolean {
            val text = inClass(member)
            val offset = text.indexOf('|')
            return CSharpGhostText.awaitsPropertyName(text.removeRange(offset, offset + 1), offset)
        }
        assertTrue("after the type", awaits("    public RankedOrder |"))
        assertTrue("a capital first", awaits("    public RankedOrder Ord|"))
        assertTrue(awaits("    public List<RankedOrder> Orders|"))
        assertTrue(awaits("    public string Title|"))
        assertFalse("a field or a variable is named", awaits("    public RankedOrder ord|"))
        assertFalse("private: a field", awaits("    private RankedOrder |"))
        assertFalse("the type is being typed", awaits("    public Ranked|"))
        assertFalse("modifiers only", awaits("    public static |"))
        assertFalse("a method", awaits("    public void |"))
        assertFalse("readonly: a field", awaits("    public readonly RankedOrder |"))
        assertFalse("a verb: a method", awaits("    public RankedOrder Get|"))
        assertFalse("something follows", awaits("    public RankedOrder Order| { get; set; }"))
    }

    @Test
    fun `no auto property where a method or a field is as likely`() {
        assertNull("a method", at(inClass("    public void Save|")))
        assertNull("a verb first", at(inClass("    public Order FindLatest|")))
        assertNull("a verb alone", at(inClass("    public string Format|")))
        assertNull("awaited", at(inClass("    public Task<Order> Latest|")))
        assertNull("async", at(inClass("    public async Task Load|")))
        assertNull("async name", at(inClass("    public Order LatestAsync|")))
        assertNull("a field", at(inClass("    private string _name|")))
        assertNull("a private member", at(inClass("    private string Name|")))
        assertNull("readonly is a field", at(inClass("    public readonly string Name|")))
        assertNull("a constant", at(inClass("    public const string Name|")))
        assertNull("a small letter", at(inClass("    public string name|")))
        assertNull("a type", at(inClass("    public class Inner|")))
        assertNull("no type yet", at(inClass("    public Name|")))
        assertNull("text after the caret", at(inClass("    public string Name| { get; }")))
        assertNull("the body is below", at(inClass("    public string Name|\n    {\n        get => \"\";\n    }")))
        assertNull("a local", at(inClass("    void M()\n    {\n        string Name|\n    }")))
    }

    @Test
    fun `a semicolon after a statement that is missing it`() {
        assertEquals(";", at(inClass("    void M()\n    {\n        total = 1|\n    }")))
        assertEquals(";", at(inClass("    void M()\n    {\n        Save()|\n    }")))
        assertNull("a header is not a statement", at(inClass("    void M()\n    {\n        if (ready)|\n    }")))
        assertNull("already terminated", at(inClass("    void M()\n    {\n        total = 1;|\n    }")))
        assertNull("text follows on the line", at(inClass("    void M()\n    {\n        total = 1| + 2\n    }")))
    }

    @Test
    fun `break on the first line of a switch section`() {
        fun inSwitch(section: String) = inClass("    void M(int x)\n    {\n        switch (x)\n        {\n$section\n        }\n    }")
        assertEquals("break;", at(inSwitch("            case 1:\n                |")))
        assertEquals("break;", at(inSwitch("            case 1:\n            case 2:\n                |")))
        assertEquals("break;", at(inSwitch("            default:\n                |")))
        assertEquals("a pattern label", "break;", at(inSwitch("            case > 0:\n                |")))
        // not right after a label: a statement is already there, or it is not a switch label
        assertNull(at(inSwitch("            case 1:\n                Save();\n                |")))
        assertNull(at(inClass("    void M()\n    {\n        goto end;\n    end:\n        |\n    }")))
    }

    @Test
    fun `an unimplemented body after a method header`() {
        assertEquals(" => throw new NotImplementedException();", at(inClass("    public int Parse(string s)|")))
        assertEquals(" => throw new NotImplementedException();", at(inClass("    public void Run()|")))
        assertNull("abstract declares, not implements", at(inClass("    public abstract int Parse(string s)|")))
        assertNull("a body is already there", at(inClass("    public int Parse(string s)| => 0;")))
        // a property without parentheses stays with the auto-property rule, not this one
        assertEquals(" { get; set; }", at(inClass("    public int Count|")))
    }

    @Test
    fun `new after the equals sign of a declaration`() {
        assertEquals("new();", at(inClass("    private readonly List<int> _items = |")))
        assertEquals(" new();", at(inClass("    private readonly List<int> _items =|")))
        assertEquals("new();", at(inClass("    private static readonly Dictionary<string, List<Order>> Cache = |")))
        assertEquals("new();", at(inClass("    public List<string> Tags { get; set; } = |")))
        assertEquals("new();", at(inClass("    public HashSet<string> Tags { get; } = |")))
        assertEquals("new();", at(inClass("    private readonly object _lock = |")))
        assertEquals("new();", at(inClass("    void M()\n    {\n        StringBuilder builder = |\n    }")))
        assertEquals("new(1, 1);", at(inClass("    private readonly SemaphoreSlim _gate = |")))
        assertEquals("new List<int>();", at(inClass("    private readonly IReadOnlyList<int> _items = |")))
        assertEquals("new Dictionary<string, Order>();", at(inClass("    public IDictionary<string, Order> Orders { get; } = |")))
        assertEquals("new HashSet<string>();", at(inClass("    private ISet<string> _seen = |")))
        assertEquals("new System.Collections.Generic.List<int>();", at(inClass("    private System.Collections.Generic.List<int> _items = |"), targetTypedNew = false))
        assertEquals("new List<int>();", at(inClass("    private List<int> _items = |"), targetTypedNew = false))
    }

    @Test
    fun `no new where the right side is anybody's guess`() {
        assertNull("var", at(inClass("    void M()\n    {\n        var items = |\n    }")))
        assertNull("some class", at(inClass("    private readonly Order _order = |")))
        assertNull("a number", at(inClass("    private int _count = |")))
        assertNull("a string", at(inClass("    private string _name = |")))
        assertNull("nullable", at(inClass("    private List<int>? _items = |")))
        assertNull("an array", at(inClass("    private int[] _items = |")))
        assertNull("an assignment", at(inClass("    void M()\n    {\n        items = |\n    }")))
        assertNull("not generic", at(inClass("    private IEnumerable _items = |")))
        assertNull("text after the caret", at(inClass("    private List<int> _items = | new();")))
    }

    @Test
    fun `assignment of a parameter in a constructor`() {
        fun constructor(members: String, parameters: String, body: String, initializer: String = "") =
            "public class Order\n{\n$members\n    public Order($parameters)$initializer\n    {\n$body\n    }\n}\n"

        assertEquals("_name = name;", at(constructor("    private readonly string _name;", "string name", "        |")))
        assertEquals("_age = age;", at(constructor("    private readonly string _name;\n    private int _age;", "string name, int age = 0", "        _name = name;\n        |")))
        assertEquals("this.name = name;", at(constructor("    private string name;", "string name", "        |")))
        assertEquals("Name = name;", at(constructor("    public string Name { get; }", "string name", "        |")))
        assertEquals("_logger = logger;", at(constructor("    private readonly ILogger<Order> _logger;", "[FromServices] ILogger<Order> logger", "        |")))
        assertEquals("the first is passed to the base", "_age = age;", at(constructor("    private string _name;\n    private int _age;", "string name, int age", "        |", " : base(name)")))
        assertEquals("the one without a member is skipped", "_age = age;", at(constructor("    private int _age;", "string name, int age", "        |")))

        assertNull("all are used", at(constructor("    private string _name;", "string name", "        _name = name ?? throw new ArgumentNullException(nameof(name));\n        |")))
        assertNull("no member for it", at(constructor("    private string _title;", "string name", "        |")))
        assertNull("no parameters", at(constructor("    private string _name;", "", "        |")))
        assertNull("inside a block", at(constructor("    private string _name;", "string name", "        if (true)\n        {\n            |\n        }")))
        assertNull("not an empty line", at(constructor("    private string _name;", "string name", "        var x = 1;|")))
        assertNull("a method", at("public class Order\n{\n    private string _name;\n    public void Rename(string name)\n    {\n        |\n    }\n}\n"))
        assertNull("a constant is no target", at(constructor("    private const string Name = \"\";", "string name", "        |")))
    }

    @Test
    fun `names in scope`() {
        val text = """
            public class Service
            {
                private readonly IRepository _repository;
                public string Name { get; set; }

                public async Task Handle(Order order, CancellationToken cancellationToken = default)
                {
                    var total = order.Total;
                    int count = 0, other;
                    foreach (var line in order.Lines) { }
                    if (order is Paid paid && dictionary.TryGetValue(key, out var found)) { }
                    Call(total);
                    |
                }

                public void Other(string unseen) { var alsoUnseen = 1; }
            }
        """.trimIndent()
        val offset = text.indexOf('|')
        val names = CSharpScopeNames.visibleAt(text.removeRange(offset, offset + 1), offset)
        for (name in listOf("order", "cancellationToken", "_repository", "Name", "total", "count", "line", "paid", "found")) assertTrue(name, name in names)
        for (name in listOf("unseen", "alsoUnseen", "Call", "Total", "Lines", "key")) assertFalse(name, name in names)
        assertEquals(listOf("name", "order", "count", "rest", "handler"), CSharpScopeNames.parameterNames("(string name, [FromBody] Order order, int count = 0, params int[] rest, Func<int, string> handler)"))
    }

    @Test
    fun `arguments named as the parameters`() {
        val visible = setOf("order", "cancellationToken", "_logger", "total")
        fun arguments(active: Int, vararg declared: String) = ArgumentSuggestions.forParameters(declared.toList(), active, visible)

        assertEquals("order, cancellationToken", arguments(0, "Order order", "CancellationToken cancellationToken = default"))
        assertEquals("cancellationToken", arguments(1, "Order order", "CancellationToken cancellationToken"))
        assertEquals("a field with an underscore", "_logger", arguments(0, "ILogger logger"))
        assertEquals("up to the first that is not at hand", "order", arguments(0, "Order order", "string comment", "CancellationToken cancellationToken"))
        assertNull("the one at the caret is not at hand", arguments(0, "string comment", "Order order"))
        assertNull("out wants more than a name", arguments(0, "out Order order"))
        assertEquals("order", arguments(0, "Order order", "params object[] total"))
        assertNull("past the end", arguments(2, "Order order", "CancellationToken cancellationToken"))
    }

    private fun inFile(text: String, fileName: String? = null, namespace: String? = null, fileScoped: Boolean = true): String? {
        val offset = text.indexOf('|')
        val context = object : CSharpGhostText.Context(true, fileName) {
            override val namespace: String? get() = namespace
            override val fileScopedNamespace: Boolean get() = fileScoped
        }
        return CSharpGhostText.suggest(text.removeRange(offset, offset + 1), offset, context)
    }

    @Test
    fun `namespace of the folder`() {
        assertEquals("Shop.Models;", inFile("using System;\n\nnamespace |", namespace = "Shop.Models"))
        assertEquals(" Shop.Models;", inFile("namespace|", namespace = "Shop.Models"))
        assertEquals("p.Models;", inFile("namespace Sho|", namespace = "Shop.Models"))
        assertEquals("Models;", inFile("namespace Shop.|\n", namespace = "Shop.Models"))
        assertEquals("block scoped by .editorconfig", "Shop.Models", inFile("namespace |", namespace = "Shop.Models", fileScoped = false))

        assertNull("no project", inFile("namespace |"))
        assertNull("another name is being typed", inFile("namespace Other|", namespace = "Shop.Models"))
        assertNull("typed in full", inFile("namespace Shop.Models|", namespace = "Shop.Models"))
        assertNull("the file has one", inFile("namespace Shop;\n\nnamespace |", namespace = "Shop.Models"))
        assertNull("not a declaration", inFile("    namespace |", namespace = "Shop.Models"))
        assertNull("text after the caret", inFile("namespace | Shop;", namespace = "Shop.Models"))
    }

    @Test
    fun `type named after the file`() {
        assertEquals("Order", inFile("namespace Shop;\n\npublic class |", "Order"))
        assertEquals(" Order", inFile("namespace Shop;\n\npublic sealed class|", "Order"))
        assertEquals("der", inFile("namespace Shop;\n\npublic record Or|", "Order"))
        assertEquals("Order", inFile("internal readonly record struct |", "Order"))
        assertEquals("Order", inFile("namespace Shop\n{\n    public partial class |\n}\n", "Order.Part"))
        assertEquals("IOrderService", inFile("public interface |", "IOrderService"))
        assertEquals("Status", inFile("public enum |", "Status"))

        assertNull("no file name", inFile("public class |"))
        assertNull("the file has a type", inFile("public class Order { }\n\npublic class |", "Order"))
        assertNull("another name is being typed", inFile("public class Line|", "Order"))
        assertNull("typed in full", inFile("public class Order|", "Order"))
        assertNull("a class in the file of an interface", inFile("public class |", "IOrderService"))
        assertNull("an interface in the file of a class", inFile("public interface |", "Order"))
        assertNull("not an identifier", inFile("public class |", "order-utils"))
        assertNull("glued to the keyword", inFile("public classOr|", "classOrder"))
    }

    @Test
    fun `logger of the class`() {
        fun inService(member: String, fields: String = "") = "namespace Shop;\n\npublic class OrderService\n{\n$fields$member\n}\n"

        assertEquals("OrderService> _logger;", at(inService("    private readonly ILogger<|")))
        assertEquals("OrderService> _logger;", at(inService("    private readonly ILogger<|", "    private readonly IRepository _repository;\n")))
        assertEquals("the fields of the class have no underscore", "OrderService> logger;", at(inService("    private readonly ILogger<|", "    private readonly IRepository repository;\n")))
        assertEquals("the closing bracket is there", "OrderService", at(inService("    private readonly ILogger<|>")))
        assertEquals("the field is there", "OrderService>", at(inService("    private readonly ILogger<|", "    private int _logger;\n")))
        assertEquals("OrderService> logger", at(inService("    public OrderService(ILogger<|")))
        assertEquals("OrderService> logger", at(inService("    public OrderService(IRepository repository, ILogger<|")))

        assertNull("another type", at(inService("    private readonly MyILogger<|")))
        assertNull("no modifiers: a statement", at(inService("    void M()\n    {\n        ILogger<|\n    }")))
        assertNull("a static class", at("public static class Extensions\n{\n    private static readonly ILogger<|\n}\n"))
        assertNull("outside a type", at("private readonly ILogger<|"))
    }

    @Test
    fun `parameters of a constructor for the readonly members`() {
        fun inOrder(members: String, line: String) = "public class Order\n{\n$members\n$line\n}\n"
        val fields = "    private readonly string _name;\n    private readonly int m_count;\n    private int _changing;\n    private readonly List<int> _items = new();\n" +
            "    private static readonly object Lock = new();\n    public Guid Id { get; }\n    public string Title { get; set; }\n    public DateTime Created { get; } = DateTime.Now;\n" +
            "    private readonly Dictionary<string, int> _event;\n"

        assertEquals("string name, int count, Guid id, Dictionary<string, int> @event)", at(inOrder(fields, "    public Order(|")))
        assertEquals("the closing parenthesis is there", "string name, int count, Guid id, Dictionary<string, int> @event", at(inOrder(fields, "    public Order(|)")))
        assertEquals("string name)", at(inOrder("    private readonly string _name;\n\n    public Order()\n    {\n    }\n", "    protected internal Order(|")))

        assertNull("nothing to take", at(inOrder("    private int _count;", "    public Order(|")))
        assertNull("a method", at(inOrder(fields, "    public Other(|")))
        assertNull("a constructor with parameters is there", at(inOrder("$fields\n    public Order(string name)\n    {\n        _name = name;\n    }\n", "    public Order(|")))
        assertNull("text after the caret", at(inOrder(fields, "    public Order(|string name)")))
        assertEquals("name", CSharpGhostText.parameterName("_name"))
        assertEquals("orderId", CSharpGhostText.parameterName("OrderId"))
        assertEquals("@class", CSharpGhostText.parameterName("_class"))
    }

    @Test
    fun `catch clause`() {
        fun inMethod(body: String) = "class A\n{\n    void M()\n    {\n$body\n    }\n}\n"

        assertEquals(" (Exception e)", at(inMethod("        try\n        {\n        }\n        catch|")))
        assertEquals("(Exception e)", at(inMethod("        try { } catch |")))
        assertEquals("(Exception e)", at(inMethod("        try\n        {\n        }\n        catch |")))
        assertEquals("the file says ex", "(Exception ex)", at(inMethod("        try { } catch (IOException ex) { }\n        try { } catch (System.Exception ex) { }\n        try { } catch |")))

        assertNull("the body is there: a bare catch", at(inMethod("        try { }\n        catch|\n        {\n        }")))
        assertNull("not the keyword", at(inMethod("        var x = mycatch|")))
        assertNull("a comment", at(inMethod("        // nothing to catch|")))
        assertNull("a string", at(inMethod("        var x = \"try catch|")))
        assertNull("text after the caret", at(inMethod("        try { } catch | (Exception e)")))
    }

    private val service = """
        public class OrderService
        {
            private readonly List<Order> _orders = new();
            private readonly string _title = "x";
            public int Count => _orders.Count;
            public string Name { get; set; }

            public decimal Total(Order order) => 0;
            private void Save(Order order, CancellationToken cancellationToken) { }
            private void Save2(Order order) { }
            private void Save2(Order order, int priority) { }
            private void Run(CancellationToken token) { }
            private double Ratio(Missing missing) => 0;
            private bool IsReady() => true;
            private float Scale(int count, string unit = "px") => 0;

            public async Task<Order> Handle(Order order, string customerName, CancellationToken cancellationToken)
            {
                int count = 0;
                §
            }
        }
    """.trimIndent()

    /** The gray text of [line] typed in the method of the service, `|` where the caret is. */
    private fun inService(line: String): String? = at(service.replace("§", line))

    @Test
    fun `the value that is wanted`() {
        assertEquals("the local over the property of the same type", "count;", inService("int amount = |"))
        assertEquals("the rest of the name that is typed", "unt;", inService("int amount = co|"))
        assertEquals("order;", inService("Order copy = |"))
        assertEquals("what an async method returns", "order;", inService("return |"))
        assertEquals("by the name where the type is not written: the one called exactly so", "Name;", inService("var name = |"))
        assertEquals("a part of the name", "customerName;", inService("var customer = |".replace("customer =", "theCustomerName =")))
        assertEquals("inside parentheses the statement goes on", "count", inService("for (int i = |"))
        assertEquals("cancellationToken;", inService("CancellationToken token = |"))

        assertEquals("the one local string over the field and the property", "customerName;", inService("string label = |"))
        assertNull("two local strings, neither is named so", inService("var text = \"x\"; string label = |"))
        assertEquals("a method that gives the type, with what it takes", "Total(order);", inService("decimal sum = |"))
        assertEquals("tal(order);", inService("decimal sum = To|"))
        assertEquals("no arguments to find", "IsReady();", inService("bool ready = |"))
        assertEquals("the optional ones are left out", "Scale(count);", inService("float scale = |"))
        assertNull("a method whose argument is not at hand is not offered", inService("double ratio = |"))
        assertNull("nothing of the type", inService("long big = |"))
        assertNull("no type and no name to go by", inService("var x = |"))
        assertNull("not what is typed", inService("int amount = zz|"))
        assertNull("typed in full", inService("int amount = count|"))
        assertNull("text after the caret", inService("int amount = | + 1;"))
        assertNull("after a dot", inService("int amount = order.|"))
        assertNull("a comparison", inService("if (count == |"))
        assertEquals("never itself: the property of the same name and type", "Count;", inService("count = |"))
        assertEquals("a string whose name is a part of this one", "Name;", inService("customerName = |"))
    }

    @Test
    fun `the call of a method of this file`() {
        fun call(line: String): String? {
            val text = service.replace("§", line)
            val offset = text.indexOf('|')
            return io.github.dotnetsupport.lang.CSharpLocalCalls.at(text.removeRange(offset, offset + 1), offset)?.let { "${it.name} ${it.parameters} ${it.active}" }
        }
        assertEquals("Save [Order order, CancellationToken cancellationToken] 0", call("Save(|"))
        assertEquals("the closing one is there", "Save [Order order, CancellationToken cancellationToken] 0", call("Save(|)"))
        assertEquals("Save [Order order, CancellationToken cancellationToken] 1", call("Save(order, |)"))
        assertEquals("a call inside is one argument", "Save [Order order, CancellationToken cancellationToken] 1", call("Save(Make(1, 2), |)"))
        assertEquals("Save [Order order, CancellationToken cancellationToken] 0", call("this.Save(|"))
        assertEquals("Total [Order order] 0", call("var sum = Total(|"))
        assertNull("overloads: the server knows which", call("Save2(|"))
        assertNull("a method of something else", call("repository.Save(|"))
        assertNull("not declared here", call("Console.WriteLine(|"))
        assertNull("no call", call("int x = |"))
        assertNull("the statement before", call("Save(order, cancellationToken); |"))

        // and what is offered for it
        val visible = setOf("order", "cancellationToken", "count")
        assertEquals("order, cancellationToken", ArgumentSuggestions.forParameters(listOf("Order order", "CancellationToken cancellationToken"), 0, visible))
    }

    @Test
    fun `arguments by the type where the name is another`() {
        val text = service.replace("§", "|")
        val offset = text.indexOf('|')
        val symbols = io.github.dotnetsupport.lang.CSharpScopeTypes.at(text.removeRange(offset, offset + 1), offset)
        fun arguments(active: Int, vararg declared: String) = io.github.dotnetsupport.lang.CSharpArguments.list(declared.toList(), active, symbols)

        assertEquals("the parameter is called `token`: the one CancellationToken at hand", "cancellationToken", arguments(0, "CancellationToken token"))
        assertEquals("order, cancellationToken", arguments(0, "Order order", "CancellationToken cancellationToken"))
        assertEquals("the one Order, whatever the parameter is called", "order", arguments(0, "Order item"))
        assertNull("two ints, the local and the property, and neither is called so", arguments(0, "int number"))
        assertEquals("the one of them called so", "count", arguments(0, "int count"))
        assertEquals("by the name among several of the type: the whole name first", "Name", arguments(0, "string name"))
        assertNull("two end as the parameter does: `Name` and `customerName`", arguments(0, "string theCustomerName"))
        assertEquals("a field with an underscore", "_title", arguments(0, "string title"))
        assertNull("several strings, none is called so", arguments(0, "string label"))
        assertNull("nothing of the type", arguments(0, "Guid id"))
        assertNull("a method is not an argument", arguments(0, "decimal total"))
        assertEquals("the row ends where nothing is found", "order", arguments(0, "Order order", "Guid id", "CancellationToken token"))
        assertNull(arguments(0, "out Order order"))
    }

    @Test
    fun `target typed new needs a modern framework`() {
        assertTrue(CSharpGhostTextProvider.hasTargetTypedNew(listOf("net8.0")))
        assertTrue(CSharpGhostTextProvider.hasTargetTypedNew(listOf("net10.0-windows", "net8.0")))
        assertTrue("nothing known", CSharpGhostTextProvider.hasTargetTypedNew(emptyList()))
        assertFalse(CSharpGhostTextProvider.hasTargetTypedNew(listOf("net48")))
        assertFalse(CSharpGhostTextProvider.hasTargetTypedNew(listOf("netstandard2.0", "net8.0")))
        assertFalse(CSharpGhostTextProvider.hasTargetTypedNew(listOf("netcoreapp3.1")))
    }
}
