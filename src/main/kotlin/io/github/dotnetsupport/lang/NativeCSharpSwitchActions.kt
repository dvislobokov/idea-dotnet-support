package io.github.dotnetsupport.lang

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * The text edits of the context actions on `switch` (0.1.162), as Rider offers them on the `switch` keyword: the missing sections / arms of
 * an enum, a `default` section, braces around the sections, and the three shapes of a `switch` turned into one another (statement ↔
 * expression, statement → `if` chain). Pure functions on the tree and the document's text, tested without an editor.
 */
object NativeCSharpSwitchEdits {
    private fun present(token: PsiElement?): Boolean = (token?.textLength ?: 0) > 0

    private fun leafAt(file: PsiFile, offset: Int): List<PsiElement> = listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)

    // ---- the tree

    /** The `switch` statement whose header (`switch (…)`) holds [offset]. */
    fun switchAt(file: PsiFile, offset: Int): CSharpSwitchStatement? {
        for (leaf in leafAt(file, offset)) {
            val statement = PsiTreeUtil.getParentOfType(leaf, CSharpSwitchStatement::class.java, false) ?: continue
            val end = statement.closeParenToken?.takeIf(::present)?.textRange?.endOffset ?: continue
            if (offset >= statement.textRange.startOffset && offset <= end) return statement
        }
        return null
    }

    /** The `switch` expression whose head (the governing expression and the `switch` keyword) holds [offset]. */
    fun switchExpressionAt(file: PsiFile, offset: Int): CSharpSwitchExpression? {
        for (leaf in leafAt(file, offset)) {
            val expression = PsiTreeUtil.getParentOfType(leaf, CSharpSwitchExpression::class.java, false) ?: continue
            val end = expression.switchKeyword?.takeIf(::present)?.textRange?.endOffset ?: continue
            if (offset >= expression.textRange.startOffset && offset <= end) return expression
        }
        return null
    }

    /** The enum [expression] is of (`OrderStatus?` too), else null. */
    fun enumOf(resolver: CSharpNameResolver, expression: CSharpExpression): SemanticType? =
        resolver.typeOf(expression)?.let(resolver::unwrapNullable)?.takeIf(::isEnum)

    private fun isEnum(type: SemanticType): Boolean = when (type) {
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.ENUM
        is SemanticType.Source -> type.info.kind == TypeKind.ENUM
        else -> false
    }

    /** `Insert` of `case OperationType.Insert:` / `case Insert:`: the member a constant names. */
    private fun memberName(expression: CSharpExpression?): String? = when (val e = NativeCSharpContextEdits.unwrap(expression)) {
        is CSharpIdentifierName -> e.identifier?.text
        is CSharpMemberAccessExpression -> (e.nameElement as? CSharpIdentifierName)?.identifier?.text
        else -> null
    }

    /** `OperationType` of `case OperationType.Insert:`: how the labels at hand qualify the members; the type's own name when none does. */
    private fun qualifier(constants: List<CSharpExpression?>, type: SemanticType): String {
        for (constant in constants) {
            val access = NativeCSharpContextEdits.unwrap(constant) as? CSharpMemberAccessExpression ?: continue
            access.expression?.text?.let { return it }
        }
        return type.minimalDisplay ?: type.name
    }

    private fun labelConstants(statement: CSharpSwitchStatement): List<CSharpExpression?> =
        statement.sections.flatMap { s -> s.labels.map { l -> (l as? CSharpCaseSwitchLabel)?.value ?: ((l as? CSharpCasePatternSwitchLabel)?.pattern as? CSharpConstantPattern)?.expression } }

    private fun hasDefault(statement: CSharpSwitchStatement): Boolean = statement.sections.any { s -> s.labels.any { it is CSharpDefaultSwitchLabel } }

    // ---- text

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    private fun lineIndent(text: CharSequence, offset: Int): String {
        val start = lineStart(text, offset)
        return text.subSequence(start, offset).takeWhile { it == ' ' || it == '\t' }.toString()
    }

    /** The indent of the line the `switch` keyword is on. */
    private fun ownIndent(text: CharSequence, element: PsiElement): String = lineIndent(text, element.textRange.startOffset)

    /** The indent of the sections: of the first one, else one unit past the `switch`. */
    private fun sectionIndent(statement: CSharpSwitchStatement, text: CharSequence, unit: String): String =
        statement.sections.firstOrNull()?.let { lineIndent(text, it.textRange.startOffset) } ?: (ownIndent(text, statement) + unit)

    /**
     * [lines] (sections, each with its own indentation) inserted before the closing `}` of [statement]: at the start of the brace's line when
     * nothing else is on it, else right before the brace on new lines.
     */
    private fun insertBeforeClose(statement: CSharpSwitchStatement, text: CharSequence, lines: List<String>): CSharpTextEdit? {
        val close = statement.closeBraceToken?.takeIf(::present) ?: return null
        val braceOffset = close.textRange.startOffset
        val start = lineStart(text, braceOffset)
        if (text.subSequence(start, braceOffset).isBlank()) return CSharpTextEdit(TextRange(start, start), lines.joinToString("") { "$it\n" })
        var from = braceOffset
        while (from > start && text[from - 1].isWhitespace()) from--
        return CSharpTextEdit(TextRange(from, braceOffset), lines.joinToString("\n", prefix = "\n", postfix = "\n" + ownIndent(text, statement)))
    }

    // ---- Add 'switch' sections / Add 'default' section

    /** `case Enum.Member:` + `break;` for every member no label covers, and `default:` when there is none (Rider: "Add 'switch' sections"). */
    fun addSections(statement: CSharpSwitchStatement, type: SemanticType, text: CharSequence, unit: String): CSharpTextEdit? {
        val constants = labelConstants(statement)
        val covered = constants.mapNotNull(::memberName).toSet()
        val missing = NativeCSharpExpectedCompletion.enumMembers(type).map { it.first }.filter { it !in covered }
        val needsDefault = !hasDefault(statement)
        if (missing.isEmpty() && !needsDefault) return null
        val qualifier = qualifier(constants, type)
        val indent = sectionIndent(statement, text, unit)
        val lines = missing.flatMap { listOf("${indent}case $qualifier.$it:", "$indent${unit}break;") } +
            if (needsDefault) listOf("${indent}default:", "$indent${unit}break;") else emptyList()
        return insertBeforeClose(statement, text, lines)
    }

    /** `default:` + `break;` at the end of a `switch` without one (Rider: "Add 'default' section"). */
    fun addDefault(statement: CSharpSwitchStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        if (hasDefault(statement) || !present(statement.openBraceToken)) return null
        val indent = sectionIndent(statement, text, unit)
        return insertBeforeClose(statement, text, listOf("${indent}default:", "$indent${unit}break;"))
    }

    // ---- Add missing arms

    private fun discardArm(expression: CSharpSwitchExpression): CSharpSwitchExpressionArm? = expression.arms.firstOrNull { it.pattern is CSharpDiscardPattern && it.whenClause == null }

    /**
     * `Enum.Member => throw new NotImplementedException(),` for every member no arm names, before the `_` arm when there is one, else at the
     * end with `_ => throw new ArgumentOutOfRangeException()` (Rider: "Add missing arms").
     */
    fun addMissingArms(expression: CSharpSwitchExpression, type: SemanticType, text: CharSequence, unit: String): CSharpTextEdit? {
        val open = expression.openBraceToken?.takeIf(::present) ?: return null
        val close = expression.closeBraceToken?.takeIf(::present) ?: return null
        val constants = expression.arms.map { (it.pattern as? CSharpConstantPattern)?.expression }
        val covered = constants.mapNotNull(::memberName).toSet()
        val missing = NativeCSharpExpectedCompletion.enumMembers(type).map { it.first }.filter { it !in covered }
        val discard = discardArm(expression)
        if (missing.isEmpty() && discard != null) return null
        val qualifier = qualifier(constants, type)
        val armIndent = expression.arms.firstOrNull()?.let { lineIndent(text, it.textRange.startOffset) } ?: (ownIndent(text, expression) + unit)
        val newArms = missing.map { "$qualifier.$it => throw new NotImplementedException()" }
        if (discard != null) {
            val at = discard.textRange.startOffset
            return CSharpTextEdit(TextRange(at, at), newArms.joinToString("") { "$it,\n$armIndent" })
        }
        val all = newArms + "_ => throw new ArgumentOutOfRangeException()"
        val last = expression.arms.lastOrNull()
        if (last == null) {
            val range = TextRange(open.textRange.endOffset, close.textRange.startOffset)
            return CSharpTextEdit(range, all.joinToString(",\n", prefix = "\n", postfix = "\n" + ownIndent(text, expression)) { armIndent + it })
        }
        val separated = expression.armsSeparators.size >= expression.arms.size
        val at = last.textRange.endOffset
        return CSharpTextEdit(TextRange(at, at), all.joinToString("", prefix = if (separated) "" else ",") { "\n$armIndent$it," }.removeSuffix(","))
    }

    // ---- Add section braces

    /** Every section's statements inside `{ }` at the label's indent, as Rider's "Add section braces"; null when all are braced already. */
    fun addSectionBraces(statement: CSharpSwitchStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        val unbraced = statement.sections.filter { it.statements.isNotEmpty() && it.statements.singleOrNull() !is CSharpBlock }
        if (unbraced.isEmpty()) return null
        val range = statement.textRange
        val builder = StringBuilder(text.subSequence(range.startOffset, range.endOffset))
        for (section in unbraced.asReversed()) {
            val colon = section.labels.last().colonToken?.takeIf(::present) ?: return null
            val indent = lineIndent(text, section.textRange.startOffset)
            val from = colon.textRange.endOffset
            val to = section.statements.last().textRange.endOffset
            var body = text.subSequence(from, to).toString()
            if (!body.trimStart(' ', '\t').startsWith("\n")) body = "\n$indent$unit" + body.trim()
            builder.replace(from - range.startOffset, to - range.startOffset, "\n$indent{${body.trimEnd()}\n$indent}")
        }
        return CSharpTextEdit(range, builder.toString())
    }

    // ---- To 'switch' expression

    /** What one section of a `switch` statement does, in the shape a `switch` expression can take. */
    private class Section(val pattern: String, val value: String, val target: String?, val throws: Boolean)

    private fun patternOf(label: CSharpSwitchLabel): String? = when (label) {
        is CSharpDefaultSwitchLabel -> "_"
        is CSharpCaseSwitchLabel -> label.value?.text
        is CSharpCasePatternSwitchLabel -> label.pattern?.text?.let { p -> label.whenClause?.let { "$p ${it.text}" } ?: p }
        else -> null
    }

    /** The section as `pattern => value`: one label, a `return x;`, a `throw x;` or `v = x; break;` (braced or not); null for anything else. */
    private fun sectionOf(section: CSharpSwitchSection): Section? {
        val label = section.labels.singleOrNull() ?: return null
        val pattern = patternOf(label) ?: return null
        val statements = section.statements.singleOrNull()?.let { if (it is CSharpBlock) it.statements else listOf(it) } ?: section.statements
        val first = statements.firstOrNull() ?: return null
        val rest = statements.drop(1)
        return when (first) {
            is CSharpReturnStatement -> if (rest.isEmpty()) Section(pattern, first.expression?.text ?: return null, null, false) else null
            is CSharpThrowStatement -> if (rest.isEmpty()) Section(pattern, first.expression?.text ?: return null, null, true) else null
            is CSharpExpressionStatement -> {
                val assignment = first.expression as? CSharpAssignmentExpression ?: return null
                if (assignment.operatorToken?.text != "=" || rest.size != 1 || rest[0] !is CSharpBreakStatement) return null
                Section(pattern, assignment.right?.text ?: return null, assignment.left?.text ?: return null, false)
            }
            else -> null
        }
    }

    private fun simple(expression: CSharpExpression?): Boolean = when (NativeCSharpContextEdits.unwrap(expression)) {
        is CSharpIdentifierName, is CSharpMemberAccessExpression, is CSharpInvocationExpression, is CSharpElementAccessExpression, is CSharpLiteralExpression,
        is CSharpThisExpression, is CSharpParenthesizedExpression -> true
        else -> false
    }

    /**
     * `return x switch { A => a, B => b, _ => c };` (or `v = x switch { … };`) for a `switch` whose sections each return / throw / assign the
     * same variable, as Rider's "To 'switch' expression"; without `default` the discard arm throws `ArgumentOutOfRangeException`.
     */
    fun toSwitchExpression(statement: CSharpSwitchStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        val governing = statement.expression ?: return null
        if (!present(statement.closeBraceToken) || statement.sections.isEmpty()) return null
        val sections = statement.sections.map { sectionOf(it) ?: return null }
        val targets = sections.filter { !it.throws }.map { it.target }.distinct()
        if (targets.size != 1) return null
        val target = targets.single()
        val indent = ownIndent(text, statement)
        val head = if (target == null) "return " else "$target = "
        val subject = if (simple(governing)) governing.text else "(${governing.text})"
        val arms = sections.map { "$indent$unit${it.pattern} => ${if (it.throws) "throw " else ""}${it.value}" }.toMutableList()
        if (sections.none { it.pattern == "_" }) {
            val name = (NativeCSharpContextEdits.unwrap(governing) as? CSharpIdentifierName)?.identifier?.text
            arms += "$indent${unit}_ => throw new ArgumentOutOfRangeException(" + (if (name != null) "nameof($name), $name, null" else "") + ")"
        }
        return CSharpTextEdit(statement.textRange, "$head$subject switch\n$indent{\n${arms.joinToString(",\n")}\n$indent};")
    }

    // ---- To 'switch' statement

    /** The `return` / assignment statement whose whole value is [expression]. */
    fun ownerStatement(expression: CSharpSwitchExpression): CSharpStatement? {
        var value: PsiElement = expression
        while (value.parent is CSharpParenthesizedExpression) value = value.parent
        return when (val parent = value.parent) {
            is CSharpReturnStatement -> parent
            is CSharpAssignmentExpression -> (parent.parent as? CSharpExpressionStatement)?.takeIf { parent.right == value && parent.operatorToken?.text == "=" }
            else -> null
        }
    }

    /** The `switch` statement of a `return` / assignment of a `switch` expression: a section per arm, `_` → `default` (Rider: "To 'switch' statement"). */
    fun toSwitchStatement(expression: CSharpSwitchExpression, text: CharSequence, unit: String): CSharpTextEdit? {
        val owner = ownerStatement(expression) ?: return null
        val governing = expression.governingExpression ?: return null
        if (!present(expression.closeBraceToken) || expression.arms.isEmpty()) return null
        val target = ((owner as? CSharpExpressionStatement)?.expression as? CSharpAssignmentExpression)?.left?.text
        val indent = ownIndent(text, owner)
        val lines = ArrayList<String>()
        lines += "${indent}switch (${governing.text})"
        lines += "$indent{"
        for (arm in expression.arms) {
            val pattern = arm.pattern ?: return null
            val value = NativeCSharpContextEdits.unwrap(arm.expression) ?: return null
            val label = if (pattern is CSharpDiscardPattern && arm.whenClause == null) "default:" else "case ${pattern.text}${arm.whenClause?.let { " ${it.text}" } ?: ""}:"
            lines += "$indent$unit$label"
            val body = "$indent$unit$unit"
            when {
                value is CSharpThrowExpression -> lines += "${body}throw ${value.expression?.text ?: return null};"
                target == null -> lines += "${body}return ${value.text};"
                else -> { lines += "$body$target = ${value.text};"; lines += "${body}break;" }
            }
        }
        lines += "$indent}"
        return CSharpTextEdit(owner.textRange, lines.joinToString("\n").removePrefix(indent))
    }

    // ---- To 'if' statements

    /** `expr == A` / `expr is P` / `expr is P && (when)` for [label]; null for `default`. */
    private fun conditionOf(label: CSharpSwitchLabel, subject: String): String? = when (label) {
        is CSharpDefaultSwitchLabel -> null
        is CSharpCaseSwitchLabel -> "$subject == ${label.value?.text}"
        is CSharpCasePatternSwitchLabel -> {
            val pattern = label.pattern
            val base = if (pattern is CSharpConstantPattern) "$subject == ${pattern.text}" else "$subject is ${pattern?.text}"
            label.whenClause?.condition?.let { "$base && (${it.text})" } ?: base
        }
        else -> null
    }

    /** The statements of [section] with the trailing `break;` dropped, re-indented from the section's statement indent to [indent]. */
    private fun bodyOf(section: CSharpSwitchSection, text: CharSequence, unit: String, indent: String): String? {
        var statements = section.statements
        if (statements.singleOrNull() is CSharpBlock) statements = (statements.single() as CSharpBlock).statements
        if (statements.lastOrNull() is CSharpBreakStatement) statements = statements.dropLast(1)
        if (statements.isEmpty()) return ""
        if (statements.any { it is CSharpGotoStatement } || PsiTreeUtil.findChildOfType(section, CSharpGotoStatement::class.java) != null) return null
        val from = statements.first().textRange.startOffset
        val to = statements.last().textRange.endOffset
        val old = lineIndent(text, from)
        // the first statement's line comes without its indent: put it back so every line re-indents alike
        return (old + text.subSequence(from, to)).lines().joinToString("\n") { line -> if (line.startsWith(old)) indent + line.substring(old.length) else line.trimStart() }
    }

    /**
     * `if (x == A) { … } else if (x == B) { … } else { … }` for the sections of a `switch` (Rider: "To 'if' statements"): several labels of one
     * section joined with `||`, the `default` section last, a section's final `break;` dropped. Not for a `switch` with `goto case`.
     */
    fun toIfStatements(statement: CSharpSwitchStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        val governing = statement.expression ?: return null
        if (!present(statement.closeBraceToken) || statement.sections.isEmpty()) return null
        val subject = if (simple(governing)) governing.text else "(${governing.text})"
        val indent = ownIndent(text, statement)
        val branches = ArrayList<Pair<String?, String>>()
        var default: String? = null
        for (section in statement.sections) {
            val body = bodyOf(section, text, unit, indent + unit) ?: return null
            if (section.labels.any { it is CSharpDefaultSwitchLabel }) {
                if (section.labels.size != 1) return null
                default = body
                continue
            }
            val conditions = section.labels.map { conditionOf(it, subject) ?: return null }
            branches += conditions.joinToString(" || ") to body
        }
        if (branches.isEmpty()) return null
        val out = StringBuilder()
        for ((i, branch) in branches.withIndex()) {
            out.append(if (i == 0) "if (" else "\n${indent}else if (").append(branch.first).append(")\n").append(indent).append("{\n")
            if (branch.second.isNotEmpty()) out.append(branch.second).append('\n')
            out.append(indent).append('}')
        }
        if (default != null) {
            out.append("\n${indent}else\n$indent{\n")
            if (default.isNotEmpty()) out.append(default).append('\n')
            out.append(indent).append('}')
        }
        return CSharpTextEdit(statement.textRange, out.toString())
    }
}

/** Alt+Enter on `switch (enum)`: the sections of the members no label covers, and `default` (Rider: "Add 'switch' sections"). */
class NativeCSharpAddSwitchSectionsIntention : NativeCSharpContextAction("Add 'switch' sections", serverHasIt = true, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val statement = NativeCSharpSwitchEdits.switchAt(file, editor.caretModel.offset) ?: return null
        val type = statement.expression?.let { NativeCSharpSwitchEdits.enumOf(resolver(file), it) } ?: return null
        return NativeCSharpSwitchEdits.addSections(statement, type, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}

/** Alt+Enter on `enum switch { … }`: the arms of the members no arm names, and `_` (Rider: "Add missing arms"). */
class NativeCSharpAddMissingArmsIntention : NativeCSharpContextAction("Add missing arms", serverHasIt = true, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val expression = NativeCSharpSwitchEdits.switchExpressionAt(file, editor.caretModel.offset) ?: return null
        val type = expression.governingExpression?.let { NativeCSharpSwitchEdits.enumOf(resolver(file), it) } ?: return null
        return NativeCSharpSwitchEdits.addMissingArms(expression, type, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}

/** Alt+Enter on a `switch` without `default`: `default: break;` at its end (Rider: "Add 'default' section"). */
class NativeCSharpAddDefaultSectionIntention : NativeCSharpContextAction("Add 'default' section", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpSwitchEdits.switchAt(file, editor.caretModel.offset)?.let { NativeCSharpSwitchEdits.addDefault(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on a `switch` with unbraced sections: `{ }` around each section's statements (Rider: "Add section braces"). */
class NativeCSharpAddSectionBracesIntention : NativeCSharpContextAction("Add section braces", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpSwitchEdits.switchAt(file, editor.caretModel.offset)?.let { NativeCSharpSwitchEdits.addSectionBraces(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on a `switch` whose sections return / assign one variable: the `switch` expression (Rider: "To 'switch' expression"). */
class NativeCSharpToSwitchExpressionIntention : NativeCSharpContextAction("To 'switch' expression", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpSwitchEdits.switchAt(file, editor.caretModel.offset)?.let { NativeCSharpSwitchEdits.toSwitchExpression(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on a `switch` statement: the `if` / `else if` / `else` chain of its sections (Rider: "To 'if' statements"). */
class NativeCSharpSwitchToIfIntention : NativeCSharpContextAction("To 'if' statements", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpSwitchEdits.switchAt(file, editor.caretModel.offset)?.let { NativeCSharpSwitchEdits.toIfStatements(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on a `switch` expression that is returned or assigned: the `switch` statement (Rider: "To 'switch' statement"). */
class NativeCSharpToSwitchStatementIntention : NativeCSharpContextAction("To 'switch' statement", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpSwitchEdits.switchExpressionAt(file, editor.caretModel.offset)?.let { NativeCSharpSwitchEdits.toSwitchStatement(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}
