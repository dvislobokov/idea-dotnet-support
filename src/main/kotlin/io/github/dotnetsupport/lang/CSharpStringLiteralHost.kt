package io.github.dotnetsupport.lang

import com.intellij.lang.ASTFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.dotnetsupport.csharp.lang.SyntaxKind

/**
 * A string literal token of csharp-psi's tree (`"…"`, `@"…"`, `"""…"""`) as a host of an injected language: the platform's RegExp in the
 * pattern of `new Regex(…)` and the like ([CSharpRegexPlaces]), whatever IntelliLang injects by hand. The leaf is what the parser makes
 * anyway, of another class ([CSharpAstFactory]); the text, the colors and the tree stay the same. Interpolated strings are no hosts.
 */
class CSharpStringLiteralLeaf(type: IElementType, text: CharSequence) : LeafPsiElement(type, text), PsiLanguageInjectionHost {
    override fun isValidHost(): Boolean = CSharpStringLiterals.shape(text) != null

    override fun updateText(text: String): PsiLanguageInjectionHost = (node as LeafElement).replaceWithText(text).psi as PsiLanguageInjectionHost

    override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> = CSharpStringEscaper(this)

    override fun toString(): String = "CSharpStringLiteral($elementType)"
}

/** Makes the string literal tokens of the native tree [CSharpStringLiteralLeaf]s; every other node is the platform's default. */
class CSharpAstFactory : ASTFactory() {
    override fun createLeaf(type: IElementType, text: CharSequence): LeafElement? = if (HOSTS.contains(type)) CSharpStringLiteralLeaf(type, text) else null

    companion object {
        val HOSTS: TokenSet = TokenSet.create(
            SyntaxKind.StringLiteralToken, SyntaxKind.SingleLineRawStringLiteralToken, SyntaxKind.MultiLineRawStringLiteralToken,
        )
    }
}

/** Edits of an injected fragment (Edit RegExp Fragment, Check RegExp) go back into the literal through this. */
class CSharpStringLiteralManipulator : AbstractElementManipulator<CSharpStringLiteralLeaf>() {
    override fun handleContentChange(element: CSharpStringLiteralLeaf, range: TextRange, newContent: String): CSharpStringLiteralLeaf =
        element.updateText(range.replace(element.text, newContent)) as CSharpStringLiteralLeaf

    override fun getRangeInElement(element: CSharpStringLiteralLeaf): TextRange =
        CSharpStringLiterals.shape(element.text)?.let { TextRange(it.contentStart, it.contentEnd) } ?: TextRange.from(0, element.textLength)
}

/** The parts of the text of a string literal: where its content is and how it is escaped. */
object CSharpStringLiterals {
    enum class Kind { REGULAR, VERBATIM, RAW }

    /** [lines]: the content of a multi-line raw string line by line, without the indentation of the closing quotes, each with its line break but the last. */
    class Shape(val kind: Kind, val contentStart: Int, val contentEnd: Int, val lines: List<TextRange>)

    /** Null for what is no string literal (an interpolated string, a char). */
    fun shape(text: String): Shape? {
        var end = text.length
        if (end >= 2 && (text.endsWith("u8") || text.endsWith("U8"))) end -= 2
        var i = 0
        val verbatim = text.startsWith("@")
        if (verbatim) i++
        if (i >= end || text[i] != '"') return null
        var quotes = 0
        while (i + quotes < end && text[i + quotes] == '"') quotes++
        if (!verbatim && quotes >= 3) {
            val start = i + quotes
            var closing = 0
            while (closing < quotes && end - closing - 1 >= start && text[end - closing - 1] == '"') closing++
            val contentEnd = if (closing == quotes) end - quotes else end
            return Shape(Kind.RAW, start, contentEnd, rawLines(text, start, contentEnd))
        }
        val start = i + 1
        val closed = end - start >= 1 && text[end - 1] == '"' && (verbatim || !escaped(text, start, end - 1))
        // `""` alone is the empty string, not an open one
        val contentEnd = if (quotes == 2 && end == start + 1) start else if (closed) end - 1 else end
        return Shape(if (verbatim) Kind.VERBATIM else Kind.REGULAR, start, contentEnd, listOf(TextRange(start, contentEnd)))
    }

    /** The quote at [at] is escaped by an odd number of backslashes before it. */
    private fun escaped(text: String, start: Int, at: Int): Boolean {
        var k = at - 1
        var n = 0
        while (k >= start && text[k] == '\\') { n++; k-- }
        return n % 2 == 1
    }

    /** `"""` + line break + lines + line break + indentation + `"""`: the lines without that indentation; a single-line raw string as it is. */
    private fun rawLines(text: String, start: Int, end: Int): List<TextRange> {
        val firstBreak = text.indexOf('\n', start)
        if (firstBreak < 0 || firstBreak >= end || text.substring(start, firstBreak).isNotBlank()) return listOf(TextRange(start, end))
        val lastBreak = text.lastIndexOf('\n', end - 1)
        if (lastBreak <= firstBreak || text.substring(lastBreak + 1, end).isNotBlank()) return listOf(TextRange(start, end))
        val indent = end - (lastBreak + 1)
        val out = ArrayList<TextRange>()
        var lineStart = firstBreak + 1
        while (lineStart <= lastBreak) {
            val lineBreak = text.indexOf('\n', lineStart)
            val contentEnd = if (lineBreak == lastBreak) lastBreak.let { if (it > lineStart && text[it - 1] == '\r') it - 1 else it } else lineBreak + 1
            var from = lineStart
            while (from < contentEnd && from - lineStart < indent && (text[from] == ' ' || text[from] == '\t')) from++
            out += TextRange(from, maxOf(from, contentEnd))
            lineStart = lineBreak + 1
        }
        return out
    }
}

/**
 * The value of the literal over a range of its text: C# escapes of a regular string (`\\`, `\"`, `\n`, `A`...) decoded, `""` of a
 * verbatim one, a raw string as it is. Offsets map back, so a regular expression `"\\d+"` reads `\d+` and its parts point into the host.
 */
class CSharpStringEscaper(host: CSharpStringLiteralLeaf) : LiteralTextEscaper<CSharpStringLiteralLeaf>(host) {
    private var offsets: IntArray? = null
    private var decodedRange: TextRange? = null

    override fun decode(rangeInsideHost: TextRange, outChars: StringBuilder): Boolean {
        val text = myHost.text
        val kind = CSharpStringLiterals.shape(text)?.kind ?: CSharpStringLiterals.Kind.RAW
        val map = IntArray(rangeInsideHost.length + 1)
        var count = 0
        var i = rangeInsideHost.startOffset
        val end = rangeInsideHost.endOffset
        var ok = true
        fun put(c: Char, at: Int) {
            outChars.append(c)
            map[count++] = at - rangeInsideHost.startOffset
        }
        while (i < end) {
            val c = text[i]
            when {
                kind == CSharpStringLiterals.Kind.VERBATIM && c == '"' && i + 1 < end && text[i + 1] == '"' -> { put('"', i); i += 2 }
                kind == CSharpStringLiterals.Kind.REGULAR && c == '\\' -> {
                    val (value, length) = escape(text, i, end)
                    val start = i
                    if (value == null) {
                        ok = false
                        for (k in start until minOf(start + length, end)) put(text[k], k)
                    } else value.forEach { put(it, start) }
                    i += length
                }
                else -> { put(c, i); i++ }
            }
        }
        map[count] = end - rangeInsideHost.startOffset
        offsets = map.copyOf(count + 1)
        decodedRange = rangeInsideHost
        return ok
    }

    override fun getOffsetInHost(offsetInDecoded: Int, rangeInsideHost: TextRange): Int {
        if (decodedRange != rangeInsideHost || offsets == null) decode(rangeInsideHost, StringBuilder())
        val map = offsets!!
        if (offsetInDecoded < 0 || offsetInDecoded >= map.size) return -1
        return rangeInsideHost.startOffset + map[offsetInDecoded]
    }

    override fun isOneLine(): Boolean = CSharpStringLiterals.shape(myHost.text)?.kind == CSharpStringLiterals.Kind.REGULAR

    private companion object {
        /** The value of the escape at [at] and its length in the text; a null value for an invalid one. */
        fun escape(text: String, at: Int, end: Int): Pair<String?, Int> {
            if (at + 1 >= end) return null to 1
            fun hex(from: Int, min: Int, max: Int): Pair<String?, Int> {
                var k = 0
                while (k < max && from + k < end && text[from + k].let { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) k++
                // `\UFFFFFFFF` is typed text, not a code point: an invalid escape, not an exception in the highlighting
                val code = if (k < min) null else text.substring(from, from + k).toLong(16).takeIf { it <= Character.MAX_CODE_POINT }?.toInt()
                if (code == null) return null to 2 + k
                return (if (code > 0xFFFF) String(Character.toChars(code)) else code.toChar().toString()) to 2 + k
            }
            return when (text[at + 1]) {
                '\'' -> "'" to 2
                '"' -> "\"" to 2
                '\\' -> "\\" to 2
                '0' -> "\u0000" to 2
                'a' -> "\u0007" to 2
                'b' -> "\b" to 2
                'e' -> "\u001B" to 2
                'f' -> "\u000C" to 2
                'n' -> "\n" to 2
                'r' -> "\r" to 2
                't' -> "\t" to 2
                'v' -> "\u000B" to 2
                'x' -> hex(at + 2, 1, 4)
                'u' -> hex(at + 2, 4, 4)
                'U' -> hex(at + 2, 8, 8)
                else -> null to 2
            }
        }
    }
}
