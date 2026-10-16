package io.github.dotnetsupport.frameworks.razor

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.lang.CSharpHighlightingLexer
import io.github.dotnetsupport.lang.CSharpLexer
import io.github.dotnetsupport.lang.CSharpTokenTypes

/**
 * The lexer of Razor (RAZOR_PLAN.md, R1), after the parsers of the Razor compiler (`HtmlMarkupParser`, `CSharpCodeParser` of
 * `Microsoft.AspNetCore.Razor.Language/Legacy`): markup with transitions — `@expr`, `@(…)`, `@{…}`, directives, `@if`/`@foreach`/…,
 * `@* *@`, `@@`, the email `a@b` that is no transition — and C# with markup in it: a tag where a statement starts, `@:`, `@<p>` templates,
 * `<text>`. A tree of its own is R2; this one is good for colours, folding, braces and the HTML of the view.
 *
 * [split] gives the editor's tokens: the C# pieces cut into the tokens of the plugin's C# lexer ([CSharpHighlightingLexer]) instead of
 * one [RazorTokenTypes.CSHARP] / [RazorTokenTypes.CSHARP_CODE] each. The state is 0 only where scanning the text afresh gives the same
 * tokens — markup or a transition at the top level, outside a tag — so the editor restarts it there.
 */
class RazorLexer(private val kind: RazorFileKind, private val split: Boolean = false) : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var scanner: RazorScanner? = null
    private var token: RazorToken? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        scanner = RazorScanner(buffer, startOffset, endOffset, kind, split)
        advance()
    }

    override fun getState(): Int = token?.state ?: 0
    override fun getTokenType(): IElementType? = token?.type
    override fun getTokenStart(): Int = token?.start ?: bufferEnd
    override fun getTokenEnd(): Int = token?.end ?: bufferEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        token = scanner?.next()
    }
}

internal class RazorToken(val type: IElementType, val start: Int, var end: Int, val state: Int)

/**
 * Scans from a top-level position: unit by unit (a run of text, a tag, an HTML comment, a construct with all it holds), so the editor
 * gets the tokens of a big file a part at a time. Everything between two transitions in markup is one [RazorTokenTypes.MARKUP].
 */
internal class RazorScanner(
    private val text: CharSequence, start: Int, private val end: Int, private val kind: RazorFileKind, split: Boolean,
) {
    private val out = ArrayDeque<RazorToken>()
    private var cursor = start
    private var pos = start
    private var pendingType: IElementType? = null
    private var pendingStart = 0
    private var pendingState = 0
    private var nextState = 0
    private val top = Markup(Mode.TOP)
    private val cs = CSharpLexer()
    private val highlighting = if (split) CSharpHighlightingLexer() else null

    fun next(): RazorToken? {
        if (out.isEmpty()) step()
        return out.removeFirstOrNull()
    }

    private fun step() {
        while (pos < end) {
            nextState = if (pendingType == null && top.raw == null) 0 else 1
            pos = unit(pos, top)
            if (out.isNotEmpty()) break
        }
        flush()
    }

    // --- emission: every char from the start to the end is in exactly one token

    /** [cursor] up to [upTo] is of [type]; runs of markup and C# are merged. */
    private fun fill(type: IElementType, upTo: Int) {
        if (upTo <= cursor) return
        if (pendingType != type || type !in MERGED) {
            flush()
            pendingType = type
            pendingStart = cursor
            pendingState = nextState
            nextState = 1
        }
        cursor = upTo
    }

    private fun token(type: IElementType, length: Int) {
        flush()
        val to = minOf(end, cursor + length)
        if (to <= cursor) return
        out.addLast(RazorToken(type, cursor, to, nextState))
        nextState = 1
        cursor = to
    }

    private fun flush() {
        val type = pendingType ?: return
        pendingType = null
        val lexer = highlighting
        if (lexer != null && (type == RazorTokenTypes.CSHARP || type == RazorTokenTypes.CSHARP_CODE)) {
            lexer.start(text, pendingStart, cursor, 0)
            var first = true
            while (lexer.tokenType != null) {
                out.addLast(RazorToken(lexer.tokenType!!, lexer.tokenStart, lexer.tokenEnd, if (first) pendingState else 1))
                first = false
                lexer.advance()
            }
        } else {
            out.addLast(RazorToken(type, pendingStart, cursor, pendingState))
        }
    }

    // --- markup

    private enum class Mode { TOP, SECTION, ELEMENT, LINE }

    /** Where markup is: the open tags of an element in code, the braces of a section, the end tag of a `<script>` being read. */
    private class Markup(val mode: Mode) {
        val stack = ArrayList<String>()
        var braces = 0
        var raw: String? = null
        var done = false
        var textTag = false
        var started = false
    }

    /** Markup in [mode] from [from]: a section's body up to its `}`, a line of `@:`, an element of code with all it holds. */
    private fun markup(from: Int, mode: Mode): Int {
        val context = Markup(mode)
        var p = from
        while (p < end && !context.done) {
            val c = text[p]
            when {
                mode == Mode.LINE && (c == '\n' || c == '\r') -> {
                    p += if (c == '\r' && at(p + 1) == '\n') 2 else 1
                    fill(RazorTokenTypes.MARKUP, p)
                    return p
                }
                mode == Mode.SECTION && c == '}' && context.braces == 0 -> return p
                else -> p = unit(p, context)
            }
        }
        return p
    }

    /** One unit of markup at [p]: a transition, a tag, an HTML comment, a run of text. */
    private fun unit(p: Int, context: Markup): Int {
        val c = text[p]
        if (c == '@') {
            if (isEmail(p)) {
                fill(RazorTokenTypes.MARKUP, p + 1)
                return p + 1
            }
            return transition(p)
        }
        if (c == '<') {
            val raw = context.raw
            if (raw != null) {
                if (!startsWith(raw, p, ignoreCase = true)) {
                    fill(RazorTokenTypes.MARKUP, p + 1)
                    return p + 1
                }
                context.raw = null
                return tag(p, context)
            }
            if (startsWith("<!--", p)) return htmlComment(p, context)
            if (context.mode == Mode.ELEMENT && !context.started && isTextTag(p + 1)) {
                context.started = true
                context.textTag = true
                val to = textTagEnd(p + 5)
                token(RazorTokenTypes.TEXT_TAG, to - p)
                if (text[to - 2] == '/') context.done = true else context.stack += "text"
                return to
            }
            if (context.mode == Mode.ELEMENT && context.textTag && context.stack.size == 1 && at(p + 1) == '/' && isTextTag(p + 2)) {
                context.stack.clear()
                context.done = true
                val to = textTagEnd(p + 6)
                token(RazorTokenTypes.TEXT_TAG, to - p)
                return to
            }
            if (isTagStart(p, lenient = context.mode == Mode.ELEMENT && !context.started)) return tag(p, context)
            fill(RazorTokenTypes.MARKUP, p + 1)
            return p + 1
        }
        if (context.mode == Mode.SECTION && (c == '{' || c == '}')) {
            if (c == '{') context.braces++ else context.braces--
            fill(RazorTokenTypes.MARKUP, p + 1)
            return p + 1
        }
        var q = p + 1
        while (q < end) {
            val d = text[q]
            if (d == '@' || d == '<') break
            if (context.mode == Mode.SECTION && (d == '{' || d == '}')) break
            if (context.mode == Mode.LINE && (d == '\n' || d == '\r')) break
            q++
        }
        fill(RazorTokenTypes.MARKUP, q)
        return q
    }

    /** `<!-- ... -->`, transitions in it as Razor has them (`MarkupCommentBlock`). */
    private fun htmlComment(p: Int, context: Markup): Int {
        val close = indexOf("-->", p + 4)
        val to = if (close < 0) end else close + 3
        markupRange(p, to)
        if (context.mode == Mode.ELEMENT && context.stack.isEmpty()) context.done = true
        context.started = true
        return to
    }

    /** Markup from [p] up to [to] with the transitions in it. */
    private fun markupRange(p: Int, to: Int) {
        var q = p
        while (q < to) {
            if (text[q] == '@' && !isEmail(q)) {
                fill(RazorTokenTypes.MARKUP, q)
                q = transition(q)
            } else {
                q++
                fill(RazorTokenTypes.MARKUP, q)
            }
        }
    }

    /** A start or an end tag at [p]; in an element of code it opens and closes ([Markup.stack]). */
    private fun tag(p: Int, context: Markup): Int {
        context.started = true
        val next = at(p + 1)
        if (next == '!' || next == '?') {
            val close = indexOf(">", p + 2)
            val to = if (close < 0) end else close + 1
            markupRange(p, to)
            if (context.mode == Mode.ELEMENT && context.stack.isEmpty()) context.done = true
            return to
        }
        val isEnd = next == '/'
        var q = p + if (isEnd) 2 else 1
        val nameStart = q
        while (q < end && isTagNameChar(text[q])) q++
        val name = text.subSequence(nameStart, q).toString()
        if (isEnd) {
            while (q < end && text[q] != '>' && text[q] != '<') {
                if (text[q] == '@' && at(q + 1) == '*') {
                    fill(RazorTokenTypes.MARKUP, q)
                    q = razorComment(q)
                } else q++
            }
            if (q < end && text[q] == '>') q++
            fill(RazorTokenTypes.MARKUP, q)
            val open = context.stack.indexOfLast { it.equals(name, ignoreCase = true) }
            if (open >= 0) while (context.stack.size > open) context.stack.removeAt(context.stack.size - 1)
            if (context.mode == Mode.ELEMENT && context.stack.isEmpty()) context.done = true
            return q
        }
        var selfClosing = false
        while (q < end) {
            val c = text[q]
            when {
                c == '>' -> { q++; break }
                c == '/' && at(q + 1) == '>' -> { q += 2; selfClosing = true; break }
                c == '<' -> break
                c.isWhitespace() -> q++
                c == '@' && at(q + 1) == '*' -> {
                    fill(RazorTokenTypes.MARKUP, q)
                    q = razorComment(q)
                }
                c == '@' && kind == RazorFileKind.COMPONENT && at(q + 1) == '@' -> {
                    fill(RazorTokenTypes.MARKUP, q)
                    token(RazorTokenTypes.ESCAPE, 1)
                    q = attribute(q + 1)
                }
                c == '@' && kind == RazorFileKind.COMPONENT -> {
                    fill(RazorTokenTypes.MARKUP, q)
                    q = directiveAttribute(q)
                }
                c == '@' -> {
                    fill(RazorTokenTypes.MARKUP, q)
                    q = transition(q)
                }
                else -> q = attribute(q)
            }
        }
        fill(RazorTokenTypes.MARKUP, q)
        if (!selfClosing) {
            val lower = name.lowercase()
            if (lower == "script" || lower == "style") context.raw = "</$lower"
            if (lower !in VOID) context.stack += name
        }
        if (context.mode == Mode.ELEMENT && context.stack.isEmpty()) context.done = true
        return q
    }

    /** `name`, `name=value`, `name="value"` of a tag: the value may hold transitions. */
    private fun attribute(p: Int): Int {
        var q = p
        while (q < end) {
            val c = text[q]
            if (c.isWhitespace() || c == '=' || c == '>' || c == '<' || c == '"' || c == '\'' || (c == '/' && at(q + 1) == '>')) break
            if (c == '@' && q > p && kind == RazorFileKind.LEGACY) break
            q++
        }
        if (q == p) q++ // a stray quote
        var r = q
        while (r < end && text[r].isWhitespace()) r++
        if (at(r) != '=') {
            fill(RazorTokenTypes.MARKUP, q)
            return q
        }
        r++
        while (r < end && text[r].isWhitespace()) r++
        fill(RazorTokenTypes.MARKUP, r)
        val quote = at(r)
        return if (quote == '"' || quote == '\'') {
            fill(RazorTokenTypes.MARKUP, r + 1)
            attributeValue(r + 1, quote)
        } else attributeValue(r, null)
    }

    /** The value of an attribute after its opening quote (or unquoted), up to and with its closing quote. */
    private fun attributeValue(p: Int, quote: Char?): Int {
        var q = p
        while (q < end) {
            val c = text[q]
            if (quote != null && c == quote) {
                fill(RazorTokenTypes.MARKUP, q + 1)
                return q + 1
            }
            if (quote == null && (c.isWhitespace() || c == '>' || (c == '/' && at(q + 1) == '>'))) break
            if (c == '@' && !isEmail(q)) {
                fill(RazorTokenTypes.MARKUP, q)
                q = transition(q)
                continue
            }
            q++
            fill(RazorTokenTypes.MARKUP, q)
        }
        fill(RazorTokenTypes.MARKUP, q)
        return q
    }

    /**
     * `@onclick="Increment"`, `@bind-Value:after="Save"`, `@key=item`: markup for the compiler until its tag helpers bind them, C# to the
     * eye — the value of the event handlers, of `@bind`, `@ref`, `@key`, `@attributes`; `@formname` and the `:event` / `:format` of a
     * binding are strings.
     */
    private fun directiveAttribute(p: Int): Int {
        var q = p + 1
        while (q < end) {
            val c = text[q]
            if (c.isWhitespace() || c == '=' || c == '>' || c == '<' || c == '"' || c == '\'' || (c == '/' && at(q + 1) == '>')) break
            q++
        }
        val name = text.subSequence(p + 1, q).toString()
        token(RazorTokenTypes.DIRECTIVE_ATTRIBUTE, q - p)
        var r = q
        while (r < end && text[r].isWhitespace()) r++
        if (at(r) != '=') return q
        r++
        while (r < end && text[r].isWhitespace()) r++
        val quote = at(r).takeIf { it == '"' || it == '\'' }
        if (quote != null) r++
        fill(RazorTokenTypes.ATTRIBUTE_VALUE, r)
        val parameter = name.substringAfter(':', "")
        val isString = name.substringBefore(':') == "formname" || parameter == "event" || parameter == "format"
        val valueEnd = if (quote != null) indexOf(quote.toString(), r).let { if (it < 0) end else it } else {
            var e = r
            while (e < end && !text[e].isWhitespace() && text[e] != '>' && !(text[e] == '/' && at(e + 1) == '>')) e++
            e
        }
        if (isString) {
            var s = r
            while (s < valueEnd) {
                if (text[s] == '@' && !isEmail(s)) {
                    fill(RazorTokenTypes.ATTRIBUTE_VALUE, s)
                    s = transition(s)
                } else {
                    s++
                    fill(RazorTokenTypes.ATTRIBUTE_VALUE, s)
                }
            }
        } else if (at(r) == '@' && at(r + 1) != '@') {
            transition(r)
            fill(RazorTokenTypes.CSHARP, valueEnd)
        } else {
            fill(RazorTokenTypes.CSHARP, valueEnd)
        }
        if (quote != null && valueEnd < end) {
            fill(RazorTokenTypes.ATTRIBUTE_VALUE, valueEnd + 1)
            return valueEnd + 1
        }
        return maxOf(valueEnd, cursor)
    }

    /** An element of markup in code at the `<` of [p]: the tags it opens and the content up to the end tag of the first one. */
    private fun markupElement(p: Int): Int {
        if (!isTagStart(p, lenient = true)) return p
        val context = Markup(Mode.ELEMENT)
        var q = p
        while (q < end && !context.done) q = unit(q, context)
        if (!context.textTag) {
            // the spaces and the line break after an element are its markup, as the compiler has them (`CompleteMarkupInCodeBlock`)
            var r = q
            while (r < end && (text[r] == ' ' || text[r] == '\t')) r++
            if (at(r) == '\r') r++
            if (at(r) == '\n') r++
            fill(RazorTokenTypes.MARKUP, r)
            q = maxOf(q, r)
        }
        return q
    }

    // --- transitions

    private fun transition(p: Int): Int {
        val next = at(p + 1)
        return when {
            next == '*' -> razorComment(p)
            next == '@' -> {
                token(RazorTokenTypes.ESCAPE, 1)
                fill(RazorTokenTypes.MARKUP, p + 2)
                p + 2
            }
            next == '(' -> {
                token(RazorTokenTypes.TRANSITION, 1)
                token(RazorTokenTypes.LPAREN, 1)
                closing(balanced(p + 2, '(', ')'), ')', RazorTokenTypes.RPAREN)
            }
            next == '{' -> {
                token(RazorTokenTypes.TRANSITION, 1)
                token(RazorTokenTypes.LBRACE, 1)
                closing(code(p + 2, block = true), '}', RazorTokenTypes.RBRACE)
            }
            isIdentifierStart(next) -> {
                val nameEnd = identifierEnd(p + 1)
                val name = text.subSequence(p + 1, nameEnd).toString()
                when {
                    // `@using Foo` is C# to the compiler (a Code span from the keyword on), `@using (…) { }` a statement
                    name == "using" && at(skipSpaces(nameEnd)) != '(' -> {
                        token(RazorTokenTypes.TRANSITION, 1)
                        val valueEnd = lineEndTrimmed(p + 1)
                        fill(RazorTokenTypes.CSHARP, valueEnd)
                        valueEnd
                    }
                    name in kind.directives && name != "using" -> directive(p, name)
                    name in STATEMENTS -> {
                        token(RazorTokenTypes.TRANSITION, 1)
                        statement(p + 1, name)
                    }
                    else -> {
                        token(RazorTokenTypes.TRANSITION, 1)
                        implicitExpression(p + 1, async = name == "await")
                    }
                }
            }
            else -> {
                token(RazorTokenTypes.TRANSITION, 1)
                p + 1
            }
        }
    }

    private fun closing(p: Int, c: Char, type: IElementType): Int {
        if (at(p) != c || p >= end) return p
        token(type, 1)
        return p + 1
    }

    private fun razorComment(p: Int): Int {
        val close = indexOf("*@", p + 2)
        val to = if (close < 0) end else close + 2
        token(RazorTokenTypes.COMMENT, to - p)
        return to
    }

    /** `@page "/x"`, `@inject IFoo Foo`: the rest of the line; `@code { }`, `@section Name { }`: the block. */
    private fun directive(p: Int, name: String): Int {
        token(RazorTokenTypes.TRANSITION, 1)
        token(RazorTokenTypes.DIRECTIVE, name.length)
        val q = p + 1 + name.length
        when (name) {
            "code", "functions" -> {
                val brace = skipWhitespace(q)
                if (at(brace) != '{') return q
                fill(TokenType.WHITE_SPACE, brace)
                token(RazorTokenTypes.LBRACE, 1)
                return closing(code(brace + 1, block = true), '}', RazorTokenTypes.RBRACE)
            }
            "section" -> {
                val nameStart = skipSpaces(q)
                if (!isIdentifierStart(at(nameStart))) return q
                val nameEnd = identifierEnd(nameStart)
                fill(RazorTokenTypes.CSHARP, nameEnd)
                val brace = skipWhitespace(nameEnd)
                if (at(brace) != '{') return nameEnd
                fill(TokenType.WHITE_SPACE, brace)
                token(RazorTokenTypes.LBRACE, 1)
                return closing(markup(brace + 1, Mode.SECTION), '}', RazorTokenTypes.RBRACE)
            }
            "addTagHelper", "removeTagHelper", "tagHelperPrefix" -> {
                val valueStart = skipSpaces(q)
                val valueEnd = lineEndTrimmed(valueStart)
                if (valueEnd <= valueStart) return q
                fill(TokenType.WHITE_SPACE, valueStart)
                fill(RazorTokenTypes.DIRECTIVE_VALUE, valueEnd)
                return valueEnd
            }
            else -> {
                val valueEnd = lineEndTrimmed(q)
                if (valueEnd <= skipSpaces(q)) return q
                fill(RazorTokenTypes.CSHARP, valueEnd)
                return valueEnd
            }
        }
    }

    /** `@Model.Items[0].Name`, `@Foo(1)?.Bar!`, `@await LoadAsync()`: identifiers, calls and indexes, as `ParseImplicitExpression`. */
    private fun implicitExpression(p: Int, async: Boolean): Int {
        var q = identifierEnd(p)
        if (async) {
            val r = skipSpaces(q)
            if (isIdentifierStart(at(r))) q = identifierEnd(r)
        }
        while (q < end) {
            val c = text[q]
            val n = at(q + 1)
            q = when {
                c == '(' || c == '[' -> {
                    val close = if (c == '(') ')' else ']'
                    fill(RazorTokenTypes.CSHARP, q + 1)
                    val r = balanced(q + 1, c, close)
                    if (at(r) != close || r >= end) {
                        q = r
                        break
                    }
                    r + 1
                }
                // `?.` is taken even when no name follows (`@val?.`), unlike `.` (`ParseMethodCallOrArrayIndex`)
                c == '?' && n == '.' -> if (isIdentifierStart(at(q + 2))) identifierEnd(q + 2) else {
                    q += 2
                    break
                }
                c == '?' && n == '[' -> q + 1
                c == '!' && n == '.' && isIdentifierStart(at(q + 2)) -> identifierEnd(q + 2)
                c == '!' && (n == '[' || n == '(' || n == '?') -> q + 1
                c == '.' && isIdentifierStart(n) -> identifierEnd(q + 1)
                else -> break
            }
        }
        fill(RazorTokenTypes.CSHARP, q)
        return q
    }

    /**
     * C# from [p] up to the [close] that balances [open] (not taken): `@( … )`, the arguments and indexes of an implicit expression. When
     * none does, the compiler takes the C# up to the next `<` (`AcceptUntil(LessThan)`).
     */
    private fun balanced(p: Int, open: Char, close: Char): Int {
        val closeAt = findClosing(p, open, close)
        if (closeAt < 0) {
            val stop = indexOf("<", p).let { if (it < 0) end else it }
            fill(RazorTokenTypes.CSHARP, stop)
            return stop
        }
        var q = p
        while (q < closeAt) {
            if (text[q] == '@' && at(q + 1) == '<') {
                fill(RazorTokenTypes.CSHARP, q)
                token(RazorTokenTypes.TRANSITION, 1)
                q = markupElement(q + 1).coerceAtLeast(q + 1)
                continue
            }
            if (text[q] == '@' && at(q + 1) == '*') {
                fill(RazorTokenTypes.CSHARP, q)
                q = razorComment(q)
                continue
            }
            if (text[q] == '@' && at(q + 1) == ':') {
                fill(RazorTokenTypes.CSHARP, q)
                token(RazorTokenTypes.TRANSITION, 1)
                token(RazorTokenTypes.COLON, 1)
                q = markup(q + 2, Mode.LINE)
                continue
            }
            q = csTokenEnd(q)
        }
        fill(RazorTokenTypes.CSHARP, closeAt)
        return maxOf(closeAt, cursor)
    }

    private fun findClosing(p: Int, open: Char, close: Char): Int {
        var depth = 0
        var q = p
        while (q < end) {
            val c = text[q]
            val e = csTokenEnd(q)
            if (e == q + 1 && cs.tokenType != CSharpTokenTypes.STRING && cs.tokenType != CSharpTokenTypes.CHAR) {
                if (c == open) depth++
                if (c == close) {
                    if (depth == 0) return q
                    depth--
                }
            }
            q = e
        }
        return -1
    }

    /** `@if (…) { … } else { … }`, `@foreach`, `@try … catch … finally`, `@do … while (…);`, `@using (…) { … }` from the keyword at [p]. */
    private fun statement(p: Int, keyword: String): Int {
        val header = skipWhitespace(identifierEnd(p))
        if (at(header) == '(' && findClosing(header + 1, '(', ')') < 0) {
            val stop = indexOf("<", p).let { if (it < 0) end else it }
            fill(RazorTokenTypes.CSHARP_CODE, stop)
            return stop
        }
        return code(p, block = false, keyword = keyword)
    }

    /**
     * Statements (or members of `@code`) from [p]: the C# with the markup that starts where a statement does (`<p>`, `@:`), templates
     * `@<p>…</p>`, comments, nested transitions. A [block] ends at its unbalanced `}` (not taken); a statement after its last block, unless
     * an `else`, `catch`, `finally` (or the `while` of a `do`) goes on.
     */
    private fun code(p: Int, block: Boolean, keyword: String? = null): Int {
        val type = RazorTokenTypes.CSHARP_CODE
        val stack = StringBuilder()
        var parens = 0
        var statementStart = block
        var caseLabel = -1 // the depth of the `case` whose `:` is to come
        var q = p
        while (q < end) {
            val c = text[q]
            if (c == '@') {
                val n = at(q + 1)
                when {
                    n == '*' -> {
                        fill(type, q)
                        q = razorComment(q)
                        continue
                    }
                    n == '<' -> {
                        fill(type, q)
                        token(RazorTokenTypes.TRANSITION, 1)
                        q = markupElement(q + 1)
                        statementStart = false
                        continue
                    }
                    n == ':' && parens == 0 -> {
                        fill(type, q)
                        token(RazorTokenTypes.TRANSITION, 1)
                        token(RazorTokenTypes.COLON, 1)
                        q = markup(q + 2, Mode.LINE)
                        statementStart = true
                        continue
                    }
                    statementStart && parens == 0 && (isIdentifierStart(n) || n == '(' || n == '{' || n == '\u0000' || n.isWhitespace()) -> {
                        fill(type, q)
                        q = transition(q)
                        statementStart = true
                        continue
                    }
                }
            }
            if (c == '<' && parens == 0) {
                // where a statement starts any `<` is markup, a tag or not (`CSharpCodeParser.ParseStatement`)
                if (statementStart && isTagStart(q, lenient = true)) {
                    fill(type, q)
                    q = markupElement(q)
                    statementStart = true
                    continue
                }
                if (!block && stack.isEmpty() && isTagStart(q)) break
            }
            val e = csTokenEnd(q)
            val csType = cs.tokenType
            when {
                csType == TokenType.WHITE_SPACE || csType in CSharpTokenTypes.COMMENTS || csType == CSharpTokenTypes.PREPROCESSOR -> {}
                csType == CSharpTokenTypes.LBRACE -> {
                    stack.append('{')
                    statementStart = true
                }
                csType == CSharpTokenTypes.LPAREN || csType == CSharpTokenTypes.LBRACKET -> {
                    if (findClosing(e, c, if (c == '(') ')' else ']') < 0) {
                        // `string.Format(<p>…`: the compiler gives up the parenthesis and goes on at the markup (`TryBalanceBlock`)
                        statementStart = true
                    } else {
                        stack.append(c)
                        parens++
                        statementStart = false
                    }
                }
                csType == CSharpTokenTypes.RPAREN || csType == CSharpTokenTypes.RBRACKET -> {
                    val open = if (c == ')') '(' else '['
                    if (stack.isEmpty() && !block) {
                        // `@if (a)) { … }`: the statement ends at the parenthesis too many
                        fill(type, q)
                        return q
                    }
                    if (stack.isNotEmpty() && stack.last() == open) {
                        stack.setLength(stack.length - 1)
                        parens--
                    }
                    statementStart = false
                }
                csType == CSharpTokenTypes.RBRACE -> {
                    if (stack.isEmpty()) {
                        fill(type, q)
                        return q
                    }
                    while (stack.isNotEmpty() && stack.last() != '{') {
                        stack.setLength(stack.length - 1)
                        parens--
                    }
                    if (stack.isNotEmpty()) stack.setLength(stack.length - 1)
                    statementStart = true
                    if (!block && stack.isEmpty()) {
                        val r = skipWhitespaceAndComments(e)
                        val word = if (isIdentifierStart(at(r))) text.subSequence(r, identifierEnd(r)).toString() else ""
                        if (!(word == "else" || word == "catch" || word == "finally" || (word == "while" && keyword == "do"))) {
                            fill(type, e)
                            return e
                        }
                        statementStart = false
                    }
                }
                csType == CSharpTokenTypes.SEMICOLON -> {
                    statementStart = parens == 0
                    caseLabel = -1
                    if (!block && stack.isEmpty()) {
                        fill(type, e)
                        return e
                    }
                }
                csType == CSharpTokenTypes.KEYWORD -> {
                    val word = text.subSequence(q, e)
                    if (statementStart && (word == "case" || word == "default")) caseLabel = stack.length
                    statementStart = false
                }
                csType == CSharpTokenTypes.OPERATOR && c == ':' && at(q + 1) == ':' -> {
                    statementStart = false
                    q = e + 1
                    continue
                }
                // the `:` of `case X:` (not one of a pattern in it, `case { Year: 2022 }:`): a statement starts after it
                csType == CSharpTokenTypes.OPERATOR && c == ':' && caseLabel == stack.length -> {
                    statementStart = true
                    caseLabel = -1
                }
                else -> statementStart = false
            }
            q = e
        }
        fill(type, q)
        return q
    }

    // --- characters

    /** The end of the C# token at [q] (its type in [cs]). */
    private fun csTokenEnd(q: Int): Int {
        cs.start(text, q, end, 0)
        return cs.tokenEnd.coerceAtLeast(q + 1)
    }

    /** `name@host`: a letter or digit on both sides of the `@` keeps it text (`HtmlTokenizer.Text`). */
    private fun isEmail(p: Int): Boolean =
        p > 0 && text[p - 1].let { it.isLetter() || it.isDigit() } && at(p + 1).let { it.isLetter() || it.isDigit() }

    /** `<div`, `</div`, `<!--`, `<!DOCTYPE`; [lenient] — where the compiler takes any `<` but one before a space for a tag (`<>`, `<1-x>`). */
    private fun isTagStart(p: Int, lenient: Boolean = false): Boolean {
        if (at(p) != '<') return false
        val n = at(p + 1)
        if (lenient) return n != '\u0000' && !n.isWhitespace() && n != '<' && n != '='
        return n.isLetter() || n == '!' || n == '?' || (n == '/' && (at(p + 2).isLetter() || at(p + 2) == '>'))
    }

    /** `text` of `<text>`, `<text/>`, `<text foo>` at [p]. */
    private fun isTextTag(p: Int): Boolean = startsWith("text", p) && at(p + 4).let { it == '>' || it == '/' || it.isWhitespace() }

    private fun textTagEnd(p: Int): Int = indexOf(">", p).let { if (it < 0) end else it + 1 }

    private fun isTagNameChar(c: Char): Boolean = !c.isWhitespace() && c != '>' && c != '/' && c != '<' && c != '@' && c != '"' && c != '\'' && c != '='

    private fun at(p: Int): Char = if (p in 0 until end) text[p] else '\u0000'

    private fun startsWith(prefix: String, p: Int, ignoreCase: Boolean = false): Boolean =
        p + prefix.length <= end && text.regionMatches(p, prefix, 0, prefix.length, ignoreCase)

    private fun indexOf(s: String, from: Int): Int {
        var i = from
        while (i + s.length <= end) {
            if (startsWith(s, i)) return i
            i++
        }
        return -1
    }

    private fun identifierEnd(p: Int): Int {
        var q = p
        while (q < end && isIdentifierPart(text[q])) q++
        return q
    }

    /** A letter, a digit, `_`, a combining mark or a formatting character, as C# identifiers have them. */
    private fun isIdentifierPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit() || when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.CONNECTOR_PUNCTUATION, Character.FORMAT -> true
        else -> false
    }

    private fun skipSpaces(p: Int): Int {
        var q = p
        while (q < end && (text[q] == ' ' || text[q] == '\t')) q++
        return q
    }

    private fun skipWhitespace(p: Int): Int {
        var q = p
        while (q < end && text[q].isWhitespace()) q++
        return q
    }

    private fun skipWhitespaceAndComments(p: Int): Int {
        var q = skipWhitespace(p)
        // comments and preprocessor lines between a block and its `else` (`IsSpacingTokenIncludingNewLinesAndCommentsAndCSharpDirectives`)
        while (q < end && ((text[q] == '/' && (at(q + 1) == '/' || at(q + 1) == '*')) || text[q] == '#' || (text[q] == '@' && at(q + 1) == '*'))) {
            q = skipWhitespace(
                when (text[q]) {
                    '#' -> lineEndTrimmed(q).coerceAtLeast(q + 1)
                    '@' -> indexOf("*@", q + 2).let { if (it < 0) end else it + 2 }
                    else -> csTokenEnd(q)
                },
            )
        }
        return q
    }

    private fun lineEndTrimmed(p: Int): Int {
        var q = p
        while (q < end && text[q] != '\n' && text[q] != '\r') q++
        while (q > p && (text[q - 1] == ' ' || text[q - 1] == '\t')) q--
        return q
    }

    companion object {
        /** The keywords of `CSharpCodeParser` that start a statement after `@` (`using` only with a `(`: else it is the directive). */
        val STATEMENTS = setOf("if", "for", "foreach", "while", "switch", "lock", "try", "do", "using")

        /** Elements without content (`HtmlMarkupParser`'s void elements): they open nothing. */
        val VOID = setOf("area", "base", "br", "col", "command", "embed", "hr", "img", "input", "keygen", "link", "meta", "param", "source", "track", "wbr")

        private val MERGED = setOf(RazorTokenTypes.MARKUP, RazorTokenTypes.CSHARP, RazorTokenTypes.CSHARP_CODE, RazorTokenTypes.ATTRIBUTE_VALUE)

        fun isIdentifierStart(c: Char): Boolean = c == '_' || c.isLetter()
    }
}
