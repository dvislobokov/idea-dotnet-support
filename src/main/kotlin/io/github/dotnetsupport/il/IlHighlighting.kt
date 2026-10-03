package io.github.dotnetsupport.il

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** The kinds of the words of the ildasm text the viewer colors. */
enum class IlTokenKind { COMMENT, STRING, NUMBER, LABEL, DIRECTIVE, OPCODE, KEYWORD, TYPE_NAME, IDENTIFIER, PUNCTUATION, WHITESPACE }

class IlToken(val kind: IlTokenKind, val start: Int, val end: Int)

/**
 * Splits the ildasm text of a member into the words the viewer colors. Not a parser of ILAsm: an opcode is the first word after an
 * `IL_xxxx:` label (so `add` the opcode and `add` a name are told apart), a type name is what follows `class` / `valuetype` /
 * `extends` / `implements`, an `[assembly]Name` reference, or a name followed by `::`.
 */
object IlLexing {
    fun tokens(text: CharSequence): List<IlToken> {
        val result = ArrayList<IlToken>()
        var i = 0
        var expectOpcode = false
        var expectType = false
        while (i < text.length) {
            val c = text[i]
            val start = i
            when {
                c.isWhitespace() -> {
                    while (i < text.length && text[i].isWhitespace()) i++
                    result += IlToken(IlTokenKind.WHITESPACE, start, i)
                    continue
                }
                c == '/' && text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') i++
                    result += IlToken(IlTokenKind.COMMENT, start, i)
                    continue
                }
                c == '/' && text.startsWith("/*", i) -> {
                    val close = indexOf(text, "*/", i + 2)
                    i = if (close < 0) text.length else close + 2
                    result += IlToken(IlTokenKind.COMMENT, start, i)
                    continue
                }
                c == '"' -> {
                    i++
                    while (i < text.length && text[i] != '"' && text[i] != '\n') i += if (text[i] == '\\' && i + 1 < text.length) 2 else 1
                    if (i < text.length && text[i] == '"') i++
                    result += IlToken(IlTokenKind.STRING, start, i)
                }
                c == '[' && assemblyReferenceEnd(text, i) > 0 -> {
                    i = wordEnd(text, assemblyReferenceEnd(text, i))
                    result += IlToken(IlTokenKind.TYPE_NAME, start, i)
                    expectType = false
                }
                c.isDigit() -> {
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '.')) i++
                    result += IlToken(IlTokenKind.NUMBER, start, i)
                }
                isWordStart(text, i) -> {
                    i = wordEnd(text, i)
                    val word = text.subSequence(start, i).toString()
                    val kind = when {
                        LABEL.matches(word) -> IlTokenKind.LABEL
                        expectOpcode -> IlTokenKind.OPCODE
                        word.startsWith('.') -> if (word == ".ctor" || word == ".cctor") IlTokenKind.IDENTIFIER else IlTokenKind.DIRECTIVE
                        expectType -> IlTokenKind.TYPE_NAME
                        word in KEYWORDS -> IlTokenKind.KEYWORD
                        text.startsWith("::", i) -> IlTokenKind.TYPE_NAME
                        else -> IlTokenKind.IDENTIFIER
                    }
                    result += IlToken(kind, start, i)
                    expectOpcode = false
                    expectType = kind == IlTokenKind.KEYWORD && word in TYPE_INTRODUCERS
                    // `IL_0000:` and an opcode after it
                    if (kind == IlTokenKind.LABEL && i < text.length && text[i] == ':' && !text.startsWith("::", i)) {
                        result += IlToken(IlTokenKind.PUNCTUATION, i, i + 1)
                        i++
                        expectOpcode = true
                    }
                    continue
                }
                else -> {
                    i++
                    result += IlToken(IlTokenKind.PUNCTUATION, start, i)
                }
            }
            expectOpcode = false
            if (result.last().kind != IlTokenKind.TYPE_NAME) expectType = false
        }
        return result
    }

    /** The end of `[System.Runtime]` when a name follows it right away; -1 for `[0]` of `.locals` and the like. */
    private fun assemblyReferenceEnd(text: CharSequence, at: Int): Int {
        var i = at + 1
        while (i < text.length && text[i] != ']' && !text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != ']' || i == at + 1) return -1
        return if (i + 1 < text.length && isWordStart(text, i + 1) && !text[i + 1].isDigit()) i + 1 else -1
    }

    private fun isWordStart(text: CharSequence, i: Int): Boolean {
        val c = text[i]
        return c.isLetter() || c == '_' || c == '$' || c == '\'' || c == '<' && i + 1 < text.length && text[i + 1] == '>' ||
            c == '.' && i + 1 < text.length && text[i + 1].isLetter()
    }

    /** A name of ILAsm: letters, digits, `_ $ @ ? \``, dots (`ldc.i4.s`, `System.Console`), `'quoted <>c'` parts, `Outer/Inner` of a nested type. */
    private fun wordEnd(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' -> {
                    val close = text.indexOf('\'', i + 1).let { if (it < 0 || text.subSequence(i, it).contains('\n')) -1 else it }
                    if (close < 0) return i + 1
                    i = close + 1
                }
                c.isLetterOrDigit() || c in "_\$@?`" -> i++
                c == '.' && i + 1 < text.length && (text[i + 1].isLetterOrDigit() || text[i + 1] == '\'' || text[i + 1] == '_' || text[i + 1].isWhitespace()) -> i++
                c == '.' && i > from -> i++ // the prefixes `constrained.`, `volatile.`
                c == '/' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '\'' || text[i + 1] == '_') -> i++
                else -> return i
            }
        }
        return i
    }

    private fun indexOf(text: CharSequence, what: String, from: Int): Int {
        var i = from
        while (i <= text.length - what.length) {
            if (text.startsWith(what, i)) return i
            i++
        }
        return -1
    }

    private fun CharSequence.startsWith(prefix: String, at: Int): Boolean = at + prefix.length <= length && regionMatches(at, prefix, 0, prefix.length)

    private val LABEL = Regex("IL_[0-9a-fA-F]+")
    private val TYPE_INTRODUCERS = setOf("class", "valuetype", "extends", "implements", "catch", "box", "unbox")

    private val KEYWORDS = setOf(
        "public", "private", "family", "assembly", "famandassem", "famorassem", "privatescope", "static", "instance", "explicit", "hidebysig",
        "specialname", "rtspecialname", "virtual", "final", "newslot", "abstract", "sealed", "strict", "cil", "managed", "native", "runtime",
        "unmanaged", "forwardref", "preservesig", "synchronized", "noinlining", "aggressiveinlining", "nooptimization", "aggressiveoptimization",
        "void", "bool", "char", "int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64", "unsigned", "float32", "float64",
        "string", "object", "int", "uint", "typedref", "class", "valuetype", "extends", "implements", "interface", "auto", "ansi", "unicode",
        "autochar", "sequential", "beforefieldinit", "nested", "serializable", "import", "init", "initonly", "literal", "default", "vararg",
        "pinned", "modreq", "modopt", "marshal", "pinvokeimpl", "cdecl", "stdcall", "thiscall", "fastcall", "catch", "finally", "fault",
        "filter", "handler", "to", "true", "false", "nullref", "bytearray", "reqsecobj", "notserialized", "method", "field", "property", "event",
    )
}

/** [IlLexing] for the editor of the viewer: the whole text at once, as the viewer shows one member at a time. */
class IlLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var tokens: List<IlToken> = emptyList()
    private var index = 0
    private var end = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.end = endOffset
        tokens = IlLexing.tokens(buffer.subSequence(startOffset, endOffset)).map { IlToken(it.kind, it.start + startOffset, it.end + startOffset) }
        index = 0
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokens.getOrNull(index)?.let { IlHighlighter.TYPES.getValue(it.kind) }
    override fun getTokenStart(): Int = tokens.getOrNull(index)?.start ?: end
    override fun getTokenEnd(): Int = tokens.getOrNull(index)?.end ?: end
    override fun advance() {
        index++
    }

    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = end
}

/** Colors of the IL, each falling back on a key of the color scheme, so both themes (and a custom one) color it sensibly. */
class IlHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = IlLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(KEYS[tokenType])

    companion object {
        val COMMENT = createTextAttributesKey("DOTNET_IL_COMMENT", Default.LINE_COMMENT)
        val STRING = createTextAttributesKey("DOTNET_IL_STRING", Default.STRING)
        val NUMBER = createTextAttributesKey("DOTNET_IL_NUMBER", Default.NUMBER)
        val LABEL = createTextAttributesKey("DOTNET_IL_LABEL", Default.LABEL)
        val DIRECTIVE = createTextAttributesKey("DOTNET_IL_DIRECTIVE", Default.METADATA)
        val OPCODE = createTextAttributesKey("DOTNET_IL_OPCODE", Default.KEYWORD)
        val KEYWORD = createTextAttributesKey("DOTNET_IL_KEYWORD", Default.PREDEFINED_SYMBOL)
        val TYPE_NAME = createTextAttributesKey("DOTNET_IL_TYPE_NAME", Default.CLASS_REFERENCE)
        val IDENTIFIER = createTextAttributesKey("DOTNET_IL_IDENTIFIER", Default.IDENTIFIER)
        val PUNCTUATION = createTextAttributesKey("DOTNET_IL_PUNCTUATION", Default.OPERATION_SIGN)

        val TYPES: Map<IlTokenKind, IElementType> = IlTokenKind.entries.associateWith { if (it == IlTokenKind.WHITESPACE) TokenType.WHITE_SPACE else IElementType("IL_$it", null) }

        private val KEYS: Map<IElementType, TextAttributesKey> = mapOf(
            IlTokenKind.COMMENT to COMMENT, IlTokenKind.STRING to STRING, IlTokenKind.NUMBER to NUMBER, IlTokenKind.LABEL to LABEL,
            IlTokenKind.DIRECTIVE to DIRECTIVE, IlTokenKind.OPCODE to OPCODE, IlTokenKind.KEYWORD to KEYWORD, IlTokenKind.TYPE_NAME to TYPE_NAME,
            IlTokenKind.IDENTIFIER to IDENTIFIER, IlTokenKind.PUNCTUATION to PUNCTUATION,
        ).mapKeys { TYPES.getValue(it.key) }
    }
}
