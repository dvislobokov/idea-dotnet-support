package io.github.dotnetsupport.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * The expression a debugger evaluates when the mouse rests on code: the identifier under the pointer with the member accesses
 * to the left of it (`person.Friend.Name` on `Name`, `this.count`, `a?.b`). By tokens, there is no parser. Deliberately modest:
 * nothing that would run code of the program (a name followed by `(`), nothing in strings and comments, no keywords but `this`.
 */
object CSharpHoverExpression {
    private class Token(val type: IElementType, val start: Int, val end: Int, val text: CharSequence)

    fun rangeAt(text: CharSequence, offset: Int): TextRange? {
        val tokens = ArrayList<Token>()
        val lexer = CSharpLexer()
        lexer.start(text)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS) tokens += Token(type, lexer.tokenStart, lexer.tokenEnd, text.subSequence(lexer.tokenStart, lexer.tokenEnd))
            if (lexer.tokenStart > offset) break // one token past the pointer is enough to see a call
            lexer.advance()
        }
        val index = tokens.indexOfFirst { offset >= it.start && offset < it.end }
        if (index < 0 || !isName(tokens[index])) return null
        if (tokens.getOrNull(index + 1)?.type == CSharpTokenTypes.LPAREN) return null // a call: evaluating it may change the program

        var first = index
        while (true) {
            val name = nameBeforeAccess(tokens, first)
            if (name < 0) break
            first = name
        }
        // `Foo().Bar`, `items[0].Name`: what is to the left is not a plain name, the chain would be evaluated out of its context
        if (first >= 1 && tokens[first - 1].type == CSharpTokenTypes.DOT) return null
        return TextRange(tokens[first].start, tokens[index].end)
    }

    /** The index of the name that [index] is a member of: `name.` or `name?.` right before it (the lexer gives `?` and `.` apart); -1 if none. */
    private fun nameBeforeAccess(tokens: List<Token>, index: Int): Int {
        if (index < 2 || tokens[index - 1].type != CSharpTokenTypes.DOT) return -1
        val dot = tokens[index - 1]
        val before = tokens[index - 2]
        val conditional = before.type == CSharpTokenTypes.OPERATOR && before.text.toString() == "?" && before.end == dot.start
        val name = if (conditional) index - 3 else index - 2
        return if (name >= 0 && isName(tokens[name])) name else -1
    }

    private fun isName(token: Token) =
        token.type == CSharpTokenTypes.IDENTIFIER || (token.type == CSharpTokenTypes.KEYWORD && token.text.toString() == "this")
}
