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
 * The text edits of the context actions on parameters and arguments (0.1.163), as Rider offers them: a null check of a parameter at the
 * start of the body, the names of the arguments of a call written or removed. Pure functions on the tree, the resolver and the document's
 * text, tested without an editor.
 */
object NativeCSharpParameterEdits {
    private fun present(token: PsiElement?): Boolean = (token?.textLength ?: 0) > 0

    private fun leafAt(file: PsiFile, offset: Int): List<PsiElement> = listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    private fun lineIndent(text: CharSequence, offset: Int): String {
        val start = lineStart(text, offset)
        return text.subSequence(start, offset).takeWhile { it == ' ' || it == '\t' }.toString()
    }

    // ---- Check parameter for null

    /** The parameter of a method, constructor or local function whose text holds [offset]. */
    fun parameterAt(file: PsiFile, offset: Int): CSharpParameter? {
        for (leaf in leafAt(file, offset)) {
            val parameter = PsiTreeUtil.getParentOfType(leaf, CSharpParameter::class.java, false) ?: continue
            if (parameter.parent !is CSharpParameterList) continue
            if (offset >= parameter.textRange.startOffset && offset <= parameter.textRange.endOffset) return parameter
        }
        return null
    }

    /** The block the check of [parameter] goes into: of the method, constructor or local function (expression-bodied: none). */
    fun bodyOf(parameter: CSharpParameter): CSharpBlock? = when (val owner = parameter.parent?.parent) {
        is CSharpBaseMethodDeclaration -> owner.body
        is CSharpLocalFunctionStatement -> owner.body
        else -> null
    }

    /** The parameter may be null and is not `out` / `params` / annotated `T?`: a class, interface, delegate, string, array, not a value type. */
    fun isReferenceParameter(parameter: CSharpParameter, resolver: CSharpNameResolver): Boolean {
        val type = parameter.type ?: return false
        if (type is CSharpNullableType) return false
        if (parameter.modifiers.any { it.text == "out" || it.text == "params" }) return false
        val resolved = resolver.resolveType(type) ?: return false
        if (resolved is SemanticType.Parameter) return false
        if (resolved is SemanticType.ArrayOf) return true
        return !resolver.isValueType(resolved) && resolver.definitionName(resolved) != "System.Void"
    }

    /** `ArgumentNullException.ThrowIfNull` is in the referenced assemblies (net6+). */
    fun hasThrowIfNull(resolver: CSharpNameResolver): Boolean =
        resolver.libraryType("System.ArgumentNullException")?.type?.members?.any { it.name == "ThrowIfNull" } == true

    /** The first statement of [body] checks [name] for null already: `ArgumentNullException.ThrowIfNull(name)`, `if (name == null) …`, `name ??= …`. */
    fun checksAlready(body: CSharpBlock, name: String): Boolean {
        val first = body.statements.firstOrNull() ?: return false
        val text = first.text
        val word = Regex("""\b${Regex.escape(name)}\b""")
        return when (first) {
            is CSharpExpressionStatement -> text.contains("ThrowIfNull") && word.containsMatchIn(text)
            is CSharpIfStatement -> first.condition?.text?.let { word.containsMatchIn(it) && it.contains("null") } == true
            else -> false
        }
    }

    /** The check of [parameter] as the first statement of its body (Rider: "Check parameter for null"). */
    fun checkForNull(parameter: CSharpParameter, throwIfNull: Boolean, text: CharSequence, unit: String): CSharpTextEdit? {
        val name = parameter.identifier?.text ?: return null
        val body = bodyOf(parameter) ?: return null
        val open = body.openBraceToken?.takeIf(::present) ?: return null
        val close = body.closeBraceToken?.takeIf(::present) ?: return null
        if (checksAlready(body, name)) return null
        val statement = if (throwIfNull) "ArgumentNullException.ThrowIfNull($name);" else "if ($name == null) throw new ArgumentNullException(nameof($name));"
        val first = body.statements.firstOrNull()
        if (first != null) {
            val start = lineStart(text, first.textRange.startOffset)
            val indent = lineIndent(text, first.textRange.startOffset)
            return CSharpTextEdit(TextRange(start, start), "$indent$statement\n", caret = indent.length + statement.length)
        }
        val outer = lineIndent(text, open.textRange.startOffset)
        val inner = outer + unit
        return CSharpTextEdit(TextRange(open.textRange.endOffset, close.textRange.startOffset), "\n$inner$statement\n$outer", caret = 1 + inner.length + statement.length)
    }

    // ---- Check variable / expression for null, Assert expression is not null (0.1.171, as Rider)

    /** [type] may hold null: a reference type (not a type parameter) or `T?` of a value type. */
    fun mayBeNull(type: SemanticType?, resolver: CSharpNameResolver): Boolean {
        type ?: return false
        if (type is SemanticType.Parameter) return false
        if (resolver.unwrapNullable(type) != type) return true
        if (type is SemanticType.ArrayOf) return true
        return !resolver.isValueType(type) && resolver.definitionName(type) != "System.Void"
    }

    /** The local declared at [offset] (on its type or name) with its value, alone in its statement of a block: `var incoming = …;`. */
    fun localAt(file: PsiFile, offset: Int): Pair<CSharpLocalDeclarationStatement, CSharpVariableDeclarator>? {
        val site = NativeCSharpContextEdits.typeSiteAt(file, offset) ?: return null
        if (site.foreach != null || site.constant || site.value == null) return null
        val declaration = site.type.parent as? CSharpVariableDeclaration ?: return null
        val statement = declaration.parent as? CSharpLocalDeclarationStatement ?: return null
        if (statement.parent !is CSharpBlock || present(statement.usingKeyword)) return null
        val variable = declaration.variables.singleOrNull() ?: return null
        return statement to variable
    }

    /** The check of the local [variable] right after its declaration [statement] (Rider: "Check variable for null"), the pattern of the parameters'. */
    fun checkVariable(statement: CSharpLocalDeclarationStatement, variable: CSharpVariableDeclarator, throwIfNull: Boolean, text: CharSequence): CSharpTextEdit? {
        val name = variable.identifier?.text ?: return null
        val block = statement.parent as? CSharpBlock ?: return null
        val next = block.statements.getOrNull(block.statements.indexOf(statement) + 1)
        val word = Regex("""\b${Regex.escape(name)}\b""")
        if (next != null && next.text.contains("null", ignoreCase = true) && word.containsMatchIn(next.text) && (next is CSharpIfStatement || next.text.contains("ThrowIfNull"))) return null
        val check = if (throwIfNull) "ArgumentNullException.ThrowIfNull($name);" else "if ($name == null) throw new ArgumentNullException(nameof($name));"
        val end = statement.textRange.endOffset
        val indent = lineIndent(text, statement.textRange.startOffset)
        return CSharpTextEdit(TextRange(end, end), "\n$indent$check", caret = 1 + indent.length + check.length)
    }

    /**
     * The value at [offset] a null check can be about: a name, a member access, a call, an element access, an `await` — as Introduce
     * Variable takes it (the receiver with its member, the call with its name) — in a statement of a block, not a declaration's name, a
     * type, the target of an assignment, `this`, a literal or a creation (never null).
     */
    fun expressionAt(file: PsiFile, offset: Int): Pair<CSharpExpression, CSharpStatement>? {
        for (leaf in leafAt(file, offset)) {
            if (leaf.text.isBlank() || leaf.text == ";") continue
            var expression = PsiTreeUtil.getParentOfType(leaf, CSharpExpression::class.java, false) ?: continue
            while (true) {
                val parent = expression.parent
                expression = when {
                    parent is CSharpMemberAccessExpression && parent.nameElement == expression -> parent
                    parent is CSharpMemberAccessExpression && parent.expression == expression && expression is CSharpSimpleName -> parent
                    parent is CSharpInvocationExpression && parent.expression == expression -> parent
                    parent is CSharpAwaitExpression -> parent
                    else -> break
                }
            }
            if (expression is CSharpSimpleName && NativeCSharpTypePositions.isType(expression)) return null
            if (expression is CSharpThisExpression || expression is CSharpLiteralExpression || expression is CSharpBaseObjectCreationExpression ||
                expression is CSharpAnonymousFunctionExpression || expression is CSharpTypeOfExpression) return null
            if (expression !is CSharpSimpleName && expression !is CSharpMemberAccessExpression && expression !is CSharpInvocationExpression &&
                expression !is CSharpElementAccessExpression && expression !is CSharpAwaitExpression) return null
            val assigned = expression.parent as? CSharpAssignmentExpression
            if (assigned != null && assigned.left == expression) return null
            var at: PsiElement = expression
            while (true) {
                val parent = at.parent ?: return null
                if (parent is CSharpAnonymousFunctionExpression || parent is CSharpLocalFunctionStatement || parent is CSharpMemberDeclaration || parent is PsiFile) return null
                if (parent is CSharpStatement && parent.parent is CSharpBlock) return expression to parent
                at = parent
            }
        }
        return null
    }

    /** [statement] under `if (expression != null) { … }` (Rider: "Check expression for null"). */
    fun checkExpression(expression: CSharpExpression, statement: CSharpStatement, text: CharSequence, unit: String): CSharpTextEdit {
        val indent = lineIndent(text, statement.textRange.startOffset)
        val body = statement.text.lines().mapIndexed { i, line -> if (i == 0) indent + unit + line else if (line.isBlank()) "" else unit + line }.joinToString("\n")
        return CSharpTextEdit(statement.textRange, "if (${expression.text} != null)\n$indent{\n$body\n$indent}")
    }

    /** `Debug.Assert(expression != null, …);` before [statement] (Rider: "Assert expression is not null"), qualified unless `System.Diagnostics` is imported. */
    fun assertNotNull(expression: CSharpExpression, statement: CSharpStatement, text: CharSequence, file: PsiFile): CSharpTextEdit {
        val imported = Regex("""(?m)^\s*(global\s+)?using\s+System\.Diagnostics\s*;""").containsMatchIn(file.text)
        val debug = if (imported) "Debug" else "System.Diagnostics.Debug"
        val shown = expression.text
        val message = if (expression is CSharpSimpleName) "nameof($shown) + \" != null\"" else "\"" + shown.replace("\\", "\\\\").replace("\"", "\\\"") + " != null\""
        val line = "$debug.Assert($shown != null, $message);"
        val start = lineStart(text, statement.textRange.startOffset)
        val indent = lineIndent(text, statement.textRange.startOffset)
        return CSharpTextEdit(TextRange(start, start), "$indent$line\n", caret = indent.length + line.length)
    }

    // ---- Add / Remove argument names

    /** The argument list of a call or creation whose parentheses hold [offset] (an argument of it or the parentheses themselves). */
    fun argumentsAt(file: PsiFile, offset: Int): CSharpArgumentList? {
        for (leaf in leafAt(file, offset)) {
            var element: PsiElement? = leaf
            while (element != null && element !is PsiFile) {
                if (element is CSharpArgumentList && (element.parent is CSharpInvocationExpression || element.parent is CSharpBaseObjectCreationExpression)) {
                    if (offset >= element.textRange.startOffset && offset <= element.textRange.endOffset) return element
                    return null
                }
                if (element is CSharpStatement || element is CSharpMemberDeclaration) break
                element = element.parent
            }
        }
        return null
    }

    /**
     * The parameters of the one target of [list]: the overload the resolver picks, when its parameters take the arguments by count; null
     * when nothing resolves or the pick does not fit (another overload may be meant).
     */
    internal fun parametersOf(list: CSharpArgumentList, resolver: CSharpNameResolver): List<CSharpNameResolver.Parameter>? {
        val candidate = NativeCSharpParameterInfo.candidates(list.parent, resolver).firstOrNull() ?: return null
        // `items.Add(x)` of an extension method: the receiver is no argument
        val reduced = (list.parent as? CSharpInvocationExpression)?.expression is CSharpMemberAccessExpression && resolver.isExtension(candidate)
        val parameters = resolver.signature(candidate, reduced) ?: return null
        val arguments = list.arguments
        val required = parameters.count { !it.optional && !it.isParams }
        val fits = arguments.size >= required && (arguments.size <= parameters.size || parameters.lastOrNull()?.isParams == true)
        return if (fits) parameters else null
    }

    /** The parameter [argument] (positional, at [index]) goes to; null past the `params` one, where no name can be written. */
    private fun parameterFor(parameters: List<CSharpNameResolver.Parameter>, index: Int, count: Int): CSharpNameResolver.Parameter? {
        val parameter = parameters.getOrNull(index) ?: return null
        // `params` with more than one value: its arguments cannot be named
        if (parameter.isParams && count > parameters.size) return null
        return parameter
    }

    /** `name: ` before every positional argument of [list] (Rider: "Add argument names"); null when none lacks a name or one cannot be named. */
    internal fun addNames(list: CSharpArgumentList, parameters: List<CSharpNameResolver.Parameter>, text: CharSequence): CSharpTextEdit? {
        val arguments = list.arguments
        val edits = arguments.withIndex().filter { it.value.nameColon == null }.map { (index, argument) ->
            val parameter = parameterFor(parameters, index, arguments.size) ?: return null
            argument.textRange.startOffset to parameter.name
        }
        if (edits.isEmpty()) return null
        return rewrite(list, text) { builder, base -> for ((offset, name) in edits.asReversed()) builder.insert(offset - base, "$name: ") }
    }

    /** The names dropped from every argument of [list], all named and in the parameters' order (Rider: "Remove argument names"). */
    internal fun removeNames(list: CSharpArgumentList, parameters: List<CSharpNameResolver.Parameter>, text: CharSequence): CSharpTextEdit? {
        val arguments = list.arguments
        if (arguments.isEmpty() || arguments.any { it.nameColon == null }) return null
        for ((index, argument) in arguments.withIndex()) {
            if (argument.nameColon?.nameElement?.identifier?.text != parameters.getOrNull(index)?.name) return null
        }
        return rewrite(list, text) { builder, base ->
            for (argument in arguments.asReversed()) {
                val colon = argument.nameColon ?: continue
                var end = colon.textRange.endOffset
                while (end < argument.textRange.endOffset && text[end].isWhitespace()) end++
                builder.delete(colon.textRange.startOffset - base, end - base)
            }
        }
    }

    private fun rewrite(list: CSharpArgumentList, text: CharSequence, edit: (StringBuilder, Int) -> Unit): CSharpTextEdit {
        val range = list.textRange
        val builder = StringBuilder(text.subSequence(range.startOffset, range.endOffset))
        edit(builder, range.startOffset)
        return CSharpTextEdit(range, builder.toString())
    }
}

/** Alt+Enter on a parameter of a reference type: its null check first in the body (Rider: "Check parameter for null"). */
class NativeCSharpCheckParameterForNullIntention : NativeCSharpContextAction("Check parameter for null", serverHasIt = true, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val parameter = NativeCSharpParameterEdits.parameterAt(file, editor.caretModel.offset) ?: return null
        val resolver = resolver(file)
        if (!NativeCSharpParameterEdits.isReferenceParameter(parameter, resolver)) return null
        return NativeCSharpParameterEdits.checkForNull(parameter, NativeCSharpParameterEdits.hasThrowIfNull(resolver), editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}

/** Alt+Enter on a local of a type that may be null: its check right after the declaration (Rider: "Check variable for null"). */
class NativeCSharpCheckVariableForNullIntention : NativeCSharpContextAction("Check variable for null", serverHasIt = true, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val (statement, variable) = NativeCSharpParameterEdits.localAt(file, editor.caretModel.offset) ?: return null
        val resolver = resolver(file)
        val value = variable.initializer?.value ?: return null
        val declared = (statement.declaration?.type)?.takeIf { !(it is CSharpIdentifierName && it.identifier?.text == "var") }?.let(resolver::resolveType)
        if (!NativeCSharpParameterEdits.mayBeNull(declared ?: resolver.typeOf(value), resolver)) return null
        return NativeCSharpParameterEdits.checkVariable(statement, variable, NativeCSharpParameterEdits.hasThrowIfNull(resolver), editor.document.charsSequence)
    }
}

/** Alt+Enter on a value that may be null: its statement under `if (value != null)` (Rider: "Check expression for null"). */
class NativeCSharpCheckExpressionForNullIntention : NativeCSharpContextAction("Check expression for null", serverHasIt = true, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        if (NativeCSharpParameterEdits.localAt(file, editor.caretModel.offset) != null) return null
        val (expression, statement) = NativeCSharpParameterEdits.expressionAt(file, editor.caretModel.offset) ?: return null
        val resolver = resolver(file)
        if (!NativeCSharpParameterEdits.mayBeNull(resolver.typeOf(expression), resolver)) return null
        return NativeCSharpParameterEdits.checkExpression(expression, statement, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}

/** Alt+Enter on a value that may be null: `Debug.Assert(value != null, …);` before its statement (Rider: "Assert expression is not null"). */
class NativeCSharpAssertNotNullIntention : NativeCSharpContextAction("Assert expression is not null", serverHasIt = true, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        if (NativeCSharpParameterEdits.localAt(file, editor.caretModel.offset) != null) return null
        val (expression, statement) = NativeCSharpParameterEdits.expressionAt(file, editor.caretModel.offset) ?: return null
        val resolver = resolver(file)
        if (!NativeCSharpParameterEdits.mayBeNull(resolver.typeOf(expression), resolver)) return null
        return NativeCSharpParameterEdits.assertNotNull(expression, statement, editor.document.charsSequence, file)
    }
}

/** Alt+Enter in the arguments of a call: `name: ` before each positional one (Rider: "Add argument names"). */
class NativeCSharpAddArgumentNamesIntention : NativeCSharpContextAction("Add argument names", serverHasIt = true, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val list = NativeCSharpParameterEdits.argumentsAt(file, editor.caretModel.offset) ?: return null
        if (list.arguments.count { it.nameColon == null } < 2) return null
        val parameters = NativeCSharpParameterEdits.parametersOf(list, resolver(file)) ?: return null
        return NativeCSharpParameterEdits.addNames(list, parameters, editor.document.charsSequence)
    }
}

/** Alt+Enter in the arguments of a call, all named in order: the names dropped (Rider: "Remove argument names"). */
class NativeCSharpRemoveArgumentNamesIntention : NativeCSharpContextAction("Remove argument names", serverHasIt = true, needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val list = NativeCSharpParameterEdits.argumentsAt(file, editor.caretModel.offset) ?: return null
        val parameters = NativeCSharpParameterEdits.parametersOf(list, resolver(file)) ?: return null
        return NativeCSharpParameterEdits.removeNames(list, parameters, editor.document.charsSequence)
    }
}
