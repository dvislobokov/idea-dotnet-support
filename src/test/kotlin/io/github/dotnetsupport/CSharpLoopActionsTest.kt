package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * The context actions on loops (0.1.163, `NativeCSharpLoopActions.kt`): `foreach` ↔ `for` over an indexable collection, a `for` reversed,
 * `foreach` over an expression statement; and where each is not offered.
 */
class CSharpLoopActionsTest : BasePlatformTestCase() {
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

    /** [members] as the members of a class after the usings; after [title] at `<caret>`, the members back. */
    private fun apply(members: String, title: String): String {
        myFixture.configureByText("Loop${files++}.cs", wrap(members))
        myFixture.launchAction(myFixture.findSingleIntention(title))
        return unwrap(myFixture.editor.document.text)
    }

    private fun available(members: String, title: String): Boolean {
        myFixture.configureByText("Loop${files++}.cs", wrap(members))
        return myFixture.filterAvailableIntentions(title).any { it.text == title }
    }

    private fun wrap(members: String): String = HEAD + members.trimIndent().prependIndent("    ") + "\n}\n"

    private fun unwrap(text: String): String = text.removePrefix(HEAD).removeSuffix("\n}\n").lines().joinToString("\n") { it.removePrefix("    ") }

    // ---- Convert 'foreach' to 'for'

    fun testForEachOverAListBecomesForWithCount() {
        assertEquals(
            """
            void M(List<string> items)
            {
                for (var i = 0; i < items.Count; i++)
                {
                    var item = items[i];
                    Console.WriteLine(item);
                }
            }
            """.trimIndent(),
            apply("""
                void M(List<string> items)
                {
                    <caret>foreach (var item in items)
                    {
                        Console.WriteLine(item);
                    }
                }
            """, "Convert 'foreach' to 'for'"),
        )
    }

    fun testForEachOverAnArrayUsesLengthAndAFreeIndex() {
        // `i` is taken by the parameter, so the index is `j`; a one-statement body gets its braces
        assertEquals(
            "void M(int[] numbers, int i)\n{\n    for (var j = 0; j < numbers.Length; j++)\n    {\n        var n = numbers[j];\n        Console.WriteLine(n);\n    }\n}",
            apply("void M(int[] numbers, int i)\n{\n    <caret>foreach (var n in numbers) Console.WriteLine(n);\n}", "Convert 'foreach' to 'for'"),
        )
        assertTrue(apply("void M(string s)\n{\n    <caret>foreach (var c in s) { }\n}", "Convert 'foreach' to 'for'").contains("for (var i = 0; i < s.Length; i++)\n    {\n        var c = s[i];\n    }"))
    }

    fun testForEachToForIsNotOffered() {
        // not indexable; the caret in the body
        assertFalse(available("void M(IEnumerable<string> items)\n{\n    <caret>foreach (var item in items) { }\n}", "Convert 'foreach' to 'for'"))
        assertFalse(available("void M(List<string> items)\n{\n    foreach (var item in items)\n    {\n        <caret>Console.WriteLine(item);\n    }\n}", "Convert 'foreach' to 'for'"))
    }

    // ---- Convert 'for' to 'foreach'

    fun testForBecomesForEachAndTheNamingStatementGoes() {
        assertEquals(
            "void M(List<string> items)\n{\n    foreach (var item in items)\n    {\n        Console.WriteLine(item);\n    }\n}",
            apply("void M(List<string> items)\n{\n    <caret>for (var i = 0; i < items.Count; i++)\n    {\n        var item = items[i];\n        Console.WriteLine(item);\n    }\n}", "Convert 'for' to 'foreach'"),
        )
        // the element named after the collection, every `names[i]` replaced
        assertEquals(
            "void M(string[] names)\n{\n    foreach (var name in names)\n    {\n        Console.WriteLine(name + name.Length);\n    }\n}",
            apply("void M(string[] names)\n{\n    <caret>for (int i = 0; i < names.Length; i++)\n    {\n        Console.WriteLine(names[i] + names[i].Length);\n    }\n}", "Convert 'for' to 'foreach'"),
        )
    }

    fun testForToForEachIsNotOffered() {
        // the index used by itself; the element written; a bound that is no collection
        assertFalse(available("void M(string[] names)\n{\n    <caret>for (var i = 0; i < names.Length; i++)\n    {\n        Console.WriteLine(i);\n    }\n}", "Convert 'for' to 'foreach'"))
        assertFalse(available("void M(string[] names)\n{\n    <caret>for (var i = 0; i < names.Length; i++)\n    {\n        names[i] = \"\";\n    }\n}", "Convert 'for' to 'foreach'"))
        assertFalse(available("void M(string[] names)\n{\n    <caret>for (var i = 0; i < 10; i++)\n    {\n        Console.WriteLine(names[i]);\n    }\n}", "Convert 'for' to 'foreach'"))
    }

    // ---- Reverse 'for'

    fun testReverseFor() {
        assertEquals(
            "void M(List<string> items)\n{\n    for (var i = items.Count - 1; i >= 0; i--)\n    {\n    }\n}",
            apply("void M(List<string> items)\n{\n    <caret>for (var i = 0; i < items.Count; i++)\n    {\n    }\n}", "Reverse 'for'"),
        )
        assertEquals(
            "void M(int n)\n{\n    for (int i = 0; i < n; i++)\n    {\n    }\n}",
            apply("void M(int n)\n{\n    <caret>for (int i = n - 1; i >= 0; i--)\n    {\n    }\n}", "Reverse 'for'"),
        )
        assertFalse(available("void M(int n)\n{\n    <caret>for (var i = 1; i <= n; i += 2)\n    {\n    }\n}", "Reverse 'for'"))
    }

    // ---- Iterate via 'foreach'

    fun testIterateViaForEach() {
        val text = apply("void M(List<string> items)\n{\n    <caret>items;\n}", "Iterate via 'foreach'")
        assertEquals("void M(List<string> items)\n{\n    foreach (var item in items)\n    {\n        \n    }\n}", text)
        assertFalse(available("void M(int count)\n{\n    <caret>count;\n}", "Iterate via 'foreach'"))
    }

    private companion object {
        const val HEAD = "using System;\nusing System.Collections.Generic;\n\nnamespace Demo;\n\nclass Sample\n{\n"

        private fun bytes(name: String): ByteArray? = CSharpLoopActionsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
