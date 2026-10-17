package io.github.dotnetsupport.lang

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * The text edits of the context actions on expressions (0.1.171), as Rider offers them (the comparison on Jellyfin, rows № 30–34 of
 * `docs/compare/jellyfin-2026-10-12.md`): a cast to a safe cast, a string literal to a regular / verbatim / raw one, an extension call to the
 * static call and back, a statement in a `try` / `catch`, an object initializer to assignments. Pure functions on the tree and the
 * document's text, tested without an editor.
 */
object NativeCSharpExpressionEdits {
    private fun leafAt(file: PsiFile, offset: Int): List<PsiElement> = listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)

    private fun <T : PsiElement> nearest(file: PsiFile, offset: Int, type: Class<T>): T? =
        leafAt(file, offset).firstNotNullOfOrNull { PsiTreeUtil.getParentOfType(it, type, false) }

    private fun lineIndent(text: CharSequence, offset: Int): String {
        val start = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        return text.subSequence(start, text.length).takeWhile { it == ' ' || it == '\t' }.toString()
    }

    // ---- To safe cast

    /** The cast whose `(Type)` part or operand holds [offset]. */
    fun castAt(file: PsiFile, offset: Int): CSharpCastExpression? = nearest(file, offset, CSharpCastExpression::class.java)

    /** `(T)x` → `x as T` when `T` is a reference type or a nullable value type (Rider: "To safe cast"); parentheses where `as` binds looser. */
    fun toSafeCast(cast: CSharpCastExpression, resolver: CSharpNameResolver): CSharpTextEdit? {
        val type = cast.type ?: return null
        val operand = cast.expression ?: return null
        if (!acceptsNull(type, resolver)) return null
        val value = when (NativeCSharpContextEdits.unwrap(operand)) {
            is CSharpConditionalExpression, is CSharpBinaryExpression, is CSharpAssignmentExpression, is CSharpLambdaExpression -> "(${operand.text})"
            else -> operand.text
        }
        val written = "$value as ${type.text}"
        // `((Folder)item).Children`: the parentheses the cast needed carry the `as` too
        val text = if (needsParentheses(cast)) "($written)" else written
        return CSharpTextEdit(cast.textRange, text, text.length)
    }

    private fun acceptsNull(type: CSharpType, resolver: CSharpNameResolver): Boolean {
        if (type is CSharpNullableType) return true
        return when (val resolved = resolver.resolveType(type) ?: return false) {
            is SemanticType.ArrayOf -> true
            is SemanticType.Library -> resolved.type.fullName == "System.Nullable`1" ||
                resolved.type.kind != IndexedTypeKind.STRUCT && resolved.type.kind != IndexedTypeKind.ENUM
            is SemanticType.Source -> resolved.info.kind.let { it != null && it != TypeKind.STRUCT && it != TypeKind.RECORD_STRUCT && it != TypeKind.ENUM }
            else -> false
        }
    }

    /** A cast is a unary operator, `as` a relational one: where the cast stood as an operand of something tighter, `as` needs parentheses. */
    private fun needsParentheses(expression: CSharpExpression): Boolean = when (val parent = expression.parent) {
        is CSharpParenthesizedExpression, is CSharpArgument, is CSharpEqualsValueClause, is CSharpReturnStatement, is CSharpArrowExpressionClause,
        is CSharpInterpolation, is CSharpInitializerExpression, is CSharpConditionalExpression, is CSharpThrowStatement, is CSharpYieldStatement -> false
        is CSharpAssignmentExpression -> parent.left == expression
        is CSharpBinaryExpression -> parent.operatorToken?.text !in setOf("??", "==", "!=", "&&", "||", "&", "|", "^")
        else -> true
    }

    // ---- strings

    enum class StringForm { REGULAR, VERBATIM, RAW }

    /** A string literal or interpolated string at [offset], with its form; null for `u8` literals, multi-line raw strings, `$$"""`. */
    class StringAt(val element: CSharpExpression, val form: StringForm, val interpolated: Boolean)

    fun stringAt(file: PsiFile, offset: Int): StringAt? {
        for (leaf in leafAt(file, offset)) {
            var at: PsiElement? = leaf
            while (at != null && at !is CSharpFile) {
                if (at is CSharpInterpolation && at.openBraceToken?.textRange?.endOffset?.let { offset >= it } == true &&
                    at.closeBraceToken?.textRange?.startOffset?.let { offset <= it } == true) break
                if (at is CSharpInterpolatedStringExpression) return interpolatedForm(at)?.let { StringAt(at, it, true) }
                if (at is CSharpLiteralExpression) return literalForm(at)?.let { StringAt(at, it, false) }
                at = at.parent
            }
        }
        return null
    }

    private fun literalForm(literal: CSharpLiteralExpression): StringForm? {
        val text = literal.text
        if (text.endsWith("u8") || text.endsWith("U8")) return null
        return when {
            text.startsWith("\"\"\"") -> if ('\n' in text) null else StringForm.RAW
            text.startsWith("@\"") -> StringForm.VERBATIM
            text.startsWith("\"") -> StringForm.REGULAR
            else -> null
        }
    }

    private fun interpolatedForm(string: CSharpInterpolatedStringExpression): StringForm? {
        val start = string.stringStartToken?.text ?: return null
        return when {
            start.startsWith("$$") -> null
            start.endsWith("\"\"\"") -> if ('\n' in string.text) null else StringForm.RAW
            '@' in start -> StringForm.VERBATIM
            else -> StringForm.REGULAR
        }
    }

    /** The value of the characters of a regular string's text part, or null when it has an escape other than `\\`, `\"`, `\'`. */
    private fun regularValue(text: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                val next = text.getOrNull(i + 1) ?: return null
                if (next != '\\' && next != '"' && next != '\'') return null
                out.append(next)
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun verbatimValue(text: String): String = text.replace("\"\"", "\"")

    private fun regularText(value: String): String = buildString {
        for (c in value) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    private fun verbatimText(value: String): String = value.replace("\"", "\"\"")

    /** The parts of a string: (is it a hole?, its text as written — the content of a text part, the whole `{…}` of a hole). */
    private fun parts(string: StringAt): List<Pair<Boolean, String>>? = when (val e = string.element) {
        is CSharpLiteralExpression -> {
            val text = e.text
            val content = when (string.form) {
                StringForm.REGULAR -> text.removePrefix("\"").removeSuffix("\"")
                StringForm.VERBATIM -> text.removePrefix("@\"").removeSuffix("\"")
                StringForm.RAW -> {
                    val quotes = text.takeWhile { it == '"' }.length
                    text.substring(quotes, text.length - quotes)
                }
            }
            listOf(false to content)
        }
        is CSharpInterpolatedStringExpression -> e.contents.map { part ->
            when (part) {
                is CSharpInterpolatedStringText -> false to part.text
                else -> true to part.text
            }
        }
        else -> null
    }

    /** The value of a text part in [form] (raw and verbatim as written but `""`; regular unescaped), or null when it is not exact. */
    private fun valueOf(form: StringForm, written: String): String? = when (form) {
        StringForm.REGULAR -> regularValue(written)
        StringForm.VERBATIM -> verbatimValue(written)
        StringForm.RAW -> written
    }

    /** [string] written in [target] form: "To regular string", "To verbatim string", "To raw string" (Rider). Null where not exact. */
    fun convert(string: StringAt, target: StringForm): CSharpTextEdit? {
        if (string.form == target) return null
        val parts = parts(string) ?: return null
        val values = parts.map { (hole, written) -> if (hole) written else valueOf(string.form, written) ?: return null }
        // an interpolated string's braces `{{` / `}}` stay as they are in every form but raw, where one `$` has none
        val texts = parts.zip(values).map { (part, value) -> if (part.first) part.second to true else value to false }
        val body = when (target) {
            StringForm.REGULAR -> texts.joinToString("") { (t, hole) -> if (hole) t else regularText(t) }
            StringForm.VERBATIM -> texts.joinToString("") { (t, hole) -> if (hole) t else verbatimText(t) }
            StringForm.RAW -> {
                val text = texts.filter { !it.second }.joinToString("") { it.first }
                if ('\n' in text || '\r' in text) return null
                if (string.interpolated && ("{{" in text || "}}" in text)) return null
                texts.joinToString("") { it.first }
            }
        }
        if (target == StringForm.RAW && (body.startsWith("\"") || body.endsWith("\""))) return null
        val dollar = if (string.interpolated) "$" else ""
        val written = when (target) {
            StringForm.REGULAR -> "$dollar\"$body\""
            StringForm.VERBATIM -> "$dollar@\"$body\""
            StringForm.RAW -> {
                val longest = Regex("\"+").findAll(body).maxOfOrNull { it.value.length } ?: 0
                val quotes = "\"".repeat(maxOf(3, longest + 1))
                "$dollar$quotes$body$quotes"
            }
        }
        return CSharpTextEdit(string.element.textRange, written, written.length)
    }

    // ---- To static method invocation / To extension method invocation

    /** A call `receiver.Method(args)` whose method name or dot holds [offset]. */
    fun memberCallAt(file: PsiFile, offset: Int): CSharpInvocationExpression? {
        for (leaf in leafAt(file, offset)) {
            val access = PsiTreeUtil.getParentOfType(leaf, CSharpMemberAccessExpression::class.java, false) ?: continue
            val call = access.parent as? CSharpInvocationExpression ?: continue
            if (call.expression != access) continue
            val start = access.operatorToken?.textRange?.startOffset ?: continue
            val end = access.nameElement?.textRange?.endOffset ?: continue
            if (offset in start..end) return call
        }
        return null
    }

    private fun methodOf(call: CSharpInvocationExpression, resolver: CSharpNameResolver): List<CSharpSymbol> {
        val access = call.expression as? CSharpMemberAccessExpression ?: return emptyList()
        val leaf = access.nameElement?.identifier ?: return emptyList()
        return resolver.resolve(leaf)?.symbols.orEmpty()
    }

    /** The static class an extension method is declared in, as the call site can name it: its simple name when that name finds it, else qualified. */
    private fun ownerName(symbol: CSharpSymbol, at: PsiElement, resolver: CSharpNameResolver): String? {
        val (simple, qualified, matches) = when (symbol) {
            is CSharpSymbol.LibraryMember -> Triple(symbol.member.type.simpleName, symbol.member.type.fullName.substringBefore('`')) { found: CSharpSymbol ->
                found is CSharpSymbol.LibraryType && found.type.fullName == symbol.member.type.fullName
            }
            is CSharpSymbol.SourceMember -> {
                val type = PsiTreeUtil.getParentOfType(symbol.element, CSharpTypeDeclaration::class.java) ?: return null
                val name = type.identifier?.text ?: return null
                val namespace = generateSequence<PsiElement>(type.parent) { it.parent }.filterIsInstance<CSharpBaseNamespaceDeclaration>()
                    .mapNotNull { it.nameElement?.text }.toList().asReversed().joinToString(".")
                Triple(name, if (namespace.isEmpty()) name else "$namespace.$name") { found: CSharpSymbol ->
                    found is CSharpSymbol.SourceType && type in found.info.targets()
                }
            }
            else -> return null
        }
        return if (resolver.typeOrNamespace(at, simple, 0).any(matches)) simple else "global::$qualified"
    }

    /** `source.Where(x => …)` of an extension method → `Enumerable.Where(source, x => …)` (Rider: "To static method invocation"). */
    fun toStaticInvocation(call: CSharpInvocationExpression, resolver: CSharpNameResolver): CSharpTextEdit? {
        val access = call.expression as? CSharpMemberAccessExpression ?: return null
        if (access.operatorToken?.text != ".") return null
        val receiver = access.expression ?: return null
        val name = access.nameElement ?: return null
        // `Enumerable.Where(…)` already: the receiver is a type
        (receiver as? CSharpIdentifierName)?.identifier?.let(resolver::resolve)?.single?.let { if (it is CSharpSymbol.LibraryType || it is CSharpSymbol.SourceType) return null }
        val symbols = methodOf(call, resolver)
        if (symbols.isEmpty() || !symbols.all(resolver::isExtension)) return null
        val owners = symbols.mapNotNull { ownerName(it, call, resolver) }.distinct()
        val owner = owners.singleOrNull() ?: return null
        val list = call.argumentList ?: return null
        val arguments = list.arguments.joinToString(", ") { it.text }
        val receiverText = if (receiver is CSharpBaseExpression) return null else receiver.text
        val text = "$owner.${name.text}($receiverText${if (arguments.isEmpty()) "" else ", $arguments"})"
        return CSharpTextEdit(call.textRange, text, owner.length + 1)
    }

    /** `Enumerable.Where(source, x => …)` of an extension method → `source.Where(x => …)` (Rider: "To extension method invocation"). */
    fun toExtensionInvocation(call: CSharpInvocationExpression, resolver: CSharpNameResolver): CSharpTextEdit? {
        val access = call.expression as? CSharpMemberAccessExpression ?: return null
        val receiver = access.expression ?: return null
        val name = access.nameElement ?: return null
        val type = (receiver as? CSharpIdentifierName)?.identifier?.let(resolver::resolve)?.single
            ?: (receiver as? CSharpMemberAccessExpression)?.nameElement?.identifier?.let(resolver::resolve)?.single ?: return null
        if (type !is CSharpSymbol.LibraryType && type !is CSharpSymbol.SourceType) return null
        val symbols = methodOf(call, resolver)
        if (symbols.isEmpty() || !symbols.all(resolver::isExtension)) return null
        val arguments = call.argumentList?.arguments ?: return null
        val first = arguments.firstOrNull() ?: return null
        if (first.nameColon != null || first.refKindKeyword != null) return null
        val value = first.expression ?: return null
        val written = when (NativeCSharpContextEdits.unwrap(value)) {
            is CSharpIdentifierName, is CSharpMemberAccessExpression, is CSharpInvocationExpression, is CSharpLiteralExpression, is CSharpElementAccessExpression,
            is CSharpThisExpression, is CSharpBaseExpression, is CSharpGenericName, is CSharpInterpolatedStringExpression -> value.text
            else -> "(${value.text})"
        }
        val text = "$written.${name.text}(${arguments.drop(1).joinToString(", ") { it.text }})"
        return CSharpTextEdit(call.textRange, text, written.length + 1)
    }

    // ---- Generate try-catch block

    /** The statement of a body at [offset], when the caret is on a call, a creation or an `await` in it (where something may throw). */
    fun throwingStatementAt(file: PsiFile, offset: Int): CSharpStatement? {
        for (leaf in leafAt(file, offset)) {
            val call = PsiTreeUtil.getParentOfType(leaf, CSharpInvocationExpression::class.java, CSharpBaseObjectCreationExpression::class.java, CSharpAwaitExpression::class.java)
                ?: continue
            var statement: PsiElement? = call
            while (statement != null && !(statement is CSharpStatement && statement !is CSharpBlock && statement.parent is CSharpBlock)) {
                if (statement is CSharpMemberDeclaration || statement is CSharpAnonymousFunctionExpression || statement is CSharpLocalFunctionStatement) return null
                statement = statement.parent
            }
            return statement as? CSharpStatement
        }
        return null
    }

    /**
     * [statement] in `try { … } catch (Exception e) { Console.WriteLine(e); throw; }`, the caret on `Exception` (Rider: "Generate try-catch
     * block", its `try` template's body). A local declaration whose variables are used further down the block is not wrapped: they would
     * leave scope.
     */
    fun tryCatch(statement: CSharpStatement, text: CharSequence, unit: String): CSharpTextEdit? {
        if (statement is CSharpLocalDeclarationStatement) {
            val names = statement.declaration?.variables?.mapNotNull { it.identifier?.text }.orEmpty()
            val block = statement.parent as? CSharpBlock ?: return null
            val after = block.statements.dropWhile { it != statement }.drop(1)
            if (after.any { following -> names.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(following.text) } }) return null
        }
        val indent = lineIndent(text, statement.textRange.startOffset)
        val body = statement.text.lines().mapIndexed { i, line -> if (i == 0) "$indent$unit$line" else if (line.isBlank()) line else "$unit$line" }.joinToString("\n")
        val head = "try\n$indent{\n$body\n$indent}\n${indent}catch ("
        val written = "$head" + "Exception e)\n$indent{\n$indent${unit}Console.WriteLine(e);\n$indent${unit}throw;\n$indent}"
        return CSharpTextEdit(statement.textRange, written, head.length)
    }

    // ---- Convert initializer to assignment

    /** A local declaration or an assignment statement at [offset] whose value is `new T(…) { A = a, … }`, with its creation and target. */
    class Initialized(val statement: CSharpStatement, val creation: CSharpBaseObjectCreationExpression, val target: String)

    fun initializedAt(file: PsiFile, offset: Int): Initialized? {
        val statement = nearest(file, offset, CSharpStatement::class.java) ?: return null
        val (value, target) = when (statement) {
            is CSharpLocalDeclarationStatement -> {
                val declarator = statement.declaration?.variables?.singleOrNull() ?: return null
                declarator.initializer?.value to (declarator.identifier?.text ?: return null)
            }
            is CSharpExpressionStatement -> {
                val assignment = statement.expression as? CSharpAssignmentExpression ?: return null
                if (assignment.operatorToken?.text != "=") return null
                assignment.right to (assignment.left?.text ?: return null)
            }
            else -> return null
        }
        val creation = NativeCSharpContextEdits.unwrap(value) as? CSharpBaseObjectCreationExpression ?: return null
        val initializer = creation.initializer ?: return null
        if (initializer.node.elementType != SyntaxKind.ObjectInitializerExpression) return null
        if (statement.parent !is CSharpBlock) return null
        // the caret before the initializer's `{`: on the declaration, the type, `new`, the arguments
        if (offset > initializer.textRange.startOffset) return null
        return Initialized(statement, creation, target)
    }

    /** `var x = new T { A = a, B = b };` → `var x = new T();`, `x.A = a;`, `x.B = b;` (Rider: "Convert initializer to assignment"). */
    fun initializerToAssignments(found: Initialized, text: CharSequence): CSharpTextEdit? {
        val creation = found.creation
        val initializer = creation.initializer ?: return null
        val assignments = initializer.expressions.map { element ->
            val assignment = element as? CSharpAssignmentExpression ?: return null
            val member = assignment.left as? CSharpIdentifierName ?: return null
            val value = assignment.right ?: return null
            if (value is CSharpInitializerExpression) return null
            "${found.target}.${member.text} = ${value.text};"
        }
        if (assignments.isEmpty()) return null
        val statementStart = found.statement.textRange.startOffset
        val indent = lineIndent(text, statementStart)
        // `new T { … }` / `new T() { … }` → `new T()`
        val head = text.subSequence(creation.textRange.startOffset, initializer.textRange.startOffset).toString().trimEnd()
        val created = if (creation.argumentList?.closeParenToken?.textLength ?: 0 > 0) head else "$head()"
        val before = text.subSequence(statementStart, creation.textRange.startOffset).toString()
        val after = text.subSequence(creation.textRange.endOffset, found.statement.textRange.endOffset).toString()
        val written = before + created + after + assignments.joinToString("") { "\n$indent$it" }
        return CSharpTextEdit(found.statement.textRange, written, (before + created + after).length)
    }
}

/** Alt+Enter on `(T)x` of a reference or nullable type: `x as T` (Rider: "To safe cast"). */
class NativeCSharpToSafeCastIntention : NativeCSharpContextAction("To safe cast", needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpExpressionEdits.castAt(file, editor.caretModel.offset)?.let { NativeCSharpExpressionEdits.toSafeCast(it, resolver(file)) }
}

abstract class NativeCSharpStringFormIntention(title: String, private val form: NativeCSharpExpressionEdits.StringForm) : NativeCSharpContextAction(title) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpExpressionEdits.stringAt(file, editor.caretModel.offset)?.let { NativeCSharpExpressionEdits.convert(it, form) }
}

/** Alt+Enter on a verbatim or raw string: the regular one, escaped (Rider: "To regular string"). */
class NativeCSharpToRegularStringIntention : NativeCSharpStringFormIntention("To regular string", NativeCSharpExpressionEdits.StringForm.REGULAR)

/** Alt+Enter on a regular string with no escapes but `\\` / `\"`: `@"…"` (Rider: "To verbatim string"). */
class NativeCSharpToVerbatimStringIntention : NativeCSharpStringFormIntention("To verbatim string", NativeCSharpExpressionEdits.StringForm.VERBATIM)

/** Alt+Enter on a one-line string: `"""…"""` (Rider: "To raw string"). */
class NativeCSharpToRawStringIntention : NativeCSharpStringFormIntention("To raw string", NativeCSharpExpressionEdits.StringForm.RAW)

/** Alt+Enter on the name of an extension method called on its receiver: the static call (Rider: "To static method invocation"). */
class NativeCSharpToStaticInvocationIntention : NativeCSharpContextAction("To static method invocation", needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpExpressionEdits.memberCallAt(file, editor.caretModel.offset)?.let { NativeCSharpExpressionEdits.toStaticInvocation(it, resolver(file)) }
}

/** Alt+Enter on the name of an extension method called statically: the call on its first argument (Rider: "To extension method invocation"). */
class NativeCSharpToExtensionInvocationIntention : NativeCSharpContextAction("To extension method invocation", needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpExpressionEdits.memberCallAt(file, editor.caretModel.offset)?.let { NativeCSharpExpressionEdits.toExtensionInvocation(it, resolver(file)) }
}

/** Alt+Enter on a call of a statement: the statement in `try` / `catch` (Rider: "Generate try-catch block"). */
class NativeCSharpGenerateTryCatchIntention : NativeCSharpContextAction("Generate try-catch block") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpExpressionEdits.throwingStatementAt(file, editor.caretModel.offset)?.let {
            NativeCSharpExpressionEdits.tryCatch(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
        }
}

/** Alt+Enter on `var x = new T { A = a };`: `var x = new T();` and `x.A = a;` (Rider: "Convert initializer to assignment"). */
class NativeCSharpInitializerToAssignmentsIntention : NativeCSharpContextAction("Convert initializer to assignment") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpExpressionEdits.initializedAt(file, editor.caretModel.offset)?.let {
            NativeCSharpExpressionEdits.initializerToAssignments(it, editor.document.charsSequence)
        }
}

