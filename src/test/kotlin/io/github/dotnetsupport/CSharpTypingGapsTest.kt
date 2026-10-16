package io.github.dotnetsupport

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.SemanticTrace
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Gaps the trace of the harness showed in everyday code once the old inference was gone (0.1.235): each is seen through a CS0029 control
 * on the assignment to the wrong type, with no refusal in the trace.
 */
class CSharpTypingGapsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
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

    private fun check(text: String, vararg expected: String) {
        val (found, log) = SemanticTrace.collectForTests {
            myFixture.configureByText("Gap${files++}.cs", text.trimIndent())
            val document = myFixture.editor.document.text
            myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
                .sortedBy(HighlightInfo::getStartOffset)
                .map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
        }
        assertEquals(expected.toList(), found)
        assertEmpty(log)
    }

    /** `value` of a setter and of an event accessor: the member's type (nothing declares it; was not typed at all). */
    fun testValueOfAnAccessorHasTheMembersType() = check("""
        using System;
        using System.Collections.Generic;
        class C
        {
            readonly List<Action<int>> _handlers = new();
            int _x;
            public int X { get => _x; set { string bad = value; _x = value; } }
            public event Action<int> Changed { add { _handlers.Add(value); string bad2 = value; } remove { _handlers.Remove(value); } }
        }
    """, "value -> CS0029: Cannot implicitly convert type 'int' to 'string'", "value -> CS0029: Cannot implicitly convert type 'System.Action<int>' to 'string'")

    /** C# 10: a lambda with typed parameters has a natural type — `var f = (string s) => …` is a `Func<string, int>`, and `f("a")` an int. */
    fun testALambdaWithTypedParametersHasItsFunctionType() = check("""
        class C
        {
            void M()
            {
                var config = (string s) => s.Length;
                int fine = config("a");
                string bad = config("a");
            }
        }
    """, "config(\"a\") -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    /** `out _` takes any `out` parameter: `TryCreate(x, out _)` resolves and has its return type. */
    fun testAnOutDiscardFitsAnyOutParameter() = check("""
        class C
        {
            static bool TryCreate(string s, out int value) { value = 0; return true; }
            void M() { string bad = TryCreate("a", out _); }
        }
    """, "TryCreate(\"a\", out _) -> CS0029: Cannot implicitly convert type 'bool' to 'string'")

    /** `new { … }` has a type: its properties, read-only; the same names and types are one type (`new[] { new { Id = 1 } }`, a LINQ projection). */
    fun testAnAnonymousTypeHasItsProperties() = check("""
        using System.Linq;
        class C
        {
            void M()
            {
                var a = new { Id = 1, Name = "x" };
                int fine = a.Id;
                string bad = a.Id;
                var list = new[] { new { Id = 1, Name = "a" }, new { Id = 2, Name = "b" } };
                var q = list.Select(x => new { x.Id, Twice = x.Id * 2 }).ToList();
                string bad2 = q[0].Twice;
                string fine2 = list[0].Name;
                var r = Deserialize("{}", new { id = 0, name = "" });
                string bad3 = r.id;
                object o = a;
            }
            static T Deserialize<T>(string json, T prototype) => prototype;
        }
    """, "a.Id -> CS0029: Cannot implicitly convert type 'int' to 'string'", "q[0].Twice -> CS0029: Cannot implicitly convert type 'int' to 'string'",
        "r.id -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    /** The parameter of a lambda that is the expression body of another: typed by what the outer delegate returns (Blazor's `RenderFragment<T>`). */
    fun testTheParameterOfANestedLambdaIsTypedByTheOuterDelegatesReturn() = check("""
        using System;
        class C
        {
            void M()
            {
                Func<int, Func<string, int>> f = a => s => { int bad = s; return a; };
            }
        }
    """, "s -> CS0029: Cannot implicitly convert type 'string' to 'int'")

    /** A partial method's declaration and implementation are one method: the call resolves to it, with its return type. */
    fun testAPartialMethodIsOneCandidate() = check("""
        partial class C
        {
            static partial int Q(int x);
            static partial int Q(int x) => x;
            partial void P(int x);
            partial void P(int x) { }
            void M() { P(1); string bad = Q(1); }
        }
    """, "Q(1) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    /** The parts of a partial method may write their parameter types differently (`System.Int32` / `int`, a `global::` of generated code). */
    fun testThePartsOfAPartialMethodCompareByResolvedParameterTypes() = check("""
        using System.Collections.Generic;
        partial class C
        {
            static partial int Q(System.Collections.Generic.List<System.Int32> x);
            static partial int Q(List<int> x) => x.Count;
            void M() { string bad = Q(new List<int>()); }
        }
    """, "Q(new List<int>()) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    /** A `file` type (C# 11) of another file is another type: its extension method is no candidate here (the OpenAPI generator's `file static class` in every project). */
    fun testAFileTypeOfAnotherFileIsNotSeen() {
        myFixture.addFileToProject("OtherGap.cs", "file static class Ext { public static string Parse(this string s) => s; }")
        check("""
            file static class Ext { public static int Parse(this string s) => 1; }
            class C { void M() { string bad = "a".Parse(); } }
        """, "\"a\".Parse() -> CS0029: Cannot implicitly convert type 'int' to 'string'")
    }

    /** `extern alias X;` — `X::Name` is looked up as a global name. */
    fun testAnExternAliasQualifiedNameResolves() = check("""
        extern alias Lib;
        class C { void M() { Lib::System.Collections.Generic.List<int> x = new(); string bad = x; } }
    """, "x -> CS0029: Cannot implicitly convert type 'System.Collections.Generic.List<int>' to 'string'")

    /** `(_, _) => …` — discard parameters (C# 9) are parameters of the lambda; `out _` and `out var x` give the inference nothing and match any out parameter. */
    fun testDiscardsInLambdasAndOutArgumentsAreNoRefusal() = check("""
        using System;
        using System.Collections.Generic;
        using System.Threading.Tasks;
        static class E
        {
            public static int M<T>(this object services, Func<T, IServiceProviderLike, Task> seeder) where T : class => 1;
            // an overload calling its sibling with its own type parameter written (eShop's AddMigration): the caller is a candidate too
            public static int M<T>(this object services) where T : class => services.M<T>((_, _) => Task.CompletedTask);
            public static int R<K, V>(this IDictionary<K, V> d, K key, out V value) { value = default!; return 1; }
            public static int P(string s, out bool b) { b = true; return 1; }
            // a second overload: `out var flag` is compared between the two without asking its type (that asks the call: a cycle)
            public static int P(ReadOnlySpan<char> s, out bool b) { b = true; return 1; }
        }
        interface IServiceProviderLike { }
        class C
        {
            void M(IDictionary<string, int> d)
            {
                string bad = E.M<string>(this, (_, _) => Task.CompletedTask);
                string bad2 = d.R("a", out _);
                string bad3 = E.P("a", out var flag);
                string bad4 = flag;
            }
            void N(Cfg configuration)
            {
                bool.TryParse(configuration["ValidateToken"], out var validateToken);
                string bad5 = validateToken;
            }
        }
        class Cfg
        {
            public string? this[string key] => null;
        }
    """, "E.M<string>(this, (_, _) => Task.CompletedTask) -> CS0029: Cannot implicitly convert type 'int' to 'string'",
        "d.R(\"a\", out _) -> CS0029: Cannot implicitly convert type 'int' to 'string'",
        "E.P(\"a\", out var flag) -> CS0029: Cannot implicitly convert type 'int' to 'string'",
        "flag -> CS0029: Cannot implicitly convert type 'bool' to 'string'",
        "validateToken -> CS0029: Cannot implicitly convert type 'bool' to 'string'")

    /** `x is { } y` over a `DateTime?` narrows to `DateTime` (eShop's `policy.Date is { } when` — a variable named with the contextual keyword too). */
    fun testAPropertyPatternOverANullableNarrowsToTheUnderlyingType() = check("""
        using System;
        class C
        {
            DateTime? Date;
            void M() { if (Date is { } when) { string bad = when.Year; } if (Date is { Year: 1 } d) { string bad2 = d; } }
        }
    """, "when.Year -> CS0029: Cannot implicitly convert type 'int' to 'string'", "d -> CS0029: Cannot implicitly convert type 'System.DateTime' to 'string'")

    /** The returns of a lambda: their best common type (`IEnumerable<X>` over `List<X>`), `return null` typed by the others (nopCommerce's SelectManyAwait / Select). */
    fun testTheReturnsOfALambdaTakeTheirBestCommonType() = check("""
        using System.Collections.Generic;
        using System.Linq;
        class C
        {
            void M(IEnumerable<int> xs)
            {
                int bad = xs.SelectMany(x => { if (x > 0) return Enumerable.Empty<string>(); return new List<string> { "a" }; }).First();
                int bad2 = xs.Select(x => { if (x > 0) return null; return "a"; }).First();
            }
        }
    """, "xs.SelectMany(x => { if (x > 0) return Enumerable.Empty<string>(); return new List<string> { \"a\" }; }).First() -> CS0029: Cannot implicitly convert type 'string' to 'int'",
        "xs.Select(x => { if (x > 0) return null; return \"a\"; }).First() -> CS0029: Cannot implicitly convert type 'string' to 'int'")

    /** `b ? [p] : array` and `f ?? (x => …)`: a target-typed operand takes the other's type (nopCommerce's Combine, MudBlazor's drop zone). */
    fun testATargetTypedOperandOfAConditionalOrCoalesceTakesTheOthersType() = check("""
        using System;
        using System.Linq;
        class C
        {
            Func<int, bool>? Selector;
            void M(string p, bool b)
            {
                var parts = b ? [p] : p.Split('/');
                int bad = parts;
                var f = Selector ?? (x => x > 0);
                string bad2 = f;
            }
        }
    """, "parts -> CS0029: Cannot implicitly convert type 'string[]' to 'int'", "f -> CS0029: Cannot implicitly convert type 'System.Func<int, bool>' to 'string'")

    /** The members of `object` through an interface-typed receiver (`c.GetType()` over `IFormComponent`). */
    fun testObjectMembersOfAnInterfaceReceiver() = check("""
        using System;
        class C
        {
            void M(IDisposable d, IOwn o) { string bad = d.GetType(); int bad2 = d.ToString(); string bad3 = o.GetType(); }
        }
        interface IOwn { }
    """, "d.GetType() -> CS0029: Cannot implicitly convert type 'System.Type' to 'string'", "d.ToString() -> CS0029: Cannot implicitly convert type 'string' to 'int'",
        "o.GetType() -> CS0029: Cannot implicitly convert type 'System.Type' to 'string'")

    /** A method group as a value is not what it returns: its function type (C# 10) when it has one, else no type (`Task.FromResult` was a `Task<null>`). */
    fun testAMethodGroupAsAValueHasItsFunctionType() = check("""
        using System;
        using System.Threading.Tasks;
        class C
        {
            int One() => 1;
            void M()
            {
                var f = One;
                string bad = f;
                Func<Task<int>> g = () => Task.FromResult(1);
            }
        }
    """, "f -> CS0029: Cannot implicitly convert type 'System.Func<int>' to 'string'")

    /** `Should().Be(default)` — a target-typed argument is not asked for its type while the call is resolved; `return (a, b, null)` gives the inference no type. */
    fun testTargetTypedArgumentsAndTupleReturnsAreNoRefusal() = check("""
        using System;
        using System.Threading.Tasks;
        class C
        {
            static int P(int a, int b) => 1;
            static int P(string a, int b) => 2;
            static Task<T> H<T>(Func<Task<T>> f) => f();
            async Task M()
            {
                string bad = P(1, default);
                var t = await H(async () => { if (bad.Length > 0) return (false, false, null); return (true, true, "a"); });
                int bad2 = t.Item3;
            }
        }
    """, "P(1, default) -> CS0029: Cannot implicitly convert type 'int' to 'string'", "t.Item3 -> CS0029: Cannot implicitly convert type 'string' to 'int'")

    /** nopCommerce's last lines, part 1: enum arithmetic under a unary minus, a switch expression of tuple arms, `Activator.CreateInstance<T>()` with the caller's `T : new()`. */
    fun testNopCommerceGapsOne() = check("""
        using System;
        class C
        {
            void M(DayOfWeek a, DayOfWeek b, bool c)
            {
                string bad = -(a - b);
                var x = c switch { true => (a: "x", b: "y"), _ => ("p", "q") };
                int bad2 = x;
            }
            static void N<T>() where T : new() { var v = Activator.CreateInstance<T>(); int bad3 = v.ToString(); }
        }
    """, "-(a - b) -> CS0029: Cannot implicitly convert type 'int' to 'string'", "x -> CS0029: Cannot implicitly convert type '(string, string)' to 'int'",
        "v.ToString() -> CS0029: Cannot implicitly convert type 'string' to 'int'")

    /** Part 2: `list.Count(x => …)` is Enumerable's (the property is not invocable), `where TKey : notnull`, a generic overload calling its sibling with an inferred `T`. */
    fun testNopCommerceGapsTwo() = check("""
        using System;
        using System.Collections.Generic;
        using System.Linq;
        using System.Threading.Tasks;
        static class E
        {
            public static Dictionary<TKey, TSource> ToDict<TSource, TKey>(this IEnumerable<TSource> source, Func<TSource, TKey> key) where TKey : notnull => null!;
            public static Task<T> GetAsync<T>(string key, Func<Task<T>> acquire) => acquire();
            public static Task<T> GetAsync<T>(string key, Func<T> acquire) => GetAsync(key, () => Task.FromResult(acquire()));
        }
        class C
        {
            async Task M(List<string> list)
            {
                string bad = list.Count(s => s.Length > 0);
                string bad2 = list.ToDict(s => s.Length);
                string bad3 = await E.GetAsync("k", () => 1);
            }
        }
    """, "list.Count(s => s.Length > 0) -> CS0029: Cannot implicitly convert type 'int' to 'string'",
        "list.ToDict(s => s.Length) -> CS0029: Cannot implicitly convert type 'System.Collections.Generic.Dictionary<int, string>' to 'string'",
        "await E.GetAsync(\"k\", () => 1) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    /** Part 3: a generic method group (`Task.FromResult`) to a delegate, `Be(default)` between `T` and `T?`, tuple-typed parameters, a generic local function with type arguments written. */
    fun testNopCommerceGapsThree() = check("""
        using System;
        using System.Collections.Generic;
        using System.Linq;
        using System.Threading.Tasks;
        // FluentAssertions' shape: the overloads live in the base `NumericAssertions<T, TAssertions>`, `Should()` gives the derived `NumericAssertions<T>`
        class NA<T, TA> where T : struct where TA : NA<T, TA>
        {
            public TA Be(T expected, string because = "", params object[] args) => default!;
            public TA Be(T? expected, string because = "", params object[] args) => default!;
        }
        class N<T> : NA<T, N<T>> where T : struct { }
        static class SE { public static N<decimal> Should(this decimal d) => new(); }
        class Sh { public string Id; public bool Selected; }
        class Sh2 { public List<Sh> Options { get; set; } }
        class C
        {
            Task<IList<int>> GetAllAsync(Func<IEnumerable<int>, Task<IEnumerable<int>>> f) => null!;
            Task<IList<int>> GetAllAsync(Func<IEnumerable<int>, IEnumerable<int>> f) => null!;
            Task<Sh> P((string A, string B) address, (string, string) option) => null!;
            async Task M(List<string> parts)
            {
                string bad = await GetAllAsync(Task.FromResult);
                string bad2 = 1.5m.Should().Be(default);
                var option = (parts.FirstOrDefault(), parts.LastOrDefault());
                var shipping = await P(("a", "b"), option);
                int bad3 = shipping.Id;
                var s = new Sh2 { Options = new List<Sh>() };
                (s.Options.FirstOrDefault(option => string.Equals(option.Id, "x", StringComparison.InvariantCultureIgnoreCase)) ?? s.Options.First()).Selected = 1;
                async Task<Sh> get<T>() => new Sh();
                var r = parts.Count switch { 1 => await get<int>(), _ => null };
                int bad4 = r.Id;
            }
        }
    """, "await GetAllAsync(Task.FromResult) -> CS0029: Cannot implicitly convert type 'System.Collections.Generic.IList<int>' to 'string'",
        "1.5m.Should().Be(default) -> CS0029: Cannot implicitly convert type 'N<decimal>' to 'string'",
        "shipping.Id -> CS0029: Cannot implicitly convert type 'string' to 'int'", "1 -> CS0029: Cannot implicitly convert type 'int' to 'bool'",
        "r.Id -> CS0029: Cannot implicitly convert type 'string' to 'int'")

    /** A type named like an imported namespace's last part (`Shipping` of the plugin beside `using Nop.Core.Domain.Shipping;`) is the type. */
    fun testATypeNamedLikeAnImportedNamespaceIsTheType() = check("""
        namespace A.B.Shipping { class X { } }
        namespace P.Domain { class Shipping { public string Name; } }
        namespace P.Services
        {
            using A.B.Shipping;
            using P.Domain;
            class C
            {
                Shipping M() => null;
                (Shipping S, string E) N() => (null, "e");
                void O() { int bad = M().Name; var t = N(); int bad2 = t.S.Name; }
            }
        }
    """, "M().Name -> CS0029: Cannot implicitly convert type 'string' to 'int'", "t.S.Name -> CS0029: Cannot implicitly convert type 'string' to 'int'")

    /**
     * MudBlazor's last lines, part 1: a lambda whose body does not bind with a candidate's parameter types (`item.StartsWith` over `object`)
     * has no conversion to that delegate (Roslyn's `PreventsSuccessfulDelegateConversion`); a collection expression does not convert to an
     * `Expression<…>`; of the applicable candidates those of a base class drop out beside one of the derived class (§12.6.4.1).
     */
    fun testMudBlazorGapsOne() = check("""
        using System;
        using System.Collections.Generic;
        using System.Threading.Tasks;
        class Items
        {
            public int Search(Func<object, Task> f) => 1;
            public int Search(Func<string, bool> f) => 2;
            public int Contain(Func<string, bool> e) => 1;
            public int Contain(IEnumerable<string> items) => 2;
        }
        class Base { public string Be(string expected) => "base"; }
        class Derived : Base { public int Be(Guid? expected) => 1; }
        class C
        {
            void M(Items items, Derived d)
            {
                string bad = items.Search(item => item.StartsWith("x", StringComparison.Ordinal));
                string bad2 = items.Contain(["a", "b"]);
                string bad3 = d.Be(null);
            }
        }
    """, "items.Search(item => item.StartsWith(\"x\", StringComparison.Ordinal)) -> CS0029: Cannot implicitly convert type 'int' to 'string'",
        "items.Contain([\"a\", \"b\"]) -> CS0029: Cannot implicitly convert type 'int' to 'string'",
        "d.Be(null) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    /**
     * MudBlazor's last lines, part 2: a lambda whose body has a type for every candidate but a member for one of them only (`args.Value` of
     * the event args, not of the context) binds with that one alone; a generic method group with its type arguments written
     * (`Mock.Of<T>`) has the signature of that instantiation for the inference.
     */
    fun testMudBlazorGapsTwo() = check("""
        using System;
        using System.Threading.Tasks;
        class Args<T> { public T Value; }
        struct Context { public int Count; }
        class Builder
        {
            public int WithChangeHandler(Func<Args<bool>, Task> handler) => 1;
            public string WithChangeHandler(Func<Context, Task> handler) => "";
            public Task EnableAsync() => Task.CompletedTask;
        }
        static class Mocks { public static T Of<T>() where T : class => null!; public static T Of<T>(Func<T, bool> predicate) where T : class => null!; }
        static class Setup { public static int ReturnsAsync<TResult>(this object mock, Func<TResult> value) => 1; }
        class C
        {
            void M(Builder b, object mock)
            {
                string bad = b.WithChangeHandler(args => !args.Value ? b.EnableAsync() : b.EnableAsync());
                string bad2 = mock.ReturnsAsync(Mocks.Of<C>);
            }
        }
    """, "b.WithChangeHandler(args => !args.Value ? b.EnableAsync() : b.EnableAsync()) -> CS0029: Cannot implicitly convert type 'int' to 'string'",
        "mock.ReturnsAsync(Mocks.Of<C>) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    /**
     * MudBlazor's last lines, part 3: an extension on `int?` for an `int?` receiver, `value` of a setter inside its lambda, `!x` of a type
     * converting to `bool`, a collection expression against a class that is no collection.
     */
    fun testMudBlazorGapsThree() = check("""
        using System;
        using System.Collections.Generic;
        using System.Linq;
        static class E { public static int ToPx(this int val) => 1; public static int ToPx(this int? val) => 2; }
        class State { public static implicit operator bool(State s) => true; }
        class Item { }
        class C
        {
            int? MaxHeight;
            State _expanded;
            int Rows { set { Action a = () => { string bad2 = value; }; } }
            void M(IEnumerable<Item> items, Item item)
            {
                string bad = MaxHeight.ToPx();
                string bad3 = !_expanded;
                string bad4 = items.Except([item]).Count();
            }
        }
    """, "value -> CS0029: Cannot implicitly convert type 'int' to 'string'", "MaxHeight.ToPx() -> CS0029: Cannot implicitly convert type 'int' to 'string'",
        "!_expanded -> CS0029: Cannot implicitly convert type 'bool' to 'string'", "items.Except([item]).Count() -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    /** A chain of generic calls with lambdas: `From<int, string>(…).Then(s => int.Parse(s) + 1)` (MudBlazor's converter chain) types `s` by the receiver's argument. */
    fun testAChainOfGenericCallsTypesTheLambdaOfTheNextCall() = check("""
        using System;
        static class Conv { public static Chain<TIn, TOut> From<TIn, TOut>(Func<TIn, TOut> forward, Func<TOut, TIn> backward) => null!; }
        interface IConv<TA, TB> { }
        class Chain<TIn, TOut>
        {
            public Chain<TIn, TNext> Then<TNext>(IConv<TOut, TNext> next) => null!;
            public Chain<TIn, TNext> Then<TNext>(Func<TOut, TNext> next) => null!;
            public Chain<TIn, TNext> Then<TNext>(Func<TOut, TNext> forward, Func<TNext, TOut> backward) => null!;
        }
        class C
        {
            void M()
            {
                var chain = Conv
                    .From<int, string>(i => i.ToString(), s => int.Parse(s))
                    .Then(s => int.Parse(s) + 1);
                string bad = chain;
            }
        }
    """, "chain -> CS0029: Cannot implicitly convert type 'Chain<int, int>' to 'string'")

    /** `using static N.Helpers;` brings the extension methods of the type (§14.5.3): MudBlazor's `<Using Include="…StringHelpers" Static="True" />` and `x.ToStr()`. */
    fun testAUsingStaticBringsTheExtensionMethodsOfTheType() = check("""
        using static N.Helpers;
        namespace N { static class Helpers { public static int ToStr2(this double d) => 1; } }
        class C { void M(double d) { string bad = d.ToStr2(); } }
    """, "d.ToStr2() -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    /** `extern alias X;` — a parameter typed `X::Name` in a signature; the directive inside a file-scoped namespace too (MudBlazor's analyzer tests). */
    fun testAnAliasQualifiedParameterTypeInASignature() = check("""
        namespace P;
        extern alias Lib;
        class C
        {
            static int M(Lib::System.Collections.Generic.List<int> x) => 1;
            void N() { string bad = M(null); }
        }
    """, "M(null) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    /** A collection expression beside an overload taking a delegate (bUnit's `Add(x => x.Items, […])`): no inference from it there, no refusal. */
    fun testACollectionExpressionGivesNoInferenceToADelegateParameter() = check("""
        using System;
        using System.Collections.Generic;
        class E { public List<int> Items = new(); }
        static class O
        {
            public static int Add<TValue>(Func<E, TValue> selector, TValue value) => 1;
            public static int Add<TValue>(Func<E, TValue> selector, Func<TValue> value) => 2;
        }
        class C
        {
            void M() { string bad = O.Add(x => x.Items, [1, 2]); }
        }
    """, "O.Add(x => x.Items, [1, 2]) -> CS0029: Cannot implicitly convert type 'int' to 'string'")
}
