package io.github.dotnetsupport.templates

import com.intellij.psi.TokenType
import io.github.dotnetsupport.lang.CSharpLexer
import io.github.dotnetsupport.lang.CSharpTokenTypes

/** The first type declared in a C# file, found by tokens: there is no parser. */
class TypeDeclaration(
    /** `class`, `struct`, `interface`, `record`, `record struct`, `record class`. */
    val kind: String,
    val name: String,
    /** `<T, U>` or empty. */
    val typeParameters: String,
    /** `public`, `internal`, ... or null when omitted. */
    val accessModifier: String?,
    val isPartial: Boolean,
    /** Offset of the kind keyword, where `partial` has to be inserted. */
    val kindOffset: Int,
    /** Namespace the type is declared in; null for the global one. */
    val namespace: String?,
)

object TypeDeclarationScanner {
    private val KINDS = setOf("class", "struct", "interface", "record")
    private val ACCESS = setOf("public", "internal", "private", "protected")
    private val MODIFIERS = ACCESS + setOf("static", "abstract", "sealed", "partial", "readonly", "ref", "unsafe", "new", "file")

    private class Token(val type: Any?, val text: String, val offset: Int)

    fun firstType(text: CharSequence): TypeDeclaration? {
        val tokens = ArrayList<Token>()
        val lexer = CSharpLexer()
        lexer.start(text)
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            if (type != TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS && type != CSharpTokenTypes.PREPROCESSOR) {
                tokens += Token(type, lexer.tokenText, lexer.tokenStart)
            }
            lexer.advance()
        }

        var namespace: String? = null
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.type == CSharpTokenTypes.KEYWORD && token.text == "namespace" && namespace == null) {
                val parts = StringBuilder()
                var j = i + 1
                while (j < tokens.size && (tokens[j].type == CSharpTokenTypes.IDENTIFIER || tokens[j].type == CSharpTokenTypes.DOT)) parts.append(tokens[j++].text)
                namespace = parts.toString().ifEmpty { null }
            }
            // `where T : class` and `new()` constraints are not declarations: a declaration is followed by a name
            if (token.type == CSharpTokenTypes.KEYWORD && token.text in KINDS && isDeclarationStart(tokens, i)) {
                return declaration(tokens, i, namespace)
            }
            i++
        }
        return null
    }

    private fun isDeclarationStart(tokens: List<Token>, index: Int): Boolean {
        val previous = tokens.getOrNull(index - 1)
        if (previous != null && previous.type == CSharpTokenTypes.OPERATOR && previous.text == ":") return false
        if (previous != null && previous.type == CSharpTokenTypes.KEYWORD && previous.text == "record") return false // handled with `record`
        var next = index + 1
        if (tokens[index].text == "record" && tokens.getOrNull(next)?.text.let { it == "struct" || it == "class" }) next++
        return tokens.getOrNull(next)?.type == CSharpTokenTypes.IDENTIFIER
    }

    private fun declaration(tokens: List<Token>, index: Int, namespace: String?): TypeDeclaration {
        var kind = tokens[index].text
        var nameIndex = index + 1
        if (kind == "record" && tokens[nameIndex].text.let { it == "struct" || it == "class" }) kind += " " + tokens[nameIndex++].text

        val modifiers = ArrayList<String>()
        var j = index - 1
        while (j >= 0 && tokens[j].type == CSharpTokenTypes.KEYWORD && tokens[j].text in MODIFIERS) modifiers += tokens[j--].text

        val typeParameters = StringBuilder()
        var k = nameIndex + 1
        if (tokens.getOrNull(k)?.text == "<") {
            var depth = 0
            while (k < tokens.size) {
                val text = tokens[k].text
                typeParameters.append(if (text == ",") ", " else if (tokens[k].type == CSharpTokenTypes.KEYWORD) "$text " else text) // `in T`, `out T`
                if (text == "<") depth++
                if (text == ">" && --depth == 0) break
                k++
            }
        }
        return TypeDeclaration(
            kind, tokens[nameIndex].text, typeParameters.toString(),
            accessModifier = modifiers.reversed().filter { it in ACCESS }.joinToString(" ").ifEmpty { null },
            isPartial = "partial" in modifiers,
            kindOffset = tokens[index].offset,
            namespace = namespace,
        )
    }
}
