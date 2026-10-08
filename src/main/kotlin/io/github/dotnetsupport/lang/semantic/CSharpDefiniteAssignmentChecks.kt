package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.LocalSymbol
import io.github.dotnetsupport.lang.TypeInfo
import io.github.dotnetsupport.lang.TypeKind
import java.util.BitSet
import java.util.IdentityHashMap

/**
 * Definite assignment (C# §9.4) of the locals and `out` parameters of a function: CS0165 (a local read before it is surely assigned),
 * CS0269 (the same of an `out` parameter), CS0177 (an `out` parameter not assigned when control leaves the method, on the `return` or on
 * the method's name for the end of its body), as Roslyn — one CS0165 / CS0269 per variable, no error in unreachable code.
 *
 * The analysis only ever adds assignments, so a state is the set of variables surely assigned (or "unreachable", which has all of them):
 * branches meet by intersection, loops need no fixpoint (a back edge only brings more), `try` leaves catch and finally blocks with the
 * state of its start, and a jump out of a `try` gets what its `finally` assigns. Conditions carry two states (`&&`, `||`, `!`, `?:`,
 * `true` / `false`, `is T t`).
 *
 * Precision first: a function is silent as a whole where its flow is not modeled — `goto`, a local function that touches a tracked
 * variable, a query, `&x`, `ref` expressions, a switch that may be exhaustive without `default`, a condition that may be a constant,
 * a `?.` / `??` / `when` that would assign something. Only variables whose type surely has state are tracked: reference types,
 * primitives, enums, `Nullable<T>`, a type name that is surely nowhere (Roslyn goes on with an error type, tracked as a reference), and
 * structs of the solution field by field (0.1.144, C# §9.4.1): `s.f = 1` assigns the field, reading `s.f` before that is CS0170, reading
 * `s` needs every field, auto-properties are hidden fields only the whole assigns; `s.f.g = 1` (a field of a field) is not modeled and
 * leaves the function silent. A local read in its own initializer (`Del d = delegate { d(); }`) is CS0165 too.
 */
internal class CSharpDefiniteAssignmentChecks(
    private val resolver: CSharpNameResolver,
    private val quiet: (PsiElement) -> Boolean,
    private val unresolved: (CSharpType) -> Boolean,
    private val report: (code: String, message: String, range: TextRange) -> Unit,
) {
    private val reachability = CSharpReachability(resolver)

    fun run(unit: CSharpCompilationUnit) {
        val roots = PsiTreeUtil.findChildrenOfAnyType(unit, CSharpBaseMethodDeclaration::class.java, CSharpAccessorDeclaration::class.java, CSharpLocalFunctionStatement::class.java)
        for (root in roots) {
            ProgressManager.checkCanceled()
            if (!quiet(root)) Function(root).run()
        }
    }

    private class Silent : RuntimeException(null, null, false, false)

    /** Surely assigned variables (bits of [Function.tracked]); [dead]: the point is unreachable, every variable counts as assigned. */
    private class St(val bits: BitSet, val dead: Boolean) {
        fun has(i: Int): Boolean = dead || bits[i]
        fun with(i: Int): St = if (dead || bits[i]) this else St((bits.clone() as BitSet).also { it.set(i) }, false)
        /** Whether this state has an assignment [other] has not. */
        fun beyond(other: St): Boolean = !dead && !other.dead && (bits.clone() as BitSet).also { it.andNot(other.bits) }.isEmpty.not()
    }

    private fun join(a: St, b: St): St = when {
        a.dead -> b
        b.dead -> a
        else -> St((a.bits.clone() as BitSet).also { it.and(b.bits) }, false)
    }

    private fun union(a: St, b: St): St = if (a.dead || b.dead) DEAD else St((a.bits.clone() as BitSet).also { it.or(b.bits) }, false)

    private enum class JumpKind { BREAK, CONTINUE, RETURN }
    private class Jump(val kind: JumpKind, val state: St, val site: PsiElement)

    private sealed class Frame
    private class Breakable(val loop: Boolean) : Frame() {
        var breaks: St = DEAD
        var continues: St = DEAD
    }
    private class Finally : Frame() {
        val deferred = ArrayList<Jump>()
    }
    /** A function or lambda: the `return`s that leave it ([outs]: only of the analyzed function, a lambda checks nothing). */
    private class Exit(val outs: Boolean) : Frame() {
        val returns = ArrayList<Jump>()
    }

    /**
     * A tracked bit: a variable, or ([field] set) one field of a struct variable. [errorType]: the type is surely nowhere — Roslyn reports
     * CS0177 on it, but no read of it (its reads are already wrong).
     */
    private class Variable(val symbol: LocalSymbol, val out: Boolean, val field: String? = null, val errorType: Boolean = false)
    /** A struct variable: the bit of each field by name (hidden ones under [HIDDEN]), [all] of them, and [leaves]: the fields with no fields of their own. */
    private class StructVar(val symbol: LocalSymbol, val out: Boolean, val fields: Map<String, Int>, val all: IntArray, val leaves: Set<Int>)
    /** A field of a struct: [leaf] when its type has no fields to assign one by one (a reference, a primitive, an enum, `Nullable<T>`). */
    private class Field(val name: String, val leaf: Boolean)
    /** `s.f` found: the struct, the field's bit, the access of `s.f` itself, and whether the chain goes on past it. */
    private class Hit(val struct: StructVar, val bit: Int, val access: CSharpMemberAccessExpression, val deep: Boolean)

    private inner class Function(private val root: PsiElement) {
        private val tracked = IdentityHashMap<LocalSymbol, Int>()
        private val structs = IdentityHashMap<LocalSymbol, StructVar>()
        private val variables = ArrayList<Variable>()
        private val reported = BitSet()
        private val reportedStructs = HashSet<LocalSymbol>()
        private val found = ArrayList<Triple<String, String, TextRange>>()
        private val frames = ArrayList<Frame>()
        private var steps = 0

        fun run() {
            val body: PsiElement? = when (root) {
                is CSharpBaseMethodDeclaration -> root.body ?: root.expressionBody
                is CSharpAccessorDeclaration -> root.body ?: root.expressionBody
                is CSharpLocalFunctionStatement -> root.body ?: root.expressionBody
                else -> null
            }
            body ?: return
            val parameters = when (root) {
                is CSharpBaseMethodDeclaration -> root.parameterList?.parameters.orEmpty()
                is CSharpLocalFunctionStatement -> root.parameterList?.parameters.orEmpty()
                else -> emptyList()
            }
            for (p in parameters) if (p.modifiers.any { it.text == "out" }) p.identifier?.let { leaf -> trackAny(leaf, p.type, out = true) }
            collect(body)
            if (variables.isEmpty()) return
            if (PsiTreeUtil.findChildrenOfAnyType(body, CSharpGotoStatement::class.java, CSharpLabeledStatement::class.java).isNotEmpty()) return
            for (local in PsiTreeUtil.findChildrenOfType(body, CSharpLocalFunctionStatement::class.java)) {
                if (touches(local)) return
            }
            try {
                val exit = Exit(outs = true)
                frames += exit
                var s = St(BitSet(), false)
                if (root is CSharpConstructorDeclaration) root.initializer?.argumentList?.let { s = arguments(it, s) }
                val end = when (body) {
                    is CSharpBlock -> statement(body, s)
                    is CSharpArrowExpressionClause -> expression(body.expression, s)
                    else -> throw Silent()
                }
                frames.removeAt(frames.size - 1)
                val name = when (root) {
                    is CSharpMethodDeclaration -> root.identifier
                    is CSharpConstructorDeclaration -> root.identifier
                    is CSharpLocalFunctionStatement -> root.identifier
                    else -> null
                }
                if (name != null) {
                    leave(end, name.textRange)
                    for (jump in exit.returns) leave(jump.state, jump.site.textRange)
                }
            } catch (_: Silent) {
                return
            }
            for ((code, message, range) in found) report(code, message, range)
        }

        /** CS0177 for each `out` parameter not assigned where control leaves the function (a struct: any of its fields). */
        private fun leave(state: St, at: TextRange) {
            if (state.dead) return
            variables.forEachIndexed { i, v ->
                if (v.out && v.field == null && !state.has(i)) found += Triple("CS0177", "The out parameter '${v.symbol.name}' must be assigned to before control leaves the current method", at)
            }
            for (sv in structs.values) {
                if (sv.out && sv.all.any { !state.has(it) }) found += Triple("CS0177", "The out parameter '${sv.symbol.name}' must be assigned to before control leaves the current method", at)
            }
        }

        /** Whether [element] mentions a tracked variable. */
        private fun touches(element: PsiElement): Boolean =
            PsiTreeUtil.collectElements(element) { e -> resolver.syntax.symbolAt(e)?.let { it in tracked || it in structs } == true }.isNotEmpty()

        private fun track(leaf: PsiElement, out: Boolean, errorType: Boolean = false) {
            val symbol = resolver.syntax.symbolAt(leaf) ?: return
            if (symbol.declaration != leaf || symbol in tracked || symbol in structs) return
            tracked[symbol] = variables.size
            variables += Variable(symbol, out, errorType = errorType)
        }

        private fun trackStruct(leaf: PsiElement, out: Boolean, fields: List<Field>) {
            val symbol = resolver.syntax.symbolAt(leaf) ?: return
            if (symbol.declaration != leaf || symbol in tracked || symbol in structs) return
            val bits = LinkedHashMap<String, Int>()
            val leaves = HashSet<Int>()
            for (field in fields) {
                bits[field.name] = variables.size
                if (field.leaf) leaves += variables.size
                variables += Variable(symbol, out, field.name)
            }
            structs[symbol] = StructVar(symbol, out, bits, bits.values.toIntArray(), leaves)
        }

        /** Tracks [leaf] of the declared [type] (null for `var`) as a plain variable, as a struct field by field, or not at all. */
        private fun trackAny(leaf: PsiElement, type: CSharpType?, out: Boolean) {
            val semantic = type?.let(resolver::resolveType)
            when {
                eligible(semantic) -> track(leaf, out)
                semantic == null -> if (type != null && unresolved(type)) track(leaf, out, errorType = true)
                semantic is SemanticType.Source && semantic.arguments.isEmpty() -> structFields(semantic.info)?.takeIf { it.isNotEmpty() }?.let { trackStruct(leaf, out, it) }
            }
        }

        /** The variables that start unassigned: locals declared without a value, `out var x` / `out T x`, `is T x`; not inside local functions. */
        private fun collect(element: PsiElement) {
            var child = element.firstChild
            while (child != null) {
                when (child) {
                    is CSharpLocalFunctionStatement -> {}
                    is CSharpLocalDeclarationStatement -> {
                        val declaration = child.declaration
                        val type = declaration?.type
                        if (declaration != null && type != null && child.modifiers.none { it.text == "const" }) {
                            val declared = if (resolver.isVar(type)) null else type
                            for (v in declaration.variables) {
                                val leaf = v.identifier ?: continue
                                // `Color Color;`: `Color.Red` may be the type, not a read of the local
                                if (leaf.text == typeName(type)) continue
                                if (v.initializer == null && v.argumentList == null) trackAny(leaf, declared, out = false)
                                else if (eligible(declared?.let(resolver::resolveType)) && resolver.syntax.symbolAt(leaf)?.references?.any { PsiTreeUtil.isAncestor(v, it, false) } == true) track(leaf, out = false)
                            }
                        }
                        collect(child)
                    }
                    is CSharpArgument -> {
                        val declared = child.expression as? CSharpDeclarationExpression
                        val designation = declared?.designation as? CSharpSingleVariableDesignation
                        val leaf = designation?.identifier
                        if (child.refKindKeyword?.text == "out" && declared != null && leaf != null) {
                            val type = declared.type
                            val semantic = if (type == null || resolver.isVar(type)) resolver.syntax.symbolAt(leaf)?.let { resolver.localType(CSharpSymbol.Local(it)) } else resolver.resolveType(type)
                            if (eligible(semantic) && (type == null || leaf.text != typeName(type))) track(leaf, out = false)
                        }
                        collect(child)
                    }
                    is CSharpIsPatternExpression -> {
                        patternVariable(child)?.let { (leaf, _) -> track(leaf, out = false) }
                        collect(child)
                    }
                    else -> collect(child)
                }
                child = child.nextSibling
            }
        }

        private fun typeName(type: CSharpType): String? = when (type) {
            is CSharpSimpleName -> type.identifier?.text
            is CSharpQualifiedName -> type.right?.identifier?.text
            is CSharpNullableType -> type.elementType?.let(::typeName)
            else -> null
        }

        /**
         * `e is T x` / `e is T { … } x` / `e is not T x` on a value that may be null (a reference type or `Nullable<T>`): the variable,
         * and whether it is assigned when the test is true. Other patterns may always match (`var x`, `T x` of a value of type `T`), where
         * Roslyn knows the false branch is unreachable: their variables are not tracked.
         */
        private fun patternVariable(test: CSharpIsPatternExpression): Pair<PsiElement, Boolean>? {
            var pattern = unparen(test.pattern)
            var whenTrue = true
            if (pattern is CSharpUnaryPattern && pattern.operatorToken?.text == "not") {
                pattern = unparen(pattern.pattern)
                whenTrue = false
            }
            val (type, designation) = when (pattern) {
                is CSharpDeclarationPattern -> pattern.type to pattern.designation
                is CSharpRecursivePattern -> pattern.type to pattern.designation
                else -> return null
            }
            val leaf = (designation as? CSharpSingleVariableDesignation)?.identifier ?: return null
            if (type == null || !eligible(resolver.resolveType(type))) return null
            val input = test.expression?.let(resolver::typeOf) ?: return null
            if (!nullable(input)) return null
            return leaf to whenTrue
        }

        /**
         * Whether the test of a pattern without a tracked variable may be true and may be false, as Roslyn sees it: a constant or relational
         * pattern (`is null`, `is not null`, `is > 0`), or a type test of a value that may be null. `var x`, `_`, `or` / `and` may always match.
         */
        private fun mayFail(test: CSharpIsPatternExpression): Boolean {
            var pattern = unparen(test.pattern)
            if (pattern is CSharpUnaryPattern && pattern.operatorToken?.text == "not") pattern = unparen(pattern.pattern)
            return when (pattern) {
                is CSharpConstantPattern, is CSharpRelationalPattern -> true
                is CSharpDeclarationPattern, is CSharpTypePattern -> test.expression?.let(resolver::typeOf)?.let(::nullable) == true
                is CSharpRecursivePattern -> pattern.type != null && test.expression?.let(resolver::typeOf)?.let(::nullable) == true
                else -> false
            }
        }

        private fun unparen(pattern: CSharpPattern?): CSharpPattern? {
            var p = pattern
            while (p is CSharpParenthesizedPattern) p = p.pattern
            return p
        }

        // ---- statements

        private fun step() {
            if (++steps > MAX_STEPS) throw Silent()
        }

        private fun statement(statement: CSharpStatement?, s: St): St {
            ProgressManager.checkCanceled()
            step()
            return when (statement) {
                null -> throw Silent()
                is CSharpBlock -> statement.statements.fold(s) { state, it -> if (it is CSharpLocalFunctionStatement) state else statement(it, state) }
                is CSharpLocalFunctionStatement, is CSharpEmptyStatement -> s
                is CSharpExpressionStatement -> expression(statement.expression, s)
                is CSharpLocalDeclarationStatement -> declaration(statement.declaration, s)
                is CSharpIfStatement -> {
                    val (t, f) = condition(statement.condition, s)
                    val then = statement(statement.statement, t)
                    val otherwise = statement.`else`?.let { statement(it.statement, f) } ?: f
                    join(then, otherwise)
                }
                is CSharpWhileStatement -> {
                    val (t, f) = condition(statement.condition, s)
                    val loop = Breakable(loop = true)
                    inside(loop) { statement(statement.statement, t) }
                    join(f, loop.breaks)
                }
                is CSharpDoStatement -> {
                    val loop = Breakable(loop = true)
                    val end = inside(loop) { statement(statement.statement, s) }
                    val (_, f) = condition(statement.condition, join(end, loop.continues))
                    join(f, loop.breaks)
                }
                is CSharpForStatement -> {
                    var state = statement.declaration?.let { declaration(it, s) } ?: s
                    for (e in statement.initializers) state = expression(e, state)
                    val (t, f) = statement.condition?.let { condition(it, state) } ?: (state to DEAD)
                    val loop = Breakable(loop = true)
                    val end = inside(loop) { statement(statement.statement, t) }
                    var next = join(end, loop.continues)
                    for (e in statement.incrementors) next = expression(e, next)
                    join(f, loop.breaks)
                }
                is CSharpForEachStatement -> {
                    val state = expression(statement.expression, s)
                    val loop = Breakable(loop = true)
                    inside(loop) { statement(statement.statement, state) }
                    join(state, loop.breaks)
                }
                is CSharpForEachVariableStatement -> {
                    if (statement.variable !is CSharpDeclarationExpression) throw Silent()
                    val state = expression(statement.expression, s)
                    val loop = Breakable(loop = true)
                    inside(loop) { statement(statement.statement, state) }
                    join(state, loop.breaks)
                }
                is CSharpReturnStatement -> {
                    jump(Jump(JumpKind.RETURN, expression(statement.expression, s), statement))
                    DEAD
                }
                is CSharpYieldStatement -> {
                    if (statement.returnOrBreakKeyword?.text == "break") {
                        jump(Jump(JumpKind.RETURN, s, statement))
                        DEAD
                    } else expression(statement.expression, s)
                }
                is CSharpThrowStatement -> {
                    expression(statement.expression, s)
                    DEAD
                }
                is CSharpBreakStatement -> {
                    jump(Jump(JumpKind.BREAK, s, statement))
                    DEAD
                }
                is CSharpContinueStatement -> {
                    jump(Jump(JumpKind.CONTINUE, s, statement))
                    DEAD
                }
                is CSharpSwitchStatement -> switch(statement, s)
                is CSharpTryStatement -> tryStatement(statement, s)
                is CSharpLockStatement -> statement(statement.statement, expression(statement.expression, s))
                is CSharpUsingStatement -> {
                    val state = statement.declaration?.let { declaration(it, s) } ?: expression(statement.expression, s)
                    statement(statement.statement, state)
                }
                is CSharpFixedStatement -> statement(statement.statement, declaration(statement.declaration, s))
                is CSharpCheckedStatement -> statement(statement.block, s)
                is CSharpUnsafeStatement -> statement(statement.block, s)
                else -> throw Silent()
            }
        }

        private fun declaration(declaration: CSharpVariableDeclaration?, s: St): St {
            declaration ?: throw Silent()
            var state = s
            for (v in declaration.variables) {
                v.argumentList?.let { state = arguments(it, state) }
                v.initializer?.value?.let { state = expression(it, state) }
                // a local with a value is tracked only when its own initializer reads it: assigned once the value is there
                if (v.initializer != null || v.argumentList != null) v.identifier?.let(resolver.syntax::symbolAt)?.let { tracked[it] }?.let { state = state.with(it) }
            }
            return state
        }

        private fun <T> inside(frame: Frame, body: () -> T): T {
            frames += frame
            try {
                return body()
            } finally {
                frames.removeAt(frames.size - 1)
            }
        }

        /** Takes [jump] to its target, or to the innermost `finally` on the way, which passes it on with what it assigns. */
        private fun jump(jump: Jump) {
            for (i in frames.indices.reversed()) {
                when (val frame = frames[i]) {
                    is Finally -> {
                        frame.deferred += jump
                        return
                    }
                    is Breakable -> when {
                        jump.kind == JumpKind.BREAK -> {
                            frame.breaks = join(frame.breaks, jump.state)
                            return
                        }
                        jump.kind == JumpKind.CONTINUE && frame.loop -> {
                            frame.continues = join(frame.continues, jump.state)
                            return
                        }
                    }
                    is Exit -> {
                        if (jump.kind != JumpKind.RETURN) throw Silent()
                        if (frame.outs) frame.returns += jump
                        return
                    }
                }
            }
            throw Silent()
        }

        private fun switch(statement: CSharpSwitchStatement, s: St): St {
            val state = expression(statement.expression, s)
            val labels = statement.sections.flatMap { it.labels }
            val hasDefault = labels.any { it is CSharpDefaultSwitchLabel }
            if (!hasDefault && (labels.any { it !is CSharpCaseSwitchLabel } || !open(statement.expression, labels))) throw Silent()
            val switch = Breakable(loop = false)
            inside(switch) {
                for (section in statement.sections) {
                    var entry = DEAD
                    for (label in section.labels) {
                        val clause = (label as? CSharpCasePatternSwitchLabel)?.whenClause
                        entry = join(entry, if (clause == null) state else noAssignment(state, condition(clause.condition, state)).first)
                    }
                    val end = section.statements.fold(entry) { st, it -> if (it is CSharpLocalFunctionStatement) st else statement(it, st) }
                    // falling out of a section is an error of its own: as a break
                    switch.breaks = join(switch.breaks, end)
                }
            }
            return if (hasDefault) switch.breaks else join(state, switch.breaks)
        }

        /**
         * A governing value with more values than any list of constants: a number, `char`, `string`, an enum (not `bool`) — by its type, or
         * by a label that is a string, number or character literal.
         */
        private fun open(expression: CSharpExpression?, labels: List<CSharpSwitchLabel>): Boolean {
            val literal = labels.any { (it as? CSharpCaseSwitchLabel)?.value?.elementType.let { k -> k == SyntaxKind.StringLiteralExpression || k == SyntaxKind.NumericLiteralExpression || k == SyntaxKind.CharacterLiteralExpression } }
            return literal || when (val type = expression?.let(resolver::typeOf)) {
                is SemanticType.Library -> type.type.kind == IndexedTypeKind.ENUM || type.type.fullName in OPEN_TYPES
                is SemanticType.Source -> type.info.kind == TypeKind.ENUM
                else -> false
            }
        }

        private fun tryStatement(statement: CSharpTryStatement, s: St): St {
            val finallyBlock = statement.finally
            val frame = Finally()
            val ends = ArrayList<St>()
            val body = {
                ends += statement(statement.block, s)
                for (clause in statement.catches) {
                    var entry = s
                    clause.filter?.let { entry = noAssignment(s, condition(it.filterExpression, s)).first }
                    ends += statement(clause.block, entry)
                }
            }
            if (finallyBlock == null) body() else inside(frame, body)
            val end = ends.reduce(::join)
            if (finallyBlock == null) return end
            val fin = statement(finallyBlock.block, s)
            for (jump in frame.deferred) jump(Jump(jump.kind, union(jump.state, fin), jump.site))
            return union(end, fin)
        }

        /** A `when` that assigns: the next label is reached through its false branch, which Roslyn may know more of. */
        private fun noAssignment(before: St, branches: Pair<St, St>): Pair<St, St> {
            if (branches.first.beyond(before) || branches.second.beyond(before)) throw Silent()
            return branches
        }

        // ---- expressions

        /** The states where [e] is true and where it is false. */
        private fun condition(e: CSharpExpression?, s: St): Pair<St, St> {
            when (e) {
                null -> throw Silent()
                is CSharpParenthesizedExpression -> return condition(e.expression, s)
                is CSharpLiteralExpression -> when (e.elementType) {
                    SyntaxKind.TrueLiteralExpression -> return s to DEAD
                    SyntaxKind.FalseLiteralExpression -> return DEAD to s
                }
                is CSharpPrefixUnaryExpression -> if (e.operatorToken?.text == "!") return condition(e.operand, s).let { it.second to it.first }
                is CSharpBinaryExpression -> when (e.operatorToken?.text) {
                    "&&" -> {
                        val (lt, lf) = condition(e.left, s)
                        val (rt, rf) = condition(e.right, lt)
                        return rt to join(lf, rf)
                    }
                    "||" -> {
                        val (lt, lf) = condition(e.left, s)
                        val (rt, rf) = condition(e.right, lf)
                        return join(lt, rt) to rf
                    }
                }
                is CSharpConditionalExpression -> {
                    val (ct, cf) = condition(e.condition, s)
                    val (at, af) = condition(e.whenTrue, ct)
                    val (bt, bf) = condition(e.whenFalse, cf)
                    return join(at, bt) to join(af, bf)
                }
                is CSharpIsPatternExpression -> {
                    val state = expression(e.expression, s)
                    val (leaf, whenTrue) = patternVariable(e) ?: return if (mayFail(e)) state to state else state to DEAD // may always match: no verdict where it fails
                    val assigned = tracked[resolver.syntax.symbolAt(leaf)]?.let(state::with) ?: state
                    return if (whenTrue) assigned to state else state to assigned
                }
            }
            if (e is CSharpBinaryExpression && (e.operatorToken?.text == "==" || e.operatorToken?.text == "!=")) {
                // `M(out x) == true`: as the operand (C# 10 keeps its branches; when it does not, this only says less)
                val literal = listOf(e.left, e.right).firstOrNull { it?.elementType == SyntaxKind.TrueLiteralExpression || it?.elementType == SyntaxKind.FalseLiteralExpression }
                if (literal != null) {
                    val (t, f) = condition(if (literal == e.left) e.right else e.left, s)
                    val same = (literal.elementType == SyntaxKind.TrueLiteralExpression) == (e.operatorToken?.text == "==")
                    return if (same) t to f else f to t
                }
            }
            val state = expression(e, s)
            // a constant Roslyn folds (`const bool`, `1 > 0`) makes a branch unreachable
            if (reachability.constant(e) == CSharpReachability.Constant.UNKNOWN) throw Silent()
            return state to state
        }

        private fun expression(e: CSharpExpression?, s: St): St {
            e ?: return s
            step()
            return when (e) {
                is CSharpParenthesizedExpression -> expression(e.expression, s)
                is CSharpIdentifierName -> read(e, s)
                is CSharpLiteralExpression, is CSharpInstanceExpression, is CSharpPredefinedType, is CSharpGenericName -> s
                is CSharpBinaryExpression -> when (e.operatorToken?.text) {
                    "&&", "||" -> condition(e, s).let { join(it.first, it.second) }
                    "is", "as" -> expression(e.left, s)
                    "??" -> {
                        val left = expression(e.left, s)
                        if (expression(e.right, left).beyond(left)) throw Silent()
                        left
                    }
                    else -> expression(e.right, expression(e.left, s))
                }
                is CSharpPrefixUnaryExpression -> when (e.operatorToken?.text) {
                    "!" -> condition(e, s).let { join(it.first, it.second) }
                    "&" -> throw Silent()
                    "++", "--" -> increment(e.operand, s)
                    else -> expression(e.operand, s)
                }
                is CSharpPostfixUnaryExpression -> when (e.operatorToken?.text) {
                    "++", "--" -> increment(e.operand, s)
                    else -> expression(e.operand, s)
                }
                is CSharpConditionalExpression -> {
                    val (t, f) = condition(e.condition, s)
                    join(expression(e.whenTrue, t), expression(e.whenFalse, f))
                }
                is CSharpIsPatternExpression -> condition(e, s).let { join(it.first, it.second) }
                is CSharpAssignmentExpression -> assignment(e, s)
                is CSharpInvocationExpression -> {
                    val callee = e.expression
                    if (callee is CSharpIdentifierName && callee.identifier?.text == "nameof" && resolver.syntax.symbolAt(callee.identifier!!) == null) return s
                    arguments(e.argumentList, expression(callee, s))
                }
                is CSharpMemberAccessExpression -> fieldAccess(e)?.let { readField(it, s) } ?: expression(e.expression, s)
                is CSharpConditionalAccessExpression -> {
                    val receiver = expression(e.expression, s)
                    if (expression(e.whenNotNull, receiver).beyond(receiver)) throw Silent()
                    receiver
                }
                is CSharpMemberBindingExpression -> s
                is CSharpElementBindingExpression -> arguments(e.argumentList, s)
                is CSharpImplicitElementAccess -> arguments(e.argumentList, s)
                is CSharpElementAccessExpression -> arguments(e.argumentList, expression(e.expression, s))
                is CSharpBaseObjectCreationExpression -> {
                    var state = (e as? CSharpObjectCreationExpression)?.type?.let { children(it, s) } ?: s
                    e.argumentList?.let { state = arguments(it, state) }
                    initializer(e.initializer, state)
                }
                is CSharpWithExpression -> initializer(e.initializer, expression(e.expression, s))
                is CSharpInitializerExpression -> initializer(e, s)
                is CSharpAnonymousObjectCreationExpression -> e.initializers.fold(s) { state, it -> expression(it.expression, state) }
                is CSharpCastExpression -> expression(e.expression, s)
                is CSharpAnonymousFunctionExpression -> {
                    inside(Exit(outs = false)) { e.block?.let { statement(it, s) } ?: expression(e.expressionBody, s) }
                    s
                }
                is CSharpSwitchExpression -> {
                    val state = expression(e.governingExpression, s)
                    var result = DEAD
                    for (arm in e.arms) {
                        val entry = arm.whenClause?.let { noAssignment(state, condition(it.condition, state)).first } ?: state
                        result = join(result, expression(arm.expression, entry))
                    }
                    if (e.arms.isEmpty()) state else result
                }
                is CSharpThrowExpression -> {
                    expression(e.expression, s)
                    DEAD
                }
                is CSharpDeclarationExpression -> s
                is CSharpQueryExpression -> {
                    // the first source is evaluated here; the rest are lambdas of the query's methods, not modeled
                    val state = expression(e.fromClause?.expression, s)
                    if (listOfNotNull(e.fromClause?.type, e.body).any(::touches)) throw Silent()
                    state
                }
                is CSharpRefExpression, is CSharpMakeRefExpression -> throw Silent()
                is CSharpTupleExpression -> e.arguments.fold(s) { state, it -> if (it.refKindKeyword != null) throw Silent() else expression(it.expression, state) }
                else -> children(e, s)
            }
        }

        /** The parts of an expression evaluated in their order (an interpolated string, `await`, an array creation, a collection expression…). */
        private fun children(element: PsiElement, s: St): St {
            var state = s
            var child = element.firstChild
            while (child != null) {
                when (child) {
                    is CSharpNameColon, is CSharpNameEquals, is CSharpPattern, is CSharpVariableDesignation -> {}
                    is CSharpArgument -> throw Silent()
                    is CSharpExpression -> state = expression(child, state)
                    is CSharpElement -> state = children(child, state)
                }
                child = child.nextSibling
            }
            return state
        }

        private fun read(name: CSharpIdentifierName, s: St): St {
            val leaf = name.identifier ?: return s
            val symbol = resolver.syntax.symbolAt(leaf) ?: return s
            structs[symbol]?.let { sv ->
                if (sv.all.all(s::has) || !reportedStructs.add(symbol)) return s
                found += if (sv.out) Triple("CS0269", "Use of unassigned out parameter '${sv.symbol.name}'", leaf.textRange)
                else Triple("CS0165", "Use of unassigned local variable '${sv.symbol.name}'", leaf.textRange)
                return s
            }
            val i = tracked[symbol] ?: return s
            if (s.has(i) || reported[i] || variables[i].errorType) return s
            reported.set(i)
            val v = variables[i]
            found += if (v.out) Triple("CS0269", "Use of unassigned out parameter '${v.symbol.name}'", leaf.textRange)
            else Triple("CS0165", "Use of unassigned local variable '${v.symbol.name}'", leaf.textRange)
            return s
        }

        /**
         * `s.f` or `s.f.g…` where `s` is a struct variable and `f` one of its fields: the struct, the field's bit, whether the chain goes on;
         * null for a property or method of `s`, or anything else.
         */
        private fun fieldAccess(e: CSharpMemberAccessExpression): Hit? {
            var access = e
            var deep = false
            while (true) {
                var receiver = access.expression
                while (receiver is CSharpParenthesizedExpression) receiver = receiver.expression
                when (receiver) {
                    is CSharpMemberAccessExpression -> { access = receiver; deep = true }
                    is CSharpIdentifierName -> {
                        val sv = receiver.identifier?.let(resolver.syntax::symbolAt)?.let(structs::get) ?: return null
                        val i = (access.nameElement as? CSharpIdentifierName)?.identifier?.text?.let(sv.fields::get) ?: return null
                        return Hit(sv, i, access, deep)
                    }
                    else -> return null
                }
            }
        }

        /** CS0170 on `s.f` (what follows it is not part of the mark), once per field, as Roslyn. */
        private fun readField(hit: Hit, s: St): St {
            val i = hit.bit
            if (s.has(i) || reported[i]) return s
            reported.set(i)
            found += Triple("CS0170", "Use of possibly unassigned field '${variables[i].field}'", hit.access.textRange)
            return s
        }

        /**
         * The bits an assignment to [e] sets: a variable, every field of a struct variable, one field of it (`s.f`); null when [e] is
         * none of those — `s.f.g = …` through a field with no fields of its own (a class) is a read of `s.f`; through a struct field it
         * is not modeled: silent.
         */
        private fun targets(e: CSharpExpression?): IntArray? {
            var x = e
            while (x is CSharpParenthesizedExpression) x = x.expression
            if (x is CSharpMemberAccessExpression) {
                val hit = fieldAccess(x) ?: return null
                if (!hit.deep) return intArrayOf(hit.bit)
                if (hit.bit in hit.struct.leaves) return null
                throw Silent()
            }
            val leaf = (x as? CSharpIdentifierName)?.identifier ?: (x as? CSharpDeclarationExpression)?.let { (it.designation as? CSharpSingleVariableDesignation)?.identifier } ?: return null
            val symbol = resolver.syntax.symbolAt(leaf) ?: return null
            structs[symbol]?.let { return it.all }
            return tracked[symbol]?.let { intArrayOf(it) }
        }

        private fun withAll(s: St, bits: IntArray): St = bits.fold(s) { st, i -> st.with(i) }

        private fun isName(e: CSharpExpression?): Boolean {
            var x = e
            while (x is CSharpParenthesizedExpression) x = x.expression
            return x is CSharpIdentifierName || x is CSharpDeclarationExpression
        }

        private fun increment(operand: CSharpExpression?, s: St): St {
            val state = expression(operand, s)
            return targets(operand)?.let { withAll(state, it) } ?: state
        }

        private fun assignment(e: CSharpAssignmentExpression, s: St): St {
            val left = e.left
            val operator = e.operatorToken?.text
            if (operator == "=") {
                if (left is CSharpTupleExpression) {
                    val targets = ArrayList<IntArray>()
                    val state = deconstructionTargets(left, s, targets)
                    return targets.fold(expression(e.right, state), ::withAll)
                }
                targets(left)?.let { return withAll(expression(e.right, s), it) }
                if (isName(left)) return expression(e.right, s)
                return expression(e.right, expression(left, s))
            }
            val state = expression(left, s)
            val right = expression(e.right, state)
            if (operator == "??=" && right.beyond(state)) throw Silent()
            val result = if (operator == "??=") state else right
            return targets(left)?.let { withAll(result, it) } ?: result
        }

        /** `(a, b) = …`: the variables it assigns, after the receivers of the other targets. */
        private fun deconstructionTargets(tuple: CSharpTupleExpression, s: St, into: MutableList<IntArray>): St {
            var state = s
            for (argument in tuple.arguments) {
                val e = argument.expression
                if (e is CSharpTupleExpression) { state = deconstructionTargets(e, state, into); continue }
                val bits = targets(e)
                if (bits != null) into += bits else if (!isName(e)) state = expression(e, state)
            }
            return state
        }

        /** The arguments in order; `out` ones are assigned when the call returns, after all are evaluated (`M(out x, x)` reads `x` first). */
        private fun arguments(list: CSharpBaseArgumentList?, s: St): St {
            list ?: return s
            var state = s
            val outs = ArrayList<IntArray>()
            for (argument in list.arguments) {
                val e = argument.expression
                if (argument.refKindKeyword?.text == "out") {
                    val bits = targets(e)
                    if (bits != null) outs += bits else if (!isName(e)) state = expression(e, state)
                } else {
                    state = expression(e, state)
                }
            }
            return outs.fold(state, ::withAll)
        }

        /** `{ P = v, [i] = v, { a, b }, x }`: the names of members are not reads. */
        private fun initializer(initializer: CSharpInitializerExpression?, s: St): St {
            initializer ?: return s
            val members = initializer.elementType == SyntaxKind.ObjectInitializerExpression || initializer.elementType == SyntaxKind.WithInitializerExpression
            var state = s
            for (e in initializer.expressions) {
                state = if (members && e is CSharpAssignmentExpression && e.operatorToken?.text == "=" && (e.left is CSharpIdentifierName || e.left is CSharpImplicitElementAccess)) {
                    (e.left as? CSharpImplicitElementAccess)?.let { state = arguments(it.argumentList, state) }
                    expression(e.right, state)
                } else expression(e, state)
            }
            return state
        }
    }

    /**
     * The fields definite assignment follows in a struct of the solution: instance fields by name; auto-properties and field-like events as
     * hidden fields only the whole assigns. Null where the struct is not modeled: a part not seen, a positional record struct, a `fixed`
     * or `ref` field.
     */
    private fun structFields(info: TypeInfo): List<Field>? {
        if (info.kind != TypeKind.STRUCT && info.kind != TypeKind.RECORD_STRUCT) return null
        val fields = ArrayList<Field>()
        for (part in info.parts) {
            val declaration = part.element() as? CSharpTypeDeclaration ?: return null
            if (declaration is CSharpRecordDeclaration && declaration.parameterList != null) return null
            val owner = (declaration.containingFile as? CSharpFile)?.let(resolver.session::reachable) ?: return null
            for (member in declaration.members) {
                val modifiers = member.modifiers.map { it.text }
                if ("static" in modifiers || "const" in modifiers) continue
                when (member) {
                    is CSharpFieldDeclaration -> {
                        val type = member.declaration?.type ?: return null
                        if ("fixed" in modifiers || "ref" in modifiers || type is CSharpRefType) return null
                        val leaf = eligible(owner.resolveType(type))
                        for (v in member.declaration?.variables.orEmpty()) fields += Field(v.identifier?.text ?: return null, leaf)
                    }
                    is CSharpPropertyDeclaration -> {
                        val accessors = member.accessorList?.accessors ?: continue
                        if (accessors.isNotEmpty() && accessors.all { it.body == null && it.expressionBody == null }) fields += Field(HIDDEN + (member.identifier?.text ?: return null), false)
                    }
                    is CSharpEventFieldDeclaration -> for (v in member.declaration?.variables.orEmpty()) fields += Field(HIDDEN + (v.identifier?.text ?: return null), false)
                    else -> {}
                }
            }
        }
        return fields
    }

    /** A type whose variables have a state to assign: a reference type, a primitive, an enum, `Nullable<T>`. */
    private fun eligible(type: SemanticType?): Boolean = when (type) {
        is SemanticType.ArrayOf -> true
        is SemanticType.Library -> when (type.type.kind) {
            IndexedTypeKind.CLASS, IndexedTypeKind.STATIC_CLASS, IndexedTypeKind.INTERFACE, IndexedTypeKind.DELEGATE, IndexedTypeKind.ENUM -> true
            IndexedTypeKind.STRUCT -> type.fullName in PRIMITIVES || type.fullName == NULLABLE
        }
        is SemanticType.Source -> when (type.info.kind) {
            TypeKind.CLASS, TypeKind.RECORD, TypeKind.INTERFACE, TypeKind.ENUM, TypeKind.DELEGATE -> true
            else -> false
        }
        else -> false
    }

    /** A value an `is` test may fail on whatever the pattern: a reference type or `Nullable<T>`. */
    private fun nullable(type: SemanticType): Boolean = when (type) {
        is SemanticType.ArrayOf -> true
        is SemanticType.Library -> when (type.type.kind) {
            IndexedTypeKind.CLASS, IndexedTypeKind.INTERFACE, IndexedTypeKind.DELEGATE -> true
            IndexedTypeKind.STRUCT -> type.fullName == NULLABLE
            else -> false
        }
        is SemanticType.Source -> type.info.kind == TypeKind.CLASS || type.info.kind == TypeKind.RECORD || type.info.kind == TypeKind.INTERFACE
        else -> false
    }

    private companion object {
        /** Visits of statements and expressions per function: past it the function is left silent (every node is visited about once). */
        const val MAX_STEPS = 50_000
        private val DEAD = St(BitSet(), true)
        const val NULLABLE = "System.Nullable`1"
        /** The prefix of a hidden field's name: no C# name starts with it. */
        const val HIDDEN = "~"
        val PRIMITIVES = setOf("System.Boolean", "System.Char", "System.SByte", "System.Byte", "System.Int16", "System.UInt16", "System.Int32", "System.UInt32",
            "System.Int64", "System.UInt64", "System.Single", "System.Double", "System.Decimal")
        val OPEN_TYPES = setOf("System.Int32", "System.Int64", "System.String", "System.Char", "System.Int16", "System.UInt32", "System.UInt64", "System.Byte", "System.SByte", "System.UInt16")
    }
}
