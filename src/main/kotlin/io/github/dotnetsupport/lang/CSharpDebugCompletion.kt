package io.github.dotnetsupport.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * What is being completed in an expression typed for a debugger (Evaluate, a watch, a condition of a breakpoint). There is no parser and
 * no compiler behind it: the names come from the stopped program, so all that is needed is where the caret is - at a new name
 * (locals, parameters, members of `this`) or after `value.` (members of that value).
 */
object CSharpDebugCompletion {
    /** [qualifier] is the expression before the dot, null at a name that stands alone; [prefix] is what is typed of the name so far. */
    class Context(val qualifier: String?, val prefix: String)

    private class Token(val type: IElementType, val start: Int, val end: Int)

    /**
     * Null where names are not completed: in strings, comments and numbers, and after a dot whose left side is not a plain chain of
     * names (`Make().`, `items[0].`): finding the members would mean evaluating it, i.e. running code of the program while typing.
     */
    fun contextAt(text: CharSequence, offset: Int): Context? {
        val tokens = ArrayList<Token>()
        val lexer = CSharpLexer()
        lexer.start(text)
        while (true) {
            val type = lexer.tokenType ?: break
            if (lexer.tokenStart >= offset) break
            // the caret inside or at the end of a literal or a comment
            if ((type in CSharpTokenTypes.COMMENTS || type in CSharpTokenTypes.STRINGS) && offset <= lexer.tokenEnd) return null
            if (type != TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS) tokens += Token(type, lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        val last = tokens.lastOrNull() ?: return Context(null, "")
        if (last.type == CSharpTokenTypes.NUMBER && last.end >= offset) return null

        val typing = last.end >= offset && (last.type == CSharpTokenTypes.IDENTIFIER || last.type == CSharpTokenTypes.KEYWORD)
        val prefix = if (typing) text.subSequence(last.start, offset).toString() else ""
        val before = if (typing) tokens.size - 2 else tokens.size - 1
        if (before < 0 || tokens[before].type != CSharpTokenTypes.DOT) return Context(null, prefix)

        // `name.` or `name?.`: the chain that ends with that name, found the way a hover finds it
        val dot = tokens[before]
        var name = before - 1
        if (name >= 0 && tokens[name].type == CSharpTokenTypes.OPERATOR && text.subSequence(tokens[name].start, tokens[name].end).toString() == "?" && tokens[name].end == dot.start) name--
        if (name < 0) return null
        // the hover would not take a name followed by `(`; here the name is followed by the dot, so only the chain to the left matters
        val range = CSharpHoverExpression.rangeAt(text.subSequence(0, tokens[name].end), tokens[name].start) ?: return null
        if (range.endOffset != tokens[name].end) return null
        return Context(text.subSequence(range.startOffset, range.endOffset).toString(), prefix)
    }

    /** A debug adapter lists more than members under a value: `[0]`, `Raw View`, `Static members`. Only what can be typed is offered. */
    fun isName(name: String): Boolean = name.isNotEmpty() && (name[0].isLetter() || name[0] == '_' || name[0] == '@') && name.drop(1).all { it.isLetterOrDigit() || it == '_' }
}
