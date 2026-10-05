package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.NativeCSharpSemanticModel

/**
 * Overload resolution of layer 11d (CSHARP_PSI_MIGRATION.md, task D1, C# §12.6.4): the applicable candidates and the better function member,
 * over the fixtures of src/test/resources/index. A call is marked `/*@*/Name(...)`; the answer is the declaration of the chosen method up
 * to its body (`static int M(long x)`), the documentation id of one of an assembly, or `?` for candidates the resolver does not tell apart.
 */
class CSharpOverloadResolutionTest : BasePlatformTestCase() {
    private var counter = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
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

    /** What each marked call of [body] (in a method of a class with [members]) resolves to, in order. */
    private fun calls(body: String, members: String): List<String> {
        val f = myFixture.addFileToProject("overloads/O${counter++}.cs", """
            using System;
            using System.Collections.Generic;
            using System.Linq;
            using System.Threading.Tasks;

            class Sample
            {
            ${members.prependIndent("    ")}
                void Body(int number, long big, short small, byte tiny, uint unsigned, double real, string text, object any, int? maybe, List<int> numbers, int[] array)
                {
            ${body.prependIndent("        ")}
                }
            }
        """.trimIndent()) as CSharpFile
        val model = NativeCSharpSemanticModel(CSharpSemanticSession(project))
        val text = f.text
        val found = ArrayList<String>()
        var at = text.indexOf(MARK)
        while (at >= 0) {
            val ref = model.symbolAt(f, at + MARK.length)
            found += when {
                ref == null -> "null"
                ref.candidates.isNotEmpty() -> "?"
                ref.declarations.isNotEmpty() -> ref.declarations.first().text.substringBefore("=>").substringBefore("{").trim()
                else -> ref.id ?: "null"
            }
            at = text.indexOf(MARK, at + 1)
        }
        assertFalse("no marks", found.isEmpty())
        return found
    }

    /** Go to Declaration goes to the overload resolution picks, not to the list of all methods of the name (debug-playground Overloads.cs). */
    fun testGoToDeclarationPicksTheOverload() {
        val f = myFixture.addFileToProject("overloads/Nav${counter++}.cs", """
            class Nav
            {
                static string Pick(long value) => "long";
                static string Pick(double value) => "double";
                static string Pick(object value) => "object";
                static string Same(int a) => "a";
                static string Same(int a, int b = 0) => "b";
                void Body(int number, string text) { Pick(number); Pick(1.5); Pick(text); Same(1); }
            }
        """.trimIndent()) as CSharpFile
        fun goTo(anchor: String): List<String> {
            val leaf = f.findElementAt(f.text.indexOf(anchor))!!
            return io.github.dotnetsupport.lang.NativeCSharpNavigation.targets(leaf).orEmpty().map { it.text.substringBefore("=>").trim() }
        }
        assertEquals(listOf("static string Pick(long value)"), goTo("Pick(number)"))
        assertEquals(listOf("static string Pick(double value)"), goTo("Pick(1.5)"))
        assertEquals(listOf("static string Pick(object value)"), goTo("Pick(text)"))
        assertEquals(listOf("static string Same(int a)"), goTo("Same(1)"))
    }

    fun testBetterConversionTarget() {
        assertEquals(
            listOf("static int M(long x)", "static int M(long x)", "static int M(double x)", "static int S(int x)", "static int S(sbyte x)"),
            calls(
                "/*@*/M(number); /*@*/M(small); /*@*/M(real); /*@*/S(5); /*@*/S((sbyte)1);",
                """
                static int M(long x) => 1;
                static int M(double x) => 2;
                static int S(int x) => 1;
                static int S(uint x) => 2;
                static int S(sbyte x) => 3;
                static int S(byte x) => 4;
                """.trimIndent(),
            ),
        )
    }

    fun testExactMatchAndReferenceConversions() {
        assertEquals(
            listOf("static int R(string x)", "static int R(object x)", "static int R(string x)", "static int E(IEnumerable<int> x)", "static int E(List<int> x)"),
            calls(
                "/*@*/R(text); /*@*/R(any); /*@*/R(null); /*@*/E(array); /*@*/E(numbers);",
                """
                static int R(string x) => 1;
                static int R(object x) => 2;
                static int E(IEnumerable<int> x) => 1;
                static int E(List<int> x) => 2;
                """.trimIndent(),
            ),
        )
    }

    fun testParamsOptionalAndNamed() {
        assertEquals(
            listOf("static int P(int a)", "static int P(int a, params int[] rest)", "static int P(int a, params int[] rest)", "static int O(int a)", "static int O(int a, int b = 0, string c = \"\")", "static int N(string name, int count)"),
            calls(
                "/*@*/P(1); /*@*/P(1, 2, 3); /*@*/P(1, array); /*@*/O(1); /*@*/O(1, c: \"x\"); /*@*/N(count: 1, name: \"x\");",
                """
                static int P(int a) => 1;
                static int P(int a, params int[] rest) => 2;
                static int O(int a) => 1;
                static int O(int a, int b = 0, string c = "") => 2;
                static int N(string name, int count) => 1;
                static int N(int count, int other) => 2;
                """.trimIndent(),
            ),
        )
    }

    fun testGenericInferenceAndTieBreaks() {
        assertEquals(
            listOf("static int G(int x)", "static int G<T>(T x)", "static int L<T>(List<T> x)", "static int K<T>(T x) where T : struct", "static int K<T>(T x, int y = 0) where T : class"),
            calls(
                "/*@*/G(number); /*@*/G(text); /*@*/L(numbers); /*@*/K(number); /*@*/K(text);",
                """
                static int G(int x) => 1;
                static int G<T>(T x) => 2;
                static int L<T>(List<T> x) => 1;
                static int L<T>(IEnumerable<T> x) => 2;
                static int K<T>(T x) where T : struct => 1;
                static int K<T>(T x, int y = 0) where T : class => 2;
                """.trimIndent(),
            ),
        )
    }

    fun testLambdasAndMethodGroups() {
        assertEquals(
            listOf("static int F(Func<int> f)", "static int F(Action a)", "static int D(Func<int, int> f)", "static int H(Func<string, int> f)"),
            calls(
                "/*@*/F(() => 1); /*@*/F(() => Console.WriteLine()); /*@*/D(x => x + 1); /*@*/H(Parse);",
                """
                static int F(Func<int> f) => 1;
                static int F(Action a) => 2;
                static int D(Func<int, int> f) => 1;
                static int D(Func<string, int, int> f) => 2;
                static int H(Func<string, int> f) => 1;
                static int H(Func<int> f) => 2;
                static int Parse(string s) => 0;
                """.trimIndent(),
            ),
        )
    }

    fun testStaticAndInstanceCandidatesAndExplicitImplementations() {
        assertEquals(
            listOf("public int Count()", "M:System.Object.Equals(System.Object)~System.Boolean"),
            calls(
                "var bag = new Bag(); bag./*@*/Count(); IEqualityComparer<int> c = null!; c./*@*/Equals(any);",
                """
                class Bag : IEnumerable<int>
                {
                    public int Count() => 0;
                    public static int Count(Bag b) => 1;
                    public IEnumerator<int> GetEnumerator() => null!;
                    System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() => null!;
                }
                """.trimIndent(),
            ),
        )
    }

    fun testTargetTypedArguments() {
        assertEquals(
            listOf("static int T(string s)", "static int V(int? x)", "static int C(int[] xs)"),
            calls(
                "/*@*/T(default); /*@*/V(null); /*@*/C([1, 2]);",
                """
                static int T(string s) => 1;
                static int V(int? x) => 1;
                static int V(int x, int y) => 2;
                static int C(int[] xs) => 1;
                static int C(int x) => 2;
                """.trimIndent(),
            ),
        )
    }

    fun testTypeParametersOfTheCallerAreTypes() {
        // `TMinMax.Compare(vector, vector)` of System.Linq: the caller's `T` is no type parameter to infer
        assertEquals(
            listOf("static abstract bool Cmp(T a, T b);", "static abstract List<T> Cmp(List<T> a, List<T> b);"),
            calls(
                "",
                """
                interface ICalc<T> { static abstract bool Cmp(T a, T b); static abstract List<T> Cmp(List<T> a, List<T> b); }
                static void Generic<T, TCalc>(T one, List<T> many) where TCalc : ICalc<T> { TCalc./*@*/Cmp(one, one); TCalc./*@*/Cmp(many, many); }
                """.trimIndent(),
            ),
        )
    }

    private companion object {
        const val MARK = "/*@*/"
    }
}
