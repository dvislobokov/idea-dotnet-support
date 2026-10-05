package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.TypeKind

/**
 * Whether the end point of a statement is reachable (C# §13.2, the part CS0161 needs, task C4c): three answers, and [Reach.UNKNOWN] wherever
 * the rules depend on something not modeled here — a condition that may be a constant, a switch that may be exhaustive, `goto`. A method
 * is reported only on [Reach.YES].
 */
class CSharpReachability(private val resolver: CSharpNameResolver) {
    enum class Reach { YES, NO, UNKNOWN }

    fun endOf(statement: CSharpStatement?): Reach = when (statement) {
        null -> Reach.UNKNOWN
        is CSharpBlock -> sequence(statement.statements)
        is CSharpExpressionStatement, is CSharpLocalDeclarationStatement, is CSharpEmptyStatement, is CSharpLocalFunctionStatement,
        is CSharpCommonForEachStatement -> Reach.YES
        is CSharpReturnStatement, is CSharpThrowStatement, is CSharpBreakStatement, is CSharpContinueStatement -> Reach.NO
        is CSharpIfStatement -> {
            val then = endOf(statement.statement)
            val otherwise = statement.`else`?.let { endOf(it.statement) } ?: Reach.YES
            when (constant(statement.condition)) {
                Constant.TRUE -> then
                Constant.FALSE -> otherwise
                Constant.NO -> or(then, otherwise)
                Constant.UNKNOWN -> if (then == otherwise) then else Reach.UNKNOWN
            }
        }
        is CSharpWhileStatement -> loop(statement, statement.condition, statement.statement)
        is CSharpForStatement -> loop(statement, statement.condition, statement.statement, conditionMissing = statement.condition == null)
        is CSharpDoStatement -> when {
            breaks(statement) -> Reach.YES
            else -> when (constant(statement.condition)) {
                Constant.TRUE -> Reach.NO
                Constant.NO, Constant.FALSE -> if (endOf(statement.statement) == Reach.NO && !continues(statement)) Reach.NO else if (endOf(statement.statement) == Reach.YES) Reach.YES else Reach.UNKNOWN
                Constant.UNKNOWN -> Reach.UNKNOWN
            }
        }
        is CSharpSwitchStatement -> switch(statement)
        is CSharpTryStatement -> {
            val finally = statement.finally?.let { endOf(it.block) } ?: Reach.YES
            if (finally != Reach.YES) finally
            else statement.catches.fold(endOf(statement.block)) { reach, clause -> or(reach, endOf(clause.block)) }
        }
        is CSharpCheckedStatement -> endOf(statement.block)
        is CSharpUnsafeStatement -> endOf(PsiTreeUtil.getChildOfType(statement, CSharpBlock::class.java))
        is CSharpLockStatement -> endOf(PsiTreeUtil.getChildOfType(statement, CSharpStatement::class.java))
        is CSharpUsingStatement -> endOf(PsiTreeUtil.getChildOfType(statement, CSharpStatement::class.java))
        is CSharpFixedStatement -> endOf(PsiTreeUtil.getChildOfType(statement, CSharpStatement::class.java))
        else -> Reach.UNKNOWN
    }

    private fun sequence(statements: List<CSharpStatement>): Reach {
        var reach = Reach.YES
        for (statement in statements) {
            // a local function declared after the end: it is not executed in order
            if (statement is CSharpLocalFunctionStatement) continue
            reach = when (reach) {
                Reach.NO -> return Reach.NO
                Reach.UNKNOWN -> if (endOf(statement) == Reach.NO) Reach.NO else Reach.UNKNOWN
                Reach.YES -> endOf(statement)
            }
        }
        return reach
    }

    private fun loop(loop: CSharpStatement, condition: CSharpExpression?, body: CSharpStatement?, conditionMissing: Boolean = false): Reach {
        if (body == null) return Reach.UNKNOWN
        val c = if (conditionMissing) Constant.TRUE else constant(condition)
        return when (c) {
            Constant.TRUE -> if (breaks(loop)) Reach.YES else Reach.NO
            Constant.FALSE, Constant.NO -> Reach.YES
            Constant.UNKNOWN -> if (breaks(loop)) Reach.YES else Reach.UNKNOWN
        }
    }

    private fun switch(statement: CSharpSwitchStatement): Reach {
        val labels = statement.sections.flatMap { it.labels }
        if (labels.any { it is CSharpCasePatternSwitchLabel }) return Reach.UNKNOWN
        if (breaks(statement)) return Reach.YES
        if (labels.any { it is CSharpDefaultSwitchLabel }) {
            // every section ends unreachably (C# requires it), and nothing breaks out
            return if (statement.sections.all { s -> s.statements.isNotEmpty() && sequence(s.statements) == Reach.NO }) Reach.NO else Reach.UNKNOWN
        }
        // no default: the end is reached for a value no case has — a type with values beyond any list of constants
        val type = statement.expression?.let(resolver::typeOf) as? SemanticType.Library
        val enumOrNumber = when {
            type == null -> false
            type.type.kind == IndexedTypeKind.ENUM -> true
            else -> type.type.fullName in OPEN_TYPES
        }
        val sourceEnum = (statement.expression?.let(resolver::typeOf) as? SemanticType.Source)?.info?.kind == TypeKind.ENUM
        return if (enumOrNumber || sourceEnum) Reach.YES else Reach.UNKNOWN
    }

    /** A `break` that leaves [target]: in it, not in a nested loop, switch or function. */
    private fun breaks(target: CSharpStatement): Boolean = jumps(target, CSharpBreakStatement::class.java, stopAtSwitch = true)

    private fun continues(target: CSharpStatement): Boolean = jumps(target, CSharpContinueStatement::class.java, stopAtSwitch = false)

    private fun jumps(target: CSharpStatement, kind: Class<out CSharpStatement>, stopAtSwitch: Boolean): Boolean {
        var found = false
        fun visit(element: PsiElement) {
            var child = element.firstChild
            while (child != null && !found) {
                when {
                    kind.isInstance(child) -> found = true
                    child is CSharpAnonymousFunctionExpression || child is CSharpLocalFunctionStatement -> {}
                    child is CSharpWhileStatement || child is CSharpDoStatement || child is CSharpForStatement || child is CSharpCommonForEachStatement -> {}
                    stopAtSwitch && child is CSharpSwitchStatement -> {}
                    else -> visit(child)
                }
                child = child.nextSibling
            }
        }
        visit(target)
        return found
    }

    enum class Constant { TRUE, FALSE, NO, UNKNOWN }

    /** `true` / `false` literally, NO when the condition surely varies (a call, a variable, a property), else UNKNOWN (C# folds constants). */
    fun constant(condition: CSharpExpression?): Constant {
        condition ?: return Constant.UNKNOWN
        when (condition.elementType) {
            SyntaxKind.TrueLiteralExpression -> return Constant.TRUE
            SyntaxKind.FalseLiteralExpression -> return Constant.FALSE
        }
        var varies = false
        PsiTreeUtil.processElements(condition) { e ->
            when (e) {
                is CSharpInvocationExpression, is CSharpAwaitExpression, is CSharpElementAccessExpression, is CSharpThisExpression, is CSharpIsPatternExpression -> varies = true
                is CSharpSimpleName -> e.identifier?.let(resolver::resolve)?.single?.let { if (isVariable(it)) varies = true }
            }
            !varies
        }
        return if (varies) Constant.NO else Constant.UNKNOWN
    }

    private fun isVariable(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.Local -> when (symbol.symbol.kind) {
            LocalSymbolKind.PARAMETER, LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER -> true
            LocalSymbolKind.LOCAL -> PsiTreeUtil.getParentOfType(symbol.symbol.declaration, CSharpLocalDeclarationStatement::class.java)?.modifiers?.none { it.text == "const" } ?: true
            else -> false
        }
        is CSharpSymbol.SourceMember -> symbol.element is CSharpBasePropertyDeclaration ||
            (PsiTreeUtil.getParentOfType(symbol.element, CSharpBaseFieldDeclaration::class.java, false) ?: symbol.element as? CSharpBaseFieldDeclaration)?.modifiers?.none { it.text == "const" } == true
        is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.PROPERTY || symbol.member.kind == IndexedMemberKind.FIELD
        else -> false
    }

    private fun or(a: Reach, b: Reach): Reach = when {
        a == Reach.YES || b == Reach.YES -> Reach.YES
        a == Reach.NO && b == Reach.NO -> Reach.NO
        else -> Reach.UNKNOWN
    }

    private companion object {
        val OPEN_TYPES = setOf("System.Int32", "System.Int64", "System.String", "System.Char", "System.Int16", "System.UInt32", "System.UInt64")
    }
}
