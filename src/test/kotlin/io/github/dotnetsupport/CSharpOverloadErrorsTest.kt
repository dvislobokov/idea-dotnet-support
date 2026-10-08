package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The errors of overload resolution and lambdas of the native pass (`CSharpOverloadChecks`): CS0121, CS0411, CS1503 with overloads,
 * CS1593, CS1660, CS1661 / CS1678, CS1643 — Roslyn's texts and spans (checked against `dotnet build`), and silence where anything is not known.
 */
class CSharpOverloadErrorsTest : BasePlatformTestCase() {
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

    /** Each error as `text under it -> CSxxxx: message`. */
    private fun errors(body: String, members: String = ""): List<String> {
        val text = """
            using System;
            using System.Collections.Generic;
            using System.Linq;
            using System.Threading.Tasks;

            namespace Probe.Inner;

            public delegate int Op(int a, int b);
            public class Item { }
            public enum Kind { A }

            public class Amb
            {
                public void M(int a, long b) { }
                public void M(long a, int b) { }
                public static void S(object a, string b) { }
                public static void S(string a, object b) { }
                public void Two(int a) { }
                public void Two(int a, string b) { }
                public void N(int a) { }
                public void N(string a) { }
                public T Make<T>() => default!;
                public void Store<T>(T value) { }
                public void Run(Func<int, int> f) { }
                public void Consume(int count) { }
                ${members.trimIndent().replace("\n", "\n                ")}

                public void Body(Amb other, Item item, List<int> numbers)
                {
                    ${body.trimIndent().replace("\n", "\n                    ")}
                }
            }
        """.trimIndent()
        myFixture.configureByText("OverloadErrors${files++}.cs", text)
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
            .sortedBy { it.startOffset }.map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    /**
     * The plugin's errors of the playground file debug-playground/Broken/Errors/[code].cs as `line:start-end CSxxxx: message` (end 0 when
     * the span ends on another line) — the form `src/test/resources/overloadErrors/[code].txt` keeps Roslyn's in (`dotnet build`'s SARIF).
     */
    private fun playground(code: String): List<String> {
        if (myFixture.tempDirFixture.getFile("GlobalUsings.g.cs") == null) myFixture.addFileToProject("GlobalUsings.g.cs", IMPLICIT_USINGS)
        val text = java.io.File(BrokenErrorFilesTest.DIRECTORY, "$code.cs").readText().removePrefix("﻿").replace("\r\n", "\n")
        myFixture.configureByText("$code.cs", text)
        val document = myFixture.editor.document
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }.map { info ->
            val line = document.getLineNumber(info.startOffset)
            val start = info.startOffset - document.getLineStartOffset(line) + 1
            val end = if (document.getLineNumber(info.endOffset) == line) info.endOffset - document.getLineStartOffset(line) + 1 else 0
            Triple(line + 1, start, "${line + 1}:$start-$end ${info.description}")
        }.distinct().sortedWith(compareBy({ it.first }, { it.second }, { it.third })).map { it.third }
    }

    private fun roslyn(code: String): List<String> =
        java.io.File("src/test/resources/overloadErrors/$code.txt").readLines().filter { it.isNotBlank() }

    private fun assertPlayground(code: String) = assertEquals(roslyn(code).joinToString("\n"), playground(code).joinToString("\n"))

    fun testPlaygroundAmbiguities() = assertPlayground("CS0121")
    fun testPlaygroundInference() = assertPlayground("CS0411")
    fun testPlaygroundBadArguments() = assertPlayground("CS1503")
    fun testPlaygroundLambdaParameterCounts() = assertPlayground("CS1593")
    fun testPlaygroundLambdaPaths() = assertPlayground("CS1643")
    fun testPlaygroundLambdaToNoDelegate() = assertPlayground("CS1660")
    fun testPlaygroundLambdaParameterTypes() = assertPlayground("CS1661")
    fun testPlaygroundLambdaParameterType() = assertPlayground("CS1678")
    fun testPlaygroundLambdaMissingRefKind() = assertPlayground("CS1676")
    fun testPlaygroundLambdaExtraRefKind() = assertPlayground("CS1677")

    fun testAmbiguousCalls() {
        assertEquals(listOf(
            "M -> CS0121: The call is ambiguous between the following methods or properties: 'Probe.Inner.Amb.M(int, long)' and 'Probe.Inner.Amb.M(long, int)'",
            "M -> CS0121: The call is ambiguous between the following methods or properties: 'Probe.Inner.Amb.M(int, long)' and 'Probe.Inner.Amb.M(long, int)'",
            "S -> CS0121: The call is ambiguous between the following methods or properties: 'Probe.Inner.Amb.S(object, string)' and 'Probe.Inner.Amb.S(string, object)'",
            "S -> CS0121: The call is ambiguous between the following methods or properties: 'Probe.Inner.Amb.S(object, string)' and 'Probe.Inner.Amb.S(string, object)'",
        ), errors("""
            M(1, 1);
            other.M(1, 2);
            M(1, 2L);
            M(1L, 2);
            S(null, null);
            Amb.S("a", "b");
            S("a", 1);
            S((object)"a", "b");
        """))
    }

    /** `IEnumerator IEnumerable.GetEnumerator()` is no candidate of `GetEnumerator()` (C# §19.6.2): Roslyn finds the public method alone. */
    fun testAnExplicitImplementationIsNoCandidate() {
        val bag = """
            using System;
            using System.Collections;
            using System.Collections.Generic;

            namespace Probe.Explicit;

            public sealed class ExplicitBag : IEnumerable<int>, IDisposable
            {
                private readonly List<int> items = new List<int>();
                public IEnumerator<int> GetEnumerator() => items.GetEnumerator();
                IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
                void IDisposable.Dispose() => items.Clear();
                public void Dispose(bool all) => items.Clear();
                public void Twice(int a, long b) { }
                public void Twice(long a, int b) { }
                public int Sum() { var sum = 0; foreach (var item in this) sum += item; using var e = GetEnumerator(); Dispose(true); return sum; }
            }
        """.trimIndent()
        val user = """
            using System;
            using System.Collections;

            namespace Probe.Explicit;

            public static class BagUser
            {
                public static void Use(ExplicitBag bag)
                {
                    using var e = bag.GetEnumerator();
                    IEnumerator plain = ((IEnumerable)bag).GetEnumerator();
                    ((IDisposable)bag).Dispose();
                    bag.Dispose(true);
                    bag.Twice(1, 1);
                }
            }
        """.trimIndent()
        fun errors(): List<String> {
            val document = myFixture.editor.document.text
            return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
                .map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
        }
        // the declarations from the PSI of the open file
        myFixture.configureByText("ExplicitBag.cs", bag)
        assertEquals(emptyList<String>(), errors())
        // the declarations from the stubs of a file that is not open
        myFixture.addFileToProject("ExplicitStub/ExplicitBag.cs", bag.replace("Probe.Explicit", "Probe.ExplicitStub"))
        com.intellij.psi.impl.PsiManagerEx.getInstanceEx(project).dropPsiCaches()
        myFixture.configureByText("BagUser.cs", user.replace("Probe.Explicit", "Probe.ExplicitStub"))
        assertEquals(listOf(
            "Twice -> CS0121: The call is ambiguous between the following methods or properties: 'Probe.ExplicitStub.ExplicitBag.Twice(int, long)' and " +
                "'Probe.ExplicitStub.ExplicitBag.Twice(long, int)'",
        ), errors())
    }

    fun testTypeArgumentsThatCannotBeInferred() {
        assertEquals(listOf(
            "Make -> CS0411: The type arguments for method 'Amb.Make<T>()' cannot be inferred from the usage. Try specifying the type arguments explicitly.",
            "Store -> CS0411: The type arguments for method 'Amb.Store<T>(T)' cannot be inferred from the usage. Try specifying the type arguments explicitly.",
            "Store -> CS0411: The type arguments for method 'Amb.Store<T>(T)' cannot be inferred from the usage. Try specifying the type arguments explicitly.",
            "Batch -> CS0411: The type arguments for method 'Amb.Batch<T>(params T[])' cannot be inferred from the usage. Try specifying the type arguments explicitly.",
        ), errors("""
            var made = Make();
            var typed = Make<int>();
            Store(null);
            other.Store(default);
            Store(1);
            Store<string?>(null);
            Batch();
            Batch(1, 2);
        """, members = "public void Batch<T>(params T[] values) { }"))
    }

    fun testBadArgumentsOfOverloads() {
        assertEquals(listOf(
            "\"x\" -> CS1503: Argument 1: cannot convert from 'string' to 'int'",
            "2 -> CS1503: Argument 2: cannot convert from 'int' to 'string'",
            "1.5 -> CS1503: Argument 1: cannot convert from 'double' to 'int'",
        ), errors("""
            Two("x");
            Two(1, 2);
            Two(1, "y");
            N(1.5);
            N(1);
            N("s");
        """))
    }

    fun testExtensionMethodsTheReceiverIsNoInstanceFor() {
        // as Queryable.Take / AsyncEnumerable.Take next to Enumerable.Take in the IDE: `this IQuery<T>` for a List<int> infers nothing, Roslyn
        // drops it before overload resolution; the classes are in two files (no known order between them), and only Enumerable's are left
        myFixture.addFileToProject("Probe/QueryKeep.cs", """
            namespace Probe;
            public interface IQuery<T> : System.Collections.Generic.IEnumerable<T> { }
            public static class QueryKeep { public static IQuery<T> Keep<T>(this IQuery<T> query, int count) => query; }
        """.trimIndent())
        myFixture.addFileToProject("Probe/SequenceKeep.cs", """
            namespace Probe;
            public static class SequenceKeep { public static System.Collections.Generic.IEnumerable<T> Keep<T>(this System.Collections.Generic.IEnumerable<T> items, int count) => items; }
        """.trimIndent())
        assertEquals(listOf(
            "\"three\" -> CS1503: Argument 2: cannot convert from 'string' to 'int'",
            "=> -> CS1660: Cannot convert lambda expression to type 'int' because it is not a delegate type",
        ), errors("""
            numbers.Keep("three");
            numbers.Keep(() => 1);
            numbers.Keep(3);
        """))
    }

    fun testLambdaParameterCounts() {
        assertEquals(listOf(
            "=> -> CS1593: Delegate 'Func<int, int>' does not take 2 arguments",
            "=> -> CS1593: Delegate 'Action' does not take 1 arguments",
            "=> -> CS1593: Delegate 'Op' does not take 1 arguments",
            "=> -> CS1593: Delegate 'Func<int, int>' does not take 2 arguments",
            "delegate -> CS1593: Delegate 'Func<int, int>' does not take 0 arguments",
        ), errors("""
            Func<int, int> f1 = (a, b) => a;
            Func<int, int> f2 = a => a;
            Action a1 = x => { };
            Op op = a => a;
            Op op2 = (a, b) => a + b;
            Run((a, b) => a);
            Func<int, int> f3 = delegate () { return 1; };
            Func<int, int> f4 = delegate { return 1; };
        """))
    }

    fun testLambdaToWhatIsNoDelegate() {
        assertEquals(listOf(
            "=> -> CS1660: Cannot convert lambda expression to type 'int' because it is not a delegate type",
            "=> -> CS1660: Cannot convert lambda expression to type 'string' because it is not a delegate type",
            "=> -> CS1660: Cannot convert lambda expression to type 'Item' because it is not a delegate type",
            "=> -> CS1660: Cannot convert lambda expression to type 'Kind' because it is not a delegate type",
            "=> -> CS1660: Cannot convert lambda expression to type 'int?' because it is not a delegate type",
            "=> -> CS1660: Cannot convert lambda expression to type 'int' because it is not a delegate type",
            "delegate -> CS1660: Cannot convert anonymous method to type 'int' because it is not a delegate type",
        ), errors("""
            int i = () => 1;
            string s = x => "a";
            Item it = () => new Item();
            Kind k = () => Kind.A;
            int? n = () => 1;
            object o = () => 1;
            Delegate d = (int x) => x;
            var v = () => 1;
            Consume(() => 1);
            int a = delegate { return 1; };
        """))
    }

    fun testLambdaParameterTypes() {
        assertEquals(listOf(
            "q -> CS1678: Parameter 1 is declared as type 'string' but should be 'int'",
            "=> -> CS1661: Cannot convert lambda expression to type 'Func<int, int>' because the parameter types do not match the delegate parameter types",
            "o -> CS1678: Parameter 1 is declared as type 'object' but should be 'Probe.Inner.Item'",
            "=> -> CS1661: Cannot convert lambda expression to type 'Func<Item, int>' because the parameter types do not match the delegate parameter types",
            "b -> CS1678: Parameter 2 is declared as type 'long' but should be 'int'",
            "=> -> CS1661: Cannot convert lambda expression to type 'Op' because the parameter types do not match the delegate parameter types",
            "delegate -> CS1661: Cannot convert anonymous method to type 'Func<int, int>' because the parameter types do not match the delegate parameter types",
            "s -> CS1678: Parameter 1 is declared as type 'string' but should be 'int'",
        ), errors("""
            Func<int, int> f1 = (string q) => 1;
            Func<int, int> f2 = (int q) => 1;
            Func<Item, int> f3 = (object o) => 1;
            Op op = (int a, long b) => 1;
            Func<int, int> f4 = delegate (string s) { return 1; };
            Func<(int a, int b), int> f5 = ((int, int) t) => 1;
            Func<object, int> f6 = (dynamic d) => 1;
        """))
    }

    fun testLambdaPathsThatReturnNothing() {
        assertEquals(listOf(
            "=> -> CS1643: Not all code paths return a value in lambda expression of type 'Func<int, int>'",
            "=> -> CS1643: Not all code paths return a value in lambda expression of type 'Func<int>'",
            "=> -> CS1643: Not all code paths return a value in lambda expression of type 'Func<int, int>'",
            "=> -> CS1643: Not all code paths return a value in lambda expression of type 'Func<Task<int>>'",
            "delegate -> CS1643: Not all code paths return a value in anonymous method of type 'Func<int, int>'",
        ), errors("""
            Func<int, int> f1 = x => { if (x > 0) return 1; };
            Func<int, int> f2 = x => { if (x > 0) return 1; else return 2; };
            Func<int> f3 = () => { };
            Func<int> f4 = () => { while (true) { } };
            Func<int> f5 = () => { throw new InvalidOperationException(); };
            Run(x => { if (x > 0) return 1; });
            Func<Task<int>> f6 = async () => { await Task.Yield(); };
            Func<Task> f7 = async () => { await Task.Yield(); };
            Func<int, int> f8 = delegate (int x) { if (x > 0) return 1; };
            Action<int> f9 = x => { if (x > 0) return; };
        """))
    }

    fun testNothingWhereSomethingIsNotKnown() {
        assertEquals(emptyList<String>(), errors("""
            dynamic d = 1;
            M(d, 1);
            Two(d);
            N(missing);
            M(1, missing);
            Pick(x => x);
            Optional(1);
            Unknown u = () => 1;
            IComparable c = () => 1;
            Run2((a, b) => a);
            Func<int, int> g = x => { goto end; end: return 1; };
            var r = numbers.Select(x => x * 2);
            Func<int, int>? nullable = x => x;
            numbers.ForEach(x => { });
            Consume(numbers.Count);
            Pick(x => x.Length);
            Pick(x => x.Length + 1, 2);
            var point = new Spot(1);
            Console.WriteLine(point.Equals(point));
            point.Deconstruct(out var at);
        """, members = """
            public record Spot(int At);
            public void Pick(Func<string, int> f, int n) { }
            public void Pick(Func<int, int> f, long n) { }
            public void Pick(Func<int, int> f) { }
            public void Pick(Func<string, int> f) { }
            public void Optional(int a, string b = "") { }
            public void Optional(long a) { }
            public void Run2(Func<int, int> f) { }
            public void Run2(Func<int, int, int> f) { }
        """).filter { !it.contains("CS0103") && !it.contains("CS0246") && !it.contains("CS0162") })
    }

    fun testNullableDelegateTypes() {
        assertEquals(listOf(
            "=> -> CS1593: Delegate 'Func<int, int>' does not take 2 arguments",
            "=> -> CS1643: Not all code paths return a value in lambda expression of type 'Func<int, int>'",
            "s -> CS1678: Parameter 1 is declared as type 'string' but should be 'int'",
            "=> -> CS1661: Cannot convert lambda expression to type 'Func<int, int>' because the parameter types do not match the delegate parameter types",
        ), errors("""
            Func<int, int>? count = (a, b) => a;
            Func<int, int>? paths = x => { if (x > 0) return 1; };
            Func<int, int>? types = (string s) => 1;
        """))
    }

    companion object {
        private val IMPLICIT_USINGS = listOf("System", "System.Collections.Generic", "System.IO", "System.Linq", "System.Threading", "System.Threading.Tasks")
            .joinToString("\n") { "global using global::$it;" }
    }

    fun testInferredDelegateOfACall() {
        assertEquals(listOf(
            "=> -> CS1643: Not all code paths return a value in lambda expression of type 'Func<int, int>'",
        ), errors("""
            var w = numbers.Select(x => { if (x > 0) return 1; });
            var v = numbers.Select(x => { if (x > 0) return 1; return 0; });
        """))
    }
}
