package io.github.dotnetsupport.lang

/**
 * Where the value of a variable is shown in the editor while the program stands at a line, as in Rider: on the lines of the current
 * member, up to the line of execution, that mention the variable. By tokens: there is no parser to tell a declaration from a use,
 * and both are worth the value.
 */
object CSharpInlineValues {
    /**
     * Zero-based lines that mention [name] as a name of its own (`total`, not `order.total` and not `"total"`), inside the member that
     * contains [currentLine] and not below that line: what is below has not happened yet, the value there would be a guess.
     */
    fun lines(text: CharSequence, name: String, currentLine: Int): List<Int> {
        if (name.isEmpty() || name == "this") return emptyList()
        val lineStarts = ArrayList<Int>().apply { add(0); text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) } }
        if (currentLine !in lineStarts.indices) return emptyList()
        val lineEnd = if (currentLine + 1 < lineStarts.size) lineStarts[currentLine + 1] else text.length

        // the member around the line; top-level statements have none, there the whole file above the line counts
        val member = CSharpDeclarations.scan(text).pathTo(lineStarts[currentLine].coerceAtMost((text.length - 1).coerceAtLeast(0))).lastOrNull { !it.kind.isType && it.kind != DeclarationKind.NAMESPACE }
        val from = member?.range?.startOffset ?: 0

        val result = LinkedHashSet<Int>()
        val lexer = CSharpLexer()
        lexer.start(text)
        var previousCode: String? = null
        var line = 0
        var position = 0
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            if (start >= lineEnd) break
            while (position < start) if (text[position++] == '\n') line++
            val token = text.subSequence(start, lexer.tokenEnd).toString()
            if (start >= from && type == CSharpTokenTypes.IDENTIFIER && token == name && previousCode != ".") result += line
            if (type != com.intellij.psi.TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS) previousCode = token
            lexer.advance()
        }
        return result.toList()
    }
}
