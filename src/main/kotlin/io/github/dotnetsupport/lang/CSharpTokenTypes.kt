package io.github.dotnetsupport.lang

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class CSharpTokenType(debugName: String) : IElementType(debugName, CSharpLanguage) {
    override fun toString(): String = "CSharp:" + super.toString()
}

object CSharpTokenTypes {
    @JvmField val LINE_COMMENT = CSharpTokenType("LINE_COMMENT")
    @JvmField val DOC_COMMENT = CSharpTokenType("DOC_COMMENT")
    @JvmField val BLOCK_COMMENT = CSharpTokenType("BLOCK_COMMENT")
    @JvmField val PREPROCESSOR = CSharpTokenType("PREPROCESSOR")

    @JvmField val STRING = CSharpTokenType("STRING")
    @JvmField val CHAR = CSharpTokenType("CHAR")
    @JvmField val NUMBER = CSharpTokenType("NUMBER")
    @JvmField val KEYWORD = CSharpTokenType("KEYWORD")
    @JvmField val IDENTIFIER = CSharpTokenType("IDENTIFIER")

    @JvmField val LBRACE = CSharpTokenType("LBRACE")
    @JvmField val RBRACE = CSharpTokenType("RBRACE")
    @JvmField val LPAREN = CSharpTokenType("LPAREN")
    @JvmField val RPAREN = CSharpTokenType("RPAREN")
    @JvmField val LBRACKET = CSharpTokenType("LBRACKET")
    @JvmField val RBRACKET = CSharpTokenType("RBRACKET")
    @JvmField val SEMICOLON = CSharpTokenType("SEMICOLON")
    @JvmField val COMMA = CSharpTokenType("COMMA")
    @JvmField val DOT = CSharpTokenType("DOT")
    @JvmField val OPERATOR = CSharpTokenType("OPERATOR")

    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, DOC_COMMENT, BLOCK_COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING, CHAR)

    /** Reserved keywords plus the contextual ones that are rarely used as plain identifiers. */
    @JvmField val KEYWORDS: Set<String> = setOf(
        "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class", "const",
        "continue", "decimal", "default", "delegate", "do", "double", "else", "enum", "event", "explicit", "extern",
        "false", "finally", "fixed", "float", "for", "foreach", "goto", "if", "implicit", "in", "int", "interface",
        "internal", "is", "lock", "long", "namespace", "new", "null", "object", "operator", "out", "override",
        "params", "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed", "short",
        "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true", "try", "typeof",
        "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual", "void", "volatile", "while",
        // contextual
        "var", "async", "await", "partial", "record", "where", "yield", "nameof", "when", "init", "required",
        "get", "set", "global", "dynamic", "with", "and", "or", "not",
    )
}
