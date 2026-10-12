package io.github.dotnetsupport

import io.github.dotnetsupport.roslyn.LambdaSuggestions
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A lambda for the delegate parameter at the caret: which delegates, which names. */
class LambdaSuggestionsTest {
    private fun head(declared: String) = LambdaSuggestions.forParameter(declared)?.head

    @Test
    fun `delegate types become lambdas with names from the types`() {
        assertEquals("serviceProvider => ", head("Func<IServiceProvider, object> implementationFactory"))
        assertEquals("(serviceProvider, order) => ", head("Func<IServiceProvider, Order, object> factory"))
        assertEquals("() => ", head("Action callback"))
        assertEquals("(i, s) => ", head("Action<int, string> handler"))
        assertEquals("order => ", head("Predicate<Order> match"))
        assertEquals("(sender, e) => ", head("EventHandler<RoutedEventArgs> handler"))
        assertEquals("(sender, e) => ", head("EventHandler handler"))
        assertEquals("(x, y) => ", head("Comparison<Order> comparison"))
        assertEquals("s => ", head("Converter<string, int> converter"))
        assertEquals("service => ", head("Func<TService, bool> filter"))
        assertEquals("orders => ", head("Func<List<Order>, int> count"))
        assertEquals("orders => ", head("Action<Order[]> handle"))
        assertEquals("(i, i2) => ", head("Func<int, int, int> add"))
        assertEquals("x => ", head("Expression<Func<T, bool>> predicate"))
        assertEquals("order => ", head("Func<Order, bool>? maybe"))
        // the names come from the types alone: Rider knows `(context, next)`, the plugin does not
        assertEquals("(httpContext, requestDelegate) => ", head("Func<HttpContext, RequestDelegate, Task> middleware"))
        assertEquals("() => ", head("params Action[] actions".replace("[]", "")))
        assertNull(head("string name"))
        assertNull(head("int count"))
        assertNull(head("Task<Order> order"))
        assertNull(head("CustomHandler handler"))
    }

    @Test
    fun `the active overload comes first and repeats are dropped`() {
        fun signature(label: String) = SignatureInformation(label)
        val help = SignatureHelp(listOf(
            signature("(extension) IServiceCollection IServiceCollection.AddSingleton(Type serviceType, object implementationInstance)"),
            signature("(extension) IServiceCollection IServiceCollection.AddSingleton(Type serviceType, Func<IServiceProvider, object> implementationFactory)"),
            signature("(extension) IServiceCollection IServiceCollection.AddSingleton<TService>(Func<IServiceProvider, TService> implementationFactory)"),
            signature("(extension) IServiceCollection IServiceCollection.AddSingleton<TService, TImplementation>(Func<IServiceProvider, TImplementation> implementationFactory)"),
        ), 2, 0)
        assertEquals(listOf("serviceProvider => "), LambdaSuggestions.forHelp(help).map { it.head })

        val second = SignatureHelp(help.signatures, 0, 1)
        assertEquals(listOf("serviceProvider => "), LambdaSuggestions.forHelp(second).map { it.head })
        // the first parameter: a delegate in the third overload only, still offered
        val first = SignatureHelp(help.signatures, 0, 0)
        assertEquals(listOf("serviceProvider => "), LambdaSuggestions.forHelp(first).map { it.head })
        val none = SignatureHelp(listOf(signature("void M(string name, int count)")), 0, 1)
        assertEquals(emptyList<String>(), LambdaSuggestions.forHelp(none).map { it.head })
    }

    @Test
    fun `only where an argument begins`() {
        assertTrue(LambdaSuggestions.atArgumentStart("Add(", 4))
        assertTrue(LambdaSuggestions.atArgumentStart("Add(a, ", 7))
        assertTrue(LambdaSuggestions.atArgumentStart("Add(\n    ", 9))
        assertFalse(LambdaSuggestions.atArgumentStart("Add(a", 5))
        assertFalse(LambdaSuggestions.atArgumentStart("var x = ", 8))
        assertEquals(listOf("Func<IServiceProvider, object>", "int"), LambdaSuggestions.splitGenericArguments("Func<IServiceProvider, object>, int"))
    }
}
