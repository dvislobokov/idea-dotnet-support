package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The access group of the native errors (`CSharpAccessChecks`): CS0122, CS0200, CS0191, CS0198, CS0131, CS0154, CS0271, CS0272, CS8852 with
 * Roslyn's texts and spans (checked against `dotnet build`), and silence where Roslyn says nothing or something else.
 */
class CSharpAccessErrorsTest : BasePlatformTestCase() {
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

    /** Each error of the access group as `text under it -> CSxxxx: message`. */
    private fun errors(text: String, codes: Set<String> = CODES): List<String> {
        myFixture.configureByText("AccessErrors${files++}.cs", "using System;\nusing System.Collections.Generic;\nnamespace Probe.Access;\n" + text.trimIndent())
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.substringBefore(':') in codes }
            .sortedBy { it.startOffset }.map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    /** As [errors], compiled as the assembly [assemblyName] against `tools/index-fixture/access` (its friends: `Probe.Open`, `Probe.Friend` with a key). */
    private fun libraryErrors(assemblyName: String, text: String, signed: Boolean = false): List<String> {
        val assemblies = AssemblyIndexSet(CSharpUsingTypesTest.ASSEMBLIES.indexes + CSharpUsingTypesTest.fixture("AccessFixture"), assemblyName, signed)
        CSharpSemanticEnvironment.setAssembliesForTests { assemblies }
        return errors("using AccessFixture;\n" + text.trimIndent(), CODES + MISSING)
    }

    /** What `dotnet build` says of the uses of `Vault` below from an assembly that is not a friend (checked against AccessFixture.dll). */
    private val strangerUse = """
        class Derived : Vault
        {
            void D()
            {
                Narrow = 1;
                ProtectedSet = 2;
                Guarded = 3;
                ProtectedInit = 4;
                this[1] = 5;
            }
        }
        class User
        {
            void M(Vault v)
            {
                v.InternalField = 1;
                v.Guarded = 1;
                v.Shared = 1;
                v.Narrow = 1;
                v.PrivateSet = 1;
                v.InternalSet = 1;
                v.ProtectedSet = 1;
                v.NarrowSet = 1;
                v.SharedSet = 1;
                int a = v.PrivateGet;
                int b = v.ProtectedGet;
                int c = v.InternalGet;
                v.ProtectedInit = 1;
                v.InternalInit = 1;
                v.GetOnly = 1;
                int e = v.SetOnly;
                v[1] = 2;
                v["a"] = "b";
                v[1L] = 2;
                v.InternalMethod();
                Vault.InternalStatic();
                var y = new Vault(1);
                var s = new Sealed();
                var g = new Guarded();
                Hidden.Count = 1;
                Vault.InternalNested n1 = null;
                Vault.ProtectedNested n2 = null;
                Vault.PrivateNested n3 = null;
                var w = new Vault { InternalProperty = 1 };
                var z = new Vault { ProtectedSet = 1, ProtectedInit = 2 };
                M2(ref v.Ro);
                M2(ref Vault.SRo);
                M2(ref v.PrivateSet);
                M2(ref v[1]);
            }
            void M2(ref int i) { }
        }
    """

    private val strangerErrors = listOf(
        "Narrow -> CS0122: 'Vault.Narrow' is inaccessible due to its protection level",
        "ProtectedInit -> CS8852: Init-only property or indexer 'Vault.ProtectedInit' can only be assigned in an object initializer, or on 'this' or 'base' in an instance constructor or an 'init' accessor.",
        "InternalField -> CS1061: 'Vault' does not contain a definition for 'InternalField' and no accessible extension method 'InternalField' accepting a first argument of type 'Vault' could be found (are you missing a using directive or an assembly reference?)",
        "Guarded -> CS0122: 'Vault.Guarded' is inaccessible due to its protection level",
        "Shared -> CS0122: 'Vault.Shared' is inaccessible due to its protection level",
        "Narrow -> CS0122: 'Vault.Narrow' is inaccessible due to its protection level",
        "v.PrivateSet -> CS0200: Property or indexer 'Vault.PrivateSet' cannot be assigned to -- it is read only",
        "v.InternalSet -> CS0200: Property or indexer 'Vault.InternalSet' cannot be assigned to -- it is read only",
        "v.ProtectedSet -> CS0272: The property or indexer 'Vault.ProtectedSet' cannot be used in this context because the set accessor is inaccessible",
        "v.NarrowSet -> CS0272: The property or indexer 'Vault.NarrowSet' cannot be used in this context because the set accessor is inaccessible",
        "v.SharedSet -> CS0272: The property or indexer 'Vault.SharedSet' cannot be used in this context because the set accessor is inaccessible",
        "v.PrivateGet -> CS0154: The property or indexer 'Vault.PrivateGet' cannot be used in this context because it lacks the get accessor",
        "v.ProtectedGet -> CS0271: The property or indexer 'Vault.ProtectedGet' cannot be used in this context because the get accessor is inaccessible",
        "v.InternalGet -> CS0154: The property or indexer 'Vault.InternalGet' cannot be used in this context because it lacks the get accessor",
        "v.ProtectedInit -> CS8852: Init-only property or indexer 'Vault.ProtectedInit' can only be assigned in an object initializer, or on 'this' or 'base' in an instance constructor or an 'init' accessor.",
        "v.InternalInit -> CS0200: Property or indexer 'Vault.InternalInit' cannot be assigned to -- it is read only",
        "v.GetOnly -> CS0200: Property or indexer 'Vault.GetOnly' cannot be assigned to -- it is read only",
        "v.SetOnly -> CS0154: The property or indexer 'Vault.SetOnly' cannot be used in this context because it lacks the get accessor",
        "v[1] -> CS0272: The property or indexer 'Vault.this[int]' cannot be used in this context because the set accessor is inaccessible",
        "v[1L] -> CS0200: Property or indexer 'Vault.this[long]' cannot be assigned to -- it is read only",
        "InternalMethod -> CS1061: 'Vault' does not contain a definition for 'InternalMethod' and no accessible extension method 'InternalMethod' accepting a first argument of type 'Vault' could be found (are you missing a using directive or an assembly reference?)",
        "InternalStatic -> CS0117: 'Vault' does not contain a definition for 'InternalStatic'",
        "Vault -> CS0122: 'Vault.Vault(int)' is inaccessible due to its protection level",
        "Sealed -> CS1729: 'Sealed' does not contain a constructor that takes 0 arguments",
        "Guarded -> CS0122: 'Guarded.Guarded()' is inaccessible due to its protection level",
        "Hidden -> CS0122: 'Hidden' is inaccessible due to its protection level",
        "InternalNested -> CS0122: 'Vault.InternalNested' is inaccessible due to its protection level",
        "ProtectedNested -> CS0122: 'Vault.ProtectedNested' is inaccessible due to its protection level",
        "PrivateNested -> CS0122: 'Vault.PrivateNested' is inaccessible due to its protection level",
        "InternalProperty -> CS0117: 'Vault' does not contain a definition for 'InternalProperty'",
        "ProtectedSet -> CS0272: The property or indexer 'Vault.ProtectedSet' cannot be used in this context because the set accessor is inaccessible",
        "ProtectedInit -> CS0272: The property or indexer 'Vault.ProtectedInit' cannot be used in this context because the set accessor is inaccessible",
        "v.Ro -> CS0192: A readonly field cannot be used as a ref or out value (except in a constructor)",
        "Vault.SRo -> CS0199: A static readonly field cannot be used as a ref or out value (except in a static constructor)",
        "v.PrivateSet -> CS0206: A non ref-returning property or indexer may not be used as an out or ref value",
        "v[1] -> CS0206: A non ref-returning property or indexer may not be used as an out or ref value",
    )

    fun testWhatAnotherAssemblyHides() {
        assertEquals(strangerErrors, libraryErrors("Probe.Other", strangerUse))
    }

    fun testAFriendNamedWithAKeyIsNotAnUnsignedAssembly() {
        // `InternalsVisibleTo("Probe.Friend, PublicKey=…")`: `dotnet build` of an unsigned Probe.Friend says the same as of a stranger
        assertEquals(strangerErrors, libraryErrors("Probe.Friend", strangerUse))
    }

    /**
     * Projects of the solution, as `dotnet build` of them says: Lib makes `Probe.Tests` its friend by an MSBuild item, Lib2 makes
     * `Probe.Other` its friend by an attribute; a project is named by its `AssemblyName`, by its file without one.
     */
    private fun projectErrors(root: String, project: String, assemblyName: String?): List<String> {
        myFixture.addFileToProject("$root/Lib/Lib.csproj", """
            <Project Sdk="Microsoft.NET.Sdk"><ItemGroup><InternalsVisibleTo Include="Probe.Tests" /></ItemGroup></Project>
        """.trimIndent())
        myFixture.addFileToProject("$root/Lib/Store.cs", """
            namespace $root.Lib;
            public class Store
            {
                internal int Count;
                internal static void Reset() { }
                protected internal int Shared;
                private protected int Narrow;
                internal int Size { get; set; }
                public int Stock { get; internal set; }
                public int Price { internal get; set; }
                internal Store(int seed) { }
                public Store() { }
            }
            internal class Secret { public static int Value; }
        """.trimIndent())
        myFixture.addFileToProject("$root/Lib2/Lib2.csproj", """<Project Sdk="Microsoft.NET.Sdk"></Project>""")
        myFixture.addFileToProject("$root/Lib2/Box.cs", """
            using System.Runtime.CompilerServices;
            [assembly: InternalsVisibleTo("Probe.Other, PublicKey=00240000")]
            [assembly: InternalsVisibleTo("Probe.Other")]
            namespace $root.Lib2;
            public class Box { internal int Hidden; }
        """.trimIndent())
        val name = assemblyName?.let { "<PropertyGroup><AssemblyName>$it</AssemblyName></PropertyGroup>" }.orEmpty()
        myFixture.addFileToProject("$root/$project/$project.csproj", """<Project Sdk="Microsoft.NET.Sdk">$name</Project>""")
        val file = myFixture.addFileToProject("$root/$project/Use.cs", """
            using $root.Lib;
            using $root.Lib2;
            namespace $root.App;
            class Use
            {
                void M(Store s, Box b)
                {
                    s.Count = 1;
                    Store.Reset();
                    s.Shared = 2;
                    var n = new Store { Size = 3 };
                    s.Stock = 4;
                    int p = s.Price;
                    Secret.Value = 5;
                    var t = new Store(1);
                    b.Hidden = 1;
                }
            }
            class Child : Store
            {
                void N() { Narrow = 1; Shared = 2; }
            }
        """.trimIndent())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.substringBefore(':') in CODES + MISSING }
            .sortedBy { it.startOffset }.map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    fun testAnotherProjectHidesItsInternals() {
        assertEquals(listOf(
            "Count -> CS1061: 'Store' does not contain a definition for 'Count' and no accessible extension method 'Count' accepting a first argument of type 'Store' could be found (are you missing a using directive or an assembly reference?)",
            "Reset -> CS0117: 'Store' does not contain a definition for 'Reset'",
            "Shared -> CS0122: 'Store.Shared' is inaccessible due to its protection level",
            "Size -> CS0117: 'Store' does not contain a definition for 'Size'",
            "s.Stock -> CS0200: Property or indexer 'Store.Stock' cannot be assigned to -- it is read only",
            "s.Price -> CS0154: The property or indexer 'Store.Price' cannot be used in this context because it lacks the get accessor",
            "Secret -> CS0122: 'Secret' is inaccessible due to its protection level",
            "Store -> CS1729: 'Store' does not contain a constructor that takes 1 arguments",
            "Hidden -> CS1061: 'Box' does not contain a definition for 'Hidden' and no accessible extension method 'Hidden' accepting a first argument of type 'Box' could be found (are you missing a using directive or an assembly reference?)",
            // Lib has a friend: its reference assembly keeps the private protected member, which a derived type of another assembly cannot reach
            "Narrow -> CS0122: 'Store.Narrow' is inaccessible due to its protection level",
        ), projectErrors("Stranger", "App", null))
    }

    fun testAFriendProjectSeesTheInternals() {
        // `<AssemblyName>Probe.Tests</AssemblyName>` is named by Lib's `InternalsVisibleTo` item; Lib2 names Probe.Other only
        assertEquals(listOf(
            "Hidden -> CS1061: 'Box' does not contain a definition for 'Hidden' and no accessible extension method 'Hidden' accepting a first argument of type 'Box' could be found (are you missing a using directive or an assembly reference?)",
        ), projectErrors("Friend", "Tests", "Probe.Tests"))
        // the project file names the assembly without AssemblyName; Lib2's attribute without a key makes it a friend
        assertEquals(10 - 1, projectErrors("ByFile", "Probe.Other", null).size)
    }

    fun testOverloadsConstructorsAndIndexers() {
        // Roslyn names the first declared of the inaccessible overloads; a constructor that fits and is out of reach is CS0122 when
        // none in reach fits; the indexer the arguments choose decides between CS0200 and CS0272
        assertEquals(listOf(
            "Open -> CS0122: 'Safe.Open(string)' is inaccessible due to its protection level",
            "Safe -> CS0122: 'Safe.Safe(string)' is inaccessible due to its protection level",
            "s[1] -> CS0272: The property or indexer 'Safe.this[int]' cannot be used in this context because the set accessor is inaccessible",
            "s[1L] -> CS0200: Property or indexer 'Safe.this[long]' cannot be assigned to -- it is read only",
            "s.Level -> CS0206: A non ref-returning property or indexer may not be used as an out or ref value",
        ), errors("""
            public class Safe
            {
                private void Open(string code) { }
                private void Open(int pin) { }
                private Safe(string name) { }
                public Safe(double size) { }
                public int this[int i] { get => i; private set { } }
                public long this[long l] => l;
                public string this[string s] { get => s; set { } }
                public int Level { get; set; }
            }
            public class Plain { }
            public struct Spot { private Spot(int x) { } }
            public record Note(string Text);
            public class Generic<T> { private Generic(T value) { } }
            public class User
            {
                void M(Safe s, Note note)
                {
                    s.Open(1);
                    var a = new Safe(2);
                    var b = new Safe("x");
                    var c = new Plain();
                    var d = new Spot();
                    var e = note with { Text = "t" };
                    s["a"] = "b";
                    s[1] = 2;
                    s[1L] = 4;
                    Bump(ref s.Level);
                }
                static void Bump(ref int value) { }
            }
        """))
    }

    fun testAFriendSeesTheInternals() {
        // the internal members, accessors and types are there for Probe.Open; protected and private protected still need a derived type
        // (`v.Narrow`: `dotnet build` says CS0281 of the public key, not said here)
        assertEquals(listOf(
            "Guarded -> CS0122: 'Vault.Guarded' is inaccessible due to its protection level",
            "v.ProtectedSet -> CS0272: The property or indexer 'Vault.ProtectedSet' cannot be used in this context because the set accessor is inaccessible",
            "v.NarrowSet -> CS0272: The property or indexer 'Vault.NarrowSet' cannot be used in this context because the set accessor is inaccessible",
            "v.PrivateGet -> CS0154: The property or indexer 'Vault.PrivateGet' cannot be used in this context because it lacks the get accessor",
            "v.ProtectedGet -> CS0271: The property or indexer 'Vault.ProtectedGet' cannot be used in this context because the get accessor is inaccessible",
            "v.InternalInit -> CS8852: Init-only property or indexer 'Vault.InternalInit' can only be assigned in an object initializer, or on 'this' or 'base' in an instance constructor or an 'init' accessor.",
            "Vault -> CS0122: 'Vault.Vault(int)' is inaccessible due to its protection level",
            "PrivateNested -> CS0122: 'Vault.PrivateNested' is inaccessible due to its protection level",
        ), libraryErrors("Probe.Open", """
            class User
            {
                void M(Vault v)
                {
                    v.InternalField = 1;
                    v.Shared = 1;
                    v.Narrow = 1;
                    v.Guarded = 1;
                    v.InternalSet = 1;
                    v.ProtectedSet = 1;
                    v.NarrowSet = 1;
                    v.SharedSet = 1;
                    int a = v.PrivateGet;
                    int b = v.ProtectedGet;
                    int c = v.InternalGet;
                    v.InternalInit = 1;
                    v.InternalMethod();
                    Vault.InternalStatic();
                    var x = new Vault("n");
                    var y = new Vault(1);
                    var s = new Sealed();
                    Hidden.Count = 1;
                    Vault.InternalNested n1 = null;
                    Vault.PrivateNested n3 = null;
                    var w = new Vault { InternalProperty = 1 };
                    int t = 3.Thrice() + 3.Twice();
                }
            }
        """))
    }

    fun testInaccessibleMembersAndTypes() {
        assertEquals(listOf(
            "_x -> CS0122: 'Outer.Pub._x' is inaccessible due to its protection level",
            "_p -> CS0122: 'A._p' is inaccessible due to its protection level",
            "Hidden -> CS0122: 'A.Hidden()' is inaccessible due to its protection level",
            "Nested -> CS0122: 'A.Nested' is inaccessible due to its protection level",
            "Prot -> CS0122: 'A.Prot' is inaccessible due to its protection level",
            "_v -> CS0122: 'Gen<int>._v' is inaccessible due to its protection level",
        ), errors("""
            public class A
            {
                private int _p;
                protected int Prot;
                private void Hidden() { }
                private class Nested { }
                internal int Inner;
                void Self(A other) { other._p = 1; other.Hidden(); }
            }
            public class Gen<T> { private T? _v; }
            public class Outer { public class Pub { private int _x; } void M(Pub p) { p._x = 1; } }
            public class Use
            {
                void U(A a, Gen<int> g)
                {
                    a._p = 1;
                    a.Hidden();
                    var n = new A.Nested();
                    a.Prot = 2;
                    a.Inner = 3;
                    g._v = 1;
                }
            }
        """))
    }

    fun testWhatRoslynsLookupSkipsIsNotInaccessible() {
        // the private member of the derived type hides nothing for the outside: the public one of the base is found; protected through a
        // derived receiver; private from a nested type; a private member of a base where an outer type has one of that name
        assertEquals(emptyList<String>(), errors("""
            public class Base { public int X; private int v; protected int P; }
            public class Derived : Base
            {
                private new int X;
                void M(Derived d) { d.P = 1; P = 2; base.P = 3; }
                class InDerived { void M(Derived d) => d.P = 4; }
            }
            public class Outer
            {
                private static int v;
                private int _own;
                class Inner : Base { int M(Outer o) => v + o._own; }
            }
            public class Use { int M(Derived d) => d.X; }
        """))
    }

    fun testReadOnlyTargets() {
        assertEquals(listOf(
            "other.Ro -> CS0191: A readonly field cannot be assigned to (except in a constructor or init-only setter of the type in which the field is defined or a variable initializer)",
            "other.GetOnly -> CS0200: Property or indexer 'A.GetOnly' cannot be assigned to -- it is read only",
            "other.Init -> CS8852: Init-only property or indexer 'A.Init' can only be assigned in an object initializer, or on 'this' or 'base' in an instance constructor or an 'init' accessor.",
            "Ro -> CS0191: A readonly field cannot be assigned to (except in a constructor or init-only setter of the type in which the field is defined or a variable initializer)",
            "GetOnly -> CS0200: Property or indexer 'A.GetOnly' cannot be assigned to -- it is read only",
            "SRo -> CS0198: A static readonly field cannot be assigned to (except in a static constructor or a variable initializer)",
            "a.Expr -> CS0200: Property or indexer 'A.Expr' cannot be assigned to -- it is read only",
            "a.PrivSet -> CS0272: The property or indexer 'A.PrivSet' cannot be used in this context because the set accessor is inaccessible",
            "a.Init -> CS8852: Init-only property or indexer 'A.Init' can only be assigned in an object initializer, or on 'this' or 'base' in an instance constructor or an 'init' accessor.",
            "a[0] -> CS0200: Property or indexer 'A.this[int]' cannot be assigned to -- it is read only",
            "GetOnly -> CS0200: Property or indexer 'A.GetOnly' cannot be assigned to -- it is read only",
            "r.X -> CS8852: Init-only property or indexer 'Rec.X' can only be assigned in an object initializer, or on 'this' or 'base' in an instance constructor or an 'init' accessor.",
            "l.Count -> CS0200: Property or indexer 'List<int>.Count' cannot be assigned to -- it is read only",
            "string.Empty -> CS0198: A static readonly field cannot be assigned to (except in a static constructor or a variable initializer)",
        ), errors("""
            public class A
            {
                public readonly int Ro;
                public static readonly int SRo;
                public int GetOnly { get; }
                public int Expr => 1;
                public int PrivSet { get; private set; }
                public int Init { get; init; }
                public int Semi { get => field; }
                public int this[int i] => i;
                public A(A other)
                {
                    Ro = 1; this.Ro += 2; GetOnly = 3; this.GetOnly = 4; Init = 5; Semi = 6; PrivSet = 7;
                    other.Ro = 1;
                    other.GetOnly = 2;
                    other.Init = 3;
                }
                static A() { SRo = 1; }
                public int Setter { get => 0; init { Ro = 1; } }
                void M() { Ro = 2; GetOnly = 3; SRo = 4; }
            }
            public record Rec(int X) { public Rec(Rec r, int y) : this(y) { X = y; } }
            public record struct Cursor(int Line);
            public class Use
            {
                void U(A a, Rec r, Cursor c, List<int> l)
                {
                    a.Expr = 1;
                    a.PrivSet = 2;
                    a.Init = 3;
                    a[0] = 4;
                    var n = new A(a) { Init = 1, GetOnly = 2 };
                    var r2 = r with { X = 1 };
                    r.X = 2;
                    c.Line = 3;
                    l.Capacity = 1;
                    l.Count = 0;
                    string.Empty = "";
                }
            }
        """))
    }

    fun testAccessorsAndValues() {
        assertEquals(listOf(
            "a.PrivGet -> CS0271: The property or indexer 'A.PrivGet' cannot be used in this context because the get accessor is inaccessible",
            "a.SetOnly -> CS0154: The property or indexer 'A.SetOnly' cannot be used in this context because it lacks the get accessor",
            "a.SetOnly -> CS0154: The property or indexer 'A.SetOnly' cannot be used in this context because it lacks the get accessor",
            "a.M() -> CS0131: The left-hand side of an assignment must be a variable, property or indexer",
            "A.C -> CS0131: The left-hand side of an assignment must be a variable, property or indexer",
            "x + 1 -> CS0131: The left-hand side of an assignment must be a variable, property or indexer",
            "1 -> CS0131: The left-hand side of an assignment must be a variable, property or indexer",
        ), errors("""
            public class A
            {
                public const int C = 1;
                private int _f;
                public int PrivGet { private get; set; }
                public int SetOnly { set { } }
                public int M() => 0;
                public ref int R() => ref _f;
                bool Own(A other) => other.PrivGet == PrivGet;
            }
            public class Use
            {
                void U(A a, int x)
                {
                    a.PrivGet = 1;
                    a.SetOnly = 2;
                    var g = a.PrivGet;
                    var s = a.SetOnly;
                    a.SetOnly += 1;
                    a.M() = 1;
                    a.R() = 2;
                    A.C = 3;
                    x + 1 = 4;
                    (1) = 5;
                    var o = new A { SetOnly = 1, PrivGet = 2 };
                }
            }
        """))
    }

    fun testNothingWhereRoslynSaysSomethingElse() {
        // an instance member from a static method (CS0120), a static one through an instance (CS0176), an override that inherits a setter,
        // an unknown type
        assertEquals(emptyList<String>(), errors("""
            public class Base { public virtual int V { get; set; } }
            public class A : Base
            {
                public readonly int Ro;
                public int GetOnly { get; }
                public static int S { get; set; }
                public override int V { get => 1; }
                static void M() { Ro = 1; GetOnly = 2; }
                void N(A a, Missing m) { a.V = 1; m.Anything = 2; }
            }
        """))
    }

    companion object {
        private val CODES = setOf("CS0122", "CS0200", "CS0191", "CS0198", "CS0131", "CS0154", "CS0271", "CS0272", "CS8852", "CS0192", "CS0199", "CS0206")

        /** What another assembly does not import is not there: the errors of a name that is not. */
        private val MISSING = setOf("CS1061", "CS0117", "CS0103", "CS1729")
    }
}
