package io.github.dotnetsupport.ml

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion

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
     * line to its end and sees what is there: at `Validate(int⟨⟩) {` it may answer `, string name) {` before `) {`).
     */
    fun text(answer: Answer?, after: ByteArray = ByteArray(0)): String? {
        val text = answer?.takeIf { it.show }?.text?.takeIf { it.isNotEmpty() } ?: return null
        return trimOverlap(text, restOfLine(after)).takeIf { it.isNotEmpty() }
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
