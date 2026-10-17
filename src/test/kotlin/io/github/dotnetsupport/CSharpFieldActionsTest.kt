package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * "Initialize field from constructor" / "Initialize members from constructor" (0.1.168, `NativeCSharpFieldActions.kt`, the user's report):
 * the parameter and the assignment in each constructor that leaves the field unassigned, a constructor made where there is none, a primary
 * constructor's parameter with the field's initializer; and where they are not offered.
 */
class CSharpFieldActionsTest : BasePlatformTestCase() {
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun apply(code: String, title: String = FIELD): String {
        myFixture.configureByText("Fields${files++}.cs", code.trimIndent() + "\n")
        myFixture.launchAction(myFixture.findSingleIntention(title))
        return myFixture.editor.document.text.trimEnd()
    }

    private fun available(code: String, title: String = FIELD): Boolean {
        myFixture.configureByText("Fields${files++}.cs", code.trimIndent() + "\n")
        return myFixture.filterAvailableIntentions(title).any { it.text == title }
    }

    fun testOneConstructorGetsTheParameterAndTheAssignment() {
        val result = apply(
            """
            public class OrderService
            {
                private readonly string _name;
                private readonly int _<caret>count;

                public OrderService(string name)
                {
                    _name = name;
                    Start();
                }

                void Start() { }
            }
            """,
        )
        assertTrue(result, result.contains("public OrderService(string name, int count)"))
        assertTrue(result, result.contains("        _name = name;\n        _count = count;\n        Start();"))
    }

    fun testTwoConstructorsOneChaining() {
        val result = apply(
            """
            public class Box
            {
                private readonly string _la<caret>bel;

                public Box() : this(0) { }

                public Box(int size)
                {
                }
            }
            """,
        )
        assertTrue(result, result.contains("public Box() : this(0) { }"))
        assertTrue(result, result.contains("public Box(int size, string label)\n    {\n        _label = label;\n    }"))
    }

    fun testNoConstructorMakesOne() {
        val result = apply(
            """
            public class Clock
            {
                private readonly System.TimeSpan _off<caret>set;

                public void Tick() { }
            }
            """,
        )
        assertTrue(result, result.contains("    private readonly System.TimeSpan _offset;\n\n    public Clock(System.TimeSpan offset)\n    {\n        _offset = offset;\n    }"))
    }

    fun testPrimaryConstructorGetsTheParameterAndTheFieldItsInitializer() {
        val result = apply(
            """
            public class Report(string title)
            {
                private readonly int _pa<caret>ges;
            }
            """,
        )
        assertTrue(result, result.contains("public class Report(string title, int pages)"))
        assertTrue(result, result.contains("private readonly int _pages = pages;"))
    }

    fun testANameEqualToTheParameterIsQualified() {
        val result = apply(
            """
            public class Person
            {
                private readonly string na<caret>me;

                public Person()
                {
                }
            }
            """,
        )
        assertTrue(result, result.contains("public Person(string name)"))
        assertTrue(result, result.contains("this.name = name;"))
    }

    fun testAnExpressionBodiedConstructorBecomesABlock() {
        val result = apply(
            """
            public class Timer
            {
                private readonly int _per<caret>iod;

                public Timer(int ticks) => Start(ticks);

                void Start(int ticks) { }
            }
            """,
        )
        assertTrue(result, result.contains("public Timer(int ticks, int period)\n    {\n        Start(ticks);\n        _period = period;\n    }"))
    }

    fun testNotOffered() {
        // assigned in every constructor
        assertFalse(available("public class A\n{\n    private readonly int _v<caret>alue;\n    public A(int value) { _value = value; }\n}"))
        assertFalse(available("public class A\n{\n    private readonly int _v<caret>alue;\n    public A(int value) { this._value = value; }\n}"))
        // static, const, initialized
        assertFalse(available("public class A\n{\n    private static readonly int _v<caret>alue;\n}"))
        assertFalse(available("public class A\n{\n    private const int V<caret>alue = 1;\n}"))
        assertFalse(available("public class A\n{\n    private readonly int _v<caret>alue = 1;\n}"))
        // in a body, not on a field
        assertFalse(available("public class A\n{\n    private readonly int _value;\n    public A() { var x<caret> = 1; }\n}"))
    }

    fun testAllMembersAtOnce() {
        val code = """
            public class Service
            {
                private readonly string _na<caret>me;
                private readonly int _count;
                private readonly bool _ready = true;
            }
            """
        assertTrue(available(code, MEMBERS))
        val result = apply(code, MEMBERS)
        assertTrue(result, result.contains("public Service(string name, int count)\n    {\n        _name = name;\n        _count = count;\n    }"))
        // one field: only the single action
        assertFalse(available("public class A\n{\n    private readonly int _v<caret>alue;\n}", MEMBERS))
    }

    companion object {
        private const val FIELD = "Initialize field from constructor"
        private const val MEMBERS = "Initialize members from constructor"

        private fun bytes(name: String): ByteArray? = CSharpFieldActionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
