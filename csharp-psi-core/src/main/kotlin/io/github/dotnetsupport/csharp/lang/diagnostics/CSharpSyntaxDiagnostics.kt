package io.github.dotnetsupport.csharp.lang.diagnostics

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.TokenType
import io.github.dotnetsupport.csharp.lang.CSharpDocCommentElementType
import io.github.dotnetsupport.csharp.lang.CSharpMissingTokenType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexerDiagnostics
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLiteralScanner
import io.github.dotnetsupport.csharp.lang.lexer.CSharpPreprocessor
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes
import io.github.dotnetsupport.csharp.lang.lexer.DirectiveStack

/** A syntax diagnostic of Roslyn: [code] over `[start, end)` of the file text (`end == start` for a zero-width one). */
data class CSharpSyntaxDiagnostic(val code: CSharpErrorCode, val start: Int, val end: Int, val message: String) {
    /** `CS1002` */
    val id: String get() = code.id

    val isWarning: Boolean get() = code.isWarning

    /** `CS1002: ; expected`, as Rider and the compiler show it. */
    val text: String get() = "$id: $message"
}

/**
 * Roslyn's syntax diagnostics of a parsed file (`SyntaxTree.GetDiagnostics()` without the semantic ones): codes, messages and
 * spans as `roslyndump` prints them (`CSharpSyntaxDiagnosticsTest`). The parser keeps a diagnostic in the description of an
 * error element ([CSharpErrorCode.describe], [CSharpDiagnosticAnchor] for where it goes); this pass turns the elements into
 * spans by Roslyn's rules (a missing token: `GetDiagnosticSpanForMissingNodeOrToken`), adds the diagnostics of the lexer
 * ([CSharpLexerDiagnostics], unterminated comments) and of the directives (the preprocessor run again with [symbols], the ones
 * the file was lexed with). Diagnostics Roslyn only counts in the port (`addError` on a built node) are not reported:
 * docs/csharp-psi/GRAMMAR.md, "Diagnostics".
 */
object CSharpSyntaxDiagnostics {
    private val DESCRIPTION = Regex("""^CS(\d{4})(?:@(\w+))?: (.*)$""", RegexOption.DOT_MATCHES_ALL)

    fun of(file: ASTNode, symbols: Set<String> = emptySet()): List<CSharpSyntaxDiagnostic> {
        val walk = Walk(file.chars)
        walk.visit(file)
        val out = ArrayList<CSharpSyntaxDiagnostic>()
        for (e in walk.errors) walk.place(e, out)
        walk.lexerDiagnostics(out)
        walk.directiveDiagnostics(symbols, out)
        return out.distinctBy { Triple(it.code, it.start, it.end) }.sortedWith(compareBy({ it.start }, { it.end }))
    }

    /** A token of the parse (not trivia), with what follows it up to the next token. */
    private class Leaf(val start: Int, val end: Int, val node: ASTNode) {
        /** The trailing trivia of the token (through the first new line) holds a new line. */
        var newLineAfter = false

        /** The end of the trailing trivia: where a token after this one would start its leading trivia. */
        var trailingEnd = end
        var trailingOpen = true
    }

    private class ErrorAt(val element: ASTNode, val description: String, val before: Int, var after: Int = before)

    private class Walk(val text: CharSequence) {
        val leaves = ArrayList<Leaf>()
        val errors = ArrayList<ErrorAt>()
        val comments = ArrayList<ASTNode>()
        val hashes = ArrayList<Int>()

        /** Pre-order over [root] without recursion (deep expression trees); doc comments are not parsed. */
        fun visit(root: ASTNode) {
            val open = java.util.IdentityHashMap<ASTNode, ErrorAt>()
            var node: ASTNode? = root.firstChildNode
            while (node != null) {
                val type = node.elementType
                var descend = false
                when {
                    type is CSharpDocCommentElementType -> trivia(node)
                    type === TokenType.ERROR_ELEMENT -> {
                        val e = ErrorAt(node, (node.psi as? PsiErrorElement)?.errorDescription ?: "", leaves.size)
                        errors += e
                        if (node.firstChildNode != null) {
                            open[node] = e
                            descend = true
                        }
                    }
                    node.firstChildNode == null -> if (node.textLength > 0) leaf(node)
                    else -> descend = true
                }
                if (descend) {
                    node = node.firstChildNode
                    continue
                }
                while (true) {
                    val next = node!!.treeNext
                    if (next != null) {
                        node = next
                        break
                    }
                    val parent = node.treeParent
                    if (parent == null || parent === root) {
                        node = null
                        break
                    }
                    open.remove(parent)?.after = leaves.size
                    node = parent
                }
            }
        }

        private fun leaf(n: ASTNode) {
            val type = n.elementType
            if (type === TokenType.WHITE_SPACE || type in CSharpTokenTypes.WHITESPACES || type in CSharpTokenTypes.COMMENTS) {
                if (type === CSharpTokenTypes.directive(SyntaxKind.HashToken)) hashes += n.startOffset
                trivia(n)
                return
            }
            leaves += Leaf(n.startOffset, n.startOffset + n.textLength, n)
        }

        private fun trivia(n: ASTNode) {
            val type = n.elementType
            if (type === SyntaxKind.MultiLineCommentTrivia || type === SyntaxKind.SingleLineCommentTrivia) comments += n
            val last = leaves.lastOrNull() ?: return
            if (!last.trailingOpen) return
            when (type) {
                // the tree keeps a run of whitespace and new lines as WHITE_SPACE leaves (PsiBuilder), not as the lexer's kinds
                TokenType.WHITE_SPACE, SyntaxKind.WhitespaceTrivia, SyntaxKind.EndOfLineTrivia -> {
                    val chars = n.chars
                    val newLine = (0 until chars.length).firstOrNull { CSharpLiteralScanner.isNewLine(chars[it]) }
                    if (newLine == null) {
                        last.trailingEnd = n.startOffset + n.textLength
                    } else {
                        val width = if (chars[newLine] == '\r' && newLine + 1 < chars.length && chars[newLine + 1] == '\n') 2 else 1
                        last.newLineAfter = true
                        last.trailingEnd = n.startOffset + newLine + width
                        last.trailingOpen = false
                    }
                }
                SyntaxKind.SingleLineCommentTrivia, SyntaxKind.MultiLineCommentTrivia -> last.trailingEnd = n.startOffset + n.textLength
                else -> last.trailingOpen = false
            }
        }

        // ---- error elements ----

        fun place(e: ErrorAt, out: MutableList<CSharpSyntaxDiagnostic>) {
            val match = DESCRIPTION.matchEntire(e.description) ?: return
            val code = CSharpErrorCode.of(match.groupValues[1].toInt()) ?: return
            val message = match.groupValues[3]
            fun add(start: Int, end: Int) {
                out += CSharpSyntaxDiagnostic(code, start, end, message)
            }
            fun addSpan(range: IntRange?) {
                if (range != null) add(range.first, range.last)
            }
            val inside = e.before until e.after
            when (CSharpDiagnosticAnchor.of(match.groups[2]?.value)) {
                CSharpDiagnosticAnchor.ELEMENT -> when {
                    e.element.treeParent?.elementType is CSharpMissingTokenType -> addSpan(missing(e.before))
                    inside.isEmpty() -> addSpan(token(e.before))
                    else -> add(leaves[inside.first].start, leaves[inside.last].end)
                }
                CSharpDiagnosticAnchor.FIRST -> addSpan(if (inside.isEmpty()) token(e.before) else token(inside.first))
                CSharpDiagnosticAnchor.SECOND -> addSpan(if (inside.count() >= 2) token(inside.first + 1) else token(e.before))
                CSharpDiagnosticAnchor.FIRST_EXPECTED -> addSpan(missing(e.before))
                CSharpDiagnosticAnchor.EACH_EXPECTED -> if (inside.isEmpty()) addSpan(missing(e.before)) else for (i in inside) addSpan(missing(i))
                CSharpDiagnosticAnchor.NEXT -> addSpan(token(e.after))
                CSharpDiagnosticAnchor.PREVIOUS -> addSpan(token(e.before - 1))
                CSharpDiagnosticAnchor.PREVIOUS_START -> leaves.getOrNull(e.before - 1)?.let { add(it.start, it.start) }
                CSharpDiagnosticAnchor.HERE -> {
                    val at = leaves.getOrNull(e.before - 1)?.trailingEnd ?: 0
                    add(at, at)
                }
                CSharpDiagnosticAnchor.PREVIOUS_SIBLING -> {
                    var sibling = e.element.treePrev
                    while (sibling != null && (sibling.textLength == 0 || isTrivia(sibling))) sibling = sibling.treePrev
                    if (sibling == null) {
                        add(leaves.getOrNull(e.before)?.start ?: text.length, leaves.getOrNull(e.after - 1)?.end ?: text.length)
                    } else {
                        val range = significantRange(sibling)
                        add(range.first, range.last)
                    }
                }
            }
        }

        private fun isTrivia(n: ASTNode): Boolean =
            n.elementType === TokenType.WHITE_SPACE || n.elementType in CSharpTokenTypes.WHITESPACES || n.elementType in CSharpTokenTypes.COMMENTS || n.elementType is CSharpDocCommentElementType

        /** The span of [node] without its leading and trailing trivia. */
        private fun significantRange(node: ASTNode): IntRange {
            val from = leaves.binarySearchBy(node.startOffset) { it.start }.let { if (it < 0) -it - 1 else it }
            val endOffset = node.startOffset + node.textLength
            var last = from
            while (last < leaves.size && leaves[last].end <= endOffset) last++
            if (last == from) return node.startOffset..endOffset
            return leaves[from].start..leaves[last - 1].end
        }

        /** The span of token [i] (the end of the file after the last one) as `start..end`. */
        private fun token(i: Int): IntRange? = when {
            i < 0 -> null
            i >= leaves.size -> text.length..text.length
            else -> leaves[i].start..leaves[i].end
        }

        /**
         * `GetDiagnosticSpanForMissingNodeOrToken` for a missing token before token [i]: zero-width at the end of the token
         * before when a new line ends its trailing trivia, else on token [i] (zero-width at the end of the file).
         */
        private fun missing(i: Int): IntRange? {
            val prev = leaves.getOrNull(i - 1)
            if (prev != null && prev.newLineAfter) return prev.end..prev.end
            // an interpolation hole is parsed by a parser of its own: its end is the end of file there
            val next = leaves.getOrNull(i)
            if (next != null && next.node.elementType === SyntaxKind.CloseBraceToken && next.node.treeParent?.elementType === SyntaxKind.Interpolation) {
                return next.start..next.start
            }
            return token(i)
        }

        // ---- lexer ----

        fun lexerDiagnostics(out: MutableList<CSharpSyntaxDiagnostic>) {
            for (leaf in leaves) {
                val type = leaf.node.elementType
                for (d in CSharpLexerDiagnostics.of(type, text.subSequence(leaf.start, leaf.end))) {
                    out += CSharpSyntaxDiagnostic(d.code, leaf.start + d.offset, leaf.start + d.offset + d.width, d.code.message(*d.args.toTypedArray()))
                }
            }
            for (c in comments) {
                // Lexer.LexSyntaxTrivia: ERR_OpenEndedComment at the start of a `/*` comment that does not end
                if (c.elementType !== SyntaxKind.MultiLineCommentTrivia) continue
                val chars = c.chars
                if (chars.length < 4 || chars[chars.length - 2] != '*' || chars[chars.length - 1] != '/') {
                    out += CSharpSyntaxDiagnostic(CSharpErrorCode.ERR_OpenEndedComment, c.startOffset, c.startOffset, CSharpErrorCode.ERR_OpenEndedComment.message())
                }
            }
        }

        // ---- directives ----

        fun directiveDiagnostics(symbols: Set<String>, out: MutableList<CSharpSyntaxDiagnostic>) {
            if (hashes.isEmpty()) return
            val collected = ArrayList<io.github.dotnetsupport.csharp.lang.lexer.CSharpLexerDiagnostic>()
            var stack = DirectiveStack.EMPTY
            var done = 0
            val firstToken = leaves.firstOrNull()?.start ?: Int.MAX_VALUE
            for (hash in hashes) {
                if (hash < done) continue // a directive in the excluded text of one before
                val preprocessor = CSharpPreprocessor(text, text.length, symbols, stack) { _, _, _, _ -> }
                preprocessor.diagnostics = collected
                preprocessor.firstTokenStart = firstToken
                if (onlyWhitespaceBefore(hash)) {
                    preprocessor.lexDirectiveAndExcludedTrivia(hash)
                    stack = preprocessor.stack
                } else {
                    preprocessor.lexMisplacedDirective(hash)
                }
                done = preprocessor.pos
            }
            for (d in collected) out += CSharpSyntaxDiagnostic(d.code, d.offset, d.offset + d.width, d.code.message(*d.args.toTypedArray()))
            // Lexer.LexToken at the end of the file
            if (stack.hasUnfinishedIf()) out += CSharpSyntaxDiagnostic(CSharpErrorCode.ERR_EndifDirectiveExpected, text.length, text.length, CSharpErrorCode.ERR_EndifDirectiveExpected.message())
            if (stack.hasUnfinishedRegion()) out += CSharpSyntaxDiagnostic(CSharpErrorCode.ERR_EndRegionDirectiveExpected, text.length, text.length, CSharpErrorCode.ERR_EndRegionDirectiveExpected.message())
        }

        /** The `#` starts a directive (Roslyn's `onlyWhitespaceOnLine` in leading trivia), not a misplaced one. */
        private fun onlyWhitespaceBefore(hash: Int): Boolean {
            var i = hash - 1
            while (i >= 0 && CSharpLiteralScanner.isWhitespace(text[i])) i--
            return i < 0 || CSharpLiteralScanner.isNewLine(text[i])
        }
    }
}
