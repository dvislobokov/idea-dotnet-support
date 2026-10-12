package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.suggest.SuggestionRules

/*
 * The "guard" rules of the gray text (0.1.163, [NativeCSharpGhostRule]): what a guard `if` does, what a `catch` does, the type of a
 * parameter from its first letters. Rider shows the same lines from its local network; these are rules, as the rest of
 * [NativeCSharpTypingGhost].
 */

/** The text helpers the rules of this file share. */
private object GuardText {
    fun lineStart(text: CharSequence, offset: Int): Int = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    fun lineBefore(text: CharSequence, offset: Int): String = text.subSequence(lineStart(text, offset), offset).toString()

    fun restOfLine(text: CharSequence, offset: Int): String {
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return text.subSequence(offset, end).toString()
    }

    /** The non-blank lines above the line of [offset], nearest first, [count] at most. */
    fun linesAbove(text: CharSequence, offset: Int, count: Int): List<String> {
        val result = ArrayList<String>()
        var end = lineStart(text, offset)
        while (end > 0 && result.size < count) {
            val start = lineStart(text, end - 1)
            val line = text.subSequence(start, end - 1).toString()
            if (line.isNotBlank()) result += line
            end = start
        }
        return result
    }

    /** The caret stands alone on its line: nothing typed before it but the indent, nothing after it. */
    fun emptyLine(text: CharSequence, offset: Int): Boolean = offset <= text.length && lineBefore(text, offset).isBlank() && restOfLine(text, offset).isBlank()

    /** The innermost block the whitespace at [offset] is in, and that has nothing in it. */
    fun emptyBlockAt(file: CSharpFile, offset: Int): CSharpBlock? {
        val leaf = file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return null
        val block = (if (leaf is CSharpBlock) leaf else PsiTreeUtil.getParentOfType(leaf, CSharpBlock::class.java)) ?: return null
        if (block.statements.isNotEmpty()) return null
        val open = block.openBraceToken?.textRange?.endOffset ?: return null
        val close = block.closeBraceToken?.textRange?.startOffset ?: return null
        return block.takeIf { offset in open..close }
    }

    /** The method, constructor, accessor-less local function or lambda [element] is written in. */
    fun functionOf(element: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(element, CSharpBaseMethodDeclaration::class.java, CSharpLocalFunctionStatement::class.java, CSharpAnonymousFunctionExpression::class.java)

    fun functionName(function: PsiElement): String? = when (function) {
        is CSharpMethodDeclaration -> function.identifier?.text
        is CSharpConstructorDeclaration -> function.identifier?.text
        is CSharpLocalFunctionStatement -> function.identifier?.text
        else -> null
    }

    fun returnTypeOf(function: PsiElement): String? = when (function) {
        is CSharpMethodDeclaration -> function.returnType?.text?.trim()
        is CSharpLocalFunctionStatement -> function.returnType?.text?.trim()
        is CSharpConstructorDeclaration -> "void"
        else -> null
    }

    fun parameterNames(function: PsiElement): List<String> {
        val list = when (function) {
            is CSharpBaseMethodDeclaration -> function.parameterList
            is CSharpLocalFunctionStatement -> function.parameterList
            is CSharpParenthesizedLambdaExpression -> function.parameterList
            else -> null
        }
        return list?.parameters?.mapNotNull { it.identifier?.text } ?: emptyList()
    }
}

/**
 * The first line of an empty guard block (`if (x == null) {`, `if (x is null)`, `if (string.IsNullOrEmpty(x))`, `if (!ok)`):
 * `throw new ArgumentNullException(nameof(x));` when `x` is a parameter and the guard stands first in the body (behind other guards only);
 * else `continue;` in a loop, `return;` in a `void` / `Task` function, `return false;` for `bool`, `return null;` for a nullable result
 * (`User?`, `Task<User?>`). Nothing for any other result: the two continuations are as likely there.
 */
object NativeCSharpNullGuardGhost : NativeCSharpGhostRule {
    private val IF_HEADER = Regex("""^\s*if\s*\(.*\)\s*\{?\s*$""")

    override fun place(text: CharSequence, offset: Int): Boolean {
        if (!GuardText.emptyLine(text, offset)) return false
        val above = GuardText.linesAbove(text, offset, 2)
        val header = when {
            above.isEmpty() -> return false
            above[0].trim() == "{" -> above.getOrNull(1) ?: return false
            above[0].trimEnd().endsWith("{") -> above[0]
            else -> return false
        }
        return IF_HEADER.matches(header)
    }

    override fun suggest(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): NativeCSharpTypingGhost.Suggestion? {
        val block = GuardText.emptyBlockAt(file, offset) ?: return null
        val guard = block.parent as? CSharpIfStatement ?: return null
        if (guard.statement != block) return null
        val function = GuardText.functionOf(guard) ?: return null
        // `if (true)`, `if (count > 0)`: no guard, its block is as likely anything
        val checked = guardOf(guard.condition) ?: return null
        val found = when {
            checked.name != null && checked.name in GuardText.parameterNames(function) && isFirstGuard(guard, function) -> "throw new ArgumentNullException(nameof(${checked.name}));"
            inLoop(guard, function) -> "continue;"
            else -> returnOf(GuardText.returnTypeOf(function) ?: return null) ?: return null
        }
        return NativeCSharpTypingGhost.Suggestion(SuggestionRules.NULL_GUARD, found)
    }

    /** The name of a parameter, or an empty string: `!cond`. */
    private class Guard(val name: String?)

    /** [condition] is a guard: a null check (the name checked), `!cond` or an `||` of such; null for any other condition. */
    private fun guardOf(condition: CSharpExpression?): Guard? {
        var e = condition
        while (e is CSharpParenthesizedExpression) e = e.expression
        return when {
            e is CSharpPrefixUnaryExpression && e.operatorToken?.text == "!" -> Guard(null)
            e is CSharpBinaryExpression && e.operatorToken?.text == "||" -> {
                val (left, right) = guardOf(e.left) to guardOf(e.right)
                if (left == null && right == null) null else Guard(left?.name ?: right?.name)
            }
            else -> checkedName(e)?.let { Guard(it) }
        }
    }

    /** `x` of `x == null` / `null == x` / `x is null` / `string.IsNullOrEmpty(x)` / `string.IsNullOrWhiteSpace(x)`; null for anything else. */
    private fun checkedName(condition: CSharpExpression?): String? {
        var e = condition
        while (e is CSharpParenthesizedExpression) e = e.expression
        return when (e) {
            is CSharpBinaryExpression -> {
                if (e.operatorToken?.text != "==") return null
                val (left, right) = e.left to e.right
                when {
                    isNull(right) -> identifier(left)
                    isNull(left) -> identifier(right)
                    else -> null
                }
            }
            is CSharpIsPatternExpression -> (e.pattern as? CSharpConstantPattern)?.expression?.takeIf(::isNull)?.let { identifier(e.expression) }
            is CSharpInvocationExpression -> {
                val callee = e.expression?.text?.trim() ?: return null
                if (callee != "string.IsNullOrEmpty" && callee != "string.IsNullOrWhiteSpace" && callee != "String.IsNullOrEmpty" && callee != "String.IsNullOrWhiteSpace") return null
                identifier(e.argumentList?.arguments?.singleOrNull()?.expression)
            }
            else -> null
        }
    }

    private fun isNull(e: CSharpExpression?): Boolean = e is CSharpLiteralExpression && e.text == "null"

    private fun identifier(e: CSharpExpression?): String? = (e as? CSharpIdentifierName)?.identifier?.text

    /** [guard] is a statement of the body of [function] and everything before it is a guard `if` too. */
    private fun isFirstGuard(guard: CSharpIfStatement, function: PsiElement): Boolean {
        val body = when (function) {
            is CSharpBaseMethodDeclaration -> function.body
            is CSharpLocalFunctionStatement -> function.body
            is CSharpAnonymousFunctionExpression -> function.block
            else -> null
        } ?: return false
        if (guard.parent != body) return false
        return body.statements.takeWhile { it != guard }.all { it is CSharpIfStatement && it.`else` == null }
    }

    private fun inLoop(guard: PsiElement, function: PsiElement): Boolean {
        var current = guard.parent
        while (current != null && current != function) {
            if (current is CSharpWhileStatement || current is CSharpDoStatement || current is CSharpForStatement || current is CSharpCommonForEachStatement) return true
            current = current.parent
        }
        return false
    }

    /** What leaves a function that gives [type] back early; null where several values would do. */
    private fun returnOf(type: String): String? {
        val bare = type.removePrefix("global::").removePrefix("System.Threading.Tasks.")
        return when {
            bare == "void" || bare == "Task" || bare == "ValueTask" -> "return;"
            bare == "bool" || bare == "Task<bool>" || bare == "ValueTask<bool>" -> "return false;"
            bare.endsWith("?") || bare.startsWith("Task<") && bare.endsWith("?>") || bare.startsWith("ValueTask<") && bare.endsWith("?>") -> "return null;"
            else -> null
        }
    }
}

/**
 * The first line of an empty `catch` block: `throw;`, or `_logger.LogError(e, "Method failed");` when the type around has an
 * `ILogger` field or property and the clause names its exception. Nothing once the block has a statement.
 */
object NativeCSharpCatchBodyGhost : NativeCSharpGhostRule {
    private val CATCH_HEADER = Regex("""(?:^|\W)catch\b""")

    override fun place(text: CharSequence, offset: Int): Boolean {
        if (!GuardText.emptyLine(text, offset)) return false
        val above = GuardText.linesAbove(text, offset, 2)
        val header = when {
            above.isEmpty() -> return false
            above[0].trim() == "{" -> above.getOrNull(1) ?: return false
            else -> above[0]
        }
        return CATCH_HEADER.containsMatchIn(header)
    }

    override fun suggest(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): NativeCSharpTypingGhost.Suggestion? {
        val block = GuardText.emptyBlockAt(file, offset) ?: return null
        val clause = block.parent as? CSharpCatchClause ?: return null
        val exception = clause.declaration?.identifier?.text
        val logger = if (exception == null) null else loggerOf(clause)
        val found = if (logger != null) {
            val method = GuardText.functionOf(clause)?.let(GuardText::functionName) ?: "Operation"
            "$logger.LogError($exception, \"$method failed\");"
        } else "throw;"
        return NativeCSharpTypingGhost.Suggestion(SuggestionRules.CATCH_BODY, found)
    }

    /** The name of the first `ILogger` / `ILogger<T>` field or property of the type around [at]. */
    private fun loggerOf(at: PsiElement): String? {
        val type = PsiTreeUtil.getParentOfType(at, CSharpTypeDeclaration::class.java) ?: return null
        for (member in type.members) {
            val name = when (member) {
                is CSharpFieldDeclaration -> member.declaration?.takeIf { isLogger(it.type) }?.variables?.firstOrNull()?.identifier?.text
                is CSharpPropertyDeclaration -> member.takeIf { isLogger(it.type) }?.identifier?.text
                else -> null
            }
            if (name != null) return name
        }
        return null
    }

    private fun isLogger(type: CSharpType?): Boolean = type?.text?.trim()?.removePrefix("global::")?.substringAfterLast('.')?.substringBefore('<') == "ILogger"
}

/**
 * `void M(Oper|)`: the one visible type whose name begins so, and the name a parameter of it gets, as the list names it — `ationType
 * operationType`. Nothing when two types begin with the letters, when they are a keyword or a predefined type, or when a name follows.
 */
object NativeCSharpParameterTypeGhost : NativeCSharpGhostRule {
    private val TYPE_START = Regex("""[(,]\s*(?:(?:this|ref|out|in|params|scoped|readonly)\s+)*([A-Z][A-Za-z0-9_]*)$""")
    private const val MAX_CANDIDATES = 2

    override fun place(text: CharSequence, offset: Int): Boolean {
        val rest = GuardText.restOfLine(text, offset).trimStart()
        if (!(rest.isEmpty() || rest.startsWith(")") || rest.startsWith(","))) return false
        return TYPE_START.containsMatchIn(GuardText.lineBefore(text, offset))
    }

    override fun suggest(file: CSharpFile, r: CSharpNameResolver, text: CharSequence, offset: Int): NativeCSharpTypingGhost.Suggestion? {
        val typed = TYPE_START.find(GuardText.lineBefore(text, offset))?.groupValues?.get(1) ?: return null
        if (typed in NativeCSharpCompletionPlace.ALL_KEYWORDS || typed in NativeCSharpCompletionPlace.PREDEFINED_TYPES) return null
        val leaf = file.findElementAt(offset - 1)?.takeIf { it.text == typed } ?: return null
        val parameter = PsiTreeUtil.getParentOfType(leaf, CSharpParameter::class.java) ?: return null
        val list = parameter.parent as? CSharpBaseParameterList ?: return null
        // the word is the type of the parameter (or its only word) and no name follows it
        val typeLeaf = parameter.type?.let { PsiTreeUtil.isAncestor(it, leaf, false) } == true
        val nameLeaf = parameter.identifier == leaf && parameter.type == null
        if (!typeLeaf && !nameLeaf) return null
        if (typeLeaf && (parameter.identifier?.textLength ?: 0) > 0) return null
        val syntax = NativeCSharpResolver(file)
        val matcher = PlainPrefixMatcher(typed)
        val names = LinkedHashSet<String>()
        for ((name, arities) in NativeCSharpTypeNames.candidates(file, matcher, syntax)) {
            if (!name.startsWith(typed) || 0 !in arities) continue
            if (syntax.visibleTypes(leaf, name, 0).isNotEmpty()) names += name
            if (names.size >= MAX_CANDIDATES) return null
        }
        NativeCSharpCompletionPlace.of(leaf)?.let { place ->
            for (element in NativeCSharpImportCompletion.types(place, file, matcher, emptySet(), syntax)) {
                if (element.getUserData(NativeCSharpImportCompletion.NOT_IMPORTED) != null) continue
                val name = element.lookupString
                if (!name.startsWith(typed)) continue
                // `List<>`: a generic type wants its arguments, the letters alone say nothing of them
                val presentation = LookupElementPresentation().also(element::renderElement)
                if (presentation.itemText?.contains('<') == true) continue
                names += name
                if (names.size >= MAX_CANDIDATES) return null
            }
        }
        val type = names.singleOrNull() ?: return null
        val taken = list.parameters.mapNotNullTo(HashSet()) { it.identifier?.text?.takeIf { n -> n.isNotEmpty() } }
        val name = CSharpVariableNames.unique(CSharpVariableNames.full(type) ?: return null, taken)
        val written = "$type $name"
        if (!written.startsWith(typed) || written == typed) return null
        return NativeCSharpTypingGhost.Suggestion(SuggestionRules.PARAMETER_TYPE, written.substring(typed.length))
    }
}
