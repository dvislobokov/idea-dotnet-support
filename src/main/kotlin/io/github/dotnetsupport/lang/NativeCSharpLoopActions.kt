package io.github.dotnetsupport.lang

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * The text edits of the context actions on loops (0.1.163), as Rider offers them on the loop keyword: `foreach` ↔ `for` over an indexable
 * collection, a `for` reversed, and `foreach` over an expression statement of a collection. Pure functions on the tree and the document's
 * text, tested without an editor.
 */
object NativeCSharpLoopEdits {
    private fun present(token: PsiElement?): Boolean = (token?.textLength ?: 0) > 0

    private fun leafAt(file: PsiFile, offset: Int): List<PsiElement> = listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)

    // ---- the tree

    /** The `foreach` statement whose header (`foreach (…)`) holds [offset]. */
    fun forEachAt(file: PsiFile, offset: Int): CSharpForEachStatement? {
        for (leaf in leafAt(file, offset)) {
            val statement = PsiTreeUtil.getParentOfType(leaf, CSharpForEachStatement::class.java, false) ?: continue
            val end = statement.closeParenToken?.takeIf(::present)?.textRange?.endOffset ?: continue
            if (offset >= statement.textRange.startOffset && offset <= end) return statement
        }
        return null
    }

    /** The `for` statement whose header holds [offset]. */
    fun forAt(file: PsiFile, offset: Int): CSharpForStatement? {
        for (leaf in leafAt(file, offset)) {
            val statement = PsiTreeUtil.getParentOfType(leaf, CSharpForStatement::class.java, false) ?: continue
            val end = statement.closeParenToken?.takeIf(::present)?.textRange?.endOffset ?: continue
            if (offset >= statement.textRange.startOffset && offset <= end) return statement
        }
        return null
    }

    /** The expression statement holding [offset] (`items;`, `GetItems();`): what "Iterate via 'foreach'" iterates over. */
    fun expressionStatementAt(file: PsiFile, offset: Int): CSharpExpressionStatement? {
        for (leaf in leafAt(file, offset)) {
            val statement = PsiTreeUtil.getParentOfType(leaf, CSharpExpressionStatement::class.java, false) ?: continue
            val expression = statement.expression ?: continue
            if (expression is CSharpAssignmentExpression || expression is CSharpAwaitExpression) continue
            if (offset >= statement.textRange.startOffset && offset <= statement.textRange.endOffset) return statement
        }
        return null
    }

    /** The names declared in the member around [at]: a new loop variable must not repeat one of them. */
    private fun takenNames(at: PsiElement): Set<String> {
        val file = at.containingFile as? CSharpFile ?: return emptySet()
        val owner = PsiTreeUtil.getParentOfType(at, CSharpMemberDeclaration::class.java)?.takeIf { it !is CSharpGlobalStatement }
            ?: PsiTreeUtil.getParentOfType(at, CSharpCompilationUnit::class.java) ?: return emptySet()
        val range = owner.textRange
        return NativeCSharpScopes.of(file).symbols.filter { range.contains(it.declaration.textRange) }.mapTo(HashSet()) { it.name.removePrefix("@") }
    }

    /** `i`, else `j`, `k`, `i1`…: an index no name of the member takes. */
    private fun freeIndex(at: PsiElement): String {
        val taken = takenNames(at)
        return listOf("i", "j", "k").firstOrNull { it !in taken } ?: CSharpVariableNames.unique("i", taken)
    }

    // ---- the types

    /** `Length` for an array or a string, `Count` for an `IList<T>` / `IReadOnlyList<T>` (a `List<T>` among them), null for anything else. */
    fun countMember(resolver: CSharpNameResolver, expression: CSharpExpression): String? {
        val type = resolver.typeOf(expression)?.let(resolver::unwrapNullable) ?: return null
        if (type is SemanticType.ArrayOf) return "Length"
        if (resolver.definitionName(type) == "System.String") return "Length"
        if (resolver.expressions.instanceOf(type, "System.Collections.Generic.IList`1") != null || resolver.expressions.instanceOf(type, "System.Collections.Generic.IReadOnlyList`1") != null) return "Count"
        return null
    }

    /** The expression is a collection `foreach` can walk: an array, or anything that is an `IEnumerable`. */
    fun isEnumerable(resolver: CSharpNameResolver, expression: CSharpExpression): Boolean {
        val type = resolver.typeOf(expression)?.let(resolver::unwrapNullable) ?: return false
        return type is SemanticType.ArrayOf || resolver.expressions.instanceOf(type, "System.Collections.IEnumerable") != null
    }

    // ---- text

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    private fun lineIndent(text: CharSequence, offset: Int): String {
        val start = lineStart(text, offset)
        return text.subSequence(start, offset).takeWhile { it == ' ' || it == '\t' }.toString()
    }

    private fun ownIndent(text: CharSequence, element: PsiElement): String = lineIndent(text, element.textRange.startOffset)

    private fun simple(expression: CSharpExpression?): Boolean = when (NativeCSharpContextEdits.unwrap(expression)) {
        is CSharpIdentifierName, is CSharpMemberAccessExpression, is CSharpInvocationExpression, is CSharpElementAccessExpression, is CSharpThisExpression,
        is CSharpParenthesizedExpression -> true
        else -> false
    }

    /** The statements of a loop body (the block's, or the one statement) re-indented to [indent], as lines; empty for an empty block. */
    private fun bodyLines(body: CSharpStatement, text: CharSequence, indent: String): List<String> {
        val statements = if (body is CSharpBlock) body.statements else listOf(body)
        if (statements.isEmpty()) return emptyList()
        val from = statements.first().textRange.startOffset
        val to = statements.last().textRange.endOffset
        val old = lineIndent(text, from)
        return (old + text.subSequence(from, to)).lines().map { line -> if (line.startsWith(old)) indent + line.substring(old.length) else line.trimStart() }
    }

    private fun loop(header: String, indent: String, lines: List<String>): String =
        (listOf(header, "$indent{") + lines + "$indent}").joinToString("\n")

    // ---- Convert 'foreach' to 'for'

    /**
     * `foreach (var x in items)` → `for (var i = 0; i < items.Count; i++) { var x = items[i]; … }` for an array (`.Length`), a string or an
     * `IList<T>` (Rider: "Convert 'foreach' to 'for'"); [count] is the member [countMember] found.
     */
    fun forEachToFor(statement: CSharpForEachStatement, count: String, text: CharSequence, unit: String): CSharpTextEdit? {
        if (present(statement.awaitKeyword)) return null
        val collection = statement.expression ?: return null
        val name = statement.identifier?.text ?: return null
        val body = statement.statement ?: return null
        val type = statement.type?.text ?: "var"
        val index = freeIndex(statement)
        val indent = ownIndent(text, statement)
        val source = if (simple(collection)) collection.text else "(${collection.text})"
        val header = "for (var $index = 0; $index < $source.$count; $index++)"
        val lines = listOf("$indent$unit$type $name = $source[$index];") + bodyLines(body, text, indent + unit)
        return CSharpTextEdit(statement.textRange, loop(header, indent, lines))
    }

    // ---- Convert 'for' to 'foreach'

    /** `var i = 0` / `int i = 0` of a `for` header: the index. */
    private fun indexOf(statement: CSharpForStatement): String? {
        val declaration = statement.declaration ?: return null
        val declarator = declaration.variables.singleOrNull() ?: return null
        val typeText = declaration.type?.text
        if (typeText != "var" && typeText != "int") return null
        val start = NativeCSharpContextEdits.unwrap(declarator.initializer?.value) as? CSharpLiteralExpression ?: return null
        if (start.text != "0") return null
        return declarator.identifier?.text
    }

    /** `i < items.Count` / `i < items.Length`: the collection walked. */
    private fun boundOf(statement: CSharpForStatement, index: String): CSharpExpression? {
        val condition = NativeCSharpContextEdits.unwrap(statement.condition) as? CSharpBinaryExpression ?: return null
        if (condition.operatorToken?.text != "<" || (NativeCSharpContextEdits.unwrap(condition.left) as? CSharpIdentifierName)?.identifier?.text != index) return null
        val bound = NativeCSharpContextEdits.unwrap(condition.right) as? CSharpMemberAccessExpression ?: return null
        val member = (bound.nameElement as? CSharpIdentifierName)?.identifier?.text
        if (member != "Count" && member != "Length") return null
        return bound.expression
    }

    /** `i++`, `++i`, `i += 1`: the index stepped by one. */
    private fun stepsByOne(statement: CSharpForStatement, index: String): Boolean {
        val step = statement.incrementors.singleOrNull() ?: return false
        return when (step) {
            is CSharpPostfixUnaryExpression -> step.operatorToken?.text == "++" && step.operand?.text == index
            is CSharpPrefixUnaryExpression -> step.operatorToken?.text == "++" && step.operand?.text == index
            is CSharpAssignmentExpression -> step.operatorToken?.text == "+=" && step.left?.text == index && step.right?.text == "1"
            else -> false
        }
    }

    /** `items[i]` of [collection]'s text whose index is [index] and that is read, not assigned. */
    private fun elementAccess(use: CSharpIdentifierName, collection: String): CSharpElementAccessExpression? {
        val argument = use.parent as? CSharpArgument ?: return null
        val list = argument.parent as? CSharpBracketedArgumentList ?: return null
        if (list.arguments.size != 1) return null
        val access = list.parent as? CSharpElementAccessExpression ?: return null
        if (access.expression?.text != collection) return null
        // written, not read: `items[i] = x`, `items[i]++`, `ref items[i]`
        val owner = access.parent
        if (owner is CSharpAssignmentExpression && owner.left == access) return null
        if (owner is CSharpPostfixUnaryExpression || owner is CSharpPrefixUnaryExpression && owner.operatorToken?.text.let { it == "++" || it == "--" }) return null
        if (owner is CSharpArgument && present(owner.refKindKeyword)) return null
        return access
    }

    /**
     * `for (var i = 0; i < items.Count; i++)` whose body reads `i` only as `items[i]` → `foreach (var item in items)` with `items[i]`
     * replaced by the element (Rider: "Convert 'for' to 'foreach'"); a first statement `var x = items[i];` names the element and goes.
     */
    fun forToForEach(statement: CSharpForStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        val index = indexOf(statement) ?: return null
        val collection = boundOf(statement, index) ?: return null
        if (!stepsByOne(statement, index)) return null
        val body = statement.statement ?: return null
        val source = collection.text
        // every `i` of the body that is the index itself (not `x.i`)
        val uses = PsiTreeUtil.findChildrenOfType(body, CSharpIdentifierName::class.java).filter { it.identifier?.text == index && (it.parent as? CSharpMemberAccessExpression)?.nameElement != it }
        val accesses = uses.map { elementAccess(it, source) ?: return null }
        var statements = if (body is CSharpBlock) body.statements else listOf(body)
        var name: String? = null
        val first = statements.firstOrNull() as? CSharpLocalDeclarationStatement
        val declarator = first?.declaration?.variables?.singleOrNull()
        if (first != null && declarator != null && accesses.any { it == NativeCSharpContextEdits.unwrap(declarator.initializer?.value) } && first.declaration?.type?.text == "var") {
            name = declarator.identifier?.text
            statements = statements.drop(1)
        }
        val taken = takenNames(statement) - index
        val element = name ?: CSharpExpressionNames.forElement(source, null, taken).firstOrNull { it !in taken } ?: "item"
        val indent = ownIndent(text, statement)
        // the body with every `items[i]` replaced, the naming statement dropped, re-indented
        val builder = StringBuilder()
        if (statements.isNotEmpty()) {
            val from = statements.first().textRange.startOffset
            val to = statements.last().textRange.endOffset
            builder.append(text.subSequence(from, to))
            for (access in accesses.filter { it.textRange.startOffset >= from && it.textRange.endOffset <= to }.sortedByDescending { it.textRange.startOffset }) {
                builder.replace(access.textRange.startOffset - from, access.textRange.endOffset - from, element)
            }
            val old = lineIndent(text, from)
            val lines = (old + builder).lines().map { line -> if (line.startsWith(old)) indent + unit + line.substring(old.length) else line.trimStart() }
            return CSharpTextEdit(statement.textRange, loop("foreach (var $element in $source)", indent, lines))
        }
        return CSharpTextEdit(statement.textRange, loop("foreach (var $element in $source)", indent, emptyList()))
    }

    // ---- Reverse 'for'

    /** `for (var i = 0; i < n; i++)` ↔ `for (var i = n - 1; i >= 0; i--)` (Rider: "Reverse 'for'"). */
    fun reverseFor(statement: CSharpForStatement, text: CharSequence): CSharpTextEdit? {
        val declaration = statement.declaration ?: return null
        val declarator = declaration.variables.singleOrNull() ?: return null
        val index = declarator.identifier?.text ?: return null
        val typeText = declaration.type?.text ?: return null
        val start = NativeCSharpContextEdits.unwrap(declarator.initializer?.value) ?: return null
        val condition = NativeCSharpContextEdits.unwrap(statement.condition) as? CSharpBinaryExpression ?: return null
        if ((NativeCSharpContextEdits.unwrap(condition.left) as? CSharpIdentifierName)?.identifier?.text != index) return null
        val step = statement.incrementors.singleOrNull() ?: return null
        val open = statement.openParenToken?.takeIf(::present) ?: return null
        val close = statement.closeParenToken?.takeIf(::present) ?: return null
        val range = TextRange(open.textRange.endOffset, close.textRange.startOffset)
        val forward = start.text == "0" && condition.operatorToken?.text == "<" && (step.text == "$index++" || step.text == "++$index")
        if (forward) {
            val bound = NativeCSharpContextEdits.unwrap(condition.right) ?: return null
            val top = if (bound is CSharpBinaryExpression) "(${bound.text})" else bound.text
            return CSharpTextEdit(range, "$typeText $index = $top - 1; $index >= 0; $index--")
        }
        val backward = condition.operatorToken?.text == ">=" && condition.right?.text == "0" && (step.text == "$index--" || step.text == "--$index")
        if (!backward) return null
        val minus = start as? CSharpBinaryExpression ?: return null
        if (minus.operatorToken?.text != "-" || minus.right?.text != "1") return null
        val top = NativeCSharpContextEdits.unwrap(minus.left)?.text ?: return null
        return CSharpTextEdit(range, "$typeText $index = 0; $index < $top; $index++")
    }

    // ---- Iterate via 'foreach'

    /** `items;` → `foreach (var item in items) { | }`, the text the postfix `.foreach` writes (Rider: "Iterate via 'foreach'"). */
    fun iterate(statement: CSharpExpressionStatement, elementType: String?, text: CharSequence, unit: String): CSharpTextEdit? {
        val expression = statement.expression ?: return null
        val indent = ownIndent(text, statement)
        val taken = takenNames(statement)
        val name = CSharpExpressionNames.forElement(expression.text, elementType, taken).firstOrNull { it !in taken } ?: "item"
        val head = "foreach (var $name in ${expression.text})\n$indent{\n$indent$unit"
        return CSharpTextEdit(statement.textRange, "$head\n$indent}", caret = head.length)
    }
}

/** Alt+Enter on `foreach` over an array, a string or an `IList<T>`: the `for` with the index (Rider: "Convert 'foreach' to 'for'"). */
class NativeCSharpForEachToForIntention : NativeCSharpContextAction("Convert 'foreach' to 'for'", needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val statement = NativeCSharpLoopEdits.forEachAt(file, editor.caretModel.offset) ?: return null
        val count = statement.expression?.let { NativeCSharpLoopEdits.countMember(resolver(file), it) } ?: return null
        return NativeCSharpLoopEdits.forEachToFor(statement, count, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}

/** Alt+Enter on `for (var i = 0; i < items.Count; i++)` reading `items[i]` only: the `foreach` (Rider: "Convert 'for' to 'foreach'"). */
class NativeCSharpForToForEachIntention : NativeCSharpContextAction("Convert 'for' to 'foreach'") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpLoopEdits.forAt(file, editor.caretModel.offset)?.let { NativeCSharpLoopEdits.forToForEach(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on a counting `for`: the same loop the other way round (Rider: "Reverse 'for'"). */
class NativeCSharpReverseForIntention : NativeCSharpContextAction("Reverse 'for'") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpLoopEdits.forAt(file, editor.caretModel.offset)?.let { NativeCSharpLoopEdits.reverseFor(it, editor.document.charsSequence) }
}

/** Alt+Enter on an expression statement of a collection (`items;`): `foreach` over it (Rider: "Iterate via 'foreach'"). */
class NativeCSharpIterateViaForEachIntention : NativeCSharpContextAction("Iterate via 'foreach'", needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val statement = NativeCSharpLoopEdits.expressionStatementAt(file, editor.caretModel.offset) ?: return null
        val expression = statement.expression ?: return null
        val resolver = resolver(file)
        if (!NativeCSharpLoopEdits.isEnumerable(resolver, expression)) return null
        val elementType = resolver.typeOf(expression)?.let(resolver::elementType)?.minimalDisplay
        return NativeCSharpLoopEdits.iterate(statement, elementType, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}
