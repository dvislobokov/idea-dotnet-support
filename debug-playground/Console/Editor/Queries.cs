using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Linq.Expressions;
using Playground.Editor.QueryRequests;

namespace Playground.Editor;

/// <summary>
/// Live check of LINQ queries on the plugin's own semantics (0.1.80, C# §12.20.3): a query expression is the method calls it translates to,
/// resolved against the real type of its source — `Queryable` for an `IQueryable&lt;T&gt;` (an in-memory <c>AsQueryable()</c> and an EF Core-like
/// <see cref="QueryDbSet{TEntity}"/>), the own query methods of <see cref="QueryBox{T}"/> and <see cref="QueryMaybeQueries"/>. Hover (Ctrl+Q) a range
/// variable or a `var`, type after a dot inside a query; type on the empty line under a marker and undo with Ctrl+Z. The file compiles as it is.
/// </summary>
public static class Queries
{
    public sealed record Order(int Id, string Name, decimal Total, int CustomerId);

    public sealed record Customer(int Id, string Name);

    public static void Run()
    {
        var orders = new List<Order> { new(1, "Tea", 3.5m, 1), new(2, "Cake", 7m, 2) }.AsQueryable();
        var shop = new QueryShop();

        // TYPE:queries-queryable — Ctrl+Q on `expensive` and on `o` of `select o.Name`. EXPECT: `IQueryable<string> expensive` (not
        // IEnumerable: Queryable.Where / Select win over Enumerable for an IQueryable), `(range variable) Order o`. Type ` && o.` right after
        // `where o.Total > 1` → the list has `Total`, `Name`, `CustomerId`.
        var expensive = from o in orders
                        where o.Total > 1
                        select o.Name;

        // TYPE:queries-dbset — Ctrl+Q on `names` and on `c`. EXPECT: `IOrderedQueryable<Customer> names` (`orderby` without another select is
        // OrderBy itself), `(range variable) Customer c`. Typing `c.Nmae` in the `where` gives a red CS1061 on `Nmae` (undo it), `c.Name` none.
        var names = from c in shop.Customers
                    where c.Name != ""
                    orderby c.Name descending
                    select c;

        // TYPE:queries-method-syntax — Ctrl+Q on `ids` and on the lambda parameter `c`. EXPECT: `IQueryable<int> ids`, `Customer c`: the lambdas
        // go to `Expression<Func<Customer, …>>` of Queryable. Type `c.` inside `Where(c => …)` → `Id`, `Name`.
        var ids = shop.Customers.Where(c => c.Id > 0).Select(c => c.Id);

        // TYPE:queries-join — Ctrl+Q on `c`, `bought`, `pairs`. EXPECT: `(range variable) Customer c`, `(range variable) IEnumerable<Order>
        // bought` (join … into is GroupJoin), `IQueryable<string> pairs`; `total` is `decimal`.
        var pairs = from c in shop.Customers
                    join o in shop.Orders on c.Id equals o.CustomerId into bought
                    let total = bought.Sum(x => x.Total)
                    where total > 0
                    select c.Name + total;

        // TYPE:queries-own-source — Ctrl+Q on `boxed`, `x`, `maybe`. EXPECT: `QueryBox<string> boxed` (QueryBox's own instance Select / Where, QueryBox is
        // no IEnumerable), `(range variable) int x`, `QueryMaybe<int> maybe` (the extension Select of QueryMaybeQueries); no red marks.
        var boxed = from x in new QueryBox<int>(3) where x > 1 select x.ToString();
        var maybe = from s in new QueryMaybe<string>("text") select s.Length;

        // TYPE:queries-group — Ctrl+Q on `g` and on `groups`. EXPECT: `(range variable) IGrouping<int, Order> g`,
        // `IQueryable<int> groups`; after `g.` the list has `Key` and the extension methods (`Count`, `Sum`).
        var groups = from o in orders
                     group o by o.CustomerId into g
                     select g.Key;

        // TYPE:queries-inherited-member — the shape of an EF Core handler (E-190): `BookDate` and `CalculationTypeId` are declared in the abstract
        // base `QueryStressTest` of the entity (QueryEntities.cs), and this file imports a DTO named `QueryStressTest` too (`using …QueryRequests`).
        // EXPECT: no red CS1061 on `BookDate` / `CalculationTypeId`; Ctrl+Q on `stress` → `List<int> stress`; `x.` inside `Where` → `BookDate`,
        // `CalculationTypeId`, `Comment`, `Id` (not the DTO's `Title`). Typing `x.Nope` in the `Select` gives a red CS1061 (undo it).
        var context = new Playground.Editor.QueryEntities.QueryStressContext();
        var stress = context
            .DirectStressTests
            .Where(x => x.BookDate == new DateOnly(2002, 01, 01))
            .Select(x => x.CalculationTypeId)
            .ToList();
        var request = new QueryStressTest { Title = "dto" };

        Console.WriteLine($"{expensive.Count()} {names.Count()} {ids.Count()} {pairs.Count()} {boxed.Value} {maybe.Value} {groups.Count()} {stress.Count} {request.Title}");
    }
}

/// <summary>As EF Core's DbContext: sets of entities that are queried, here over lists in memory.</summary>
public sealed class QueryShop
{
    public QueryDbSet<Queries.Customer> Customers { get; } = new([new(1, "Ann"), new(2, "Bob")]);
    public QueryDbSet<Queries.Order> Orders { get; } = new([new(1, "Tea", 3.5m, 1), new(2, "Cake", 7m, 2)]);
}

/// <summary>As EF Core's DbSet&lt;TEntity&gt;: an <see cref="IQueryable{T}"/> of its own, the provider of an in-memory list behind it.</summary>
public sealed class QueryDbSet<TEntity>(IEnumerable<TEntity> items) : IQueryable<TEntity> where TEntity : class
{
    private readonly IQueryable<TEntity> _items = items.AsQueryable();

    public Type ElementType => _items.ElementType;
    public Expression Expression => _items.Expression;
    public IQueryProvider Provider => _items.Provider;
    public IEnumerator<TEntity> GetEnumerator() => _items.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}

/// <summary>A source of its own: the query pattern by instance methods, no IEnumerable.</summary>
public sealed class QueryBox<T>(T value)
{
    public T Value { get; } = value;
    public QueryBox<TResult> Select<TResult>(Func<T, TResult> selector) => new(selector(Value));
    public QueryBox<T> Where(Func<T, bool> predicate) => this;
}

public sealed class QueryMaybe<T>(T value)
{
    public T Value { get; } = value;
}

/// <summary>The query pattern by extension methods.</summary>
public static class QueryMaybeQueries
{
    public static QueryMaybe<TResult> Select<T, TResult>(this QueryMaybe<T> source, Func<T, TResult> selector) => new(selector(source.Value));
}
