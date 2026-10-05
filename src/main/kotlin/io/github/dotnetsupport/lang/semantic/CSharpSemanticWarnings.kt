package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.NativeCSharpScopes
import io.github.dotnetsupport.lang.TypeKind

/**
 * The compiler warnings of task D2 that need no flow analysis beyond reachability, with Roslyn's codes and spans:
 * - CS0162, unreachable code: the first token of the first statement after one whose end is surely unreachable (gray to the end of the block);
 * - CS0168 / CS0219, a local never used: declared and never referenced, or only ever assigned constants;
 * - CS4014, a call of an `async` function's body that returns a task and is not awaited;
 * - nullable warnings of the simplest kind, where the nullable context is on: `null` (`default`, `(T)null`, `null!` is fine) to a local of a
 *   non-nullable reference type (CS8600), to a field, property or argument of one (CS8625), `return null` of a method that returns one
 *   (CS8603), and a field or auto-property of one no constructor sets (CS8618, a class without constructors). Nothing that depends on the
 *   flow state of a variable (CS8601, CS8602, CS8604): telling those apart needs the nullable flow analysis of Roslyn.
 *
 * As [CSharpSemanticChecks]: a missing warning is fine, a false one is not. [report] drops what `#pragma warning disable` or the project
 * turns off ([CSharpWarningContext]).
 */
internal class CSharpSemanticWarnings(private val resolver: CSharpNameResolver, private val report: (CSharpSemanticProblem) -> Unit) {
    private val file: CSharpFile = resolver.file
    private val reachability = CSharpReachability(resolver)

    fun warn(code: String, message: String, range: TextRange, gray: TextRange? = null) {
        if (CSharpWarningContext.isSuppressed(file, code, range.startOffset)) return
        report(CSharpSemanticProblem(code, message, range, warning = true, gray = gray))
    }

    // ---- CS0162

    /** The statements of the body [body] of a function: what follows a statement whose end is surely unreachable is reported, once a block. */
    fun checkUnreachable(body: CSharpBlock) {
        val function = body.parent ?: return
        if (PsiTreeUtil.findChildOfAnyType(function, CSharpGotoStatement::class.java, CSharpLabeledStatement::class.java, CSharpYieldStatement::class.java) != null) return
        sequence(body.statements)
    }

    private fun sequence(statements: List<CSharpStatement>) {
        var reach = CSharpReachability.Reach.YES
        for ((i, statement) in statements.withIndex()) {
            if (statement is CSharpLocalFunctionStatement) continue
            when (reach) {
                CSharpReachability.Reach.NO -> {
                    val first = firstReported(statements.subList(i, statements.size)) ?: return
                    val token = firstToken(first) ?: return
                    val end = statements.last().textRange.endOffset
                    warn("CS0162", "Unreachable code detected", token.textRange, TextRange(first.textRange.startOffset, maxOf(end, first.textRange.endOffset)))
                    return
                }
                CSharpReachability.Reach.YES -> inner(statement)
                CSharpReachability.Reach.UNKNOWN -> {}
            }
            val end = reachability.endOf(statement)
            reach = when (reach) {
                CSharpReachability.Reach.YES -> end
                else -> if (end == CSharpReachability.Reach.NO) CSharpReachability.Reach.NO else CSharpReachability.Reach.UNKNOWN
            }
        }
    }

    /** The statements inside [statement] that are reachable when it is. */
    private fun inner(statement: CSharpStatement) {
        when (statement) {
            is CSharpBlock -> sequence(statement.statements)
            is CSharpIfStatement -> {
                val c = reachability.constant(statement.condition)
                if (c == CSharpReachability.Constant.NO || c == CSharpReachability.Constant.TRUE) statement.statement?.let(::embedded)
                if (c == CSharpReachability.Constant.NO || c == CSharpReachability.Constant.FALSE) statement.`else`?.statement?.let(::embedded)
            }
            is CSharpWhileStatement -> reachability.constant(statement.condition).let { c ->
                if (c == CSharpReachability.Constant.NO || c == CSharpReachability.Constant.TRUE) statement.statement?.let(::embedded)
            }
            is CSharpForStatement -> {
                val c = if (statement.condition == null) CSharpReachability.Constant.TRUE else reachability.constant(statement.condition)
                if (c == CSharpReachability.Constant.NO || c == CSharpReachability.Constant.TRUE) statement.statement?.let(::embedded)
            }
            is CSharpDoStatement -> statement.statement?.let(::embedded)
            is CSharpCommonForEachStatement -> statement.statement?.let(::embedded)
            is CSharpTryStatement -> {
                statement.block?.let(::inner)
                statement.catches.forEach { it.block?.let(::inner) }
                statement.finally?.block?.let(::inner)
            }
            is CSharpSwitchStatement -> if (reachability.constant(statement.expression) == CSharpReachability.Constant.NO) {
                statement.sections.forEach { sequence(it.statements) }
            }
            is CSharpUsingStatement -> statement.statement?.let(::embedded)
            is CSharpLockStatement -> statement.statement?.let(::embedded)
            is CSharpCheckedStatement -> statement.block?.let(::inner)
            is CSharpUnsafeStatement, is CSharpFixedStatement -> PsiTreeUtil.getChildOfType(statement, CSharpStatement::class.java)?.let(::embedded)
        }
    }

    private fun embedded(statement: CSharpStatement) = if (statement is CSharpBlock) sequence(statement.statements) else inner(statement)

    /** Roslyn reports no block, empty statement or local function: the first statement in them or after them. */
    private fun firstReported(statements: List<CSharpStatement>): CSharpStatement? {
        for (statement in statements) {
            when (statement) {
                is CSharpBlock -> firstReported(statement.statements)?.let { return it }
                is CSharpEmptyStatement, is CSharpLocalFunctionStatement -> {}
                else -> return statement
            }
        }
        return null
    }

    private fun firstToken(element: PsiElement): PsiElement? {
        var leaf: PsiElement? = PsiTreeUtil.getDeepestFirst(element)
        while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment || leaf.textLength == 0)) leaf = PsiTreeUtil.nextLeaf(leaf)
        return leaf?.takeIf { element.textRange.contains(it.textRange) }
    }

    // ---- CS0168, CS0219

    /** Every local of a declaration statement or of `for` that nothing reads. */
    fun checkUnusedLocals() {
        for (symbol in NativeCSharpScopes.of(file).symbols) {
            if (symbol.kind != LocalSymbolKind.LOCAL) continue
            val declarator = symbol.declaration.parent as? CSharpVariableDeclarator ?: continue
            if (declarator.argumentList != null) continue
            val declaration = declarator.parent as? CSharpVariableDeclaration ?: continue
            val statement = declaration.parent
            if (statement !is CSharpLocalDeclarationStatement && statement !is CSharpForStatement) continue
            if (statement is CSharpLocalDeclarationStatement && statement.usingKeyword != null) continue
            if (statement.parent is CSharpGlobalStatement) continue
            if (isQuiet(declarator)) continue
            val initializer = declarator.initializer?.value
            if (initializer != null && !isConstant(initializer)) continue
            var written = false
            var used = false
            for (reference in symbol.references) {
                val name = reference.parent as? CSharpSimpleName
                if (name == null) { used = true; break }
                val assignment = name.parent as? CSharpAssignmentExpression
                if (assignment != null && assignment.left == name && assignment.operatorToken?.text == "=") {
                    val value = assignment.right
                    if (value == null || !isConstant(value)) { used = true; break }
                    written = true
                } else {
                    used = true
                    break
                }
            }
            if (used) continue
            val identifier = symbol.declaration
            if (initializer == null && !written) warn("CS0168", "The variable '${symbol.name}' is declared but never used", identifier.textRange)
            else warn("CS0219", "The variable '${symbol.name}' is assigned but its value is never used", identifier.textRange)
        }
    }

    /** A constant expression as Roslyn folds it for CS0219: literals, `default`, `nameof`, casts and operators of them, `null!`. */
    private fun isConstant(expression: CSharpExpression): Boolean = constantKind(expression) != null

    private enum class ConstantKind { STRING, OTHER, NULL }

    private fun constantKind(e: CSharpExpression): ConstantKind? = when (e) {
        is CSharpLiteralExpression -> when (e.elementType) {
            SyntaxKind.StringLiteralExpression -> ConstantKind.STRING
            SyntaxKind.NullLiteralExpression, SyntaxKind.DefaultLiteralExpression -> ConstantKind.NULL
            SyntaxKind.NumericLiteralExpression, SyntaxKind.CharacterLiteralExpression, SyntaxKind.TrueLiteralExpression, SyntaxKind.FalseLiteralExpression -> ConstantKind.OTHER
            else -> null
        }
        is CSharpDefaultExpression -> ConstantKind.NULL
        is CSharpParenthesizedExpression -> e.expression?.let(::constantKind)
        is CSharpPostfixUnaryExpression -> if (e.operatorToken?.text == "!") e.operand?.let(::constantKind) else null
        is CSharpPrefixUnaryExpression -> if (e.operatorToken?.text in setOf("-", "+", "!", "~")) e.operand?.let(::constantKind)?.takeIf { it == ConstantKind.OTHER } else null
        is CSharpCastExpression -> e.expression?.let(::constantKind)?.let { kind -> if (kind == ConstantKind.NULL || castKeepsConstant(e.type, kind)) kind else null }
        is CSharpBinaryExpression -> {
            val left = e.left?.let(::constantKind)
            val right = e.right?.let(::constantKind)
            val op = e.operatorToken?.text
            when {
                left == null || right == null -> null
                left == ConstantKind.STRING && right == ConstantKind.STRING && op == "+" -> ConstantKind.STRING
                left == ConstantKind.OTHER && right == ConstantKind.OTHER && op in ARITHMETIC -> ConstantKind.OTHER
                else -> null
            }
        }
        is CSharpInvocationExpression -> if ((e.expression as? CSharpIdentifierName)?.identifier?.text == "nameof" && resolver.syntax.symbolAt((e.expression as CSharpIdentifierName).identifier!!) == null &&
            (e.expression as? CSharpIdentifierName)?.identifier?.let(resolver::resolve)?.symbols.isNullOrEmpty()) ConstantKind.STRING else null
        else -> null
    }

    /** `(int)1.5`, `(string)"x"`: a cast to a predefined type of a literal; others may be user-defined conversions. */
    private fun castKeepsConstant(type: CSharpType?, kind: ConstantKind): Boolean = type is CSharpPredefinedType && (kind == ConstantKind.OTHER && type.text != "object" && type.text != "string" || kind == ConstantKind.STRING && type.text == "string")

    // ---- CS4014

    fun checkNotAwaited(statement: CSharpExpressionStatement) {
        val call = statement.expression as? CSharpInvocationExpression ?: return
        val function = enclosingFunction(statement) ?: return
        val modifiers = when (function) {
            is CSharpMethodDeclaration -> function.modifiers
            is CSharpLocalFunctionStatement -> function.modifiers
            is CSharpAnonymousFunctionExpression -> function.modifiers
            else -> return
        }
        if (modifiers.none { it.text == "async" }) return
        val type = resolver.typeOf(call) as? SemanticType.Library ?: return
        if (type.type.fullName !in TASKS) return
        warn("CS4014", "Because this call is not awaited, execution of the current method continues before the call is completed. " +
            "Consider applying the 'await' operator to the result of the call.", call.textRange)
    }

    private fun enclosingFunction(element: PsiElement): PsiElement? {
        var at: PsiElement? = element.parent
        while (at != null && at !is CSharpFile) {
            when (at) {
                is CSharpAnonymousFunctionExpression, is CSharpLocalFunctionStatement, is CSharpMethodDeclaration -> return at
                is CSharpMemberDeclaration, is CSharpAccessorDeclaration -> return null
            }
            at = at.parent
        }
        return null
    }

    // ---- nullable: CS8618 of the implicit constructor (the rest: CSharpNullableFlow)

    /** The source generators of the project are known and fresh: a partial type is complete, its parts all seen. */
    var generatorsKnown: Boolean = false

    private fun warningsOn(at: PsiElement): Boolean = CSharpWarningContext.nullableAt(file, at.textRange.startOffset).warnings

    /**
     * [type] written in [file] is a reference type that is not nullable: annotations are on where it is written, it has no `?`, and it is a
     * class, interface, delegate or array the resolver knows — not a type parameter, not `dynamic`.
     */
    private fun nonNullableReference(type: CSharpType?): Boolean {
        type ?: return false
        if (type is CSharpNullableType || type is CSharpRefType || resolver.isVar(type)) return false
        val containing = type.containingFile ?: return false
        if (!CSharpWarningContext.nullableAt(containing, type.textRange.startOffset).annotations) return false
        if (type.text == "dynamic") return false
        return when (val resolved = resolver.resolveType(type)) {
            null, is SemanticType.Parameter -> false
            is SemanticType.ArrayOf -> true
            is SemanticType.Source -> resolved.info.kind in REFERENCE_KINDS
            is SemanticType.Library -> resolved.type.kind == IndexedTypeKind.CLASS || resolved.type.kind == IndexedTypeKind.INTERFACE || resolved.type.kind == IndexedTypeKind.DELEGATE
        }
    }

    /**
     * A class without constructors: each instance or static field and auto-property of a non-nullable reference type without an initializer
     * is null when the implicit constructor ends (CS8618 on its name). Not `required`, `abstract`, with attributes or of a record with
     * parameters; of a partial type only when its parts are all known ([generatorsKnown]: another part may have a constructor), each part
     * reporting its own members.
     */
    fun checkUninitialized(type: CSharpTypeDeclaration) {
        if (type.elementType != SyntaxKind.ClassDeclaration && type.elementType != SyntaxKind.RecordDeclaration) return
        val parts: List<CSharpTypeDeclaration> = if (type.modifiers.any { it.text == "partial" }) {
            if (!generatorsKnown) return
            val info = resolver.syntax.declaredType(type) ?: return
            info.parts.map { it.element() as? CSharpTypeDeclaration ?: return }
        } else listOf(type)
        if (parts.any { it.parameterList != null }) return
        val constructors = parts.flatMap { part -> part.members.filterIsInstance<CSharpConstructorDeclaration>() }
        if (constructors.any { it.modifiers.none { m -> m.text == "static" } }) return
        val hasStaticConstructor = constructors.isNotEmpty()
        for (member in type.members) {
            if (member.attributeLists.isNotEmpty() || member.modifiers.any { it.text == "required" || it.text == "const" || it.text == "abstract" || it.text == "extern" }) continue
            val static = member.modifiers.any { it.text == "static" }
            if (static && hasStaticConstructor) continue
            when (member) {
                is CSharpBaseFieldDeclaration -> {
                    if (member is CSharpFieldDeclaration && member.modifiers.any { it.text == "fixed" }) continue
                    val declaration = member.declaration ?: continue
                    if (!nonNullableReference(declaration.type)) continue
                    for (variable in declaration.variables) {
                        if (variable.initializer != null || variable.argumentList != null) continue
                        val name = variable.identifier ?: continue
                        if (!warningsOn(name)) continue
                        val kind = if (member is CSharpEventFieldDeclaration) "event" else "field"
                        warn("CS8618", "Non-nullable $kind '${name.text}' must contain a non-null value when exiting constructor. Consider adding the 'required' modifier or declaring the $kind as nullable.", name.textRange)
                    }
                }
                is CSharpPropertyDeclaration -> {
                    if (member.initializer != null || member.expressionBody != null || member.explicitInterfaceSpecifier != null) continue
                    val accessors = member.accessorList?.accessors ?: continue
                    if (accessors.isEmpty() || accessors.any { it.body != null || it.expressionBody != null }) continue
                    if (!nonNullableReference(member.type)) continue
                    val name = member.identifier ?: continue
                    if (!warningsOn(name)) continue
                    warn("CS8618", "Non-nullable property '${name.text}' must contain a non-null value when exiting constructor. Consider adding the 'required' modifier or declaring the property as nullable.", name.textRange)
                }
            }
        }
    }

    // ----

    private fun isQuiet(element: PsiElement): Boolean = generateSequence(element.parent) { it.parent }.takeWhile { it !is CSharpFile }.any { it is CSharpIncompleteMember }

    companion object {
        private val ARITHMETIC = setOf("+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", "&&", "||", "==", "!=", "<", ">", "<=", ">=")
        private val REFERENCE_KINDS = setOf(TypeKind.CLASS, TypeKind.STATIC_CLASS, TypeKind.RECORD, TypeKind.INTERFACE, TypeKind.DELEGATE)
        private val TASKS = setOf("System.Threading.Tasks.Task", "System.Threading.Tasks.Task`1", "System.Threading.Tasks.ValueTask", "System.Threading.Tasks.ValueTask`1")
    }
}
