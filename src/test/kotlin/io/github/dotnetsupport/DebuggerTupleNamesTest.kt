package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.CSharpFile
import io.github.dotnetsupport.debugger.DotNetTupleNames
import io.github.dotnetsupport.debugger.TupleShape
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFrameLocals
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * The names of tuple elements in the debugger come from the declarations of the frame's variables ([CSharpFrameLocals]): the line of the
 * stop is marked `// STOP`, the variables are looked up by name as the adapter lists them.
 */
class DebuggerTupleNamesTest : BasePlatformTestCase() {
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

    /** The shapes of [names] at the `// STOP` line of [body], a method of a class. */
    private fun shapes(body: String, vararg names: String): Map<String, TupleShape> {
        val file = myFixture.addFileToProject("tuples/T${counter++}.cs", """
            using System;

            class Sample
            {
                void Body((int X, int Y) point, (int, string) plain)
                {
            ${body.prependIndent("        ")}
                }
            }
        """.trimIndent()) as CSharpFile
        val line = file.text.lines().indexOfFirst { "// STOP" in it }
        assertTrue(line >= 0)
        return CSharpFrameLocals.declaredTypes(file, line, names.toList()).mapNotNull { (name, type) -> DotNetTupleNames.shape(type)?.let { name to it } }.toMap()
    }

    private fun TupleShape.text(): String = names.indices.joinToString(", ", "(", ")") { i -> (names[i] ?: "_") + (elements[i]?.let { " ${it.text()}" } ?: "") }

    fun testNamedTupleLiteral() {
        val shapes = shapes("""
            var tuple = (Id: 1, Name: "tuple");
            Console.WriteLine(tuple); // STOP
        """.trimIndent(), "tuple")
        val shape = shapes["tuple"]!!
        assertEquals("(Id, Name)", shape.text())
        assertEquals(listOf("Id", "Name"), DotNetTupleNames.names(listOf("Item1", "Item2"), shape))
        assertEquals("(Id: 1, Name: \"tuple\")", DotNetTupleNames.summary("(1, \"tuple\")", shape))
    }

    fun testNestedPartialAndUnnamed() {
        val shapes = shapes("""
            (int Id, (string Name, bool Ok) Inner) nested = (1, ("x", true));
            (int Id, string) partial = (2, "y");
            var unnamed = (3, "z");
            var count = 4;
            Console.WriteLine(nested); // STOP
        """.trimIndent(), "nested", "partial", "unnamed", "count")
        assertEquals("(Id, Inner (Name, Ok))", shapes["nested"]!!.text())
        assertEquals("(Id, _)", shapes["partial"]!!.text())
        assertEquals(listOf("Id", "Item2"), DotNetTupleNames.names(listOf("Item1", "Item2"), shapes["partial"]))
        // no names at all, not a tuple: the adapter's names stay
        assertNull(shapes["unnamed"])
        assertNull(shapes["count"])
    }

    fun testParametersAndScopes() {
        val shapes = shapes("""
            {
                var inner = (A: 1, B: 2);
            }
            var later = (C: 1, D: 2);
            Console.WriteLine(point); // STOP
            var after = (E: 1, F: 2);
        """.trimIndent(), "point", "plain", "inner", "later", "after")
        assertEquals("(X, Y)", shapes["point"]!!.text())
        assertNull(shapes["plain"])
        // a block the line is not in, a declaration below the line: not what the frame sees
        assertNull(shapes["inner"])
        assertNull(shapes["after"])
        assertEquals("(C, D)", shapes["later"]!!.text())
    }

    fun testALocalOfALoopBody() {
        val shapes = shapes("""
            foreach (var i in new[] { 1 })
            {
                var item = (Inner: 1, Value: 2);
                Console.WriteLine(item); // STOP
            }
            var item2 = 0;
        """.trimIndent(), "item")
        assertEquals("(Inner, Value)", shapes["item"]!!.text())
    }

    private companion object {
        var counter = 0

        private fun bytes(name: String): ByteArray? = DebuggerTupleNamesTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy { AssemblyIndexSet(listOf("System.Runtime", "System.Console").map(::fixture)) }
    }
}
