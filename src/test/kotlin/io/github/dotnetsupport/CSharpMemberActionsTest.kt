package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The member actions of 0.1.173 (`NativeCSharpMemberActions.kt`): an auto-property to a backing field / the `field` keyword / a computed
 * property and back to an auto-property, Encapsulate field, To factory method, To explicit / implicit implementation; where each is not
 * offered.
 */
class CSharpMemberActionsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.CONTEXT_ACTIONS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The file [text] (its namespace made unique) after [title] at `<caret>`, the namespace line cut off. */
    private fun apply(text: String, title: String): String {
        val n = files++
        myFixture.configureByText("Members$n.cs", "namespace Members$n;\n\n" + text.trimIndent() + "\n")
        myFixture.launchAction(myFixture.findSingleIntention(title))
        return myFixture.editor.document.text.removePrefix("namespace Members$n;\n\n").trimEnd()
    }

    private fun available(text: String, title: String): Boolean {
        val n = files++
        myFixture.configureByText("Members$n.cs", "namespace Members$n;\n\n" + text.trimIndent() + "\n")
        return myFixture.filterAvailableIntentions(title).any { it.text == title }
    }

    // ---- auto-properties

    fun testToPropertyWithBackingField() {
        assertEquals("""
            public class User
            {
                private string _name = "none";

                public string Name
                {
                    get => _name;
                    private set => _name = value;
                }
            }
        """.trimIndent(), apply("""
            public class User
            {
                public string Na<caret>me { get; private set; } = "none";
            }
        """, "To property with backing field"))
        // get-only: a readonly field
        assertEquals("""
            public class User
            {
                private readonly int _id;

                public int Id
                {
                    get => _id;
                }
            }
        """.trimIndent(), apply("""
            public class User
            {
                public int <caret>Id { get; }
            }
        """, "To property with backing field"))
    }

    fun testToPropertyWithFieldKeywordAndComputed() {
        assertEquals("""
            public class User
            {
                public string Name
                {
                    get => field;
                    set => field = value;
                } = "none";
            }
        """.trimIndent(), apply("""
            public class User
            {
                public string <caret>Name { get; set; } = "none";
            }
        """, "To property with 'field' keyword"))
        assertEquals("""
            public class User
            {
                public string Name => "none";
            }
        """.trimIndent(), apply("""
            public class User
            {
                public string <caret>Name { get; set; } = "none";
            }
        """, "To computed property"))
        assertEquals("""
            using System;
            public class User
            {
                public string Name => throw new NotImplementedException();
            }
        """.trimIndent(), apply("""
            using System;
            public class User
            {
                public string <caret>Name { get; set; }
            }
        """, "To computed property"))
    }

    fun testToAutoProperty() {
        assertEquals("""
            public class User
            {
                public string Name { get; private set; } = "none";
            }
        """.trimIndent(), apply("""
            public class User
            {
                private string _name = "none";

                public string <caret>Name
                {
                    get { return _name; }
                    private set => _name = value;
                }
            }
        """, "To auto-property"))
    }

    fun testPropertyActionsAreNotOfferedElsewhere() {
        // an auto-property is no candidate of "To auto-property", a property with logic none of the others'
        assertFalse(available("public class User { public string <caret>Name { get; set; } }", "To auto-property"))
        val logic = "public class User { private string _name; public string <caret>Name { get => _name; set => _name = value.Trim(); } }"
        assertFalse(available(logic, "To auto-property"))
        assertFalse(available(logic, "To property with backing field"))
        // the field used elsewhere stays
        assertFalse(available("public class User { private string _name; public string <caret>Name { get => _name; set => _name = value; } public void Reset() => _name = null; }", "To auto-property"))
        // an abstract property, an interface's, a field of that name already there
        assertFalse(available("public abstract class User { public abstract string <caret>Name { get; set; } }", "To property with backing field"))
        assertFalse(available("public interface IUser { string <caret>Name { get; set; } }", "To computed property"))
        assertFalse(available("public class User { private int _name; public string <caret>Name { get; set; } }", "To property with backing field"))
    }

    // ---- Encapsulate field

    fun testEncapsulateField() {
        assertEquals("""
            public class Manager
            {
                private readonly string _logger;

                public string Logger => _logger;
            }
        """.trimIndent(), apply("""
            public class Manager
            {
                private readonly string _log<caret>ger;
            }
        """, "Encapsulate field"))
        // a public field named as its property: renamed, private, the property public
        assertEquals("""
            public class Counter
            {
                private int _count;

                public int Count
                {
                    get => _count;
                    set => _count = value;
                }
            }
        """.trimIndent(), apply("""
            public class Counter
            {
                public int Co<caret>unt;
            }
        """, "Encapsulate field"))
        assertFalse(available("public class C { private const int <caret>Max = 1; }", "Encapsulate field"))
        assertFalse(available("public class C { private int <caret>_count; public int Count => 0; }", "Encapsulate field"))
    }

    // ---- To factory method

    fun testToFactoryMethod() {
        assertEquals("""
            public class Order
            {
                public int Id { get; }

                private Order(int id)
                {
                    Id = id;
                }

                public static Order Create(int id) => new Order(id);
            }

            public class Shop
            {
                public Order Make() => Order.Create(1);
                public Order Other() => new Order(2) { };
            }
        """.trimIndent(), apply("""
            public class Order
            {
                public int Id { get; }

                public Ord<caret>er(int id)
                {
                    Id = id;
                }
            }

            public class Shop
            {
                public Order Make() => new Order(1);
                public Order Other() => new Order(2) { };
            }
        """, "To factory method"))
        assertFalse(available("public abstract class Base { protected Ba<caret>se() { } }", "To factory method"))
        assertFalse(available("public class Box<T> { public B<caret>ox() { } }", "To factory method"))
        assertFalse(available("public class Order { static Ord<caret>er() { } }", "To factory method"))
    }

    // ---- To explicit / implicit implementation

    fun testToExplicitAndBack() {
        assertEquals("""
            public interface IRunner { void Run(); }

            public class Runner : IRunner
            {
                void IRunner.Run() { }
            }
        """.trimIndent(), apply("""
            public interface IRunner { void Run(); }

            public class Runner : IRunner
            {
                public void R<caret>un() { }
            }
        """, "To explicit implementation"))
        assertEquals("""
            public interface IRunner { void Run(); }

            public class Runner : IRunner
            {
                public void Run() { }
            }
        """.trimIndent(), apply("""
            public interface IRunner { void Run(); }

            public class Runner : IRunner
            {
                void IRunner.R<caret>un() { }
            }
        """, "To implicit implementation"))
        // a member implementing nothing, a virtual one
        assertFalse(available("public class Runner { public void R<caret>un() { } }", "To explicit implementation"))
        assertFalse(available("public interface IRunner2 { void Run(); } public class Runner2 : IRunner2 { public virtual void R<caret>un() { } }", "To explicit implementation"))
    }

    companion object {
        private fun bytes(name: String): ByteArray? = CSharpMemberActionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
