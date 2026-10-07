package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.CSharpMissingTokenType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * The typing assistance of the editor on csharp-psi's tree (CSHARP_PSI_MIGRATION.md, step 9, feature `EDITING`): Extend Selection
 * ([NativeCSharpSelection]), Complete Statement ([NativeCSharpCompleteStatement]) and the gray `;` of [CSharpGhostText.semicolon]
 * ([NativeCSharpGhostText]). Used when the switch is NATIVE and the file has the native tree ([usable]); otherwise the token versions answer.
 */
object NativeCSharpEditing {
    /** The switch says NATIVE and [file] is of the native tree (a file parsed under the other switch keeps its tree for a while). */
    fun usable(file: PsiFile?): Boolean =
        // the tree is PSI: the inline completion handler asks on the EDT without read access (seen live, 2026.1 has no implicit read access there)
        file is CSharpFile && read { file.compilationUnit != null } && CSharpFeatures.native(CSharpFeature.EDITING, file.project)

    internal fun <T> read(compute: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) compute() else ReadAction.compute<T, RuntimeException>(compute)

    /** A token the parser put in for a missing one: an empty composite whose type names the token kind. */
    internal fun missingKind(element: PsiElement): com.intellij.psi.tree.IElementType? = (element.elementType as? CSharpMissingTokenType)?.tokenKind

    /** The first and the last leaf of [node] that are code: no whitespace, no comment, nothing empty. */
    internal fun codeRange(node: PsiElement): TextRange? {
        val range = node.textRange
        var first: PsiElement? = PsiTreeUtil.getDeepestFirst(node)
        while (first != null && first.textRange.startOffset < range.endOffset && !isCode(first)) first = PsiTreeUtil.nextLeaf(first)
        var last: PsiElement? = PsiTreeUtil.getDeepestLast(node)
        while (last != null && last.textRange.endOffset > range.startOffset && !isCode(last)) last = PsiTreeUtil.prevLeaf(last)
        if (first == null || last == null) return null
        val start = first.textRange.startOffset
        val end = last.textRange.endOffset
        return if (start < range.startOffset || end > range.endOffset || start >= end) null else TextRange(start, end)
    }

    internal fun isCode(leaf: PsiElement): Boolean = leaf.textLength > 0 && !CSharpLeaves.isTrivia(leaf)

    /**
     * Whether [node] or a node around it has its `{` and lost its `}`: the parser then gave the braces that follow to the wrong owners (an
     * initializer took the `}` of the method, the method the one of the class), nothing there is to be trusted. A block with neither is a body
     * not written yet.
     */
    internal fun structureShifted(node: PsiElement): Boolean {
        var current: PsiElement? = node
        while (current != null && current !is PsiFile) {
            val children = generateSequence(current.firstChild) { it.nextSibling }.toList()
            if (children.any { missingKind(it) === SyntaxKind.CloseBraceToken } && children.any { CSharpLeaves.isPunctuation(it, "{") }) return true
            current = current.parent
        }
        return false
    }

    /** An error that is more than a missing token: skipped tokens, which complete statement and the gray `;` keep away from. */
    internal fun hasSkippedTokens(node: PsiElement): Boolean = PsiTreeUtil.findChildrenOfType(node, PsiErrorElement::class.java).any { it.textLength > 0 }

    internal val STRING_TOKENS = setOf(
        SyntaxKind.StringLiteralToken, SyntaxKind.CharacterLiteralToken, SyntaxKind.SingleLineRawStringLiteralToken, SyntaxKind.MultiLineRawStringLiteralToken,
        SyntaxKind.Utf8StringLiteralToken, SyntaxKind.Utf8SingleLineRawStringLiteralToken, SyntaxKind.Utf8MultiLineRawStringLiteralToken,
    )

    /** The text of a string / char literal token without its quotes (`@"`, `"""`, `u8`), or null when it is unterminated. */
    internal fun literalContent(token: PsiElement): TextRange? {
        val text = token.text
        val start = token.textRange.startOffset
        val body = text.removeSuffix("u8").removeSuffix("U8")
        val quote = if (token.elementType === SyntaxKind.CharacterLiteralToken) '\'' else '"'
        val open = body.indexOf(quote).takeIf { it >= 0 } ?: return null
        val count = body.substring(open).takeWhile { it == quote }.length.let { if (quote == '"' && it >= 3) it else 1 }
        val close = body.length - count
        if (close < open + count || body.substring(close) != quote.toString().repeat(count)) return null
        if (count == 1 && body.length < open + 2) return null
        return TextRange(start + open + count, start + close)
    }
}

/**
 * Extend Selection (Ctrl+W) on the tree: every node around the caret, trimmed to its code, from the name to the member access, the
 * invocation, the argument, the argument list without and with its parentheses, the expression, the statement, the contents of the block
 * and the block, the member (with and without its doc comment), the body of the type and the type, as in Rider. Inside a string the text
 * without the quotes comes before the literal, inside an interpolated string the hole and the text between `$"` and `"`. The token version
 * ([CSharpSelection]) knows only brackets, strings and comments: no `name.Trim()`, no `a + b`, no statement, no member.
 */
object NativeCSharpSelection {
    /** [text] parsed as a native file of its own: for tests. */
    fun ranges(text: CharSequence, caret: Int): List<TextRange> = NativeCSharpEditing.read { ranges(NativeCSharpSyntaxModel.parse(text), caret) }

    /** The ranges around [caret], each containing it, smallest first. */
    fun ranges(file: CSharpFile, caret: Int): MutableList<TextRange> {
        val result = LinkedHashSet<TextRange>()
        val leaves = listOfNotNull(file.findElementAt(caret), if (caret > 0) file.findElementAt(caret - 1) else null).distinct()
        for (leaf in leaves) {
            var element: PsiElement? = leaf
            while (element != null && element !is PsiFile) {
                add(element, result)
                element = element.parent
            }
        }
        return result.filter { it.startOffset <= caret && caret <= it.endOffset && !it.isEmpty }.sortedBy { it.length }.toMutableList()
    }

    private fun add(element: PsiElement, result: MutableSet<TextRange>) {
        if (element.textLength == 0 || element is PsiWhiteSpace || element is PsiErrorElement || NativeCSharpEditing.missingKind(element) != null) return
        if (element.firstChild == null) {
            // a leaf: the text of a literal without its quotes, then the literal; the word inside is the platform's
            if (element.elementType in NativeCSharpEditing.STRING_TOKENS) NativeCSharpEditing.literalContent(element)?.let(result::add)
            if (element is PsiComment || element.elementType in NativeCSharpEditing.STRING_TOKENS) result += element.textRange
            return
        }
        if (element is PsiComment) { result += element.textRange; return }
        val code = NativeCSharpEditing.codeRange(element) ?: return
        result += code
        // a member with its doc comment and the comments right above it, as in Rider
        if (element is CSharpMemberDeclaration || element is CSharpStatement) {
            var above: PsiElement? = element.prevSibling
            var start = -1
            while (above != null && (above is PsiWhiteSpace || above is PsiComment)) {
                if (above is PsiWhiteSpace && above.text.count { it == '\n' } > 1) break // a blank line ends the comments of the member
                if (above is PsiComment) start = above.textRange.startOffset
                above = above.prevSibling
            }
            if (start >= 0) result += TextRange(start, code.endOffset)
        }
        bracketed(element, result)
        if (element is CSharpInterpolatedStringExpression) {
            val start = element.stringStartToken?.takeIf { it.textLength > 0 }
            val end = element.stringEndToken?.takeIf { it.textLength > 0 }
            if (start != null && end != null && start.textRange.endOffset < end.textRange.startOffset) result += TextRange(start.textRange.endOffset, end.textRange.startOffset)
        }
    }

    private val PAIRS = mapOf("(" to ")", "[" to "]", "{" to "}")

    /** The first opening bracket among the children of [element] and the last closing one of its kind: what is inside, and both with it. */
    private fun bracketed(element: PsiElement, result: MutableSet<TextRange>) {
        val children = generateSequence(element.firstChild) { it.nextSibling }.filter { it.firstChild == null && it.textLength > 0 }.toList()
        val open = children.firstOrNull { child -> PAIRS.keys.any { CSharpLeaves.isPunctuation(child, it) } } ?: return
        val close = children.lastOrNull { CSharpLeaves.isPunctuation(it, PAIRS.getValue(open.text)) } ?: return
        if (close.textRange.startOffset < open.textRange.endOffset) return
        result += TextRange(open.textRange.startOffset, close.textRange.endOffset)
        val inner = TextRange(open.textRange.endOffset, close.textRange.startOffset)
        // what is inside, comments included: the contents of a block start with the comment over its first statement
        fun inside(leaf: PsiElement) = leaf.textLength > 0 && leaf !is PsiWhiteSpace
        var first = PsiTreeUtil.nextLeaf(open)
        while (first != null && first.textRange.startOffset < inner.endOffset && !inside(first)) first = PsiTreeUtil.nextLeaf(first)
        var last = PsiTreeUtil.prevLeaf(close)
        while (last != null && last.textRange.endOffset > inner.startOffset && !inside(last)) last = PsiTreeUtil.prevLeaf(last)
        if (first != null && last != null && first.textRange.startOffset < last.textRange.endOffset && first.textRange.startOffset >= inner.startOffset) {
            result += TextRange(first.textRange.startOffset, last.textRange.endOffset)
        }
    }
}

/**
 * Complete Statement (Ctrl+Shift+Enter) on the tree: the statement or declaration at the caret tells what it lacks. The `)`, `]` and `}`
 * the parser found missing and the `;` go in where the code ends (`Foo(a, b` -> `Foo(a, b);`, also across lines), a header with no
 * body (`if (x)`, `foreach (...)`, `while`, `lock`, `using`, `switch`, `catch`, `else`, a method, a local function, a class) gets a block
 * with the caret in it, an abstract / extern / partial method or one of an interface gets `;`, a field gets `;`. A complete statement
 * moves the caret past its end before the new line, not to the end of the caret's line. Where the code around lost a `}`, or has skipped
 * tokens, or lacks anything else (`x = `, `Make(a, `), [plan] gives up and the token version answers.
 */
object NativeCSharpCompleteStatement {
    /** Insert [text] at [offset] of the text before the change; [caret]: the caret goes this far into it. */
    class Insert(val offset: Int, val text: String, val caret: Int? = null)

    /** What to do: the insertions, where the caret goes (an offset before the change, past the insertions at it when [afterInserts]), and the new line. */
    class Plan(val inserts: List<Insert>, val caret: Int, val newLine: Boolean, val afterInserts: Boolean = true) {
        /** The caret after the change. */
        fun caretAfter(): Int {
            val inner = inserts.indexOfFirst { it.caret != null }
            if (inner >= 0) {
                val at = inserts[inner]
                return at.offset + inserts.withIndex().filter { (i, it) -> it.offset < at.offset || (it.offset == at.offset && i < inner) }.sumOf { it.value.text.length } + at.caret!!
            }
            return caret + inserts.filter { it.offset < caret || (afterInserts && it.offset == caret) }.sumOf { it.text.length }
        }

        /** The text after the change, for tests. */
        fun applyTo(text: CharSequence): String {
            val builder = StringBuilder(text)
            for (insert in inserts.withIndex().sortedWith(compareByDescending<IndexedValue<Insert>> { it.value.offset }.thenByDescending { it.index })) {
                builder.insert(insert.value.offset, insert.value.text)
            }
            return builder.toString()
        }
    }

    /** [text] parsed as a native file of its own, with the indent of [indentUnit]: for tests. */
    fun plan(text: CharSequence, caret: Int, indentUnit: String = "    "): Plan? =
        NativeCSharpEditing.read { plan(NativeCSharpSyntaxModel.parse(text), caret, indentUnit) }

    fun indentUnit(file: PsiFile): String {
        val options = CodeStyle.getIndentOptions(file)
        return if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE.coerceAtLeast(1))
    }

    fun plan(file: CSharpFile, caret: Int, indentUnit: String): Plan? {
        val text = file.viewProvider.contents
        val leaf = codeLeafOnLine(file, text, caret) ?: return null
        val anchor = anchor(leaf) ?: return null
        if (NativeCSharpEditing.structureShifted(anchor) || NativeCSharpEditing.hasSkippedTokens(anchor)) return null
        return Planner(text, indentUnit).plan(anchor)
    }

    /** The code leaf at the caret, or the last one before it on its line (the caret at the end of `Foo(a, b`). */
    private fun codeLeafOnLine(file: CSharpFile, text: CharSequence, caret: Int): PsiElement? {
        file.findElementAt(caret)?.takeIf { NativeCSharpEditing.isCode(it) && it.textRange.startOffset < caret }?.let { return it }
        val lineStart = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (caret == 0) 0 else it + 1 }
        var leaf = if (caret > 0) file.findElementAt(caret - 1) else null
        while (leaf != null && leaf.textRange.endOffset > lineStart) {
            if (NativeCSharpEditing.isCode(leaf)) return leaf
            leaf = PsiTreeUtil.prevLeaf(leaf)
        }
        // nothing before the caret on its line: the first code after it on the line
        var next = file.findElementAt(caret)
        val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        while (next != null && next.textRange.startOffset < lineEnd) {
            if (NativeCSharpEditing.isCode(next)) return next
            next = PsiTreeUtil.nextLeaf(next)
        }
        return null
    }

    /** The innermost statement or declaration around [leaf]; a header's `else` belongs to its clause. */
    private fun anchor(leaf: PsiElement): PsiElement? {
        var current: PsiElement? = leaf
        while (current != null && current !is PsiFile) {
            when (current) {
                is CSharpBlock -> {}
                is CSharpStatement, is CSharpElseClause, is CSharpCatchClause, is CSharpFinallyClause -> return current
                is CSharpBaseNamespaceDeclaration -> return null
                is CSharpMemberDeclaration -> return current
                is CSharpAccessorDeclaration, is CSharpSwitchSection -> return null
            }
            current = current.parent
        }
        return null
    }

    private val CLOSERS = mapOf(SyntaxKind.CloseParenToken to ")", SyntaxKind.CloseBracketToken to "]", SyntaxKind.CloseBraceToken to "}", SyntaxKind.SemicolonToken to ";")

    private class Planner(val text: CharSequence, val unit: String) {
        fun plan(anchor: PsiElement): Plan? = when (anchor) {
            is CSharpIfStatement, is CSharpWhileStatement, is CSharpForStatement, is CSharpCommonForEachStatement, is CSharpLockStatement, is CSharpUsingStatement,
            is CSharpFixedStatement, is CSharpElseClause -> header(anchor, embedded(anchor))
            is CSharpDoStatement -> if (absent(anchor.statement)) header(anchor, anchor.statement, upTo = anchor.statement) else simple(anchor)
            is CSharpSwitchStatement -> if (anchor.openBraceToken == null && anchor.sections.isEmpty()) block(anchor, closersBefore(anchor, null) ?: return null) else simple(anchor)
            is CSharpCatchClause -> blockOwner(anchor, anchor.block)
            is CSharpFinallyClause -> blockOwner(anchor, anchor.block)
            is CSharpTryStatement -> blockOwner(anchor, anchor.block)
            is CSharpCheckedStatement -> blockOwner(anchor, anchor.block)
            is CSharpUnsafeStatement -> blockOwner(anchor, anchor.block)
            is CSharpBaseMethodDeclaration -> method(anchor, anchor.body, anchor.expressionBody, anchor.semicolonToken, wantsBody(anchor))
            is CSharpLocalFunctionStatement -> method(anchor, anchor.body, anchor.expressionBody, anchor.semicolonToken, true)
            is CSharpBaseTypeDeclaration -> typeBody(anchor)
            is CSharpExpressionStatement, is CSharpLocalDeclarationStatement, is CSharpReturnStatement, is CSharpThrowStatement, is CSharpYieldStatement,
            is CSharpBreakStatement, is CSharpContinueStatement, is CSharpGotoStatement, is CSharpBaseFieldDeclaration, is CSharpPropertyDeclaration -> simple(anchor)
            else -> null
        }

        /** The missing tokens of [node] in the order of the text, nested statements included. */
        fun missing(node: PsiElement): List<PsiElement> = PsiTreeUtil.collectElements(node) { NativeCSharpEditing.missingKind(it) != null }.toList()

        /** The end of the code before [element]: where a missing token is typed. */
        fun codeEndBefore(element: PsiElement): Int? {
            var leaf = PsiTreeUtil.prevLeaf(element)
            while (leaf != null && !NativeCSharpEditing.isCode(leaf)) leaf = PsiTreeUtil.prevLeaf(leaf)
            return leaf?.textRange?.endOffset
        }

        /** An unterminated literal at the end: a `)` or `;` after it would land inside the string. */
        fun endsInOpenLiteral(node: PsiElement): Boolean {
            var last: PsiElement? = PsiTreeUtil.getDeepestLast(node)
            while (last != null && !NativeCSharpEditing.isCode(last)) last = PsiTreeUtil.prevLeaf(last)
            return last != null && last.elementType in NativeCSharpEditing.STRING_TOKENS && NativeCSharpEditing.literalContent(last) == null
        }

        /** A statement that only closes what it opened and ends with `;`: the missing `)`, `]`, `}`, `;` go in, the caret after them. */
        fun simple(anchor: PsiElement): Plan? {
            if (endsInOpenLiteral(anchor)) return null
            val inserts = ArrayList<Insert>()
            for (gap in missing(anchor)) {
                val closer = CLOSERS[NativeCSharpEditing.missingKind(gap)] ?: return null
                inserts += Insert(codeEndBefore(gap) ?: return null, closer)
            }
            val end = NativeCSharpEditing.codeRange(anchor)?.endOffset ?: return null
            return Plan(inserts, inserts.maxOfOrNull { it.offset }?.coerceAtLeast(end) ?: end, newLine = true)
        }

        fun embedded(anchor: PsiElement): CSharpStatement? = when (anchor) {
            is CSharpIfStatement -> anchor.statement
            is CSharpWhileStatement -> anchor.statement
            is CSharpForStatement -> anchor.statement
            is CSharpCommonForEachStatement -> anchor.statement
            is CSharpLockStatement -> anchor.statement
            is CSharpUsingStatement -> anchor.statement
            is CSharpFixedStatement -> anchor.statement
            is CSharpElseClause -> anchor.statement
            else -> null
        }

        /** No statement written: the parser's stand-in, all of it missing. */
        fun absent(statement: PsiElement?): Boolean = statement == null || statement.textLength == 0

        /** The closers missing in [anchor] before [upTo] (the header), or null when something else is missing there. */
        fun closersBefore(anchor: PsiElement, upTo: PsiElement?): List<Insert>? {
            val inserts = ArrayList<Insert>()
            for (gap in missing(anchor)) {
                if (upTo != null && PsiTreeUtil.isAncestor(upTo, gap, false)) continue
                val kind = NativeCSharpEditing.missingKind(gap)
                // the block that is to come, or the `;` the parser wanted instead of it
                if (kind === SyntaxKind.OpenBraceToken || kind === SyntaxKind.CloseBraceToken || kind === SyntaxKind.SemicolonToken) continue
                val closer = CLOSERS[kind] ?: return null
                inserts += Insert(codeEndBefore(gap) ?: return null, closer)
            }
            return inserts
        }

        /** `if (x)`: the `)` it lacks and a block when no statement follows; a statement on the same line is completed itself. */
        fun header(anchor: PsiElement, statement: PsiElement?, upTo: PsiElement? = statement): Plan? {
            val closers = closersBefore(anchor, upTo) ?: return null
            if (absent(statement)) return block(anchor, closers)
            statement!!
            if (statement is CSharpBlock) return intoBlock(statement, closers)
            val headerEnd = closers.maxOfOrNull { it.offset } ?: codeEndBefore(statement) ?: return null
            if (closers.isEmpty() && sameLine(headerEnd, statement.textRange.startOffset)) return plan(statement)
            return Plan(closers, headerEnd, newLine = true)
        }

        fun blockOwner(anchor: PsiElement, block: CSharpBlock?): Plan? = when {
            block == null || absent(block) -> block(anchor, closersBefore(anchor, block) ?: return null)
            else -> intoBlock(block, closersBefore(anchor, block) ?: return null)
        }

        /** `void M()`: a body with the caret in it, or `;` where none is wanted; an existing body takes the caret. */
        fun method(anchor: PsiElement, body: CSharpBlock?, arrow: PsiElement?, semicolon: PsiElement?, wantsBody: Boolean): Plan? {
            if (body != null && !absent(body)) return intoBlock(body, closersBefore(anchor, body) ?: return null)
            if (arrow != null || semicolon != null) return simple(anchor)
            val closers = closersBefore(anchor, null) ?: return null
            if (wantsBody) return block(anchor, closers)
            val end = closers.maxOfOrNull { it.offset } ?: NativeCSharpEditing.codeRange(anchor)?.endOffset ?: return null
            return Plan(closers + Insert(end, ";"), end, newLine = true)
        }

        fun wantsBody(method: CSharpBaseMethodDeclaration): Boolean {
            if (method.modifiers.any { it.text == "abstract" || it.text == "extern" || it.text == "partial" }) return false
            return method.parent !is CSharpInterfaceDeclaration
        }

        /** `class Order`: a body when it has none; one with a body takes the caret into it. */
        fun typeBody(type: CSharpBaseTypeDeclaration): Plan? {
            val open = type.openBraceToken
            if (open != null) return if (type.closeBraceToken != null) Plan(emptyList(), open.textRange.endOffset, newLine = true) else null
            if (type.semicolonToken != null || (type is CSharpTypeDeclaration && type.members.isNotEmpty())) return null
            return block(type, closersBefore(type, null) ?: return null)
        }

        /** The caret into an existing block: a new line after its `{`. */
        fun intoBlock(block: CSharpBlock, closers: List<Insert>): Plan? {
            val open = block.openBraceToken ?: return null
            return Plan(closers, open.textRange.endOffset, newLine = true)
        }

        /** A block after the code of [anchor]'s header, in the brace style of the code around, the caret on its empty line. */
        fun block(anchor: PsiElement, closers: List<Insert>): Plan? {
            val headerEnd = closers.maxOfOrNull { it.offset } ?: NativeCSharpEditing.codeRange(anchor)?.endOffset ?: return null
            val indent = lineIndent(NativeCSharpEditing.codeRange(anchor)?.startOffset ?: return null)
            val head = if (braceOnSameLine(anchor)) " {\n" else "\n$indent{\n"
            val body = "$head$indent$unit"
            return Plan(closers + Insert(headerEnd, "$body\n$indent}", caret = body.length), headerEnd, newLine = false)
        }

        /** K&R when the nearest `{` around is at the end of a line of code, Allman (C#'s, Rider's default) otherwise. */
        fun braceOnSameLine(anchor: PsiElement): Boolean {
            var current = anchor.parent
            while (current != null && current !is PsiFile) {
                val open = generateSequence(current.firstChild) { it.nextSibling }.firstOrNull { CSharpLeaves.isPunctuation(it, "{") }
                if (open != null) {
                    val offset = open.textRange.startOffset
                    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
                    return text.subSequence(lineStart, offset).isNotBlank()
                }
                current = current.parent
            }
            return false
        }

        fun lineIndent(offset: Int): String {
            val lineStart = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
            return text.subSequence(lineStart, offset).takeWhile { it == ' ' || it == '\t' }.toString()
        }

        fun sameLine(a: Int, b: Int): Boolean = (minOf(a, b) until maxOf(a, b)).none { text[it] == '\n' }
    }
}

/**
 * The gray `;` of [CSharpGhostText.semicolon] on the tree: the statement the caret ends is missing its `;` and nothing else, as the parser
 * sees it, so a statement written over several lines (`Make(a,` / `b)`, a chain of `.Where(...)`) gets it as well, and a line that only
 * looks like a statement does not.
 */
object NativeCSharpGhostText {
    private val STATEMENTS = listOf(
        CSharpExpressionStatement::class.java, CSharpLocalDeclarationStatement::class.java, CSharpReturnStatement::class.java, CSharpThrowStatement::class.java,
        CSharpYieldStatement::class.java, CSharpBaseFieldDeclaration::class.java,
    )

    /** Whether `;` is what the statement ending at [offset] (the code before it on its line) lacks. */
    fun needsSemicolon(file: CSharpFile, offset: Int): Boolean = NativeCSharpEditing.read {
        var leaf = file.findElementAt((offset - 1).coerceAtLeast(0))
        while (leaf != null && !NativeCSharpEditing.isCode(leaf)) leaf = PsiTreeUtil.prevLeaf(leaf)
        if (leaf == null || leaf.textRange.endOffset > offset) return@read false
        var statement: PsiElement? = leaf
        while (statement != null && statement !is PsiFile && STATEMENTS.none { it.isInstance(statement) }) {
            if (statement is CSharpBlock || statement is CSharpMemberDeclaration) return@read false
            statement = statement.parent
        }
        if (statement == null || statement is PsiFile) return@read false
        // the statement ends here, its `;` is the only thing missing in it
        if (NativeCSharpEditing.codeRange(statement)?.endOffset != leaf.textRange.endOffset) return@read false
        val missing = PsiTreeUtil.collectElements(statement) { NativeCSharpEditing.missingKind(it) != null }
        if (missing.size != 1 || NativeCSharpEditing.missingKind(missing[0]) !== SyntaxKind.SemicolonToken) return@read false
        !NativeCSharpEditing.structureShifted(statement) && !NativeCSharpEditing.hasSkippedTokens(statement)
    }
}
