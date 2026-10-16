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
 * The method group conversion and the function types of lambdas and method groups, ported (CSHARP_PSI_MIGRATION.md, D6c2): each case
 * decides the overload a call goes to, seen through the type of the call (a CS0029 control on the assignment to the wrong type), and
 * the semantics refuses nothing on the way (no `inference` / `applicable` / `resolve` / `better` / `conversion` line in the trace).
 */
class RoslynMethodGroupConversionTest : BasePlatformTestCase() {
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
        myFixture.configureByText("Group${files++}.cs", text.trimIndent())
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
            .sortedBy(HighlightInfo::getStartOffset)
            .map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    private fun check(members: String, body: String, control: String) {
        val (found, log) = SemanticTrace.collectForTests {
            errors("""
                using System;
                using System.Collections.Generic;
                using System.Threading.Tasks;
                $members
                class C
                {
                    static Task Invoke() => Task.CompletedTask;
                    static void P(int x) { }
                    static void P(string s) { }
                    static T Id<T>(T x) => x;
                    static void L(long x) { }
                    bool flag;
                    void Test()
                    {
                        $body
                    }
                }
            """)
        }
        assertEquals(listOf(control), found)
        assertEmpty(log.filter { l -> listOf("inference", "applicable", "resolve", "better", "conversion").any { l.startsWith(it) } })
    }

    /** `DebounceAsync(Invoke)` of MudBlazor's tests: a local function to `Func<Task>`, by the return type `Task` (never `Action`). */
    fun testALocalFunctionToTheDelegateItReturnsFor() = check(
        "static class O { public static int D(Func<Task> f) => 1; public static string D(Action a) => \"\"; }",
        "Task Local() => Task.CompletedTask; string bad = O.D(Local);",
        "O.D(Local) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    fun testTheMethodOfTheGroupIsPickedByTheDelegatesParameters() = check(
        "static class O { public static string M(Action<string> a) => \"\"; }",
        "int bad = O.M(P);",
        "O.M(P) -> CS0029: Cannot implicitly convert type 'string' to 'int'")

    fun testAGenericMethodGroupInfersFromTheDelegate() = check(
        "static class O { public static int M(Func<int, int> f) => 1; }",
        "string bad = O.M(Id);",
        "O.M(Id) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    /** `void L(long)` is applicable for `Action<int>` but not compatible (no reference conversion): the `Action<long>` overload is the call. */
    fun testDelegateCompatibilityIsStricterThanApplicability() = check(
        "static class O { public static int M(Action<int> a) => 1; public static string M(Action<long> a) => \"\"; }",
        "int bad = O.M(L);",
        "O.M(L) -> CS0029: Cannot implicitly convert type 'string' to 'int'")

    /** `EventCallback.Factory.Create(this, () => { called = true; })` of MudBlazor: a block that returns nothing is no `Func<Task>`. */
    fun testABlockLambdaWithoutAReturnIsNoFuncOfTask() = check(
        "static class O { public static int M(Action a) => 1; public static string M(Func<Task> f) => \"\"; }",
        "string bad = O.M(() => { flag = true; });",
        "O.M(() => { flag = true; }) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    fun testTheFunctionTypeOfALambdaIsTheBoundOfATypeParameter() = check(
        "static class O { public static int F<T>(T value) => 1; }",
        "string bad = O.F(() => 5);",
        "O.F(() => 5) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    fun testTheFunctionTypeOfAMethodGroupIsTheBoundOfATypeParameter() = check(
        "static class O { public static int F<T>(T value) => 1; }",
        "string bad = O.F(Invoke);",
        "O.F(Invoke) -> CS0029: Cannot implicitly convert type 'int' to 'string'")

    /** bUnit's `Add(x => x.Click, () => { })`: the function type `Action` is dropped beside the bound `Cb` of the selector, the generic candidate fails. */
    fun testAFunctionTypeBoundIsDroppedBesideATypeBound() = check(
        "struct Cb { } class E { public Cb Click; } static class O { public static int A<TValue>(Func<E, TValue> sel, TValue value) => 1; public static string A(Func<E, Cb> sel, Action cb) => \"\"; }",
        "int bad = O.A(x => x.Click, () => { });",
        "O.A(x => x.Click, () => { }) -> CS0029: Cannot implicitly convert type 'string' to 'int'")
}
