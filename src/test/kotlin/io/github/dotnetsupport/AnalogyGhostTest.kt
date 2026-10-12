package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpTypingGhost
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The analogy and value rules of the gray text (0.1.163, `NativeCSharpAnalogyGhost.kt`): the statement a `switch` repeats, the collection
 * of a `foreach` and the bound of a `for`, the parameter of `nameof(`, the string of `string.IsNullOrEmpty(`; where each keeps silent.
 */
class AnalogyGhostTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
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

    private fun code(body: String, parameters: String = "", members: String = ""): String = """
using System;
using System.Collections.Generic;

namespace Shop;

public enum OperationType { Insert, Update, Delete, Read }

public class Order { public int Id { get; set; } }

public class Handler
{
    public static string Insert(Order o) => "";
    public static string Update(Order o) => "";
    public static string Delete(Order o) => "";
}

public static class Endpoints
{
$members
    public static string Map($parameters)
    {
$body
        return "";
    }
}
"""

    private fun ghost(text: String): NativeCSharpTypingGhost.Suggestion? {
        myFixture.configureByText("Analogy${counter++}.cs", text)
        return NativeCSharpTypingGhost.suggestion(myFixture.file as CSharpFile, myFixture.editor.document.charsSequence, myFixture.caretOffset)
    }

    private fun gray(text: String): String? = ghost(text)?.text

    private fun lines(vararg lines: String): String = lines.joinToString("\n") { "        $it" }

    // ---- 1. the statement a switch repeats

    fun testTheSectionRepeatsTheReturnWithTheMemberName() {
        val body = lines("switch (operation)", "{", "    case OperationType.Insert:", "        return \"Insert\";", "    case OperationType.Update:", "        return \"Update\";", "    case OperationType.Delete:", "        <caret>", "}")
        val ghost = ghost(code(body, "OperationType operation"))
        assertEquals("return \"Delete\";", ghost?.text)
        assertEquals("section analogy", ghost?.rule)
    }

    fun testTheSectionRepeatsACallAndKeepsTheBreakForTheNextLine() {
        val body = lines("var result = \"\";", "switch (operation)", "{", "    case OperationType.Insert:", "        result = Handler.Insert(order);", "        break;",
            "    case OperationType.Update:", "        result = Handler.Update(order);", "        break;", "    case OperationType.Delete:", "        <caret>", "}")
        assertEquals("result = Handler.Delete(order);", gray(code(body, "OperationType operation, Order order")))
    }

    fun testTheCamelCaseFormOfTheMemberIsReplacedToo() {
        val body = lines("switch (operation)", "{", "    case OperationType.Insert:", "        return Log(\"insert\", nameof(OperationType.Insert));",
            "    case OperationType.Update:", "        return Log(\"update\", nameof(OperationType.Update));", "    case OperationType.Delete:", "        <caret>", "}")
        val members = "    private static string Log(string a, string b) => a + b;"
        assertEquals("return Log(\"delete\", nameof(OperationType.Delete));", gray(code(body, "OperationType operation", members)))
    }

    fun testTheRestOfATypedStatement() {
        val sections = arrayOf("switch (operation)", "{", "    case OperationType.Insert:", "        return \"Insert\";", "    case OperationType.Update:", "        return \"Update\";", "    case OperationType.Delete:")
        assertEquals(" \"Delete\";", gray(code(lines(*sections, "        return<caret>", "}"), "OperationType operation")))
        assertEquals("elete\";", gray(code(lines(*sections, "        return \"D<caret>", "}"), "OperationType operation")))
    }

    fun testNoAnalogyWithOneSectionOrDifferentShapesOrAStatementWritten() {
        // one section before: too little to call it a pattern
        assertNull(gray(code(lines("switch (operation)", "{", "    case OperationType.Insert:", "        return \"Insert\";", "    case OperationType.Update:", "        <caret>", "}"), "OperationType operation")))
        // the sections differ in more than the member
        assertNull(gray(code(lines("switch (operation)", "{", "    case OperationType.Insert:", "        return \"Insert\";", "    case OperationType.Update:", "        return \"Updated\";", "    case OperationType.Delete:", "        <caret>", "}"), "OperationType operation")))
        // the member is not in the statement
        assertNull(gray(code(lines("switch (operation)", "{", "    case OperationType.Insert:", "        return \"one\";", "    case OperationType.Update:", "        return \"one\";", "    case OperationType.Delete:", "        <caret>", "}"), "OperationType operation")))
        // the section has its statement, the caret is below it
        assertNull(gray(code(lines("switch (operation)", "{", "    case OperationType.Insert:", "        return \"Insert\";", "    case OperationType.Update:", "        return \"Update\";", "    case OperationType.Delete:", "        Console.WriteLine();", "        <caret>", "}"), "OperationType operation")))
        // `default` names no member
        assertNull(gray(code(lines("switch (operation)", "{", "    case OperationType.Insert:", "        return \"Insert\";", "    case OperationType.Update:", "        return \"Update\";", "    default:", "        <caret>", "}"), "OperationType operation")))
    }

    fun testTheArmRepeatsTheValue() {
        val body = lines("var name = operation switch", "{", "    OperationType.Insert => \"Insert\",", "    OperationType.Update => \"Update\",", "    OperationType.Delete => <caret>", "};")
        assertEquals("\"Delete\",", gray(code(body, "OperationType operation")))
        val typed = lines("var name = operation switch", "{", "    OperationType.Insert => \"Insert\",", "    OperationType.Update => \"Update\",", "    OperationType.Delete => \"D<caret>", "};")
        assertEquals("elete\",", gray(code(typed, "OperationType operation")))
        assertNull(gray(code(lines("var name = operation switch", "{", "    OperationType.Insert => \"Insert\",", "    OperationType.Delete => <caret>", "};"), "OperationType operation")))
    }

    // ---- 2. the collection of a foreach

    fun testForeachTakesTheOneCollectionInScope() {
        val ghost = ghost(code(lines("foreach (var order in <caret>"), "List<Order> orders, int count"))
        assertEquals("orders)", ghost?.text)
        assertEquals("foreach collection", ghost?.rule)
        // `)` already there: the name alone; a typed start continues
        assertEquals("orders", gray(code(lines("foreach (var order in <caret>)"), "List<Order> orders")))
        assertEquals("ders)", gray(code(lines("foreach (var order in or<caret>"), "Order[] orders")))
        assertEquals("orders)", gray(code(lines("foreach (Order order in <caret>"), "IEnumerable<Order> orders")))
    }

    fun testForeachPicksTheCollectionNamedAsThePlural() {
        assertEquals("orders)", gray(code(lines("foreach (var order in <caret>"), "List<Order> orders, List<int> ids")))
        // several collections, none named as the element: silent; a string is no collection
        assertNull(gray(code(lines("foreach (var item in <caret>"), "List<Order> orders, List<int> ids")))
        assertNull(gray(code(lines("foreach (var c in <caret>"), "string name")))
        assertNull(gray(code(lines("foreach (var order in <caret>"), "int count")))
    }

    /** Jellyfin № 6: several collections, none named as the element — the one whose elements are of the element's type. */
    fun testForeachPicksTheCollectionOfTheElementsType() {
        val body = lines("var incoming = new Dictionary<int, string>();", "foreach (var order in <caret>")
        assertEquals("source)", gray(code(body, "ICollection<Order> source")))
        // a suffix of the type names the element too (`provider` of `IAuthProvider`); two of that type: silent
        assertNull(gray(code(lines("foreach (var order in <caret>"), "ICollection<Order> source, List<Order> other")))
    }

    // ---- 3. the bound of a for

    fun testForBoundOfTheOneCollection() {
        val ghost = ghost(code(lines("for (var i = 0; i < <caret>)"), "List<Order> orders"))
        assertEquals("orders.Count; i++", ghost?.text)
        assertEquals("for bound", ghost?.rule)
        assertEquals("orders.Length; i++)", gray(code(lines("for (int i = 0; i < <caret>"), "Order[] orders")))
        assertEquals("ders.Count; i++)", gray(code(lines("for (var i = 0; i < or<caret>"), "List<Order> orders")))
        // two collections, or one without Count: silent
        assertNull(gray(code(lines("for (var i = 0; i < <caret>)"), "List<Order> orders, List<int> ids")))
        assertNull(gray(code(lines("for (var i = 0; i < <caret>)"), "IEnumerable<Order> orders")))
        assertNull(gray(code(lines("for (var i = 0; j < <caret>)"), "List<Order> orders")))
    }

    // ---- 4. the parameter of nameof

    fun testNameofTheOneParameter() {
        val ghost = ghost(code(lines("throw new ArgumentNullException(nameof(<caret>));"), "Order order"))
        assertEquals("order", ghost?.text)
        assertEquals("nameof", ghost?.rule)
        assertEquals("order)", gray(code(lines("var n = nameof(<caret>"), "Order order")))
        assertEquals("der)", gray(code(lines("var n = nameof(or<caret>"), "Order order")))
    }

    fun testNameofTheParameterTheIfTests() {
        val body = lines("if (order == null)", "{", "    throw new ArgumentNullException(nameof(<caret>));", "}")
        assertEquals("order", gray(code(body, "Order order, int count")))
        assertEquals("count", gray(code(lines("if (count is < 0) throw new ArgumentOutOfRangeException(nameof(<caret>));"), "Order order, int count")))
        // several parameters and no `if` naming one of them: silent; none at all: silent
        assertNull(gray(code(lines("var n = nameof(<caret>);"), "Order order, int count")))
        assertNull(gray(code(lines("if (order == null && count < 0) throw new ArgumentException(nameof(<caret>));"), "Order order, int count")))
        assertNull(gray(code(lines("var n = nameof(<caret>);"))))
    }

    // ---- 5. the string of string.IsNullOrEmpty

    fun testTheOneStringInScope() {
        val ghost = ghost(code(lines("if (string.IsNullOrEmpty(<caret>))"), "string name, int count"))
        assertEquals("name", ghost?.text)
        assertEquals("string argument", ghost?.rule)
        assertEquals("name)", gray(code(lines("var empty = string.IsNullOrWhiteSpace(<caret>"), "string name")))
        assertEquals("me)", gray(code(lines("var empty = String.IsNullOrEmpty(na<caret>"), "string name")))
        assertEquals("title)", gray(code(lines("var title = order.Id.ToString();", "if (string.IsNullOrEmpty(<caret>"), "Order order")))
        // two strings, or none: silent
        assertNull(gray(code(lines("if (string.IsNullOrEmpty(<caret>))"), "string name, string email")))
        assertNull(gray(code(lines("if (string.IsNullOrEmpty(<caret>))"), "Order order")))
    }

    companion object {
        private var counter = 0

        private fun bytes(name: String): ByteArray? = AnalogyGhostTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
