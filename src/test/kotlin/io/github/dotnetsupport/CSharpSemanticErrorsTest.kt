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

    fun testNothingWhenAnImportIsUnknown() {
        assertEmpty(codes("""
            using Vendor.Missing;
            class A { void M() { Helper.Run(); Thing t = null!; } }
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
