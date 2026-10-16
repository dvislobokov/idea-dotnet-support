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
