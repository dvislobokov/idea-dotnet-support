package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpGenerateSite
import io.github.dotnetsupport.lang.CSharpGenerator
import io.github.dotnetsupport.lang.CSharpRiderPopups
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpGenerate
import io.github.dotnetsupport.lang.NativeCSharpGenerateRow
import io.github.dotnetsupport.lang.NativeCSharpGenerateRunner
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * Generate (Alt+Insert) of the native tree (CSHARP_PSI_MIGRATION.md, task C4d): Rider's generators on the semantics of the plugin over
 * the fixture assemblies (System.Runtime and others) — the members written, the base types and `using` directives added, where each
 * generator is gray.
 */
class CSharpGenerateTest : BasePlatformTestCase() {
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            NativeCSharpGenerateRunner.setChooserForTests(null)
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun generate(generator: CSharpGenerator, text: String, pick: ((String) -> Boolean)? = null): String {
        myFixture.configureByText("Generate${files++}.cs", text.trimIndent() + "\n")
        if (pick != null) NativeCSharpGenerateRunner.setChooserForTests { choices -> choices.filter { pick(it.text) } }
        NativeCSharpGenerateRunner.run(generator, project, myFixture.editor, myFixture.file)
        return myFixture.editor.document.text.trimEnd()
    }

    private fun available(text: String): Set<CSharpGenerator> {
        myFixture.configureByText("Generate${files++}.cs", text.trimIndent() + "\n")
        val site = CSharpGenerateSite.at(myFixture.file as CSharpFile, myFixture.editor.caretModel.offset) ?: return emptySet()
        return CSharpGenerator.entries.filterTo(LinkedHashSet()) { NativeCSharpGenerate.choices(it, site).isNotEmpty() }
    }

    // ---- constructor

    fun testConstructorOfReadOnlyFieldsAndGetOnlyProperties() {
        assertEquals(
            """
            class Order
            {
                private readonly int _id;
                public string Name { get; }

                public Order(int id, string name)
                {
                    _id = id;
                    Name = name;
                }
            }
            """.trimIndent(),
            generate(CSharpGenerator.CONSTRUCTOR, """
                class Order
                {
                    private readonly int _id;
                    public string Name { get; }<caret>
                }
            """),
        )
    }

    fun testConstructorPassesTheParametersOfTheBaseConstructor() {
        val text = generate(CSharpGenerator.CONSTRUCTOR, """
            class Shape
            {
                protected Shape(string name) { }
            }

            class Circle : Shape
            {
                private readonly double _radius;
                <caret>
            }
        """)
        assertTrue(text, text.contains("public Circle(string name, double radius) : base(name)\n    {\n        _radius = radius;\n    }"))
    }

    fun testABlankLineBetweenMembersTakesTheMembersWhereTheCaretIs() {
        val text = generate(CSharpGenerator.CONSTRUCTOR, "class A\n{\n    private readonly int _id;\n\n    // above\n<caret>\n    // below\n    void M() { }\n}")
        assertTrue(text, text.contains("    // above\n\n    public A(int id)\n    {\n        _id = id;\n    }\n\n    // below\n"))
    }

    fun testACommentAfterTheMembersKeepsABlankLine() {
        val text = generate(CSharpGenerator.FORMATTING_MEMBERS, "class A\n{\n    // note<caret>\n    private int _id;\n}")
        assertTrue(text, text.contains("class A\n{\n    public override string ToString()\n    {\n        return \$\"{nameof(_id)}: {_id}\";\n    }\n\n    // note\n"))
    }

    fun testConstructorAssignsAFieldOfTheSameNameThroughThis() {
        val text = generate(CSharpGenerator.CONSTRUCTOR, "class A\n{\n    private readonly int count;<caret>\n}")
        assertTrue(text, text.contains("public A(int count)\n    {\n        this.count = count;\n    }"))
    }

    // ---- properties

    fun testReadOnlyPropertiesAndProperties() {
        val readOnly = generate(CSharpGenerator.READ_ONLY_PROPERTIES, "class A\n{\n    private int _count;<caret>\n}")
        assertTrue(readOnly, readOnly.contains("    private int _count;\n\n    public int Count => _count;\n}"))
        val both = generate(CSharpGenerator.PROPERTIES, "class A\n{\n    private string _title = \"\";<caret>\n    private readonly int _id;\n}")
        assertTrue(both, both.contains("public string Title\n    {\n        get => _title;\n        set => _title = value;\n    }"))
        assertFalse("a read-only field gets no setter: $both", both.contains("Id"))
    }

    // ---- missing members

    fun testMissingMembersOfAGenericInterfaceOfTheSolutionWithTheUsingOfTheException() {
        val text = generate(CSharpGenerator.MISSING_MEMBERS, """
            interface IRepository<T>
            {
                T Find(int id);
                string? Name { get; }
                void Save(T item, out bool created);
            }

            class Order { }

            class Orders : IRepository<Order>
            {
                <caret>
            }
        """)
        assertTrue(text, text.startsWith("using System;\n\n"))
        assertTrue(text, text.contains("public Order Find(int id)\n    {\n        throw new NotImplementedException();\n    }"))
        assertTrue(text, text.contains("public string? Name { get; }"))
        assertTrue(text, text.contains("public void Save(Order item, out bool created)"))
    }

    fun testMissingMembersOfLibraryInterfacesAndAbstractBases() {
        val labels = ArrayList<String>()
        val text = generate(CSharpGenerator.MISSING_MEMBERS, pick = { labels += it; true }, text = """
            using System;

            abstract class Shape
            {
                public abstract double Area();
                protected abstract string Label { get; set; }
                public virtual void Draw() { }
            }

            class Circle : Shape, IDisposable, IComparable<Circle>
            {
                <caret>
            }
        """)
        assertTrue(text, text.contains("public override double Area()\n    {\n        throw new NotImplementedException();\n    }"))
        assertTrue(text, text.contains("protected override string Label { get; set; }"))
        assertTrue(text, text.contains("public void Dispose()\n    {\n        throw new NotImplementedException();\n    }"))
        assertTrue(text, text.contains("public int CompareTo(Circle? other)"))
        assertFalse("a virtual member is not missing: $text", text.contains("Draw()\n    {\n        throw"))
        assertEquals("one using System: $text", 1, Regex("using System;").findAll(text).count())
        assertTrue("the chooser names the type argument: $labels", "CompareTo(Circle? other): int" in labels)
    }

    fun testMissingMembersKeepDefaultValuesModifiersAndAttributesOfParameters() {
        val text = generate(CSharpGenerator.MISSING_MEMBERS, """
            using System.Runtime.CompilerServices;
            using System.Threading;
            using System.Threading.Tasks;

            interface IOrders
            {
                Task AddAsync(string order, CancellationToken ct = default);
                void Log(string text, int level = 0, string? source = null, params object[] args);
                void Trace(ref int count, in long id, [CallerMemberName] string caller = "");
            }

            class Orders : IOrders
            {
                <caret>
            }
        """)
        assertTrue(text, text.contains("public Task AddAsync(string order, CancellationToken ct = default)"))
        assertTrue(text, text.contains("public void Log(string text, int level = 0, string? source = null, params object[] args)"))
        assertTrue(text, text.contains("public void Trace(ref int count, in long id, [CallerMemberName] string caller = \"\")"))
    }

    fun testMissingMembersAreNotOfferedWhenAllAreImplemented() {
        assertFalse(CSharpGenerator.MISSING_MEMBERS in available("using System;\nclass A : IDisposable\n{\n    public void Dispose() { }<caret>\n}"))
        assertFalse(CSharpGenerator.MISSING_MEMBERS in available("class A\n{\n    <caret>\n}"))
    }

    // ---- overriding members

    fun testOverridingMembersCallTheBase() {
        val text = generate(CSharpGenerator.OVERRIDING_MEMBERS, """
            class Shape
            {
                public virtual string Describe(int depth) => "";
                protected virtual int Sides { get; set; }
            }

            class Square : Shape
            {
                <caret>
            }
        """) { it.startsWith("Describe") || it.startsWith("Sides") || it.startsWith("ToString") }
        assertTrue(text, text.contains("public override string Describe(int depth)\n    {\n        return base.Describe(depth);\n    }"))
        assertTrue(text, text.contains("protected override int Sides\n    {\n        get => base.Sides;\n        set => base.Sides = value;\n    }"))
        assertTrue(text, text.contains("public override string? ToString()\n    {\n        return base.ToString();\n    }"))
    }

    fun testOverridingMembersListObjectAndSkipWhatIsOverridden() {
        myFixture.configureByText("Generate${files++}.cs", "class A\n{\n    public override string ToString() => \"\";\n    <caret>\n}\n")
        val site = CSharpGenerateSite.at(myFixture.file as CSharpFile, myFixture.editor.caretModel.offset)!!
        val choices = NativeCSharpGenerate.choices(CSharpGenerator.OVERRIDING_MEMBERS, site)
        val rows = choices.map { it.text }
        assertTrue(rows.toString(), rows.any { it.startsWith("Equals(") } && rows.any { it.startsWith("GetHashCode(") })
        // one node per base in the chooser: the rows of a base share its group (robot, 0.1.85: «object» twice)
        assertEquals(1, choices.mapNotNull { it.group }.distinct().size)
        assertFalse(rows.toString(), rows.any { it.startsWith("ToString") || it.startsWith("Finalize") })
    }

    // ---- equality, formatting, deconstruction, disposal

    fun testEqualityMembersOfAClass() {
        val text = generate(CSharpGenerator.EQUALITY_MEMBERS, """
            class Point
            {
                public int X { get; }
                public string? Name { get; }
                <caret>
            }
        """)
        assertTrue(text, text.startsWith("using System;\n\nclass Point : IEquatable<Point>"))
        assertTrue(text, text.contains("public bool Equals(Point? other)\n    {\n        if (other is null) return false;\n        if (ReferenceEquals(this, other)) return true;\n        return X == other.X && Name == other.Name;\n    }"))
        assertTrue(text, text.contains("public override bool Equals(object? obj)"))
        assertTrue(text, text.contains("return Equals((Point)obj);"))
        assertTrue(text, text.contains("public override int GetHashCode()\n    {\n        return HashCode.Combine(X, Name);\n    }"))
        assertTrue(text, text.contains("public static bool operator ==(Point? left, Point? right)\n    {\n        return Equals(left, right);\n    }"))
        assertTrue(text, text.contains("public static bool operator !=(Point? left, Point? right)"))
    }

    fun testEqualityMembersOfAStruct() {
        val text = generate(CSharpGenerator.EQUALITY_MEMBERS, "using System;\nstruct P\n{\n    public double X;<caret>\n}")
        assertTrue(text, text.contains("struct P : IEquatable<P>"))
        assertTrue(text, text.contains("public bool Equals(P other)\n    {\n        return X.Equals(other.X);\n    }"))
        assertTrue(text, text.contains("return obj is P other && Equals(other);"))
        assertTrue(text, text.contains("return left.Equals(right);"))
    }

    fun testFormattingMembers() {
        val text = generate(CSharpGenerator.FORMATTING_MEMBERS, "class A\n{\n    public int Id { get; set; }\n    private string _name = \"\";<caret>\n}")
        assertTrue(text, text.contains("public override string ToString()\n    {\n        return \$\"{nameof(Id)}: {Id}, {nameof(_name)}: {_name}\";\n    }"))
    }

    fun testDeconstructor() {
        val text = generate(CSharpGenerator.DECONSTRUCTOR, "class A\n{\n    public int Id { get; }\n    public string Name { get; } = \"\";<caret>\n}")
        assertTrue(text, text.contains("public void Deconstruct(out int id, out string name)\n    {\n        id = Id;\n        name = Name;\n    }"))
    }

    fun testDisposePatternOfASealedClassAndOfAnOpenOne() {
        val sealed = generate(CSharpGenerator.DISPOSE_PATTERN, "using System;\nsealed class A\n{\n    private readonly IDisposable _inner;<caret>\n    private IDisposable? _other;\n}")
        assertTrue(sealed, sealed.contains("sealed class A : IDisposable"))
        assertTrue(sealed, sealed.contains("public void Dispose()\n    {\n        _inner.Dispose();\n        _other?.Dispose();\n    }"))
        val open = generate(CSharpGenerator.DISPOSE_PATTERN, "using System;\nclass B\n{\n    private readonly IDisposable _inner;<caret>\n}")
        assertTrue(open, open.contains("protected virtual void Dispose(bool disposing)\n    {\n        if (disposing)\n        {\n            _inner.Dispose();\n        }\n    }"))
        assertTrue(open, open.contains("GC.SuppressFinalize(this);"))
    }

    // ---- delegating members, comparers, relational members (0.1.81)

    fun testDelegatingMembersOfALibraryType() {
        val text = generate(CSharpGenerator.DELEGATING_MEMBERS, """
            using System.Collections.Generic;

            class Bag
            {
                private readonly List<int> _items = new();<caret>
            }
        """) { it.startsWith("Add(") || it.startsWith("Count:") || it.startsWith("this[") }
        assertTrue(text, text.contains("public void Add(int item)\n    {\n        _items.Add(item);\n    }"))
        assertTrue(text, text.contains("public int Count => _items.Count;"))
        assertTrue(text, text.contains("public int this[int index]\n    {\n        get => _items[index];\n        set => _items[index] = value;\n    }"))
    }

    fun testDelegatingMembersOfAnInterfaceOfTheSolution() {
        myFixture.configureByText("Generate${files++}.cs", "interface IShape\n{\n    double Area { get; }\n    void Draw(int scale);\n    event System.EventHandler Changed;\n}\n\nclass Wrapper\n{\n    private readonly IShape _shape;<caret>\n}\n")
        val site = CSharpGenerateSite.at(myFixture.file as CSharpFile, myFixture.editor.caretModel.offset)!!
        val rows = NativeCSharpGenerate.choices(CSharpGenerator.DELEGATING_MEMBERS, site)
        assertEquals(rows.map { it.text }.toString(), setOf("_shape: IShape"), rows.mapNotNull { it.group?.text }.toSet())
        NativeCSharpGenerateRunner.setChooserForTests { it }
        NativeCSharpGenerateRunner.run(CSharpGenerator.DELEGATING_MEMBERS, project, myFixture.editor, myFixture.file)
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("public double Area => _shape.Area;"))
        assertTrue(text, text.contains("public void Draw(int scale)\n    {\n        _shape.Draw(scale);\n    }"))
        assertTrue(text, text.startsWith("using System;\n"))
        assertTrue(text, text.contains("public event EventHandler Changed\n    {\n        add => _shape.Changed += value;\n        remove => _shape.Changed -= value;\n    }"))
    }

    fun testEqualityComparerIsANestedClassWithAStaticProperty() {
        val text = generate(CSharpGenerator.EQUALITY_COMPARER, """
            class Person
            {
                public string Name { get; } = "";
                public int Age { get; }
                <caret>
            }
        """)
        assertTrue(text, text.contains("private sealed class NameAgeEqualityComparer : IEqualityComparer<Person>"))
        assertTrue(text, text.contains("public bool Equals(Person? x, Person? y)\n        {\n            if (ReferenceEquals(x, y)) return true;\n            if (x is null) return false;\n" +
            "            if (y is null) return false;\n            if (x.GetType() != y.GetType()) return false;\n            return x.Name == y.Name && x.Age == y.Age;\n        }"))
        assertTrue(text, text.contains("public int GetHashCode(Person obj)\n        {\n            return HashCode.Combine(obj.Name, obj.Age);\n        }"))
        assertTrue(text, text.contains("public static IEqualityComparer<Person> NameAgeComparer { get; } = new NameAgeEqualityComparer();"))
        assertTrue(text, text.contains("using System.Collections.Generic;"))
    }

    fun testRelationalMembersCompareInOrder() {
        val text = generate(CSharpGenerator.RELATIONAL_MEMBERS, """
            class Person
            {
                public string Name { get; } = "";
                public int Age { get; }
                <caret>
            }
        """)
        assertTrue(text, text.contains("class Person : IComparable<Person>, IComparable"))
        assertTrue(text, text.contains("public int CompareTo(Person? other)\n    {\n        if (ReferenceEquals(this, other)) return 0;\n        if (other is null) return 1;\n" +
            "        var nameComparison = string.Compare(Name, other.Name, StringComparison.Ordinal);\n        if (nameComparison != 0) return nameComparison;\n" +
            "        return Age.CompareTo(other.Age);\n    }"))
        assertTrue(text, text.contains("public int CompareTo(object? obj)"))
        assertTrue(text, text.contains("return obj is Person other ? CompareTo(other) : throw new ArgumentException(\$\"Object must be of type {nameof(Person)}\");"))
        assertTrue(text, text.contains("public static bool operator <(Person? left, Person? right)\n    {\n        return Comparer<Person>.Default.Compare(left, right) < 0;\n    }"))
        assertTrue(text, text.contains("public static bool operator >=(Person? left, Person? right)"))
    }

    fun testRelationalComparerOfAStruct() {
        val text = generate(CSharpGenerator.RELATIONAL_COMPARER, "struct Money\n{\n    public decimal Amount;\n    public string? Currency;<caret>\n}")
        assertTrue(text, text.contains("private sealed class AmountCurrencyRelationalComparer : IComparer<Money>"))
        assertTrue(text, text.contains("public int Compare(Money x, Money y)\n        {\n            var amountComparison = x.Amount.CompareTo(y.Amount);\n" +
            "            if (amountComparison != 0) return amountComparison;\n            return string.Compare(x.Currency, y.Currency, StringComparison.Ordinal);\n        }"))
        assertTrue(text, text.contains("public static IComparer<Money> AmountCurrencyComparer { get; } = new AmountCurrencyRelationalComparer();"))
    }

    // ---- the popup

    fun testGenerateListsEveryGeneratorInAType() {
        myFixture.configureByText("Generate${files++}.cs", "class A\n{\n    private int _x;<caret>\n}\n")
        val rows = CSharpRiderPopups.generatorRows(project, myFixture.editor, myFixture.file).filterIsInstance<NativeCSharpGenerateRow>()
        assertEquals(CSharpGenerator.entries.map { it.title }, rows.map { it.templatePresentation.text })
        val enabled = available("class A\n{\n    private int _x;<caret>\n}")
        assertTrue(enabled.toString(), CSharpGenerator.CONSTRUCTOR in enabled && CSharpGenerator.PROPERTIES in enabled && CSharpGenerator.EQUALITY_MEMBERS in enabled)
        assertFalse(enabled.toString(), CSharpGenerator.MISSING_MEMBERS in enabled || CSharpGenerator.PARTIAL_MEMBERS in enabled)
        myFixture.configureByText("Generate${files++}.cs", "<caret>using System;\nclass A { }\n")
        assertTrue("outside a type there is nothing to generate", CSharpRiderPopups.generatorRows(project, myFixture.editor, myFixture.file).isEmpty())
    }

    fun testServerRowsWithANativeEquivalentAreDropped() {
        for (title in listOf("Generate constructor 'Order(int)'", "Generate Equals and GetHashCode...", "Generate overrides...", "Implement interface", "Implement abstract class", "Extract method")) {
            assertTrue(title, CSharpRiderPopups.hasNativeEquivalent(title))
        }
        for (title in listOf("Add 'DebuggerDisplay' attribute", "Extract interface...", "Implement all members explicitly", "Extract local function")) {
            assertFalse(title, CSharpRiderPopups.hasNativeEquivalent(title))
        }
    }

    private companion object {
        fun bytes(name: String): ByteArray? = CSharpGenerateTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
