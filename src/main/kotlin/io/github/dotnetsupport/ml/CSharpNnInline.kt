package io.github.dotnetsupport.ml

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.lang.CSharpLeaves
import io.github.dotnetsupport.lang.CSharpLexer
import io.github.dotnetsupport.lang.CSharpTokenTypes

/** The network behind [CSharpNnInlineCompletionProvider]; [CSharpMlModels] implements it, tests fake it. */
interface CSharpNnEngine {
    /** The line at the caret of [editor] (the key of its KV-cache session), or null while the network is not ready. Suspends until its thread is free. */
    suspend fun complete(editor: Any, context: CSharpNnInline.Context): CSharpNnInline.Answer?
    /** A shown suggestion was accepted with Tab. */
    fun accepted() {}
}

/**
 * The pure part of the grey text (no platform, no model): what the network gets from the document and what of its answer is shown.
 * The rules are those the Go plugin arrived at in live use (0.2.199–0.2.206): the overlap with the rest of the line is dropped, the
 * gate counts the code tokens only (the text of a string literal is the guess), an uncertain line shows its certain start, a line
 * just opened by Enter has its own gate, the gate after a dot is lower. The engine (`ml-core`) does the healing, the closer trimming
 * and the show rule; nothing of that is repeated here.
 */
object CSharpNnInline {
    /** The text before the caret the network sees (its prompt cuts 40 KB forward to a line start). */
    const val PREFIX_BYTES = 40_000
    /** The text after the line of the caret the network sees. */
    const val SUFFIX_BYTES = 16_000

    /** UTF-8 as the document has it (`\n` line ends), [path] relative to the project root like in training. */
    class Context(val path: ByteArray, val before: ByteArray, val after: ByteArray)
    class Answer(val text: String, val show: Boolean, val confProd: Double)

    /** [PREFIX_BYTES] before [offset]; after it the rest of its line and [SUFFIX_BYTES] more. */
    fun context(text: CharSequence, offset: Int, path: String): Context {
        // a char is at least one UTF-8 byte: PREFIX_BYTES chars hold enough bytes, the byte cut is exact
        var from = maxOf(0, offset - PREFIX_BYTES)
        if (from > 0 && Character.isLowSurrogate(text[from])) from++
        val before = utf8(text.subSequence(from, offset)).let { if (it.size > PREFIX_BYTES) it.copyOfRange(it.size - PREFIX_BYTES, it.size) else it }
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        var to = minOf(text.length, lineEnd + SUFFIX_BYTES)
        if (to < text.length && to > lineEnd && Character.isHighSurrogate(text[to - 1])) to--
        val line = utf8(text.subSequence(offset, lineEnd))
        val rest = utf8(text.subSequence(lineEnd, to)).let { if (it.size > SUFFIX_BYTES) it.copyOf(SUFFIX_BYTES) else it }
        return Context(path.toByteArray(Charsets.UTF_8), before, line + rest)
    }

    /** [filePath] relative to [basePath] with `/`, or the file name when it lies outside. */
    fun relativePath(basePath: String?, filePath: String): String {
        val base = basePath?.replace('\\', '/')?.trimEnd('/')
        val path = filePath.replace('\\', '/')
        return if (base != null && base.isNotEmpty() && path.startsWith("$base/")) path.substring(base.length + 1) else path.substringAfterLast('/')
    }

    /** True when the byte before the caret ends `.`, `?.`, `::` or `->`: a member list, where the lower gate applies (ML_INLINE_TASK.md). */
    fun afterDot(before: ByteArray): Boolean {
        val n = before.size
        if (n == 0) return false
        val last = before[n - 1].toInt().toChar()
        if (last == '.') return true
        if (n < 2) return false
        val prev = before[n - 2].toInt().toChar()
        return (last == ':' && prev == ':') || (last == '>' && prev == '-')
    }

    /**
     * The grey text of [answer]: only what is shown, without the tail that the line already has after the caret (the model writes the
     * line to its end and sees what is there: at `Validate(int⟨⟩) {` it may answer `, string name) {` before `) {`). Nothing when the
     * suggestion is what already follows the caret on the line, or when it would make the line a copy of the previous one
     * ([repeatsPreviousLine]).
     */
    fun text(answer: Answer?, after: ByteArray = ByteArray(0), before: ByteArray = ByteArray(0)): String? {
        val text = answer?.takeIf { it.show }?.text?.takeIf { it.isNotEmpty() } ?: return null
        val rest = restOfLine(after)
        if (text == rest || repeatsPreviousLine(before, text)) return null
        return trimOverlap(text, rest).takeIf { it.isNotEmpty() }
    }

    /**
     * True when the line of the caret completed with [text] equals the previous line exactly: the model copied the line above
     * (`a.Name = b.Name;` twice), a repetition the engine's n-gram guard does not see because the copy is in the prompt, not in the output.
     */
    fun repeatsPreviousLine(before: ByteArray, text: String): Boolean {
        val line = lineBefore(before, 0)
        val prevEnd = before.size - line.size - 1   // the `\n` before the current line
        if (prevEnd < 0) return false
        var prevStart = prevEnd
        while (prevStart > 0 && before[prevStart - 1] != '\n'.code.toByte()) prevStart--
        if (prevEnd == prevStart) return false   // an empty previous line is no copy
        return String(before, prevStart, prevEnd - prevStart, Charsets.UTF_8) == String(line, Charsets.UTF_8) + text
    }

    /**
     * True when the caret stands inside a string or character literal (the text of an interpolated string, a raw or verbatim one) or in
     * a comment (`// ⟨⟩` and `///` included), gated by [CSharpMlSettings.inlineInStrings] (on: log and error messages are code-like) and [CSharpMlSettings.inlineInComments] (off: prose);
     * right after the closing quote or the end of a block comment it is code again, and so is a hole of an interpolated string on the native tree. From the PSI
     * leaf at the caret (either tree, [CSharpLeaves]) while the document is committed, else from the host lexer over the text (the
     * request of a typing event comes with the cached PSI, which may be behind the document).
     */
    fun inStringOrComment(file: PsiFile, document: Document, offset: Int): Boolean = literalAt(file, document, offset) != null

    /** [inStringOrComment] over the text alone: the token of the host lexer that holds the character before the caret. */
    fun inStringOrComment(text: CharSequence, offset: Int): Boolean = literalAt(text, offset) != null

    /** What the caret is inside of: a string / character literal, a comment, or nothing (code). */
    enum class Literal { STRING, COMMENT }

    /** [Literal] at the caret, or null in code; strings are gated by [CSharpMlSettings.inlineInStrings], comments by [CSharpMlSettings.inlineInComments]. */
    fun literalAt(file: PsiFile, document: Document, offset: Int): Literal? {
        if (offset <= 0) return null
        val text = document.immutableCharSequence
        if (!PsiDocumentManager.getInstance(file.project).isCommitted(document)) return literalAt(text, offset)
        val leaf = file.findElementAt(offset - 1) ?: return null
        if (CSharpLeaves.STRINGS.contains(leaf.node.elementType)) return Literal.STRING.takeIf { offset < leaf.textRange.endOffset || !closedString(leaf.text) }
        val comment = CSharpLeaves.commentAround(leaf) ?: return null
        val block = comment.text.startsWith("/*")
        return Literal.COMMENT.takeIf { if (block) offset < comment.textRange.endOffset || !comment.text.endsWith("*/") else text[offset - 1] != '\n' }
    }

    fun literalAt(text: CharSequence, offset: Int): Literal? {
        if (offset <= 0) return null
        val lexer = CSharpLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: return null
            if (lexer.tokenEnd >= offset) return when (type) {
                CSharpTokenTypes.STRING, CSharpTokenTypes.CHAR -> Literal.STRING.takeIf { offset < lexer.tokenEnd || !closedString(lexer.tokenSequence) }
                CSharpTokenTypes.LINE_COMMENT, CSharpTokenTypes.DOC_COMMENT -> Literal.COMMENT.takeIf { text[offset - 1] != '\n' }
                CSharpTokenTypes.BLOCK_COMMENT -> Literal.COMMENT.takeIf { offset < lexer.tokenEnd || !(lexer.tokenSequence.length >= 4 && lexer.tokenSequence.endsWith("*/")) }
                else -> null
            }
            lexer.advance()
        }
    }

    /**
     * True when the literal [text] ends with its closing quote: `"a"`, `'a'`, `@"a\"` (verbatim: no escapes), `"""a"""`, `"a"u8`;
     * not `"a`, `"a\"`, a lone `"` or the text of an interpolated string (no quotes at all).
     */
    fun closedString(text: CharSequence): Boolean {
        var t = text
        if (t.length > 2 && (t.endsWith("u8") || t.endsWith("U8"))) t = t.subSequence(0, t.length - 2)
        val open = t.indexOfFirst { it == '"' || it == '\'' }
        if (open < 0 || t.length < open + 2 || t[t.length - 1] != t[open]) return false
        val verbatim = t.subSequence(0, open).contains('@') || t.length >= open + 3 && t[open + 1] == '"' && t[open + 2] == '"' && t[open] == '"'
        if (verbatim) return true
        var backslashes = 0
        var i = t.length - 2
        while (i > open && t[i] == '\\') { backslashes++; i-- }
        return backslashes % 2 == 0
    }

    /** [text] without its longest tail that is also the start of [rest] (whitespace included: `, error) {` before `) {` → `, error`). */
    fun trimOverlap(text: String, rest: String): String {
        for (n in minOf(text.length, rest.length) downTo 1) if (text.regionMatches(text.length - n, rest, 0, n)) return text.dropLast(n)
        return text
    }

    /** The current line of [after] (what follows the caret up to the line end). */
    fun restOfLine(after: ByteArray): String {
        var n = 0
        while (n < after.size && after[n] != '\n'.code.toByte()) n++
        return String(after, 0, n, Charsets.UTF_8)
    }

    /**
     * The model's confidence over its code only: the tokens inside a string or character literal or a line comment are free text, each
     * word of it is unlikely on its own and the product over the line never reaches the gate (`throw new InvalidOperationException("…")`).
     * Counted: every token that starts and ends outside a literal and the token that ends the line; [lineBefore] (the current line up to
     * the healed boundary) gives the state the caret is in.
     */
    fun codeConfidence(lineBefore: ByteArray, tokens: List<ByteArray>, logProbs: FloatArray, stopLogProb: Float): Double {
        val state = LiteralState().apply { scan(lineBefore) }
        var sum = 0.0
        for (i in tokens.indices) {
            // the tokens that open and close the literal are part of the guess too
            val code = !state.inText
            state.scan(tokens[i])
            if (code && !state.inText) sum += logProbs[i]
        }
        if (!stopLogProb.isNaN() && !state.inText) sum += stopLogProb
        return Math.exp(sum)
    }

    /**
     * The certain start of a suggestion whose whole line is not: the longest run of its tokens, cut at a word boundary, whose product
     * of probabilities passes [gate] — without the [typed] bytes the first tokens reproduce. Null when nothing worth showing is left
     * (a lone first word on a fresh line, fewer than three word bytes, an open bracket or a trailing comma).
     */
    fun certainPrefix(tokens: List<ByteArray>, logProbs: FloatArray, typed: Int, gate: Double): ByteArray? {
        var sum = 0.0
        var best = -1
        for (i in tokens.indices) {
            sum += logProbs[i]
            if (Math.exp(sum) < gate) break
            best = i
        }
        // the longest cut that reads as a finished piece: at a word boundary, never inside an identifier, ending with a word or a
        // closing bracket, brackets balanced, at least three word bytes beyond what is typed
        val raw = ByteArray(tokens.sumOf { it.size })
        var n = 0
        for (t in tokens) { System.arraycopy(t, 0, raw, n, t.size); n += t.size }
        val ends = IntArray(tokens.size); var end = 0
        for (i in tokens.indices) { end += tokens[i].size; ends[i] = end }
        for (i in best downTo 0) {
            if (typed == 0 && i == 0) break   // a lone first word is no suggestion
            if (i < tokens.lastIndex && isWordByte(tokens[i + 1][0]) && isWordByte(tokens[i].last())) continue
            if (ends[i] <= typed) break
            if (finished(raw, typed, ends[i])) return raw.copyOfRange(typed, ends[i])
        }
        return null
    }

    private fun finished(raw: ByteArray, from: Int, to: Int): Boolean {
        val last = raw[to - 1].toInt().toChar()
        if (!isWordByte(raw[to - 1]) && last != ')' && last != ']' && last != '}') return false
        var depth = 0; var words = 0
        for (i in from until to) {
            when (raw[i].toInt().toChar()) { '(', '[', '{' -> depth++; ')', ']', '}' -> depth-- }
            if (isWordByte(raw[i])) words++
        }
        return depth == 0 && words >= 3
    }

    private fun isWordByte(b: Byte): Boolean {
        val c = b.toInt() and 0xff
        return c in 65..90 || c in 97..122 || c in 48..57 || c == 95 || c >= 128
    }

    /** True when the caret's line has nothing but indentation before it: the line just opened by Enter, where the whole statement is a guess. */
    fun blankLine(before: ByteArray): Boolean {
        var i = before.size
        while (i > 0 && before[i - 1] != '\n'.code.toByte()) { val c = before[i - 1].toInt(); if (c != ' '.code && c != '\t'.code) return false; i-- }
        return true
    }

    /** The current line of [before] without its last [typed] bytes (the healed remainder the model reproduced). */
    fun lineBefore(before: ByteArray, typed: Int): ByteArray {
        val end = maxOf(0, before.size - typed)
        var start = end
        while (start > 0 && before[start - 1] != '\n'.code.toByte()) start--
        return before.copyOfRange(start, end)
    }

    /**
     * Where in a C# line a byte stream stands: inside `"…"` (with `\` escapes; `@"…"` verbatim, where `""` is a quote), inside `'…'`,
     * after `//`, or in code. Interpolation holes of `$"…"` count as text (a rough rule, good enough for the gate).
     */
    private class LiteralState {
        private var quote = 0   // 0, '"' or '\''
        private var verbatim = false
        private var escaped = false
        private var comment = false
        private var lastSlash = false
        private var lastAt = false
        val inText: Boolean get() = quote != 0 || comment

        fun scan(bytes: ByteArray) {
            for (b in bytes) {
                val c = b.toInt() and 0xff
                when {
                    comment -> if (c == '\n'.code) comment = false
                    quote != 0 -> when {
                        escaped -> escaped = false
                        !verbatim && c == '\\'.code -> escaped = true
                        c == quote -> quote = 0
                        c == '\n'.code && !verbatim -> quote = 0
                    }
                    c == '"'.code -> { quote = c; verbatim = lastAt }
                    c == '\''.code -> { quote = c; verbatim = false }
                    c == '/'.code && lastSlash -> comment = true
                }
                lastSlash = c == '/'.code && quote == 0 && !comment
                lastAt = c == '@'.code || (lastAt && c == '$'.code)
            }
        }
    }

    fun suggestion(text: String?): InlineCompletionSuggestion =
        if (text == null) InlineCompletionSuggestion.Empty else InlineCompletionSingleSuggestion.build { emit(InlineCompletionGrayTextElement(text)) }

    private fun utf8(s: CharSequence): ByteArray = s.toString().toByteArray(Charsets.UTF_8)
}
