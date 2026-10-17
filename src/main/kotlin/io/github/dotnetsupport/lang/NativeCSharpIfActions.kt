package io.github.dotnetsupport.lang

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * The text edits of the context actions on `if` (0.1.163), as Rider offers them on the keyword: the condition inverted with the branches
 * swapped (a guard clause where there is no `else`), nested `if`s merged into one, one split into two, braces around a single-statement
 * body and off a one-statement block, and the body of a trailing `if` turned into a guard clause. Pure functions on the tree and the
 * document's text, tested without an editor.
 */
object NativeCSharpIfEdits {
    private fun present(token: PsiElement?): Boolean = (token?.textLength ?: 0) > 0

    private fun leafAt(file: PsiFile, offset: Int): List<PsiElement> = listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)

    // ---- the tree

    /** The `if` whose header (`if (…)`) holds [offset]. */
    fun ifAt(file: PsiFile, offset: Int): CSharpIfStatement? = NativeCSharpContextEdits.ifAt(file, offset)

    /** A statement with a body (`if`, `else`, `while`, `for`, `foreach`, `using`, `lock`, `fixed`) whose header holds [offset], with that body. */
    fun bodyOwnerAt(file: PsiFile, offset: Int): Pair<PsiElement, CSharpStatement?>? {
        for (leaf in leafAt(file, offset)) {
            var at: PsiElement? = leaf
            while (at != null && at !is CSharpMemberDeclaration) {
                val header = headerEnd(at)
                if (header != null) {
                    if (offset >= at.textRange.startOffset && offset <= header) return at to bodyOf(at)
                    return null
                }
                at = at.parent
            }
        }
        return null
    }

    /** The end of the header of a statement with a body: its closing parenthesis, or the `else` keyword. */
    private fun headerEnd(element: PsiElement): Int? = when (element) {
        is CSharpIfStatement -> element.closeParenToken
        is CSharpWhileStatement -> element.closeParenToken
        is CSharpForStatement -> element.closeParenToken
        is CSharpCommonForEachStatement -> element.closeParenToken
        is CSharpUsingStatement -> element.closeParenToken
        is CSharpLockStatement -> element.closeParenToken
        is CSharpFixedStatement -> element.closeParenToken
        is CSharpElseClause -> element.elseKeyword
        else -> null
    }?.takeIf(::present)?.textRange?.endOffset

    private fun bodyOf(element: PsiElement): CSharpStatement? = when (element) {
        is CSharpIfStatement -> element.statement
        is CSharpWhileStatement -> element.statement
        is CSharpForStatement -> element.statement
        is CSharpCommonForEachStatement -> element.statement
        is CSharpUsingStatement -> element.statement
        is CSharpLockStatement -> element.statement
        is CSharpFixedStatement -> element.statement
        is CSharpElseClause -> element.statement
        else -> null
    }

    // ---- text

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    private fun lineIndent(text: CharSequence, offset: Int): String {
        val start = lineStart(text, offset)
        return text.subSequence(start, offset).takeWhile { it == ' ' || it == '\t' }.toString()
    }

    private fun ownIndent(text: CharSequence, element: PsiElement): String = lineIndent(text, element.textRange.startOffset)

    /** [s] (an element's text, its first line without indent) with every line after the first shifted: [add] put before, [remove] taken off. */
    private fun shift(s: String, add: String = "", remove: String = ""): String =
        s.lines().mapIndexed { i, line -> if (i == 0 || line.isBlank()) line else add + if (remove.isNotEmpty() && line.startsWith(remove)) line.substring(remove.length) else line }.joinToString("\n")

    /** The statements of [block] as one text at [indent] (their lines re-indented from their own indent), empty for an empty block. */
    private fun statementsText(block: CSharpBlock, text: CharSequence, indent: String): String {
        val statements = block.statements
        if (statements.isEmpty()) return ""
        val from = statements.first().textRange.startOffset
        val to = statements.last().textRange.endOffset
        val old = lineIndent(text, from)
        return (old + text.subSequence(from, to)).lines().joinToString("\n") { line -> if (line.startsWith(old)) indent + line.substring(old.length) else line.trimStart() }
    }

    // ---- negation

    private val FLIPPED = mapOf("==" to "!=", "!=" to "==", "<" to ">=", ">=" to "<", ">" to "<=", "<=" to ">")
    private val COMPARISONS = FLIPPED.keys

    /** An expression `!` reads as a whole: a name, a call, a comparison, `is`, `!x`, `(…)`. */
    private fun atomic(e: CSharpExpression?): Boolean = when (val u = e) {
        is CSharpIdentifierName, is CSharpMemberAccessExpression, is CSharpInvocationExpression, is CSharpElementAccessExpression, is CSharpThisExpression,
        is CSharpLiteralExpression, is CSharpParenthesizedExpression, is CSharpIsPatternExpression, is CSharpPrefixUnaryExpression -> true
        is CSharpBinaryExpression -> u.operatorToken?.text.let { it in COMPARISONS || it == "is" || it == "as" }
        else -> false
    }

    /** `!` of [condition], as Rider writes it: comparisons flipped, `!x` ↔ `x`, `is null` ↔ `is not null`, De Morgan over `&&` / `||` of comparisons, else `!(…)`. */
    fun negated(condition: CSharpExpression): String {
        val e = NativeCSharpContextEdits.unwrap(condition) ?: condition
        when (e) {
            is CSharpLiteralExpression -> when (e.text) {
                "true" -> return "false"
                "false" -> return "true"
            }
            is CSharpPrefixUnaryExpression -> if (e.operatorToken?.text == "!") {
                val operand = e.operand
                return if (operand is CSharpParenthesizedExpression) operand.expression?.text ?: e.text else operand?.text ?: e.text
            }
            is CSharpIdentifierName, is CSharpMemberAccessExpression, is CSharpInvocationExpression, is CSharpElementAccessExpression, is CSharpThisExpression -> return "!${e.text}"
            is CSharpIsPatternExpression -> {
                val pattern = e.pattern
                val subject = e.expression?.text ?: return "!(${e.text})"
                return when {
                    pattern is CSharpUnaryPattern && pattern.operatorToken?.text == "not" -> "$subject is ${pattern.pattern?.text}"
                    pattern is CSharpConstantPattern || pattern is CSharpTypePattern -> "$subject is not ${pattern.text}"
                    else -> "!(${e.text})"
                }
            }
            is CSharpBinaryExpression -> {
                val op = e.operatorToken?.text
                val left = e.left
                val right = e.right
                if (op in COMPARISONS && left != null && right != null) return "${left.text} ${FLIPPED.getValue(op!!)} ${right.text}"
                // `x is T` (a type, not a pattern) is the `is` operator in the tree
                if (op == "is" && left != null && right != null) return "${left.text} is not ${right.text}"
                if ((op == "&&" || op == "||") && atomic(left) && atomic(right)) {
                    val joined = if (op == "&&") "||" else "&&"
                    return "${deMorganOperand(left!!)} $joined ${deMorganOperand(right!!)}"
                }
            }
            else -> {}
        }
        return "!(${e.text})"
    }

    /** An operand of De Morgan: its negation, parenthesized when that is itself an `&&` / `||` (`(a && b) || c` → `(!a || !b) && !c`). */
    private fun deMorganOperand(operand: CSharpExpression): String {
        val inner = NativeCSharpContextEdits.unwrap(operand)
        val logical = inner is CSharpBinaryExpression && (inner.operatorToken?.text == "&&" || inner.operatorToken?.text == "||")
        return negated(operand).let { if (logical) "($it)" else it }
    }

    /** [condition] as an operand of `&&`: parenthesized unless it binds at least as tightly. */
    private fun andOperand(condition: CSharpExpression): String {
        val e = NativeCSharpContextEdits.unwrap(condition) ?: condition
        val safe = atomic(e) || e is CSharpBinaryExpression && e.operatorToken?.text == "&&"
        return if (safe) e.text else "(${e.text})"
    }

    // ---- the place of a trailing `if`

    /** The jump a guard clause of [statement] makes when it is the last statement of a body: `return;` in a void member, `continue;` in a loop; null elsewhere. */
    private fun guardJump(statement: CSharpIfStatement): String? {
        val block = statement.parent as? CSharpBlock ?: return null
        if (block.statements.lastOrNull() != statement) return null
        return when (val owner = block.parent) {
            is CSharpWhileStatement, is CSharpDoStatement, is CSharpForStatement, is CSharpCommonForEachStatement -> "continue;"
            is CSharpMethodDeclaration -> if (returnsNothing(owner.returnType, owner.modifiers) && !iterator(block)) "return;" else null
            is CSharpLocalFunctionStatement -> if (returnsNothing(owner.returnType, owner.modifiers) && !iterator(block)) "return;" else null
            is CSharpConstructorDeclaration -> "return;"
            is CSharpAccessorDeclaration -> if (owner.keyword?.text != "get") "return;" else null
            else -> null
        }
    }

    private fun returnsNothing(type: CSharpType?, modifiers: List<PsiElement>): Boolean {
        if ((type as? CSharpPredefinedType)?.keyword?.text == "void") return true
        val name = type?.text?.let(CSharpStubsText::collapse)?.removePrefix("global::")?.removePrefix("System.Threading.Tasks.")
        return modifiers.any { it.text == "async" } && (name == "Task" || name == "ValueTask")
    }

    private fun iterator(block: CSharpBlock): Boolean = PsiTreeUtil.findChildOfType(block, CSharpYieldStatement::class.java) != null

    /** The body [statement] as the statements of a guard clause at [indent]: the block's statements, or the one statement. */
    private fun unwrapped(statement: CSharpStatement, text: CharSequence, indent: String): String =
        if (statement is CSharpBlock) statementsText(statement, text, indent) else indent + statement.text

    // ---- Invert 'if'

    /**
     * `if (!c) else-branch else then-branch` (Rider: "Invert 'if'"); without `else`, the trailing `if` of a body becomes a guard: `if (!c)
     * return;` (`continue;` in a loop) and the old body after it; in the middle of a block, when the body leaves it, the statements after
     * the `if` become its branch and the old body the `else` ([invertInTheMiddle]). Not for an `else if` chain.
     */
    fun invert(statement: CSharpIfStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        val condition = statement.condition ?: return null
        val then = statement.statement ?: return null
        if (!present(statement.closeParenToken)) return null
        val range = statement.textRange
        val elseClause = statement.`else`
        if (elseClause != null) {
            val other = elseClause.statement ?: return null
            if (other is CSharpIfStatement) return null
            val whole = text.subSequence(range.startOffset, range.endOffset).toString()
            fun at(offset: Int) = offset - range.startOffset
            val result = whole.substring(0, at(condition.textRange.startOffset)) + negated(condition) +
                whole.substring(at(condition.textRange.endOffset), at(then.textRange.startOffset)) + other.text +
                whole.substring(at(then.textRange.endOffset), at(other.textRange.startOffset)) + then.text +
                whole.substring(at(other.textRange.endOffset))
            return CSharpTextEdit(range, result)
        }
        val jump = guardJump(statement) ?: return invertInTheMiddle(statement, condition, then, text, unit)
        val indent = ownIndent(text, statement)
        val body = unwrapped(then, text, indent)
        return CSharpTextEdit(range, "if (${negated(condition)}) $jump" + if (body.isBlank()) "" else "\n" + body)
    }

    /** The body leaves the block where it ends: `return`, `throw`, `continue`, `break`, `goto` last. */
    private fun exits(statement: CSharpStatement): Boolean = when (val last = if (statement is CSharpBlock) statement.statements.lastOrNull() else statement) {
        is CSharpReturnStatement, is CSharpThrowStatement, is CSharpContinueStatement, is CSharpBreakStatement, is CSharpGotoStatement -> true
        else -> false
    }

    /**
     * `if (c) { …; throw …; } rest` in the middle of a block, no `else` (0.1.171, as Rider on Jellyfin's `RenameUser`): `if (!c) { rest }
     * else { …; throw …; }` — the statements after the `if` go into its new then-branch. Only when the old body leaves the block (else
     * `rest` would run after it too). A comment ending the line of the last statement goes with it.
     */
    private fun invertInTheMiddle(statement: CSharpIfStatement, condition: CSharpExpression, then: CSharpStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        val block = statement.parent as? CSharpBlock ?: return null
        val index = block.statements.indexOf(statement)
        val rest = block.statements.drop(index + 1)
        if (index < 0 || rest.isEmpty() || !exits(then)) return null
        val indent = ownIndent(text, statement)
        val inner = indent + unit
        val from = rest.first().textRange.startOffset
        var to = rest.last().textRange.endOffset
        val lineEnd = text.indexOf('\n', to).let { if (it < 0) text.length else it }
        if (text.subSequence(to, lineEnd).trimStart().startsWith("//")) to = text.subSequence(to, lineEnd).trimEnd().length + to
        val old = lineIndent(text, from)
        val restText = (old + text.subSequence(from, to)).lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> ""
                line.startsWith(old) -> inner + line.substring(old.length)
                else -> line.trimStart().let { if (it.startsWith("#")) it else inner + it }
            }
        }
        val thenText = if (then is CSharpBlock) statementsText(then, text, inner) else inner + then.text
        val result = "if (${negated(condition)})\n$indent{\n$restText\n$indent}\n${indent}else\n$indent{\n$thenText\n$indent}"
        return CSharpTextEdit(TextRange(statement.textRange.startOffset, to), result)
    }

    // ---- Invert condition of `?:`

    /** The `?:` whose text holds [offset], the nearest one. */
    fun conditionalAt(file: PsiFile, offset: Int): CSharpConditionalExpression? {
        for (leaf in leafAt(file, offset)) {
            val conditional = PsiTreeUtil.getParentOfType(leaf, CSharpConditionalExpression::class.java, false) ?: continue
            if (conditional.condition != null && conditional.whenTrue != null && conditional.whenFalse != null) return conditional
        }
        return null
    }

    /** `c ? a : b` → `!c ? b : a`, the layout between the parts kept (Rider: "Invert condition"). */
    fun invertCondition(conditional: CSharpConditionalExpression, text: CharSequence): CSharpTextEdit? {
        val condition = conditional.condition ?: return null
        val whenTrue = conditional.whenTrue ?: return null
        val whenFalse = conditional.whenFalse ?: return null
        if (!present(conditional.questionToken) || !present(conditional.colonToken)) return null
        val head = text.subSequence(condition.textRange.endOffset, whenTrue.textRange.startOffset)
        val middle = text.subSequence(whenTrue.textRange.endOffset, whenFalse.textRange.startOffset)
        // `?:` binds weaker than every operator `negated` writes: no parentheses needed around the negation
        val written = negated(condition)
        return CSharpTextEdit(conditional.textRange, written + head + whenFalse.text + middle + whenTrue.text)
    }

    // ---- Convert to guard clause

    /** The trailing `if (c) { statements }` of a body as `if (!c) return;` and the statements after it (Rider: "Convert to guard clause"); a block of two or more statements. */
    fun toGuardClause(statement: CSharpIfStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        val then = statement.statement as? CSharpBlock ?: return null
        if (statement.`else` != null || then.statements.size < 2) return null
        return invert(statement, text, unit)
    }

    // ---- Merge nested 'if' statements

    /** `if (a) { if (b) body }` → `if (a && b) body` (Rider: "Merge nested 'if' statements"): nothing else in the outer body, no `else` on either. */
    fun mergeNested(statement: CSharpIfStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        val outer = statement.condition ?: return null
        if (statement.`else` != null) return null
        val then = statement.statement ?: return null
        val inner = (if (then is CSharpBlock) then.statements.singleOrNull() else then) as? CSharpIfStatement ?: return null
        val innerCondition = inner.condition ?: return null
        if (inner.`else` != null || !present(inner.closeParenToken)) return null
        val innerBody = inner.statement ?: return null
        val indent = ownIndent(text, statement)
        val bodyIndent = ownIndent(text, innerBody)
        // the inner body re-indented to where the outer one was: one unit left when the outer body was a block
        val body = if (then is CSharpBlock) {
            val whole = text.subSequence(innerBody.textRange.startOffset, innerBody.textRange.endOffset).toString()
            shift(whole, remove = bodyIndent.removePrefix(indent).let { if (it.length >= unit.length) unit else it })
        } else innerBody.text
        val onOwnLine = text.subSequence(lineStart(text, innerBody.textRange.startOffset), innerBody.textRange.startOffset).isBlank()
        val glue = if (onOwnLine) "\n" + (if (innerBody is CSharpBlock) indent else indent + unit) else " "
        return CSharpTextEdit(statement.textRange, "if (${andOperand(outer)} && ${andOperand(innerCondition)})$glue$body")
    }

    // ---- Split into two 'if's

    /** `if (a && b) body` → `if (a) { if (b) body }` (Rider: "Split into two 'if's"); no `else`. */
    fun split(statement: CSharpIfStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        val condition = NativeCSharpContextEdits.unwrap(statement.condition) as? CSharpBinaryExpression ?: return null
        if (condition.operatorToken?.text != "&&" || statement.`else` != null) return null
        val left = condition.left ?: return null
        val right = condition.right ?: return null
        val then = statement.statement ?: return null
        val indent = ownIndent(text, statement)
        val inner = "if (${right.text})" + (if (then is CSharpBlock) "\n$indent$unit" + shift(then.text, add = unit) else "\n$indent$unit$unit${then.text}")
        return CSharpTextEdit(statement.textRange, "if (${left.text})\n$indent{\n$indent$unit$inner\n$indent}")
    }

    // ---- Add braces / Remove braces

    /** The single statement [body] of [owner] in a block of its own (Rider: "Add braces"); not an `else if`, not a block already. */
    fun addBraces(owner: PsiElement, body: CSharpStatement?, text: CharSequence, unit: String): CSharpTextEdit? {
        body ?: return null
        if (body is CSharpBlock || owner is CSharpElseClause && body is CSharpIfStatement) return null
        val header = headerEnd(owner) ?: return null
        val indent = ownIndent(text, owner)
        val inner = indent + unit
        val sameLine = text.subSequence(lineStart(text, body.textRange.startOffset), body.textRange.startOffset).isNotBlank()
        // on the header's line the body's continuation lines are shifted along with it; on its own line they keep their indent
        val bodyText = if (sameLine) shift(body.text, add = unit) else body.text
        return CSharpTextEdit(TextRange(header, body.textRange.endOffset), "\n$indent{\n$inner$bodyText\n$indent}")
    }

    /**
     * The one statement of the block [body] of [owner] without its braces (Rider: "Remove braces"); not a declaration, a label or a local
     * function, and not an inner `if` that an `else` of [owner] would then belong to.
     */
    fun removeBraces(owner: PsiElement, body: CSharpStatement?, text: CharSequence, unit: String): CSharpTextEdit? {
        val block = body as? CSharpBlock ?: return null
        val statement = block.statements.singleOrNull() ?: return null
        if (statement is CSharpLocalDeclarationStatement || statement is CSharpLabeledStatement || statement is CSharpLocalFunctionStatement) return null
        if (statement is CSharpIfStatement && owner is CSharpIfStatement && owner.`else` != null && statement.`else` == null) return null
        if (!present(block.closeBraceToken)) return null
        if (PsiTreeUtil.findChildOfType(block, com.intellij.psi.PsiComment::class.java) != null) return null
        val header = headerEnd(owner) ?: return null
        val indent = ownIndent(text, owner)
        val old = lineIndent(text, statement.textRange.startOffset)
        val sameLine = text.subSequence(lineStart(text, statement.textRange.startOffset), statement.textRange.startOffset).isNotBlank()
        val bodyText = if (sameLine) shift(statement.text, add = indent + unit, remove = old) else statement.text
        return CSharpTextEdit(TextRange(header, block.textRange.endOffset), "\n$indent$unit$bodyText")
    }
}

/** Alt+Enter on `if`: the condition negated and the branches swapped; a trailing `if` without `else` becomes a guard (Rider: "Invert 'if'"). */
class NativeCSharpInvertIfIntention : NativeCSharpContextAction("Invert 'if'") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpIfEdits.ifAt(file, editor.caretModel.offset)?.let { NativeCSharpIfEdits.invert(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on a `?:`: the condition negated and the branches swapped (Rider: "Invert condition"). */
class NativeCSharpInvertConditionIntention : NativeCSharpContextAction("Invert condition") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpIfEdits.conditionalAt(file, editor.caretModel.offset)?.let { NativeCSharpIfEdits.invertCondition(it, editor.document.charsSequence) }
}

/** Alt+Enter on an `if` whose only statement is another `if`: one `if` with `&&` (Rider: "Merge nested 'if' statements"). */
class NativeCSharpMergeNestedIfIntention : NativeCSharpContextAction("Merge nested 'if' statements") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpIfEdits.ifAt(file, editor.caretModel.offset)?.let { NativeCSharpIfEdits.mergeNested(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on `if (a && b)`: `if (a) { if (b) … }` (Rider: "Split into two 'if's"). */
class NativeCSharpSplitIfIntention : NativeCSharpContextAction("Split into two 'if's") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpIfEdits.ifAt(file, editor.caretModel.offset)?.let { NativeCSharpIfEdits.split(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on the trailing `if` of a body with a block of statements: `if (!c) return;` and the statements after it (Rider: "Convert to guard clause"). */
class NativeCSharpToGuardClauseIntention : NativeCSharpContextAction("Convert to guard clause") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpIfEdits.ifAt(file, editor.caretModel.offset)?.let { NativeCSharpIfEdits.toGuardClause(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on `if` / `else` / a loop / `using` / `lock` with a single-statement body: the body in braces (Rider: "Add braces"). */
class NativeCSharpAddBracesIntention : NativeCSharpContextAction("Add braces") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpIfEdits.bodyOwnerAt(file, editor.caretModel.offset)?.let { (owner, body) -> NativeCSharpIfEdits.addBraces(owner, body, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on `if` / `else` / a loop / `using` / `lock` whose block holds one statement: the statement alone (Rider: "Remove braces"). */
class NativeCSharpRemoveBracesIntention : NativeCSharpContextAction("Remove braces") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpIfEdits.bodyOwnerAt(file, editor.caretModel.offset)?.let { (owner, body) -> NativeCSharpIfEdits.removeBraces(owner, body, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}
