package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.codeInsight.lookup.Lookup
import io.github.dotnetsupport.roslyn.RoslynCompletionPolicy
import io.github.dotnetsupport.roslyn.RoslynSignatureTail
import org.eclipse.lsp4j.CompletionItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A generic method or type chosen in completion gets its `<>`, as Rider completes it. The items are the ones the server has sent
 * (tools/roslyn-lsp/capture_generics.py): the label `AddSingleton<>`, the bare name inserted, the signature in the documentation.
 */
class GenericCompletionTest {
    private class Row(val label: String, val kind: Int, val inserted: String?, val documentation: String) {
        val name: String get() = label.removeSuffix("<>")
    }

    private val rows: List<Row> = javaClass.getResourceAsStream("/roslyn/capture-5.12-generics/30-summary_of_generic_items.json")!!.use { stream ->
        JsonParser.parseString(stream.readBytes().toString(Charsets.UTF_8)).asJsonObject.getAsJsonArray("result").map { it.asJsonObject }.map { row ->
            Row(row["label"].asString, row["kind"].asInt, row["textEditText"]?.takeIf { !it.isJsonNull }?.asString, row["documentation"].asString)
        }
    }

    private fun row(label: String) = rows.single { it.label == label }

    @Test
    fun `the server marks a generic with its label and inserts the bare name`() {
        for (label in listOf("AddSingleton<>", "GetRequiredService<>", "Select<>", "OfType<>", "Empty<>", "List<>", "Dictionary<>", "Task<>")) {
            val row = row(label)
            assertTrue(label, RoslynCompletionPolicy.isGeneric(row.label))
            assertEquals(label, row.name, row.inserted)
        }
        // the generic and the plain one of a name are two rows
        assertFalse(RoslynCompletionPolicy.isGeneric(row("AddSingleton").label))
        assertFalse(RoslynCompletionPolicy.isGeneric(row("Task").label))
        assertFalse(RoslynCompletionPolicy.isGeneric("<>"))
        assertFalse(RoslynCompletionPolicy.isGeneric(null))
        assertTrue(RoslynCompletionPolicy.isGenericType(CompletionItemKind.forValue(row("List<>").kind)))
        assertFalse(RoslynCompletionPolicy.isGenericType(CompletionItemKind.forValue(row("Select<>").kind)))
    }

    @Test
    fun `type arguments are written when nothing infers them`() {
        val expected = mapOf(
            "AddSingleton<>" to true,          // AddSingleton<TService>()
            "GetRequiredService<>" to true,    // GetRequiredService<T>()
            "OfType<>" to true,                // IEnumerable.OfType<TResult>(): the receiver has no TResult
            "Empty<>" to true,                 // Array.Empty<T>()
            "Make<>" to true,                  // Make<T>()
            "Convert<>" to true,               // Convert<TSource, TResult>(TSource value): TResult is in no parameter
            "Register<>" to true,              // Register<TService>()
            "Select<>" to false,               // IEnumerable<int>.Select<int, TResult>(Func<int, int, TResult> selector)
            "Same<>" to false,                 // Same<T>(T value)
        )
        for ((label, needed) in expected) {
            val row = row(label)
            assertEquals(label + ": " + RoslynSignatureTail.signature(row.documentation), needed, RoslynCompletionPolicy.needsTypeArguments(row.documentation, row.name))
        }
        fun needs(signature: String, name: String) = RoslynCompletionPolicy.needsTypeArguments("```csharp\n$signature\n```", name)
        assertFalse("inferred from the receiver of an extension", needs("(extension) int IEnumerable<int>.First<int>()", "First"))
        assertFalse(needs("(extension) List<TSource> IEnumerable<TSource>.ToList<TSource>()", "ToList"))
        assertTrue("the type of the class infers nothing", needs("TOutput Holder<T>.Get<TOutput>()", "Get"))
        assertFalse(needs("List<TOutput> List<int>.ConvertAll<TOutput>(Converter<int, TOutput> converter)", "ConvertAll"))
        assertTrue("T is not TKey", needs("void Cache.Put<T>(TKey key)", "Put"))
        assertFalse("not generic", needs("void Console.WriteLine()", "WriteLine"))
        assertFalse("no signature", RoslynCompletionPolicy.needsTypeArguments("just words", "Make"))
    }

    @Test
    fun `what is inserted after the name`() {
        fun call(label: String, rest: String = ""): String {
            val row = row(label)
            val tail = RoslynSignatureTail.parse(row.documentation, row.name)
            assertNotNull(label, tail)
            val call = RoslynCompletionPolicy.call(tail!!.type, tail.tail, rest, RoslynCompletionPolicy.needsTypeArguments(row.documentation, row.name))
            return call.text.substring(0, call.caret) + "|" + call.text.substring(call.caret)
        }
        assertEquals("<|>()", call("AddSingleton<>"))
        assertEquals("<|>()", call("GetRequiredService<>"))
        assertEquals("<|>()", call("OfType<>"))
        assertEquals("a void one is a statement", "<|>();", call("Register<>"))
        assertEquals("<|>()", call("Register<>", ");"))
        assertEquals("(|)", call("Select<>"))
        assertEquals("(|)", call("Same<>"))
        assertEquals("(|)", call("AddSingleton"))
    }

    @Test
    fun `the row of a generic method has its signature`() {
        val register = RoslynSignatureTail.parse(row("Register<>").documentation, "Register")!!
        assertEquals("void", register.type)
        assertEquals("<TService>()", register.tail)
        val addSingleton = RoslynSignatureTail.parse(row("AddSingleton<>").documentation, "AddSingleton")!!
        assertEquals("IServiceCollection", addSingleton.type)
        assertEquals("the server says `+ 4 generic overloads`, with no-break spaces", "<TService>()  +4 overloads", addSingleton.tail)
        assertEquals("(Type serviceType)  +3 overloads", RoslynSignatureTail.parse(row("AddSingleton").documentation, "AddSingleton")!!.tail)
    }

    @Test
    fun `a generic type gets its brackets when chosen by Enter or Tab`() {
        val policy = RoslynCompletionPolicy
        val text = "var list = new List"
        val start = text.indexOf("List")
        assertTrue(policy.addsTypeArguments(Lookup.NORMAL_SELECT_CHAR, text, start, text.length))
        assertTrue(policy.addsTypeArguments(Lookup.REPLACE_SELECT_CHAR, text, start, text.length))
        assertFalse("typed <: the hand goes on", policy.addsTypeArguments('<', text, start, text.length))
        assertFalse("typed .", policy.addsTypeArguments('.', text, start, text.length))
        val replaced = "List<int> x"
        assertFalse("the brackets are there", policy.addsTypeArguments(Lookup.REPLACE_SELECT_CHAR, replaced, 0, 4))
        val documented = "    /// see List"
        assertFalse("a documentation comment writes List{T}", policy.addsTypeArguments(Lookup.NORMAL_SELECT_CHAR, documented, documented.indexOf("List"), documented.length))
    }
}
