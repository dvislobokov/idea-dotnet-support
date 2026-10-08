package io.github.dotnetsupport

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The semantic errors of the native pass (CSHARP_PSI_MIGRATION.md, task C4c): Roslyn's codes, texts and spans where everything involved is
 * known, and silence where anything is not (the cases of `build/c4c-probe`, checked against `dotnet build`).
 */
class CSharpSemanticErrorsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0
    private var assemblies: AssemblyIndexSet? = null

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        assemblies = CSharpUsingTypesTest.ASSEMBLIES
        CSharpSemanticEnvironment.setAssembliesForTests { assemblies }
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSemanticEnvironment.setGeneratesForTests(null)
            CSharpSemanticEnvironment.setGeneratedKnownForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun highlight(text: String): List<HighlightInfo> {
        myFixture.configureByText("SemanticErrors${files++}.cs", text.trimIndent())
        return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.description?.let { d -> d.startsWith("CS") || d.startsWith("Unnecessary") || d.startsWith("The using") } == true }
    }

    /** Each problem as `text under it -> CSxxxx: message`. */
    private fun errors(text: String): List<String> {
        val infos = highlight(text)
        val document = myFixture.editor.document.text
        return infos.filter { it.severity == HighlightSeverity.ERROR }.sortedBy { it.startOffset }.map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    private fun codes(text: String): List<String> = errors(text).map { it.substringBefore(" -> ") + " " + it.substringAfter(" -> ").substringBefore(':') }

    private fun wrap(body: String, members: String = ""): String = """
        using System;
        using System.Collections.Generic;

        namespace Probe.Inner;

        public class Calc
        {
            public int Add(int a, int b) => a + b;
            public int Two(int a) => a;
            public int Two(int a, int b) => a + b;
            public static int Twice(int a) => a * 2;
            public int Prop { get; set; }
        }

        public interface IShape { double Area(); }
        public record Point(int X, int Y);
        public enum Color { Red, Green }

        public class Errors
        {
            ${members.replace("\n", "\n            ")}
            public void Body(Calc calc, List<int> list, IShape shape, Point p, double d, int? n, long l, object o, (int a, string b) t)
            {
                ${body.replace("\n", "\n                ")}
            }
        }
    """

    fun testNamesThatAreNowhere() {
        assertEquals(listOf(
            "Stopwatch CS0103", "missing CS0103", "missingCall CS0103", "Unknown CS0246", "IMissing CS0246",
        ), codes(wrap("""
            var sw = Stopwatch.StartNew();
            Console.WriteLine(missing);
            missingCall();
            List<Unknown> u = null!;
            IMissing m = null!;
        """)))
    }

    fun testMessagesAreRoslyns() {
        assertEquals(listOf(
            "missing -> CS0103: The name 'missing' does not exist in the current context",
            "IMissing -> CS0246: The type or namespace name 'IMissing' could not be found (are you missing a using directive or an assembly reference?)",
        ), errors(wrap("""
            Console.WriteLine(missing);
            IMissing m = null!;
        """)))
    }

    fun testMembersThatAreNotThere() {
        assertEquals(listOf(
            "Nope -> CS1061: 'Calc' does not contain a definition for 'Nope' and no accessible extension method 'Nope' accepting a first argument of type 'Calc' could be found (are you missing a using directive or an assembly reference?)",
            "Static -> CS0117: 'Calc' does not contain a definition for 'Static'",
            "WriteLin -> CS0117: 'Console' does not contain a definition for 'WriteLin'",
            "Nope3 -> CS1061: 'List<int>' does not contain a definition for 'Nope3' and no accessible extension method 'Nope3' accepting a first argument of type 'List<int>' could be found (are you missing a using directive or an assembly reference?)",
            "Volume -> CS1061: 'IShape' does not contain a definition for 'Volume' and no accessible extension method 'Volume' accepting a first argument of type 'IShape' could be found (are you missing a using directive or an assembly reference?)",
            "Blue -> CS0117: 'Color' does not contain a definition for 'Blue'",
            "Z -> CS1061: 'Point' does not contain a definition for 'Z' and no accessible extension method 'Z' accepting a first argument of type 'Point' could be found (are you missing a using directive or an assembly reference?)",
        ), errors(wrap("""
            calc.Nope();
            Calc.Static();
            Console.WriteLin("x");
            list.Nope3 = 1;
            shape.Volume();
            shape.ToString();
            Color.Blue.ToString();
            p.Z.ToString();
            p.X.ToString();
            p.Deconstruct(out var x, out var y);
            list.Count.ToString();
        """)))
    }

    fun testNothingOnWhatIsNotKnown() {
        // the base that is not there is an error of its own; nothing is said of what a value of that type has
        assertEquals(listOf("MissingBase CS0246"), codes(wrap("""
            o.Anything();
            t.a.ToString();
            t.Item1.ToString();
            dynamic dyn = o;
            dyn.Whatever();
            var anonymous = new { A = 1 };
            anonymous.A.ToString();
            var x = nameof(Missing);
            Odd odd = null!;
            odd.Whatever();
            Action act = null!;
            act.Invoke();
        """, members = """
            class Odd : MissingBase { }
            partial class Part { void M() { Generated(); this.Other(); } }
            int Value { get => 1; set => Console.WriteLine(value); }
        """)))
    }

    fun testNothingInMembersWithSyntaxErrors() {
        // the syntax error alone
        assertEquals(listOf("; CS1026"), codes(wrap("""
            missing(;
        """)))
    }

    /** The live case of 2026-10-08: an unterminated string in one statement, `this.db` (a primary-constructor parameter, no member) in another. */
    fun testASyntaxErrorSilencesItsStatementOnly() {
        val text = wrap("""
            int id = 1;
            Log("Customer {CustomerId} not found", id"); // log warn about
            this.nope.Add(id);
            calc.Nope();
        """)
        val found = codes(text)
        assertTrue(found.toString(), found.any { it.contains("CS1010") })
        // Roslyn binds the recovered tree: `Log` is nowhere (CS0103) even in the broken statement, and the other statements are checked
        assertEquals(found.toString(), listOf("Log CS0103", "nope CS1061", "Nope CS1061"), found.filter { it.endsWith("CS0103") || it.endsWith("CS1061") })
        // an error in the signature still silences the whole member
        assertEquals(emptyList<String>(), codes(wrap("", members = "void Broken(int { calc.Nope(); }")).filter { it.endsWith("CS1061") })
    }

    // ---- compiler-messages parity (0.1.143)

    fun testInconsistentAccessibility() {
        assertEquals(listOf("MyMethod CS0050"), codes("""
            class Hidden { }
            public class Shown { public static Hidden MyMethod() => new Hidden(); }
        """))
        assertEquals(listOf("F CS0051", "M CS0052", "P CS0053", "E CS7025", "Deleg CS0059", "Derived CS0060"), codes("""
            public class A
            {
                class B { }
                public static void F(B b) { }
                public B M;
                public B P { get; set; }
                public event System.Action<B> E;
                public delegate void Deleg(B b);
                public class Derived : B { }
                private B fine;
                internal B Also(B b) => b;   // internal member of a public type with a private type: CS0050 / CS0051 too
            }
        """).filter { it.endsWith("CS0050") || it.endsWith("CS0051") || it.endsWith("CS0052") || it.endsWith("CS0053") || it.endsWith("CS7025") || it.endsWith("CS0059") || it.endsWith("CS0060") }.take(6))
        // a protected member of a public type is seen by derived types of other assemblies: an internal type there is CS0051; in an
        // internal type the member is effectively private protected, and the internal type is fine
        assertEquals(listOf("M CS0051"), codes("""
            internal class Inner { }
            public class Open { protected void M(Inner i) { } internal Inner N() => null; }
            internal class Closed { protected void M(Inner i) { } }
        """).filter { it.endsWith("CS0051") || it.endsWith("CS0050") })
    }

    fun testModifiersNotValidForTheItem() {
        fun only106(text: String) = codes(text).filter { it.endsWith("CS0106") }
        assertEquals(listOf("virtual CS0106"), only106("public class C1 { public virtual int field; }"))
        assertEquals(listOf("static CS0106"), only106("public class C2 { public static int this[int i] => i; }"))
        assertEquals(listOf("readonly CS0106"), only106("public class C3 { public readonly void M() { } }"))
        assertEquals(emptyList<String>(), only106("public struct C4 { public readonly void M() { } }"))
        assertEquals(listOf("abstract CS0106"), only106("public class C5 { public abstract struct S { } }"))
        // `public` inside a block ends the block (as Roslyn parses it): the rest is a member of the class, nothing to say about the modifier
        assertEquals(listOf("public CS0106"), only106("public void TopLevel() { }"))
        assertEquals(listOf("i CS0504"), codes("public class K { static const int i = 0; }").filter { it.endsWith("CS0504") })
        assertEquals(listOf("private CS1527", "new CS1530"), codes("private class P1 { } new class P2 { }").filter { it.endsWith("CS1527") || it.endsWith("CS1530") })
    }

    fun testStructLayoutCycle() {
        assertEquals(listOf("other CS0523", "maybe CS0523", "Next CS0523", "Back CS0523"), codes("""
            struct Self { public Self other; public static Self fine; public Self? maybe; }
            struct Ring { public Link Next { get; set; } }
            struct Link { public Ring Back; }
            class Ok { public Ok self; }
        """).filter { it.endsWith("CS0523") }.distinct().take(4))
    }

    fun testPartialMethodsWithoutTheOtherHalf() {
        CSharpSemanticEnvironment.setGeneratedKnownForTests(true)
        assertEquals(listOf("Defined CS8795", "Implemented CS0759"), codes("""
            public partial class PartialType
            {
                public partial void Defined(int x);
                public partial void Implemented(int y) { }
                partial void Fine();
            }
        """).filter { it.endsWith("CS8795") || it.endsWith("CS0759") })
    }

    fun testNotAllPathsReturnInALocalFunctionOrWithAnUnknownReturnType() {
        assertEquals(listOf("Local CS0161"), codes("class L { void M() { int Local(int a) { if (a > 0) return 1; } } }").filter { it.endsWith("CS0161") })
        // `Task` nowhere (no using): Roslyn goes on with an error type, a value is expected
        assertEquals(listOf("Nowhere CS0246", "Run CS0161"), codes("class L2 { async Nowhere Run() { await System.Threading.Tasks.Task.Delay(1); } }").filter { it.endsWith("CS0161") || it.endsWith("CS0246") })
        assertEquals(emptyList<String>(), codes("using System.Threading.Tasks; class L3 { async Task Run() { await Task.Delay(1); } }").filter { it.endsWith("CS0161") })
    }

    // ---- ref rules (0.1.146), each verified against the Roslyn oracle (ref-probe.cs of the corpus)

    fun testRefReturnsAndRefLocals() {
        fun ref(text: String) = codes("class R { struct S { public int x; } readonly int ro = 0; readonly S rs; int plain; delegate ref int RefD(); delegate int ValD(); $text }")
            .filter { it.substringAfterLast(' ').let { c -> c.startsWith("CS81") || c.startsWith("CS83") || c == "CS1510" || c == "CS9059" } }
        assertEquals(listOf("p CS8166"), ref("ref int A(int p) { return ref p; }"))
        assertEquals(listOf("p.x CS8167"), ref("ref int A(S p) { return ref p.x; }"))
        assertEquals(listOf("s.x CS8169"), ref("ref int A() { S s; s.x = 1; return ref s.x; }"))
        assertEquals(listOf("r CS8157"), ref("ref int A(int p) { ref int r = ref p; return ref r; }"))
        assertEquals(listOf("p CS8333"), ref("ref int A(in int p) { return ref p; }"))
        assertEquals(emptyList<String>(), ref("ref readonly int A(in int p) { return ref p; } ref readonly int B() { return ref ro; } ref int C(ref int p) { return ref p; } ref int D(int[] a) { return ref a[0]; } ref int E() { return ref plain; }"))
        assertEquals(listOf("ro CS8160"), ref("ref int A() { return ref ro; }"))
        assertEquals(listOf("rs.x CS8162"), ref("ref int A() { return ref rs.x; }"))
        assertEquals(listOf("2 + 2 CS8156"), ref("ref int A() { return ref 2 + 2; }"))
        assertEquals(listOf("plain CS8150"), ref("ref int A() => plain;"))
        assertEquals(listOf("ref plain CS8149"), ref("int A() { return ref plain; }"))
        assertEquals(listOf("ref plain CS8149", "plain CS8150"), ref("void A() { ValD d = () => ref plain; RefD e = () => plain; RefD f = () => ref plain; }"))
        assertEquals(listOf("x + 1 CS1510", "l CS8173"), ref("void A() { int x = 1; ref int r = ref x; r = ref (x + 1); long l = 2; ref int q = ref l; }"))
        assertEquals(listOf("ref x CS8171", "x CS8172", "z CS8174"), ref("void A() { int x = 1; var y = ref x; ref int w = x; for (ref int z; x < 2; x++) { } }"))
        assertEquals(listOf("ref CS9059"), ref("ref int field;"))
        assertEquals(listOf("P CS8146", "set CS8147"), ref("ref int P { set { } } ref int Q { get => ref plain; set { } }").filter { it != "set CS8147" || true }.take(2))
    }

    fun testRefReturnsOfStructsAndInterfaces() {
        assertEquals(listOf("d CS8170"), codes("struct P { public int d; public ref int M() { return ref d; } }").filter { it.endsWith("CS8170") })
        assertEquals(emptyList<String>(), codes("struct P { public int d; [System.Diagnostics.CodeAnalysis.UnscopedRef] public ref int M() { return ref d; } }").filter { it.endsWith("CS8170") })
        assertEquals(listOf("GetNumber CS8148"), codes("class B { public virtual int GetNumber() => 0; } class D : B { int n; public override ref int GetNumber() { return ref n; } }").filter { it.endsWith("CS8148") })
        assertEquals(listOf("Test CS8152"), codes("interface ITest { ref readonly int M(); } class Test : ITest { public int M() => 0; }").filter { it.endsWith("CS8152") })
        assertEquals(listOf("CaptureArgument(ref localVariable) CS8347", "localVariable CS8168"), codes("""
            ref struct Entity { }
            class Program
            {
                static Entity CaptureArgument(ref int customArg) => new Entity();
                static Entity Example() { int localVariable = 1; return CaptureArgument(ref localVariable); }
            }
        """).filter { it.endsWith("CS8347") || it.endsWith("CS8168") })
    }

    fun testRefReturnsOfLibraryMembers() {
        // the index says `ref` (ReadOnlySpan<T>.this[], GetPinnableReference) and `ref readonly` (format 5); verified against Roslyn (ref-library-probe.cs)
        fun ref(text: String) = codes("using System; class R { $text }").filter { it.substringAfterLast(' ').let { c -> c.startsWith("CS8") || c == "CS1510" } }
        assertEquals(emptyList<String>(), ref("ref int A(Span<int> s) => ref s[0]; ref readonly int C(ReadOnlySpan<int> s) => ref s[0]; ref readonly int J(ReadOnlySpan<int> s) => ref s.GetPinnableReference();"))
        assertEquals(listOf("s[0] CS8333"), ref("ref int B(ReadOnlySpan<int> s) => ref s[0];"))
        assertEquals(listOf("s.GetPinnableReference() CS8333"), ref("ref int K(ReadOnlySpan<int> s) => ref s.GetPinnableReference();"))
        assertEquals(listOf("Math.Max(1, 2) CS8156", "s.Length CS8156"), ref("ref int E() => ref Math.Max(1, 2); ref int F(Span<int> s) => ref s.Length;"))
        assertEquals(listOf("s[0] CS8329"), ref("void G(ReadOnlySpan<int> s) { ref int r = ref s[0]; ref readonly int q = ref s[0]; }"))
        assertEquals(listOf("p CS8329", "q CS8329"), ref("void O(in int p, ref readonly int q) { ref int a = ref p; ref int b = ref q; ref readonly int c = ref p; }"))
        assertEquals(listOf("p CS8333", "w CS8156"), ref("ref int I(ref readonly int p) => ref p; ref int L(ref readonly int p) { ref readonly int w = ref p; return ref w; }"))
        assertEquals(listOf("x CS1510"), ref("void M() { int v = 0; ref readonly int x = ref v; ref int y = ref x; }"))
        assertEquals(listOf("arg2.Alice CS8334"), ref("ref int N(in int arg1, in (int Alice, int Bob) arg2) { return ref arg2.Alice; }"))
    }

    fun testAPrivateNestedTypeIsSeenByPrivateMembersOfItsOwner() {
        // the ref probe of 2026-10-08: `S` and the members are private in the same type, nothing to say
        assertEquals(emptyList<String>(), codes("class Owner { struct S { public int x; } readonly S rs; ref int A(S p) => ref p.x; }").filter { it.endsWith("CS0051") || it.endsWith("CS0052") || it.endsWith("CS0050") })
    }

    fun testAwaitOfSomethingNotAwaitable() {
        assertEquals(listOf("id CS1061"), codes("""
            using System.Threading.Tasks;
            class W { async Task M(int id, Task t, Task<int> u) { await id; await t; await u; } }
        """).filter { it.endsWith("CS1061") })
    }

    fun testANameThatIsNowhereIsPaintedAsUnresolved() {
        val infos = highlight(wrap("calc.Nope(); missing(); Unknown u = null;"))
        val unresolved = infos.filter { it.forcedTextAttributesKey == com.intellij.openapi.editor.colors.CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES }.map { it.description?.substringBefore(':') }
        assertEquals(listOf("CS1061", "CS0103", "CS0246"), unresolved)
    }

    fun testNothingWhenTheReferencesAreNotAllKnown() {
        assemblies = null
        assertEmpty(codes(wrap("missing();")))
    }

    fun testAGenericExtensionOfAReceiverOutsideItsConstraint() {
        // `day.AddEndpointFilter(...)`: `where TBuilder : IEndpointConventionBuilder` — `Twice<T>(this T) where T : Shape` the same way
        val text = """
            using System;
            using Fixture;

            class C
            {
                void M(Circle circle, DayOfWeek day)
                {
                    circle.Twice();
                    day.Twice();
                }
            }
        """
        assertEquals(listOf("Twice CS1061"), codes(text))
    }

    fun testNothingWhenTheProjectGeneratesSources() {
        CSharpSemanticEnvironment.setGeneratesForTests(true)
        assertEquals(listOf("Nope CS1061"), codes(wrap("missing(); calc.Nope();")))
    }

    /** D4: the source generators of the project have run in the helper and are fresh, so a partial type is as complete as any other. */
    fun testPartialTypesAreCheckedWhenTheGeneratorsHaveRun() {
        val members = """
            partial class Part { void M() { Generated(); this.Other(); } }
        """
        assertEmpty(codes(wrap("", members = members)))
        CSharpSemanticEnvironment.setGeneratedKnownForTests(true)
        // the types of the first file are declared again by the second: CS0101 there, as csc would say
        assertEquals(listOf("Generated CS0103", "Other CS1061"), codes(wrap("", members = members)).filterNot { it.endsWith("CS0101") })
    }

    /** The part a generator wrote is in another file, with `global::` names as generators write them (the JSON context of the playground). */
    fun testGeneratedPartInAnotherFile() {
        myFixture.addFileToProject("GeneratedContext.g.cs", """
            namespace Probe.Inner
            {
                internal partial class Context : global::System.IDisposable
                {
                    public static global::Probe.Inner.Context Default => null!;
                    public int Order => 0;
                    public void Dispose() { }
                }
            }
        """.trimIndent())
        val text = """
            namespace Probe.Inner;
            internal partial class Context { void M() { var a = Context.Default.Order; var b = Context.Default.Customer; var c = Context.Missing; } }
        """
        assertEmpty(codes(text))
        CSharpSemanticEnvironment.setGeneratedKnownForTests(true)
        assertEquals(listOf("Customer CS1061", "Missing CS0117"), codes(text))
    }

    /** 0.1.142: a `using` of a namespace that is nowhere is an error on the directive, and the rest is checked all the same (as Roslyn). */
    fun testAnUnknownImportIsAnErrorAndTheRestIsChecked() {
        assertEquals(listOf("Vendor CS0246", "Helper CS0103", "Thing CS0246"), codes("""
            using Vendor.Missing;
            class A { void M() { Helper.Run(); Thing t = null!; } }
        """))
        assertEquals(listOf("Nothing CS0234", "Alone CS0246"), codes("""
            using System.Nothing;
            using Alone;
            class UnknownImports { }
        """))
    }

    /** DEV_JOURNEY 5.5 (0.1.100): an implementation without the default value of its interface, a constructor, an awaited call — before the build. */
    fun testMissingArgumentsOfImplementationsAndConstructors() {
        assertEquals(listOf(
            "AddAsync -> CS7036: There is no argument given that corresponds to the required parameter 'ct' of 'Errors.Repo.AddAsync(Point, CancellationToken)'",
            "AddAsync -> CS7036: There is no argument given that corresponds to the required parameter 'ct' of 'Errors.Repo.AddAsync(Point, CancellationToken)'",
            "Repo -> CS7036: There is no argument given that corresponds to the required parameter 'name' of 'Errors.Repo.Repo(string)'",
            "Calc -> CS1729: 'Calc' does not contain a constructor that takes 2 arguments",
            "Repo -> CS1729: 'Repo' does not contain a constructor that takes 2 arguments",
        ), errors(wrap("""
            var repo = new Repo("a");
            repo.AddAsync(p);
            IRepo face = repo;
            face.AddAsync(p);
            var q = new Point(1, 2);
            var r = new Repo();
            var c = new Calc(1, 2);
            var c2 = new Calc();
            var r2 = new Repo("a", 2);
        """, """
            public interface IRepo { System.Threading.Tasks.Task AddAsync(Point p, System.Threading.CancellationToken ct = default); }
            public class Repo : IRepo
            {
                public Repo(string name) { }
                public System.Threading.Tasks.Task AddAsync(Point p, System.Threading.CancellationToken ct) => System.Threading.Tasks.Task.CompletedTask;
            }
            private async System.Threading.Tasks.Task Run() { await new Repo("b").AddAsync(new Point(1, 2)); }
        """)))
    }

    fun testArguments() {
        assertEquals(listOf(
            "Add -> CS7036: There is no argument given that corresponds to the required parameter 'b' of 'Calc.Add(int, int)'",
            "Add -> CS1501: No overload for method 'Add' takes 3 arguments",
            "Two -> CS1501: No overload for method 'Two' takes 0 arguments",
            "Two -> CS1501: No overload for method 'Two' takes 3 arguments",
            "Twice -> CS7036: There is no argument given that corresponds to the required parameter 'a' of 'Calc.Twice(int)'",
            "Max -> CS1501: No overload for method 'Max' takes 1 arguments",
            // named arguments since D2 (0.1.78)
            "Add -> CS7036: There is no argument given that corresponds to the required parameter 'a' of 'Calc.Add(int, int)'",
        ), errors(wrap("""
            calc.Add(1);
            calc.Add(1, 2, 3);
            calc.Two();
            calc.Two(1, 2, 3);
            Calc.Twice();
            Math.Max(1);
            calc.Add(1, 2);
            Math.Max(1, 2);
            Console.WriteLine("{0} {1} {2}", 1, 2, 3);
            calc.Add(b: 1);
        """)))
    }

    /**
     * C# 7.2 non-trailing named arguments: a named argument in its own position lets positional ones follow (the playground's
     * `Draw(width: 1, "t", shape: shape)` got a false CS7036 on `title`, robot 0.1.117); out of position it does not (CS8323 in Roslyn,
     * nothing here); CS7036 names the first parameter no argument is bound to, by position or by name.
     */
    fun testNamedArgumentsBindAsRoslynBindsThem() {
        assertEquals(listOf(
            "Draw -> CS7036: There is no argument given that corresponds to the required parameter 'title' of 'Errors.Draw(int, string, int)'",
            "Draw -> CS7036: There is no argument given that corresponds to the required parameter 'title' of 'Errors.Draw(int, string, int)'",
            "Draw -> CS7036: There is no argument given that corresponds to the required parameter 'shape' of 'Errors.Draw(int, string, int)'",
        ), errors(wrap("""
            Draw(width: 1, "t", shape: 2);
            Draw(1, title: "t", shape: 2);
            Draw(width: 1, title: "t", 2);
            Draw(shape: 2, width: 1, title: "t");
            Draw(title: "t", 1, 2);
            Log(format: "x", 1, 2);
            Log("x", args: new object[] { 1 });
            Draw(width: 1, shape: 2);
            Draw(1, shape: 2);
            Draw(title: "t", width: 1);
        """, """
            void Draw(int width, string title, int shape) { }
            void Log(string format, params object[] args) { }
        """)))
    }

    fun testConversions() {
        assertEquals(listOf(
            "\"three\" -> CS0029: Cannot implicitly convert type 'string' to 'int'",
            "1.5 -> CS0266: Cannot implicitly convert type 'double' to 'int'. An explicit conversion exists (are you missing a cast?)",
            "d -> CS0266: Cannot implicitly convert type 'double' to 'int'. An explicit conversion exists (are you missing a cast?)",
            "5 -> CS0029: Cannot implicitly convert type 'int' to 'string'",
            "list -> CS0029: Cannot implicitly convert type 'System.Collections.Generic.List<int>' to 'string'",
            "n -> CS0266: Cannot implicitly convert type 'int?' to 'int'. An explicit conversion exists (are you missing a cast?)",
            "l -> CS0266: Cannot implicitly convert type 'long' to 'int'. An explicit conversion exists (are you missing a cast?)",
            "1 -> CS0029: Cannot implicitly convert type 'int' to 'bool'",
            "\"c\" -> CS0029: Cannot implicitly convert type 'string' to 'char'",
        ), errors(wrap("""
            int count = "three";
            int fromDouble = 1.5;
            int fromD = d;
            string s = 5;
            string s2 = list;
            int fromN = n;
            int fromL = l;
            bool b = 1;
            char c = "c";
            byte small = 1;
            long wide = 1;
            double fine = 1;
            int? maybe = 1;
            object any = "x";
            IEnumerable<int> e = list;
            string fromNull = null!;
            int fromTernary = b ? 1 : 2;
            const long big = 1;
            uint u = big;
        """)))
    }

    fun testReturns() {
        assertEquals(listOf(
            "\"x\" -> CS0029: Cannot implicitly convert type 'string' to 'int'",
            "1 -> CS0029: Cannot implicitly convert type 'int' to 'string'",
            "NoReturn -> CS0161: 'Errors.NoReturn(int)': not all code paths return a value",
            "get -> CS0161: 'Errors.Prop2.get': not all code paths return a value",
        ), errors(wrap("", members = """
            public int Ret() { return "x"; }
            public string Ret2() => 1;
            public int NoReturn(int a) { if (a > 0) return 1; }
            public int Prop2 { get { if (Prop3) return 1; } }
            public bool Prop3 { get; set; }
            public int Loop() { while (true) { } }
            public int Throws() { throw new Exception(); }
            public int Both(int a) { if (a > 0) return 1; else return 2; }
            public int Switched(int a) { switch (a) { case 1: return 1; default: return 2; } }
            public int Tried() { try { return 1; } finally { } }
            public int Constant() { const bool yes = true; while (yes) { } }
            public IEnumerable<int> Iterator() { yield return 1; }
            public async System.Threading.Tasks.Task NoValue() { }
        """)))
    }

    fun testAttributes() {
        assertEquals(listOf(
            "Missing -> CS0246: The type or namespace name 'MissingAttribute' could not be found (are you missing a using directive or an assembly reference?)",
            "Missing -> CS0246: The type or namespace name 'Missing' could not be found (are you missing a using directive or an assembly reference?)",
        ), errors(wrap("", members = """
            [Missing] public void Attr() { }
            [Obsolete] public void Attr2() { }
        """)))
    }

    fun testQualifiedNames() {
        assertEquals(listOf("Foo CS0234", "System.Nothing CS0234"), codes(wrap("""
            System.Foo.Bar x = null!;
            System.Nothing();
        """)))
    }

    fun testUnusedUsings() {
        highlight("""
            using System;
            using System.Text;
            using System.Collections.Generic;
            using System.Linq;

            class A { void M(List<int> list) { Console.WriteLine(list.Count); } }
        """)
        val document = myFixture.editor.document.text
        val gray = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.description == "Unnecessary using directive." }
            .map { document.substring(it.startOffset, it.endOffset) }
        assertEquals(listOf("using System.Text;", "using System.Linq;"), gray)
        myFixture.editor.caretModel.moveToOffset(document.indexOf("System.Text"))
        myFixture.launchAction(myFixture.findSingleIntention("Remove unused directives in file"))
        assertEquals("""
            using System;
            using System.Collections.Generic;

            class A { void M(List<int> list) { Console.WriteLine(list.Count); } }
        """.trimIndent(), myFixture.editor.document.text)
    }

    fun testUsedByExtensionMethods() {
        highlight("""
            using System.Collections.Generic;
            using System.Linq;

            class A { int M(List<int> list) => list.First(); }
        """)
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.description == "Unnecessary using directive." })
    }

    fun testImportType() {
        myFixture.configureByText("Import${files++}.cs", """
            namespace App;

            class A { void M() { var list = new List<caret><int>(); } }
        """.trimIndent())
        myFixture.doHighlighting()
        val fix = myFixture.findSingleIntention("Import 'System.Collections.Generic.List'")
        myFixture.launchAction(fix)
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("using System.Collections.Generic;\n\nnamespace App;"))
    }

    fun testMissingAbstractAndInterfaceMembers() {
        assertEquals(listOf(
            "Circle -> CS0534: 'Shop.Circle' does not implement inherited abstract member 'Shop.Shape.Area()'",
            "IDisposable -> CS0535: 'Shop.Circle' does not implement interface member 'System.IDisposable.Dispose()'",
            "IComparable<Circle> -> CS0535: 'Shop.Circle' does not implement interface member 'System.IComparable<Shop.Circle>.CompareTo(Shop.Circle?)'",
        ), errors("""
            using System;
            namespace Shop;
            public abstract class Shape
            {
                public abstract double Area();
                public virtual string Describe() => "";
            }
            public class Circle : Shape, IDisposable, IComparable<Circle>
            {
            }
        """))
        val fine = """
            using System;
            using System.Collections.Generic;
            namespace Shop;
            public abstract class Shape { public abstract double Area(); }
            public abstract class Half : Shape, IDisposable { public abstract void Dispose(); }
            public class Done : Half { public override double Area() => 0; public override void Dispose() { } }
            public class Explicit : IDisposable { void IDisposable.Dispose() { } }
            public class Base : IDisposable { void IDisposable.Dispose() { } }
            public class Again : Base, IDisposable { }
            public abstract class StillAbstract : Shape { }
            public partial class Parted : IDisposable { }
            public record Rec(int X) : IEquatable<Rec>;
            public class Numbers : List<int>, IEnumerable<int> { }
        """
        assertEquals("nothing is missing, or nothing sure: ${errors(fine)}", emptyList<String>(), errors(fine).filter { "CS0534" in it || "CS0535" in it })
        assertEquals("a syntax error in the type silences it", emptyList<String>(),
            errors("using System;\nnamespace Shop;\npublic class Typing : IDisposable\n{\n    public void Dis(\n}\n").filter { "CS0535" in it })
    }

    fun testAltEnterOnTheMissingMemberError() {
        myFixture.configureByText("SemanticErrors${files++}.cs", "using System;\nnamespace Shop;\npublic class Res : IDisp<caret>osable\n{\n}\n")
        myFixture.launchAction(myFixture.findSingleIntention("Implement missing members"))
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("public void Dispose()"))
        assertTrue(highlight(myFixture.editor.document.text).none { it.description?.startsWith("CS0535") == true })
    }
}
