package io.github.dotnetsupport

import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The index of an assembly as the indexer writes it (indexer/Program.cs), read by the plugin. The fixtures are the indexes of
 * System.Console.dll and System.Linq.dll of the reference pack of .NET 10, made by the indexer of the repository.
 */
class AssemblyIndexTest {
    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/index/$name.dnix")!!.use { it.readBytes() }

    private val console = AssemblyIndex.read(fixture("System.Console"))
    private val linq = AssemblyIndex.read(fixture("System.Linq"))

    @Test
    fun `the header`() {
        assertEquals(AssemblyIndex.FORMAT_VERSION, console.version)
        assertEquals("System.Console", console.assemblyName)
        assertEquals("System.Linq", linq.assemblyName)
        assertEquals("the name of the file is the MVID", 32, console.mvid.length)
        assertTrue(console.typeCount > 0 && console.memberCount > 0)
        assertEquals("every type and every member is found by its name", console.typeCount + console.memberCount, console.nameCount)
    }

    @Test
    fun `a static method by the beginning of its name`() {
        val found = console.members("WriteLi")
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.name == "WriteLine" && it.qualifiedName == "Console.WriteLine" })
        assertTrue(found.all { it.kind == IndexedMemberKind.METHOD && it.returnType == "void" && it.type.namespace == "System" })
        assertTrue("the overloads are all there: " + found.size, found.size >= 17)
        val signatures = found.map { it.signature }.toSet()
        assertTrue(signatures.toString(), "()" in signatures)
        assertTrue(signatures.toString(), "(string value)" in signatures)
        assertTrue(signatures.toString(), "(int value)" in signatures)
        assertTrue(signatures.toString(), signatures.any { it.startsWith("(string format, params ") })
        assertEquals("void Console.WriteLine()", found.first { it.parameters.isEmpty() }.toString())
    }

    @Test
    fun `the case of the letters does not matter`() {
        assertEquals(console.members("WriteLi").map { it.toString() }, console.members("writeli").map { it.toString() })
        assertEquals(console.members("WriteLi").size, console.members("WRITELI").size)
        assertTrue(console.members("Write").map { it.name }.toSet().containsAll(setOf("Write", "WriteLine")))
        assertTrue(console.members("zzz").isEmpty())
        assertTrue("an empty prefix is everything, up to the limit", console.members("", limit = 5).size == 5)
    }

    @Test
    fun `properties, types and enum members`() {
        val title = console.members("Title").single()
        assertEquals(IndexedMemberKind.PROPERTY, title.kind)
        assertEquals("string", title.returnType)
        assertEquals("", title.signature)

        val type = console.types("Console").first { it.name == "Console" }
        assertEquals(IndexedTypeKind.STATIC_CLASS, type.kind)
        assertEquals("System.Console", type.qualifiedName)
        val color = console.types("ConsoleCol").single()
        assertEquals(IndexedTypeKind.ENUM, color.kind)
        val red = console.members("DarkR").single()
        assertEquals(IndexedMemberKind.ENUM_MEMBER, red.kind)
        assertEquals("ConsoleColor.DarkRed", red.qualifiedName)
        assertTrue("types and members are asked for apart", console.types("WriteLi").isEmpty())
    }

    @Test
    fun `extension methods say what they extend`() {
        val select = linq.members("Select").filter { it.name == "Select" }
        assertTrue(select.isNotEmpty())
        for (method in select) {
            assertEquals(IndexedMemberKind.EXTENSION_METHOD, method.kind)
            assertEquals("Enumerable", method.type.name)
            assertEquals("System.Linq", method.type.namespace)
            assertTrue(method.parameters.first().isThis)
            assertEquals("IEnumerable<TSource>", method.parameters.first().type)
            assertEquals(2, method.arity)
            assertEquals("IEnumerable<TResult>", method.returnType)
        }
        assertTrue(select.map { it.signature }.toString(), "(this IEnumerable<TSource> source, Func<TSource, TResult> selector)" in select.map { it.signature })
        val range = linq.members("Range").single { it.name == "Range" }
        assertEquals("a static method that extends nothing", IndexedMemberKind.METHOD, range.kind)
        assertEquals("(int start, int count)", range.signature)
    }

    @Test
    fun `a file that is not an index is refused`() {
        fun refused(bytes: ByteArray) = runCatching { AssemblyIndex.read(bytes) }.exceptionOrNull()
        assertNotNull(refused(ByteArray(0)))
        assertNotNull(refused("not an index at all, but long enough to have a header of the right size, is it not".toByteArray()))
        val whole = fixture("System.Console")
        assertNotNull("cut short", refused(whole.copyOf(whole.size / 2)))
        assertNotNull("another format", refused(whole.copyOf().also { it[4] = 99 }))
        assertFalse(refused(whole) != null)
    }

    @Test
    fun `a mapped file reads the same`() {
        val file = Files.createTempFile("System.Console", "." + AssemblyIndex.EXTENSION)
        try {
            Files.write(file, fixture("System.Console"))
            val mapped = AssemblyIndex.open(file)
            assertEquals(console.members("WriteLi").map { it.toString() }, mapped.members("WriteLi").map { it.toString() })
            assertEquals(console.mvid, mapped.mvid)
        } finally {
            // a mapped file stays locked on Windows until the buffer is collected
            runCatching { Files.deleteIfExists(file) }
        }
    }
}
