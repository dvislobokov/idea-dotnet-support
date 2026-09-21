package io.github.dotnetsupport

import io.github.dotnetsupport.lang.CSharpDeclarationInfo
import io.github.dotnetsupport.lang.CSharpDeclarations
import io.github.dotnetsupport.lang.DeclarationKind
import junit.framework.TestCase

class CSharpDeclarationsTest : TestCase() {
    private fun outline(text: String): List<String> {
        fun lines(declaration: CSharpDeclarationInfo, indent: String): List<String> =
            listOf("$indent${declaration.kind.title} ${declaration.presentation}") + declaration.children.flatMap { lines(it, "$indent  ") }
        return CSharpDeclarations.scan(text).declarations.flatMap { lines(it, "") }
    }

    private val service = """
        using System;
        using System.Collections.Generic;
        using static System.Math;

        namespace Shop.Orders
        {
            /// <summary>Orders.</summary>
            [Serializable]
            public sealed partial class OrderService<T> : IOrderService, IDisposable where T : class, new()
            {
                private const int Limit = 10;
                private readonly Dictionary<string, List<int>> _cache = new();
                private int _a, _b;
                public static readonly OrderService<T> Instance = new OrderService<T> { Name = "x" };
                public event EventHandler<OrderEventArgs> Changed;

                static OrderService() { }
                public OrderService(IRepository repository, int limit = 10) : base(repository) { }
                ~OrderService() { }

                public string Name { get; set; } = "orders";
                public int Count => _cache.Count;
                public required string Id { get; init; }
                public T this[int index] { get => default; set { } }

                [Obsolete("use TotalAsync")]
                public decimal Total(IEnumerable<OrderLine> lines, decimal discount = 0m)
                {
                    decimal Local(int x) { return x; }
                    var total = lines.Sum(line => line.Price);
                    if (discount > 0) { total -= discount; }
                    return total;
                }

                public async Task<IReadOnlyList<T>> FindAsync<TKey>(TKey key, CancellationToken token = default) where TKey : notnull => await Task.FromResult(new List<T>());
                public (int Count, decimal Sum) Summary() => (0, 0m);
                void IDisposable.Dispose() { }
                public static bool operator ==(OrderService<T> a, OrderService<T> b) => true;
                public static implicit operator string(OrderService<T> service) => service.Name;
                protected abstract void Reset();

                private enum State { New, [Description("x")] Active = 2, Closed }
                public record Line(string Sku, int Quantity);
                internal delegate void Handler(object sender, EventArgs e);
            }

            public interface IOrderService
            {
                decimal Total(IEnumerable<OrderLine> lines, decimal discount = 0m);
                string Name { get; }
            }

            public readonly record struct Money(decimal Amount, string Currency)
            {
                public override string ToString() => Amount + Currency;
            }
        }
    """.trimIndent()

    fun testTypesAndMembers() {
        assertEquals(
            listOf(
                "namespace Shop.Orders",
                "  class OrderService",
                "    field Limit: int",
                "    field _cache: Dictionary<string, List<int>>",
                "    field _a: int",
                "    field Instance: OrderService<T>",
                "    event Changed: EventHandler<OrderEventArgs>",
                "    constructor OrderService()",
                "    constructor OrderService(IRepository repository, int limit = 10)",
                "    constructor ~OrderService()",
                "    property Name: string",
                "    property Count: int",
                "    property Id: string",
                "    indexer this[int index]: T",
                "    method Total(IEnumerable<OrderLine> lines, decimal discount = 0m): decimal",
                "    method FindAsync(TKey key, CancellationToken token = default): Task<IReadOnlyList<T>>",
                "    method Summary(): (int Count, decimal Sum)",
                "    method Dispose(): void",
                "    operator operator ==(OrderService<T> a, OrderService<T> b): bool",
                "    operator operator string(OrderService<T> service)",
                "    method Reset(): void",
                "    enum State",
                "      enum member New",
                "      enum member Active",
                "      enum member Closed",
                "    record Line(string Sku, int Quantity)",
                "    delegate Handler(object sender, EventArgs e): void",
                "  interface IOrderService",
                "    method Total(IEnumerable<OrderLine> lines, decimal discount = 0m): decimal",
                "    property Name: string",
                "  record Money(decimal Amount, string Currency)",
                "    method ToString(): string",
            ),
            outline(service),
        )
    }

    fun testRangesAndPaths() {
        val structure = CSharpDeclarations.scan(service)
        assertEquals("using System;\nusing System.Collections.Generic;\nusing static System.Math;", structure.usings!!.substring(service))

        val total = structure.all().first { it.name == "Total" && it.kind == DeclarationKind.METHOD }
        // the attribute belongs to the declaration, the doc comment above the class does not
        assertTrue(total.range.substring(service).startsWith("[Obsolete(\"use TotalAsync\")]"))
        assertTrue(total.range.substring(service).endsWith("return total;\n        }"))
        assertTrue(total.body!!.substring(service).startsWith("{") && total.body!!.substring(service).endsWith("}"))
        assertEquals("Total", total.nameRange.substring(service))
        assertEquals(setOf("public"), total.modifiers)
        assertEquals("Shop.Orders.OrderService.Total", structure.qualifiedName(total))

        val type = structure.all().first { it.name == "OrderService" && it.kind == DeclarationKind.CLASS }
        assertTrue(type.range.substring(service).startsWith("[Serializable]"))
        assertEquals(setOf("public", "sealed", "partial"), type.modifiers)
        // inside a body: the members around the caret, not the local function
        val inLocal = service.indexOf("return x;")
        assertEquals(listOf("Shop.Orders", "OrderService", "Total"), structure.pathTo(inLocal).map { it.name })
    }

    fun testFileScopedNamespaceAndTopLevelProgram() {
        assertEquals(
            listOf("namespace Shop", "  class A", "    method M(): void", "  struct B", "    field X: int"),
            outline("using System;\nnamespace Shop;\n\npublic class A { void M() { } }\ninternal struct B { public int X; }\n"),
        )
        // statements of a top-level program are not declarations; the types after them are
        assertEquals(
            listOf("class Helper", "  method Run(): int"),
            outline("using var scope = Open();\nvar builder = WebApplication.CreateBuilder(args);\napp.MapGet(\"/\", () => \"hi\");\napp.Run();\n\nclass Helper { public static int Run() => 1; }\n"),
        )
        assertNull(CSharpDeclarations.scan("var x = 1;").usings)
    }

    fun testPreprocessorAndComments() {
        assertEquals(
            listOf("class A", "  method Debug(): void", "  method Release(): void", "  property P: int"),
            outline("class A\n{\n#if DEBUG\n    void Debug() { }\n#else\n    void Release() { }\n#endif\n    // void Commented() { }\n    /* class Hidden { } */\n    #region Props\n    int P { get; }\n    #endregion\n}\n"),
        )
    }

    /** Code that is being typed: fewer declarations are fine, exceptions and hangs are not. */
    fun testUnfinishedCode() {
        assertEquals(listOf("class A", "  method M(): void"), outline("class A { void M() { if (x) { "))
        assertEquals(listOf("class A"), outline("class A { public "))
        assertEquals(listOf("class A", "  field x: int"), outline("class A { int x = new Foo(1, "))
        assertEquals(emptyList<String>(), outline("}}}} ]] )) ;;"))
        assertEquals(listOf("namespace N"), outline("namespace N { class"))
        outline("class A { [Attr( void M() { } }") // an attribute that is never closed: whatever comes out, it comes out
        for (end in service.indices step 7) CSharpDeclarations.scan(service.substring(0, end)) // every prefix of a real file
    }
}
