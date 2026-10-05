package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SyntaxTraverser
import com.intellij.refactoring.introduce.inplace.OccurrencesChooser
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpTypeDisplay
import io.github.dotnetsupport.lang.semantic.CSharpTypeFacts
import io.github.dotnetsupport.lsp.RoslynServerStatus

/*
 * Alt+Enter context actions on csharp-psi's tree, without the language server (CSHARP_PSI_MIGRATION.md, task A7): `if` ↔ `?:` (the return
 * and the assignment forms), block body ↔ expression body (methods, local functions, constructors, operators, properties, indexers,
 * accessors), introduce variable / inline variable, `var` ↔ explicit type (the types of expressions, C2). The edits are pure functions of
 * the tree and the text ([NativeCSharpContextEdits], tested without an editor); the names are Rider's.
 */

/** A replacement of the text: the edits of an action, applied from the end so the earlier offsets hold. */
typealias CSharpEdits = List<Pair<TextRange, String>>

object NativeCSharpContextEdits {
    // ---- the tree

    fun unwrap(expression: CSharpExpression?): CSharpExpression? {
        var at = expression
        while (at is CSharpParenthesizedExpression) at = at.expression
        return at
    }

    private fun present(token: PsiElement?): Boolean = (token?.textLength ?: 0) > 0

    /** The one statement of a braced block with nothing else in it (no comment, no directive); [statement] itself when it is no block. */
    fun single(statement: CSharpStatement?): CSharpStatement? {
        if (statement !is CSharpBlock) return statement
        val inner = statement.statements.singleOrNull() ?: return null
        val open = statement.openBraceToken?.takeIf(::present) ?: return null
        val close = statement.closeBraceToken?.takeIf(::present) ?: return null
        val text = statement.text
        val base = statement.textRange.startOffset
        if (text.substring(open.textRange.endOffset - base, inner.textRange.startOffset - base).isNotBlank()) return null
        if (text.substring(inner.textRange.endOffset - base, close.textRange.startOffset - base).isNotBlank()) return null
        return inner
    }

    /** A comment in [owner] that is not inside one of [kept]: the edit would drop it. */
    private fun losesComment(owner: PsiElement, kept: List<PsiElement?>): Boolean =
        PsiTreeUtil.findChildrenOfType(owner, PsiComment::class.java).any { comment -> kept.none { it != null && PsiTreeUtil.isAncestor(it, comment, false) } }

    private fun leafAt(file: PsiFile, offset: Int): List<PsiElement> = listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)

    // ---- text

    fun unit(file: PsiFile): String {
        val options = CodeStyle.getSettings(file).getIndentOptions(CSharpFileType)
        return if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
    }

    private fun indentOf(text: CharSequence, offset: Int): String = NativeCSharpUsingEdits.indentOf(text, offset).orEmpty()

    /** [offset] moved back over the whitespace before it: where `{` of a body begins its separation from the header. */
    private fun trimBack(text: CharSequence, offset: Int): Int {
        var at = offset
        while (at > 0 && text[at - 1].isWhitespace()) at--
        return at
    }

    // ---- if ↔ ?:

    /** The `if` statement whose header (`if (…)`) holds [offset]. */
    fun ifAt(file: PsiFile, offset: Int): CSharpIfStatement? {
        for (leaf in leafAt(file, offset)) {
            val statement = PsiTreeUtil.getParentOfType(leaf, CSharpIfStatement::class.java, false) ?: continue
            val end = statement.closeParenToken?.takeIf(::present)?.textRange?.endOffset ?: continue
            if (offset >= statement.textRange.startOffset && offset <= end) return statement
        }
        return null
    }

    /**
     * `if (c) return a; else return b;` and `if (c) return a; return b;` → `return c ? a : b;`; `if (c) x = a; else x = b;` → `x = c ? a :
     * b;` (the same target and operator). The branches may be braced blocks of one statement.
     */
    fun ifToConditional(statement: CSharpIfStatement, text: CharSequence): CSharpTextEdit? {
        val condition = statement.condition ?: return null
        if (!present(statement.closeParenToken)) return null
        val whenTrue = single(statement.statement) ?: return null
        val elseClause = statement.`else`
        if (elseClause != null) {
            val whenFalse = single(elseClause.statement) ?: return null
            val combined = combine(condition, whenTrue, whenFalse) ?: return null
            if (losesComment(statement, combined.second)) return null
            return CSharpTextEdit(statement.textRange, combined.first)
        }
        if (whenTrue !is CSharpReturnStatement) return null
        val block = statement.parent as? CSharpBlock ?: return null
        val next = block.statements.getOrNull(block.statements.indexOf(statement) + 1) as? CSharpReturnStatement ?: return null
        if (text.subSequence(statement.textRange.endOffset, next.textRange.startOffset).isNotBlank()) return null
        val combined = combine(condition, whenTrue, next) ?: return null
        if (losesComment(statement, combined.second) || losesComment(next, combined.second)) return null
        return CSharpTextEdit(TextRange(statement.textRange.startOffset, next.textRange.endOffset), combined.first)
    }

    /** The statement of `?:` for the two branches, and the expressions it keeps. */
    private fun combine(condition: CSharpExpression, a: CSharpStatement, b: CSharpStatement): Pair<String, List<PsiElement?>>? {
        if (a is CSharpReturnStatement && b is CSharpReturnStatement) {
            if (!present(a.semicolonToken) || !present(b.semicolonToken)) return null
            val x = a.expression ?: return null
            val y = b.expression ?: return null
            return "return ${conditionText(condition)} ? ${x.text} : ${y.text};" to listOf(condition, x, y)
        }
        if (a is CSharpExpressionStatement && b is CSharpExpressionStatement) {
            if (!present(a.semicolonToken) || !present(b.semicolonToken)) return null
            val p = a.expression as? CSharpAssignmentExpression ?: return null
            val q = b.expression as? CSharpAssignmentExpression ?: return null
            val operator = p.operatorToken?.text ?: return null
            if (operator != q.operatorToken?.text) return null
            val left = p.left ?: return null
            if (CSharpStubsText.collapse(left.text) != CSharpStubsText.collapse(q.left?.text ?: return null)) return null
            val x = p.right ?: return null
            val y = q.right ?: return null
            return "${left.text} $operator ${conditionText(condition)} ? ${x.text} : ${y.text};" to listOf(condition, left, x, y)
        }
        return null
    }

    /** The condition of `?:`: in parentheses when it binds looser than `??` or would read as a nullable type (`x is int ? a : b`). */
    private fun conditionText(condition: CSharpExpression): String {
        val loose = condition is CSharpAssignmentExpression || condition is CSharpConditionalExpression || condition is CSharpAnonymousFunctionExpression ||
            condition is CSharpIsPatternExpression || condition is CSharpBinaryExpression && condition.operatorToken?.text.let { it == "is" || it == "as" }
        return if (loose) "(${condition.text})" else condition.text
    }

    /** The `?:` at [offset] that is the whole value of a `return` or of an assignment statement, with that statement. */
    fun conditionalAt(file: PsiFile, offset: Int): Pair<CSharpStatement, CSharpConditionalExpression>? {
        for (leaf in leafAt(file, offset)) {
            var at: PsiElement? = PsiTreeUtil.getParentOfType(leaf, CSharpConditionalExpression::class.java, false)
            while (at is CSharpConditionalExpression) {
                statementOfConditional(at)?.let { return it to at as CSharpConditionalExpression }
                at = PsiTreeUtil.getParentOfType(at, CSharpConditionalExpression::class.java, true, CSharpStatement::class.java, CSharpAnonymousFunctionExpression::class.java)
            }
        }
        return null
    }

    private fun statementOfConditional(conditional: CSharpConditionalExpression): CSharpStatement? {
        var top: PsiElement = conditional
        while (top.parent is CSharpParenthesizedExpression) top = top.parent
        return when (val parent = top.parent) {
            is CSharpReturnStatement -> parent
            is CSharpAssignmentExpression -> (parent.parent as? CSharpExpressionStatement)?.takeIf { parent.right == top }
            else -> null
        }
    }

    /** `return c ? a : b;` / `x = c ? a : b;` → `if (c) { … } else { … }`; a `throw` branch becomes a `throw` statement. */
    fun conditionalToIf(statement: CSharpStatement, conditional: CSharpConditionalExpression, text: CharSequence, unit: String): CSharpTextEdit? {
        if (statement.parent !is CSharpBlock) return null
        val condition = conditional.condition ?: return null
        val whenTrue = conditional.whenTrue ?: return null
        val whenFalse = conditional.whenFalse ?: return null
        if (!present(conditional.colonToken)) return null
        val branch: (CSharpExpression) -> String? = when (statement) {
            is CSharpReturnStatement -> { e -> throwStatement(e) ?: "return ${e.text};" }
            is CSharpExpressionStatement -> {
                val assignment = statement.expression as? CSharpAssignmentExpression ?: return null
                val left = assignment.left ?: return null
                val operator = assignment.operatorToken?.text ?: return null
                if (losesComment(statement, listOf(left, condition, whenTrue, whenFalse))) return null
                ({ e -> throwStatement(e) ?: "${left.text} $operator ${e.text};" })
            }
            else -> return null
        }
        val semicolon = (statement as? CSharpReturnStatement)?.semicolonToken ?: (statement as? CSharpExpressionStatement)?.semicolonToken
        if (!present(semicolon)) return null
        if (statement is CSharpReturnStatement && losesComment(statement, listOf(condition, whenTrue, whenFalse))) return null
        val a = branch(whenTrue) ?: return null
        val b = branch(whenFalse) ?: return null
        val indent = indentOf(text, statement.textRange.startOffset)
        val inner = indent + unit
        val conditionText = (unwrap(condition).takeIf { condition is CSharpParenthesizedExpression } ?: condition).text
        val result = "if ($conditionText)\n$indent{\n$inner$a\n$indent}\n${indent}else\n$indent{\n$inner$b\n$indent}"
        return CSharpTextEdit(statement.textRange, result)
    }

    /**
     * A `?:` at [offset] deeper in a statement (an argument, an operand): the statement it belongs to, which `if` can repeat with either
     * branch, as Rider's "Convert '?:' to 'if' statement" does there. Not where moving the condition first would change whether it runs
     * (the right of `&&`, a branch of another `?:`, after `?.`, a lambda, a loop's condition).
     */
    fun conditionalSplitAt(file: PsiFile, offset: Int): Pair<CSharpStatement, CSharpConditionalExpression>? {
        for (leaf in leafAt(file, offset)) {
            var at: PsiElement? = PsiTreeUtil.getParentOfType(leaf, CSharpConditionalExpression::class.java, false)
            while (at is CSharpConditionalExpression) {
                val statement = enclosingStatement(at)
                if (statement != null && splittable(statement) && movable(at, statement)) return statement to at
                at = PsiTreeUtil.getParentOfType(at, CSharpConditionalExpression::class.java, true, CSharpStatement::class.java, CSharpAnonymousFunctionExpression::class.java)
            }
        }
        return null
    }

    private fun splittable(statement: CSharpStatement): Boolean = when (statement) {
        is CSharpExpressionStatement, is CSharpReturnStatement -> true
        is CSharpLocalDeclarationStatement -> statement.declaration?.variables?.size == 1 && statement.usingKeyword == null && statement.modifiers.isEmpty() &&
            statement.declaration?.variables?.single()?.initializer?.value != null && statement.declaration?.type !is CSharpRefType
        else -> false
    }

    /**
     * `Foo(c ? a : b);` → `if (c) { Foo(a); } else { Foo(b); }`; `var x = Foo(c ? a : b);` → `T x;` and `x = Foo(a);` / `x = Foo(b);` in the
     * branches (the type written out); a `throw` branch becomes the `throw` statement.
     */
    fun splitConditional(statement: CSharpStatement, conditional: CSharpConditionalExpression, text: CharSequence, unit: String, resolver: CSharpNameResolver?): CSharpTextEdit? {
        if (statement.parent !is CSharpBlock) return null
        val condition = conditional.condition ?: return null
        val whenTrue = conditional.whenTrue ?: return null
        val whenFalse = conditional.whenFalse ?: return null
        if (!present(conditional.colonToken)) return null
        val indent = indentOf(text, statement.textRange.startOffset)
        val inner = indent + unit
        var declaration = ""
        // the part of the statement that repeats in each branch, the `?:` cut out
        val repeated: TextRange
        var head = ""
        if (statement is CSharpLocalDeclarationStatement) {
            val declarator = statement.declaration!!.variables.single()
            val value = declarator.initializer!!.value!!
            if (!value.textRange.contains(conditional.textRange)) return null
            val written = statement.declaration?.type?.takeUnless { it is CSharpIdentifierName && it.identifier?.text == "var" }?.let { CSharpStubsText.collapse(it.text) }
                ?: resolver?.let { r -> r.typeOf(value)?.let { CSharpTypeFacts.written(r, it, value) } } ?: return null
            val name = declarator.identifier?.text ?: return null
            declaration = "$written $name;\n$indent"
            head = "$name = "
            repeated = value.textRange
        } else repeated = statement.textRange
        val base = repeated.startOffset
        val whole = text.subSequence(repeated.startOffset, repeated.endOffset).toString()
        // `"a" + (c ? 1 : 2)`: the parentheses go with the `?:` when the branch needs none
        var wrapped: PsiElement = conditional
        while (wrapped.parent is CSharpParenthesizedExpression) wrapped = wrapped.parent
        fun branch(e: CSharpExpression): String {
            // `x = c ? a : throw …` (the `?:` is all the statement computes): the `throw` alone
            if (e is CSharpThrowExpression && isAll(conditional, statement)) return throwStatement(e)!!
            val cut = if (isPrimary(e)) wrapped.textRange else conditional.textRange
            val body = whole.substring(0, cut.startOffset - base) + e.text + whole.substring(cut.endOffset - base)
            val line = if (statement is CSharpLocalDeclarationStatement) "$head$body;" else body
            return line.replace("\n$indent", "\n$inner")
        }
        if (whenTrue is CSharpThrowExpression && !isAll(conditional, statement) || whenFalse is CSharpThrowExpression && !isAll(conditional, statement)) return null
        val conditionText = (unwrap(condition).takeIf { condition is CSharpParenthesizedExpression } ?: condition).text
        val result = "${declaration}if ($conditionText)\n$indent{\n$inner${branch(whenTrue)}\n$indent}\n${indent}else\n$indent{\n$inner${branch(whenFalse)}\n$indent}"
        return CSharpTextEdit(statement.textRange, result)
    }

    /** Whether the `?:` is the whole value the statement returns or assigns (then a `throw` branch can stand alone). */
    private fun isAll(conditional: CSharpConditionalExpression, statement: CSharpStatement): Boolean {
        var top: PsiElement = conditional
        while (top.parent is CSharpParenthesizedExpression) top = top.parent
        return when (val parent = top.parent) {
            is CSharpReturnStatement -> true
            is CSharpEqualsValueClause -> parent.parent?.parent?.parent == statement
            is CSharpAssignmentExpression -> parent.right == top && parent.parent == statement
            else -> false
        }
    }

    private fun throwStatement(expression: CSharpExpression): String? =
        (unwrap(expression) as? CSharpThrowExpression)?.expression?.let { "throw ${it.text};" }

    // ---- block body ↔ expression body

    /**
     * The member, local function or accessor whose header holds [offset]: from its start to its body (`{` of the block, or the end of `=>`;
     * for a property, the start of its accessor list). Not in the body: the caret in a lambda of a body is not on the method.
     */
    fun bodyOwnerAt(file: PsiFile, offset: Int): PsiElement? {
        for (leaf in leafAt(file, offset)) {
            var at: PsiElement? = leaf
            while (at != null && at !is PsiFile) {
                if (isBodyOwner(at)) {
                    val headerEnd = headerEnd(at)
                    if (headerEnd != null && offset >= at.textRange.startOffset && offset <= headerEnd) return at
                    // in the body of this one: not on an outer one either
                    if (headerEnd != null && offset > headerEnd) return null
                }
                at = at.parent
            }
        }
        return null
    }

    private fun isBodyOwner(element: PsiElement): Boolean = element is CSharpBaseMethodDeclaration || element is CSharpLocalFunctionStatement ||
        element is CSharpAccessorDeclaration || element is CSharpPropertyDeclaration || element is CSharpIndexerDeclaration

    private fun headerEnd(owner: PsiElement): Int? {
        val (body, arrow) = bodyOf(owner)
        arrow?.let { return it.arrowToken?.textRange?.endOffset ?: it.textRange.startOffset }
        if (owner is CSharpBasePropertyDeclaration) return owner.accessorList?.textRange?.startOffset
        return body?.textRange?.startOffset
    }

    private fun bodyOf(owner: PsiElement): Pair<CSharpBlock?, CSharpArrowExpressionClause?> = when (owner) {
        is CSharpBaseMethodDeclaration -> owner.body to owner.expressionBody
        is CSharpLocalFunctionStatement -> owner.body to owner.expressionBody
        is CSharpAccessorDeclaration -> owner.body to owner.expressionBody
        is CSharpPropertyDeclaration -> null to owner.expressionBody
        is CSharpIndexerDeclaration -> null to owner.expressionBody
        else -> null to null
    }

    /** The body of one `return e;` / `e;` / `throw e;` → `=> e;`; a property or indexer with only a `get` of one such → `=> e;`. */
    fun toExpressionBody(owner: PsiElement, text: CharSequence): CSharpTextEdit? {
        if (owner is CSharpBasePropertyDeclaration) {
            if (owner is CSharpPropertyDeclaration && (owner.initializer != null || owner.expressionBody != null)) return null
            if (owner is CSharpIndexerDeclaration && owner.expressionBody != null) return null
            val list = owner.accessorList ?: return null
            if (!present(list.closeBraceToken)) return null
            val accessor = list.accessors.singleOrNull() ?: return null
            if (accessor.keyword?.text != "get" || accessor.modifiers.isNotEmpty() || accessor.attributeLists.isNotEmpty()) return null
            val expression = accessor.expressionBody?.expression?.takeIf { present(accessor.semicolonToken) }?.text
                ?: accessor.body?.let { bodyExpression(it, returns = true) } ?: return null
            if (losesComment(list, listOf(accessor.expressionBody?.expression, accessor.body?.let(::single)))) return null
            return CSharpTextEdit(TextRange(trimBack(text, list.textRange.startOffset), list.textRange.endOffset), " => $expression;")
        }
        val (body, arrow) = bodyOf(owner)
        if (arrow != null || body == null || !present(body.closeBraceToken)) return null
        val expression = bodyExpression(body, returns(owner)) ?: return null
        return CSharpTextEdit(TextRange(trimBack(text, body.textRange.startOffset), body.textRange.endOffset), " => $expression;")
    }

    /** The expression of a body of one statement: `return e;` (where the member returns), `e;` (where it does not), `throw e;`. */
    private fun bodyExpression(body: CSharpBlock, returns: Boolean): String? {
        val statement = single(body) ?: return null
        val expression = when (statement) {
            is CSharpReturnStatement -> statement.expression?.takeIf { returns && present(statement.semicolonToken) }?.text
            is CSharpExpressionStatement -> statement.expression?.takeIf { !returns && present(statement.semicolonToken) }?.text
            is CSharpThrowStatement -> statement.expression?.takeIf { present(statement.semicolonToken) }?.let { "throw ${it.text}" }
            else -> null
        } ?: return null
        val kept = when (statement) {
            is CSharpReturnStatement -> statement.expression
            is CSharpExpressionStatement -> statement.expression
            is CSharpThrowStatement -> statement.expression
            else -> null
        }
        if (losesComment(statement, listOf(kept))) return null
        return expression
    }

    /** `=> e;` → a block of `return e;` (`e;` where nothing is returned, `throw e;` for a throw expression). */
    fun toBlockBody(owner: PsiElement, text: CharSequence, unit: String): CSharpTextEdit? {
        val (_, arrow) = bodyOf(owner)
        arrow ?: return null
        val expression = arrow.expression ?: return null
        val semicolon = when (owner) {
            is CSharpBaseMethodDeclaration -> owner.semicolonToken
            is CSharpLocalFunctionStatement -> owner.semicolonToken
            is CSharpAccessorDeclaration -> owner.semicolonToken
            is CSharpPropertyDeclaration -> owner.semicolonToken
            is CSharpIndexerDeclaration -> owner.semicolonToken
            else -> null
        }?.takeIf(::present) ?: return null
        val statement = throwStatement(expression) ?: if (returns(owner)) "return ${expression.text};" else "${expression.text};"
        val range = TextRange(trimBack(text, arrow.textRange.startOffset), semicolon.textRange.endOffset)
        val indent = indentOf(text, owner.textRange.startOffset)
        val result = when {
            owner is CSharpBasePropertyDeclaration -> "\n$indent{\n$indent${unit}get { $statement }\n$indent}"
            // an accessor of a property written on one line stays on it
            owner is CSharpAccessorDeclaration && owner.parent?.text?.contains('\n') != true -> " { $statement }"
            else -> "\n$indent{\n$indent$unit$statement\n$indent}"
        }
        return CSharpTextEdit(range, result)
    }

    /** Whether the body of [owner] returns a value: not of `void`, of `async Task` / `async ValueTask`, a constructor, a setter. */
    private fun returns(owner: PsiElement): Boolean = when (owner) {
        is CSharpMethodDeclaration -> returnsValue(owner.returnType, owner.modifiers)
        is CSharpLocalFunctionStatement -> returnsValue(owner.returnType, owner.modifiers)
        is CSharpOperatorDeclaration, is CSharpConversionOperatorDeclaration -> true
        is CSharpAccessorDeclaration -> owner.keyword?.text == "get"
        is CSharpBasePropertyDeclaration -> true
        else -> false
    }

    private fun returnsValue(type: CSharpType?, modifiers: List<PsiElement>): Boolean {
        if ((type as? CSharpPredefinedType)?.keyword?.text == "void") return false
        val name = type?.text?.let(CSharpStubsText::collapse)?.removePrefix("global::")?.removePrefix("System.Threading.Tasks.")
        return !(modifiers.any { it.text == "async" } && (name == "Task" || name == "ValueTask"))
    }

    // ---- var ↔ explicit type

    /** The type of a local's declaration (or of `foreach`) at [offset] — on the type or the name — with what gives the local its value. */
    class TypeSite(val type: CSharpType, val value: CSharpExpression?, val foreach: CSharpForEachStatement?, val constant: Boolean)

    fun typeSiteAt(file: PsiFile, offset: Int): TypeSite? {
        for (leaf in leafAt(file, offset)) {
            val foreach = PsiTreeUtil.getParentOfType(leaf, CSharpForEachStatement::class.java, false)
            if (foreach != null) {
                val type = foreach.type
                val name = foreach.identifier
                if (type != null && name != null && offset >= type.textRange.startOffset && offset <= name.textRange.endOffset) return TypeSite(type, null, foreach, false)
            }
            val declaration = PsiTreeUtil.getParentOfType(leaf, CSharpVariableDeclaration::class.java, false) ?: continue
            val type = declaration.type ?: continue
            val variable = declaration.variables.singleOrNull() ?: continue
            val name = variable.identifier ?: continue
            if (offset < type.textRange.startOffset || offset > name.textRange.endOffset) continue
            val owner = declaration.parent
            if (owner !is CSharpLocalDeclarationStatement && owner !is CSharpUsingStatement && owner !is CSharpForStatement) continue
            val constant = (owner as? CSharpLocalDeclarationStatement)?.modifiers?.any { it.text == "const" } == true
            return TypeSite(type, variable.initializer?.value, null, constant)
        }
        return null
    }

    private fun isVar(type: CSharpType): Boolean = type is CSharpIdentifierName && type.identifier?.text == "var"

    /** `var` → the type it stands for, written as at this place (`List<int>` under `using System.Collections.Generic;`). */
    fun useExplicitType(site: TypeSite, resolver: CSharpNameResolver): CSharpTextEdit? {
        if (!isVar(site.type) || site.foreach == null && site.value == null) return null
        val type = resolver.expressionType(site.type) ?: return null
        val written = CSharpTypeFacts.written(resolver, type, site.type) ?: return null
        if (written == "var" || written == "void") return null
        return CSharpTextEdit(site.type.textRange, written)
    }

    /** The explicit type → `var`, where the value has exactly that type (not `long x = 1`, not `IList<int> x = new List<int>()`). */
    fun useVar(site: TypeSite, resolver: CSharpNameResolver): CSharpTextEdit? {
        val type = site.type
        if (isVar(type) || type is CSharpRefType || type is CSharpScopedType || site.constant) return null
        val actual = if (site.foreach != null) {
            site.foreach.expression?.let(resolver::typeOf)?.let(resolver::elementType)
        } else {
            val value = unwrap(site.value) ?: return null
            // what has no type of its own takes the declared one: `var` would lose it
            if (value is CSharpInitializerExpression || value is CSharpStackAllocArrayCreationExpression || value is CSharpAnonymousFunctionExpression ||
                value is CSharpImplicitObjectCreationExpression || value is CSharpCollectionExpression || value is CSharpLiteralExpression && value.text.let { it == "null" || it == "default" } ||
                value is CSharpConditionalExpression || value is CSharpSwitchExpression) return null
            resolver.typeOf(site.value!!)
        } ?: return null
        val declared = resolver.resolveType(type) ?: return null
        val shown = CSharpTypeDisplay.display(declared) ?: return null
        if (shown != CSharpTypeDisplay.display(actual)) return null
        return CSharpTextEdit(type.textRange, "var")
    }

    // ---- introduce variable

    /** What "Introduce variable" takes out: [expression] before [statement]; [whole]: the expression is all the statement does (`Foo();`). */
    class Extraction(val expression: CSharpExpression, val statement: CSharpStatement, val whole: Boolean, val name: String)

    /**
     * The expression to put into a variable: the selected one, or the one at the caret — a call or a member access with its receiver, an
     * `await` with what it awaits, a literal, an operator with its operands. Not where moving it before the statement would change when
     * or whether it runs (the right of `&&` / `||` / `??`, a branch of `?:`, after `?.`, a loop's condition, a lambda), not a target of
     * an assignment, `ref` / `out`, a constant place; its type must be known (`var` needs one).
     */
    fun extractionAt(file: CSharpFile, selection: TextRange?, offset: Int, resolver: CSharpNameResolver): Extraction? {
        val expression = (if (selection != null && !selection.isEmpty) selected(file, selection) else atCaret(file, offset)) ?: return null
        val statement = enclosingStatement(expression) ?: return null
        if (!movable(expression, statement)) return null
        val type = resolver.typeOf(expression) ?: return null
        if ((type as? io.github.dotnetsupport.lang.semantic.SemanticType.Library)?.type?.fullName == "System.Void") return null
        val whole = statement is CSharpExpressionStatement && statement.expression == expression
        if (whole && expression is CSharpAssignmentExpression) return null
        val written = CSharpTypeFacts.written(resolver, type, expression)
        return Extraction(expression, statement, whole, nameFor(expression, written, statement))
    }

    /** The selected expression, or the one at the caret as "Introduce variable" takes it (a call with its receiver, an operator...). */
    fun expressionAt(file: PsiFile, selection: TextRange?, offset: Int): CSharpExpression? =
        if (selection != null && !selection.isEmpty) selected(file, selection) else atCaret(file, offset)

    /** A name for a new variable or parameter holding [expression]: by the member it calls, else by its type; unique among the locals around [at]. */
    fun suggestedName(expression: CSharpExpression, resolver: CSharpNameResolver, at: PsiElement): String {
        val written = resolver.typeOf(expression)?.let { CSharpTypeFacts.written(resolver, it, expression) }
        return nameFor(expression, written, at)
    }

    private fun selected(file: PsiFile, selection: TextRange): CSharpExpression? {
        val text = file.text
        var start = selection.startOffset
        var end = selection.endOffset
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        var element: PsiElement? = file.findElementAt(start)
        while (element != null && element !is PsiFile && element.textRange.startOffset == start) {
            if (element.textRange.endOffset == end && element is CSharpExpression) return element
            if (element.textRange.endOffset > end) break
            element = element.parent
        }
        return null
    }

    private fun atCaret(file: PsiFile, offset: Int): CSharpExpression? {
        for (leaf in leafAt(file, offset)) {
            if (leaf.text.isBlank() || leaf.text == ";") continue
            var expression = PsiTreeUtil.getParentOfType(leaf, CSharpExpression::class.java, false) ?: continue
            while (true) {
                val parent = expression.parent
                expression = when {
                    parent is CSharpMemberAccessExpression && parent.nameElement == expression -> parent
                    parent is CSharpMemberBindingExpression -> return null
                    parent is CSharpInvocationExpression && parent.expression == expression -> parent
                    parent is CSharpElementAccessExpression && parent.expression == expression && expression !is CSharpSimpleName -> parent
                    parent is CSharpObjectCreationExpression && parent.type == expression -> parent
                    parent is CSharpGenericName || parent is CSharpQualifiedName || parent is CSharpTypeArgumentList -> parent as? CSharpExpression ?: return null
                    parent is CSharpAwaitExpression -> parent
                    else -> break
                }
            }
            // a local or a parameter by itself: a variable already
            if (expression is CSharpSimpleName) return null
            return expression
        }
        return null
    }

    private fun enclosingStatement(expression: CSharpExpression): CSharpStatement? {
        var at: PsiElement = expression
        while (true) {
            val parent = at.parent ?: return null
            if (parent is CSharpAnonymousFunctionExpression || parent is CSharpLocalFunctionStatement || parent is CSharpMemberDeclaration || parent is PsiFile) return null
            if (parent is CSharpStatement) return parent.takeIf { it.parent is CSharpBlock }
            at = parent
        }
    }

    private fun movable(expression: CSharpExpression, statement: CSharpStatement): Boolean {
        if (expression is CSharpSimpleName && NativeCSharpTypePositions.isType(expression)) return false
        if (expression is CSharpInitializerExpression || expression is CSharpDeclarationExpression || expression is CSharpThrowExpression) return false
        if (expression is CSharpAnonymousFunctionExpression || expression is CSharpImplicitObjectCreationExpression || expression is CSharpCollectionExpression) return false
        if (statement is CSharpWhileStatement || statement is CSharpDoStatement || statement is CSharpForStatement) return false
        // `const` needs a constant
        if (statement is CSharpLocalDeclarationStatement && statement.modifiers.any { it.text == "const" }) return false
        var child: PsiElement = expression
        var parent = expression.parent
        while (parent != null && parent != statement) {
            when (parent) {
                is CSharpAssignmentExpression -> if (parent.left == child) return false
                is CSharpBinaryExpression -> if (parent.right == child && parent.operatorToken?.text in SHORT_CIRCUIT) return false
                is CSharpConditionalExpression -> if (parent.condition != child) return false
                is CSharpConditionalAccessExpression -> if (parent.whenNotNull == child) return false
                is CSharpArgument -> if (parent.refKindKeyword != null && present(parent.refKindKeyword)) return false
                is CSharpPostfixUnaryExpression, is CSharpRefExpression, is CSharpPattern, is CSharpSwitchLabel, is CSharpInitializerExpression -> return false
                is CSharpPrefixUnaryExpression -> if (parent.operatorToken?.text == "++" || parent.operatorToken?.text == "--" || parent.operatorToken?.text == "&") return false
                is CSharpInvocationExpression -> if ((parent.expression as? CSharpSimpleName)?.identifier?.text == "nameof") return false
                is CSharpSwitchExpressionArm -> return false
            }
            child = parent
            parent = parent.parent
        }
        return parent == statement
    }

    private val SHORT_CIRCUIT = setOf("&&", "||", "??")

    private fun nameFor(expression: CSharpExpression, written: String?, statement: PsiElement): String {
        var core = unwrap(expression)
        if (core is CSharpAwaitExpression) core = unwrap(core.expression)
        val member = when (core) {
            is CSharpInvocationExpression -> when (val callee = core.expression) {
                is CSharpMemberAccessExpression -> callee.nameElement?.identifier?.text
                is CSharpSimpleName -> callee.identifier?.text
                else -> null
            }?.let(CSharpUsingNames::ofMethod)
            is CSharpMemberAccessExpression -> core.nameElement?.identifier?.text?.takeIf { it.firstOrNull()?.isUpperCase() == true }
            else -> null
        }
        val suggestion = member?.let { CSharpVariableNames.forType(it).firstOrNull() }
            ?: written?.let { CSharpVariableNames.forType(it).firstOrNull() }
            ?: "value"
        return CSharpVariableNames.unique(suggestion, takenNames(statement))
    }

    /** The names of the locals, parameters and local functions of the member around [at]: a new local must not repeat one of them. */
    private fun takenNames(at: PsiElement): Set<String> {
        val file = at.containingFile as? CSharpFile ?: return emptySet()
        val owner = PsiTreeUtil.getParentOfType(at, CSharpMemberDeclaration::class.java)?.takeIf { it !is CSharpGlobalStatement }
            ?: PsiTreeUtil.getParentOfType(at, CSharpCompilationUnit::class.java) ?: return emptySet()
        val range = owner.textRange
        return NativeCSharpScopes.of(file).symbols.filter { range.contains(it.declaration.textRange) }.mapTo(HashSet()) { it.name.removePrefix("@") }
    }

    /**
     * The same expression (the same tokens) elsewhere in the block of [extraction]'s statement, with the one of [extraction], in order: what
     * "Replace all N occurrences" puts the variable in, as Rider's chooser offers. Empty when there is no other one or one variable cannot
     * serve them all (a local of the expression declared after the first of them, or in a nested block).
     */
    fun occurrences(extraction: Extraction): List<CSharpExpression> {
        val block = extraction.statement.parent as? CSharpBlock ?: return emptyList()
        val expression = extraction.expression
        val key = tokens(expression)
        val found = SyntaxTraverser.psiTraverser(block).filter(CSharpExpression::class.java).filter { candidate ->
            candidate.javaClass == expression.javaClass && candidate.textLength >= 1 && tokens(candidate) == key
        }.toList().filter { candidate ->
            if (candidate == expression) return@filter true
            val statement = enclosingStatement(candidate) ?: return@filter false
            PsiTreeUtil.isAncestor(block, statement, true) && movable(candidate, statement) &&
                // `Foo();` alone elsewhere would become a bare name
                !(statement is CSharpExpressionStatement && statement.expression == candidate)
        }
        val outermost = found.filter { c -> found.none { other -> other != c && PsiTreeUtil.isAncestor(other, c, true) } }.sortedBy { it.textRange.startOffset }
        if (outermost.size < 2) return emptyList()
        // the declaration goes before the first one: what the expression reads must be visible there
        val first = outermost.first()
        val anchor = block.statements.firstOrNull { it.textRange.contains(first.textRange) } ?: return emptyList()
        val scopes = NativeCSharpScopes.of(expression.containingFile as? CSharpFile ?: return emptyList())
        for (leaf in SyntaxTraverser.psiTraverser(expression).filter { it.firstChild == null }) {
            val symbol = scopes.symbolAt(leaf) ?: continue
            if (expression.textRange.contains(symbol.declaration.textRange)) continue
            if (symbol.declaration.textRange.startOffset >= anchor.textRange.startOffset || !symbol.scope.textRange.contains(anchor.textRange)) return emptyList()
        }
        // an occurrence that is the whole statement can only be the first (`var x = Foo();` takes its place)
        if (outermost.drop(1).any { (it.parent as? CSharpExpressionStatement)?.expression == it }) return emptyList()
        return outermost
    }

    private fun tokens(element: PsiElement): List<String> =
        SyntaxTraverser.psiTraverser(element).filter { it.firstChild == null && it !is PsiWhiteSpace && it !is PsiComment && it.textLength > 0 }.map { it.text }.toList()

    /**
     * The text of introducing a variable for all [occurrences] (from [occurrences]): over the returned range, the parts in order — a string
     * as it is, null where the name goes.
     */
    fun extractAllParts(extraction: Extraction, occurrences: List<CSharpExpression>, text: CharSequence): Pair<TextRange, List<String?>> {
        val first = occurrences.first()
        val block = extraction.statement.parent as CSharpBlock
        val anchor = block.statements.first { it.textRange.contains(first.textRange) }
        val start = anchor.textRange.startOffset
        val parts = ArrayList<String?>()
        parts += "var "
        parts += null
        var cursor: Int
        val rest: List<CSharpExpression>
        if (anchor is CSharpExpressionStatement && anchor.expression == first) {
            parts += " = ${first.text}"
            cursor = first.textRange.endOffset
            rest = occurrences.drop(1)
        } else {
            parts += " = ${first.text};\n${indentOf(text, start)}"
            cursor = start
            rest = occurrences
        }
        for (occurrence in rest) {
            parts += text.subSequence(cursor, occurrence.textRange.startOffset).toString()
            parts += null
            cursor = occurrence.textRange.endOffset
        }
        return TextRange(start, cursor) to parts
    }

    fun extractAll(extraction: Extraction, occurrences: List<CSharpExpression>, text: CharSequence, name: String = extraction.name): CSharpTextEdit {
        val (range, parts) = extractAllParts(extraction, occurrences, text)
        return CSharpTextEdit(range, parts.joinToString("") { it ?: name }, 4)
    }

    /**
     * The text of [extraction] over [range]: `var ` + name + [middle] + name (no second name when the expression was the whole statement:
     * `var name = Foo()` before its `;`).
     */
    class ExtractText(val range: TextRange, val middle: String, val use: Boolean)

    fun extractText(extraction: Extraction, text: CharSequence): ExtractText {
        val start = extraction.statement.textRange.startOffset
        val expression = extraction.expression
        val range = TextRange(start, expression.textRange.endOffset)
        if (extraction.whole) return ExtractText(range, " = ${expression.text}", use = false)
        val indent = indentOf(text, start)
        return ExtractText(range, " = ${expression.text};\n$indent${text.subSequence(start, expression.textRange.startOffset)}", use = true)
    }

    /** The edit of [extraction] with [name]: `var name = expression;` before the statement, the expression replaced by the name. */
    fun extract(extraction: Extraction, text: CharSequence, name: String = extraction.name): CSharpTextEdit {
        val parts = extractText(extraction, text)
        return CSharpTextEdit(parts.range, "var " + name + parts.middle + if (parts.use) name else "", 4)
    }

    // ---- inline variable

    /** The local at [offset] (its declaration or a use) that "Inline variable" can replace by its value everywhere, with its uses. */
    class Inlining(val statement: CSharpLocalDeclarationStatement, val value: CSharpExpression, val references: List<PsiElement>)

    fun inliningAt(file: CSharpFile, offset: Int): Inlining? {
        val resolver = NativeCSharpResolver(file)
        val leaf = NativeCSharpRename.identifierAt(file, offset)?.takeIf { resolver.symbolAt(it) != null } ?: declaredByTypeAt(file, offset) ?: return null
        val symbol = resolver.symbolAt(leaf) ?: return null
        if (symbol.kind != LocalSymbolKind.LOCAL || symbol.isWritten) return null
        val declarator = symbol.declaration.parent as? CSharpVariableDeclarator ?: return null
        val declaration = declarator.parent as? CSharpVariableDeclaration ?: return null
        val statement = declaration.parent as? CSharpLocalDeclarationStatement ?: return null
        if (statement.parent !is CSharpBlock || declaration.variables.size != 1 || !present(statement.semicolonToken)) return null
        if (statement.usingKeyword != null || statement.awaitKeyword != null) return null
        if (declaration.type is CSharpRefType || declaration.type is CSharpScopedType) return null
        val value = declarator.initializer?.value ?: return null
        if (value is CSharpRefExpression || value is CSharpInitializerExpression || value is CSharpStackAllocArrayCreationExpression) return null
        // what takes its type from the declaration (`List<int> x = new();`, a lambda, `[1, 2]`, `default`) means nothing at the uses
        val bare = NativeCSharpContextEdits.unwrap(value)
        if (bare is CSharpAnonymousFunctionExpression || bare is CSharpImplicitObjectCreationExpression || bare is CSharpCollectionExpression ||
            bare is CSharpLiteralExpression && (bare.text == "default" || bare.text == "null")) return null
        val references = resolver.references(symbol)
        if (references.isEmpty()) return null
        if (references.any { NativeCSharpUsageKinds.kindOfLeaf(it) == CSharpUsageKind.NAMEOF || it.parent !is CSharpSimpleName }) return null
        if (losesComment(statement, listOf(value))) return null
        return Inlining(statement, value, references)
    }

    /** The caret on the type of a local declaration of one variable (`var` of `var x = …`): its name, as the server offers it there too (robot, E-78). */
    private fun declaredByTypeAt(file: CSharpFile, offset: Int): PsiElement? {
        val leaf = file.findElementAt(offset) ?: return null
        val declaration = PsiTreeUtil.getParentOfType(leaf, CSharpVariableDeclaration::class.java, false, CSharpStatement::class.java) ?: return null
        val type = declaration.type ?: return null
        if (!type.textRange.containsOffset(offset) || declaration.variables.size != 1) return null
        return declaration.variables.single().identifier
    }

    /** Every use replaced by the value (in parentheses where the place needs them), the declaration removed with its line. */
    fun inline(inlining: Inlining, text: CharSequence): CSharpEdits {
        val edits = ArrayList<Pair<TextRange, String>>()
        val value = inlining.value
        val bare = value.text
        for (reference in inlining.references) {
            val name = reference.parent as CSharpExpression
            edits += name.textRange to if (isPrimary(value) || isWholeSlot(name)) bare else "($bare)"
        }
        edits += lineRange(text, inlining.statement.textRange) to ""
        return edits
    }

    private fun isPrimary(e: CSharpExpression): Boolean = when (e) {
        is CSharpSimpleName, is CSharpMemberAccessExpression, is CSharpInvocationExpression, is CSharpElementAccessExpression, is CSharpLiteralExpression,
        is CSharpParenthesizedExpression, is CSharpThisExpression, is CSharpBaseExpression, is CSharpTypeOfExpression, is CSharpDefaultExpression,
        is CSharpCheckedExpression, is CSharpInterpolatedStringExpression, is CSharpObjectCreationExpression, is CSharpTupleExpression, is CSharpSizeOfExpression -> true
        else -> false
    }

    /** A place that takes any expression as it is: an argument, an initializer, `return`, a statement, `=>`, parentheses. */
    private fun isWholeSlot(name: CSharpExpression): Boolean = when (val parent = name.parent) {
        is CSharpArgument, is CSharpEqualsValueClause, is CSharpReturnStatement, is CSharpExpressionStatement, is CSharpArrowExpressionClause,
        is CSharpParenthesizedExpression -> true
        is CSharpAssignmentExpression -> parent.right == name
        is CSharpInitializerExpression -> true
        else -> false
    }

    /** [range]'s whole line(s) with the line break when nothing else is on them; else [range] alone. */
    fun lineRange(text: CharSequence, range: TextRange): TextRange {
        var start = range.startOffset
        while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
        var end = range.endOffset
        while (end < text.length && (text[end] == ' ' || text[end] == '\t' || text[end] == '\r')) end++
        val wholeLine = (start == 0 || text[start - 1] == '\n') && (end == text.length || text[end] == '\n')
        return if (wholeLine) TextRange(start, if (end < text.length) end + 1 else end) else range
    }

    /** Applies [edits] to the document of [editor], the last first; the caret goes to [caret] (an offset in the old text) when given. */
    fun apply(editor: Editor, edits: CSharpEdits, caret: Int? = null) {
        val document = editor.document
        var moved = caret
        for ((range, replacement) in edits.sortedByDescending { it.first.startOffset }) {
            document.replaceString(range.startOffset, range.endOffset, replacement)
            if (moved != null && moved >= range.endOffset) moved += replacement.length - range.length
        }
        moved?.let { editor.caretModel.moveToOffset(it.coerceIn(0, document.textLength)) }
    }
}

/**
 * A native context action ([CSharpFeature.CONTEXT_ACTIONS]). Those the server has too stand back while it is ready and the switch is
 * ROSLYN; with NATIVE the server's rows are dropped ([NativeCSharpServerActions]). [needsTypes]: it reads the types of C2, so not while
 * the IDE indexes.
 */
abstract class NativeCSharpContextAction(private val title: String, private val serverHasIt: Boolean, private val needsTypes: Boolean = false) : IntentionAction, PriorityAction, DumbAware {
    override fun getText(): String = title
    override fun getFamilyName(): String = title
    override fun startInWriteAction(): Boolean = true
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.NORMAL

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is CSharpFile || file.compilationUnit == null) return false
        if (serverHasIt && !CSharpFeatures.native(CSharpFeature.CONTEXT_ACTIONS, project) && RoslynServerStatus.isReady(project)) return false
        if (needsTypes && DumbService.isDumb(project)) return false
        return edit(file, editor) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val edit = edit(file, editor) ?: return
        editor.document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
        editor.caretModel.moveToOffset(edit.range.startOffset + edit.caret)
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
    }

    abstract fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit?

    protected fun resolver(file: CSharpFile): CSharpNameResolver = CSharpSemanticSession(file.project).resolver(file)
}

/** Alt+Enter on `if`: `return c ? a : b;` / `x = c ? a : b;` for an `if` whose branches return or assign (Rider: "Convert to '?:' expression"). */
class NativeCSharpIfToConditionalIntention : NativeCSharpContextAction("Convert to '?:' expression", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpContextEdits.ifAt(file, editor.caretModel.offset)?.let { NativeCSharpContextEdits.ifToConditional(it, editor.document.charsSequence) }
}

/** Alt+Enter on a `?:` that is returned or assigned: the `if` / `else` statement it stands for; deeper in a statement, the statement in both branches. */
class NativeCSharpConditionalToIfIntention : NativeCSharpContextAction("Convert '?:' to 'if' statement", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val text = editor.document.charsSequence
        val unit = NativeCSharpContextEdits.unit(file)
        NativeCSharpContextEdits.conditionalAt(file, editor.caretModel.offset)?.let { (statement, conditional) ->
            NativeCSharpContextEdits.conditionalToIf(statement, conditional, text, unit)?.let { return it }
        }
        // deeper in a statement (an argument, an operand, a local's value): the statement repeated in both branches
        val (statement, conditional) = NativeCSharpContextEdits.conditionalSplitAt(file, editor.caretModel.offset) ?: return null
        val resolver = if (DumbService.isDumb(file.project)) null else resolver(file)
        return NativeCSharpContextEdits.splitConditional(statement, conditional, text, unit, resolver)
    }
}

/** Alt+Enter on the header of a member, local function or accessor whose body is one statement: `=> expression;` (Rider: "To expression body"). */
class NativeCSharpToExpressionBodyIntention : NativeCSharpContextAction("To expression body", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpContextEdits.bodyOwnerAt(file, editor.caretModel.offset)?.let { NativeCSharpContextEdits.toExpressionBody(it, editor.document.charsSequence) }
}

/** Alt+Enter on the header of an expression-bodied member: a block with `return expression;` (Rider: "To block body"). */
class NativeCSharpToBlockBodyIntention : NativeCSharpContextAction("To block body", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpContextEdits.bodyOwnerAt(file, editor.caretModel.offset)?.let {
            NativeCSharpContextEdits.toBlockBody(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
        }
}

/** Alt+Enter on `var` of a local: the type it stands for (Rider: "Use explicit type"). */
class NativeCSharpUseExplicitTypeIntention : NativeCSharpContextAction("Use explicit type", serverHasIt = true, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpContextEdits.typeSiteAt(file, editor.caretModel.offset)?.let { NativeCSharpContextEdits.useExplicitType(it, resolver(file)) }
}

/** Alt+Enter on the explicit type of a local whose value has that very type: `var` (Rider: "Use 'var'"). */
class NativeCSharpUseVarIntention : NativeCSharpContextAction("Use 'var'", serverHasIt = true, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpContextEdits.typeSiteAt(file, editor.caretModel.offset)?.let { NativeCSharpContextEdits.useVar(it, resolver(file)) }
}

/**
 * Alt+Enter on an expression (the selected one, or the call / member access / operator at the caret): `var name = expression;` before
 * its statement and the name in its place, the name then edited in both places at once (Rider: "Introduce variable").
 */
class NativeCSharpIntroduceVariableIntention : NativeCSharpContextAction("Introduce variable", serverHasIt = true, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? = extraction(file, editor)?.let { NativeCSharpContextEdits.extract(it, editor.document.charsSequence) }

    private fun extraction(file: CSharpFile, editor: Editor): NativeCSharpContextEdits.Extraction? {
        val selection = editor.selectionModel.takeIf { it.hasSelection() }?.let { TextRange(it.selectionStart, it.selectionEnd) }
        return NativeCSharpContextEdits.extractionAt(file, selection, editor.caretModel.offset, resolver(file))
    }

    // the chooser of occurrences is a popup: the edit makes its own write command afterwards
    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): com.intellij.codeInsight.intention.preview.IntentionPreviewInfo {
        val edit = (file as? CSharpFile)?.let { edit(it, editor) } ?: return com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY
        editor.document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
        return com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.DIFF
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val extraction = extraction(file, editor) ?: return
        if (IntentionPreviewUtils.isIntentionPreviewActive()) {
            val edit = NativeCSharpContextEdits.extract(extraction, editor.document.charsSequence)
            editor.document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
            return
        }
        val occurrences = NativeCSharpContextEdits.occurrences(extraction)
        if (occurrences.size < 2) return introduce(project, editor, file, extraction, emptyList())
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) {
            return introduce(project, editor, file, extraction, if (allOccurrencesForTests) occurrences else emptyList())
        }
        // Rider's chooser: "Replace this occurrence only" / "Replace all N occurrences", the occurrences highlighted while choosing
        val choices = linkedMapOf<OccurrencesChooser.ReplaceChoice, List<PsiElement>>(
            OccurrencesChooser.ReplaceChoice.NO to listOf(extraction.expression), OccurrencesChooser.ReplaceChoice.ALL to occurrences,
        )
        OccurrencesChooser.simpleChooser<PsiElement>(editor).showChooser(choices) { choice ->
            introduce(project, editor, file, extraction, if (choice == OccurrencesChooser.ReplaceChoice.ALL) occurrences else emptyList())
        }
    }

    /** `var name = expression;` before the statement (before the first of [all] when given) and the name in a template at every place. */
    private fun introduce(project: Project, editor: Editor, file: CSharpFile, extraction: NativeCSharpContextEdits.Extraction, all: List<CSharpExpression>) {
        WriteCommandAction.writeCommandAction(project, file).withName("Introduce Variable").run<RuntimeException> {
            if (!extraction.expression.isValid || all.any { !it.isValid }) return@run
            val text = editor.document.charsSequence
            val (range, parts) = if (all.size >= 2) NativeCSharpContextEdits.extractAllParts(extraction, all, text) else {
                val single = NativeCSharpContextEdits.extractText(extraction, text)
                single.range to (listOf("var ", null, single.middle) + if (single.use) listOf(null) else emptyList())
            }
            editor.selectionModel.removeSelection()
            // the name in a template at each place: typing a new one changes the declaration and the uses together
            val name = extraction.name
            val manager = TemplateManager.getInstance(project)
            val template = manager.createTemplate("", "")
            template.isToReformat = false
            // the text carries its own indent: the template must not add the line's one again
            (template as? com.intellij.codeInsight.template.impl.TemplateImpl)?.setToIndent(false)
            var declared = false
            for (part in parts) when {
                part != null -> template.addTextSegment(part)
                !declared -> { template.addVariable("NAME", ConstantNode(name), ConstantNode(name), true); declared = true }
                else -> template.addVariableSegment("NAME")
            }
            editor.document.deleteString(range.startOffset, range.endOffset)
            editor.caretModel.moveToOffset(range.startOffset)
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            manager.startTemplate(editor, template)
        }
    }

    companion object {
        /** In tests: "Replace all N occurrences" instead of this one only. */
        @Volatile
        @org.jetbrains.annotations.TestOnly
        var allOccurrencesForTests: Boolean = false
    }
}

/** Alt+Enter on a local assigned once (its declaration or a use): its value in place of every use, the declaration gone (Rider: "Inline variable"). */
class NativeCSharpInlineVariableIntention : NativeCSharpContextAction("Inline variable", serverHasIt = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpContextEdits.inliningAt(file, editor.caretModel.offset)?.let { CSharpTextEdit(it.statement.textRange, "") }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val inlining = NativeCSharpContextEdits.inliningAt(file, editor.caretModel.offset) ?: return
        val first = inlining.references.minOf { it.textRange.startOffset }
        NativeCSharpContextEdits.apply(editor, NativeCSharpContextEdits.inline(inlining, editor.document.charsSequence), first)
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
    }
}
