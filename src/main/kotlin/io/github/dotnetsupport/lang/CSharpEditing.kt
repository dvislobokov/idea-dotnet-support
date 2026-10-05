package io.github.dotnetsupport.lang

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class CSharpCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class CSharpBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(
            BracePair(CSharpTokenTypes.LBRACE, CSharpTokenTypes.RBRACE, true),
            BracePair(CSharpTokenTypes.LPAREN, CSharpTokenTypes.RPAREN, false),
            BracePair(CSharpTokenTypes.LBRACKET, CSharpTokenTypes.RBRACKET, false),
        )
    }
}

/**
 * `"` gets its pair. The token of a string starts at its prefix (`$"`, `@"`, `$@"`, `$$"""`), and the platform takes a quote for an opening
 * one only where the token starts: `Console.WriteLine($"` was left without its `"`.
 */
class CSharpQuoteHandler : SimpleTokenSetQuoteHandler(CSharpStringTokens.LITERAL_TEXT) {
    // The editor's tokens split a literal ([CSharpHighlightingLexer]): it starts with a piece of type STRING / CHAR and, when split, ends
    // with STRING_END / CHAR_END. Where a literal ends is asked of [CSharpLexer], which keeps it whole.

    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        if (iterator.tokenType !in CSharpTokenTypes.STRINGS) return false
        val text = iterator.document.charsSequence
        val start = iterator.start
        return offset >= start && (start until offset).all { text[it] == '$' || text[it] == '@' }
    }

    override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean = when (iterator.tokenType) {
        CSharpTokenTypes.STRING, CSharpTokenTypes.CHAR -> literalEnd(iterator.document.charsSequence, iterator.start) == iterator.end && offset == iterator.end - 1
        CSharpStringTokens.STRING_END, CSharpStringTokens.CHAR_END -> offset == iterator.end - 1
        else -> false
    }

    override fun isNonClosedLiteral(iterator: HighlighterIterator, chars: CharSequence): Boolean {
        if (iterator.tokenType !in CSharpTokenTypes.STRINGS) return false
        val end = literalEnd(chars, iterator.start)
        return iterator.start >= end - 1 || (chars[end - 1] != '"' && chars[end - 1] != '\'')
    }

    private fun literalEnd(chars: CharSequence, start: Int): Int {
        val lexer = CSharpLexer()
        lexer.start(chars, start, chars.length, 0)
        return lexer.tokenEnd
    }
}

/**
 * `<` of a generic gets its `>`, as the parentheses get theirs: `AddSingleton<|>`, `new List<|>`, `Task<|>`. The platform pairs only what
 * the brace matcher lists, and `<` cannot be listed there: it is an operator as often as a bracket. Without a parser the two are told
 * apart by what stands before: a name with a capital letter right before `<`, no space between, is a type or a generic method; `i < n`
 * and `count<5` are comparisons. `Count<5` looks like a generic until the `5`: the pair is put and taken back at the first character
 * that no list of type arguments can have ([continuesTypeArguments]).
 */
object CSharpAngleBrackets {
    /** [offset] is where `<` stands (or is about to). */
    fun opensGeneric(text: CharSequence, offset: Int): Boolean {
        var start = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        if (start == offset || !text[start].isUpperCase()) return false
        // a name follows: `<` is put in front of what is already written, the pair would land in the middle. The parentheses of a
        // call are not that: `AddSingleton|()` becomes `AddSingleton<|>()`
        val next = text.getOrNull(offset + 1)
        return next == null || !(next.isLetterOrDigit() || next == '_' || next == '<')
    }

    /** `>` typed right before a `>`: true when it closes a generic opened on this line, so the one that is there is stepped over. */
    fun closesGeneric(text: CharSequence, offset: Int): Boolean {
        if (text.getOrNull(offset) != '>') return false
        val lineStart = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        var depth = 0
        for (i in lineStart until offset) {
            when (text[i]) {
                '<' -> if (opensGenericBefore(text, i)) depth++
                '>' -> if (depth > 0 && text[i - 1] != '=' && text[i - 1] != '-') depth--
            }
        }
        return depth > 0
    }

    /**
     * Whether [c], typed after [inside] (what stands between `<` and the caret), can still belong to a list of type arguments:
     * `string, List<int>`, `(int, string)`, `int?[]`, `global::System.String`. A digit first, an operator, `;`, a space that follows no
     * comma cannot: `Count<5`, `Count<=`, `Count<n;`, `Count<n &&` are comparisons, and the `>` that was put for them is taken back.
     */
    fun continuesTypeArguments(inside: CharSequence, c: Char): Boolean {
        val last = inside.lastOrNull()
        val open = inside.count { it == '(' } - inside.count { it == ')' }
        return when {
            c.isLetter() || c == '_' || c == '@' -> true
            c.isDigit() -> last != null && (last.isLetterOrDigit() || last == '_')
            c == ',' -> inside.isNotBlank() && last != ','
            c == ' ' -> last == ',' || (open > 0 && last != null && last != ' ' && last != '(')
            c == '(' -> last == null || last == ',' || last == ' ' || last == '(' || last == '<'
            c == ')' -> open > 0
            c == '.' || c == '?' || c == '[' || c == ']' || c == '<' || c == ':' -> inside.isNotEmpty()
            else -> false
        }
    }

    /** `(` typed between the `>` of type arguments and an empty `()`: the call is entered, not a second pair put. */
    fun entersCall(text: CharSequence, offset: Int): Boolean =
        offset > 0 && text[offset - 1] == '>' && text.getOrNull(offset) == '(' && text.getOrNull(offset + 1) == ')'

    private fun opensGenericBefore(text: CharSequence, offset: Int): Boolean {
        var start = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        return start != offset && text[start].isUpperCase()
    }
}

class CSharpAngleBracketTypedHandler : com.intellij.codeInsight.editorActions.TypedHandlerDelegate() {
    override fun beforeCharTyped(c: Char, project: com.intellij.openapi.project.Project, editor: com.intellij.openapi.editor.Editor, file: PsiFile, fileType: com.intellij.openapi.fileTypes.FileType): Result {
        if (file !is CSharpFile || !enabled() || editor.caretModel.caretCount != 1) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = editor.document.immutableCharSequence
        // the pair this handler has put, the caret right before its `>`
        val pair = editor.getUserData(PAIR)?.takeIf { it.isValid && it.endOffset - 1 == offset && text.getOrNull(offset) == '>' && text.getOrNull(it.startOffset) == '<' }
        if (pair != null && c != '>' && !CSharpAngleBrackets.continuesTypeArguments(text.subSequence(pair.startOffset + 1, offset), c)) {
            // a comparison after all: `Count<5`
            forget(editor)
            editor.document.deleteString(offset, offset + 1)
            return Result.CONTINUE
        }
        if (c == '(' && CSharpAngleBrackets.entersCall(text, offset)) {
            // `AddSingleton<IClock>|()`, as the completion of a generic method leaves it: the parentheses are there already
            editor.caretModel.moveToOffset(offset + 1)
            com.intellij.codeInsight.AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
            return Result.STOP
        }
        if (c != '>' || !CSharpAngleBrackets.closesGeneric(text, offset)) return Result.CONTINUE
        if (pair != null) forget(editor)
        editor.caretModel.moveToOffset(offset + 1)
        return Result.STOP
    }

    private fun forget(editor: com.intellij.openapi.editor.Editor) {
        editor.getUserData(PAIR)?.dispose()
        editor.putUserData(PAIR, null)
    }

    override fun charTyped(c: Char, project: com.intellij.openapi.project.Project, editor: com.intellij.openapi.editor.Editor, file: PsiFile): Result {
        if (c != '<' || file !is CSharpFile || !enabled() || editor.caretModel.caretCount != 1) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = editor.document.immutableCharSequence
        if (offset == 0 || text[offset - 1] != '<' || !CSharpAngleBrackets.opensGeneric(text, offset - 1) || inTextOrComment(editor, offset - 1)) return Result.CONTINUE
        editor.document.insertString(offset, ">")
        // remembered, to be taken back when what is typed inside turns out to be no type: a nested pair replaces the outer one
        forget(editor)
        editor.putUserData(PAIR, editor.document.createRangeMarker(offset - 1, offset + 1).apply { isGreedyToRight = false })
        return Result.CONTINUE
    }

    private fun enabled() = com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET

    private companion object {
        val PAIR = com.intellij.openapi.util.Key.create<com.intellij.openapi.editor.RangeMarker>("dotnet.csharp.angle.pair")
    }

    private fun inTextOrComment(editor: com.intellij.openapi.editor.Editor, offset: Int): Boolean {
        val highlighter = (editor as? com.intellij.openapi.editor.ex.EditorEx)?.highlighter ?: return false
        val iterator = highlighter.createIterator(offset)
        if (iterator.atEnd()) return false
        val type = iterator.tokenType
        return CSharpStringTokens.LITERAL_TEXT.contains(type) || CSharpTokenTypes.COMMENTS.contains(type)
    }
}

/** Backspace on `<` of an empty `<>` takes the `>` with it. */
class CSharpAngleBracketBackspaceHandler : com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate() {
    private var paired = false

    override fun beforeCharDeleted(c: Char, file: PsiFile, editor: com.intellij.openapi.editor.Editor) {
        val offset = editor.caretModel.offset
        val text = editor.document.immutableCharSequence
        paired = c == '<' && file is CSharpFile && offset > 0 && text.getOrNull(offset) == '>' &&
            com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET && CSharpAngleBrackets.closesGeneric(text, offset)
    }

    override fun charDeleted(c: Char, file: PsiFile, editor: com.intellij.openapi.editor.Editor): Boolean {
        if (!paired) return false
        paired = false
        val offset = editor.caretModel.offset
        if (editor.document.immutableCharSequence.getOrNull(offset) != '>') return false
        editor.document.deleteString(offset, offset + 1)
        return true
    }
}

/**
 * `;` typed right before the `;` that ends the line steps over it: the completion of a void method puts `();` with the caret inside,
 * and after the arguments the hand types `)` and `;` as it always does. Inside `for (;;)` something follows the `;`, so it is typed.
 */
object CSharpSemicolons {
    fun stepsOver(text: CharSequence, offset: Int): Boolean {
        if (text.getOrNull(offset) != ';') return false
        var i = offset + 1
        while (i < text.length && text[i] != '\n') { if (!text[i].isWhitespace()) return false; i++ }
        return true
    }
}

class CSharpSemicolonTypedHandler : com.intellij.codeInsight.editorActions.TypedHandlerDelegate() {
    override fun beforeCharTyped(c: Char, project: com.intellij.openapi.project.Project, editor: com.intellij.openapi.editor.Editor, file: PsiFile, fileType: com.intellij.openapi.fileTypes.FileType): Result {
        if (file !is CSharpFile || editor.caretModel.caretCount != 1) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = editor.document.immutableCharSequence
        if (c == ')') {
            if (!CSharpParentheses.closes(text, offset)) return Result.CONTINUE
            editor.caretModel.moveToOffset(offset + 1)
            return Result.STOP
        }
        if (c != ';') return Result.CONTINUE
        if (CSharpSemicolons.stepsOver(text, offset)) {
            editor.caretModel.moveToOffset(offset + 1)
            return Result.STOP
        }
        val after = CSharpParentheses.semicolonAfter(text, offset) ?: return Result.CONTINUE
        // `new Repository(|)` + `;`: `new Repository();|`, as in Rider (DEV_JOURNEY 4.3: the `)` was lost)
        if (text.getOrNull(after) == ';') editor.caretModel.moveToOffset(after + 1)
        else {
            editor.document.insertString(after, ";")
            editor.caretModel.moveToOffset(after + 1)
        }
        return Result.STOP
    }
}

/**
 * The parentheses of a line by the tokens of [CSharpLexer] (what is in strings, chars and comments does not count): whether a typed `)` is
 * the one that is there already, and where a `;` typed inside the parentheses that end a statement goes.
 */
object CSharpParentheses {
    private val HEADERS = setOf("for", "foreach", "if", "while", "using", "lock", "switch", "catch", "fixed", "when")

    private class Line(val tokens: List<Triple<com.intellij.psi.tree.IElementType, Int, Int>>, val end: Int)

    private fun line(text: CharSequence, offset: Int): Line {
        val start = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        var end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        if (end > start && text[end - 1] == '\r') end--
        val lexer = CSharpLexer()
        lexer.start(text, start, end, 0)
        val tokens = ArrayList<Triple<com.intellij.psi.tree.IElementType, Int, Int>>()
        while (lexer.tokenType != null) {
            tokens += Triple(lexer.tokenType!!, lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return Line(tokens, end)
    }

    /** Whether [offset] is inside a string, a char or a comment of its line. */
    private fun inLiteral(line: Line, offset: Int): Boolean =
        line.tokens.any { (type, start, end) -> offset in (start + 1) until end && (type in CSharpTokenTypes.STRINGS || type in CSharpTokenTypes.COMMENTS) }

    /** The `(` open before [offset] on its line, less the `)` that close them. */
    private fun open(line: Line, offset: Int): Int {
        var depth = 0
        for ((type, start, _) in line.tokens) {
            if (start >= offset) break
            if (type == CSharpTokenTypes.LPAREN) depth++ else if (type == CSharpTokenTypes.RPAREN && depth > 0) depth--
        }
        return depth
    }

    /** `)` typed right before a `)`: true when the line has as many `)` after [offset] as there are `(` open before it, so a new one would be extra. */
    fun closes(text: CharSequence, offset: Int): Boolean {
        if (text.getOrNull(offset) != ')') return false
        val line = line(text, offset)
        if (inLiteral(line, offset)) return false
        val open = open(line, offset)
        if (open == 0) return false
        var depth = 0
        var closing = 0
        for ((type, start, _) in line.tokens) {
            if (start < offset) continue
            if (type == CSharpTokenTypes.LPAREN) depth++
            else if (type == CSharpTokenTypes.RPAREN) { if (depth > 0) depth-- else closing++ }
        }
        return closing >= open
    }

    /**
     * `;` typed at [offset], where only the `)` that close what is open before it follow on the line (a `;` after them at most): the offset
     * after those `)`, where the `;` goes. Null in the header of `for (;;)`, `if (…)` and the like, in strings and comments.
     */
    fun semicolonAfter(text: CharSequence, offset: Int): Int? {
        if (text.getOrNull(offset) != ')') return null
        val line = line(text, offset)
        if (inLiteral(line, offset)) return null
        val first = line.tokens.firstOrNull { it.first != com.intellij.psi.TokenType.WHITE_SPACE }
        if (first != null && text.subSequence(first.second, first.third).toString() in HEADERS) return null
        if (line.tokens.any { it.first == CSharpTokenTypes.KEYWORD && it.second < offset && text.subSequence(it.second, it.third).toString() == "for" }) return null
        var closing = 0
        var after = offset
        for ((type, start, end) in line.tokens) {
            if (start < offset) continue
            when {
                type == CSharpTokenTypes.RPAREN && after == start -> { closing++; after = end }
                type == com.intellij.psi.TokenType.WHITE_SPACE || type in CSharpTokenTypes.COMMENTS -> {}
                type == CSharpTokenTypes.SEMICOLON && start >= after -> return if (closing in 1..open(line, offset)) start else null
                else -> return null
            }
        }
        return if (closing in 1..open(line, offset)) after else null
    }
}

/**
 * The list opens by itself after a space that follows `override`, `partial` or `new`, as in Rider: the members to override, the partial
 * methods, the types to create. The client of the language server opens it on its trigger characters, but the server is off by
 * default, and the platform opens a list by itself only on letters.
 */
class CSharpSpaceAutoPopupHandler : com.intellij.codeInsight.editorActions.TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: com.intellij.openapi.project.Project, editor: com.intellij.openapi.editor.Editor, file: PsiFile): Result {
        if (charTyped != ' ' || file !is CSharpFile || editor.caretModel.caretCount != 1) return Result.CONTINUE
        val offset = editor.caretModel.offset
        if (!CSharpSpaceAutoPopup.opens(editor.document.immutableCharSequence, offset)) return Result.CONTINUE
        val leaf = file.findElementAt(offset - 1)
        if (leaf != null && CSharpLeaves.isInStringOrComment(leaf)) return Result.CONTINUE
        com.intellij.codeInsight.AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }
}

object CSharpSpaceAutoPopup {
    private val WORDS = setOf("override", "partial", "new")

    /** Whether a space typed at [offset] of [text] comes right after one of the words that open the list. */
    fun opens(text: CharSequence, offset: Int): Boolean {
        var start = offset
        while (start > 0 && text[start - 1].isLetter()) start--
        if (start == offset || start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == '@' || text[start - 1] == '.')) return false
        return text.subSequence(start, offset).toString() in WORDS
    }
}
