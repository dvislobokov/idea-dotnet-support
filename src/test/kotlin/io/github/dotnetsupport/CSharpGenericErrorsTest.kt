package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Type arguments and constraints of the native pass (CSharpGenericChecks): CS0305, CS0308, CS0310, CS0311, CS0315, CS0452, CS0453 with
 * Roslyn's texts and spans (checked against `dotnet build`), and silence where an argument or a constraint is not known.
 */
class CSharpGenericErrorsTest : BasePlatformTestCase() {
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
    private fun errors(text: String): List<String> {
        myFixture.configureByText("GenericErrors${files++}.cs", PRELUDE + text.trimIndent())
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
            .sortedWith(compareBy({ it.startOffset }, { it.description })).map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    private fun codes(text: String): List<String> = errors(text).map { it.substringBefore(" -> ") + " " + it.substringAfter(" -> ").substringBefore(':') }

    private fun body(code: String): String = "public class Uses\n{\n    public void Run(object o)\n    {\n" + code.trimIndent().lines().joinToString("\n") { "        $it" } + "\n    }\n}\n"

    fun testConstraintTypesInCode() {
        assertEquals(listOf(
            "string -> CS0311: The type 'string' cannot be used as type parameter 'T' in the generic type or method 'Kennel<T>'. There is no implicit reference conversion from 'string' to 'P.Animal'.",
            "Customer -> CS0311: The type 'P.Customer' cannot be used as type parameter 'T' in the generic type or method 'Sorter<T>'. There is no implicit reference conversion from 'P.Customer' to 'System.IComparable<P.Customer>'.",
            "int -> CS0315: The type 'int' cannot be used as type parameter 'T' in the generic type or method 'Kennel<T>'. There is no boxing conversion from 'int' to 'P.Animal'.",
            "string -> CS0311: The type 'string' cannot be used as type parameter 'T' in the generic type or method 'Kennel<T>'. There is no implicit reference conversion from 'string' to 'P.Animal'.",
            "string -> CS0311: The type 'string' cannot be used as type parameter 'T' in the generic type or method 'Kennel<T>'. There is no implicit reference conversion from 'string' to 'P.Animal'.",
        ), errors(body("""
            Kennel<string>? a = null;
            var s = new Sorter<Customer>();
            var k = new Kennel<int>();
            var t = typeof(Kennel<string>);
            List<Kennel<string>>? l = null;
            Kennel<Dog> dogs = new();
            Sorter<string> names = new();
            Sorter<Version> versions = new();
            var count = Kennel<Dog>.Count;
            Console.WriteLine($"{a}{s}{k}{t}{l}{dogs}{names}{versions}{count}");
        """)))
    }

    fun testSignaturesReportOnTheMember() {
        // Roslyn checks the types of a member's signature after binding it: the error is on the name of the member or parameter
        assertEquals(listOf(
            "_field CS0311", "Prop CS0311", "Method CS0311", "p CS0311", "c CS0311", "Derived CS0311", "T CS0311", "U CS0311",
        ), codes("""
            public class Holder
            {
                private Kennel<string>? _field;
                public Kennel<string>? Prop { get; set; }
                public Kennel<string>? Method(Kennel<string>? p) => null;
                public Holder(Kennel<string>? c) { }
            }
            public class Derived : Box<Kennel<string>> { }
            public class Gen<T> where T : Kennel<string> { }
            public class Gm { public void M<U>() where U : Kennel<string> { } }
        """))
    }

    fun testCallsReportOnTheName() {
        assertEquals(listOf(
            "Make<string> -> CS0310: 'string' must be a non-abstract type with a public parameterless constructor in order to use it as parameter 'T' in the generic type or method 'F.Make<T>()'",
            "Make<string> -> CS0311: The type 'string' cannot be used as type parameter 'T' in the generic type or method 'F.Make<T>()'. There is no implicit reference conversion from 'string' to 'P.Animal'.",
            "Take<string> -> CS0311: The type 'string' cannot be used as type parameter 'T' in the generic type or method 'F.Take<T>(T)'. There is no implicit reference conversion from 'string' to 'P.Animal'.",
            "Pair<string, int> -> CS0311: The type 'string' cannot be used as type parameter 'T' in the generic type or method 'F.Pair<T, U>()'. There is no implicit reference conversion from 'string' to 'P.Animal'.",
            "Pair<string, int> -> CS0315: The type 'int' cannot be used as type parameter 'U' in the generic type or method 'F.Pair<T, U>()'. There is no boxing conversion from 'int' to 'P.Animal'.",
        ), errors(body("""
            var f = F.Make<string>();
            F.Take<string>("x");
            F.Pair<string, int>();
            F.Pair<Dog, Dog>();
            var dog = F.Make<Dog>();
            F.Take(new Dog());
        """)))
    }

    fun testClassStructAndNew() {
        assertEquals(listOf(
            "int -> CS0452: The type 'int' must be a reference type in order to use it as parameter 'T' in the generic type or method 'Cache<T>'",
            "int -> CS0452: The type 'int' must be a reference type in order to use it as parameter 'T' in the generic type or method 'WeakReference<T>'",
            "string -> CS0453: The type 'string' must be a non-nullable value type in order to use it as parameter 'T' in the generic type or method 'Range<T>'",
            "int? -> CS0453: The type 'int?' must be a non-nullable value type in order to use it as parameter 'T' in the generic type or method 'Range<T>'",
            "Customer -> CS0453: The type 'Customer' must be a non-nullable value type in order to use it as parameter 'T' in the generic type or method 'Nullable<T>'",
            "Hidden -> CS0310: 'Hidden' must be a non-abstract type with a public parameterless constructor in order to use it as parameter 'T' in the generic type or method 'Factory<T>'",
            "Shape -> CS0310: 'Shape' must be a non-abstract type with a public parameterless constructor in order to use it as parameter 'T' in the generic type or method 'Factory<T>'",
            "Person -> CS0310: 'Person' must be a non-abstract type with a public parameterless constructor in order to use it as parameter 'T' in the generic type or method 'Factory<T>'",
            "GetValues<int> -> CS0315: The type 'int' cannot be used as type parameter 'TEnum' in the generic type or method 'Enum.GetValues<TEnum>()'. There is no boxing conversion from 'int' to 'System.Enum'.",
        ), errors(body("""
            Cache<int>? a = null;
            var w = new WeakReference<int>(1);
            Range<string>? b = null;
            var c = new Range<int?>();
            Nullable<Customer> d = null;
            var e = new Factory<Hidden>();
            var g = new Factory<Shape>();
            var h = new Factory<Person>();
            var colors = Enum.GetValues<Color>();
            var ints = Enum.GetValues<int>();
            var ok1 = new Cache<string>();
            var ok2 = new Cache<int[]>();
            var ok3 = new Range<Color>();
            var ok4 = new Factory<Dog>();
            var ok5 = new Factory<Point>();
            var ok6 = new Factory<Unit>();
            var ok7 = new WeakReference<Customer>(new Customer());
        """)))
    }

    fun testArity() {
        assertEquals(listOf(
            "Box<int, int> -> CS0305: Using the generic type 'Box<T>' requires 1 type arguments",
            "List<int, int> -> CS0305: Using the generic type 'List<T>' requires 1 type arguments",
            "List -> CS0305: Using the generic type 'List<T>' requires 1 type arguments",
            "Dictionary<string> -> CS0305: Using the generic type 'Dictionary<TKey, TValue>' requires 2 type arguments",
            "Customer<int> -> CS0308: The non-generic type 'Customer' cannot be used with type arguments",
            "Inner<int> -> CS0308: The non-generic type 'Outer.Inner' cannot be used with type arguments",
            "Box<int, int> -> CS0305: Using the generic type 'Box<T>' requires 1 type arguments",
            "Make<int, int> -> CS0305: Using the generic method 'F.Make<T>()' requires 1 type arguments",
            "Plain<int> -> CS0308: The non-generic method 'F.Plain()' cannot be used with type arguments",
            "Over<int, int> -> CS0305: Using the generic method 'F.Over<T>(int)' requires 1 type arguments",
            "Store<int, int> -> CS0305: Using the generic method 'F.Store<T>(T)' requires 1 type arguments",
        ), errors(body("""
            Box<int, int>? a = null;
            List<int, int>? b = null;
            List? c = null;
            var d = new Dictionary<string>();
            Customer<int>? e = null;
            Outer.Inner<int>? i = null;
            var g = new System.Collections.Generic.List<int>();
            var h = new Box<int, int>();
            F.Make<int, int>();
            F.Plain<int>();
            F.Over<int, int>();
            F.Over();
            F.Over<int>(1);
            var j = typeof(Box<>);
            var k = typeof(Dictionary<,>);
            F.Store<int, int>(1);
        """)))
    }

    fun testArityLeftOfAMemberAccess() {
        assertEquals(listOf(
            "Counter<int, int> -> CS0305: Using the generic type 'Counter<T>' requires 1 type arguments",
            "Counter -> CS0305: Using the generic type 'Counter<T>' requires 1 type arguments",
            "Plain<int> -> CS0308: The non-generic type 'Plain' cannot be used with type arguments",
            "Counter<int, int> -> CS0305: Using the generic type 'Counter<T>' requires 1 type arguments",
            "Counter -> CS0305: Using the generic type 'Counter<T>' requires 1 type arguments",
            "Counter<int, int> -> CS0305: Using the generic type 'Counter<T>' requires 1 type arguments",
        ), errors("""
            public class Counter<T> { public static int Count; public static int Get() => 0; }
            public class Plain { public static int Count; }
            public class Uses
            {
                public int Counter2 => 0;
                public void Run(Plain value)
                {
                    var a = Counter<int, int>.Count;
                    var b = Counter.Count;
                    var c = Plain<int>.Count;
                    var d = Counter<int, int>.Get();
                    Counter.Get();
                    var e = P.Counter<int, int>.Count;
                    var ok1 = Counter<int>.Count;
                    var ok2 = Plain.Count;
                    var ok3 = value.ToString();
                    Console.WriteLine($"{a}{b}{c}{d}{e}{ok1}{ok2}{ok3}");
                }
            }
        """))
    }

    fun testExtensionMethodsOfNamespacesNotImported() {
        // `Twice` and `Sum` are extension methods of the fixture assembly's namespace `Fixture`: Roslyn does not look there from a file
        // without its `using` (in the IDE: Vector.Store of System.Numerics, EF Core's Like silenced `Make.Store<int, int>(1)`, `Make.Like("text")`)
        val tools = """
            public static class Tools
            {
                public static T Twice<T>(T value) => value;
                public static void Sum<T>(T value) where T : new() { }
            }
            public class Uses
            {
                public void Run()
                {
                    Tools.Twice<int, int>(1);
                    Tools.Sum("text");
                }
            }
        """
        assertEquals(listOf(
            "Twice<int, int> -> CS0305: Using the generic method 'Tools.Twice<T>(T)' requires 1 type arguments",
            "Sum -> CS0310: 'string' must be a non-abstract type with a public parameterless constructor in order to use it as parameter 'T' in the generic type or method 'Tools.Sum<T>(T)'",
        ), errors(tools))
        // with the namespace imported the extension methods are candidates too: what Roslyn makes of them is not modeled, nothing is said
        myFixture.configureByText("GenericErrors${files++}.cs", "using Fixture;\n\nnamespace Q;\n\n" + tools.trimIndent())
        assertEquals(emptyList<String>(), myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull { it.description?.takeIf { d -> d.startsWith("CS") } })
    }

    fun testInferredTypeArguments() {
        // Roslyn checks the inferred arguments as written ones, on the method's name, the type without its nullable annotation
        assertEquals(listOf(
            "Ref -> CS0452: The type 'int' must be a reference type in order to use it as parameter 'T' in the generic type or method 'Infer.Ref<T>(T)'",
            "Val -> CS0453: The type 'string' must be a non-nullable value type in order to use it as parameter 'T' in the generic type or method 'Infer.Val<T>(T)'",
            "Val -> CS0453: The type 'string' must be a non-nullable value type in order to use it as parameter 'T' in the generic type or method 'Infer.Val<T>(T)'",
            "Nw -> CS0310: 'string' must be a non-abstract type with a public parameterless constructor in order to use it as parameter 'T' in the generic type or method 'Infer.Nw<T>(T)'",
            "Ani -> CS0311: The type 'P.Customer' cannot be used as type parameter 'T' in the generic type or method 'Infer.Ani<T>(T)'. There is no implicit reference conversion from 'P.Customer' to 'P.Animal'.",
            "Ref -> CS0452: The type 'int?' must be a reference type in order to use it as parameter 'T' in the generic type or method 'Infer.Ref<T>(T)'",
            "Nums -> CS0453: The type 'string' must be a non-nullable value type in order to use it as parameter 'T' in the generic type or method 'Infer.Nums<T>(List<T>)'",
        ), errors("""
            public static class Infer
            {
                public static T Ref<T>(T v) where T : class => v;
                public static void Val<T>(T v) where T : struct { }
                public static void Nw<T>(T v) where T : new() { }
                public static void Two<T>(T a, T b) where T : struct { }
                public static void Ani<T>(T v) where T : Animal { }
                public static void Over<T>(T v) where T : class { }
                public static void Over(int a, int b) { }
                public static void Nums<T>(List<T> v) where T : struct { }
                public static void Seq<T>(IEnumerable<T> v) where T : struct { }
            }
            public class Uses
            {
                public void Run(string? maybe, int? number)
                {
                    Infer.Ref(5);
                    Infer.Ref("s");
                    Infer.Val("s");
                    Infer.Val(maybe);
                    Infer.Nw("s");
                    Infer.Two(1, 2L);
                    Infer.Ani(new Customer());
                    Infer.Ani(new Dog());
                    Infer.Ref(number);
                    // Roslyn: CS0452 (the overload is left out), CS0453 (inferred through the interface); not modeled
                    Infer.Over(5);
                    Infer.Nums(new List<string>());
                    Infer.Seq(new List<string>());
                    Infer.Val(1);
                }
            }
        """))
    }

    fun testTypesNestedInGenericTypesAndTheirMethods() {
        assertEquals(listOf(
            "Put<int> -> CS0452: The type 'int' must be a reference type in order to use it as parameter 'U' in the generic type or method 'Holder<string>.Put<U>(string, U)'",
            "int -> CS0452: The type 'int' must be a reference type in order to use it as parameter 'U' in the generic type or method 'Holder<long>.Inner<U>'",
            "Put -> CS0452: The type 'int' must be a reference type in order to use it as parameter 'U' in the generic type or method 'Holder<long>.Put<U>(long, U)'",
            "int[] -> CS0310: 'int[]' must be a non-abstract type with a public parameterless constructor in order to use it as parameter 'T' in the generic type or method 'Factory<T>'",
        ), errors("""
            public class Holder<T>
            {
                public static void Put<U>(T t, U u) where U : class { }
                public class Inner<U> where U : class { }
            }
            public class Uses
            {
                public void Run()
                {
                    Holder<string>.Put<int>("a", 1);
                    var i = new Holder<long>.Inner<int>();
                    Holder<long>.Put(1L, 2);
                    var f = new Factory<int[]>();
                    var ok = new Holder<long>.Inner<string>();
                    Holder<string>.Put("a", "b");
                    Console.WriteLine($"{i}{f}{ok}");
                }
            }
        """))
    }

    fun testSignaturesOfEveryKindOfMember() {
        assertEquals(listOf(
            "A CS0452", "B CS0452", "this CS0452", "c CS0452", "+ CS0452", "c CS0452", "Cache<int> CS0452", "E CS0452", "int CS0452", "Rec CS0452", "R CS0452", "p CS0452",
        ), codes("""
            public class Attr<T> : Attribute where T : class { }
            public class Sig
            {
                public Cache<int>? A, B;
                public Cache<int> this[int i] => null!;
                public object this[Cache<int> c, int j] => 0;
                public static Cache<int>? operator +(Sig s, Cache<int> c) => null;
                public static implicit operator Cache<int>(Sig s) => null!;
                public event Action<Cache<int>>? E;
                [Attr<int>] public void M() { }
            }
            public record Rec(Cache<int> R);
            public class Prim(Cache<int> p) { public object O => p; }
        """))
    }

    fun testNothingWhereSomethingIsNotKnown() {
        assertEquals(emptyList<String>(), codes("""
            public partial class Generated { }
            public class Generic<T> where T : Animal
            {
                public Kennel<T>? Own;
                public void Run<U>() where U : class
                {
                    Kennel<T>? a = null;
                    Kennel<U>? b = null;
                    Kennel<Missing>? c = null;
                    Kennel<dynamic>? d = null;
                    Cache<U>? e = null;
                    var f = F.Make<T>();
                    Kennel<Generated>? g = null;
                    Factory<Generated>? h = null;
                    Console.WriteLine($"{a}{b}{c}{d}{e}{f}{g}{h}");
                }
            }
            public class Overloads
            {
                public static void Store<T>(T x) where T : Animal { }
                public static void Store<T>(T x, int y) where T : class { }
                public static void Same<T>(int x) where T : Animal { }
                public static void Same<T>(string x) where T : Animal { }
                public void Run() { Same<string>(default); }
            }
        """).filter { !it.startsWith("Missing ") })
    }

    companion object {
        private val PRELUDE = """
            using System;
            using System.Collections.Generic;

            namespace P;

            public class Animal { }
            public class Dog : Animal { }
            public class Customer { }
            public class Hidden { private Hidden() { } }
            public abstract class Shape { }
            public record Person(string Name);
            public class Unit { public Unit() { } }
            public struct Point { public int X; }
            public enum Color { Red }
            public class Version : IComparable<Version> { public int CompareTo(Version? other) => 0; }
            public class Kennel<T> where T : Animal { public static int Count; }
            public class Sorter<T> where T : IComparable<T> { }
            public class Cache<T> where T : class { }
            public class Range<T> where T : struct { }
            public class Factory<T> where T : new() { }
            public class Box<T> { }
            public class Outer { public class Inner { } }
            public static class F
            {
                public static T Make<T>() where T : Animal, new() => new T();
                public static void Take<T>(T x) where T : Animal { }
                public static void Pair<T, U>() where T : Animal where U : Animal { }
                public static void Plain() { }
                public static void Over() { }
                public static void Over<T>(int x) { }
                public static void Store<T>(T value) { }
            }

        """.trimIndent() + "\n"
    }
}
