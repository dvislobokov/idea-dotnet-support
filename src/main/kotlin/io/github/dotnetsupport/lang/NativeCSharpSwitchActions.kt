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
        // an unconditional `_` already takes the other members: arms that throw would change what they do (Rider offers nothing there)
        if (discard != null) return null
        if (missing.isEmpty()) return null
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

    // ---- To 'if' statements / To ?: expression on a switch expression (0.1.173)

    /** `x == A` / `x is P` / `x is P && (c)` for the pattern of [arm]; null for the discard arm without `when`. */
    private fun armCondition(arm: CSharpSwitchExpressionArm, subject: String): String? {
        val pattern = arm.pattern ?: return null
        if (pattern is CSharpDiscardPattern && arm.whenClause == null) return null
        val base = when (pattern) {
            is CSharpConstantPattern -> "$subject == ${pattern.text}"
            is CSharpDiscardPattern -> "true"
            else -> "$subject is ${pattern.text}"
        }
        val condition = arm.whenClause?.condition?.text ?: return base
        return if (pattern is CSharpDiscardPattern) condition else "$base && ($condition)"
    }

    /**
     * The `if` / `else if` / `else` chain of a `switch` expression that is returned or assigned (Rider: "To 'if' statements"): each arm's
     * value returned or assigned in its branch, a throwing arm thrown; without a discard arm the last `else` throws
     * `ArgumentOutOfRangeException`, as the `switch` expression would have thrown.
     */
    fun expressionToIfStatements(expression: CSharpSwitchExpression, text: CharSequence, unit: String): CSharpTextEdit? {
        val owner = ownerStatement(expression) ?: return null
        val governing = expression.governingExpression ?: return null
        if (!present(expression.closeBraceToken) || expression.arms.isEmpty()) return null
        val target = ((owner as? CSharpExpressionStatement)?.expression as? CSharpAssignmentExpression)?.left?.text
        val subject = if (simple(governing)) governing.text else "(${governing.text})"
        val indent = ownIndent(text, owner)
        val inner = indent + unit
        fun body(value: CSharpExpression): String = when {
            value is CSharpThrowExpression -> "${inner}throw ${value.expression?.text};"
            target == null -> "${inner}return ${value.text};"
            else -> "$inner$target = ${value.text};"
        }
        val out = StringBuilder()
        var open = false
        var closed = false
        for (arm in expression.arms) {
            val value = NativeCSharpContextEdits.unwrap(arm.expression) ?: return null
            if (value is CSharpThrowExpression && value.expression == null) return null
            val condition = armCondition(arm, subject)
            if (condition == null) {
                if (!open) return null
                out.append("\n${indent}else\n$indent{\n").append(body(value)).append('\n').append(indent).append('}')
                closed = true
                break
            }
            out.append(if (!open) "if (" else "\n${indent}else if (").append(condition).append(")\n").append(indent).append("{\n")
                .append(body(value)).append('\n').append(indent).append('}')
            open = true
        }
        if (!open) return null
        if (!closed) {
            val name = (NativeCSharpContextEdits.unwrap(governing) as? CSharpIdentifierName)?.identifier?.text
            out.append("\n${indent}else\n$indent{\n${inner}throw new ArgumentOutOfRangeException(")
                .append(if (name != null) "nameof($name), $name, null" else "").append(");\n").append(indent).append('}')
        }
        return CSharpTextEdit(owner.textRange, out.toString())
    }

    /** A value of a branch of `?:` that needs parentheses there: a conditional, an assignment, a lambda, a `switch` expression. */
    private fun branch(value: CSharpExpression): String = when (value) {
        is CSharpConditionalExpression, is CSharpAssignmentExpression, is CSharpAnonymousFunctionExpression, is CSharpSwitchExpression -> "(${value.text})"
        else -> value.text
    }

    /**
     * `x == A ? a : b` for a `switch` expression of one arm and a discard arm (Rider: "To ?: expression"); `x is P ? a : b` for a pattern.
     * Parenthesized where it is an operand of something tighter than `?:`.
     */
    fun toConditional(expression: CSharpSwitchExpression): CSharpTextEdit? {
        val governing = expression.governingExpression ?: return null
        val arms = expression.arms
        if (arms.size != 2 || !present(expression.closeBraceToken)) return null
        val last = arms[1]
        if (last.pattern !is CSharpDiscardPattern || last.whenClause != null) return null
        val first = arms[0]
        val subject = if (simple(governing)) governing.text else "(${governing.text})"
        val condition = armCondition(first, subject) ?: return null
        val whenTrue = NativeCSharpContextEdits.unwrap(first.expression) ?: return null
        val whenFalse = NativeCSharpContextEdits.unwrap(last.expression) ?: return null
        val conditional = "$condition ? ${branch(whenTrue)} : ${branch(whenFalse)}"
        val bare = when (val parent = expression.parent) {
            is CSharpReturnStatement, is CSharpEqualsValueClause, is CSharpArgument, is CSharpArrowExpressionClause, is CSharpParenthesizedExpression,
            is CSharpExpressionStatement, is CSharpInterpolation -> true
            is CSharpAssignmentExpression -> parent.right == expression
            else -> false
        }
        return CSharpTextEdit(expression.textRange, if (bare) conditional else "($conditional)")
    }

    // ---- Add condition component (0.1.173)

    /** The tuple a governing expression is (`switch (a, b)` / `(a, b) switch`), else null. */
    private fun tupleOf(governing: CSharpExpression?): CSharpTupleExpression? = NativeCSharpContextEdits.unwrap(governing) as? CSharpTupleExpression ?: governing as? CSharpTupleExpression

    /** [pattern] with one more component `_`: a positional pattern of a tuple gets `, _`, anything else becomes `(pattern, _)`. */
    private fun widened(pattern: CSharpPattern, tuple: Boolean): String? {
        if (tuple) {
            val positional = (pattern as? CSharpRecursivePattern)?.takeIf { it.type == null && it.propertyPatternClause == null }?.positionalPatternClause ?: return null
            val close = positional.closeParenToken?.takeIf(::present) ?: return null
            val text = pattern.text
            val at = close.textRange.startOffset - pattern.textRange.startOffset
            return text.substring(0, at) + ", _" + text.substring(at)
        }
        return "(${pattern.text}, _)"
    }

    /**
     * One more component of the value a `switch` matches (Rider: "Add condition component"): `switch (x)` becomes `switch (x, |)` with the
     * caret on the new component, every label's pattern `(P, _)` (`, _` added to the positional patterns of a tuple), `default` / `_` as
     * they are. The new component is left for the user to write, as Rider's does.
     */
    fun addConditionComponent(statement: CSharpSwitchStatement): CSharpTextEdit? {
        val governing = statement.expression ?: return null
        val close = statement.closeParenToken?.takeIf(::present) ?: return null
        val tuple = tupleOf(governing)
        val edits = ArrayList<Triple<Int, Int, String>>()
        for (section in statement.sections) for (label in section.labels) {
            when (label) {
                is CSharpCaseSwitchLabel -> {
                    val value = label.value ?: return null
                    if (tuple != null) return null
                    edits += Triple(value.textRange.startOffset, value.textRange.endOffset, "(${value.text}, _)")
                }
                is CSharpCasePatternSwitchLabel -> {
                    val pattern = label.pattern ?: return null
                    if (pattern is CSharpDiscardPattern) continue
                    edits += Triple(pattern.textRange.startOffset, pattern.textRange.endOffset, widened(pattern, tuple != null) ?: return null)
                }
            }
        }
        val at = (tuple?.closeParenToken?.takeIf(::present) ?: if (tuple != null) return null else close).textRange.startOffset
        edits += Triple(at, at, ", $CARET")
        return applied(statement, edits)
    }

    /** The same for a `switch` expression: `x switch` becomes `(x, |) switch`, every arm's pattern `(P, _)`, the discard arm as it is. */
    fun addConditionComponent(expression: CSharpSwitchExpression): CSharpTextEdit? {
        val governing = expression.governingExpression ?: return null
        if (!present(expression.closeBraceToken)) return null
        val tuple = tupleOf(governing)
        val edits = ArrayList<Triple<Int, Int, String>>()
        for (arm in expression.arms) {
            val pattern = arm.pattern ?: return null
            if (pattern is CSharpDiscardPattern) continue
            edits += Triple(pattern.textRange.startOffset, pattern.textRange.endOffset, widened(pattern, tuple != null) ?: return null)
        }
        if (tuple != null) {
            val at = tuple.closeParenToken?.takeIf(::present)?.textRange?.startOffset ?: return null
            edits += Triple(at, at, ", $CARET")
        } else {
            val g = governing.textRange
            edits += Triple(g.startOffset, g.endOffset, "(${governing.text}, $CARET)")
        }
        return applied(expression, edits)
    }

    /** Where the caret goes in a replacement text: cut out by [applied]. */
    private const val CARET = "\u0000caret\u0000"

    /** [edits] (absolute ranges inside [element], none overlapping) applied to its text; the caret where [CARET] was. */
    private fun applied(element: PsiElement, edits: List<Triple<Int, Int, String>>): CSharpTextEdit {
        val base = element.textRange.startOffset
        val builder = StringBuilder(element.text)
        for ((from, to, replacement) in edits.sortedByDescending { it.first }) builder.replace(from - base, to - base, replacement)
        val caret = builder.indexOf(CARET)
        if (caret >= 0) builder.delete(caret, caret + CARET.length)
        return CSharpTextEdit(element.textRange, builder.toString(), caret.coerceAtLeast(0))
    }

    // ---- Generate type patterns (0.1.173)

    private val NOT_MATCHED = setOf("System.Object", "System.ValueType", "System.Enum", "System.String")

    /** The type a type pattern of [label] / an arm tests (`case Series series:` → `Series`), as written. */
    private fun testedType(pattern: CSharpPattern?): String? = when (pattern) {
        is CSharpDeclarationPattern -> pattern.type?.text
        is CSharpTypePattern -> pattern.type?.text
        is CSharpRecursivePattern -> pattern.type?.text
        is CSharpConstantPattern -> pattern.expression?.text
        else -> null
    }

    private fun testedTypes(statement: CSharpSwitchStatement): Set<String> = statement.sections.flatMap { it.labels }.mapNotNull { label ->
        when (label) {
            is CSharpCasePatternSwitchLabel -> testedType(label.pattern)
            is CSharpCaseSwitchLabel -> label.value?.text
            else -> null
        }
    }.map { it.substringAfterLast('.') }.toSet()

    /** A subtype to match: its name as the place can write it, and the name of its variable. */
    class Subtype(val name: String, val variable: String)

    /**
     * The direct subtypes of the type [governing] is of, from the solution's types (the base lists the supertype index names): what Rider's
     * "Generate type patterns" lists; the generic, static and already matched ones left out. Empty for a sealed or value type and for
     * `object`.
     */
    fun subtypes(governing: CSharpExpression, resolver: CSharpNameResolver, taken: Set<String>, matched: Set<String>): List<Subtype> {
        val type = resolver.typeOf(governing)?.let(resolver::unwrapNullable) ?: return emptyList()
        if (resolver.definitionName(type) in NOT_MATCHED || resolver.isValueType(type)) return emptyList()
        val project = governing.project
        val target = when (type) {
            is SemanticType.Source -> {
                if (type.info.kind == TypeKind.ENUM || type.info.parts.any { "sealed" in it.modifiers || "static" in it.modifiers }) return emptyList()
                type.info.parts.firstNotNullOfOrNull { it.element() }?.let(io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch::targetOf)
            }
            is SemanticType.Library -> {
                if (type.type.isSealed || type.type.isStatic || type.type.kind == IndexedTypeKind.ENUM) return emptyList()
                io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch.targetOf(io.github.dotnetsupport.lang.semantic.CSharpSymbol.LibraryType(type.type))
            }
            else -> null
        } ?: return emptyList()
        val session = io.github.dotnetsupport.lang.semantic.CSharpSemanticSession(project)
        val names = HashSet(taken)
        val found = ArrayList<Subtype>()
        for (declaration in io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch.directSubtypes(project, target, session)) {
            val typeDeclaration = declaration as? CSharpTypeDeclaration ?: continue
            if (typeDeclaration.typeParameterList != null || typeDeclaration.modifiers.any { it.text == "static" }) continue
            val simpleName = typeDeclaration.identifier?.text ?: continue
            if (simpleName in matched) continue
            val info = (typeDeclaration.containingFile as? CSharpFile)?.let { session.resolver(it).syntax.declaredType(typeDeclaration) } ?: continue
            val visible = resolver.typeOrNamespace(governing, simpleName, 0).any { (it as? io.github.dotnetsupport.lang.semantic.CSharpSymbol.SourceType)?.info?.key == info.key }
            val written = if (visible) simpleName else info.qualifiedName
            var variable = CSharpVariableNames.forType(simpleName).firstOrNull() ?: simpleName.replaceFirstChar { it.lowercase() }
            if (variable in NativeCSharpCompletionPlace.ALL_KEYWORDS) variable = "@$variable"
            var unique = variable
            var n = 1
            while (!names.add(unique)) unique = variable + n++
            found += Subtype(written, unique)
        }
        return found
    }

    /** The names declared in the member around [element]: what a pattern variable must not repeat. */
    fun takenNames(element: PsiElement): Set<String> {
        val owner = PsiTreeUtil.getParentOfType(element, CSharpMemberDeclaration::class.java) ?: return emptySet()
        val names = HashSet<String>()
        PsiTreeUtil.processElements(owner) { e ->
            when (e) {
                is CSharpVariableDeclarator -> e.identifier?.text?.let(names::add)
                is CSharpParameter -> e.identifier?.text?.let(names::add)
                is CSharpSingleVariableDesignation -> e.identifier?.text?.let(names::add)
            }
            true
        }
        return names
    }

    /** `case Series series:` + `break;` for each subtype, after the last section that is not `default` (Rider: "Generate type patterns"). */
    fun addTypePatterns(statement: CSharpSwitchStatement, subtypes: List<Subtype>, text: CharSequence, unit: String): CSharpTextEdit? {
        if (subtypes.isEmpty()) return null
        val indent = sectionIndent(statement, text, unit)
        val lines = subtypes.flatMap { listOf("${indent}case ${it.name} ${it.variable}:", "$indent${unit}break;") }
        val defaultSection = statement.sections.firstOrNull { s -> s.labels.any { it is CSharpDefaultSwitchLabel } }
        if (defaultSection != null) {
            val at = lineStart(text, defaultSection.textRange.startOffset)
            return CSharpTextEdit(TextRange(at, at), lines.joinToString("") { "$it\n" })
        }
        return insertBeforeClose(statement, text, lines)
    }

    /** The same arms for a `switch` expression: `Series series => throw new NotImplementedException(),` before the discard arm or at the end. */
    fun addTypePatternArms(expression: CSharpSwitchExpression, subtypes: List<Subtype>, text: CharSequence, unit: String): CSharpTextEdit? {
        if (subtypes.isEmpty()) return null
        val open = expression.openBraceToken?.takeIf(::present) ?: return null
        val close = expression.closeBraceToken?.takeIf(::present) ?: return null
        val armIndent = expression.arms.firstOrNull()?.let { lineIndent(text, it.textRange.startOffset) } ?: (ownIndent(text, expression) + unit)
        val arms = subtypes.map { "${it.name} ${it.variable} => throw new NotImplementedException()" }
        val discard = discardArm(expression)
        if (discard != null) {
            val at = discard.textRange.startOffset
            return CSharpTextEdit(TextRange(at, at), arms.joinToString("") { "$it,\n$armIndent" })
        }
        val last = expression.arms.lastOrNull()
        if (last == null) {
            return CSharpTextEdit(TextRange(open.textRange.endOffset, close.textRange.startOffset), arms.joinToString(",\n", prefix = "\n", postfix = "\n" + ownIndent(text, expression)) { armIndent + it })
        }
        val separated = expression.armsSeparators.size >= expression.arms.size
        val at = last.textRange.endOffset
        return CSharpTextEdit(TextRange(at, at), arms.joinToString("", prefix = if (separated) "" else ",") { "\n$armIndent$it," }.removeSuffix(","))
    }

    fun testedTypesOf(expression: CSharpSwitchExpression): Set<String> = expression.arms.mapNotNull { testedType(it.pattern) }.map { it.substringAfterLast('.') }.toSet()

    fun testedTypesOf(statement: CSharpSwitchStatement): Set<String> = testedTypes(statement)
}

/** Alt+Enter on `switch (enum)`: the sections of the members no label covers, and `default` (Rider: "Add 'switch' sections"). */
class NativeCSharpAddSwitchSectionsIntention : NativeCSharpContextAction("Add 'switch' sections", needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val statement = NativeCSharpSwitchEdits.switchAt(file, editor.caretModel.offset) ?: return null
        val type = statement.expression?.let { NativeCSharpSwitchEdits.enumOf(resolver(file), it) } ?: return null
        return NativeCSharpSwitchEdits.addSections(statement, type, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}

/** Alt+Enter on `enum switch { … }`: the arms of the members no arm names, and `_` (Rider: "Add missing arms"). */
class NativeCSharpAddMissingArmsIntention : NativeCSharpContextAction("Add missing arms", needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val expression = NativeCSharpSwitchEdits.switchExpressionAt(file, editor.caretModel.offset) ?: return null
        val type = expression.governingExpression?.let { NativeCSharpSwitchEdits.enumOf(resolver(file), it) } ?: return null
        return NativeCSharpSwitchEdits.addMissingArms(expression, type, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}

/** Alt+Enter on a `switch` without `default`: `default: break;` at its end (Rider: "Add 'default' section"). */
class NativeCSharpAddDefaultSectionIntention : NativeCSharpContextAction("Add 'default' section") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpSwitchEdits.switchAt(file, editor.caretModel.offset)?.let { NativeCSharpSwitchEdits.addDefault(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on a `switch` with unbraced sections: `{ }` around each section's statements (Rider: "Add section braces"). */
class NativeCSharpAddSectionBracesIntention : NativeCSharpContextAction("Add section braces") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpSwitchEdits.switchAt(file, editor.caretModel.offset)?.let { NativeCSharpSwitchEdits.addSectionBraces(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on a `switch` whose sections return / assign one variable: the `switch` expression (Rider: "To 'switch' expression"). */
class NativeCSharpToSwitchExpressionIntention : NativeCSharpContextAction("To 'switch' expression") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpSwitchEdits.switchAt(file, editor.caretModel.offset)?.let { NativeCSharpSwitchEdits.toSwitchExpression(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on a `switch` statement: the `if` / `else if` / `else` chain of its sections (Rider: "To 'if' statements"). */
class NativeCSharpSwitchToIfIntention : NativeCSharpContextAction("To 'if' statements") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val text = editor.document.charsSequence
        val unit = NativeCSharpContextEdits.unit(file)
        NativeCSharpSwitchEdits.switchAt(file, editor.caretModel.offset)?.let { return NativeCSharpSwitchEdits.toIfStatements(it, text, unit) }
        // a `switch` expression that is returned or assigned: the `if` chain returning / assigning each arm's value (0.1.173)
        return NativeCSharpSwitchEdits.switchExpressionAt(file, editor.caretModel.offset)?.let { NativeCSharpSwitchEdits.expressionToIfStatements(it, text, unit) }
    }
}

/** Alt+Enter on a `switch` expression that is returned or assigned: the `switch` statement (Rider: "To 'switch' statement"). */
class NativeCSharpToSwitchStatementIntention : NativeCSharpContextAction("To 'switch' statement") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpSwitchEdits.switchExpressionAt(file, editor.caretModel.offset)?.let { NativeCSharpSwitchEdits.toSwitchStatement(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on a `switch` expression of one arm and `_`: the `?:` it stands for (Rider: "To ?: expression"). */
class NativeCSharpSwitchToConditionalIntention : NativeCSharpContextAction("To ?: expression") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpSwitchEdits.switchExpressionAt(file, editor.caretModel.offset)?.let(NativeCSharpSwitchEdits::toConditional)
}

/** Alt+Enter on a `switch`: one more component of the value it matches, every pattern widened with `_` (Rider: "Add condition component"). */
class NativeCSharpAddConditionComponentIntention : NativeCSharpContextAction("Add condition component") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        NativeCSharpSwitchEdits.switchAt(file, editor.caretModel.offset)?.let { return NativeCSharpSwitchEdits.addConditionComponent(it) }
        return NativeCSharpSwitchEdits.switchExpressionAt(file, editor.caretModel.offset)?.let { NativeCSharpSwitchEdits.addConditionComponent(it) }
    }
}

/**
 * Alt+Enter on a `switch` over a class or an interface: a section (an arm) for each direct subtype of the solution no pattern tests yet,
 * `case Series series:` + `break;` (Rider: "Generate type patterns…", without its dialog: every subtype).
 */
class NativeCSharpGenerateTypePatternsIntention : NativeCSharpContextAction("Generate type patterns", needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val text = editor.document.charsSequence
        val unit = NativeCSharpContextEdits.unit(file)
        NativeCSharpSwitchEdits.switchAt(file, editor.caretModel.offset)?.let { statement ->
            val governing = statement.expression ?: return null
            val subtypes = NativeCSharpSwitchEdits.subtypes(governing, resolver(file), NativeCSharpSwitchEdits.takenNames(statement), NativeCSharpSwitchEdits.testedTypesOf(statement))
            return NativeCSharpSwitchEdits.addTypePatterns(statement, subtypes, text, unit)
        }
        val expression = NativeCSharpSwitchEdits.switchExpressionAt(file, editor.caretModel.offset) ?: return null
        val governing = expression.governingExpression ?: return null
        val subtypes = NativeCSharpSwitchEdits.subtypes(governing, resolver(file), NativeCSharpSwitchEdits.takenNames(expression), NativeCSharpSwitchEdits.testedTypesOf(expression))
        return NativeCSharpSwitchEdits.addTypePatternArms(expression, subtypes, text, unit)
    }
}
