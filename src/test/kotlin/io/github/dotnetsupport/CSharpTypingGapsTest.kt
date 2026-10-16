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
