package io.github.dotnetsupport

import io.github.dotnetsupport.lang.CSharpIdioms
import junit.framework.TestCase

class CSharpIdiomsTest : TestCase() {
    /** The grey text [CSharpIdioms] offers at the `<caret>` of [code], or null; trailing/leading whitespace of the answer kept. */
    private fun suggest(code: String): String? {
        val offset = code.indexOf("<caret>")
        check(offset >= 0) { "no <caret> in the code" }
        return CSharpIdioms.suggest(code.replace("<caret>", ""), offset)
    }

    private fun body(code: String): String? = suggest(code)?.trim()

    fun testNullGuardOnParameter() {
        assertEquals("throw new ArgumentNullException(nameof(name));", body("""
            class C {
                void M(string name) {
                    if (name == null) {
            <caret>
                    }
                }
            }""".trimIndent()))
    }

    fun testAllmanBraces() {
        assertEquals("throw new ArgumentNullException(nameof(name));", body("""
            class C
            {
                void M(string name)
                {
                    if (name is null)
                    {
            <caret>
                    }
                }
            }""".trimIndent()))
    }

    fun testLocalIsNotGuarded() {
        assertNull(body("""
            class C {
                void M() {
                    string local = Get();
                    if (local == null) {
            <caret>
                    }
                }
            }""".trimIndent()))
    }

    fun testEmptyAndWhitespace() {
        assertEquals("throw new ArgumentException(\"Value cannot be null or empty.\", nameof(id));", body("""
            class C {
                void M(string id) {
                    if (string.IsNullOrEmpty(id)) {
            <caret>
                    }
                }
            }""".trimIndent()))
        assertEquals("throw new ArgumentException(\"Value cannot be null or whitespace.\", nameof(id));", body("""
            class C {
                void M(string id) {
                    if (IsNullOrWhiteSpace(id)) {
            <caret>
                    }
                }
            }""".trimIndent()))
    }

    fun testRangeGuard() {
        assertEquals("throw new ArgumentOutOfRangeException(nameof(count));", body("""
            class C {
                void M(int count) {
                    if (count < 0) {
            <caret>
                    }
                }
            }""".trimIndent()))
    }

    fun testObjectDisposed() {
        assertEquals("throw new ObjectDisposedException(nameof(Res));", body("""
            class Res {
                private bool _disposed;
                public void Use() {
                    if (_disposed) {
            <caret>
                    }
                }
            }""".trimIndent()))
    }

    fun testConstructorAssignments() {
        val suggestion = suggest("""
            class Server {
                private int port;
                private string host;
                public Server(int port, string host) {
            <caret>
                }
            }""".trimIndent())!!.trim()
        assertTrue(suggestion, suggestion.startsWith("this.port = port;"))
        assertTrue(suggestion, suggestion.contains("this.host = host;"))
    }

    fun testConstructorAssignmentToBackingField() {
        assertEquals("_port = port;", body("""
            class Server {
                private int _port;
                public Server(int port) {
            <caret>
                }
            }""".trimIndent()))
    }

    fun testNoMatchingMemberNoAssignment() {
        assertNull(body("""
            class Server {
                public Server(int port) {
            <caret>
                }
            }""".trimIndent()))
    }

    fun testTypedPrefixIsSubtracted() {
        assertEquals("row new ArgumentNullException(nameof(name));", suggest("""
            class C {
                void M(string name) {
                    if (name == null) {
                        th<caret>
                    }
                }
            }""".trimIndent()))
    }

    fun testNotSuggestedTwice() {
        assertNull(body("""
            class C {
                void M(string name) {
                    if (name == null) {
            <caret>
                        throw new ArgumentNullException(nameof(name));
                    }
                }
            }""".trimIndent()))
    }
}
