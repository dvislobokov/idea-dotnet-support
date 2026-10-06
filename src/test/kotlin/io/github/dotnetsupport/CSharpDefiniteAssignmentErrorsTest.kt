package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Definite assignment of the native pass (`CSharpDefiniteAssignmentChecks`): CS0165 / CS0269 / CS0177 with Roslyn's texts and spans
 * (checked against `dotnet build`), and silence wherever the flow is not modeled.
 */
class CSharpDefiniteAssignmentErrorsTest : BasePlatformTestCase() {
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

    /** The errors of definite assignment in [members] of a class, as `text under it -> CSxxxx: message`. */
    private fun errors(members: String): List<String> {
        val text = """
            using System;
            using System.Collections.Generic;
            using System.Linq;

            namespace Probe.Flow;

            public enum Level { Low, High }
            public struct Pair { public int A; public int B; }
            public class Holder { public bool Has(out int v) { v = 1; return true; } public int Value; }

            public class Flow
            {
                private static bool Ready() => DateTime.Now.Ticks > 0;
                private static void Use(object? o) { }
                private static bool Get(out int v) { v = 1; return true; }
                private static void Two(out int a, int b) { a = b; }
                private const bool Debug = false;

                ${members.trimIndent().replace("\n", "\n                ")}
            }
        """.trimIndent()
        myFixture.configureByText("Flow${files++}.cs", text)
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR)
            .filter { it.severity == HighlightSeverity.ERROR && it.description?.let { d -> d.startsWith("CS0165") || d.startsWith("CS0177") || d.startsWith("CS0269") } == true }
            .sortedBy { it.startOffset }.map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    private fun codes(members: String): List<String> = errors(members).map { it.substringBefore(" -> ") + " " + it.substringAfter(" -> ").substringBefore(':') }

    fun testMessagesAndSpansAreRoslyns() {
        assertEquals(listOf(
            "x -> CS0165: Use of unassigned local variable 'x'",
            "M -> CS0177: The out parameter 'p' must be assigned to before control leaves the current method",
            "p -> CS0269: Use of unassigned out parameter 'p'",
            "return; -> CS0177: The out parameter 'q' must be assigned to before control leaves the current method",
        ), errors("""
            public void A() { int x; Use(x); Use(x); }
            public void M(out int p) { }
            public void B(out int p, out int q) { Use(p); p = 1; if (Ready()) return; q = 1; }
        """))
    }

    fun testBranchesAndLoops() {
        assertEquals(listOf("a CS0165", "b CS0165", "c CS0165", "d CS0165"), codes("""
            public void If(int n) { int a; if (n > 0) a = 1; Use(a); int ok; if (n > 0) ok = 1; else ok = 2; Use(ok); }
            public void While(int[] items) { int b; foreach (var i in items) b = i; Use(b); int ok; while (true) { if (Ready()) { ok = 1; break; } } Use(ok); }
            public void Switch(Level l) { int c; switch (l) { case Level.Low: c = 1; break; } Use(c); int ok; switch (l) { case Level.Low: ok = 1; break; default: ok = 2; break; } Use(ok); }
            public void Do() { int d; for (var i = 0; i < 3; i++) d = i; Use(d); int ok; do { ok = 1; } while (Ready()); Use(ok); int ok2; for (;;) { ok2 = 1; break; } Use(ok2); }
        """))
    }

    fun testConditionsAndOutArguments() {
        assertEquals(listOf("a CS0165", "b CS0165", "x CS0165", "s CS0165"), codes("""
            public void And(bool f) { int a; if (f && Get(out a)) Use(a); else Use(a); }
            public void Or(bool f) { int b; if (f || Get(out b)) Use(b); int ok; if (!Get(out ok)) return; Use(ok); }
            public void Order() { int x; Two(out x, x); }
            public void Cond(bool f) { int ok; var v = f ? (ok = 1) : (ok = 2); Use(ok); int ok2; if (f ? Get(out ok2) : Get(out ok2)) Use(ok2); }
            public void Is(object o, bool f) { if (o is string s || f) Use(s); if (!(o is string t)) return; Use(t); if (o is not string u) return; Use(u); }
            public void Eq() { int ok; if (Get(out ok) == true) Use(ok); }
        """))
    }

    fun testValuesOfConditionalsAndSwitchesOnLiterals() {
        assertEquals(listOf("a CS0165", "b CS0165"), codes("""
            public void Ternary(bool f) { int a; var s = f ? null : "x"; Use(s); Use(a); }
            public void Literals(object k) { int b; switch (k.ToString()) { case "x": b = 1; break; } Use(b); }
        """))
    }

    fun testTryCatchFinally() {
        assertEquals(listOf("a CS0165", "b CS0165", "M2 CS0177"), codes("""
            public void T1(string t) { int a; try { a = int.Parse(t); } catch { } Use(a); int ok; try { ok = int.Parse(t); } catch { throw; } Use(ok); }
            public void T2() { int b; try { b = 1; } finally { Use(b); } }
            public void M1(out int p) { try { return; } finally { p = 1; } }
            public void M2(out int p) { try { p = int.Parse(""); } catch (FormatException) { } }
        """))
    }

    fun testLambdasAreCheckedWhereTheyAreCreated() {
        assertEquals(listOf("x CS0165"), codes("""
            public void L() { int x; Action a = () => Use(x); x = 1; a(); int y = 1; Action b = () => Use(y); int z; Action c = () => { z = 1; Use(z); }; }
        """))
    }

    fun testUnreachableCodeAndExpressionBodies() {
        assertEquals(listOf("E CS0177"), codes("""
            public void U() { int x; return; Use(x); }
            public void Th(out int p) => throw new InvalidOperationException();
            public void E(out int p) => Use(1);
            public void Ok(out int p) => p = 1;
        """))
    }

    fun testSilentWhereTheFlowIsNotModeled() {
        assertEmpty(errors("""
            public void LocalFunction() { int x; Set(); Use(x); void Set() { x = 1; } }
            public void Goto() { int x; goto end; end: Use(x); }
            public void BoolSwitch(bool b) { int x; switch (b) { case true: x = 1; break; case false: x = 2; break; } Use(x); }
            public void ConstCondition() { int x; if (Debug) Use(x); }
            public void Conditional(Holder? h) { int x; if (h?.Has(out x) == true) Use(x); }
            public void Struct() { Pair p; p.A = 1; p.B = 2; Use(p); }
            public void Query(int[] items) { int x; var q = from i in items select i + x; }
            public void Nameof() { int x; Use(nameof(x)); }
            public void AlwaysMatches(int n) { int x; if (!(n is var m)) Use(x); }
        """))
    }
}
