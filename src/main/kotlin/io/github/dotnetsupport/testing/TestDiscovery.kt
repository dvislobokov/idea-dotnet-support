package io.github.dotnetsupport.testing

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.lang.CSharpLexer
import io.github.dotnetsupport.lang.CSharpTokenTypes

/** A test class ([methodName] is null) or a test method, with the range of its name in the file. */
class TestTarget(val className: String, val methodName: String?, val nameRange: TextRange) {
    val displayName: String get() = className.substringAfterLast('.').replace('+', '.') + methodName?.let { ".$it" }.orEmpty()

    /**
     * "Contains" rather than "equals": the fully qualified name of a parameterized NUnit test includes its arguments.
     * The trailing dot of a class filter keeps `OrderTests` from matching `OrderTestsBase`.
     */
    val filter: String get() = "FullyQualifiedName~" + escape(if (methodName == null) "$className." else "$className.$methodName")

    companion object {
        private val SPECIAL = Regex("""[\\()&|=!~]""")
        fun escape(value: String): String = SPECIAL.replace(value) { "\\" + it.value }
    }
}

/**
 * Finds xUnit, NUnit and MSTest tests by tokens: a method directly inside a type, preceded by a test attribute.
 * There is no parser, so inherited tests and custom attributes derived from the known ones are not seen.
 */
object TestDiscovery {
    private val ATTRIBUTES = listOf("Fact", "Theory", "Test", "TestCase", "TestCaseSource", "TestMethod", "DataTestMethod")
        .flatMap { listOf(it, it + "Attribute") }.toSet()
    private val TYPE_KEYWORDS = setOf("class", "struct", "record")

    private class Token(val type: IElementType, val text: String, val start: Int, val end: Int)
    private class Scope(val name: String, val isType: Boolean, val depth: Int, val nameRange: TextRange?)

    /** Cheap check before tokenizing a file that obviously has no tests. */
    fun mayContainTests(text: CharSequence): Boolean = ATTRIBUTES.any { text.contains("[$it") || text.contains(", $it") || text.contains(".$it") }

    fun targetAt(text: CharSequence, offset: Int): TestTarget? = targets(text).find { it.nameRange.containsOffset(offset) }

    fun find(text: CharSequence, className: String, methodName: String?): TestTarget? =
        targets(text).find { it.className == className && it.methodName == methodName }

    fun targets(text: CharSequence): List<TestTarget> {
        if (!mayContainTests(text)) return emptyList()
        val tokens = tokenize(text)
        val result = ArrayList<TestTarget>()
        val scopes = ArrayList<Scope>()
        val classNameRanges = LinkedHashMap<String, TextRange>()
        var fileNamespace = ""
        var depth = 0
        var parentheses = 0
        var pendingScope: Scope? = null   // `class Foo` / `namespace A.B` seen, waiting for its `{`
        var testAttributeSeen = false

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val inTypeBody = scopes.lastOrNull()?.let { it.isType && depth == it.depth + 1 } == true
            when {
                token.type == CSharpTokenTypes.LPAREN -> parentheses++
                token.type == CSharpTokenTypes.RPAREN -> parentheses--
                token.type == CSharpTokenTypes.LBRACE -> {
                    pendingScope?.let { scope ->
                        scopes += scope
                        if (scope.isType) classNameRanges[qualifiedName(fileNamespace, scopes)] = scope.nameRange!!
                    }
                    pendingScope = null
                    testAttributeSeen = false
                    depth++
                }
                token.type == CSharpTokenTypes.RBRACE -> {
                    depth--
                    while (scopes.isNotEmpty() && scopes.last().depth >= depth) scopes.removeAt(scopes.lastIndex)
                    testAttributeSeen = false
                }
                token.type == CSharpTokenTypes.SEMICOLON && parentheses == 0 -> {
                    // `namespace A.B;` applies to the rest of the file; `record Foo(int X);` has no body
                    pendingScope?.takeIf { !it.isType }?.let { fileNamespace = it.name }
                    pendingScope = null
                    testAttributeSeen = false
                }
                token.type == CSharpTokenTypes.KEYWORD && token.text == "namespace" -> {
                    val name = StringBuilder()
                    while (tokens.getOrNull(i + 1)?.let { it.type == CSharpTokenTypes.IDENTIFIER || it.type == CSharpTokenTypes.DOT } == true) name.append(tokens[++i].text)
                    pendingScope = Scope(name.toString(), isType = false, depth = depth, nameRange = null)
                }
                token.type == CSharpTokenTypes.KEYWORD && token.text in TYPE_KEYWORDS && tokens.getOrNull(i + 1)?.type == CSharpTokenTypes.IDENTIFIER &&
                    tokens.getOrNull(i - 1)?.let { it.type == CSharpTokenTypes.OPERATOR && it.text == ":" } != true -> {
                    val name = tokens[++i]
                    pendingScope = Scope(name.text, isType = true, depth = depth, nameRange = TextRange(name.start, name.end))
                }
                // attributes of a member: [Fact], [Theory, Trait("a", "b")], [Xunit.Fact]
                inTypeBody && pendingScope == null && parentheses == 0 && token.type == CSharpTokenTypes.LBRACKET -> {
                    var nesting = 0
                    while (i < tokens.size) {
                        val inner = tokens[i]
                        if (inner.type == CSharpTokenTypes.LBRACKET || inner.type == CSharpTokenTypes.LPAREN) nesting++
                        if (inner.type == CSharpTokenTypes.RBRACKET || inner.type == CSharpTokenTypes.RPAREN) nesting--
                        if (nesting == 1 && inner.type == CSharpTokenTypes.IDENTIFIER && inner.text in ATTRIBUTES) testAttributeSeen = true
                        if (nesting == 0) break
                        i++
                    }
                }
                inTypeBody && testAttributeSeen && parentheses == 0 && token.type == CSharpTokenTypes.IDENTIFIER &&
                    tokens.getOrNull(i + 1)?.let { it.type == CSharpTokenTypes.LPAREN || (it.type == CSharpTokenTypes.OPERATOR && it.text == "<") } == true -> {
                    result += TestTarget(qualifiedName(fileNamespace, scopes), token.text, TextRange(token.start, token.end))
                    testAttributeSeen = false
                }
            }
            i++
        }

        // a class is a target when it has test methods of its own
        val classes = result.map { it.className }.distinct()
        return result + classes.mapNotNull { name -> classNameRanges[name]?.let { TestTarget(name, null, it) } }
    }

    private fun qualifiedName(fileNamespace: String, scopes: List<Scope>): String {
        val namespaces = (listOf(fileNamespace) + scopes.filter { !it.isType }.map { it.name }).filter { it.isNotEmpty() }
        val types = scopes.filter { it.isType }.joinToString("+") { it.name }
        return (namespaces + types).joinToString(".")
    }

    private fun tokenize(text: CharSequence): List<Token> {
        val tokens = ArrayList<Token>()
        val lexer = CSharpLexer()
        lexer.start(text)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS && type != CSharpTokenTypes.PREPROCESSOR) {
                tokens += Token(type, lexer.tokenText, lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        return tokens
    }
}
