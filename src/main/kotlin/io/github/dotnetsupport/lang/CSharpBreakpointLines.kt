package io.github.dotnetsupport.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Lines of a C# file a breakpoint makes sense on: the ones with code that runs. There is no parser, so it is an estimate by
 * tokens and [CSharpDeclarations]: inside a body of a member, on an expression body or an initializer, on a top-level statement;
 * not on comments, directives, `using`s, attributes and the headers of types and members. The debugger has the last word anyway:
 * a breakpoint it cannot bind stays unverified.
 */
object CSharpBreakpointLines {
    /** Zero-based numbers of the lines of [text] that can have a breakpoint. */
    fun find(text: CharSequence): Set<Int> {
        val structure = CSharpDeclarations.scan(text)
        val result = HashSet<Int>()
        val lexer = CSharpLexer()
        lexer.start(text)
        var line = 0
        var position = 0
        var seenLine = -1 // only the first code token of a line decides
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            while (position < start) if (text[position++] == '\n') line++
            if (line != seenLine && isCode(type)) {
                seenLine = line
                val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                if (isBreakable(structure, type, text.subSequence(start, lexer.tokenEnd), start, lineEnd, text)) result += line
            }
            lexer.advance()
        }
        return result
    }

    private fun isCode(type: IElementType) = type != TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS && type != CSharpTokenTypes.PREPROCESSOR

    private fun isBreakable(structure: CSharpFileStructure, type: IElementType, token: CharSequence, offset: Int, lineEnd: Int, text: CharSequence): Boolean {
        if (structure.usings?.containsOffset(offset) == true) return false
        val path = structure.pathTo(offset)
        // Top-level statements; the scanner may take one for a member of nothing (`var x = 1;` looks like a field).
        if (path.none { it.kind.isType }) {
            if (path.any { it.kind == DeclarationKind.NAMESPACE }) return false
            return type != CSharpTokenTypes.LBRACKET && !(type == CSharpTokenTypes.KEYWORD && token.toString() == "namespace")
        }
        val member = path.last()
        if (member.kind.isType || member.kind == DeclarationKind.NAMESPACE || member.kind == DeclarationKind.ENUM_MEMBER) return false
        val body = member.body
        // the line has a part of the body: `void Run() {` and a one-line member count, a header with the brace on the next line does not
        if (body != null) return lineEnd > body.startOffset && offset < body.endOffset
        // `=> expression;` and `= initializer;`: from the line of the name on, the attributes above are not code
        return lineEnd >= member.nameRange.endOffset && text.subSequence(member.nameRange.endOffset, member.range.endOffset).contains('=')
    }
}
