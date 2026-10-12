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
 * The context actions on `if` (0.1.163, `NativeCSharpIfActions.kt`): Invert 'if', Merge nested 'if' statements, Split into two 'if's,
 * Convert to guard clause, Add braces / Remove braces; and where each is not offered.
 */
class CSharpIfActionsTest : BasePlatformTestCase() {
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

    /** [members] as the members of a class after the usings; after [title] at `<caret>`, the members back. */
    private fun apply(members: String, title: String): String {
        myFixture.configureByText("If${files++}.cs", wrap(members))
        myFixture.launchAction(myFixture.findSingleIntention(title))
        return unwrap(myFixture.editor.document.text)
    }

    private fun available(members: String, title: String): Boolean {
        myFixture.configureByText("If${files++}.cs", wrap(members))
        return myFixture.availableIntentions.any { it.text == title }
    }

    private fun wrap(members: String): String = HEAD + members.trimIndent().prependIndent("    ") + "\n}\n"

    private fun unwrap(text: String): String = text.removePrefix(HEAD).removeSuffix("\n}\n").lines().joinToString("\n") { it.removePrefix("    ") }

    // ---- Invert 'if'

    fun testInvertSwapsTheBranchesAndNegatesTheCondition() {
        assertEquals(
            """
            void M(int n)
            {
                if (n != 0)
                {
                    B();
                }
                else
                {
                    A();
                }
            }
            """.trimIndent(),
            apply("""
                void M(int n)
                {
                    i<caret>f (n == 0)
                    {
                        A();
                    }
                    else
                    {
                        B();
                    }
                }
            """, "Invert 'if'"),
        )
        assertEquals(
            "void M(bool ok)\n{\n    if (ok) B(); else A();\n}",
            apply("void M(bool ok)\n{\n    <caret>if (!ok) A(); else B();\n}", "Invert 'if'"),
        )
    }

    fun testInvertNegatesAsRiderWrites() {
        fun inverted(condition: String): String {
            val text = apply("void M(int n, string s, object o, bool a, bool b)\n{\n    <caret>if ($condition) A(); else B();\n}", "Invert 'if'")
            return text.substringAfter("if (").substringBefore(") B();")
        }
        assertEquals("n >= 1", inverted("n < 1"))
        assertEquals("n <= 1", inverted("n > 1"))
        assertEquals("!a", inverted("a"))
        assertEquals("a", inverted("!a"))
        assertEquals("a && b", inverted("!(a && b)"))
        assertEquals("o is not null", inverted("o is null"))
        assertEquals("o is null", inverted("o is not null"))
        assertEquals("s is not string", inverted("s is string"))
        assertEquals("n != 1 || !b", inverted("n == 1 && b"))
        assertEquals("!a && n >= 2", inverted("a || n < 2"))
        assertEquals("!(a ? b : a)", inverted("a ? b : a"))
        assertEquals("n <= 1 || (!a && !b)", inverted("n > 1 && (a || b)"))
        assertEquals("(!a || !b) && n >= 2", inverted("(a && b) || n < 2"))
    }

    fun testInvertWithoutElseMakesAGuardAtTheEndOfABody() {
        assertEquals(
            """
            void M(bool ok)
            {
                if (!ok) return;
                A();
                B();
            }
            """.trimIndent(),
            apply("""
                void M(bool ok)
                {
                    <caret>if (ok)
                    {
                        A();
                        B();
                    }
                }
            """, "Invert 'if'"),
        )
        assertEquals(
            "void M(int[] items)\n{\n    foreach (var item in items)\n    {\n        if (item == 0) continue;\n        A();\n    }\n}",
            apply("void M(int[] items)\n{\n    foreach (var item in items)\n    {\n        <caret>if (item != 0)\n        {\n            A();\n        }\n    }\n}", "Invert 'if'"),
        )
    }

    fun testInvertIsNotOffered() {
        // an `else if` chain; not the last statement without `else`; a method that returns a value; the caret in the body
        assertFalse(available("void M(int n)\n{\n    <caret>if (n == 0) A(); else if (n == 1) B(); else C();\n}", "Invert 'if'"))
        assertFalse(available("void M(bool ok)\n{\n    <caret>if (ok) A();\n    B();\n}", "Invert 'if'"))
        assertFalse(available("int M(bool ok)\n{\n    <caret>if (ok) A();\n}", "Invert 'if'"))
        assertFalse(available("void M(bool ok)\n{\n    if (ok) <caret>A(); else B();\n}", "Invert 'if'"))
    }

    // ---- Convert to guard clause

    fun testGuardClause() {
        assertEquals(
            "async Task M(string s)\n{\n    if (string.IsNullOrEmpty(s)) return;\n    A();\n    B();\n}",
            apply("async Task M(string s)\n{\n    <caret>if (!string.IsNullOrEmpty(s))\n    {\n        A();\n        B();\n    }\n}", "Convert to guard clause"),
        )
        // one statement only (Invert 'if' does that), an `else`, not the last statement, a value returned
        assertFalse(available("void M(bool ok)\n{\n    <caret>if (ok)\n    {\n        A();\n    }\n}", "Convert to guard clause"))
        assertFalse(available("void M(bool ok)\n{\n    <caret>if (ok)\n    {\n        A();\n        B();\n    }\n    else { C(); }\n}", "Convert to guard clause"))
        assertFalse(available("void M(bool ok)\n{\n    <caret>if (ok)\n    {\n        A();\n        B();\n    }\n    C();\n}", "Convert to guard clause"))
        assertFalse(available("int M(bool ok)\n{\n    <caret>if (ok)\n    {\n        A();\n        B();\n    }\n}", "Convert to guard clause"))
    }

    // ---- Merge nested 'if' statements

    fun testMergeNestedIfs() {
        assertEquals(
            """
            void M(int n, bool a, bool b)
            {
                if (n > 0 && (a || b))
                {
                    A();
                    B();
                }
            }
            """.trimIndent(),
            apply("""
                void M(int n, bool a, bool b)
                {
                    <caret>if (n > 0)
                    {
                        if (a || b)
                        {
                            A();
                            B();
                        }
                    }
                }
            """, "Merge nested 'if' statements"),
        )
        assertEquals(
            "void M(bool a, bool b)\n{\n    if (a && b) A();\n}",
            apply("void M(bool a, bool b)\n{\n    <caret>if (a) if (b) A();\n}", "Merge nested 'if' statements"),
        )
        assertEquals(
            "void M(bool a, bool b)\n{\n    if (a && b)\n        A();\n}",
            apply("void M(bool a, bool b)\n{\n    <caret>if (a)\n    {\n        if (b)\n            A();\n    }\n}", "Merge nested 'if' statements"),
        )
    }

    fun testMergeNestedIsNotOffered() {
        // something else in the outer body; an `else` on the inner; an `else` on the outer; no inner `if`
        assertFalse(available("void M(bool a, bool b)\n{\n    <caret>if (a)\n    {\n        if (b) A();\n        B();\n    }\n}", "Merge nested 'if' statements"))
        assertFalse(available("void M(bool a, bool b)\n{\n    <caret>if (a)\n    {\n        if (b) A(); else B();\n    }\n}", "Merge nested 'if' statements"))
        assertFalse(available("void M(bool a, bool b)\n{\n    <caret>if (a)\n    {\n        if (b) A();\n    }\n    else B();\n}", "Merge nested 'if' statements"))
        assertFalse(available("void M(bool a)\n{\n    <caret>if (a)\n    {\n        A();\n    }\n}", "Merge nested 'if' statements"))
    }

    // ---- Split into two 'if's

    fun testSplitIntoTwoIfs() {
        assertEquals(
            """
            void M(int n, bool a)
            {
                if (n > 0)
                {
                    if (a)
                    {
                        A();
                        B();
                    }
                }
            }
            """.trimIndent(),
            apply("""
                void M(int n, bool a)
                {
                    <caret>if (n > 0 && a)
                    {
                        A();
                        B();
                    }
                }
            """, "Split into two 'if's"),
        )
        assertEquals(
            "void M(bool a, bool b)\n{\n    if (a)\n    {\n        if (b)\n            A();\n    }\n}",
            apply("void M(bool a, bool b)\n{\n    <caret>if (a && b) A();\n}", "Split into two 'if's"),
        )
        // `||`, an `else`
        assertFalse(available("void M(bool a, bool b)\n{\n    <caret>if (a || b) A();\n}", "Split into two 'if's"))
        assertFalse(available("void M(bool a, bool b)\n{\n    <caret>if (a && b) A(); else B();\n}", "Split into two 'if's"))
    }

    // ---- Add braces / Remove braces

    fun testAddBraces() {
        assertEquals(
            "void M(bool ok)\n{\n    if (ok)\n    {\n        A();\n    }\n}",
            apply("void M(bool ok)\n{\n    <caret>if (ok) A();\n}", "Add braces"),
        )
        assertEquals(
            "void M(bool ok)\n{\n    if (ok)\n    {\n        A();\n    }\n    else\n    {\n        B();\n    }\n}",
            apply("void M(bool ok)\n{\n    if (ok)\n    {\n        A();\n    }\n    el<caret>se\n        B();\n}", "Add braces"),
        )
        assertEquals(
            "void M(int[] items)\n{\n    foreach (var item in items)\n    {\n        A(item);\n    }\n}",
            apply("void M(int[] items)\n{\n    <caret>foreach (var item in items)\n        A(item);\n}", "Add braces"),
        )
        assertEquals(
            "void M(int n)\n{\n    while (n > 0)\n    {\n        n--;\n    }\n}",
            apply("void M(int n)\n{\n    <caret>while (n > 0) n--;\n}", "Add braces"),
        )
        // a block already; `else if`
        assertFalse(available("void M(bool ok)\n{\n    <caret>if (ok)\n    {\n        A();\n    }\n}", "Add braces"))
        assertFalse(available("void M(bool ok)\n{\n    if (ok) A();\n    el<caret>se if (!ok) B();\n}", "Add braces"))
    }

    fun testRemoveBraces() {
        assertEquals(
            "void M(bool ok)\n{\n    if (ok)\n        A();\n}",
            apply("void M(bool ok)\n{\n    <caret>if (ok)\n    {\n        A();\n    }\n}", "Remove braces"),
        )
        assertEquals(
            "void M(bool ok)\n{\n    if (ok)\n        A();\n    else\n    {\n        B();\n    }\n}",
            apply("void M(bool ok)\n{\n    <caret>if (ok)\n    {\n        A();\n    }\n    else\n    {\n        B();\n    }\n}", "Remove braces"),
        )
        assertEquals(
            "void M(int n)\n{\n    while (n > 0)\n        n--;\n}",
            apply("void M(int n)\n{\n    <caret>while (n > 0) { n--; }\n}", "Remove braces"),
        )
        // two statements; a declaration; a dangling `else`; no braces
        assertFalse(available("void M(bool ok)\n{\n    <caret>if (ok)\n    {\n        A();\n        B();\n    }\n}", "Remove braces"))
        assertFalse(available("void M(bool ok)\n{\n    <caret>if (ok)\n    {\n        var x = 1;\n    }\n}", "Remove braces"))
        assertFalse(available("void M(bool ok, bool b)\n{\n    <caret>if (ok)\n    {\n        if (b) A();\n    }\n    else B();\n}", "Remove braces"))
        assertFalse(available("void M(bool ok)\n{\n    <caret>if (ok) A();\n}", "Remove braces"))
    }

    private companion object {
        const val HEAD = "using System;\nusing System.Threading.Tasks;\n\nnamespace Demo;\n\nclass Sample\n{\n    static void A() { }\n    static void A(int n) { }\n    static void B() { }\n    static void C() { }\n\n"

        private fun bytes(name: String): ByteArray? = CSharpIfActionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
