package io.github.dotnetsupport.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * The items of composite format strings (`{0}`, `{1,5:N2}`) as Rider colors them (`ReSharper.FORMAT_STRING_ITEM`): in the format argument
 * of `string.Format`, `StringBuilder.AppendFormat`, `Console.Write` / `WriteLine` (and other writers) with arguments to format, and
 * `Trace.TraceInformation` / `TraceWarning` / `TraceError`. By the name of the called method on the tokens of [CSharpLexer], so the same
 * on both trees and with no index: the format argument is a literal, the first argument or, after an `IFormatProvider`, the second.
 */
object CSharpFormatItems {
    private val FORMAT = setOf("Format", "AppendFormat")
    private val WRITE = setOf("Write", "WriteLine", "TraceInformation", "TraceWarning", "TraceError")
    // `Debug.WriteLine(message, category)`, `Trace.Write(message, category)`: a second string is no argument to format
    private val NOT_FORMATTING = setOf("Debug", "Trace")

    /** The format items of [text] in order; `true` for the second of two items side by side (`{0}{1}`), which Rider colors apart. */
    fun items(text: CharSequence): List<Pair<TextRange, Boolean>> {
        val tokens = tokens(text)
        val out = ArrayList<Pair<TextRange, Boolean>>()
        for (k in tokens.indices) {
            val name = tokens[k]
            if (name.type != CSharpTokenTypes.IDENTIFIER || tokens.getOrNull(k + 1)?.type != CSharpTokenTypes.LPAREN) continue
            val method = name.text(text)
            if (method !in FORMAT && method !in WRITE) continue
            val receiver = if (tokens.getOrNull(k - 1)?.type == CSharpTokenTypes.DOT) tokens.getOrNull(k - 2)?.text(text) else null
            if (method == "Format" && receiver != "string" && receiver != "String") continue
            if (method in WRITE && (receiver == null || (receiver in NOT_FORMATTING && method.startsWith("Write")))) continue
            val arguments = arguments(tokens, k + 1)
            // `Console.WriteLine("{0}")` takes the string as it is
            if (method in WRITE && arguments.size < 2) continue
            val format = arguments.take(2).firstOrNull { it.size == 1 && it[0].type == CSharpTokenTypes.STRING && !interpolated(text, it[0].start) }?.single() ?: continue
            if (format != arguments[0].singleOrNull() && method !in FORMAT) continue
            out += itemsOf(text, format.start, format.end)
        }
        return out
    }

    private fun interpolated(text: CharSequence, start: Int): Boolean {
        var i = start
        while (i < text.length && (text[i] == '$' || text[i] == '@')) if (text[i++] == '$') return true
        return false
    }

    /** The items of a (non-interpolated) string literal over `[start, end)`. */
    fun itemsOf(text: CharSequence, start: Int, end: Int): List<Pair<TextRange, Boolean>> {
        var i = start
        var verbatim = false
        while (i < end && text[i] == '@') { verbatim = true; i++ }
        var quotes = 0
        while (i + quotes < end && text[i + quotes] == '"') quotes++
        val raw = quotes >= 3 && !verbatim
        val contentStart = i + if (raw) quotes else 1
        val contentEnd = if (raw) end - quotes else end - 1
        val out = ArrayList<Pair<TextRange, Boolean>>()
        var lastEnd = -1
        var lastSecond = false
        i = contentStart
        while (i < contentEnd) {
            val c = text[i]
            when {
                c == '\\' && !verbatim && !raw -> i += 2
                (c == '{' || c == '}') && i + 1 < contentEnd && text[i + 1] == c -> i += 2
                c == '{' -> {
                    val close = itemEnd(text, i, contentEnd)
                    if (close < 0) i++ else {
                        val second = lastEnd == i && !lastSecond
                        out += TextRange(i, close) to second
                        lastEnd = close
                        lastSecond = second
                        i = close
                    }
                }
                else -> i++
            }
        }
        return out
    }

    /** `{index[,alignment][:format]}` at [at]: the end after `}`, or -1 when no item stands there. */
    private fun itemEnd(text: CharSequence, at: Int, limit: Int): Int {
        var i = at + 1
        while (i < limit && text[i] == ' ') i++
        val digits = i
        while (i < limit && text[i].isDigit()) i++
        if (i == digits) return -1
        while (i < limit && text[i] == ' ') i++
        if (i < limit && text[i] == ',') {
            i++
            while (i < limit && text[i] == ' ') i++
            if (i < limit && text[i] == '-') i++
            val alignment = i
            while (i < limit && text[i].isDigit()) i++
            if (i == alignment) return -1
            while (i < limit && text[i] == ' ') i++
        }
        if (i < limit && text[i] == ':') {
            while (i < limit && text[i] != '}' && text[i] != '{') i++
        }
        return if (i < limit && text[i] == '}') i + 1 else -1
    }

    private class Token(val type: IElementType, val start: Int, val end: Int) {
        fun text(text: CharSequence): String = text.subSequence(start, end).toString()
    }

    private fun tokens(text: CharSequence): List<Token> {
        val out = ArrayList<Token>()
        val lexer = CSharpLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS) out += Token(type, lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return out
    }

    /** The arguments of the call whose `(` is `tokens[open]`, each as its tokens; at most the first three are needed. */
    private fun arguments(tokens: List<Token>, open: Int): List<List<Token>> {
        val out = ArrayList<List<Token>>()
        var current = ArrayList<Token>()
        var depth = 0
        for (k in open + 1 until tokens.size) {
            val token = tokens[k]
            when (token.type) {
                CSharpTokenTypes.LPAREN, CSharpTokenTypes.LBRACKET, CSharpTokenTypes.LBRACE -> depth++
                CSharpTokenTypes.RPAREN, CSharpTokenTypes.RBRACKET, CSharpTokenTypes.RBRACE -> if (depth == 0) {
                    if (current.isNotEmpty()) out += current
                    return out
                } else depth--
                CSharpTokenTypes.SEMICOLON -> if (depth == 0) return out.also { if (current.isNotEmpty()) it += current }
                CSharpTokenTypes.COMMA -> if (depth == 0) {
                    out += current
                    current = ArrayList()
                    if (out.size >= 3) return out
                    continue
                }
            }
            current += token
        }
        if (current.isNotEmpty()) out += current
        return out
    }
}

/** Colors [CSharpFormatItems] over the string. DumbAware: no index, and the platform skips other annotators in files it does not index. */
class CSharpFormatItemsAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is CSharpFile) return
        for ((range, second) in CSharpFormatItems.items(element.viewProvider.contents)) {
            val key = if (second) CSharpSyntaxHighlighter.FORMAT_ITEM_2 else CSharpSyntaxHighlighter.FORMAT_ITEM
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
        }
    }
}
