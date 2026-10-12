package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Duplicate declarations and declaration order of the native pass ([io.github.dotnetsupport.lang.semantic.CSharpDeclarationChecks]):
 * CS0128, CS0136, CS1930, CS1931, CS0841, CS0844, CS0100, CS0102, CS0111, CS0663, CS0557, CS0756, CS0757, CS8646, CS0101, CS0264 with
 * Roslyn's texts and spans (every expectation checked against `dotnet build`), and silence where C# allows the name or the verdict is not sure.
 */
class CSharpDeclarationErrorsTest : BasePlatformTestCase() {
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

    /** Each error as `text under it -> CSxxxx: message`, in the order of the file. */
    private fun errors(text: String, name: String = "DeclarationErrors${files++}.cs"): List<String> {
        myFixture.configureByText(name, "using System;\nusing System.Collections.Generic;\n\n" + text.trimIndent())
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
            .sortedWith(compareBy({ it.startOffset }, { it.description })).map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    private fun codes(text: String): List<String> = errors(text).map { it.substringBefore(" -> ") + " " + it.substringAfter(" -> ").substringBefore(':') }

    fun testLocalsDeclaredTwiceInOneScope() {
        assertEquals(listOf(
            "total -> CS0128: A local variable or function named 'total' is already defined in this scope",
            "n -> CS0128: A local variable or function named 'n' is already defined in this scope",
            "message -> CS0128: A local variable or function named 'message' is already defined in this scope",
            "Log -> CS0128: A local variable or function named 'Log' is already defined in this scope",
        ), errors("""
            namespace Locals1;
            class C
            {
                void M(object a, object b, int code)
                {
                    var total = 1;
                    var total = 2;
                    if (a is int n && b is int n) { }
                    switch (code)
                    {
                        case 1: var message = ""; break;
                        case 2: var message = ""; break;
                    }
                    switch (a) { case int v: break; case long v: break; }
                    void Log() { }
                    void Log() { }
                    for (var i = 0; i < 1; i++) { }
                    for (var i = 0; i < 1; i++) { }
                    { var s = 1; }
                    { var s = 2; }
                    Console.WriteLine(int.TryParse("", out var _) && int.TryParse("", out var _));
                    Func<int, int, int> f = (_, _) => 0;
                }
            }
        """))
    }

    fun testShadowingInTheSameFunction() {
        assertEquals(listOf(
            "id CS0136", "sum CS0136", "e CS0136", "number CS0136", "y CS0136", "index CS0136", "value CS0136",
        ), codes("""
            namespace Locals2;
            class C(int seed)
            {
                int _field;
                void M(int id, int[] items, object a, object b, int factor)
                {
                    foreach (var item in items) { var id = item; }
                    { var sum = 1; }
                    var sum = 0;
                    var e = "";
                    try { } catch (Exception e) { }
                    if (a is int number) { } else if (b is int number) { }
                    Func<int, int> f = y => { var y = 1; return y; };
                    Func<int, int> g = x => { var factor = 2; return x; };
                    Func<int, int> h = factor => factor;
                    int Local(int factor) { var sum = factor; return sum; }
                    var seed = 1;
                    var _field = 2;
                }
                int this[int index] { get { var index = 1; return index; } }
                int P { get => 0; set { var value = 1; } }
            }
        """))
    }

    fun testSwitchCatchQueryAndInactiveCode() {
        // as `dotnet build` says: a case pattern is in its section, the locals of the sections in the switch block; a catch filter is in the catch
        assertEquals(listOf("n CS0136", "m CS0136", "e CS0136", "x CS0841", "ex CS0128"), codes("""
            using System.Linq;
            namespace Locals5;
            class C
            {
                void M(object o)
                {
                    switch (o)
                    {
                        case int n: var n = 1; break;
                        case long m: break;
                        case string: var m = 1; break;
                    }
                    try { } catch (Exception e) { var e = 1; }
                    static void F() => Console.WriteLine(x);
                    int x = 1;
                    try { } catch (Exception ex) when (ex.Message is string ex) { }
                    var q = from s in new[] { "a" } where int.TryParse(s, out var o) select s;
            #if DEBUG
                    int dbg = 1;
            #else
                    int dbg = 2;
            #endif
                }
            #if DEBUG
                void D() { }
            #else
                void D() { }
            #endif
            }
        """).filter { !it.startsWith("x CS8421") })
    }

    fun testUseBeforeDeclaration() {
        assertEquals(listOf(
            "count -> CS0841: Cannot use local variable 'count' before it is declared",
            "step -> CS0841: Cannot use local variable 'step' before it is declared",
            "number -> CS0841: Cannot use local variable 'number' before it is declared",
        ), errors("""
            namespace Locals3;
            class C
            {
                int total;
                void M(object o)
                {
                    Console.WriteLine(count);
                    var count = 1;
                    Func<int> later = () => step;
                    var step = 2;
                    if (number > 0 && o is int number) { }
                    Helper();
                    void Helper() { }
                }
            }
        """))
    }

    fun testUseBeforeDeclarationThatHidesAField() {
        // Roslyn looks the name up in the type: a single field is CS0844, a property or a method still CS0841
        assertEquals(listOf(
            "total -> CS0844: Cannot use local variable 'total' before it is declared. The declaration of the local variable hides the field 'C.total'.",
            "Limit -> CS0844: Cannot use local variable 'Limit' before it is declared. The declaration of the local variable hides the field 'C.Limit'.",
            "Size -> CS0841: Cannot use local variable 'Size' before it is declared",
            "Run -> CS0841: Cannot use local variable 'Run' before it is declared",
            "name -> CS0844: Cannot use local variable 'name' before it is declared. The declaration of the local variable hides the field 'Base.name'.",
            "value -> CS0844: Cannot use local variable 'value' before it is declared. The declaration of the local variable hides the field 'Box<T>.value'.",
        ), errors("""
            namespace Locals4;
            class C
            {
                int total;
                const int Limit = 1;
                int Size { get; set; }
                void Run() { }
                void M()
                {
                    Console.WriteLine(total);
                    var total = 1;
                    Func<int> f = () => Limit;
                    int Limit = 2;
                    Size = 1;
                    var Size = 2;
                    Console.WriteLine(Run);
                    var Run = 3;
                }
            }
            class Base { protected string name = ""; private int hidden; }
            class Derived : Base { void M() { Console.WriteLine(name); var name = ""; } }
            class Box<T> { T? value; void M() { Console.WriteLine(value); T? value = default; } }
            partial class Parts { int count; void M() { Console.WriteLine(count); var count = 1; } }
        """))
    }

    fun testLocalFunctionsMixedWithLocals() {
        // the first local of a scope is the declared one, else the first local function: later ones CS0128, earlier ones CS0136
        assertEquals(listOf(
            "a CS0128", "a CS0128", "b CS0136", "b CS0128", "c CS0136", "c CS0136", "d CS0128", "d CS0128", "e CS0136", "f CS0136", "g CS0136", "h CS0128",
        ), codes("""
            namespace Locals6;
            class C
            {
                void M(int code)
                {
                    int a = 1; void a() { } int a = 2;
                    void b() { } int b = 1; void b() { }
                    void c() { } void c() { } int c = 1;
                    int d = 1; int d = 2; void d() { }
                    { int e = 1; } void e() { }
                    void f() { } { int f = 1; }
                    { void g() { } } int g = 1;
                    switch (code) { case 1: int h = 1; break; case 2: void h() { } break; }
                    void ok() { int ok = 1; }
                }
            }
        """))
    }

    fun testQueryRangeVariables() {
        // a clause seeing several range variables (`let`, a second `from`) gets them in a transparent identifier: silent there
        assertEquals(listOf("x CS1931", "i CS1930", "i CS1930", "i CS1930", "z CS1931", "q CS1931", "s CS1931", "s CS0136", "t CS0128", "s2 CS0136"), codes("""
            using System.Linq;
            namespace Queries1;
            class C
            {
                void M(int[] items, int x)
                {
                    var a = from x in items select x;
                    var c = from i in items from i in items select i;
                    var d = from i in items let i = 1 select i;
                    var e = from i in items join j in items on i equals j into i select i;
                    var f = from i in items select i into i select i;
                    var h = from i in items let z = 1 select z;
                    var z = 2;
                    Func<int, int> g = q => (from q in items select q).Count();
                    var n = from s in items select (from s in items select s).Count();
                    var m = from s in items let s3 = s select (from s in items select s).Count();
                    var o = from s in items orderby int.TryParse("", out var s) select s;
                    var p = from s in items where int.TryParse("", out var t) && int.TryParse("", out var t) select s;
                    var r = from s in items from v in items where int.TryParse("", out var v) select v;
                    var w = from s in items select s into s2 where int.TryParse("", out var s2) select s2;
                    var y = from s in items where items.Any(s => s > 0) select s;
                    var o2 = 1;
                    var k = from s in items where int.TryParse("", out var o2) select s;
                    { var s = 1; }
                }
            }
        """))
        assertEquals(listOf(
            "x -> CS1931: The range variable 'x' conflicts with a previous declaration of 'x'",
            "i -> CS1930: The range variable 'i' has already been declared",
        ), errors("""
            using System.Linq;
            namespace Queries2;
            class C { void M(int[] items, int x) { var a = from x in items from i in items from i in items select i; } }
        """))
    }

    fun testCompileOrderOfTheDefaultGlob() {
        // `dotnet msbuild -getItem:Compile` of the same files on Windows and on Linux
        val windows = listOf("0dir/n.cs", "1.cs", "a-b.cs", "a.b.cs", "a.cs", "aa/t.cs", "ab.cs", "a/k.cs", "a_b.cs", "B.cs", "b2.cs", "e.cs", "Sub2/x.cs", "sub/A.cs",
            "sub/deep/q.cs", "sub/z.cs", "Z.cs", "Zed/y.cs", "zz/Ab.cs", "zz/ac.cs", "[x].cs", "_dir/m.cs", "_u.cs", "~t.cs")
        assertEquals(windows, windows.shuffled(java.util.Random(1)).sortedWith { a, b -> io.github.dotnetsupport.lang.semantic.CSharpCompileOrder.compare(a, b, '\\') })
        val linux = listOf("0dir/n.cs", "1.cs", "a-b.cs", "a.b.cs", "a.cs", "a/k.cs", "aa/t.cs", "ab.cs", "a_b.cs", "B.cs", "b2.cs", "e.cs", "sub/A.cs", "sub/deep/q.cs",
            "Sub/m.cs", "sub/z.cs", "Sub2/x.cs", "Z.cs", "Zed/y.cs", "zz/Ab.cs", "zz/ac.cs", "[x].cs", "_dir/m.cs", "_u.cs", "~t.cs")
        assertEquals(linux, linux.shuffled(java.util.Random(1)).sortedWith { a, b -> io.github.dotnetsupport.lang.semantic.CSharpCompileOrder.compare(a, b, '/') })
    }

    fun testDuplicateTypesAcrossFolders() {
        fun cs0101(path: String): List<String?> {
            myFixture.configureFromTempProjectFile(path)
            return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.startsWith("CS0101") == true }.map { it.description }
        }
        myFixture.addFileToProject("Types3/a.cs", "namespace Types3; class Root { }")
        myFixture.addFileToProject("Types3/aa/T.cs", "namespace Types3; class Root { }")
        myFixture.addFileToProject("Types3/sub/A.cs", "namespace Types3; class Sep { }")
        myFixture.addFileToProject("Types3/Sub2/X.cs", "namespace Types3; class Sep { }")
        val message = { name: String -> listOf("CS0101: The namespace 'Types3' already contains a definition for '$name'") }
        assertEquals(emptyList<String>(), cs0101("Types3/a.cs"))
        assertEquals(message("Root"), cs0101("Types3/aa/T.cs"))
        // `\` sorts after the letters and digits, `/` before them
        val windows = com.intellij.openapi.util.SystemInfo.isWindows
        assertEquals(if (windows) message("Sep") else emptyList(), cs0101("Types3/sub/A.cs"))
        assertEquals(if (windows) emptyList() else message("Sep"), cs0101("Types3/Sub2/X.cs"))
    }

    fun testOperatorsConversionsAndRefKinds() {
        assertEquals(listOf(
            "+ -> CS0111: Type 'Ops' already defines a member called 'op_Addition' with the same parameter types",
            "op_Addition -> CS0111: Type 'Ops' already defines a member called 'op_Addition' with the same parameter types",
            "== -> CS0111: Type 'Ops' already defines a member called 'op_Equality' with the same parameter types",
            "int -> CS0557: Duplicate user-defined conversion in type 'Ops'",
            "op_Implicit -> CS0111: Type 'Ops' already defines a member called 'op_Implicit' with the same parameter types",
            "op_AdditionAssignment -> CS0111: Type 'Ops' already defines a member called 'op_AdditionAssignment' with the same parameter types",
            "M -> CS0663: 'Refs' cannot define an overloaded method that differs only on parameter modifiers 'out' and 'ref'",
            "M -> CS0663: 'Refs' cannot define an overloaded method that differs only on parameter modifiers 'ref readonly' and 'ref'",
            "N -> CS0111: Type 'Refs' already defines a member called 'N' with the same parameter types",
            "Refs -> CS0663: 'Refs' cannot define an overloaded constructor that differs only on parameter modifiers 'in' and 'ref'",
        ), errors("""
            namespace Members4;
            class Ops
            {
                public static Ops operator +(Ops a, Ops b) => a;
                public static Ops operator +(Ops x, Ops y) => x;
                public static Ops operator -(Ops a) => a;
                public static Ops operator -(Ops a, Ops b) => a;
                public static Ops op_Addition(Ops a, Ops b) => a;
                public static bool operator ==(Ops a, Ops b) => true;
                public static bool operator !=(Ops a, Ops b) => true;
                public static bool operator ==(Ops a, Ops b) => true;
                public static implicit operator int(Ops a) => 0;
                public static explicit operator int(Ops a) => 0;
                public static explicit operator long(Ops a) => 0;
                public static int op_Implicit(Ops a) => 0;
                public void operator +=(Ops a) { }
                public void op_AdditionAssignment(Ops a) { }
                public static Ops operator +(Ops a, in int b) => a;
                public static Ops operator +(Ops a, int b) => a;
            }
            class Refs
            {
                void M(ref int a) { }
                void M(out int a) { a = 0; }
                void M(ref readonly int a) { }
                void M(int a) { }
                void N(scoped ref int a) { }
                void N(ref int a) { }
                Refs(ref int a) { }
                Refs(in int a) { }
            }
        """))
    }

    fun testExplicitImplementationsAndIndexers() {
        assertEquals(listOf(
            "Twice -> CS8646: 'IA.P' is explicitly implemented more than once.",
            "Twice -> CS8646: 'IA.Run(int)' is explicitly implemented more than once.",
            "Twice -> CS8646: 'IA.this[int]' is explicitly implemented more than once.",
            "Run -> CS0111: Type 'Twice' already defines a member called 'Members5.IA.Run' with the same parameter types",
            "P -> CS0102: The type 'Twice' already contains a definition for 'Members5.IA.P'",
            "this -> CS0111: Type 'Twice' already defines a member called 'this' with the same parameter types",
            "Run -> CS0111: Type 'Outside' already defines a member called 'Members5.IA.Run' with the same parameter types",
            "this -> CS0102: The type 'Grid' already contains a definition for 'Item'",
            "this -> CS0102: The type 'Grid' already contains a definition for 'Item'",
            "this -> CS0102: The type 'Named' already contains a definition for 'Cell'",
        ), errors("""
            namespace Members5;
            interface IA { void Run(int a); int P { get; } int this[int i] { get; } }
            class Twice : IA
            {
                void IA.Run(int a) { }
                void IA.Run(int b) { }
                int IA.P => 0;
                int IA.P => 1;
                int IA.this[int i] => 0;
                int IA.this[int j] => 1;
                public void Run(int a) { }
            }
            class Outside { void IA.Run(int a) { } void IA.Run(int b) { } }
            class Grid { public int Item; public int this[int i] => 0; public int this[string s] => 0; }
            class Table { public int this[int i] => 0; public int Value; }
            class Named
            {
                [System.Runtime.CompilerServices.IndexerName("Cell")] public int this[int i] => 0;
                public int Item;
                public int Cell;
            }
        """).filter { !it.contains("CS0540") })
    }

    fun testPartialMethodsRecordsAndMixedNames() {
        assertEquals(listOf(
            "Done -> CS0757: A partial method may not have multiple implementing declarations",
            "Twice -> CS0111: Type 'Parts' already defines a member called 'Twice' with the same parameter types",
            "Twice -> CS0756: A partial method may not have multiple defining declarations",
            "Done2 -> CS0102: The type 'Parts' already contains a definition for 'Done2'",
            "Load -> CS0111: Type 'Parts' already defines a member called 'Load' with the same parameter types",
            "X -> CS0102: The type 'Pos' already contains a definition for 'X'",
            "Y -> CS0102: The type 'Pos' already contains a definition for 'Y'",
            "Pos -> CS0111: Type 'Pos' already defines a member called 'Pos' with the same parameter types",
            "Data -> CS0102: The type 'Mixed' already contains a definition for 'Data'",
            "Data -> CS0102: The type 'Mixed' already contains a definition for 'Data'",
            "Data -> CS0111: Type 'Mixed' already defines a member called 'Data' with the same parameter types",
        ), errors("""
            namespace Members6;
            partial class Parts
            {
                partial void Done(int a);
                partial void Done(int a) { }
                partial void Done(int b) { }
                partial void Twice(int a);
                partial void Twice(int a);
                public int Done2;
                partial void Done2(int a);
                partial void Done2(int a) { }
                partial void Load(int a);
                public void Load(int a) { }
            }
            record Pos(int X, int Y, int Z, int W)
            {
                public class X { }
                public void Y() { }
                public string Z = "";
                public int W { get; init; } = W;
                public Pos(int X, int Y, int Z, int W) { }
            }
            record Base(int A);
            record Derived(int A, int B) : Base(A) { public void A() { } }
            class Mixed
            {
                public int Data;
                public void Data(int a) { }
                public void Data(int b) { }
            }
        """).filter { !it.contains("CS8866") && !it.contains("CS0108") })
    }

    fun testPartialTypeParameterNames() {
        assertEquals(listOf(
            "Gen -> CS0264: Partial declarations of 'Gen<T>' must have the same type parameter names in the same order",
            "Inner -> CS0264: Partial declarations of 'Outer.Inner<A, B>' must have the same type parameter names in the same order",
        ), errors("""
            namespace Types4;
            partial class Gen<T> { }
            partial class Gen<U> { }
            partial class Gen<U> { }
            partial class Same<T> { }
            partial class Same<T> { }
            partial interface IVariant<in T> { }
            partial interface IVariant<T> { }
            class Outer { partial class Inner<A, B> { } partial class Inner<B, A> { } }
        """).filter { !it.contains("CS1067") })
        // in two files the first one in the compilation has it
        myFixture.addFileToProject("Types5/A.cs", "namespace Types5; partial class Split<T> { }")
        myFixture.addFileToProject("Types5/B.cs", "namespace Types5; partial class Split<U> { }")
        for ((path, expected) in listOf("Types5/A.cs" to 1, "Types5/B.cs" to 0)) {
            myFixture.configureFromTempProjectFile(path)
            assertEquals(path, expected, myFixture.doHighlighting(HighlightSeverity.ERROR).count { it.description?.startsWith("CS0264") == true })
        }
    }

    fun testDuplicateParameters() {
        assertEquals(listOf(
            "sender -> CS0100: The parameter name 'sender' is a duplicate",
            "name -> CS0100: The parameter name 'name' is a duplicate",
            "x -> CS0100: The parameter name 'x' is a duplicate",
            "_ -> CS0100: The parameter name '_' is a duplicate",
        ), errors("""
            namespace Params1;
            delegate void Handler(object sender, object sender);
            class C(string name, int name)
            {
                void Move(int x, int y, int x) { }
                void Discards(int _, int _) { }
                void Fine() { Func<int, int, int> f = (_, _) => 0; Action<int, int> g = delegate (int _, int _) { }; }
            }
        """))
    }

    fun testDuplicateMembers() {
        assertEquals(listOf(
            "_id -> CS0102: The type 'Order' already contains a definition for '_id'",
            "Status -> CS0102: The type 'Order' already contains a definition for 'Status'",
            "Ship -> CS0102: The type 'Order' already contains a definition for 'Ship'",
            "Ship -> CS0102: The type 'Order' already contains a definition for 'Ship'",
            "Line -> CS0102: The type 'Order' already contains a definition for 'Line'",
            "Red -> CS0102: The type 'Color' already contains a definition for 'Red'",
            "Email -> CS0102: The type 'Customer' already contains a definition for 'Email'",
            "Value -> CS0102: The type 'Generic<T>.Inner' already contains a definition for 'Value'",
        ), errors("""
            namespace Members1;
            interface IShape { double Area(); }
            class Order : IShape
            {
                int _id;
                int _id;
                string Status { get; set; } = "";
                string Status = "";
                void Ship() { }
                bool Ship;
                void Ship(int days) { }
                class Line { }
                class Line { }
                void Pay() { }
                void Pay(int a) { }
                class Item { }
                class Item<T> { }
                double IShape.Area() => 0;
                double Area() => 1;
            }
            enum Color { Red, Green, Red }
            partial class Customer { string Email { get; set; } = ""; partial void Created(); }
            partial class Customer { string Email = ""; partial void Created() { } }
            record Point(int X) { public int X { get; init; } = X; }
            class Generic<T> { class Inner { T? Value; T? Value; } }
        """))
    }

    fun testDuplicateSignatures() {
        fun same(type: String, member: String, at: String = member) = "$at -> CS0111: Type '$type' already defines a member called '$member' with the same parameter types"
        assertEquals(listOf(
            same("Store", "Save"), same("Store", "Clear"), same("Store", "Find"), same("Store", "Name"), same("Store", "Items"),
            same("Store", "Store"), same("Store", "this"), same("StoreExtensions", "Reset"), same("Session", "Session"),
        ), errors("""
            namespace Members2;
            class Store
            {
                void Save(string key) { }
                void Save(string name) { }
                int Save(string value, int retries = 0) => retries;
                void Clear(params int[] ids) { }
                void Clear(int[] keys) { }
                void Find<T>(T item) { }
                void Find<U>(U value) { }
                void Find(int item) { }
                void Find<T>(int item) { }
                void Name(string text) { }
                void Name(string? other) { }
                void Count(ref int total) { }
                void Count(int total) { }
                void Items(List<int> items) { }
                void Items(System.Collections.Generic.List<int> list) { }
                void Items(List<long> items) { }
                Store(int capacity) { }
                Store(int size) { }
                int this[int index] => index;
                int this[int position] => position;
            }
            static class StoreExtensions
            {
                static void Reset(this Store store) { }
                static void Reset(Store other) { }
            }
            class Session(string user)
            {
                Session(string name) : this(name) { }
            }
            partial class Hooks { partial void OnSave(int id); partial void OnSave(int id) { } }
        """))
    }

    fun testNoSignatureVerdictOnUnknownTypes() {
        assertEquals(listOf("Missing CS0246", "Missing CS0246"), codes("""
            namespace Members3;
            class C
            {
                void M(Missing a) { }
                void M(Missing b) { }
            }
        """))
    }

    fun testDuplicateTypes() {
        assertEquals(listOf(
            "Invoice -> CS0101: The namespace 'Types1' already contains a definition for 'Invoice'",
            "Money -> CS0101: The namespace 'Types1' already contains a definition for 'Money'",
            "Box -> CS0101: The namespace 'Types1' already contains a definition for 'Box'",
            "Ledger -> CS0101: The namespace 'Types1.Inner' already contains a definition for 'Ledger'",
        ), errors("""
            namespace Types1
            {
                class Invoice { }
                class Invoice { }
                struct Money { }
                record Money(decimal Amount);
                class Box<T> { }
                class Box<T> { }
                class Box { }
                partial class Report { }
                partial class Report { }
                class Outer { class Invoice { } }
                namespace Inner { class Invoice { } class Ledger { } }
                namespace Inner { class Ledger { } }
            }
        """))
    }

    fun testDuplicateTypeInAnotherFileOfTheFolder() {
        myFixture.addFileToProject("Types2/A.cs", "namespace Types2; class Shared { } partial class Parts { } file class Local { }")
        myFixture.addFileToProject("Types2/B.cs", "namespace Types2; class Shared { } partial class Parts { } file class Local { }")
        // only the file that comes later in the compilation has the error
        myFixture.configureFromTempProjectFile("Types2/B.cs")
        val inB = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.startsWith("CS0101") == true }.map { it.description }
        assertEquals(listOf("CS0101: The namespace 'Types2' already contains a definition for 'Shared'"), inB)
        myFixture.configureFromTempProjectFile("Types2/A.cs")
        assertEquals(emptyList<String>(), myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.startsWith("CS0101") == true }.map { it.description })
    }

    fun testTopLevelStatements() {
        assertEquals(listOf("top CS0128", "early CS0841", "inner CS0136"), codes("""
            int top = 1;
            int top = 2;
            Console.WriteLine(early);
            int early = 3;
            { int inner = 1; }
            int inner = 4;
            void Lf(int top) { }
            Console.WriteLine(top + early + inner);
            class K { void M() { int top = 1; int early = 2; } }
        """))
    }
}
