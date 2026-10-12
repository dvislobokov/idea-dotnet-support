package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.semantic.CSharpReachability.Reach
import java.util.IdentityHashMap

/**
 * The errors of `return`, `await`, `switch` and the jump statements, decided by the statement and the function around it: CS0126 / CS0127 /
 * CS1997 / CS1622 (what a `return` may carry), CS8030 / CS8031 (of a lambda whose delegate type is written down), CS4032 / CS4033 /
 * CS4034 / CS1996 (`await` outside an `async` function or in a `lock`), CS0152 / CS0163 / CS8070 (duplicate case labels by their constant
 * values, a section whose end is reachable), CS0139 / CS0157 / CS0159 (`break` / `continue` / `goto` without a target, or out of a
 * `finally`, in top-level statements too). Types only name things in the messages: where a type does not resolve, nothing is said.
 *
 * Left alone, as Roslyn decides them by things not modeled here: the return of a lambda passed as an argument (overload resolution picks
 * its type), `await` in a query, a catch filter or unsafe code, a switch whose labels may subsume each other or whose governing value may
 * be constant, a fall-through after a jump the reachability does not know (a valid `goto case`, `yield`).
 */
internal class CSharpStatementChecks(
    private val resolver: CSharpNameResolver, private val isQuiet: (PsiElement) -> Boolean, private val report: (CSharpSemanticProblem) -> Unit,
) {
    private val iterators = IdentityHashMap<PsiElement, Boolean>()
    private val reachability by lazy { CSharpReachability(resolver) }
    private val constants by lazy { CSharpConstantValues(resolver) }

    fun run(unit: PsiElement) {
        PsiTreeUtil.processElements(unit) { e ->
            ProgressManager.checkCanceled()
            if (e is CSharpElement && !isQuiet(e)) when (e) {
                is CSharpReturnStatement -> checkReturn(e)
                is CSharpAwaitExpression -> e.awaitKeyword?.let { checkAwait(e, it) }
                is CSharpCommonForEachStatement -> e.awaitKeyword?.let { checkAwait(e, it) }
                is CSharpUsingStatement -> e.awaitKeyword?.let { checkAwait(e, it) }
                is CSharpLocalDeclarationStatement -> e.awaitKeyword?.let { checkAwait(e, it) }
                is CSharpSwitchStatement -> checkSwitch(e)
                is CSharpBreakStatement, is CSharpContinueStatement -> checkJump(e as CSharpStatement)
                is CSharpGotoStatement -> checkGoto(e)
            }
            true
        }
    }

    private fun error(code: String, message: String, range: TextRange) = report(CSharpSemanticProblem(code, message, range))

    // ---- the function a statement is in

    /** The nearest lambda / anonymous method, local function, accessor or member (a top-level statement is a [CSharpGlobalStatement]). */
    private fun functionOf(element: PsiElement): PsiElement? {
        var at = element.parent
        while (at != null && at !is CSharpFile) {
            if (isFunction(at)) return at
            at = at.parent
        }
        return null
    }

    private fun isFunction(e: PsiElement) =
        e is CSharpAnonymousFunctionExpression || e is CSharpLocalFunctionStatement || e is CSharpAccessorDeclaration || e is CSharpMemberDeclaration

    private fun modifiers(function: PsiElement): List<PsiElement> = when (function) {
        is CSharpAnonymousFunctionExpression -> function.modifiers
        is CSharpLocalFunctionStatement -> function.modifiers
        is CSharpAccessorDeclaration -> function.modifiers
        is CSharpMemberDeclaration -> function.modifiers
        else -> emptyList()
    }

    private fun isAsync(function: PsiElement) = modifiers(function).any { it.text == "async" }

    /** A `yield` of [function] itself, not of a lambda or local function in it: an iterator, where `return` and `await` follow other rules. */
    private fun isIterator(function: PsiElement): Boolean = iterators.getOrPut(function) {
        var found = false
        fun visit(element: PsiElement) {
            var child = element.firstChild
            while (child != null && !found) {
                when (child) {
                    is CSharpYieldStatement -> found = true
                    is CSharpAnonymousFunctionExpression, is CSharpLocalFunctionStatement -> {}
                    else -> visit(child)
                }
                child = child.nextSibling
            }
        }
        visit(function)
        found
    }

    /** What [function] returns as written: [void] for `void`, a setter, a constructor; null where it is not modeled (a lambda, an operator, `ref`). */
    private class Returns(val void: Boolean, val type: CSharpType?)

    private fun returnsOf(function: PsiElement): Returns? = when (function) {
        is CSharpMethodDeclaration -> function.returnType?.let(::returns)
        is CSharpLocalFunctionStatement -> function.returnType?.let(::returns)
        is CSharpConstructorDeclaration -> Returns(true, null)
        is CSharpAccessorDeclaration -> when (function.keyword?.text) {
            "get" -> (function.parent?.parent as? CSharpBasePropertyDeclaration)?.takeIf { it !is CSharpEventDeclaration }?.type?.let(::returns)
            "set", "init", "add", "remove" -> Returns(true, null)
            else -> null
        }
        else -> null
    }

    private fun returns(type: CSharpType): Returns? = when {
        type is CSharpRefType -> null
        type is CSharpPredefinedType && type.text == "void" -> Returns(true, null)
        else -> Returns(false, type)
    }

    /**
     * [type] as Roslyn's messages write what a function returns: `List<int>`, `int?`, `List<string?>`; the annotation of a reference type
     * itself is dropped (`string` of `string?`). Null when it does not resolve.
     */
    private fun shown(type: CSharpType): Pair<SemanticType, String>? {
        val resolved = resolver.resolveType(type) ?: return null
        val top = when {
            type !is CSharpNullableType -> type
            resolved is SemanticType.Parameter -> return null
            (resolved as? SemanticType.Library)?.type?.fullName == NULLABLE -> type
            else -> type.elementType ?: return null
        }
        return resolved to (written(top) ?: return null)
    }

    /** [type] as Roslyn's messages write it with its nullable annotations: `List<string?>`, `(string? a, int b)`, `string?[]`. */
    private fun written(type: CSharpType): String? {
        if ('?' !in type.text) return resolver.resolveType(type)?.let { CSharpTypeDisplay.display(it, qualified = false) }
        return when (type) {
            is CSharpNullableType -> {
                val element = type.elementType ?: return null
                // `T?` of an unconstrained type parameter is not modeled
                if (resolver.resolveType(element) is SemanticType.Parameter) return null
                written(element)?.plus("?")
            }
            is CSharpArrayType -> (written(type.elementType ?: return null) ?: return null) + type.rankSpecifiers.joinToString("") { it.text.filterNot(Char::isWhitespace) }
            is CSharpTupleType -> type.elements.map { e -> (written(e.type ?: return null) ?: return null) + (e.identifier?.let { " " + it.text } ?: "") }.joinToString(", ", "(", ")")
            is CSharpGenericName, is CSharpQualifiedName, is CSharpAliasQualifiedName -> {
                val generic = type as? CSharpGenericName ?: (type as? CSharpQualifiedName)?.right as? CSharpGenericName
                    ?: (type as? CSharpAliasQualifiedName)?.nameElement as? CSharpGenericName ?: return null
                if (type is CSharpQualifiedName && '?' in type.left?.text.orEmpty()) return null
                val display = resolver.resolveType(type)?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: return null
                val arguments = generic.typeArgumentList?.arguments ?: return null
                val plain = arguments.map { a -> resolver.resolveType(a)?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: return null }
                // only a name whose arguments are its own (not a type nested in a generic one: `Outer<int>.Inner<string?>`)
                val head = display.substringBefore('<')
                if (display != plain.joinToString(", ", "$head<", ">")) return null
                arguments.map { a -> written(a) ?: return null }.joinToString(", ", "$head<", ">")
            }
            else -> null
        }
    }

    // ---- return: CS0126, CS0127, CS1997, CS1622, CS0157

    private fun checkReturn(statement: CSharpReturnStatement) {
        val keyword = statement.returnKeyword ?: return
        val function = functionOf(statement) ?: return
        if (crossesFinally(statement, function)) return error("CS0157", "Control cannot leave the body of a finally clause", statement.textRange)
        if (function is CSharpAnonymousFunctionExpression) return checkLambdaReturn(statement, keyword, function)
        val returns = returnsOf(function) ?: return
        val expression = statement.expression
        if (isIterator(function)) {
            val type = returns.type?.let(resolver::resolveType) as? SemanticType.Library ?: return
            if (type.type.fullName in ITERATOR_TYPES) error("CS1622", "Cannot return a value from an iterator. Use the yield return statement to return a value, or yield break to end the iteration.", keyword.textRange)
            return
        }
        if (returns.void) {
            if (expression == null) return
            val name = functionDisplay(function) ?: return
            return error("CS0127", "Since '$name' returns void, a return keyword must not be followed by an object expression", keyword.textRange)
        }
        val (type, text) = shown(returns.type ?: return) ?: return
        if (!isAsync(function)) {
            if (expression == null) required(text, keyword)
            return
        }
        val task = type as? SemanticType.Library ?: return
        when (task.type.fullName) {
            TASK, VALUE_TASK -> if (expression != null) {
                val name = functionDisplay(function) ?: return
                error("CS1997", "Since '$name' is an async method that returns '$text', a return keyword must not be followed by an object expression", keyword.textRange)
            }
            TASK_OF, VALUE_TASK_OF -> if (expression == null) {
                val argument = (returns.type as? CSharpGenericName)?.typeArgumentList?.arguments?.singleOrNull() ?: return
                shown(argument)?.let { required(it.second, keyword) }
            }
        }
    }

    /**
     * The `return` of a lambda or an anonymous method, by the delegate type it is converted to where that is written down (a declaration,
     * an assignment, a cast; an argument depends on overload resolution): CS8030 / CS8031 for a value the delegate does not return,
     * CS0126 for a missing one. A type whose nullable annotations the type alone does not tell (`List<string?>`) is named only from syntax.
     */
    private fun checkLambdaReturn(statement: CSharpReturnStatement, keyword: PsiElement, lambda: CSharpAnonymousFunctionExpression) {
        if ((lambda as? CSharpParenthesizedLambdaExpression)?.returnType != null) return
        var at: PsiElement = lambda
        while (at.parent is CSharpParenthesizedExpression) at = at.parent
        val syntax = when (val parent = at.parent) {
            is CSharpCastExpression -> parent.type ?: return
            is CSharpAssignmentExpression -> if (parent.right == at && parent.operatorToken?.text == "=") null else return
            is CSharpEqualsValueClause -> ((parent.parent as? CSharpVariableDeclarator)?.parent as? CSharpVariableDeclaration)?.type?.takeIf { it.text != "var" } ?: return
            else -> return
        }
        val delegate = resolver.expressions.delegateOf(lambda) ?: return
        val returns = resolver.expressions.delegateSignature(delegate)?.second ?: return
        val void = resolver.definitionName(returns) == VOID
        val expression = statement.expression
        if (!isAsync(lambda)) {
            if (void) {
                if (expression != null) error("CS8030", "Anonymous function converted to a void returning delegate cannot return a value", keyword.textRange)
            } else if (expression == null) {
                lambdaType(returns, syntax)?.let { required(it, keyword) }
            }
            return
        }
        when (resolver.definitionName(returns)) {
            VOID -> if (expression != null) error("CS8030", "Anonymous function converted to a void returning delegate cannot return a value", keyword.textRange)
            TASK, VALUE_TASK -> if (expression != null) {
                val name = if (resolver.definitionName(returns) == TASK) "Task" else "ValueTask"
                error("CS8031", "Async lambda expression converted to a '$name' returning delegate cannot return a value", keyword.textRange)
            }
            TASK_OF, VALUE_TASK_OF -> if (expression == null) {
                val argument = (returns as? SemanticType.Library)?.arguments?.singleOrNull() ?: return
                lambdaType(argument, syntax)?.let { required(it, keyword) }
            }
        }
    }

    /**
     * [type] a delegate returns, as Roslyn writes it: the type alone does not keep nullable annotations, so a type with type arguments,
     * elements or a tuple is named only when the delegate type [syntax] has no `?` at all.
     */
    private fun lambdaType(type: SemanticType, syntax: CSharpType?): String? {
        if (type is SemanticType.Parameter) return null
        val display = CSharpTypeDisplay.display(type, qualified = false) ?: return null
        val simple = display.none { it == '<' || it == '[' || it == '(' } || (resolver.definitionName(type) == NULLABLE && display.count { it == '<' } == 0)
        return if (simple || syntax != null && '?' !in syntax.text) display else null
    }

    private fun required(type: String, keyword: PsiElement) = error("CS0126", "An object of a type convertible to '$type' is required", keyword.textRange)

    /** A `finally` between [statement] and its [function]. */
    private fun crossesFinally(statement: PsiElement, function: PsiElement): Boolean = innermostFinally(statement, function) != null

    private fun innermostFinally(statement: PsiElement, function: PsiElement): CSharpFinallyClause? {
        var at = statement.parent
        while (at != null && at != function) {
            if (at is CSharpFinallyClause) return at
            at = at.parent
        }
        return null
    }

    /** `Outer.Inner.M<T>(List<int>, ref int)`, `Probe.P.set`, `Probe.this[int].get`, `Local()`: a function as Roslyn's messages name it. */
    private fun functionDisplay(function: PsiElement): String? = when (function) {
        is CSharpMethodDeclaration -> if (function.explicitInterfaceSpecifier != null) null else
            "${ownerName(function) ?: return null}.${function.identifier?.text ?: return null}${typeParameters(function.typeParameterList)}(${parameters(function.parameterList) ?: return null})"
        is CSharpLocalFunctionStatement -> "${function.identifier?.text ?: return null}${typeParameters(function.typeParameterList)}(${parameters(function.parameterList) ?: return null})"
        is CSharpConstructorDeclaration -> "${ownerName(function) ?: return null}.${function.identifier?.text ?: return null}(${parameters(function.parameterList) ?: return null})"
        is CSharpAccessorDeclaration -> {
            val keyword = function.keyword?.text
            when (val property = function.parent?.parent) {
                is CSharpPropertyDeclaration -> if (property.explicitInterfaceSpecifier != null) null else "${ownerName(property) ?: return null}.${property.identifier?.text ?: return null}.$keyword"
                is CSharpIndexerDeclaration -> if (property.explicitInterfaceSpecifier != null) null else "${ownerName(property) ?: return null}.this[${parameters(property.parameterList) ?: return null}].$keyword"
                else -> null
            }
        }
        else -> null
    }

    private fun typeParameters(list: CSharpTypeParameterList?): String = list?.parameters?.joinToString(", ", "<", ">") { it.identifier?.text.orEmpty() }.orEmpty()

    private fun parameters(list: CSharpBaseParameterList?): String? = list?.parameters.orEmpty().map { p ->
        if (p.default != null || p.modifiers.any { it.text !in PARAMETER_MODIFIERS }) return null
        val type = written(p.type ?: return null) ?: return null
        (p.modifiers.map { it.text } + type).joinToString(" ")
    }.joinToString(", ")

    /** `Outer.Inner` of the type declaring [member]; null inside a generic type (Roslyn writes its type parameters). */
    private fun ownerName(member: PsiElement): String? {
        val names = ArrayList<String>()
        var at: PsiElement? = PsiTreeUtil.getParentOfType(member, CSharpBaseTypeDeclaration::class.java) ?: return null
        while (at is CSharpBaseTypeDeclaration) {
            if ((at as? CSharpTypeDeclaration)?.typeParameterList != null) return null
            names += at.identifier?.text ?: return null
            at = at.parent
        }
        return names.asReversed().joinToString(".")
    }

    // ---- await: CS4032, CS4033, CS4034, CS1996

    private fun checkAwait(element: PsiElement, keyword: PsiElement) {
        var inLock = false
        var child = element
        var at = element.parent
        while (at != null && at !is CSharpFile && !isFunction(at)) {
            when (at) {
                is CSharpLockStatement -> if (at.statement == child) inLock = true
                // CS1995 / CS7094 / CS4004 there
                is CSharpQueryExpression, is CSharpCatchFilterClause, is CSharpUnsafeStatement -> return
            }
            child = at
            at = at.parent
        }
        val function = at?.takeIf { it !is CSharpFile } ?: return
        if (generateSequence(function) { it.parent }.takeWhile { it !is CSharpFile }.any { it is CSharpUnsafeStatement || modifiers(it).any { m -> m.text == "unsafe" } }) return
        if (function !is CSharpGlobalStatement && !isAsync(function)) {
            if (function is CSharpAnonymousFunctionExpression) {
                val what = if (function is CSharpAnonymousMethodExpression) "anonymous method" else "lambda expression"
                error("CS4034", "The 'await' operator can only be used within an async $what. Consider marking this $what with the 'async' modifier.", keyword.textRange)
            } else {
                if (isIterator(function)) return
                val returns = returnsOf(function) ?: return
                if (returns.void) {
                    error("CS4033", "The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task'.", keyword.textRange)
                } else {
                    val type = shown(returns.type ?: return)?.second ?: return
                    error("CS4032", "The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task<$type>'.", keyword.textRange)
                }
            }
        }
        if (inLock) error("CS1996", "Cannot await in the body of a lock statement", keyword.textRange)
    }

    // ---- switch: CS0152, CS0163, CS8070

    private fun checkSwitch(switch: CSharpSwitchStatement) {
        val sections = switch.sections
        val labels = sections.flatMap { it.labels }
        // a pattern without `when` may subsume what follows it (CS8120 then, and unreachable sections)
        if (labels.any { it is CSharpCasePatternSwitchLabel && it.whenClause == null }) return
        val governing = switch.expression?.let(resolver::typeOf)
        val seen = HashSet<String>()
        val duplicates = HashSet<CSharpSwitchLabel>()
        var comparable = true
        for (label in labels) {
            if (label !is CSharpCaseSwitchLabel) continue
            val value = label.value ?: return
            val constant = governing?.let { caseValue(value, it) }
            if (constant != null) {
                if (!seen.add(constant.key)) {
                    duplicates += label
                    error("CS0152", "The switch statement contains multiple cases with the label value '${shown(constant, quoted = true)}'", label.textRange)
                }
                continue
            }
            // a named constant: the same name twice is a duplicate too, two names of one value are not seen
            if (!isNamedConstant(value)) comparable = false
            else if (!seen.add("name:" + value.text.filterNot(Char::isWhitespace))) duplicates += label
        }
        if (comparable) checkFallThrough(switch, sections, duplicates)
    }

    /** The sections whose end is surely reachable: CS0163, CS8070 for the last one, on the last label of the section. */
    private fun checkFallThrough(switch: CSharpSwitchStatement, sections: List<CSharpSwitchSection>, duplicates: Set<CSharpSwitchLabel>) {
        // a constant governing value or `when` condition makes sections unreachable
        if (reachability.constant(switch.expression) != CSharpReachability.Constant.NO) return
        val labels = sections.flatMap { it.labels }
        if (labels.any { it is CSharpCasePatternSwitchLabel && reachability.constant(it.whenClause?.condition) != CSharpReachability.Constant.NO }) return
        if (labels.any { it !is CSharpCaseSwitchLabel && it !is CSharpDefaultSwitchLabel && it !is CSharpCasePatternSwitchLabel }) return
        if (mayBeUnreachable(switch)) return
        for ((i, section) in sections.withIndex()) {
            val last = section.labels.lastOrNull() ?: continue
            if (section.labels.any { it in duplicates } || endOf(section.statements) != Reach.YES) continue
            if (i == sections.lastIndex) error("CS8070", "Control cannot fall out of switch from final case label ('${last.text}')", last.textRange)
            else error("CS0163", "Control cannot fall through from one case label ('${last.text}') to another", last.textRange)
        }
    }

    /**
     * As [CSharpReachability] reads a block: a local function is not executed in order; a `continue` without a loop, a `goto` without
     * its label (CS0139 / CS0159 there) do not jump, Roslyn goes on past them.
     */
    private fun endOf(statements: List<CSharpStatement>): Reach {
        var reach = Reach.YES
        for (statement in statements) {
            if (statement is CSharpLocalFunctionStatement) continue
            val end = if (isBrokenJump(statement)) Reach.YES else reachability.endOf(statement)
            reach = when (reach) {
                Reach.NO -> return Reach.NO
                Reach.UNKNOWN -> if (end == Reach.NO) Reach.NO else Reach.UNKNOWN
                Reach.YES -> end
            }
        }
        return reach
    }

    private fun isBrokenJump(statement: CSharpStatement): Boolean = when (statement) {
        is CSharpBreakStatement, is CSharpContinueStatement -> jumpTarget(statement) == JumpTarget.NONE
        is CSharpGotoStatement -> missingGoto(statement) != null
        else -> false
    }

    /** A statement before [statement] (in it or in a block around it) whose end is not reachable: Roslyn says nothing of dead code. */
    private fun mayBeUnreachable(statement: PsiElement): Boolean {
        var child = statement
        var at = statement.parent
        while (at != null && at !is CSharpFile && (!isFunction(at) || at is CSharpGlobalStatement)) {
            val siblings = when (at) {
                is CSharpBlock -> at.statements
                is CSharpSwitchSection -> at.statements
                // top-level statements are one block
                is CSharpGlobalStatement -> at.parent?.children.orEmpty().filterIsInstance<CSharpGlobalStatement>().mapNotNull { it.statement }
                else -> emptyList()
            }
            for (s in siblings) {
                if (s == child) break
                if (s is CSharpLocalFunctionStatement || isBrokenJump(s)) continue
                // a `goto` that finds its label always jumps (the reachability does not follow it)
                if (s is CSharpGotoStatement || reachability.endOf(s) == Reach.NO) return true
            }
            child = at
            at = at.parent
        }
        return false
    }

    /**
     * The value of a case label of a switch on [governing] (`int`, `long`, `char`, `string`, an enum): a constant expression
     * [CSharpConstantValues] folds, of a type that converts to the governing one; null for anything else (another error there).
     */
    private fun caseValue(value: CSharpExpression, governing: SemanticType): CSharpConstantValues.Value? =
        constants.of(value)?.let { constants.forSwitch(it, governing) }

    /**
     * A value as Roslyn's messages write it: a number (an enum member's too), the character itself, a string in quotes in CS0152
     * ([quoted]), bare in CS0159 (`case b:`), `null` there as nothing.
     */
    private fun shown(value: CSharpConstantValues.Value, quoted: Boolean): String = when (value.kind) {
        CSharpConstantValues.Kind.STRING -> if (quoted) "\"${value.text}\"" else value.text.orEmpty()
        CSharpConstantValues.Kind.NULL -> if (quoted) "null" else ""
        CSharpConstantValues.Kind.CHAR -> value.number!!.toInt().toChar().toString()
        else -> value.number.toString()
    }

    /** A literal (of any type) or a name of a constant or an enum member: a case label that cannot be a type pattern. */
    private fun isNamedConstant(value: CSharpExpression): Boolean {
        var e = unparenthesized(value)
        if (e is CSharpPrefixUnaryExpression) e = unparenthesized(e.operand ?: return false)
        if (e is CSharpLiteralExpression) return true
        val name = when (e) {
            is CSharpIdentifierName -> e
            is CSharpMemberAccessExpression -> e.nameElement
            else -> null
        } ?: return false
        return when (val symbol = name.identifier?.let(resolver::resolve)?.single) {
            is CSharpSymbol.SourceMember -> symbol.element is CSharpEnumMemberDeclaration ||
                PsiTreeUtil.getParentOfType(symbol.element, CSharpBaseFieldDeclaration::class.java, false)?.modifiers?.any { it.text == "const" } == true
            is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.CONSTANT || symbol.member.kind == IndexedMemberKind.ENUM_MEMBER
            is CSharpSymbol.Local -> symbol.symbol.kind == LocalSymbolKind.LOCAL &&
                PsiTreeUtil.getParentOfType(symbol.symbol.declaration, CSharpLocalDeclarationStatement::class.java)?.modifiers?.any { it.text == "const" } == true
            else -> false
        }
    }

    private fun unparenthesized(expression: CSharpExpression): CSharpExpression {
        var e = expression
        while (e is CSharpParenthesizedExpression) e = e.expression ?: return e
        return e
    }

    // ---- jumps: CS0139, CS0157, CS0159

    /** `break` / `continue`: CS0139 with no loop (or switch, for `break`) up to the member, CS0157 when the one there is outside a `finally`. */
    private fun checkJump(statement: CSharpStatement) {
        when (jumpTarget(statement)) {
            JumpTarget.NONE -> error("CS0139", "No enclosing loop out of which to break or continue", statement.textRange)
            JumpTarget.OUT_OF_FINALLY -> error("CS0157", "Control cannot leave the body of a finally clause", statement.textRange)
            else -> {}
        }
    }

    private enum class JumpTarget { FOUND, OUT_OF_FINALLY, NONE, UNKNOWN }

    private fun jumpTarget(statement: CSharpStatement): JumpTarget {
        val isBreak = statement is CSharpBreakStatement
        var crossedFinally = false
        var at = statement.parent
        while (at != null && at !is CSharpFile) {
            when {
                at is CSharpWhileStatement || at is CSharpDoStatement || at is CSharpForStatement || at is CSharpCommonForEachStatement || isBreak && at is CSharpSwitchStatement ->
                    return if (crossedFinally) JumpTarget.OUT_OF_FINALLY else JumpTarget.FOUND
                at is CSharpFinallyClause -> crossedFinally = true
                // a lambda or local function inside a loop: CS1632
                at is CSharpAnonymousFunctionExpression || at is CSharpLocalFunctionStatement -> return JumpTarget.UNKNOWN
                // the member, top-level statements too
                at is CSharpAccessorDeclaration || at is CSharpMemberDeclaration -> return JumpTarget.NONE
            }
            at = at.parent
        }
        return JumpTarget.UNKNOWN
    }

    private fun checkGoto(statement: CSharpGotoStatement) {
        missingGoto(statement)?.let { (message, range) -> return error("CS0159", message, range) }
        if (statement.caseOrDefaultKeyword != null) return
        val function = functionOf(statement) ?: return
        val name = (statement.expression as? CSharpIdentifierName)?.identifier ?: return
        val finally = innermostFinally(statement, function) ?: return
        if (labels(function).filter { it.identifier?.text == name.text }.none { PsiTreeUtil.isAncestor(finally, it, true) })
            error("CS0157", "Control cannot leave the body of a finally clause", statement.textRange)
    }

    /**
     * CS0159 of a `goto`: the message and the range when its label surely is not there; a `goto case` / `goto default` looks in the
     * nearest switch, by the values [CSharpConstantValues] folds.
     */
    private fun missingGoto(statement: CSharpGotoStatement): Pair<String, TextRange>? {
        val function = functionOf(statement) ?: return null
        val caseOrDefault = statement.caseOrDefaultKeyword
        if (caseOrDefault == null) {
            val name = (statement.expression as? CSharpIdentifierName)?.identifier ?: return null
            if (labels(function).any { it.identifier?.text == name.text }) return null
            return "No such label '${name.text}' within the scope of the goto statement" to name.textRange
        }
        var at = statement.parent
        while (at != null && at !is CSharpSwitchStatement) {
            if (at == function || at is CSharpFinallyClause || at is CSharpAnonymousFunctionExpression || at is CSharpLocalFunctionStatement) return null
            at = at.parent
        }
        val switch = at as? CSharpSwitchStatement ?: return null
        val labels = switch.sections.flatMap { it.labels }
        if (labels.any { it !is CSharpCaseSwitchLabel && it !is CSharpDefaultSwitchLabel }) return null
        if (caseOrDefault.text == "default") {
            return if (labels.none { it is CSharpDefaultSwitchLabel }) "No such label 'default:' within the scope of the goto statement" to statement.textRange else null
        }
        val governing = switch.expression?.let(resolver::typeOf) ?: return null
        val target = caseValue(statement.expression ?: return null, governing) ?: return null
        val values = labels.filterIsInstance<CSharpCaseSwitchLabel>().map { caseValue(it.value ?: return null, governing)?.key ?: return null }
        if (target.key in values) return null
        return "No such label 'case ${shown(target, quoted = false)}:' within the scope of the goto statement" to statement.textRange
    }

    /** The labeled statements of [function] itself, not of the lambdas and local functions in it; of all top-level statements for one of them. */
    private fun labels(function: PsiElement): List<CSharpLabeledStatement> {
        if (function is CSharpGlobalStatement) {
            return function.parent?.children.orEmpty().filterIsInstance<CSharpGlobalStatement>().flatMap(::ownLabels)
        }
        return ownLabels(function)
    }

    private fun ownLabels(function: PsiElement): List<CSharpLabeledStatement> {
        val found = ArrayList<CSharpLabeledStatement>()
        fun visit(element: PsiElement) {
            var child = element.firstChild
            while (child != null) {
                if (child is CSharpLabeledStatement) found += child
                if (child !is CSharpAnonymousFunctionExpression && child !is CSharpLocalFunctionStatement) visit(child)
                child = child.nextSibling
            }
        }
        visit(function)
        return found
    }

    private companion object {
        const val VOID = "System.Void"
        const val NULLABLE = "System.Nullable`1"
        const val TASK = "System.Threading.Tasks.Task"
        const val TASK_OF = "System.Threading.Tasks.Task`1"
        const val VALUE_TASK = "System.Threading.Tasks.ValueTask"
        const val VALUE_TASK_OF = "System.Threading.Tasks.ValueTask`1"
        val ITERATOR_TYPES = setOf("System.Collections.IEnumerable", "System.Collections.IEnumerator", "System.Collections.Generic.IEnumerable`1",
            "System.Collections.Generic.IEnumerator`1", "System.Collections.Generic.IAsyncEnumerable`1", "System.Collections.Generic.IAsyncEnumerator`1")
        val PARAMETER_MODIFIERS = setOf("ref", "out", "in", "params")
    }
}
