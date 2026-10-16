package io.github.dotnetsupport

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Roslyn's method type inference, ported (CSHARP_PSI_MIGRATION.md, D6b): what the strict inference of the compiler errors did not model
 * before (upper bounds, `T?` with a lower bound, interfaces a class implements, dependencies) gives no error where Roslyn gives none, and
 * a sure failure is still CS0411. The inferred type is seen through an assignment that converts only from the right one.
 */
class RoslynMethodTypeInferenceTest : BasePlatformTestCase() {
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

    /** The errors of [text] as `line: text under it -> CSxxxx: message`. */
    private fun errors(text: String): List<String> {
        myFixture.configureByText("Inference${files++}.cs", text.trimIndent())
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
            .sortedBy(HighlightInfo::getStartOffset)
            .map { "${document.substring(0, it.startOffset).count { c -> c == '\n' } + 1}: " + document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    /**
     * bUnit's `parameters.Add(x => x.Severity, …)` (MudBlazor's tests, D6e3): the lambda's parameter is typed by the receiver's type argument
     * only through the call being inferred — Roslyn binds the body for the candidate; the port did not (the log: "lambda parameter `x` is ?
     * to the resolver, MudBlazor.MudAlert to the inference", 3391 distinct lines), and the type of the call was the simple inference's.
     */
    fun testLambdaBodyBoundWithTheCandidatesParameterTypes() {
        val (found, log) = io.github.dotnetsupport.lang.semantic.SemanticTrace.collectForTests {
            errors("""
                using System;
                class Alert { public int Severity; public string Text = ""; }
                class Builder<TComponent>
                {
                    public Builder<TComponent> Add<TValue>(Func<TComponent, TValue> selector, TValue value) => this;
                    public TValue Get<TValue>(Func<TComponent, TValue> selector) => default!;
                }
                class C
                {
                    void M()
                    {
                        var b = new Builder<Alert>().Add(x => x.Severity, 1).Add(x => x.Text, "a");
                        int fine = new Builder<Alert>().Get(x => x.Severity);
                        string wrong = new Builder<Alert>().Get(x => x.Severity);
                        var nope = new Builder<Alert>().Get(x => x.Nope);
                    }
                }
            """)
        }
        assertEquals(listOf(
            "14: new Builder<Alert>().Get(x => x.Severity) -> CS0029: Cannot implicitly convert type 'int' to 'string'",
            "15: Nope -> CS1061: 'Alert' does not contain a definition for 'Nope' and no accessible extension method 'Nope' accepting a first argument of type 'Alert' could be found (are you missing a using directive or an assembly reference?)",
        ), found)
        // the calls with a body of a known type are inferred by Roslyn's inference, without a refusal
        assertEmpty(log.filter { it.startsWith("inference") && ("Inference${files - 1}.cs:12" in it || "Inference${files - 1}.cs:13" in it || "Inference${files - 1}.cs:14" in it) })
    }

    /**
     * A colleague's case on 0.1.185: `Task.Run(() => StartTask(…), token)` with a lambda returning `Task<R>` was taken for
     * `Run<Task<R>>(Func<Task<R>>)` — a false CS0029 «Task<Task<R>> to Task<R>»; Roslyn picks `Run<R>(Func<Task<R>>, CancellationToken)`.
     */
    fun testTaskRunOfALambdaReturningATask() {
        assertEquals(listOf("13: Task.Run(() => \"x\") -> CS0029: Cannot implicitly convert type 'System.Threading.Tasks.Task<string>' to 'System.Threading.Tasks.Task<R>'"), errors("""
            using System.Collections.Generic;
            using System.Threading;
            using System.Threading.Tasks;
            class R { }
            class C
            {
                Task<R> Task1 = null!;
                Dictionary<int, CancellationTokenSource> sources = new();
                Task<R> Start(int a, CancellationToken token) => Task.FromResult(new R());
                void M(int id)
                {
                    Task1 = Task.Run(() => Start(1, sources[id].Token), sources[id].Token);
                    Task1 = Task.Run(() => "x");
                    Task1 = Task.Run(() => Start(1, sources[id].Token));
                    Task1 = Task.Run(async () => await Start(1, sources[id].Token));
                    Task1 = Task.Run(() => { return Start(1, sources[id].Token); }, sources[id].Token);
                }
            }
        """))
    }

    /**
     * eShop's `ToDictionary(k => k.Id, v => v)`: against the overload with an `IEqualityComparer<TKey>` the implicitly typed lambda has no
     * type and gives no inference, as in Roslyn — the port refused there (851 lines of the four solutions, D6e3).
     */
    fun testAnImplicitlyTypedLambdaToANonDelegateGivesNoInference() {
        val (found, log) = io.github.dotnetsupport.lang.semantic.SemanticTrace.collectForTests {
            errors("""
                using System.Collections.Generic;
                using System.Linq;
                class Item { public int Id; }
                class C
                {
                    void M(List<Item> items)
                    {
                        Dictionary<int, Item> fine = items.ToDictionary(k => k.Id, v => v);
                        string wrong = items.ToDictionary(k => k.Id, v => v);
                    }
                }
            """)
        }
        assertEquals(listOf("9: items.ToDictionary(k => k.Id, v => v) -> CS0029: Cannot implicitly convert type " +
            "'System.Collections.Generic.Dictionary<int, Item>' to 'string'"), found)
        assertEmpty(log.filter { it.startsWith("inference ToDictionary") })
    }

    /**
     * A target-typed `new()` (and a `throw`, a conditional without a natural type) has no type: Roslyn makes no inference from it and
     * infers from the other arguments — the port refused the whole call (bUnit's `Add(p => p.Items, new())`, D6e3).
     */
    fun testATargetTypedNewGivesNoInference() {
        val (found, log) = io.github.dotnetsupport.lang.semantic.SemanticTrace.collectForTests {
            errors("""
                class R { }
                class C
                {
                    static T Pick<T>(T a, T b) => a;
                    void M(bool flag)
                    {
                        R fine = Pick(new R(), new());
                        R both = Pick(new R(), flag ? null : new());
                        string wrong = Pick(new R(), new());
                    }
                }
            """)
        }
        assertEquals(listOf("9: Pick(new R(), new()) -> CS0029: Cannot implicitly convert type 'R' to 'string'"), found)
        assertEmpty(log.filter { it.startsWith("inference Pick") })
    }

    /**
     * A collection expression bounds the type parameter through the element type of its target (`MakeCollectionExpressionTypeInferences`):
     * its elements and the element types of its spreads; an empty one infers nothing (CS0411 alone). It was a refusal (D6e3, 492 lines).
     */
    fun testACollectionExpressionInfersFromItsElements() {
        val (found, log) = io.github.dotnetsupport.lang.semantic.SemanticTrace.collectForTests {
            errors("""
                using System.Collections.Generic;
                class C
                {
                    static List<T> Make<T>(IEnumerable<T> items) => new();
                    static T First<T>(T[] items) => items[0];
                    void M(List<int> more)
                    {
                        List<int> a = Make([1, 2]);
                        int b = First([1, 2, ..more]);
                        string c = First([1, 2]);
                        var d = Make([]);
                    }
                }
            """)
        }
        assertEquals(listOf(
            "10: First([1, 2]) -> CS0029: Cannot implicitly convert type 'int' to 'string'",
            "11: Make -> CS0411: The type arguments for method 'C.Make<T>(IEnumerable<T>)' cannot be inferred from the usage. Try specifying the type arguments explicitly.",
        ), found)
        assertEmpty(log.filter { it.startsWith("inference") })
    }

    /**
     * An interpolated string converts to a handler type, a constant one too (Roslyn's `GetInterpolatedStringConversion`): the overloads of
     * `StringBuilder.AppendLine($"…")` were "not known" (222 lines of the four solutions, D6c2); a non-constant one prefers the handler.
     */
    fun testAnInterpolatedStringConvertsToAHandler() {
        val (found, log) = io.github.dotnetsupport.lang.semantic.SemanticTrace.collectForTests {
            errors("""
                using System.Text;
                class C
                {
                    string ToS(int x) => "";
                    void M(StringBuilder sb, int x)
                    {
                        sb.AppendLine($"M {ToS(x)} {x}");
                        sb.Append($"{x}");
                        int bad = sb.AppendLine($"{x}");
                    }
                }
            """)
        }
        assertEquals(listOf("9: sb.AppendLine(\$\"{x}\") -> CS0029: Cannot implicitly convert type 'System.Text.StringBuilder' to 'int'"), found)
        assertEmpty(log.filter { it.startsWith("applicable Append") || it.startsWith("resolve Append") })
    }

    /** An anonymous object converts to `object` and to no class: nopCommerce's `RedirectToRoute(name, new { id })` was not known (1366 calls). */
    fun testAnAnonymousObjectGoesToTheObjectOverload() {
        val (found, log) = io.github.dotnetsupport.lang.semantic.SemanticTrace.collectForTests {
            errors("""
                class P { }
                class C
                {
                    static int Go(string name, object values) => 1;
                    static string Go(string name, P p) => "";
                    void M(int id)
                    {
                        int fine = Go("x", new { id });
                        string bad = Go("x", new { id, name = "n" });
                    }
                }
            """)
        }
        assertEquals(listOf("9: Go(\"x\", new { id, name = \"n\" }) -> CS0029: Cannot implicitly convert type 'int' to 'string'"), found)
        assertEmpty(log.filter { it.startsWith("applicable Go") || it.startsWith("resolve Go") })
    }

    /**
     * A pointer parameter takes `null`, `default` or a pointer only: beside an `object?` overload a `void*` one does not apply to an object —
     * `ArgumentNullException.ThrowIfNull(x)` was "the type of parameter 1 is not known" in 1354 calls of nopCommerce (D6c2).
     */
    fun testAPointerOverloadDoesNotApplyToAnObject() {
        val (found, log) = io.github.dotnetsupport.lang.semantic.SemanticTrace.collectForTests {
            errors("""
                using System;
                unsafe class Lib
                {
                    public static int Go(object? x) => 1;
                    public static string Go(void* x) => "";
                }
                class C
                {
                    void M(object o)
                    {
                        ArgumentNullException.ThrowIfNull(o);
                        int fine = Lib.Go(o);
                        string bad = Lib.Go(o);
                    }
                }
            """)
        }
        assertEquals(listOf("13: Lib.Go(o) -> CS0029: Cannot implicitly convert type 'int' to 'string'"), found)
        assertEmpty(log.filter { it.contains("ThrowIfNull") || it.startsWith("applicable Go") || it.startsWith("resolve Go") })
    }

    fun testUpperBoundThroughAContravariantInterface() {
        // IComparer<in T>: IComparer<object> gives T an upper bound object, "s" a lower bound string — T is string
        assertEmpty(errors("""
            using System.Collections.Generic;
            class C
            {
                static T Pick<T>(IComparer<T> comparer, T value) => value;
                string M(IComparer<object> comparer) => Pick(comparer, "s");
            }
        """))
    }

    fun testNullableWithALowerBound() {
        // SPEC ERROR of Roslyn: M<T>(T?, T) with (char?, int) infers lower bounds char and int, and chooses int
        assertEmpty(errors("""
            class C
            {
                static T Pick<T>(T? first, T second) where T : struct => second;
                int M(char? c) => Pick(c, 1);
            }
        """))
    }

    fun testInterfaceImplementedByAClass() {
        assertEmpty(errors("""
            using System.Collections.Generic;
            class Bag : List<string> { }
            class C
            {
                static T First<T>(IEnumerable<T> items) => default!;
                string M(Bag bag) => First(bag);
            }
        """))
    }

    fun testDependentTypeParametersFixedInTurn() {
        assertEmpty(errors("""
            using System;
            class C
            {
                static TResult Apply<TSource, TMiddle, TResult>(TSource source, Func<TSource, TMiddle> first, Func<TMiddle, TResult> second) => second(first(source));
                string M() => Apply(1, x => x * 2.0, y => y.ToString());
            }
        """))
    }

    /** The type of a call (the lenient inference of expressions, D6b): the best of the bounds, not the first argument's type. */
    fun testTheTypeOfACallIsTheBestOfTheBounds() {
        val found = errors("""
            class C
            {
                static T Pick<T>(T a, T b) => a;
                int M() => Pick(1, 2L);
            }
        """)
        // T is long (int converts to it), not int of the first argument: long to int is CS0266 as Roslyn reports it
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single(), "CS0266" in found.single() && "'long'" in found.single())
    }

    /** nopCommerce's `AsyncEnumerable.Select(async (ProductTag x, CancellationToken _) => …)`: an async lambda gives the delegate's `ValueTask<X>`. */
    fun testAsyncLambdaReturnsTheTaskLikeTypeOfTheDelegate() {
        assertEmpty(errors("""
            using System;
            using System.Collections.Generic;
            using System.Threading;
            using System.Threading.Tasks;
            static class AsyncEnumerable
            {
                public static IAsyncEnumerable<TResult> Select<TSource, TResult>(this IAsyncEnumerable<TSource> source, Func<TSource, TResult> selector) => null!;
                public static IAsyncEnumerable<TResult> Select<TSource, TResult>(this IAsyncEnumerable<TSource> source, Func<TSource, CancellationToken, ValueTask<TResult>> selector) => null!;
                public static IAsyncEnumerable<TResult> Select<TSource, TResult>(this IAsyncEnumerable<TSource> source, Func<TSource, int, CancellationToken, ValueTask<TResult>> selector) => null!;
            }
            class Tag { public int Id; }
            class TagModel { public int Id; }
            class Factory
            {
                static Task<int> CountAsync(int id) => Task.FromResult(id);
                IAsyncEnumerable<TagModel> M(IAsyncEnumerable<Tag> tags) => tags.Select(async (Tag x, CancellationToken _) => new TagModel { Id = await CountAsync(x.Id) });
            }
        """))
    }

    fun testSureFailureIsStillCS0411() {
        val found = errors("""
            class C
            {
                static T Same<T>(T a, T b) => a;
                void M() => Same(1, "s");
            }
        """)
        assertEquals(found.toString(), listOf("CS0411"), found.map { it.substringAfter(" -> ").substringBefore(':') })
    }
}
