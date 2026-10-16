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
 * Roslyn's better function member, ported (CSHARP_PSI_MIGRATION.md, D6c4): each rule decides the overload a call goes to, seen through
 * the type of the call — the two overloads return different types and the assignment to the wrong one is the CS0029 control; and the
 * comparison is known (no `better` line in the trace).
 */
class RoslynBetterFunctionMemberTest : BasePlatformTestCase() {
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

    private fun errors(text: String): List<String> {
        myFixture.configureByText("Better${files++}.cs", text.trimIndent())
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
            .sortedBy(HighlightInfo::getStartOffset)
            .map { "${document.substring(0, it.startOffset).count { c -> c == '\n' } + 1}: " + document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    /** [body] inside a class with the overloads [members]; the one CS0029 expected is [control]; the comparison known. */
    private fun check(members: String, body: String, control: String) {
        val (found, log) = SemanticTrace.collectForTests {
            errors("""
                using System;
                using System.Collections.Generic;
                using System.Threading.Tasks;
                $members
                class C
                {
                    static int F() => 1;
                    void Test()
                    {
                        $body
                    }
                }
            """)
        }
        assertEquals(listOf(control), found.map { it.substringAfter(": ") })
        assertEmpty(log.filter { it.startsWith("better") || it.startsWith("resolve") })
    }

    fun testTheExactMatchOfALambdaByItsInferredReturnType() = check(
        "static class O { public static int M(Func<int, int> f) => 1; public static long M(Func<int, long> f) => 1; }",
        "string bad = O.M(x => x);",
        "O.M(x => x) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    fun testADelegateReturningAValueOverAVoidOne() = check(
        "static class O { public static int M(Func<int> f) => 1; public static string M(Action f) => \"\"; }",
        "string bad = O.M(() => F());",
        "O.M(() => F()) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    fun testTheExactMatchOfAnAsyncLambdaThroughTask() = check(
        "static class O { public static int M(Func<Task<object>> f) => 1; public static string M(Func<Task<string>> f) => \"\"; }",
        "int bad = O.M(async () => \"s\");",
        "O.M(async () => \"s\") -> CS0029: Cannot implicitly convert type 'string' to 'int'")

    fun testANonGenericMethodOverAGenericOne() = check(
        "static class O { public static T M<T>(T x) => x; public static string M(int x) => \"\"; }",
        "int bad = O.M(1);",
        "O.M(1) -> CS0029: Cannot implicitly convert type 'string' to 'int'")

    fun testTheNormalFormOverTheExpandedOne() = check(
        "static class O { public static int M(object o) => 1; public static string M(params object[] a) => \"\"; }",
        "string bad = O.M(\"s\");",
        "O.M(\"s\") -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    fun testTheMoreSpecificParameterTypes() = check(
        "static class O { public static int M<T>(T x, string y) => 1; public static string M<T>(T x, T y) => \"\"; }",
        "string bad = O.M(\"a\", \"b\");",
        "O.M(\"a\", \"b\") -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    fun testASignedIntegralTargetOverAnUnsignedOne() = check(
        "static class O { public static int M(long x) => 1; public static string M(ulong x) => \"\"; }",
        "string bad = O.M(1);",
        "O.M(1) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    fun testAValueParameterOverAnInOne() = check(
        "static class O { public static int M(in int x) => 1; public static string M(int x) => \"\"; }",
        "int v = 1; int bad = O.M(v);",
        "O.M(v) -> CS0029: Cannot implicitly convert type 'string' to 'int'")

    fun testTheBetterConversionTargetOfATypedArgument() = check(
        "static class O { public static int M(IEnumerable<string> x) => 1; public static string M(List<string> x) => \"\"; }",
        "int bad = O.M(new List<string>());",
        "O.M(new List<string>()) -> CS0029: Cannot implicitly convert type 'string' to 'int'")

    /** The receiver of two extension methods is their first argument: the exact match decides (`Queryable.Where` over `Enumerable.Where`). */
    fun testTheReceiverOfExtensionMethodsIsTheFirstArgument() = check(
        """
        interface IQ<T> : IEnumerable<T> { }
        static class Q { public static int Where<T>(this IQ<T> s, Func<T, bool> p) => 1; }
        static class E { public static string Where<T>(this IEnumerable<T> s, Func<T, bool> p) => ""; }
        """.trimIndent(),
        "IQ<int> q = null!; string bad = q.Where(x => x > 0);",
        "q.Where(x => x > 0) -> CS0029: Cannot implicitly convert type 'int' to 'string'")
}
