package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.csharp.lang.lexer.CSharpLiteralScanner

/** `ParserHelpers`: the void elements of HTML and the identifier checks of the directive parser. */
object ParserHelpers {
    val VOID_ELEMENTS: Set<String> = setOf("area", "base", "br", "col", "command", "embed", "hr", "img", "input", "keygen", "link", "meta", "param", "source", "track", "wbr")

    fun isVoidElement(name: String): Boolean = name.lowercase() in VOID_ELEMENTS

    fun isNewLine(value: String): Boolean = (value.length == 1 && CSharpLiteralScanner.isNewLine(value[0])) || value == "\r\n"

    /** `ParserHelpers.IsIdentifier` (`SyntaxFacts.IsValidIdentifier` when [requireIdentifierStart]). */
    fun isIdentifier(value: String, requireIdentifierStart: Boolean): Boolean {
        if (value.isEmpty()) return false
        if (requireIdentifierStart) {
            if (!CSharpLiteralScanner.isIdentifierStartCharacter(value[0])) return false
            for (i in 1 until value.length) if (!CSharpLiteralScanner.isIdentifierPartCharacter(value[i])) return false
            return true
        }
        return value.all { CSharpLiteralScanner.isIdentifierPartCharacter(it) }
    }
}
